package com.rover.gateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Экран настройки: брокер MQTT, ID ровера, кнопки запуск/стоп.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // Порядок: broker, user, pass, period, rover_id
    private lateinit var etBroker: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etPeriod: EditText
    private lateinit var etRoverId: EditText
    private lateinit var tvStatus: TextView
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

        // Простой LinearLayout-UI (без Compose)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        fun label(t: String): TextView = TextView(this).apply { text = t }

        etBroker = EditText(this).apply { hint = "Брокер MQTT (tcp://host:1883)" }
        etUser = EditText(this).apply { hint = "Логин (пусто = без auth)" }
        etPass = EditText(this).apply { hint = "Пароль" }
        etPeriod = EditText(this).apply { hint = "Период телеметрии, мс" }
        etRoverId = EditText(this).apply { hint = "ID ровера (роver/<id>/...)" }

        btnStart = Button(this).apply { text = "Запустить" }
        btnStop = Button(this).apply { text = "Остановить" }
        tvStatus = TextView(this).apply { text = "Не запущен" }

        root.addView(label("Брокер MQTT"))
        root.addView(etBroker)
        root.addView(label("Логин"))
        root.addView(etUser)
        root.addView(label("Пароль"))
        root.addView(etPass)
        root.addView(label("Период телеметрии (мс)"))
        root.addView(etPeriod)
        root.addView(label("ID ровера"))
        root.addView(etRoverId)
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(tvStatus)
        setContentView(root)

        btnStart.setOnClickListener { startGateway() }
        btnStop.setOnClickListener { stopGateway() }
    }

    override fun onResume() {
        super.onResume()
        loadPrefs()
        tvStatus.text = prefs.getString("last_status", "Готов") ?: "Готов"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(needsPermissions)
        }
    }

    private fun startGateway() {
        savePrefs()
        val intent = Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        tvStatus.text = "Запуск..."
    }

    private fun stopGateway() {
        val intent = Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_STOP)
        startService(intent)
        tvStatus.text = "Остановлен"
    }

    private fun savePrefs() {
        prefs.edit()
            .putString(GatewayService.KEY_BROKER, etBroker.text.toString())
            .putString(GatewayService.KEY_USER, etUser.text.toString())
            .putString(GatewayService.KEY_PASS, etPass.text.toString())
            .putString(GatewayService.KEY_SENSOR_PERIOD, etPeriod.text.toString())
            .putString(GatewayService.KEY_ROVER_ID, etRoverId.text.toString())
            .apply()
    }

    private fun loadPrefs() {
        etBroker.setText(prefs.getString(GatewayService.KEY_BROKER, "tcp://broker.hivemq.com:1883"))
        etUser.setText(prefs.getString(GatewayService.KEY_USER, ""))
        etPass.setText(prefs.getString(GatewayService.KEY_PASS, ""))
        etPeriod.setText(prefs.getString(GatewayService.KEY_SENSOR_PERIOD, "2000"))
        etRoverId.setText(prefs.getString(GatewayService.KEY_ROVER_ID, "demo"))
    }

    companion object {
        fun lastStatus(context: Context): String =
            context.getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("last_status", "Не запущен") ?: "Не запущен"
    }
}