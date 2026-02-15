package org.fossify.phone.wallet

/**
 * Wallet policy knobs that define how "high value" transfers behave.
 *
 * These are UX/security tradeoffs, not protocol constraints.
 */
object WalletPolicy {
    // "High value" threshold used for additional UX friction and safety checks.
    const val HIGH_VALUE_USD_THRESHOLD: Double = 50.0

    // Hard cap for a single transfer over wallet/message channels: 100 BTC.
    // 1 BTC = 100_000_000 sats.
    const val MAX_SINGLE_TX_SATS: Long = 10_000_000_000L

    // Keep low-value invoice requests short-lived by default.
    const val LOW_VALUE_INVOICE_EXPIRY_SECONDS: Int = 10 * 60
    const val DEFAULT_INVOICE_EXPIRY_SECONDS: Int = 60 * 60

    fun isAmountWithinSingleTxLimit(sats: Long): Boolean {
        return sats in 1L..MAX_SINGLE_TX_SATS
    }

    fun enforceSingleTxLimitOrNull(sats: Long?): Long? {
        val amount = sats?.takeIf { it > 0L } ?: return null
        return amount.takeIf { isAmountWithinSingleTxLimit(it) }
    }

    fun satsToUsdApprox(sats: Long, usdPerBtc: Double?): Double? {
        if (sats <= 0L) return null
        val rate = usdPerBtc?.takeIf { it > 0.0 } ?: return null
        val btc = sats.toDouble() / 100_000_000.0
        return btc * rate
    }

    fun isHighValue(sats: Long, usdPerBtc: Double?): Boolean? {
        val usd = satsToUsdApprox(sats, usdPerBtc) ?: return null
        return usd >= HIGH_VALUE_USD_THRESHOLD
    }

    fun invoiceExpirySeconds(amountSats: Long?, usdPerBtc: Double?): Int {
        val sats = amountSats?.takeIf { it > 0L } ?: return DEFAULT_INVOICE_EXPIRY_SECONDS
        val high = isHighValue(sats, usdPerBtc)
        return if (high == false) LOW_VALUE_INVOICE_EXPIRY_SECONDS else DEFAULT_INVOICE_EXPIRY_SECONDS
    }
}
