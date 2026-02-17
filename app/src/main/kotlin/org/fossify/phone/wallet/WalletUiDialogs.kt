package org.fossify.phone.wallet

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.shareTextIntent
import org.fossify.commons.extensions.toast
import org.fossify.messages.helpers.WalletTokenParser
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.DialogWalletInvoicePreviewBinding
import org.fossify.phone.databinding.DialogWalletTopupConfirmBinding
import java.text.NumberFormat
import java.util.Locale

object WalletUiDialogs {
    fun showInvoicePreviewDialog(
        activity: SimpleActivity,
        federation: FederationEntry,
        invoiceMessage: String,
        onSendInMessages: (String) -> Unit,
    ) {
        val binding = DialogWalletInvoicePreviewBinding.inflate(activity.layoutInflater)
        val invoice = WalletTokenParser.findPayToken(invoiceMessage).orEmpty().ifBlank { invoiceMessage.trim() }
        val fixedAmountSats = WalletFederationTopupManager.parseFixedInvoiceSats(invoice)
        val amountText = fixedAmountSats?.let { sats ->
            activity.getString(R.string.wallet_sats_value, NumberFormat.getIntegerInstance().format(sats))
        } ?: activity.getString(R.string.wallet_invoice_amount_variable)

        binding.walletInvoicePreviewFederation.text = federation.name
        binding.walletInvoicePreviewAmount.text = amountText
        binding.walletInvoicePreviewShort.text = abbreviate(invoice)
        binding.walletInvoicePreviewFull.text = invoice
        binding.walletInvoicePreviewToggle.text = activity.getString(R.string.wallet_invoice_show_full)
        applyInvoiceDialogTheme(activity, binding)

        var expanded = false
        binding.walletInvoicePreviewToggle.setOnClickListener {
            expanded = !expanded
            binding.walletInvoicePreviewFull.isVisible = expanded
            binding.walletInvoicePreviewShort.isVisible = !expanded
            binding.walletInvoicePreviewToggle.text = activity.getString(
                if (expanded) R.string.wallet_invoice_hide_full else R.string.wallet_invoice_show_full
            )
        }

        var dialog: AlertDialog? = null
        binding.walletInvoicePreviewCopy.setOnClickListener {
            activity.copyToClipboard(invoiceMessage)
            activity.toast(org.fossify.commons.R.string.value_copied_to_clipboard)
        }
        binding.walletInvoicePreviewSend.setOnClickListener {
            onSendInMessages(invoiceMessage)
            dialog?.dismiss()
        }
        binding.walletInvoicePreviewShare.setOnClickListener {
            activity.shareTextIntent(invoiceMessage)
        }
        binding.walletInvoicePreviewClose.setOnClickListener {
            dialog?.dismiss()
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.wallet_invoice) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    fun showTopupConfirmDialog(
        activity: SimpleActivity,
        quote: WalletFederationTopupManager.TopupQuote,
        onConfirm: () -> Unit,
    ) {
        val amountFormat = NumberFormat.getIntegerInstance(Locale.getDefault())
        val binding = DialogWalletTopupConfirmBinding.inflate(activity.layoutInflater)
        binding.walletTopupTarget.text = quote.targetFederation.name
        binding.walletTopupInvoiceAmount.text =
            activity.getString(R.string.wallet_sats_value, amountFormat.format(quote.invoiceAmountSats))
        binding.walletTopupCurrentBalance.text =
            activity.getString(R.string.wallet_sats_value, amountFormat.format(quote.currentFederationBalanceSats))
        binding.walletTopupMintAmount.text =
            activity.getString(R.string.wallet_sats_value, amountFormat.format(quote.mintAmountSats))
        binding.walletTopupSource.text = quote.sourceFederation.name
        binding.walletTopupFeeEstimate.text =
            activity.getString(R.string.wallet_sats_value, amountFormat.format(quote.estimatedFeeSats))
        applyTopupDialogTheme(activity, binding)

        var dialog: AlertDialog? = null
        binding.walletTopupCancel.setOnClickListener {
            dialog?.dismiss()
        }
        binding.walletTopupConfirm.setOnClickListener {
            dialog?.dismiss()
            onConfirm()
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.wallet_federation_topup_confirm_title) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun abbreviate(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length <= 64) return trimmed
        return buildString {
            append(trimmed.take(28))
            append("…")
            append(trimmed.takeLast(24))
        }
    }

    private fun applyInvoiceDialogTheme(activity: SimpleActivity, binding: DialogWalletInvoicePreviewBinding) {
        val textColor = activity.getProperTextColor()
        val secondary = textColor.adjustAlpha(0.72f)
        val primaryColor = activity.getProperPrimaryColor()
        val onPrimary = primaryColor.getContrastColor()

        binding.root.setBackgroundColor(activity.getProperBackgroundColor())
        binding.walletInvoicePreviewCard.setCardBackgroundColor(textColor.adjustAlpha(0.06f))
        binding.walletInvoicePreviewCard.strokeColor = textColor.adjustAlpha(0.14f)
        binding.walletInvoicePreviewCard.strokeWidth = 1

        applyTextColorRecursively(binding.root, textColor)
        binding.walletInvoicePreviewShort.setTextColor(secondary)
        binding.walletInvoicePreviewFull.setTextColor(secondary)
        binding.walletInvoicePreviewToggle.setTextColor(primaryColor)

        stylePrimaryButton(binding.walletInvoicePreviewSend, primaryColor, onPrimary)
        styleOutlinedButton(binding.walletInvoicePreviewCopy, primaryColor)
        styleOutlinedButton(binding.walletInvoicePreviewShare, primaryColor)
        styleOutlinedButton(binding.walletInvoicePreviewClose, primaryColor)
    }

    private fun applyTopupDialogTheme(activity: SimpleActivity, binding: DialogWalletTopupConfirmBinding) {
        val textColor = activity.getProperTextColor()
        val primaryColor = activity.getProperPrimaryColor()
        val onPrimary = primaryColor.getContrastColor()

        binding.root.setBackgroundColor(activity.getProperBackgroundColor())
        binding.walletTopupCard.setCardBackgroundColor(textColor.adjustAlpha(0.06f))
        binding.walletTopupCard.strokeColor = textColor.adjustAlpha(0.14f)
        binding.walletTopupCard.strokeWidth = 1

        applyTextColorRecursively(binding.root, textColor)
        styleOutlinedButton(binding.walletTopupCancel, primaryColor)
        stylePrimaryButton(binding.walletTopupConfirm, primaryColor, onPrimary)
    }

    private fun applyTextColorRecursively(view: View, textColor: Int) {
        when (view) {
            is TextView -> view.setTextColor(textColor)
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyTextColorRecursively(view.getChildAt(i), textColor)
                }
            }
        }
    }

    private fun stylePrimaryButton(button: MaterialButton, primaryColor: Int, onPrimary: Int) {
        button.backgroundTintList = ColorStateList.valueOf(primaryColor)
        button.setTextColor(onPrimary)
        button.rippleColor = ColorStateList.valueOf(onPrimary.adjustAlpha(0.2f))
    }

    private fun styleOutlinedButton(button: MaterialButton, primaryColor: Int) {
        button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        button.strokeColor = ColorStateList.valueOf(primaryColor.adjustAlpha(0.55f))
        button.setTextColor(primaryColor)
        button.rippleColor = ColorStateList.valueOf(primaryColor.adjustAlpha(0.2f))
    }
}
