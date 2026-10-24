package com.bluenet.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDirectoryTest {
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val c = "cccccccccccccccc"

    @Test
    fun signedAnnouncementNameWinsOverBleHint() {
        val peers = PeerDirectory.merge(
            nearby = listOf(NearbyPeer(a, "Wal's p", isSharingInternet = false, rssi = -60)),
            contacts = listOf(Contact(a, "Wal's phone", isSharingInternet = true, hops = 1, lastSeenMs = 0)),
            linked = setOf(a),
        )
        val peer = peers.single()
        assertEquals("Wal's phone", peer.name)
        assertTrue(peer.isSharingInternet)
        assertTrue(peer.canProvideInternet)
        assertTrue(peer.linked)
    }

    @Test
    fun remoteSharerCannotProvideInternet() {
        val peer = PeerDirectory.merge(emptyList(), listOf(Contact(b, "far", true, hops = 3, lastSeenMs = 0)), emptySet()).single()
        assertTrue(peer.isSharingInternet)
        assertFalse(peer.nearby)
        assertFalse(peer.canProvideInternet)
        assertNull(peer.rssi)
    }

    @Test
    fun sortsUsableSharersFirstThenLinkedThenBySignal() {
        val peers = PeerDirectory.merge(
            nearby = listOf(
                NearbyPeer(a, "weak", false, -90),
                NearbyPeer(b, "strong", false, -40),
                NearbyPeer(c, "sharer", true, -95),
            ),
            contacts = emptyList(),
            linked = setOf(a),
        )
        assertEquals(listOf("sharer", "weak", "strong"), peers.map { it.name })
    }

    @Test
    fun fallsBackToShortIdAndExpiresStaleContacts() {
        val peer = PeerDirectory.merge(emptyList(), emptyList(), setOf(c)).single()
        assertEquals("cccccc", peer.name)

        val contacts = mapOf(
            a to Contact(a, "old", false, 1, lastSeenMs = 0),
            b to Contact(b, "new", false, 1, lastSeenMs = PeerDirectory.CONTACT_TIMEOUT_MS),
        )
        assertEquals(setOf(b), PeerDirectory.expire(contacts, PeerDirectory.CONTACT_TIMEOUT_MS + 1).keys)
    }
}
