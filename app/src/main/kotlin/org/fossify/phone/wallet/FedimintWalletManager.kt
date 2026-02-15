package org.fossify.phone.wallet

import android.content.Context
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.wallet.fedimint.FedimintWebEngine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * Minimal Fedimint wallet backend using the official Fedimint Web SDK (WASM) executed in a WebView.
 *
 * This is intentionally API-similar to [LdkWalletManager] so the UI can switch backends cleanly.
 */
object FedimintWalletManager {
    private const val TAG = "FedimintWalletManager"
    private const val START_TIMEOUT_MS = 45_000L
    private const val BALANCE_TIMEOUT_MS = 30_000L
    private const val INVOICE_TIMEOUT_MS = 45_000L
    private const val PAY_TIMEOUT_MS = 120_000L
    private const val ECASH_TIMEOUT_MS = 120_000L
    private const val START_WAIT_TIMEOUT_MS = 60_000L

    private val lock = Any()

    data class EcashSpendResult(
        val notes: String,
        val operationId: String?,
    )

    @Volatile
    private var federationId: String? = null

    @Volatile
    private var isStarting: Boolean = false

    @Volatile
    private var lastError: Throwable? = null

    fun getLastErrorMessage(): String? {
        val raw = lastError?.message ?: FedimintWebEngine.getLastErrorMessage()
        return normalizeErrorMessage(raw)
    }

    fun isBusy(): Boolean = isStarting

    fun getRunningFederationId(): String? = federationId

    fun isRunning(): Boolean = federationId?.isNotBlank() == true && !isStarting

    fun verifyRunningFederationBlocking(context: Context, federation: FederationEntry): Boolean {
        if (federationId != federation.id || federationId.isNullOrBlank()) return false
        return try {
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "isOpen",
                params = JSONObject(),
                timeoutMs = 15_000L
            ) ?: return false.also { federationId = null }

            val obj = JSONObject(res)
            val ok = obj.optBoolean("ok", false)
            val open = if (ok) {
                when (val result = obj.opt("result")) {
                    is JSONObject -> result.optBoolean("open", false)
                    is Boolean -> result
                    else -> false
                }
            } else {
                false
            }

            if (!open) {
                federationId = null
            }
            open
        } catch (_: Throwable) {
            federationId = null
            false
        }
    }

    fun ensureStartedBlocking(context: Context, federation: FederationEntry): Boolean {
        val invite = normalizeInviteCode(federation.invite)
        val clientName = clientNameForFederation(federation)

        if (invite.isBlank()) {
            lastError = IllegalArgumentException("Missing federation invite code")
            return false
        }

        val waitDeadline = System.currentTimeMillis() + START_WAIT_TIMEOUT_MS
        while (true) {
            synchronized(lock) {
                if (federationId == federation.id && lastError == null && !isStarting) {
                    return true
                }
                if (!isStarting) {
                    isStarting = true
                    break
                }
            }
            if (System.currentTimeMillis() >= waitDeadline) {
                lastError = RuntimeException("Wallet startup timed out")
                return false
            }
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                lastError = e
                return false
            }
        }

        return try {
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "openOrJoin",
                params = JSONObject().apply {
                    put("clientName", clientName)
                    put("inviteCode", invite)
                },
                timeoutMs = START_TIMEOUT_MS
            ) ?: run {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Wallet startup timed out" })
                federationId = null
                return false
            }

            val ok = parseOk(res)
            if (ok) {
                federationId = federation.id
                lastError = null
            } else {
                lastError = RuntimeException(parseError(res))
                clearRunningStateIfNotInitialized(lastError?.message)
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "ensureStartedBlocking failed", t)
            lastError = t
            clearRunningStateIfNotInitialized(t.message)
            false
        } finally {
            synchronized(lock) {
                isStarting = false
            }
        }
    }

    fun ensureStarted(
        context: Context,
        federation: FederationEntry,
        callback: ((success: Boolean, error: String?) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        ensureBackgroundThread {
            val ok = ensureStartedBlocking(appContext, federation)
            callback?.invoke(ok, if (ok) null else getLastErrorMessage())
        }
    }

    /**
     * Returns the balance in sats.
     */
    fun getBalanceSatsBlocking(context: Context, federation: FederationEntry): Long? {
        if (!ensureStartedBlocking(context, federation)) return null

        return try {
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "getBalanceMsats",
                params = JSONObject(),
                timeoutMs = BALANCE_TIMEOUT_MS
            ) ?: run {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Balance request timed out" })
                clearRunningStateIfNotInitialized(lastError?.message)
                return null
            }

            val obj = JSONObject(res)
            if (!obj.optBoolean("ok", false)) {
                lastError = RuntimeException(obj.optString("error", "Unknown error"))
                clearRunningStateIfNotInitialized(lastError?.message)
                return null
            }

            // The Web SDK uses millisats for balances, but return shapes can vary by version.
            val msats = parseBalanceMsats(obj.opt("result"))
            if (msats == null) {
                val preview = obj.opt("result")?.toString().orEmpty().take(300)
                lastError = RuntimeException("Unsupported balance payload: $preview")
                clearRunningStateIfNotInitialized(lastError?.message)
                null
            } else {
                lastError = null
                msats.div(1000L)
            }
        } catch (t: Throwable) {
            lastError = t
            clearRunningStateIfNotInitialized(t.message)
            null
        }
    }

    fun createBolt11InvoiceBlocking(
        context: Context,
        federation: FederationEntry,
        amountSats: Long,
        memo: String,
        expirySeconds: Int = 3600,
    ): String? {
        if (amountSats <= 0L) {
            lastError = IllegalArgumentException("Amount required")
            return null
        }
        if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
            lastError = IllegalArgumentException("Amount exceeds maximum transfer limit")
            return null
        }

        if (!ensureStartedBlocking(context, federation)) return null

        return try {
            val amountMsats = satsToMsatsOrNull(amountSats) ?: run {
                lastError = IllegalArgumentException("Amount is too large")
                return null
            }
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "createInvoice",
                params = JSONObject().apply {
                    put("amountMsats", amountMsats)
                    put("memo", memo)
                    put("expiryTime", expirySeconds)
                },
                timeoutMs = INVOICE_TIMEOUT_MS
            ) ?: run {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Invoice creation timed out" })
                clearRunningStateIfNotInitialized(lastError?.message)
                return null
            }

            val obj = JSONObject(res)
            if (!obj.optBoolean("ok", false)) {
                lastError = RuntimeException(obj.optString("error", "Unknown error"))
                clearRunningStateIfNotInitialized(lastError?.message)
                return null
            }

            // Response shape depends on SDK version; handle both { invoice: "..." } and nested.
            val result = obj.opt("result")
            when (result) {
                is JSONObject -> {
                    result.optString("invoice")
                        .ifBlank { result.optString("bolt11") }
                        .ifBlank { result.optString("payment_request") }
                        .takeIf { it.isNotBlank() }
                }
                is String -> result.trim().takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (t: Throwable) {
            lastError = t
            clearRunningStateIfNotInitialized(t.message)
            null
        }
    }

    fun payBolt11InvoiceBlocking(context: Context, federation: FederationEntry, invoice: String): Boolean {
        val cleaned = invoice.trim()
        if (cleaned.isBlank()) {
            lastError = IllegalArgumentException("Invoice required")
            return false
        }

        if (!ensureStartedBlocking(context, federation)) return false

        return try {
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "payInvoice",
                params = JSONObject().apply { put("invoice", cleaned) },
                timeoutMs = PAY_TIMEOUT_MS
            )

            if (res == null) {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Invoice payment timed out" })
                clearRunningStateIfNotInitialized(lastError?.message)
                return false
            }

            val ok = parseOk(res)
            if (!ok) {
                lastError = RuntimeException(parseError(res))
                clearRunningStateIfNotInitialized(lastError?.message)
            } else {
                lastError = null
            }
            ok
        } catch (t: Throwable) {
            lastError = t
            clearRunningStateIfNotInitialized(t.message)
            false
        }
    }

    /**
     * Generates an ecash token (OOB notes string) for out-of-band transfers.
     *
     * Amount is in sats. Returns the notes string on success.
     */
    fun spendEcashBlocking(
        context: Context,
        federation: FederationEntry,
        amountSats: Long,
        tryCancelAfterSecs: Int = 86400,
    ): EcashSpendResult? {
        if (amountSats <= 0L) {
            lastError = IllegalArgumentException("Amount required")
            return null
        }
        if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
            lastError = IllegalArgumentException("Amount exceeds maximum transfer limit")
            return null
        }

        if (!ensureStartedBlocking(context, federation)) return null

        return try {
            val amountMsats = satsToMsatsOrNull(amountSats) ?: run {
                lastError = IllegalArgumentException("Amount is too large")
                return null
            }
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "spendNotes",
                params = JSONObject().apply {
                    put("amountMsats", amountMsats)
                    put("tryCancelAfterSecs", tryCancelAfterSecs.coerceAtLeast(0))
                    put("includeInvite", false)
                    put("extraMeta", JSONObject())
                },
                timeoutMs = ECASH_TIMEOUT_MS
            ) ?: run {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Ecash generation timed out" })
                clearRunningStateIfNotInitialized(lastError?.message)
                return null
            }

            val obj = JSONObject(res)
            if (!obj.optBoolean("ok", false)) {
                lastError = RuntimeException(obj.optString("error", "Unknown error"))
                clearRunningStateIfNotInitialized(lastError?.message)
                return null
            }

            val result = obj.opt("result")
            val notes = when (result) {
                is JSONObject -> {
                    // engine.html normalizes to { notes: "..." }, but tolerate other SDK shapes.
                    result.optString("notes")
                        .ifBlank { result.optString("oob_notes") }
                        .ifBlank { result.optString("oobNotes") }
                        .trim()
                }
                is String -> result.trim()
                else -> ""
            }
            if (notes.isBlank()) {
                lastError = RuntimeException("Ecash generation did not return notes")
                return null
            }
            val operationId = when (result) {
                is JSONObject -> result.optString("operation_id")
                    .ifBlank { result.optString("operationId") }
                    .trim()
                    .ifBlank { null }
                else -> null
            }
            lastError = null
            EcashSpendResult(notes = notes, operationId = operationId)
        } catch (t: Throwable) {
            lastError = t
            clearRunningStateIfNotInitialized(t.message)
            null
        }
    }

    fun tryCancelSpendBlocking(context: Context, federation: FederationEntry, operationId: String): Boolean {
        val cleaned = operationId.trim()
        if (cleaned.isBlank()) {
            lastError = IllegalArgumentException("Operation id required")
            return false
        }

        if (!ensureStartedBlocking(context, federation)) return false

        return try {
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "tryCancelSpendNotes",
                params = JSONObject().apply { put("operationId", cleaned) },
                timeoutMs = 45_000L
            ) ?: run {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Try-cancel timed out" })
                return false
            }

            val ok = parseOk(res)
            if (!ok) {
                lastError = RuntimeException(parseError(res))
            } else {
                lastError = null
            }
            ok
        } catch (t: Throwable) {
            lastError = t
            false
        }
    }

    fun redeemEcashBlocking(context: Context, federation: FederationEntry, notes: String): Boolean {
        val cleaned = notes.trim()
        if (cleaned.isBlank()) {
            lastError = IllegalArgumentException("Notes required")
            return false
        }

        if (!ensureStartedBlocking(context, federation)) return false

        return try {
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "redeemEcash",
                params = JSONObject().apply { put("notes", cleaned) },
                timeoutMs = ECASH_TIMEOUT_MS
            )

            if (res == null) {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Ecash redeem timed out" })
                clearRunningStateIfNotInitialized(lastError?.message)
                return false
            }

            val ok = parseOk(res)
            if (!ok) {
                lastError = RuntimeException(parseError(res))
                clearRunningStateIfNotInitialized(lastError?.message)
            } else {
                lastError = null
            }
            ok
        } catch (t: Throwable) {
            lastError = t
            clearRunningStateIfNotInitialized(t.message)
            false
        }
    }

    fun exportMnemonicBlocking(context: Context, federation: FederationEntry): String? {
        return try {
            val clientName = clientNameForFederation(federation)
            val res = FedimintWebEngine.callBlocking(
                context = context,
                method = "getMnemonic",
                params = JSONObject().apply { put("clientName", clientName) },
                timeoutMs = 30_000L
            ) ?: run {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Mnemonic export timed out" })
                return null
            }

            val obj = JSONObject(res)
            if (!obj.optBoolean("ok", false)) {
                lastError = RuntimeException(obj.optString("error", "Unknown error"))
                return null
            }
            val result = obj.optJSONObject("result")
            val words = result?.optJSONArray("words")
            val phrase = if (words != null && words.length() > 0) {
                buildString {
                    for (i in 0 until words.length()) {
                        val word = words.optString(i).trim()
                        if (word.isBlank()) continue
                        if (isNotEmpty()) append(' ')
                        append(word)
                    }
                }
            } else {
                result?.optString("mnemonic").orEmpty().trim()
            }
            if (phrase.isBlank()) {
                lastError = RuntimeException("Wallet mnemonic is empty")
                null
            } else {
                lastError = null
                phrase
            }
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    fun importMnemonicBlocking(context: Context, federation: FederationEntry, mnemonic: String): Boolean {
        val cleaned = mnemonic.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
        if (cleaned.isBlank()) {
            lastError = IllegalArgumentException("Mnemonic required")
            return false
        }

        return try {
            val clientName = clientNameForFederation(federation)
            val setRes = FedimintWebEngine.callBlocking(
                context = context,
                method = "setMnemonic",
                params = JSONObject().apply {
                    put("clientName", clientName)
                    put("mnemonic", cleaned)
                },
                timeoutMs = 45_000L
            ) ?: run {
                val engineMsg = FedimintWebEngine.getLastErrorMessage().orEmpty().trim()
                lastError = RuntimeException(engineMsg.ifBlank { "Mnemonic restore timed out" })
                return false
            }

            val obj = JSONObject(setRes)
            if (!obj.optBoolean("ok", false)) {
                lastError = RuntimeException(obj.optString("error", "Unknown error"))
                return false
            }

            val started = ensureStartedBlocking(context, federation)
            if (!started && lastError == null) {
                lastError = RuntimeException("Wallet failed to start after mnemonic restore")
            }
            started
        } catch (t: Throwable) {
            lastError = t
            false
        }
    }

    private fun parseOk(payload: String): Boolean {
        return try {
            JSONObject(payload).optBoolean("ok", false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun parseError(payload: String): String {
        return try {
            JSONObject(payload).optString("error", "Unknown error")
        } catch (_: Throwable) {
            "Unknown error"
        }
    }

    private fun parseBalanceMsats(result: Any?): Long? {
        return parseBalanceMsats(result, hint = "")
    }

    private fun normalizeErrorMessage(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null

        val lower = text.lowercase()
        if (lower.contains("runtimeerror") && lower.contains("unreachable")) {
            return "Selected federation appears incompatible with the embedded Fedimint runtime."
        }
        return text
    }

    private fun clearRunningStateIfNotInitialized(raw: String?) {
        val text = raw?.trim().orEmpty().lowercase()
        if (text.isBlank()) return
        if (text.contains("wallet not open") ||
            text.contains("not initialized") ||
            text.contains("client is not initialized") ||
            text.contains("runtimeerror") && text.contains("unreachable") ||
            text.contains("incompatible")
        ) {
            federationId = null
        }
    }

    private fun clientNameForFederation(federation: FederationEntry): String {
        val seed = federation.id.trim().ifBlank { federation.invite.trim() }.ifBlank { "fm-default" }
        // Fedimint Web SDK transport expects a UUID-like client name (36 chars).
        return UUID.nameUUIDFromBytes("cyberphone:$seed".toByteArray(Charsets.UTF_8)).toString()
    }

    private fun parseBalanceMsats(value: Any?, hint: String): Long? {
        return when (value) {
            null -> null
            is Number -> {
                val parsed = value.toLong()
                applyAmountHint(parsed, hint = hint, raw = value.toString())
            }
            is String -> parseStringAmount(value, hint)
            is JSONObject -> {
                val preferredKeys = arrayOf(
                    "msats",
                    "amount_msats",
                    "balance_msats",
                    "total_msats",
                    "amountMsats",
                    "balanceMsats",
                    "totalMsats",
                    "sats",
                    "amount_sats",
                    "balance_sats",
                    "total_sats",
                    "amountSats",
                    "balanceSats",
                    "totalSats",
                    "amount",
                    "balance",
                    "total",
                    "value",
                )

                for (key in preferredKeys) {
                    if (value.has(key)) {
                        parseBalanceMsats(value.opt(key), hint = key)?.let { return it }
                    }
                }

                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    parseBalanceMsats(value.opt(key), hint = key)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    parseBalanceMsats(value.opt(i), hint = hint)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun parseStringAmount(raw: String, hint: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val direct = trimmed.toLongOrNull()
        if (direct != null) {
            return applyAmountHint(direct, hint = hint, raw = trimmed)
        }

        val extracted = NUMBER_EXTRACTOR.find(trimmed)?.value?.toLongOrNull() ?: return null
        return applyAmountHint(extracted, hint = hint, raw = trimmed)
    }

    private fun applyAmountHint(value: Long, hint: String, raw: String): Long? {
        if (value < 0L) return null

        val lowerHint = hint.lowercase()
        val lowerRaw = raw.lowercase()
        val hintIsMsat = lowerHint.contains("msat")
        val rawIsMsat = lowerRaw.contains("msat")
        if (hintIsMsat || rawIsMsat) return value

        val hintIsSat = lowerHint.contains("sat")
        val rawIsSat = lowerRaw.contains("sat")
        if (hintIsSat || rawIsSat) return safeMultiply(value, 1000L)

        // Default to msats when no explicit unit hint is present.
        return value
    }

    private fun safeMultiply(value: Long, factor: Long): Long? {
        return if (value > Long.MAX_VALUE / factor) null else value * factor
    }

    private fun satsToMsatsOrNull(sats: Long): Long? {
        if (sats <= 0L) return null
        return safeMultiply(sats, 1000L)
    }

    private fun normalizeInviteCode(raw: String): String {
        var text = raw.trim()
        if (text.isBlank()) return ""

        // Prefer explicit query parameters when invite links are wrapped in URLs.
        Regex("(?i)(?:^|[?&])(invite|invite_code)=([^&#]+)")
            .find(text)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { encoded ->
                val decoded = decodeUrlComponent(encoded).trim()
                if (decoded.isNotBlank()) {
                    text = decoded
                }
            }

        val token = Regex("(?i)fed1[0-9a-z]+").find(text)?.value
        return token?.trim().orEmpty().ifBlank { text.trim() }
    }

    private fun decodeUrlComponent(value: String): String {
        return try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    // No global init required; the WebView is created with applicationContext internally.

    private val NUMBER_EXTRACTOR = Regex("-?\\d+")
}
