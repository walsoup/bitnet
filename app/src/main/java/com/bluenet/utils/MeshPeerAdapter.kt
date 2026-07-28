package com.bluenet.utils

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bluenet.R
import com.bluenet.databinding.ItemMeshPeerBinding
import com.bluenet.mesh.MeshPeer

class MeshPeerAdapter(
    private val onConnectClicked: (MeshPeer) -> Unit
) : ListAdapter<MeshPeer, MeshPeerAdapter.PeerViewHolder>(DiffCallback) {

    inner class PeerViewHolder(private val binding: ItemMeshPeerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(peer: MeshPeer) {
            binding.tvPeerName.text = peer.displayName
            
            val signalDbm = peer.signalStrength
            binding.tvPeerInfo.text = when {
                signalDbm >= -60 -> binding.root.context.getString(R.string.signal_strong, signalDbm)
                signalDbm >= -80 -> binding.root.context.getString(R.string.signal_medium, signalDbm)
                else -> binding.root.context.getString(R.string.signal_weak, signalDbm)
            }

            if (peer.isSharingInternet) {
                binding.chipSharing.visibility = View.VISIBLE
                binding.btnConnectPeer.visibility = View.VISIBLE
            } else {
                binding.chipSharing.visibility = View.GONE
                binding.btnConnectPeer.visibility = View.GONE
            }

            binding.btnConnectPeer.setOnClickListener {
                onConnectClicked(peer)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val binding = ItemMeshPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<MeshPeer>() {
            override fun areItemsTheSame(oldItem: MeshPeer, newItem: MeshPeer): Boolean {
                return oldItem.macAddress == newItem.macAddress
            }

            override fun areContentsTheSame(oldItem: MeshPeer, newItem: MeshPeer): Boolean {
                return oldItem == newItem
            }
        }
    }
}
