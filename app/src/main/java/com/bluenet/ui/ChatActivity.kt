package com.bluenet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluenet.R
import com.bluenet.core.mesh.ChatMessage
import com.bluenet.databinding.ActivityChatBinding
import com.bluenet.node.MeshNode
import com.bluenet.node.MeshRuntime
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A direct conversation with one peer, or the broadcast chat when no peer ID is given. */
class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private val node: MeshNode by lazy { MeshRuntime.node(this) }
    private var peerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER_ID)
        val conversation = peerId ?: ChatMessage.BROADCAST_CONVERSATION
        binding.titleText.text = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.broadcast_chat)

        val adapter = MessageAdapter(showSender = peerId == null)
        binding.messageList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messageList.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.sendButton.isEnabled = false
        binding.messageInput.doAfterTextChanged { binding.sendButton.isEnabled = !it.isNullOrBlank() }
        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text.toString()
            if (text.isBlank()) return@setOnClickListener
            node.sendText(peerId, text)
            binding.messageInput.text.clear()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    node.messages.messages
                        .map { all -> all.filter { it.conversationId == conversation } }
                        .distinctUntilChanged()
                        .collect { messages ->
                            adapter.submitList(messages) {
                                if (messages.isNotEmpty()) binding.messageList.scrollToPosition(messages.size - 1)
                            }
                            binding.emptyText.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                        }
                }
                launch {
                    combine(node.peers, node.linkedCount) { peers, linked -> peers to linked }.collect { (peers, linked) ->
                        val id = peerId
                        binding.subtitleText.text = if (id == null) {
                            getString(R.string.broadcast_chat_subtitle)
                        } else {
                            val peer = peers.firstOrNull { it.id == id }
                            when {
                                peer == null -> getString(R.string.peer_out_of_range)
                                else -> PeerAdapter.details(this@ChatActivity, peer)
                            }
                        }
                        binding.offlineText.visibility = if (linked == 0) View.VISIBLE else View.GONE
                    }
                }
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
