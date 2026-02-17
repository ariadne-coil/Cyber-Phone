package org.fossify.phone.activities

import android.text.InputType
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import com.google.android.material.textfield.TextInputLayout
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityWalletLiquidityProviderSettingsBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.wallet.FederationDirectoryManager
import org.fossify.phone.wallet.FederationEntry
import org.fossify.phone.wallet.LiquidityProviderEntry
import java.util.Locale

class WalletLiquidityProviderSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWalletLiquidityProviderSettingsBinding::inflate)

    private var providers: List<LiquidityProviderEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupTopAppBar(binding.walletLiquiditySettingsAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.walletLiquiditySettingsHolder)
        setupActions()
        loadProviders()
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    private fun setupActions() = binding.apply {
        walletLiquidityModeHolder.setOnClickListener {
            showModeDialog()
        }
        walletLiquidityProviderHolder.setOnClickListener {
            showProviderDialog()
        }
        walletLiquidityCustomHolder.setOnClickListener {
            showCustomProviderDialog()
        }
    }

    private fun loadProviders() {
        ensureBackgroundThread {
            providers = FederationDirectoryManager.getLiquidityProviders(this, network = null)
            runOnUiThread {
                renderState()
            }
        }
    }

    private fun showModeDialog() {
        val checked = if (currentMode() == MODE_MANUAL) 1 else 0
        val options = arrayOf(
            getString(R.string.wallet_liquidity_mode_auto),
            getString(R.string.wallet_liquidity_mode_manual),
        )
        getAlertDialogBuilder()
            .setSingleChoiceItems(options, checked) { dialog, which ->
                config.walletLiquidityProviderMode = if (which == 1) MODE_MANUAL else MODE_AUTO
                if (config.walletLiquidityProviderMode == MODE_MANUAL &&
                    config.walletLiquidityProviderId.isBlank() &&
                    providers.isNotEmpty()
                ) {
                    config.walletLiquidityProviderId = providers.first().id
                }
                dialog.dismiss()
                renderState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProviderDialog() {
        if (providers.isEmpty()) {
            showCustomProviderDialog()
            return
        }
        val names = providers.map { formatProviderLabel(it) }.toTypedArray()
        val currentId = config.walletLiquidityProviderId.trim()
        val checked = providers.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        getAlertDialogBuilder()
            .setTitle(R.string.wallet_liquidity_provider)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val selected = providers.getOrNull(which) ?: return@setSingleChoiceItems
                config.walletLiquidityProviderMode = MODE_MANUAL
                config.walletLiquidityProviderId = selected.id
                dialog.dismiss()
                renderState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustomProviderDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            setPadding(pad, pad / 2, pad, 0)
        }

        fun newField(hint: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
            val input = EditText(this).apply {
                setHint(hint)
                setText(value)
                this.inputType = inputType
                setSingleLine()
            }
            val layout = TextInputLayout(this).apply {
                addView(input)
                val margin = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = margin
                }
            }
            content.addView(layout)
            return input
        }

        val nameField = newField(
            getString(R.string.wallet_liquidity_custom_name_hint),
            config.walletLiquidityCustomName
        )
        val networkField = newField(
            getString(R.string.wallet_liquidity_custom_network_hint),
            config.walletLiquidityCustomNetwork
        )
        val nodeIdField = newField(
            getString(R.string.wallet_liquidity_custom_node_id_hint),
            config.walletLiquidityCustomNodeId
        )
        val addressField = newField(
            getString(R.string.wallet_liquidity_custom_address_hint),
            config.walletLiquidityCustomAddress
        )
        val tokenField = newField(
            getString(R.string.wallet_liquidity_custom_token_hint),
            config.walletLiquidityCustomToken
        )

        getAlertDialogBuilder()
            .setTitle(R.string.wallet_liquidity_custom_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty()
                val network = networkField.text?.toString()?.trim().orEmpty()
                val nodeId = nodeIdField.text?.toString()?.trim().orEmpty()
                val address = addressField.text?.toString()?.trim().orEmpty()
                val token = tokenField.text?.toString()?.trim().orEmpty()

                if (nodeId.isBlank() || address.isBlank()) {
                    toast(R.string.wallet_liquidity_custom_required)
                    return@setPositiveButton
                }

                config.walletLiquidityCustomName = name
                config.walletLiquidityCustomNetwork = network
                config.walletLiquidityCustomNodeId = nodeId
                config.walletLiquidityCustomAddress = address
                config.walletLiquidityCustomToken = token
                config.walletLiquidityProviderMode = MODE_MANUAL
                config.walletLiquidityProviderId = CUSTOM_PROVIDER_ID
                loadProviders()
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                config.walletLiquidityCustomName = ""
                config.walletLiquidityCustomNetwork = ""
                config.walletLiquidityCustomNodeId = ""
                config.walletLiquidityCustomAddress = ""
                config.walletLiquidityCustomToken = ""
                if (config.walletLiquidityProviderId == CUSTOM_PROVIDER_ID) {
                    config.walletLiquidityProviderId = ""
                    config.walletLiquidityProviderMode = MODE_AUTO
                }
                loadProviders()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderState() = binding.apply {
        val mode = currentMode()
        walletLiquidityModeValue.text = if (mode == MODE_MANUAL) {
            getString(R.string.wallet_liquidity_mode_manual)
        } else {
            getString(R.string.wallet_liquidity_mode_auto)
        }

        val selectedFederation = FederationDirectoryManager.getSelectedFederation(this@WalletLiquidityProviderSettingsActivity)
        val autoProvider = resolveAutoProviderPreview(selectedFederation)
        val manualProvider = providers.firstOrNull { it.id == config.walletLiquidityProviderId.trim() }

        val providerText = when (mode) {
            MODE_MANUAL -> {
                if (manualProvider != null) {
                    getString(R.string.wallet_liquidity_provider_manual_summary, formatProviderLabel(manualProvider))
                } else {
                    getString(R.string.wallet_liquidity_provider_manual_unset)
                }
            }

            else -> {
                if (autoProvider != null) {
                    getString(R.string.wallet_liquidity_provider_auto_summary, formatProviderLabel(autoProvider))
                } else {
                    getString(R.string.wallet_liquidity_provider_not_set)
                }
            }
        }
        walletLiquidityProviderValue.text = providerText
        walletLiquidityProviderHolder.isEnabled = providers.isNotEmpty()
        walletLiquidityProviderHint.text = if (providers.isEmpty()) {
            getString(R.string.wallet_liquidity_provider_none_available)
        } else {
            getString(R.string.wallet_liquidity_provider_hint)
        }
        walletLiquidityCustomValue.text = customProviderSummary()
    }

    private fun resolveAutoProviderPreview(selectedFederation: FederationEntry?): LiquidityProviderEntry? {
        return if (selectedFederation != null) {
            FederationDirectoryManager.resolveLiquidityProvider(this, selectedFederation)
        } else {
            providers.firstOrNull()
        }
    }

    private fun formatProviderLabel(provider: LiquidityProviderEntry): String {
        val network = provider.network?.trim().orEmpty()
        if (network.isBlank()) return provider.name
        return getString(
            R.string.wallet_liquidity_provider_with_network,
            provider.name,
            network.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
            }
        )
    }

    private fun currentMode(): String {
        return when (config.walletLiquidityProviderMode.trim().lowercase()) {
            MODE_MANUAL -> MODE_MANUAL
            else -> MODE_AUTO
        }
    }

    private fun customProviderSummary(): String {
        val node = config.walletLiquidityCustomNodeId.trim()
        val address = config.walletLiquidityCustomAddress.trim()
        if (node.isBlank() || address.isBlank()) {
            return getString(R.string.wallet_liquidity_custom_not_set)
        }
        val name = config.walletLiquidityCustomName.trim().ifBlank { getString(R.string.wallet_liquidity_custom_default_name) }
        return "$name • $address"
    }

    private companion object {
        const val MODE_AUTO = "auto"
        const val MODE_MANUAL = "manual"
        const val CUSTOM_PROVIDER_ID = "custom-provider"
    }
}
