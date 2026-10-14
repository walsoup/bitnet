package com.bluenet.node

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of this device's own use of a peer's internet. */
data class TunnelStatus(
    val state: State = State.IDLE,
    val peerId: String? = null,
    val peerName: String? = null,
    val attempt: Int = 0,
    val error: String? = null,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val flows: Int = 0,
) {
    enum class State { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    val isActive: Boolean get() = state == State.CONNECTING || state == State.CONNECTED || state == State.RECONNECTING
}

/** Process-wide holder for the mesh node and tunnel state shared by services, tiles and UI. */
object MeshRuntime {
    @Volatile private var instance: MeshNode? = null

    fun node(context: Context): MeshNode =
        instance ?: synchronized(this) {
            instance ?: MeshNode(context.applicationContext).also { instance = it }
        }

    private val _tunnel = MutableStateFlow(TunnelStatus())
    val tunnel: StateFlow<TunnelStatus> = _tunnel.asStateFlow()

    fun publishTunnel(status: TunnelStatus) {
        _tunnel.value = status
    }
}
