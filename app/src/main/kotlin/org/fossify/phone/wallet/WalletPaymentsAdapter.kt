package org.fossify.phone.wallet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.fossify.phone.R
import org.fossify.phone.databinding.ItemWalletPaymentBinding
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

class WalletPaymentsAdapter(
    private val onItemClicked: (PaymentDetails) -> Unit,
) : RecyclerView.Adapter<WalletPaymentsAdapter.PaymentViewHolder>() {
    private companion object {
        const val PAYLOAD_COLORS = "payload_colors"
    }

    private var items: List<PaymentDetails> = emptyList()
    private var textColor: Int? = null
    private var secondaryTextColor: Int? = null

    fun submitList(items: List<PaymentDetails>) {
        val oldItems = this.items
        this.items = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = items.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return paymentStableId(oldItems[oldItemPosition]) == paymentStableId(items[newItemPosition])
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition] == items[newItemPosition]
            }
        })
        diff.dispatchUpdatesTo(this)
    }

    fun updateTextColors(textColor: Int, secondaryTextColor: Int) {
        val changed = this.textColor != textColor || this.secondaryTextColor != secondaryTextColor
        this.textColor = textColor
        this.secondaryTextColor = secondaryTextColor
        if (changed && items.isNotEmpty()) {
            notifyItemRangeChanged(0, items.size, PAYLOAD_COLORS)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaymentViewHolder {
        val binding = ItemWalletPaymentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PaymentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PaymentViewHolder, position: Int) {
        bindItem(holder, position)
    }

    override fun onBindViewHolder(holder: PaymentViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_COLORS)) {
            textColor?.let { holder.binding.walletPaymentTitle.setTextColor(it) }
            secondaryTextColor?.let { holder.binding.walletPaymentSubtitle.setTextColor(it) }
            return
        }
        bindItem(holder, position)
    }

    private fun bindItem(holder: PaymentViewHolder, position: Int) {
        val item = items[position]

        val amountSats = item.amountMsat?.div(1000UL)
        val amountText = amountSats?.let { formatSats(it) } ?: "—"

        val directionSymbol = when (item.direction) {
            PaymentDirection.INBOUND -> "\u2193"
            PaymentDirection.OUTBOUND -> "\u2191"
        }

        val statusSymbol = when (item.status) {
            PaymentStatus.PENDING -> "\u2026"
            PaymentStatus.SUCCEEDED -> "\u2713"
            PaymentStatus.FAILED -> "\u2717"
        }

        val tsRaw = item.latestUpdateTimestamp.toLong()
        val tsMs = if (tsRaw in 1..9_999_999_999L) tsRaw * 1000L else tsRaw
        val timeText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tsMs))

        holder.binding.apply {
            walletPaymentTitle.text = root.context.getString(
                R.string.wallet_sats_value,
                "$directionSymbol $amountText"
            )
            walletPaymentSubtitle.text = String.format(Locale.getDefault(), "%s %s", statusSymbol, timeText)

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

    private fun paymentStableId(details: PaymentDetails): String {
        val kindId = when (val kind = details.kind) {
            is PaymentKind.Bolt11 -> "b11:${kind.hash.lowercase(Locale.US)}"
            is PaymentKind.Bolt11Jit -> "jit:${kind.hash.lowercase(Locale.US)}"
            else -> "${details.direction}:${details.latestUpdateTimestamp}:${details.amountMsat}"
        }
        return "$kindId:${details.direction}:${details.status}"
    }

    class PaymentViewHolder(val binding: ItemWalletPaymentBinding) : RecyclerView.ViewHolder(binding.root)
}
