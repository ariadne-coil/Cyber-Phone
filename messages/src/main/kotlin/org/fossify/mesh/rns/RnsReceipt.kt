package org.fossify.mesh.rns

data class RnsReceipt(
    val packetHash: ByteArray,
    val truncatedHash: ByteArray,
    val destinationHash: ByteArray,
    val createdAt: Long,
    val onDelivered: (() -> Unit)? = null
)
