package org.fossify.mesh.wifiaware

object MeshWifiAwareState {
    @Volatile
    private var active = false

    fun setActive(value: Boolean) {
        active = value
    }

    fun isActive(): Boolean = active
}
