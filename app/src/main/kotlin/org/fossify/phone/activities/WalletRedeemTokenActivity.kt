package org.fossify.phone.activities

import android.os.Bundle
import androidx.core.view.isVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityWalletRedeemTokenBinding
import org.fossify.phone.wallet.FederationDirectoryManager
import org.fossify.phone.wallet.FederationEntry
import org.fossify.phone.wallet.FedimintWalletManager
import org.fossify.messages.helpers.EXTRA_WALLET_TOKEN_TEXT
import org.fossify.messages.helpers.WalletTokenParser
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Redeems a Cyber Phone Fedimint ecash token (CPFM1) into the local Fedimint wallet.
 */
class WalletRedeemTokenActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWalletRedeemTokenBinding::inflate)

    private var tokenText: String = ""
    private var parsed: WalletTokenParser.FedimintEcashToken? = null
    private var allFederations: List<FederationEntry> = emptyList()
    private var selectedFederation: FederationEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupToolbar()
        binding.walletRedeemTokenToolbar.setNavigationOnClickListener { finish() }

        tokenText = intent?.getStringExtra(EXTRA_WALLET_TOKEN_TEXT)?.trim().orEmpty()
        parsed = WalletTokenParser.parseFedimintEcashToken(tokenText)
        if (parsed == null) {
            toast(R.string.wallet_redeem_token_invalid)
            finish()
            return
        }

        binding.walletRedeemTokenFederationValue.setOnClickListener { showSelectFederationDialog() }

        ensureBackgroundThread {
            val p = parsed ?: return@ensureBackgroundThread
            allFederations = FederationDirectoryManager.getFederations(this)
                .filter { it.kind.trim().equals("fedimint", ignoreCase = true) }
                .sortedBy { it.name.lowercase() }
            selectedFederation = allFederations.firstOrNull { it.id == p.federationId } ?: allFederations.firstOrNull()

            runOnUiThread {
                renderToken()
            }
        }

        binding.walletRedeemTokenButton.setOnClickListener { redeem() }
    }

    private fun setupToolbar() {
        binding.walletRedeemTokenToolbar.setTitle(R.string.wallet_redeem_token_title)
        binding.walletRedeemTokenToolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
    }

    private fun renderToken() {
        val p = parsed ?: return
        val fed = selectedFederation
        binding.walletRedeemTokenFederationValue.text = fed?.name
            ?: getString(R.string.wallet_select_federation)

        val satsText = NumberFormat.getIntegerInstance().format(p.amountSats)
        binding.walletRedeemTokenAmountValue.text = getString(R.string.wallet_redeem_token_amount_unverified, satsText)

        val exp = p.expiresAtEpochSec
        val expiresLine = if (exp > 0L) {
            val dt = Instant.ofEpochSecond(exp).atZone(ZoneId.systemDefault()).toLocalDateTime()
            val formatted = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(dt)
            "Expires: $formatted"
        } else {
            "Expires: unknown"
        }
        binding.walletRedeemTokenExpiresValue.text = expiresLine

        val federationHint = p.federationId.trim()
        binding.walletRedeemTokenError.isVisible = fed == null || fed.id != federationHint
        if (fed == null) {
            binding.walletRedeemTokenError.text = getString(R.string.wallet_redeem_token_select_federation)
        } else if (fed.id != federationHint) {
            binding.walletRedeemTokenError.text = getString(R.string.wallet_redeem_token_federation_hint, federationHint)
        }
    }

    private fun redeem() {
        val p = parsed ?: return
        val fed = selectedFederation
        if (fed == null) {
            toast(R.string.wallet_redeem_token_select_federation)
            return
        }

        binding.walletRedeemTokenProgress.isVisible = true
        binding.walletRedeemTokenError.isVisible = false

        ensureBackgroundThread {
            val ok = FedimintWalletManager.redeemEcashBlocking(this, fed, p.notes)
            val error = FedimintWalletManager.getLastErrorMessage().orEmpty().ifBlank {
                getString(R.string.wallet_unknown_error)
            }

            runOnUiThread {
                binding.walletRedeemTokenProgress.isVisible = false
                if (ok) {
                    toast(R.string.wallet_redeem_token_success)
                    finish()
                } else {
                    binding.walletRedeemTokenError.text = getString(R.string.wallet_redeem_token_failed, error)
                    binding.walletRedeemTokenError.isVisible = true
                }
            }
        }
    }

    private fun showSelectFederationDialog() {
        val items = allFederations
        if (items.isEmpty()) {
            toast(R.string.wallet_directory_loading_failed)
            return
        }

        val names = items.map { it.name }.toTypedArray()
        val checked = items.indexOfFirst { it.id == selectedFederation?.id }.takeIf { it >= 0 } ?: 0

        getAlertDialogBuilder()
            .setSingleChoiceItems(names, checked) { dialog, which ->
                selectedFederation = items.getOrNull(which)
                dialog.dismiss()
                renderToken()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
