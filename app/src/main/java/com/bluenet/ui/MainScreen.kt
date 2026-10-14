package com.bluenet.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bluenet.R
import com.bluenet.core.PeerIds
import com.bluenet.core.mesh.MeshPayloads
import com.bluenet.node.MeshNode
import com.bluenet.node.Peer
import com.bluenet.node.TunnelStatus
import com.bluenet.service.BlueNetVpnService
import com.walsoup.linen.ActionButton
import com.walsoup.linen.ActionStyle
import com.walsoup.linen.EmptyState
import com.walsoup.linen.Hairline
import com.walsoup.linen.Linen
import com.walsoup.linen.LinenDimens
import com.walsoup.linen.LineLabel
import com.walsoup.linen.PageHeader
import com.walsoup.linen.SectionTitle
import com.walsoup.linen.SettingLine
import com.walsoup.linen.SwitchLine

data class MainUiState(
    val status: MeshNode.Status,
    val hasPermissions: Boolean,
    val nickname: String,
    val peerId: String,
    val sharing: Boolean,
    val sharingClients: Int,
    val peers: List<Peer>,
    val linkedCount: Int,
    val tunnel: TunnelStatus,
)

class MainActions(
    val onGrantPermissions: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val onSharingChange: (Boolean) -> Unit,
    val onSaveName: (String) -> Unit,
    val onOpenChat: (Peer?) -> Unit,
    val onUseInternet: (Peer) -> Unit,
    val onDisconnect: () -> Unit,
)

@Composable
fun MainScreen(state: MainUiState, actions: MainActions) {
    var editingName by rememberSaveable { mutableStateOf(false) }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Linen.colors.bg),
        contentPadding = PaddingValues(
            start = LinenDimens.ScreenMargin,
            end = LinenDimens.ScreenMargin,
            top = insets.calculateTopPadding(),
            bottom = insets.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            PageHeader(
                overline = statusOverline(state.status),
                title = stringResource(R.string.app_name),
                lede = stringResource(R.string.you_are, state.nickname),
            )
        }
        item { MeshStats(state) }
        item { Attention(state, actions) }

        if (state.tunnel.state != TunnelStatus.State.IDLE) {
            item { TunnelSection(state.tunnel, actions.onDisconnect) }
        }

        item {
            SectionTitle(stringResource(R.string.this_phone_title))
            SwitchLine(
                label = stringResource(R.string.share_title),
                checked = state.sharing,
                onCheckedChange = actions.onSharingChange,
                subtitle = when {
                    state.tunnel.isActive -> stringResource(R.string.share_blocked_while_connected)
                    state.sharing -> stringResource(R.string.share_active, state.sharingClients)
                    else -> stringResource(R.string.share_subtitle)
                },
            )
            SettingLine(
                label = stringResource(R.string.edit_name_title),
                value = state.nickname,
                onClick = { editingName = true },
            )
        }

        item {
            SectionTitle(stringResource(R.string.nearby_title))
            SettingLine(
                label = stringResource(R.string.broadcast_chat),
                subtitle = stringResource(R.string.broadcast_chat_subtitle),
                onClick = { actions.onOpenChat(null) },
            )
        }
        items(state.peers, key = { it.id }) { peer ->
            PeerLine(
                peer = peer,
                canUseInternet = peer.canProvideInternet && !(state.tunnel.isActive && state.tunnel.peerId == peer.id),
                onChat = { actions.onOpenChat(peer) },
                onUseInternet = { actions.onUseInternet(peer) },
            )
        }
        if (state.peers.isEmpty()) {
            item { EmptyState(stringResource(R.string.no_peers)) }
        }
    }

    if (editingName) {
        NameDialog(
            current = state.nickname,
            onSave = {
                actions.onSaveName(it)
                editingName = false
            },
            onDismiss = { editingName = false },
        )
    }
}

@Composable
private fun statusOverline(status: MeshNode.Status): String = stringResource(
    when (status.state) {
        MeshNode.State.RUNNING -> R.string.status_running
        MeshNode.State.BLUETOOTH_OFF -> R.string.status_bluetooth_off
        MeshNode.State.NO_PERMISSION -> R.string.status_permissions
        MeshNode.State.NO_BLUETOOTH -> R.string.status_no_bluetooth
        MeshNode.State.STOPPED -> R.string.status_stopped
    }
)

/** Counts sit in a row of monospace figures, labelled underneath. */
@Composable
private fun MeshStats(state: MainUiState) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Stat(state.peers.count { it.nearby }.toString(), stringResource(R.string.stat_nearby))
            Stat(state.linkedCount.toString(), stringResource(R.string.stat_linked))
            Stat(PeerIds.short(state.peerId), stringResource(R.string.stat_id))
        }
        Hairline()
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = Linen.type.monoDock, color = Linen.colors.text)
        Text(label.uppercase(), style = Linen.type.overline, color = Linen.colors.muted)
    }
}

@Composable
private fun Attention(state: MainUiState, actions: MainActions) {
    when {
        !state.hasPermissions -> SettingLine(
            label = stringResource(R.string.permission_title),
            subtitle = stringResource(R.string.permission_rationale),
            value = stringResource(R.string.grant),
            warning = true,
            onClick = actions.onGrantPermissions,
        )
        state.status.state == MeshNode.State.BLUETOOTH_OFF -> SettingLine(
            label = stringResource(R.string.status_bluetooth_off),
            value = stringResource(R.string.enable_bluetooth),
            warning = true,
            onClick = actions.onEnableBluetooth,
        )
        state.status.state == MeshNode.State.NO_BLUETOOTH -> SettingLine(
            label = stringResource(R.string.status_no_bluetooth),
            warning = true,
        )
        state.status.state == MeshNode.State.RUNNING && (!state.status.canAcceptLinks || !state.status.canAdvertise) ->
            SettingLine(
                label = stringResource(R.string.status_unsupported),
                subtitle = stringResource(R.string.status_unsupported_detail),
                warning = true,
            )
    }
}

@Composable
private fun TunnelSection(tunnel: TunnelStatus, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val name = tunnel.peerName.orEmpty()
    Column {
        SectionTitle(stringResource(R.string.tunnel_title))
        Text(
            text = when (tunnel.state) {
                TunnelStatus.State.IDLE -> ""
                TunnelStatus.State.CONNECTING -> stringResource(R.string.tunnel_connecting, name)
                TunnelStatus.State.CONNECTED -> stringResource(R.string.tunnel_connected, name)
                TunnelStatus.State.RECONNECTING ->
                    stringResource(R.string.tunnel_reconnecting, tunnel.attempt + 1, BlueNetVpnService.MAX_ATTEMPTS)
                TunnelStatus.State.FAILED -> stringResource(R.string.tunnel_failed, tunnel.error.orEmpty())
            },
            style = Linen.type.body,
            color = if (tunnel.state == TunnelStatus.State.FAILED) Linen.colors.rec else Linen.colors.text,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (tunnel.state == TunnelStatus.State.CONNECTED || tunnel.state == TunnelStatus.State.RECONNECTING) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("↑ " + Formatter.formatShortFileSize(context, tunnel.bytesUp), style = Linen.type.monoSmall, color = Linen.colors.textSecondary)
                Text("↓ " + Formatter.formatShortFileSize(context, tunnel.bytesDown), style = Linen.type.monoSmall, color = Linen.colors.textSecondary)
                Text(stringResource(R.string.tunnel_flows, tunnel.flows), style = Linen.type.monoSmall, color = Linen.colors.textSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (tunnel.isActive) {
            ActionButton(stringResource(R.string.disconnect), onDisconnect, style = ActionStyle.Record)
        } else {
            ActionButton(stringResource(R.string.dismiss), onDisconnect, style = ActionStyle.Ghost)
        }
        Spacer(Modifier.height(20.dp))
        Hairline()
    }
}

/** A peer row: name and state on the left, signal in monospace, and "Use internet" for a nearby sharer. */
@Composable
private fun PeerLine(peer: Peer, canUseInternet: Boolean, onChat: () -> Unit, onUseInternet: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LinenDimens.RowMinHeight)
                .clickable(role = Role.Button, onClick = onChat)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineLabel(peer.name, peerSubtitle(peer), Modifier.weight(1f))
            peer.rssi?.let {
                Text(
                    stringResource(R.string.peer_signal, it),
                    style = Linen.type.monoSmall,
                    color = Linen.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (canUseInternet) {
                Spacer(Modifier.width(12.dp))
                ActionButton(stringResource(R.string.peer_use_internet), onUseInternet, style = ActionStyle.Tonal)
            }
        }
        Hairline()
    }
}

@Composable
fun peerSubtitle(peer: Peer): String {
    val parts = buildList {
        if (peer.isSharingInternet) add(stringResource(R.string.peer_sharing))
        if (peer.linked) add(stringResource(R.string.peer_linked))
        peer.hops?.takeIf { it > 1 }?.let { add(stringResource(R.string.peer_hops, it)) }
    }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" · ")
        peer.nearby -> stringResource(R.string.peer_nearby)
        else -> stringResource(R.string.peer_via_mesh)
    }
}

@Composable
private fun NameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(LinenDimens.DialogRadius),
        containerColor = Linen.colors.surface,
        title = { Text(stringResource(R.string.edit_name_title), style = Linen.type.title, color = Linen.colors.text) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MeshPayloads.MAX_NICKNAME_BYTES) },
                singleLine = true,
                textStyle = Linen.type.body,
                supportingText = { Text(stringResource(R.string.edit_name_hint), style = Linen.type.caption) },
            )
        },
        confirmButton = {
            ActionButton(stringResource(R.string.save), { onSave(name) }, enabled = name.isNotBlank())
        },
        dismissButton = {
            ActionButton(stringResource(R.string.cancel), onDismiss, style = ActionStyle.Ghost)
        },
    )
}
