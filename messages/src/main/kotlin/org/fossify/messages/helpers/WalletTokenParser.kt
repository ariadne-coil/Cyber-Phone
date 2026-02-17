package org.fossify.messages.helpers

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * Very lightweight detection of wallet pay tokens in message bodies.
 *
 * We intentionally keep this heuristic-based (not a full decoder) so it works even when the
 * wallet backend is disabled. The actual validation happens in the Wallet UI.
 */
object WalletTokenParser {
    enum class WalletAction {
        PAY,
        REDEEM,
        PAY_REQUEST,
    }

    data class WalletActionToken(
        val action: WalletAction,
        val token: String,
        val federationIdHint: String? = null,
        val requestId: String? = null,
        val amountSats: Long? = null,
    )

    data class FedimintEcashToken(
        val federationId: String,
        val amountSats: Long,
        val expiresAtEpochSec: Long,
        val notes: String,
        val raw: String,
    )

    data class FedimintPaymentRequest(
        val requestId: String,
        val federationId: String,
        val amountSats: Long,
        val raw: String,
    )

    data class FedimintPaymentInvoiceResponse(
        val requestId: String,
        val federationId: String,
        val amountSats: Long,
        val invoice: String,
        val raw: String,
    )

    data class FedimintPaymentDeniedResponse(
        val requestId: String,
        val federationId: String,
        val amountSats: Long,
        val raw: String,
    )

    private val bolt11Regex = Regex("(?i)(?:lightning:)?(ln(?:bc|tb|bcrt)[0-9a-z]+)")
    private val bech32BtcRegex = Regex("(?i)(?:bitcoin:)?((?:bc1|tb1|bcrt1)[0-9a-z]{20,})")

    // Cyber Phone Fedimint out-of-band token format.
    // CPFM1:<federationId>:<amountSats>:<expiresAtEpochSec>:<base64url(notes)>
    private const val FEDIMINT_ECASH_PREFIX = "CPFM1:"
    // Cyber Phone Lightning invoice wrapper with federation hint.
    // CPINV1:<federationId>:<bolt11Invoice>
    private const val FEDERATION_INVOICE_PREFIX = "CPINV1:"
    // Fedimint payment request / response handshake:
    // Request:   CPREQ1:<requestId>:<federationId>:<amountSats>
    // Approve:   CPREQINV1:<requestId>:<federationId>:<amountSats>:<bolt11Invoice>
    // Deny:      CPREQDENY1:<requestId>:<federationId>:<amountSats>
    private const val FEDIMINT_PAY_REQUEST_PREFIX = "CPREQ1:"
    private const val FEDIMINT_PAY_INVOICE_PREFIX = "CPREQINV1:"
    private const val FEDIMINT_PAY_DENY_PREFIX = "CPREQDENY1:"
    // Lightweight federation sidecar hint for plain invoice messages.
    // CPFED1:<federationId>
    private const val FEDERATION_HINT_PREFIX = "CPFED1:"
    private val fedimintEcashRegex = Regex("(?i)(?:^|\\b)CPFM1:([A-Za-z0-9._\\-]{1,120}):(\\d{1,18}):(\\d{1,18}):([A-Za-z0-9_\\-]+=*)(?:$|\\b)")
    private val federationInvoiceRegex = Regex(
        "(?i)(?:^|\\b)CPINV1:([A-Za-z0-9._\\-]{1,120}):((?:lightning:)?ln(?:bc|tb|bcrt)[0-9a-z]+)(?:$|\\b)"
    )
    private val fedimintPayRequestRegex = Regex(
        "(?i)(?:^|\\b)CPREQ1:([A-Za-z0-9_\\-]{8,64}):([A-Za-z0-9._\\-]{1,120}):(\\d{1,18})(?:$|\\b)"
    )
    private val fedimintPayInvoiceRegex = Regex(
        "(?i)(?:^|\\b)CPREQINV1:([A-Za-z0-9_\\-]{8,64}):([A-Za-z0-9._\\-]{1,120}):(\\d{1,18}):((?:lightning:)?ln(?:bc|tb|bcrt)[0-9a-z]+)(?:$|\\b)"
    )
    private val fedimintPayDenyRegex = Regex(
        "(?i)(?:^|\\b)CPREQDENY1:([A-Za-z0-9_\\-]{8,64}):([A-Za-z0-9._\\-]{1,120}):(\\d{1,18})(?:$|\\b)"
    )
    private val federationHintRegex = Regex("(?i)(?:^|\\b)CPFED1:([A-Za-z0-9._\\-]{1,120})(?:$|\\b)")

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

        val fmPayRequest = parseFedimintPaymentRequest(raw)
        if (fmPayRequest != null) {
            return WalletActionToken(
                action = WalletAction.PAY_REQUEST,
                token = fmPayRequest.raw,
                federationIdHint = fmPayRequest.federationId,
                requestId = fmPayRequest.requestId,
                amountSats = fmPayRequest.amountSats,
            )
        }

        val fmPayInvoice = parseFedimintPaymentInvoiceResponse(raw)
        if (fmPayInvoice != null) {
            return WalletActionToken(
                action = WalletAction.PAY,
                token = fmPayInvoice.invoice,
                federationIdHint = fmPayInvoice.federationId,
                requestId = fmPayInvoice.requestId,
                amountSats = fmPayInvoice.amountSats,
            )
        }

        val wrappedInvoice = parseFederationInvoiceToken(raw)
        if (wrappedInvoice != null) {
            return WalletActionToken(
                action = WalletAction.PAY,
                token = wrappedInvoice.second,
                federationIdHint = wrappedInvoice.first,
            )
        }

        val pay = findPayToken(raw)
        if (!pay.isNullOrBlank()) {
            return WalletActionToken(
                action = WalletAction.PAY,
                token = pay,
                federationIdHint = parseFederationHint(raw),
            )
        }

        return null
    }

    fun buildLightningInvoiceMessage(
        invoice: String,
        federationId: String,
        federationName: String? = null,
    ): String {
        val cleanedInvoice = cleanToken(invoice)
        if (cleanedInvoice.isBlank()) return ""
        val fedId = federationId.trim().ifBlank { "unknown" }
        val fedLine = federationName?.trim().takeIf { !it.isNullOrBlank() }?.let {
            "Federation: $it ($fedId)"
        } ?: "Federation: $fedId"
        return buildString {
            append("lightning:")
            append(cleanedInvoice)
            append('\n')
            append(fedLine)
            append('\n')
            append(FEDERATION_HINT_PREFIX)
            append(fedId)
        }
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

    fun newFedimintPaymentRequestId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(24)
    }

    fun buildFedimintPaymentRequestMessage(
        requestId: String,
        federationId: String,
        amountSats: Long,
        federationName: String? = null,
    ): String {
        val req = requestId.trim().ifBlank { newFedimintPaymentRequestId() }
        val fed = federationId.trim().ifBlank { "unknown" }
        val amount = amountSats.coerceAtLeast(0L)
        val fedLine = federationName?.trim().takeIf { !it.isNullOrBlank() }?.let {
            "Federation: $it ($fed)"
        } ?: "Federation: $fed"
        return buildString {
            append("Payment request: ")
            append(amount)
            append(" sats")
            append('\n')
            append(fedLine)
            append('\n')
            append(FEDIMINT_PAY_REQUEST_PREFIX)
            append(req)
            append(':')
            append(fed)
            append(':')
            append(amount)
        }
    }

    fun buildFedimintPaymentInvoiceResponseMessage(
        requestId: String,
        federationId: String,
        amountSats: Long,
        invoice: String,
        federationName: String? = null,
    ): String {
        val req = requestId.trim().ifBlank { return "" }
        val fed = federationId.trim().ifBlank { "unknown" }
        val amount = amountSats.coerceAtLeast(0L)
        val cleanedInvoice = cleanToken(invoice)
        if (cleanedInvoice.isBlank()) return ""
        val fedLine = federationName?.trim().takeIf { !it.isNullOrBlank() }?.let {
            "Federation: $it ($fed)"
        } ?: "Federation: $fed"
        return buildString {
            append("Payment request approved")
            append('\n')
            append("lightning:")
            append(cleanedInvoice)
            append('\n')
            append(fedLine)
            append('\n')
            append(FEDIMINT_PAY_INVOICE_PREFIX)
            append(req)
            append(':')
            append(fed)
            append(':')
            append(amount)
            append(':')
            append(cleanedInvoice)
        }
    }

    fun buildFedimintPaymentDeniedMessage(
        requestId: String,
        federationId: String,
        amountSats: Long,
        federationName: String? = null,
    ): String {
        val req = requestId.trim().ifBlank { return "" }
        val fed = federationId.trim().ifBlank { "unknown" }
        val amount = amountSats.coerceAtLeast(0L)
        val fedLine = federationName?.trim().takeIf { !it.isNullOrBlank() }?.let {
            "Federation: $it ($fed)"
        } ?: "Federation: $fed"
        return buildString {
            append("Payment request denied")
            append('\n')
            append(fedLine)
            append('\n')
            append(FEDIMINT_PAY_DENY_PREFIX)
            append(req)
            append(':')
            append(fed)
            append(':')
            append(amount)
        }
    }

    fun parseFedimintPaymentRequest(text: String): FedimintPaymentRequest? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val match = fedimintPayRequestRegex.find(raw) ?: return null
        val requestId = match.groupValues.getOrNull(1).orEmpty().trim().ifBlank { return null }
        val federationId = match.groupValues.getOrNull(2).orEmpty().trim().ifBlank { return null }
        val amountSats = match.groupValues.getOrNull(3)?.toLongOrNull() ?: return null
        if (amountSats <= 0L) return null
        return FedimintPaymentRequest(
            requestId = requestId,
            federationId = federationId,
            amountSats = amountSats,
            raw = match.value.trim(),
        )
    }

    fun parseFedimintPaymentInvoiceResponse(text: String): FedimintPaymentInvoiceResponse? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val match = fedimintPayInvoiceRegex.find(raw) ?: return null
        val requestId = match.groupValues.getOrNull(1).orEmpty().trim().ifBlank { return null }
        val federationId = match.groupValues.getOrNull(2).orEmpty().trim().ifBlank { return null }
        val amountSats = match.groupValues.getOrNull(3)?.toLongOrNull() ?: return null
        if (amountSats <= 0L) return null
        val invoice = cleanToken(match.groupValues.getOrNull(4).orEmpty())
        if (invoice.isBlank()) return null
        return FedimintPaymentInvoiceResponse(
            requestId = requestId,
            federationId = federationId,
            amountSats = amountSats,
            invoice = invoice,
            raw = match.value.trim(),
        )
    }

    fun parseFedimintPaymentDeniedResponse(text: String): FedimintPaymentDeniedResponse? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val match = fedimintPayDenyRegex.find(raw) ?: return null
        val requestId = match.groupValues.getOrNull(1).orEmpty().trim().ifBlank { return null }
        val federationId = match.groupValues.getOrNull(2).orEmpty().trim().ifBlank { return null }
        val amountSats = match.groupValues.getOrNull(3)?.toLongOrNull() ?: return null
        if (amountSats <= 0L) return null
        return FedimintPaymentDeniedResponse(
            requestId = requestId,
            federationId = federationId,
            amountSats = amountSats,
            raw = match.value.trim(),
        )
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

    private fun parseFederationInvoiceToken(text: String): Pair<String, String>? {
        val match = federationInvoiceRegex.find(text) ?: return null
        val federationId = match.groupValues.getOrNull(1).orEmpty().trim().ifBlank { return null }
        val invoiceToken = match.groupValues.getOrNull(2).orEmpty()
        val cleanedInvoice = cleanToken(invoiceToken)
        if (cleanedInvoice.isBlank()) return null
        return federationId to cleanedInvoice
    }

    private fun parseFederationHint(text: String): String? {
        val match = federationHintRegex.find(text) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
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
