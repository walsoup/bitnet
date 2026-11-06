package com.bluenet.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import com.bluenet.core.Log
import com.bluenet.core.crypto.Identity
import com.bluenet.core.link.Channels
import com.bluenet.core.link.LinkTransport
import com.bluenet.core.link.SecureLink
import com.bluenet.core.tunnel.TunnelFrame
import com.bluenet.core.tunnel.TunnelHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Receives tunnel frames from the sharer this device is using as its internet uplink. */
interface TunnelEndpoint {
    fun onFrame(frame: TunnelFrame)
    fun onLinkClosed()
}

/**
 * Owns every [SecureLink] to a directly connected peer, carried over BLE L2CAP connection-oriented
 * channels.
 *
 * There is at most one link per peer. When both sides connect to each other at the same time, both
 * keep the link that was initiated by the peer with the lower ID, so they agree without talking.
 */
@SuppressLint("MissingPermission")
class LinkManager(
    private val adapter: BluetoothAdapter,
    private val identity: Identity,
    private val onMeshPacket: (bytes: ByteArray, fromPeerId: String) -> Unit,
    private val onLinkOpened: (peerId: String) -> Unit,
    private val tunnelEndpoint: (peerId: String) -> TunnelEndpoint?,
    private val onListening: (psm: Int) -> Unit = {},
) {
    private class Entry(val link: SecureLink) {
        @Volatile var host: TunnelHost? = null
    }

    private val lock = Any()
    private val listenLock = Any()
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val entries = HashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<SecureLink?>>()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "link-manager").apply { isDaemon = true }
    }

    private val _links = MutableStateFlow<Set<String>>(emptySet())
    /** Peer IDs with an open link. */
    val links: StateFlow<Set<String>> = _links.asStateFlow()

    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile var psm: Int = 0
        private set

    /** When false, tunnel requests from peers are refused and existing tunnels are torn down. */
    @Volatile var sharing: Boolean = false
        set(value) {
            field = value
            if (!value) synchronized(lock) { entries.values.toList() }.forEach { closeHost(it) }
        }

    /**
     * Opens the L2CAP server socket, or reopens it after the accept loop has died.
     * Returns false if this device cannot accept links right now.
     */
    fun start(): Boolean = ensureListening()

    /** Restarts listening when the server socket has dropped. Safe to call while already up. */
    fun ensureListening(): Boolean {
        running.set(true)
        val listening = synchronized(listenLock) {
            if (server == null) openServerLocked()
            server != null
        }
        if (listening) ensureAcceptThread()
        return listening
    }

    fun stop() {
        running.set(false)
        val current = synchronized(listenLock) {
            server.also {
                server = null
                psm = 0
            }
        }
        current?.let { try { it.close() } catch (_: IOException) {} }
        val all = synchronized(lock) { entries.values.toList().also { entries.clear() } }
        all.forEach {
            closeHost(it)
            it.link.closeGracefully()
            tunnelEndpoint(it.link.remotePeerId)?.onLinkClosed()
        }
        publish()
    }

    fun link(peerId: String): SecureLink? = synchronized(lock) { entries[peerId]?.link?.takeIf { it.isOpen } }

    fun send(peerId: String, channel: Int, body: ByteArray): Boolean = link(peerId)?.send(channel, body) ?: false

    fun broadcast(channel: Int, body: ByteArray, exceptPeerId: String?) {
        val targets = synchronized(lock) { entries.values.map { it.link } }
        for (link in targets) {
            if (link.remotePeerId != exceptPeerId && link.isOpen) link.send(channel, body)
        }
    }

    fun isConnecting(peerId: String): Boolean = pending.containsKey(peerId)

    /**
     * Returns the link to [peerId], connecting to [device] on [psm] if needed. Blocks; concurrent
     * calls for the same peer share one attempt. Returns null if the connection or handshake fails.
     */
    fun connect(peerId: String, device: BluetoothDevice, psm: Int): SecureLink? {
        link(peerId)?.let { return it }
        val future = CompletableFuture<SecureLink?>()
        val existing = pending.putIfAbsent(peerId, future)
        if (existing != null) return try { existing.get(CONNECT_WAIT_S, TimeUnit.SECONDS) } catch (_: Exception) { null }
        try {
            val result = dial(peerId, device, psm)
            future.complete(result)
            return result
        } catch (e: Throwable) {
            future.complete(null)
            throw e
        } finally {
            pending.remove(peerId, future)
        }
    }

    fun connectAsync(peerId: String, device: BluetoothDevice, psm: Int, onResult: (SecureLink?) -> Unit = {}) {
        pool.execute { onResult(connect(peerId, device, psm)) }
    }

    fun tunnelHostCount(): Int = synchronized(lock) { entries.values.count { it.host != null } }

    private fun dial(peerId: String, device: BluetoothDevice, psm: Int): SecureLink? {
        val socket = try {
            device.createInsecureL2capChannel(psm)
        } catch (e: Exception) {
            Log.d(TAG, "L2CAP connect to ${peerId.take(6)} failed: ${e.message}")
            return null
        }
        try {
            connectSocket(socket)
        } catch (e: Exception) {
            Log.d(TAG, "L2CAP connect to ${peerId.take(6)} failed: ${e.message}")
            closeQuietly(socket)
            return null
        }
        val link = try {
            SecureLink.connect(SocketTransport(socket), identity, expectedPeerId = peerId)
        } catch (e: Exception) {
            Log.w(TAG, "Handshake with ${peerId.take(6)} failed: ${e.message}")
            closeQuietly(socket)
            return null
        }
        return register(link)
    }

    /**
     * [BluetoothSocket.connect] has no timeout of its own, and one stuck dial blocks every other
     * auto-link. Close the socket when the wait expires so connect() unblocks.
     */
    private fun connectSocket(socket: BluetoothSocket) {
        val task = pool.submit { socket.connect() }
        try {
            task.get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            closeQuietly(socket)
            throw IOException("timed out")
        } catch (e: InterruptedException) {
            task.cancel(true)
            closeQuietly(socket)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            closeQuietly(socket)
            throw e
        }
    }

    private fun openServerLocked(): Boolean {
        val socket = try {
            adapter.listenUsingInsecureL2capChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open L2CAP server", e)
            return false
        }
        server = socket
        psm = socket.psm
        Log.d(TAG, "Listening on PSM $psm")
        onListening(psm)
        return true
    }

    private fun ensureAcceptThread() {
        synchronized(listenLock) {
            if (acceptThread?.isAlive == true) return
            acceptThread = Thread(::acceptLoop, "link-accept").apply { isDaemon = true; start() }
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = synchronized(listenLock) { server }
            if (socket == null) {
                val opened = synchronized(listenLock) {
                    if (!running.get()) return
                    if (server != null) true else openServerLocked()
                }
                if (!opened) break
                continue
            }
            val accepted = try {
                socket.accept()
            } catch (e: IOException) {
                if (!running.get()) break
                Log.w(TAG, "L2CAP accept failed; reopening", e)
                synchronized(listenLock) {
                    if (server === socket) {
                        server = null
                        psm = 0
                        try { socket.close() } catch (_: IOException) {}
                        onListening(0)
                    }
                }
                continue
            }
            if (!running.get()) {
                try { accepted.close() } catch (_: IOException) {}
                break
            }
            if (synchronized(lock) { entries.size } >= MAX_LINKS) {
                try { accepted.close() } catch (_: IOException) {}
                continue
            }
            pool.execute {
                try {
                    register(SecureLink.accept(SocketTransport(accepted), identity))
                } catch (e: Exception) {
                    Log.w(TAG, "Inbound handshake failed: ${e.message}")
                    closeQuietly(accepted)
                }
            }
        }
    }

    /** Adds [link] unless a better link to the same peer already exists; returns the link kept. */
    private fun register(link: SecureLink): SecureLink? {
        val peerId = link.remotePeerId
        val replaced: Entry?
        synchronized(lock) {
            val current = entries[peerId]
            if (current != null && current.link.isOpen && isPreferred(current.link) && !isPreferred(link)) {
                link.close()
                return current.link
            }
            replaced = current
            val entry = Entry(link)
            if (replaced != null) {
                // Keep the sharer's sockets across a duplicate-link race. The host sends through
                // whichever link is current, so flows don't die just because both phones dialed.
                entry.host = synchronized(replaced) { replaced.host.also { replaced.host = null } }
            }
            entries[peerId] = entry
        }
        link.start(dispatcher)
        replaced?.link?.closeGracefully()
        publish()
        Log.d(TAG, "Link to ${peerId.take(6)} open (${if (link.initiatedLocally) "outbound" else "inbound"})")
        onLinkOpened(peerId)
        return if (link.isOpen) link else null
    }

    private fun isPreferred(link: SecureLink): Boolean {
        val initiator = if (link.initiatedLocally) identity.peerId else link.remotePeerId
        return initiator == minOf(identity.peerId, link.remotePeerId)
    }

    private val dispatcher = object : SecureLink.Listener {
        override fun onRecord(link: SecureLink, channel: Int, body: ByteArray) {
            when (channel) {
                Channels.MESH -> onMeshPacket(body, link.remotePeerId)
                Channels.TUNNEL_UP -> onTunnelUp(link, body)
                Channels.TUNNEL_DOWN -> {
                    val frame = TunnelFrame.decode(body) ?: return
                    if (isActive(link)) tunnelEndpoint(link.remotePeerId)?.onFrame(frame)
                }
            }
        }

        override fun onClosed(link: SecureLink, cause: Throwable?) {
            val entry = synchronized(lock) {
                val current = entries[link.remotePeerId]
                if (current?.link === link) entries.remove(link.remotePeerId) else null
            } ?: return
            closeHost(entry)
            tunnelEndpoint(link.remotePeerId)?.onLinkClosed()
            publish()
        }
    }

    private fun isActive(link: SecureLink): Boolean = synchronized(lock) { entries[link.remotePeerId]?.link === link }

    private fun onTunnelUp(link: SecureLink, body: ByteArray) {
        val frame = TunnelFrame.decode(body) ?: return
        val entry = synchronized(lock) { entries[link.remotePeerId]?.takeIf { it.link === link } } ?: return
        if (!sharing) {
            entry.host?.let { closeHost(entry) }
            val reply = when (frame.type) {
                TunnelFrame.OPEN_TCP, TunnelFrame.OPEN_UDP -> TunnelFrame.openFail(frame.streamId, TunnelFrame.FAIL_NOT_SHARING)
                TunnelFrame.RESET -> null
                else -> TunnelFrame.reset(frame.streamId)
            }
            reply?.let { link.send(Channels.TUNNEL_DOWN, it.encode()) }
            return
        }
        val host = entry.host ?: synchronized(entry) {
            entry.host ?: TunnelHost({ outbound ->
                send(link.remotePeerId, Channels.TUNNEL_DOWN, outbound.encode())
            }).also { entry.host = it }
        }
        host.onFrame(frame)
    }

    private fun closeHost(entry: Entry) {
        val host = synchronized(entry) { entry.host.also { entry.host = null } }
        host?.close()
    }

    private fun publish() {
        _links.value = synchronized(lock) { entries.keys.toSet() }
    }

    private class SocketTransport(private val socket: BluetoothSocket) : LinkTransport {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override fun close() = socket.close()
    }

    companion object {
        private const val TAG = "LinkManager"
        private const val MAX_LINKS = 8
        private const val CONNECT_WAIT_S = 40L
        private const val CONNECT_TIMEOUT_S = 15L

        private fun closeQuietly(socket: BluetoothSocket) {
            try { socket.close() } catch (_: IOException) {}
        }
    }
}
