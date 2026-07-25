package com.bluenet.client

import android.util.Log
import com.bluenet.multiplexer.Frame
import com.bluenet.multiplexer.FrameType
import com.bluenet.multiplexer.StreamMultiplexer
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.random.Random

class TunPacketRouter(
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val multiplexer: StreamMultiplexer
) {
    private val isRunning = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool()

    data class StreamKey(
        val protocol: Byte,
        val dstIpBytes: ByteArray,
        val dstPort: Int,
        val srcPort: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as StreamKey
            if (protocol != other.protocol) return false
            if (!dstIpBytes.contentEquals(other.dstIpBytes)) return false
            if (dstPort != other.dstPort) return false
            if (srcPort != other.srcPort) return false
            return true
        }

        override fun hashCode(): Int {
            var result = protocol.toInt()
            result = 31 * result + dstIpBytes.contentHashCode()
            result = 31 * result + dstPort
            result = 31 * result + srcPort
            return result
        }
    }

    class TcpStreamState(
        val streamId: Int,
        val key: StreamKey,
        @Volatile var clientSeq: Long,
        @Volatile var tunSeq: Long
    )

    private val tcpStreams = ConcurrentHashMap<String, TcpStreamState>() // connectionKey -> TcpStreamState
    private val streamToTcpState = ConcurrentHashMap<Int, TcpStreamState>() // streamId -> TcpStreamState

    private val udpStreams = ConcurrentHashMap<String, Int>() // connectionKey -> streamId
    private val streamToUdpKey = ConcurrentHashMap<Int, StreamKey>() // streamId -> StreamKey

    private val ipClientBytes = byteArrayOf(10, 0, 8, 2)
    private var packetIdCounter = Random.nextInt(1, 30000)

    fun start() {
        executor.execute { readTunLoop() }
    }

    fun handleIncomingFrame(frame: Frame) {
        val streamId = frame.streamId

        // Handle TCP Frame
        val tcpState = streamToTcpState[streamId]
        if (tcpState != null) {
            when (frame.type) {
                FrameType.DATA, FrameType.COMPRESSED_DATA -> {
                    if (frame.payload.isNotEmpty()) {
                        val packet = buildTcpPacket(
                            key = tcpState.key,
                            flags = 0x18, // PSH | ACK
                            seq = tcpState.tunSeq,
                            ack = tcpState.clientSeq,
                            payload = frame.payload
                        )
                        tcpState.tunSeq += frame.payload.size
                        writeToTun(packet)
                    }
                }
                FrameType.CLOSE -> {
                    val finPacket = buildTcpPacket(
                        key = tcpState.key,
                        flags = 0x11, // FIN | ACK
                        seq = tcpState.tunSeq,
                        ack = tcpState.clientSeq,
                        payload = ByteArray(0)
                    )
                    tcpState.tunSeq += 1
                    writeToTun(finPacket)
                    removeTcpStream(tcpState.streamId)
                }
                else -> {}
            }
            return
        }

        // Handle UDP Frame
        val udpKey = streamToUdpKey[streamId]
        if (udpKey != null) {
            when (frame.type) {
                FrameType.DATA, FrameType.COMPRESSED_DATA -> {
                    if (frame.payload.isNotEmpty()) {
                        val packet = buildUdpPacket(
                            srcIp = udpKey.dstIpBytes,
                            srcPort = udpKey.dstPort,
                            dstPort = udpKey.srcPort,
                            payload = frame.payload
                        )
                        writeToTun(packet)
                    }
                }
                FrameType.CLOSE -> {
                    removeUdpStream(streamId)
                }
                else -> {}
            }
        }
    }

    private fun readTunLoop() {
        val buffer = ByteArray(32767)
        try {
            while (isRunning.get()) {
                val length = tunInput.read(buffer)
                if (length <= 0) break
                val packetData = buffer.copyOf(length)
                processOutboundIpPacket(packetData)
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error reading TUN packet loop", e)
            }
        }
    }

    private fun processOutboundIpPacket(packet: ByteArray) {
        if (packet.size < 20) return

        val versionAndIhl = packet[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return // IPv4 only

        val ihl = (versionAndIhl and 0x0F) * 4
        if (packet.size < ihl + 8) return

        val protocol = packet[9]
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        if (protocol.toInt() == 6) { // TCP
            processTcpOutbound(packet, ihl, dstIp, srcPort, dstPort)
        } else if (protocol.toInt() == 17) { // UDP
            processUdpOutbound(packet, ihl, dstIp, srcPort, dstPort)
        }
    }

    private fun processTcpOutbound(packet: ByteArray, ihl: Int, dstIp: ByteArray, srcPort: Int, dstPort: Int) {
        if (packet.size < ihl + 20) return

        val seqNo = parseUnsignedInt(packet, ihl + 4)
        val flags = packet[ihl + 13].toInt() and 0xFF


        val dataOffset = (packet[ihl + 12].toInt() and 0xF0) ushr 4
        val tcpHeaderLen = dataOffset * 4
        val payloadOffset = ihl + tcpHeaderLen
        val payload = if (packet.size > payloadOffset) packet.copyOfRange(payloadOffset, packet.size) else ByteArray(0)

        val dstIpStr = dstIp.joinToString(".")
        val connectionKey = "6:$srcPort:$dstIpStr:$dstPort"

        var tcpState = tcpStreams[connectionKey]

        // SYN Handshake
        if ((flags and 0x02) != 0) { // SYN
            if (tcpState == null) {
                val streamId = multiplexer.generateStreamId()
                val key = StreamKey(6.toByte(), dstIp, dstPort, srcPort)
                val initialTunSeq = Random.nextLong(10000, 1000000)

                tcpState = TcpStreamState(streamId, key, clientSeq = seqNo + 1, tunSeq = initialTunSeq)
                tcpStreams[connectionKey] = tcpState
                streamToTcpState[streamId] = tcpState

                // Send CONNECT_TCP over Bluetooth multiplexer to Host
                val connectPayload = ByteBuffer.allocate(6).apply {
                    put(dstIp)
                    putShort(dstPort.toShort())
                }.array()
                multiplexer.sendFrame(Frame(FrameType.CONNECT_TCP, streamId, connectPayload))

                // Synthesize SYN-ACK back to TUN interface
                val synAckPacket = buildTcpPacket(
                    key = key,
                    flags = 0x12, // SYN | ACK
                    seq = tcpState.tunSeq,
                    ack = tcpState.clientSeq,
                    payload = ByteArray(0)
                )
                tcpState.tunSeq += 1
                writeToTun(synAckPacket)
            }
            return
        }

        if (tcpState == null) return

        // RST packet
        if ((flags and 0x04) != 0) {
            multiplexer.sendFrame(Frame(FrameType.CLOSE, tcpState.streamId))
            removeTcpStream(tcpState.streamId)
            return
        }

        // FIN packet
        if ((flags and 0x01) != 0) {
            if (payload.isNotEmpty()) {
                multiplexer.sendFrame(Frame(FrameType.DATA, tcpState.streamId, payload))
                tcpState.clientSeq += payload.size
            }
            tcpState.clientSeq += 1
            multiplexer.sendFrame(Frame(FrameType.CLOSE, tcpState.streamId))

            // Reply with ACK to TUN
            val finAckPacket = buildTcpPacket(
                key = tcpState.key,
                flags = 0x10, // ACK
                seq = tcpState.tunSeq,
                ack = tcpState.clientSeq,
                payload = ByteArray(0)
            )
            writeToTun(finAckPacket)
            removeTcpStream(tcpState.streamId)
            return
        }

        // ACK / PSH|ACK Data Transfer
        if (payload.isNotEmpty()) {
            tcpState.clientSeq = max(tcpState.clientSeq, seqNo + payload.size)
            multiplexer.sendFrame(Frame(FrameType.DATA, tcpState.streamId, payload))
        } else {
            tcpState.clientSeq = max(tcpState.clientSeq, seqNo)
        }
    }

    private fun processUdpOutbound(packet: ByteArray, ihl: Int, dstIp: ByteArray, srcPort: Int, dstPort: Int) {
        val payloadOffset = ihl + 8
        val payload = if (packet.size > payloadOffset) packet.copyOfRange(payloadOffset, packet.size) else ByteArray(0)

        val dstIpStr = dstIp.joinToString(".")
        val connectionKey = "17:$srcPort:$dstIpStr:$dstPort"

        var streamId = udpStreams[connectionKey]
        if (streamId == null) {
            streamId = multiplexer.generateStreamId()
            val key = StreamKey(17.toByte(), dstIp, dstPort, srcPort)
            udpStreams[connectionKey] = streamId
            streamToUdpKey[streamId] = key

            val connectPayload = ByteBuffer.allocate(6).apply {
                put(dstIp)
                putShort(dstPort.toShort())
            }.array()
            multiplexer.sendFrame(Frame(FrameType.CONNECT_UDP, streamId, connectPayload))
        }

        if (payload.isNotEmpty()) {
            multiplexer.sendFrame(Frame(FrameType.DATA, streamId, payload))
        }
    }

    private fun removeTcpStream(streamId: Int) {
        val state = streamToTcpState.remove(streamId)
        if (state != null) {
            val keyStr = "6:${state.key.srcPort}:${state.key.dstIpBytes.joinToString(".")}:${state.key.dstPort}"
            tcpStreams.remove(keyStr)
        }
    }

    private fun removeUdpStream(streamId: Int) {
        val key = streamToUdpKey.remove(streamId)
        if (key != null) {
            val keyStr = "17:${key.srcPort}:${key.dstIpBytes.joinToString(".")}:${key.dstPort}"
            udpStreams.remove(keyStr)
        }
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(tunOutput) {
            try {
                tunOutput.write(packet)
                tunOutput.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error writing packet to TUN output", e)
            }
        }
    }

    private fun parseUnsignedInt(packet: ByteArray, offset: Int): Long {
        return ((packet[offset].toLong() and 0xFF) shl 24) or
                ((packet[offset + 1].toLong() and 0xFF) shl 16) or
                ((packet[offset + 2].toLong() and 0xFF) shl 8) or
                (packet[offset + 3].toLong() and 0xFF)
    }

    internal fun buildTcpPacket(key: StreamKey, flags: Int, seq: Long, ack: Long, payload: ByteArray): ByteArray {
        val headerLen = 20
        val tcpHeaderLen = 20
        val totalLen = headerLen + tcpHeaderLen + payload.size
        val packet = ByteArray(totalLen)

        // IP Header
        packet[0] = 0x45.toByte() // IPv4, IHL 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = (totalLen shr 8).toByte()
        packet[3] = totalLen.toByte()
        val pid = nextPacketId()
        packet[4] = (pid shr 8).toByte()
        packet[5] = pid.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte() // TTL
        packet[9] = 6.toByte() // TCP
        System.arraycopy(key.dstIpBytes, 0, packet, 12, 4) // Source IP (Remote Server IP)
        System.arraycopy(ipClientBytes, 0, packet, 16, 4) // Dest IP (TUN IP 10.0.8.2)

        // IP Checksum
        val ipChecksum = calculateIpChecksum(packet, headerLen)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = ipChecksum.toByte()

        // TCP Header (Starts at byte 20)
        val tcpOffset = 20
        packet[tcpOffset] = (key.dstPort shr 8).toByte() // Source Port
        packet[tcpOffset + 1] = key.dstPort.toByte()
        packet[tcpOffset + 2] = (key.srcPort shr 8).toByte() // Destination Port
        packet[tcpOffset + 3] = key.srcPort.toByte()

        // Sequence Number
        packet[tcpOffset + 4] = (seq shr 24).toByte()
        packet[tcpOffset + 5] = (seq shr 16).toByte()
        packet[tcpOffset + 6] = (seq shr 8).toByte()
        packet[tcpOffset + 7] = seq.toByte()

        // Acknowledgment Number
        packet[tcpOffset + 8] = (ack shr 24).toByte()
        packet[tcpOffset + 9] = (ack shr 16).toByte()
        packet[tcpOffset + 10] = (ack shr 8).toByte()
        packet[tcpOffset + 11] = ack.toByte()

        packet[tcpOffset + 12] = 0x50.toByte() // Data Offset 5 (20 bytes)
        packet[tcpOffset + 13] = flags.toByte() // TCP Flags

        // Window size (65535)
        packet[tcpOffset + 14] = 0xFF.toByte()
        packet[tcpOffset + 15] = 0xFF.toByte()

        // Copy Payload
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOffset + tcpHeaderLen, payload.size)
        }

        // TCP Checksum with Pseudo-Header
        val tcpChecksum = calculateTcpChecksum(key.dstIpBytes, ipClientBytes, packet, tcpOffset, tcpHeaderLen + payload.size)
        packet[tcpOffset + 16] = (tcpChecksum shr 8).toByte()
        packet[tcpOffset + 17] = tcpChecksum.toByte()

        return packet
    }

    internal fun buildUdpPacket(srcIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val headerLen = 20
        val udpHeaderLen = 8
        val totalLen = headerLen + udpHeaderLen + payload.size
        val packet = ByteArray(totalLen)

        // IP Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = (totalLen shr 8).toByte()
        packet[3] = totalLen.toByte()
        val pid = nextPacketId()
        packet[4] = (pid shr 8).toByte()
        packet[5] = pid.toByte()
        packet[6] = 0x00.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte() // TTL
        packet[9] = 17.toByte() // UDP
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(ipClientBytes, 0, packet, 16, 4)

        val ipChecksum = calculateIpChecksum(packet, headerLen)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = ipChecksum.toByte()

        // UDP Header
        val udpOffset = 20
        packet[udpOffset] = (srcPort shr 8).toByte()
        packet[udpOffset + 1] = srcPort.toByte()
        packet[udpOffset + 2] = (dstPort shr 8).toByte()
        packet[udpOffset + 3] = dstPort.toByte()

        val udpLen = udpHeaderLen + payload.size
        packet[udpOffset + 4] = (udpLen shr 8).toByte()
        packet[udpOffset + 5] = udpLen.toByte()
        packet[udpOffset + 6] = 0x00.toByte() // UDP Checksum optional in IPv4
        packet[udpOffset + 7] = 0x00.toByte()

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, udpOffset + udpHeaderLen, payload.size)
        }

        return packet
    }

    private fun calculateIpChecksum(packet: ByteArray, headerLen: Int): Int {
        packet[10] = 0
        packet[11] = 0
        var sum = 0
        for (i in 0 until headerLen step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun calculateTcpChecksum(srcIp: ByteArray, dstIp: ByteArray, tcpPacket: ByteArray, tcpOffset: Int, tcpLen: Int): Int {
        var sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 6 // Protocol TCP
        sum += tcpLen

        tcpPacket[tcpOffset + 16] = 0
        tcpPacket[tcpOffset + 17] = 0

        for (i in 0 until tcpLen step 2) {
            val word = if (i + 1 < tcpLen) {
                ((tcpPacket[tcpOffset + i].toInt() and 0xFF) shl 8) or (tcpPacket[tcpOffset + i + 1].toInt() and 0xFF)
            } else {
                (tcpPacket[tcpOffset + i].toInt() and 0xFF) shl 8
            }
            sum += word
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    @Synchronized
    private fun nextPacketId(): Int {
        packetIdCounter = (packetIdCounter + 1) and 0xFFFF
        return packetIdCounter
    }

    fun stop() {
        isRunning.set(false)
        tcpStreams.clear()
        streamToTcpState.clear()
        udpStreams.clear()
        streamToUdpKey.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "TunPacketRouter"
    }
}
