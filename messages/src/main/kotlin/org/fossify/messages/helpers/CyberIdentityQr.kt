package org.fossify.messages.helpers

/**
 * "Cyber Identity" QR payload.
 *
 * We use vCard because it is widely supported by OEM QR scanners and "Add to contacts" flows,
 * but we also support parsing plain-text tokens (mesh URI, E2E key prefix, BTC address, etc).
 */
data class CyberIdentityPayload(
    val raw: String,
    val meshAddress: String? = null,
    val e2ePublicKeyBase64: String? = null,
    val walletOnchainAddress: String? = null,
    val walletLightningInvoice: String? = null,
)

object CyberIdentityQr {
    private const val VCARD_BEGIN = "BEGIN:VCARD"

    private const val X_MESH = "X-CYBERPHONE-MESH"
    private const val X_E2E = "X-CYBERPHONE-E2EKEY"
    private const val X_BTC = "X-CYBERPHONE-BTC"
    private const val X_LN = "X-CYBERPHONE-LN"

    // Keep regexes intentionally permissive; actual validation happens in the corresponding feature.
    private val e2eRegex = Regex("(?i)${Regex.escape(E2E_KEY_MESSAGE_PREFIX)}([A-Za-z0-9+/=\\-_]+)")
    private val bolt11Regex = Regex("(?i)(?:lightning:)?(ln(?:bc|tb|bcrt)[0-9a-z]+)")
    private val bech32BtcRegex = Regex("(?i)(?:bitcoin:)?((?:bc1|tb1|bcrt1)[0-9a-z]{20,})")

    fun parse(raw: String): CyberIdentityPayload? {
        val text = raw.trim()
        if (text.isBlank()) return null

        var mesh: String? = null
        var e2e: String? = null
        var btc: String? = null
        var ln: String? = null

        // vCard: prefer explicit X- fields first.
        if (text.contains(VCARD_BEGIN, ignoreCase = true)) {
            for (line in unfoldVCard(text)) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith(X_MESH, ignoreCase = true) -> {
                        val candidate = trimmed.substringAfter(':', "").trim()
                        mesh = MeshDiscoveryManager.extractMeshAddress(candidate) ?: mesh
                    }
                    trimmed.startsWith(X_E2E, ignoreCase = true) -> {
                        val candidate = trimmed.substringAfter(':', "").trim()
                        e2e = extractE2ePublicKey(candidate) ?: e2e
                    }
                    trimmed.startsWith(X_BTC, ignoreCase = true) -> {
                        val candidate = trimmed.substringAfter(':', "").trim()
                        btc = extractOnchainAddress(candidate) ?: btc
                    }
                    trimmed.startsWith(X_LN, ignoreCase = true) -> {
                        val candidate = trimmed.substringAfter(':', "").trim()
                        ln = extractBolt11(candidate) ?: ln
                    }
                }
            }
        }

        // Plain-text fallback.
        if (mesh.isNullOrBlank()) mesh = MeshDiscoveryManager.extractMeshAddress(text)
        if (e2e.isNullOrBlank()) e2e = extractE2ePublicKey(text)
        if (btc.isNullOrBlank()) btc = extractOnchainAddress(text)
        if (ln.isNullOrBlank()) ln = extractBolt11(text)

        if (mesh.isNullOrBlank() && e2e.isNullOrBlank() && btc.isNullOrBlank() && ln.isNullOrBlank()) {
            return null
        }

        return CyberIdentityPayload(
            raw = text,
            meshAddress = mesh?.trim()?.takeIf { it.isNotBlank() },
            e2ePublicKeyBase64 = e2e?.trim()?.takeIf { it.isNotBlank() },
            walletOnchainAddress = btc?.trim()?.takeIf { it.isNotBlank() },
            walletLightningInvoice = ln?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    fun buildVCard(
        displayName: String?,
        phoneNumber: String?,
        meshUri: String,
        e2ePublicKeyBase64: String?,
        walletOnchainAddress: String?,
        walletLightningInvoice: String?,
    ): String {
        val fn = (displayName ?: "Cyber Phone").trim().ifBlank { "Cyber Phone" }
        val tel = phoneNumber?.trim().orEmpty().ifBlank { "" }

        val lines = ArrayList<String>(12)
        lines.add("BEGIN:VCARD")
        lines.add("VERSION:3.0")
        lines.add("FN:${escapeVCardValue(fn)}")
        if (tel.isNotBlank()) {
            lines.add("TEL:$tel")
        }

        // Keep mesh in NOTE: as a human-friendly fallback for generic scanners.
        lines.add("NOTE:$meshUri")
        lines.add("$X_MESH:$meshUri")

        val e2e = e2ePublicKeyBase64?.trim().orEmpty()
        if (e2e.isNotBlank()) {
            lines.add("$X_E2E:${E2E_KEY_MESSAGE_PREFIX}$e2e")
        }

        val btc = walletOnchainAddress?.trim().orEmpty()
        if (btc.isNotBlank()) {
            lines.add("$X_BTC:$btc")
        }

        val ln = walletLightningInvoice?.trim().orEmpty()
        if (ln.isNotBlank()) {
            lines.add("$X_LN:$ln")
        }

        lines.add("END:VCARD")
        return lines.joinToString("\n")
    }

    private fun extractE2ePublicKey(text: String): String? {
        val t = text.trim()
        if (t.startsWith(E2E_KEY_MESSAGE_PREFIX, ignoreCase = true)) {
            return t.substringAfter(E2E_KEY_MESSAGE_PREFIX, "").trim().takeIf { it.isNotBlank() }
        }
        return e2eRegex.find(t)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractBolt11(text: String): String? {
        val t = text.trim()
        return bolt11Regex.find(t)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractOnchainAddress(text: String): String? {
        val t = text.trim()
        // If a BIP21 URI is present, strip query params.
        val base = t.substringBefore("?").trim()
        return bech32BtcRegex.find(base)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun unfoldVCard(text: String): List<String> {
        val rawLines = text.replace("\r", "\n").split('\n')
        val lines = ArrayList<String>(rawLines.size)
        var current: StringBuilder? = null
        for (raw in rawLines) {
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                // vCard line folding (continuation)
                current?.append(raw.trimStart())
                continue
            }
            if (current != null) {
                lines.add(current.toString())
            }
            current = StringBuilder(raw)
        }
        if (current != null) {
            lines.add(current.toString())
        }
        return lines
    }

    private fun escapeVCardValue(value: String): String {
        // Minimal escaping for vCard 3.0 values.
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(";", "\\;")
            .replace(",", "\\,")
    }
}

