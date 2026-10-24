package com.bluenet.core.tunnel

import com.bluenet.core.tunnel.TunnelTestSupport.APP_IP
import com.bluenet.core.tunnel.TunnelTestSupport.APP_PORT
import com.bluenet.core.tunnel.TunnelTestSupport.appSegment
import com.bluenet.core.tunnel.TunnelTestSupport.parseTcp
import com.bluenet.core.tunnel.TunnelTestSupport.parseUdp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class TunnelClientTest {

    private val server = Ipv4.addressInt(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
    private val serverPort = 443
    private val appIss = 1_000

    private var now = 0L
    private val tunOut = ArrayDeque<ByteArray>()
    private val frames = ArrayDeque<TunnelFrame>()
    private lateinit var client: TunnelClient

    @Before
    fun setUp() {
        client = TunnelClient(
            tun = { tunOut.addLast(it) },
            link = { frames.addLast(it); true },
            executor = { it.run() },
            clock = { now },
        )
    }

    private fun send(packet: ByteArray) = client.onTunPacket(packet, packet.size)

    private fun app(seq: Int, ack: Int, flags: Int, payload: ByteArray = ByteArray(0), window: Int = 65_535, mss: Int? = null) =
        send(appSegment(server, serverPort, seq, ack, flags, payload, window, mss))

    private fun nextTcp(): TcpSegment = parseTcp(tunOut.removeFirst())

    /** Completes the three-way handshake and returns (streamId, server ISS). */
    private fun handshake(window: Int = 65_535, mss: Int = 1460): Pair<Int, Int> {
        app(appIss, 0, TcpSegment.SYN, window = window, mss = mss)
        val open = frames.removeFirst()
        client.onFrame(TunnelFrame.openOk(open.streamId))
        val synAck = nextTcp()
        app(appIss + 1, synAck.seq + 1, TcpSegment.ACK, window = window)
        return open.streamId to synAck.seq
    }

    @Test
    fun synIsAnsweredOnlyAfterTheSharerConnects() {
        app(appIss, 0, TcpSegment.SYN, mss = 1400)

        val open = frames.removeFirst()
        assertEquals(TunnelFrame.OPEN_TCP, open.type)
        assertEquals(serverPort, open.port)
        assertArrayEquals(Ipv4.addressBytes(server), open.address)
        assertTrue("No SYN-ACK before the real connection exists", tunOut.isEmpty())

        app(appIss, 0, TcpSegment.SYN, mss = 1400)
        assertTrue("Retransmitted SYN while opening is ignored", tunOut.isEmpty() && frames.isEmpty())

        client.onFrame(TunnelFrame.openOk(open.streamId))
        val synAck = nextTcp()
        assertEquals(TcpSegment.SYN or TcpSegment.ACK, synAck.flags)
        assertEquals(appIss + 1, synAck.ack)
        assertEquals(TunnelClient.OUR_MSS, synAck.mss)
        assertEquals(server, synAck.source)
        assertEquals(APP_IP, synAck.destination)
        assertEquals(serverPort, synAck.sourcePort)
        assertEquals(APP_PORT, synAck.destinationPort)
    }

    @Test
    fun failedConnectResetsTheApp() {
        app(appIss, 0, TcpSegment.SYN)
        val open = frames.removeFirst()
        client.onFrame(TunnelFrame.openFail(open.streamId, TunnelFrame.FAIL_UNREACHABLE))

        val rst = nextTcp()
        assertTrue(rst.has(TcpSegment.RST))
        assertEquals(appIss + 1, rst.ack)
        assertEquals(0, client.openTcpFlows)
    }

    @Test
    fun appDataIsForwardedExactlyOnceAndAcknowledged() {
        val (stream, iss) = handshake()
        val hello = "hello".toByteArray()

        app(appIss + 1, iss + 1, TcpSegment.ACK or TcpSegment.PSH, hello)
        val data = frames.removeFirst()
        assertEquals(TunnelFrame.DATA, data.type)
        assertEquals(stream, data.streamId)
        assertArrayEquals(hello, data.payload)
        assertEquals(appIss + 1 + hello.size, nextTcp().ack)

        app(appIss + 1, iss + 1, TcpSegment.ACK or TcpSegment.PSH, hello)
        assertTrue("A retransmitted segment must not be forwarded again", frames.isEmpty())
        assertEquals(appIss + 1 + hello.size, nextTcp().ack)

        app(appIss + 100, iss + 1, TcpSegment.ACK or TcpSegment.PSH, "later".toByteArray())
        assertTrue("Out-of-order data is dropped", frames.isEmpty())
        assertEquals("Duplicate ACK asks for the missing bytes", appIss + 1 + hello.size, nextTcp().ack)

        app(appIss + 3, iss + 1, TcpSegment.ACK or TcpSegment.PSH, "llo world".toByteArray())
        assertArrayEquals("Only the new tail of an overlapping segment is forwarded", " world".toByteArray(), frames.removeFirst().payload)
    }

    @Test
    fun sharerDataIsSegmentedToMssAndWindow() {
        val (stream, iss) = handshake(window = 3_000, mss = 1_000)
        val body = ByteArray(5_000) { it.toByte() }

        client.onFrame(TunnelFrame.data(stream, body))
        val firstBurst = (0 until 3).map { nextTcp() }
        assertTrue("Window of 3000 allows exactly three 1000-byte segments", tunOut.isEmpty())
        assertEquals(listOf(1_000, 1_000, 1_000), firstBurst.map { it.payload.size })
        assertEquals(listOf(iss + 1, iss + 1_001, iss + 2_001), firstBurst.map { it.seq })

        app(appIss + 1, iss + 3_001, TcpSegment.ACK, window = 3_000)
        val rest = generateSequence { tunOut.removeFirstOrNull()?.let(::parseTcp) }.toList()
        assertEquals(listOf(1_000, 1_000), rest.map { it.payload.size })

        val received = ByteArrayOutputStream()
        (firstBurst + rest).forEach { received.write(it.payload) }
        assertArrayEquals(body, received.toByteArray())
    }

    @Test
    fun acknowledgedDataReturnsCreditToTheSharer() {
        val (stream, iss) = handshake()
        val chunk = ByteArray(TunnelFrame.MAX_DATA)
        repeat(3) { client.onFrame(TunnelFrame.data(stream, chunk)) }
        tunOut.clear()

        app(appIss + 1, iss + 1 + chunk.size * 3, TcpSegment.ACK)

        val grant = frames.single { it.type == TunnelFrame.WINDOW }
        assertEquals(chunk.size * 3, grant.windowIncrement)
    }

    @Test
    fun lostSegmentsAreRetransmittedAfterTimeout() {
        val (stream, iss) = handshake()
        client.onFrame(TunnelFrame.data(stream, "response".toByteArray()))
        val original = nextTcp()

        now += TunnelClient.INITIAL_RTO_MS - 1
        client.tick()
        assertTrue(tunOut.isEmpty())

        now += 1
        client.tick()
        val retransmitted = nextTcp()
        assertEquals(original.seq, retransmitted.seq)
        assertArrayEquals(original.payload, retransmitted.payload)

        app(appIss + 1, iss + 1 + original.payload.size, TcpSegment.ACK)
        now += 60_000
        client.tick()
        assertTrue("Nothing left to retransmit once acknowledged", tunOut.isEmpty())
    }

    @Test
    fun closeHandshakeInBothDirections() {
        val (stream, iss) = handshake()

        app(appIss + 1, iss + 1, TcpSegment.FIN or TcpSegment.ACK)
        assertEquals(TunnelFrame.FIN, frames.removeFirst().type)
        assertEquals(appIss + 2, nextTcp().ack)

        client.onFrame(TunnelFrame.data(stream, "bye".toByteArray()))
        client.onFrame(TunnelFrame.fin(stream))
        val data = nextTcp()
        assertArrayEquals("bye".toByteArray(), data.payload)
        val fin = nextTcp()
        assertTrue(fin.has(TcpSegment.FIN))
        assertEquals(iss + 4, fin.seq)

        app(appIss + 2, fin.seq + 1, TcpSegment.ACK)
        assertEquals(0, client.openTcpFlows)
    }

    @Test
    fun exhaustedUpstreamCreditClosesTheWindowUntilGranted() {
        val (stream, iss) = handshake()
        var seq = appIss + 1
        var forwarded = 0
        var lastWindow = -1
        while (forwarded < TunnelFrame.INITIAL_WINDOW + 10_000) {
            app(seq, iss + 1, TcpSegment.ACK, ByteArray(1_400))
            val ack = nextTcp()
            lastWindow = ack.window
            val accepted = ack.ack - seq
            frames.removeAll { forwarded += it.payload.size; true }
            if (accepted == 0) break
            seq = ack.ack
        }
        assertEquals(TunnelFrame.INITIAL_WINDOW, forwarded)
        assertEquals(0, lastWindow)

        client.onFrame(TunnelFrame.window(stream, 32_768))
        assertEquals("Window update tells the app it may send again", 32_768, nextTcp().window)
    }

    @Test
    fun udpDatagramsRoundTrip() {
        val dns = Ipv4.addressInt(byteArrayOf(1, 1, 1, 1))
        val query = byteArrayOf(1, 2, 3, 4)
        send(UdpDatagram.build(APP_IP, 53_000, dns, 53, query))

        val open = frames.removeFirst()
        assertEquals(TunnelFrame.OPEN_UDP, open.type)
        assertEquals(53, open.port)
        assertArrayEquals(query, frames.removeFirst().payload)

        client.onFrame(TunnelFrame.data(open.streamId, byteArrayOf(9, 9)))
        val reply = parseUdp(tunOut.removeFirst())
        assertEquals(dns, reply.source)
        assertEquals(53, reply.sourcePort)
        assertEquals(53_000, reply.destinationPort)
        assertArrayEquals(byteArrayOf(9, 9), reply.payload)

        now += 60_000
        client.tick()
        assertEquals(TunnelFrame.RESET, frames.removeFirst().type)
        assertEquals(0, client.openUdpFlows)
    }

    @Test
    fun segmentsForUnknownConnectionsAreReset() {
        app(5_000, 7_000, TcpSegment.ACK, "stray".toByteArray())
        val rst = nextTcp()
        assertTrue(rst.has(TcpSegment.RST))
        assertEquals(7_000, rst.seq)
        assertTrue(frames.isEmpty())
    }
}
