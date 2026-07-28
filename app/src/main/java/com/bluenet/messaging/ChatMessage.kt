package com.bluenet.messaging

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class ChatMessage(
    val messageId: String,
    val senderId: String,
    val recipientId: String,
    val senderNickname: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 7,
    val hopCount: Int = 0,
    val status: MessageStatus = MessageStatus.PENDING
) {
    val isBroadcast: Boolean
        get() = recipientId.isEmpty() || recipientId == "*"

    fun toPayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeUTF(messageId)
        dos.writeUTF(senderId)
        dos.writeUTF(recipientId)
        dos.writeUTF(senderNickname)
        dos.writeLong(timestamp)
        dos.writeUTF(content)
        dos.flush()
        return baos.toByteArray()
    }

    companion object {
        fun fromPayload(payload: ByteArray, ttl: Int = 7, hopCount: Int = 0): ChatMessage? {
            return try {
                val bais = ByteArrayInputStream(payload)
                val dis = DataInputStream(bais)
                val messageId = dis.readUTF()
                val senderId = dis.readUTF()
                val recipientId = dis.readUTF()
                val senderNickname = dis.readUTF()
                val timestamp = dis.readLong()
                val content = dis.readUTF()
                ChatMessage(
                    messageId = messageId,
                    senderId = senderId,
                    recipientId = recipientId,
                    senderNickname = senderNickname,
                    content = content,
                    timestamp = timestamp,
                    ttl = ttl,
                    hopCount = hopCount,
                    status = MessageStatus.RECEIVED
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
