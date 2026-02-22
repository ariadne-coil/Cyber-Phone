package org.fossify.mesh.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.fossify.mesh.ble.MeshBleState

@SuppressLint("MissingPermission")
class MeshBleController(
    private val context: Context,
    private val onPayload: (ByteArray) -> Unit,
    private val onPeerConnected: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "MeshBle"
        val SERVICE_UUID: UUID = UUID.fromString("3c2c4a22-4b5f-4f76-9e6f-48c46c2a9df1")
        val CHAR_UUID: UUID = UUID.fromString("7d3b9e5b-0b9c-4a7f-b2e6-4bd0d64d3e3b")
        // BLE defaults to MTU=23 (payload=20). We request larger MTU, but must always work with default.
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private val connections = ConcurrentHashMap<String, BluetoothGatt>()
    private val writeChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val buffers = ConcurrentHashMap<String, ByteBuffer>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val sendQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val sending = ConcurrentHashMap<String, AtomicBoolean>()
    private val thread = HandlerThread("mesh-ble").apply { start() }
    private val handler = Handler(thread.looper)

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canScan(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    }

    private fun canAdvertise(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    }

    private fun canConnect(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun start() {
        MeshBleState.setBluetoothEnabled(adapter?.isEnabled == true)
        if (adapter == null || !adapter.isEnabled) {
            MeshBleState.setActive(false)
            MeshBleState.setConnections(0)
            return
        }

        advertiser = if (canAdvertise()) adapter.bluetoothLeAdvertiser else null
        scanner = if (canScan()) adapter.bluetoothLeScanner else null

        if (canConnect()) {
            startServer()
        }
        if (advertiser != null) {
            startAdvertise()
        }
        if (scanner != null) {
            startScan()
        }

        val active = canConnect() && (advertiser != null || scanner != null)
        MeshBleState.setActive(active)
    }

    fun stop() {
        MeshBleState.setActive(false)
        MeshBleState.setConnections(0)
        try {
            if (canScan()) {
                scanner?.stopScan(scanCallback)
            }
        } catch (_: Exception) {
        }
        try {
            if (canAdvertise()) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (_: Exception) {
        }
        try {
            if (canConnect()) {
                gattServer?.close()
            }
        } catch (_: Exception) {
        }
        connections.values.forEach {
            try {
                if (canConnect()) {
                    it.close()
                }
            } catch (_: Exception) {
            }
        }
        connections.clear()
        writeChars.clear()
        buffers.clear()
        mtus.clear()
        sendQueues.clear()
        sending.clear()
    }

    fun send(raw: ByteArray) {
        MeshBleState.markTx()
        val framed = ByteBuffer.allocate(2 + raw.size)
        framed.putShort(raw.size.toShort())
        framed.put(raw)
        val payload = framed.array()
        connections.forEach { (address, gatt) ->
            val characteristic = writeChars[address] ?: return@forEach
            val mtu = (mtus[address] ?: DEFAULT_MTU).coerceAtLeast(DEFAULT_MTU)
            val maxChunk = (mtu - ATT_OVERHEAD).coerceAtLeast(20)
            var offset = 0
            val queue = sendQueues.getOrPut(address) { ArrayDeque() }
            synchronized(queue) {
                while (offset < payload.size) {
                    val end = (offset + maxChunk).coerceAtMost(payload.size)
                    queue.addLast(payload.copyOfRange(offset, end))
                    offset = end
                }
            }
            // Start pumping the queue if we aren't already.
            tryWriteNext(address)
        }
    }

    private fun tryWriteNext(address: String) {
        val state = sending.getOrPut(address) { AtomicBoolean(false) }
        if (!state.compareAndSet(false, true)) return
        handler.post { writeNextChunk(address) }
    }

    private fun writeNextChunk(address: String) {
        val state = sending[address] ?: return
        val gatt = connections[address]
        val characteristic = writeChars[address]
        val queue = sendQueues[address]
        if (gatt == null || characteristic == null || queue == null) {
            state.set(false)
            return
        }

        val chunk = synchronized(queue) { queue.pollFirst() }
        if (chunk == null) {
            state.set(false)
            return
        }

        characteristic.value = chunk
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = try {
            gatt.writeCharacteristic(characteristic)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            // Put it back and retry later. OEM stacks can be finicky if we hit them too fast.
            synchronized(queue) { queue.addFirst(chunk) }
            handler.postDelayed({ writeNextChunk(address) }, 60L)
        }
    }

    private fun startServer() {
        gattServer = manager.openGattServer(context, serverCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
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
            if (!canConnect()) return
            handler.post {
                try {
                    @Suppress("DEPRECATION")
                    val gatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, false, gattCallback)
                    }
                    if (gatt == null) {
                        return@post
                    }
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connections[gatt.device.address] = gatt
                MeshBleState.setConnections(connections.size)
                mtus[gatt.device.address] = DEFAULT_MTU
                try {
                    onPeerConnected?.invoke()
                } catch (_: Exception) {
                }
                if (!canConnect()) return
                gatt.discoverServices()
                try {
                    gatt.requestMtu(517)
                } catch (_: Exception) {
                }
            } else {
                connections.remove(gatt.device.address)
                MeshBleState.setConnections(connections.size)
                writeChars.remove(gatt.device.address)
                buffers.remove(gatt.device.address)
                mtus.remove(gatt.device.address)
                sendQueues.remove(gatt.device.address)
                sending.remove(gatt.device.address)
                if (canConnect()) {
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(CHAR_UUID) ?: return
            writeChars[gatt.device.address] = characteristic
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= DEFAULT_MTU) {
                mtus[gatt.device.address] = mtu
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // Drive the per-peer send queue.
            val address = gatt.device.address
            val queue = sendQueues[address]
            val state = sending[address]
            if (queue == null || state == null) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Back off slightly on errors to avoid wedging the stack.
                handler.postDelayed({ writeNextChunk(address) }, 120L)
                return
            }
            handler.post { writeNextChunk(address) }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Some devices only show up as "incoming" connections. Connect back so we can also send.
                if (!connections.containsKey(device.address)) {
                    if (!canConnect()) return
                    handler.post {
                        try {
                            @Suppress("DEPRECATION")
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                            } else {
                                device.connectGatt(context, false, gattCallback)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                return
            }
            if (newState != BluetoothProfile.STATE_CONNECTED) {
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
                MeshBleState.markRx()
                onPayload(payload)
            }
            val remaining = ByteArray(buffer.remaining())
            buffer.get(remaining)
            buffer.clear()
            buffer.put(remaining)

            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (_: Exception) {
                }
            }
        }
    }
}
