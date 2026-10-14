package com.bluenet.core.mesh

import com.bluenet.core.Log
import com.bluenet.core.crypto.Identity
import com.bluenet.core.toHex

/** What the router needs from the link layer: the directly connected peers. */
interface MeshTransport {
    fun neighbors(): Set<String>
    fun sendTo(peerId: String, packet: ByteArray): Boolean
    fun broadcast(packet: ByteArray, exceptPeerId: String?)
}

/**
 * Flooding router for signed [MeshPacket]s.
 *
 * Packets are verified, de-duplicated by message ID, delivered locally when addressed to us or to
 * everyone, and re-flooded with a decremented TTL. Direct messages to a neighbor skip the flood.
 * This class is thread-safe; packets may arrive concurrently from several links.
 */
class MeshRouter(
    private val identity: Identity,
    private val transport: MeshTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxHops: Int = DEFAULT_MAX_HOPS,
    seenCapacity: Int = 4_096,
    private val maxClockSkewMs: Long = 60 * 60 * 1000L,
) {
    interface Listener {
        fun onText(message: ChatMessage)
        fun onDelivered(messageId: String)
        fun onAnnounce(peerId: String, nickname: String, isSharingInternet: Boolean, hops: Int)
    }

    @Volatile
    var listener: Listener? = null

    val localPeerId: String get() = identity.peerId

    private val seen = object : LinkedHashMap<String, Unit>(seenCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > seenCapacity
    }

    fun onPacket(bytes: ByteArray, fromPeerId: String) {
        val packet = MeshPacket.decode(bytes) ?: return
        if (packet.ttl <= 0 || isSeen(packet.messageIdHex)) return
        if (!packet.verify()) {
            Log.w(TAG, "Dropping packet with invalid signature from neighbor $fromPeerId")
            return
        }
        if (Math.abs(clock() - packet.timestamp) > maxClockSkewMs) return
        if (!markSeen(packet.messageIdHex)) return
        if (packet.senderId == identity.peerId) return

        val forMe = packet.recipientId == identity.peerId
        if (forMe || packet.isBroadcast) deliver(packet)
        if (!forMe && packet.ttl > 1) forward(packet.forwarded(), fromPeerId)
    }

    fun sendText(recipientId: String?, nickname: String, text: String): ChatMessage {
        val packet = MeshPacket.create(identity, MeshPacket.TYPE_TEXT, recipientId, MeshPayloads.encodeText(nickname, text), maxHops, clock())
        send(packet)
        return ChatMessage(
            id = packet.messageIdHex,
            senderId = identity.peerId,
            recipientId = recipientId,
            nickname = nickname,
            text = text,
            timestamp = packet.timestamp,
            status = MessageStatus.SENT,
            hops = 0,
            outgoing = true,
        )
    }

    fun announce(nickname: String, isSharingInternet: Boolean) {
        send(MeshPacket.create(identity, MeshPacket.TYPE_ANNOUNCE, null, MeshPayloads.encodeAnnounce(nickname, isSharingInternet), maxHops, clock()))
    }

    private fun deliver(packet: MeshPacket) {
        when (packet.type) {
            MeshPacket.TYPE_TEXT -> {
                val text = MeshPayloads.decodeText(packet.payload) ?: return
                listener?.onText(
                    ChatMessage(
                        id = packet.messageIdHex,
                        senderId = packet.senderId,
                        recipientId = packet.recipientId,
                        nickname = text.nickname,
                        text = text.text,
                        timestamp = packet.timestamp,
                        status = MessageStatus.RECEIVED,
                        hops = packet.hops + 1,
                        outgoing = false,
                    )
                )
                if (!packet.isBroadcast) {
                    send(MeshPacket.create(identity, MeshPacket.TYPE_ACK, packet.senderId, packet.messageId, maxHops, clock()))
                }
            }
            MeshPacket.TYPE_ACK -> {
                if (!packet.isBroadcast && packet.payload.size == MeshPacket.ID_SIZE) {
                    listener?.onDelivered(packet.payload.toHex())
                }
            }
            MeshPacket.TYPE_ANNOUNCE -> {
                val announce = MeshPayloads.decodeAnnounce(packet.payload) ?: return
                listener?.onAnnounce(packet.senderId, announce.nickname, announce.isSharingInternet, packet.hops + 1)
            }
        }
    }

    private fun send(packet: MeshPacket) {
        markSeen(packet.messageIdHex)
        forward(packet, exceptPeerId = null)
    }

    private fun forward(packet: MeshPacket, exceptPeerId: String?) {
        val bytes = packet.encode()
        val recipient = packet.recipientId
        if (recipient != null && recipient != exceptPeerId && recipient in transport.neighbors()) {
            if (transport.sendTo(recipient, bytes)) return
        }
        transport.broadcast(bytes, exceptPeerId)
    }

    @Synchronized
    private fun isSeen(id: String): Boolean = seen.containsKey(id)

    /** Returns true if [id] was not seen before. */
    @Synchronized
    private fun markSeen(id: String): Boolean = seen.put(id, Unit) == null

    companion object {
        private const val TAG = "MeshRouter"
        const val DEFAULT_MAX_HOPS = 7
    }
}
