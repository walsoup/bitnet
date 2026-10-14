package com.bluenet.core

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Invalid hex character" }
        ((hi shl 4) or lo).toByte()
    }
}

/**
 * Peer IDs are the first [SIZE] bytes of SHA-256(public key), rendered as lowercase hex.
 * Binding the ID to the key is what lets the link handshake and signed mesh packets prove
 * that a peer really owns the ID it advertises.
 */
object PeerIds {
    const val SIZE = 8
    const val HEX_LENGTH = SIZE * 2
    val BROADCAST_BYTES = ByteArray(SIZE)

    fun isValid(id: String): Boolean =
        id.length == HEX_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    fun toBytes(id: String): ByteArray {
        require(isValid(id)) { "Invalid peer id: $id" }
        return id.hexToBytes()
    }

    fun fromBytes(bytes: ByteArray, offset: Int = 0): String =
        bytes.copyOfRange(offset, offset + SIZE).toHex()

    fun short(id: String): String = id.take(6)
}
