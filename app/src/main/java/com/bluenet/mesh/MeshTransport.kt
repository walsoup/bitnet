package com.bluenet.mesh

interface MeshTransport {
    fun sendPacket(data: ByteArray, targetPeerId: String? = null): Boolean
    fun broadcastPacket(data: ByteArray): Boolean
    fun setPacketListener(listener: (data: ByteArray, fromPeerId: String) -> Unit)
    fun getConnectedPeers(): List<String>
}
