package com.example.melvis

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.melvis.services.MqttServices
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException

class Beranda : AppCompatActivity() {

    // UI
    private lateinit var tvTemperature: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimestamp: TextView
    private lateinit var imgLastDetection: ImageView
    private lateinit var btnKamera: ImageView
    private lateinit var btnLihatRiwayat: ImageView
    private lateinit var lampu_hijau: ImageView
    private lateinit var lampu_merah: ImageView

    // MQTT
    private var mqttService: MqttServices? = null
    private var isBound = false
    private val topicDht = "cam/dht"
    private val topicDeteksi = "cam/deteksi"
    private val topicMode = "cam/mode"

    // ===== LAST DATA =====
    object LastData {
        var suhu: String? = null
        var kelembapan: String? = null
        var status: String? = null
        var timestamp: String? = null
        var bitmap: Bitmap? = null
    }
    private val lastData = LastData

    // ===== MQTT Service Connection =====
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MqttServices.LocalBinder
            mqttService = binder.getService()
            isBound = true
            mqttService?.subscribeTopic(topicDht)
            mqttService?.subscribeTopic(topicDeteksi)
            mqttService?.subscribeTopic(topicMode)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
            isBound = false
        }
    }

    // ===== MQTT Receiver =====
    private val mqttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val topic = intent?.getStringExtra("topic") ?: return
            val payload = intent.getStringExtra("payload") ?: return

            Log.d("MQTT_DEBUG", "Terima MQTT -> Topic: $topic | Payload: $payload")

            when (topic) {
                topicDht -> {
                    val parts = payload.split(",")
                    if (parts.size == 2) {
                        val suhu = parts[0].replace("[^0-9.]".toRegex(), "")
                        val kelembapan = parts[1].replace("[^0-9.]".toRegex(), "")

                        lastData.suhu = suhu
                        lastData.kelembapan = kelembapan
                        saveLastDataToPrefs()

                        runOnUiThread {
                            tvTemperature.text = "Suhu: $suhu°C"
                            tvHumidity.text = "Kelembapan: $kelembapan%"
                        }

                        Log.d("MQTT_DEBUG", "Update DHT -> Suhu: $suhu, Kelembapan: $kelembapan")
                    }
                }

                topicDeteksi -> {
                    try {
                        val json = JSONObject(payload)
                        val label = json.getString("label")
                        val timestamp = json.getString("timestamp")
                        val imageUrl = json.getString("image_url")

                        lastData.status = label
                        lastData.timestamp = timestamp
                        saveLastDataToPrefs()

                        runOnUiThread {
                            tvStatus.text = "Status: $label"
                            tvTimestamp.text = timestamp
                            updateLampuStatus(label)
                        }

                        loadImage(imageUrl) // cukup satu kali

                    } catch (e: Exception) {
                        Log.e("MQTT_DEBUG", "JSON Error: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_beranda)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainHome)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ===== Init UI =====
        tvTemperature = findViewById(R.id.tvTemperature)
        tvHumidity = findViewById(R.id.tvHumidity)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimestamp = findViewById(R.id.tvTimestamp)
        imgLastDetection = findViewById(R.id.imgLastDetection)
        btnKamera = findViewById(R.id.btnMonitoringBottom)
        btnLihatRiwayat = findViewById(R.id.btnRiwayatBottom)
        lampu_hijau = findViewById(R.id.iconLeft)
        lampu_merah = findViewById(R.id.iconRight)

        // ===== Load last data =====
        loadLastDataFromPrefs()

        // ===== Button Listener =====
        btnKamera.setOnClickListener {
            startActivity(Intent(this, KontrolKamera::class.java))
            publishMode("CCTV")
        }

        btnLihatRiwayat.setOnClickListener {
            startActivity(Intent(this, Riwayat::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MqttServices::class.java), connection, Context.BIND_AUTO_CREATE)
        registerReceiver(mqttReceiver, IntentFilter("MQTT_MESSAGE"))
    }

    override fun onStop() {
        super.onStop()
        if (isBound) unbindService(connection)
        unregisterReceiver(mqttReceiver)
    }

    // ===== PUBLISH MODE =====
    private fun publishMode(mode: String) {
        if (mqttService?.isMqttConnected() == true) {
            mqttService?.publishMessage("cam/mode", mode)
        } else {
            mqttService?.mqttListener = object : MqttServices.MqttListener {
                override fun onConnected() {
                    mqttService?.publishMessage("cam/mode", mode)
                    mqttService?.mqttListener = null
                }
            }
        }
    }

    // ===== Load & Cache Image =====
    private fun loadImage(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return
                val bmp = BitmapFactory.decodeStream(response.body?.byteStream())
                if (bmp != null) {
                    lastData.bitmap?.recycle()
                    lastData.bitmap = bmp
                    runOnUiThread { imgLastDetection.setImageBitmap(bmp) }
                    saveBitmapToCache(bmp)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.e("MQTT_DEBUG", "Load gambar gagal: ${e.message}")
            }
        })
    }

    private fun saveBitmapToCache(bitmap: Bitmap) {
        try {
            val file = File(cacheDir, "last_image.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.e("CACHE", "Gagal simpan bitmap: ${e.message}")
        }
    }

    // ===== SharedPreferences =====
    private fun saveLastDataToPrefs() {
        val prefs = getSharedPreferences("lastData", MODE_PRIVATE)
        prefs.edit().apply {
            putString("suhu", lastData.suhu)
            putString("kelembapan", lastData.kelembapan)
            putString("status", lastData.status)
            putString("timestamp", lastData.timestamp)
            apply()
        }
    }

    private fun loadLastDataFromPrefs() {
        val prefs = getSharedPreferences("lastData", MODE_PRIVATE)
        lastData.suhu = prefs.getString("suhu", null)
        lastData.kelembapan = prefs.getString("kelembapan", null)
        lastData.status = prefs.getString("status", null)
        lastData.timestamp = prefs.getString("timestamp", null)

        lastData.suhu?.let { tvTemperature.text = "Suhu: $it°C" }
        lastData.kelembapan?.let { tvHumidity.text = "Kelembapan: $it%" }
        lastData.status?.let {
            tvStatus.text = "Status: $it"
            updateLampuStatus(it)
        }
        lastData.timestamp?.let { tvTimestamp.text = it }

        loadLastBitmapFromCache()
    }

    private fun loadLastBitmapFromCache() {
        try {
            val file = File(cacheDir, "last_image.png")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                lastData.bitmap = bitmap
                imgLastDetection.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Log.e("CACHE", "Gagal load bitmap: ${e.message}")
        }
    }

    // ===== Lampu Status =====
    private fun updateLampuStatus(label: String) {
        when (label.lowercase()) {
            "sehat" -> {
                lampu_hijau.setImageResource(R.drawable.hijau)
                lampu_merah.setImageResource(R.drawable.putih)
            }
            "terdeteksi penyakit" -> {
                lampu_hijau.setImageResource(R.drawable.putih)
                lampu_merah.setImageResource(R.drawable.merah)
            }
            else -> {
                lampu_hijau.setImageResource(R.drawable.putih)
                lampu_merah.setImageResource(R.drawable.putih)
            }
        }
    }
}
