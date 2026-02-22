package org.fossify.phone.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LdkLspsStateCompatTest {
    private enum class LegacyPaymentState {
        EXPECT_PAYMENT,
        REFUNDED,
    }

    @Test
    fun expectPayment_matchesLegacyAndStringVariants() {
        assertTrue(LdkLspsStateCompat.isExpectPayment(LegacyPaymentState.EXPECT_PAYMENT))
        assertTrue(LdkLspsStateCompat.isExpectPayment("EXPECT_PAYMENT"))
        assertTrue(LdkLspsStateCompat.isExpectPayment("ExpectPayment"))
        assertTrue(LdkLspsStateCompat.isExpectPayment("LSPS1_EXPECT_PAYMENT"))
    }

    @Test
    fun refunded_matchesLegacyAndStringVariants() {
        assertTrue(LdkLspsStateCompat.isRefunded(LegacyPaymentState.REFUNDED))
        assertTrue(LdkLspsStateCompat.isRefunded("REFUNDED"))
        assertTrue(LdkLspsStateCompat.isRefunded("lsps1_refunded"))
        assertTrue(LdkLspsStateCompat.isRefunded("PAYMENT_REFUND"))
    }

    @Test
    fun unknownOrNullStates_areRejected() {
        assertFalse(LdkLspsStateCompat.isExpectPayment(null))
        assertFalse(LdkLspsStateCompat.isExpectPayment("PENDING"))
        assertFalse(LdkLspsStateCompat.isRefunded(null))
        assertFalse(LdkLspsStateCompat.isRefunded("EXPECT_PAYMENT"))
    }
}
