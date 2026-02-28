package org.fossify.phone.wallet

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.lightningdevkit.ldknode.Bolt11Invoice
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

object LnurlPayManager {
    sealed class ResolveResult {
        data class Success(
            val invoice: String,
            val resolvedAmountSats: Long?,
        ) : ResolveResult()

        data class Failure(
            val errorMessage: String,
        ) : ResolveResult()
    }

    data class WithdrawRequest(
        val callbackUrl: String,
        val k1: String,
        val defaultDescription: String,
        val minWithdrawableMsat: Long,
        val maxWithdrawableMsat: Long,
        val minWithdrawableSats: Long,
        val maxWithdrawableSats: Long,
    )

    sealed class WithdrawResolveResult {
        data class Success(
            val request: WithdrawRequest,
        ) : WithdrawResolveResult()

        data class Failure(
            val errorMessage: String,
        ) : WithdrawResolveResult()
    }

    sealed class WithdrawSubmitResult {
        data object Success : WithdrawSubmitResult()

        data class Failure(
            val errorMessage: String,
        ) : WithdrawSubmitResult()
    }

    private const val TAG_PAY_REQUEST = "payRequest"
    private const val TAG_WITHDRAW_REQUEST = "withdrawRequest"
    private const val STATUS_ERROR = "ERROR"

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val bech32Map = IntArray(128) { -1 }.apply {
        BECH32_CHARSET.forEachIndexed { idx, c ->
            this[c.code] = idx
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun isLnurlPayDestination(raw: String): Boolean {
        val cleaned = stripLightningPrefix(raw.trim())
        if (cleaned.isBlank()) return false
        val lower = cleaned.lowercase(Locale.ROOT)
        return lower.startsWith("lnurl1") ||
            lower.startsWith("lnurlp://") ||
            lower.startsWith("https://") ||
            lower.startsWith("http://") ||
            looksLikeLightningAddress(cleaned)
    }

    fun isLnurlWithdrawDestination(raw: String): Boolean {
        val cleaned = stripLightningPrefix(raw.trim())
        if (cleaned.isBlank()) return false
        val lower = cleaned.lowercase(Locale.ROOT)
        return lower.startsWith("lnurl1") ||
            lower.startsWith("lnurlw://") ||
            lower.startsWith("https://") ||
            lower.startsWith("http://")
    }

    fun resolveInvoiceBlocking(
        rawDestination: String,
        amountSats: Long?,
    ): ResolveResult {
        val cleaned = stripLightningPrefix(rawDestination.trim())
        if (cleaned.isBlank()) {
            return ResolveResult.Failure("Lightning destination is empty.")
        }
        if (!isLnurlPayDestination(cleaned)) {
            return ResolveResult.Failure("Not a Lightning Address or LNURL destination.")
        }

        val initialUrl = destinationToInitialUrl(cleaned) ?: return ResolveResult.Failure(
            "Could not parse LNURL destination."
        )
        val payRequest = fetchJson(initialUrl) ?: return ResolveResult.Failure(
            "Could not fetch LNURL pay endpoint."
        )

        val endpointError = parseLnurlError(payRequest)
        if (!endpointError.isNullOrBlank()) {
            return ResolveResult.Failure(endpointError)
        }

        val tag = payRequest.optString("tag").trim()
        if (!tag.equals(TAG_PAY_REQUEST, ignoreCase = true)) {
            return ResolveResult.Failure("LNURL endpoint does not support payments.")
        }

        val callback = payRequest.optString("callback").trim()
        if (callback.isBlank()) {
            return ResolveResult.Failure("LNURL callback is missing.")
        }
        val callbackUrl = callback.toHttpUrlOrNull()
            ?: return ResolveResult.Failure("LNURL callback URL is invalid.")

        val minMsat = payRequest.optLong("minSendable", -1L)
        val maxMsat = payRequest.optLong("maxSendable", -1L)
        if (minMsat <= 0L || maxMsat <= 0L || maxMsat < minMsat) {
            return ResolveResult.Failure("LNURL amount range is invalid.")
        }

        val requestedMsat = when {
            amountSats != null && amountSats > 0L -> {
                amountSats.saturatingMul(1000L)
            }

            minMsat == maxMsat -> minMsat
            else -> return ResolveResult.Failure("Amount required for Lightning Address/LNURL payment.")
        }

        if (requestedMsat < minMsat || requestedMsat > maxMsat) {
            val minSats = msatToRoundedSats(minMsat)
            val maxSats = msatToRoundedSats(maxMsat)
            return ResolveResult.Failure(
                "Amount is out of range for this LNURL destination (${minSats}..${maxSats} sats)."
            )
        }
        val expectedSats = msatToRoundedSats(requestedMsat)

        val invoiceRequestUrl = callbackUrl.newBuilder()
            .addQueryParameter("amount", requestedMsat.toString())
            .build()
            .toString()

        val invoiceResponse = fetchJson(invoiceRequestUrl) ?: return ResolveResult.Failure(
            "Could not fetch LNURL invoice callback."
        )
        val invoiceError = parseLnurlError(invoiceResponse)
        if (!invoiceError.isNullOrBlank()) {
            return ResolveResult.Failure(invoiceError)
        }

        val invoice = stripLightningPrefix(invoiceResponse.optString("pr").trim())
        if (!LdkWalletManager.isBolt11Invoice(invoice)) {
            return ResolveResult.Failure("LNURL callback did not return a valid BOLT11 invoice.")
        }

        val invoiceAmountSats = parseInvoiceSats(invoice)
        if (invoiceAmountSats != null && invoiceAmountSats > 0L) {
            if (invoiceAmountSats != expectedSats) {
                return ResolveResult.Failure(
                    "LNURL callback returned an invoice amount mismatch."
                )
            }
        }

        return ResolveResult.Success(
            invoice = invoice,
            resolvedAmountSats = invoiceAmountSats ?: expectedSats,
        )
    }

    private fun destinationToInitialUrl(destination: String): String? {
        val raw = destination.trim()
        if (raw.isBlank()) return null

        val lower = raw.lowercase(Locale.ROOT)
        if (lower.startsWith("lnurl1")) {
            return decodeLnurlBech32(raw)
        }
        if (lower.startsWith("lnurlp://")) {
            return "https://${raw.substringAfter("://")}"
        }
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return raw
        }
        if (looksLikeLightningAddress(raw)) {
            val local = raw.substringBefore("@").trim()
            val domain = raw.substringAfter("@").trim()
            if (local.isBlank() || domain.isBlank()) return null
            return "https://$domain/.well-known/lnurlp/$local"
        }
        return null
    }

    fun resolveWithdrawRequestBlocking(rawDestination: String): WithdrawResolveResult {
        val cleaned = stripLightningPrefix(rawDestination.trim())
        if (cleaned.isBlank()) {
            return WithdrawResolveResult.Failure("LNURL withdraw destination is empty.")
        }
        if (!isLnurlWithdrawDestination(cleaned)) {
            return WithdrawResolveResult.Failure("Not an LNURL-withdraw destination.")
        }

        val initialUrl = destinationToInitialWithdrawUrl(cleaned) ?: return WithdrawResolveResult.Failure(
            "Could not parse LNURL-withdraw destination."
        )
        val endpoint = fetchJson(initialUrl) ?: return WithdrawResolveResult.Failure(
            "Could not fetch LNURL-withdraw endpoint."
        )
        val endpointError = parseLnurlError(endpoint)
        if (!endpointError.isNullOrBlank()) {
            return WithdrawResolveResult.Failure(endpointError)
        }

        val tag = endpoint.optString("tag").trim()
        if (!tag.equals(TAG_WITHDRAW_REQUEST, ignoreCase = true)) {
            return WithdrawResolveResult.Failure("LNURL endpoint does not support withdraw.")
        }

        val callback = endpoint.optString("callback").trim()
        if (callback.isBlank()) {
            return WithdrawResolveResult.Failure("LNURL withdraw callback is missing.")
        }
        val callbackUrl = callback.toHttpUrlOrNull()
            ?: return WithdrawResolveResult.Failure("LNURL withdraw callback URL is invalid.")

        val k1 = endpoint.optString("k1").trim()
        if (k1.isBlank()) {
            return WithdrawResolveResult.Failure("LNURL withdraw challenge is missing.")
        }

        val minMsat = endpoint.optLong("minWithdrawable", -1L)
        val maxMsat = endpoint.optLong("maxWithdrawable", -1L)
        if (minMsat <= 0L || maxMsat <= 0L || maxMsat < minMsat) {
            return WithdrawResolveResult.Failure("LNURL withdraw amount range is invalid.")
        }

        val description = endpoint.optString("defaultDescription").trim().ifBlank {
            "LNURL withdraw"
        }
        val request = WithdrawRequest(
            callbackUrl = callbackUrl.toString(),
            k1 = k1,
            defaultDescription = description,
            minWithdrawableMsat = minMsat,
            maxWithdrawableMsat = maxMsat,
            minWithdrawableSats = msatToRoundedSats(minMsat),
            maxWithdrawableSats = msatToRoundedSats(maxMsat),
        )
        return WithdrawResolveResult.Success(request)
    }

    fun submitWithdrawInvoiceBlocking(
        request: WithdrawRequest,
        amountSats: Long,
        invoice: String,
    ): WithdrawSubmitResult {
        val sats = amountSats.coerceAtLeast(0L)
        if (sats <= 0L) {
            return WithdrawSubmitResult.Failure("Withdraw amount is invalid.")
        }
        val requestedMsat = sats.saturatingMul(1000L)
        if (requestedMsat < request.minWithdrawableMsat || requestedMsat > request.maxWithdrawableMsat) {
            return WithdrawSubmitResult.Failure(
                "Amount is out of range (${request.minWithdrawableSats}..${request.maxWithdrawableSats} sats)."
            )
        }
        val cleanedInvoice = stripLightningPrefix(invoice.trim())
        if (!LdkWalletManager.isBolt11Invoice(cleanedInvoice)) {
            return WithdrawSubmitResult.Failure("Invalid Lightning invoice for LNURL withdraw.")
        }
        val callbackUrl = request.callbackUrl.toHttpUrlOrNull()
            ?: return WithdrawSubmitResult.Failure("LNURL withdraw callback URL is invalid.")

        val submitUrl = callbackUrl.newBuilder()
            .addQueryParameter("k1", request.k1)
            .addQueryParameter("pr", cleanedInvoice)
            .build()
            .toString()

        return try {
            val requestCall = Request.Builder()
                .url(submitUrl)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(requestCall).execute().use { response ->
                if (!response.isSuccessful) {
                    return WithdrawSubmitResult.Failure("LNURL withdraw callback failed (${response.code}).")
                }
                val body = response.body?.string().orEmpty().trim()
                if (body.isBlank()) {
                    return WithdrawSubmitResult.Success
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed != null) {
                    val submitError = parseLnurlError(parsed)
                    if (!submitError.isNullOrBlank()) {
                        return WithdrawSubmitResult.Failure(submitError)
                    }
                }
                WithdrawSubmitResult.Success
            }
        } catch (_: IOException) {
            WithdrawSubmitResult.Failure("Could not submit LNURL withdraw callback.")
        } catch (_: Exception) {
            WithdrawSubmitResult.Failure("Could not submit LNURL withdraw callback.")
        }
    }

    private fun destinationToInitialWithdrawUrl(destination: String): String? {
        val raw = destination.trim()
        if (raw.isBlank()) return null

        val lower = raw.lowercase(Locale.ROOT)
        if (lower.startsWith("bitcoin:")) {
            val encoded = Regex("(?i)(?:^|[?&])lightning=([^&\\s]+)")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
            if (encoded.isNotBlank()) {
                val decoded = decodePercentEncoded(encoded)
                return destinationToInitialWithdrawUrl(decoded)
            }
            return null
        }
        if (lower.startsWith("lnurl1")) {
            return decodeLnurlBech32(raw)
        }
        if (lower.startsWith("lnurlw://")) {
            return "https://${raw.substringAfter("://")}"
        }
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return raw
        }
        return null
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
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                runCatching { JSONObject(body) }.getOrNull()
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLnurlError(obj: JSONObject): String? {
        val status = obj.optString("status").trim()
        if (!status.equals(STATUS_ERROR, ignoreCase = true)) return null
        return obj.optString("reason").trim().ifBlank {
            obj.optString("message").trim().ifBlank { "LNURL endpoint returned an error." }
        }
    }

    private fun parseInvoiceSats(invoice: String): Long? {
        val parsed = runCatching { Bolt11Invoice.fromStr(invoice.trim()) }.getOrNull() ?: return null
        val msat = runCatching { parsed.amountMilliSatoshis() }.getOrNull() ?: return null
        return runCatching { (msat / 1000UL).toLong() }.getOrNull()
    }

    private fun stripLightningPrefix(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("lightning:", ignoreCase = true)) {
            trimmed.substringAfter(":", "")
        } else {
            trimmed
        }
    }

    private fun looksLikeLightningAddress(raw: String): Boolean {
        if (raw.contains(' ')) return false
        if (!raw.contains('@')) return false
        if (raw.count { it == '@' } != 1) return false
        val local = raw.substringBefore("@").trim()
        val domain = raw.substringAfter("@").trim()
        if (local.isBlank() || domain.isBlank()) return false
        if (!domain.contains('.')) return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        return true
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
        return runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
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

    private fun Long.saturatingMul(multiplier: Long): Long {
        if (this <= 0L || multiplier <= 0L) return 0L
        if (this > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE
        return this * multiplier
    }

    private fun msatToRoundedSats(msat: Long): Long {
        if (msat <= 0L) return 0L
        val quotient = msat / 1000L
        val remainder = msat % 1000L
        return if (remainder == 0L) quotient else quotient + 1L
    }

    private fun decodePercentEncoded(value: String): String {
        val trimmed = value.trim()
        return runCatching { URLDecoder.decode(trimmed, StandardCharsets.UTF_8) }
            .getOrDefault(trimmed)
    }
}
