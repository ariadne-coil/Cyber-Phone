package org.fossify.phone.activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.core.view.isVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
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
import org.fossify.phone.wallet.WalletPolicy
import org.fossify.messages.helpers.EXTRA_WALLET_DESTINATION
import org.fossify.messages.helpers.EXTRA_WALLET_SECURE_CHANNEL
import java.text.NumberFormat
import java.util.Locale
import org.lightningdevkit.ldknode.Bolt11Invoice

/**
 * Small "Pay" surface that can be launched from the Messages module without a compile-time dependency.
 *
 * It is intentionally minimal: it reuses the same wallet backend as the Wallet tab and validates the
 * destination by trying to pay it. Full validation happens in LDK.
 */
class WalletPayActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWalletPayBinding::inflate)

    private var allFederations: List<FederationEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupToolbar(binding.walletPayToolbar)

        binding.walletPayToolbar.setNavigationOnClickListener {
            finish()
        }

        binding.walletPayFederationCard.setOnClickListener { showSelectFederationDialog() }
        binding.walletPayFederationValue.setOnClickListener { showSelectFederationDialog() }

        val prefill = intent?.getStringExtra(EXTRA_WALLET_DESTINATION)?.trim().orEmpty()
        if (prefill.isNotBlank()) {
            binding.walletPayDestination.setText(prefill)
            parseFixedInvoiceSats(prefill)?.let { sats ->
                binding.walletPayAmount.setText(sats.toString())
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
                    val desired = fixed.toString()
                    if (current != desired) {
                        binding.walletPayAmount.setText(desired)
                    }
                    binding.walletPayAmount.isEnabled = false
                } else {
                    binding.walletPayAmount.isEnabled = true
                }
            }
        })

        binding.walletPayButton.setOnClickListener { startPayment() }

        // Load federations and render current selection.
        ensureBackgroundThread {
            allFederations = FederationDirectoryManager.getFederations(this)
            runOnUiThread {
                renderFederationLine()
                renderRateLine()
            }
        }
    }

    private fun setupToolbar(toolbar: com.google.android.material.appbar.MaterialToolbar) {
        toolbar.setTitle(R.string.wallet_send)
        toolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
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
        val destination = binding.walletPayDestination.text?.toString()?.trim().orEmpty()
        val amountSats = binding.walletPayAmount.text?.toString()?.trim()?.toLongOrNull()

        if (destination.isBlank()) {
            toast(R.string.wallet_send_destination_required)
            return
        }

        val selected = FederationDirectoryManager.getSelectedFederation(this)
        if (selected == null) {
            showSelectFederationDialog()
            return
        }
        val isFm = selected.kind.trim().equals("fedimint", ignoreCase = true)

        val isBolt11 = LdkWalletManager.isBolt11Invoice(destination)
        val fixedInvoiceSats = if (isBolt11) parseFixedInvoiceSats(destination) else null

        if (isFm && !isBolt11) {
            toast(R.string.wallet_fedimint_invoice_only)
            return
        }

        if (isFm && fixedInvoiceSats == null) {
            toast(R.string.wallet_fedimint_variable_invoice_unsupported)
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

        val shouldConfirm = isHighValue || (usdApprox == null && policySats != null)
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
                    performPayment(selected, destination, amountSats)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        performPayment(selected, destination, amountSats)
    }

    private fun performPayment(
        selected: FederationEntry,
        destination: String,
        amountSats: Long?,
    ) {
        val isFm = selected.kind.trim().equals("fedimint", ignoreCase = true)
        binding.walletPayProgress.isVisible = true
        binding.walletPayError.isVisible = false

        ensureBackgroundThread {
            val started = if (isFm) {
                FedimintWalletManager.ensureStartedBlocking(this, selected)
            } else {
                LdkWalletManager.ensureStartedBlocking(this, selected)
            }

            var ok = false
            if (started) {
                val isBolt11 = LdkWalletManager.isBolt11Invoice(destination)
                if (isFm) {
                    // Fedimint backend supports Lightning invoices only.
                    ok = isBolt11 && FedimintWalletManager.payBolt11InvoiceBlocking(this, selected, destination)
                } else {
                    if (isBolt11) {
                        ok = LdkWalletManager.payBolt11Invoice(destination, amountSats) != null
                    } else {
                        ok = LdkWalletManager.sendOnchain(destination, amountSats ?: 0L) != null
                    }
                }
            }

            val backendError = if (isFm) FedimintWalletManager.getLastErrorMessage() else LdkWalletManager.getLastErrorMessage()
            val error = backendError.orEmpty().ifBlank {
                getString(R.string.wallet_unknown_error)
            }

            runOnUiThread {
                binding.walletPayProgress.isVisible = false
                if (ok) {
                    toast(R.string.wallet_send_submitted)
                    finish()
                } else {
                    binding.walletPayError.text = getString(R.string.wallet_send_failed, error)
                    binding.walletPayError.isVisible = true
                }
            }
        }
    }

    private fun parseFixedInvoiceSats(text: String): Long? {
        val invoice = runCatching { Bolt11Invoice.fromStr(text.trim()) }.getOrNull() ?: return null
        val msat = runCatching { invoice.amountMilliSatoshis() }.getOrNull() ?: return null
        val sats = runCatching { (msat / 1000UL).toLong() }.getOrNull() ?: return null
        return sats.takeIf { it > 0L }
    }
}
