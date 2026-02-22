package org.fossify.phone.wallet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.fossify.phone.databinding.ItemFederationBinding

class FederationAdapter(
    private val onItemClicked: (FederationEntry) -> Unit,
) : RecyclerView.Adapter<FederationAdapter.FederationViewHolder>() {
    private companion object {
        const val PAYLOAD_COLORS = "payload_colors"
    }

    private var items: List<FederationEntry> = emptyList()
    private var selectedId: String = ""
    private var textColor: Int? = null
    private var secondaryTextColor: Int? = null

    fun submitList(items: List<FederationEntry>, selectedId: String) {
        val oldItems = this.items
        val oldSelectedId = this.selectedId
        this.items = items
        this.selectedId = selectedId
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = items.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].id == items[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = items[newItemPosition]
                val oldSelected = oldItem.id == oldSelectedId
                val newSelected = newItem.id == selectedId
                return oldItem == newItem && oldSelected == newSelected
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FederationViewHolder {
        val binding = ItemFederationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FederationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FederationViewHolder, position: Int) {
        bindItem(holder, position)
    }

    override fun onBindViewHolder(holder: FederationViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_COLORS)) {
            textColor?.let { holder.binding.federationItemName.setTextColor(it) }
            secondaryTextColor?.let { holder.binding.federationItemMeta.setTextColor(it) }
            return
        }
        bindItem(holder, position)
    }

    private fun bindItem(holder: FederationViewHolder, position: Int) {
        val item = items[position]
        val isSelected = item.id == selectedId

        holder.binding.apply {
            federationItemName.text = item.name
            val metaParts = buildList {
                item.network?.takeIf { it.isNotBlank() }?.let { add(it) }
                item.website?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            federationItemMeta.text = when {
                metaParts.isEmpty() && isSelected -> "Selected"
                metaParts.isEmpty() -> ""
                isSelected -> "Selected · " + metaParts.joinToString(" · ")
                else -> metaParts.joinToString(" · ")
            }

            textColor?.let { federationItemName.setTextColor(it) }
            secondaryTextColor?.let { federationItemMeta.setTextColor(it) }

            federationItemHolder.alpha = if (isSelected) 1f else 0.92f
            federationItemHolder.setOnClickListener { onItemClicked(item) }
        }
    }

    override fun getItemCount(): Int = items.size

    class FederationViewHolder(val binding: ItemFederationBinding) : RecyclerView.ViewHolder(binding.root)
}
