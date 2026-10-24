package com.bluenet.core.tunnel

import org.junit.Assert.assertEquals

/** Builds packets the way an app's kernel would and inspects what the tunnel writes back. */
object TunnelTestSupport {
    val APP_IP = Ipv4.addressInt(byteArrayOf(10, 0, 8, 2))
    const val APP_PORT = 40_000

    fun appSegment(
        destination: Int, destinationPort: Int,
        seq: Int, ack: Int, flags: Int,
        payload: ByteArray = ByteArray(0),
        window: Int = 65_535, mss: Int? = null,
        sourcePort: Int = APP_PORT,
    ): ByteArray = TcpSegment.build(APP_IP, sourcePort, destination, destinationPort, seq, ack, flags, window, payload, mss = mss)

    fun parseTcp(packet: ByteArray): TcpSegment {
        val header = Ipv4.parse(packet)!!
        assertEquals("IPv4 header checksum", 0, Ipv4.checksum(packet, 0, header.headerLength, 0))
        val segmentLength = header.totalLength - header.headerLength
        assertEquals(
            "TCP checksum", 0,
            Ipv4.checksum(packet, header.headerLength, segmentLength, Ipv4.pseudoHeaderSum(header.source, header.destination, header.protocol, segmentLength)),
        )
        return TcpSegment.parse(packet, header)!!
    }

    fun parseUdp(packet: ByteArray): UdpDatagram {
        val header = Ipv4.parse(packet)!!
        assertEquals(Ipv4.PROTO_UDP, header.protocol)
        val length = header.totalLength - header.headerLength
        assertEquals("UDP checksum", 0, Ipv4.checksum(packet, header.headerLength, length, Ipv4.pseudoHeaderSum(header.source, header.destination, header.protocol, length)))
        return UdpDatagram.parse(packet, header)!!
    }
}
