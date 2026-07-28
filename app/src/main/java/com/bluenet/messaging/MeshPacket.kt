package com.bluenet.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

data class AckPayload(
    val messageId: String,
    val senderId: String,
    val recipientId: String
)

data class RoutingAnnouncementPayload(
    val senderId: String,
    val nickname: String
)

data class MeshPacket(
    val type: Byte,
    val ttl: Int,
    val hopCount: Int,
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 1 + 1 + 4 + payload.size)
        buffer.put(type)
        buffer.put(ttl.toByte())
        buffer.put(hopCount.toByte())
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun toByteArray(): ByteArray = encode()

    fun toChatMessage(): ChatMessage? {
        if (type != TYPE_TEXT_MESSAGE) return null
        return ChatMessage.fromPayload(payload, ttl, hopCount)
    }

    fun toAck(): AckPayload? {
        if (type != TYPE_ACK) return null
        return decodeAckPayload(payload)
    }

    fun toRoutingAnnouncement(): RoutingAnnouncementPayload? {
        if (type != TYPE_ROUTING_ANNOUNCEMENT) return null
        return decodeRoutingPayload(payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MeshPacket
        if (type != other.type) return false
        if (ttl != other.ttl) return false
        if (hopCount != other.hopCount) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type.toInt()
        result = 31 * result + ttl
        result = 31 * result + hopCount
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val TYPE_TEXT_MESSAGE: Byte = 1
        const val TYPE_ACK: Byte = 2
        const val TYPE_ROUTING_ANNOUNCEMENT: Byte = 3

        fun decode(bytes: ByteArray): MeshPacket? {
            if (bytes.size < 7) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes)
                val type = buffer.get()
                val ttl = buffer.get().toInt() and 0xFF
                val hopCount = buffer.get().toInt() and 0xFF
                val payloadLen = buffer.int
                if (payloadLen < 0 || buffer.remaining() < payloadLen) return null
                val payload = ByteArray(payloadLen)
                buffer.get(payload)
                MeshPacket(type, ttl, hopCount, payload)
            } catch (e: Exception) {
                null
            }
        }

        fun fromByteArray(bytes: ByteArray): MeshPacket? = decode(bytes)

        fun createTextMessage(message: ChatMessage): MeshPacket {
            return MeshPacket(
                type = TYPE_TEXT_MESSAGE,
                ttl = message.ttl,
                hopCount = message.hopCount,
                payload = message.toPayload()
            )
        }

        fun createAck(messageId: String, senderId: String, recipientId: String, ttl: Int = 7): MeshPacket {
            val payload = encodeAckPayload(messageId, senderId, recipientId)
            return MeshPacket(
                type = TYPE_ACK,
                ttl = ttl,
                hopCount = 0,
                payload = payload
            )
        }

        fun createRoutingAnnouncement(senderId: String, nickname: String, ttl: Int = 7): MeshPacket {
            val payload = encodeRoutingPayload(senderId, nickname)
            return MeshPacket(
                type = TYPE_ROUTING_ANNOUNCEMENT,
                ttl = ttl,
                hopCount = 0,
                payload = payload
            )
        }

        private fun encodeAckPayload(messageId: String, senderId: String, recipientId: String): ByteArray {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeUTF(messageId)
            dos.writeUTF(senderId)
            dos.writeUTF(recipientId)
            dos.flush()
            return baos.toByteArray()
        }

        private fun decodeAckPayload(payload: ByteArray): AckPayload? {
            return try {
                val bais = ByteArrayInputStream(payload)
                val dis = DataInputStream(bais)
                val messageId = dis.readUTF()
                val senderId = dis.readUTF()
                val recipientId = dis.readUTF()
                AckPayload(messageId, senderId, recipientId)
            } catch (e: Exception) {
                null
            }
        }

        private fun encodeRoutingPayload(senderId: String, nickname: String): ByteArray {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeUTF(senderId)
            dos.writeUTF(nickname)
            dos.flush()
            return baos.toByteArray()
        }

        private fun decodeRoutingPayload(payload: ByteArray): RoutingAnnouncementPayload? {
            return try {
                val bais = ByteArrayInputStream(payload)
                val dis = DataInputStream(bais)
                val senderId = dis.readUTF()
                val nickname = dis.readUTF()
                RoutingAnnouncementPayload(senderId, nickname)
            } catch (e: Exception) {
                null
            }
        }
    }
}
