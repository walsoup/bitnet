package com.bluenet.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluenet.Permissions
import com.bluenet.R
import com.bluenet.core.PeerIds
import com.bluenet.core.mesh.MeshPayloads
import com.bluenet.databinding.ActivityMainBinding
import com.bluenet.node.MeshNode
import com.bluenet.node.MeshRuntime
import com.bluenet.node.Peer
import com.bluenet.node.TunnelStatus
import com.bluenet.service.BlueNetVpnService
import com.bluenet.service.MeshService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val node: MeshNode by lazy { MeshRuntime.node(this) }
    private lateinit var peerAdapter: PeerAdapter

    private var askedForPermissions = false
    private var pendingVpnPeerId: String? = null
    private var updatingShareSwitch = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.hasBluetooth(this)) {
            MeshService.start(this)
        } else {
            toast(R.string.permission_denied)
        }
        renderBanner(node.status.value)
    }

    private val vpnConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val peerId = pendingVpnPeerId
        pendingVpnPeerId = null
        if (result.resultCode == RESULT_OK && peerId != null) startTunnel(peerId) else toast(R.string.vpn_denied)
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        askedForPermissions = savedInstanceState?.getBoolean(STATE_ASKED) ?: false
        pendingVpnPeerId = savedInstanceState?.getString(STATE_PENDING_VPN)

        peerAdapter = PeerAdapter(onChat = ::openChat, onUseInternet = ::useInternet)
        binding.peerList.layoutManager = LinearLayoutManager(this)
        binding.peerList.adapter = peerAdapter
        binding.peerList.itemAnimator = null

        binding.editNameButton.setOnClickListener { showNameDialog() }
        binding.broadcastRow.setOnClickListener {
            startActivity(ChatActivity.intent(this, null, getString(R.string.broadcast_chat)))
        }
        binding.shareSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingShareSwitch) return@setOnCheckedChangeListener
            when {
                !node.setSharing(checked) -> {
                    toast(R.string.share_blocked_while_connected)
                    setShareSwitch(!checked)
                }
                checked -> MeshService.start(this)
            }
        }
        binding.disconnectButton.setOnClickListener {
            if (MeshRuntime.tunnel.value.isActive) {
                BlueNetVpnService.disconnect(this)
            } else {
                MeshRuntime.publishTunnel(TunnelStatus())
            }
        }

        observe()
    }

    override fun onStart() {
        super.onStart()
        if (Permissions.hasBluetooth(this)) {
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

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(node.status, node.peers, node.linkedCount) { status, peers, linked ->
                        Triple(status, peers.count { it.nearby }, linked)
                    }.collect { (status, nearby, linked) ->
                        renderStatus(status, nearby, linked)
                        renderBanner(status)
                    }
                }
                launch {
                    node.nickname.collect { name ->
                        binding.identityText.text = getString(R.string.you_are, name) + " · " +
                            getString(R.string.your_id, PeerIds.short(node.peerId))
                    }
                }
                launch {
                    combine(node.sharing, node.sharingClients, MeshRuntime.tunnel) { sharing, clients, tunnel ->
                        Triple(sharing, clients, tunnel)
                    }.collect { (sharing, clients, tunnel) -> renderSharing(sharing, clients, tunnel) }
                }
                launch {
                    node.peers.collect { peers ->
                        peerAdapter.submitList(peers)
                        binding.emptyText.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch { MeshRuntime.tunnel.collect(::renderTunnel) }
            }
        }
    }

    private fun renderStatus(status: MeshNode.Status, nearby: Int, linked: Int) {
        val (text, background) = when (status.state) {
            MeshNode.State.RUNNING -> getString(R.string.status_running, nearby, linked) to R.drawable.bg_chip_success
            MeshNode.State.BLUETOOTH_OFF -> getString(R.string.status_bluetooth_off) to R.drawable.bg_chip
            MeshNode.State.NO_PERMISSION -> getString(R.string.status_permissions) to R.drawable.bg_chip
            MeshNode.State.NO_BLUETOOTH -> getString(R.string.status_no_bluetooth) to R.drawable.bg_chip
            MeshNode.State.STOPPED -> getString(R.string.status_stopped) to R.drawable.bg_chip
        }
        binding.statusChip.text = text
        binding.statusChip.setBackgroundResource(background)
        binding.statusChip.setTextColor(
            getColor(if (status.state == MeshNode.State.RUNNING) R.color.bn_success else R.color.bn_on_surface_variant)
        )
    }

    private fun renderBanner(status: MeshNode.Status) {
        val hasPermissions = Permissions.hasBluetooth(this)
        val (text, action) = when {
            !hasPermissions -> getString(R.string.permission_rationale) to getString(R.string.grant)
            status.state == MeshNode.State.BLUETOOTH_OFF -> getString(R.string.status_bluetooth_off) to getString(R.string.enable_bluetooth)
            status.state == MeshNode.State.NO_BLUETOOTH -> getString(R.string.status_no_bluetooth) to null
            status.state == MeshNode.State.RUNNING && (!status.canAcceptLinks || !status.canAdvertise) ->
                getString(R.string.status_unsupported) to null
            else -> null to null
        }
        binding.bannerCard.visibility = if (text != null) View.VISIBLE else View.GONE
        binding.bannerText.text = text
        binding.bannerButton.visibility = if (action != null) View.VISIBLE else View.GONE
        binding.bannerButton.text = action
        binding.bannerButton.setOnClickListener {
            if (!Permissions.hasBluetooth(this)) requestPermissionsOrOpenSettings() else requestEnableBluetooth()
        }
    }

    private fun renderSharing(sharing: Boolean, clients: Int, tunnel: TunnelStatus) {
        setShareSwitch(sharing)
        binding.shareSwitch.isEnabled = !tunnel.isActive
        binding.shareSubtitle.text = when {
            tunnel.isActive -> getString(R.string.share_blocked_while_connected)
            sharing -> getString(R.string.share_active, clients)
            else -> getString(R.string.share_subtitle)
        }
    }

    private fun renderTunnel(tunnel: TunnelStatus) {
        peerAdapter.tunnelPeerId = if (tunnel.isActive) tunnel.peerId else null
        binding.tunnelCard.visibility = if (tunnel.state == TunnelStatus.State.IDLE) View.GONE else View.VISIBLE
        val name = tunnel.peerName ?: ""
        binding.tunnelStatusText.text = when (tunnel.state) {
            TunnelStatus.State.IDLE -> ""
            TunnelStatus.State.CONNECTING -> getString(R.string.tunnel_connecting, name)
            TunnelStatus.State.CONNECTED -> getString(R.string.tunnel_connected, name)
            TunnelStatus.State.RECONNECTING -> getString(R.string.tunnel_reconnecting, tunnel.attempt + 1, BlueNetVpnService.MAX_ATTEMPTS)
            TunnelStatus.State.FAILED -> getString(R.string.tunnel_failed, tunnel.error ?: "")
        }
        val showStats = tunnel.state == TunnelStatus.State.CONNECTED || tunnel.state == TunnelStatus.State.RECONNECTING
        binding.tunnelStatsText.visibility = if (showStats) View.VISIBLE else View.INVISIBLE
        binding.tunnelStatsText.text = getString(
            R.string.tunnel_stats,
            Formatter.formatShortFileSize(this, tunnel.bytesUp),
            Formatter.formatShortFileSize(this, tunnel.bytesDown),
            tunnel.flows,
        )
        binding.disconnectButton.setText(if (tunnel.isActive) R.string.disconnect else R.string.dismiss)
    }

    private fun setShareSwitch(checked: Boolean) {
        if (binding.shareSwitch.isChecked == checked) return
        updatingShareSwitch = true
        binding.shareSwitch.isChecked = checked
        updatingShareSwitch = false
    }

    private fun openChat(peer: Peer) {
        startActivity(ChatActivity.intent(this, peer.id, peer.name))
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

    private fun showNameDialog() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(node.nickname.value)
            setSelection(text.length)
            hint = getString(R.string.edit_name_hint)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MeshPayloads.MAX_NICKNAME_BYTES))
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_name_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ -> node.setNickname(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val STATE_ASKED = "asked_permissions"
        private const val STATE_PENDING_VPN = "pending_vpn_peer"
    }
}
