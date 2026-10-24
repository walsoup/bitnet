package com.bluenet.core.link

import com.bluenet.core.Log
import com.bluenet.core.crypto.Handshake
import com.bluenet.core.crypto.HandshakeException
import com.bluenet.core.crypto.HandshakeResult
import com.bluenet.core.crypto.Identity
import com.bluenet.core.crypto.RecordCipher
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A raw bidirectional byte stream, e.g. an L2CAP socket. */
interface LinkTransport : Closeable {
    val input: InputStream
    val output: OutputStream
}

object Channels {
    const val CONTROL = 0
    const val MESH = 1
    /** Tunnel frames from a client to the peer sharing its internet. */
    const val TUNNEL_UP = 2
    /** Tunnel frames from the sharing peer back to its client. */
    const val TUNNEL_DOWN = 3
}

/**
 * An authenticated, encrypted link to one directly connected peer.
 *
 * Wire format after the [Handshake]: `u32 length || AES-GCM(channel || body)`. Records are
 * delivered to the [Listener] on the reader thread in order; [send] never blocks on the socket
 * because a dedicated writer thread drains a bounded queue.
 */
class SecureLink private constructor(
    private val transport: LinkTransport,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val session: HandshakeResult,
    val initiatedLocally: Boolean,
    private val config: Config,
) {
    data class Config(
        val keepAliveIntervalMs: Long = 5_000,
        val idleTimeoutMs: Long = 20_000,
        val sendQueueCapacity: Int = 4_096,
        val sendTimeoutMs: Long = 5_000,
        val handshakeTimeoutMs: Long = 15_000,
    )

    interface Listener {
        fun onRecord(link: SecureLink, channel: Int, body: ByteArray)
        fun onClosed(link: SecureLink, cause: Throwable?)
    }

    val remotePeerId: String get() = session.remotePeerId
    val remotePublicKey: ByteArray get() = session.remotePublicKey

    private val open = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<ByteArray>(config.sendQueueCapacity)
    @Volatile private var listener: Listener? = null
    @Volatile private var lastReceivedAt = System.currentTimeMillis()
    @Volatile private var lastSentAt = System.currentTimeMillis()
    private var writerThread: Thread? = null
    private var keepAlive: ScheduledFuture<*>? = null

    private val sentBytes = AtomicLong()
    private val receivedBytes = AtomicLong()
    val bytesSent: Long get() = sentBytes.get()
    val bytesReceived: Long get() = receivedBytes.get()
    val isOpen: Boolean get() = open.get()

    fun start(listener: Listener) {
        this.listener = listener
        if (!open.get()) {
            listener.onClosed(this, IOException("Link closed before start"))
            return
        }
        writerThread = Thread(::writeLoop, "link-writer-${remotePeerId.take(6)}").apply { isDaemon = true; start() }
        Thread(::readLoop, "link-reader-${remotePeerId.take(6)}").apply { isDaemon = true; start() }
        keepAlive = scheduler.scheduleWithFixedDelay(::checkKeepAlive, config.keepAliveIntervalMs, config.keepAliveIntervalMs, TimeUnit.MILLISECONDS)
    }

    /** Queues a record. Returns false if the link is closed or its queue stayed full too long. */
    fun send(channel: Int, body: ByteArray): Boolean {
        require(body.size <= MAX_BODY) { "Record body too large (${body.size} bytes)" }
        if (!open.get()) return false
        val record = ByteArray(body.size + 1)
        record[0] = channel.toByte()
        System.arraycopy(body, 0, record, 1, body.size)
        val queued = try {
            queue.offer(record, config.sendTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!queued) {
            close(IOException("Send queue stalled"))
            return false
        }
        sentBytes.addAndGet(body.size.toLong())
        return true
    }

    fun close(cause: Throwable? = null) {
        if (!open.compareAndSet(true, false)) return
        keepAlive?.cancel(false)
        queue.clear()
        writerThread?.interrupt()
        try { transport.close() } catch (_: IOException) {}
        if (cause != null) Log.d(TAG, "Link to $remotePeerId closed: ${cause.message}")
        listener?.onClosed(this, cause)
    }

    private fun writeLoop() {
        try {
            while (open.get()) {
                val record = queue.poll(1, TimeUnit.SECONDS) ?: continue
                writeRecord(record)
                if (queue.isEmpty()) output.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun writeRecord(plaintext: ByteArray) {
        val ciphertext = session.sendCipher.seal(plaintext)
        output.writeInt(ciphertext.size)
        output.write(ciphertext)
        lastSentAt = System.currentTimeMillis()
    }

    private fun readLoop() {
        try {
            while (open.get()) {
                val plaintext = readRecord(input, session.receiveCipher)
                lastReceivedAt = System.currentTimeMillis()
                val channel = plaintext[0].toInt() and 0xFF
                val body = plaintext.copyOfRange(1, plaintext.size)
                if (channel == Channels.CONTROL) {
                    handleControl(body)
                } else {
                    receivedBytes.addAndGet(body.size.toLong())
                    listener?.onRecord(this, channel, body)
                }
            }
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun handleControl(body: ByteArray) {
        when (body.firstOrNull()) {
            CONTROL_PING -> send(Channels.CONTROL, byteArrayOf(CONTROL_PONG))
            CONTROL_GOODBYE -> close()
            else -> Unit
        }
    }

    private fun checkKeepAlive() {
        if (!open.get()) return
        val now = System.currentTimeMillis()
        if (now - lastReceivedAt > config.idleTimeoutMs) {
            close(IOException("Link idle for ${now - lastReceivedAt} ms"))
        } else if (now - lastSentAt >= config.keepAliveIntervalMs) {
            send(Channels.CONTROL, byteArrayOf(CONTROL_PING))
        }
    }

    /** Tells the peer we are leaving, then closes once the goodbye has had a moment to flush. */
    fun closeGracefully() {
        if (!open.get()) return
        send(Channels.CONTROL, byteArrayOf(CONTROL_GOODBYE))
        scheduler.schedule({ close() }, 300, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "SecureLink"
        const val MAX_BODY = 32 * 1024
        private const val MAX_CIPHERTEXT = MAX_BODY + 1 + RecordCipher.OVERHEAD

        private const val CONTROL_PING: Byte = 1
        private const val CONTROL_PONG: Byte = 2
        private const val CONTROL_CONFIRM: Byte = 3
        private const val CONTROL_GOODBYE: Byte = 4

        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "link-scheduler").apply { isDaemon = true }
        }

        /** Opens a link as the connecting side. Blocks until the handshake completes or fails. */
        fun connect(transport: LinkTransport, identity: Identity, expectedPeerId: String?, config: Config = Config()): SecureLink =
            establish(transport, config, initiator = true) { input, output ->
                Handshake.initiate(input, output, identity, expectedPeerId)
            }

        /** Opens a link as the accepting side. Blocks until the handshake completes or fails. */
        fun accept(transport: LinkTransport, identity: Identity, config: Config = Config()): SecureLink =
            establish(transport, config, initiator = false) { input, output ->
                Handshake.respond(input, output, identity)
            }

        private fun establish(
            transport: LinkTransport,
            config: Config,
            initiator: Boolean,
            handshake: (DataInputStream, DataOutputStream) -> HandshakeResult,
        ): SecureLink {
            val done = AtomicBoolean(false)
            val watchdog = scheduler.schedule({
                if (!done.get()) try { transport.close() } catch (_: IOException) {}
            }, config.handshakeTimeoutMs, TimeUnit.MILLISECONDS)
            try {
                val input = DataInputStream(transport.input)
                val output = DataOutputStream(BufferedOutputStream(transport.output, 64 * 1024))
                val session = handshake(input, output)

                val confirm = byteArrayOf(Channels.CONTROL.toByte(), CONTROL_CONFIRM) + session.transcriptHash
                val sealed = session.sendCipher.seal(confirm)
                output.writeInt(sealed.size)
                output.write(sealed)
                output.flush()

                val peerConfirm = readRecord(input, session.receiveCipher)
                val expected = byteArrayOf(Channels.CONTROL.toByte(), CONTROL_CONFIRM) + session.transcriptHash
                if (!peerConfirm.contentEquals(expected)) throw HandshakeException("Key confirmation failed")

                done.set(true)
                return SecureLink(transport, input, output, session, initiator, config)
            } catch (e: Exception) {
                done.set(true)
                try { transport.close() } catch (_: IOException) {}
                throw if (e is IOException) e else HandshakeException("Handshake failed: ${e.message}")
            } finally {
                watchdog.cancel(false)
            }
        }

        private fun readRecord(input: DataInputStream, cipher: RecordCipher): ByteArray {
            val length = input.readInt()
            if (length <= RecordCipher.OVERHEAD || length > MAX_CIPHERTEXT) throw IOException("Bad record length $length")
            val ciphertext = ByteArray(length)
            input.readFully(ciphertext)
            val plaintext = cipher.open(ciphertext)
            if (plaintext.isEmpty()) throw IOException("Empty record")
            return plaintext
        }
    }
}
