package com.bluenet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import com.bluenet.core.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A nearby BlueNet device seen over BLE. */
data class Sighting(
    val announcement: BleAnnouncement,
    val device: BluetoothDevice,
    val rssi: Int,
    val lastSeenElapsed: Long,
) {
    val peerId: String get() = announcement.peerId
}

/**
 * Advertises this device's [BleAnnouncement] and tracks the announcements of nearby devices.
 *
 * Callers must hold the Bluetooth runtime permissions before calling [start]; every Bluetooth call
 * is also guarded against [SecurityException] in case a permission is revoked while running.
 * All methods must be called on the main thread.
 */
@SuppressLint("MissingPermission")
class BleDiscovery(
    private val adapter: BluetoothAdapter,
    private val localPeerId: String,
) {
    enum class AdvertiseState { OFF, ADVERTISING, UNSUPPORTED, FAILED }

    private val handler = Handler(Looper.getMainLooper())
    private val _sightings = MutableStateFlow<Map<String, Sighting>>(emptyMap())
    val sightings: StateFlow<Map<String, Sighting>> = _sightings.asStateFlow()
    private val _advertiseState = MutableStateFlow(AdvertiseState.OFF)
    val advertiseState: StateFlow<AdvertiseState> = _advertiseState.asStateFlow()

    private var running = false
    private var scanning = false
    private var advertising = false
    private var announcement: BleAnnouncement? = null

    private val evictTask = object : Runnable {
        override fun run() {
            val cutoff = SystemClock.elapsedRealtime() - PEER_TIMEOUT_MS
            _sightings.update { current -> current.filterValues { it.lastSeenElapsed >= cutoff } }
            handler.postDelayed(this, EVICT_INTERVAL_MS)
        }
    }

    private val refreshAdvertisingTask = object : Runnable {
        override fun run() {
            restartAdvertising()
            handler.postDelayed(this, ADVERTISE_REFRESH_MS)
        }
    }

    fun start(announcement: BleAnnouncement) {
        if (running) {
            update(announcement)
            return
        }
        running = true
        this.announcement = announcement
        startScan()
        startAdvertising()
        handler.postDelayed(evictTask, EVICT_INTERVAL_MS)
        handler.postDelayed(refreshAdvertisingTask, ADVERTISE_REFRESH_MS)
    }

    /** Re-advertises if the announcement changed. */
    fun update(announcement: BleAnnouncement) {
        if (announcement == this.announcement) return
        this.announcement = announcement
        if (running) restartAdvertising()
    }

    /**
     * Some controllers stop connectable advertising once a central connects, so the link layer
     * calls this after a link opens to make sure we stay discoverable.
     */
    fun restartAdvertising() {
        if (!running) return
        stopAdvertising()
        startAdvertising()
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(evictTask)
        handler.removeCallbacks(refreshAdvertisingTask)
        stopScan()
        stopAdvertising()
        _sightings.value = emptyMap()
    }

    private fun startScan() {
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleAnnouncement.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start scanning", e)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop scanning", e)
        }
    }

    private fun startAdvertising() {
        val payload = announcement ?: return
        if (!adapter.isMultipleAdvertisementSupported) {
            _advertiseState.value = AdvertiseState.UNSUPPORTED
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            _advertiseState.value = AdvertiseState.UNSUPPORTED
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleAnnouncement.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BleAnnouncement.MANUFACTURER_ID, payload.encode())
            .build()
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            advertising = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start advertising", e)
            _advertiseState.value = AdvertiseState.FAILED
        }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try {
            adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop advertising", e)
        }
        _advertiseState.value = AdvertiseState.OFF
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed with error $errorCode")
            scanning = false
            if (running) handler.postDelayed({ if (running && !scanning) startScan() }, SCAN_RETRY_MS)
        }
    }

    private fun record(result: ScanResult) {
        val bytes = result.scanRecord?.getManufacturerSpecificData(BleAnnouncement.MANUFACTURER_ID)
        val announcement = BleAnnouncement.decode(bytes) ?: return
        if (announcement.peerId == localPeerId) return
        val sighting = Sighting(announcement, result.device, result.rssi, SystemClock.elapsedRealtime())
        _sightings.update { it + (announcement.peerId to sighting) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _advertiseState.value = AdvertiseState.ADVERTISING
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return
            Log.w(TAG, "Advertising failed with error $errorCode")
            advertising = false
            _advertiseState.value =
                if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) AdvertiseState.UNSUPPORTED else AdvertiseState.FAILED
        }
    }

    companion object {
        private const val TAG = "BleDiscovery"
        const val PEER_TIMEOUT_MS = 45_000L
        private const val EVICT_INTERVAL_MS = 10_000L
        private const val SCAN_RETRY_MS = 30_000L
        private const val ADVERTISE_REFRESH_MS = 120_000L
    }
}
