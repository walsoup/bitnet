package com.bluenet.core.tunnel

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TunnelHostTest {

    private val loopback = byteArrayOf(127, 0, 0, 1)
    private val frames = LinkedBlockingQueue<TunnelFrame>()
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = closeables.forEach { runCatching { it.close() } }

    private fun host(policy: DestinationPolicy = DestinationPolicy.ALLOW_ALL) =
        TunnelHost(link = { frames.put(it); true }, policy = policy).also { h -> closeables += AutoCloseable { h.close() } }

    private fun nextFrame(): TunnelFrame = frames.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("No frame within 5s")

    private fun echoServer(): Int {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        closeables += server
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.getInputStream().copyTo(socket.getOutputStream())
                }
            }
        }
        return server.localPort
    }

    @Test
    fun dataSentWhileConnectingIsDeliveredInOrder() {
        val port = echoServer()
        val host = host()
        val expected = ByteArrayOutputStream()

        host.onFrame(TunnelFrame.openTcp(1, loopback, port))
        repeat(200) { i ->
            val chunk = "chunk-$i;".toByteArray()
            expected.write(chunk)
            host.onFrame(TunnelFrame.data(1, chunk))
        }

        assertEquals(TunnelFrame.OPEN_OK, nextFrame().type)
        val echoed = ByteArrayOutputStream()
        while (echoed.size() < expected.size()) {
            val frame = nextFrame()
            if (frame.type == TunnelFrame.DATA) echoed.write(frame.payload)
        }
        assertArrayEquals(expected.toByteArray(), echoed.toByteArray())

        host.onFrame(TunnelFrame.fin(1))
        var sawFin = false
        while (!sawFin) sawFin = nextFrame().type == TunnelFrame.FIN
        val deadline = System.currentTimeMillis() + 5_000
        while (host.streamCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("Stream is released once both directions are closed", 0, host.streamCount)
    }

    @Test
    fun refusedConnectionReportsOpenFail() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        host().onFrame(TunnelFrame.openTcp(7, loopback, closedPort))

        val frame = nextFrame()
        assertEquals(TunnelFrame.OPEN_FAIL, frame.type)
        assertEquals(7, frame.streamId)
        assertEquals(TunnelFrame.FAIL_UNREACHABLE, frame.failReason)
    }

    @Test
    fun defaultPolicyBlocksLoopbackAndPrivateNetworks() {
        val host = host(DestinationPolicy.PUBLIC_INTERNET_ONLY)
        host.onFrame(TunnelFrame.openTcp(1, loopback, 80))
        host.onFrame(TunnelFrame.openTcp(2, byteArrayOf(192.toByte(), 168.toByte(), 1, 1), 80))
        host.onFrame(TunnelFrame.openUdp(3, byteArrayOf(10, 0, 0, 1), 53))

        repeat(3) {
            val frame = nextFrame()
            assertEquals(TunnelFrame.OPEN_FAIL, frame.type)
            assertEquals(TunnelFrame.FAIL_BLOCKED, frame.failReason)
        }
        assertTrue(DestinationPolicy.PUBLIC_INTERNET_ONLY.allows(byteArrayOf(1, 1, 1, 1), 53))
        assertTrue(DestinationPolicy.isNonPublic(byteArrayOf(100, 64, 0, 1)))
        assertTrue(DestinationPolicy.isNonPublic(byteArrayOf(172.toByte(), 20, 0, 1)))
    }

    @Test
    fun readsStopWhenTheClientRunsOutOfCredit() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        closeables += server
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { it.getOutputStream().write(ByteArray(1_000_000)) }
            }
        }
        val host = host()
        host.onFrame(TunnelFrame.openTcp(1, loopback, server.localPort))
        assertEquals(TunnelFrame.OPEN_OK, nextFrame().type)

        var received = 0
        while (true) {
            val frame = frames.poll(500, TimeUnit.MILLISECONDS) ?: break
            if (frame.type == TunnelFrame.DATA) received += frame.payload.size
        }
        assertEquals(TunnelFrame.INITIAL_WINDOW, received)

        host.onFrame(TunnelFrame.window(1, 50_000))
        var more = 0
        while (true) {
            val frame = frames.poll(500, TimeUnit.MILLISECONDS) ?: break
            if (frame.type == TunnelFrame.DATA) more += frame.payload.size
        }
        assertEquals(50_000, more)
    }

    @Test
    fun udpDatagramsAreRelayed() {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        closeables += socket
        thread(isDaemon = true) {
            runCatching {
                val buffer = ByteArray(1500)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                socket.send(DatagramPacket(packet.data.copyOf(packet.length).reversedArray(), packet.length, packet.socketAddress))
            }
        }
        val host = host()
        host.onFrame(TunnelFrame.openUdp(5, loopback, socket.localPort))
        host.onFrame(TunnelFrame.data(5, byteArrayOf(1, 2, 3)))

        val reply = nextFrame()
        assertEquals(TunnelFrame.DATA, reply.type)
        assertArrayEquals(byteArrayOf(3, 2, 1), reply.payload)
    }

    @Test
    fun framesForUnknownStreamsAreReset() {
        host().onFrame(TunnelFrame.data(42, byteArrayOf(1)))
        val frame = nextFrame()
        assertEquals(TunnelFrame.RESET, frame.type)
        assertEquals(42, frame.streamId)
    }
}
