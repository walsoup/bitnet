package com.bluenet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluenet.R
import com.bluenet.core.mesh.ChatMessage
import com.bluenet.node.MeshNode
import com.bluenet.node.MeshRuntime
import com.walsoup.linen.LinenTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A direct conversation with one peer, or the broadcast chat when no peer ID is given. */
class ChatActivity : ComponentActivity() {
    private val node: MeshNode by lazy { MeshRuntime.node(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val peerId = intent.getStringExtra(EXTRA_PEER_ID)
        val conversation = peerId ?: ChatMessage.BROADCAST_CONVERSATION
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.broadcast_chat)

        setContent {
            val conversationMessages = remember {
                node.messages.messages
                    .map { all -> all.filter { it.conversationId == conversation } }
                    .distinctUntilChanged()
            }
            val messages by conversationMessages.collectAsStateWithLifecycle(emptyList())
            val peers by node.peers.collectAsStateWithLifecycle()
            val linked by node.linkedCount.collectAsStateWithLifecycle()

            LinenTheme {
                ChatScreen(
                    state = ChatUiState(
                        title = title,
                        broadcast = peerId == null,
                        peer = peerId?.let { id -> peers.firstOrNull { it.id == id } },
                        linked = linked > 0,
                        messages = messages,
                    ),
                    onBack = ::finish,
                    onSend = { node.sendText(peerId, it) },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, peerId: String?, title: String): Intent =
            Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_PEER_ID, peerId)
                .putExtra(EXTRA_TITLE, title)
    }
}
