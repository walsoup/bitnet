package com.bluenet.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.bluenet.mesh.ConnectionState
import com.bluenet.mesh.MeshService
import com.bluenet.utils.PreferencesManager

@RequiresApi(Build.VERSION_CODES.N)
class BlueNetClientTileService : TileService() {

    private var meshService: MeshService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.MeshBinder
            meshService = binder.getService()
            isBound = true
            updateTileState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isBound = false
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val intent = Intent(this, MeshService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val manager = meshService?.meshManager

        if (manager != null) {
            val state = manager.connectionState.value
            if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
                manager.disconnect()
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Mesh Connect"
                tile.updateTile()
            } else {
                // Try to reconnect to last connected peer
                val peers = manager.peers.value
                val lastPeerId = PreferencesManager.getLastConnectedPeerId(this)
                val targetPeer = peers.firstOrNull { it.peerId == lastPeerId && it.isSharingInternet }
                    ?: peers.firstOrNull { it.isSharingInternet }

                if (targetPeer != null) {
                    manager.connectToPeer(targetPeer)
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Connecting..."
                    tile.updateTile()
                } else {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "No Peers"
                    tile.updateTile()
                }
            }
        } else {
            // Start mesh service
            val intent = Intent(this, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Mesh Connect"
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = meshService?.meshManager?.connectionState?.value
        when (state) {
            ConnectionState.CONNECTED -> {
                tile.label = "Mesh Connected"
                tile.state = Tile.STATE_ACTIVE
            }
            ConnectionState.CONNECTING -> {
                tile.label = "Connecting..."
                tile.state = Tile.STATE_ACTIVE
            }
            else -> {
                tile.label = "Mesh Connect"
                tile.state = Tile.STATE_INACTIVE
            }
        }
        tile.updateTile()
    }
}
