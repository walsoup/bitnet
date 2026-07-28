package com.bluenet.messaging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class MessageStore {

    private val messagesMap = ConcurrentHashMap<String, ChatMessage>()
    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    fun saveMessage(message: ChatMessage) {
        messagesMap[message.messageId] = message
        notifyStateChanged()
    }

    fun getMessage(messageId: String): ChatMessage? {
        return messagesMap[messageId]
    }

    fun updateMessageStatus(messageId: String, newStatus: MessageStatus): Boolean {
        val existing = messagesMap[messageId] ?: return false
        if (existing.status == newStatus) return true
        val updated = existing.copy(status = newStatus)
        messagesMap[messageId] = updated
        notifyStateChanged()
        return true
    }

    fun getConversation(peerId: String): List<ChatMessage> {
        if (peerId == "*" || peerId.isEmpty()) {
            return getBroadcastMessages()
        }
        return messagesMap.values
            .filter { (it.senderId == peerId && !it.isBroadcast) || (it.recipientId == peerId) }
            .sortedBy { it.timestamp }
    }

    fun getBroadcastMessages(): List<ChatMessage> {
        return messagesMap.values
            .filter { it.isBroadcast }
            .sortedBy { it.timestamp }
    }

    fun getAllMessages(): List<ChatMessage> {
        return messagesMap.values.sortedBy { it.timestamp }
    }

    fun getPendingMessages(): List<ChatMessage> {
        return messagesMap.values.filter { it.status == MessageStatus.PENDING }
    }

    fun deleteMessage(messageId: String): Boolean {
        val removed = messagesMap.remove(messageId) != null
        if (removed) {
            notifyStateChanged()
        }
        return removed
    }

    fun clear() {
        messagesMap.clear()
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        _messagesFlow.value = getAllMessages()
    }
}
