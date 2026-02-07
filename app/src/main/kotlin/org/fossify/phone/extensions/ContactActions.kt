package org.fossify.phone.extensions

import android.content.ContentValues
import android.content.Intent
import android.provider.ContactsContract
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.SimpleListItem
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.dialogs.DynamicBottomSheetChooserDialog
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.lxmf.LxmfAddress

private const val ACTION_CALL = 1
private const val ACTION_MESSAGE = 2
private const val ACTION_EDIT = 3
private const val ACTION_TOGGLE_FAVORITE = 4
private const val ACTION_DELETE = 5
private const val ACTION_CANCEL = 6

fun SimpleActivity.showContactActionsDialog(contact: Contact) {
    val isFavorite = contact.starred == 1
    val favoriteLabel = if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites

    val items = arrayOf(
        SimpleListItem(id = ACTION_CALL, textRes = R.string.contact_action_call),
        SimpleListItem(id = ACTION_MESSAGE, textRes = R.string.contact_action_message),
        SimpleListItem(id = ACTION_EDIT, textRes = R.string.contact_action_edit),
        SimpleListItem(id = ACTION_TOGGLE_FAVORITE, textRes = favoriteLabel),
        SimpleListItem(id = ACTION_DELETE, textRes = R.string.delete_contact),
        SimpleListItem(id = ACTION_CANCEL, textRes = R.string.contact_action_cancel),
    )

    DynamicBottomSheetChooserDialog.createChooser(
        fragmentManager = supportFragmentManager,
        title = null,
        items = items
    ) { selected ->
        when (selected.id) {
            ACTION_CALL -> handleContactCall(contact)
            ACTION_MESSAGE -> handleContactMessage(contact)
            ACTION_EDIT -> startContactDetailsIntent(contact)
            ACTION_TOGGLE_FAVORITE -> toggleContactFavorite(contact)
            ACTION_DELETE -> askConfirmDeleteContact(contact)
            ACTION_CANCEL -> Unit
        }
    }
}

private fun SimpleActivity.handleContactCall(contact: Contact) {
    val number = contact.pickBestPhoneNumberForCall()
    if (number.isNullOrBlank()) {
        toast(R.string.no_phone_numbers_found)
        return
    }
    startCallWithConfirmationCheck(number, contact.getNameToDisplay())
}

private fun SimpleActivity.handleContactMessage(contact: Contact) {
    val address = contact.pickBestAddressForMessaging()
    if (address.isNullOrBlank()) {
        toast(R.string.no_phone_numbers_found)
        return
    }

    // If the contact is mesh-only and mesh is disabled, don't open a conversation that cannot send.
    if (LxmfAddress.isMeshLike(address)) {
        val meshMode = MeshConfig.newInstance(this).getMeshMode()
        if (meshMode == MeshMode.STANDARD_ONLY) {
            toast(org.fossify.messages.R.string.mesh_disabled)
            return
        }
    }

    val normalized = if (LxmfAddress.isMeshLike(address)) LxmfAddress.normalize(address) else address
    val threadId = getThreadId(normalized)
    Intent(this, ThreadActivity::class.java).apply {
        putExtra(THREAD_ID, threadId)
        putExtra(THREAD_TITLE, contact.getNameToDisplay())
        putExtra(THREAD_NUMBER, normalized)
        startActivity(this)
    }
}

private fun SimpleActivity.toggleContactFavorite(contact: Contact) {
    handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
        if (!granted) return@handlePermission

        val newStarred = if (contact.starred == 1) 0 else 1
        val values = ContentValues().apply {
            put(ContactsContract.Contacts.STARRED, newStarred)
        }

        val updated = try {
            contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                values,
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(contact.contactId.toString())
            )
        } catch (_: Exception) {
            0
        }

        if (updated == 0) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        } else if (this is MainActivity) {
            refreshFragments()
        }
    }
}

private fun SimpleActivity.askConfirmDeleteContact(contact: Contact) {
    val question = String.format(getString(R.string.deletion_confirmation), "\"${contact.getNameToDisplay()}\"")
    ConfirmationDialog(this, question) {
        handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
            if (!granted) return@handlePermission
            SimpleContactsHelper(this).deleteContactRawIDs(arrayListOf(contact.rawId)) {
                runOnUiThread {
                    if (this is MainActivity) {
                        refreshFragments()
                    }
                }
            }
        }
    }
}

private fun Contact.pickBestPhoneNumberForCall(): String? {
    if (phoneNumbers.isEmpty()) return null

    fun normalizedValue(p: org.fossify.commons.models.PhoneNumber): String {
        val raw = p.value.orEmpty().trim()
        val candidate = raw.ifEmpty { p.normalizedNumber.orEmpty().trim() }
        return candidate
    }

    fun isMesh(p: org.fossify.commons.models.PhoneNumber): Boolean {
        val candidate = normalizedValue(p)
        return candidate.isNotBlank() && LxmfAddress.isMeshLike(candidate)
    }

    val primaryNonMesh = phoneNumbers.firstOrNull { it.isPrimary && !isMesh(it) && normalizedValue(it).isNotBlank() }
    if (primaryNonMesh != null) {
        return primaryNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: primaryNonMesh.value?.trim()
    }

    val firstNonMesh = phoneNumbers.firstOrNull { !isMesh(it) && normalizedValue(it).isNotBlank() }
    if (firstNonMesh != null) {
        return firstNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: firstNonMesh.value?.trim()
    }

    return null
}

private fun Contact.pickBestAddressForMessaging(): String? {
    if (phoneNumbers.isEmpty()) return null

    fun normalizedValue(p: org.fossify.commons.models.PhoneNumber): String {
        val raw = p.value.orEmpty().trim()
        val candidate = raw.ifEmpty { p.normalizedNumber.orEmpty().trim() }
        return candidate
    }

    fun isMesh(p: org.fossify.commons.models.PhoneNumber): Boolean {
        val candidate = normalizedValue(p)
        return candidate.isNotBlank() && LxmfAddress.isMeshLike(candidate)
    }

    val primaryNonMesh = phoneNumbers.firstOrNull { it.isPrimary && !isMesh(it) && normalizedValue(it).isNotBlank() }
    if (primaryNonMesh != null) {
        return primaryNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: primaryNonMesh.value?.trim()
    }

    val firstNonMesh = phoneNumbers.firstOrNull { !isMesh(it) && normalizedValue(it).isNotBlank() }
    if (firstNonMesh != null) {
        return firstNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: firstNonMesh.value?.trim()
    }

    val primaryAny = phoneNumbers.firstOrNull { it.isPrimary && normalizedValue(it).isNotBlank() }
    if (primaryAny != null) return normalizedValue(primaryAny)

    return phoneNumbers.firstOrNull { normalizedValue(it).isNotBlank() }?.let { normalizedValue(it) }
}
