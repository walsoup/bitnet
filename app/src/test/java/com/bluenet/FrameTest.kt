package com.bluenet

import com.bluenet.multiplexer.Frame
import com.bluenet.multiplexer.FrameType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class FrameTest {

    @Test
    fun testFrameWriteAndRead() {
        val payload = "Hello BlueNet Multiplexer!".toByteArray(Charsets.UTF_8)
        val originalFrame = Frame(FrameType.DATA, streamId = 42, payload = payload)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        originalFrame.writeTo(dos)

        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val readFrame = Frame.readFrom(dis)

        assertNotNull(readFrame)
        assertEquals(FrameType.DATA, readFrame!!.type)
        assertEquals(42, readFrame.streamId)
        assertArrayEquals(payload, readFrame.payload)
    }

    @Test
    fun testFrameTypesFromCode() {
        assertEquals(FrameType.CONNECT_TCP, FrameType.fromCode(0x01))
        assertEquals(FrameType.DATA, FrameType.fromCode(0x02))
        assertEquals(FrameType.CLOSE, FrameType.fromCode(0x03))
        assertEquals(FrameType.CONNECT_UDP, FrameType.fromCode(0x04))
        assertEquals(FrameType.KEEPALIVE, FrameType.fromCode(0x05))
        assertEquals(FrameType.COMPRESSED_DATA, FrameType.fromCode(0x06))
    }

    @Test
    fun testCompressionAndDecompression() {
        // Large repeating text payload that compresses very well
        val originalText = "BlueNet L2CAP High-Speed Tethering Proxy Protocol ".repeat(50)
        val payload = originalText.toByteArray(Charsets.UTF_8)

        val frame = Frame(FrameType.DATA, streamId = 1, payload = payload)
        val compressedFrame = frame.compressIfBeneficial()

        assertEquals(FrameType.COMPRESSED_DATA, compressedFrame.type)
        assertTrue("Compressed payload size should be smaller than original", compressedFrame.payload.size < payload.size)

        val decompressedPayload = compressedFrame.decompressPayload()
        assertArrayEquals(payload, decompressedPayload)
    }

    @Test
    fun testSmallPayloadNotCompressed() {
        val payload = "Tiny payload".toByteArray()
        val frame = Frame(FrameType.DATA, streamId = 1, payload = payload)
        val compressedFrame = frame.compressIfBeneficial()

        assertEquals(FrameType.DATA, compressedFrame.type)
        assertArrayEquals(payload, compressedFrame.payload)
    }
}
