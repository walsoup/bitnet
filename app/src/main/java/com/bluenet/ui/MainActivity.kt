package com.bluenet.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluenet.Permissions
import com.bluenet.R
import com.bluenet.node.MeshNode
import com.bluenet.node.MeshRuntime
import com.bluenet.node.Peer
import com.bluenet.node.TunnelStatus
import com.bluenet.service.BlueNetVpnService
import com.bluenet.service.MeshService
import com.walsoup.linen.LinenTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val node: MeshNode by lazy { MeshRuntime.node(this) }
    private val hasPermissions = MutableStateFlow(false)

    private var askedForPermissions = false
    private var pendingVpnPeerId: String? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermissions.value = Permissions.hasBluetooth(this)
        if (hasPermissions.value) MeshService.start(this) else toast(R.string.permission_denied)
    }

    private val vpnConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val peerId = pendingVpnPeerId
        pendingVpnPeerId = null
        if (result.resultCode == RESULT_OK && peerId != null) startTunnel(peerId) else toast(R.string.vpn_denied)
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askedForPermissions = savedInstanceState?.getBoolean(STATE_ASKED) ?: false
        pendingVpnPeerId = savedInstanceState?.getString(STATE_PENDING_VPN)
        hasPermissions.value = Permissions.hasBluetooth(this)

        val actions = MainActions(
            onGrantPermissions = ::requestPermissionsOrOpenSettings,
            onEnableBluetooth = ::requestEnableBluetooth,
            onSharingChange = ::setSharing,
            onSaveName = node::setNickname,
            onOpenChat = ::openChat,
            onUseInternet = ::useInternet,
            onDisconnect = ::disconnect,
        )

        setContent {
            val status by node.status.collectAsStateWithLifecycle()
            val permitted by hasPermissions.collectAsStateWithLifecycle()
            val nickname by node.nickname.collectAsStateWithLifecycle()
            val sharing by node.sharing.collectAsStateWithLifecycle()
            val sharingClients by node.sharingClients.collectAsStateWithLifecycle()
            val peers by node.peers.collectAsStateWithLifecycle()
            val linked by node.linkedCount.collectAsStateWithLifecycle()
            val tunnel by MeshRuntime.tunnel.collectAsStateWithLifecycle()

            LinenTheme {
                MainScreen(
                    state = MainUiState(
                        status = status,
                        hasPermissions = permitted,
                        nickname = nickname,
                        peerId = node.peerId,
                        sharing = sharing,
                        sharingClients = sharingClients,
                        peers = peers,
                        linkedCount = linked,
                        tunnel = tunnel,
                    ),
                    actions = actions,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        hasPermissions.value = Permissions.hasBluetooth(this)
        if (hasPermissions.value) {
            MeshService.start(this)
        } else if (!askedForPermissions) {
            askedForPermissions = true
            permissionLauncher.launch(Permissions.requested)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ASKED, askedForPermissions)
        outState.putString(STATE_PENDING_VPN, pendingVpnPeerId)
    }

    private fun setSharing(enabled: Boolean) {
        when {
            !node.setSharing(enabled) -> toast(R.string.share_blocked_while_connected)
            enabled -> MeshService.start(this)
        }
    }

    private fun openChat(peer: Peer?) {
        startActivity(ChatActivity.intent(this, peer?.id, peer?.name ?: getString(R.string.broadcast_chat)))
    }

    private fun useInternet(peer: Peer) {
        if (node.sharing.value) {
            toast(R.string.connect_blocked_while_sharing)
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            pendingVpnPeerId = peer.id
            vpnConsentLauncher.launch(consent)
        } else {
            startTunnel(peer.id)
        }
    }

    private fun startTunnel(peerId: String) {
        node.lastSharerId = peerId
        BlueNetVpnService.connect(this, peerId)
    }

    private fun disconnect() {
        if (MeshRuntime.tunnel.value.isActive) {
            BlueNetVpnService.disconnect(this)
        } else {
            MeshRuntime.publishTunnel(TunnelStatus())
        }
    }

    private fun requestPermissionsOrOpenSettings() {
        val canAsk = !askedForPermissions ||
            Permissions.requested.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (canAsk) {
            askedForPermissions = true
            permissionLauncher.launch(Permissions.requested)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun requestEnableBluetooth() {
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_ASKED = "asked_permissions"
        private const val STATE_PENDING_VPN = "pending_vpn_peer"
    }
}
