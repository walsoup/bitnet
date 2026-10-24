package com.bluenet.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.bluenet.R
import com.bluenet.core.mesh.ChatMessage
import com.bluenet.core.mesh.MessageStatus
import com.bluenet.node.Peer
import com.walsoup.linen.EmptyState
import com.walsoup.linen.Hairline
import com.walsoup.linen.Linen
import com.walsoup.linen.LinenDimens
import com.walsoup.linen.RoundIconButton
import java.util.Date

data class ChatUiState(
    val title: String,
    /** True for the broadcast chat, where every bubble names its sender. */
    val broadcast: Boolean,
    /** The other peer, or null when it is out of range (always null for the broadcast chat). */
    val peer: Peer?,
    val linked: Boolean,
    val messages: List<ChatMessage>,
)

@Composable
fun ChatScreen(state: ChatUiState, onBack: () -> Unit, onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Linen.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ChatHeader(state, onBack)

        if (!state.linked) {
            Text(
                text = stringResource(R.string.chat_unreachable),
                style = Linen.type.caption,
                color = Linen.colors.rec,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Linen.colors.recSoft)
                    .padding(horizontal = LinenDimens.ScreenMargin, vertical = 10.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                EmptyState(
                    stringResource(R.string.chat_empty),
                    Modifier.padding(horizontal = LinenDimens.ScreenMargin),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = LinenDimens.ScreenMargin, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    Bubble(message, showSender = state.broadcast && !message.outgoing)
                }
            }
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            onSend = {
                if (draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
            },
        )
    }
}

@Composable
private fun ChatHeader(state: ChatUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LinenDimens.ScreenMargin, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(state.title, style = Linen.type.title, color = Linen.colors.text, maxLines = 1)
            Text(
                text = when {
                    state.broadcast -> stringResource(R.string.broadcast_chat_subtitle)
                    state.peer == null -> stringResource(R.string.peer_out_of_range)
                    else -> peerSubtitle(state.peer)
                },
                style = Linen.type.caption,
                color = Linen.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
    Hairline()
}

@Composable
private fun Bubble(message: ChatMessage, showSender: Boolean) {
    val c = Linen.colors
    val shape = RoundedCornerShape(LinenDimens.CardRadius)
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(
                    if (message.outgoing) {
                        Modifier.background(c.container, shape)
                    } else {
                        Modifier
                            .background(c.surface, shape)
                            .border(1.dp, c.hairline, shape)
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (showSender) {
                Text(message.nickname, style = Linen.type.label, color = c.primary)
            }
            Text(message.text, style = Linen.type.body, color = c.text)
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(timeFormat.format(Date(message.timestamp)), style = Linen.type.monoTiny, color = c.muted)
                bubbleMeta(message)?.let {
                    Text(" · $it", style = Linen.type.caption, color = c.muted)
                }
            }
        }
    }
}

@Composable
private fun bubbleMeta(message: ChatMessage): String? = when {
    message.outgoing && message.status == MessageStatus.DELIVERED -> stringResource(R.string.chat_delivered)
    message.outgoing -> stringResource(R.string.chat_sent)
    message.hops > 1 -> stringResource(R.string.chat_via_hops, message.hops)
    else -> null
}

/** Message field and send button in one pill, in the thumb zone. */
@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    val c = Linen.colors
    val shape = RoundedCornerShape(LinenDimens.DockRadius)
    val canSend = draft.isNotBlank()

    Row(
        modifier = Modifier
            .padding(horizontal = LinenDimens.ScreenMargin, vertical = 12.dp)
            .fillMaxWidth()
            .heightIn(min = LinenDimens.DockHeight)
            .background(c.surface, shape)
            .border(1.dp, c.hairline, shape)
            .padding(start = 22.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            textStyle = Linen.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.primary),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { field ->
                if (draft.isEmpty()) {
                    Text(stringResource(R.string.chat_hint), style = Linen.type.body, color = c.muted)
                }
                field()
            },
        )
        Spacer(Modifier.width(10.dp))
        RoundIconButton(
            icon = Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(R.string.chat_send),
            onClick = onSend,
            container = if (canSend) c.primary else c.container,
            tint = if (canSend) c.onPrimary else c.muted,
        )
    }
}
