package com.rover.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Собирает телеметрию самого телефона: GPS, гиро/акселерометр, уровень батареи.
 *
 * GPS через системный LocationManager (не play-services!) — работает
 * на любом устройстве, в т.ч. без Google Play Services / GMS.
 */
@SuppressLint("MissingPermission")
class SensorHub(context: Context) : SensorEventListener {

    private val ctx = context.applicationContext
    private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var lastLocation: Location? = null

    // Сырые показания гиро у Телефона
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var ax = 0.0
    private var ay = 0.0
    private var az = 0.0

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
    }

    init {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL)

        startLocation()
    }

    private fun startLocation() {
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) return

        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { locManager.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { locManager.isProviderEnabled(it) },
        )
        providers.forEach { p ->
            runCatching {
                locManager.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper())
                locManager.getLastKnownLocation(p)?.let { lastLocation = it }
            }
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

        val loc = lastLocation
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
        runCatching { locManager.removeUpdates(locListener) }
    }
}