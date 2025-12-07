#include <WiFi.h>
#include <PubSubClient.h>
#include <esp_camera.h>
#include <ESP32Servo.h>
#include "DHT.h"
#include <WiFiClient.h>
extern "C" {
  #include "esp_http_server.h"
}

// ==================== CONFIG WIFI, MQTT, SERVER AI ====================
const char* ssid = "Apa";
const char* password = "12345600";

const char* mqtt_server = "test.mosquitto.org";
const int mqtt_port = 1883;

const char* serverAI = "192.168.43.179";
const int serverAI_port = 8000;
const char* serverAI_path = "/serverAI";  

// ===== Variabel =====
WiFiClient streamClient;
float hmem = 0;
float tmem = 0;
int flashlight = 0;
bool cctvActive = false;
volatile bool triggerCCTVFrame = false;
unsigned long previousBlink = 0;
bool ledState = false;
const int blinkInterval = 1000; // 1 detik
int posX = 1;  // Posisi awal servo
int posY = 1;  // Posisi awal servo

// ===== STATE UNTUK AI DETECTION =====
struct AIState {
  unsigned long nextScanTime = 0; // waktu berikutnya untuk scan
};
AIState aiState;

// ==================== PINS ====================
// DHT + LED + Servo
#define LED_GREEN 48
#define LED_RED 47
#define DHTPIN 40
#define DHTTYPE DHT11
DHT dht(DHTPIN, DHTTYPE);
#define FLASH_PIN 2  // GPIO2 = LED ON
#define SERVO_X_PIN 42
#define SERVO_Y_PIN 41
//pin kamera
#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     15
#define SIOD_GPIO_NUM     4
#define SIOC_GPIO_NUM     5
#define Y9_GPIO_NUM       16
#define Y8_GPIO_NUM       17
#define Y7_GPIO_NUM       18
#define Y6_GPIO_NUM       12
#define Y5_GPIO_NUM       10
#define Y4_GPIO_NUM       8
#define Y3_GPIO_NUM       9
#define Y2_GPIO_NUM       11
#define VSYNC_GPIO_NUM    6
#define HREF_GPIO_NUM     7
#define PCLK_GPIO_NUM     13

Servo servoX;
Servo servoY;
String pesan;
// ==================== MODE ====================
enum Mode {AI, CCTV, Normal};
volatile Mode currentMode = Normal;

// ==================== AI ====================
int servoMin = 10;    // sudut paling kiri
int servoMax = 150;   // sudut paling kanan
int servoStep = 30;   // jarak tiap capture
int posGX = servoMin;  // posisi awal
bool goingRight = true; // arah gerak servo
const int scanDelay = 120000; // 2 menit

// ==================== MQTT ====================
const char* topicServoX = "cam/servoX";
const char* topicServoY = "cam/servoY";
const char* topicDHT = "cam/dht";
const char* topicMode = "cam/mode";

WiFiClient espClient;
PubSubClient client(espClient);

httpd_handle_t stream_httpd = NULL;

static const char *_STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=123456789000000000000987654321";
static const char *_STREAM_BOUNDARY = "\r\n--123456789000000000000987654321\r\n";
static const char *_STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\nX-Timestamp: %d.%06d\r\n\r\n";

typedef struct {
    httpd_req_t *req;
    size_t len;
} jpg_chunking_t;

static size_t jpg_encode_stream(void *arg, size_t index, const void *data, size_t len) {
    jpg_chunking_t *j = (jpg_chunking_t *)arg;
    if (!index) j->len = 0;
    if (httpd_resp_send_chunk(j->req, (const char *)data, len) != ESP_OK) {
        return 0;
    }
    j->len += len;
    return len;
}

static esp_err_t stream_handler(httpd_req_t *req) {
    camera_fb_t *fb = NULL;
    esp_err_t res = ESP_OK;
    size_t _jpg_buf_len = 0;
    uint8_t *_jpg_buf = NULL;
    char part_buf[128];
    int64_t last_frame = esp_timer_get_time();

    httpd_resp_set_type(req, _STREAM_CONTENT_TYPE);
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

    while (true) {
        fb = esp_camera_fb_get();
        if (!fb) {
            res = ESP_FAIL;
            break;
        }
        if (fb->format != PIXFORMAT_JPEG) {
            bool jpeg_converted = frame2jpg(fb, 80, &_jpg_buf, &_jpg_buf_len);
            esp_camera_fb_return(fb);
            fb = NULL;
            if (!jpeg_converted) {
                res = ESP_FAIL;
                break;
            }
        } else {
            _jpg_buf_len = fb->len;
            _jpg_buf = fb->buf;
        }

        res = httpd_resp_send_chunk(req, _STREAM_BOUNDARY, strlen(_STREAM_BOUNDARY));
        if (res != ESP_OK) break;

        size_t hlen = snprintf(part_buf, 128, _STREAM_PART, _jpg_buf_len, fb->timestamp.tv_sec, fb->timestamp.tv_usec);
        res = httpd_resp_send_chunk(req, (const char *)part_buf, hlen);
        if (res != ESP_OK) break;

        res = httpd_resp_send_chunk(req, (const char *)_jpg_buf, _jpg_buf_len);
        if (res != ESP_OK) break;

        if (fb) esp_camera_fb_return(fb);
        if (_jpg_buf && fb == NULL) free(_jpg_buf);
        _jpg_buf = NULL;
    }

    return res;
}

// ==================== FUNCTIONS ====================
void setup_wifi();
void reconnect();
void callback(char* topic, byte* payload, unsigned int length);
void sendDHT();
bool performAI(int pos);
void pushFrameToVPS(camera_fb_t* fb);
void masukModeCCTV();
void stopStream();
void handleAIMode();
void startStreamServer(uint16_t port = 81);

// ==================== SETUP ====================
void setup() {
  Serial.begin(115200);
  delay(100);

  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_RED, OUTPUT);
  pinMode(FLASH_PIN, OUTPUT);

  // LED startup test
  digitalWrite(LED_RED, HIGH);
  digitalWrite(LED_GREEN, HIGH);
  Serial.println("[STARTUP] LED test ON");
  delay(1000);
  digitalWrite(LED_RED, LOW);
  digitalWrite(LED_GREEN, LOW);
  Serial.println("[STARTUP] LED test OFF");

  dht.begin();
  servoX.attach(SERVO_X_PIN);
  servoY.attach(SERVO_Y_PIN);

  // ===== CAMERA =====
  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_QVGA;
  config.jpeg_quality = 10;
  config.fb_count = 2;
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.fb_location = CAMERA_FB_IN_PSRAM;

  if(psramFound()){
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 10;
    config.fb_count = 1;
    config.grab_mode = CAMERA_GRAB_LATEST;
  } else {
    config.frame_size = FRAMESIZE_QVGA;
    config.fb_location = CAMERA_FB_IN_DRAM;
  }

  if(esp_camera_init(&config) != ESP_OK){
    Serial.println("Camera init failed!");
    while(true);
  }

  sensor_t *s = esp_camera_sensor_get();
  s->set_framesize(s, FRAMESIZE_QVGA);
  s->set_contrast(s, 0);
  s->set_raw_gma(s, 1);

  Serial.println("Camera Setup Complete");

  // ===== WIFI + MQTT =====
  setup_wifi();
  client.setServer(mqtt_server, mqtt_port);
  client.setCallback(callback);

  // try connect MQTT once at startup
  reconnect();

  // ensure AI timer starts now (so first scan immediate if mode set)
  aiState.nextScanTime = 0;
}

// ==================== LOOP ====================
void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    WiFi.reconnect();
    delay(2000);
    return;
  }

  if (!client.connected()) {
    reconnect();
  }
  client.loop();

  static unsigned long lastDHT = 0;
  if (millis() - lastDHT > 2000) {
    lastDHT = millis();
    // sendDHT only here (rate-limited)
    sendDHT();
  }

  if (currentMode == AI) {
    handleAIMode();
    // don't call sendDHT() here; we already send periodically from loop()
  } else if (currentMode == CCTV) {
    masukModeCCTV();
  }else if (currentMode == Normal) {
    unsigned long now = millis();
    if (now - previousBlink >= blinkInterval) {
        previousBlink = now;
        ledState = !ledState; // toggle state
        digitalWrite(LED_GREEN, ledState ? HIGH : LOW);
        digitalWrite(LED_RED, ledState ? HIGH : LOW);
    }
  }

  // small yield
  delay(10);
}

// ==================== WIFI ====================
void setup_wifi(){
  WiFi.begin(ssid,password);
  Serial.print("Connecting WiFi...");
  while(WiFi.status()!=WL_CONNECTED){
    delay(500);
    Serial.print(".");
  }
  Serial.println(" connected. IP: "+WiFi.localIP().toString());
}

// ==================== MQTT ====================
void reconnect() {
  static unsigned long lastReconnectAttempt = 0;
  static bool firstAttempt = true;

  if (WiFi.status() != WL_CONNECTED) return;

  if (millis() - lastReconnectAttempt > 5000 || firstAttempt) {
    firstAttempt = false;
    lastReconnectAttempt = millis();

    Serial.print("[MQTT] Connecting...");
    if (client.connect(("ESP32CAM-"+String(random(0xffff), HEX)).c_str())) {
      Serial.println("connected!");
      client.subscribe(topicServoX);
      client.subscribe(topicServoY);
      client.subscribe(topicMode);
    } else {
      Serial.print(" failed, rc=");
      Serial.println(client.state());
    }
  }
}

void callback(char* topic, byte* payload, unsigned int length){
  String msg="";
  for(unsigned int i=0;i<length;i++) msg += (char)payload[i];
  msg.trim();

  Serial.print("[MQTT RECEIVED] Topic: ");
  Serial.print(topic);
  Serial.print(" | Message: ");
  Serial.println(msg);

  String sTopic = String(topic);
  sTopic.trim();

  // mode control (normalize uppercase)
  String up = msg;
  up.toUpperCase();

  if (sTopic == String(topicMode)) {
    if (up == "AI") {
      currentMode = AI;
      aiState.nextScanTime = 0; // immediate scan
      Serial.println("[MODE] Switched to AI_DETECTION");
    } else if (up == "CCTV") {
      currentMode = CCTV;
      Serial.println("[MODE] Switched to CCTV");
    }
  }

}

// ==================== DHT ====================
void sendDHT(){
  float h = dht.readHumidity();
  float t = dht.readTemperature();
  if(!isnan(h) && !isnan(t)){
    String payload = "{\"temp\":"+String(t)+",\"hum\":"+String(h)+"}";
    client.publish(topicDHT, payload.c_str());
    Serial.print("[DHT DATA] Temp: ");
    Serial.print(t);
    Serial.print("°C, Humidity: ");
    Serial.print(h);
    Serial.println("%");
  } else {
    Serial.println("[DHT] Read failed");
  }
}

// ==================== AI DETECTION ====================
bool performAI(int pos) {
  unsigned long start = millis();
  servoX.write(pos);
  Serial.printf("\n[AI] Mulai deteksi di posisi servo: %d\n", pos);

  // debug heap
  Serial.printf("[MEM] free heap: %u\n", ESP.getFreeHeap());

  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println("[CAMERA] fb capture failed!");
    return false;
  }
  Serial.printf("[CAMERA] Gambar berhasil diambil. Ukuran: %d bytes\n", fb->len);

  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (isnan(t) || isnan(h)) { t = 0; h = 0; }
  Serial.printf("[DHT] Suhu: %.2f °C | Kelembapan: %.2f %%\n", t, h);

  bool penyakitDetected = false;

  // Build multipart pieces (small strings only)
  const String boundary = "----ESP32CAMBoundary";
  String partStart = "--" + boundary + "\r\n";
  partStart += "Content-Disposition: form-data; name=\"file\"; filename=\"frame.jpg\"\r\n";
  partStart += "Content-Type: image/jpeg\r\n\r\n";

  String partMiddle = "\r\n--" + boundary + "\r\n";
  partMiddle += "Content-Disposition: form-data; name=\"suhu\"\r\n\r\n";
  partMiddle += String(t) + "\r\n";
  partMiddle += "--" + boundary + "\r\n";
  partMiddle += "Content-Disposition: form-data; name=\"kelembapan\"\r\n\r\n";
  partMiddle += String(h) + "\r\n";
  partMiddle += "--" + boundary + "--\r\n";

  // compute content-length safely (use unsigned long long to avoid overflow)
  unsigned long long contentLength = (unsigned long long)partStart.length() + (unsigned long long)fb->len + (unsigned long long)partMiddle.length();

  if (WiFi.status() == WL_CONNECTED) {
    WiFiClient client;
    Serial.print("[HTTP] Connecting to ");
    Serial.print(serverAI);
    Serial.print(":");
    Serial.println(serverAI_port);

    if (!client.connect(serverAI, serverAI_port)) {
      Serial.println("[HTTP] Connection failed");
      esp_camera_fb_return(fb);
      return false;
    }

    // compose request headers (no HTTPClient)
    String req = "";
    req += String("POST ") + serverAI_path + " HTTP/1.1\r\n";
    req += String("Host: ") + serverAI + "\r\n";
    req += "User-Agent: ESP32CAM\r\n";
    req += String("Content-Type: multipart/form-data; boundary=") + boundary + "\r\n";
    req += String("Content-Length: ") + String(contentLength) + "\r\n";
    req += "Connection: close\r\n\r\n";

    // send headers
    client.print(req);
    // send multipart start
    client.print(partStart);

    // send image binary
    size_t written = 0;
    const uint8_t* buf = fb->buf;
    size_t toWrite = fb->len;
    while (toWrite > 0) {
      // write in chunks (avoid huge single write)
      size_t chunk = toWrite > 1024 ? 1024 : toWrite;
      client.write(buf + written, chunk);
      written += chunk;
      toWrite -= chunk;
      // a short delay can help networking stack
      delay(1);
    }

    // send rest
    client.print(partMiddle);

    Serial.println("[HTTP] Request sent, waiting response...");

    // read response (with timeout)
    unsigned long timeout = millis() + 10000; // 10s
    String response = "";
    while (client.connected() && millis() < timeout) {
      while (client.available()) {
        char c = client.read();
        response += c;
        // limit response size to avoid huge strings
        if (response.length() > 20000) break;
      }
      if (response.length() > 0 && response.indexOf("\r\n\r\n") != -1) {
        // we at least have headers + maybe body
        // don't break immediately; keep reading until connection closes or timeout
      }
      delay(1);
    }

    // parse HTTP status code from response start
    int httpCode = 0;
    if (response.length() > 12) {
      int idx = response.indexOf(' ');
      if (idx > 0) {
        int idx2 = response.indexOf(' ', idx + 1);
        if (idx2 > idx) {
          String codeStr = response.substring(idx + 1, idx2);
          httpCode = codeStr.toInt();
        }
      }
    }
    Serial.printf("[HTTP] Parsed status: %d\n", httpCode);
    // optional print small snippet
    Serial.println("[HTTP] Response snippet:");
    Serial.println(response.substring(0, min(300, (int)response.length())));

    // analyze body for keyword "penyakit"
    if (httpCode == 200 || httpCode == 201) {
      if (response.indexOf("penyakit") != -1) {
        penyakitDetected = true;
        digitalWrite(LED_RED, HIGH);
        digitalWrite(LED_GREEN, LOW);
        Serial.println("[AI] Hasil: PENYAKIT terdeteksi ❌ → LED MERAH ON");
      } else {
        penyakitDetected = false;
        digitalWrite(LED_GREEN, HIGH);
        digitalWrite(LED_RED, LOW);
        Serial.println("[AI] Hasil: SEHAT / BUKAN DAUN ✅ → LED HIJAU ON");
      }
    } else {
      Serial.printf("[HTTP] Non-200 response: %d\n", httpCode);
    }

    client.stop();
  } else {
    Serial.println("[WIFI] Tidak terhubung! Gagal kirim ke server AI.");
  }

  // always return fb
  esp_camera_fb_return(fb);

  unsigned long duration = millis() - start;
  Serial.printf("[AI] Waktu total proses (capture + HTTP): %lu ms\n", duration);
  Serial.println("========================================================");
  return penyakitDetected;
}

void handleAIMode() {
  unsigned long now = millis();

  if (now >= aiState.nextScanTime) {
    Serial.println("[SCAN] Mulai pemindaian baru...");
    bool sakit = performAI(posGX);

    if (sakit) {
      aiState.nextScanTime = now + 60000; // 1 menit kalau sakit
      Serial.println("[SCAN] Delay scan berikutnya: 1 menit (karena penyakit).");
    } else {
      aiState.nextScanTime = now + 120000; // 2 menit kalau sehat
      Serial.println("[SCAN] Delay scan berikutnya: 2 menit (karena sehat).");
    }

    // Atur arah servo
    if (goingRight) {
      posGX += servoStep;
      if (posGX >= servoMax) {
        posGX = servoMax;
        goingRight = false;
        Serial.println("[SERVO] Batas kanan tercapai → berbalik arah kiri.");
      }
    } else {
      posGX -= servoStep;
      if (posGX <= servoMin) {
        posGX = servoMin;
        goingRight = true;
        Serial.println("[SERVO] Batas kiri tercapai → berbalik arah kanan.");
      }
    }
  }
}

//cctv
void masukModeCCTV() {
  Serial.println("\n===========================");
  Serial.println("[MODE] CCTV AKTIF");
  Serial.println("===========================");

  Serial.println("[INFO] Memulai server kamera...");
  startStreamServer();   // start server UI + stream

  Serial.print("[INFO] IP ESP32: ");
  Serial.println(WiFi.localIP());

  Serial.println("-----------------------------------");
  Serial.print("[AKSES UI]     : http://");
  Serial.println(WiFi.localIP());
  Serial.print("[AKSES STREAM] : http://");
  Serial.print(WiFi.localIP());
  Serial.println(":81/stream");
  Serial.println("-----------------------------------");

  // Nyalakan LED indikator (dua-duanya ON)
  digitalWrite(LED_GREEN, HIGH);
  digitalWrite(LED_RED, HIGH);
}

void startStreamServer(uint16_t port) {
  httpd_config_t config = HTTPD_DEFAULT_CONFIG();
      config.server_port = port;

    if(stream_httpd == NULL){
      if (httpd_start(&stream_httpd, &config) == ESP_OK) {
          httpd_uri_t stream_uri = {
              .uri = "/stream",
              .method = HTTP_GET,
              .handler = stream_handler,
              .user_ctx = NULL
          };
          httpd_register_uri_handler(stream_httpd, &stream_uri);
      } else {
          Serial.println("[ERROR] Gagal start HTTP server");
      }
  } else {
      Serial.println("[INFO] HTTP server sudah berjalan");
  }
}


