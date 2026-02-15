package org.fossify.messages.helpers

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Very lightweight detection of wallet pay tokens in message bodies.
 *
 * We intentionally keep this heuristic-based (not a full decoder) so it works even when the
 * wallet backend is disabled. The actual validation happens in the Wallet UI.
 */
object WalletTokenParser {
    enum class WalletAction {
        PAY,
        REDEEM
    }

    data class WalletActionToken(
        val action: WalletAction,
        val token: String,
    )

    data class FedimintEcashToken(
        val federationId: String,
        val amountSats: Long,
        val expiresAtEpochSec: Long,
        val notes: String,
        val raw: String,
    )

    private val bolt11Regex = Regex("(?i)(?:lightning:)?(ln(?:bc|tb|bcrt)[0-9a-z]+)")
    private val bech32BtcRegex = Regex("(?i)(?:bitcoin:)?((?:bc1|tb1|bcrt1)[0-9a-z]{20,})")

    // Cyber Phone Fedimint out-of-band token format.
    // CPFM1:<federationId>:<amountSats>:<expiresAtEpochSec>:<base64url(notes)>
    private const val FEDIMINT_ECASH_PREFIX = "CPFM1:"
    private val fedimintEcashRegex = Regex("(?i)(?:^|\\b)CPFM1:([A-Za-z0-9._\\-]{1,120}):(\\d{1,18}):(\\d{1,18}):([A-Za-z0-9_\\-]+=*)(?:$|\\b)")

    fun findPayToken(text: String): String? {
        val raw = text.trim()
        if (raw.isBlank()) return null

        // 1) BOLT11 invoice (optionally prefixed with "lightning:").
        bolt11Regex.find(raw)?.groupValues?.getOrNull(1)?.let { candidate ->
            val cleaned = cleanToken(candidate)
            if (cleaned.isNotBlank()) return cleaned
        }

        // 2) Bitcoin bech32 address (optionally "bitcoin:" URI).
        // If a BIP21 URI is present, strip query params.
        bech32BtcRegex.find(raw)?.groupValues?.getOrNull(1)?.let { candidate ->
            val cleaned = cleanToken(candidate)
            if (cleaned.isNotBlank()) return cleaned
        }

        return null
    }

    fun findActionToken(text: String): WalletActionToken? {
        val raw = text.trim()
        if (raw.isBlank()) return null

        // Prefer redeemable ecash tokens over pay tokens.
        val fm = parseFedimintEcashToken(raw)
        if (fm != null) {
            return WalletActionToken(action = WalletAction.REDEEM, token = fm.raw)
        }

        val pay = findPayToken(raw)
        if (!pay.isNullOrBlank()) {
            return WalletActionToken(action = WalletAction.PAY, token = pay)
        }

        return null
    }

    fun buildFedimintEcashMessage(
        federationId: String,
        amountSats: Long,
        expiresAtEpochSec: Long,
        notes: String,
    ): String {
        val fed = federationId.trim().ifBlank { "unknown" }
        val amount = amountSats.coerceAtLeast(0L)
        val exp = expiresAtEpochSec.coerceAtLeast(0L)
        val notesB64 = encodeBase64UrlNoPadding(notes)
        return "$FEDIMINT_ECASH_PREFIX$fed:$amount:$exp:$notesB64"
    }

    fun parseFedimintEcashToken(text: String): FedimintEcashToken? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val match = fedimintEcashRegex.find(raw) ?: return null

        val federationId = match.groupValues.getOrNull(1).orEmpty().trim()
        val amountSats = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return null
        val expiresAt = match.groupValues.getOrNull(3)?.toLongOrNull() ?: return null
        val notesB64 = match.groupValues.getOrNull(4).orEmpty().trim()
        val notes = decodeBase64Url(notesB64) ?: return null

        return FedimintEcashToken(
            federationId = federationId,
            amountSats = amountSats,
            expiresAtEpochSec = expiresAt,
            notes = notes,
            raw = match.value.trim(),
        )
    }

    private fun cleanToken(token: String): String {
        var t = token.trim()
        if (t.startsWith("lightning:", ignoreCase = true)) t = t.substringAfter("lightning:", "")
        if (t.startsWith("bitcoin:", ignoreCase = true)) t = t.substringAfter("bitcoin:", "")
        t = t.substringBefore("?") // bip21 params
        t = t.trim().trimEnd('.', ',', ')', ']', '}', ';', ':', '!')
        return t
    }

    private fun encodeBase64UrlNoPadding(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun decodeBase64Url(b64Url: String): String? {
        val cleaned = b64Url.trim()
        if (cleaned.isBlank()) return null
        val padded = padBase64(cleaned)
        return try {
            val bytes = Base64.getUrlDecoder().decode(padded)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun padBase64(input: String): String {
        // Android's Base64 decoder is tolerant in many cases, but padding to 4 bytes makes it reliable.
        val mod = input.length % 4
        return if (mod == 0) input else input + "=".repeat(4 - mod)
    }
}
