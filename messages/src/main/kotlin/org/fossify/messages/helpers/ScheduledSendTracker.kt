package org.fossify.messages.helpers

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Persist MMS completion across process death and ignore duplicate/stale callbacks. */
class ScheduledSendTracker(private val prefs: SharedPreferences) {
    companion object {
        private val lock = Any()
        fun forContext(context: Context) = ScheduledSendTracker(context.getSharedPreferences("scheduled_mms_sends", Context.MODE_PRIVATE))
    }

    fun begin(messageId: Long, parts: Int): String = synchronized(lock) {
        require(parts > 0)
        val attempt = UUID.randomUUID().toString()
        check(prefs.edit().putString("$messageId.attempt", attempt).putInt("$messageId.parts", parts)
            .putBoolean("$messageId.failed", false).putStringSet("$messageId.done", emptySet()).commit())
        attempt
    }

    fun complete(messageId: Long, attempt: String, part: Int, succeeded: Boolean): Boolean = synchronized(lock) {
        if (prefs.getString("$messageId.attempt", null) != attempt) return@synchronized false
        val parts = prefs.getInt("$messageId.parts", 0)
        if (part !in 0 until parts) return@synchronized false
        val done = prefs.getStringSet("$messageId.done", emptySet()).orEmpty().toMutableSet()
        done.add(part.toString())
        val failed = prefs.getBoolean("$messageId.failed", false) || !succeeded
        check(prefs.edit().putStringSet("$messageId.done", done).putBoolean("$messageId.failed", failed).commit())
        done.size == parts && !failed
    }

    fun forget(messageId: Long) = synchronized(lock) {
        check(prefs.edit().remove("$messageId.attempt").remove("$messageId.parts")
            .remove("$messageId.failed").remove("$messageId.done").commit())
    }
}
