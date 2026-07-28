package com.bluenet.messaging

import com.bluenet.mesh.MeshTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshRouterTest {

    private class MockTransport : MeshTransport {
        val sentPackets = mutableListOf<Pair<ByteArray, String?>>()
        val broadcastPackets = mutableListOf<ByteArray>()
        var connectedPeersList = mutableListOf<String>()
        private var listener: ((ByteArray, String) -> Unit)? = null

        override fun sendPacket(data: ByteArray, targetPeerId: String?): Boolean {
            sentPackets.add(Pair(data, targetPeerId))
            return true
        }

        override fun broadcastPacket(data: ByteArray): Boolean {
            broadcastPackets.add(data)
            return true
        }

        override fun setPacketListener(listener: (ByteArray, String) -> Unit) {
            this.listener = listener
        }

        override fun getConnectedPeers(): List<String> {
            return connectedPeersList
        }

        fun injectIncomingPacket(data: ByteArray, fromPeerId: String) {
            listener?.invoke(data, fromPeerId)
        }
    }

    private lateinit var storeA: MessageStore
    private lateinit var storeB: MessageStore
    private lateinit var storeC: MessageStore

    private lateinit var transportA: MockTransport
    private lateinit var transportB: MockTransport
    private lateinit var transportC: MockTransport

    private lateinit var routerA: MeshRouter
    private lateinit var routerB: MeshRouter
    private lateinit var routerC: MeshRouter

    @Before
    fun setUp() {
        storeA = MessageStore()
        storeB = MessageStore()
        storeC = MessageStore()

        transportA = MockTransport()
        transportB = MockTransport()
        transportC = MockTransport()

        routerA = MeshRouter("nodeA", storeA, transportA)
        routerB = MeshRouter("nodeB", storeB, transportB)
        routerC = MeshRouter("nodeC", storeC, transportC)
    }

    @Test
    fun testDeduplication() {
        val chatMsg = ChatMessage(
            messageId = "dup-msg-1",
            senderId = "nodeA",
            recipientId = "nodeB",
            senderNickname = "Alice",
            content = "Duplicate test"
        )
        val packet = MeshPacket.createTextMessage(chatMsg)

        // First processing should succeed
        val processedFirst = routerB.processIncomingPacket(packet, fromPeerId = "nodeA")
        assertTrue(processedFirst)
        assertTrue(routerB.isMessageSeen("dup-msg-1"))

        // Second processing of identical packet should be dropped as duplicate
        val processedSecond = routerB.processIncomingPacket(packet, fromPeerId = "nodeA")
        assertFalse(processedSecond)
    }

    @Test
    fun testTtlDecrementAndHopCountIncrementOnForwarding() {
        val chatMsg = ChatMessage(
            messageId = "hop-msg-1",
            senderId = "nodeA",
            recipientId = "nodeC",
            senderNickname = "Alice",
            content = "Multi-hop routing",
            ttl = 7,
            hopCount = 0
        )
        val packet = MeshPacket.createTextMessage(chatMsg)

        var forwardedPacketCaptured: MeshPacket? = null
        routerB.onForwardedPacket = { pkt, _ ->
            forwardedPacketCaptured = pkt
        }

        // Node B receives packet intended for Node C
        val processed = routerB.processIncomingPacket(packet, fromPeerId = "nodeA")
        assertTrue(processed)

        assertNotNull(forwardedPacketCaptured)
        assertEquals(6, forwardedPacketCaptured?.ttl)
        assertEquals(1, forwardedPacketCaptured?.hopCount)
    }

    @Test
    fun testTtlZeroDrop() {
        val chatMsg = ChatMessage(
            messageId = "zero-ttl-msg",
            senderId = "nodeA",
            recipientId = "nodeB",
            senderNickname = "Alice",
            content = "Will drop",
            ttl = 0,
            hopCount = 7
        )
        val packet = MeshPacket.createTextMessage(chatMsg)

        val processed = routerB.processIncomingPacket(packet, fromPeerId = "nodeA")
        assertFalse(processed)
        assertNull(storeB.getMessage("zero-ttl-msg"))
    }

    @Test
    fun testDirectRecipient1to1RoutingAndAckGeneration() {
        val chatMsg = ChatMessage(
            messageId = "direct-1to1-msg",
            senderId = "nodeA",
            recipientId = "nodeB",
            senderNickname = "Alice",
            content = "Direct 1:1 message"
        )
        val packet = MeshPacket.createTextMessage(chatMsg)

        var receivedMessage: ChatMessage? = null
        routerB.onMessageReceived = { msg ->
            receivedMessage = msg
        }

        transportB.connectedPeersList.add("nodeA")

        val processed = routerB.processIncomingPacket(packet, fromPeerId = "nodeA")
        assertTrue(processed)

        assertNotNull(receivedMessage)
        assertEquals("direct-1to1-msg", receivedMessage?.messageId)
        assertEquals(MessageStatus.RECEIVED, receivedMessage?.status)
        assertEquals(MessageStatus.RECEIVED, storeB.getMessage("direct-1to1-msg")?.status)

        // Verify ACK was generated and sent back to nodeA
        assertTrue(transportB.sentPackets.isNotEmpty() || transportB.broadcastPackets.isNotEmpty())
        val ackDataBytes = if (transportB.sentPackets.isNotEmpty()) {
            transportB.sentPackets.first().first
        } else {
            transportB.broadcastPackets.first()
        }
        val ackPacket = MeshPacket.decode(ackDataBytes)
        assertNotNull(ackPacket)
        assertEquals(MeshPacket.TYPE_ACK, ackPacket?.type)
        
        val ackPayload = ackPacket?.toAck()
        assertEquals("direct-1to1-msg", ackPayload?.messageId)
        assertEquals("nodeB", ackPayload?.senderId)
        assertEquals("nodeA", ackPayload?.recipientId)
    }

    @Test
    fun testMultiHopForwardingThroughIntermediatePeers() {
        // Wire virtual transport connections between nodes:
        // routerA sends -> routerB receives
        // routerB sends -> routerC receives
        // routerC sends ACK -> routerB receives -> routerA receives
        transportA.connectedPeersList.add("nodeB")
        transportB.connectedPeersList.addAll(listOf("nodeA", "nodeC"))
        transportC.connectedPeersList.add("nodeB")

        // 1. Node A originates a 1:1 message to Node C
        val msg = routerA.sendTextMessage(
            recipientId = "nodeC",
            content = "Hello Node C via Node B!",
            senderNickname = "Alice"
        )

        assertEquals(MessageStatus.SENT, storeA.getMessage(msg.messageId)?.status)
        assertTrue(transportA.broadcastPackets.isNotEmpty() || transportA.sentPackets.isNotEmpty())

        val packetDataA = if (transportA.sentPackets.isNotEmpty()) {
            transportA.sentPackets.first().first
        } else {
            transportA.broadcastPackets.first()
        }

        // 2. Node B receives packet from Node A
        var forwardedDataByB: ByteArray? = null
        routerB.onForwardedPacket = { pkt, _ ->
            forwardedDataByB = pkt.encode()
        }

        val processedByB = routerB.processIncomingBytes(packetDataA, fromPeerId = "nodeA")
        assertTrue(processedByB)
        assertNotNull(forwardedDataByB)

        // 3. Node C receives forwarded packet from Node B
        var messageReceivedByC: ChatMessage? = null
        routerC.onMessageReceived = { receivedMsg ->
            messageReceivedByC = receivedMsg
        }

        val processedByC = routerC.processIncomingBytes(forwardedDataByB!!, fromPeerId = "nodeB")
        assertTrue(processedByC)

        assertNotNull(messageReceivedByC)
        assertEquals(msg.messageId, messageReceivedByC?.messageId)
        assertEquals("Hello Node C via Node B!", messageReceivedByC?.content)
        assertEquals(MessageStatus.RECEIVED, messageReceivedByC?.status)
        assertEquals(6, messageReceivedByC?.ttl)
        assertEquals(1, messageReceivedByC?.hopCount)

        // 4. Node C generated ACK for Node A and sent it to Node B
        val ackDataC = if (transportC.sentPackets.isNotEmpty()) {
            transportC.sentPackets.first().first
        } else {
            transportC.broadcastPackets.first()
        }

        var ackForwardedDataByB: ByteArray? = null
        routerB.onForwardedPacket = { pkt, _ ->
            ackForwardedDataByB = pkt.encode()
        }

        val ackProcessedByB = routerB.processIncomingBytes(ackDataC, fromPeerId = "nodeC")
        assertTrue(ackProcessedByB)
        assertNotNull(ackForwardedDataByB)

        // 5. Node A receives forwarded ACK from Node B
        var ackReceivedIdByA: String? = null
        routerA.onAckReceived = { ackId ->
            ackReceivedIdByA = ackId
        }

        val ackProcessedByA = routerA.processIncomingBytes(ackForwardedDataByB!!, fromPeerId = "nodeB")
        assertTrue(ackProcessedByA)

        assertEquals(msg.messageId, ackReceivedIdByA)
        // Verify Node A's MessageStore status updated to DELIVERED
        assertEquals(MessageStatus.DELIVERED, storeA.getMessage(msg.messageId)?.status)
    }

    @Test
    fun testBroadcastMessageFlooding() {
        var broadcastReceivedByB: ChatMessage? = null
        routerB.onMessageReceived = { msg ->
            broadcastReceivedByB = msg
        }

        val msg = routerA.sendTextMessage(
            recipientId = "*",
            content = "Mesh-wide announcement",
            senderNickname = "Alice"
        )

        val packetData = if (transportA.broadcastPackets.isNotEmpty()) {
            transportA.broadcastPackets.first()
        } else {
            transportA.sentPackets.first().first
        }

        var forwardedByB = false
        routerB.onForwardedPacket = { _, _ ->
            forwardedByB = true
        }

        val processedByB = routerB.processIncomingBytes(packetData, fromPeerId = "nodeA")
        assertTrue(processedByB)

        assertNotNull(broadcastReceivedByB)
        assertEquals(msg.messageId, broadcastReceivedByB?.messageId)
        assertTrue(forwardedByB)
    }

    @Test
    fun testLRUCacheLimit() {
        val smallRouter = MeshRouter("testNode", storeB, transportB, maxHops = 7, lruCapacity = 5)
        for (i in 1..10) {
            smallRouter.markMessageSeen("msg-$i")
        }

        // Cache size is capped at 5, so earlier entries (1..5) should be evicted
        assertFalse(smallRouter.isMessageSeen("msg-1"))
        assertFalse(smallRouter.isMessageSeen("msg-5"))
        assertTrue(smallRouter.isMessageSeen("msg-6"))
        assertTrue(smallRouter.isMessageSeen("msg-10"))
    }
}
