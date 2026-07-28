package com.bluenet.mesh

import org.junit.Assert.assertFalse
import org.junit.Test

class MeshAdvertiserTest {

    @Test
    fun testAdvertiserInitialState() {
        val advertiser = MeshAdvertiser(null)
        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun testStartWithNullAdapterFailsGracefully() {
        val advertiser = MeshAdvertiser(null)
        advertiser.start("peer123", "TestPeer", true, 100)
        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun testStopResetsStateSafely() {
        val advertiser = MeshAdvertiser(null)
        advertiser.stop()
        assertFalse(advertiser.isAdvertising)
    }

    @Test
    fun testUpdateAdvertisementSafely() {
        val advertiser = MeshAdvertiser(null)
        advertiser.updateAdvertisement(true, 100)
        assertFalse(advertiser.isAdvertising)
    }
}
