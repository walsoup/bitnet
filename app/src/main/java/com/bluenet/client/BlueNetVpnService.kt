package com.bluenet.client

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
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

    private var userRequestedDisconnect: Boolean = false
    private var reconnectAttempt: Int = 0
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastDeviceAddress: String? = null
    private var lastPsm: Int = 1
    private var lastCompatMode: Boolean = false
    private var statusCallback: ((String) -> Unit)? = null

    var isVpnConnected: Boolean = false
        private set

    inner class VpnBinder : Binder() {
        fun getService(): BlueNetVpnService = this@BlueNetVpnService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_VPN) {
            stopVpn(userExplicit = true)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == VpnService.SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        return binder
    }

    @SuppressLint("MissingPermission")
    fun connectToHost(deviceAddress: String, psm: Int, compatMode: Boolean = false, onStatusChanged: (String) -> Unit) {
        userRequestedDisconnect = false
        lastDeviceAddress = deviceAddress
        lastPsm = psm
        lastCompatMode = compatMode
        statusCallback = onStatusChanged

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)

        if (device == null) {
            onStatusChanged("Device not found: $deviceAddress")
            return
        }

        val modeDesc = if (compatMode) "RFCOMM Compat Mode" else "PSM $psm"
        onStatusChanged("Connecting ($modeDesc) to $deviceAddress...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Connecting to $deviceAddress..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification("Connecting to $deviceAddress..."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }

        l2capClient = L2capClient(
            context = this,
            device = device,
            psm = psm,
            onConnected = { socket ->
                onStatusChanged("Bluetooth Connected. Initializing VPN Tunnel...")
                setupVpnTunnel(socket.inputStream, socket.outputStream, onStatusChanged)
            },
            onError = { err ->
                Log.w(TAG, "L2CAP Client error: $err")
                handleUnexpectedDisconnect(err)
            }
        )
        l2capClient?.connect(compatMode)
    }

    private fun handleUnexpectedDisconnect(errorMessage: String) {
        val autoConnectEnabled = com.bluenet.utils.PreferencesManager.isAutoConnectEnabled(this)
        if (!userRequestedDisconnect && autoConnectEnabled && reconnectAttempt < MAX_RECONNECT_ATTEMPTS && lastDeviceAddress != null) {
            reconnectAttempt++
            val delayMs = reconnectAttempt * 4000L
            val statusMsg = "Connection lost. Reconnecting ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS) in ${delayMs / 1000}s..."
            statusCallback?.invoke(statusMsg)
            updateNotification(statusMsg)
            Log.d(TAG, statusMsg)

            cleanupSocketsOnly()

            reconnectHandler.postDelayed({
                if (!userRequestedDisconnect && !isVpnConnected && lastDeviceAddress != null) {
                    val addr = lastDeviceAddress!!
                    val psm = lastPsm
                    val compat = lastCompatMode
                    val callback = statusCallback ?: {}
                    connectToHost(addr, psm, compat, callback)
                }
            }, delayMs)
        } else {
            statusCallback?.invoke(errorMessage)
            stopVpn(userExplicit = false)
        }
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
                stopVpn(userExplicit = false)
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
                    handleUnexpectedDisconnect("L2CAP Multiplexer Disconnected")
                }
            )
            multiplexer = mp
            mp.start()

            packetRouter = TunPacketRouter(tunIn, tunOut, mp)
            packetRouter?.start()

            isVpnConnected = true
            reconnectAttempt = 0
            updateNotification("BlueNet VPN Connected & Tunneling Traffic")
            onStatusChanged("Connected! Speed-optimized L2CAP Tethering Active")
            Log.d(TAG, "VPN Tunnel established over L2CAP")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up VPN tunnel", e)
            onStatusChanged("VPN Setup Error: ${e.localizedMessage}")
            stopVpn(userExplicit = false)
        }
    }

    fun getTxBytes(): Long = multiplexer?.getTxBytes() ?: 0L
    fun getRxBytes(): Long = multiplexer?.getRxBytes() ?: 0L

    private fun cleanupSocketsOnly() {
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
    }

    fun stopVpn(userExplicit: Boolean = true) {
        if (userExplicit) {
            userRequestedDisconnect = true
            reconnectAttempt = 0
            reconnectHandler.removeCallbacksAndMessages(null)
        }

        cleanupSocketsOnly()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "BlueNetVpnService stopped (userExplicit=$userExplicit)")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BlueNet Client VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intentToDisconnect = Intent(this, BlueNetVpnService::class.java).apply {
            action = ACTION_DISCONNECT_VPN
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val disconnectPendingIntent = PendingIntent.getService(this, 0, intentToDisconnect, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueNet Client VPN")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .build()
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun updateNotification(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlueNetVpnService"
        private const val CHANNEL_ID = "bluenet_vpn_channel"
        private const val NOTIFICATION_ID = 2002
        private const val MAX_RECONNECT_ATTEMPTS = 5
        const val ACTION_DISCONNECT_VPN = "com.bluenet.ACTION_DISCONNECT_VPN"
    }
}
