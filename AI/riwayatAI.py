from fastapi import FastAPI, Form
from fastapi.middleware.cors import CORSMiddleware
import pandas as pd
import numpy as np
import json
from sklearn.linear_model import LinearRegression
import matplotlib.pyplot as plt
import io
import base64
import logging

# =================== SETUP LOGGING ===================
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("riwayat_ai_analysis")

app = FastAPI()

# Enable CORS supaya bisa diakses dari Android
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.post("/analyze_riwayat_complete")
async def analyze_riwayat_complete(data: str = Form(...)):
    try:
        logger.info("Menerima request untuk analisis riwayat")
        logger.info(f"Payload diterima: {data}")  # cetak data mentah

        # ================= Parse JSON =================
        data_list = json.loads(data)
        logger.info(f"Jumlah data diterima: {len(data_list)}")
        df = pd.DataFrame(data_list)
        df['suhu'] = pd.to_numeric(df['suhu'], errors='coerce')
        df['kelembapan'] = pd.to_numeric(df['kelembapan'], errors='coerce')
        df['waktu'] = pd.to_datetime(df['waktu'])
        logger.info("Parsing data selesai")

        # ================= 1️⃣ Statistik =================
        stats = {
            "mean_suhu": round(df['suhu'].mean(), 2),
            "mean_kelembapan": round(df['kelembapan'].mean(), 2),
            "max_suhu": round(df['suhu'].max(), 2),
            "min_suhu": round(df['suhu'].min(), 2),
            "max_kelembapan": round(df['kelembapan'].max(), 2),
            "min_kelembapan": round(df['kelembapan'].min(), 2),
        }
        logger.info(f"Statistik: {stats}")

        # ================= 2️⃣ Deteksi Anomali =================
        df['suhu_z'] = (df['suhu'] - df['suhu'].mean()) / df['suhu'].std()
        df['kelembapan_z'] = (df['kelembapan'] - df['kelembapan'].mean()) / df['kelembapan'].std()
        anomalies = df[(df['suhu_z'].abs() > 2) | (df['kelembapan_z'].abs() > 2)]
        anomalies_list = anomalies[['waktu', 'suhu', 'kelembapan']].to_dict(orient='records')
        logger.info(f"Jumlah anomali terdeteksi: {len(anomalies_list)}")

        # ================= 3️⃣ Prediksi Tren =================
        df_sorted = df.sort_values('waktu')
        df_sorted['timestamp'] = df_sorted['waktu'].astype('int64') / 10**9  # convert ke detik

        X = df_sorted['timestamp'].values.reshape(-1, 1)
        y_suhu = df_sorted['suhu'].fillna(df_sorted['suhu'].mean()).values
        y_kela = df_sorted['kelembapan'].fillna(df_sorted['kelembapan'].mean()).values

        model_suhu = LinearRegression().fit(X, y_suhu)
        model_kela = LinearRegression().fit(X, y_kela)

        future_times = np.linspace(X.max(), X.max() + 3600*5, 5).reshape(-1,1)  # prediksi 5 jam ke depan
        suhu_pred = model_suhu.predict(future_times)
        kela_pred = model_kela.predict(future_times)

        # versi datetime untuk plotting
        future_waktu_dt = pd.to_datetime(future_times.flatten(), unit='s')
        # versi string untuk dikirim ke client
        future_waktu_str = future_waktu_dt.astype(str).tolist()
        logger.info(f"Prediksi tren selesai: {future_waktu_str}")

        trend = {
            "waktu": future_waktu_str,
            "suhu_trend": [round(v,2) for v in suhu_pred],
            "kelembapan_trend": [round(v,2) for v in kela_pred]
        }

        # ================= 4️⃣ Rekomendasi =================
        recommendation = []
        if not anomalies.empty:
            recommendation.append("Ada kondisi ekstrem, periksa sistem irigasi/ventilasi")
        if max(suhu_pred) > 35:
            recommendation.append("Prediksi suhu tinggi, siapkan pendinginan/shading")
        if min(kela_pred) < 30:
            recommendation.append("Prediksi kelembapan rendah, lakukan penyiraman tambahan")
        if not recommendation:
            recommendation.append("Kondisi normal, monitoring rutin")
        logger.info(f"Rekomendasi: {recommendation}")

        # ================= 5️⃣ Visualisasi =================
        plt.figure(figsize=(10,5))
        plt.plot(df_sorted['waktu'], df_sorted['suhu'], label='Suhu Riwayat', color='red')
        plt.plot(df_sorted['waktu'], df_sorted['kelembapan'], label='Kelembapan Riwayat', color='blue')
        plt.plot(future_waktu_dt, suhu_pred, '--', label='Suhu Prediksi', color='orange')
        plt.plot(future_waktu_dt, kela_pred, '--', label='Kelembapan Prediksi', color='cyan')

        if not anomalies.empty:
            plt.scatter(anomalies['waktu'], anomalies['suhu'], color='darkred', label='Anomali Suhu', zorder=5)
            plt.scatter(anomalies['waktu'], anomalies['kelembapan'], color='darkblue', label='Anomali Kelembapan', zorder=5)

        plt.xlabel('Waktu')
        plt.ylabel('Nilai')
        plt.title('Tren Suhu & Kelembapan Riwayat + Prediksi')
        plt.legend()
        plt.tight_layout()

        buf = io.BytesIO()
        plt.savefig(buf, format='png')
        plt.close()
        buf.seek(0)
        img_base64 = base64.b64encode(buf.read()).decode('utf-8')
        logger.info("Visualisasi selesai, dikonversi ke Base64")

        # ================= 6️⃣ Kembalikan Semua Output =================
        result = {
            "stats": stats,
            "anomalies": anomalies_list,
            "trend": trend,
            "recommendation": " | ".join(recommendation),
            "graph_base64": img_base64
        }

        logger.info("Analisis riwayat selesai, mengirim hasil ke client")
        return result

    except Exception as e:
        logger.error(f"Terjadi error saat analisis: {e}")
        return {"error": str(e)}
