package com.bluenet.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bluenet.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MeshService : Service() {

    lateinit var meshManager: MeshManager
        private set
    private val binder = MeshBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var peerId: String = ""
    private var displayName: String = ""

    companion object {
        const val ACTION_STOP_MESH = "com.bluenet.mesh.ACTION_STOP_MESH"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "bluenet_mesh_channel"
    }

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
        fun getManager(): MeshManager = meshManager
    }

    override fun onCreate() {
        super.onCreate()
        
        peerId = PreferencesManager.getPeerId(this)
        displayName = PreferencesManager.getDisplayName(this)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        meshManager = MeshManager(this, bluetoothAdapter)

        createNotificationChannel()

        meshManager.peers.onEach { peersList ->
            updateNotification(peersList.size)
        }.launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MESH) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(0)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        meshManager.start(peerId, displayName)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stop()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BlueNet Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active mesh connections and status"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peerCount: Int): Notification {
        val stopIntent = Intent(this, MeshService::class.java).apply {
            action = ACTION_STOP_MESH
        }
        val stopFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, stopFlags)

        val mainActivityIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (mainActivityIntent != null) {
            PendingIntent.getActivity(this, 0, mainActivityIntent, stopFlags)
        } else {
            null
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("BlueNet Mesh Active")
            .setContentText("$peerCount peers nearby")
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun updateNotification(peerCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(peerCount))
    }
}
