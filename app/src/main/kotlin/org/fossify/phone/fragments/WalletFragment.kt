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
import android.widget.TextView
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
import org.fossify.phone.wallet.LiquidityProviderEntry
import org.fossify.phone.wallet.LdkWalletManager
import org.fossify.phone.wallet.WalletBackupCrypto
import org.fossify.phone.wallet.WalletContactHelper
import org.fossify.phone.wallet.WalletFederationTopupManager
import org.fossify.phone.wallet.WalletPaymentsAdapter
import org.fossify.phone.wallet.WalletPolicy
import org.fossify.phone.wallet.WalletStoragePaths
import org.fossify.phone.wallet.WalletUiDialogs
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.helpers.THREAD_AUTO_SEND
import org.fossify.messages.helpers.THREAD_TEXT
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.helpers.WalletPaymentRequestStateManager
import org.fossify.messages.helpers.WalletTokenParser
import org.fossify.messages.models.Conversation
import org.fossify.mesh.lxmf.LxmfAddress
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.PaymentStatus
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
        return FederationDirectoryManager.isFedimintFederation(selected)
    }

    private fun isBitcoinMainnet(selected: FederationEntry?): Boolean {
        if (selected == null || isFedimint(selected)) return false
        return when (selected.network?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "bitcoin", "mainnet", "btc", "" -> true
            else -> false
        }
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
        binding.walletExchange.setOnClickListener {
            showExchangeDialog()
        }
        binding.walletMint.setOnClickListener {
            showMintDialog()
        }
        binding.walletWithdraw.setOnClickListener {
            showWithdrawDialog()
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
        binding.walletMintLabel.setTextColor(heroSecondary)
        binding.walletWithdrawLabel.setTextColor(heroSecondary)

        binding.walletSend.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletReceive.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletSync.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletExchange.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletMint.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletWithdraw.backgroundTintList = ColorStateList.valueOf(actionButtonTint)
        binding.walletSend.iconTint = ColorStateList.valueOf(heroText)
        binding.walletReceive.iconTint = ColorStateList.valueOf(heroText)
        binding.walletSync.iconTint = ColorStateList.valueOf(heroText)
        binding.walletExchange.iconTint = ColorStateList.valueOf(heroText)
        binding.walletMint.iconTint = ColorStateList.valueOf(heroText)
        binding.walletWithdraw.iconTint = ColorStateList.valueOf(heroText)
        binding.walletSend.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))
        binding.walletReceive.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))
        binding.walletSync.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))
        binding.walletExchange.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))
        binding.walletMint.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))
        binding.walletWithdraw.rippleColor = ColorStateList.valueOf(heroText.adjustAlpha(0.28f))

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
            var ldkStartedForSelection: Boolean = false
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
                        val selectedFed = selected
                        val selectedFedId = selectedFed.id
                        val runningFedId = FedimintWalletManager.getRunningFederationId()
                        val busy = FedimintWalletManager.isBusy()

                        // If we already have the selected federation active, try balance directly first.
                        // The strict open-check can transiently fail during startup handoff.
                        if (!busy && runningFedId == selectedFedId) {
                            fmBalanceSats = FedimintWalletManager.getBalanceSatsBlocking(context, selectedFed)
                        }

                        // Fallback: verify/open state and schedule a (re)start if needed.
                        if (fmBalanceSats == null && !FedimintWalletManager.isBusy()) {
                            val isRunningForSelection = runningFedId == selectedFedId &&
                                FedimintWalletManager.verifyRunningFederationBlocking(context, selectedFed)
                            if (isRunningForSelection) {
                                fmBalanceSats = FedimintWalletManager.getBalanceSatsBlocking(context, selectedFed)
                            } else {
                                // Start asynchronously to keep refresh bounded and avoid UI timeouts.
                                // Throttle retries on repeated failures to prevent startup loops.
                                val nowMs = System.currentTimeMillis()
                                val retryCooldownMs = 30_000L
                                val inFlight = fedimintStartInFlightForId == selectedFedId
                                val bypassCooldown = runningFedId == selectedFedId
                                val cooldownActive = !bypassCooldown &&
                                    lastFedimintStartAttemptId == selectedFedId &&
                                    (nowMs - lastFedimintStartAttemptMs) < retryCooldownMs
                                if (!inFlight && !cooldownActive) {
                                    requestFedimintStartFor = selectedFed
                                    fedimintStartInFlightForId = selectedFedId
                                    lastFedimintStartAttemptId = selectedFedId
                                    lastFedimintStartAttemptMs = nowMs
                                }
                            }
                        }
                    } else {
                        // Leaving Fedimint selection; allow immediate future attempts when user switches back.
                        fedimintStartInFlightForId = null
                        val started = LdkWalletManager.ensureStartedBlocking(context, selected)
                        ldkStartedForSelection = started
                        if (started) {
                            // Run a blocking sync in the refresh worker so feerate failover/recovery
                            // can complete before we render balances.
                            LdkWalletManager.syncWalletsBlocking()
                        }
                        if (started) {
                            // Generate default receive data once the wallet is enabled (selection made),
                            // so the user has addresses ready without manual setup.
                            ensureDefaultReceiveDataBlocking(selected)
                        }
                    }
                }

                if (!isFm && selected != null && ldkStartedForSelection) {
                    // Clear stale lastError so a transient sync fee-rate failure doesn't
                    // persist into the rendered snapshot when listBalances succeeds.
                    balances = loadLdkBalancesWithRecovery(selected)
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

        val showExchange = selected != null && isBitcoinMainnet(selected)
        val showFedimintActions = selected != null && isFm
        binding.walletExchangeHolder.beVisibleIf(showExchange)
        binding.walletFedimintActionsRow.beVisibleIf(showFedimintActions)
        binding.walletMintHolder.beVisibleIf(showFedimintActions)
        binding.walletWithdrawHolder.beVisibleIf(showFedimintActions)
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

        val ldkError = LdkWalletManager.getLastErrorMessage()
        if (balances == null && !ldkError.isNullOrBlank()) {
            binding.walletError.text =
                context.getString(R.string.wallet_status_error, ldkError)
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
            val backend = if (FederationDirectoryManager.isFedimintFederation(entry)) {
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
        val selectedIsFedimint = isFedimint(selected)

        val vb = DialogWalletSendBinding.inflate(host.layoutInflater)
        vb.walletSendDestination.setText("")
        vb.walletSendAmount.setText("")
        activeSendDestination = vb.walletSendDestination
        if (selectedIsFedimint) {
            vb.walletSendDestinationHolder.beGone()
            vb.walletSendHintText.text = host.getString(R.string.wallet_send_request_pick_thread)
            activeSendDestination = null
        } else {
            // Contact picker shortcut.
            vb.walletSendDestinationHolder.setEndIconOnClickListener {
                launchWalletContactPicker()
            }
            vb.walletSendHintText.text = host.getString(R.string.wallet_send_hint)
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
                        val amountSats = vb.walletSendAmount.text?.toString()?.trim()?.toLongOrNull()
                        val txLimitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                            .format(WalletPolicy.MAX_SINGLE_TX_SATS)

                        if (selectedIsFedimint) {
                            if (amountSats == null || amountSats <= 0L) {
                                host.toast(R.string.wallet_send_request_amount_required)
                                return@setOnClickListener
                            }
                            if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                                host.toast(host.getString(R.string.wallet_amount_over_limit, txLimitText))
                                return@setOnClickListener
                            }

                            alertDialog.dismiss()
                            startFedimintPaymentRequestFlow(selected, amountSats)
                            return@setOnClickListener
                        }

                        val destination = vb.walletSendDestination.text?.toString()?.trim().orEmpty()
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

    private fun startFedimintPaymentRequestFlow(
        federation: FederationEntry,
        amountSats: Long,
    ) {
        val host = activity ?: return
        val requestId = WalletTokenParser.newFedimintPaymentRequestId()
        val requestMessage = WalletTokenParser.buildFedimintPaymentRequestMessage(
            requestId = requestId,
            federationId = federation.id,
            amountSats = amountSats,
            federationName = federation.name,
        )
        if (requestMessage.isBlank()) {
            host.toast(org.fossify.commons.R.string.unknown_error_occurred)
            return
        }

        showExistingThreadPicker(requestMessage) { conversation ->
            WalletPaymentRequestStateManager.registerOutgoingRequest(
                context = context,
                requestId = requestId,
                threadId = conversation.threadId,
                federationId = federation.id,
                amountSats = amountSats,
            )
            host.toast(R.string.wallet_send_request_created)
        }
    }

    private fun showExchangeDialog() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (!isBitcoinMainnet(selected)) {
            host.toast(R.string.wallet_select_federation)
            return
        }

        val items = arrayOf(
            host.getString(R.string.wallet_exchange_onchain_to_lightning),
            host.getString(R.string.wallet_exchange_lightning_to_onchain),
        )
        host.getAlertDialogBuilder()
            .setTitle(R.string.wallet_exchange_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptAmountDialog(
                        titleRes = R.string.wallet_exchange_onchain_to_lightning,
                        hintRes = R.string.wallet_exchange_amount_hint,
                    ) { amountSats ->
                        exchangeOnchainToLightning(amountSats)
                    }

                    1 -> promptAmountDialog(
                        titleRes = R.string.wallet_exchange_lightning_to_onchain,
                        hintRes = R.string.wallet_exchange_amount_hint,
                    ) { amountSats ->
                        exchangeLightningToOnchain(amountSats)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMintDialog() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (!isFedimint(selected)) {
            host.toast(R.string.wallet_select_federation)
            return
        }

        promptAmountDialog(
            titleRes = R.string.wallet_mint_title,
            hintRes = R.string.wallet_mint_amount_hint,
        ) { amountSats ->
            mintToSelectedFederation(amountSats)
        }
    }

    private fun showWithdrawDialog() {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (!isFedimint(selected)) {
            host.toast(R.string.wallet_select_federation)
            return
        }

        promptAmountDialog(
            titleRes = R.string.wallet_withdraw_title,
            hintRes = R.string.wallet_withdraw_amount_hint,
        ) { amountSats ->
            withdrawFromSelectedFederation(amountSats)
        }
    }

    private fun promptAmountDialog(
        titleRes: Int,
        hintRes: Int,
        onAmount: (Long) -> Unit,
    ) {
        val host = activity ?: return
        val density = host.resources.displayMetrics.density
        val spacing = (16f * density).toInt()

        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing / 2, spacing, 0)
        }
        val amountView = EditText(host).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = host.getString(hintRes)
        }
        container.addView(
            amountView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        host.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .apply {
                host.setupDialogStuff(container, this, titleRes) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val amountSats = amountView.text?.toString()?.trim()?.toLongOrNull() ?: 0L
                        val txLimitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                            .format(WalletPolicy.MAX_SINGLE_TX_SATS)
                        if (amountSats <= 0L) {
                            host.toast(R.string.wallet_exchange_amount_required)
                            return@setOnClickListener
                        }
                        if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                            host.toast(host.getString(R.string.wallet_amount_over_limit, txLimitText))
                            return@setOnClickListener
                        }
                        alertDialog.dismiss()
                        onAmount(amountSats)
                    }
                }
            }
    }

    private fun exchangeOnchainToLightning(amountSats: Long) {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (!isBitcoinMainnet(selected) || selected == null) {
            host.toast(R.string.wallet_select_federation)
            return
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            try {
                val started = LdkWalletManager.ensureStartedBlocking(context, selected)
                var errorMessage: String? = null

                if (started) {
                    val estimatedFunding = LdkWalletManager.estimateLiquidityBootstrapFundingSats(amountSats)
                    host.runOnUiThread {
                        binding.walletProgress.beGone()
                        showAdvancedConvertWarningDialog(
                            selected = selected,
                            amountSats = amountSats,
                            estimatedFundingSats = estimatedFunding,
                        )
                    }
                    return@ensureBackgroundThread
                } else {
                    errorMessage = LdkWalletManager.getLastErrorMessage()
                }

                val finalError = errorMessage.orEmpty().ifBlank {
                    LdkWalletManager.getLastErrorMessage()
                        ?: host.getString(R.string.wallet_unknown_error)
                }

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    when {
                        else -> host.toast(host.getString(R.string.wallet_exchange_failed, finalError))
                    }
                }
            } catch (t: Throwable) {
                val message = t.message?.trim().orEmpty().ifBlank {
                    host.getString(R.string.wallet_unknown_error)
                }
                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    host.toast(host.getString(R.string.wallet_exchange_failed, message))
                }
            }
        }
    }

    private fun showAdvancedConvertWarningDialog(
        selected: FederationEntry,
        amountSats: Long,
        estimatedFundingSats: Long,
    ) {
        val host = activity ?: return
        host.getAlertDialogBuilder()
            .setTitle(R.string.wallet_exchange_advanced_title)
            .setMessage(
                host.getString(
                    R.string.wallet_exchange_bootstrap_warning,
                    formatSats(amountSats.toULong()),
                    formatSats(estimatedFundingSats.toULong())
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallet_exchange_advanced_button) { _, _ ->
                showAdvancedConvertConfirmationDialog(
                    selected = selected,
                    amountSats = amountSats,
                    estimatedFundingSats = estimatedFundingSats,
                )
            }
            .show()
    }

    private fun showAdvancedConvertConfirmationDialog(
        selected: FederationEntry,
        amountSats: Long,
        estimatedFundingSats: Long,
    ) {
        val host = activity ?: return
        val density = host.resources.displayMetrics.density
        val spacing = (16f * density).toInt()

        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing / 2, spacing, 0)
        }
        val messageView = TextView(host).apply {
            text = host.getString(
                R.string.wallet_exchange_advanced_confirm_message,
                formatSats(amountSats.toULong()),
                formatSats(estimatedFundingSats.toULong())
            )
        }
        val confirmInput = EditText(host).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = host.getString(R.string.wallet_exchange_advanced_confirm_hint)
            setSingleLine()
        }
        container.addView(
            messageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            confirmInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = spacing / 2
            }
        )

        host.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallet_exchange_advanced_execute, null)
            .apply {
                host.setupDialogStuff(container, this, R.string.wallet_exchange_advanced_confirm_title) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val typed = confirmInput.text?.toString()?.trim().orEmpty()
                        if (typed.uppercase(Locale.ROOT) != "CONFIRM") {
                            host.toast(R.string.wallet_exchange_advanced_confirm_required)
                            return@setOnClickListener
                        }
                        alertDialog.dismiss()
                        runAdvancedConvertOnchainToLightning(selected, amountSats)
                    }
                }
            }
    }

    private fun runAdvancedConvertOnchainToLightning(
        selected: FederationEntry,
        amountSats: Long,
    ) {
        val host = activity ?: return
        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            try {
                val started = LdkWalletManager.ensureStartedBlocking(context, selected)
                var success = false
                var pendingMessage: String? = null
                var errorMessage: String? = null

                if (started) {
                    val direct = LdkWalletManager.bootstrapOutboundLiquidityBlocking(
                        context = context,
                        requiredSats = amountSats,
                    )
                    when {
                        direct.success -> success = true
                        direct.pending -> {
                            pendingMessage = direct.errorMessage
                                ?: host.getString(R.string.wallet_exchange_advanced_pending)
                        }

                        else -> {
                            val fallback = LdkWalletManager.bootstrapOutboundLiquidityViaGatewayHintsBlocking(
                                context = context,
                                gatewayNodeIds = emptyList(),
                                requiredSats = amountSats,
                                network = selected.network,
                            )
                            when {
                                fallback.success -> success = true
                                fallback.pending -> {
                                    pendingMessage = fallback.errorMessage
                                        ?: host.getString(R.string.wallet_exchange_advanced_pending)
                                }

                                else -> {
                                    errorMessage = fallback.errorMessage ?: direct.errorMessage
                                }
                            }
                        }
                    }
                } else {
                    errorMessage = LdkWalletManager.getLastErrorMessage()
                }

                val finalError = errorMessage.orEmpty().ifBlank {
                    LdkWalletManager.getLastErrorMessage()
                        ?: host.getString(R.string.wallet_unknown_error)
                }

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    when {
                        success -> {
                            host.toast(R.string.wallet_exchange_submitted)
                            refreshAll(force = false)
                        }
                        !pendingMessage.isNullOrBlank() -> host.toast(pendingMessage)
                        else -> host.toast(host.getString(R.string.wallet_exchange_failed, finalError))
                    }
                }
            } catch (t: Throwable) {
                val message = t.message?.trim().orEmpty().ifBlank {
                    host.getString(R.string.wallet_unknown_error)
                }
                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    host.toast(host.getString(R.string.wallet_exchange_failed, message))
                }
            }
        }
    }

    private fun exchangeLightningToOnchain(amountSats: Long) {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (!isBitcoinMainnet(selected) || selected == null) {
            host.toast(R.string.wallet_select_federation)
            return
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            try {
                val started = LdkWalletManager.ensureStartedBlocking(context, selected)
                var errorMessage: String? = null

                if (started) {
                    val plan = LdkWalletManager.estimateLightningToOnchainChannelCloseSats(amountSats)
                    if (plan == null) {
                        errorMessage = LdkWalletManager.getLastErrorMessage()
                    } else {
                        host.runOnUiThread {
                            binding.walletProgress.beGone()
                            showAdvancedReverseConvertWarningDialog(
                                selected = selected,
                                amountSats = amountSats,
                                plan = plan,
                            )
                        }
                        return@ensureBackgroundThread
                    }
                } else {
                    errorMessage = LdkWalletManager.getLastErrorMessage()
                }

                val finalError = errorMessage.orEmpty().ifBlank {
                    LdkWalletManager.getLastErrorMessage()
                        ?: host.getString(R.string.wallet_unknown_error)
                }

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    when {
                        else -> host.toast(host.getString(R.string.wallet_exchange_failed, finalError))
                    }
                }
            } catch (t: Throwable) {
                val message = t.message?.trim().orEmpty().ifBlank {
                    host.getString(R.string.wallet_unknown_error)
                }
                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    host.toast(host.getString(R.string.wallet_exchange_failed, message))
                }
            }
        }
    }

    private fun showAdvancedReverseConvertWarningDialog(
        selected: FederationEntry,
        amountSats: Long,
        plan: LdkWalletManager.LightningToOnchainPlan,
    ) {
        val host = activity ?: return
        host.getAlertDialogBuilder()
            .setTitle(R.string.wallet_exchange_advanced_title)
            .setMessage(
                host.getString(
                    R.string.wallet_exchange_reverse_warning,
                    formatSats(amountSats.toULong()),
                    plan.channelsToClose,
                    formatSats(plan.estimatedReleaseSats.toULong()),
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallet_exchange_advanced_button) { _, _ ->
                showAdvancedReverseConvertConfirmationDialog(
                    selected = selected,
                    amountSats = amountSats,
                    plan = plan,
                )
            }
            .show()
    }

    private fun showAdvancedReverseConvertConfirmationDialog(
        selected: FederationEntry,
        amountSats: Long,
        plan: LdkWalletManager.LightningToOnchainPlan,
    ) {
        val host = activity ?: return
        val density = host.resources.displayMetrics.density
        val spacing = (16f * density).toInt()

        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing / 2, spacing, 0)
        }
        val messageView = TextView(host).apply {
            text = host.getString(
                R.string.wallet_exchange_reverse_confirm_message,
                formatSats(amountSats.toULong()),
                plan.channelsToClose,
                formatSats(plan.estimatedReleaseSats.toULong()),
            )
        }
        val confirmInput = EditText(host).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = host.getString(R.string.wallet_exchange_advanced_confirm_hint)
            setSingleLine()
        }
        container.addView(
            messageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            confirmInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = spacing / 2
            }
        )

        host.getAlertDialogBuilder()
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallet_exchange_advanced_execute, null)
            .apply {
                host.setupDialogStuff(container, this, R.string.wallet_exchange_advanced_confirm_title) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val typed = confirmInput.text?.toString()?.trim().orEmpty()
                        if (typed.uppercase(Locale.ROOT) != "CONFIRM") {
                            host.toast(R.string.wallet_exchange_advanced_confirm_required)
                            return@setOnClickListener
                        }
                        alertDialog.dismiss()
                        runAdvancedConvertLightningToOnchain(selected, amountSats)
                    }
                }
            }
    }

    private fun runAdvancedConvertLightningToOnchain(
        selected: FederationEntry,
        amountSats: Long,
    ) {
        val host = activity ?: return
        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            try {
                val started = LdkWalletManager.ensureStartedBlocking(context, selected)
                var success = false
                var pendingMessage: String? = null
                var errorMessage: String? = null

                if (started) {
                    val result = LdkWalletManager.convertLightningToOnchainByClosingChannelsBlocking(amountSats)
                    when {
                        result.success -> success = true

                        result.pending -> {
                            pendingMessage = result.errorMessage
                                ?: host.getString(R.string.wallet_exchange_reverse_pending)
                        }

                        else -> {
                            errorMessage = result.errorMessage
                        }
                    }
                } else {
                    errorMessage = LdkWalletManager.getLastErrorMessage()
                }

                val finalError = errorMessage.orEmpty().ifBlank {
                    LdkWalletManager.getLastErrorMessage()
                        ?: host.getString(R.string.wallet_unknown_error)
                }

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    when {
                        success -> {
                            host.toast(R.string.wallet_exchange_submitted)
                            refreshAll(force = false)
                        }
                        !pendingMessage.isNullOrBlank() -> {
                            host.toast(pendingMessage)
                            refreshAll(force = false)
                        }
                        else -> host.toast(host.getString(R.string.wallet_exchange_failed, finalError))
                    }
                }
            } catch (t: Throwable) {
                val message = t.message?.trim().orEmpty().ifBlank {
                    host.getString(R.string.wallet_unknown_error)
                }
                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    host.toast(host.getString(R.string.wallet_exchange_failed, message))
                }
            }
        }
    }

    private fun mintToSelectedFederation(amountSats: Long) {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (!isFedimint(selected) || selected == null) {
            host.toast(R.string.wallet_select_federation)
            return
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            try {
                val mintInvoice = FedimintWalletManager.createBolt11InvoiceBlocking(
                    context = context,
                    federation = selected,
                    amountSats = amountSats,
                    memo = "Federation mint",
                    expirySeconds = 15 * 60,
                )
                if (mintInvoice.isNullOrBlank()) {
                    val err = FedimintWalletManager.getLastErrorMessage().orEmpty().ifBlank {
                        host.getString(R.string.wallet_unknown_error)
                    }
                    host.runOnUiThread {
                        binding.walletProgress.beGone()
                        host.toast(host.getString(R.string.wallet_mint_failed, err))
                    }
                    return@ensureBackgroundThread
                }

                val quote = WalletFederationTopupManager.buildTopupQuote(
                    context = context,
                    targetFederation = selected,
                    invoice = mintInvoice,
                    currentFederationBalanceSats = 0L,
                    assumeZeroOnUnknownBalance = true,
                )
                if (quote == null) {
                    host.runOnUiThread {
                        binding.walletProgress.beGone()
                        host.toast(host.getString(R.string.wallet_mint_failed, host.getString(R.string.wallet_federation_topup_unavailable)))
                    }
                    return@ensureBackgroundThread
                }

                val topup = WalletFederationTopupManager.topupFromMainnetBlocking(context, quote)
                val err = topup.errorMessage
                    ?: FedimintWalletManager.getLastErrorMessage()
                    ?: host.getString(R.string.wallet_unknown_error)

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    if (topup.success) {
                        host.toast(R.string.wallet_mint_success)
                        refreshAll(force = false)
                    } else {
                        host.toast(host.getString(R.string.wallet_mint_failed, err))
                    }
                }
            } catch (t: Throwable) {
                val message = LdkWalletManager.summarizeThrowableForUi(t)
                    ?: FedimintWalletManager.getLastErrorMessage()
                    ?: LdkWalletManager.normalizeExternalErrorMessage(t.message)
                    ?: host.getString(R.string.wallet_unknown_error)
                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    host.toast(host.getString(R.string.wallet_mint_failed, message))
                }
            }
        }
    }

    private fun withdrawFromSelectedFederation(amountSats: Long) {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (!isFedimint(selected) || selected == null) {
            host.toast(R.string.wallet_select_federation)
            return
        }

        fun resolveWithdrawError(defaultMessage: String, vararg candidates: String?): String {
            return candidates.asSequence()
                .mapNotNull { it?.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: defaultMessage
        }

        val source = WalletFederationTopupManager.findMainnetSourceFederation(
            context = context,
            targetFederationId = selected.id,
            targetNetwork = selected.network,
        )
        if (source == null) {
            host.toast(R.string.wallet_withdraw_source_unavailable)
            return
        }

        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            try {
                val sourceStarted = LdkWalletManager.ensureStartedBlocking(context, source)
                if (!sourceStarted) {
                    val err = resolveWithdrawError(
                        defaultMessage = host.getString(R.string.wallet_withdraw_source_start_failed),
                        LdkWalletManager.getLastErrorMessage(),
                        FedimintWalletManager.getLastErrorMessage(),
                    )
                    host.runOnUiThread {
                        binding.walletProgress.beGone()
                        host.toast(host.getString(R.string.wallet_withdraw_failed, err))
                    }
                    return@ensureBackgroundThread
                }

                var inboundLiquidity = LdkWalletManager.getUsableInboundLiquiditySats() ?: 0L
                val activeLiquidityProviderId = LdkWalletManager.getActiveLiquidityProviderId().orEmpty()
                val autoResolvedProvider = FederationDirectoryManager.resolveLiquidityProvider(context, source)
                val liveFedimintGatewayNodeIds = FedimintWalletManager.discoverLightningGatewayNodeIdsBlocking(
                    context = context,
                    federation = selected,
                )
                val combinedGatewayNodeIds = (selected.vettedGateways + liveFedimintGatewayNodeIds)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
                val autoGatewayCandidates = LdkWalletManager.discoverLspsCandidatesFromGatewayHints(
                    network = source.network,
                    gatewayNodeIds = combinedGatewayNodeIds,
                    maxCandidates = 6,
                )
                val autoDirectoryProviders = FederationDirectoryManager
                    .getLiquidityProviders(context, source.network)
                    .asSequence()
                    .filter { it.id.trim().isNotBlank() }
                    .filter { it.nodeId.trim().isNotBlank() && it.address.trim().isNotBlank() }
                    .take(6)
                    .toList()
                val hasAnyLiquidityPath = inboundLiquidity >= amountSats ||
                    activeLiquidityProviderId.isNotBlank() ||
                    autoResolvedProvider != null ||
                    autoGatewayCandidates.isNotEmpty() ||
                    autoDirectoryProviders.isNotEmpty()
                if (!hasAnyLiquidityPath) {
                    host.runOnUiThread {
                        binding.walletProgress.beGone()
                        host.toast(
                            host.getString(
                                R.string.wallet_withdraw_incoming_liquidity_missing,
                                formatSats(amountSats.toULong()),
                                formatSats(inboundLiquidity.toULong()),
                            )
                        )
                    }
                    return@ensureBackgroundThread
                }

                // Prime chain state before invoice creation to reduce transient fee-rate failures.
                // Use no-recovery variant so the recovery budget is preserved for invoice creation.
                LdkWalletManager.syncWalletsBlockingNoRecovery()

                var receiveInvoice = createWithdrawReceiveInvoiceWithRecovery(
                    sourceFederation = source,
                    amountSats = amountSats,
                    preferJitChannel = true,
                    maxAttempts = 3,
                )
                if (receiveInvoice.isNullOrBlank()) {
                    val firstError = LdkWalletManager.getLastErrorMessage().orEmpty()
                    val shouldRetryAfterRestart = autoResolvedProvider != null &&
                        isLikelyLiquiditySetupError(firstError)

                    if (shouldRetryAfterRestart) {
                        // Rebuild the node so a newly resolved provider gets applied deterministically.
                        val restarted = restartLdkWalletBlocking(source)
                        if (restarted) {
                            inboundLiquidity = LdkWalletManager.getUsableInboundLiquiditySats() ?: inboundLiquidity
                            receiveInvoice = createWithdrawReceiveInvoiceWithRecovery(
                                sourceFederation = source,
                                amountSats = amountSats,
                                preferJitChannel = true,
                                maxAttempts = 2,
                            )
                        }
                    }

                    // If inbound is already available but JIT path keeps failing, fallback to standard receive.
                    if (receiveInvoice.isNullOrBlank() && inboundLiquidity >= amountSats) {
                        receiveInvoice = createWithdrawReceiveInvoiceWithRecovery(
                            sourceFederation = source,
                            amountSats = amountSats,
                            preferJitChannel = false,
                            maxAttempts = 2,
                        )
                    }

                    if (receiveInvoice.isNullOrBlank() && autoGatewayCandidates.isNotEmpty()) {
                        receiveInvoice = tryAutoLiquidityProviderFromGateways(
                            sourceFederation = source,
                            targetFederation = selected,
                            amountSats = amountSats,
                            candidates = autoGatewayCandidates,
                        )
                    }

                    if (receiveInvoice.isNullOrBlank() && autoDirectoryProviders.isNotEmpty()) {
                        receiveInvoice = tryAutoLiquidityProviderFromDirectory(
                            sourceFederation = source,
                            amountSats = amountSats,
                            providers = autoDirectoryProviders,
                        )
                    }
                }
                if (receiveInvoice.isNullOrBlank()) {
                    val err = resolveWithdrawError(
                        defaultMessage = host.getString(R.string.wallet_withdraw_invoice_create_failed),
                        LdkWalletManager.getLastErrorMessage(),
                        FedimintWalletManager.getLastErrorMessage(),
                    )
                    host.runOnUiThread {
                        binding.walletProgress.beGone()
                        host.toast(host.getString(R.string.wallet_withdraw_failed, err))
                    }
                    return@ensureBackgroundThread
                }

                val payAccepted = FedimintWalletManager.payBolt11InvoiceBlocking(
                    context = context,
                    federation = selected,
                    invoice = receiveInvoice,
                )
                var withdrawSucceeded = false
                var withdrawPending = false
                var err = FedimintWalletManager.getLastErrorMessage()

                if (payAccepted) {
                    when (
                        LdkWalletManager.awaitIncomingLightningInvoiceStatusBlocking(
                            invoiceStr = receiveInvoice,
                            timeoutMs = 180_000L,
                            failOnTimeout = false,
                        )
                    ) {
                        PaymentStatus.SUCCEEDED -> {
                            withdrawSucceeded = true
                            err = null
                        }

                        PaymentStatus.PENDING -> {
                            withdrawPending = true
                            err = LdkWalletManager.getLastErrorMessage()
                                ?: FedimintWalletManager.getLastErrorMessage()
                        }

                        PaymentStatus.FAILED,
                        null,
                        -> {
                            val statusError = LdkWalletManager.getLastErrorMessage()
                                ?: FedimintWalletManager.getLastErrorMessage()
                            if (isLikelyFeerateError(statusError)) {
                                withdrawPending = true
                                err = statusError
                            } else {
                                err = statusError
                            }
                        }
                    }
                } else {
                    err = resolveWithdrawError(
                        defaultMessage = host.getString(R.string.wallet_withdraw_payment_rejected),
                        err,
                        FedimintWalletManager.getLastErrorMessage(),
                        LdkWalletManager.getLastErrorMessage(),
                    )
                }

                if (!withdrawSucceeded && !withdrawPending) {
                    err = resolveWithdrawError(
                        defaultMessage = host.getString(R.string.wallet_withdraw_settlement_failed),
                        err,
                        LdkWalletManager.getLastErrorMessage(),
                        FedimintWalletManager.getLastErrorMessage(),
                    )
                }

                val finalError = resolveWithdrawError(
                    defaultMessage = host.getString(R.string.wallet_withdraw_settlement_failed),
                    err,
                    LdkWalletManager.getLastErrorMessage(),
                    FedimintWalletManager.getLastErrorMessage(),
                )

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    if (withdrawSucceeded) {
                        host.toast(R.string.wallet_withdraw_success)
                        refreshAll(force = false)
                    } else if (withdrawPending) {
                        host.toast(R.string.wallet_withdraw_pending)
                        refreshAll(force = false)
                    } else {
                        host.toast(host.getString(R.string.wallet_withdraw_failed, finalError))
                    }
                }
            } catch (t: Throwable) {
                val message = resolveWithdrawError(
                    defaultMessage = host.getString(R.string.wallet_withdraw_settlement_failed),
                    LdkWalletManager.summarizeThrowableForUi(t),
                    LdkWalletManager.getLastErrorMessage(),
                    FedimintWalletManager.getLastErrorMessage(),
                    LdkWalletManager.normalizeExternalErrorMessage(t.message),
                )
                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    host.toast(host.getString(R.string.wallet_withdraw_failed, message))
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
        val existing = WalletContactHelper.getWalletDestinations(host, rawId, 0)
        vb.contactWalletAddress.setText(existing.onchain.orEmpty())
        vb.contactWalletLightning.setText(existing.lightning.orEmpty())

        host.getAlertDialogBuilder()
            .setNeutralButton(R.string.clear) { _, _ ->
                WalletContactHelper.deleteWalletDestination(host, rawId)
                activeSendDestination?.setText("")
                host.toast(R.string.done)
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, null)
            .apply {
                host.setupDialogStuff(vb.root, this, R.string.contact_wallet_address) { alertDialog ->
                    alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val onchain = vb.contactWalletAddress.text?.toString()?.trim().orEmpty()
                        val lightning = vb.contactWalletLightning.text?.toString()?.trim().orEmpty()
                        if (onchain.isBlank() && lightning.isBlank()) {
                            host.toast(R.string.contact_wallet_destination_required)
                            return@setOnClickListener
                        }
                        if (onchain.isBlank()) {
                            WalletContactHelper.deleteWalletOnchainDestination(host, rawId)
                        } else {
                            WalletContactHelper.upsertWalletOnchainDestination(host, rawId, onchain)
                        }
                        if (lightning.isBlank()) {
                            WalletContactHelper.deleteWalletLightningDestination(host, rawId)
                        } else {
                            WalletContactHelper.upsertWalletLightningDestination(host, rawId, lightning)
                        }
                        activeSendDestination?.setText(if (lightning.isNotBlank()) lightning else onchain)
                        host.toast(R.string.done)
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun sendPayment(destination: String, amountSats: Long?) {
        val host = activity ?: return
        val selected = FederationDirectoryManager.getSelectedFederation(context) ?: return
        val normalizedDestination = normalizePayDestination(destination)
        if (normalizedDestination.isBlank()) {
            host.toast(R.string.wallet_send_destination_required)
            return
        }

        var effectiveFederation = selected
        var isFm = isFedimint(effectiveFederation)
        val isBolt11Destination = LdkWalletManager.isBolt11Invoice(normalizedDestination)
        val fixedDestinationInvoiceSats = if (isBolt11Destination) parseFixedInvoiceSats(normalizedDestination) else null
        if (isFm && (!isBolt11Destination || fixedDestinationInvoiceSats == null)) {
            WalletFederationTopupManager.findMainnetSourceFederation(
                context = context,
                targetFederationId = selected.id,
                targetNetwork = selected.network,
            )?.let { mainnetSource ->
                effectiveFederation = mainnetSource
                isFm = false
            }
        }
        val tryFedimintFallback = FederationDirectoryManager.shouldTryFedimintFallback(effectiveFederation)

        binding.walletProgress.beVisible()

        ensureBackgroundThread {
            try {
                val started = if (isFm) {
                    FedimintWalletManager.ensureStartedBlocking(context, effectiveFederation)
                } else {
                    LdkWalletManager.ensureStartedBlocking(context, effectiveFederation)
                }
                var successMessage: String? = null
                var pendingMessage: String? = null
                var userErrorMessage: String? = null
                var usedFedimintBackend = isFm

                val txLimitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                    .format(WalletPolicy.MAX_SINGLE_TX_SATS)

                if (started) {
                    val isBolt11 = LdkWalletManager.isBolt11Invoice(normalizedDestination)
                    val fixedInvoiceSats = if (isBolt11) parseFixedInvoiceSats(normalizedDestination) else null
                    if (fixedInvoiceSats != null && !WalletPolicy.isAmountWithinSingleTxLimit(fixedInvoiceSats)) {
                        userErrorMessage = host.getString(R.string.wallet_amount_over_limit, txLimitText)
                    } else if (amountSats != null && !WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                        userErrorMessage = host.getString(R.string.wallet_amount_over_limit, txLimitText)
                    }

                    if (userErrorMessage == null) {
                        if (isFm && isBolt11) {
                            // Proactive top-up only when we can confidently read federation balance.
                            val proactiveTopupQuote = WalletFederationTopupManager.buildTopupQuote(
                                context = context,
                                targetFederation = effectiveFederation,
                                invoice = normalizedDestination,
                                assumeZeroOnUnknownBalance = false,
                            )
                            if (proactiveTopupQuote != null) {
                                host.runOnUiThread {
                                    binding.walletProgress.beGone()
                                    showMintTopupDialogForWallet(
                                        quote = proactiveTopupQuote,
                                        destination = normalizedDestination,
                                    )
                                }
                                return@ensureBackgroundThread
                            }
                        }

                        if (!isBolt11) {
                            // Fedimint currently supports Lightning (BOLT11) only via the embedded SDK.
                            if (isFm) {
                                userErrorMessage = host.getString(R.string.wallet_fedimint_invoice_only)
                            } else {
                                if (amountSats == null || amountSats <= 0L) {
                                    userErrorMessage = host.getString(R.string.wallet_send_amount_required)
                                } else {
                                    val txId = LdkWalletManager.sendOnchain(normalizedDestination, amountSats)
                                    if (txId != null) {
                                        successMessage = host.getString(R.string.wallet_send_submitted)
                                    }
                                }
                            }
                        } else {
                            if (isFm) {
                                val fixed = parseFixedInvoiceSats(normalizedDestination)
                                if (fixed == null) {
                                    userErrorMessage = host.getString(R.string.wallet_fedimint_variable_invoice_requires_mainnet)
                                } else {
                                    val ok = FedimintWalletManager.payBolt11InvoiceBlocking(context, effectiveFederation, normalizedDestination)
                                    if (ok) successMessage = host.getString(R.string.wallet_send_submitted)
                                }
                            } else {
                                val id = LdkWalletManager.payBolt11Invoice(normalizedDestination, amountSats)
                                var allowFedimintFallback = true
                                if (id != null) {
                                    when (LdkWalletManager.awaitOutgoingLightningPaymentStatusBlocking(id)) {
                                        PaymentStatus.SUCCEEDED -> {
                                            successMessage = host.getString(R.string.wallet_send_success)
                                            allowFedimintFallback = false
                                        }

                                        PaymentStatus.PENDING -> {
                                            pendingMessage = host.getString(R.string.wallet_send_pending)
                                            allowFedimintFallback = false
                                        }

                                        PaymentStatus.FAILED,
                                        null,
                                        -> {
                                            // Keep allowFedimintFallback = true.
                                        }
                                    }
                                }

                                if (allowFedimintFallback && tryFedimintFallback) {
                                    usedFedimintBackend = true
                                    val fmStarted = FedimintWalletManager.ensureStartedBlocking(context, effectiveFederation)
                                    val fmOk = fmStarted && FedimintWalletManager.payBolt11InvoiceBlocking(
                                        context = context,
                                        federation = effectiveFederation,
                                        invoice = normalizedDestination,
                                    )
                                    if (fmOk) {
                                        successMessage = host.getString(R.string.wallet_send_submitted)
                                    }
                                }
                            }
                        }
                    }
                }

                val backendError = if (usedFedimintBackend) {
                    FedimintWalletManager.getLastErrorMessage()
                } else {
                    LdkWalletManager.getLastErrorMessage()
                }
                val error = backendError.orEmpty().ifBlank {
                    host.getString(R.string.wallet_unknown_error)
                }
                val insufficientBalance = (
                    usedFedimintBackend &&
                    successMessage == null &&
                    pendingMessage == null &&
                    userErrorMessage == null &&
                    WalletFederationTopupManager.isLikelyInsufficientBalance(error)
                    )
                val topupQuote = if (insufficientBalance) {
                    WalletFederationTopupManager.buildTopupQuote(
                        context = context,
                        targetFederation = effectiveFederation,
                        invoice = normalizedDestination,
                    )
                } else {
                    null
                }
                val finalError = if (insufficientBalance && topupQuote == null) {
                    host.getString(R.string.wallet_federation_topup_unavailable)
                } else {
                    error
                }

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    if (successMessage != null) {
                        host.toast(successMessage!!)
                        refreshAll(force = false)
                    } else if (pendingMessage != null) {
                        host.toast(pendingMessage!!)
                        refreshAll(force = false)
                    } else if (topupQuote != null) {
                        showMintTopupDialogForWallet(
                            quote = topupQuote,
                            destination = destination,
                        )
                    } else if (userErrorMessage != null) {
                        host.toast(userErrorMessage!!)
                    } else {
                        host.toast(host.getString(R.string.wallet_send_failed, finalError))
                    }
                }
            } catch (t: Throwable) {
                val error = t.message?.trim().orEmpty().ifBlank { host.getString(R.string.wallet_unknown_error) }
                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    host.toast(host.getString(R.string.wallet_send_failed, error))
                }
            }
        }
    }

    private fun showMintTopupDialogForWallet(
        quote: WalletFederationTopupManager.TopupQuote,
        destination: String,
    ) {
        val host = activity ?: return
        WalletUiDialogs.showTopupConfirmDialog(
            activity = host,
            quote = quote,
            onConfirm = {
                performTopupAndRetryWalletPayment(
                    quote = quote,
                    destination = destination,
                )
            },
        )
    }

    private fun performTopupAndRetryWalletPayment(
        quote: WalletFederationTopupManager.TopupQuote,
        destination: String,
    ) {
        val host = activity ?: return
        binding.walletProgress.beVisible()

        ensureBackgroundThread {
            try {
                val topup = WalletFederationTopupManager.topupFromMainnetBlocking(context, quote)
                val paidAfterTopup = if (topup.success) {
                    FedimintWalletManager.payBolt11InvoiceBlocking(
                        context = context,
                        federation = quote.targetFederation,
                        invoice = destination,
                    )
                } else {
                    false
                }
                val paidByDirectRetry = if (!topup.success) {
                    // If top-up fails due source-side constraints, retry direct federation payment once.
                    FedimintWalletManager.payBolt11InvoiceBlocking(
                        context = context,
                        federation = quote.targetFederation,
                        invoice = destination,
                    )
                } else {
                    false
                }
                val paid = paidAfterTopup || paidByDirectRetry

                val error = when {
                    paid -> null
                    topup.pending -> topup.errorMessage
                    !topup.success -> topup.errorMessage ?: FedimintWalletManager.getLastErrorMessage()
                    else -> FedimintWalletManager.getLastErrorMessage()
                }.orEmpty().ifBlank { host.getString(R.string.wallet_unknown_error) }

                host.runOnUiThread {
                    binding.walletProgress.beGone()
                    if (paid) {
                        host.toast(R.string.wallet_send_submitted)
                        refreshAll(force = false)
                    } else {
                        host.toast(host.getString(R.string.wallet_send_failed, error))
                    }
                }
            } catch (t: Throwable) {
                val error = t.message?.trim().orEmpty().ifBlank { host.getString(R.string.wallet_unknown_error) }
                host.runOnUiThread {
                    binding.walletProgress.beGone()
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
                                    val invoiceMessage = WalletTokenParser.buildLightningInvoiceMessage(
                                        invoice = invoice,
                                        federationId = selected.id,
                                        federationName = selected.name,
                                    ).ifBlank { invoice }

                                    WalletUiDialogs.showInvoicePreviewDialog(
                                        activity = host,
                                        federation = selected,
                                        invoiceMessage = invoiceMessage,
                                        onSendInMessages = { payload -> sendTextInMessages(payload) }
                                    )
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
        val payload = buildWalletMessageForSend(text)
        if (payload.isBlank()) {
            host.toast(R.string.wallet_send_destination_required)
            return
        }

        val items = arrayOf(
            host.getString(R.string.wallet_send_existing_thread),
            host.getString(R.string.wallet_send_new_message),
        )
        host.getAlertDialogBuilder()
            .setTitle(R.string.wallet_send_in_messages)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showExistingThreadPicker(payload)
                    1 -> openNewConversationWithPrefill(payload)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildWalletMessageForSend(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        val selected = FederationDirectoryManager.getSelectedFederation(context)
        if (selected != null && LdkWalletManager.isBolt11Invoice(trimmed)) {
            return WalletTokenParser.buildLightningInvoiceMessage(
                invoice = trimmed,
                federationId = selected.id,
                federationName = selected.name,
            ).ifBlank { trimmed }
        }
        return trimmed
    }

    private fun openNewConversationWithPrefill(text: String) {
        val host = activity ?: return
        Intent(host, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            host.startActivity(this)
        }
    }

    private fun showExistingThreadPicker(
        prefillText: String,
        onConversationSelected: ((Conversation) -> Unit)? = null,
    ) {
        val host = activity ?: return
        binding.walletProgress.beVisible()
        ensureBackgroundThread {
            val conversations = runCatching {
                context.conversationsDB.getNonArchived()
            }.getOrDefault(emptyList())
                .filterNot { it.isScheduled }
                .sortedByDescending { it.date }

            host.runOnUiThread {
                binding.walletProgress.beGone()
                if (conversations.isEmpty()) {
                    host.toast(org.fossify.messages.R.string.no_conversations_found)
                    return@runOnUiThread
                }

                val labels = conversations.map { conversation ->
                    val title = conversation.title.trim().ifBlank { conversation.phoneNumber.trim() }
                        .ifBlank { conversation.threadId.toString() }
                    if (LxmfAddress.isMeshThreadId(conversation.threadId) ||
                        LxmfAddress.isMeshLike(conversation.phoneNumber)
                    ) {
                        host.getString(R.string.wallet_thread_mesh_label, title)
                    } else {
                        title
                    }
                }.toTypedArray()

                host.getAlertDialogBuilder()
                    .setTitle(R.string.wallet_send_existing_thread)
                    .setItems(labels) { _, which ->
                        conversations.getOrNull(which)?.let { conversation ->
                            onConversationSelected?.invoke(conversation)
                            openExistingThreadWithPrefill(conversation, prefillText)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun openExistingThreadWithPrefill(conversation: Conversation, prefillText: String) {
        val host = activity ?: return
        Intent(host, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            putExtra(THREAD_NUMBER, conversation.phoneNumber)
            putExtra(THREAD_TEXT, prefillText)
            putExtra(THREAD_AUTO_SEND, true)
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

    private fun restartLdkWalletBlocking(sourceFederation: FederationEntry): Boolean {
        LdkWalletManager.stopBlocking()
        val restarted = LdkWalletManager.ensureStartedBlocking(context, sourceFederation)
        if (!restarted) return false
        // Best-effort sync keeps wallet state coherent before invoice generation.
        LdkWalletManager.syncWalletsBlocking()
        return true
    }

    private fun loadLdkBalancesWithRecovery(selectedFederation: FederationEntry): org.lightningdevkit.ldknode.BalanceDetails? {
        val initial = LdkWalletManager.listBalances()
        if (initial != null) return initial

        var lastError = LdkWalletManager.getLastErrorMessage()
        if (!isLikelyFeerateError(lastError)) {
            return null
        }

        repeat(2) { attempt ->
            if (attempt == 1) {
                if (!restartLdkWalletBlocking(selectedFederation)) {
                    return@repeat
                }
            } else {
                LdkWalletManager.syncWalletsBlocking()
            }

            try {
                Thread.sleep(350L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }

            val retried = LdkWalletManager.listBalances()
            if (retried != null) {
                return retried
            }

            lastError = LdkWalletManager.getLastErrorMessage()
            if (!isLikelyFeerateError(lastError)) {
                return null
            }
        }

        return null
    }

    private fun createWithdrawReceiveInvoiceWithRecovery(
        sourceFederation: FederationEntry,
        amountSats: Long,
        preferJitChannel: Boolean,
        maxAttempts: Int,
    ): String? {
        val attempts = maxAttempts.coerceIn(1, 5)
        var lastError: String? = null

        repeat(attempts) { attempt ->
            if (attempt > 0) {
                LdkWalletManager.syncWalletsBlockingNoRecovery()
                try {
                    Thread.sleep(450L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }

            val invoice = LdkWalletManager.createBolt11Invoice(
                amountSats = amountSats,
                memo = "Federation withdraw",
                expirySeconds = 5 * 60,
                preferJitChannel = preferJitChannel,
            )
            if (!invoice.isNullOrBlank()) {
                return invoice
            }

            lastError = LdkWalletManager.getLastErrorMessage()
            val shouldRestart =
                isLikelyFeerateError(lastError) ||
                    (preferJitChannel && isLikelyLiquiditySetupError(lastError))
            if (shouldRestart && attempt < attempts - 1) {
                if (!restartLdkWalletBlocking(sourceFederation)) {
                    return null
                }
            }
        }

        if (isLikelyFeerateError(lastError)) {
            LdkWalletManager.syncWalletsBlockingNoRecovery()
        }

        return null
    }

    private fun parseFixedInvoiceSats(text: String): Long? {
        val invoice = runCatching { Bolt11Invoice.fromStr(text.trim()) }.getOrNull() ?: return null
        val msat = runCatching { invoice.amountMilliSatoshis() }.getOrNull() ?: return null
        val sats = runCatching { (msat / 1000UL).toLong() }.getOrNull() ?: return null
        return sats.takeIf { it > 0L }
    }

    private fun isLikelyLiquiditySetupError(raw: String?): Boolean {
        val text = raw?.trim().orEmpty().lowercase(Locale.ROOT)
        if (text.isBlank()) return false
        return text.contains("instant payments setup is missing") ||
            (text.contains("liquidity source") && text.contains("missing")) ||
            (text.contains("liquidity source") && text.contains("unavailable")) ||
            text.contains("incoming lightning liquidity is too low")
    }

    private fun isLikelyFeerateError(raw: String?): Boolean {
        val text = raw?.trim().orEmpty().lowercase(Locale.ROOT)
        if (text.isBlank()) return false
        return text.contains("feerate") ||
            text.contains("fee rate") ||
            text.contains("fee rates") ||
            (text.contains("fee") && text.contains("estimate")) ||
            ((text.contains("fee") || text.contains("fees")) && (text.contains("rate") || text.contains("rates"))) ||
            (text.contains("fee") && text.contains("invalid"))
    }

    private data class LiquidityPrefsSnapshot(
        val mode: String,
        val providerId: String,
        val customName: String,
        val customNetwork: String,
        val customNodeId: String,
        val customAddress: String,
        val customToken: String,
    )

    private fun snapshotLiquidityPrefs(): LiquidityPrefsSnapshot {
        val cfg = context.config
        return LiquidityPrefsSnapshot(
            mode = cfg.walletLiquidityProviderMode,
            providerId = cfg.walletLiquidityProviderId,
            customName = cfg.walletLiquidityCustomName,
            customNetwork = cfg.walletLiquidityCustomNetwork,
            customNodeId = cfg.walletLiquidityCustomNodeId,
            customAddress = cfg.walletLiquidityCustomAddress,
            customToken = cfg.walletLiquidityCustomToken,
        )
    }

    private fun restoreLiquidityPrefs(snapshot: LiquidityPrefsSnapshot) {
        val cfg = context.config
        cfg.walletLiquidityProviderMode = snapshot.mode
        cfg.walletLiquidityProviderId = snapshot.providerId
        cfg.walletLiquidityCustomName = snapshot.customName
        cfg.walletLiquidityCustomNetwork = snapshot.customNetwork
        cfg.walletLiquidityCustomNodeId = snapshot.customNodeId
        cfg.walletLiquidityCustomAddress = snapshot.customAddress
        cfg.walletLiquidityCustomToken = snapshot.customToken
    }

    private fun applyCustomLiquidityProvider(
        name: String,
        network: String,
        nodeId: String,
        address: String,
        token: String = "",
    ) {
        val cfg = context.config
        cfg.walletLiquidityCustomName = name
        cfg.walletLiquidityCustomNetwork = network
        cfg.walletLiquidityCustomNodeId = nodeId
        cfg.walletLiquidityCustomAddress = address
        cfg.walletLiquidityCustomToken = token
        cfg.walletLiquidityProviderMode = "manual"
        cfg.walletLiquidityProviderId = "custom-provider"
    }

    private fun applyManualDirectoryLiquidityProvider(providerId: String) {
        val cfg = context.config
        cfg.walletLiquidityProviderMode = "manual"
        cfg.walletLiquidityProviderId = providerId
    }

    private fun tryAutoLiquidityProviderFromGateways(
        sourceFederation: FederationEntry,
        targetFederation: FederationEntry,
        amountSats: Long,
        candidates: List<Pair<String, String>>,
    ): String? {
        if (candidates.isEmpty()) return null
        val snapshot = snapshotLiquidityPrefs()
        var successfulInvoice: String? = null
        val normalizedNetwork = sourceFederation.network?.trim().orEmpty().ifBlank { "bitcoin" }
        val providerName = "${targetFederation.name} auto"

        for ((nodeId, address) in candidates) {
            applyCustomLiquidityProvider(
                name = providerName,
                network = normalizedNetwork,
                nodeId = nodeId,
                address = address,
            )
            LdkWalletManager.stopBlocking()
            if (!LdkWalletManager.ensureStartedBlocking(context, sourceFederation)) {
                FederationDirectoryManager.recordLiquidityProviderOutcome(context, "custom-provider", success = false)
                continue
            }
            LdkWalletManager.syncWalletsBlocking()

            val invoice = LdkWalletManager.createBolt11Invoice(
                amountSats = amountSats,
                memo = "Federation withdraw",
                expirySeconds = 5 * 60,
                preferJitChannel = true,
            )
            if (!invoice.isNullOrBlank()) {
                FederationDirectoryManager.recordLiquidityProviderOutcome(context, "custom-provider", success = true)
                successfulInvoice = invoice
                break
            } else {
                FederationDirectoryManager.recordLiquidityProviderOutcome(context, "custom-provider", success = false)
            }
        }

        if (successfulInvoice.isNullOrBlank()) {
            restoreLiquidityPrefs(snapshot)
            LdkWalletManager.stopBlocking()
            LdkWalletManager.ensureStartedBlocking(context, sourceFederation)
        }
        return successfulInvoice
    }

    private fun tryAutoLiquidityProviderFromDirectory(
        sourceFederation: FederationEntry,
        amountSats: Long,
        providers: List<LiquidityProviderEntry>,
    ): String? {
        if (providers.isEmpty()) return null
        val snapshot = snapshotLiquidityPrefs()
        var successfulInvoice: String? = null

        for (provider in providers) {
            val providerId = provider.id.trim()
            if (providerId.isBlank()) continue
            applyManualDirectoryLiquidityProvider(providerId)
            LdkWalletManager.stopBlocking()
            if (!LdkWalletManager.ensureStartedBlocking(context, sourceFederation)) {
                FederationDirectoryManager.recordLiquidityProviderOutcome(context, providerId, success = false)
                continue
            }
            LdkWalletManager.syncWalletsBlocking()

            val invoice = LdkWalletManager.createBolt11Invoice(
                amountSats = amountSats,
                memo = "Federation withdraw",
                expirySeconds = 5 * 60,
                preferJitChannel = true,
            )
            if (!invoice.isNullOrBlank()) {
                FederationDirectoryManager.recordLiquidityProviderOutcome(context, providerId, success = true)
                successfulInvoice = invoice
                break
            } else {
                FederationDirectoryManager.recordLiquidityProviderOutcome(context, providerId, success = false)
            }
        }

        if (successfulInvoice.isNullOrBlank()) {
            restoreLiquidityPrefs(snapshot)
            LdkWalletManager.stopBlocking()
            LdkWalletManager.ensureStartedBlocking(context, sourceFederation)
        }
        return successfulInvoice
    }

    private fun normalizePayDestination(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val actionToken = WalletTokenParser.findActionToken(trimmed)
        if (actionToken?.action == WalletTokenParser.WalletAction.PAY) {
            return actionToken.token.trim().ifBlank { trimmed }
        }
        return WalletTokenParser.findPayToken(trimmed)?.trim().orEmpty().ifBlank { trimmed }
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
