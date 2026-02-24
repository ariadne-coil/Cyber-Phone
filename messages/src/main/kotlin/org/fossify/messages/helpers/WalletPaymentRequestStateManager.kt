package org.fossify.messages.helpers

import android.content.Context
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

/**
 * Local state for Fedimint request/response payment handshakes.
 *
 * We keep this lightweight and app-local (SharedPreferences) so both :messages and :app can
 * validate the same pending request state without introducing new storage dependencies.
 */
object WalletPaymentRequestStateManager {
    data class PendingRequest(
        val requestId: String,
        val threadId: Long,
        val federationId: String,
        val amountSats: Long,
        val status: String,
        val invoiceFingerprint: String?,
    )

    data class ValidationResult(
        val isValid: Boolean,
        val error: String? = null,
    )

    private const val PREFS_NAME = "wallet_fedimint_payment_requests"
    private const val KEY_REQUESTS_JSON = "requests_json"
    private const val KEY_HANDLED_RESPONSE_MESSAGES = "handled_response_messages"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_INVOICE_RECEIVED = "invoice_received"
    private const val STATUS_PAID = "paid"
    private const val STATUS_DENIED = "denied"
    private const val MAX_HANDLED_RESPONSE_MESSAGES = 1024
    private const val REQUEST_RETENTION_MS = 180L * 24L * 60L * 60L * 1000L
    private const val REQUEST_PRUNE_INTERVAL_MS = 10L * 60L * 1000L
    private const val REQUEST_MAX_ENTRIES = 20_000
    private const val REQUEST_TRIM_TO_ENTRIES = 12_000

    private val lock = Any()
    @Volatile
    private var lastRequestsPruneAt = 0L

    fun registerOutgoingRequest(
        context: Context,
        requestId: String,
        threadId: Long,
        federationId: String,
        amountSats: Long,
    ) {
        if (requestId.isBlank() || threadId == 0L || federationId.isBlank() || amountSats <= 0L) return
        synchronized(lock) {
            val prefs = prefs(context)
            val requests = loadRequests(prefs)
            val now = System.currentTimeMillis()
            val obj = JSONObject().apply {
                put("requestId", requestId)
                put("threadId", threadId)
                put("federationId", federationId.trim())
                put("amountSats", amountSats)
                put("status", STATUS_PENDING)
                put("createdAtMs", now)
                put("updatedAtMs", now)
                put("invoiceFp", "")
            }
            requests.put(requestId, obj)
            maybePruneRequestsLocked(requests, now)
            prefs.edit().putString(KEY_REQUESTS_JSON, requests.toString()).apply()
        }
    }

    fun getPendingRequest(context: Context, requestId: String): PendingRequest? {
        if (requestId.isBlank()) return null
        synchronized(lock) {
            val obj = loadRequests(prefs(context)).optJSONObject(requestId) ?: return null
            return obj.toPendingRequest()
        }
    }

    fun markRequestDenied(context: Context, requestId: String) {
        updateRequestStatus(context, requestId, STATUS_DENIED)
    }

    fun markRequestPaid(context: Context, requestId: String) {
        updateRequestStatus(context, requestId, STATUS_PAID)
    }

    fun hasHandledResponseMessage(context: Context, messageId: Long): Boolean {
        // Mesh/LXMF message ids are negative by design; only 0 is invalid/unknown.
        if (messageId == 0L) return false
        synchronized(lock) {
            val handled = prefs(context).getStringSet(KEY_HANDLED_RESPONSE_MESSAGES, emptySet()).orEmpty()
            return handled.contains(messageId.toString())
        }
    }

    fun markResponseMessageHandled(context: Context, messageId: Long) {
        // Mesh/LXMF message ids are negative by design; only 0 is invalid/unknown.
        if (messageId == 0L) return
        synchronized(lock) {
            val prefs = prefs(context)
            val current = prefs.getStringSet(KEY_HANDLED_RESPONSE_MESSAGES, emptySet()).orEmpty().toMutableSet()
            current.add(messageId.toString())
            val trimmed = if (current.size <= MAX_HANDLED_RESPONSE_MESSAGES) {
                current
            } else {
                current.asSequence()
                    .mapNotNull { it.toLongOrNull() }
                    .sortedDescending()
                    .take(MAX_HANDLED_RESPONSE_MESSAGES)
                    .map { it.toString() }
                    .toMutableSet()
            }
            prefs.edit().putStringSet(KEY_HANDLED_RESPONSE_MESSAGES, trimmed).apply()
        }
    }

    fun validateAndReserveInvoiceResponse(
        context: Context,
        requestId: String,
        threadId: Long,
        federationId: String,
        amountSats: Long,
        invoice: String,
    ): ValidationResult {
        if (requestId.isBlank()) return ValidationResult(false, "Missing request id.")
        if (threadId == 0L) return ValidationResult(false, "Invalid request thread.")
        if (federationId.isBlank()) return ValidationResult(false, "Missing federation id.")
        if (amountSats <= 0L) return ValidationResult(false, "Invalid amount in payment response.")
        if (invoice.isBlank()) return ValidationResult(false, "Missing invoice in payment response.")

        synchronized(lock) {
            val prefs = prefs(context)
            val requests = loadRequests(prefs)
            val obj = requests.optJSONObject(requestId)
                ?: return ValidationResult(false, "Unknown or expired payment request.")

            val expectedThreadId = obj.optLong("threadId", 0L)
            if (expectedThreadId != threadId) {
                return ValidationResult(false, "Payment response does not match this thread.")
            }

            val expectedFederationId = obj.optString("federationId", "").trim()
            if (!expectedFederationId.equals(federationId.trim(), ignoreCase = true)) {
                return ValidationResult(false, "Payment response federation does not match the request.")
            }

            val expectedAmount = obj.optLong("amountSats", -1L)
            if (expectedAmount <= 0L || expectedAmount != amountSats) {
                return ValidationResult(false, "Payment response amount does not match the request.")
            }

            when (obj.optString("status", STATUS_PENDING).trim().lowercase(Locale.ROOT)) {
                STATUS_PAID -> return ValidationResult(false, "Payment request is already paid.")
                STATUS_DENIED -> return ValidationResult(false, "Payment request was denied.")
                STATUS_INVOICE_RECEIVED -> {
                    val existingFp = obj.optString("invoiceFp", "").trim()
                    val newFp = fingerprint(invoice)
                    return if (existingFp.isNotBlank() && existingFp == newFp) {
                        ValidationResult(true)
                    } else {
                        ValidationResult(false, "Payment request already has a different invoice response.")
                    }
                }
            }

            obj.put("status", STATUS_INVOICE_RECEIVED)
            obj.put("invoiceFp", fingerprint(invoice))
            obj.put("updatedAtMs", System.currentTimeMillis())
            requests.put(requestId, obj)
            maybePruneRequestsLocked(requests)
            prefs.edit().putString(KEY_REQUESTS_JSON, requests.toString()).apply()
            return ValidationResult(true)
        }
    }

    private fun updateRequestStatus(context: Context, requestId: String, status: String) {
        if (requestId.isBlank()) return
        synchronized(lock) {
            val prefs = prefs(context)
            val requests = loadRequests(prefs)
            val obj = requests.optJSONObject(requestId) ?: return
            obj.put("status", status)
            obj.put("updatedAtMs", System.currentTimeMillis())
            requests.put(requestId, obj)
            maybePruneRequestsLocked(requests)
            prefs.edit().putString(KEY_REQUESTS_JSON, requests.toString()).apply()
        }
    }

    private fun maybePruneRequestsLocked(requests: JSONObject, now: Long = System.currentTimeMillis()) {
        val overLimit = requests.length() > REQUEST_MAX_ENTRIES
        val due = now - lastRequestsPruneAt >= REQUEST_PRUNE_INTERVAL_MS
        if (!overLimit && !due) return
        lastRequestsPruneAt = now

        val minKeepTime = now - REQUEST_RETENTION_MS
        val keys = ArrayList<String>()
        val iterator = requests.keys()
        while (iterator.hasNext()) {
            keys.add(iterator.next())
        }

        // First pass: remove malformed/expired entries.
        keys.forEach { key ->
            val obj = requests.optJSONObject(key)
            if (obj == null) {
                requests.remove(key)
                return@forEach
            }
            val createdAt = obj.optLong("createdAtMs", 0L)
            val updatedAt = obj.optLong("updatedAtMs", createdAt)
            if (updatedAt in 1 until minKeepTime) {
                requests.remove(key)
            }
        }

        if (requests.length() <= REQUEST_MAX_ENTRIES) return

        // Hard cap: keep only the most recently touched entries.
        val scored = ArrayList<Pair<String, Long>>()
        val remainingIterator = requests.keys()
        while (remainingIterator.hasNext()) {
            val key = remainingIterator.next()
            val obj = requests.optJSONObject(key) ?: continue
            val createdAt = obj.optLong("createdAtMs", 0L)
            val updatedAt = obj.optLong("updatedAtMs", createdAt)
            scored.add(key to updatedAt)
        }
        val keep = scored
            .sortedByDescending { it.second }
            .take(REQUEST_TRIM_TO_ENTRIES)
            .mapTo(LinkedHashSet()) { it.first }

        scored.forEach { (key, _) ->
            if (!keep.contains(key)) {
                requests.remove(key)
            }
        }
    }

    private fun loadRequests(prefs: android.content.SharedPreferences): JSONObject {
        val raw = prefs.getString(KEY_REQUESTS_JSON, "{}").orEmpty().trim()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun JSONObject.toPendingRequest(): PendingRequest {
        val fp = optString("invoiceFp", "").trim().ifBlank { null }
        return PendingRequest(
            requestId = optString("requestId", ""),
            threadId = optLong("threadId", 0L),
            federationId = optString("federationId", ""),
            amountSats = optLong("amountSats", 0L),
            status = optString("status", STATUS_PENDING),
            invoiceFingerprint = fp,
        )
    }

    private fun fingerprint(value: String): String {
        val data = value.trim().lowercase(Locale.ROOT).toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return buildString(digest.size * 2) {
            digest.forEach { b ->
                append(((b.toInt() ushr 4) and 0xF).toString(16))
                append((b.toInt() and 0xF).toString(16))
            }
        }
    }
}
