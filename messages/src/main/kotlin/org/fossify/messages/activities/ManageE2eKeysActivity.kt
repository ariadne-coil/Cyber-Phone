package org.fossify.messages.activities

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.Manifest
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageE2eKeysBinding
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.helpers.JSON_MIME_TYPE
import org.fossify.messages.helpers.MeshDiscoveryManager
import org.fossify.messages.helpers.TXT_MIME_TYPE
import org.fossify.mesh.MeshIdentityStore
import org.fossify.mesh.MeshManager
import org.fossify.mesh.MeshContactHelper

class ManageE2eKeysActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityManageE2eKeysBinding::inflate)
    private var pendingMeshAddress: String? = null

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
                    refreshProfile()
                    toast(R.string.e2e_import_successful)
                } else {
                    toast(R.string.e2e_import_failed)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scanQrCode()
            } else {
                toast(R.string.profile_mesh_scan_denied)
            }
        }

    private val scanQr =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents?.trim().orEmpty()
            if (contents.isBlank()) {
                return@registerForActivityResult
            }
            val meshAddress = MeshDiscoveryManager.extractMeshAddress(contents)
                ?: if (contents.startsWith("mesh:", ignoreCase = true)) contents.trim() else null
            if (meshAddress.isNullOrBlank()) {
                toast(R.string.profile_mesh_invalid)
                return@registerForActivityResult
            }
            pendingMeshAddress = meshAddress
            requestContactForMeshAddress()
        }

    private val pickContact =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val contactUri = result.data?.data ?: return@registerForActivityResult
            val contactId = contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            } ?: return@registerForActivityResult

            val rawId = resolveRawContactId(contactId) ?: return@registerForActivityResult
            val meshAddress = pendingMeshAddress ?: return@registerForActivityResult
            if (!hasPermission(PERMISSION_WRITE_CONTACTS)) {
                handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
                    if (granted) {
                        MeshContactHelper.upsertMeshAddressForRawContact(this, rawId, meshAddress)
                        toast(R.string.profile_mesh_saved)
                        pendingMeshAddress = null
                    }
                }
                return@registerForActivityResult
            }
            MeshContactHelper.upsertMeshAddressForRawContact(this, rawId, meshAddress)
            toast(R.string.profile_mesh_saved)
            pendingMeshAddress = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupTopAppBar(binding.manageE2eKeysAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.manageE2eKeysHolder)
        refreshProfile()
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        refreshProfile()
    }

    private fun refreshProfile() {
        binding.profileContactValue.text = getProfileDisplayName() ?: getString(R.string.profile_unknown)
        binding.profilePhoneValue.text = getProfilePhoneNumber() ?: getString(R.string.profile_unknown)
        binding.profileMeshAddressValue.text = MeshDiscoveryManager.getLocalMeshAddress(this)
            ?: getString(R.string.profile_unknown)
        binding.manageE2eKeysPublicValue.text = E2eManager.getPublicKeyBase64(this)
        binding.manageE2eKeysPrivateValue.text = E2eManager.getPrivateKeyBase64(this)
    }

    private fun setupActions() {
        binding.manageE2eKeysPublicValue.setOnClickListener {
            copyToClipboard(E2eManager.getPublicKeyBase64(this))
        }
        binding.manageE2eKeysPrivateValue.setOnClickListener {
            copyToClipboard(E2eManager.getPrivateKeyBase64(this))
        }
        binding.profileMeshAddressValue.setOnClickListener {
            val address = MeshDiscoveryManager.getLocalMeshAddress(this) ?: return@setOnClickListener
            copyToClipboard(address)
        }
        binding.profileShowQrHolder.setOnClickListener {
            showQrCode()
        }
        binding.profileScanQrHolder.setOnClickListener {
            scanQrCode()
        }
        binding.profileRotateMeshHolder.setOnClickListener {
            ConfirmationDialog(this, getString(R.string.profile_rotate_mesh)) {
                MeshIdentityStore.rotateIdentity(this)
                MeshManager.restart(this)
                refreshProfile()
                toast(R.string.profile_mesh_rotated)
            }
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
                refreshProfile()
                toast(R.string.e2e_keys_regenerated)
            }
        }
    }

    private fun showQrCode() {
        val address = MeshDiscoveryManager.getLocalMeshAddress(this) ?: return
        val content = MeshDiscoveryManager.buildMeshAddressMessage(this) ?: address
        val size = resources.getDimensionPixelSize(R.dimen.profile_qr_size)
        val bitmap = createQrBitmap(content, size) ?: return
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
    }

    private fun scanQrCode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val options = ScanOptions()
            .setPrompt(getString(R.string.profile_mesh_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(true)
        scanQr.launch(options)
    }

    private fun requestContactForMeshAddress() {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            handlePermission(PERMISSION_READ_CONTACTS) { granted ->
                if (granted) {
                    launchContactPicker()
                }
            }
            return
        }
        launchContactPicker()
    }

    private fun launchContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        pickContact.launch(intent)
    }

    private fun resolveRawContactId(contactId: Long): Long? {
        return contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun getProfileDisplayName(): String? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        return contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun getProfilePhoneNumber(): String? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        val profileId = contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        } ?: return null

        return contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(profileId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun createQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }
}
