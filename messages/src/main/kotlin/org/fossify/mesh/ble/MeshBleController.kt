package org.fossify.mesh.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MeshBleController(
    private val context: Context,
    private val onPayload: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "MeshBle"
        val SERVICE_UUID: UUID = UUID.fromString("3c2c4a22-4b5f-4f76-9e6f-48c46c2a9df1")
        val CHAR_UUID: UUID = UUID.fromString("7d3b9e5b-0b9c-4a7f-b2e6-4bd0d64d3e3b")
        private const val MAX_CHUNK = 180
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private val connections = ConcurrentHashMap<String, BluetoothGatt>()
    private val writeChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val buffers = ConcurrentHashMap<String, ByteBuffer>()
    private val thread = HandlerThread("mesh-ble").apply { start() }
    private val handler = Handler(thread.looper)

    fun start() {
        if (adapter == null || !adapter.isEnabled) return
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        startServer()
        startAdvertise()
        startScan()
    }

    fun stop() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        connections.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        connections.clear()
        writeChars.clear()
        buffers.clear()
    }

    fun send(raw: ByteArray) {
        val framed = ByteBuffer.allocate(2 + raw.size)
        framed.putShort(raw.size.toShort())
        framed.put(raw)
        val payload = framed.array()
        connections.forEach { (address, gatt) ->
            val characteristic = writeChars[address] ?: return@forEach
            var offset = 0
            while (offset < payload.size) {
                val end = (offset + MAX_CHUNK).coerceAtMost(payload.size)
                val chunk = payload.copyOfRange(offset, end)
                characteristic.value = chunk
                gatt.writeCharacteristic(characteristic)
                offset = end
            }
        }
    }

    private fun startServer() {
        gattServer = manager.openGattServer(context, serverCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    private fun startAdvertise() {
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScan() {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            if (connections.containsKey(device.address)) return
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connections[gatt.device.address] = gatt
                gatt.discoverServices()
            } else {
                connections.remove(gatt.device.address)
                writeChars.remove(gatt.device.address)
                buffers.remove(gatt.device.address)
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: return
            writeChars[gatt.device.address] = characteristic
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothGatt.STATE_CONNECTED) {
                buffers.remove(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != CHAR_UUID) return
            val buffer = buffers.getOrPut(device.address) { ByteBuffer.allocate(65536) }
            if (buffer.remaining() < value.size) {
                buffer.clear()
            }
            buffer.put(value)
            buffer.flip()
            while (buffer.remaining() >= 2) {
                buffer.mark()
                val len = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() < len) {
                    buffer.reset()
                    break
                }
                val payload = ByteArray(len)
                buffer.get(payload)
                onPayload(payload)
            }
            val remaining = ByteArray(buffer.remaining())
            buffer.get(remaining)
            buffer.clear()
            buffer.put(remaining)
        }
    }
}
