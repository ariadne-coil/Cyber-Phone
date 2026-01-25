package org.fossify.messages.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.messages.R
import org.fossify.messages.databinding.DialogEditContactKeyBinding

class EditContactKeyDialog(
    private val activity: Activity,
    private val currentKey: String?,
    private val callback: (key: String?) -> Unit,
) {
    private var dialog: AlertDialog? = null

    init {
        val binding = DialogEditContactKeyBinding.inflate(activity.layoutInflater).apply {
            editContactKeyText.setText(currentKey.orEmpty())
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.e2e_edit_contact_key) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.showKeyboard(binding.editContactKeyText)
                    alertDialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                        val key = binding.editContactKeyText.text?.toString()?.trim().orEmpty()
                        callback(key.ifEmpty { null })
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
