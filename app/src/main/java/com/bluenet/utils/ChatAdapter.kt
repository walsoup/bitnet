package com.bluenet.utils

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bluenet.R
import com.bluenet.messaging.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(private val localPeerId: String) : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(ChatDiffCallback()) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardMessage: CardView = itemView.findViewById(R.id.cardMessage)
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvBody: TextView = itemView.findViewById(R.id.tvBody)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)

        fun bind(message: ChatMessage) {
            tvBody.text = message.content
            tvTime.text = timeFormat.format(Date(message.timestamp))

            val isMine = message.senderId == localPeerId

            if (isMine) {
                tvSender.visibility = View.GONE
                cardMessage.setCardBackgroundColor(itemView.context.getColor(R.color.colorPrimaryContainer))
                tvBody.setTextColor(itemView.context.getColor(R.color.colorOnPrimaryContainer))
                tvTime.setTextColor(itemView.context.getColor(R.color.colorOnPrimaryContainer))
                val layoutParams = cardMessage.layoutParams as LinearLayout.LayoutParams
                layoutParams.gravity = Gravity.END
                cardMessage.layoutParams = layoutParams
            } else {
                tvSender.visibility = View.VISIBLE
                tvSender.text = message.senderNickname
                cardMessage.setCardBackgroundColor(itemView.context.getColor(R.color.colorSurfaceVariant))
                tvBody.setTextColor(itemView.context.getColor(R.color.colorOnSurfaceVariant))
                tvTime.setTextColor(itemView.context.getColor(R.color.colorOnSurfaceVariant))
                val layoutParams = cardMessage.layoutParams as LinearLayout.LayoutParams
                layoutParams.gravity = Gravity.START
                cardMessage.layoutParams = layoutParams
            }
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.messageId == newItem.messageId
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
