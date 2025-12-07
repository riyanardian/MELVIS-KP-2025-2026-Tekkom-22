package com.example.melvis

import RiwayatAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.example.melvis.services.MqttServices
import org.json.JSONArray
import org.json.JSONObject

class Riwayat : AppCompatActivity() {

    private lateinit var rvRiwayat: RecyclerView
    private lateinit var adapter: RiwayatAdapter
    private val listRiwayat = mutableListOf<RiwayatItem>()
    private lateinit var btnRefresh: Button
    private lateinit var btnKamera: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnReviewAI: Button


    // MQTT Service instance
    private var mqttService: MqttServices? = null
    private var isBound = false

    private val apiUrl = "http://192.168.43.179/api/get_cam_data.php"

    // ====================== MQTT SERVICE CONNECTION ======================
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as MqttServices.LocalBinder
            mqttService = localBinder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            mqttService = null
        }
    }

    // ====================== ON CREATE ======================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_riwayat)

        // Bind ke MQTT Service
        Intent(this, MqttServices::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Edge-to-edge system bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainHome)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        rvRiwayat = findViewById(R.id.rvRiwayat)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnHome = findViewById(R.id.btnHome)
        btnKamera = findViewById(R.id.btnMonitoringBottom)

        rvRiwayat.layoutManager = LinearLayoutManager(this)
        adapter = RiwayatAdapter(listRiwayat) { item, position ->
            // Panggil fungsi hapus di activity
            hapusDataDariServer(item.filename ?: "", position)
        }
        rvRiwayat.adapter = adapter

        ambilDataDariMySQL()

        val headerText = findViewById<TextView>(R.id.tvHeader)

        rvRiwayat.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView.canScrollVertically(-1)) {
                    // Sudah scroll ke bawah, kasih shadow
                    headerText.elevation = 8f
                } else {
                    // Di atas, hilangkan shadow
                    headerText.elevation = 8f
                }
            }
        })

        btnReviewAI = findViewById(R.id.btnReviewAI)
        // Tombol Review with AI
        btnReviewAI.setOnClickListener {
            if (listRiwayat.isNotEmpty()) {
                // Buka AiAnalysisActivity dan kirim listRiwayat
                val intent = Intent(this, AiAnalysisActivity::class.java)
                // Bisa kirim seluruh list sebagai JSON string
                val jsonArray = org.json.JSONArray()
                listRiwayat.forEach { item ->
                    val obj = org.json.JSONObject()
                    obj.put("filename", item.filename)
                    obj.put("label", item.label)
                    obj.put("waktu", item.waktu)
                    obj.put("suhu", item.suhu)
                    obj.put("kelembapan", item.kelembapan)
                    jsonArray.put(obj)
                }
                intent.putExtra("dataList", jsonArray.toString())
                startActivity(intent)
            } else {
                Toast.makeText(this, "Tidak ada data untuk dianalisis", Toast.LENGTH_SHORT).show()
            }
        }


        // Refresh LIST
        btnRefresh.setOnClickListener {
            ambilDataDariMySQL()
        }

        // ====================== HOME BUTTON ======================
        btnHome.setOnClickListener {
            startActivity(Intent(this, Beranda::class.java))
            publishMode("AI")
        }

        // ====================== KAMERA BUTTON ======================
        btnKamera.setOnClickListener {
            startActivity(Intent(this, KontrolKamera::class.java))
            publishMode("CCTV")
        }

    }

    // ====================== FUNGSI PUBLISH MODE ======================
    private fun publishMode(mode: String) {
        if (mqttService?.isMqttConnected() == true) {
            mqttService?.publishMessage("cam/mode", mode)
            Log.d("MQTT_DEBUG", "Publish mode $mode berhasil")
        } else {
            Log.d("MQTT_DEBUG", "MQTT belum connect, menunggu...")
            mqttService?.mqttListener = object : MqttServices.MqttListener {
                override fun onConnected() {
                    mqttService?.publishMessage("cam/mode", mode)
                    Log.d("MQTT_DEBUG", "Publish mode $mode berhasil setelah connect")
                    mqttService?.mqttListener = null
                }
            }
        }
    }

    // ====================== GET DATA MYSQL ======================
    private fun ambilDataDariMySQL() {
        val queue = Volley.newRequestQueue(this)
        val request = StringRequest(apiUrl, { response ->
            try {
                val jsonArray = JSONArray(response)
                listRiwayat.clear()

                for (i in 0 until jsonArray.length()) {
                    val obj: JSONObject = jsonArray.getJSONObject(i)
                    val item = RiwayatItem(
                        filename = obj.optString("filename"),
                        label = obj.optString("label"),
                        waktu = obj.optString("waktu"),
                        suhu = obj.optString("suhu"),
                        kelembapan = obj.optString("kelembapan"),
                        image_url = obj.optString("image_url")
                    )
                    listRiwayat.add(item)
                }

                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, { error ->
            error.printStackTrace()
        })

        queue.add(request)
    }

    private fun hapusDataDariServer(filename: String, position: Int) {
        val queue = Volley.newRequestQueue(this)
        val url = "http://192.168.43.179/api/delete_cam_data.php"

        val stringRequest = object : StringRequest(Method.POST, url,
            { response ->
                try {
                    val json = JSONObject(response)
                    if (json.getBoolean("success")) {
                        adapter.removeItem(position)
                        Toast.makeText(this, "Data berhasil dihapus", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Gagal hapus: ${json.getString("message")}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            { error ->
                error.printStackTrace()
            }) {
            override fun getParams(): MutableMap<String, String> {
                return mutableMapOf("filename" to filename)
            }
        }

        queue.add(stringRequest)
    }


    // ====================== UNBIND MQTT ======================
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
