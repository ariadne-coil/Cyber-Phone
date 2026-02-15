package org.fossify.phone.wallet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.phone.databinding.ItemFederationBinding

class FederationAdapter(
    private val onItemClicked: (FederationEntry) -> Unit,
) : RecyclerView.Adapter<FederationAdapter.FederationViewHolder>() {

    private var items: List<FederationEntry> = emptyList()
    private var selectedId: String = ""
    private var textColor: Int? = null
    private var secondaryTextColor: Int? = null

    fun submitList(items: List<FederationEntry>, selectedId: String) {
        this.items = items
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    fun updateTextColors(textColor: Int, secondaryTextColor: Int) {
        this.textColor = textColor
        this.secondaryTextColor = secondaryTextColor
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FederationViewHolder {
        val binding = ItemFederationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FederationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FederationViewHolder, position: Int) {
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

