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
 * Самовосстанавливающийся: при обрыве соединения сам инициирует рескан с
 * нарастающей задержкой, пока [active] и BLE включен.
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
    @Volatile private var scanning = false
    private var scanAttempts = 0
    var connected: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                onConnectedChange?.invoke(value)
            }
        }

    @Volatile private var active = false

    private val listeners = CopyOnWriteArrayList<ConnectionObserver>()

    interface ConnectionObserver {
        fun onConnected()
        fun onDisconnected()
        fun onTelemetry(data: ByteArray)
        fun onStatus(text: String)
    }

    fun addObserver(o: ConnectionObserver) = listeners.add(o)

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /* ---------------- Скан/рескан ---------------- */

    private val rescanTask = object : Runnable {
        override fun run() {
            if (!active || connected || scanning || gatt != null) return
            if (!isBluetoothOn()) {
                status("BLE: адаптер выключен, жду включения...")
                handler.postDelayed(this, 3000)
                return
            }
            scanAttempts++
            scanning = true
            status("BLE: сканирую ROVER...")
            adapter?.bluetoothLeScanner?.startScan(scanCallback)
            val timeout = if (scanAttempts <= 1) 15000L else 5000L
            handler.postDelayed({ stopScan() }, timeout)
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Ищем ровер по имени ИЛИ по UUID сервиса (имя Android отдаёт не всегда).
            val name = result.device.name ?: ""
            val svcOk = result.scanRecord?.serviceUuids?.any { it.uuid == Protocol.Uuids.SERVICE } == true
            if (name == "ROVER-S3" || name.startsWith("ROVER") || svcOk) {
                scanAttempts = 0
                handler.post { stopScan(); connect(result.device) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        // не нашли ровер — пробуем снова с нарастающей паузой (до 10с)
        if (active && !connected && gatt == null) {
            val backoff = (scanAttempts * 1000L).coerceAtMost(10_000L)
            handler.postDelayed(rescanTask, backoff)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (active) return
        active = true
        scanAttempts = 0
        handler.post(rescanTask)
    }

    /* ---------------- Подключение ---------------- */

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        status("BLE: подключаюсь к ${device.name}...")
        val g = device.connectGatt(null, false, gattCallback) ?: run {
            status("BLE: connectGatt вернул null, пробую снова...")
            handler.postDelayed(rescanTask, 2000)
            return
        }
        gatt = g
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        // ошибка статуса при «подключении» — считаем обрывом
                        onError?.invoke("BLE connect error (status $status)")
                        g.close()
                        gatt = null
                        scheduleReconnect()
                        return
                    }
                    gatt = g
                    handler.post { g.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connected = false
                    g.close()
                    gatt = null
                    scheduleReconnect()
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
            connected = true
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

    private fun scheduleReconnect() {
        if (!active) return
        if (gatt != null) return
        status("BLE: переподключение через 3с...")
        handler.postDelayed(rescanTask, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun enableTelemetry(g: BluetoothGatt) {
        val svc = g.getService(Protocol.Uuids.SERVICE) ?: run {
            status("BLE: сервис ровера не найден")
            onError?.invoke("BLE: rover service UUID not found on device")
            connected = false
            g.close()
            gatt = null
            scheduleReconnect()
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

    /* ---------------- Очистка ---------------- */

    @SuppressLint("MissingPermission")
    fun close() {
        active = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        runCatching { gatt?.disconnect() }
        gatt = null
        connected = false
    }
}

object UuidUtils {
    val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}