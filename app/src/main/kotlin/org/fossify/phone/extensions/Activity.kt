package org.fossify.phone.extensions

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.IntentCompat
import org.fossify.commons.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.CallConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.initiateCall
import org.fossify.commons.extensions.isDefaultDialer
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.launchViewContactIntent
import org.fossify.commons.extensions.openFullScreenIntentSettings
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.PERMISSION_CALL_PHONE
import org.fossify.commons.helpers.PERMISSION_READ_PHONE_STATE
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.mesh.MeshContactHelper
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.phone.BuildConfig
import org.fossify.phone.activities.DialpadActivity
import org.fossify.phone.activities.DialerActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.dialogs.SelectSIMDialog
import org.fossify.phone.mesh.voip.MeshVoipCallActivity
import org.fossify.phone.wallet.WalletContactHelper

private fun BaseSimpleActivity.launchInternalPstnCallIntent(
    recipient: String,
    handle: PhoneAccountHandle? = null
) {
    val intent = if (hasPermission(PERMISSION_CALL_PHONE)) {
        Intent(this, DialerActivity::class.java).apply {
            action = Intent.ACTION_CALL
            data = Uri.fromParts("tel", recipient, null)
            if (handle != null) {
                putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
        }
    } else {
        Intent(this, DialpadActivity::class.java).apply {
            action = Intent.ACTION_DIAL
            data = Uri.fromParts("tel", recipient, null)
        }
    }

    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        toast(R.string.no_app_found)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun SimpleActivity.startCallIntent(
    recipient: String,
    forceSimSelector: Boolean = false
) {
    val meshMode = MeshConfig.newInstance(this).getMeshMode()
    if (LxmfAddress.isMeshLike(recipient)) {
        if (meshMode == MeshMode.STANDARD_ONLY) {
            toast(org.fossify.messages.R.string.mesh_disabled)
            return
        }
        // Mesh calls are handled in-app (VoIP) without Telecom.
        MeshVoipCallActivity.startOutgoing(
            context = this,
            meshAddress = LxmfAddress.normalize(recipient),
            displayName = null,
            fallbackNumber = null,
            allowFallback = false
        )
        return
    }

    if (isDefaultDialer()) {
        getHandleToUse(
            intent = null,
            phoneNumber = recipient,
            forceSimSelector = forceSimSelector
        ) { handle ->
            launchInternalPstnCallIntent(recipient, handle)
        }
    } else {
        launchInternalPstnCallIntent(recipient, null)
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(
    recipient: String,
    name: String,
    forceSimSelector: Boolean = false
) {
    val meshMode = MeshConfig.newInstance(this).getMeshMode()
    val isMesh = meshMode != MeshMode.STANDARD_ONLY && LxmfAddress.isMeshLike(recipient)

    if (config.showCallConfirmation) {
        CallConfirmationDialog(this, name) {
            if (isMesh) {
                MeshVoipCallActivity.startOutgoing(
                    context = this,
                    meshAddress = LxmfAddress.normalize(recipient),
                    displayName = name,
                    fallbackNumber = null,
                    allowFallback = false
                )
            } else {
                startCallIntent(recipient, forceSimSelector)
            }
        }
    } else {
        // Pass along the display name for mesh calls so they look consistent.
        if (isMesh) {
            MeshVoipCallActivity.startOutgoing(
                context = this,
                meshAddress = LxmfAddress.normalize(recipient),
                displayName = name,
                fallbackNumber = null,
                allowFallback = false
            )
        } else {
            startCallIntent(recipient, forceSimSelector)
        }
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(contact: Contact) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(
            activity = this,
            callee = contact.getNameToDisplay()
        ) {
            initiateCall(contact) { launchInternalPstnCallIntent(it) }
        }
    } else {
        initiateCall(contact) { launchInternalPstnCallIntent(it) }
    }
}

fun SimpleActivity.launchCreateNewContactIntent() {
    Intent().apply {
        action = Intent.ACTION_INSERT
        data = ContactsContract.Contacts.CONTENT_URI
        MeshContactHelper.addMeshPhoneInsertExtras(this)
        WalletContactHelper.addWalletInsertExtras(this)
        launchActivityIntent(this)
    }
}

fun BaseSimpleActivity.callContactWithSim(
    recipient: String,
    useMainSIM: Boolean
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        val wantedSimIndex = if (useMainSIM) 0 else 1
        val handle = getAvailableSIMCardLabels()
            .sortedBy { it.id }
            .getOrNull(wantedSimIndex)?.handle
        launchInternalPstnCallIntent(recipient, handle)
    }
}

fun BaseSimpleActivity.callContactWithSimWithConfirmationCheck(
    recipient: String,
    name: String,
    useMainSIM: Boolean
) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(this, name) {
            callContactWithSim(recipient, useMainSIM)
        }
    } else {
        callContactWithSim(recipient, useMainSIM)
    }
}

// handle private contacts differently, only Simple Contacts Pro can open them
fun Activity.startContactDetailsIntent(contact: Contact) {
    val simpleContacts = "org.fossify.contacts"
    val simpleContactsDebug = "org.fossify.contacts.debug"
    if (contact.rawId > 1000000 && contact.contactId > 1000000 && contact.rawId == contact.contactId &&
        (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
    ) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            `package` =
                if (isPackageInstalled(simpleContacts)) simpleContacts else simpleContactsDebug
            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )
            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey =
                SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())
            val publicUri =
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            runOnUiThread {
                val launchEditor = {
                    Intent(Intent.ACTION_EDIT).apply {
                        data = publicUri
                        putExtra("finishActivityOnSaveCompleted", true)
                        launchActivityIntent(this)
                    }
                }
                if (this is BaseSimpleActivity && contact.rawId > 0) {
                    handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
                        if (granted) {
                            MeshContactHelper.ensureMeshPhoneRowForRawContact(this, contact.rawId.toLong())
                            WalletContactHelper.ensureWalletDestinationRowForRawContact(this, contact.rawId.toLong())
                        }
                        launchEditor()
                    }
                } else {
                    launchEditor()
                }
            }
        }
    }
}

// used at devices with multiple SIM cards
@SuppressLint("MissingPermission")
fun SimpleActivity.getHandleToUse(
    intent: Intent?,
    phoneNumber: String,
    forceSimSelector: Boolean = false,
    callback: (handle: PhoneAccountHandle?) -> Unit
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        if (it) {
            val defaultHandle =
                telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
            when {
                forceSimSelector -> showSelectSimDialog(phoneNumber, callback)
                intent?.hasExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE) == true -> {
                    val handle = IntentCompat.getParcelableExtra(
                        intent,
                        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                        PhoneAccountHandle::class.java
                    )
                    if (handle != null) {
                        callback(handle)
                    } else {
                        showSelectSimDialog(phoneNumber, callback)
                    }
                }

                config.getCustomSIM(phoneNumber) != null -> {
                    callback(config.getCustomSIM(phoneNumber))
                }

                defaultHandle != null -> callback(defaultHandle)
                else -> showSelectSimDialog(phoneNumber, callback)
            }
        }
    }
}

fun SimpleActivity.showSelectSimDialog(
    phoneNumber: String,
    callback: (handle: PhoneAccountHandle?) -> Unit
) = SelectSIMDialog(
    activity = this,
    phoneNumber = phoneNumber,
    onDismiss = {
        if (this is DialerActivity) {
            finish()
        }
    }
) { handle ->
    callback(handle)
}

fun SimpleActivity.handleFullScreenNotificationsPermission(callback: (granted: Boolean) -> Unit) {
    handleNotificationPermission { granted ->
        if (granted) {
            if (canUseFullScreenIntent()) {
                callback(true)
            } else {
                PermissionRequiredDialog(
                    activity = this,
                    textId = R.string.allow_full_screen_notifications_incoming_calls,
                    positiveActionCallback = {
                        @SuppressLint("NewApi")
                        openFullScreenIntentSettings(BuildConfig.APPLICATION_ID)
                    },
                    negativeActionCallback = {
                        callback(false)
                    }
                )
            }
        } else {
            PermissionRequiredDialog(
                activity = this,
                textId = R.string.allow_notifications_incoming_calls,
                positiveActionCallback = {
                    openNotificationSettings()
                },
                negativeActionCallback = {
                    callback(false)
                }
            )
        }
    }
}
