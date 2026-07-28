package com.bluenet.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bluenet.bluetooth.L2capServer
import com.bluenet.client.BlueNetVpnService
import com.bluenet.host.HostProxyManager
import com.bluenet.host.HostService
import com.bluenet.multiplexer.StreamMultiplexer
import com.bluenet.utils.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class MeshManager(private val context: Context, private val bluetoothAdapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "MeshManager"
    }

    private val advertiser = MeshAdvertiser(bluetoothAdapter)
    private val scanner = MeshScanner(bluetoothAdapter)

    val peers: StateFlow<List<MeshPeer>> = scanner.peers

    private val _isSharingInternet = MutableStateFlow(PreferencesManager.isSharingInternet(context))
    val isSharingInternet: StateFlow<Boolean> = _isSharingInternet.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedPeer = MutableStateFlow<MeshPeer?>(null)
    val connectedPeer: StateFlow<MeshPeer?> = _connectedPeer.asStateFlow()

    // Host-side (sharing) components
    private var l2capServer: L2capServer? = null
    private var activeMultiplexer: StreamMultiplexer? = null
    private var hostProxyManager: HostProxyManager? = null
    var currentPsm: Int = -1
        private set

    // Callbacks for status forwarding
    var onSharingStatusChanged: ((String, Int) -> Unit)? = null

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BlueNetVpnService.ACTION_VPN_STATE_CHANGED) {
                when (intent.getStringExtra(BlueNetVpnService.EXTRA_STATE)) {
                    BlueNetVpnService.STATE_CONNECTING -> {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                    BlueNetVpnService.STATE_CONNECTED -> {
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    BlueNetVpnService.STATE_DISCONNECTED -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _connectedPeer.value = null
                    }
                }
            }
        }
    }

    private var isReceiverRegistered = false

    fun start(peerId: String, displayName: String) {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(BlueNetVpnService.ACTION_VPN_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(vpnStateReceiver, filter)
            }
            isReceiverRegistered = true
        }

        val isSharing = _isSharingInternet.value
        advertiser.start(peerId, displayName, isSharing, currentPsm)
        scanner.start()

        if (isSharing) {
            startHostServer()
        }
    }

    fun stop() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(vpnStateReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        advertiser.stop()
        scanner.stop()
        stopHostServer()
        disconnectInternal()
    }

    @SuppressLint("MissingPermission")
    fun setInternetSharing(enabled: Boolean) {
        _isSharingInternet.value = enabled
        PreferencesManager.setSharingInternet(context, enabled)

        if (enabled) {
            startHostServer()
        } else {
            stopHostServer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHostServer() {
        if (l2capServer != null) return

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Cannot start host server: Bluetooth disabled")
            return
        }

        val hostIntent = Intent(context, HostService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, hostIntent)
        } else {
            context.startService(hostIntent)
        }

        l2capServer = L2capServer(
            bluetoothAdapter = bluetoothAdapter,
            onClientConnected = { socket ->
                Log.d(TAG, "Client connected from: ${socket.remoteDevice.address}")
                onSharingStatusChanged?.invoke("Client Connected (${socket.remoteDevice.address})", currentPsm)

                val multiplexer = StreamMultiplexer(
                    inputStream = socket.inputStream,
                    outputStream = socket.outputStream,
                    onFrameReceived = { frame -> hostProxyManager?.handleFrame(frame) },
                    onError = { ex ->
                        Log.e(TAG, "Host multiplexer connection dropped", ex)
                        onSharingStatusChanged?.invoke("Client Disconnected", currentPsm)
                    }
                )
                activeMultiplexer = multiplexer
                hostProxyManager = HostProxyManager(multiplexer)
                multiplexer.start()
            },
            onError = { err ->
                Log.e(TAG, "L2CAP Server error: $err")
                onSharingStatusChanged?.invoke("Error: $err", currentPsm)
            }
        )

        val success = l2capServer?.start() == true
        if (success) {
            currentPsm = l2capServer?.psm ?: -1
            Log.d(TAG, "Host server started on PSM: $currentPsm")
            advertiser.updateAdvertisement(true, currentPsm)
            onSharingStatusChanged?.invoke("Sharing on PSM: $currentPsm", currentPsm)
        } else {
            Log.e(TAG, "Failed to start L2CAP server")
            l2capServer = null
            _isSharingInternet.value = false
            PreferencesManager.setSharingInternet(context, false)
        }
    }

    private fun stopHostServer() {
        activeMultiplexer?.close()
        activeMultiplexer = null
        hostProxyManager?.closeAll()
        hostProxyManager = null
        l2capServer?.stop()
        l2capServer = null
        currentPsm = -1
        advertiser.updateAdvertisement(false, 0)

        val stopHostIntent = Intent(context, HostService::class.java).apply {
            action = HostService.ACTION_STOP_HOST
        }
        context.startService(stopHostIntent)
    }

    fun getTxBytes(): Long = activeMultiplexer?.getTxBytes() ?: 0L
    fun getRxBytes(): Long = activeMultiplexer?.getRxBytes() ?: 0L

    @SuppressLint("MissingPermission")
    fun connectToPeer(peer: MeshPeer) {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) {
            Log.w(TAG, "Already connecting/connected, disconnect first")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Cannot connect: Bluetooth disabled")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        _connectedPeer.value = peer

        PreferencesManager.saveLastConnectedPeerId(context, peer.peerId)
        PreferencesManager.saveClientConnection(context, peer.macAddress, peer.psm, false)

        val vpnIntent = Intent(context, BlueNetVpnService::class.java).apply {
            action = BlueNetVpnService.ACTION_CONNECT_VPN
            putExtra(BlueNetVpnService.EXTRA_PEER_MAC, peer.macAddress)
            putExtra(BlueNetVpnService.EXTRA_PEER_PSM, peer.psm)
        }
        ContextCompat.startForegroundService(context, vpnIntent)
    }

    fun disconnect() {
        disconnectInternal()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPeer.value = null
    }

    private fun disconnectInternal() {
        val vpnIntent = Intent(context, BlueNetVpnService::class.java).apply {
            action = BlueNetVpnService.ACTION_DISCONNECT_VPN
        }
        context.startService(vpnIntent)
    }
}
