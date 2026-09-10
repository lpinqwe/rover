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
    private var bridgeStarted = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // Асинхронные публикации в MQTT, чтобы main-thread (BLE-callback) не касался сети.
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "rover-io").apply { isDaemon = true }
    }

    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private var lastStatusText = ""
    private var lastStatusAt = 0L
    private var tgReports = 0
    private var tgWindowStart = 0L

    private val topicPrefix: String
        get() = "rover/${prefs.getString(KEY_ROVER_ID, "demo") ?: "demo"}"

    private val sensorPeriodMs: Long
        get() = prefs.getString(KEY_SENSOR_PERIOD, "2000")?.toLongOrNull() ?: 2000

    companion object {
        const val ACTION_START = "com.rover.gateway.START"
        const val ACTION_STOP = "com.rover.gateway.STOP"
        const val ACTION_BLE_CMD = "com.rover.gateway.BLE_CMD"

        const val EXTRA_CMD = "cmd"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_STEER = "steer"
        const val EXTRA_ON = "on"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_POS = "pos"

        const val KEY_ROVER_ID = "rover_id"
        const val KEY_BROKER = "broker"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
        const val KEY_SENSOR_PERIOD = "sensor_period"

        const val DEFAULT_BROKER = "ssl://ed44fbaa0a7a41afaf940381fb18cd2a.s1.eu.hivemq.cloud:8883"

        private const val CHANNEL_ID = "rover_bridge"
        private const val NOTIF_ID = 42

        /** Запущен ли мост (нужно для прямой панели управления). */
        @Volatile var running = false
        @Volatile var bleConnected = false
        @Volatile var lastTelemetry: Protocol.Telemetry? = null

        private val crashGuardStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        private var originalUncaughtHandler: Thread.UncaughtExceptionHandler? = null
        private var lastCrashAt = 0L

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
        // Дедуп: одинаковый статус (напр. «подключаюсь к…» в цикле ретраев) пишем один раз в 15с.
        val now = System.currentTimeMillis()
        if (text == lastStatusText && now - lastStatusAt < 15_000) return
        lastStatusText = text
        lastStatusAt = now
        appendLog("SYS", text)
        handler.post { storeStatus(text) }
    }

    /** Публикация в MQTT вне main-thread. Безопасна после стопа моста. */
    private fun pub(block: () -> Unit) {
        runCatching { io.execute { runCatching(block) } }
    }

    /** Транзитные проблемы подключения — только в лог (их чинит самовосстановление). */
    private fun logTransient(text: String) {
        statusG(text)
    }

    /** Серьёзные ошибки: в лог + ТГ, но не чаще 5 раз за 5 минут. */
    private fun reportError(text: String) {
        statusG(text)
        val now = System.currentTimeMillis()
        if (now - tgWindowStart > 300_000) {
            tgReports = 0
            tgWindowStart = now
        }
        if (tgReports >= 5) return
        tgReports++
        TgNotify.report(prefs, "[Rover] $text")
    }

    override fun onCreate() {
        super.onCreate()
        // Репортим краш в ТГ, потом отдаём стандартному обработчику: процесс
        // упадёт, а START_STICKY перезапустит сервис (см. onStartCommand null).
        if (crashGuardStarted.compareAndSet(false, true)) {
            originalUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                runCatching {
                    val now = System.currentTimeMillis()
                    // Кулдаун: при краш-лупе не флудить ТГ повторными CRASH
                    if (now - lastCrashAt > 20_000) {
                        lastCrashAt = now
                        TgNotify.report(getSharedPreferences("cfg", MODE_PRIVATE), "[Rover] CRASH: $e")
                    }
                }
                originalUncaughtHandler?.uncaughtException(thread, e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** BLE-телеметрия ESP32 → топик MQTT esptelemetry. */
    private fun bridgeTelemetry(owner: GatewayService): (ByteArray) -> Unit = { data ->
        val t = Protocol.decodeTelemetry(data)
        if (t != null) {
            lastTelemetry = t
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
            owner.mqtt?.let { mm ->
                val payload = m.toString()
                owner.pub { mm.publish("$topicPrefix/esptelemetry", payload) }
            }
            appendLog("BLE_RX", "bat=${t.batteryVolts / 10.0}V  L=${t.leftPwm}%  R=${t.rightPwm}%  tilt=${t.tiltTenths / 10.0}°  temp=${t.tempC}°C  ack=${t.ackSeq}/${t.ackStatus}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> if (!running) start() // перезапуск ОС после гибели процесса — поднимаем мост заново
            ACTION_START -> start()
            ACTION_STOP -> {
                running = false
                stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_BLE_CMD -> {
                // Прямое управление с телефона по BLE (вкладка «Управление»).
                if (!running) {
                    appendLog("SYS", "Шлюз не запущен — нажми «Запустить»")
                    stopSelf(startId)
                } else {
                    handleLocalCommand(intent)
                }
            }
        }
        return START_STICKY
    }

    private fun start() {
        if (bridgeStarted) return
        bridgeStarted = true
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        running = true
        startForeground(NOTIF_ID, buildNotification("Запуск..."))
        acquireWakeLock()
        appendLog("SYS", "=== Gateway start ===")
        // Всё тяжёлое (подключение MQTT, скан BLE) — в фоне, чтобы не морозить UI.
        thread { startBridge() }
        scheduleSupervisor()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "rover::bridge")
            .apply { acquire(6 * 60 * 60 * 1000L) } // таймаут, чтобы не было утечки
    }

    private val supervisor = object : Runnable {
        override fun run() {
            if (!running) return
            mqtt?.ensureConnected() // Paho сам не ретраит первичный фейл — лечим по таймеру
            handler.postDelayed(this, 5000)
        }
    }

    private fun scheduleSupervisor() {
        handler.removeCallbacks(supervisor)
        handler.postDelayed(supervisor, 5000)
    }

    private fun startBridge() {
        runCatching { runBridge() }.onFailure { t ->
            appendLog("SYS", "ОШИБКА МОСТА: $t")
            reportError("bridge crashed: ${t.message ?: t}")
        }
    }

    private fun runBridge() {
        val broker = prefs.getString(KEY_BROKER, DEFAULT_BROKER) ?: DEFAULT_BROKER
        val user = prefs.getString(KEY_USER, "roverCred") ?: "roverCred"
        val pass = prefs.getString(KEY_PASS, "mqttHIVE!2#") ?: "mqttHIVE!2#"
        appendLog("SYS", "broker=$broker  user=$user")

        // Датчики телефона
        sensors = SensorHub(this)

        // BLE → мостят в MQTT
        ble = BleClient(this) { statusG(it) }.also { b ->
            b.onTelemetry = bridgeTelemetry(this)
            b.onError = { logTransient("BLE: ${it}") }
            b.onConnectedChange = { c ->
                bleConnected = c
                mqtt?.let { mm ->
                    val payload = JSONObject().apply {
                        put("ble_connected", c)
                        put("ts", System.currentTimeMillis())
                    }.toString()
                    pub { mm.publish("$topicPrefix/status", payload) }
                }
            }
            b.startScan()
        }

        // MQTT: слушаем команды
        mqtt = MqttClient(broker, "rover-gw-" + (android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "phone"), ::statusG).also { m ->
            m.configure(user, pass)
            m.onMessage = ::handleMqttMessage
            m.onError = { logTransient("MQTT: ${it}") }
            m.onConnectedChange = { c ->
                mqtt?.let { mm ->
                    val payload = JSONObject().apply {
                        put("mqtt_connected", c)
                        put("ts", System.currentTimeMillis())
                    }.toString()
                    pub { mm.publish("$topicPrefix/status", payload) }
                }
            }
            m.connect(listOf("$topicPrefix/cmd", "$topicPrefix/action", "$topicPrefix/config"))
        }

        // Периодическая телеметрия телефона
        handler.post(object : Runnable {
            override fun run() {
                val s = sensors
                if (s != null) {
                    mqtt?.let { mm ->
                        val payload = s.buildJson().toString()
                        pub { mm.publish("$topicPrefix/sensors", payload) }
                    }
                }
                handler.postDelayed(this, sensorPeriodMs)
            }
        })
    }

    private fun handleMqttMessage(topic: String, payload: String) {
        // Вызывается из Paho-delivery потока — не должен ронять процесс.
        runCatching { doHandleMqtt(topic, payload) }.onFailure {
            appendLog("MQTT_RX", "ошибка обработки: ${it.message}")
        }
    }

    private fun doHandleMqtt(topic: String, payload: String) {
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

        val s = seq.getAndIncrement() and 0xFF
        val packet = when (cmd) {
            Protocol.CMD_DRIVE -> Protocol.driveSeq(cmd, s, speed, steer)
            Protocol.CMD_STOP, Protocol.CMD_PING, Protocol.CMD_RESET -> Protocol.stopSeq(cmd, s)
            else -> Protocol.buildCard(cmd, s, data)
        }

        statusG("MQTT → BLE: ${cmdType} seq=$s")
        appendLog("MQTT_TX", "${packet.joinToString("") { "%02X".format(it) }} (${packet.size} bytes)  cmd=$cmdType")
        b.writeCommand(packet)
    }

    /** Локальная команда с панели управления телефона → сразу в BLE. */
    private fun handleLocalCommand(intent: Intent) {
        val b = ble ?: return
        if (!b.connected) {
            statusG("Телефон: BLE не подключён — команда не отправлена")
            return
        }
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        val s = seq.getAndIncrement() and 0xFF

        val packet: ByteArray?
        var cmdType = cmd
        when (cmd) {
            "drive" -> {
                val speed = intent.getIntExtra(EXTRA_SPEED, 0).coerceIn(-100, 100)
                val steer = intent.getIntExtra(EXTRA_STEER, 0).coerceIn(-100, 100)
                packet = Protocol.driveSeq(Protocol.CMD_DRIVE, s, speed, steer)
                cmdType = "drive(s=$speed,st=$steer)"
            }
            "stop" -> packet = Protocol.stopSeq(Protocol.CMD_STOP, s)
            "light" -> {
                val on = intent.getBooleanExtra(EXTRA_ON, false)
                packet = Protocol.singleSeq(Protocol.CMD_LIGHT, s, if (on) 1 else 0)
                cmdType = if (on) "light:ON" else "light:OFF"
            }
            "mine" -> {
                val ch = intent.getIntExtra(EXTRA_CHANNEL, 0)
                packet = Protocol.singleSeq(Protocol.CMD_MINE, s, ch)
                cmdType = "mine:#$ch"
            }
            "leg" -> {
                val ch = intent.getIntExtra(EXTRA_CHANNEL, 0)
                val pos = intent.getIntExtra(EXTRA_POS, 0).coerceIn(-100, 100)
                packet = Protocol.buildCard(Protocol.CMD_LEG, s, byteArrayOf(ch.toByte(), pos.toByte()))
                cmdType = "leg:$ch pos=$pos"
            }
            "ping" -> packet = Protocol.stopSeq(Protocol.CMD_PING, s)
            "reset" -> packet = Protocol.stopSeq(Protocol.CMD_RESET, s)
            else -> return
        }
        if (packet != null) {
            statusG("Телефон → BLE: $cmdType seq=$s")
            appendLog("LOCAL_TX", "${packet.joinToString("") { "%02X".format(it) }} (${packet.size} bytes)  cmd=$cmdType")
            b.writeCommand(packet)
        }
    }

    private fun storeStatus(text: String) {
        prefs.edit().putString("last_status", text).apply()
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        mqtt?.disconnect()
        ble?.close()
        sensors?.stop()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        bridgeStarted = false
        io.shutdownNow()
    }

    override fun onDestroy() {
        running = false
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