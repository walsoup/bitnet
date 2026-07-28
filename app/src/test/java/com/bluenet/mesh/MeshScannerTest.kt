package com.bluenet.mesh

import org.junit.Assert.*
import org.junit.Test

class MeshScannerTest {

    @Test
    fun testMeshScannerInitialState() {
        val scanner = MeshScanner(null)
        assertTrue(scanner.peers.value.isEmpty())
    }

    @Test
    fun testPeersSortingPrioritizesInternetSharersAndSignalStrength() {
        val scanner = MeshScanner(null)

        val announcementA = MeshPeerAnnouncement(
            protocolVersion = 1,
            peerId = "peerA",
            isSharingInternet = false,
            hasName = true,
            psm = 0,
            displayName = "PeerA"
        )
        val announcementB = MeshPeerAnnouncement(
            protocolVersion = 1,
            peerId = "peerB",
            isSharingInternet = true,
            hasName = true,
            psm = 100,
            displayName = "PeerB"
        )
        val announcementC = MeshPeerAnnouncement(
            protocolVersion = 1,
            peerId = "peerC",
            isSharingInternet = true,
            hasName = true,
            psm = 200,
            displayName = "PeerC"
        )
        val announcementD = MeshPeerAnnouncement(
            protocolVersion = 1,
            peerId = "peerD",
            isSharingInternet = false,
            hasName = true,
            psm = 0,
            displayName = "PeerD"
        )

        // Directly test peers sorting via MeshPeer addition in internal state or simulated announcement
        val peersMapField = MeshScanner::class.java.getDeclaredField("_peersMap")
        peersMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val peersMap = peersMapField.get(scanner) as java.util.concurrent.ConcurrentHashMap<String, MeshPeer>

        val now = System.currentTimeMillis()
        peersMap["peerA"] = MeshPeer("peerA", "PeerA", "00:11:22:33:44:55", false, -50, now, 0)
        peersMap["peerB"] = MeshPeer("peerB", "PeerB", "00:11:22:33:44:56", true, -80, now, 100)
        peersMap["peerC"] = MeshPeer("peerC", "PeerC", "00:11:22:33:44:57", true, -40, now, 200)
        peersMap["peerD"] = MeshPeer("peerD", "PeerD", "00:11:22:33:44:58", false, -30, now, 0)

        val updateMethod = MeshScanner::class.java.getDeclaredMethod("updatePeersList")
        updateMethod.isAccessible = true
        updateMethod.invoke(scanner)

        val sorted = scanner.peers.value
        assertEquals(4, sorted.size)
        // Internet sharers first (ordered by highest RSSI), then non-sharers (ordered by highest RSSI)
        assertEquals("peerC", sorted[0].peerId) // isSharing=true, RSSI -40
        assertEquals("peerB", sorted[1].peerId) // isSharing=true, RSSI -80
        assertEquals("peerD", sorted[2].peerId) // isSharing=false, RSSI -30
        assertEquals("peerA", sorted[3].peerId) // isSharing=false, RSSI -50
    }

    @Test
    fun testStalePeerEviction() {
        val scanner = MeshScanner(null)
        val peersMapField = MeshScanner::class.java.getDeclaredField("_peersMap")
        peersMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val peersMap = peersMapField.get(scanner) as java.util.concurrent.ConcurrentHashMap<String, MeshPeer>

        val now = System.currentTimeMillis()
        val freshPeer = MeshPeer("fresh", "FreshPeer", "00:11:22:33:44:55", false, -60, now - 5000, 0)
        val stalePeer = MeshPeer("stale", "StalePeer", "00:11:22:33:44:56", false, -60, now - 70000, 0) // > 60s old

        peersMap[freshPeer.peerId] = freshPeer
        peersMap[stalePeer.peerId] = stalePeer

        scanner.cleanupStalePeers()

        val activePeers = scanner.peers.value
        assertEquals(1, activePeers.size)
        assertEquals("fresh", activePeers[0].peerId)
    }
}
