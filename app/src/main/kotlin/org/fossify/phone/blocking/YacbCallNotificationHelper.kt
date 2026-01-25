package org.fossify.phone.blocking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.fossify.commons.extensions.notificationManager
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import kotlin.math.absoluteValue

object YacbCallNotificationHelper {
    private const val CHANNEL_BLOCKED_CALLS = "blocked_calls"
    private const val CHANNEL_CALL_INFO = "call_info"

    fun notifyBlockedCall(context: Context, info: CallInfo) {
        if (!context.config.showBlockedCallNotifications) {
            return
        }

        ensureChannels(context)
        val contentText = buildInfoText(context, info)
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCKED_CALLS)
            .setSmallIcon(R.drawable.ic_phone_down_red_vector)
            .setContentTitle(context.getString(R.string.notification_blocked_call))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .build()

        context.notificationManager.notify(makeNotificationId(info), notification)
    }

    fun notifyIncomingCallInfo(context: Context, info: CallInfo) {
        if (!context.config.showCallRatingNotifications) {
            return
        }

        if (info.rating == null) {
            return
        }

        ensureChannels(context)
        val contentText = buildInfoText(context, info)
        val notification = NotificationCompat.Builder(context, CHANNEL_CALL_INFO)
            .setSmallIcon(R.drawable.ic_call_received_vector)
            .setContentTitle(context.getString(R.string.notification_incoming_call))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .build()

        context.notificationManager.notify(makeNotificationId(info), notification)
    }

    private fun buildInfoText(context: Context, info: CallInfo): String {
        val parts = ArrayList<String>()
        val displayName = info.displayName?.takeIf { it.isNotBlank() }
        if (displayName != null) {
            parts.add(displayName)
        }
        info.number?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        info.rating?.let { parts.add(ratingToText(context, it)) }
        return parts.joinToString(" · ")
    }

    private fun ratingToText(context: Context, rating: YacbSiaManager.Rating): String {
        return when (rating) {
            YacbSiaManager.Rating.POSITIVE -> context.getString(R.string.call_rating_positive)
            YacbSiaManager.Rating.NEUTRAL -> context.getString(R.string.call_rating_neutral)
            YacbSiaManager.Rating.NEGATIVE -> context.getString(R.string.call_rating_negative)
        }
    }

    private fun ensureChannels(context: Context) {
        val manager = context.notificationManager
        if (manager.getNotificationChannel(CHANNEL_BLOCKED_CALLS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BLOCKED_CALLS,
                    context.getString(R.string.notification_channel_blocked_calls),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        if (manager.getNotificationChannel(CHANNEL_CALL_INFO) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CALL_INFO,
                    context.getString(R.string.notification_channel_call_info),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun makeNotificationId(info: CallInfo): Int {
        val number = info.number ?: "unknown"
        return number.hashCode().absoluteValue + 1000
    }
}
