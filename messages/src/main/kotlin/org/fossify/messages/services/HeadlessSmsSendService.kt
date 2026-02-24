package org.fossify.messages.services

import android.app.Service
import android.content.Intent
import android.net.Uri
import com.klinker.android.send_message.Settings
import org.fossify.messages.messaging.sendMessageCompat

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val number = extractDestinationAddress(intent) ?: return START_NOT_STICKY
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        if (text.isNotEmpty()) {
            val addresses = listOf(number)
            val subId = Settings.DEFAULT_SUBSCRIPTION_ID
            runCatching {
                sendMessageCompat(text, addresses, subId, emptyList())
            }
        }

        return START_NOT_STICKY
    }

    private fun extractDestinationAddress(intent: Intent): String? {
        val data = intent.data ?: return null
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme !in setOf("sms", "smsto", "mms", "mmsto")) return null

        // Use Uri parsing instead of manual prefix stripping so mmsto:/mms: variants are handled safely.
        val raw = data.schemeSpecificPart.orEmpty()
            .substringBefore('?')
            .substringBefore('#')
            .removePrefix("//")
            .trim()
        if (raw.isBlank()) return null

        val decoded = Uri.decode(raw).trim()
        if (decoded.isBlank()) return null

        return decoded
            .split(',', ';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
    }
}
