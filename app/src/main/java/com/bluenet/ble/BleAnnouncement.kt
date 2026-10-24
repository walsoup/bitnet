package com.bluenet.ble

import com.bluenet.core.PeerIds
import com.bluenet.core.mesh.MeshPayloads
import java.util.UUID

/**
 * What a device broadcasts over BLE so nearby peers can find it and open an L2CAP link.
 *
 * Carried as manufacturer data in the scan response:
 * `version(1) | peerId(8) | flags(1) | psm(u16) | name(<= 8 bytes UTF-8)`.
 * The name is only a hint for the peer list; the authoritative name arrives in signed mesh
 * announcements once a link is up.
 */
data class BleAnnouncement(
    val peerId: String,
    val isSharingInternet: Boolean,
    val psm: Int,
    val name: String,
) {
    fun encode(): ByteArray {
        val nameBytes = MeshPayloads.truncateUtf8(name, MAX_NAME_BYTES)
        val out = ByteArray(FIXED_SIZE + nameBytes.size)
        out[0] = VERSION.toByte()
        PeerIds.toBytes(peerId).copyInto(out, 1)
        out[9] = (if (isSharingInternet) FLAG_SHARING else 0).toByte()
        out[10] = (psm ushr 8).toByte()
        out[11] = psm.toByte()
        nameBytes.copyInto(out, FIXED_SIZE)
        return out
    }

    companion object {
        const val VERSION = 2
        /** Bluetooth SIG "reserved for testing" company identifier. */
        const val MANUFACTURER_ID = 0xFFFF
        val SERVICE_UUID: UUID = UUID.fromString("0000b10e-0000-1000-8000-00805f9b34fb")

        const val MAX_NAME_BYTES = 8
        private const val FIXED_SIZE = 12
        private const val FLAG_SHARING = 0x01

        fun decode(bytes: ByteArray?): BleAnnouncement? {
            if (bytes == null || bytes.size < FIXED_SIZE || bytes.size > FIXED_SIZE + MAX_NAME_BYTES) return null
            if (bytes[0].toInt() != VERSION) return null
            val psm = ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
            if (psm == 0) return null
            return BleAnnouncement(
                peerId = PeerIds.fromBytes(bytes, 1),
                isSharingInternet = bytes[9].toInt() and FLAG_SHARING != 0,
                psm = psm,
                name = String(bytes, FIXED_SIZE, bytes.size - FIXED_SIZE, Charsets.UTF_8),
            )
        }
    }
}
