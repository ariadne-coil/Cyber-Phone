package org.fossify.phone.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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
import org.fossify.phone.databinding.ActivityWalletSendTokenBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.wallet.ExchangeRateManager
import org.fossify.phone.wallet.FederationDirectoryManager
import org.fossify.phone.wallet.FederationEntry
import org.fossify.phone.wallet.FedimintWalletManager
import org.fossify.phone.wallet.WalletEcashCancelWorker
import org.fossify.phone.wallet.WalletPolicy
import org.fossify.messages.helpers.EXTRA_WALLET_SECURE_CHANNEL
import org.fossify.messages.helpers.EXTRA_WALLET_TOKEN_TEXT
import org.fossify.messages.helpers.WalletTokenParser
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Generates a Fedimint out-of-band ecash token and returns it to the caller as message text.
 *
 * This is launched from the Messages module (no compile-time dependency) via setClassName().
 */
class WalletSendTokenActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWalletSendTokenBinding::inflate)

    private var allFederations: List<FederationEntry> = emptyList()
    private var secureChannel: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        secureChannel = intent?.getBooleanExtra(EXTRA_WALLET_SECURE_CHANNEL, false) == true

        setupToolbar()
        applyThemeColors()
        binding.walletSendTokenToolbar.setNavigationOnClickListener { finish() }

        binding.walletSendTokenFederationCard.setOnClickListener { showSelectFederationDialog() }
        binding.walletSendTokenFederationValue.setOnClickListener { showSelectFederationDialog() }

        binding.walletSendTokenChannelHint.text = getString(
            if (secureChannel) R.string.wallet_send_token_channel_secure else R.string.wallet_send_token_channel_insecure
        )

        binding.walletSendTokenButton.setOnClickListener { startSendToken() }

        ensureBackgroundThread {
            // Only show Fedimint-backed entries for OOB ecash tokens.
            allFederations = FederationDirectoryManager.getFederations(this)
                .filter { FederationDirectoryManager.isFedimintFederation(it) }
            runOnUiThread {
                ensureFedimintSelection()
                renderFederationLine()
                renderRateLine()
            }
        }
    }

    private fun setupToolbar() {
        binding.walletSendTokenToolbar.setTitle(R.string.wallet_send_token_title)
        binding.walletSendTokenToolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
    }

    private fun applyThemeColors() {
        val textColor = getProperTextColor()
        val secondary = textColor.adjustAlpha(0.72f)
        val primaryColor = getProperPrimaryColor()
        val onPrimary = primaryColor.getContrastColor()

        binding.root.setBackgroundColor(getProperBackgroundColor())
        binding.walletSendTokenToolbar.setBackgroundColor(primaryColor)
        binding.walletSendTokenToolbar.setTitleTextColor(onPrimary)
        binding.walletSendTokenToolbar.navigationIcon?.mutate()?.setTint(onPrimary)

        val cardColor = textColor.adjustAlpha(0.06f)
        val cardStroke = textColor.adjustAlpha(0.14f)
        binding.walletSendTokenFederationCard.setCardBackgroundColor(cardColor)
        binding.walletSendTokenFederationCard.strokeColor = cardStroke
        binding.walletSendTokenFederationCard.strokeWidth = 1

        binding.walletSendTokenFederationLabel.setTextColor(secondary)
        binding.walletSendTokenFederationValue.setTextColor(textColor)
        binding.walletSendTokenRate.setTextColor(secondary)
        binding.walletSendTokenChannelHint.setTextColor(secondary)

        tintInputLayout(binding.walletSendTokenAmountLayout, textColor, primaryColor)
        binding.walletSendTokenAmount.setTextColor(textColor)
        binding.walletSendTokenAmount.setHintTextColor(secondary)

        binding.walletSendTokenButton.backgroundTintList = ColorStateList.valueOf(primaryColor)
        binding.walletSendTokenButton.setTextColor(onPrimary)
        binding.walletSendTokenButton.rippleColor = ColorStateList.valueOf(onPrimary.adjustAlpha(0.2f))

        binding.walletSendTokenProgress.setIndicatorColor(primaryColor)
        binding.walletSendTokenError.setTextColor(getColor(org.fossify.commons.R.color.md_red_400))
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

    private fun ensureFedimintSelection() {
        val selectedId = config.walletSelectedFederationId
        val ok = allFederations.any { it.id == selectedId }
        if (!ok && allFederations.isNotEmpty()) {
            config.walletSelectedFederationId = allFederations.first().id
        }
    }

    private fun renderFederationLine() {
        val selectedId = config.walletSelectedFederationId
        val selected = allFederations.firstOrNull { it.id == selectedId }
        binding.walletSendTokenFederationValue.text = selected?.name
            ?: getString(R.string.wallet_select_federation)
    }

    private fun renderRateLine() {
        val rate = ExchangeRateManager.getCachedUsdRate(this)
        binding.walletSendTokenRate.isVisible = rate != null && rate > 0.0
        if (rate != null && rate > 0.0) {
            val formatted = NumberFormat.getCurrencyInstance(Locale.US).format(rate)
            binding.walletSendTokenRate.text = getString(R.string.wallet_rate_short, formatted)
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
                renderFederationLine()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startSendToken() {
        val amountSats = binding.walletSendTokenAmount.text?.toString()?.trim()?.toLongOrNull()
        if (amountSats == null || amountSats <= 0L) {
            toast(R.string.wallet_send_token_amount_required)
            return
        }
        if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
            val limitText = NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(WalletPolicy.MAX_SINGLE_TX_SATS)
            toast(getString(R.string.wallet_amount_over_limit, limitText))
            return
        }

        val selectedId = config.walletSelectedFederationId
        val selected = allFederations.firstOrNull { it.id == selectedId } ?: allFederations.firstOrNull()
        if (selected == null) {
            showSelectFederationDialog()
            return
        }

        val usdRate = ExchangeRateManager.getCachedUsdRate(this)
        val usdApprox = WalletPolicy.satsToUsdApprox(amountSats, usdRate)
        val isHighValue = usdApprox?.let { it >= WalletPolicy.HIGH_VALUE_USD_THRESHOLD }
        val isUnknownValue = usdApprox == null

        // Insecure channels (plain SMS) are allowed only for low-value transfers we can confidently price.
        if (!secureChannel && (isHighValue == true || isUnknownValue)) {
            toast(R.string.wallet_requires_secure_channel)
            return
        }

        // Low value => no confirmation. High/unknown => confirmation.
        val shouldConfirm = isHighValue == true || isUnknownValue
        if (shouldConfirm) {
            val satsText = NumberFormat.getIntegerInstance(Locale.getDefault()).format(amountSats)
            val msg = if (usdApprox != null) {
                val usdText = NumberFormat.getCurrencyInstance(Locale.US).format(usdApprox)
                getString(R.string.wallet_confirm_payment_fiat, satsText, usdText)
            } else {
                getString(R.string.wallet_confirm_payment, satsText)
            }
            getAlertDialogBuilder()
                .setMessage(msg)
                .setPositiveButton(R.string.ok) { _, _ -> performSendToken(selected, amountSats) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        performSendToken(selected, amountSats)
    }

    private fun performSendToken(selected: FederationEntry, amountSats: Long) {
        binding.walletSendTokenProgress.isVisible = true
        binding.walletSendTokenError.isVisible = false

        // Plain SMS tokens should expire quickly.
        val tryCancelAfterSecs = if (secureChannel) 24 * 60 * 60 else 10 * 60
        val expiresAtEpochSec = (System.currentTimeMillis() / 1000L) + tryCancelAfterSecs

        ensureBackgroundThread {
            val spend = FedimintWalletManager.spendEcashBlocking(
                context = this,
                federation = selected,
                amountSats = amountSats,
                tryCancelAfterSecs = tryCancelAfterSecs,
            )

            val error = FedimintWalletManager.getLastErrorMessage().orEmpty().ifBlank {
                getString(R.string.wallet_unknown_error)
            }

            runOnUiThread {
                binding.walletSendTokenProgress.isVisible = false
                if (spend == null || spend.notes.isBlank()) {
                    binding.walletSendTokenError.text = getString(R.string.wallet_send_token_failed, error)
                    binding.walletSendTokenError.isVisible = true
                    return@runOnUiThread
                }

                val tokenText = WalletTokenParser.buildFedimintEcashMessage(
                    federationId = selected.id,
                    amountSats = amountSats,
                    expiresAtEpochSec = expiresAtEpochSec,
                    notes = spend.notes,
                )
                scheduleSpendTryCancel(
                    federationId = selected.id,
                    operationId = spend.operationId,
                    tryCancelAfterSecs = tryCancelAfterSecs,
                )

                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_WALLET_TOKEN_TEXT, tokenText)
                )
                finish()
            }
        }
    }

    private fun scheduleSpendTryCancel(
        federationId: String,
        operationId: String?,
        tryCancelAfterSecs: Int,
    ) {
        val op = operationId?.trim().orEmpty()
        if (op.isBlank()) return

        val delaySecs = tryCancelAfterSecs.coerceAtLeast(0).toLong()
        val input = workDataOf(
            WalletEcashCancelWorker.KEY_FEDERATION_ID to federationId,
            WalletEcashCancelWorker.KEY_OPERATION_ID to op,
        )
        val req = OneTimeWorkRequestBuilder<WalletEcashCancelWorker>()
            .setInputData(input)
            .setInitialDelay(delaySecs, TimeUnit.SECONDS)
            .addTag(WalletEcashCancelWorker.TAG)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                WalletEcashCancelWorker.uniqueName(federationId, op),
                ExistingWorkPolicy.REPLACE,
                req
            )
    }
}
