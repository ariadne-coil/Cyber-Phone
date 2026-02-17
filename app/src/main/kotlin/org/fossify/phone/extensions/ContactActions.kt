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
import org.fossify.phone.databinding.DialogContactWalletAddressBinding
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.helpers.THREAD_TEXT
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshMode
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.wallet.FederationDirectoryManager
import org.fossify.phone.wallet.ExchangeRateManager
import org.fossify.phone.wallet.FedimintWalletManager
import org.fossify.phone.wallet.LdkWalletManager
import org.fossify.phone.wallet.WalletContactHelper
import org.fossify.phone.wallet.WalletUiDialogs
import org.fossify.phone.databinding.DialogWalletCreateInvoiceBinding
import org.fossify.phone.wallet.FederationEntry
import org.fossify.phone.wallet.WalletPolicy
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.WalletTokenParser
import java.text.NumberFormat
import java.util.Locale

private const val ACTION_CALL = 1
private const val ACTION_MESSAGE = 2
private const val ACTION_EDIT = 3
private const val ACTION_TOGGLE_FAVORITE = 4
private const val ACTION_DELETE = 5
private const val ACTION_CANCEL = 6
private const val ACTION_WALLET_PAY = 7
private const val ACTION_WALLET_REQUEST = 8
private const val ACTION_WALLET_SET_ADDRESS = 9

fun SimpleActivity.showContactActionsDialog(contact: Contact) {
    val isFavorite = contact.starred == 1
    val favoriteLabel = if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
    val isPrivateContact = contact.rawId > 1_000_000 && contact.contactId > 1_000_000 && contact.rawId == contact.contactId
    val walletDestinations = if (isPrivateContact) {
        WalletContactHelper.WalletDestinations()
    } else {
        WalletContactHelper.getWalletDestinations(this, contact.rawId.toLong(), contact.contactId)
    }
    val walletDest = walletDestinations.preferred().orEmpty()
    val hasWallet = !walletDestinations.isEmpty()
    val walletEditLabel = if (hasWallet) R.string.contact_action_edit_wallet_address else R.string.contact_action_set_wallet_address

    val list = ArrayList<SimpleListItem>()
    list.add(SimpleListItem(id = ACTION_CALL, textRes = R.string.contact_action_call))
    list.add(SimpleListItem(id = ACTION_MESSAGE, textRes = R.string.contact_action_message))
    if (hasWallet) {
        list.add(SimpleListItem(id = ACTION_WALLET_PAY, textRes = R.string.contact_action_pay))
    }
    list.add(SimpleListItem(id = ACTION_WALLET_REQUEST, textRes = R.string.contact_action_request_payment))
    if (!isPrivateContact) {
        list.add(SimpleListItem(id = ACTION_WALLET_SET_ADDRESS, textRes = walletEditLabel))
    }
    list.add(SimpleListItem(id = ACTION_EDIT, textRes = R.string.contact_action_edit))
    list.add(SimpleListItem(id = ACTION_TOGGLE_FAVORITE, textRes = favoriteLabel))
    list.add(SimpleListItem(id = ACTION_DELETE, textRes = R.string.delete_contact))
    list.add(SimpleListItem(id = ACTION_CANCEL, textRes = R.string.contact_action_cancel))

    val items = list.toTypedArray()

    DynamicBottomSheetChooserDialog.createChooser(
        fragmentManager = supportFragmentManager,
        title = null,
        items = items
    ) { selected ->
        when (selected.id) {
            ACTION_CALL -> handleContactCall(contact)
            ACTION_MESSAGE -> handleContactMessage(contact)
            ACTION_WALLET_PAY -> handleWalletPay(walletDest)
            ACTION_WALLET_REQUEST -> handleWalletRequest(contact)
            ACTION_WALLET_SET_ADDRESS -> handleWalletSetAddress(
                contact = contact,
                existingOnchain = walletDestinations.onchain,
                existingLightning = walletDestinations.lightning
            )
            ACTION_EDIT -> startContactDetailsIntent(contact)
            ACTION_TOGGLE_FAVORITE -> toggleContactFavorite(contact)
            ACTION_DELETE -> askConfirmDeleteContact(contact)
            ACTION_CANCEL -> Unit
        }
    }
}

private fun SimpleActivity.handleWalletPay(destination: String) {
    if (destination.isBlank()) {
        toast(R.string.contact_action_set_wallet_address)
        return
    }
    val intent = Intent().apply {
        setClassName(this@handleWalletPay, "org.fossify.phone.activities.WalletPayActivity")
        putExtra(org.fossify.messages.helpers.EXTRA_WALLET_DESTINATION, destination)
    }
    startActivity(intent)
}

private fun SimpleActivity.handleWalletSetAddress(
    contact: Contact,
    existingOnchain: String?,
    existingLightning: String?
) {
    handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
        if (!granted) return@handlePermission

        val vb = DialogContactWalletAddressBinding.inflate(this@handleWalletSetAddress.layoutInflater)
        vb.contactWalletAddress.setText(existingOnchain.orEmpty())
        vb.contactWalletLightning.setText(existingLightning.orEmpty())

        getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                if (contact.rawId > 0) {
                    WalletContactHelper.deleteWalletDestination(this, contact.rawId.toLong())
                    toast(R.string.done)
                }
            }
            .setPositiveButton(R.string.ok, null)
            .apply {
                setupDialogStuff(vb.root, this, R.string.contact_wallet_address) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val onchain = vb.contactWalletAddress.text?.toString()?.trim().orEmpty()
                        val lightning = vb.contactWalletLightning.text?.toString()?.trim().orEmpty()
                        if (onchain.isBlank() && lightning.isBlank()) {
                            toast(R.string.contact_wallet_destination_required)
                            return@setOnClickListener
                        }
                        if (contact.rawId <= 0) {
                            toast(org.fossify.commons.R.string.unknown_error_occurred)
                            return@setOnClickListener
                        }
                        val rawId = contact.rawId.toLong()
                        if (onchain.isBlank()) {
                            WalletContactHelper.deleteWalletOnchainDestination(this@handleWalletSetAddress, rawId)
                        } else {
                            WalletContactHelper.upsertWalletOnchainDestination(this@handleWalletSetAddress, rawId, onchain)
                        }

                        if (lightning.isBlank()) {
                            WalletContactHelper.deleteWalletLightningDestination(this@handleWalletSetAddress, rawId)
                        } else {
                            WalletContactHelper.upsertWalletLightningDestination(this@handleWalletSetAddress, rawId, lightning)
                        }
                        toast(R.string.done)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}

private fun SimpleActivity.handleWalletRequest(contact: Contact) {
    val selectedFederation = FederationDirectoryManager.getSelectedFederation(this)
    if (selectedFederation == null) {
        toast(R.string.wallet_select_federation)
        return
    }
    val isFm = FederationDirectoryManager.isFedimintFederation(selectedFederation)

    val address = contact.pickBestAddressForMessaging()
    if (address.isNullOrBlank()) {
        toast(R.string.no_phone_numbers_found)
        return
    }

    // Let the user decide between Lightning invoice and on-chain address (if supported).
    val items = if (isFm) {
        arrayOf(getString(R.string.wallet_receive_lightning))
    } else {
        arrayOf(
            getString(R.string.wallet_receive_lightning),
            getString(R.string.wallet_receive_onchain),
        )
    }

    getAlertDialogBuilder()
        .setItems(items) { _, which ->
            when {
                which == 0 -> showInvoiceAndPrefillThread(contact, address, selectedFederation)
                !isFm && which == 1 -> showAddressAndPrefillThread(contact, address, selectedFederation)
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

private fun SimpleActivity.showInvoiceAndPrefillThread(
    contact: Contact,
    threadAddress: String,
    federation: FederationEntry,
) {
    val isFm = FederationDirectoryManager.isFedimintFederation(federation)
    val vb = DialogWalletCreateInvoiceBinding.inflate(this@showInvoiceAndPrefillThread.layoutInflater)
    vb.walletInvoiceAmount.setText("")
    vb.walletInvoiceMemo.setText(getString(R.string.app_launcher_name))

    getAlertDialogBuilder()
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.wallet_create_invoice, null)
        .apply {
            setupDialogStuff(vb.root, this, R.string.wallet_receive_lightning) { alertDialog ->
                alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val amountSats = vb.walletInvoiceAmount.text?.toString()?.trim()?.toLongOrNull()
                    val memo = vb.walletInvoiceMemo.text?.toString()?.trim().orEmpty()
                    val txLimitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                        .format(WalletPolicy.MAX_SINGLE_TX_SATS)

                    if (isFm && (amountSats == null || amountSats <= 0L)) {
                        toast(R.string.wallet_fedimint_fixed_amount_required)
                        return@setOnClickListener
                    }
                    if (amountSats != null && !WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                        toast(getString(R.string.wallet_amount_over_limit, txLimitText))
                        return@setOnClickListener
                    }
                    alertDialog.dismiss()

                    ensureBackgroundThread {
                        val started = if (isFm) {
                            FedimintWalletManager.ensureStartedBlocking(this@showInvoiceAndPrefillThread, federation)
                        } else {
                            LdkWalletManager.ensureStartedBlocking(this@showInvoiceAndPrefillThread, federation)
                        }
                        val rate = ExchangeRateManager.getCachedUsdRate(this@showInvoiceAndPrefillThread)
                        val expiry = WalletPolicy.invoiceExpirySeconds(amountSats, rate)
                        val invoice = if (!started) {
                            null
                        } else if (isFm) {
                            FedimintWalletManager.createBolt11InvoiceBlocking(
                                context = this@showInvoiceAndPrefillThread,
                                federation = federation,
                                amountSats = amountSats ?: 0L,
                                memo = memo,
                                expirySeconds = expiry
                            )
                        } else {
                            LdkWalletManager.createBolt11Invoice(amountSats, memo, expirySeconds = expiry)
                        }
                        if (invoice != null) {
                            config.setWalletLastInvoiceForFederation(federation.id, invoice)
                            config.setWalletLastInvoiceCreatedMsForFederation(federation.id, System.currentTimeMillis())
                        }

                        runOnUiThread {
                            if (invoice == null) {
                                val err = if (isFm) FedimintWalletManager.getLastErrorMessage() else LdkWalletManager.getLastErrorMessage()
                                toast(getString(R.string.wallet_invoice_failed, err.orEmpty()))
                            } else {
                                val invoiceMessage = WalletTokenParser.buildLightningInvoiceMessage(
                                    invoice = invoice,
                                    federationId = federation.id,
                                    federationName = federation.name,
                                ).ifBlank { invoice }
                                WalletUiDialogs.showInvoicePreviewDialog(
                                    activity = this@showInvoiceAndPrefillThread,
                                    federation = federation,
                                    invoiceMessage = invoiceMessage,
                                    onSendInMessages = { payload ->
                                        openThreadWithPrefill(contact, threadAddress, payload)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
}

private fun SimpleActivity.showAddressAndPrefillThread(
    contact: Contact,
    threadAddress: String,
    federation: FederationEntry,
) {
    if (FederationDirectoryManager.isFedimintFederation(federation)) {
        toast(R.string.wallet_fedimint_no_onchain)
        return
    }
    ensureBackgroundThread {
        val started = LdkWalletManager.ensureStartedBlocking(this@showAddressAndPrefillThread, federation)
        val address = if (started) LdkWalletManager.newOnchainAddress() else null
        if (address != null) {
            config.setWalletLastOnchainAddressForFederation(federation.id, address)
            config.setWalletLastOnchainAddressCreatedMsForFederation(federation.id, System.currentTimeMillis())
        }

        runOnUiThread {
            if (address == null) {
                toast(getString(R.string.wallet_address_failed, LdkWalletManager.getLastErrorMessage().orEmpty()))
            } else {
                openThreadWithPrefill(contact, threadAddress, address)
            }
        }
    }
}

private fun SimpleActivity.openThreadWithPrefill(contact: Contact, address: String, text: String) {
    val normalized = if (LxmfAddress.isMeshLike(address)) LxmfAddress.normalize(address) else address
    val threadId = getThreadId(normalized)
    Intent(this, ThreadActivity::class.java).apply {
        putExtra(THREAD_ID, threadId)
        putExtra(THREAD_TITLE, contact.getNameToDisplay())
        putExtra(THREAD_NUMBER, normalized)
        putExtra(THREAD_TEXT, text)
        startActivity(this)
    }
}

private fun SimpleActivity.handleContactCall(contact: Contact) {
    val meshMode = MeshConfig.newInstance(this).getMeshMode()
    val meshAddress = if (meshMode != MeshMode.STANDARD_ONLY) contact.pickBestMeshAddress() else null
    val number = contact.pickBestPhoneNumberForCall()

    // Prefer mesh calling if enabled and available. The actual call is handled in-app (VoIP),
    // and we keep the exact same UI surface as PSTN calls.
    val target = when {
        !meshAddress.isNullOrBlank() && meshMode != MeshMode.STANDARD_ONLY -> meshAddress
        !number.isNullOrBlank() -> number
        else -> null
    }

    if (target == null) {
        toast(R.string.no_phone_numbers_found)
        return
    }

    startCallWithConfirmationCheck(target, contact.getNameToDisplay())
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

// Telecom mesh calling has been intentionally removed in favor of in-app VoIP calls.

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

    fun isWallet(p: org.fossify.commons.models.PhoneNumber): Boolean {
        val candidate = normalizedValue(p)
        val labeled =
            p.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM &&
                (p.label.equals("Bitcoin", ignoreCase = true) || p.label.equals("Lightning", ignoreCase = true))
        return labeled ||
            candidate.startsWith("bitcoin:", ignoreCase = true) ||
            candidate.startsWith("ln", ignoreCase = true) ||
            (candidate.contains('@') && !candidate.contains(' ')) ||
            candidate.startsWith("bc1", ignoreCase = true) ||
            candidate.startsWith("tb1", ignoreCase = true) ||
            candidate.startsWith("bcrt1", ignoreCase = true)
    }

    val primaryNonMesh = phoneNumbers.firstOrNull { it.isPrimary && !isMesh(it) && !isWallet(it) && normalizedValue(it).isNotBlank() }
    if (primaryNonMesh != null) {
        return primaryNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: primaryNonMesh.value?.trim()
    }

    val firstNonMesh = phoneNumbers.firstOrNull { !isMesh(it) && !isWallet(it) && normalizedValue(it).isNotBlank() }
    if (firstNonMesh != null) {
        return firstNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: firstNonMesh.value?.trim()
    }

    return null
}

private fun Contact.pickBestMeshAddress(): String? {
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

    val primaryMesh = phoneNumbers.firstOrNull { it.isPrimary && isMesh(it) }
    if (primaryMesh != null) return LxmfAddress.normalize(normalizedValue(primaryMesh))

    val firstMesh = phoneNumbers.firstOrNull { isMesh(it) }
    return firstMesh?.let { LxmfAddress.normalize(normalizedValue(it)) }
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

    fun isWallet(p: org.fossify.commons.models.PhoneNumber): Boolean {
        val candidate = normalizedValue(p)
        val labeled =
            p.type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM &&
                (p.label.equals("Bitcoin", ignoreCase = true) || p.label.equals("Lightning", ignoreCase = true))
        return labeled ||
            candidate.startsWith("bitcoin:", ignoreCase = true) ||
            candidate.startsWith("ln", ignoreCase = true) ||
            (candidate.contains('@') && !candidate.contains(' ')) ||
            candidate.startsWith("bc1", ignoreCase = true) ||
            candidate.startsWith("tb1", ignoreCase = true) ||
            candidate.startsWith("bcrt1", ignoreCase = true)
    }

    val primaryNonMesh = phoneNumbers.firstOrNull { it.isPrimary && !isMesh(it) && !isWallet(it) && normalizedValue(it).isNotBlank() }
    if (primaryNonMesh != null) {
        return primaryNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: primaryNonMesh.value?.trim()
    }

    val firstNonMesh = phoneNumbers.firstOrNull { !isMesh(it) && !isWallet(it) && normalizedValue(it).isNotBlank() }
    if (firstNonMesh != null) {
        return firstNonMesh.normalizedNumber.takeIf { it.isNotBlank() } ?: firstNonMesh.value?.trim()
    }

    val primaryAny = phoneNumbers.firstOrNull { it.isPrimary && !isWallet(it) && normalizedValue(it).isNotBlank() }
    if (primaryAny != null) return normalizedValue(primaryAny)

    return phoneNumbers.firstOrNull { !isWallet(it) && normalizedValue(it).isNotBlank() }?.let { normalizedValue(it) }
}
