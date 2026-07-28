package com.bluenet.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatMessageTest {

    @Test
    fun testChatMessageCreationAndDefaults() {
        val id = UUID.randomUUID().toString()
        val msg = ChatMessage(
            messageId = id,
            senderId = "nodeA",
            recipientId = "nodeB",
            senderNickname = "Alice",
            content = "Hello Mesh!"
        )

        assertEquals(id, msg.messageId)
        assertEquals("nodeA", msg.senderId)
        assertEquals("nodeB", msg.recipientId)
        assertEquals("Alice", msg.senderNickname)
        assertEquals("Hello Mesh!", msg.content)
        assertEquals(7, msg.ttl)
        assertEquals(0, msg.hopCount)
        assertEquals(MessageStatus.PENDING, msg.status)
        assertFalse(msg.isBroadcast)
    }

    @Test
    fun testIsBroadcastProperty() {
        val broadcastMsg1 = ChatMessage("1", "nodeA", "*", "Alice", "Broadcast 1")
        val broadcastMsg2 = ChatMessage("2", "nodeA", "", "Alice", "Broadcast 2")
        val targetedMsg = ChatMessage("3", "nodeA", "nodeB", "Alice", "Targeted")

        assertTrue(broadcastMsg1.isBroadcast)
        assertTrue(broadcastMsg2.isBroadcast)
        assertFalse(targetedMsg.isBroadcast)
    }

    @Test
    fun testPayloadSerializationRoundTrip() {
        val original = ChatMessage(
            messageId = "msg-12345",
            senderId = "peer-alpha",
            recipientId = "peer-beta",
            senderNickname = "Bob 🚀",
            content = "Multi-hop text message with unicode characters: 🎉 聊天 123",
            timestamp = 1700000000000L,
            ttl = 6,
            hopCount = 1,
            status = MessageStatus.SENT
        )

        val payload = original.toPayload()
        assertNotNull(payload)
        assertTrue(payload.isNotEmpty())

        val restored = ChatMessage.fromPayload(payload, ttl = 6, hopCount = 1)
        assertNotNull(restored)
        assertEquals(original.messageId, restored?.messageId)
        assertEquals(original.senderId, restored?.senderId)
        assertEquals(original.recipientId, restored?.recipientId)
        assertEquals(original.senderNickname, restored?.senderNickname)
        assertEquals(original.content, restored?.content)
        assertEquals(original.timestamp, restored?.timestamp)
        assertEquals(6, restored?.ttl)
        assertEquals(1, restored?.hopCount)
        assertEquals(MessageStatus.RECEIVED, restored?.status)
    }

    @Test
    fun testFromPayloadWithCorruptData() {
        val corruptPayload = byteArrayOf(0x01, 0x02, 0x03)
        val restored = ChatMessage.fromPayload(corruptPayload)
        assertNull(restored)
    }
}
