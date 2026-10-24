package com.bluenet.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bluenet.Permissions
import com.bluenet.R
import com.bluenet.node.MeshNode
import com.bluenet.node.MeshRuntime
import com.bluenet.node.TunnelStatus
import com.bluenet.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

abstract class BaseTileService<T> : TileService() {
    private var scope: CoroutineScope? = null
    protected val node: MeshNode by lazy { MeshRuntime.node(this) }

    protected abstract fun state(): Flow<T>
    protected abstract fun render(tile: Tile, state: T)

    override fun onStartListening() {
        scope?.cancel()
        scope = MainScope().also { scope ->
            scope.launch {
                state().collect { value ->
                    val tile = qsTile ?: return@collect
                    render(tile, value)
                    tile.updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Toggles sharing this device's internet with the mesh. */
class ShareTileService : BaseTileService<Pair<Boolean, TunnelStatus>>() {
    override fun state() = combine(node.sharing, MeshRuntime.tunnel) { sharing, tunnel -> sharing to tunnel }

    override fun render(tile: Tile, state: Pair<Boolean, TunnelStatus>) {
        val (sharing, tunnel) = state
        tile.label = getString(R.string.tile_share)
        tile.state = when {
            tunnel.isActive -> Tile.STATE_UNAVAILABLE
            sharing -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
    }

    override fun onClick() {
        if (!Permissions.hasBluetooth(this)) return openApp()
        val enable = !node.sharing.value
        if (!node.setSharing(enable)) return
        if (enable) MeshService.start(this)
    }
}

/** Connects to (or disconnects from) the most recently used sharer. */
class ConnectTileService : BaseTileService<TunnelStatus>() {
    override fun state() = MeshRuntime.tunnel

    override fun render(tile: Tile, state: TunnelStatus) {
        tile.label = getString(R.string.tile_connect)
        tile.state = if (state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle = when (state.state) {
            TunnelStatus.State.CONNECTED -> state.peerName
            TunnelStatus.State.CONNECTING, TunnelStatus.State.RECONNECTING -> getString(R.string.status_starting)
            else -> node.lastSharerId?.let { id -> node.peer(id)?.name ?: id.take(6) }
        }
        tile.subtitle = subtitle
    }

    override fun onClick() {
        if (MeshRuntime.tunnel.value.isActive) {
            BlueNetVpnService.disconnect(this)
            return
        }
        val peerId = node.lastSharerId
        if (peerId == null || !Permissions.hasBluetooth(this) || node.sharing.value || VpnService.prepare(this) != null) {
            return openApp()
        }
        MeshService.start(this)
        BlueNetVpnService.connect(this, peerId)
    }
}
