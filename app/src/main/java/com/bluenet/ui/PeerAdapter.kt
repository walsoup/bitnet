package com.bluenet.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bluenet.R
import com.bluenet.databinding.ItemPeerBinding
import com.bluenet.node.Peer

class PeerAdapter(
    private val onChat: (Peer) -> Unit,
    private val onUseInternet: (Peer) -> Unit,
) : ListAdapter<Peer, PeerAdapter.Holder>(Diff) {

    /** The sharer we are currently tunnelling through, whose "Use internet" button is hidden. */
    var tunnelPeerId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemPeerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(peer: Peer) {
            val context = binding.root.context
            binding.avatar.text = initials(peer.name)
            binding.name.text = peer.name
            binding.details.text = details(context, peer)
            binding.sharingChip.visibility = if (peer.isSharingInternet) View.VISIBLE else View.GONE
            binding.useInternetButton.visibility =
                if (peer.canProvideInternet && peer.id != tunnelPeerId) View.VISIBLE else View.GONE
            binding.chatButton.setOnClickListener { onChat(peer) }
            binding.useInternetButton.setOnClickListener { onUseInternet(peer) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Peer>() {
        override fun areItemsTheSame(oldItem: Peer, newItem: Peer) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Peer, newItem: Peer) = oldItem == newItem
    }

    companion object {
        fun initials(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "?"
            return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
        }

        fun details(context: Context, peer: Peer): String {
            val parts = buildList {
                peer.rssi?.let { add(context.getString(R.string.peer_signal, it)) }
                peer.hops?.takeIf { it > 1 }?.let { add(context.getString(R.string.peer_hops, it)) }
                if (peer.linked) add(context.getString(R.string.peer_linked))
            }
            return when {
                parts.isNotEmpty() -> parts.joinToString(" · ")
                else -> context.getString(R.string.peer_via_mesh)
            }
        }
    }
}
