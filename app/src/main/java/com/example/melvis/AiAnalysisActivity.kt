package com.example.melvis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject
import kotlinx.coroutines.*


class AiAnalysisActivity : AppCompatActivity() {

    private lateinit var tvStats: TextView
    private lateinit var tvRecommendation: TextView
    private lateinit var ivGraph: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_analysis)

        tvStats = findViewById(R.id.tvStats)
        tvRecommendation = findViewById(R.id.tvRecommendation)
        ivGraph = findViewById(R.id.ivGraph)

        // Ambil data listRiwayat dari intent
        val dataJson = intent.getStringExtra("dataList") ?: "[]"

        // Kirim data otomatis ke FastAPI begitu activity dibuka
        kirimDataKeServer(dataJson)
    }

    private fun kirimDataKeServer(dataListJson: String) {
        val queue = Volley.newRequestQueue(this)
        val url = "http://192.168.43.179:8000/analyze_riwayat_complete"

        val request = object : StringRequest(
            Method.POST, url,
            { response ->
                parseAiResponse(response)
            },
            { error ->
                error.printStackTrace()
                Toast.makeText(this, "Gagal kirim data ke AI server", Toast.LENGTH_SHORT).show()
            }
        ) {
            override fun getParams(): MutableMap<String, String> {
                return mutableMapOf("data" to dataListJson)
            }
        }

        queue.add(request)
    }

    private fun parseAiResponse(response: String) {
        try {
            val json = JSONObject(response)

            // ===== Statistik =====
            val stats = json.getJSONObject("stats")
            tvStats.text = "Suhu: ${stats.getDouble("mean_suhu")}°C (avg), Max: ${stats.getDouble("max_suhu")}, Min: ${stats.getDouble("min_suhu")}\n" +
                    "Kelembapan: ${stats.getDouble("mean_kelembapan")}% (avg), Max: ${stats.getDouble("max_kelembapan")}, Min: ${stats.getDouble("min_kelembapan")}"
            tvStats.visibility = View.VISIBLE

            tvRecommendation.text = json.getString("recommendation")
            tvRecommendation.visibility = View.VISIBLE

            // ===== Base64 Grafik =====
            val imgBase64 = json.getString("graph_base64")

            // Decode & scale bitmap di background
            GlobalScope.launch(Dispatchers.Default) {
                val imageBytes = Base64.decode(imgBase64, Base64.DEFAULT)
                var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                // resize sesuai ImageView
                val width = ivGraph.width.takeIf { it > 0 } ?: 600
                val height = ivGraph.height.takeIf { it > 0 } ?: 300
                bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)

                // Set bitmap di UI thread
                withContext(Dispatchers.Main) {
                    ivGraph.setImageBitmap(bitmap)
                    ivGraph.visibility = View.VISIBLE
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error parsing AI response", Toast.LENGTH_SHORT).show()
        }
    }

}
