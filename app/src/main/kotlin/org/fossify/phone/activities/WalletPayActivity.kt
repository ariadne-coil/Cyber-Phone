package org.fossify.phone.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.core.view.isVisible
import com.google.android.material.textfield.TextInputLayout
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityWalletPayBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.wallet.ExchangeRateManager
import org.fossify.phone.wallet.FederationDirectoryManager
import org.fossify.phone.wallet.FederationEntry
import org.fossify.phone.wallet.FedimintWalletManager
import org.fossify.phone.wallet.LdkWalletManager
import org.fossify.phone.wallet.WalletFederationTopupManager
import org.fossify.phone.wallet.WalletPolicy
import org.fossify.phone.wallet.WalletUiDialogs
import org.fossify.messages.helpers.EXTRA_WALLET_AUTO_PAY
import org.fossify.messages.helpers.EXTRA_WALLET_DESTINATION
import org.fossify.messages.helpers.EXTRA_WALLET_FEDERATION_ID_HINT
import org.fossify.messages.helpers.EXTRA_WALLET_REQUEST_AMOUNT_SATS
import org.fossify.messages.helpers.EXTRA_WALLET_REQUEST_ID
import org.fossify.messages.helpers.EXTRA_WALLET_REQUEST_THREAD_ID
import org.fossify.messages.helpers.EXTRA_WALLET_SECURE_CHANNEL
import org.fossify.messages.helpers.WalletPaymentRequestStateManager
import org.fossify.messages.helpers.WalletTokenParser
import java.text.NumberFormat
import java.util.Locale
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.PaymentStatus

/**
 * Small "Pay" surface that can be launched from the Messages module without a compile-time dependency.
 *
 * It is intentionally minimal: it reuses the same wallet backend as the Wallet tab and validates the
 * destination by trying to pay it. Full validation happens in LDK.
 */
class WalletPayActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWalletPayBinding::inflate)

    private var allFederations: List<FederationEntry> = emptyList()
    private var federationHintId: String? = null
    private var requestIdHint: String? = null
    private var requestAmountSatsHint: Long? = null
    private var requestThreadIdHint: Long = 0L
    private var autoPayRequested: Boolean = false
    private var autoPayTriggered: Boolean = false
    private var pendingValidatedRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupToolbar(binding.walletPayToolbar)
        applyThemeColors()

        binding.walletPayToolbar.setNavigationOnClickListener {
            finish()
        }

        binding.walletPayFederationCard.setOnClickListener { showSelectFederationDialog() }
        binding.walletPayFederationValue.setOnClickListener { showSelectFederationDialog() }

        val rawPrefill = intent?.getStringExtra(EXTRA_WALLET_DESTINATION)?.trim().orEmpty()
        val parsedAction = WalletTokenParser.findActionToken(rawPrefill)
        val prefill = if (parsedAction?.action == WalletTokenParser.WalletAction.PAY) {
            parsedAction.token
        } else {
            rawPrefill
        }
        federationHintId = intent?.getStringExtra(EXTRA_WALLET_FEDERATION_ID_HINT)?.trim().orEmpty().ifBlank {
            parsedAction?.federationIdHint.orEmpty()
        }
        requestIdHint = intent?.getStringExtra(EXTRA_WALLET_REQUEST_ID)?.trim().orEmpty().ifBlank {
            parsedAction?.requestId.orEmpty()
        }
        requestAmountSatsHint = intent?.getLongExtra(EXTRA_WALLET_REQUEST_AMOUNT_SATS, 0L)
            ?.takeIf { it > 0L }
            ?: parsedAction?.amountSats?.takeIf { it > 0L }
        requestThreadIdHint = intent?.getLongExtra(EXTRA_WALLET_REQUEST_THREAD_ID, 0L) ?: 0L
        autoPayRequested = intent?.getBooleanExtra(EXTRA_WALLET_AUTO_PAY, false) == true

        if (prefill.isNotBlank()) {
            binding.walletPayDestination.setText(prefill)
            parseFixedInvoiceSats(prefill)?.let { sats ->
                binding.walletPayAmount.setText(String.format(Locale.US, "%d", sats))
                // Fixed-amount invoice, don't let the user accidentally enter a conflicting amount.
                binding.walletPayAmount.isEnabled = false
            }
        }

        // Keep the amount field in sync when the user pastes a fixed-amount invoice.
        binding.walletPayDestination.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                val fixed = parseFixedInvoiceSats(text)
                if (fixed != null) {
                    val current = binding.walletPayAmount.text?.toString()?.trim()
                    val desired = String.format(Locale.US, "%d", fixed)
                    if (current != desired) {
                        binding.walletPayAmount.setText(desired)
                    }
                    binding.walletPayAmount.isEnabled = false
                } else {
                    binding.walletPayAmount.isEnabled = true
                }
            }
        })
        if (!requestIdHint.isNullOrBlank()) {
            binding.walletPayDestination.isEnabled = false
            requestAmountSatsHint?.let { amount ->
                binding.walletPayAmount.setText(String.format(Locale.US, "%d", amount))
                binding.walletPayAmount.isEnabled = false
            }
        }

        binding.walletPayButton.setOnClickListener { startPayment() }

        // Load federations and render current selection.
        ensureBackgroundThread {
            allFederations = FederationDirectoryManager.getFederations(this)
            applyFederationHintIfPossible()
            runOnUiThread {
                renderFederationLine()
                renderRateLine()
                if (autoPayRequested && !autoPayTriggered) {
                    autoPayTriggered = true
                    startPayment()
                }
            }
        }
    }

    private fun setupToolbar(toolbar: com.google.android.material.appbar.MaterialToolbar) {
        toolbar.setTitle(R.string.wallet_send)
        toolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
    }

    private fun applyThemeColors() {
        val textColor = getProperTextColor()
        val secondary = textColor.adjustAlpha(0.72f)
        val primaryColor = getProperPrimaryColor()
        val onPrimary = primaryColor.getContrastColor()

        binding.root.setBackgroundColor(getProperBackgroundColor())
        binding.walletPayToolbar.setBackgroundColor(primaryColor)
        binding.walletPayToolbar.setTitleTextColor(onPrimary)
        binding.walletPayToolbar.navigationIcon?.mutate()?.setTint(onPrimary)

        val cardColor = textColor.adjustAlpha(0.06f)
        val cardStroke = textColor.adjustAlpha(0.14f)
        binding.walletPayFederationCard.setCardBackgroundColor(cardColor)
        binding.walletPayFederationCard.strokeColor = cardStroke
        binding.walletPayFederationCard.strokeWidth = 1

        binding.walletPayFederationLabel.setTextColor(secondary)
        binding.walletPayFederationValue.setTextColor(textColor)
        binding.walletPayRate.setTextColor(secondary)

        tintInputLayout(binding.walletPayDestinationLayout, textColor, primaryColor)
        tintInputLayout(binding.walletPayAmountLayout, textColor, primaryColor)
        binding.walletPayDestination.setTextColor(textColor)
        binding.walletPayDestination.setHintTextColor(secondary)
        binding.walletPayAmount.setTextColor(textColor)
        binding.walletPayAmount.setHintTextColor(secondary)

        binding.walletPayButton.backgroundTintList = ColorStateList.valueOf(primaryColor)
        binding.walletPayButton.setTextColor(onPrimary)
        binding.walletPayButton.rippleColor = ColorStateList.valueOf(onPrimary.adjustAlpha(0.2f))

        binding.walletPayProgress.setIndicatorColor(primaryColor)
        binding.walletPayError.setTextColor(getColor(org.fossify.commons.R.color.md_red_400))
    }

    private fun tintInputLayout(layout: TextInputLayout, textColor: Int, primaryColor: Int) {
        val strokeDefault = textColor.adjustAlpha(0.28f)
        layout.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf()
                ),
                intArrayOf(
                    primaryColor,
                    strokeDefault
                )
            )
        )
        layout.defaultHintTextColor = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(
                primaryColor,
                textColor.adjustAlpha(0.62f)
            )
        )
    }

    private fun renderFederationLine() {
        val selected = FederationDirectoryManager.getSelectedFederation(this)
        if (selected == null) {
            binding.walletPayFederationValue.text = getString(R.string.wallet_select_federation)
        } else {
            binding.walletPayFederationValue.text = selected.name
        }
    }

    private fun renderRateLine() {
        val rate = ExchangeRateManager.getCachedUsdRate(this)
        binding.walletPayRate.isVisible = rate != null && rate > 0.0
        if (rate != null && rate > 0.0) {
            val formatted = NumberFormat.getCurrencyInstance(Locale.US).format(rate)
            binding.walletPayRate.text = getString(R.string.wallet_rate_short, formatted)
        }
    }

    private fun applyFederationHintIfPossible() {
        val hint = federationHintId?.trim().orEmpty()
        if (hint.isBlank()) return
        val matched = allFederations.firstOrNull { it.id.equals(hint, ignoreCase = true) } ?: return
        config.walletSelectedFederationId = matched.id
    }

    private fun showSelectFederationDialog() {
        val items = allFederations
        if (items.isEmpty()) {
            toast(R.string.wallet_directory_loading_failed)
            return
        }

        val names = items.map { it.name }.toTypedArray()
        val selectedId = config.walletSelectedFederationId
        val checked = items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0

        getAlertDialogBuilder()
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val chosen = items.getOrNull(which) ?: return@setSingleChoiceItems
                config.walletSelectedFederationId = chosen.id
                dialog.dismiss()
                // Restart node on federation switch.
                LdkWalletManager.stop {
                    runOnUiThread { renderFederationLine() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startPayment() {
        val rawDestination = binding.walletPayDestination.text?.toString()?.trim().orEmpty()
        val destination = normalizePayDestination(rawDestination)
        val amountSats = binding.walletPayAmount.text?.toString()?.trim()?.toLongOrNull()
        pendingValidatedRequestId = null

        if (destination.isBlank()) {
            toast(R.string.wallet_send_destination_required)
            return
        }

        val selected = FederationDirectoryManager.getSelectedFederation(this) ?: run {
            showSelectFederationDialog()
            return
        }
        var effectiveFederation: FederationEntry = selected
        var isFm = FederationDirectoryManager.isFedimintFederation(effectiveFederation)

        val isBolt11 = LdkWalletManager.isBolt11Invoice(destination)
        val fixedInvoiceSats = if (isBolt11) parseFixedInvoiceSats(destination) else null
        if (isFm && (!isBolt11 || fixedInvoiceSats == null)) {
            WalletFederationTopupManager.findMainnetSourceFederation(
                context = this,
                targetFederationId = selected.id,
                targetNetwork = selected.network,
            )?.let { mainnetSource ->
                effectiveFederation = mainnetSource
                isFm = false
            }
        }

        if (isFm && !isBolt11) {
            toast(R.string.wallet_fedimint_invoice_only)
            return
        }

        if (isFm && fixedInvoiceSats == null) {
            toast(R.string.wallet_fedimint_variable_invoice_requires_mainnet)
            return
        }

        if (isBolt11 && fixedInvoiceSats == null && (amountSats == null || amountSats <= 0L)) {
            toast(R.string.wallet_send_invoice_amount_required)
            return
        }

        if (!isBolt11 && (amountSats == null || amountSats <= 0L)) {
            toast(R.string.wallet_send_amount_required)
            return
        }

        val requestValidationError = validateRequestResponse(
            selectedFederation = selected,
            destination = destination,
            fixedInvoiceSats = fixedInvoiceSats,
        )
        if (!requestValidationError.isNullOrBlank()) {
            binding.walletPayError.text = getString(R.string.wallet_request_response_invalid, requestValidationError)
            binding.walletPayError.isVisible = true
            return
        }

        val policySats = fixedInvoiceSats ?: amountSats
        if (policySats != null && !WalletPolicy.isAmountWithinSingleTxLimit(policySats)) {
            val limitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(WalletPolicy.MAX_SINGLE_TX_SATS)
            toast(getString(R.string.wallet_amount_over_limit, limitText))
            return
        }
        val usdRate = ExchangeRateManager.getCachedUsdRate(this)
        val usdApprox = policySats?.let { WalletPolicy.satsToUsdApprox(it, usdRate) }
        val isHighValue = usdApprox?.let { it >= WalletPolicy.HIGH_VALUE_USD_THRESHOLD } ?: false

        // If this payment was initiated from a message, we may enforce "high value => secure channel".
        val enforceSecure = intent?.hasExtra(EXTRA_WALLET_SECURE_CHANNEL) == true
        val secureChannel = intent?.getBooleanExtra(EXTRA_WALLET_SECURE_CHANNEL, false) == true
        if (enforceSecure && !secureChannel && isHighValue) {
            toast(R.string.wallet_requires_secure_channel)
            return
        }

        val shouldConfirm = if (autoPayRequested && !requestIdHint.isNullOrBlank()) {
            false
        } else {
            isHighValue || (usdApprox == null && policySats != null)
        }
        if (shouldConfirm && policySats != null) {
            val satsText = NumberFormat.getIntegerInstance(Locale.getDefault()).format(policySats)
            val msg = if (usdApprox != null) {
                val usdText = NumberFormat.getCurrencyInstance(Locale.US).format(usdApprox)
                getString(R.string.wallet_confirm_payment_fiat, satsText, usdText)
            } else {
                getString(R.string.wallet_confirm_payment, satsText)
            }
            getAlertDialogBuilder()
                .setMessage(msg)
                .setPositiveButton(R.string.ok) { _, _ ->
                    performPayment(effectiveFederation, destination, amountSats, allowTopupPrompt = true)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        performPayment(effectiveFederation, destination, amountSats, allowTopupPrompt = true)
    }

    private fun performPayment(
        selected: FederationEntry,
        destination: String,
        amountSats: Long?,
        allowTopupPrompt: Boolean,
    ) {
        val isFm = FederationDirectoryManager.isFedimintFederation(selected)
        val tryFedimintFallback = FederationDirectoryManager.shouldTryFedimintFallback(selected)
        binding.walletPayProgress.isVisible = true
        binding.walletPayError.isVisible = false

        ensureBackgroundThread {
            try {
                val started = if (isFm) {
                    FedimintWalletManager.ensureStartedBlocking(this, selected)
                } else {
                    LdkWalletManager.ensureStartedBlocking(this, selected)
                }

                var ok = false
                var usedFedimintBackend = isFm
                var pendingMessage: String? = null
                var mainnetRecoveryError: String? = null
                if (started) {
                    val isBolt11 = LdkWalletManager.isBolt11Invoice(destination)
                    if (isFm && allowTopupPrompt && isBolt11) {
                        // Proactive top-up only when we can confidently read federation balance.
                        val proactiveTopupQuote = WalletFederationTopupManager.buildTopupQuote(
                            context = this,
                            targetFederation = selected,
                            invoice = destination,
                            assumeZeroOnUnknownBalance = false,
                        )
                        if (proactiveTopupQuote != null) {
                            runOnUiThread {
                                binding.walletPayProgress.isVisible = false
                                showMintTopupDialog(quote = proactiveTopupQuote, destination = destination)
                            }
                            return@ensureBackgroundThread
                        }
                    }

                    if (isFm) {
                        // Fedimint backend supports Lightning invoices only.
                        ok = isBolt11 && FedimintWalletManager.payBolt11InvoiceBlocking(this, selected, destination)
                    } else {
                        if (isBolt11) {
                            var allowFedimintFallback = true
                            val paymentId = LdkWalletManager.payBolt11Invoice(destination, amountSats)
                            if (paymentId != null) {
                                when (LdkWalletManager.awaitOutgoingLightningPaymentStatusBlocking(paymentId)) {
                                    PaymentStatus.SUCCEEDED -> {
                                        ok = true
                                        allowFedimintFallback = false
                                    }

                                    PaymentStatus.PENDING -> {
                                        pendingMessage = getString(R.string.wallet_send_pending)
                                        allowFedimintFallback = false
                                    }

                                    PaymentStatus.FAILED,
                                    null,
                                    -> {
                                        // Keep allowFedimintFallback = true.
                                    }
                                }
                            }
                            if (!ok && pendingMessage == null) {
                                val requiredSats = WalletFederationTopupManager.parseFixedInvoiceSats(destination)
                                    ?: amountSats?.takeIf { it > 0L }
                                val currentError = LdkWalletManager.getLastErrorMessage()
                                if (WalletFederationTopupManager.shouldAttemptMainnetLightningRecovery(requiredSats, currentError)) {
                                    val recovery = WalletFederationTopupManager.recoverMainnetLightningPaymentBlocking(
                                        context = this,
                                        sourceFederation = selected,
                                        invoice = destination,
                                        amountSats = amountSats,
                                    )
                                    when {
                                        recovery.success -> {
                                            ok = true
                                            allowFedimintFallback = false
                                        }

                                        recovery.pending -> {
                                            pendingMessage = recovery.errorMessage
                                                ?: getString(R.string.wallet_send_pending)
                                            allowFedimintFallback = false
                                        }

                                        else -> {
                                            mainnetRecoveryError = recovery.errorMessage
                                        }
                                    }
                                }
                            }
                            if (!ok && pendingMessage == null && allowFedimintFallback && tryFedimintFallback) {
                                usedFedimintBackend = true
                                val fmStarted = FedimintWalletManager.ensureStartedBlocking(this, selected)
                                ok = fmStarted && FedimintWalletManager.payBolt11InvoiceBlocking(
                                    context = this,
                                    federation = selected,
                                    invoice = destination,
                                )
                            }
                        } else {
                            ok = LdkWalletManager.sendOnchain(destination, amountSats ?: 0L) != null
                        }
                    }
                }

                val backendError = if (usedFedimintBackend) {
                    FedimintWalletManager.getLastErrorMessage()
                } else {
                    mainnetRecoveryError?.takeIf { it.isNotBlank() } ?: LdkWalletManager.getLastErrorMessage()
                }
                val error = backendError.orEmpty().ifBlank {
                    getString(R.string.wallet_unknown_error)
                }
                val insufficientBalance = (
                    usedFedimintBackend &&
                    !ok &&
                    pendingMessage == null &&
                    allowTopupPrompt &&
                    WalletFederationTopupManager.isLikelyInsufficientBalance(error)
                    )
                val topupQuote = if (insufficientBalance) {
                    WalletFederationTopupManager.buildTopupQuote(
                        context = this,
                        targetFederation = selected,
                        invoice = destination,
                    )
                } else {
                    null
                }
                val finalError = if (insufficientBalance && topupQuote == null) {
                    getString(R.string.wallet_federation_topup_unavailable)
                } else {
                    error
                }

                runOnUiThread {
                    binding.walletPayProgress.isVisible = false
                    if (ok) {
                        markPendingRequestPaidIfNeeded()
                        toast(R.string.wallet_send_success)
                        finish()
                    } else if (pendingMessage != null) {
                        markPendingRequestPaidIfNeeded()
                        toast(pendingMessage)
                        finish()
                    } else if (topupQuote != null) {
                        showMintTopupDialog(quote = topupQuote, destination = destination)
                    } else {
                        binding.walletPayError.text = getString(R.string.wallet_send_failed, finalError)
                        binding.walletPayError.isVisible = true
                    }
                }
            } catch (t: Throwable) {
                val error = t.message?.trim().orEmpty().ifBlank { getString(R.string.wallet_unknown_error) }
                runOnUiThread {
                    binding.walletPayProgress.isVisible = false
                    binding.walletPayError.text = getString(R.string.wallet_send_failed, error)
                    binding.walletPayError.isVisible = true
                }
            }
        }
    }

    private fun showMintTopupDialog(
        quote: WalletFederationTopupManager.TopupQuote,
        destination: String,
    ) {
        WalletUiDialogs.showTopupConfirmDialog(
            activity = this,
            quote = quote,
            onConfirm = {
                performTopupAndRetry(quote = quote, destination = destination)
            },
        )
    }

    private fun performTopupAndRetry(
        quote: WalletFederationTopupManager.TopupQuote,
        destination: String,
    ) {
        binding.walletPayProgress.isVisible = true
        binding.walletPayError.isVisible = false

        ensureBackgroundThread {
            try {
                val topup = WalletFederationTopupManager.topupFromMainnetBlocking(this, quote)
                val paidAfterTopup = if (topup.success) {
                    FedimintWalletManager.payBolt11InvoiceBlocking(
                        context = this,
                        federation = quote.targetFederation,
                        invoice = destination,
                    )
                } else {
                    false
                }
                val paidByDirectRetry = if (!topup.success) {
                    // If top-up fails due source-side constraints, retry direct federation payment once.
                    FedimintWalletManager.payBolt11InvoiceBlocking(
                        context = this,
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
                }.orEmpty().ifBlank { getString(R.string.wallet_unknown_error) }

                runOnUiThread {
                    binding.walletPayProgress.isVisible = false
                    if (paid) {
                        markPendingRequestPaidIfNeeded()
                        toast(R.string.wallet_send_submitted)
                        finish()
                    } else {
                        if (!topup.pending && isInstantSetupMissing(error)) {
                            showInstantSetupDialog(error)
                        } else {
                            binding.walletPayError.text = getString(R.string.wallet_send_failed, error)
                            binding.walletPayError.isVisible = true
                        }
                    }
                }
            } catch (t: Throwable) {
                val error = t.message?.trim().orEmpty().ifBlank { getString(R.string.wallet_unknown_error) }
                runOnUiThread {
                    binding.walletPayProgress.isVisible = false
                    binding.walletPayError.text = getString(R.string.wallet_send_failed, error)
                    binding.walletPayError.isVisible = true
                }
            }
        }
    }

    private fun isInstantSetupMissing(error: String): Boolean {
        val text = error.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return false
        return text.contains("top-up in") && text.contains("not set up") ||
            text.contains("instant payments setup is missing") ||
            text.contains("liquidity provider")
    }

    private fun showInstantSetupDialog(error: String) {
        getAlertDialogBuilder()
            .setTitle(R.string.wallet_instant_setup_title)
            .setMessage(
                getString(R.string.wallet_instant_setup_message) + "\n\n" +
                    getString(R.string.wallet_send_failed, error)
            )
            .setPositiveButton(R.string.wallet_instant_setup_action) { _, _ ->
                openInstantPaymentsSetup()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openInstantPaymentsSetup() {
        val intent = android.content.Intent(
            this,
            WalletLiquidityProviderSettingsActivity::class.java
        )
        startActivity(intent)
    }

    private fun parseFixedInvoiceSats(text: String): Long? {
        val invoice = runCatching { Bolt11Invoice.fromStr(text.trim()) }.getOrNull() ?: return null
        val msat = runCatching { invoice.amountMilliSatoshis() }.getOrNull() ?: return null
        val sats = runCatching { (msat / 1000UL).toLong() }.getOrNull() ?: return null
        return sats.takeIf { it > 0L }
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

    private fun validateRequestResponse(
        selectedFederation: FederationEntry,
        destination: String,
        fixedInvoiceSats: Long?,
    ): String? {
        val reqId = requestIdHint?.trim().orEmpty()
        if (reqId.isBlank()) return null

        val pendingRequest = WalletPaymentRequestStateManager.getPendingRequest(this, reqId)
        val reqAmount = requestAmountSatsHint?.takeIf { it > 0L }
            ?: pendingRequest?.amountSats?.takeIf { it > 0L }
            ?: return "Missing requested amount."
        val reqThread = requestThreadIdHint.takeIf { it != 0L }
            ?: pendingRequest?.threadId?.takeIf { it != 0L }
            ?: return "Missing request thread."
        if (!LdkWalletManager.isBolt11Invoice(destination)) {
            return "Requested payment must be a Lightning invoice."
        }
        val invoiceAmount = fixedInvoiceSats ?: return "Requested payment requires a fixed-amount invoice."
        if (invoiceAmount != reqAmount) {
            return "Invoice amount does not match the request."
        }
        val expectedFederationId = federationHintId?.trim().orEmpty().ifBlank {
            pendingRequest?.federationId.orEmpty().ifBlank { selectedFederation.id }
        }
        if (!selectedFederation.id.equals(expectedFederationId, ignoreCase = true)) {
            return "Selected federation does not match the request."
        }

        val validation = WalletPaymentRequestStateManager.validateAndReserveInvoiceResponse(
            context = this,
            requestId = reqId,
            threadId = reqThread,
            federationId = selectedFederation.id,
            amountSats = reqAmount,
            invoice = destination,
        )
        if (!validation.isValid) {
            return validation.error ?: "Request validation failed."
        }

        pendingValidatedRequestId = reqId
        return null
    }

    private fun markPendingRequestPaidIfNeeded() {
        val reqId = pendingValidatedRequestId?.trim().orEmpty()
        if (reqId.isBlank()) return
        WalletPaymentRequestStateManager.markRequestPaid(this, reqId)
    }
}
