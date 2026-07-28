package com.bluenet.mesh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

data class MeshPeerAnnouncement(
    val protocolVersion: Byte,
    val peerId: String,
    val isSharingInternet: Boolean,
    val hasName: Boolean,
    val psm: Int,
    val displayName: String?
) {
    fun toBytes(): ByteArray {
        val nameBytes = if (hasName && displayName != null) {
            val fullBytes = displayName.toByteArray(StandardCharsets.UTF_8)
            if (fullBytes.size > MeshConstants.MAX_DISPLAY_NAME_LENGTH) {
                var byteLength = 0
                var validCharLength = 0
                var i = 0
                while (i < displayName.length) {
                    val codePoint = displayName.codePointAt(i)
                    val charCount = Character.charCount(codePoint)
                    val str = displayName.substring(i, i + charCount)
                    val bytes = str.toByteArray(StandardCharsets.UTF_8)
                    if (byteLength + bytes.size > MeshConstants.MAX_DISPLAY_NAME_LENGTH) {
                        break
                    }
                    byteLength += bytes.size
                    validCharLength += charCount
                    i += charCount
                }
                displayName.substring(0, validCharLength).toByteArray(StandardCharsets.UTF_8)
            } else fullBytes
        } else ByteArray(0)

        val bufferSize = 1 + 8 + 1 + (if (isSharingInternet) 2 else 0) + nameBytes.size
        val buffer = ByteBuffer.allocate(bufferSize)
        
        buffer.put(protocolVersion)
        
        // Convert peerId (hex string) to byte array padded to 8 bytes
        val peerIdBytes = peerId.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        val paddedPeerId = ByteArray(8)
        System.arraycopy(peerIdBytes, 0, paddedPeerId, 0, minOf(peerIdBytes.size, 8))
        buffer.put(paddedPeerId)
        
        var flags = 0
        if (isSharingInternet) flags = flags or 0x01
        if (hasName && nameBytes.isNotEmpty()) flags = flags or 0x02
        buffer.put(flags.toByte())
        
        if (isSharingInternet) {
            buffer.putShort(psm.toShort())
        }
        
        if (hasName && nameBytes.isNotEmpty()) {
            buffer.put(nameBytes)
        }
        
        return buffer.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): MeshPeerAnnouncement? {
            try {
                val buffer = ByteBuffer.wrap(bytes)
                if (buffer.remaining() < 10) return null // Need at least proto(1) + id(8) + flags(1)
                
                val protocolVersion = buffer.get()
                if (protocolVersion != MeshConstants.PROTOCOL_VERSION) return null
                
                val peerIdBytes = ByteArray(8)
                buffer.get(peerIdBytes)
                val hexList = peerIdBytes.map { "%02x".format(it.toInt() and 0xFF) }
                val lastNonZero = hexList.indexOfLast { it != "00" }
                val peerId = if (lastNonZero < 0) hexList.joinToString("") else hexList.take(lastNonZero + 1).joinToString("")
                
                val flags = buffer.get().toInt()
                val isSharingInternet = (flags and 0x01) != 0
                val hasName = (flags and 0x02) != 0
                
                var psm = 0
                if (isSharingInternet) {
                    if (buffer.remaining() < 2) return null
                    psm = buffer.short.toInt() and 0xFFFF
                    if (psm <= 0 || psm > 65535) return null
                }
                
                var displayName: String? = null
                if (hasName && buffer.remaining() > 0) {
                    val nameBytes = ByteArray(minOf(buffer.remaining(), MeshConstants.MAX_DISPLAY_NAME_LENGTH))
                    buffer.get(nameBytes)
                    displayName = String(nameBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                }
                
                return MeshPeerAnnouncement(protocolVersion, peerId, isSharingInternet, hasName, psm, displayName)
            } catch (e: Exception) {
                return null
            }
        }
    }
}
