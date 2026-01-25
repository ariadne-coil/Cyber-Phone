package org.fossify.mesh

data class MeshIdentity(
    val publicKey: ByteArray,
    val privateKey: ByteArray?
)
