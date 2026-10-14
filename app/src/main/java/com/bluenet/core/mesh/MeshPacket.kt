package com.bluenet.core.mesh

import com.bluenet.core.PeerIds
import com.bluenet.core.crypto.EcKeys
import com.bluenet.core.crypto.Identity
import com.bluenet.core.toHex
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * A signed, multi-hop mesh packet.
 *
 * Layout: `version | type | ttl | hops | messageId[16] | sender[8] | recipient[8] | timestamp i64 |
 * payloadLength u16 | payload | senderKey[65] | sigLength u8 | signature`.
 * Everything except `ttl` and `hops` is covered by the sender's ECDSA signature, and the sender ID
 * must equal the hash of `senderKey`, so relays can forward packets but cannot forge or alter them.
 */
class MeshPacket private constructor(
    val type: Int,
    val ttl: Int,
    val hops: Int,
    val messageId: ByteArray,
    val senderId: String,
    /** Null for broadcast packets. */
    val recipientId: String?,
    val timestamp: Long,
    val payload: ByteArray,
    val senderKey: ByteArray,
    val signature: ByteArray,
) {
    val messageIdHex: String = messageId.toHex()
    val isBroadcast: Boolean get() = recipientId == null

    fun verify(): Boolean =
        EcKeys.peerIdOf(senderKey) == senderId && Identity.verify(senderKey, signedBytes(type, messageId, senderId, recipientId, timestamp, payload, senderKey), signature)

    fun forwarded(): MeshPacket =
        MeshPacket(type, ttl - 1, hops + 1, messageId, senderId, recipientId, timestamp, payload, senderKey, signature)

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(FIXED_SIZE + payload.size + signature.size)
        buffer.put(VERSION.toByte())
        buffer.put(type.toByte())
        buffer.put(ttl.toByte())
        buffer.put(hops.toByte())
        buffer.put(messageId)
        buffer.put(PeerIds.toBytes(senderId))
        buffer.put(recipientId?.let(PeerIds::toBytes) ?: PeerIds.BROADCAST_BYTES)
        buffer.putLong(timestamp)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        buffer.put(senderKey)
        buffer.put(signature.size.toByte())
        buffer.put(signature)
        return buffer.array()
    }

    companion object {
        const val VERSION = 2
        const val TYPE_TEXT = 1
        const val TYPE_ACK = 2
        const val TYPE_ANNOUNCE = 3
        const val ID_SIZE = 16
        const val MAX_PAYLOAD = 2048
        private const val MAX_SIGNATURE = 80
        private const val FIXED_SIZE = 4 + ID_SIZE + PeerIds.SIZE * 2 + 8 + 2 + EcKeys.PUBLIC_KEY_SIZE + 1

        private val random = SecureRandom()

        fun create(identity: Identity, type: Int, recipientId: String?, payload: ByteArray, ttl: Int, timestamp: Long): MeshPacket {
            require(payload.size <= MAX_PAYLOAD) { "Payload too large" }
            val messageId = ByteArray(ID_SIZE).also(random::nextBytes)
            val signature = identity.sign(signedBytes(type, messageId, identity.peerId, recipientId, timestamp, payload, identity.publicKey))
            return MeshPacket(type, ttl, 0, messageId, identity.peerId, recipientId, timestamp, payload, identity.publicKey, signature)
        }

        fun decode(bytes: ByteArray): MeshPacket? = try {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.get().toInt() != VERSION) null else {
                val type = buffer.get().toInt() and 0xFF
                val ttl = buffer.get().toInt() and 0xFF
                val hops = buffer.get().toInt() and 0xFF
                val messageId = ByteArray(ID_SIZE).also { buffer.get(it) }
                val sender = ByteArray(PeerIds.SIZE).also { buffer.get(it) }
                val recipient = ByteArray(PeerIds.SIZE).also { buffer.get(it) }
                val timestamp = buffer.long
                val payloadLength = buffer.short.toInt() and 0xFFFF
                if (payloadLength > MAX_PAYLOAD) null else {
                    val payload = ByteArray(payloadLength).also { buffer.get(it) }
                    val senderKey = ByteArray(EcKeys.PUBLIC_KEY_SIZE).also { buffer.get(it) }
                    val sigLength = buffer.get().toInt() and 0xFF
                    if (sigLength == 0 || sigLength > MAX_SIGNATURE || buffer.remaining() != sigLength) null else {
                        val signature = ByteArray(sigLength).also { buffer.get(it) }
                        val recipientId = if (recipient.contentEquals(PeerIds.BROADCAST_BYTES)) null else recipient.toHex()
                        MeshPacket(type, ttl, hops, messageId, sender.toHex(), recipientId, timestamp, payload, senderKey, signature)
                    }
                }
            }
        } catch (_: RuntimeException) {
            null
        }

        private fun signedBytes(
            type: Int,
            messageId: ByteArray,
            senderId: String,
            recipientId: String?,
            timestamp: Long,
            payload: ByteArray,
            senderKey: ByteArray,
        ): ByteArray {
            val buffer = ByteBuffer.allocate(2 + ID_SIZE + PeerIds.SIZE * 2 + 8 + 2 + payload.size + senderKey.size)
            buffer.put(VERSION.toByte())
            buffer.put(type.toByte())
            buffer.put(messageId)
            buffer.put(PeerIds.toBytes(senderId))
            buffer.put(recipientId?.let(PeerIds::toBytes) ?: PeerIds.BROADCAST_BYTES)
            buffer.putLong(timestamp)
            buffer.putShort(payload.size.toShort())
            buffer.put(payload)
            buffer.put(senderKey)
            return buffer.array()
        }
    }
}

object MeshPayloads {
    const val MAX_NICKNAME_BYTES = 32
    const val MAX_TEXT_BYTES = 1500

    class Text(val nickname: String, val text: String)
    class Announce(val nickname: String, val isSharingInternet: Boolean)

    fun encodeText(nickname: String, text: String): ByteArray {
        val nick = truncateUtf8(nickname, MAX_NICKNAME_BYTES)
        val body = text.toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_TEXT_BYTES) { "Message too long" }
        return byteArrayOf(nick.size.toByte()) + nick + body
    }

    fun decodeText(payload: ByteArray): Text? {
        if (payload.isEmpty()) return null
        val nickLength = payload[0].toInt() and 0xFF
        if (nickLength > MAX_NICKNAME_BYTES || payload.size < 1 + nickLength) return null
        val nickname = String(payload, 1, nickLength, StandardCharsets.UTF_8)
        val text = String(payload, 1 + nickLength, payload.size - 1 - nickLength, StandardCharsets.UTF_8)
        return Text(nickname, text)
    }

    fun encodeAnnounce(nickname: String, isSharingInternet: Boolean): ByteArray =
        byteArrayOf(if (isSharingInternet) 1 else 0) + truncateUtf8(nickname, MAX_NICKNAME_BYTES)

    fun decodeAnnounce(payload: ByteArray): Announce? {
        if (payload.isEmpty() || payload.size > 1 + MAX_NICKNAME_BYTES) return null
        return Announce(String(payload, 1, payload.size - 1, StandardCharsets.UTF_8), (payload[0].toInt() and 1) != 0)
    }

    /** Truncates to at most [maxBytes] of UTF-8 without splitting a code point. */
    fun truncateUtf8(value: String, maxBytes: Int): ByteArray {
        val full = value.toByteArray(StandardCharsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = 0
        var bytes = 0
        while (end < value.length) {
            val cp = value.codePointAt(end)
            val size = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return value.substring(0, end).toByteArray(StandardCharsets.UTF_8)
    }
}
