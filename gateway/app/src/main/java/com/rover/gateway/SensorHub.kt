package com.rover.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

/**
 * Собирает телеметрию самого телефона: GPS, гиро/акселерометр, уровень батареи.
 * Периодически формирует JSON-пакет для публикации в MQTT.
 */
@SuppressLint("MissingPermission")
class SensorHub(context: Context) : SensorEventListener {

    private val ctx = context.applicationContext
    private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var lastKnownGps: LocationResult? = null

    // Сырые показания гиро у Телефона
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var ax = 0.0
    private var ay = 0.0
    private var az = 0.0

    private lateinit var fused: FusedLocationProviderClient

    init {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL)

        fused = LocationServices.getFusedLocationProviderClient(ctx)
        startLocation()
    }

    private fun startLocation() {
        val req = LocationRequest.Builder(1000)
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000)
            .setMaxUpdateDelayMillis(2000)
            .build()
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper())
    }

    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastKnownGps = result
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> { gx = e.values[0].toDouble(); gy = e.values[1].toDouble(); gz = e.values[2].toDouble() }
            Sensor.TYPE_ACCELEROMETER -> { ax = e.values[0].toDouble(); ay = e.values[1].toDouble(); az = e.values[2].toDouble() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun batteryPercent(): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /** Угол наклона телефона по акселерометру (градусы, 0 = горизонтально). */
    fun tiltDeg(): Double {
        val mag = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        if (mag < 1e-6) return 0.0
        return kotlin.math.acos((ax / mag).coerceIn(-1.0, 1.0)) * 180.0 / kotlin.math.PI
    }

    fun buildJson(): JSONObject {
        val jo = JSONObject()
        jo.put("v", 1)
        jo.put("ts", System.currentTimeMillis())
        jo.put("tilt_deg", (tiltDeg() * 10).toInt() / 10.0)

        // GPS
        val loc = lastKnownGps?.lastLocation
        if (loc != null) {
            val g = JSONObject()
            g.put("lat", loc.latitude)
            g.put("lon", loc.longitude)
            g.put("alt_m", loc.altitude)
            g.put("acc_m", loc.accuracy)
            g.put("speed_mps", loc.speed)
            g.put("ts_ms", loc.time)
            jo.put("gps", g)
        }

        // Вращение
        val imu = JSONObject()
        imu.put("gx", gx)
        imu.put("gy", gy)
        imu.put("gz", gz)
        imu.put("ax", ax)
        imu.put("ay", ay)
        imu.put("az", az)
        jo.put("imu", imu)

        // Батарея
        jo.put("battery_pct", batteryPercent())

        return jo
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        runCatching { fused.removeLocationUpdates(locCallback) }
    }
}