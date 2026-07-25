package com.bluenet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.bluenet.client.BlueNetVpnService
import com.bluenet.databinding.ActivityMainBinding
import com.bluenet.host.HostService
import com.bluenet.utils.BluetoothUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var hostService: HostService? = null
    private var isHostBound = false

    private var vpnService: BlueNetVpnService? = null
    private var isVpnBound = false

    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private val hostServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HostService.HostBinder
            hostService = binder.getService()
            isHostBound = true
            updateHostUi()
            updateGlobalStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostService = null
            isHostBound = false
            updateHostUi()
            updateGlobalStatus()
        }
    }

    private val vpnServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BlueNetVpnService.VpnBinder
            vpnService = binder.getService()
            isVpnBound = true
            updateClientUi()
            updateGlobalStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            isVpnBound = false
            updateClientUi()
            updateGlobalStatus()
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startClientVpn()
        } else {
            Toast.makeText(this, "VPN Permission required to accelerate tethering", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModeSwitching()
        setupHostUi()
        setupClientUi()
        setupTouchBounces()

        requestBluetoothPermissions()

        val hostIntent = Intent(this, HostService::class.java)
        bindService(hostIntent, hostServiceConnection, Context.BIND_AUTO_CREATE)

        val vpnIntent = Intent(this, BlueNetVpnService::class.java)
        bindService(vpnIntent, vpnServiceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Attach tactile spring bounce & haptic feedback touch listeners to interactive controls
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchBounces() {
        val bounceViews = listOf(
            binding.btnToggleHost,
            binding.btnToggleClient,
            binding.btnCopyHostInfo,
            binding.btnRefreshDevices
        )

        for (v in bounceViews) {
            v.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        triggerHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        view.animate()
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(100)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(250)
                            .setInterpolator(OvershootInterpolator(3.0f))
                            .start()
                    }
                }
                false
            }
        }
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

    private fun setupModeSwitching() {
        binding.rgModeSelector.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            triggerHaptic(HapticFeedbackConstants.CLOCK_TICK)

            if (checkedId == R.id.btnHostMode) {
                animateCardSwitch(binding.cardClient, binding.cardHost)
            } else {
                animateCardSwitch(binding.cardHost, binding.cardClient)
                refreshPairedDevicesSafely()
            }
            updateGlobalStatus()
        }
    }

    private fun animateCardSwitch(fromCard: View, toCard: View) {
        fromCard.visibility = View.GONE
        fromCard.alpha = 0f
        fromCard.scaleX = 0.95f
        fromCard.scaleY = 0.95f

        toCard.visibility = View.VISIBLE
        toCard.alpha = 0f
        toCard.scaleX = 0.93f
        toCard.scaleY = 0.93f
        toCard.animate()
            .alpha(1.0f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Location permissions are mandatory for Bluetooth discovery on Android 10 (API 29) & Android 11 (API 30)
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
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            refreshPairedDevicesSafely()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            refreshPairedDevicesSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevicesSafely() {
        try {
            pairedDevices = BluetoothUtils.getPairedDevices(this)
            val deviceNames = if (pairedDevices.isNotEmpty()) {
                pairedDevices.map { "${it.name ?: "Bluetooth Device"} (${it.address})" }
            } else {
                listOf(getString(R.string.no_paired_devices))
            }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spDevices.adapter = adapter

            binding.spDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position >= 0 && position < pairedDevices.size) {
                        val dev = pairedDevices[position]
                        binding.etMacAddress.setText(dev.address)
                        binding.tilMacAddress.error = null
                        triggerHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } catch (_: Exception) {}
    }

    private fun setupHostUi() {
        binding.btnToggleHost.setOnClickListener {
            val service = hostService ?: return@setOnClickListener
            if (service.isServerRunning) {
                triggerStateChangeHaptic(false)
                service.stopHostServer()
                binding.layoutMacContainer.visibility = View.GONE
                updateHostUi()
                updateGlobalStatus()
            } else {
                triggerStateChangeHaptic(true)
                val intent = Intent(this, HostService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                service.startHostServer { statusText, _ ->
                    runOnUiThread {
                        updateHostUi()
                        updateGlobalStatus(statusText)
                    }
                }
            }
        }

        binding.btnCopyHostInfo.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONFIRM)
            val mac = binding.tvHostMac.text.toString()
            val psm = binding.tvHostPsm.text.toString()
            val textToCopy = "MAC: $mac\n$psm"

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("BlueNet Host Info", textToCopy)
            clipboard.setPrimaryClip(clip)

            // Pulse bounce on copy button
            binding.btnCopyHostInfo.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(120)
                .withEndAction {
                    binding.btnCopyHostInfo.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(180)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                }
                .start()

            Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClientUi() {
        refreshPairedDevicesSafely()

        binding.btnRefreshDevices.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            binding.btnRefreshDevices.animate()
                .rotationBy(360f)
                .setDuration(400)
                .start()
            refreshPairedDevicesSafely()
            Toast.makeText(this, "Refreshed paired Bluetooth devices", Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleClient.setOnClickListener {
            val service = vpnService ?: return@setOnClickListener
            if (service.isVpnConnected) {
                triggerStateChangeHaptic(false)
                service.stopVpn()
                updateClientUi()
                updateGlobalStatus()
            } else {
                triggerStateChangeHaptic(true)
                checkVpnPermissionAndConnect()
            }
        }
    }

    private fun checkVpnPermissionAndConnect() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startClientVpn()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClientVpn() {
        var targetMac = binding.etMacAddress.text.toString().trim().uppercase()
        val psmText = binding.etPsm.text.toString().trim()
        val psm = psmText.toIntOrNull() ?: 1

        if (targetMac.isEmpty()) {
            val selectedIndex = binding.spDevices.selectedItemPosition
            if (selectedIndex >= 0 && selectedIndex < pairedDevices.size) {
                targetMac = pairedDevices[selectedIndex].address
            }
        }

        if (targetMac.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            binding.tilMacAddress.error = "Enter a valid MAC address (e.g. AA:BB:CC:DD:EE:FF)"
            triggerHaptic(HapticFeedbackConstants.REJECT)
            Toast.makeText(this, "Please enter a valid MAC address or select a device", Toast.LENGTH_LONG).show()
            return
        } else {
            binding.tilMacAddress.error = null
        }

        val vpnIntent = Intent(this, BlueNetVpnService::class.java)
        startService(vpnIntent)

        val compatMode = binding.swCompatMode.isChecked
        vpnService?.connectToHost(targetMac, psm, compatMode) { statusText ->
            runOnUiThread {
                updateClientUi()
                updateGlobalStatus(statusText)
            }
        }
        updateGlobalStatus("Connecting to $targetMac...")
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

        val isHostRunning = hostService?.isServerRunning == true
        if (isHostRunning) {
            val currentTx = hostService?.getTxBytes() ?: 0L
            val currentRx = hostService?.getRxBytes() ?: 0L
            val txSpeed = maxOf(0L, ((currentTx - lastHostTx) / dt).toLong())
            val rxSpeed = maxOf(0L, ((currentRx - lastHostRx) / dt).toLong())
            lastHostTx = currentTx
            lastHostRx = currentRx

            binding.layoutHostTrafficStats.visibility = View.VISIBLE
            binding.tvHostTxSpeed.text = formatSpeed(txSpeed)
            binding.tvHostTxTotal.text = "Total: ${formatBytes(currentTx)}"
            binding.tvHostRxSpeed.text = formatSpeed(rxSpeed)
            binding.tvHostRxTotal.text = "Total: ${formatBytes(currentRx)}"
        } else {
            binding.layoutHostTrafficStats.visibility = View.GONE
            lastHostTx = 0L
            lastHostRx = 0L
        }

        val isClientConnected = vpnService?.isVpnConnected == true
        if (isClientConnected) {
            val currentTx = vpnService?.getTxBytes() ?: 0L
            val currentRx = vpnService?.getRxBytes() ?: 0L
            val txSpeed = maxOf(0L, ((currentTx - lastClientTx) / dt).toLong())
            val rxSpeed = maxOf(0L, ((currentRx - lastClientRx) / dt).toLong())
            lastClientTx = currentTx
            lastClientRx = currentRx

            binding.layoutClientTrafficStats.visibility = View.VISIBLE
            binding.tvClientTxSpeed.text = formatSpeed(txSpeed)
            binding.tvClientTxTotal.text = "Total: ${formatBytes(currentTx)}"
            binding.tvClientRxSpeed.text = formatSpeed(rxSpeed)
            binding.tvClientRxTotal.text = "Total: ${formatBytes(currentRx)}"
        } else {
            binding.layoutClientTrafficStats.visibility = View.GONE
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

    @SuppressLint("MissingPermission")
    private fun updateHostUi() {
        val isRunning = hostService?.isServerRunning == true
        if (isRunning) {
            binding.btnToggleHost.text = getString(R.string.btn_stop_host)
            binding.btnToggleHost.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))

            val psm = hostService?.currentPsm ?: -1

            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val adapter = bluetoothManager?.adapter
                val mac = adapter?.address
                val deviceName = adapter?.name ?: "Host Device"
                val displayAddress = if (mac != null && mac != "02:00:00:00:00:00") mac else deviceName
                binding.tvHostMac.text = displayAddress

                if (psm > 0) {
                    binding.tvHostPsm.text = "PSM: $psm"
                    binding.tvHostPsm.visibility = View.VISIBLE
                } else {
                    binding.tvHostPsm.visibility = View.GONE
                }

                if (binding.layoutMacContainer.visibility != View.VISIBLE) {
                    binding.layoutMacContainer.visibility = View.VISIBLE
                    binding.layoutMacContainer.scaleX = 0.9f
                    binding.layoutMacContainer.scaleY = 0.9f
                    binding.layoutMacContainer.alpha = 0f
                    binding.layoutMacContainer.animate()
                        .alpha(1.0f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(2.0f))
                        .start()
                }
            } catch (_: Exception) {
                binding.layoutMacContainer.visibility = View.GONE
            }
        } else {
            binding.btnToggleHost.text = getString(R.string.btn_start_host)
            binding.btnToggleHost.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.layoutMacContainer.visibility = View.GONE
            binding.layoutHostTrafficStats.visibility = View.GONE
        }
    }

    private fun updateClientUi() {
        val isConnected = vpnService?.isVpnConnected == true
        if (isConnected) {
            binding.btnToggleClient.text = getString(R.string.btn_disconnect)
            binding.btnToggleClient.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
        } else {
            binding.btnToggleClient.text = getString(R.string.btn_connect_mac)
            binding.btnToggleClient.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.layoutClientTrafficStats.visibility = View.GONE
        }
    }

    private fun updateGlobalStatus(customStatus: String? = null) {
        val isHostMode = binding.btnHostMode.isChecked
        val isHostRunning = hostService?.isServerRunning == true
        val isClientConnected = vpnService?.isVpnConnected == true

        val greenColor = ContextCompat.getColor(this, R.color.status_green)
        val amberColor = ContextCompat.getColor(this, R.color.status_amber)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)

        when {
            customStatus != null -> {
                binding.tvGlobalStatus.text = customStatus
                if (customStatus.lowercase().contains("connected") || customStatus.lowercase().contains("active")) {
                    binding.ivStatusDot.setColorFilter(greenColor)
                } else if (customStatus.lowercase().contains("connecting") || customStatus.lowercase().contains("listening")) {
                    binding.ivStatusDot.setColorFilter(amberColor)
                } else {
                    binding.ivStatusDot.setColorFilter(mutedColor)
                }
            }
            isHostMode && isHostRunning -> {
                val psm = hostService?.currentPsm ?: -1
                val channelMode = if (psm > 0) "L2CAP CoC & RFCOMM" else "RFCOMM High-Speed"
                binding.tvGlobalStatus.text = "Host Server Active ($channelMode)"
                binding.ivStatusDot.setColorFilter(greenColor)
            }
            !isHostMode && isClientConnected -> {
                binding.tvGlobalStatus.text = "Client Connected & Tunneling Traffic"
                binding.ivStatusDot.setColorFilter(greenColor)
            }
            else -> {
                binding.tvGlobalStatus.text = getString(R.string.status_offline)
                binding.ivStatusDot.setColorFilter(mutedColor)
            }
        }

        // Pulse bounce status dot indicator
        binding.ivStatusDot.animate()
            .scaleX(1.4f)
            .scaleY(1.4f)
            .setDuration(150)
            .withEndAction {
                binding.ivStatusDot.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }
            .start()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsRunnable)
        if (isHostBound) {
            unbindService(hostServiceConnection)
            isHostBound = false
        }
        if (isVpnBound) {
            unbindService(vpnServiceConnection)
            isVpnBound = false
        }
        super.onDestroy()
    }
}
