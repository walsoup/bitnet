package com.bluenet.core.tunnel

import java.util.concurrent.Executor

/** Growable FIFO byte buffer. Not thread-safe. */
class ByteQueue(initialCapacity: Int = 4096) {
    private var data = ByteArray(initialCapacity)
    private var head = 0
    private var tail = 0

    val size: Int get() = tail - head

    fun append(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        System.arraycopy(bytes, 0, data, tail, bytes.size)
        tail += bytes.size
    }

    fun drop(count: Int) {
        require(count in 0..size)
        head += count
        if (head == tail) {
            head = 0
            tail = 0
        }
    }

    fun copy(offset: Int, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset + count <= size)
        return data.copyOfRange(head + offset, head + offset + count)
    }

    private fun ensureCapacity(extra: Int) {
        if (tail + extra <= data.size) return
        val needed = size + extra
        val target = if (needed <= data.size) data else ByteArray(maxOf(data.size * 2, needed))
        System.arraycopy(data, head, target, 0, size)
        tail = size
        head = 0
        data = target
    }
}

/** Runs submitted tasks one at a time, in order, on a shared backing executor. */
class SerialExecutor(private val backing: Executor) : Executor {
    private val tasks = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    @Synchronized
    override fun execute(command: Runnable) {
        tasks.addLast(Runnable {
            try {
                command.run()
            } finally {
                scheduleNext()
            }
        })
        if (active == null) scheduleNext()
    }

    @Synchronized
    private fun scheduleNext() {
        active = tasks.removeFirstOrNull()
        active?.let {
            try {
                backing.execute(it)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                tasks.clear()
                active = null
            }
        }
    }
}
