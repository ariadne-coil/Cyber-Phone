package org.fossify.messages.extensions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getMyFileUri
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.launchViewContactIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.PERMISSION_CALL_PHONE
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.SimpleContact
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.MeshContactHelper
import org.fossify.messages.activities.ConversationDetailsActivity
import org.fossify.messages.helpers.MeshDiscoveryManager
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.WalletContactHelper
import org.fossify.mesh.lxmf.LxmfAddress
import java.io.File
import java.util.Locale

fun BaseSimpleActivity.dialNumber(phoneNumber: String, callback: (() -> Unit)? = null) {
    hideKeyboard()

    // If mesh is enabled and we have a mesh address for this contact/number, prefer mesh calling.
    // This is used by the Messages thread call button, so it must decide between mesh and PSTN.
    val meshMode = try {
        MeshConfig.newInstance(this).getMeshMode()
    } catch (_: Exception) {
        MeshMode.STANDARD_ONLY
    }

    if (meshMode != MeshMode.STANDARD_ONLY) {
        val meshAddress = try {
            when {
                LxmfAddress.isMeshLike(phoneNumber) -> LxmfAddress.normalize(phoneNumber)
                else -> MeshDiscoveryManager.getMeshAddressForPhoneNumber(this, phoneNumber)
            }
        } catch (_: Exception) {
            null
        }

        if (!meshAddress.isNullOrBlank()) {
            // MeshVoipCallActivity lives in the app module. Avoid a module dependency cycle by
            // launching it via an explicit class name.
            val allowFallback = meshMode == MeshMode.MESH_WITH_FALLBACK && !LxmfAddress.isMeshLike(phoneNumber)
            val fallbackNumber = phoneNumber.takeIf { allowFallback }
            val intent = Intent().apply {
                setClassName(packageName, "org.fossify.phone.mesh.voip.MeshVoipCallActivity")
                putExtra("mesh_voip_incoming", false)
                putExtra("mesh_voip_mesh_address", meshAddress)
                putExtra("mesh_voip_display_name", null as String?)
                putExtra("mesh_voip_fallback_number", fallbackNumber)
                putExtra("mesh_voip_allow_fallback", allowFallback)
            }
            try {
                startActivity(intent)
                callback?.invoke()
            } catch (e: Exception) {
                showErrorToast(e)
            }
            return
        }

        if (meshMode == MeshMode.MESH_ONLY) {
            toast(org.fossify.messages.R.string.mesh_delivery_failed)
            callback?.invoke()
            return
        }
    }

    handlePermission(PERMISSION_CALL_PHONE) {
        val action = if (it) Intent.ACTION_CALL else Intent.ACTION_DIAL
        Intent(action).apply {
            data = Uri.fromParts("tel", phoneNumber, null)

            try {
                startActivity(this)
                callback?.invoke()
            } catch (_: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}

fun Activity.launchViewIntent(uri: Uri, mimetype: String, filename: String) {
    // Older stored attachments (and some mesh attachments) might still be persisted as file:// URIs.
    // Convert them to FileProvider content:// URIs to avoid FileUriExposedException on modern Android.
    val safeUri = if (uri.scheme == "file") {
        val filePath = uri.path
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            try {
                if (file.exists()) getMyFileUri(file) else uri
            } catch (_: Exception) {
                // Fallback: try copying into cache/attachments which is always part of provider_paths.xml
                try {
                    val outDir = File(cacheDir, "attachments").apply { mkdirs() }
                    val ext = filename.substringAfterLast('.', "").lowercase(Locale.getDefault()).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                    val suffix = if (ext != null) ".${ext}" else ".bin"
                    val outFile = File.createTempFile("view_", suffix, outDir)
                    file.inputStream().use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    getMyFileUri(outFile)
                } catch (_: Exception) {
                    uri
                }
            }
        } else {
            uri
        }
    } else {
        uri
    }
    Intent().apply {
        action = Intent.ACTION_VIEW
        setDataAndType(safeUri, mimetype.lowercase(Locale.getDefault()))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            hideKeyboard()
            startActivity(this)
        } catch (_: ActivityNotFoundException) {
            val newMimetype = filename.getMimeType()
            if (newMimetype.isNotEmpty() && mimetype != newMimetype) {
                launchViewIntent(safeUri, newMimetype, filename)
            } else {
                toast(org.fossify.commons.R.string.no_app_found)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun Activity.startContactDetailsIntent(contact: SimpleContact) {
    val simpleContacts = "org.fossify.contacts"
    val simpleContactsDebug = "org.fossify.contacts.debug"
    if (
        contact.rawId > 1000000 &&
        contact.contactId > 1000000 &&
        contact.rawId == contact.contactId &&
        (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
    ) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            setPackage(
                if (isPackageInstalled(simpleContacts)) {
                    simpleContacts
                } else {
                    simpleContactsDebug
                }
            )

            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )

            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey = SimpleContactsHelper(this)
                .getContactLookupKey(
                    contactId = (contact).rawId.toString()
                )

            val publicUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey
            )

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
                            WalletContactHelper.dedupeWalletDestinationRowsForRawContact(this, contact.rawId.toLong())
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

fun Activity.launchConversationDetails(threadId: Long) {
    Intent(this, ConversationDetailsActivity::class.java).apply {
        putExtra(THREAD_ID, threadId)
        startActivity(this)
    }
}
