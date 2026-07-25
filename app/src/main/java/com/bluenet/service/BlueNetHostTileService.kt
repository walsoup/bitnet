package com.bluenet.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.bluenet.host.HostService

@RequiresApi(Build.VERSION_CODES.N)
class BlueNetHostTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        if (tile.state == Tile.STATE_ACTIVE) {
            val intent = Intent(this, HostService::class.java).apply {
                action = HostService.ACTION_STOP_HOST
            }
            startService(intent)
            tile.state = Tile.STATE_INACTIVE
            tile.label = "BlueNet Host"
            tile.updateTile()
        } else {
            val intent = Intent(this, HostService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Host Running"
            tile.updateTile()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.label = "BlueNet Host"
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
