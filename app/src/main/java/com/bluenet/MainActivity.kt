package com.bluenet

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluenet.client.BlueNetVpnService
import com.bluenet.databinding.ActivityMainBinding
import com.bluenet.host.HostService
import com.bluenet.mesh.ConnectionState
import com.bluenet.mesh.MeshPeer
import com.bluenet.mesh.MeshService
import com.bluenet.utils.MeshPeerAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var meshService: MeshService? = null
    private var isMeshBound = false

    private var vpnService: BlueNetVpnService? = null
    private var isVpnBound = false
    
    private var hostService: HostService? = null
    private var isHostBound = false

    private lateinit var peerAdapter: MeshPeerAdapter
    
    private var pendingPeerToConnect: MeshPeer? = null

    private val meshServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.MeshBinder
            meshService = binder.getService()
            isMeshBound = true
            observeMeshState()
            restartMeshService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isMeshBound = false
        }
    }

    private val vpnServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BlueNetVpnService.VpnBinder
            vpnService = binder.getService()
            isVpnBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            isVpnBound = false
        }
    }
    
    private val hostServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HostService.HostBinder
            hostService = binder.getService()
            isHostBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostService = null
            isHostBound = false
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingPeerToConnect?.let {
                meshService?.meshManager?.connectToPeer(it)
                pendingPeerToConnect = null
            }
        } else {
            Toast.makeText(this, "VPN Permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        requestBluetoothPermissions()

        val meshIntent = Intent(this, MeshService::class.java)
        bindService(meshIntent, meshServiceConnection, Context.BIND_AUTO_CREATE)

        val vpnIntent = Intent(this, BlueNetVpnService::class.java)
        bindService(vpnIntent, vpnServiceConnection, Context.BIND_AUTO_CREATE)
        
        val hostIntent = Intent(this, HostService::class.java)
        bindService(hostIntent, hostServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupRecyclerView() {
        peerAdapter = MeshPeerAdapter(
            onConnectClicked = { peer ->
                triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
                connectToPeer(peer)
            },
            onChatClicked = { peer ->
                triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_PEER_ID, peer.peerId)
                    putExtra(ChatActivity.EXTRA_PEER_NAME, peer.displayName)
                }
                startActivity(intent)
            }
        )
        binding.rvPeers.layoutManager = LinearLayoutManager(this)
        binding.rvPeers.adapter = peerAdapter
    }

    private fun setupListeners() {
        binding.swShareInternet.setOnCheckedChangeListener { _, isChecked ->
            triggerHaptic(HapticFeedbackConstants.CLOCK_TICK)
            meshService?.meshManager?.setInternetSharing(isChecked)
        }

        binding.btnDisconnect.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            meshService?.meshManager?.disconnect()
        }
    }

    private fun observeMeshState() {
        val meshManager = meshService?.meshManager ?: return

        lifecycleScope.launch {
            meshManager.peers.collect { peers ->
                peerAdapter.submitList(peers)
                binding.chipPeerCount.text = getString(R.string.chip_peers_count, peers.size)
                if (peers.isEmpty()) {
                    binding.tvNoPeers.visibility = View.VISIBLE
                    binding.rvPeers.visibility = View.GONE
                } else {
                    binding.tvNoPeers.visibility = View.GONE
                    binding.rvPeers.visibility = View.VISIBLE
                }
            }
        }

        lifecycleScope.launch {
            meshManager.isSharingInternet.collect { isSharing ->
                if (binding.swShareInternet.isChecked != isSharing) {
                    binding.swShareInternet.isChecked = isSharing
                }
                
                binding.layoutSharingStats.visibility = if (isSharing) View.VISIBLE else View.GONE
                
                if (isSharing) {
                    val psm = hostService?.currentPsm ?: -1
                    if (psm > 0) {
                        binding.tvSharingPsm.text = getString(R.string.sharing_on_psm, psm)
                    } else {
                        binding.tvSharingPsm.text = "Sharing Internet..."
                    }
                }
            }
        }

        lifecycleScope.launch {
            meshManager.connectionState.collect { state ->
                val greenColor = ContextCompat.getColor(this@MainActivity, R.color.status_green)
                val amberColor = ContextCompat.getColor(this@MainActivity, R.color.status_amber)
                
                when (state) {
                    ConnectionState.DISCONNECTED -> {
                        val isSharing = meshManager.isSharingInternet.value
                        if (isSharing) {
                            binding.tvGlobalStatus.text = getString(R.string.status_sharing)
                            binding.ivStatusDot.setColorFilter(greenColor)
                        } else {
                            binding.tvGlobalStatus.text = getString(R.string.status_scanning)
                            binding.ivStatusDot.setColorFilter(amberColor)
                        }
                        binding.cardConnectionStatus.visibility = View.GONE
                    }
                    ConnectionState.CONNECTING -> {
                        binding.tvGlobalStatus.text = "Connecting..."
                        binding.ivStatusDot.setColorFilter(amberColor)
                        binding.cardConnectionStatus.visibility = View.GONE
                    }
                    ConnectionState.CONNECTED -> {
                        binding.tvGlobalStatus.text = getString(R.string.status_connected)
                        binding.ivStatusDot.setColorFilter(greenColor)
                        binding.cardConnectionStatus.visibility = View.VISIBLE
                    }
                }
            }
        }

        lifecycleScope.launch {
            meshManager.connectedPeer.collect { peer ->
                if (peer != null) {
                    binding.tvConnectedPeerName.text = getString(R.string.connected_to, peer.displayName)
                    binding.tvConnectedPeerAddress.text = peer.macAddress
                }
            }
        }
    }

    private fun connectToPeer(peer: MeshPeer) {
        pendingPeerToConnect = peer
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            meshService?.meshManager?.connectToPeer(peer)
            pendingPeerToConnect = null
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        if (permissionsMap.values.any { it }) {
            restartMeshService()
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        } else {
            restartMeshService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            restartMeshService()
        }
    }

    private fun restartMeshService() {
        val peerId = com.bluenet.utils.PreferencesManager.getPeerId(this)
        val displayName = com.bluenet.utils.PreferencesManager.getDisplayName(this)
        meshService?.meshManager?.start(peerId, displayName)
    }

    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastHostTx = 0L
    private var lastHostRx = 0L
    private var lastClientTx = 0L
    private var lastClientRx = 0L
    private var lastStatsTime = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            updateTrafficStats()
            statsHandler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        statsHandler.post(statsRunnable)
    }

    override fun onPause() {
        statsHandler.removeCallbacks(statsRunnable)
        super.onPause()
    }

    private fun updateTrafficStats() {
        val now = System.currentTimeMillis()
        val timeDiffSec = if (lastStatsTime > 0) (now - lastStatsTime) / 1000.0 else 1.0
        val dt = if (timeDiffSec <= 0) 1.0 else timeDiffSec
        lastStatsTime = now

        val isSharing = meshService?.meshManager?.isSharingInternet?.value == true
        if (isSharing) {
            val manager = meshService?.meshManager
            val currentTx = manager?.getTxBytes() ?: 0L
            val currentRx = manager?.getRxBytes() ?: 0L
            val txSpeed = maxOf(0L, ((currentTx - lastHostTx) / dt).toLong())
            val rxSpeed = maxOf(0L, ((currentRx - lastHostRx) / dt).toLong())
            lastHostTx = currentTx
            lastHostRx = currentRx

            binding.tvShareTxSpeed.text = formatSpeed(txSpeed)
            binding.tvShareTxTotal.text = "Total: ${formatBytes(currentTx)}"
            binding.tvShareRxSpeed.text = formatSpeed(rxSpeed)
            binding.tvShareRxTotal.text = "Total: ${formatBytes(currentRx)}"
            
            val psm = manager?.currentPsm ?: -1
            if (psm > 0) {
                binding.tvSharingPsm.text = getString(R.string.sharing_on_psm, psm)
            }
        } else {
            lastHostTx = 0L
            lastHostRx = 0L
        }

        val isConnected = meshService?.meshManager?.connectionState?.value == ConnectionState.CONNECTED
        if (isConnected && isVpnBound) {
            val currentTx = vpnService?.getTxBytes() ?: 0L
            val currentRx = vpnService?.getRxBytes() ?: 0L
            val txSpeed = maxOf(0L, ((currentTx - lastClientTx) / dt).toLong())
            val rxSpeed = maxOf(0L, ((currentRx - lastClientRx) / dt).toLong())
            lastClientTx = currentTx
            lastClientRx = currentRx

            binding.tvClientTxSpeed.text = formatSpeed(txSpeed)
            binding.tvClientTxTotal.text = "Total: ${formatBytes(currentTx)}"
            binding.tvClientRxSpeed.text = formatSpeed(rxSpeed)
            binding.tvClientRxTotal.text = "Total: ${formatBytes(currentRx)}"
        } else {
            lastClientTx = 0L
            lastClientRx = 0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }
    
    private fun triggerHaptic(type: Int = HapticFeedbackConstants.CONTEXT_CLICK) {
        try {
            binding.root.performHapticFeedback(type)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(25)
                }
            }
        } catch (_: Exception) {}
    }

    private fun triggerStateChangeHaptic(isActivation: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = if (isActivation) longArrayOf(0, 40, 60, 40) else longArrayOf(0, 70)
                    val amplitudes = if (isActivation) intArrayOf(0, 200, 0, 255) else intArrayOf(0, 180)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsRunnable)
        if (isMeshBound) {
            unbindService(meshServiceConnection)
            isMeshBound = false
        }
        if (isVpnBound) {
            unbindService(vpnServiceConnection)
            isVpnBound = false
        }
        if (isHostBound) {
            unbindService(hostServiceConnection)
            isHostBound = false
        }
        super.onDestroy()
    }
}
