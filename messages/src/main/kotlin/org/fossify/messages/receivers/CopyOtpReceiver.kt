package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.fossify.messages.R
import org.fossify.messages.helpers.COPY_OTP
import org.fossify.messages.helpers.EXTRA_OTP

class CopyOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != COPY_OTP) {
            return
        }
        val otp = intent.getStringExtra(EXTRA_OTP)?.trim().orEmpty()
        if (otp.isEmpty()) {
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.messages_category_otp), otp))
        Toast.makeText(context, context.getString(R.string.copy_otp), Toast.LENGTH_SHORT).show()
    }
}
