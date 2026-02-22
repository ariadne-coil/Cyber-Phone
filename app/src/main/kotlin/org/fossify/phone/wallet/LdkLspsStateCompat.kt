package org.fossify.phone.wallet

/**
 * Compatibility shim for LSPS payment states across ldk-node versions.
 *
 * ldk-node 0.6.x exposed PaymentState while 0.7.x renamed it to Lsps1PaymentState.
 * Both are enums with comparable semantic values, but the type name changed.
 *
 * This adapter deliberately works on Any? to avoid hard dependencies on a specific enum type.
 */
internal object LdkLspsStateCompat {
    private val expectPaymentStates = setOf("EXPECT_PAYMENT", "EXPECTPAYMENT")
    private val refundedStates = setOf("REFUNDED", "REFUND")

    fun isExpectPayment(state: Any?): Boolean {
        val normalized = normalizeStateName(state) ?: return false
        return normalized in expectPaymentStates ||
            normalized.contains("EXPECT_PAYMENT") ||
            normalized.contains("EXPECTPAYMENT")
    }

    fun isRefunded(state: Any?): Boolean {
        val normalized = normalizeStateName(state) ?: return false
        return normalized in refundedStates ||
            normalized.contains("REFUNDED") ||
            normalized.endsWith("_REFUND")
    }

    private fun normalizeStateName(state: Any?): String? {
        val raw = when (state) {
            null -> return null
            is Enum<*> -> state.name
            else -> state.toString()
        }.trim()

        if (raw.isEmpty()) return null

        return raw
            .replace('-', '_')
            .replace(' ', '_')
            .uppercase()
    }
}
