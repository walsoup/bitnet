package com.bluenet.core.tunnel

import com.bluenet.core.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sharing end of the tunnel for one client link: opens real sockets for the client's streams and
 * relays their data.
 *
 * Writes for a stream run in order on a [SerialExecutor], so data that arrives while the outbound
 * connection is still being established is queued rather than lost. Reads only proceed while the
 * client has granted credit. [onFrame] is called from the link's reader thread and never blocks.
 */
class TunnelHost(
    private val link: FrameSender,
    private val policy: DestinationPolicy = DestinationPolicy.PUBLIC_INTERNET_ONLY,
    private val connectTimeoutMs: Int = 10_000,
    private val maxStreams: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private interface Stream {
        fun onFrame(frame: TunnelFrame)
        fun abort()
    }

    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "tunnel-host").apply { isDaemon = true }
    }
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val closed = AtomicBoolean(false)

    val streamCount: Int get() = streams.size

    fun onFrame(frame: TunnelFrame) {
        if (closed.get()) return
        when (frame.type) {
            TunnelFrame.OPEN_TCP, TunnelFrame.OPEN_UDP -> open(frame)
            else -> {
                val stream = streams[frame.streamId]
                if (stream != null) {
                    stream.onFrame(frame)
                } else if (frame.type != TunnelFrame.RESET) {
                    link.send(TunnelFrame.reset(frame.streamId))
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        streams.values.forEach { it.abort() }
        streams.clear()
        pool.shutdownNow()
    }

    private fun open(frame: TunnelFrame) {
        val id = frame.streamId
        if (streams.containsKey(id)) {
            link.send(TunnelFrame.reset(id))
            streams.remove(id)?.abort()
            return
        }
        if (!policy.allows(frame.address, frame.port)) {
            link.send(TunnelFrame.openFail(id, TunnelFrame.FAIL_BLOCKED))
            return
        }
        if (streams.size >= maxStreams) {
            link.send(TunnelFrame.openFail(id, TunnelFrame.FAIL_LIMIT))
            return
        }
        val target = InetSocketAddress(InetAddress.getByAddress(frame.address), frame.port)
        if (frame.type == TunnelFrame.OPEN_TCP) {
            val stream = TcpStream(id, target)
            streams[id] = stream
            stream.connect()
        } else {
            val stream = try {
                UdpStream(id, target)
            } catch (e: IOException) {
                link.send(TunnelFrame.openFail(id, TunnelFrame.FAIL_UNREACHABLE))
                return
            }
            streams[id] = stream
            stream.start()
        }
    }

    private fun remove(id: Int, stream: Stream) {
        streams.remove(id, stream)
    }

    private inner class TcpStream(val id: Int, val target: InetSocketAddress) : Stream {
        private val socket = Socket()
        private val writes = SerialExecutor(pool)
        private val aborted = AtomicBoolean(false)
        private val creditLock = Object()
        private var credit = TunnelFrame.INITIAL_WINDOW
        private var pendingGrant = 0
        private var readDone = false
        private var writeDone = false

        fun connect() = writes.execute {
            try {
                socket.connect(target, connectTimeoutMs)
                socket.tcpNoDelay = true
            } catch (e: IOException) {
                if (!aborted.get()) {
                    Log.d(TAG, "Connect to $target failed: ${e.message}")
                    link.send(TunnelFrame.openFail(id, TunnelFrame.FAIL_UNREACHABLE))
                }
                aborted.set(true)
                finish()
                return@execute
            }
            if (aborted.get()) return@execute
            link.send(TunnelFrame.openOk(id))
            try {
                pool.execute(::readLoop)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                abort()
            }
        }

        override fun onFrame(frame: TunnelFrame) {
            when (frame.type) {
                TunnelFrame.DATA -> writes.execute { write(frame.payload) }
                TunnelFrame.FIN -> writes.execute { shutdownOutput() }
                TunnelFrame.WINDOW -> synchronized(creditLock) {
                    credit += frame.windowIncrement
                    creditLock.notifyAll()
                }
                TunnelFrame.RESET -> abort()
            }
        }

        private fun write(bytes: ByteArray) {
            if (aborted.get() || writeDone) return
            try {
                socket.getOutputStream().write(bytes)
                pendingGrant += bytes.size
                if (pendingGrant >= TunnelFrame.GRANT_THRESHOLD) {
                    link.send(TunnelFrame.window(id, pendingGrant))
                    pendingGrant = 0
                }
            } catch (e: IOException) {
                fail()
            }
        }

        private fun shutdownOutput() {
            if (aborted.get()) return
            try {
                if (socket.isConnected) socket.shutdownOutput()
            } catch (_: IOException) {}
            synchronized(this) { writeDone = true }
            closeIfDone()
        }

        private fun readLoop() {
            val buffer = ByteArray(TunnelFrame.MAX_DATA)
            try {
                val input = socket.getInputStream()
                while (!aborted.get()) {
                    val allowed = awaitCredit()
                    if (allowed <= 0) return
                    val n = input.read(buffer, 0, minOf(allowed, buffer.size))
                    if (n < 0) {
                        link.send(TunnelFrame.fin(id))
                        synchronized(this) { readDone = true }
                        closeIfDone()
                        return
                    }
                    if (n == 0) continue
                    synchronized(creditLock) { credit -= n }
                    link.send(TunnelFrame.data(id, buffer.copyOf(n)))
                }
            } catch (_: IOException) {
                fail()
            } catch (_: InterruptedException) {
                abort()
            }
        }

        private fun awaitCredit(): Int = synchronized(creditLock) {
            while (credit <= 0 && !aborted.get()) creditLock.wait(1_000)
            if (aborted.get()) 0 else credit
        }

        private fun closeIfDone() {
            val done = synchronized(this) { readDone && writeDone }
            if (done) finish()
        }

        /** The socket failed underneath us: tell the client and clean up. */
        private fun fail() {
            if (aborted.get()) return
            link.send(TunnelFrame.reset(id))
            abort()
        }

        override fun abort() {
            if (!aborted.compareAndSet(false, true)) return
            synchronized(creditLock) { creditLock.notifyAll() }
            finish()
        }

        private fun finish() {
            try { socket.close() } catch (_: IOException) {}
            remove(id, this)
        }
    }

    private inner class UdpStream(val id: Int, target: InetSocketAddress) : Stream {
        private val socket = DatagramSocket().apply {
            connect(target)
            soTimeout = 1_000
        }
        private val aborted = AtomicBoolean(false)
        private val idleTimeoutMs = if (target.port == 53) 20_000L else 120_000L
        @Volatile private var lastActivity = clock()

        fun start() {
            try {
                pool.execute(::readLoop)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                abort()
            }
        }

        override fun onFrame(frame: TunnelFrame) {
            when (frame.type) {
                TunnelFrame.DATA -> try {
                    socket.send(DatagramPacket(frame.payload, frame.payload.size))
                    lastActivity = clock()
                } catch (_: IOException) {
                    link.send(TunnelFrame.reset(id))
                    abort()
                }
                TunnelFrame.RESET -> abort()
            }
        }

        private fun readLoop() {
            val buffer = ByteArray(65_535)
            while (!aborted.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    lastActivity = clock()
                    if (packet.length <= TunnelFrame.MAX_DATA) {
                        link.send(TunnelFrame.data(id, buffer.copyOf(packet.length)))
                    }
                } catch (_: SocketTimeoutException) {
                    if (clock() - lastActivity > idleTimeoutMs) {
                        link.send(TunnelFrame.reset(id))
                        abort()
                    }
                } catch (_: IOException) {
                    if (!aborted.get()) link.send(TunnelFrame.reset(id))
                    abort()
                }
            }
        }

        override fun abort() {
            if (!aborted.compareAndSet(false, true)) return
            socket.close()
            remove(id, this)
        }
    }

    companion object {
        private const val TAG = "TunnelHost"
    }
}
