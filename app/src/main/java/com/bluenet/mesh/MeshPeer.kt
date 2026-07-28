package com.bluenet.mesh

data class MeshPeer(
    val peerId: String,
    val displayName: String,
    val macAddress: String,
    val isSharingInternet: Boolean,
    val signalStrength: Int,
    var lastSeen: Long = System.currentTimeMillis(),
    val psm: Int
)
