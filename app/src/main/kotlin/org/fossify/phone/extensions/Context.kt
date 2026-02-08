package org.fossify.phone.extensions

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import org.fossify.mesh.MeshContactHelper
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.helpers.Config
import org.fossify.phone.models.SIMAccount

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.audioManager: AudioManager
    get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.powerManager: PowerManager
    get() = getSystemService(Context.POWER_SERVICE) as PowerManager

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

@SuppressLint("MissingPermission")
fun Context.getAvailableSIMCardLabels(): List<SIMAccount> {
    val simAccounts = mutableListOf<SIMAccount>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                address = Uri.decode(address.substringAfter("tel:"))
                label += " ($address)"
            }

            simAccounts.add(
                SIMAccount(
                    id = index + 1,
                    handle = phoneAccount.accountHandle,
                    label = label,
                    phoneNumber = address.substringAfter("tel:"),
                    color = phoneAccount.highlightColor
                )
            )
        }
    } catch (ignored: Exception) {
    }

    return simAccounts
}

@SuppressLint("MissingPermission")
fun Context.areMultipleSIMsAvailable(): Boolean {
    return try {
        telecomManager.callCapablePhoneAccounts.size > 1
    } catch (ignored: Exception) {
        false
    }
}

fun Context.clearMissedCalls() {
    ensureBackgroundThread {
        try {
            // notification cancellation triggers MissedCallNotifier.clearMissedCalls() which, in turn,
            // should update the database and reset the cached missed call count in MissedCallNotifier.java
            // https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#170
            telecomManager.cancelMissedCallsNotification()
        } catch (ignored: Exception) {
        }
    }
}

fun Context.canLaunchAccountsConfiguration(): Boolean {
    return canLaunchAccountsConfiguration(handle = null)
}

fun Context.canLaunchAccountsConfiguration(handle: PhoneAccountHandle?): Boolean {
    return buildAccountsConfigurationIntents(handle).any { it.resolveActivity(packageManager) != null }
}

fun Context.launchAccountsConfiguration(handle: PhoneAccountHandle? = null): Boolean {
    for (baseIntent in buildAccountsConfigurationIntents(handle)) {
        val intent = Intent(baseIntent)
        if (this !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(packageManager) == null) continue
        try {
            startActivity(intent)
            return true
        } catch (_: Exception) {
            // Try the next fallback.
        }
    }
    return false
}

fun Activity.startAddContactIntent(phoneNumber: String) {
    Intent().apply {
        action = Intent.ACTION_INSERT_OR_EDIT
        type = "vnd.android.cursor.item/contact"
        putExtra(KEY_PHONE, phoneNumber)
        MeshContactHelper.addMeshPhoneInsertExtras(this)
        launchActivityIntent(this)
    }
}

private fun Context.buildAccountsConfigurationIntents(handle: PhoneAccountHandle?): List<Intent> {
    val intents = ArrayList<Intent>(6)

    // Try to open the specific PhoneAccount first, if the platform supports it.
    if (handle != null) {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            // API 26+: returns an Intent that should open account-specific settings.
            val method = telecom.javaClass.getMethod("createManagePhoneAccountIntent", PhoneAccountHandle::class.java)
            val intent = method.invoke(telecom, handle) as? Intent
            if (intent != null) intents.add(intent)
        } catch (_: Exception) {
        }

        // Some OEMs expose this action instead of (or in addition to) createManagePhoneAccountIntent().
        intents.add(
            Intent("android.telecom.action.CONFIGURE_PHONE_ACCOUNT").apply {
                putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
        )
    }

    // Generic fallbacks.
    intents.add(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
    intents.add(Intent("android.telecom.action.SHOW_CALL_SETTINGS"))
    intents.add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    intents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    return intents
}
