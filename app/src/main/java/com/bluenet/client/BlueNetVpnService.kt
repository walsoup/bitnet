package com.bluenet.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bluenet.bluetooth.L2capClient
import com.bluenet.multiplexer.StreamMultiplexer
import java.io.FileInputStream
import java.io.FileOutputStream

class BlueNetVpnService : VpnService() {

    private val binder = VpnBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var l2capClient: L2capClient? = null
    private var multiplexer: StreamMultiplexer? = null
    private var packetRouter: TunPacketRouter? = null

    var isVpnConnected: Boolean = false
        private set

    inner class VpnBinder : Binder() {
        fun getService(): BlueNetVpnService = this@BlueNetVpnService
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == VpnService.SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        return binder
    }

    @SuppressLint("MissingPermission")
    fun connectToHost(deviceAddress: String, psm: Int, compatMode: Boolean = false, onStatusChanged: (String) -> Unit) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)

        if (device == null) {
            onStatusChanged("Device not found: $deviceAddress")
            return
        }

        val modeDesc = if (compatMode) "RFCOMM Compat Mode" else "PSM $psm"
        onStatusChanged("Connecting ($modeDesc) to $deviceAddress...")

        l2capClient = L2capClient(
            context = this,
            device = device,
            psm = psm,
            onConnected = { socket ->
                onStatusChanged("Bluetooth Connected. Initializing VPN Tunnel...")
                setupVpnTunnel(socket.inputStream, socket.outputStream, onStatusChanged)
            },
            onError = { err ->
                onStatusChanged(err)
                stopVpn()
            }
        )
        l2capClient?.connect(compatMode)
    }

    private fun setupVpnTunnel(inputStream: java.io.InputStream, outputStream: java.io.OutputStream, onStatusChanged: (String) -> Unit) {
        try {
            val builder = Builder()
                .addAddress("10.0.8.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setSession("BlueNet L2CAP Tunnel")
                .setMtu(1500)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                onStatusChanged("Failed to create TUN Interface")
                stopVpn()
                return
            }

            val tunFd = vpnInterface!!.fileDescriptor
            val tunIn = FileInputStream(tunFd)
            val tunOut = FileOutputStream(tunFd)

            val mp = StreamMultiplexer(
                inputStream = inputStream,
                outputStream = outputStream,
                onFrameReceived = { frame -> packetRouter?.handleIncomingFrame(frame) },
                onError = { err ->
                    Log.e(TAG, "Client multiplexer error", err)
                    onStatusChanged("L2CAP Multiplexer Disconnected")
                    stopVpn()
                }
            )
            multiplexer = mp
            mp.start()

            packetRouter = TunPacketRouter(tunIn, tunOut, mp)
            packetRouter?.start()

            isVpnConnected = true
            onStatusChanged("Connected! Speed-optimized L2CAP Tethering Active")
            Log.d(TAG, "VPN Tunnel established over L2CAP")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up VPN tunnel", e)
            onStatusChanged("VPN Setup Error: ${e.localizedMessage}")
            stopVpn()
        }
    }

    fun stopVpn() {
        packetRouter?.stop()
        packetRouter = null

        multiplexer?.close()
        multiplexer = null

        l2capClient?.disconnect()
        l2capClient = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        isVpnConnected = false
        stopSelf()
        Log.d(TAG, "BlueNetVpnService stopped")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlueNetVpnService"
    }
}
