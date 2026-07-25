package com.bluenet.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.bluenet.client.BlueNetVpnService
import com.bluenet.utils.PreferencesManager

@RequiresApi(Build.VERSION_CODES.N)
class BlueNetClientTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        if (tile.state == Tile.STATE_ACTIVE) {
            val intent = Intent(this, BlueNetVpnService::class.java).apply {
                action = BlueNetVpnService.ACTION_DISCONNECT_VPN
            }
            startService(intent)
            tile.state = Tile.STATE_INACTIVE
            tile.label = "BlueNet Client"
            tile.updateTile()
        } else {
            val lastMac = PreferencesManager.getLastMac(this)

            if (lastMac.isNotEmpty()) {
                val intent = Intent(this, BlueNetVpnService::class.java)
                startService(intent)
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Client Active"
                tile.updateTile()
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.label = "BlueNet Client"
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
