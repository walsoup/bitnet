package com.bluenet.core.link

import com.bluenet.core.crypto.Identity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SecureLinkTest {

    private val executor = Executors.newCachedThreadPool()
    private val sockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }

    private class SocketTransport(val socket: Socket) : LinkTransport {
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override fun close() = socket.close()
    }

    private fun socketPair(): Pair<LinkTransport, LinkTransport> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val accepted = server.accept()
            sockets += client
            sockets += accepted
            return SocketTransport(client) to SocketTransport(accepted)
        }
    }

    private class RecordingListener : SecureLink.Listener {
        val records = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        val closed = CountDownLatch(1)
        override fun onRecord(link: SecureLink, channel: Int, body: ByteArray) {
            records.put(channel to body)
        }
        override fun onClosed(link: SecureLink, cause: Throwable?) = closed.countDown()
    }

    @Test
    fun handshakeAuthenticatesBothSidesAndCarriesRecords() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val (a, b) = socketPair()

        val bobSide = executor.submit<SecureLink> { SecureLink.accept(b, bob) }
        val aliceLink = SecureLink.connect(a, alice, expectedPeerId = bob.peerId)
        val bobLink = bobSide.get(10, TimeUnit.SECONDS)

        assertEquals(bob.peerId, aliceLink.remotePeerId)
        assertEquals(alice.peerId, bobLink.remotePeerId)
        assertTrue(aliceLink.initiatedLocally)
        assertFalse(bobLink.initiatedLocally)

        val aliceListener = RecordingListener()
        val bobListener = RecordingListener()
        aliceLink.start(aliceListener)
        bobLink.start(bobListener)

        val big = ByteArray(SecureLink.MAX_BODY) { (it % 251).toByte() }
        assertTrue(aliceLink.send(Channels.MESH, "hello bob".toByteArray()))
        assertTrue(aliceLink.send(Channels.TUNNEL_UP, big))
        assertTrue(bobLink.send(Channels.TUNNEL_DOWN, "hi alice".toByteArray()))

        val first = bobListener.records.poll(5, TimeUnit.SECONDS)!!
        assertEquals(Channels.MESH, first.first)
        assertArrayEquals("hello bob".toByteArray(), first.second)
        val second = bobListener.records.poll(5, TimeUnit.SECONDS)!!
        assertEquals(Channels.TUNNEL_UP, second.first)
        assertArrayEquals(big, second.second)
        val reply = aliceListener.records.poll(5, TimeUnit.SECONDS)!!
        assertEquals(Channels.TUNNEL_DOWN, reply.first)
        assertArrayEquals("hi alice".toByteArray(), reply.second)

        aliceLink.close()
        assertTrue(bobListener.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun connectFailsWhenPeerIsNotWhoWeExpected() {
        val alice = Identity.generate()
        val impostor = Identity.generate()
        val expected = Identity.generate()
        val (a, b) = socketPair()

        executor.submit { runCatching { SecureLink.accept(b, impostor) } }
        try {
            SecureLink.connect(a, alice, expectedPeerId = expected.peerId)
            fail("Handshake with the wrong peer must fail")
        } catch (_: IOException) {
        }
    }

    @Test
    fun tamperedRecordClosesTheLink() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val (a, b) = socketPair()
        val bobSide = executor.submit<SecureLink> { SecureLink.accept(b, bob) }
        SecureLink.connect(a, alice, expectedPeerId = bob.peerId)
        val bobLink = bobSide.get(10, TimeUnit.SECONDS)
        val listener = RecordingListener()
        bobLink.start(listener)

        // Write a well-framed record with garbage ciphertext straight onto the socket.
        val raw = a.output
        raw.write(byteArrayOf(0, 0, 0, 40))
        raw.write(ByteArray(40) { 7 })
        raw.flush()

        assertTrue(listener.closed.await(5, TimeUnit.SECONDS))
        assertFalse(bobLink.isOpen)
        assertTrue(listener.records.isEmpty())
    }
}
