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
import com.bluenet.mesh.MeshService

@RequiresApi(Build.VERSION_CODES.N)
class BlueNetHostTileService : TileService() {

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
            val currentlySharing = manager.isSharingInternet.value
            manager.setInternetSharing(!currentlySharing)

            tile.state = if (!currentlySharing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (!currentlySharing) "Sharing Internet" else "Share Internet"
            tile.updateTile()
        } else {
            // Start mesh service first
            val intent = Intent(this, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Sharing Internet"
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isSharing = meshService?.meshManager?.isSharingInternet?.value == true
        tile.label = if (isSharing) "Sharing Internet" else "Share Internet"
        tile.state = if (isSharing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
