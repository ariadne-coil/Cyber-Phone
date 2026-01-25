package org.fossify.mesh.rns

interface RnsInterface {
    val name: String
    fun start()
    fun stop()
    fun send(raw: ByteArray)
}
