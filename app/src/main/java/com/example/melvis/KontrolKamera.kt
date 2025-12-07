package com.example.melvis

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.melvis.services.MqttServices

class KontrolKamera : AppCompatActivity() {

    private lateinit var progressLoading: ProgressBar
    private lateinit var cameraView: WebView
    private lateinit var btnLihatRiwayat: ImageView
    private lateinit var btnHome: ImageView

    private var mqttService: MqttServices? = null
    private var isServiceBound = false

    // ===== Binder dari Service =====
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MqttServices.LocalBinder
            mqttService = localBinder.getService()
            isServiceBound = true

        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kontrol_kamera)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainHome)) { v, insets ->
            val sysBar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sysBar.left, sysBar.top, sysBar.right, sysBar.bottom)
            insets
        }

        // Bind ke MQTT Service
        Intent(this, MqttServices::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Inisialisasi Views
        progressLoading = findViewById(R.id.progressLoading)
        cameraView = findViewById(R.id.cameraView)
        btnLihatRiwayat = findViewById(R.id.btnRiwayatBottom)
        btnHome = findViewById(R.id.btnHome)

        setupWebView()

        // Tombol Home → Balik ke mode AI
        btnHome.setOnClickListener {
            startActivity(Intent(this, Beranda::class.java))
            if (mqttService?.isMqttConnected() == true) {
                mqttService?.publishMessage("cam/mode", "AI")
                Log.d("MQTT_DEBUG", "Publish mode CCTV berhasil")
            } else {
                Log.d("MQTT_DEBUG", "MQTT belum connect, menunggu connect untuk publish...")
                mqttService?.mqttListener = object : MqttServices.MqttListener {
                    override fun onConnected() {
                        mqttService?.publishMessage("cam/mode", "CCTV")
                        Log.d("MQTT_DEBUG", "Publish mode CCTV berhasil setelah connect")
                        mqttService?.mqttListener = null // reset listener
                    }
                }
            }
        }

        btnLihatRiwayat.setOnClickListener {
            startActivity(Intent(this, Riwayat::class.java))
        }

        val btnRefresh = findViewById<Button>(R.id.btnRefreshStream)

        btnRefresh.setOnClickListener {
            val web = findViewById<WebView>(R.id.cameraView)
            web.reload()
            Toast.makeText(this, "Stream di-refresh...", Toast.LENGTH_SHORT).show()
        }

    }

    private fun setupWebView() {
        cameraView.webChromeClient = WebChromeClient()
        cameraView.webViewClient = WebViewClient()

        val settings = cameraView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        cameraView.loadUrl("http://192.168.101.79:81/stream")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
    }
}
