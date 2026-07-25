package com.bluenet.host

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bluenet.bluetooth.L2capServer
import com.bluenet.multiplexer.StreamMultiplexer

class HostService : Service() {

    private val binder = HostBinder()
    var l2capServer: L2capServer? = null
        private set
    private var activeMultiplexer: StreamMultiplexer? = null
    private var hostProxyManager: HostProxyManager? = null

    var currentPsm: Int = -1
        private set

    var isServerRunning: Boolean = false
        private set

    inner class HostBinder : Binder() {
        fun getService(): HostService = this@HostService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    fun startHostServer(onStatusChanged: (String, Int) -> Unit) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onStatusChanged("Bluetooth disabled", -1)
            return
        }

        startForeground(NOTIFICATION_ID, createNotification("L2CAP Host Active - Waiting for client..."))

        l2capServer = L2capServer(
            bluetoothAdapter = bluetoothAdapter,
            onClientConnected = { socket -> handleClientSocket(socket, onStatusChanged) },
            onError = { err -> onStatusChanged("Error: $err", currentPsm) }
        )

        val success = l2capServer?.start() == true
        if (success) {
            isServerRunning = true
            currentPsm = l2capServer?.psm ?: -1
            onStatusChanged("Host Server Running on PSM: $currentPsm", currentPsm)
            Log.d(TAG, "HostService started on PSM: $currentPsm")
        } else {
            isServerRunning = false
            onStatusChanged("Failed to start L2CAP server", -1)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun handleClientSocket(socket: BluetoothSocket, onStatusChanged: (String, Int) -> Unit) {
        onStatusChanged("Client Connected (${socket.remoteDevice.address})", currentPsm)
        updateNotification("Client Connected - Tunneling Active")

        val multiplexer = StreamMultiplexer(
            inputStream = socket.inputStream,
            outputStream = socket.outputStream,
            onFrameReceived = { frame -> hostProxyManager?.handleFrame(frame) },
            onError = { ex ->
                Log.e(TAG, "Host Multiplexer connection dropped", ex)
                onStatusChanged("Client Disconnected", currentPsm)
                updateNotification("L2CAP Host Active - Waiting for client...")
            }
        )

        activeMultiplexer = multiplexer
        hostProxyManager = HostProxyManager(multiplexer)
        multiplexer.start()
    }

    fun getTxBytes(): Long = activeMultiplexer?.getTxBytes() ?: 0L
    fun getRxBytes(): Long = activeMultiplexer?.getRxBytes() ?: 0L

    fun stopHostServer() {
        activeMultiplexer?.close()
        activeMultiplexer = null
        hostProxyManager?.closeAll()
        hostProxyManager = null
        l2capServer?.stop()
        l2capServer = null
        isServerRunning = false
        currentPsm = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "HostService stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BlueNet Host Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueNet Bluetooth Host")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
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
        stopHostServer()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HostService"
        private const val CHANNEL_ID = "bluenet_host_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
