package com.scrcpy.adb

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbSync(private val stream: AdbStream) : AutoCloseable {

    fun sendFile(input: InputStream, remotePath: String, mode: Int = 0b110100100) { // 0644
        // Protocol: "SEND" + pathLen (4 bytes LE) + path,mode
        val pathAndMode = "$remotePath,$mode"
        val pathBytes = pathAndMode.toByteArray(Charsets.UTF_8)
        
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ID_SEND)
        header.putInt(pathBytes.size)
        stream.outputStream.write(header.array())
        stream.outputStream.write(pathBytes)

        val buffer = ByteArray(SYNC_CHUNK_SIZE)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            val chunkHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            chunkHeader.put(ID_DATA)
            chunkHeader.putInt(read)
            stream.outputStream.write(chunkHeader.array())
            stream.outputStream.write(buffer, 0, read)
        }

        // Send DONE
        val doneHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        doneHeader.put(ID_DONE)
        doneHeader.putInt((System.currentTimeMillis() / 1000).toInt())
        stream.outputStream.write(doneHeader.array())

        // Read OKAY or FAIL
        val respHeader = ByteArray(8)
        var respRead = 0
        while (respRead < 8) {
            val count = stream.inputStream.read(respHeader, respRead, 8 - respRead)
            if (count < 0) throw IllegalStateException("Unexpected EOF reading sync response")
            respRead += count
        }
        val respBuf = ByteBuffer.wrap(respHeader).order(ByteOrder.LITTLE_ENDIAN)
        val idBytes = ByteArray(4)
        respBuf.get(idBytes)
        val status = String(idBytes, Charsets.US_ASCII)
        if (status == "FAIL") {
            val msgLen = respBuf.getInt()
            val msgBytes = ByteArray(msgLen)
            var msgRead = 0
            while (msgRead < msgLen) {
                val count = stream.inputStream.read(msgBytes, msgRead, msgLen - msgRead)
                if (count < 0) break
                msgRead += count
            }
            throw IllegalStateException("Sync failed: " + String(msgBytes, Charsets.UTF_8))
        } else if (status != "OKAY") {
            throw IllegalStateException("Sync invalid status: $status")
        }
    }

    override fun close() {
        try {
            val quit = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            quit.put(ID_QUIT)
            quit.putInt(0)
            stream.outputStream.write(quit.array())
        } catch (_: Exception) {}
        stream.close()
    }

    companion object {
        val ID_SEND = byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
        val ID_DATA = byteArrayOf('D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte())
        val ID_DONE = byteArrayOf('D'.code.toByte(), 'O'.code.toByte(), 'N'.code.toByte(), 'E'.code.toByte())
        val ID_QUIT = byteArrayOf('Q'.code.toByte(), 'U'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte())
        const val SYNC_CHUNK_SIZE = 64 * 1024
    }
}
