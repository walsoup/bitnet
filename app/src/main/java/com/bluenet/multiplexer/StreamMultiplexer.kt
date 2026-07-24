package com.bluenet.multiplexer

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamMultiplexer(
    inputStream: InputStream,
    outputStream: OutputStream,
    private val onFrameReceived: (Frame) -> Unit,
    private val onError: (Exception) -> Unit,
    private val enableKeepAlive: Boolean = true
) {
    private val dis = DataInputStream(inputStream)
    private val dos = DataOutputStream(outputStream)

    private val isRunning = AtomicBoolean(true)
    private val nextStreamId = AtomicInteger(1)
    private var readerThread: Thread? = null
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var lastActivityTimestamp = System.currentTimeMillis()

    fun start() {
        readerThread = Thread({ readLoop() }, "MultiplexerReaderThread").apply { start() }
        if (enableKeepAlive) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler?.scheduleWithFixedDelay({
                checkHeartbeat()
            }, KEEP_ALIVE_INTERVAL_SEC, KEEP_ALIVE_INTERVAL_SEC, TimeUnit.SECONDS)
        }
    }

    private fun checkHeartbeat() {
        if (!isRunning.get()) return
        val now = System.currentTimeMillis()
        val idleMs = now - lastActivityTimestamp

        if (idleMs >= TIMEOUT_MS) {
            Log.w(TAG, "Multiplexer heartbeat timeout (idle for ${idleMs}ms), closing connection")
            val ex = IOException("Heartbeat timeout - connection lost")
            close()
            onError(ex)
        } else if (idleMs >= KEEP_ALIVE_INTERVAL_SEC * 1000) {
            sendFrame(Frame(FrameType.KEEPALIVE, 0))
        }
    }

    fun generateStreamId(): Int {
        return nextStreamId.getAndIncrement()
    }

    fun sendFrame(frame: Frame) {
        if (!isRunning.get()) return
        try {
            val optimizedFrame = frame.compressIfBeneficial()
            optimizedFrame.writeTo(dos)
            lastActivityTimestamp = System.currentTimeMillis()
        } catch (e: IOException) {
            Log.e(TAG, "Error sending frame streamId=${frame.streamId}", e)
            close()
            onError(e)
        }
    }

    private fun readLoop() {
        try {
            while (isRunning.get()) {
                val frame = Frame.readFrom(dis) ?: break
                lastActivityTimestamp = System.currentTimeMillis()
                if (frame.type == FrameType.COMPRESSED_DATA) {
                    val decompressed = frame.decompressPayload()
                    val normalizedFrame = Frame(FrameType.DATA, frame.streamId, decompressed)
                    onFrameReceived(normalizedFrame)
                } else {
                    onFrameReceived(frame)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error reading from L2CAP stream", e)
                close()
                onError(e)
            }
        }
    }

    fun close() {
        if (isRunning.compareAndSet(true, false)) {
            scheduler?.shutdownNow()
            scheduler = null
            try {
                dis.close()
            } catch (_: IOException) {}
            try {
                dos.close()
            } catch (_: IOException) {}
            readerThread?.interrupt()
            readerThread = null
            Log.d(TAG, "StreamMultiplexer closed")
        }
    }

    companion object {
        private const val TAG = "StreamMultiplexer"
        private const val KEEP_ALIVE_INTERVAL_SEC = 15L
        private const val TIMEOUT_MS = 45_000L
    }
}
