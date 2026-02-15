package org.fossify.messages.helpers

import org.fossify.messages.helpers.WalletTokenParser.WalletAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletTokenParserTest {
    @Test
    fun buildParseFedimintToken_roundTrips() {
        val raw = WalletTokenParser.buildFedimintEcashMessage(
            federationId = "mutinynet",
            amountSats = 1234L,
            expiresAtEpochSec = 1700000000L,
            notes = "hello world",
        )

        val parsed = WalletTokenParser.parseFedimintEcashToken(raw)
        assertNotNull(parsed)
        assertEquals("mutinynet", parsed!!.federationId)
        assertEquals(1234L, parsed.amountSats)
        assertEquals(1700000000L, parsed.expiresAtEpochSec)
        assertEquals("hello world", parsed.notes)
        assertEquals(raw, parsed.raw)
    }

    @Test
    fun parseFedimintToken_rejectsInvalid() {
        // Empty notes payload should be rejected.
        assertNull(WalletTokenParser.parseFedimintEcashToken("CPFM1:abc:1:2:"))
        assertNull(WalletTokenParser.parseFedimintEcashToken("CPFM0:abc:1:2:AA"))
    }

    @Test
    fun findPayToken_detectsBolt11AndCleansPrefixAndPunctuation() {
        val token = WalletTokenParser.findPayToken("lightning:lnbc1abc123).")
        assertEquals("lnbc1abc123", token)
    }

    @Test
    fun findPayToken_detectsBech32AndCleansBip21Params() {
        val addr = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
        val token = WalletTokenParser.findPayToken("bitcoin:$addr?amount=0.001")
        assertEquals(addr, token)
    }

    @Test
    fun findPayToken_prefersBolt11IfPresentInBip21() {
        val addr = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
        val token = WalletTokenParser.findPayToken("bitcoin:$addr?lightning=lnbc1abc123")
        assertEquals("lnbc1abc123", token)
    }

    @Test
    fun findActionToken_prefersRedeemOverPay() {
        val redeem = WalletTokenParser.buildFedimintEcashMessage(
            federationId = "mutinynet",
            amountSats = 1L,
            expiresAtEpochSec = 1L,
            notes = "n",
        )
        val mixed = "$redeem lightning:lnbc1abc"
        val act = WalletTokenParser.findActionToken(mixed)
        assertNotNull(act)
        assertEquals(WalletAction.REDEEM, act!!.action)
        assertTrue(act.token.startsWith("CPFM1:", ignoreCase = true))
    }
}
