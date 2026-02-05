package org.fossify.mesh.ble

object MeshBleState {
    @Volatile
    private var bluetoothEnabled = false

    @Volatile
    private var active = false

    @Volatile
    private var connections = 0

    @Volatile
    private var lastRxMs = 0L

    @Volatile
    private var lastTxMs = 0L

    fun setBluetoothEnabled(value: Boolean) {
        bluetoothEnabled = value
    }

    fun isBluetoothEnabled(): Boolean = bluetoothEnabled

    fun setActive(value: Boolean) {
        active = value
    }

    fun isActive(): Boolean = active

    fun setConnections(value: Int) {
        connections = value
    }

    fun getConnections(): Int = connections

    fun markRx() {
        lastRxMs = System.currentTimeMillis()
    }

    fun markTx() {
        lastTxMs = System.currentTimeMillis()
    }

    fun getLastRxMs(): Long = lastRxMs

    fun getLastTxMs(): Long = lastTxMs
}

