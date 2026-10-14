package com.bluenet.ui

import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bluenet.R
import com.bluenet.core.mesh.ChatMessage
import com.bluenet.core.mesh.MessageStatus
import com.bluenet.databinding.ItemMessageBinding
import java.util.Date

/** Chat bubbles. Sender names are shown on incoming messages in the broadcast chat. */
class MessageAdapter(private val showSender: Boolean) : ListAdapter<ChatMessage, MessageAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        private val sideMargin = (48 * binding.root.resources.displayMetrics.density).toInt()

        fun bind(message: ChatMessage) {
            val context = binding.root.context
            val params = binding.bubble.layoutParams as LinearLayout.LayoutParams
            if (message.outgoing) {
                params.gravity = Gravity.END
                params.marginStart = sideMargin
                params.marginEnd = 0
            } else {
                params.gravity = Gravity.START
                params.marginStart = 0
                params.marginEnd = sideMargin
            }
            binding.bubble.layoutParams = params
            binding.bubble.setBackgroundResource(if (message.outgoing) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)

            val textColor = ContextCompat.getColor(context, if (message.outgoing) R.color.bn_on_bubble_out else R.color.bn_on_bubble_in)
            binding.text.setTextColor(textColor)
            binding.text.setLinkTextColor(textColor)
            binding.meta.setTextColor(textColor)
            binding.text.text = message.text

            binding.sender.visibility = if (showSender && !message.outgoing) View.VISIBLE else View.GONE
            binding.sender.text = message.nickname

            val time = DateFormat.getTimeFormat(context).format(Date(message.timestamp))
            val detail = when {
                message.outgoing && message.status == MessageStatus.DELIVERED -> context.getString(R.string.chat_delivered)
                message.outgoing && message.recipientId != null -> context.getString(R.string.chat_sent)
                !message.outgoing && message.hops > 1 -> context.getString(R.string.chat_via_hops, message.hops)
                else -> null
            }
            binding.meta.text = if (detail != null) "$time · $detail" else time
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) = oldItem == newItem
    }
}
