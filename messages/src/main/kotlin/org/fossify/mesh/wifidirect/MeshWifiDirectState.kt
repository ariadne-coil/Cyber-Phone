package org.fossify.mesh.wifidirect

object MeshWifiDirectState {
    @Volatile
    private var ssid: String? = null
    @Volatile
    private var passphrase: String? = null
    @Volatile
    private var groupOwner: Boolean = false
    @Volatile
    private var updatedAt: Long = 0L

    fun update(ssid: String?, passphrase: String?, isGroupOwner: Boolean) {
        this.ssid = ssid
        this.passphrase = passphrase
        this.groupOwner = isGroupOwner
        this.updatedAt = System.currentTimeMillis()
    }

    fun clear() {
        ssid = null
        passphrase = null
        groupOwner = false
        updatedAt = 0L
    }

    fun getSsid(): String? = ssid

    fun getPassphrase(): String? = passphrase

    fun isGroupOwner(): Boolean = groupOwner

    fun getUpdatedAt(): Long = updatedAt
}
