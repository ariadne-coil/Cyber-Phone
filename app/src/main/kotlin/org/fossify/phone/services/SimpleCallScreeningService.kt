package org.fossify.phone.services

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import org.fossify.phone.blocking.YacbBlockingEngine
import org.fossify.phone.blocking.YacbCallNotificationHelper

@RequiresApi(Build.VERSION_CODES.N)
class SimpleCallScreeningService : CallScreeningService() {
    private val blockingEngine by lazy { YacbBlockingEngine(this) }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        blockingEngine.evaluateCall(number) { decision ->
            decision.callInfo?.let { info ->
                if (decision.shouldBlock) {
                    YacbCallNotificationHelper.notifyBlockedCall(this, info)
                } else {
                    YacbCallNotificationHelper.notifyIncomingCallInfo(this, info)
                }
            }
            respondToCall(callDetails, isBlocked = decision.shouldBlock)
        }
    }

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(isBlocked)
            .setRejectCall(isBlocked)
            .setSkipCallLog(isBlocked)
            .setSkipNotification(isBlocked)
            .build()

        respondToCall(callDetails, response)
    }
}
