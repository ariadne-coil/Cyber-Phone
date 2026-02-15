package org.fossify.phone.fragments

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.ContactsContract
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.shareTextIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.DialogContactWalletAddressBinding
import org.fossify.phone.databinding.DialogWalletCreateInvoiceBinding
import org.fossify.phone.databinding.DialogWalletSendBinding
import org.fossify.phone.databinding.FragmentWalletContentBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.wallet.ExchangeRateManager
import org.fossify.phone.wallet.FederationDirectoryManager
import org.fossify.phone.wallet.FederationEntry
import org.fossify.phone.wallet.FedimintWalletManager
import org.fossify.phone.wallet.LdkWalletManager
import org.fossify.phone.wallet.WalletBackupCrypto
import org.fossify.phone.wallet.WalletContactHelper
import org.fossify.phone.wallet.WalletPaymentsAdapter
import org.fossify.phone.wallet.WalletPolicy
import org.fossify.phone.wallet.WalletStoragePaths
import org.fossify.messages.activities.NewConversationActivity
import org.lightningdevkit.ldknode.Bolt11Invoice
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class WalletFragment(context: Context, attributeSet: AttributeSet) :
    MyViewPagerFragment<WalletFragment.WalletInnerBinding>(context, attributeSet),
    RefreshItemsListener {

    private lateinit var binding: FragmentWalletContentBinding
    private val paymentsAdapter = WalletPaymentsAdapter { /* details dialog later */ }

    private var allFederations: List<FederationEntry> = emptyList()

    @Volatile
    private var isRefreshing: Boolean = false

    @Volatile
    private var refreshGeneration: Long = 0L

    @Volatile
    private var pendingRefreshRequested: Boolean = false

    @Volatile
    private var pendingRefreshForce: Boolean = false

    @Volatile
    private var fedimintStartInFlightForId: String? = null

    @Volatile
    private var lastFedimintStartAttemptId: String = ""

    @Volatile
    private var lastFedimintStartAttemptMs: Long = 0L

    private var activeSendDestination: com.google.android.material.textfield.TextInputEditText? = null

    private val maxBackupPayloadBytes = 16L * 1024L * 1024L

    private fun isFedimint(selected: FederationEntry?): Boolean {
        return selected?.kind?.trim()?.equals("fedimint", ignoreCase = true) == true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentWalletContentBinding.bind(this)
        innerBinding = WalletInnerBinding()
    }

    override fun setupFragment() {
        binding.walletPaymentsList.adapter = paymentsAdapter

        binding.walletSwipeRefresh.setOnRefreshListener {
            refreshAll(force = true)
        }

        binding.walletMyInvoiceValue.setOnClickListener {
            val selected = FederationDirectoryManager.getSelectedFederation(context)
            val invoice = selected?.let { context.config.getWalletLastInvoiceForFederation(it.id) }.orEmpty().trim()
            if (invoice.isBlank()) {
                showCreateInvoiceDialog()
            } else {
                activity?.copyToClipboard(invoice)
                activity?.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
            }
        }
        binding.walletMyInvoiceValue.setOnLongClickListener {
            val selected = FederationDirectoryManager.getSelectedFederation(context)
            val invoice = selected?.let { context.config.getWalletLastInvoiceForFederation(it.id) }.orEmpty().trim()
            if (invoice.isNotBlank()) {
                showShareSendRegenerateDialog(
                    titleRes = R.string.wallet_invoice,
                    value = invoice,
                    onRegenerate = { showCreateInvoiceDialog() }
                )
            } else {
                showCreateInvoiceDialog()
            }
            true
        }

        binding.walletMyAddressValue.setOnClickListener {
            val selected = FederationDirectoryManager.getSelectedFederation(context)
            if (isFedimint(selected)) {
                activity?.toast(R.string.wallet_fedimint_no_onchain)
                return@setOnClickListener
            }
            val address = selected?.let { context.config.getWalletLastOnchainAddressForFederation(it.id) }.orEmpty().trim()
            if (address.isBlank()) {
                showNewAddressDialog()
            } else {
                activity?.copyToClipboard(address)
                activity?.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
            }
        }
        binding.walletMyAddressValue.setOnLongClickListener {
            val selected = FederationDirectoryManager.getSelectedFederation(context)
            if (isFedimint(selected)) {
                activity?.toast(R.string.wallet_fedimint_no_onchain)
                return@setOnLongClickListener true
            }
            val address = selected?.let { context.config.getWalletLastOnchainAddressForFederation(it.id) }.orEmpty().trim()
            if (address.isNotBlank()) {
                showShareSendRegenerateDialog(
                    titleRes = R.string.wallet_onchain_address,
                    value = address,
                    onRegenerate = { showNewAddressDialog() }
                )
            } else {
                showNewAddressDialog()
            }
            true
        }

        binding.walletMyBip21Value.setOnClickListener {
            val selected = FederationDirectoryManager.getSelectedFederation(context)
            if (isFedimint(selected)) {
                activity?.toast(R.string.wallet_fedimint_no_onchain)
                return@setOnClickListener
            }
            val uri = getBip21ForSelected(selected).trim()
            if (uri.isBlank()) {
                // Generate missing parts (invoice + address) so the unified URI becomes usable.
                ensureDefaultReceiveData(selected)
            } else {
                activity?.copyToClipboard(uri)
                activity?.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
            }
        }
        binding.walletMyBip21Value.setOnLongClickListener {
            val selected = FederationDirectoryManager.getSelectedFederation(context)
            if (isFedimint(selected)) {
                activity?.toast(R.string.wallet_fedimint_no_onchain)
                return@setOnLongClickListener true
            }
            val uri = getBip21ForSelected(selected).trim()
            if (uri.isNotBlank()) {
                showShareSendRegenerateDialog(
                    titleRes = R.string.wallet_my_unified_uri,
                    value = uri,
                    onRegenerate = { regenerateUnifiedUri(selected) }
                )
            } else {
                regenerateUnifiedUri(selected)
            }
            true
        }

        binding.walletSelectFederationButton.setOnClickListener {
            showSelectFederationDialog()
        }
        binding.walletFederationCard.setOnClickListener {
            showSelectFederationDialog()
        }
        binding.walletSend.setOnClickListener {
            showSendDialog()
        }
        binding.walletReceive.setOnClickListener {
            showReceiveOptionsDialog()
        }
        binding.walletSync.setOnClickListener {
            refreshAll(force = true)
        }
        binding.walletBackup.setOnClickListener {
            launchWalletBackupCreate()
        }
        binding.walletRestore.setOnClickListener {
            launchWalletBackupRestore()
        }

        // Render cached data immediately, then refresh in the background.
        loadDirectoryFromCacheAndRender()
        refreshAll(force = false)
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        val secondary = textColor.adjustAlpha(0.75f)
        val cardTextSecondary = textColor.adjustAlpha(0.68f)
        val heroText = Color.WHITE
        val heroSecondary = heroText.adjustAlpha(0.8f)
        val heroBackground = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.blendARGB(properPrimaryColor, primaryColor, 0.35f),
                ColorUtils.blendARGB(properPrimaryColor, Color.BLACK, 0.24f)
            )
        ).apply {
            cornerRadius = resources.getDimension(R.dimen.bigger_margin)
        }

        val neutralCard = textColor.adjustAlpha(0.05f)
        val neutralStroke = textColor.adjustAlpha(0.1f)
        val actionButtonTint = heroText.adjustAlpha(0.16f)

        binding.walletHeroContainer.background = heroBackground
        binding.walletFederationCard.setCardBackgroundColor(Color.TRANSPARENT)

        binding.walletMyAddressesCard.setCardBackgroundColor(neutralCard)
        binding.walletMyAddressesCard.strokeColor = neutralStroke
        binding.walletMyAddressesCard.strokeWidth = 1

        binding.walletPaymentsCard.setCardBackgroundColor(neutralCard)
        binding.walletPaymentsCard.strokeColor = neutralStroke
        binding.walletPaymentsCard.strokeWidth = 1

        binding.walletBackupRestoreCard.setCardBackgroundColor(neutralCard)
        binding.walletBackupRestoreCard.strokeColor = neutralStroke
        binding.walletBackupRestoreCard.strokeWidth = 1

        binding.walletEmptyTitle.setTextColor(textColor)
        binding.walletEmptySubtitle.setTextColor(secondary)
        binding.walletSelectFederationButton.setTextColor(textColor)

        binding.walletFederationLabel.setTextColor(heroSecondary)
        binding.walletFederationValue.setTextColor(heroText)
        binding.walletFederationArrow.setColorFilter(heroSecondary)

        binding.walletBalanceTitle.setTextColor(heroSecondary)
        binding.walletBalanceTotal.setTextColor(heroText)
        binding.walletBalanceFiat.setTextColor(heroSecondary)
        binding.walletBalanceOnchainLabel.setTextColor(heroSecondary)
        binding.walletBalanceOnchainValue.setTextColor(heroText)
        binding.walletBalanceLnLabel.setTextColor(heroSecondary)
        binding.walletBalanceLnValue.setTextColor(heroText)
        binding.walletExchangeRate.setTextColor(heroSecondary)
        binding.walletSendLabel.setTextColor(heroSecondary)
        binding.walletReceiveLabel.setTextColor(heroSecondary)
        binding.walletSyncLabel.setTextColor(heroSecondary)

        binding.walletSend.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletReceive.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletSync.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletSend.iconTint = ColorStateList.valueOf(heroText)
        binding.walletReceive.iconTint = ColorStateList.valueOf(heroText)
        binding.walletSync.iconTint = ColorStateList.valueOf(heroText)
        binding.walletSend.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))
        binding.walletReceive.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))
        binding.walletSync.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))

        binding.walletMyAddressesTitle.setTextColor(textColor)
        binding.walletMyAddressesHint.setTextColor(cardTextSecondary)
        binding.walletMyInvoiceLabel.setTextColor(cardTextSecondary)
        binding.walletMyInvoiceValue.setTextColor(textColor)
        binding.walletMyAddressLabel.setTextColor(cardTextSecondary)
        binding.walletMyAddressValue.setTextColor(textColor)
        binding.walletMyBip21Label.setTextColor(cardTextSecondary)
        binding.walletMyBip21Value.setTextColor(textColor)

        binding.walletPaymentsTitle.setTextColor(textColor)
        binding.walletPaymentsPlaceholder.setTextColor(secondary)
        binding.walletStatusSubtitle.setTextColor(secondary)
        binding.walletError.setTextColor(textColor)
        binding.walletBackupRestoreTitle.setTextColor(textColor)
        binding.walletBackupRestoreHint.setTextColor(cardTextSecondary)

        binding.walletProgress.setIndicatorColor(properPrimaryColor)
        binding.walletSwipeRefresh.setColorSchemeColors(properPrimaryColor)

        paymentsAdapter.updateTextColors(textColor = textColor, secondaryTextColor = secondary)
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        refreshAll(force = invalidate) {
            callback?.invoke()
        }
    }

    override fun onSearchClosed() = Unit

    override fun onSearchQueryChanged(text: String) = Unit

    private fun loadDirectoryFromCacheAndRender() {
        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            val directory = FederationDirectoryManager.loadDirectory(context)
            allFederations = directory?.federations.orEmpty().sortedBy { it.name.lowercase(Locale.getDefault()) }

            activity?.runOnUiThread {
                binding.walletProgress.beGone()
                renderStatic()
            }
        }
    }

    private fun refreshAll(force: Boolean, callback: (() -> Unit)? = null) {
        if (isRefreshing) {
            pendingRefreshRequested = true
            pendingRefreshForce = pendingRefreshForce || force
            callback?.invoke()
            return
        }
        isRefreshing = true
        refreshGeneration += 1L
        val generation = refreshGeneration

        binding.walletProgress.beVisible()
        binding.root.postDelayed({
            if (isRefreshing && refreshGeneration == generation) {
                isRefreshing = false
                refreshGeneration += 1L
                binding.walletSwipeRefresh.isRefreshing = false
                binding.walletProgress.beGone()
                binding.walletError.text = context.getString(
                    R.string.wallet_status_error,
                    "Refresh timed out"
                )
                binding.walletError.beVisible()
                callback?.invoke()
            }
        }, 70_000L)

        ensureBackgroundThread {
            var selected: FederationEntry? = null
            var isFm = false
            var balances: org.lightningdevkit.ldknode.BalanceDetails? = null
            var payments: List<org.lightningdevkit.ldknode.PaymentDetails> = emptyList()
            var fmBalanceSats: Long? = null
            var rate: Double? = null
            var fatalError: Throwable? = null
            var requestFedimintStartFor: FederationEntry? = null

            try {
                val now = System.currentTimeMillis()

                // Directory refresh.
                val dirStale = now - context.config.walletDirectoryLastSyncMs > 24L * 60L * 60L * 1000L
                if (force || dirStale || context.config.walletDirectoryJson.isBlank()) {
                    FederationDirectoryManager.refreshBlocking(context)
                }

                // Exchange rate refresh.
                val rateStale = now - context.config.walletBtcUsdRateLastSyncMs > 30L * 60L * 1000L
                if (force || rateStale || context.config.walletBtcUsdRate <= 0.0) {
                    ExchangeRateManager.refreshBlocking(context)
                }

                // Reload directory, then refresh wallet snapshot.
                val directory = FederationDirectoryManager.loadDirectory(context)
                allFederations = directory?.federations.orEmpty().sortedBy { it.name.lowercase(Locale.getDefault()) }

                selected = FederationDirectoryManager.getSelectedFederation(context)
                isFm = isFedimint(selected)

                // Ensure the wallet backend is running for the selected federation.
                if (selected != null) {
                    if (isFm) {
                        val selectedFedId = selected!!.id
                        val runningFedId = FedimintWalletManager.getRunningFederationId()
                        val isRunningForSelection = runningFedId == selectedFedId &&
                            FedimintWalletManager.verifyRunningFederationBlocking(context, selected!!)
                        if (isRunningForSelection) {
                            fmBalanceSats = FedimintWalletManager.getBalanceSatsBlocking(context, selected!!)
                        } else if (!FedimintWalletManager.isBusy()) {
                            // Start asynchronously to keep refresh bounded and avoid UI timeouts.
                            // Throttle retries on repeated failures to prevent startup loops.
                            val nowMs = System.currentTimeMillis()
                            val retryCooldownMs = 30_000L
                            val inFlight = fedimintStartInFlightForId == selectedFedId
                            val cooldownActive = lastFedimintStartAttemptId == selectedFedId &&
                                (nowMs - lastFedimintStartAttemptMs) < retryCooldownMs
                            if (!inFlight && !cooldownActive) {
                                requestFedimintStartFor = selected
                                fedimintStartInFlightForId = selectedFedId
                                lastFedimintStartAttemptId = selectedFedId
                                lastFedimintStartAttemptMs = nowMs
                            }
                        }
                    } else {
                        // Leaving Fedimint selection; allow immediate future attempts when user switches back.
                        fedimintStartInFlightForId = null
                        val started = LdkWalletManager.ensureStartedBlocking(context, selected!!)
                        if (started && force) {
                            // Keep the UI responsive on federation switches.
                            LdkWalletManager.syncWallets()
                        }
                        if (started) {
                            // Generate default receive data once the wallet is enabled (selection made),
                            // so the user has addresses ready without manual setup.
                            ensureDefaultReceiveDataBlocking(selected!!)
                        }
                    }
                }

                if (!isFm) {
                    balances = LdkWalletManager.listBalances()
                    payments = LdkWalletManager.listPayments(limit = 20)
                }
                rate = ExchangeRateManager.getCachedUsdRate(context)
            } catch (t: Throwable) {
                Log.w("WalletFragment", "refreshAll failed", t)
                fatalError = t
            } finally {
                isRefreshing = false

                binding.root.post {
                    if (generation != refreshGeneration) {
                        return@post
                    }
                    binding.walletSwipeRefresh.isRefreshing = false
                    binding.walletProgress.beGone()

                    renderStatic(rate = rate)
                    if (isFm && selected != null) {
                        renderFedimintSnapshot(balanceSats = fmBalanceSats, rateUsd = rate)
                    } else {
                        renderWalletSnapshot(balances = balances, rateUsd = rate, payments = payments)
                    }

                    if (fatalError != null) {
                        val message = fatalError?.message.orEmpty().ifBlank {
                            context.getString(R.string.wallet_unknown_error)
                        }
                        binding.walletError.text = context.getString(R.string.wallet_status_error, message)
                        binding.walletError.beVisible()
                    }

                    requestFedimintStartFor?.let { fed ->
                        FedimintWalletManager.ensureStarted(context, fed) { success, _ ->
                            binding.root.post {
                                if (fedimintStartInFlightForId == fed.id) {
                                    fedimintStartInFlightForId = null
                                }
                                if (success) {
                                    refreshAll(force = false)
                                } else {
                                    val selectedNow = FederationDirectoryManager.getSelectedFederation(context)
                                    if (selectedNow != null && isFedimint(selectedNow) && selectedNow.id == fed.id) {
                                        val err = FedimintWalletManager.getLastErrorMessage()
                                            .orEmpty()
                                            .ifBlank { context.getString(R.string.wallet_unknown_error) }
                                        binding.walletError.text = context.getString(R.string.wallet_status_error, err)
                                        binding.walletError.beVisible()
                                    }
                                }
                            }
                        }
                    }

                    callback?.invoke()

                    if (pendingRefreshRequested) {
                        val followUpForce = pendingRefreshForce
                        pendingRefreshRequested = false
                        pendingRefreshForce = false
                        refreshAll(force = followUpForce)
                    }
                }
            }
        }
    }

    private fun ensureDefaultReceiveData(selected: FederationEntry?) {
        val host = activity ?: return
        val fed = selected ?: FederationDirectoryManager.getSelectedFederation(context)
        if (fed == null) {
            showSelectFederationDialog()
            return
        }
        if (isFedimint(fed)) {
            // Fedimint invoices require a fixed amount; don't silently create surprise invoices.
            showCreateInvoiceDialog()
            return
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            val started = LdkWalletManager.ensureStartedBlocking(context, fed)
            if (started) {
                ensureDefaultReceiveDataBlocking(fed)
            }
            val err = LdkWalletManager.getLastErrorMessage().orEmpty()
            host.runOnUiThread {
                binding.walletProgress.beGone()
                if (!started) {
                    host.toast(host.getString(R.string.wallet_status_error, err.ifBlank { host.getString(R.string.wallet_unknown_error) }))
                }
                renderMyAddresses()
            }
        }
    }

    private fun ensureDefaultReceiveDataBlocking(selected: FederationEntry) {
        if (isFedimint(selected)) return

        val fedId = selected.id
        val cfg = context.config

        val invoice = cfg.getWalletLastInvoiceForFederation(fedId)
        if (invoice.isBlank()) {
            val memo = context.getString(R.string.app_launcher_name)
            val expirySeconds = 24 * 60 * 60 // "My receive" defaults should be usable for a while.
            val created = LdkWalletManager.createBolt11Invoice(amountSats = null, memo = memo, expirySeconds = expirySeconds)
            if (!created.isNullOrBlank()) {
                cfg.setWalletLastInvoiceForFederation(fedId, created)
                cfg.setWalletLastInvoiceCreatedMsForFederation(fedId, System.currentTimeMillis())
            }
        }

        val address = cfg.getWalletLastOnchainAddressForFederation(fedId)
        if (address.isBlank()) {
            val created = LdkWalletManager.newOnchainAddress()
            if (!created.isNullOrBlank()) {
                cfg.setWalletLastOnchainAddressForFederation(fedId, created)
                cfg.setWalletLastOnchainAddressCreatedMsForFederation(fedId, System.currentTimeMillis())
            }
        }
    }

    private fun regenerateUnifiedUri(selected: FederationEntry?) {
        val host = activity ?: return
        val fed = selected ?: FederationDirectoryManager.getSelectedFederation(context)
        if (fed == null) {
            showSelectFederationDialog()
            return
        }
        if (isFedimint(fed)) {
            host.toast(R.string.wallet_fedimint_no_onchain)
            return
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            val started = LdkWalletManager.ensureStartedBlocking(context, fed)
            if (started) {
                val memo = context.getString(R.string.app_launcher_name)
                val expirySeconds = 24 * 60 * 60

                val newInvoice = LdkWalletManager.createBolt11Invoice(amountSats = null, memo = memo, expirySeconds = expirySeconds)
                if (!newInvoice.isNullOrBlank()) {
                    context.config.setWalletLastInvoiceForFederation(fed.id, newInvoice)
                    context.config.setWalletLastInvoiceCreatedMsForFederation(fed.id, System.currentTimeMillis())
                }

                val newAddress = LdkWalletManager.newOnchainAddress()
                if (!newAddress.isNullOrBlank()) {
                    context.config.setWalletLastOnchainAddressForFederation(fed.id, newAddress)
                    context.config.setWalletLastOnchainAddressCreatedMsForFederation(fed.id, System.currentTimeMillis())
                }
            }

            val uri = getBip21ForSelected(fed).trim()
            val err = LdkWalletManager.getLastErrorMessage().orEmpty().ifBlank { host.getString(R.string.wallet_unknown_error) }

            host.runOnUiThread {
                binding.walletProgress.beGone()
                renderMyAddresses()
                if (uri.isNotBlank()) {
                    showShareSendRegenerateDialog(
                        titleRes = R.string.wallet_my_unified_uri,
                        value = uri,
                        onRegenerate = { regenerateUnifiedUri(fed) }
                    )
                } else {
                    host.toast(host.getString(R.string.wallet_status_error, err))
                }
            }
        }
    }

    private fun getBip21ForSelected(selected: FederationEntry?): String {
        val fed = selected ?: return ""
        if (isFedimint(fed)) return ""

        val cfg = context.config
        val address = cfg.getWalletLastOnchainAddressForFederation(fed.id).trim()
        if (address.isBlank()) return ""
        val invoice = cfg.getWalletLastInvoiceForFederation(fed.id).trim()

        // Minimal BIP21 with optional lightning parameter.
        val base = "bitcoin:$address"
        return if (invoice.isBlank()) {
            base
        } else {
            "$base?lightning=${Uri.encode(invoice)}"
        }
    }

    private fun renderStatic(rate: Double? = ExchangeRateManager.getCachedUsdRate(context)) {
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        val hasSelection = selected != null
        val isFm = isFedimint(selected)

        binding.walletEmptyHolder.isVisible = !hasSelection
        binding.walletDashboardHolder.isVisible = hasSelection

        // Directory last sync line.
        val lastDirSyncMs = context.config.walletDirectoryLastSyncMs
        val directoryLine = if (lastDirSyncMs > 0L) {
            val formatted =
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastDirSyncMs))
            context.getString(R.string.wallet_directory_last_sync, formatted)
        } else {
            context.getString(R.string.wallet_directory_never_synced)
        }

        // Rate line.
        val rateLine = if (rate != null && rate > 0.0) {
            val formattedRate = NumberFormat.getCurrencyInstance(Locale.US).format(rate)
            val updatedMs = context.config.walletBtcUsdRateLastSyncMs
            val updated = if (updatedMs > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(updatedMs))
            } else {
                context.getString(R.string.wallet_unknown)
            }
            context.getString(R.string.wallet_rate_line, formattedRate, updated)
        } else {
            context.getString(R.string.wallet_rate_line_unavailable)
        }

        binding.walletEmptySubtitle.text = listOf(directoryLine, rateLine).joinToString("\n")

        if (selected != null) {
            binding.walletFederationValue.text = selected.name
        }

        // Status subtitle.
        val walletLine = when {
            selected == null -> ""
            isFm && FedimintWalletManager.isBusy() -> context.getString(R.string.wallet_status_starting)
            isFm && FedimintWalletManager.isRunning() &&
                FedimintWalletManager.getRunningFederationId() == selected.id ->
                context.getString(R.string.wallet_status_running)
            isFm && !FedimintWalletManager.getLastErrorMessage().isNullOrBlank() ->
                context.getString(R.string.wallet_status_error, FedimintWalletManager.getLastErrorMessage().orEmpty())
            !isFm && LdkWalletManager.isBusy() -> context.getString(R.string.wallet_status_starting)
            !isFm && LdkWalletManager.isRunning() -> {
                val nodeId = LdkWalletManager.getNodeId().orEmpty()
                if (nodeId.isBlank()) {
                    context.getString(R.string.wallet_status_running)
                } else {
                    context.getString(R.string.wallet_status_running_node, nodeId)
                }
            }
            !isFm && !LdkWalletManager.getLastErrorMessage().isNullOrBlank() ->
                context.getString(R.string.wallet_status_error, LdkWalletManager.getLastErrorMessage().orEmpty())
            else -> context.getString(R.string.wallet_status_not_started)
        }

        binding.walletStatusSubtitle.text = listOf(directoryLine, rateLine, walletLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        binding.walletError.beGone()

        renderMyAddresses()

        // Hide unsupported UI elements for Fedimint until on-chain deposit/backup are implemented.
        binding.walletMyAddressLabel.beVisibleIf(!isFm)
        binding.walletMyAddressValue.beVisibleIf(!isFm)
        binding.walletMyBip21Label.beVisibleIf(!isFm)
        binding.walletMyBip21Value.beVisibleIf(!isFm)
    }

    private fun renderMyAddresses() {
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        val invoice = selected?.let { context.config.getWalletLastInvoiceForFederation(it.id) }.orEmpty().trim()
        val address = selected?.let { context.config.getWalletLastOnchainAddressForFederation(it.id) }.orEmpty().trim()
        val bip21 = getBip21ForSelected(selected).trim()

        binding.walletMyInvoiceValue.text = invoice.ifBlank { context.getString(R.string.wallet_not_generated_yet) }
        binding.walletMyAddressValue.text = address.ifBlank { context.getString(R.string.wallet_not_generated_yet) }
        binding.walletMyBip21Value.text = bip21.ifBlank { context.getString(R.string.wallet_not_generated_yet) }
    }

    private fun renderFedimintSnapshot(balanceSats: Long?, rateUsd: Double?) {
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (selected == null) {
            paymentsAdapter.submitList(emptyList())
            binding.walletPaymentsPlaceholder.beVisible()
            return
        }

        val errorMsg = FedimintWalletManager.getLastErrorMessage()
        if (balanceSats == null && !errorMsg.isNullOrBlank()) {
            binding.walletError.text = context.getString(R.string.wallet_status_error, errorMsg)
            binding.walletError.beVisible()
        } else {
            binding.walletError.beGone()
        }

        if (balanceSats == null) {
            binding.walletBalanceTotal.text = context.getString(R.string.wallet_balance_unknown)
            binding.walletBalanceOnchainValue.text = context.getString(R.string.wallet_unknown)
            binding.walletBalanceLnValue.text = context.getString(R.string.wallet_unknown)
            binding.walletBalanceFiat.beGone()
            binding.walletExchangeRate.text = ""
        } else {
            val total = balanceSats.coerceAtLeast(0L)
            binding.walletBalanceTotal.text = context.getString(R.string.wallet_sats_value, formatSats(total.toULong()))
            binding.walletBalanceOnchainValue.text = context.getString(R.string.wallet_unknown)
            binding.walletBalanceLnValue.text = context.getString(R.string.wallet_sats_value, formatSats(total.toULong()))

            if (rateUsd != null && rateUsd > 0.0) {
                val btc = total.toDouble() / 100_000_000.0
                val approxUsd = btc * rateUsd
                val usdText = NumberFormat.getCurrencyInstance(Locale.US).format(approxUsd)
                binding.walletBalanceFiat.text = context.getString(R.string.wallet_fiat_approx, usdText)
                binding.walletBalanceFiat.beVisible()
            } else {
                binding.walletBalanceFiat.beGone()
            }

            binding.walletExchangeRate.text = ExchangeRateManager.getCachedUsdRate(context)?.let {
                val formattedRate = NumberFormat.getCurrencyInstance(Locale.US).format(it)
                context.getString(R.string.wallet_rate_short, formattedRate)
            }.orEmpty()
        }

        paymentsAdapter.submitList(emptyList())
        binding.walletPaymentsPlaceholder.beVisible()
    }

    private fun renderWalletSnapshot(
        balances: org.lightningdevkit.ldknode.BalanceDetails?,
        rateUsd: Double?,
        payments: List<org.lightningdevkit.ldknode.PaymentDetails>,
    ) {
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (selected == null) {
            paymentsAdapter.submitList(emptyList())
            binding.walletPaymentsPlaceholder.beVisible()
            return
        }

        if (balances == null && !LdkWalletManager.getLastErrorMessage().isNullOrBlank()) {
            binding.walletError.text =
                context.getString(R.string.wallet_status_error, LdkWalletManager.getLastErrorMessage().orEmpty())
            binding.walletError.beVisible()
        } else {
            binding.walletError.beGone()
        }

        if (balances == null) {
            binding.walletBalanceTotal.text = context.getString(R.string.wallet_balance_unknown)
            binding.walletBalanceOnchainValue.text = context.getString(R.string.wallet_unknown)
            binding.walletBalanceLnValue.text = context.getString(R.string.wallet_unknown)
            binding.walletBalanceFiat.beGone()
            binding.walletExchangeRate.text = ""
        } else {
            val totalOnchain = balances.totalOnchainBalanceSats
            val spendableOnchain = balances.spendableOnchainBalanceSats
            val totalLightning = balances.totalLightningBalanceSats
            val total = totalOnchain + totalLightning

            binding.walletBalanceTotal.text = context.getString(R.string.wallet_sats_value, formatSats(total))
            binding.walletBalanceOnchainValue.text = context.getString(R.string.wallet_sats_value, formatSats(spendableOnchain))
            binding.walletBalanceLnValue.text = context.getString(R.string.wallet_sats_value, formatSats(totalLightning))

            if (rateUsd != null && rateUsd > 0.0) {
                val btc = total.toDouble() / 100_000_000.0
                val approxUsd = btc * rateUsd
                val usdText = NumberFormat.getCurrencyInstance(Locale.US).format(approxUsd)
                binding.walletBalanceFiat.text = context.getString(R.string.wallet_fiat_approx, usdText)
                binding.walletBalanceFiat.beVisible()
            } else {
                binding.walletBalanceFiat.beGone()
            }

            binding.walletExchangeRate.text = ExchangeRateManager.getCachedUsdRate(context)?.let {
                val formattedRate = NumberFormat.getCurrencyInstance(Locale.US).format(it)
                context.getString(R.string.wallet_rate_short, formattedRate)
            }.orEmpty()
        }

        paymentsAdapter.submitList(payments)
        binding.walletPaymentsPlaceholder.beVisibleIf(payments.isEmpty())
    }

    private fun showSelectFederationDialog() {
        val host = activity ?: return
        val items = allFederations
        if (items.isEmpty()) {
            host.toast(R.string.wallet_directory_loading_failed)
            return
        }

        val names = items.map { entry ->
            val backend = if (entry.kind.trim().equals("fedimint", ignoreCase = true)) {
                "Fedimint"
            } else {
                "Bitcoin"
            }
            "${entry.name} [$backend]"
        }.toTypedArray()
        val selectedId = host.config.walletSelectedFederationId
        val checked = items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0

        host.getAlertDialogBuilder()
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val chosen = items.getOrNull(which) ?: return@setSingleChoiceItems
                val changed = host.config.walletSelectedFederationId != chosen.id
                host.config.walletSelectedFederationId = chosen.id
                dialog.dismiss()
                if (!changed) {
                    return@setSingleChoiceItems
                }
                // Update visual selection immediately, then refresh backend data.
                renderStatic()
                // Refresh immediately for the new federation selection, but do not force remote
                // directory/rate network refreshes on every switch (can cause long spinner loops).
                refreshAll(force = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSendDialog() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (selected == null) {
            showSelectFederationDialog()
            return
        }

        val vb = DialogWalletSendBinding.inflate(host.layoutInflater)
        vb.walletSendDestination.setText("")
        vb.walletSendAmount.setText("")
        activeSendDestination = vb.walletSendDestination

        // Contact picker shortcut.
        vb.walletSendDestinationHolder.setEndIconOnClickListener {
            launchWalletContactPicker()
        }

        host.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallet_send, null)
            .apply {
                host.setupDialogStuff(vb.root, this, R.string.wallet_send) { alertDialog ->
                    alertDialog.setOnDismissListener {
                        activeSendDestination = null
                    }
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val destination = vb.walletSendDestination.text?.toString()?.trim().orEmpty()
                        val amountSats = vb.walletSendAmount.text?.toString()?.trim()?.toLongOrNull()

                        if (destination.isBlank()) {
                            host.toast(R.string.wallet_send_destination_required)
                            return@setOnClickListener
                        }

                        alertDialog.dismiss()
                        sendPayment(destination, amountSats)
                    }
                }
            }
    }

    private fun launchWalletContactPicker() {
        val host = activity ?: return
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        host.startActivityForResult(intent, REQUEST_CODE_PICK_WALLET_CONTACT)
    }

    fun handleWalletContactPickerResult(resultCode: Int, resultData: Intent?) {
        val host = activity ?: return
        if (resultCode != android.app.Activity.RESULT_OK) return
        val contactUri = resultData?.data ?: return

        ensureBackgroundThread {
            val resolver = host.contentResolver
            val contactId = resolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    id to name
                } else null
            } ?: return@ensureBackgroundThread

            val rawId = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(contactId.first.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }

            val destination = WalletContactHelper.getWalletDestination(host, rawId, contactId.first.toInt())

            host.runOnUiThread {
                if (!destination.isNullOrBlank()) {
                    activeSendDestination?.setText(destination)
                } else {
                    host.getAlertDialogBuilder()
                        .setTitle(R.string.contact_wallet_address)
                        .setMessage(
                            host.getString(
                                R.string.wallet_contact_no_address,
                                contactId.second.ifBlank { contactId.first.toString() }
                            )
                        )
                        .setPositiveButton(R.string.contact_action_set_wallet_address) { _, _ ->
                            if (rawId != null && rawId > 0L) {
                                showSetWalletAddressDialog(rawId)
                            } else {
                                host.toast(org.fossify.commons.R.string.unknown_error_occurred)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun showSetWalletAddressDialog(rawId: Long) {
        val host = activity ?: return
        val vb = DialogContactWalletAddressBinding.inflate(host.layoutInflater)
        vb.contactWalletAddress.setText("")

        host.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .apply {
                host.setupDialogStuff(vb.root, this, R.string.contact_wallet_address) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = vb.contactWalletAddress.text?.toString()?.trim().orEmpty()
                        if (value.isBlank()) {
                            host.toast(R.string.wallet_send_destination_required)
                            return@setOnClickListener
                        }
                        WalletContactHelper.upsertWalletDestination(host, rawId, value)
                        activeSendDestination?.setText(value)
                        host.toast(R.string.done)
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun sendPayment(destination: String, amountSats: Long?) {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context) ?: return
        val isFm = isFedimint(selected)

        binding.walletProgress.beVisible()

        ensureBackgroundThread {
            val started = if (isFm) {
                FedimintWalletManager.ensureStartedBlocking(context, selected)
            } else {
                LdkWalletManager.ensureStartedBlocking(context, selected)
            }
            var successMessage: String? = null
            var userErrorMessage: String? = null

            val txLimitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(WalletPolicy.MAX_SINGLE_TX_SATS)

            if (started) {
                val isBolt11 = LdkWalletManager.isBolt11Invoice(destination)
                val fixedInvoiceSats = if (isBolt11) parseFixedInvoiceSats(destination) else null
                if (fixedInvoiceSats != null && !WalletPolicy.isAmountWithinSingleTxLimit(fixedInvoiceSats)) {
                    userErrorMessage = host.getString(R.string.wallet_amount_over_limit, txLimitText)
                } else if (amountSats != null && !WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                    userErrorMessage = host.getString(R.string.wallet_amount_over_limit, txLimitText)
                }

                if (userErrorMessage == null) {
                    if (!isBolt11) {
                        // Fedimint currently supports Lightning (BOLT11) only via the embedded SDK.
                        if (isFm) {
                            userErrorMessage = host.getString(R.string.wallet_fedimint_invoice_only)
                        } else {
                            if (amountSats == null || amountSats <= 0L) {
                                userErrorMessage = host.getString(R.string.wallet_send_amount_required)
                            } else {
                                val txId = LdkWalletManager.sendOnchain(destination, amountSats)
                                if (txId != null) {
                                    successMessage = host.getString(R.string.wallet_send_submitted)
                                }
                            }
                        }
                    } else {
                        if (isFm) {
                            val fixed = parseFixedInvoiceSats(destination)
                            if (fixed == null) {
                                userErrorMessage = host.getString(R.string.wallet_fedimint_variable_invoice_unsupported)
                            } else {
                                val ok = FedimintWalletManager.payBolt11InvoiceBlocking(context, selected, destination)
                                if (ok) successMessage = host.getString(R.string.wallet_send_submitted)
                            }
                        } else {
                            val id = LdkWalletManager.payBolt11Invoice(destination, amountSats)
                            if (id != null) {
                                successMessage = host.getString(R.string.wallet_send_submitted)
                            }
                        }
                    }
                }
            }

            val backendError = if (isFm) FedimintWalletManager.getLastErrorMessage() else LdkWalletManager.getLastErrorMessage()
            val error = backendError.orEmpty().ifBlank {
                host.getString(R.string.wallet_unknown_error)
            }

            host.runOnUiThread {
                binding.walletProgress.beGone()
                if (successMessage != null) {
                    host.toast(successMessage!!)
                    refreshAll(force = false)
                } else if (userErrorMessage != null) {
                    host.toast(userErrorMessage!!)
                } else {
                    host.toast(host.getString(R.string.wallet_send_failed, error))
                }
            }
        }
    }

    private fun launchWalletBackupCreate() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (selected == null) {
            showSelectFederationDialog()
            return
        }

        val filename = buildString {
            append("cyber_phone_wallet_")
            append(selected.id)
            append("_")
            append(java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()))
            append(".json")
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        host.startActivityForResult(intent, REQUEST_CODE_CREATE_WALLET_BACKUP)
    }

    private fun launchWalletBackupRestore() {
        val host = activity ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        host.startActivityForResult(intent, REQUEST_CODE_OPEN_WALLET_BACKUP)
    }

    fun handleWalletBackupCreateResult(resultCode: Int, resultData: Intent?) {
        val host = activity ?: return
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri = resultData?.data ?: return

        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (selected == null) {
            host.toast(R.string.wallet_select_federation)
            return
        }
        promptBackupPassphrase(requireConfirmation = true) { passphrase ->
            createEncryptedBackupNow(uri = uri, selected = selected, passphrase = passphrase)
        }
    }

    fun handleWalletBackupRestoreResult(resultCode: Int, resultData: Intent?) {
        val host = activity ?: return
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri = resultData?.data ?: return

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            val raw = readUriBytesLimited(uri, maxBytes = maxBackupPayloadBytes * 2)
            val envelope = raw?.toString(Charsets.UTF_8)?.let { WalletBackupCrypto.parseEnvelope(it) }
            host.runOnUiThread {
                binding.walletProgress.beGone()
                if (envelope == null) {
                    host.toast(host.getString(R.string.wallet_restore_failed, host.getString(R.string.wallet_backup_invalid_format)))
                    return@runOnUiThread
                }

                val backupFederation = envelope.federation.toFederationEntry()

                host.getAlertDialogBuilder()
                    .setMessage(host.getString(R.string.wallet_restore_confirm, backupFederation.name))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        promptBackupPassphrase(requireConfirmation = false) { passphrase ->
                            restoreEncryptedBackupNow(
                                envelope = envelope,
                                passphrase = passphrase
                            )
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun createEncryptedBackupNow(uri: Uri, selected: FederationEntry, passphrase: CharArray) {
        val host = activity ?: return
        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            var ok = false
            var message: String? = null
            try {
                val isFm = isFedimint(selected)
                if (!isFm && !stopLdkBlocking()) {
                    throw IllegalStateException(host.getString(R.string.wallet_unknown_error))
                }

                val payload = if (isFm) {
                    val mnemonic = FedimintWalletManager.exportMnemonicBlocking(host, selected)
                        ?.trim()
                        .orEmpty()
                    if (mnemonic.isBlank()) {
                        throw IllegalStateException(
                            FedimintWalletManager.getLastErrorMessage()
                                .orEmpty()
                                .ifBlank { host.getString(R.string.wallet_unknown_error) }
                        )
                    }
                    JSONObject()
                        .put("version", 2)
                        .put("wallet_type", "fedimint")
                        .put("mnemonic", mnemonic)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                } else {
                    val storageDir = WalletStoragePaths.ldkFederationDir(context, selected.id)
                    val totalBytes = storageDir.walkTopDown()
                        .filter { it.isFile }
                        .fold(0L) { acc, file -> acc + file.length() }
                    if (totalBytes > maxBackupPayloadBytes) {
                        throw IllegalStateException(host.getString(R.string.wallet_backup_too_large))
                    }

                    ByteArrayOutputStream().use { bos ->
                        ZipOutputStream(BufferedOutputStream(bos)).use { zip ->
                            writeWalletBackupZip(zip, selected.id)
                        }
                        bos.toByteArray()
                    }
                }

                if (payload.size.toLong() > maxBackupPayloadBytes) {
                    throw IllegalStateException(host.getString(R.string.wallet_backup_too_large))
                }

                val envelope = WalletBackupCrypto.createEnvelopeJson(
                    passphrase = passphrase,
                    walletType = if (isFedimint(selected)) "fedimint" else "ldk",
                    federation = selected,
                    plaintext = payload,
                ).toString()
                val envelopeBytes = envelope.toByteArray(Charsets.UTF_8)
                if (envelopeBytes.size.toLong() > maxBackupPayloadBytes * 2) {
                    throw IllegalStateException(host.getString(R.string.wallet_backup_too_large))
                }

                val out = host.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException(host.getString(R.string.wallet_unknown_error))
                out.use { os -> os.write(envelopeBytes) }
                ok = true
            } catch (t: Throwable) {
                message = t.message
            } finally {
                passphrase.fill('\u0000')
            }

            host.runOnUiThread {
                binding.walletProgress.beGone()
                if (ok) {
                    host.toast(R.string.wallet_backup_success)
                } else {
                    host.toast(
                        host.getString(
                            R.string.wallet_backup_failed,
                            message.orEmpty().ifBlank { host.getString(R.string.wallet_unknown_error) }
                        )
                    )
                }
                refreshAll(force = false)
            }
        }
    }

    private fun restoreEncryptedBackupNow(
        envelope: WalletBackupCrypto.Envelope,
        passphrase: CharArray,
    ) {
        val host = activity ?: return
        val backupFederation = envelope.federation.toFederationEntry().let {
            if (envelope.walletType == "fedimint") it.copy(kind = "fedimint") else it.copy(kind = "ldk")
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            var ok = false
            var message: String? = null
            try {
                val plaintext = try {
                    WalletBackupCrypto.decryptEnvelope(passphrase, envelope)
                } catch (_: Throwable) {
                    throw IllegalStateException(host.getString(R.string.wallet_backup_wrong_passphrase))
                }
                if (plaintext.isEmpty() || plaintext.size.toLong() > maxBackupPayloadBytes) {
                    throw IllegalStateException(host.getString(R.string.wallet_backup_invalid_format))
                }

                FederationDirectoryManager.upsertFederation(host, backupFederation)

                if (envelope.walletType == "fedimint") {
                    val payload = JSONObject(plaintext.toString(Charsets.UTF_8))
                    val mnemonic = payload.optString("mnemonic").orEmpty().trim()
                    if (mnemonic.isBlank()) {
                        throw IllegalStateException(host.getString(R.string.wallet_backup_invalid_format))
                    }
                    ok = FedimintWalletManager.importMnemonicBlocking(host, backupFederation, mnemonic)
                    if (!ok) {
                        message = FedimintWalletManager.getLastErrorMessage()
                            .orEmpty()
                            .ifBlank { host.getString(R.string.wallet_unknown_error) }
                    }
                } else {
                    if (!stopLdkBlocking()) {
                        throw IllegalStateException(host.getString(R.string.wallet_unknown_error))
                    }
                    ZipInputStream(ByteArrayInputStream(plaintext)).use { zip ->
                        extractWalletBackupZip(zip, backupFederation.id)
                    }
                    host.config.walletSelectedFederationId = backupFederation.id
                    ok = true

                    val started = LdkWalletManager.ensureStartedBlocking(host, backupFederation)
                    if (started) {
                        LdkWalletManager.syncWalletsBlocking()
                    } else {
                        message = LdkWalletManager.getLastErrorMessage()
                            .orEmpty()
                            .ifBlank { host.getString(R.string.wallet_unknown_error) }
                    }
                }

                if (ok && envelope.walletType == "fedimint") {
                    host.config.walletSelectedFederationId = backupFederation.id
                }
            } catch (t: Throwable) {
                message = t.message
            } finally {
                passphrase.fill('\u0000')
            }

            host.runOnUiThread {
                binding.walletProgress.beGone()
                if (ok) {
                    host.toast(R.string.wallet_restore_success)
                    if (!message.isNullOrBlank()) {
                        host.toast(host.getString(R.string.wallet_status_error, message))
                    }
                } else {
                    host.toast(
                        host.getString(
                            R.string.wallet_restore_failed,
                            message.orEmpty().ifBlank { host.getString(R.string.wallet_unknown_error) }
                        )
                    )
                }
                refreshAll(force = true)
            }
        }
    }

    private fun readUriBytesLimited(uri: Uri, maxBytes: Long): ByteArray? {
        val host = activity ?: return null
        val input = host.contentResolver.openInputStream(uri) ?: return null
        input.use { ins ->
            val bos = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = ins.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    return null
                }
                bos.write(buffer, 0, read)
            }
            return bos.toByteArray()
        }
    }

    private fun stopLdkBlocking(timeoutMs: Long = 25_000L): Boolean {
        val latch = CountDownLatch(1)
        LdkWalletManager.stop { latch.countDown() }
        return runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    private fun promptBackupPassphrase(
        requireConfirmation: Boolean,
        onConfirmed: (CharArray) -> Unit,
    ) {
        val host = activity ?: return
        val density = host.resources.displayMetrics.density
        val spacing = (16f * density).toInt()

        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing / 2, spacing, 0)
        }
        val passphraseView = EditText(host).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = host.getString(R.string.wallet_backup_passphrase_hint)
        }
        container.addView(
            passphraseView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val confirmView = if (requireConfirmation) {
            EditText(host).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = host.getString(R.string.wallet_backup_passphrase_confirm_hint)
            }.also {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (10f * density).toInt()
                container.addView(it, lp)
            }
        } else {
            null
        }

        host.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .apply {
                val title = if (requireConfirmation) {
                    R.string.wallet_backup_passphrase_title
                } else {
                    R.string.wallet_restore_passphrase_title
                }
                host.setupDialogStuff(container, this, title) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val passphrase = passphraseView.text?.toString().orEmpty()
                        val confirmation = confirmView?.text?.toString().orEmpty()

                        if (passphrase.length < 8) {
                            host.toast(R.string.wallet_backup_passphrase_too_short)
                            return@setOnClickListener
                        }
                        if (requireConfirmation && passphrase != confirmation) {
                            host.toast(R.string.wallet_backup_passphrase_mismatch)
                            return@setOnClickListener
                        }

                        passphraseView.text?.clear()
                        confirmView?.text?.clear()
                        alertDialog.dismiss()
                        onConfirmed(passphrase.toCharArray())
                    }
                }
            }
    }

    private fun writeWalletBackupZip(zip: ZipOutputStream, federationId: String) {
        val storageDirName = WalletStoragePaths.federationDirName(federationId)
        // Write a manifest for deterministic restores and format migration.
        val manifest = JSONObject()
            .put("version", 2)
            .put("type", "ldk")
            .put("federation_id", federationId)
            .put("storage_dir", storageDirName)
            .put("created_at", System.currentTimeMillis())
            .toString()
        zip.putNextEntry(ZipEntry("wallet_backup.json"))
        zip.write(manifest.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        val baseDir = WalletStoragePaths.ldkFederationDir(context, federationId)
        if (!baseDir.exists()) {
            // Nothing to back up yet.
            return
        }

        baseDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(baseDir).invariantSeparatorsPath
                val entryName = "ldk/$storageDirName/$rel"
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
    }

    private fun extractWalletBackupZip(zip: ZipInputStream, federationId: String) {
        val storageDirName = WalletStoragePaths.federationDirName(federationId)
        val targetBase = WalletStoragePaths.ldkFederationDir(context, federationId)
        val parent = targetBase.parentFile ?: throw IllegalStateException("Wallet storage parent unavailable")
        if (!parent.exists()) parent.mkdirs()
        val tempBase = File(parent, ".${targetBase.name}.restore-${System.currentTimeMillis()}")
        if (tempBase.exists()) {
            deleteRecursively(tempBase)
        }
        tempBase.mkdirs()

        var extractedFiles = 0

        var entry: ZipEntry? = zip.nextEntry
        val acceptedPrefixes = arrayOf("ldk/$storageDirName/", "ldk/$federationId/")
        while (entry != null) {
            val name = entry.name.replace('\\', '/')
            val prefix = acceptedPrefixes.firstOrNull { name.startsWith(it) }
            if (prefix == null) {
                entry = zip.nextEntry
                continue
            }
            val rel = name.removePrefix(prefix).trimStart('/')
            if (rel.isBlank()) {
                entry = zip.nextEntry
                continue
            }
            if (rel.contains("..")) {
                entry = zip.nextEntry
                continue
            }
            val outFile = File(tempBase, rel)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { out ->
                    zip.copyTo(out)
                }
                extractedFiles++
            }
            entry = zip.nextEntry
        }

        if (extractedFiles == 0) {
            deleteRecursively(tempBase)
            throw IllegalArgumentException("Backup does not contain wallet files for the selected federation")
        }

        val previous = if (targetBase.exists()) {
            File(parent, ".${targetBase.name}.pre-restore-${System.currentTimeMillis()}").also {
                if (!targetBase.renameTo(it)) {
                    deleteRecursively(tempBase)
                    throw IllegalStateException("Could not prepare existing wallet for restore")
                }
            }
        } else {
            null
        }

        val moved = tempBase.renameTo(targetBase)
        if (!moved) {
            deleteRecursively(tempBase)
            if (previous != null && previous.exists()) {
                previous.renameTo(targetBase)
            }
            throw IllegalStateException("Could not finalize restored wallet files")
        }

        if (previous != null && previous.exists()) {
            deleteRecursively(previous)
        }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        runCatching { file.delete() }
    }

    private fun showReceiveOptionsDialog() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (selected == null) {
            showSelectFederationDialog()
            return
        }
        val isFm = isFedimint(selected)

        val items = if (isFm) {
            arrayOf(host.getString(R.string.wallet_receive_lightning))
        } else {
            arrayOf(
                host.getString(R.string.wallet_receive_lightning),
                host.getString(R.string.wallet_receive_onchain),
            )
        }

        host.getAlertDialogBuilder()
            .setItems(items) { _, which ->
                when {
                    which == 0 -> showCreateInvoiceDialog()
                    !isFm && which == 1 -> showNewAddressDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateInvoiceDialog() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context) ?: return
        val isFm = isFedimint(selected)

        val vb = DialogWalletCreateInvoiceBinding.inflate(host.layoutInflater)
        vb.walletInvoiceAmount.setText("")
        vb.walletInvoiceMemo.setText(host.getString(R.string.app_launcher_name))

        host.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallet_create_invoice, null)
            .apply {
                host.setupDialogStuff(vb.root, this, R.string.wallet_receive_lightning) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val amountSats = vb.walletInvoiceAmount.text?.toString()?.trim()?.toLongOrNull()
                        val memo = vb.walletInvoiceMemo.text?.toString()?.trim().orEmpty()
                        val txLimitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                            .format(WalletPolicy.MAX_SINGLE_TX_SATS)

                        if (isFm && (amountSats == null || amountSats <= 0L)) {
                            host.toast(R.string.wallet_fedimint_fixed_amount_required)
                            return@setOnClickListener
                        }
                        if (amountSats != null && !WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                            host.toast(host.getString(R.string.wallet_amount_over_limit, txLimitText))
                            return@setOnClickListener
                        }

                        alertDialog.dismiss()

                        binding.walletProgress.beVisible()
                        ensureBackgroundThread {
                            val rate = ExchangeRateManager.getCachedUsdRate(context)
                            val expiry = WalletPolicy.invoiceExpirySeconds(amountSats, rate)

                            val invoice = if (isFm) {
                                val sats = amountSats ?: 0L
                                FedimintWalletManager.createBolt11InvoiceBlocking(context, selected, sats, memo, expirySeconds = expiry)
                            } else {
                                val started = LdkWalletManager.ensureStartedBlocking(context, selected)
                                if (started) LdkWalletManager.createBolt11Invoice(amountSats, memo, expirySeconds = expiry) else null
                            }

                            val backendError = if (isFm) FedimintWalletManager.getLastErrorMessage() else LdkWalletManager.getLastErrorMessage()
                            val error = backendError.orEmpty().ifBlank { host.getString(R.string.wallet_unknown_error) }

                            host.runOnUiThread {
                                binding.walletProgress.beGone()
                                if (invoice != null) {
                                    context.config.setWalletLastInvoiceForFederation(selected.id, invoice)
                                    context.config.setWalletLastInvoiceCreatedMsForFederation(selected.id, System.currentTimeMillis())
                                    renderMyAddresses()

                                    host.getAlertDialogBuilder()
                                        .setTitle(R.string.wallet_invoice)
                                        .setMessage(invoice)
                                        .setPositiveButton(R.string.copy) { _, _ ->
                                            host.copyToClipboard(invoice)
                                            host.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
                                        }
                                        .setNeutralButton(R.string.wallet_send_in_messages) { _, _ ->
                                            sendTextInMessages(invoice)
                                        }
                                        .setNegativeButton(R.string.share) { _, _ ->
                                            host.shareTextIntent(invoice)
                                        }
                                        .show()
                                } else {
                                    host.toast(host.getString(R.string.wallet_invoice_failed, error))
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun showNewAddressDialog() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context) ?: return
        if (isFedimint(selected)) {
            host.toast(R.string.wallet_fedimint_no_onchain)
            return
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            val started = LdkWalletManager.ensureStartedBlocking(context, selected)
            val address = if (started) LdkWalletManager.newOnchainAddress() else null

            val error = LdkWalletManager.getLastErrorMessage().orEmpty().ifBlank {
                host.getString(R.string.wallet_unknown_error)
            }

            host.runOnUiThread {
                binding.walletProgress.beGone()
                if (address != null) {
                    context.config.setWalletLastOnchainAddressForFederation(selected.id, address)
                    context.config.setWalletLastOnchainAddressCreatedMsForFederation(selected.id, System.currentTimeMillis())
                    renderMyAddresses()

                    host.getAlertDialogBuilder()
                        .setTitle(R.string.wallet_onchain_address)
                        .setMessage(address)
                        .setPositiveButton(R.string.copy) { _, _ ->
                            host.copyToClipboard(address)
                            host.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
                        }
                        .setNeutralButton(R.string.wallet_send_in_messages) { _, _ ->
                            sendTextInMessages(address)
                        }
                        .setNegativeButton(R.string.share) { _, _ ->
                            host.shareTextIntent(address)
                        }
                        .show()
                } else {
                    host.toast(host.getString(R.string.wallet_address_failed, error))
                }
            }
        }
    }

    private fun showShareSendRegenerateDialog(
        titleRes: Int,
        value: String,
        onRegenerate: () -> Unit,
    ) {
        val host = activity ?: return
        val actions = arrayOf(
            host.getString(R.string.copy),
            host.getString(R.string.wallet_send_in_messages),
            host.getString(R.string.share),
            host.getString(R.string.wallet_generate_new),
        )

        host.getAlertDialogBuilder()
            .setTitle(titleRes)
            .setMessage(value)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        host.copyToClipboard(value)
                        host.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
                    }
                    1 -> sendTextInMessages(value)
                    2 -> host.shareTextIntent(value)
                    3 -> onRegenerate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendTextInMessages(text: String) {
        val host = activity ?: return
        Intent(host, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            host.startActivity(this)
        }
    }

    private fun formatSats(value: ULong): String {
        // ULong can overflow Long theoretically, but balances are well within Long for all practical use-cases.
        val asLong = try {
            value.toLong()
        } catch (_: Exception) {
            return value.toString()
        }
        return NumberFormat.getIntegerInstance().format(asLong.absoluteValue)
    }

    private fun parseFixedInvoiceSats(text: String): Long? {
        val invoice = runCatching { Bolt11Invoice.fromStr(text.trim()) }.getOrNull() ?: return null
        val msat = runCatching { invoice.amountMilliSatoshis() }.getOrNull() ?: return null
        val sats = runCatching { (msat / 1000UL).toLong() }.getOrNull() ?: return null
        return sats.takeIf { it > 0L }
    }

    class WalletInnerBinding : InnerBinding {
        override val fragmentList = null
        override val recentsList = null
    }

    companion object {
        const val REQUEST_CODE_PICK_WALLET_CONTACT = 12021
        const val REQUEST_CODE_CREATE_WALLET_BACKUP = 12022
        const val REQUEST_CODE_OPEN_WALLET_BACKUP = 12023
    }
}
