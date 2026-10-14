package com.bluenet.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bluenet.Permissions
import com.bluenet.R
import com.bluenet.core.Log
import com.bluenet.node.MeshNode
import com.bluenet.node.MeshRuntime
import com.bluenet.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keeps the mesh node running while the app is in the background. */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val node: MeshNode by lazy { MeshRuntime.node(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BlueNetVpnService.disconnect(this)
            node.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.status_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Missing Bluetooth permissions make a connectedDevice foreground service illegal.
            Log.e(TAG, "Could not enter the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        node.start()
        if (observer == null) observer = scope.launch { observeNode() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        node.stop()
        super.onDestroy()
    }

    private suspend fun observeNode() {
        combine(node.status, node.sharing, node.peers, node.linkedCount, node.sharingClients) { status, sharing, peers, linked, clients ->
            when (status.state) {
                MeshNode.State.RUNNING ->
                    if (sharing) getString(R.string.notification_sharing, clients)
                    else getString(R.string.notification_idle, peers.count { it.nearby }, linked)
                MeshNode.State.BLUETOOTH_OFF -> getString(R.string.status_bluetooth_off)
                MeshNode.State.NO_PERMISSION -> getString(R.string.status_permissions)
                else -> getString(R.string.status_starting)
            }
        }.distinctUntilChanged().collect(::notifyStatus)
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun notifyStatus(text: String) {
        if (!Permissions.canNotify(this)) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_host)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.bluenet.action.STOP_MESH"

        /** Starts the mesh. Callers must hold the Bluetooth permissions. */
        fun start(context: Context) {
            if (!Permissions.hasBluetooth(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the mesh service", e)
            }
        }
    }
}
