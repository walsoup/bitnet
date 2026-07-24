package com.bluenet

import com.bluenet.client.TunPacketRouter
import com.bluenet.multiplexer.StreamMultiplexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class TunPacketRouterTest {

    @Test
    fun testBuildIPv4TcpPacketHeader() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream()
        val mux = StreamMultiplexer(pipeIn, pipeOut, {}, {})
        val router = TunPacketRouter(java.io.FileInputStream(java.io.FileDescriptor.`in`), java.io.FileOutputStream(java.io.FileDescriptor.out), mux)

        val srcIp = byteArrayOf(192.toByte(), 168.toByte(), 1.toByte(), 50.toByte())
        val srcPort = 8080
        val dstPort = 443
        val payload = "HTTP/1.1 200 OK\r\n\r\n".toByteArray()

        val packet = router.buildIPv4Packet(protocol = 6, srcIp = srcIp, srcPort = srcPort, dstPort = dstPort, payload = payload)

        // IPv4 Header verification
        assertEquals(0x45.toByte(), packet[0]) // Version 4, IHL 5
        assertEquals(6.toByte(), packet[9]) // Protocol TCP
        assertEquals(srcIp[0], packet[12])
        assertEquals(srcIp[1], packet[13])
        assertEquals(srcIp[2], packet[14])
        assertEquals(srcIp[3], packet[15])
        assertEquals(10.toByte(), packet[16])
        assertEquals(0.toByte(), packet[17])
        assertEquals(8.toByte(), packet[18])
        assertEquals(2.toByte(), packet[19])

        // TCP Header verification (headerLen = 20)
        val headerLen = 20
        val readSrcPort = ((packet[headerLen].toInt() and 0xFF) shl 8) or (packet[headerLen + 1].toInt() and 0xFF)
        val readDstPort = ((packet[headerLen + 2].toInt() and 0xFF) shl 8) or (packet[headerLen + 3].toInt() and 0xFF)
        assertEquals(srcPort, readSrcPort)
        assertEquals(dstPort, readDstPort)

        // Data Offset = 0x50 (5 words = 20 bytes)
        assertEquals(0x50.toByte(), packet[headerLen + 12])
        // Flags = 0x18 (ACK + PSH)
        assertEquals(0x18.toByte(), packet[headerLen + 13])
        // Window Size = 0xFFFF
        assertEquals(0xFF.toByte(), packet[headerLen + 14])
        assertEquals(0xFF.toByte(), packet[headerLen + 15])

        // TCP Checksum should be non-zero
        val tcpChecksum = ((packet[headerLen + 16].toInt() and 0xFF) shl 8) or (packet[headerLen + 17].toInt() and 0xFF)
        assertNotEquals(0, tcpChecksum)
    }

    @Test
    fun testBuildIPv4UdpPacketHeader() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream()
        val mux = StreamMultiplexer(pipeIn, pipeOut, {}, {})
        val router = TunPacketRouter(java.io.FileInputStream(java.io.FileDescriptor.`in`), java.io.FileOutputStream(java.io.FileDescriptor.out), mux)

        val srcIp = byteArrayOf(8.toByte(), 8.toByte(), 8.toByte(), 8.toByte())
        val srcPort = 53
        val dstPort = 54321
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        val packet = router.buildIPv4Packet(protocol = 17, srcIp = srcIp, srcPort = srcPort, dstPort = dstPort, payload = payload)

        // IPv4 Header verification
        assertEquals(0x45.toByte(), packet[0])
        assertEquals(17.toByte(), packet[9]) // Protocol UDP

        // UDP Header verification (headerLen = 20)
        val headerLen = 20
        val readSrcPort = ((packet[headerLen].toInt() and 0xFF) shl 8) or (packet[headerLen + 1].toInt() and 0xFF)
        val readDstPort = ((packet[headerLen + 2].toInt() and 0xFF) shl 8) or (packet[headerLen + 3].toInt() and 0xFF)
        assertEquals(srcPort, readSrcPort)
        assertEquals(dstPort, readDstPort)

        // UDP Length = 8 + payload.size = 13
        val udpLen = ((packet[headerLen + 4].toInt() and 0xFF) shl 8) or (packet[headerLen + 5].toInt() and 0xFF)
        assertEquals(8 + payload.size, udpLen)
    }
}
