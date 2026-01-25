package org.fossify.phone.dialogs

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.models.contacts.Contact
import org.fossify.mesh.MeshContactHelper
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.phone.R
import org.fossify.phone.databinding.DialogMeshAddressBinding

class MeshAddressDialog(
    private val activity: BaseSimpleActivity,
    private val contact: Contact,
    private val onSaved: (() -> Unit)? = null
) {
    private val binding by activity.viewBinding(DialogMeshAddressBinding::inflate)
    private var dialog: AlertDialog? = null

    init {
        if (isPrivateContact(contact)) {
            activity.toast(R.string.mesh_address_private_contact)
        } else {
            val existing = MeshContactHelper.getMeshAddress(activity, contact.contactId.toLong())
            binding.meshAddressInput.setText(existing.orEmpty())
            binding.meshAddressInput.onTextChangeListener {
                binding.meshAddressHint.error = null
                updateActions()
            }

            binding.meshAddressPaste.setOnClickListener {
                pasteFromClipboard()
            }

            binding.meshAddressCopy.setOnClickListener {
                val address = getCurrentAddress()
                if (address.isNotBlank()) {
                    activity.copyToClipboard(address)
                }
            }

            binding.meshAddressShare.setOnClickListener {
                val address = getCurrentAddress()
                if (address.isNotBlank()) {
                    shareAddress(address)
                }
            }

            binding.meshAddressClear.setOnClickListener {
                binding.meshAddressInput.setText("")
            }

            updateActions()

            activity.getAlertDialogBuilder()
                .setPositiveButton(org.fossify.commons.R.string.save) { _, _ ->
                    saveAddress()
                }
                .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                .apply {
                    activity.setupDialogStuff(binding.root, this) {
                        dialog = it
                    }
                }
        }
    }

    private fun updateActions() {
        val hasValue = getCurrentAddress().isNotBlank()
        binding.meshAddressCopy.isEnabled = hasValue
        binding.meshAddressShare.isEnabled = hasValue
        binding.meshAddressClear.isEnabled = hasValue

        val enabledAlpha = 1f
        val disabledAlpha = 0.4f
        binding.meshAddressCopy.alpha = if (hasValue) enabledAlpha else disabledAlpha
        binding.meshAddressShare.alpha = if (hasValue) enabledAlpha else disabledAlpha
        binding.meshAddressClear.alpha = if (hasValue) enabledAlpha else disabledAlpha
    }

    private fun pasteFromClipboard() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0) ?: run {
            activity.toast(R.string.mesh_address_clipboard_empty)
            return
        }
        val text = item.coerceToText(activity).toString().trim()
        if (text.isBlank()) {
            activity.toast(R.string.mesh_address_clipboard_empty)
            return
        }
        binding.meshAddressInput.setText(text)
        binding.meshAddressInput.setSelection(binding.meshAddressInput.text?.length ?: 0)
    }

    private fun shareAddress(address: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, address)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.mesh_address_share)))
    }

    private fun saveAddress() {
        activity.handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
            if (!granted) {
                return@handlePermission
            }
            val input = getCurrentAddress()
            if (input.isBlank()) {
                MeshContactHelper.deleteMeshAddressForRawContact(activity, contact.rawId.toLong())
                activity.toast(R.string.mesh_address_removed)
                onSaved?.invoke()
                dialog?.dismiss()
                return@handlePermission
            }

            val normalized = LxmfAddress.normalize(input)
            if (!LxmfAddress.isMeshAddress(normalized)) {
                binding.meshAddressHint.error = activity.getString(R.string.mesh_address_invalid)
                return@handlePermission
            }

            MeshContactHelper.upsertMeshAddressForRawContact(
                activity,
                contact.rawId.toLong(),
                normalized
            )
            activity.toast(R.string.mesh_address_saved)
            onSaved?.invoke()
            dialog?.dismiss()
        }
    }

    private fun getCurrentAddress(): String {
        return binding.meshAddressInput.text?.toString()?.trim().orEmpty()
    }

    private fun isPrivateContact(contact: Contact): Boolean {
        return contact.rawId > 1_000_000 &&
            contact.contactId > 1_000_000 &&
            contact.rawId == contact.contactId
    }
}
