package com.bluenet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluenet.databinding.ActivityChatBinding
import com.bluenet.mesh.MeshService
import com.bluenet.utils.ChatAdapter
import com.bluenet.utils.PreferencesManager
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private var meshService: MeshService? = null
    private var isMeshBound = false
    private lateinit var chatAdapter: ChatAdapter
    private var peerId: String = ""
    private var localPeerId: String = ""

    companion object {
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
    }

    private val meshServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.MeshBinder
            meshService = binder.getService()
            isMeshBound = true
            observeMessages()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isMeshBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: ""
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Chat"
        localPeerId = PreferencesManager.getPeerId(this)

        binding.toolbar.title = peerName
        binding.toolbar.setNavigationOnClickListener { finish() }

        chatAdapter = ChatAdapter(localPeerId)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvChat.layoutManager = layoutManager
        binding.rvChat.adapter = chatAdapter

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty() && meshService != null) {
                val nickname = PreferencesManager.getDisplayName(this)
                meshService?.meshRouter?.sendTextMessage(peerId, text, nickname)
                binding.etMessage.text?.clear()
            }
        }

        val meshIntent = Intent(this, MeshService::class.java)
        bindService(meshIntent, meshServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeMessages() {
        val store = meshService?.messageStore ?: return
        lifecycleScope.launch {
            store.messagesFlow.collect { messages ->
                val conversation = store.getConversation(peerId)
                chatAdapter.submitList(conversation) {
                    if (conversation.isNotEmpty()) {
                        binding.rvChat.scrollToPosition(conversation.size - 1)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isMeshBound) {
            unbindService(meshServiceConnection)
            isMeshBound = false
        }
        super.onDestroy()
    }
}
