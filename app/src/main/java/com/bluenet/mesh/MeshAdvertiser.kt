package com.bluenet.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log

@SuppressLint("MissingPermission")
class MeshAdvertiser(private val bluetoothAdapter: BluetoothAdapter?) {
    private val TAG = "MeshAdvertiser"
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    private var currentPeerId = ""
    private var currentDisplayName = ""

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertising started successfully")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error: $errorCode")
            isAdvertising = false
        }
    }

    fun start(peerId: String, displayName: String, isSharingInternet: Boolean, psm: Int) {
        if (isAdvertising) stop()

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser is null")
            return
        }

        currentPeerId = peerId
        currentDisplayName = displayName

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = peerId,
            isSharingInternet = isSharingInternet,
            hasName = displayName.isNotEmpty(),
            psm = psm,
            displayName = displayName
        )

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(MeshConstants.MESH_SERVICE_UUID))
            .addManufacturerData(MeshConstants.MANUFACTURER_ID, announcement.toBytes())
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
        }
    }

    fun stop() {
        if (isAdvertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop advertising", e)
            }
            isAdvertising = false
            Log.d(TAG, "Advertising stopped")
        }
    }

    fun updateAdvertisement(isSharingInternet: Boolean, psm: Int) {
        if (isAdvertising) {
            start(currentPeerId, currentDisplayName, isSharingInternet, psm)
        }
    }
}
