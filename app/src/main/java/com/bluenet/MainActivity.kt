package com.bluenet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bluenet.client.BlueNetVpnService
import com.bluenet.databinding.ActivityMainBinding
import com.bluenet.host.HostService
import com.bluenet.utils.BluetoothUtils
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var hostService: HostService? = null
    private var isHostBound = false

    private var vpnService: BlueNetVpnService? = null
    private var isVpnBound = false

    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var currentHostPin: String = ""

    private val hostServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HostService.HostBinder
            hostService = binder.getService()
            isHostBound = true
            updateHostUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostService = null
            isHostBound = false
            updateHostUi()
        }
    }

    private val vpnServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BlueNetVpnService.VpnBinder
            vpnService = binder.getService()
            isVpnBound = true
            updateClientUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            isVpnBound = false
            updateClientUi()
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

        requestBluetoothPermissions()

        val hostIntent = Intent(this, HostService::class.java)
        bindService(hostIntent, hostServiceConnection, Context.BIND_AUTO_CREATE)

        val vpnIntent = Intent(this, BlueNetVpnService::class.java)
        bindService(vpnIntent, vpnServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupModeSwitching() {
        binding.rgModeSelector.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btnHostMode) {
                binding.cardHost.visibility = View.VISIBLE
                binding.cardClient.visibility = View.GONE
            } else {
                binding.cardHost.visibility = View.GONE
                binding.cardClient.visibility = View.VISIBLE
                refreshPairedDevicesSafely()
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
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
                listOf("No paired Bluetooth devices found")
            }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spDevices.adapter = adapter
        } catch (_: Exception) {}
    }

    private fun setupHostUi() {
        binding.btnToggleHost.setOnClickListener {
            val service = hostService ?: return@setOnClickListener
            if (service.isServerRunning) {
                service.stopHostServer()
                binding.layoutMacContainer.visibility = View.GONE
                updateHostUi()
            } else {
                val intent = Intent(this, HostService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                service.startHostServer { statusText, _ ->
                    runOnUiThread {
                        binding.tvHostStatus.text = statusText
                        updateHostUi()
                    }
                }
            }
        }
    }

    private fun setupClientUi() {
        refreshPairedDevicesSafely()

        binding.btnToggleClient.setOnClickListener {
            val service = vpnService ?: return@setOnClickListener
            if (service.isVpnConnected) {
                service.stopVpn()
                updateClientUi()
            } else {
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
            Toast.makeText(this, "Enter a valid MAC address (e.g. AA:BB:CC:DD:EE:FF) or select a paired device", Toast.LENGTH_LONG).show()
            return
        }

        val vpnIntent = Intent(this, BlueNetVpnService::class.java)
        startService(vpnIntent)

        val compatMode = binding.swCompatMode.isChecked
        vpnService?.connectToHost(targetMac, psm, compatMode) { statusText ->
            runOnUiThread {
                binding.tvClientStatus.text = statusText
                updateClientUi()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateHostUi() {
        val isRunning = hostService?.isServerRunning == true
        if (isRunning) {
            binding.btnToggleHost.text = getString(R.string.btn_stop_host)
            val psm = hostService?.currentPsm ?: -1
            val channelMode = if (psm > 0) "L2CAP CoC & RFCOMM Dual" else "RFCOMM High-Speed"
            binding.tvHostStatus.text = "Host Server Active ($channelMode)"

            // Show MAC address on Host Card
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

                binding.layoutMacContainer.visibility = View.VISIBLE
            } catch (_: Exception) {
                binding.layoutMacContainer.visibility = View.GONE
            }
        } else {
            binding.btnToggleHost.text = getString(R.string.btn_start_host)
            binding.tvHostStatus.text = getString(R.string.status_offline)
            binding.layoutMacContainer.visibility = View.GONE
        }
    }

    private fun updateClientUi() {
        val isConnected = vpnService?.isVpnConnected == true
        if (isConnected) {
            binding.btnToggleClient.text = getString(R.string.btn_disconnect)
        } else {
            binding.btnToggleClient.text = getString(R.string.btn_connect_mac)
        }
    }

    override fun onDestroy() {
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
