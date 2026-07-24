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

class TunPacketRouter(
    private val tunInput: FileInputStream,
    private val tunOutput: FileOutputStream,
    private val multiplexer: StreamMultiplexer
) {
    private val isRunning = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool()
    private val activeStreams = ConcurrentHashMap<String, Int>() // "protocol:srcPort:dstIp:dstPort" -> StreamID
    private val streamToKey = ConcurrentHashMap<Int, StreamKey>()

    data class StreamKey(val protocol: Byte, val dstIpBytes: ByteArray, val dstPort: Int, val srcPort: Int)

    fun start() {
        executor.execute { readTunLoop() }
    }

    fun handleIncomingFrame(frame: Frame) {
        val streamId = frame.streamId
        val key = streamToKey[streamId] ?: return

        when (frame.type) {
            FrameType.DATA -> {
                // Synthesize IP packet back to TUN interface
                val ipPacket = buildIPv4Packet(key.protocol, key.dstIpBytes, key.dstPort, key.srcPort, frame.payload)
                synchronized(tunOutput) {
                    try {
                        tunOutput.write(ipPacket)
                        tunOutput.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error writing to TUN output", e)
                    }
                }
            }
            FrameType.CLOSE -> {
                activeStreams.entries.removeIf { it.value == streamId }
                streamToKey.remove(streamId)
            }
            else -> {}
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
        if (version != 4) return // Process IPv4

        val protocol = packet[9] // 6 for TCP, 17 for UDP
        val dstIp = packet.copyOfRange(16, 20)

        val ihl = (versionAndIhl and 0x0F) * 4
        if (packet.size < ihl + 8) return

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        val connectionKey = "$protocol:$srcPort:${dstIp.joinToString(".")}:$dstPort"
        var streamId = activeStreams[connectionKey]

        val payloadOffset = if (protocol.toInt() == 6) {
            val dataOffset = (packet[ihl + 12].toInt() and 0xF0) ushr 4
            ihl + (dataOffset * 4)
        } else {
            ihl + 8 // UDP header is 8 bytes
        }
        val payload = if (packet.size > payloadOffset) packet.copyOfRange(payloadOffset, packet.size) else ByteArray(0)

        if (streamId == null) {
            streamId = multiplexer.generateStreamId()
            activeStreams[connectionKey] = streamId
            streamToKey[streamId] = StreamKey(protocol, dstIp, dstPort, srcPort)

            // Send CONNECT frame
            val connectPayload = ByteBuffer.allocate(6).apply {
                put(dstIp)
                putShort(dstPort.toShort())
            }.array()

            val connectType = if (protocol.toInt() == 6) FrameType.CONNECT_TCP else FrameType.CONNECT_UDP
            multiplexer.sendFrame(Frame(connectType, streamId, connectPayload))
        }

        if (payload.isNotEmpty()) {
            multiplexer.sendFrame(Frame(FrameType.DATA, streamId, payload))
        }
    }

    internal fun buildIPv4Packet(protocol: Byte, srcIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val headerLen = 20
        val transportLen = if (protocol.toInt() == 6) 20 else 8
        val totalLen = headerLen + transportLen + payload.size
        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[2] = (totalLen shr 8).toByte()
        packet[3] = totalLen.toByte()
        packet[8] = 64.toByte() // TTL
        packet[9] = protocol // Protocol
        System.arraycopy(srcIp, 0, packet, 12, 4) // Source IP
        // Destination IP 10.0.8.2
        packet[16] = 10.toByte(); packet[17] = 0.toByte(); packet[18] = 8.toByte(); packet[19] = 2.toByte()

        // Checksum initially 0
        packet[10] = 0
        packet[11] = 0

        var sum = 0
        for (i in 0 until 20 step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum shr 8).toByte()
        packet[11] = checksum.toByte()

        // Source Port
        packet[headerLen] = (srcPort shr 8).toByte()
        packet[headerLen + 1] = srcPort.toByte()

        // Destination Port
        packet[headerLen + 2] = (dstPort shr 8).toByte()
        packet[headerLen + 3] = dstPort.toByte()

        if (protocol.toInt() == 6) { // TCP
            // Data Offset (5 32-bit words = 20 bytes header)
            packet[headerLen + 12] = 0x50.toByte()
            // Flags: ACK (0x10) | PSH (0x08) = 0x18
            packet[headerLen + 13] = 0x18.toByte()
            // Window Size: 65535 (0xFFFF)
            packet[headerLen + 14] = 0xFF.toByte()
            packet[headerLen + 15] = 0xFF.toByte()

            // Copy payload before checksum calculation
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packet, headerLen + transportLen, payload.size)
            }

            // TCP Checksum calculation over Pseudo-Header + TCP Header + Payload
            var tcpSum = 0
            tcpSum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
            tcpSum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
            tcpSum += (10 shl 8) or 0
            tcpSum += (8 shl 8) or 2
            tcpSum += 6
            val tcpLen = transportLen + payload.size
            tcpSum += tcpLen

            val segmentLen = transportLen + payload.size
            for (i in 0 until segmentLen step 2) {
                val offset = headerLen + i
                val word = if (i + 1 < segmentLen) {
                    ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                } else {
                    (packet[offset].toInt() and 0xFF) shl 8
                }
                tcpSum += word
            }

            while ((tcpSum shr 16) > 0) {
                tcpSum = (tcpSum and 0xFFFF) + (tcpSum shr 16)
            }
            val tcpChecksum = tcpSum.inv() and 0xFFFF
            packet[headerLen + 16] = (tcpChecksum shr 8).toByte()
            packet[headerLen + 17] = tcpChecksum.toByte()
        } else if (protocol.toInt() == 17) { // UDP
            // UDP Length
            val udpLen = transportLen + payload.size
            packet[headerLen + 4] = (udpLen shr 8).toByte()
            packet[headerLen + 5] = udpLen.toByte()

            // Payload
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packet, headerLen + transportLen, payload.size)
            }
        }

        return packet
    }

    fun stop() {
        isRunning.set(false)
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "TunPacketRouter"
    }
}
