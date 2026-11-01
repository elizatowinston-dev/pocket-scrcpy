package com.scrcpy.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray = EMPTY_DATA
) {
    val magic: Int = command xor 0xFFFFFFFF.toInt()

    fun calculateChecksum(): Int {
        var sum = 0
        for (b in data) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }

    fun write(os: OutputStream) {
        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(calculateChecksum())
        header.putInt(magic)
        os.write(header.array())
        if (data.isNotEmpty()) {
            os.write(data)
        }
        os.flush()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbMessage) return false
        return command == other.command &&
                arg0 == other.arg0 &&
                arg1 == other.arg1 &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_LENGTH = 24
        val EMPTY_DATA = ByteArray(0)

        const val CMD_SYNC = 0x434e5953
        const val CMD_CNXN = 0x4e584e43
        const val CMD_OPEN = 0x4e45504f
        const val CMD_OKAY = 0x59414b4f
        const val CMD_CLSE = 0x45534c43
        const val CMD_WRTE = 0x45545257
        const val CMD_AUTH = 0x48545541

        const val AUTH_TYPE_TOKEN = 1
        const val AUTH_TYPE_SIGNATURE = 2
        const val AUTH_TYPE_RSAPUBLICKEY = 3

        const val MAX_PAYLOAD = 256 * 1024

        fun read(input: InputStream): AdbMessage {
            val headerBytes = ByteArray(HEADER_LENGTH)
            var read = 0
            while (read < HEADER_LENGTH) {
                val count = input.read(headerBytes, read, HEADER_LENGTH - read)
                if (count < 0) throw IllegalStateException("Unexpected EOF reading ADB header")
                read += count
            }
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.getInt()
            val arg0 = buf.getInt()
            val arg1 = buf.getInt()
            val dataLength = buf.getInt()
            val dataChecksum = buf.getInt()
            val magic = buf.getInt()

            if (command xor 0xFFFFFFFF.toInt() != magic) {
                throw IllegalStateException("Invalid ADB magic: command=$command magic=$magic")
            }

            val data = if (dataLength > 0) {
                val dataBuf = ByteArray(dataLength)
                var dataRead = 0
                while (dataRead < dataLength) {
                    val count = input.read(dataBuf, dataRead, dataLength - dataRead)
                    if (count < 0) throw IllegalStateException("Unexpected EOF reading ADB payload")
                    dataRead += count
                }
                dataBuf
            } else {
                EMPTY_DATA
            }

            return AdbMessage(command, arg0, arg1, data)
        }
    }
}
