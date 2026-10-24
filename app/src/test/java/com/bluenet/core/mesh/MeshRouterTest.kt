package com.bluenet.core.mesh

import com.bluenet.core.crypto.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterTest {

    /** In-memory mesh: nodes exchange packets synchronously over explicit neighbor edges. */
    private class Network {
        val nodes = LinkedHashMap<String, Node>()
        val edges = HashMap<String, MutableSet<String>>()
        var delivered = 0

        fun add(name: String): Node = Node(this, Identity.generate()).also { nodes[name] = it }

        fun connect(a: Node, b: Node) {
            edges.getOrPut(a.id) { mutableSetOf() } += b.id
            edges.getOrPut(b.id) { mutableSetOf() } += a.id
        }

        fun byId(id: String): Node = nodes.values.first { it.id == id }
    }

    private class Node(val network: Network, val identity: Identity) : MeshRouter.Listener {
        val id = identity.peerId
        val texts = mutableListOf<ChatMessage>()
        val delivered = mutableListOf<String>()
        val announces = mutableListOf<Pair<String, Int>>()

        val router: MeshRouter = MeshRouter(identity, object : MeshTransport {
            override fun neighbors(): Set<String> = network.edges[id].orEmpty()
            override fun sendTo(peerId: String, packet: ByteArray): Boolean {
                network.delivered++
                network.byId(peerId).router.onPacket(packet, id)
                return true
            }
            override fun broadcast(packet: ByteArray, exceptPeerId: String?) {
                neighbors().filter { it != exceptPeerId }.forEach { sendTo(it, packet) }
            }
        }).also { it.listener = this }

        override fun onText(message: ChatMessage) { texts += message }
        override fun onDelivered(messageId: String) { delivered += messageId }
        override fun onAnnounce(peerId: String, nickname: String, isSharingInternet: Boolean, hops: Int) {
            announces += peerId to hops
        }
    }

    @Test
    fun directMessageCrossesTwoHopsAndIsAcknowledged() {
        val net = Network()
        val a = net.add("a"); val b = net.add("b"); val c = net.add("c")
        net.connect(a, b); net.connect(b, c)

        val sent = a.router.sendText(c.id, "alice", "hello carol")

        assertEquals(1, c.texts.size)
        val received = c.texts.single()
        assertEquals("hello carol", received.text)
        assertEquals("alice", received.nickname)
        assertEquals(a.id, received.senderId)
        assertEquals(2, received.hops)
        assertEquals(sent.id, received.id)
        assertTrue("Relay must not see a message addressed to someone else", b.texts.isEmpty())
        assertEquals(listOf(sent.id), a.delivered)
    }

    @Test
    fun broadcastReachesEveryoneOnceDespiteCycles() {
        val net = Network()
        val a = net.add("a"); val b = net.add("b"); val c = net.add("c"); val d = net.add("d")
        net.connect(a, b); net.connect(b, c); net.connect(c, a); net.connect(c, d)

        a.router.sendText(null, "alice", "hi all")

        assertEquals(1, b.texts.size)
        assertEquals(1, c.texts.size)
        assertEquals(1, d.texts.size)
        assertTrue(a.texts.isEmpty())
        assertTrue("Broadcasts are not acknowledged", a.delivered.isEmpty())
    }

    @Test
    fun ttlLimitsHowFarPacketsTravel() {
        val net = Network()
        val line = (0 until 10).map { net.add("n$it") }
        line.zipWithNext().forEach { (x, y) -> net.connect(x, y) }

        line.first().router.sendText(null, "n0", "ping")

        val reached = line.drop(1).count { it.texts.isNotEmpty() }
        assertEquals(MeshRouter.DEFAULT_MAX_HOPS, reached)
    }

    @Test
    fun announcementsArePropagatedEveryTime() {
        val net = Network()
        val a = net.add("a"); val b = net.add("b"); val c = net.add("c")
        net.connect(a, b); net.connect(b, c)

        a.router.announce("alice", isSharingInternet = true)
        a.router.announce("alice", isSharingInternet = true)

        assertEquals(listOf(a.id to 2, a.id to 2), c.announces)
    }

    @Test
    fun relaysCannotForgeOrAlterPackets() {
        val alice = Identity.generate()
        val packet = MeshPacket.create(alice, MeshPacket.TYPE_TEXT, null, MeshPayloads.encodeText("alice", "real"), 7, System.currentTimeMillis())
        val encoded = packet.encode()

        val tampered = encoded.copyOf()
        val textOffset = encoded.size - 65 - 1 - packet.signature.size - 1
        tampered[textOffset] = (tampered[textOffset] + 1).toByte()
        assertFalse(MeshPacket.decode(tampered)!!.verify())

        assertTrue(MeshPacket.decode(encoded)!!.verify())
        assertTrue("TTL and hop count are mutable in transit", MeshPacket.decode(packet.forwarded().encode())!!.verify())

        val net = Network()
        val victim = net.add("victim")
        victim.router.onPacket(tampered, "someone")
        assertTrue(victim.texts.isEmpty())
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(MeshPacket.decode(ByteArray(0)))
        assertNull(MeshPacket.decode(ByteArray(10) { 2 }))
        assertNull(MeshPacket.decode(ByteArray(400) { 2 }))
    }

    @Test
    fun textPayloadKeepsUnicodeAndTruncatesNicknameSafely() {
        val payload = MeshPayloads.encodeText("😀".repeat(20), "héllo 👋")
        val decoded = MeshPayloads.decodeText(payload)
        assertNotNull(decoded)
        assertEquals("héllo 👋", decoded!!.text)
        assertEquals("😀".repeat(8), decoded.nickname)
    }

    @Test
    fun messageStoreTracksDeliveryAndConversations() {
        val store = MessageStore(capacity = 3)
        val me = "1111111111111111"
        val bob = "2222222222222222"
        store.add(ChatMessage("m1", me, bob, "me", "hi", 1, MessageStatus.SENT, 0, outgoing = true))
        store.add(ChatMessage("m2", bob, me, "bob", "yo", 2, MessageStatus.RECEIVED, 1, outgoing = false))
        store.add(ChatMessage("m3", bob, null, "bob", "all", 3, MessageStatus.RECEIVED, 1, outgoing = false))
        store.markDelivered("m1")

        assertEquals(listOf("m1", "m2"), store.conversation(bob).map { it.id })
        assertEquals(MessageStatus.DELIVERED, store.conversation(bob).first().status)
        assertEquals(listOf("m3"), store.conversation(ChatMessage.BROADCAST_CONVERSATION).map { it.id })

        store.add(ChatMessage("m4", bob, null, "bob", "more", 4, MessageStatus.RECEIVED, 1, outgoing = false))
        assertEquals(3, store.messages.value.size)
        assertFalse(store.messages.value.any { it.id == "m1" })
    }

    @Test
    fun ackThatArrivesBeforeTheMessageIsStoredStillMarksItDelivered() {
        val store = MessageStore()
        store.markDelivered("early")
        store.add(ChatMessage("early", "1111111111111111", "2222222222222222", "me", "hi", 1, MessageStatus.SENT, 0, outgoing = true))
        assertEquals(MessageStatus.DELIVERED, store.messages.value.single().status)
    }
}
