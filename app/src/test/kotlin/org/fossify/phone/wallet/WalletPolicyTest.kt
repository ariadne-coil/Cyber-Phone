package org.fossify.phone.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletPolicyTest {
    @Test
    fun satsToUsdApprox_handlesInvalidInputs() {
        assertNull(WalletPolicy.satsToUsdApprox(0L, 100_000.0))
        assertNull(WalletPolicy.satsToUsdApprox(10_000L, null))
        assertNull(WalletPolicy.satsToUsdApprox(10_000L, 0.0))
    }

    @Test
    fun isHighValue_usesUsdThreshold() {
        // At $100k/BTC: 10k sats ~= $10 (low), 50000 sats ~= $50 (high).
        assertFalse(WalletPolicy.isHighValue(10_000L, 100_000.0)!!)
        assertTrue(WalletPolicy.isHighValue(50_000L, 100_000.0)!!)
    }

    @Test
    fun invoiceExpirySeconds_lowValueUsesShortExpiry() {
        val expiry = WalletPolicy.invoiceExpirySeconds(amountSats = 10_000L, usdPerBtc = 100_000.0)
        assertEquals(WalletPolicy.LOW_VALUE_INVOICE_EXPIRY_SECONDS, expiry)
    }

    @Test
    fun invoiceExpirySeconds_highOrUnknownUsesDefault() {
        val high = WalletPolicy.invoiceExpirySeconds(amountSats = 1_000_000L, usdPerBtc = 100_000.0)
        assertEquals(WalletPolicy.DEFAULT_INVOICE_EXPIRY_SECONDS, high)

        val unknown = WalletPolicy.invoiceExpirySeconds(amountSats = 10_000L, usdPerBtc = null)
        assertEquals(WalletPolicy.DEFAULT_INVOICE_EXPIRY_SECONDS, unknown)
    }
}

