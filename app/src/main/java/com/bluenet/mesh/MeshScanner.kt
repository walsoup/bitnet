package com.bluenet.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class MeshScanner(private val bluetoothAdapter: BluetoothAdapter?) {
    private val TAG = "MeshScanner"
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    private val _peersMap = ConcurrentHashMap<String, MeshPeer>()
    private val _peersFlow = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peersFlow.asStateFlow()

    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            cleanupStalePeers()
            if (isScanning) {
                cleanupHandler.postDelayed(this, 15000L)
            }
        }
    }

    private var localPeerId: String = ""

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { processScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
        }
    }

    fun processScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val manufacturerData = record.getManufacturerSpecificData(MeshConstants.MANUFACTURER_ID) ?: return

        val announcement = MeshPeerAnnouncement.fromBytes(manufacturerData) ?: return

        if (localPeerId.isNotEmpty() && announcement.peerId == localPeerId) {
            return
        }

        val peer = MeshPeer(
            peerId = announcement.peerId,
            displayName = announcement.displayName ?: "Unknown",
            macAddress = result.device?.address ?: "",
            isSharingInternet = announcement.isSharingInternet,
            signalStrength = result.rssi,
            lastSeen = System.currentTimeMillis(),
            psm = announcement.psm
        )

        _peersMap[peer.peerId] = peer
        updatePeersList()
    }

    fun start(peerId: String = "") {
        if (peerId.isNotEmpty()) {
            localPeerId = peerId
        }
        if (isScanning) return
        
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MeshConstants.MESH_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d(TAG, "Scanning started")
            cleanupHandler.postDelayed(cleanupRunnable, 15000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning", e)
        }
    }

    fun stop() {
        if (isScanning) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop scanning", e)
            }
            isScanning = false
            cleanupHandler.removeCallbacks(cleanupRunnable)
            Log.d(TAG, "Scanning stopped")
        }
    }

    fun cleanupStalePeers() {
        val now = System.currentTimeMillis()
        var changed = false
        val iterator = _peersMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeen > MeshConstants.PEER_TIMEOUT_MS) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            updatePeersList()
        }
    }

    private fun updatePeersList() {
        val now = System.currentTimeMillis()
        _peersMap.entries.removeIf { now - it.value.lastSeen > MeshConstants.PEER_TIMEOUT_MS }
        val sortedList = _peersMap.values.sortedWith(
            compareByDescending<MeshPeer> { it.isSharingInternet }
                .thenByDescending { it.signalStrength }
        )
        _peersFlow.value = sortedList
    }
}
