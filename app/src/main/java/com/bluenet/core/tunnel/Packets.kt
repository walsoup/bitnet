package com.bluenet.core.tunnel

import java.util.concurrent.atomic.AtomicInteger

/** Minimal IPv4/TCP/UDP codec for packets read from and written to the TUN interface. */
object Ipv4 {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val HEADER_SIZE = 20

    class Header(val headerLength: Int, val totalLength: Int, val protocol: Int, val source: Int, val destination: Int)

    private val nextId = AtomicInteger(1)

    /** Returns null for non-IPv4 packets, truncated packets and fragments. */
    fun parse(packet: ByteArray, length: Int = packet.size): Header? {
        if (length < HEADER_SIZE) return null
        val versionIhl = packet[0].toInt() and 0xFF
        if (versionIhl ushr 4 != 4) return null
        val headerLength = (versionIhl and 0x0F) * 4
        val totalLength = u16(packet, 2)
        if (headerLength < HEADER_SIZE || totalLength < headerLength || totalLength > length) return null
        val flagsFragment = u16(packet, 6)
        if (flagsFragment and 0x2000 != 0 || flagsFragment and 0x1FFF != 0) return null
        return Header(headerLength, totalLength, packet[9].toInt() and 0xFF, i32(packet, 12), i32(packet, 16))
    }

    fun writeHeader(packet: ByteArray, totalLength: Int, protocol: Int, source: Int, destination: Int) {
        packet[0] = 0x45
        packet[1] = 0
        putU16(packet, 2, totalLength)
        putU16(packet, 4, nextId.getAndIncrement() and 0xFFFF)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = protocol.toByte()
        putU16(packet, 10, 0)
        putI32(packet, 12, source)
        putI32(packet, 16, destination)
        putU16(packet, 10, checksum(packet, 0, HEADER_SIZE, 0))
    }

    /** Pseudo-header sum for TCP/UDP checksums. */
    fun pseudoHeaderSum(source: Int, destination: Int, protocol: Int, length: Int): Int =
        (source ushr 16) + (source and 0xFFFF) + (destination ushr 16) + (destination and 0xFFFF) + protocol + length

    fun checksum(data: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial.toLong()
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    fun addressBytes(address: Int): ByteArray =
        byteArrayOf((address ushr 24).toByte(), (address ushr 16).toByte(), (address ushr 8).toByte(), address.toByte())

    fun addressInt(bytes: ByteArray): Int = i32(bytes, 0)

    fun format(address: Int): String = addressBytes(address).joinToString(".") { (it.toInt() and 0xFF).toString() }

    fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    fun putI32(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }
}

class TcpSegment(
    val source: Int,
    val destination: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val seq: Int,
    val ack: Int,
    val flags: Int,
    val window: Int,
    /** MSS option value, if present (only meaningful on SYN). */
    val mss: Int?,
    val payload: ByteArray,
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        private const val HEADER_SIZE = 20

        fun parse(packet: ByteArray, header: Ipv4.Header): TcpSegment? {
            val o = header.headerLength
            if (header.totalLength - o < HEADER_SIZE) return null
            val dataOffset = ((packet[o + 12].toInt() and 0xF0) ushr 4) * 4
            if (dataOffset < HEADER_SIZE || o + dataOffset > header.totalLength) return null
            var mss: Int? = null
            var i = o + HEADER_SIZE
            val optionsEnd = o + dataOffset
            while (i < optionsEnd) {
                when (val kind = packet[i].toInt() and 0xFF) {
                    0 -> break
                    1 -> i++
                    else -> {
                        if (i + 1 >= optionsEnd) break
                        val len = packet[i + 1].toInt() and 0xFF
                        if (len < 2 || i + len > optionsEnd) break
                        if (kind == 2 && len == 4) mss = Ipv4.u16(packet, i + 2)
                        i += len
                    }
                }
            }
            return TcpSegment(
                source = header.source,
                destination = header.destination,
                sourcePort = Ipv4.u16(packet, o),
                destinationPort = Ipv4.u16(packet, o + 2),
                seq = Ipv4.i32(packet, o + 4),
                ack = Ipv4.i32(packet, o + 8),
                flags = packet[o + 13].toInt() and 0x3F,
                window = Ipv4.u16(packet, o + 14),
                mss = mss,
                payload = packet.copyOfRange(o + dataOffset, header.totalLength),
            )
        }

        fun build(
            source: Int, sourcePort: Int, destination: Int, destinationPort: Int,
            seq: Int, ack: Int, flags: Int, window: Int,
            payload: ByteArray = ByteArray(0), offset: Int = 0, length: Int = payload.size,
            mss: Int? = null,
        ): ByteArray {
            val tcpHeader = if (mss != null) HEADER_SIZE + 4 else HEADER_SIZE
            val total = Ipv4.HEADER_SIZE + tcpHeader + length
            val packet = ByteArray(total)
            Ipv4.writeHeader(packet, total, Ipv4.PROTO_TCP, source, destination)
            val o = Ipv4.HEADER_SIZE
            Ipv4.putU16(packet, o, sourcePort)
            Ipv4.putU16(packet, o + 2, destinationPort)
            Ipv4.putI32(packet, o + 4, seq)
            Ipv4.putI32(packet, o + 8, ack)
            packet[o + 12] = ((tcpHeader / 4) shl 4).toByte()
            packet[o + 13] = flags.toByte()
            Ipv4.putU16(packet, o + 14, window)
            if (mss != null) {
                packet[o + 20] = 2
                packet[o + 21] = 4
                Ipv4.putU16(packet, o + 22, mss)
            }
            System.arraycopy(payload, offset, packet, o + tcpHeader, length)
            val segmentLength = tcpHeader + length
            val sum = Ipv4.checksum(packet, o, segmentLength, Ipv4.pseudoHeaderSum(source, destination, Ipv4.PROTO_TCP, segmentLength))
            Ipv4.putU16(packet, o + 16, sum)
            return packet
        }
    }
}

class UdpDatagram(
    val source: Int,
    val destination: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
) {
    companion object {
        private const val HEADER_SIZE = 8

        fun parse(packet: ByteArray, header: Ipv4.Header): UdpDatagram? {
            val o = header.headerLength
            if (header.totalLength - o < HEADER_SIZE) return null
            val udpLength = Ipv4.u16(packet, o + 4)
            if (udpLength < HEADER_SIZE || o + udpLength > header.totalLength) return null
            return UdpDatagram(
                header.source, header.destination,
                Ipv4.u16(packet, o), Ipv4.u16(packet, o + 2),
                packet.copyOfRange(o + HEADER_SIZE, o + udpLength),
            )
        }

        fun build(source: Int, sourcePort: Int, destination: Int, destinationPort: Int, payload: ByteArray): ByteArray {
            val udpLength = HEADER_SIZE + payload.size
            val total = Ipv4.HEADER_SIZE + udpLength
            val packet = ByteArray(total)
            Ipv4.writeHeader(packet, total, Ipv4.PROTO_UDP, source, destination)
            val o = Ipv4.HEADER_SIZE
            Ipv4.putU16(packet, o, sourcePort)
            Ipv4.putU16(packet, o + 2, destinationPort)
            Ipv4.putU16(packet, o + 4, udpLength)
            System.arraycopy(payload, 0, packet, o + HEADER_SIZE, payload.size)
            var sum = Ipv4.checksum(packet, o, udpLength, Ipv4.pseudoHeaderSum(source, destination, Ipv4.PROTO_UDP, udpLength))
            if (sum == 0) sum = 0xFFFF
            Ipv4.putU16(packet, o + 6, sum)
            return packet
        }
    }
}
