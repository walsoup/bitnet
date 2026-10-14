package com.bluenet.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.bluenet.core.Log
import com.bluenet.core.link.Channels
import com.bluenet.core.link.SecureLink
import com.bluenet.core.tunnel.FrameSender
import com.bluenet.core.tunnel.PacketWriter
import com.bluenet.core.tunnel.TunnelClient
import com.bluenet.core.tunnel.TunnelFrame
import com.bluenet.link.TunnelEndpoint
import com.bluenet.node.MeshNode
import com.bluenet.node.MeshRuntime
import com.bluenet.node.TunnelStatus
import com.bluenet.node.TunnelStatus.State
import com.bluenet.ui.MainActivity
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Routes this device's IPv4 traffic through a nearby peer that shares its internet.
 *
 * The TUN interface feeds a [TunnelClient]; its frames ride the [SecureLink] to the sharer. If the
 * link drops, open flows are reset and the link is re-established a few times before giving up.
 * IPv6 is deliberately not configured, which makes Android block it instead of leaking it.
 */
class BlueNetVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private val node: MeshNode by lazy { MeshRuntime.node(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: return START_NOT_STICKY
                val current = session
                if (current != null && current.peerId == peerId && !current.isStopped) return START_NOT_STICKY
                current?.stop()
                session = Session(peerId).also { it.start() }
            }
            ACTION_DISCONNECT -> endSession()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        endSession()
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    private fun endSession() {
        session?.stop()
        session = null
        MeshRuntime.publishTunnel(TunnelStatus())
        stopSelf()
    }

    private fun onSessionFinished(finished: Session) = main.post {
        if (session === finished) {
            session = null
            stopSelf()
        }
    }

    private inner class Session(val peerId: String) : TunnelEndpoint {
        private val stopped = AtomicBoolean(false)
        private val stopSignal = CountDownLatch(1)
        val isStopped: Boolean get() = stopped.get()

        private val loop: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tunnel-client").apply { isDaemon = true }
        }
        @Volatile private var link: SecureLink? = null
        @Volatile private var linkLost = CountDownLatch(1)
        @Volatile private var tun: ParcelFileDescriptor? = null
        private val wakePipe: Array<FileDescriptor> = Os.pipe()
        private var bytesUpBefore = 0L
        private var bytesDownBefore = 0L

        private val client = TunnelClient(
            tun = PacketWriter { packet -> writeTun(packet) },
            link = FrameSender { frame -> link?.send(Channels.TUNNEL_UP, frame.encode()) ?: false },
            executor = loop,
        )

        fun start() {
            Thread(::run, "vpn-session").apply { isDaemon = true; start() }
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            stopSignal.countDown()
            linkLost.countDown()
            try { Os.write(wakePipe[1], byteArrayOf(1), 0, 1) } catch (_: ErrnoException) {}
        }

        override fun onFrame(frame: TunnelFrame) {
            // Called on the link's reader thread; a late frame must never throw into it.
            if (stopped.get()) return
            try {
                client.onFrame(frame)
            } catch (_: RejectedExecutionException) {
            }
        }

        override fun onLinkClosed() = linkLost.countDown()

        private fun run() {
            node.attachTunnel(peerId, this)
            var failure: String? = null
            try {
                failure = connectLoop()
            } catch (e: Exception) {
                Log.e(TAG, "VPN session crashed", e)
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                stopped.set(true)
                node.detachTunnel(peerId, this)
                client.resetAll()
                loop.shutdown()
                try { loop.awaitTermination(1, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                try { tun?.close() } catch (_: Exception) {}
                tun = null
                closePipe()
                if (failure != null) {
                    MeshRuntime.publishTunnel(TunnelStatus(State.FAILED, peerId, peerName(), error = failure))
                }
                onSessionFinished(this)
            }
        }

        /** Returns a failure description, or null if the user stopped the session. */
        private fun connectLoop(): String? {
            if (node.sharing.value) return "Turn off internet sharing first"
            ensureMeshRunning()

            var attempt = 0
            var connectedOnce = false
            while (!stopped.get()) {
                publish(if (connectedOnce) State.RECONNECTING else State.CONNECTING, attempt)
                val next = node.linkTo(peerId)
                if (next == null) {
                    attempt++
                    if (attempt >= MAX_ATTEMPTS) return "${peerName()} is out of range"
                    sleepUnlessStopped(RETRY_DELAY_MS * attempt)
                    continue
                }
                if (tun == null) {
                    tun = establishTun() ?: return "VPN permission was revoked"
                    Thread(::readTun, "vpn-tun-reader").apply { isDaemon = true; start() }
                    loop.scheduleWithFixedDelay({ client.tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
                }

                linkLost = CountDownLatch(1)
                link = next
                if (!next.isOpen || stopped.get()) {
                    link = null
                    continue
                }
                attempt = 0
                connectedOnce = true
                node.lastSharerId = peerId
                Log.d(TAG, "Tunnel up through ${peerId.take(6)}")
                while (!stopped.get() && !linkLost.await(STATS_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    publish(State.CONNECTED, 0, next)
                }
                bytesUpBefore += next.bytesSent
                bytesDownBefore += next.bytesReceived
                link = null
                client.resetAll()
            }
            return null
        }

        private fun ensureMeshRunning() {
            if (node.status.value.state == MeshNode.State.RUNNING) return
            MeshService.start(this@BlueNetVpnService)
            val deadline = System.currentTimeMillis() + MESH_START_TIMEOUT_MS
            while (!stopped.get() && node.status.value.state != MeshNode.State.RUNNING && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
        }

        private fun establishTun(): ParcelFileDescriptor? {
            val configure = PendingIntent.getActivity(
                this@BlueNetVpnService, 0,
                Intent(this@BlueNetVpnService, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val pfd = Builder()
                .setSession("BlueNet · ${peerName()}")
                .addAddress(TUN_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(MTU)
                .addDisallowedApplication(packageName)
                .setConfigureIntent(configure)
                .setBlocking(false)
                .establish()
            return pfd
        }

        private fun readTun() {
            val fd = tun?.fileDescriptor ?: return
            val buffer = ByteArray(MTU + 100)
            val polls = arrayOf(
                StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() },
                StructPollfd().apply { this.fd = wakePipe[0]; events = OsConstants.POLLIN.toShort() },
            )
            try {
                while (!stopped.get()) {
                    polls.forEach { it.revents = 0 }
                    Os.poll(polls, -1)
                    if (polls[1].revents.toInt() != 0) break
                    while (true) {
                        val n = try {
                            Os.read(fd, buffer, 0, buffer.size)
                        } catch (e: ErrnoException) {
                            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) break
                            throw e
                        }
                        if (n <= 0) break
                        client.onTunPacket(buffer, n)
                    }
                }
            } catch (e: Exception) {
                if (!stopped.get()) Log.w(TAG, "TUN read failed", e)
                stop()
            }
        }

        private fun writeTun(packet: ByteArray) {
            val fd = tun?.fileDescriptor ?: return
            try {
                Os.write(fd, packet, 0, packet.size)
            } catch (_: ErrnoException) {
                // A full TUN queue drops the packet; TCP retransmits and UDP tolerates loss.
            }
        }

        private fun publish(state: State, attempt: Int, current: SecureLink? = null) {
            if (stopped.get()) return
            MeshRuntime.publishTunnel(
                TunnelStatus(
                    state = state,
                    peerId = peerId,
                    peerName = peerName(),
                    attempt = attempt,
                    bytesUp = bytesUpBefore + (current?.bytesSent ?: 0),
                    bytesDown = bytesDownBefore + (current?.bytesReceived ?: 0),
                    flows = client.openTcpFlows + client.openUdpFlows,
                )
            )
        }

        private fun peerName(): String = node.peer(peerId)?.name ?: peerId.take(6)

        private fun sleepUnlessStopped(ms: Long) {
            stopSignal.await(ms, TimeUnit.MILLISECONDS)
        }

        private fun closePipe() {
            wakePipe.forEach { try { Os.close(it) } catch (_: ErrnoException) {} }
        }
    }

    companion object {
        private const val TAG = "BlueNetVpn"
        private const val ACTION_CONNECT = "com.bluenet.action.VPN_CONNECT"
        private const val ACTION_DISCONNECT = "com.bluenet.action.VPN_DISCONNECT"
        private const val EXTRA_PEER_ID = "peer_id"

        private const val TUN_ADDRESS = "10.0.8.2"
        private const val MTU = 1500
        private const val TICK_MS = 100L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val MESH_START_TIMEOUT_MS = 5_000L
        const val MAX_ATTEMPTS = 5

        /** Connects through [peerId]. The caller must already have VPN consent from [VpnService.prepare]. */
        fun connect(context: Context, peerId: String) {
            val intent = Intent(context, BlueNetVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PEER_ID, peerId)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the VPN service", e)
            }
        }

        fun disconnect(context: Context) {
            if (MeshRuntime.tunnel.value.state == State.IDLE) return
            if (!MeshRuntime.tunnel.value.isActive) {
                MeshRuntime.publishTunnel(TunnelStatus())
                return
            }
            try {
                context.startService(Intent(context, BlueNetVpnService::class.java).setAction(ACTION_DISCONNECT))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the VPN service", e)
                MeshRuntime.publishTunnel(TunnelStatus())
            }
        }
    }
}
