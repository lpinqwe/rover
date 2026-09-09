package com.rover.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Фоновый мост: MQTT (Интернет) <-> BLE (ESP32-S3).
 * Плюс публикует телеметрию датчиков телефона (GPS/гиро/батарея).
 */
class GatewayService : Service() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var mqtt: MqttClient? = null
    private var ble: BleClient? = null
    private var sensors: SensorHub? = null

    private var seq = 0
    private val topicPrefix: String
        get() = "rover/${prefs.getString(KEY_ROVER_ID, "demo") ?: "demo"}"

    private val sensorPeriodMs: Long
        get() = prefs.getString(KEY_SENSOR_PERIOD, "2000")?.toLongOrNull() ?: 2000

    companion object {
        const val ACTION_START = "com.rover.gateway.START"
        const val ACTION_STOP = "com.rover.gateway.STOP"

        const val KEY_ROVER_ID = "rover_id"
        const val KEY_BROKER = "broker"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
        const val KEY_SENSOR_PERIOD = "sensor_period"

        private const val CHANNEL_ID = "rover_bridge"
        private const val NOTIF_ID = 42

        /** Лог для отладочной консоли в MainActivity. Список (timestamp, message). */
        val logBuffer = mutableListOf<Pair<Long, String>>()

        fun appendLog(tag: String, msg: String) {
            synchronized(logBuffer) {
                logBuffer.add(Pair(System.currentTimeMillis(), "[$tag] $msg"))
                if (logBuffer.size > 2000) logBuffer.removeAt(0)
            }
        }
    }

    private fun statusG(text: String) {
        appendLog("SYS", text)
        handler.post { storeStatus(text) }
    }

    /** Показать и отослать ошибку в ТГ-чат. */
    private fun reportError(text: String) {
        statusG(text)
        TgNotify.report(prefs, "[Rover] $text")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** BLE-телеметрия ESP32 → топик MQTT esptelemetry. */
    private fun bridgeTelemetry(owner: GatewayService): (ByteArray) -> Unit = { data ->
        val t = Protocol.decodeTelemetry(data)
        if (t != null) {
            val m = JSONObject()
            m.put("ts", System.currentTimeMillis())
            m.put("ble_connected", t.connected)
            m.put("tilted", t.tilted)
            m.put("watchdog_stop", t.watchdogStop)
            m.put("battery", t.batteryVolts / 10.0)
            m.put("left_pwm", t.leftPwm)
            m.put("right_pwm", t.rightPwm)
            m.put("mc_temp_c", t.tempC)
            m.put("ack_seq", t.ackSeq)
            m.put("ack_status", t.ackStatus)
            m.put("esp_tilt_deg", t.tiltTenths / 10.0)
            m.put("leg_bits", t.legBits)
            owner.mqtt?.publish("$topicPrefix/esptelemetry", m.toString())
            appendLog("BLE_RX", "bat=${t.batteryVolts / 10.0}V  L=${t.leftPwm}%  R=${t.rightPwm}%  tilt=${t.tiltTenths / 10.0}°  temp=${t.tempC}°C  ack=${t.ackSeq}/${t.ackStatus}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> {
                stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun start() {
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        startForeground(NOTIF_ID, buildNotification("Запуск..."))
        appendLog("SYS", "=== Gateway start ===")

        val broker = prefs.getString(KEY_BROKER, "ssl://ed44fbaa0a7a41afaf940381fb18cd2a.s1.eu.hivemq.cloud:8883") ?: "ssl://ed44fbaa0a7a41afaf940381fb18cd2a.s1.eu.hivemq.cloud:8883"
        val user = prefs.getString(KEY_USER, "roverCred") ?: "roverCred"
        val pass = prefs.getString(KEY_PASS, "mqttHIVE!2#") ?: "mqttHIVE!2#"
        appendLog("SYS", "broker=$broker  user=$user")

        // Датчики телефона
        sensors = SensorHub(this)

        // BLE → мостят в MQTT
        ble = BleClient(this) { statusG(it) }.also { b ->
            b.onTelemetry = bridgeTelemetry(this)
            b.onError = { reportError(it) }
            b.onConnectedChange = { c ->
                mqtt?.publish("$topicPrefix/status", JSONObject().apply {
                    put("ble_connected", c)
                    put("ts", System.currentTimeMillis())
                }.toString())
            }
            b.startScan()
        }

        // MQTT: слушаем команды
        mqtt = MqttClient(broker, "rover-gw-" + (android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "phone"), ::statusG).also { m ->
            m.configure(user, pass)
            m.onMessage = ::handleMqttMessage
            m.onError = { reportError(it) }
            m.onConnectedChange = { c ->
                mqtt?.publish("$topicPrefix/status", JSONObject().apply {
                    put("mqtt_connected", c)
                    put("ts", System.currentTimeMillis())
                }.toString())
            }
            m.connect(listOf("$topicPrefix/cmd", "$topicPrefix/action", "$topicPrefix/config"))
        }

        // Периодическая телеметрия телефона
        handler.post(object : Runnable {
            override fun run() {
                val s = sensors
                if (s != null) {
                    mqtt?.publish("$topicPrefix/sensors", s.buildJson().toString())
                }
                handler.postDelayed(this, sensorPeriodMs)
            }
        })
    }

    private fun handleMqttMessage(topic: String, payload: String) {
        appendLog("MQTT_RX", "$topic: $payload")
        val b = ble ?: return
        if (!b.connected) {
            statusG("MQTT: команда получена, но BLE не подключён")
            return
        }
        val jo = runCatching { JSONObject(payload) }.getOrNull() ?: return

        var cmdType = jo.optString("type", "")
        var cmd: Int
        var data: ByteArray = ByteArray(0)
        var speed = 0
        var steer = 0

        when {
            topic.endsWith("/cmd") || cmdType == "drive" -> {
                cmd = Protocol.CMD_DRIVE
                speed = (jo.optDouble("speed", 0.0) * 100).toInt().coerceIn(-100, 100)
                steer = (jo.optDouble("steer", 0.0) * 100).toInt().coerceIn(-100, 100)
                data = byteArrayOf(speed.toByte(), steer.toByte())
                cmdType = "drive"
            }
            jo.has("speed") && jo.has("steer") -> {
                cmd = Protocol.CMD_DRIVE
                speed = (jo.optDouble("speed", 0.0) * 100).toInt().coerceIn(-100, 100)
                steer = (jo.optDouble("steer", 0.0) * 100).toInt().coerceIn(-100, 100)
                data = byteArrayOf(speed.toByte(), steer.toByte())
            }
            else -> when (cmdType) {
                "stop" -> { cmd = Protocol.CMD_STOP }
                "light" -> { cmd = Protocol.CMD_LIGHT; data = byteArrayOf(if (jo.optBoolean("on").not()) 0 else 1); cmdType = "light:${jo.optBoolean("on")}" }
                "mine" -> { cmd = Protocol.CMD_MINE; data = byteArrayOf(jo.optInt("channel", 0).toByte()) }
                "leg" -> { cmd = Protocol.CMD_LEG; data = byteArrayOf(jo.optInt("channel", 0).toByte(), (jo.optDouble("pos", 0.0) * 100).toInt().toByte()) }
                "ping" -> { cmd = Protocol.CMD_PING }
                "reset" -> { cmd = Protocol.CMD_RESET }
                else -> return
            }
        }

        if (cmdType == "drive" && data.isEmpty()) data = byteArrayOf(speed.toByte(), steer.toByte())

        seq = (seq + 1) and 0xFF
        val packet = when (cmd) {
            Protocol.CMD_DRIVE -> Protocol.driveSeq(cmd, seq, speed, steer)
            Protocol.CMD_STOP, Protocol.CMD_PING, Protocol.CMD_RESET -> Protocol.stopSeq(cmd, seq)
            else -> Protocol.buildCard(cmd, seq, data)
        }

        statusG("MQTT → BLE: ${cmdType} seq=$seq")
        appendLog("MQTT_TX", "${packet.joinToString("") { "%02X".format(it) }} (${packet.size} bytes)  cmd=$cmdType")
        b.writeCommand(packet)
    }

    private fun storeStatus(text: String) {
        prefs.edit().putString("last_status", text).apply()
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        mqtt?.disconnect()
        ble?.close()
        sensors?.stop()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    /* ---------------- Notification ---------------- */

    private fun buildNotification(text: String): Notification {
        val ch = NotificationChannel(
            CHANNEL_ID, "Rover bridge",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val stop = android.content.Intent(this, GatewayService::class.java).setAction(ACTION_STOP)
        val pendingStop = android.app.PendingIntent.getService(
            this, 0, stop, android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rover Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .addAction(0, "Стоп", pendingStop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}