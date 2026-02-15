package org.fossify.phone.wallet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.phone.databinding.ItemWalletPaymentBinding
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class WalletPaymentsAdapter(
    private val onItemClicked: (PaymentDetails) -> Unit,
) : RecyclerView.Adapter<WalletPaymentsAdapter.PaymentViewHolder>() {

    private var items: List<PaymentDetails> = emptyList()
    private var textColor: Int? = null
    private var secondaryTextColor: Int? = null

    fun submitList(items: List<PaymentDetails>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun updateTextColors(textColor: Int, secondaryTextColor: Int) {
        this.textColor = textColor
        this.secondaryTextColor = secondaryTextColor
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaymentViewHolder {
        val binding = ItemWalletPaymentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PaymentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PaymentViewHolder, position: Int) {
        val item = items[position]

        val amountSats = item.amountMsat?.div(1000UL)
        val amountText = amountSats?.let { formatSats(it) } ?: "—"

        val direction = when (item.direction) {
            PaymentDirection.INBOUND -> "Received"
            PaymentDirection.OUTBOUND -> "Sent"
        }

        val status = when (item.status) {
            PaymentStatus.PENDING -> "Pending"
            PaymentStatus.SUCCEEDED -> "Complete"
            PaymentStatus.FAILED -> "Failed"
        }

        val tsRaw = item.latestUpdateTimestamp.toLong()
        val tsMs = if (tsRaw in 1..9_999_999_999L) tsRaw * 1000L else tsRaw
        val timeText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tsMs))

        holder.binding.apply {
            walletPaymentTitle.text = "$direction $amountText sats"
            walletPaymentSubtitle.text = "$status · $timeText"

            textColor?.let { walletPaymentTitle.setTextColor(it) }
            secondaryTextColor?.let { walletPaymentSubtitle.setTextColor(it) }

            walletPaymentHolder.setOnClickListener { onItemClicked(item) }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatSats(value: ULong): String {
        val asLong = try {
            value.toLong()
        } catch (_: Exception) {
            return value.toString()
        }
        return NumberFormat.getIntegerInstance().format(asLong)
    }

    class PaymentViewHolder(val binding: ItemWalletPaymentBinding) : RecyclerView.ViewHolder(binding.root)
}

