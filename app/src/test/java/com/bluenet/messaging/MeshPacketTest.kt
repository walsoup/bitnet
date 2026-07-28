package com.bluenet.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshPacketTest {

    @Test
    fun testTextMessagePacketEncodingAndDecoding() {
        val chatMsg = ChatMessage(
            messageId = "msg-999",
            senderId = "alice-peer",
            recipientId = "bob-peer",
            senderNickname = "Alice",
            content = "Test direct message",
            timestamp = 1700000005000L,
            ttl = 5,
            hopCount = 2
        )

        val packet = MeshPacket.createTextMessage(chatMsg)
        assertEquals(MeshPacket.TYPE_TEXT_MESSAGE, packet.type)
        assertEquals(5, packet.ttl)
        assertEquals(2, packet.hopCount)

        val encoded = packet.encode()
        assertNotNull(encoded)
        assertTrue(encoded.size > 7)

        val decoded = MeshPacket.decode(encoded)
        assertNotNull(decoded)
        assertEquals(MeshPacket.TYPE_TEXT_MESSAGE, decoded?.type)
        assertEquals(5, decoded?.ttl)
        assertEquals(2, decoded?.hopCount)

        val restoredChatMsg = decoded?.toChatMessage()
        assertNotNull(restoredChatMsg)
        assertEquals(chatMsg.messageId, restoredChatMsg?.messageId)
        assertEquals(chatMsg.senderId, restoredChatMsg?.senderId)
        assertEquals(chatMsg.recipientId, restoredChatMsg?.recipientId)
        assertEquals(chatMsg.senderNickname, restoredChatMsg?.senderNickname)
        assertEquals(chatMsg.content, restoredChatMsg?.content)
    }

    @Test
    fun testAckPacketEncodingAndDecoding() {
        val packet = MeshPacket.createAck(
            messageId = "msg-888",
            senderId = "bob-peer",
            recipientId = "alice-peer",
            ttl = 7
        )

        assertEquals(MeshPacket.TYPE_ACK, packet.type)
        assertEquals(7, packet.ttl)
        assertEquals(0, packet.hopCount)

        val encoded = packet.toByteArray()
        val decoded = MeshPacket.fromByteArray(encoded)
        assertNotNull(decoded)
        assertEquals(MeshPacket.TYPE_ACK, decoded?.type)

        val ackPayload = decoded?.toAck()
        assertNotNull(ackPayload)
        assertEquals("msg-888", ackPayload?.messageId)
        assertEquals("bob-peer", ackPayload?.senderId)
        assertEquals("alice-peer", ackPayload?.recipientId)
    }

    @Test
    fun testRoutingAnnouncementPacketEncodingAndDecoding() {
        val packet = MeshPacket.createRoutingAnnouncement(
            senderId = "router-1",
            nickname = "Node 1",
            ttl = 4
        )

        assertEquals(MeshPacket.TYPE_ROUTING_ANNOUNCEMENT, packet.type)
        assertEquals(4, packet.ttl)

        val encoded = packet.encode()
        val decoded = MeshPacket.decode(encoded)
        assertNotNull(decoded)

        val routingPayload = decoded?.toRoutingAnnouncement()
        assertNotNull(routingPayload)
        assertEquals("router-1", routingPayload?.senderId)
        assertEquals("Node 1", routingPayload?.nickname)
    }

    @Test
    fun testDecodeCorruptBytes() {
        assertNull(MeshPacket.decode(byteArrayOf()))
        assertNull(MeshPacket.decode(byteArrayOf(1, 2, 3)))
        
        // Header says payload length is 100 bytes, but array ends prematurely
        val truncatedHeader = byteArrayOf(
            MeshPacket.TYPE_TEXT_MESSAGE, // type
            7, // ttl
            0, // hopCount
            0, 0, 0, 100 // payload size = 100
        )
        assertNull(MeshPacket.decode(truncatedHeader))
    }

    @Test
    fun testEqualsAndHashCode() {
        val packet1 = MeshPacket(MeshPacket.TYPE_TEXT_MESSAGE, 7, 0, byteArrayOf(1, 2, 3))
        val packet2 = MeshPacket(MeshPacket.TYPE_TEXT_MESSAGE, 7, 0, byteArrayOf(1, 2, 3))
        val packet3 = MeshPacket(MeshPacket.TYPE_TEXT_MESSAGE, 7, 0, byteArrayOf(1, 2, 4))

        assertEquals(packet1, packet2)
        assertEquals(packet1.hashCode(), packet2.hashCode())
        assertTrue(packet1 != packet3)
    }
}
