package com.bluenet.node

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.bluenet.Permissions
import com.bluenet.ble.BleAnnouncement
import com.bluenet.ble.BleDiscovery
import com.bluenet.core.Log
import com.bluenet.core.crypto.Identity
import com.bluenet.core.link.Channels
import com.bluenet.core.link.SecureLink
import com.bluenet.core.mesh.ChatMessage
import com.bluenet.core.mesh.MeshPayloads
import com.bluenet.core.mesh.MeshRouter
import com.bluenet.core.mesh.MeshTransport
import com.bluenet.core.mesh.MessageStore
import com.bluenet.link.LinkManager
import com.bluenet.link.TunnelEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Composition root for one device on the mesh: identity, BLE discovery, links, the message router
 * and the user's settings. Created once per process through [MeshRuntime]; started and stopped by
 * the mesh foreground service.
 *
 * [start], [stop] and the setters may be called from any thread; Bluetooth discovery work is
 * always moved to the main thread.
 */
class MeshNode internal constructor(private val context: Context) {
    enum class State { STOPPED, NO_PERMISSION, NO_BLUETOOTH, BLUETOOTH_OFF, RUNNING }

    data class Status(val state: State, val canAdvertise: Boolean = true, val canAcceptLinks: Boolean = true)

    private val settings = Settings(context)
    val identity: Identity = settings.loadOrCreateIdentity()
    val peerId: String get() = identity.peerId
    val messages = MessageStore()

    private val _status = MutableStateFlow(Status(State.STOPPED))
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _nickname = MutableStateFlow(settings.nickname(identity.peerId))
    val nickname: StateFlow<String> = _nickname.asStateFlow()
    private val _sharing = MutableStateFlow(settings.sharing)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()
    private val _linkedCount = MutableStateFlow(0)
    val linkedCount: StateFlow<Int> = _linkedCount.asStateFlow()
    private val _sharingClients = MutableStateFlow(0)
    /** Number of linked peers currently tunnelling through this device. */
    val sharingClients: StateFlow<Int> = _sharingClients.asStateFlow()

    var lastSharerId: String?
        get() = settings.lastSharerId
        set(value) { settings.lastSharerId = value }

    private val contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Main-thread state.
    private var wanted = false
    private var receiverRegistered = false
    private var session: Job? = null
    private var discovery: BleDiscovery? = null
    private val firstSeen = HashMap<String, Long>()

    @Volatile private var links: LinkManager? = null
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val autoConnecting = AtomicInteger()
    private val tunnelEndpoints = ConcurrentHashMap<String, TunnelEndpoint>()

    private val router = MeshRouter(identity, object : MeshTransport {
        override fun neighbors(): Set<String> = links?.links?.value.orEmpty()
        override fun sendTo(peerId: String, packet: ByteArray): Boolean = links?.send(peerId, Channels.MESH, packet) ?: false
        override fun broadcast(packet: ByteArray, exceptPeerId: String?) {
            links?.broadcast(Channels.MESH, packet, exceptPeerId)
        }
    })

    init {
        router.listener = object : MeshRouter.Listener {
            override fun onText(message: ChatMessage) = messages.add(message)
            override fun onDelivered(messageId: String) = messages.markDelivered(messageId)
            override fun onAnnounce(peerId: String, nickname: String, isSharingInternet: Boolean, hops: Int) {
                contacts.update { it + (peerId to Contact(peerId, nickname, isSharingInternet, hops, System.currentTimeMillis())) }
            }
        }
    }

    fun start() = onMain {
        wanted = true
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        bringUp()
    }

    fun stop() = onMain {
        wanted = false
        tearDown()
        if (receiverRegistered) {
            context.unregisterReceiver(bluetoothStateReceiver)
            receiverRegistered = false
        }
        _status.value = Status(State.STOPPED)
    }

    /** Sends a chat message to [recipientId], or to everyone when null. */
    fun sendText(recipientId: String?, text: String) {
        val body = String(MeshPayloads.truncateUtf8(text.trim(), MeshPayloads.MAX_TEXT_BYTES), Charsets.UTF_8)
        if (body.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            messages.add(router.sendText(recipientId, _nickname.value, body))
        }
    }

    fun setNickname(value: String) {
        val name = Settings.sanitizeNickname(value)
        if (name.isEmpty() || name == _nickname.value) return
        settings.setNickname(name)
        _nickname.value = name
        refreshAnnouncements()
    }

    /** Returns false if sharing can't be enabled because this device is using someone else's internet. */
    fun setSharing(enabled: Boolean): Boolean {
        if (enabled && MeshRuntime.tunnel.value.isActive) return false
        settings.sharing = enabled
        _sharing.value = enabled
        links?.sharing = enabled
        refreshAnnouncements()
        return true
    }

    /** Blocking: returns a link to [peerId], connecting if it is in BLE range. Never call on the main thread. */
    fun linkTo(peerId: String): SecureLink? {
        val manager = links ?: return null
        manager.link(peerId)?.let { return it }
        val sighting = discovery?.sightings?.value?.get(peerId) ?: return null
        return manager.connect(peerId, sighting.device, sighting.announcement.psm)
    }

    /** Routes tunnel frames arriving from [peerId] to [endpoint], across link reconnects. */
    fun attachTunnel(peerId: String, endpoint: TunnelEndpoint) {
        tunnelEndpoints[peerId] = endpoint
    }

    fun detachTunnel(peerId: String, endpoint: TunnelEndpoint) {
        tunnelEndpoints.remove(peerId, endpoint)
    }

    fun peer(peerId: String): Peer? = _peers.value.firstOrNull { it.id == peerId }

    private fun bringUp() {
        if (session != null) return
        val adapter = adapter
        when {
            !Permissions.hasBluetooth(context) -> { _status.value = Status(State.NO_PERMISSION); return }
            adapter == null -> { _status.value = Status(State.NO_BLUETOOTH); return }
            !adapter.isEnabled -> { _status.value = Status(State.BLUETOOTH_OFF); return }
        }
        adapter!!

        val manager = LinkManager(adapter, identity, router::onPacket, ::onLinkOpened, tunnelEndpoints::get)
        manager.sharing = _sharing.value
        val accepting = manager.start()
        val ble = BleDiscovery(adapter, identity.peerId)
        links = manager
        discovery = ble
        ble.start(announcement())
        _status.value = Status(State.RUNNING, canAcceptLinks = accepting)

        session = scope.launch {
            launch {
                combine(ble.sightings, manager.links, contacts) { sightings, linked, known ->
                    PeerDirectory.merge(
                        sightings.values.map {
                            NearbyPeer(it.peerId, it.announcement.name, it.announcement.isSharingInternet, it.rssi)
                        },
                        known.values,
                        linked,
                    )
                }.collect { _peers.value = it }
            }
            launch {
                combine(ble.sightings, manager.links) { _, linked -> linked.size }.collect {
                    _linkedCount.value = it
                    autoLink()
                }
            }
            launch {
                ble.advertiseState.collect { state ->
                    val canAdvertise = state != BleDiscovery.AdvertiseState.UNSUPPORTED && state != BleDiscovery.AdvertiseState.FAILED
                    _status.update { if (it.state == State.RUNNING) it.copy(canAdvertise = canAdvertise) else it }
                }
            }
            launch {
                while (isActive) {
                    announceNow()
                    delay(ANNOUNCE_INTERVAL_MS)
                }
            }
            launch {
                while (isActive) {
                    delay(HOUSEKEEPING_INTERVAL_MS)
                    contacts.update { PeerDirectory.expire(it, System.currentTimeMillis()) }
                    _sharingClients.value = manager.tunnelHostCount()
                    autoLink()
                }
            }
        }
        Log.d(TAG, "Mesh node ${identity.peerId} up (accepting=$accepting)")
    }

    private fun tearDown() {
        session?.cancel()
        session = null
        discovery?.stop()
        discovery = null
        links?.stop()
        links = null
        firstSeen.clear()
        _peers.value = PeerDirectory.merge(emptyList(), contacts.value.values, emptySet())
        _linkedCount.value = 0
        _sharingClients.value = 0
    }

    /**
     * Keeps a few links open so the mesh stays connected. The peer with the lower ID dials; the
     * other side only dials if no link appears after a grace period (e.g. one-way radio range).
     */
    private fun autoLink() {
        val manager = links ?: return
        val ble = discovery ?: return
        val linked = manager.links.value
        val sightings = ble.sightings.value
        val now = SystemClock.elapsedRealtime()
        firstSeen.keys.retainAll(sightings.keys)
        sightings.keys.forEach { firstSeen.putIfAbsent(it, now) }
        if (linked.size >= AUTO_LINK_TARGET || autoConnecting.get() > 0) return

        val candidate = sightings.values
            .filter { it.peerId !in linked && !manager.isConnecting(it.peerId) && (retryAt[it.peerId] ?: 0L) <= now }
            .filter { identity.peerId < it.peerId || now - (firstSeen[it.peerId] ?: now) >= HIGHER_ID_GRACE_MS }
            .maxByOrNull { it.rssi } ?: return

        autoConnecting.incrementAndGet()
        manager.connectAsync(candidate.peerId, candidate.device, candidate.announcement.psm) { link ->
            autoConnecting.decrementAndGet()
            if (link == null) retryAt[candidate.peerId] = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
            main.post { autoLink() }
        }
    }

    private fun onLinkOpened(peerId: String) {
        retryAt.remove(peerId)
        router.announce(_nickname.value, _sharing.value)
        main.post { discovery?.restartAdvertising() }
    }

    private fun refreshAnnouncements() {
        onMain { discovery?.update(announcement()) }
        scope.launch { announceNow() }
    }

    private suspend fun announceNow() {
        if (links == null) return
        withContext(Dispatchers.IO) { router.announce(_nickname.value, _sharing.value) }
    }

    private fun announcement() = BleAnnouncement(identity.peerId, _sharing.value, links?.psm ?: 0, _nickname.value)

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> if (wanted) bringUp()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    tearDown()
                    if (wanted) _status.value = Status(State.BLUETOOTH_OFF)
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val TAG = "MeshNode"
        private const val AUTO_LINK_TARGET = 4
        private const val HIGHER_ID_GRACE_MS = 20_000L
        private const val RETRY_BACKOFF_MS = 30_000L
        private const val ANNOUNCE_INTERVAL_MS = 30_000L
        private const val HOUSEKEEPING_INTERVAL_MS = 10_000L
    }
}
