package org.fossify.phone.wallet

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.fossify.phone.extensions.config
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

object RecurringLnurlManager {
    sealed class RegistrationResult {
        data class Success(
            val recurringPaymentCode: String,
            val zapCapable: Boolean,
        ) : RegistrationResult()

        data class Failure(
            val message: String,
        ) : RegistrationResult()
    }

    private data class RootKeyPair(
        val privateKeyHex: String,
        val publicKeyHex: String,
    )

    private const val LNURL_PROTOCOL = "LNURL"
    private val federationIdRegex = Regex("^[0-9a-fA-F]{64}$")
    private val hexRegex = Regex("^[0-9a-fA-F]+$")
    private val secpParams = SECNamedCurves.getByName("secp256k1")
    private val secpDomain = ECDomainParameters(
        secpParams.curve,
        secpParams.g,
        secpParams.n,
        secpParams.h,
    )
    private val secureRandom = SecureRandom()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun registerLnurlPaycodeBlocking(
        context: Context,
        federation: FederationEntry,
        rotateRootKey: Boolean = false,
    ): RegistrationResult {
        if (!FederationDirectoryManager.isFedimintFederation(federation)) {
            return RegistrationResult.Failure("Selected wallet is not a Fedimint federation.")
        }

        val recurringApiBase = normalizeRecurringdApiBase(federation.recurringdApi)
            ?: return RegistrationResult.Failure("Selected federation does not expose recurring LNURL support.")

        val federationId = federation.id.trim()
        if (!federationIdRegex.matches(federationId)) {
            return RegistrationResult.Failure("Federation ID is not compatible with recurring LNURL registration.")
        }

        val rootKeyPair = loadOrCreateRootKeyPair(
            context = context,
            federationId = federation.id,
            rotateRootKey = rotateRootKey,
        ) ?: return RegistrationResult.Failure("Could not create a recurring payment root key.")

        val endpoint = "$recurringApiBase/lnv1/paycodes"
        val requestPayload = JSONObject().apply {
            put("federation_id", federationId.lowercase(Locale.ROOT))
            put("protocol", LNURL_PROTOCOL)
            put("payment_code_root_key", rootKeyPair.publicKeyHex)
            put("meta", buildLnurlMeta(federation))
        }

        return try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .put(requestPayload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                val bodyText = response.body.string().trim()
                if (!response.isSuccessful) {
                    return RegistrationResult.Failure(parseApiFailureMessage(bodyText, response.code))
                }

                val body = runCatching { JSONObject(bodyText) }.getOrNull()
                    ?: return RegistrationResult.Failure("Recurring payment server returned an invalid response.")

                val recurringPaymentCode = extractRecurringPaymentCode(body)
                if (recurringPaymentCode.isBlank()) {
                    val errorFromBody = parseApiFailureMessage(bodyText, response.code)
                    return RegistrationResult.Failure(
                        errorFromBody.ifBlank { "Recurring payment code was missing in server response." }
                    )
                }

                context.config.setWalletRecurringRootPrivateKeyForFederation(
                    federationId = federation.id,
                    privateKeyHex = rootKeyPair.privateKeyHex,
                )
                val zapCapable = detectZapCapability(recurringPaymentCode)

                RegistrationResult.Success(
                    recurringPaymentCode = recurringPaymentCode,
                    zapCapable = zapCapable,
                )
            }
        } catch (_: IOException) {
            RegistrationResult.Failure("Could not connect to the recurring payment server.")
        } catch (_: Exception) {
            RegistrationResult.Failure("Could not register recurring LNURL payment code.")
        }
    }

    private fun loadOrCreateRootKeyPair(
        context: Context,
        federationId: String,
        rotateRootKey: Boolean,
    ): RootKeyPair? {
        if (!rotateRootKey) {
            val existingPrivateKeyHex = context.config
                .getWalletRecurringRootPrivateKeyForFederation(federationId)
                .trim()
            if (existingPrivateKeyHex.isNotBlank()) {
                deriveRootKeyPair(existingPrivateKeyHex)?.let { return it }
            }
        }

        return generateRootKeyPair()
    }

    private fun buildLnurlMeta(federation: FederationEntry): String {
        val displayName = federation.name.trim().ifBlank { "Cyber Phone" }
        return JSONArray()
            .put(JSONArray().put("text/plain").put(displayName))
            .toString()
    }

    private fun extractRecurringPaymentCode(responseBody: JSONObject): String {
        val direct = responseBody.optString("recurring_payment_code").trim()
        if (direct.isNotBlank()) return direct

        val data = responseBody.optJSONObject("data")
        val nested = data?.optString("recurring_payment_code").orEmpty().trim()
        if (nested.isNotBlank()) return nested

        return ""
    }

    private fun parseApiFailureMessage(body: String, statusCode: Int): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        if (parsed == null) {
            return if (statusCode > 0) {
                "Recurring payment server request failed (HTTP $statusCode)."
            } else {
                "Recurring payment server request failed."
            }
        }

        val directCandidates = listOf(
            parsed.optString("error"),
            parsed.optString("message"),
            parsed.optString("reason"),
            parsed.optString("detail"),
        )
        directCandidates.firstOrNull { it.isNotBlank() }?.trim()?.let { return it }

        val errorObject = parsed.optJSONObject("error")
        if (errorObject != null) {
            listOf(
                errorObject.optString("message"),
                errorObject.optString("reason"),
                errorObject.optString("detail"),
                errorObject.optString("error"),
            ).firstOrNull { it.isNotBlank() }?.trim()?.let { return it }
        }

        val errors = parsed.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val first = errors.opt(0)
            when (first) {
                is String -> if (first.isNotBlank()) return first.trim()
                is JSONObject -> listOf(
                    first.optString("message"),
                    first.optString("reason"),
                    first.optString("detail"),
                    first.optString("error"),
                ).firstOrNull { it.isNotBlank() }?.trim()?.let { return it }
            }
        }

        return if (statusCode > 0) {
            "Recurring payment server request failed (HTTP $statusCode)."
        } else {
            "Recurring payment server request failed."
        }
    }

    private fun normalizeRecurringdApiBase(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
        if (trimmed.isBlank()) {
            return null
        }

        val withScheme = if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        val normalizedPath = parsed.encodedPath.trimEnd('/')
        val normalized = parsed.newBuilder()
            .encodedPath(if (normalizedPath.isBlank()) "/" else normalizedPath)
            .build()
            .toString()

        return normalized.removeSuffix("/")
    }

    private fun detectZapCapability(recurringPaymentCode: String): Boolean {
        val payRequestUrl = resolvePayRequestUrl(recurringPaymentCode) ?: return false
        val endpoint = fetchJson(payRequestUrl) ?: return false
        val allowsNostr = when {
            endpoint.has("allowsNostr") -> endpoint.optBoolean("allowsNostr", false)
            endpoint.has("allows_nostr") -> endpoint.optBoolean("allows_nostr", false)
            else -> false
        }
        val nostrPubkey = endpoint.optString("nostrPubkey")
            .ifBlank { endpoint.optString("nostr_pubkey") }
            .trim()
        val hasNostrPubkey = nostrPubkey.matches(Regex("^[0-9a-fA-F]{64}$"))
        return allowsNostr && hasNostrPubkey
    }

    private fun resolvePayRequestUrl(recurringPaymentCode: String): String? {
        val code = recurringPaymentCode.trim()
            .removePrefix("lightning:")
            .removePrefix("LIGHTNING:")
            .trim()
        if (code.isBlank()) return null

        val lower = code.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("lnurl1") -> decodeLnurlBech32(code)
            lower.startsWith("https://") || lower.startsWith("http://") -> code
            lower.startsWith("lnurlp://") -> "https://${code.substringAfter("://")}"
            code.contains("@") && !code.contains(' ') -> {
                val local = code.substringBefore("@").trim()
                val domain = code.substringAfter("@").trim()
                if (local.isBlank() || domain.isBlank()) null else "https://$domain/.well-known/lnurlp/$local"
            }

            else -> null
        }
    }

    private fun fetchJson(url: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                if (body.isBlank()) return null
                runCatching { JSONObject(body) }.getOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeLnurlBech32(raw: String): String? {
        val value = raw.trim().lowercase(Locale.ROOT)
        if (!value.startsWith("lnurl1")) return null
        val sep = value.lastIndexOf('1')
        if (sep <= 0 || sep + 7 > value.length) return null

        val hrp = value.substring(0, sep)
        val dataPart = value.substring(sep + 1)
        if (dataPart.isEmpty()) return null

        val data = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val c = dataPart[i]
            if (c.code >= bech32Map.size) return null
            val mapped = bech32Map[c.code]
            if (mapped < 0) return null
            data[i] = mapped
        }

        if (!verifyChecksum(hrp, data)) return null
        val payload = data.copyOfRange(0, data.size - 6)
        val bytes = convertBits(payload, fromBits = 5, toBits = 8, pad = false) ?: return null
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        val expanded = expandHrp(hrp)
        val values = IntArray(expanded.size + data.size)
        System.arraycopy(expanded, 0, values, 0, expanded.size)
        System.arraycopy(data, 0, values, expanded.size, data.size)
        return polymod(values) == 1
    }

    private fun expandHrp(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        var idx = 0
        hrp.forEach { c ->
            out[idx++] = c.code shr 5
        }
        out[idx++] = 0
        hrp.forEach { c ->
            out[idx++] = c.code and 31
        }
        return out
    }

    private fun polymod(values: IntArray): Int {
        val generators = intArrayOf(
            0x3b6a57b2,
            0x26508e6d,
            0x1ea119fa,
            0x3d4233dd,
            0x2a1462b3,
        )
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            for (i in generators.indices) {
                if (((top ushr i) and 1) == 1) {
                    chk = chk xor generators[i]
                }
            }
        }
        return chk
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        val out = ArrayList<Byte>()

        for (value in data) {
            if (value < 0 || (value ushr fromBits) != 0) return null
            acc = (acc shl fromBits or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }

        if (pad) {
            if (bits > 0) {
                out.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }

        return out.toByteArray()
    }

    private fun generateRootKeyPair(): RootKeyPair {
        while (true) {
            val privateKeyBytes = ByteArray(32)
            secureRandom.nextBytes(privateKeyBytes)
            val privateKey = BigInteger(1, privateKeyBytes)
            if (privateKey <= BigInteger.ZERO || privateKey >= secpDomain.n) {
                continue
            }

            val publicPoint = FixedPointCombMultiplier()
                .multiply(secpDomain.g, privateKey)
                .normalize()

            return RootKeyPair(
                privateKeyHex = privateKeyBytes.toHexLower(),
                publicKeyHex = publicPoint.getEncoded(true).toHexLower(),
            )
        }
    }

    private fun deriveRootKeyPair(privateKeyHex: String): RootKeyPair? {
        val normalized = privateKeyHex.trim()
        if (normalized.length != 64 || !hexRegex.matches(normalized)) {
            return null
        }

        val privateKeyBytes = normalized.hexToByteArray() ?: return null
        val privateKey = BigInteger(1, privateKeyBytes)
        if (privateKey <= BigInteger.ZERO || privateKey >= secpDomain.n) {
            return null
        }

        val publicPoint = FixedPointCombMultiplier()
            .multiply(secpDomain.g, privateKey)
            .normalize()

        return RootKeyPair(
            privateKeyHex = privateKeyBytes.toHexLower(),
            publicKeyHex = publicPoint.getEncoded(true).toHexLower(),
        )
    }

    private fun String.hexToByteArray(): ByteArray? {
        if (length % 2 != 0 || !hexRegex.matches(this)) {
            return null
        }
        val out = ByteArray(length / 2)
        var i = 0
        while (i < length) {
            val hi = Character.digit(this[i], 16)
            val lo = Character.digit(this[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun ByteArray.toHexLower(): String {
        return buildString(size * 2) {
            for (b in this@toHexLower) {
                append(((b.toInt() ushr 4) and 0x0f).toString(16))
                append((b.toInt() and 0x0f).toString(16))
            }
        }
    }

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val bech32Map = IntArray(128) { -1 }.apply {
        BECH32_CHARSET.forEachIndexed { idx, c ->
            this[c.code] = idx
        }
    }
}
