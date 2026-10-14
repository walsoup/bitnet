package com.bluenet.core.tunnel

import java.nio.ByteBuffer

/**
 * One message of the tunnel protocol, carried on the link's tunnel channels.
 *
 * Streams are opened by the client with OPEN_TCP/OPEN_UDP and answered with OPEN_OK/OPEN_FAIL.
 * DATA in each direction is limited by credit: a sender starts with [INITIAL_WINDOW] bytes per
 * stream and the receiver returns credit with WINDOW once it has passed data on. FIN half-closes
 * one direction; RESET aborts the stream.
 */
class TunnelFrame(val type: Int, val streamId: Int, val payload: ByteArray = EMPTY) {

    fun encode(): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size).apply {
            put(type.toByte())
            putInt(streamId)
            put(payload)
        }.array()

    /** Destination of an OPEN_TCP / OPEN_UDP frame. */
    val address: ByteArray get() = payload.copyOfRange(0, 4)
    val port: Int get() = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
    val windowIncrement: Int get() = ByteBuffer.wrap(payload).int
    val failReason: Int get() = if (payload.isEmpty()) 0 else payload[0].toInt() and 0xFF

    override fun toString(): String = "TunnelFrame(type=$type, stream=$streamId, ${payload.size} bytes)"

    companion object {
        const val OPEN_TCP = 1
        const val OPEN_UDP = 2
        const val OPEN_OK = 3
        const val OPEN_FAIL = 4
        const val DATA = 5
        const val WINDOW = 6
        const val FIN = 7
        const val RESET = 8

        const val FAIL_BLOCKED = 1
        const val FAIL_UNREACHABLE = 2
        const val FAIL_LIMIT = 3
        const val FAIL_NOT_SHARING = 4

        const val HEADER_SIZE = 5
        const val INITIAL_WINDOW = 256 * 1024
        /** Credit is returned in batches of at least this many bytes; must stay below [INITIAL_WINDOW]. */
        const val GRANT_THRESHOLD = 32 * 1024
        const val MAX_DATA = 16 * 1024

        private val EMPTY = ByteArray(0)

        fun decode(bytes: ByteArray): TunnelFrame? {
            if (bytes.size < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes)
            val type = buffer.get().toInt() and 0xFF
            val streamId = buffer.int
            val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
            val valid = when (type) {
                OPEN_TCP, OPEN_UDP -> payload.size == 6
                WINDOW -> payload.size == 4
                OPEN_OK, FIN, RESET -> payload.isEmpty()
                OPEN_FAIL -> payload.size <= 1
                DATA -> payload.size <= MAX_DATA
                else -> false
            }
            return if (valid) TunnelFrame(type, streamId, payload) else null
        }

        fun openTcp(streamId: Int, address: ByteArray, port: Int) = TunnelFrame(OPEN_TCP, streamId, endpoint(address, port))
        fun openUdp(streamId: Int, address: ByteArray, port: Int) = TunnelFrame(OPEN_UDP, streamId, endpoint(address, port))
        fun openOk(streamId: Int) = TunnelFrame(OPEN_OK, streamId)
        fun openFail(streamId: Int, reason: Int) = TunnelFrame(OPEN_FAIL, streamId, byteArrayOf(reason.toByte()))
        fun data(streamId: Int, bytes: ByteArray) = TunnelFrame(DATA, streamId, bytes)
        fun window(streamId: Int, increment: Int) = TunnelFrame(WINDOW, streamId, ByteBuffer.allocate(4).putInt(increment).array())
        fun fin(streamId: Int) = TunnelFrame(FIN, streamId)
        fun reset(streamId: Int) = TunnelFrame(RESET, streamId)

        private fun endpoint(address: ByteArray, port: Int): ByteArray =
            address.copyOf(4) + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }
}

fun interface FrameSender {
    fun send(frame: TunnelFrame): Boolean
}
