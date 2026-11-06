package com.bluenet.core.mesh

import com.bluenet.core.Log
import com.bluenet.core.crypto.Identity
import com.bluenet.core.toHex
import kotlin.math.abs
import kotlin.math.min

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
 *
 * Text ids are remembered for the whole acceptance window, apart from announce and ack ids.
 * Announces are a fresh id every half minute from every node, and a single FIFO lets that traffic
 * forget a text while a late copy of it is still acceptable. Duplicate announces and acks are
 * harmless; a duplicate text is delivered and flooded again.
 *
 * Direct texts stay in an outbox until they are acknowledged or [DIRECT_RETRY_MS] has passed, and
 * broadcasts are retried for [BROADCAST_RETRY_MS]. [onConnectivityChanged] flushes that outbox, so
 * a message sent while nobody is linked still goes out when a link comes up.
 */
class MeshRouter(
    private val identity: Identity,
    private val transport: MeshTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxHops: Int = DEFAULT_MAX_HOPS,
    private val maxClockSkewMs: Long = DEFAULT_MAX_CLOCK_SKEW_MS,
    textSeenCapacity: Int = DEFAULT_TEXT_SEEN,
    ephemeralSeenCapacity: Int = DEFAULT_EPHEMERAL_SEEN,
) {
    interface Listener {
        fun onText(message: ChatMessage)
        fun onDelivered(messageId: String)
        fun onAnnounce(peerId: String, nickname: String, isSharingInternet: Boolean, hops: Int)
    }

    @Volatile
    var listener: Listener? = null

    val localPeerId: String get() = identity.peerId

    private val seenLock = Any()
    private val textSeen = TextSeen(textSeenCapacity, maxClockSkewMs, clock)
    private val ephemeralSeen = IdFifo(ephemeralSeenCapacity)
    private val outbox = LinkedHashMap<String, Outbound>()

    fun onPacket(bytes: ByteArray, fromPeerId: String) {
        val packet = MeshPacket.decode(bytes) ?: return
        if (packet.ttl <= 0) return
        if (!packet.verify()) {
            Log.w(TAG, "Dropping packet with invalid signature from neighbor $fromPeerId")
            return
        }
        if (abs(clock() - packet.timestamp) > maxClockSkewMs) return
        if (!remember(packet)) {
            // The recipient already has this text. Acknowledge again so a lost ACK doesn't make
            // the sender retry until the retry window ends, and don't deliver or forward it.
            if (packet.type == MeshPacket.TYPE_TEXT &&
                packet.recipientId == identity.peerId &&
                packet.senderId != identity.peerId
            ) {
                acknowledge(packet)
            }
            return
        }
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

    /** Sends any text that has not been acknowledged yet. Called when a new link opens. */
    fun onConnectivityChanged() = retryPending(force = true)

    /** Sends texts whose retry time has arrived, and drops those that have aged out. */
    fun retryPending(now: Long = clock(), force: Boolean = false) {
        val due = ArrayList<MeshPacket>()
        synchronized(outbox) {
            val iterator = outbox.entries.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next().value
                if (!live(item, now)) {
                    iterator.remove()
                    continue
                }
                if (!force && item.nextAt > now) continue
                item.attempts++
                item.nextAt = now + retryDelay(item.attempts)
                due += item.packet
            }
        }
        for (packet in due) forward(packet, exceptPeerId = null)
    }

    /** Text ids still remembered. Entries outside the acceptance window are dropped first. */
    internal fun retainedTextIds(): Int = synchronized(seenLock) { textSeen.retained() }

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
                if (!packet.isBroadcast) acknowledge(packet)
            }
            MeshPacket.TYPE_ACK -> {
                if (!packet.isBroadcast && packet.payload.size == MeshPacket.ID_SIZE) {
                    val id = packet.payload.toHex()
                    synchronized(outbox) { outbox.remove(id) }
                    listener?.onDelivered(id)
                }
            }
            MeshPacket.TYPE_ANNOUNCE -> {
                val announce = MeshPayloads.decodeAnnounce(packet.payload) ?: return
                listener?.onAnnounce(packet.senderId, announce.nickname, announce.isSharingInternet, packet.hops + 1)
            }
        }
    }

    private fun acknowledge(packet: MeshPacket) {
        transmit(
            MeshPacket.create(identity, MeshPacket.TYPE_ACK, packet.senderId, packet.messageId, maxHops, clock()),
            track = false,
        )
    }

    private fun send(packet: MeshPacket) = transmit(packet, track = packet.type == MeshPacket.TYPE_TEXT)

    private fun transmit(packet: MeshPacket, track: Boolean) {
        remember(packet)
        if (track) trackOutbound(packet)
        forward(packet, exceptPeerId = null)
    }

    private fun trackOutbound(packet: MeshPacket) {
        val now = clock()
        synchronized(outbox) {
            if (outbox.containsKey(packet.messageIdHex)) return
            while (outbox.size >= MAX_OUTBOX) outbox.remove(outbox.keys.first())
            outbox[packet.messageIdHex] = Outbound(packet, attempts = 1, nextAt = now + retryDelay(1))
        }
    }

    private fun live(item: Outbound, now: Long): Boolean {
        val age = now - item.packet.timestamp
        if (age < -maxClockSkewMs) return false
        return if (item.packet.isBroadcast) age <= BROADCAST_RETRY_MS else age <= DIRECT_RETRY_MS
    }

    private fun retryDelay(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 3)
        return min(RETRY_INITIAL_MS shl shift, RETRY_MAX_MS)
    }

    private fun forward(packet: MeshPacket, exceptPeerId: String?) {
        val bytes = packet.encode()
        val recipient = packet.recipientId
        if (recipient != null && recipient != exceptPeerId && recipient in transport.neighbors()) {
            if (transport.sendTo(recipient, bytes)) return
        }
        transport.broadcast(bytes, exceptPeerId)
    }

    /** Returns true if [packet] had not been seen before. */
    private fun remember(packet: MeshPacket): Boolean = synchronized(seenLock) {
        if (packet.type == MeshPacket.TYPE_TEXT) textSeen.add(packet.messageIdHex, packet.timestamp)
        else ephemeralSeen.add(packet.messageIdHex)
    }

    private class Outbound(val packet: MeshPacket, var attempts: Int, var nextAt: Long)

    /**
     * Text ids keyed by the packet timestamp. Ids older than the acceptance window are dropped,
     * and the map never grows past [capacity]; the oldest timestamp is the one forgotten.
     */
    private class TextSeen(
        private val capacity: Int,
        private val ttlMs: Long,
        private val clock: () -> Long,
    ) {
        private val entries = LinkedHashMap<String, Long>()

        fun add(id: String, timestamp: Long): Boolean {
            prune()
            if (entries.containsKey(id)) return false
            entries[id] = timestamp
            while (entries.size > capacity) {
                val oldest = entries.entries.minBy { it.value }.key
                entries.remove(oldest)
            }
            return true
        }

        fun retained(): Int {
            prune()
            return entries.size
        }

        private fun prune() {
            val now = clock()
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                if (abs(now - iterator.next().value) > ttlMs) iterator.remove()
            }
        }
    }

    /** Insertion-order FIFO. Seeing an id again does not refresh it. */
    private class IdFifo(private val capacity: Int) {
        private val entries = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
        }

        fun add(id: String): Boolean {
            if (entries.containsKey(id)) return false
            entries[id] = Unit
            return true
        }
    }

    companion object {
        private const val TAG = "MeshRouter"
        const val DEFAULT_MAX_HOPS = 7
        const val DEFAULT_MAX_CLOCK_SKEW_MS = 60 * 60 * 1000L
        const val DEFAULT_TEXT_SEEN = 2_048
        const val DEFAULT_EPHEMERAL_SEEN = 512
        const val DIRECT_RETRY_MS = 2 * 60 * 1000L
        const val BROADCAST_RETRY_MS = 60_000L
        const val RETRY_INITIAL_MS = 4_000L
        private const val RETRY_MAX_MS = 20_000L
        private const val MAX_OUTBOX = 64
    }
}
