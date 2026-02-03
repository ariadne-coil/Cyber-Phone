package org.fossify.mesh.rns

data class RnsRequestReceipt(
    val requestId: ByteArray,
    val onResponse: ((RnsRequestReceipt) -> Unit)? = null,
    val onFailure: ((RnsRequestReceipt) -> Unit)? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var response: Any? = null
)
