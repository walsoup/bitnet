package com.bluenet.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bluenet.bluetooth.L2capClient
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

    // Client-side (consuming) components
    private var l2capClient: L2capClient? = null

    // Callbacks for status forwarding
    var onSharingStatusChanged: ((String, Int) -> Unit)? = null

    fun start(peerId: String, displayName: String) {
        val isSharing = _isSharingInternet.value
        advertiser.start(peerId, displayName, isSharing, currentPsm)
        scanner.start()

        // If sharing was previously enabled, restart the host server
        if (isSharing) {
            startHostServer()
        }
    }

    fun stop() {
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
        if (l2capServer != null) return // Already running

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Cannot start host server: Bluetooth disabled")
            return
        }

        // Start HostService for foreground notification
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
            // Update advertisement with new PSM
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

        val device = bluetoothAdapter.getRemoteDevice(peer.macAddress)
        if (device == null) {
            Log.e(TAG, "Device not found: ${peer.macAddress}")
            _connectionState.value = ConnectionState.DISCONNECTED
            _connectedPeer.value = null
            return
        }

        PreferencesManager.saveLastConnectedPeerId(context, peer.peerId)
        PreferencesManager.saveClientConnection(context, peer.macAddress, peer.psm, false)

        // Start VPN service
        val vpnIntent = Intent(context, BlueNetVpnService::class.java)
        ContextCompat.startForegroundService(context, vpnIntent)

        l2capClient = L2capClient(
            context = context,
            device = device,
            psm = peer.psm,
            onConnected = { socket ->
                Log.d(TAG, "Connected to peer ${peer.displayName} at ${peer.macAddress}")
                _connectionState.value = ConnectionState.CONNECTED

                // The VpnService will set up the TUN tunnel using the connected socket
                // We send a connectToHost call to the VPN service with the peer's details
                val vpnService = Intent(context, BlueNetVpnService::class.java)
                vpnService.putExtra("peer_mac", peer.macAddress)
                vpnService.putExtra("peer_psm", peer.psm)
                context.startService(vpnService)
            },
            onError = { err ->
                Log.e(TAG, "Connection to peer failed: $err")
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedPeer.value = null
            }
        )
        l2capClient?.connect(false)
    }

    fun disconnect() {
        disconnectInternal()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPeer.value = null
    }

    private fun disconnectInternal() {
        l2capClient?.disconnect()
        l2capClient = null

        // Stop VPN service
        val vpnIntent = Intent(context, BlueNetVpnService::class.java).apply {
            action = BlueNetVpnService.ACTION_DISCONNECT_VPN
        }
        context.startService(vpnIntent)
    }
}
