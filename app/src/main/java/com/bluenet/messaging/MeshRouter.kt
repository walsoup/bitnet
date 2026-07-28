package com.bluenet.messaging

import android.util.Log
import com.bluenet.mesh.MeshTransport
import java.util.LinkedHashMap
import java.util.UUID

class MeshRouter(
    val localPeerId: String,
    val messageStore: MessageStore,
    val transport: MeshTransport? = null,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val lruCapacity: Int = DEFAULT_LRU_CAPACITY
) {
    companion object {
        private const val TAG = "MeshRouter"
        const val DEFAULT_MAX_HOPS = 7
        const val DEFAULT_LRU_CAPACITY = 1000
    }

    private val seenMessageCache = LruCache<String, Boolean>(lruCapacity)

    var onMessageReceived: ((ChatMessage) -> Unit)? = null
    var onAckReceived: ((String) -> Unit)? = null
    var onForwardedPacket: ((MeshPacket, String?) -> Unit)? = null

    init {
        transport?.setPacketListener { data, fromPeerId ->
            processIncomingBytes(data, fromPeerId)
        }
    }

    fun processIncomingBytes(data: ByteArray, fromPeerId: String? = null): Boolean {
        val packet = MeshPacket.decode(data) ?: return false
        return processIncomingPacket(packet, fromPeerId)
    }

    fun processIncomingPacket(packet: MeshPacket, fromPeerId: String? = null): Boolean {
        if (packet.ttl <= 0) {
            Log.d(TAG, "Dropped packet: TTL <= 0 (ttl=${packet.ttl})")
            return false
        }

        val dedupKey = extractDedupKey(packet) ?: return false
        if (seenMessageCache.containsKey(dedupKey)) {
            Log.d(TAG, "Dropped duplicate packet with dedup key: $dedupKey")
            return false
        }
        seenMessageCache.put(dedupKey, true)

        val nextTtl = packet.ttl - 1
        val nextHopCount = packet.hopCount + 1

        when (packet.type) {
            MeshPacket.TYPE_TEXT_MESSAGE -> {
                val chatMsg = packet.toChatMessage() ?: return false
                val updatedMsg = chatMsg.copy(ttl = nextTtl, hopCount = nextHopCount)
                val isForMe = chatMsg.recipientId == localPeerId
                val isBroadcast = chatMsg.isBroadcast

                if (isForMe || isBroadcast) {
                    val receivedMsg = updatedMsg.copy(status = MessageStatus.RECEIVED)
                    messageStore.saveMessage(receivedMsg)
                    onMessageReceived?.invoke(receivedMsg)

                    if (isForMe) {
                        val ackPacket = MeshPacket.createAck(
                            messageId = chatMsg.messageId,
                            senderId = localPeerId,
                            recipientId = chatMsg.senderId,
                            ttl = maxHops
                        )
                        val ackDedupKey = "ACK_${chatMsg.messageId}_$localPeerId"
                        seenMessageCache.put(ackDedupKey, true)
                        sendPacketOrBroadcast(ackPacket, chatMsg.senderId, fromPeerId)
                    }
                }

                if (!isForMe && nextTtl > 0) {
                    val forwardPacket = MeshPacket(
                        type = MeshPacket.TYPE_TEXT_MESSAGE,
                        ttl = nextTtl,
                        hopCount = nextHopCount,
                        payload = chatMsg.toPayload()
                    )
                    val targetRecipient = if (isBroadcast) null else chatMsg.recipientId
                    sendPacketOrBroadcast(forwardPacket, targetRecipient, excludePeerId = fromPeerId)
                    onForwardedPacket?.invoke(forwardPacket, fromPeerId)
                }
            }

            MeshPacket.TYPE_ACK -> {
                val ackPayload = packet.toAck() ?: return false
                if (ackPayload.recipientId == localPeerId) {
                    messageStore.updateMessageStatus(ackPayload.messageId, MessageStatus.DELIVERED)
                    onAckReceived?.invoke(ackPayload.messageId)
                } else if (nextTtl > 0) {
                    val forwardAck = MeshPacket(
                        type = MeshPacket.TYPE_ACK,
                        ttl = nextTtl,
                        hopCount = nextHopCount,
                        payload = packet.payload
                    )
                    sendPacketOrBroadcast(forwardAck, ackPayload.recipientId, excludePeerId = fromPeerId)
                    onForwardedPacket?.invoke(forwardAck, fromPeerId)
                }
            }

            MeshPacket.TYPE_ROUTING_ANNOUNCEMENT -> {
                if (nextTtl > 0) {
                    val forwardPacket = MeshPacket(
                        type = MeshPacket.TYPE_ROUTING_ANNOUNCEMENT,
                        ttl = nextTtl,
                        hopCount = nextHopCount,
                        payload = packet.payload
                    )
                    sendPacketOrBroadcast(forwardPacket, null, excludePeerId = fromPeerId)
                    onForwardedPacket?.invoke(forwardPacket, fromPeerId)
                }
            }
        }

        return true
    }

    fun sendTextMessage(recipientId: String, content: String, senderNickname: String): ChatMessage {
        val messageId = UUID.randomUUID().toString()
        val chatMsg = ChatMessage(
            messageId = messageId,
            senderId = localPeerId,
            recipientId = recipientId,
            senderNickname = senderNickname,
            content = content,
            timestamp = System.currentTimeMillis(),
            ttl = maxHops,
            hopCount = 0,
            status = MessageStatus.SENT
        )
        messageStore.saveMessage(chatMsg)
        seenMessageCache.put(messageId, true)

        val packet = MeshPacket.createTextMessage(chatMsg)
        val targetRecipient = if (chatMsg.isBroadcast) null else recipientId
        sendPacketOrBroadcast(packet, targetRecipient, excludePeerId = null)

        return chatMsg
    }

    fun resendMessage(messageId: String): Boolean {
        val msg = messageStore.getMessage(messageId) ?: return false
        val packet = MeshPacket.createTextMessage(msg)
        val targetRecipient = if (msg.isBroadcast) null else msg.recipientId
        sendPacketOrBroadcast(packet, targetRecipient, excludePeerId = null)
        return true
    }

    fun isMessageSeen(messageId: String): Boolean = seenMessageCache.containsKey(messageId)

    fun markMessageSeen(messageId: String) {
        seenMessageCache.put(messageId, true)
    }

    private fun sendPacketOrBroadcast(packet: MeshPacket, targetPeerId: String?, excludePeerId: String?) {
        val data = packet.encode()
        transport?.let { tr ->
            if (targetPeerId != null && tr.getConnectedPeers().contains(targetPeerId)) {
                tr.sendPacket(data, targetPeerId)
            } else {
                tr.broadcastPacket(data)
            }
        }
    }

    private fun extractDedupKey(packet: MeshPacket): String? {
        return when (packet.type) {
            MeshPacket.TYPE_TEXT_MESSAGE -> {
                packet.toChatMessage()?.messageId
            }
            MeshPacket.TYPE_ACK -> {
                val ack = packet.toAck() ?: return null
                "ACK_${ack.messageId}_${ack.senderId}"
            }
            MeshPacket.TYPE_ROUTING_ANNOUNCEMENT -> {
                val routing = packet.toRoutingAnnouncement() ?: return null
                "ANNOUNCE_${routing.senderId}"
            }
            else -> null
        }
    }

    private class LruCache<K, V>(private val capacity: Int) {
        private val map = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > capacity
            }
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }

        @Synchronized
        fun containsKey(key: K): Boolean = map.containsKey(key)

        @Synchronized
        fun clear() = map.clear()

        @Synchronized
        fun size(): Int = map.size
    }
}
