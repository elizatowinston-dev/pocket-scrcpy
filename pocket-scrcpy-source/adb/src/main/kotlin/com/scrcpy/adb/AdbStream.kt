package com.scrcpy.adb

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AdbStream(
    val localId: Int,
    var remoteId: Int,
    private val client: AdbClient
) : AutoCloseable {

    private val incomingQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private val isClosed = AtomicBoolean(false)
    private var currentBuffer: ByteArray? = null
    private var currentBufferOffset = 0

    val inputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val res = read(b, 0, 1)
            return if (res < 0) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (currentBuffer == null || currentBufferOffset >= currentBuffer!!.size) {
                if (isClosed.get() && incomingQueue.isEmpty()) return -1
                val chunk = incomingQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (chunk.isEmpty()) {
                    return -1 // EOF marker
                }
                currentBuffer = chunk
                currentBufferOffset = 0
            }

            val available = currentBuffer!!.size - currentBufferOffset
            val toCopy = minOf(len, available)
            System.arraycopy(currentBuffer!!, currentBufferOffset, b, off, toCopy)
            currentBufferOffset += toCopy
            return toCopy
        }

        override fun close() {
            this@AdbStream.close()
        }
    }

    val outputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (isClosed.get()) throw IllegalStateException("Stream closed")
            val data = ByteArray(len)
            System.arraycopy(b, off, data, 0, len)
            client.sendWrite(this@AdbStream, data)
        }

        override fun flush() {}

        override fun close() {
            this@AdbStream.close()
        }
    }

    internal fun onDataReceived(data: ByteArray) {
        if (!isClosed.get()) {
            incomingQueue.offer(data)
        }
    }

    internal fun onRemoteClosed() {
        if (!isClosed.get()) {
            isClosed.set(true)
            incomingQueue.offer(ByteArray(0))
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            incomingQueue.offer(ByteArray(0))
            client.sendClose(this)
        }
    }
}
