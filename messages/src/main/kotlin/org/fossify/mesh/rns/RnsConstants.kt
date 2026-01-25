package org.fossify.mesh.rns

object RnsConstants {
    const val MTU = 500
    const val DEFAULT_PER_HOP_TIMEOUT = 6
    const val TRUNCATED_HASH_LENGTH_BITS = 128
    const val NAME_HASH_LENGTH_BITS = 80
    const val IFAC_MIN_SIZE = 1

    val IFAC_SALT = RnsHex.decode("adf54d882c9a9b80771eb4995d702d4a3e733391b2a0f53f416d9f907e55cff8")

    val HEADER_MIN_SIZE = 2 + 1 + (TRUNCATED_HASH_LENGTH_BITS / 8)
    val HEADER_MAX_SIZE = 2 + 1 + (TRUNCATED_HASH_LENGTH_BITS / 8) * 2
    val MDU = MTU - HEADER_MAX_SIZE - IFAC_MIN_SIZE
}
