package com.bluenet

import com.bluenet.multiplexer.Frame
import com.bluenet.multiplexer.FrameType
import com.bluenet.multiplexer.StreamMultiplexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamMultiplexerTest {

    @Test
    fun testStreamIdGeneration() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream()

        val multiplexer = StreamMultiplexer(
            inputStream = pipeIn,
            outputStream = pipeOut,
            onFrameReceived = {},
            onError = {}
        )

        val id1 = multiplexer.generateStreamId()
        val id2 = multiplexer.generateStreamId()
        val id3 = multiplexer.generateStreamId()

        assertEquals(1, id1)
        assertEquals(2, id2)
        assertEquals(3, id3)
        multiplexer.close()
    }

    @Test
    fun testFrameTransmissionBetweenMultiplexers() {
        val out1 = PipedOutputStream()
        val in2 = PipedInputStream(out1)

        val out2 = PipedOutputStream()
        val in1 = PipedInputStream(out2)

        val latch = CountDownLatch(1)
        var receivedFrame: Frame? = null

        val mux1 = StreamMultiplexer(
            inputStream = in1,
            outputStream = out1,
            onFrameReceived = {},
            onError = {},
            enableKeepAlive = false
        )

        val mux2 = StreamMultiplexer(
            inputStream = in2,
            outputStream = out2,
            onFrameReceived = { frame ->
                receivedFrame = frame
                latch.countDown()
            },
            onError = {},
            enableKeepAlive = false
        )

        mux1.start()
        mux2.start()

        val testData = "Multiplexer Integration Test Payload".toByteArray()
        val frameToSend = Frame(FrameType.DATA, streamId = 100, payload = testData)
        mux1.sendFrame(frameToSend)

        val completed = latch.await(3, TimeUnit.SECONDS)
        assertTrue("Frame should be received within timeout", completed)
        assertNotNull(receivedFrame)
        assertEquals(100, receivedFrame!!.streamId)
        assertEquals(FrameType.DATA, receivedFrame!!.type)

        mux1.close()
        mux2.close()
    }
}
