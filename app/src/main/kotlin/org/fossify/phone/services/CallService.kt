package org.fossify.phone.services

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.os.Build
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.keyguardManager
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.DiagnosticsLogger
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)
        val number = call.details.handle?.schemeSpecificPart

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isIncoming = !call.isOutgoing()
        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isIncoming && isDeviceLocked -> false
            !isIncoming && isDeviceLocked -> false
            isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)
        DiagnosticsLogger.log(
            this,
            "onCallAdded incoming=$isIncoming locked=$isDeviceLocked lowPriority=$lowPriority " +
                "postNotif=${hasPermission(PERMISSION_POST_NOTIFICATIONS)} " +
                "fsi=${canUseFullScreenIntent()} number=${maskNumber(number)}"
        )
        val shouldStartActivity = isIncoming ||
            lowPriority ||
            !hasPermission(PERMISSION_POST_NOTIFICATIONS) ||
            !canUseFullScreenIntent()
        if (shouldStartActivity) {
            try {
                DiagnosticsLogger.log(this, "startActivity attempt")
                startActivity(CallActivity.getStartIntent(this))
                DiagnosticsLogger.log(this, "startActivity dispatched")
            } catch (e: Exception) {
                // If launching the UI fails, fall back to a regular notification.
                DiagnosticsLogger.log(this, "startActivity failed: ${e::class.java.simpleName}")
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    @Deprecated("Use onCallEndpointChanged on newer platforms.")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }

    private fun maskNumber(number: String?): String {
        if (number.isNullOrBlank()) {
            return "unknown"
        }

        val digits = number.filter { it.isDigit() }
        if (digits.length <= 4) {
            return digits
        }

        val suffix = digits.takeLast(4)
        return "****$suffix"
    }
}
