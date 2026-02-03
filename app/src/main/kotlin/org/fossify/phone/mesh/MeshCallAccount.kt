package org.fossify.phone.mesh

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import org.fossify.phone.R

object MeshCallAccount {
    fun getHandle(context: Context): PhoneAccountHandle {
        val component = ComponentName(context, MeshCallConnectionService::class.java)
        return PhoneAccountHandle(component, MeshCallConstants.PHONE_ACCOUNT_ID)
    }

    fun register(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = getHandle(context)
        if (telecomManager.getPhoneAccount(handle) != null) {
            return
        }
        val account = PhoneAccount.builder(handle, context.getString(R.string.mesh_call_account_label))
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setSupportedUriSchemes(listOf("mesh"))
            .build()
        telecomManager.registerPhoneAccount(account)
    }
}
