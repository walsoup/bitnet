package com.bluenet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAnnouncementTest {

    @Test
    fun roundTrips() {
        val announcement = BleAnnouncement("0123456789abcdef", isSharingInternet = true, psm = 0x00C1, name = "Wal")
        assertEquals(announcement, BleAnnouncement.decode(announcement.encode()))
    }

    @Test
    fun peerIdWithTrailingZeroBytesSurvives() {
        val announcement = BleAnnouncement("ab00000000000000", isSharingInternet = false, psm = 128, name = "")
        assertEquals("ab00000000000000", BleAnnouncement.decode(announcement.encode())!!.peerId)
    }

    @Test
    fun longNamesAreTruncatedOnACodePointBoundaryAndFitTheScanResponse() {
        val encoded = BleAnnouncement("0123456789abcdef", false, 200, "Zoë's phone 📱📱").encode()
        val decoded = BleAnnouncement.decode(encoded)!!
        assertEquals("Zoë's p", decoded.name)
        // AD length + type + company ID + payload must fit the 31-byte scan response.
        assertTrue(2 + 2 + encoded.size <= 31)
    }

    @Test
    fun rejectsMalformedPayloads() {
        val valid = BleAnnouncement("0123456789abcdef", false, 200, "x").encode()
        assertNull(BleAnnouncement.decode(null))
        assertNull(BleAnnouncement.decode(valid.copyOf(11)))
        assertNull(BleAnnouncement.decode(valid.copyOf().also { it[0] = 1 }))
        assertNull(BleAnnouncement.decode(valid.copyOf().also { it[10] = 0; it[11] = 0 }))
        assertNull(BleAnnouncement.decode(valid + ByteArray(10)))
    }
}
