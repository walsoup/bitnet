package com.bluenet.mesh

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MeshPeerDiscoveryStressTest {

    // =========================================================================
    // 1. Binary Serialization Vulnerabilities (MeshPeerAnnouncement)
    // =========================================================================

    @Test
    fun testPeerIdWithTrailingZeroByteRoundTrip() {
        // Peer ID ending in byte 0x00, e.g. hex "1122334455667700"
        val peerIdWithZeroEnd = "1122334455667700"
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = peerIdWithZeroEnd,
            isSharingInternet = false,
            hasName = false,
            psm = 0,
            displayName = null
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull("Deserialization failed for peer ID ending in 00", parsed)
        assertEquals("Peer ID ending in 00 was corrupted during round-trip", peerIdWithZeroEnd, parsed!!.peerId)
    }

    @Test
    fun testHasNameFlagWithNullDisplayNameRoundTrip() {
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = "1122334455667788",
            isSharingInternet = false,
            hasName = true,
            psm = 0,
            displayName = null
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull(parsed)
        assertEquals("hasName flag should match parsed state", announcement.hasName, parsed!!.hasName)
    }

    @Test
    fun testExtremeUtf8EmojiDisplayNameTruncation() {
        // 4-byte UTF-8 emojis: 😀 (U+1F600) and 😁 (U+1F601) and 😂 (U+1F602)
        // 4 bytes each = 12 bytes total. MAX_DISPLAY_NAME_LENGTH is 8.
        // Should truncate cleanly to "😀😁" (8 bytes).
        val emojiName = "😀😁😂"
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = "1122334455667788",
            isSharingInternet = false,
            hasName = true,
            psm = 0,
            displayName = emojiName
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull("Deserialization failed for emoji display name", parsed)
        assertEquals("😀😁", parsed!!.displayName)
    }

    @Test
    fun testZwjFamilyEmojiDisplayNameSafety() {
        val familyEmoji = "👨‍👩‍👧‍👦"
        val announcement = MeshPeerAnnouncement(
            protocolVersion = MeshConstants.PROTOCOL_VERSION,
            peerId = "1122334455667788",
            isSharingInternet = false,
            hasName = true,
            psm = 0,
            displayName = familyEmoji
        )
        val bytes = announcement.toBytes()
        val parsed = MeshPeerAnnouncement.fromBytes(bytes)

        assertNotNull("Deserialization failed for ZWJ emoji", parsed)
        assertTrue("Truncated name byte length must be <= 8", parsed!!.displayName!!.toByteArray(Charsets.UTF_8).size <= 8)
    }

    @Test
    fun testCorruptedByteArrays() {
        // 1. Empty byte array
        assertNull("Empty byte array should return null", MeshPeerAnnouncement.fromBytes(byteArrayOf()))

        // 2. Truncated byte array (less than 10 bytes minimum)
        val truncated = byteArrayOf(1, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte()) // 9 bytes
        assertNull("9-byte array should return null", MeshPeerAnnouncement.fromBytes(truncated))

        // 3. Flags say isSharingInternet (0x01), but buffer ends without PSM
        val missingPsmBytes = byteArrayOf(1, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x01) // 10 bytes, flags=0x01, no PSM short
        assertNull("Missing PSM should return null", MeshPeerAnnouncement.fromBytes(missingPsmBytes))

        // 4. Undefined flag bits set (e.g. 0xFC)
        val extraFlagsBytes = byteArrayOf(1, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0xFC.toByte())
        val parsedExtraFlags = MeshPeerAnnouncement.fromBytes(extraFlagsBytes)
        assertNotNull("Packet with extra flag bits should parse without throwing exception", parsedExtraFlags)
        assertFalse(parsedExtraFlags!!.isSharingInternet)
        assertFalse(parsedExtraFlags.hasName)
    }

    @Test
    fun testPsmBoundaries() {
        // PSM = 1 (valid minimum)
        val ann1 = MeshPeerAnnouncement(1, "1122334455667788", true, false, 1, null)
        val parsed1 = MeshPeerAnnouncement.fromBytes(ann1.toBytes())
        assertNotNull(parsed1)
        assertEquals(1, parsed1!!.psm)

        // PSM = 65535 (0xFFFF, maximum 16-bit unsigned short)
        val annMax = MeshPeerAnnouncement(1, "1122334455667788", true, false, 65535, null)
        val parsedMax = MeshPeerAnnouncement.fromBytes(annMax.toBytes())
        assertNotNull("PSM 65535 (0xFFFF) should parse as unsigned short 65535", parsedMax)
        assertEquals(65535, parsedMax!!.psm)
    }


    // =========================================================================
    // 2. Sorting & Eviction Vulnerabilities (MeshScanner)
    // =========================================================================

    @Test
    fun testLocalPeerIdCaseSensitivityInScanner() {
        val scanner = MeshScanner(null)
        // Start scanner with UPPERCASE peer ID
        scanner.start("A1B2C3D4E5F67890")

        // Parse announcement which generates lowercase peer ID "a1b2c3d4e5f67890" via %02x
        val announcement = MeshPeerAnnouncement.fromBytes(
            MeshPeerAnnouncement(
                protocolVersion = 1,
                peerId = "A1B2C3D4E5F67890",
                isSharingInternet = false,
                hasName = true,
                psm = 0,
                displayName = "Self"
            ).toBytes()
        )

        assertNotNull(announcement)
        
        // Emulate scanner processScanResult localPeerId check logic:
        // Line 61 in MeshScanner: announcement.peerId == localPeerId
        val localPeerIdField = MeshScanner::class.java.getDeclaredField("localPeerId")
        localPeerIdField.isAccessible = true
        val storedLocalPeerId = localPeerIdField.get(scanner) as String
        
        val isFilteredOut = announcement!!.peerId == storedLocalPeerId
        assertTrue(
            "Local peer announcement ('${announcement.peerId}') was not filtered out when localPeerId was set to '$storedLocalPeerId'",
            isFilteredOut
        )
    }

    @Test
    fun testRapidConcurrentPeerMapAccessAndSorting() {
        val scanner = MeshScanner(null)
        val peersMapField = MeshScanner::class.java.getDeclaredField("_peersMap")
        peersMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val peersMap = peersMapField.get(scanner) as ConcurrentHashMap<String, MeshPeer>

        val updateMethod = MeshScanner::class.java.getDeclaredMethod("updatePeersList")
        updateMethod.isAccessible = true

        val numThreads = 10
        val updatesPerThread = 100
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val now = System.currentTimeMillis()

        for (t in 0 until numThreads) {
            val threadId = t
            executor.execute {
                try {
                    for (i in 0 until updatesPerThread) {
                        val peerId = "peer_%02d_%02d".format(threadId, i % 10)
                        peersMap[peerId] = MeshPeer(
                            peerId = peerId,
                            displayName = "P_$peerId",
                            macAddress = "00:11:22:33:%02d:%02d".format(threadId, i % 10),
                            isSharingInternet = (i % 2 == 0),
                            signalStrength = -50 - (i % 30),
                            lastSeen = now,
                            psm = if (i % 2 == 0) 100 + i else 0
                        )
                        updateMethod.invoke(scanner)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent churn completed", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val currentPeers = scanner.peers.value
        assertNotNull("Peers flow value should not be null", currentPeers)
        assertTrue("Peers map should contain discovered peers", currentPeers.isNotEmpty())
    }

    @Test
    fun testStalePeerEvictionExactBoundary() {
        val scanner = MeshScanner(null)
        val peersMapField = MeshScanner::class.java.getDeclaredField("_peersMap")
        peersMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val peersMap = peersMapField.get(scanner) as ConcurrentHashMap<String, MeshPeer>

        val now = System.currentTimeMillis()
        val peerBoundary = MeshPeer("boundary", "BoundaryPeer", "00:11:22:33:44:55", false, -60, now - MeshConstants.PEER_TIMEOUT_MS, 0)
        val peerStale = MeshPeer("stale", "StalePeer", "00:11:22:33:44:56", false, -60, now - (MeshConstants.PEER_TIMEOUT_MS + 100), 0)
        val peerFresh = MeshPeer("fresh", "FreshPeer", "00:11:22:33:44:57", false, -60, now - 1000, 0)

        peersMap[peerBoundary.peerId] = peerBoundary
        peersMap[peerStale.peerId] = peerStale
        peersMap[peerFresh.peerId] = peerFresh

        scanner.cleanupStalePeers()

        val activePeers = scanner.peers.value
        assertFalse("Stale peer (> 60s) should be evicted", activePeers.any { it.peerId == "stale" })
        assertTrue("Fresh peer (< 60s) should be kept", activePeers.any { it.peerId == "fresh" })
    }

    @Test
    fun testRssiSortingWithExtremeValues() {
        val scanner = MeshScanner(null)
        val peersMapField = MeshScanner::class.java.getDeclaredField("_peersMap")
        peersMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val peersMap = peersMapField.get(scanner) as ConcurrentHashMap<String, MeshPeer>

        val now = System.currentTimeMillis()
        peersMap["peerMin"] = MeshPeer("peerMin", "MinRSSI", "00:11:22:33:44:55", false, Int.MIN_VALUE, now, 0)
        peersMap["peerZero"] = MeshPeer("peerZero", "ZeroRSSI", "00:11:22:33:44:56", false, 0, now, 0)
        peersMap["peerNeg80"] = MeshPeer("peerNeg80", "Neg80RSSI", "00:11:22:33:44:57", false, -80, now, 0)
        peersMap["peerMax"] = MeshPeer("peerMax", "MaxRSSI", "00:11:22:33:44:58", false, Int.MAX_VALUE, now, 0)

        val updateMethod = MeshScanner::class.java.getDeclaredMethod("updatePeersList")
        updateMethod.isAccessible = true
        updateMethod.invoke(scanner)

        val sorted = scanner.peers.value
        assertEquals(4, sorted.size)
        assertEquals("peerMax", sorted[0].peerId)
        assertEquals("peerZero", sorted[1].peerId)
        assertEquals("peerNeg80", sorted[2].peerId)
        assertEquals("peerMin", sorted[3].peerId)
    }
}
