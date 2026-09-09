package com.rover.gateway

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран настройки + отладочная консоль (лог MQTT/BLE).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var logOffset = 0

    private lateinit var etBroker: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etPeriod: EditText
    private lateinit var etRoverId: EditText
    private lateinit var etTgToken: EditText
    private lateinit var etTgChat: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val needsPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val dp = resources.displayMetrics.density.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp)
        }

        fun label(t: String): TextView = TextView(this).apply {
            text = t; setPadding(0, 4 * dp, 0, 0)
        }

        etBroker = EditText(this).apply { hint = "MQTT broker (ssl://host:8883)" }
        etUser = EditText(this).apply { hint = "MQTT user" }
        etPass = EditText(this).apply { hint = "MQTT pass" }
        etPeriod = EditText(this).apply { hint = "Период телеметрии (мс)" }
        etRoverId = EditText(this).apply { hint = "ID ровера (роver/<id>/...)" }
        etTgToken = EditText(this).apply { hint = "TG bot token" }
        etTgChat = EditText(this).apply { hint = "TG chat id" }
        btnStart = Button(this).apply { text = "▶ Запустить" }
        btnStop = Button(this).apply { text = "■ Стоп" }
        tvStatus = TextView(this).apply { text = "Не запущен" }

        // --- Отладочная консоль ---
        tvLog = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8 * dp, 8 * dp, 8 * dp, 8 * dp)
            setBackgroundColor(0xFF0C0C0C.toInt())
            setTextColor(0xFFCCCCCC.toInt())
            text = ""
        }
        scrollLog = ScrollView(this).apply {
            setBackgroundColor(0xFF0C0C0C.toInt())
            addView(tvLog)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        // --- Верхняя часть: настройки ---
        val settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        settingsPanel.addView(label("MQTT broker"))
        settingsPanel.addView(etBroker)
        settingsPanel.addView(label("Логин"))
        settingsPanel.addView(etUser)
        settingsPanel.addView(label("Пароль"))
        settingsPanel.addView(etPass)
        settingsPanel.addView(label("Период телеметрии (мс)"))
        settingsPanel.addView(etPeriod)
        settingsPanel.addView(label("ID ровера"))
        settingsPanel.addView(etRoverId)
        settingsPanel.addView(label("TG bot token"))
        settingsPanel.addView(etTgToken)
        settingsPanel.addView(label("TG chat id"))
        settingsPanel.addView(etTgChat)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnStop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        settingsPanel.addView(btnRow)
        settingsPanel.addView(tvStatus)

        // --- Консоль-метка ---
        val logLabel = TextView(this).apply {
            text = "── Отладочная консоль (MQTT + BLE) ──"
            textSize = 12f; setPadding(0, 8 * dp, 0, 4 * dp)
        }

        root.addView(settingsPanel)
        root.addView(logLabel)
        root.addView(scrollLog)

        setContentView(root)

        btnStart.setOnClickListener { startGateway() }
        btnStop.setOnClickListener { stopGateway() }

        // Пулл лога каждые 500мс
        handler.post(pollLog)
    }

    private val pollLog = object : Runnable {
        override fun run() {
            val buf = GatewayService.logBuffer
            if (buf.size > logOffset) {
                val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                val sb = StringBuilder(tvLog.text)
                for (i in logOffset until buf.size) {
                    val ts = fmt.format(Date(buf[i].first))
                    sb.appendLine("$ts  ${buf[i].second}")
                }
                logOffset = buf.size
                // Лимит буфера — последние 500 строк
                val lines = sb.split("\n")
                if (lines.size > 500) {
                    tvLog.text = lines.takeLast(500).joinToString("\n")
                } else {
                    tvLog.text = sb.toString()
                }
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            tvStatus.text = prefs.getString("last_status", "Готов") ?: "Готов"
            handler.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        loadPrefs()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(needsPermissions)
        }
    }

    private fun startGateway() {
        savePrefs()
        logOffset = 0
        tvLog.text = ""
        val intent = Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopGateway() {
        val intent = Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_STOP)
        startService(intent)
    }

    private fun savePrefs() {
        prefs.edit()
            .putString(GatewayService.KEY_BROKER, etBroker.text.toString())
            .putString(GatewayService.KEY_USER, etUser.text.toString())
            .putString(GatewayService.KEY_PASS, etPass.text.toString())
            .putString(GatewayService.KEY_SENSOR_PERIOD, etPeriod.text.toString())
            .putString(GatewayService.KEY_ROVER_ID, etRoverId.text.toString())
            .putString(TgNotify.KEY_TG_TOKEN, etTgToken.text.toString())
            .putString(TgNotify.KEY_TG_CHAT, etTgChat.text.toString())
            .apply()
    }

    private fun loadPrefs() {
        etBroker.setText(prefs.getString(GatewayService.KEY_BROKER, "ssl://ed44fbaa0a7a41afaf940381fb18cd2a.s1.eu.hivemq.cloud:8883"))
        etUser.setText(prefs.getString(GatewayService.KEY_USER, "roverCred"))
        etPass.setText(prefs.getString(GatewayService.KEY_PASS, "mqttHIVE!2#"))
        etPeriod.setText(prefs.getString(GatewayService.KEY_SENSOR_PERIOD, "2000"))
        etRoverId.setText(prefs.getString(GatewayService.KEY_ROVER_ID, "demo"))
        etTgToken.setText(prefs.getString(TgNotify.KEY_TG_TOKEN, TgNotify.DEFAULT_TOKEN))
        etTgChat.setText(prefs.getString(TgNotify.KEY_TG_CHAT, TgNotify.DEFAULT_CHAT))
    }
}