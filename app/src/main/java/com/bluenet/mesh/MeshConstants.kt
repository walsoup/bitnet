package com.bluenet.mesh

import java.util.UUID

object MeshConstants {
    val MESH_SERVICE_UUID: UUID = UUID.fromString("0000B10E-0000-1000-8000-00805F9B34FB")
    val MESH_CHAR_UUID: UUID = UUID.fromString("0000B10F-0000-1000-8000-00805F9B34FB")
    const val PROTOCOL_VERSION: Byte = 1
    const val ANNOUNCEMENT_INTERVAL_MS = 10000L
    const val PEER_TIMEOUT_MS = 60000L
    const val MAX_DISPLAY_NAME_LENGTH = 8
    const val MANUFACTURER_ID = 0xFFFF
}
