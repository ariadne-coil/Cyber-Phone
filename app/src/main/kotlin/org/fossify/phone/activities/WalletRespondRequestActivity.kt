package org.fossify.phone.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.core.view.isVisible
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.helpers.EXTRA_WALLET_PAYMENT_REQUEST_TEXT
import org.fossify.messages.helpers.EXTRA_WALLET_REQUEST_ACTION
import org.fossify.messages.helpers.EXTRA_WALLET_SECURE_CHANNEL
import org.fossify.messages.helpers.EXTRA_WALLET_TOKEN_TEXT
import org.fossify.messages.helpers.WALLET_REQUEST_ACTION_APPROVE
import org.fossify.messages.helpers.WALLET_REQUEST_ACTION_DENY
import org.fossify.messages.helpers.WalletTokenParser
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityWalletRespondRequestBinding
import org.fossify.phone.wallet.ExchangeRateManager
import org.fossify.phone.wallet.FederationDirectoryManager
import org.fossify.phone.wallet.FederationEntry
import org.fossify.phone.wallet.FedimintWalletManager
import org.fossify.phone.wallet.WalletPolicy
import java.text.NumberFormat

/**
 * Handles incoming Fedimint payment requests (CPREQ1).
 *
 * Approve: creates a fixed-amount invoice and returns a CPREQINV1 response payload.
 * Deny: returns a CPREQDENY1 payload.
 */
class WalletRespondRequestActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWalletRespondRequestBinding::inflate)

    private var requestText: String = ""
    private var request: WalletTokenParser.FedimintPaymentRequest? = null
    private var allFederations: List<FederationEntry> = emptyList()
    private var selectedFederation: FederationEntry? = null
    private var secureChannel: Boolean = false
    private var requestedAction: String = ""
    private var autoActionDispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        secureChannel = intent?.getBooleanExtra(EXTRA_WALLET_SECURE_CHANNEL, false) == true
        requestedAction = intent?.getStringExtra(EXTRA_WALLET_REQUEST_ACTION)?.trim().orEmpty().lowercase()
        requestText = intent?.getStringExtra(EXTRA_WALLET_PAYMENT_REQUEST_TEXT)?.trim().orEmpty()
        request = WalletTokenParser.parseFedimintPaymentRequest(requestText)
        if (request == null) {
            toast(R.string.wallet_review_request_invalid)
            finish()
            return
        }

        setupToolbar()
        applyThemeColors()
        binding.walletRespondRequestToolbar.setNavigationOnClickListener { finish() }

        binding.walletRespondRequestChannelHint.text = getString(
            if (secureChannel) {
                R.string.wallet_review_request_channel_secure
            } else {
                R.string.wallet_review_request_channel_insecure
            }
        )

        binding.walletRespondRequestApprove.setOnClickListener { approveRequest() }
        binding.walletRespondRequestDeny.setOnClickListener { denyRequest() }

        ensureBackgroundThread {
            val req = request ?: return@ensureBackgroundThread
            allFederations = FederationDirectoryManager.getFederations(this)
                .filter { FederationDirectoryManager.isFedimintFederation(it) }
                .sortedBy { it.name.lowercase() }
            selectedFederation = allFederations.firstOrNull { it.id.equals(req.federationId, ignoreCase = true) }
            runOnUiThread {
                renderRequest()
                dispatchAutoActionIfRequested()
            }
        }
    }

    private fun setupToolbar() {
        binding.walletRespondRequestToolbar.setTitle(R.string.wallet_review_request_title)
        binding.walletRespondRequestToolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
    }

    private fun applyThemeColors() {
        val textColor = getProperTextColor()
        val secondary = textColor.adjustAlpha(0.72f)
        val primaryColor = getProperPrimaryColor()
        val onPrimary = primaryColor.getContrastColor()

        binding.root.setBackgroundColor(getProperBackgroundColor())
        binding.walletRespondRequestToolbar.setBackgroundColor(primaryColor)
        binding.walletRespondRequestToolbar.setTitleTextColor(onPrimary)
        binding.walletRespondRequestToolbar.navigationIcon?.mutate()?.setTint(onPrimary)

        val cardColor = textColor.adjustAlpha(0.06f)
        val cardStroke = textColor.adjustAlpha(0.14f)
        binding.walletRespondRequestCard.setCardBackgroundColor(cardColor)
        binding.walletRespondRequestCard.strokeColor = cardStroke
        binding.walletRespondRequestCard.strokeWidth = 1

        binding.walletRespondRequestFederationLabel.setTextColor(secondary)
        binding.walletRespondRequestFederationValue.setTextColor(textColor)
        binding.walletRespondRequestAmountLabel.setTextColor(secondary)
        binding.walletRespondRequestAmountValue.setTextColor(textColor)
        binding.walletRespondRequestIdLabel.setTextColor(secondary)
        binding.walletRespondRequestIdValue.setTextColor(textColor.adjustAlpha(0.9f))
        binding.walletRespondRequestChannelHint.setTextColor(secondary)
        binding.walletRespondRequestError.setTextColor(getColor(org.fossify.commons.R.color.md_red_400))

        binding.walletRespondRequestApprove.backgroundTintList = ColorStateList.valueOf(primaryColor)
        binding.walletRespondRequestApprove.setTextColor(onPrimary)
        binding.walletRespondRequestApprove.rippleColor = ColorStateList.valueOf(onPrimary.adjustAlpha(0.2f))

        binding.walletRespondRequestDeny.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        binding.walletRespondRequestDeny.strokeColor = ColorStateList.valueOf(primaryColor.adjustAlpha(0.55f))
        binding.walletRespondRequestDeny.setTextColor(primaryColor)
        binding.walletRespondRequestDeny.rippleColor = ColorStateList.valueOf(primaryColor.adjustAlpha(0.2f))

        binding.walletRespondRequestProgress.setIndicatorColor(primaryColor)
    }

    private fun renderRequest() {
        val req = request ?: return
        val satsText = NumberFormat.getIntegerInstance().format(req.amountSats)
        binding.walletRespondRequestAmountValue.text = getString(R.string.wallet_sats_value, satsText)
        binding.walletRespondRequestIdValue.text = req.requestId
        binding.walletRespondRequestFederationValue.text = selectedFederation?.name ?: req.federationId

        val federationAvailable = selectedFederation != null
        binding.walletRespondRequestApprove.isEnabled = federationAvailable
        if (!federationAvailable) {
            binding.walletRespondRequestError.text = getString(R.string.wallet_review_request_unknown_federation)
            binding.walletRespondRequestError.isVisible = true
        } else {
            binding.walletRespondRequestError.isVisible = false
        }
    }

    private fun setBusy(isBusy: Boolean) {
        binding.walletRespondRequestProgress.isVisible = isBusy
        binding.walletRespondRequestButtonRow.isEnabled = !isBusy
        binding.walletRespondRequestApprove.isEnabled = !isBusy && selectedFederation != null
        binding.walletRespondRequestDeny.isEnabled = !isBusy
    }

    private fun denyRequest() {
        val req = request ?: return
        val token = WalletTokenParser.buildFedimintPaymentDeniedMessage(
            requestId = req.requestId,
            federationId = req.federationId,
            amountSats = req.amountSats,
            federationName = selectedFederation?.name,
        )
        if (token.isBlank()) {
            toast(R.string.wallet_review_request_invalid)
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_WALLET_TOKEN_TEXT, token))
        finish()
    }

    private fun approveRequest() {
        val req = request ?: return
        val federation = selectedFederation ?: run {
            binding.walletRespondRequestError.text = getString(R.string.wallet_review_request_unknown_federation)
            binding.walletRespondRequestError.isVisible = true
            return
        }

        if (!federation.id.equals(req.federationId, ignoreCase = true)) {
            binding.walletRespondRequestError.text = getString(R.string.wallet_review_request_unknown_federation)
            binding.walletRespondRequestError.isVisible = true
            return
        }

        setBusy(true)
        binding.walletRespondRequestError.isVisible = false

        ensureBackgroundThread {
            val started = FedimintWalletManager.ensureStartedBlocking(this, federation)
            val rate = ExchangeRateManager.getCachedUsdRate(this)
            val expiry = WalletPolicy.invoiceExpirySeconds(req.amountSats, rate)
            val invoice = if (started) {
                FedimintWalletManager.createBolt11InvoiceBlocking(
                    context = this,
                    federation = federation,
                    amountSats = req.amountSats,
                    memo = getString(R.string.app_launcher_name),
                    expirySeconds = expiry
                )
            } else {
                null
            }

            val error = FedimintWalletManager.getLastErrorMessage().orEmpty().ifBlank {
                getString(R.string.wallet_unknown_error)
            }

            runOnUiThread {
                setBusy(false)
                if (invoice.isNullOrBlank()) {
                    binding.walletRespondRequestError.text =
                        getString(R.string.wallet_review_request_invoice_failed, error)
                    binding.walletRespondRequestError.isVisible = true
                    return@runOnUiThread
                }

                val token = WalletTokenParser.buildFedimintPaymentInvoiceResponseMessage(
                    requestId = req.requestId,
                    federationId = federation.id,
                    amountSats = req.amountSats,
                    invoice = invoice,
                    federationName = federation.name,
                )
                if (token.isBlank()) {
                    binding.walletRespondRequestError.text =
                        getString(R.string.wallet_review_request_invalid)
                    binding.walletRespondRequestError.isVisible = true
                    return@runOnUiThread
                }

                setResult(RESULT_OK, Intent().putExtra(EXTRA_WALLET_TOKEN_TEXT, token))
                finish()
            }
        }
    }

    private fun dispatchAutoActionIfRequested() {
        if (autoActionDispatched) return
        when (requestedAction) {
            WALLET_REQUEST_ACTION_DENY -> {
                autoActionDispatched = true
                denyRequest()
            }

            WALLET_REQUEST_ACTION_APPROVE -> {
                val federation = selectedFederation
                if (federation == null) {
                    toast(R.string.wallet_review_request_unknown_federation)
                    finish()
                    return
                }
                autoActionDispatched = true
                approveRequest()
            }
        }
    }
}
