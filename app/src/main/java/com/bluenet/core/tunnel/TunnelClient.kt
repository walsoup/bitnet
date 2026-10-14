package com.bluenet.core.tunnel

import com.bluenet.core.Log
import java.util.concurrent.Executor
import kotlin.random.Random

fun interface PacketWriter {
    fun write(packet: ByteArray)
}

/**
 * Client end of the tunnel: terminates the TCP and UDP flows that apps send into the TUN
 * interface and carries their payloads to the sharing peer as [TunnelFrame]s.
 *
 * Each TCP flow gets a small TCP endpoint of its own: the SYN is only answered once the sharer
 * reports that the real connection succeeded, app data is acknowledged in order (duplicates and
 * out-of-order segments are dropped and re-ACKed), and data from the sharer is segmented to the
 * app's MSS and window and retransmitted on timeout.
 *
 * All state is confined to [executor], which must be single-threaded. Call [tick] periodically
 * (every ~100 ms) to drive retransmissions and timeouts.
 */
class TunnelClient(
    private val tun: PacketWriter,
    private val link: FrameSender,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class FlowKey(val source: Int, val sourcePort: Int, val destination: Int, val destinationPort: Int)

    private enum class State { OPENING, SYN_RECEIVED, ESTABLISHED }

    private val tcpByKey = HashMap<FlowKey, TcpFlow>()
    private val tcpById = HashMap<Int, TcpFlow>()
    private val udpByKey = HashMap<FlowKey, UdpFlow>()
    private val udpById = HashMap<Int, UdpFlow>()
    private var nextStreamId = 1

    @Volatile var openTcpFlows = 0
        private set
    @Volatile var openUdpFlows = 0
        private set

    fun onTunPacket(packet: ByteArray, length: Int) {
        val copy = packet.copyOf(length)
        executor.execute { handlePacket(copy) }
    }

    fun onFrame(frame: TunnelFrame) {
        executor.execute { handleFrame(frame) }
    }

    fun tick() {
        executor.execute { handleTick(clock()) }
    }

    /** Resets every flow, e.g. after the link to the sharer is lost. */
    fun resetAll() {
        executor.execute {
            tcpById.values.toList().forEach { abort(it, notifyHost = false) }
            udpById.values.toList().forEach { removeUdp(it) }
        }
    }

    private fun allocateStreamId(): Int {
        val id = nextStreamId
        nextStreamId = if (nextStreamId == Int.MAX_VALUE) 1 else nextStreamId + 1
        return id
    }

    private fun handlePacket(packet: ByteArray) {
        val header = Ipv4.parse(packet) ?: return
        when (header.protocol) {
            Ipv4.PROTO_TCP -> TcpSegment.parse(packet, header)?.let(::handleTcp)
            Ipv4.PROTO_UDP -> UdpDatagram.parse(packet, header)?.let(::handleUdp)
        }
    }

    // ---------------------------------------------------------------------------------- TCP

    private inner class TcpFlow(val key: FlowKey, val streamId: Int, appIss: Int, appMss: Int, var appWindow: Int) {
        var state = State.OPENING
        val mss = minOf(appMss, OUR_MSS)
        val iss = Random.nextInt()
        var rcvNxt = appIss + 1
        var sndUna = iss
        var sndNxt = iss
        /** Data from the sharer starting at sequence number [sndUna]. */
        val sendBuffer = ByteQueue()
        var upstreamCredit = TunnelFrame.INITIAL_WINDOW
        var pendingGrant = 0
        var appFinished = false
        var hostFinished = false
        var finSent = false
        var finSeq = 0
        var finAcked = false
        var rto = INITIAL_RTO_MS
        var retries = 0
        var lastTransmit = 0L
        val openedAt = clock()

        val advertisedWindow: Int get() = upstreamCredit.coerceIn(0, 0xFFFF)
        val inFlight: Int get() = sndNxt - sndUna

        fun packet(flags: Int, seq: Int = sndNxt, payload: ByteArray = EMPTY, mss: Int? = null): ByteArray =
            TcpSegment.build(
                source = key.destination, sourcePort = key.destinationPort,
                destination = key.source, destinationPort = key.sourcePort,
                seq = seq, ack = rcvNxt, flags = flags, window = advertisedWindow,
                payload = payload, mss = mss,
            )
    }

    private fun handleTcp(segment: TcpSegment) {
        val key = FlowKey(segment.source, segment.sourcePort, segment.destination, segment.destinationPort)
        val flow = tcpByKey[key]
        if (flow == null) {
            when {
                segment.has(TcpSegment.RST) -> Unit
                segment.has(TcpSegment.SYN) && !segment.has(TcpSegment.ACK) -> openTcp(key, segment)
                else -> replyReset(segment)
            }
            return
        }

        if (segment.has(TcpSegment.RST)) {
            link.send(TunnelFrame.reset(flow.streamId))
            removeTcp(flow)
            return
        }

        when (flow.state) {
            State.OPENING -> return
            State.SYN_RECEIVED -> {
                if (segment.has(TcpSegment.SYN)) {
                    sendSynAck(flow)
                    return
                }
                if (!segment.has(TcpSegment.ACK) || segment.ack != flow.iss + 1) return
                flow.state = State.ESTABLISHED
                flow.sndUna = flow.iss + 1
                flow.retries = 0
                flow.rto = INITIAL_RTO_MS
            }
            State.ESTABLISHED -> if (segment.has(TcpSegment.SYN)) {
                sendAck(flow)
                return
            }
        }
        processEstablished(flow, segment)
    }

    private fun openTcp(key: FlowKey, syn: TcpSegment) {
        if (tcpById.size >= MAX_TCP_FLOWS) {
            replyReset(syn)
            return
        }
        val flow = TcpFlow(key, allocateStreamId(), syn.seq, syn.mss ?: DEFAULT_APP_MSS, syn.window)
        tcpByKey[key] = flow
        tcpById[flow.streamId] = flow
        openTcpFlows = tcpById.size
        if (!link.send(TunnelFrame.openTcp(flow.streamId, Ipv4.addressBytes(key.destination), key.destinationPort))) {
            abort(flow, notifyHost = false)
        }
    }

    private fun processEstablished(flow: TcpFlow, segment: TcpSegment) {
        if (segment.has(TcpSegment.ACK)) {
            val ack = segment.ack
            if (seqGt(ack, flow.sndUna) && seqLe(ack, flow.sndNxt)) {
                var acked = ack - flow.sndUna
                if (flow.finSent && ack == flow.finSeq + 1) {
                    flow.finAcked = true
                    acked -= 1
                }
                val dataAcked = minOf(acked, flow.sendBuffer.size)
                flow.sendBuffer.drop(dataAcked)
                flow.sndUna = ack
                flow.retries = 0
                flow.rto = INITIAL_RTO_MS
                flow.lastTransmit = clock()
                flow.pendingGrant += dataAcked
                if (flow.pendingGrant >= TunnelFrame.GRANT_THRESHOLD) {
                    link.send(TunnelFrame.window(flow.streamId, flow.pendingGrant))
                    flow.pendingGrant = 0
                }
            }
            flow.appWindow = segment.window
        }

        var needAck = false
        val payload = segment.payload
        if (payload.isNotEmpty()) {
            needAck = true
            val alreadyReceived = flow.rcvNxt - segment.seq
            if (alreadyReceived >= 0 && alreadyReceived < payload.size && !flow.appFinished) {
                val accepted = minOf(payload.size - alreadyReceived, flow.upstreamCredit)
                var offset = alreadyReceived
                val end = alreadyReceived + accepted
                while (offset < end) {
                    val chunk = minOf(end - offset, TunnelFrame.MAX_DATA)
                    link.send(TunnelFrame.data(flow.streamId, payload.copyOfRange(offset, offset + chunk)))
                    offset += chunk
                }
                flow.rcvNxt += accepted
                flow.upstreamCredit -= accepted
            }
        }

        if (segment.has(TcpSegment.FIN) && !flow.appFinished) {
            needAck = true
            if (segment.seq + payload.size == flow.rcvNxt) {
                flow.rcvNxt += 1
                flow.appFinished = true
                link.send(TunnelFrame.fin(flow.streamId))
            }
        }

        if (needAck) sendAck(flow)
        pump(flow)
        finishIfDone(flow)
    }

    /** Sends as much buffered sharer data as the app's window allows, then our FIN if due. */
    private fun pump(flow: TcpFlow) {
        if (flow.state != State.ESTABLISHED) return
        while (!flow.finSent) {
            val sent = flow.inFlight
            val available = flow.sendBuffer.size - sent
            val window = flow.appWindow - sent
            if (available <= 0 || window <= 0) break
            val count = minOf(available, window, flow.mss)
            if (sent == 0) flow.lastTransmit = clock()
            tun.write(flow.packet(TcpSegment.ACK or TcpSegment.PSH, payload = flow.sendBuffer.copy(sent, count)))
            flow.sndNxt += count
        }
        if (flow.hostFinished && !flow.finSent && flow.inFlight == flow.sendBuffer.size) {
            if (flow.inFlight == 0) flow.lastTransmit = clock()
            tun.write(flow.packet(TcpSegment.FIN or TcpSegment.ACK))
            flow.finSeq = flow.sndNxt
            flow.sndNxt += 1
            flow.finSent = true
        }
    }

    private fun sendAck(flow: TcpFlow) {
        tun.write(flow.packet(TcpSegment.ACK))
    }

    private fun sendSynAck(flow: TcpFlow) {
        tun.write(flow.packet(TcpSegment.SYN or TcpSegment.ACK, seq = flow.iss, mss = OUR_MSS))
    }

    private fun finishIfDone(flow: TcpFlow) {
        if (flow.appFinished && flow.finAcked) removeTcp(flow)
    }

    private fun abort(flow: TcpFlow, notifyHost: Boolean) {
        tun.write(flow.packet(TcpSegment.RST or TcpSegment.ACK))
        if (notifyHost) link.send(TunnelFrame.reset(flow.streamId))
        removeTcp(flow)
    }

    private fun removeTcp(flow: TcpFlow) {
        tcpByKey.remove(flow.key)
        tcpById.remove(flow.streamId)
        openTcpFlows = tcpById.size
    }

    private fun replyReset(segment: TcpSegment) {
        val packet = if (segment.has(TcpSegment.ACK)) {
            TcpSegment.build(segment.destination, segment.destinationPort, segment.source, segment.sourcePort, segment.ack, 0, TcpSegment.RST, 0)
        } else {
            var length = segment.payload.size
            if (segment.has(TcpSegment.SYN)) length++
            if (segment.has(TcpSegment.FIN)) length++
            TcpSegment.build(segment.destination, segment.destinationPort, segment.source, segment.sourcePort, 0, segment.seq + length, TcpSegment.RST or TcpSegment.ACK, 0)
        }
        tun.write(packet)
    }

    // ---------------------------------------------------------------------------------- UDP

    private inner class UdpFlow(val key: FlowKey, val streamId: Int) {
        var lastActivity = clock()
        val idleTimeoutMs = if (key.destinationPort == 53) DNS_IDLE_TIMEOUT_MS else UDP_IDLE_TIMEOUT_MS
    }

    private fun handleUdp(datagram: UdpDatagram) {
        if (datagram.payload.size > TunnelFrame.MAX_DATA) return
        val key = FlowKey(datagram.source, datagram.sourcePort, datagram.destination, datagram.destinationPort)
        var flow = udpByKey[key]
        if (flow == null) {
            if (udpById.size >= MAX_UDP_FLOWS) return
            flow = UdpFlow(key, allocateStreamId())
            udpByKey[key] = flow
            udpById[flow.streamId] = flow
            openUdpFlows = udpById.size
            if (!link.send(TunnelFrame.openUdp(flow.streamId, Ipv4.addressBytes(key.destination), key.destinationPort))) {
                removeUdp(flow)
                return
            }
        }
        flow.lastActivity = clock()
        link.send(TunnelFrame.data(flow.streamId, datagram.payload))
    }

    private fun removeUdp(flow: UdpFlow) {
        udpByKey.remove(flow.key)
        udpById.remove(flow.streamId)
        openUdpFlows = udpById.size
    }

    // ------------------------------------------------------------------------------- frames

    private fun handleFrame(frame: TunnelFrame) {
        tcpById[frame.streamId]?.let { handleTcpFrame(it, frame); return }
        udpById[frame.streamId]?.let { handleUdpFrame(it, frame); return }
        if (frame.type != TunnelFrame.RESET && frame.type != TunnelFrame.OPEN_FAIL) {
            link.send(TunnelFrame.reset(frame.streamId))
        }
    }

    private fun handleTcpFrame(flow: TcpFlow, frame: TunnelFrame) {
        when (frame.type) {
            TunnelFrame.OPEN_OK -> if (flow.state == State.OPENING) {
                flow.state = State.SYN_RECEIVED
                flow.sndNxt = flow.iss + 1
                flow.lastTransmit = clock()
                sendSynAck(flow)
            }
            TunnelFrame.OPEN_FAIL -> {
                tun.write(TcpSegment.build(flow.key.destination, flow.key.destinationPort, flow.key.source, flow.key.sourcePort, 0, flow.rcvNxt, TcpSegment.RST or TcpSegment.ACK, 0))
                removeTcp(flow)
            }
            TunnelFrame.DATA -> {
                if (flow.state == State.OPENING || flow.hostFinished) return
                if (flow.sendBuffer.size + frame.payload.size > TunnelFrame.INITIAL_WINDOW) {
                    Log.w(TAG, "Sharer exceeded flow-control window on stream ${flow.streamId}")
                    abort(flow, notifyHost = true)
                    return
                }
                flow.sendBuffer.append(frame.payload)
                pump(flow)
            }
            TunnelFrame.WINDOW -> {
                val before = flow.advertisedWindow
                flow.upstreamCredit += frame.windowIncrement
                if (before < flow.mss && flow.state == State.ESTABLISHED) sendAck(flow)
            }
            TunnelFrame.FIN -> {
                flow.hostFinished = true
                pump(flow)
                finishIfDone(flow)
            }
            TunnelFrame.RESET -> abort(flow, notifyHost = false)
        }
    }

    private fun handleUdpFrame(flow: UdpFlow, frame: TunnelFrame) {
        when (frame.type) {
            TunnelFrame.DATA -> {
                flow.lastActivity = clock()
                tun.write(UdpDatagram.build(flow.key.destination, flow.key.destinationPort, flow.key.source, flow.key.sourcePort, frame.payload))
            }
            TunnelFrame.OPEN_FAIL, TunnelFrame.RESET -> removeUdp(flow)
        }
    }

    // ------------------------------------------------------------------------------- timers

    private fun handleTick(now: Long) {
        for (flow in tcpById.values.toList()) {
            when (flow.state) {
                State.OPENING -> if (now - flow.openedAt > OPEN_TIMEOUT_MS) abort(flow, notifyHost = true)
                State.SYN_RECEIVED, State.ESTABLISHED -> retransmitIfDue(flow, now)
            }
        }
        for (flow in udpById.values.toList()) {
            if (now - flow.lastActivity > flow.idleTimeoutMs) {
                link.send(TunnelFrame.reset(flow.streamId))
                removeUdp(flow)
            }
        }
    }

    private fun retransmitIfDue(flow: TcpFlow, now: Long) {
        if (now - flow.lastTransmit < flow.rto) return
        val zeroWindow = flow.appWindow == 0 && flow.state == State.ESTABLISHED
        if (flow.inFlight == 0) {
            if (zeroWindow && flow.sendBuffer.size > 0) {
                // Window probe: one byte past the closed window so the app re-advertises.
                flow.lastTransmit = now
                tun.write(flow.packet(TcpSegment.ACK, payload = flow.sendBuffer.copy(0, 1)))
                flow.sndNxt += 1
            }
            return
        }
        if (!zeroWindow && ++flow.retries > MAX_RETRIES) {
            Log.w(TAG, "Stream ${flow.streamId} timed out after $MAX_RETRIES retransmissions")
            abort(flow, notifyHost = true)
            return
        }
        flow.rto = minOf(flow.rto * 2, MAX_RTO_MS)
        flow.lastTransmit = now
        if (flow.state == State.SYN_RECEIVED) {
            sendSynAck(flow)
        } else {
            flow.sndNxt = flow.sndUna
            flow.finSent = false
            pump(flow)
        }
    }

    companion object {
        private const val TAG = "TunnelClient"
        /** Matches the TUN MTU of 1500 bytes minus IPv4 and TCP headers. */
        const val OUR_MSS = 1460
        private const val DEFAULT_APP_MSS = 536
        const val INITIAL_RTO_MS = 1_000L
        private const val MAX_RTO_MS = 30_000L
        private const val MAX_RETRIES = 8
        private const val OPEN_TIMEOUT_MS = 30_000L
        private const val UDP_IDLE_TIMEOUT_MS = 120_000L
        private const val DNS_IDLE_TIMEOUT_MS = 20_000L
        private const val MAX_TCP_FLOWS = 512
        private const val MAX_UDP_FLOWS = 256
        private val EMPTY = ByteArray(0)

        private fun seqGt(a: Int, b: Int) = a - b > 0
        private fun seqLe(a: Int, b: Int) = a - b <= 0
    }
}
