from fastapi import FastAPI, File, Form, UploadFile
from fastapi.staticfiles import StaticFiles
from ultralytics import YOLO
import cv2, os, json, mysql.connector, uuid, time
from datetime import datetime
import paho.mqtt.client as mqtt
import numpy as np

# ===== FOLDER PENYIMPANAN GAMBAR =====
UPLOAD_FOLDER = "Image"
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

# ===== LOAD MODEL YOLOv8 =====
MODEL_PATH = "model/best.pt"
model = YOLO(MODEL_PATH)

# ===== KONEKSI DATABASE MYSQL =====
db_conn = mysql.connector.connect(
    host="localhost",
    user="root",
    password="",
    database="melvis"
)
cursor = db_conn.cursor()

# ===== KONFIGURASI MQTT BROKER =====
MQTT_BROKER = "test.mosquitto.org"      # bisa diganti broker lokal
MQTT_PORT = 1883
MQTT_TOPIC_DETEKSI = "cam/deteksi"   # topic untuk Android

mqtt_client = mqtt.Client()
mqtt_client.connect(MQTT_BROKER, MQTT_PORT, 60)

def enhance_image_quality(image_path):
    img = cv2.imread(image_path)

    if img is None:
        return image_path

    # =======================
    # 1. Brightness ringan (TIDAK ubah warna)
    # =======================
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    mean_brightness = gray.mean()

    # nilai default
    beta = 0  

    # hanya angkat sedikit jika gelap → 100% aman
    if mean_brightness < 70:
        beta = 15
    elif mean_brightness < 90:
        beta = 10
    elif mean_brightness < 110:
        beta = 5
    # kalau terang → tidak disentuh
    else:
        beta = 0  

    # brightness lembut (alpha=1 → tidak ubah kontras)
    img = cv2.convertScaleAbs(img, alpha=1, beta=beta)

    # =======================
    # 2. Sedikit perbaiki exposure pakai gamma (AMAN warna)
    # =======================
    if mean_brightness < 90:
        gamma = 1.05   # sangat lembut
    else:
        gamma = 1.0    

    inv_gamma = 1.0 / gamma
    table = (np.array([((i / 255.0) ** inv_gamma) * 255
              for i in range(256)])).astype("uint8")
    img = cv2.LUT(img, table)

    # =======================
    # 3. Upscale ukuran (tanpa merubah warna/tekstur)
    # =======================
    h, w = img.shape[:2]

    # target panjang sisi maksimal
    target = 900  

    scale = target / max(h, w)
    if scale > 1.0:
        new_size = (int(w * scale), int(h * scale))
        img = cv2.resize(img, new_size, interpolation=cv2.INTER_LINEAR)
        # INTER_LINEAR aman → tidak bikin tekstur palsu

    enhanced_path = image_path.replace(".jpg", "_enhanced.jpg")
    cv2.imwrite(enhanced_path, img)

    return enhanced_path


# ===== FUNGSI DETEKSI DARI YOLO =====
def run_detection(image_path):
    results = model(image_path)  # proses deteksi
    detections = []

    for result in results:
        for box in result.boxes:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            conf = float(box.conf[0])
            cls = int(box.cls[0])
            label = model.names[cls]

            detections.append({
                "bbox": [round(x1, 2), round(y1, 2), round(x2, 2), round(y2, 2)],
                "confidence": round(conf, 3),
                "label": label
            })
    return detections


# ===== FUNGSI GAMBAR BOUNDING BOX DI GAMBAR =====
def draw_bounding_boxes(image_path, detections):
    img = cv2.imread(image_path)

    for det in detections:
        x1, y1, x2, y2 = map(int, det["bbox"])
        label = det["label"]
        conf = det["confidence"]

        cv2.rectangle(img, (x1, y1), (x2, y2), (0, 0, 255), 2)
        cv2.putText(img, f"{label} {conf:.2f}", (x1, y1 - 10),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 2)

    output_path = image_path.replace(".jpg", "_bbox.jpg")
    cv2.imwrite(output_path, img)
    return output_path


# ===== SIMPAN HASIL DETEKSI KE MYSQL =====
def save_to_mysql(filename, detections, suhu, kelembapan):
    if not detections:
        label = "bukan daun"
        confidence = 1.0
    else:
        label = detections[0]["label"]
        confidence = detections[0]["confidence"]

    sql = """
        INSERT INTO cam (filename, detections, label, confidence, suhu, kelembapan)
        VALUES (%s, %s, %s, %s, %s, %s)
    """
    cursor.execute(sql, (filename, json.dumps(detections), label, confidence, suhu, kelembapan))
    db_conn.commit()


# ===== MQTT CONFIG WITH DEBUGGING =====
mqtt_client = mqtt.Client(client_id=f"ServerPython-{uuid.uuid4().hex}", clean_session=True)  # ClientId unik
mqtt_client.enable_logger()  # Aktifkan logging bawaan paho

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"[MQTT] Terhubung ke broker {MQTT_BROKER}:{MQTT_PORT} ✅")
    else:
        print(f"[MQTT] Gagal connect, rc={rc} ❌")
    print(f"Flags: {flags}")

def on_disconnect(client, userdata, rc):
    print(f"[MQTT] Terputus dari broker, rc={rc}")
    if rc != 0:
        print("[MQTT] Mencoba reconnect otomatis...")
        try:
            client.reconnect()
        except Exception as e:
            print(f"[MQTT] ERROR reconnect: {e}")

def on_publish(client, userdata, mid):
    print(f"[MQTT] Data berhasil dikirim! MID: {mid}")

def on_log(client, userdata, level, buf):
    # Semua log internal paho muncul di sini
    print(f"[MQTT LOG] {buf}")

mqtt_client.on_connect = on_connect
mqtt_client.on_disconnect = on_disconnect
mqtt_client.on_publish = on_publish
mqtt_client.on_log = on_log

try:
    mqtt_client.connect(MQTT_BROKER, MQTT_PORT, 60)
    mqtt_client.loop_start()  # Start loop supaya reconnect otomatis
except Exception as e:
    print(f"[MQTT] ERROR saat koneksi awal: {e}")


# ===== PUBLISH FUNCTION DENGAN DEBUG =====
def send_detection_to_android(label, timestamp, image_url):
    data = {
        "label": label,
        "timestamp": timestamp,
        "image_url": image_url
    }
    payload_str = json.dumps(data)
    try:
        print(f"[MQTT DEBUG] Mempublish ke topic {MQTT_TOPIC_DETEKSI}: {payload_str}")
        result = mqtt_client.publish(MQTT_TOPIC_DETEKSI, payload_str, qos=1, retain=False)
        result.wait_for_publish()  # Tunggu sampai publish selesai
        status = result.rc
        if status == 0:
            print(f"[MQTT] Data dikirim ke Android berhasil! Topic: {MQTT_TOPIC_DETEKSI}")
        else:
            print(f"[MQTT] Gagal kirim data ke Android. Status code: {status}")
    except Exception as e:
        print(f"[MQTT] ERROR saat publish: {e}")


# ===== FASTAPI SERVER =====
app = FastAPI()

@app.post("/serverAI")
async def detect_leaf(
    file: UploadFile = File(...),
    suhu: float = Form(...),
    kelembapan: float = Form(...)
):
    start_time = time.time()
    try:
        # === simpan file ke folder Image ===
        filename = f"{uuid.uuid4().hex}.jpg"
        file_path = os.path.join(UPLOAD_FOLDER, filename)

        image_bytes = await file.read()
        with open(file_path, "wb") as f:
            f.write(image_bytes)

        print(f"[SERVER] File diterima: {filename} | Suhu: {suhu} | Kelembapan: {kelembapan}")

        # deteksi AI
        enhanced_path = enhance_image_quality(file_path)
        detections = run_detection(enhanced_path)


        if detections:
            bbox_image_path = draw_bounding_boxes(enhanced_path, detections)
        else:
            bbox_image_path = enhanced_path


        # simpan hasil ke database
        save_to_mysql(os.path.basename(bbox_image_path), detections, suhu, kelembapan)

        # kirim realtime ke Android via MQTT
        label = detections[0]["label"] if detections else "bukan daun"
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        image_url = f"http://192.168.43.179:8000/Image/{os.path.basename(bbox_image_path)}"
        send_detection_to_android(label, timestamp, image_url)

        # hasil status
        if not detections:
            status = "bukan daun"
        elif label == "sehat":
            status = "sehat"
        else:
            status = "terdeteksi penyakit"

        duration = round((time.time() - start_time) * 1000, 2)
        print(f"[SERVER] Hasil: {status} | Waktu proses total: {duration} ms")

        return {"status": status}

    except Exception as e:
        print("[SERVER] ERROR:", e)
        return {"status": "error", "detail": str(e)}


# menyediakan gambar via URL HTTP
app.mount("/Image", StaticFiles(directory=UPLOAD_FOLDER), name="Image")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
