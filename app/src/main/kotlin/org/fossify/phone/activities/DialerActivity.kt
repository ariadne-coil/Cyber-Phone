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
import org.fossify.commons.helpers.REQUEST_CODE_SET_DEFAULT_DIALER
import org.fossify.phone.R
import org.fossify.phone.extensions.canLaunchAccountsConfiguration
import org.fossify.phone.extensions.getHandleToUse
import org.fossify.phone.extensions.launchAccountsConfiguration
import org.fossify.mesh.MeshManager
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.call.MeshCallQuality
import org.fossify.mesh.call.MeshCallRouter
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.phone.mesh.MeshCallContactHelper
import org.fossify.phone.mesh.MeshCallController

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
                // Ensure the mesh backend is running before probing, otherwise we may always fall back.
                MeshManager.ensureRunning(this)
                // Do not fall back to PSTN when the "number" is actually a mesh address.
                attemptMeshCall(rawNumber, meshAddress, meshMode, allowTelFallback = !isMeshLike)
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

    private fun attemptMeshCall(
        phoneNumber: String,
        meshAddress: String,
        meshMode: MeshMode,
        allowTelFallback: Boolean
    ) {
        val destinationHash = LxmfAddress.decode(meshAddress)
        if (destinationHash == null) {
            if (allowTelFallback && meshMode == MeshMode.MESH_WITH_FALLBACK) {
                placeTelCall()
            } else {
                toast(R.string.mesh_invalid_address)
                finish()
            }
            return
        }

        val preferredQuality = MeshCallQuality.fromId(MeshConfig.newInstance(this).meshCallQuality)
        MeshCallRouter.probe(
            context = this,
            remoteDeliveryHash = destinationHash,
            preferredQuality = preferredQuality,
            timeoutMs = 4000L
        ) { result ->
            runOnUiThread {
                val destination = result.remoteDestination
                if (result.success && destination != null) {
                    val displayName = MeshCallContactHelper.getContactName(this, phoneNumber)
                    val placed = MeshCallController.placeMeshCall(
                        context = this,
                        remoteDeliveryHash = destinationHash,
                        remoteCallHash = result.remoteCallHash,
                        remoteDestination = destination,
                        quality = result.quality,
                        displayName = displayName,
                        phoneNumber = phoneNumber
                    )
                    if (placed) {
                        finish()
                    } else {
                        toast(R.string.mesh_call_account_disabled)
                        if (canLaunchAccountsConfiguration()) {
                            ConfirmationDialog(this@DialerActivity, getString(R.string.mesh_call_account_open_settings)) {
                                launchAccountsConfiguration()
                                finish()
                            }
                        } else {
                            finish()
                        }
                    }
                } else {
                    if (allowTelFallback && meshMode == MeshMode.MESH_WITH_FALLBACK) {
                        placeTelCall()
                    } else {
                        toast(R.string.mesh_delivery_failed)
                        finish()
                    }
                }
            }
        }
    }

    private fun placeTelCall() {
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
