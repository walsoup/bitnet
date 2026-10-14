package com.bluenet.core.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MessageStatus { SENT, DELIVERED, RECEIVED }

data class ChatMessage(
    val id: String,
    val senderId: String,
    /** Null for messages to everyone. */
    val recipientId: String?,
    val nickname: String,
    val text: String,
    val timestamp: Long,
    val status: MessageStatus,
    val hops: Int,
    val outgoing: Boolean,
) {
    /** The chat this message belongs to: the other peer's ID, or [BROADCAST_CONVERSATION]. */
    val conversationId: String
        get() = when {
            recipientId == null -> BROADCAST_CONVERSATION
            outgoing -> recipientId
            else -> senderId
        }

    companion object {
        const val BROADCAST_CONVERSATION = "*"
    }
}

/** In-memory chat history, capped at [capacity] messages. */
class MessageStore(private val capacity: Int = 1_000) {
    private val messagesById = LinkedHashMap<String, ChatMessage>()
    /** ACKs that arrived before their message was stored (the ACK can beat `add` on another thread). */
    private val earlyAcks = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= MAX_EARLY_ACKS) remove(first())
            return super.add(element)
        }
    }
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    @Synchronized
    fun add(message: ChatMessage) {
        val acked = message.outgoing && message.status == MessageStatus.SENT && earlyAcks.remove(message.id)
        messagesById[message.id] = if (acked) message.copy(status = MessageStatus.DELIVERED) else message
        while (messagesById.size > capacity) {
            messagesById.remove(messagesById.keys.first())
        }
        publish()
    }

    @Synchronized
    fun markDelivered(messageId: String) {
        val existing = messagesById[messageId]
        if (existing == null) {
            earlyAcks.add(messageId)
            return
        }
        if (existing.status == MessageStatus.DELIVERED) return
        messagesById[messageId] = existing.copy(status = MessageStatus.DELIVERED)
        publish()
    }

    fun conversation(conversationId: String): List<ChatMessage> =
        messages.value.filter { it.conversationId == conversationId }

    private fun publish() {
        _messages.value = messagesById.values.sortedBy { it.timestamp }
    }

    private companion object {
        const val MAX_EARLY_ACKS = 256
    }
}
