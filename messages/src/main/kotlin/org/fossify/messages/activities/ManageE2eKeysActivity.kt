package org.fossify.messages.activities

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageE2eKeysBinding
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.helpers.JSON_MIME_TYPE
import org.fossify.messages.helpers.TXT_MIME_TYPE

class ManageE2eKeysActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityManageE2eKeysBinding::inflate)

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(TXT_MIME_TYPE)) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            try {
                val data = E2eManager.buildBackupData(this)
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(data.toByteArray(Charsets.UTF_8))
                }
                toast(R.string.e2e_backup_successful)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                if (E2eManager.importBackupData(this, content)) {
                    refreshKeys()
                    toast(R.string.e2e_import_successful)
                } else {
                    toast(R.string.e2e_import_failed)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupTopAppBar(binding.manageE2eKeysAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.manageE2eKeysHolder)
        refreshKeys()
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        refreshKeys()
    }

    private fun refreshKeys() {
        binding.manageE2eKeysPublicValue.text = E2eManager.getPublicKeyBase64(this)
        binding.manageE2eKeysPrivateValue.text = E2eManager.getPrivateKeyBase64(this)
    }

    private fun setupActions() {
        binding.manageE2eKeysCopyPublicHolder.setOnClickListener {
            copyToClipboard(E2eManager.getPublicKeyBase64(this))
        }
        binding.manageE2eKeysCopyPrivateHolder.setOnClickListener {
            copyToClipboard(E2eManager.getPrivateKeyBase64(this))
        }
        binding.manageE2eKeysBackupHolder.setOnClickListener {
            createDocument.launch("cyber_phone_e2e_keys.txt")
        }
        binding.manageE2eKeysImportHolder.setOnClickListener {
            openDocument.launch(arrayOf(TXT_MIME_TYPE, JSON_MIME_TYPE, "text/plain"))
        }
        binding.manageE2eKeysRegenerateHolder.setOnClickListener {
            ConfirmationDialog(this, getString(R.string.e2e_regenerate_confirmation)) {
                E2eManager.regenerateKeyPair(this)
                refreshKeys()
                toast(R.string.e2e_keys_regenerated)
            }
        }
    }
}
