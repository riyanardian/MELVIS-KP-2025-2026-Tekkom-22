package com.example.melvis.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MqttServices : Service() {

    private val binder = LocalBinder()
    private var mqttClient: MqttClient? = null
    private val brokerUrl = "tcp://test.mosquitto.org:1883"
    private val isConnected = AtomicBoolean(false)
    private val lastPayloadMap = mutableMapOf<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 🔥 Listener untuk Activity
    interface MqttListener {
        fun onConnected()
    }

    var mqttListener: MqttListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): MqttServices = this@MqttServices
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d("MqttServices", "Service created")
        initMqtt()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {}
        Log.d("MqttServices", "Service destroyed")
    }

    // =================== MQTT CALLBACK ===================
    private val mqttCallback = object : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            Log.e("MqttServices", "Connection lost: ${cause?.message}")
            isConnected.set(false)
            scope.launch { reconnectWithBackoff() }
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            val payload = message?.toString() ?: ""
            topic?.let {
                lastPayloadMap[it] = payload

                sendBroadcast(Intent("MQTT_MESSAGE").apply {
                    putExtra("topic", it)
                    putExtra("payload", payload)
                })
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
    }

    // =================== INIT MQTT ===================
    private fun recreateClient() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {}

        // Biarkan broker generate clientId otomatis
        mqttClient = MqttClient(brokerUrl, MqttClient.generateClientId(), MemoryPersistence())
        mqttClient?.setCallback(mqttCallback)
    }

    private fun initMqtt() {
        recreateClient()
        scope.launch { reconnectWithBackoff() }
    }

    // =================== RECONNECT BACKOFF ===================
    private suspend fun reconnectWithBackoff() {
        var attempt = 0
        while (!isConnected.get() && scope.isActive) {
            try {
                if (!isNetworkAvailable()) {
                    delay(2000)
                    continue
                }

                recreateClient()

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = false
                    isCleanSession = true
                    connectionTimeout = 10
                }

                mqttClient?.connect(options)
                isConnected.set(true)
                Log.d("MqttServices", "✅ MQTT CONNECTED")

                // 🔥 Notify Activity
                sendBroadcast(Intent("MQTT_CONNECTED"))
                mqttListener?.onConnected()
                break
            } catch (e: Exception) {
                attempt++
                val backoff = min(30000L, 1000L * (1 shl min(attempt, 6)))
                Log.e("MqttServices", "Reconnect failed (attempt $attempt): ${e.message}. Backoff $backoff ms")
                delay(backoff)
            }
        }
    }

    // =================== NETWORK CHECK ===================
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (_: Exception) {
            false
        }
    }

    // =================== PUBLISH ===================
    fun publishMessage(topic: String, payload: String) {
        if (!isConnected.get()) {
            Log.e("MqttServices", "Cannot publish, MQTT not connected")
            return
        }
        scope.launch {
            try {
                val msg = MqttMessage(payload.toByteArray()).apply {
                    qos = 1
                    isRetained = false
                }
                mqttClient?.publish(topic, msg)
                lastPayloadMap[topic] = payload
                Log.d("MqttServices", "Published to $topic: $payload")
            } catch (e: Exception) {
                Log.e("MqttServices", "Publish error: ${e.message}")
            }
        }
    }

    // =================== SUBSCRIBE ===================
    fun subscribeTopic(topic: String) {
        scope.launch {
            while (!isConnected.get()) delay(100)
            try {
                mqttClient?.subscribe(topic, 1)
                Log.d("MqttServices", "Subscribed to $topic")
            } catch (e: Exception) {
                Log.e("MqttServices", "Subscribe error: ${e.message}")
            }
        }
    }

    fun getLastPayload(topic: String): String = lastPayloadMap[topic] ?: "OFF"
    fun isMqttConnected(): Boolean = isConnected.get()
}
