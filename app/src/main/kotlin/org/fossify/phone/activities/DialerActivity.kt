package org.fossify.phone.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.PERMISSION_CALL_PHONE
import org.fossify.commons.helpers.REQUEST_CODE_SET_DEFAULT_DIALER
import org.fossify.phone.R
import org.fossify.phone.extensions.canLaunchAccountsConfiguration
import org.fossify.phone.extensions.getHandleToUse
import org.fossify.phone.extensions.launchAccountsConfiguration
import org.fossify.mesh.MeshManager
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.phone.mesh.MeshCallContactHelper
import org.fossify.phone.mesh.voip.MeshVoipCallActivity

class DialerActivity : SimpleActivity() {
    private var callNumber: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.action == Intent.ACTION_CALL && intent.data != null) {
            callNumber = intent.data

            // make sure Simple Dialer is the default Phone app before initiating an outgoing call
            if (!isDefaultDialer()) {
                launchSetDefaultDialerIntent()
            } else {
                initOutgoingCall()
            }
        } else {
            toast(R.string.unknown_error_occurred)
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    private fun initOutgoingCall() {
        try {
            val rawNumber = callNumber.toString().replace("tel:", "")
            if (isNumberBlocked(rawNumber, getBlockedNumbers())) {
                toast(R.string.calling_blocked_number)
                finish()
                return
            }

            val meshConfig = MeshConfig.newInstance(this)
            val meshMode = meshConfig.getMeshMode()
            val isMeshLike = LxmfAddress.isMeshLike(rawNumber)
            val meshAddress = if (meshMode != MeshMode.STANDARD_ONLY) {
                // If the user is explicitly calling a mesh address (no PSTN number), use it directly.
                // Otherwise, look up the mesh address for a regular phone number contact.
                if (isMeshLike) {
                    LxmfAddress.normalize(rawNumber)
                } else {
                    MeshCallContactHelper.getMeshAddressForNumber(this, rawNumber)
                }
            } else null

            if (!meshAddress.isNullOrBlank() && meshMode != MeshMode.STANDARD_ONLY) {
                MeshManager.ensureRunning(this)
                val displayName = if (!isMeshLike) {
                    MeshCallContactHelper.getContactName(this, rawNumber)
                } else {
                    null
                }
                MeshVoipCallActivity.startOutgoing(
                    context = this,
                    meshAddress = meshAddress,
                    displayName = displayName,
                    fallbackNumber = if (!isMeshLike) rawNumber else null,
                    allowFallback = !isMeshLike && meshMode == MeshMode.MESH_WITH_FALLBACK
                )
                finish()
                return
            } else if (meshMode == MeshMode.MESH_ONLY) {
                toast(R.string.mesh_delivery_failed)
                finish()
                return
            }

            placeTelCall()
        } catch (e: Exception) {
            showErrorToast(e)
            finish()
        }
    }

    // Telecom-based mesh calling has been removed in favor of in-app VoIP mesh calls.

    @SuppressLint("MissingPermission")
    private fun placeTelCall() {
        handlePermission(PERMISSION_CALL_PHONE) { granted ->
            if (!granted) {
                toast(R.string.unknown_error_occurred)
                finish()
                return@handlePermission
            }
            getHandleToUse(intent, callNumber.toString()) { handle ->
                if (handle != null) {
                    Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, false)
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                        telecomManager.placeCall(callNumber, this)
                    }
                }
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            if (!isDefaultDialer()) {
                try {
                    hideKeyboard()
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        startActivity(this)
                    }
                    toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
                } catch (ignored: Exception) {
                }
                finish()
            } else {
                initOutgoingCall()
            }
        }
    }
}
