package com.bluenet.mesh

import org.junit.Assert.*
import org.junit.Test

class MeshPeerAnnouncementTest {

    @Test
    fun testToBytesAndFromBytesRoundTrip() {
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = "a1b2c3d4e5f67890",
            isSharingInternet = true,
            hasName = true,
            psm = 1234,
            displayName = "Peer1"
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals(MeshConstants.PROTOCOL_VERSION, parsed!!.protocolVersion)
        assertEquals("a1b2c3d4e5f67890", parsed.peerId)
        assertTrue(parsed.isSharingInternet)
        assertTrue(parsed.hasName)
        assertEquals(1234, parsed.psm)
        assertEquals("Peer1", parsed.displayName)
    }

    @Test
    fun testShortPeerIdPaddingAndParsing() {
        val shortPeerId = "123456"
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = shortPeerId,
            isSharingInternet = false,
            hasName = true,
            psm = 0,
            displayName = "Test"
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals("123456", parsed!!.peerId)
    }

    @Test
    fun testByteToHexSignExtension() {
        val hexWithHighBitBytes = "ff80fe7f"
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = hexWithHighBitBytes,
            isSharingInternet = false,
            hasName = false,
            psm = 0,
            displayName = null
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals("ff80fe7f", parsed!!.peerId)
        assertFalse(parsed.peerId.contains("ffff"))
    }

    @Test
    fun testMultiByteUtf8DisplayNameTruncation() {
        // "日本語123" is 9 bytes in UTF-8: 日(3B) 本(3B) 語(3B) 1(1B) 2(1B) 3(1B)
        // Truncating to 8 bytes without slicing mid-character should give "日本" (6 bytes)
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = "11223344",
            isSharingInternet = false,
            hasName = true,
            psm = 0,
            displayName = "日本語123"
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals("日本", parsed!!.displayName)
    }

    @Test
    fun testPsmValidation() {
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = "11223344",
            isSharingInternet = true,
            hasName = false,
            psm = 0, // Invalid PSM when sharing internet
            displayName = null
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        // Invalid PSM (0) when isSharingInternet is true should cause validation to fail and return null
        assertNull(parsed)
    }

    @Test
    fun testInvalidProtocolVersion() {
        val bytes = byteArrayOf(99, 0, 0, 0, 0, 0, 0, 0, 0, 0) // Proto 99
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)
        assertNull(parsed)
    }
}
