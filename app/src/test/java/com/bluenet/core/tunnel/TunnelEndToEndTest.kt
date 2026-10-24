package com.bluenet.core.tunnel

import com.bluenet.core.tunnel.TunnelTestSupport.appSegment
import com.bluenet.core.tunnel.TunnelTestSupport.parseTcp
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Client and host wired back to back, with a scripted "app" talking TCP to a real echo server. */
class TunnelEndToEndTest {

    private val loop = Executors.newSingleThreadExecutor()
    private val tunOut = LinkedBlockingQueue<ByteArray>()
    private lateinit var host: TunnelHost
    private lateinit var server: ServerSocket

    @After
    fun tearDown() {
        host.close()
        server.close()
        loop.shutdownNow()
    }

    @Test
    fun echoThroughTheTunnel() {
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching { server.accept().use { it.getInputStream().copyTo(it.getOutputStream()) } }
        }

        lateinit var client: TunnelClient
        host = TunnelHost(link = { client.onFrame(TunnelFrame.decode(it.encode())!!); true }, policy = DestinationPolicy.ALLOW_ALL)
        client = TunnelClient(tun = { tunOut.put(it) }, link = { host.onFrame(TunnelFrame.decode(it.encode())!!); true }, executor = loop)

        val dst = Ipv4.addressInt(byteArrayOf(127, 0, 0, 1))
        val port = server.localPort
        fun send(packet: ByteArray) = client.onTunPacket(packet, packet.size)
        fun next(): TcpSegment = parseTcp(tunOut.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("tunnel stalled"))

        val iss = 77
        send(appSegment(dst, port, iss, 0, TcpSegment.SYN, mss = 1460))
        val synAck = next()
        var appSeq = iss + 1
        var appAck = synAck.seq + 1
        send(appSegment(dst, port, appSeq, appAck, TcpSegment.ACK))

        val request = ByteArray(200_000) { (it * 31).toByte() }
        var offset = 0
        val echoed = ByteArrayOutputStream()
        while (echoed.size() < request.size) {
            if (offset < request.size && appSeq - (iss + 1) - offset == 0) {
                val n = minOf(1_400, request.size - offset)
                send(appSegment(dst, port, appSeq, appAck, TcpSegment.ACK or TcpSegment.PSH, request.copyOfRange(offset, offset + n)))
            }
            val segment = next()
            if (segment.ack - appSeq > 0) {
                offset += segment.ack - appSeq
                appSeq = segment.ack
            }
            if (segment.payload.isNotEmpty() && segment.seq == appAck) {
                echoed.write(segment.payload)
                appAck += segment.payload.size
                send(appSegment(dst, port, appSeq, appAck, TcpSegment.ACK))
            }
        }
        assertArrayEquals(request, echoed.toByteArray())
    }
}
