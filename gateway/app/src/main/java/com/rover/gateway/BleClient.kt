package com.rover.gateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BLE-central: находит ровер, подключается, пишет команды, слушает телеметрию.
 * Управление сконфигурировано под firmware (СЕРВИС/CMD/TELEMETRY).
 */
class BleClient(
    context: Context,
    private val status: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val handler = Handler(Looper.getMainLooper())

    var onTelemetry: ((ByteArray) -> Unit)? = null
    var onConnectedChange: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile private var gatt: BluetoothGatt? = null
    private var scanning = false

    @Volatile var connected = false
        private set

    private val listeners = CopyOnWriteArrayList<ConnectionObserver>()

    interface ConnectionObserver {
        fun onConnected()
        fun onDisconnected()
        fun onTelemetry(data: ByteArray)
        fun onStatus(text: String)
    }

    fun addObserver(o: ConnectionObserver) = listeners.add(o)

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /* ---------------- Скан ---------------- */

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            if (name == "ROVER-S3" || name.startsWith("ROVER")) {
                handler.post { stopScan(); connect(result.device) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning || !isBluetoothOn()) return
        scanning = true
        status("BLE: сканирую ROVER...")
        adapter?.bluetoothLeScanner?.startScan(scanCallback)
        handler.postDelayed({ stopScan() }, 15000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    /* ---------------- Подключение ---------------- */

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        status("BLE: подключаюсь к ${device.name}...")
        gatt = device.connectGatt(null, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    handler.post { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    setConnected(false)
                    g.close()
                    gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                status("BLE: не удалось обнаружить сервисы (code $status)")
                onError?.invoke("BLE services discovery failed (code $status)")
                runCatching { g.disconnect() }
                return
            }
            setConnected(true)
            g.let { enableTelemetry(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val data = characteristic.value ?: return
            onTelemetry?.invoke(data)
            listeners.forEach { it.onTelemetry(data) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableTelemetry(g: BluetoothGatt) {
        val svc = g.getService(Protocol.Uuids.SERVICE) ?: run {
            status("BLE: сервис ровера не найден")
            onError?.invoke("BLE: rover service UUID not found on device")
            return
        }
        val c = svc.getCharacteristic(Protocol.Uuids.TELEMETRY) ?: return
        val desc = c.getDescriptor(UuidUtils.CCCD) ?: return
        g.setCharacteristicNotification(c, true)
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(desc)
        status("BLE: подключён, телеметрия активна")
    }

    /* ---------------- Отправка команд ---------------- */

    @SuppressLint("MissingPermission")
    fun writeCommand(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val svc = g.getService(Protocol.Uuids.SERVICE) ?: return false
        val c = svc.getCharacteristic(Protocol.Uuids.CMD) ?: return false
        c.value = data
        return g.writeCharacteristic(c)
    }

    fun setConnected(v: Boolean) {
        connected = v
        onConnectedChange?.invoke(v)
    }

    /* ---------------- Очистка ---------------- */

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        runCatching { gatt?.disconnect() }
        gatt = null
    }
}

object UuidUtils {
    val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}