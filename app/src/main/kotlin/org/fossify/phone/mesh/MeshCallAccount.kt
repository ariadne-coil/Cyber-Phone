package org.fossify.phone.mesh

import android.annotation.SuppressLint
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

    @SuppressLint("MissingPermission")
    fun isEnabled(context: Context): Boolean {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = getHandle(context)
        return try {
            telecomManager.callCapablePhoneAccounts.contains(handle)
        } catch (_: Exception) {
            false
        }
    }

    fun register(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val handle = getHandle(context)
        val account = PhoneAccount.builder(handle, context.getString(R.string.mesh_call_account_label))
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            // Telecom is inconsistent across OEM builds with custom schemes on outgoing calls.
            // Supporting tel ensures outgoing mesh calls can be placed reliably while we still
            // route them to this account explicitly via EXTRA_PHONE_ACCOUNT_HANDLE.
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL, "mesh"))
            .build()
        // Always (re-)register so updates (supported schemes etc) are applied across app upgrades.
        telecomManager.registerPhoneAccount(account)
    }
}
