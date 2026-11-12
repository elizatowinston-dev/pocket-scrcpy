package com.scrcpy.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

sealed class DeviceMessage {
    data class Clipboard(val text: String) : DeviceMessage()
    data class AckClipboard(val sequence: Long) : DeviceMessage()
    data class UhidOutput(val id: Int, val data: ByteArray) : DeviceMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UhidOutput) return false
            return id == other.id && data.contentEquals(other.data)
        }
        override fun hashCode(): Int = 31 * id + data.contentHashCode()
    }

    companion object {
        const val TYPE_CLIPBOARD = 0
        const val TYPE_ACK_CLIPBOARD = 1
        const val TYPE_UHID_OUTPUT = 2

        fun parse(inputStream: InputStream): DeviceMessage {
            val dis = DataInputStream(inputStream)
            val type = dis.readUnsignedByte()
            return when (type) {
                TYPE_CLIPBOARD -> {
                    val len = dis.readInt()
                    val bytes = ByteArray(len)
                    dis.readFully(bytes)
                    Clipboard(String(bytes, StandardCharsets.UTF_8))
                }
                TYPE_ACK_CLIPBOARD -> {
                    val seq = dis.readLong()
                    AckClipboard(seq)
                }
                TYPE_UHID_OUTPUT -> {
                    val id = dis.readUnsignedShort()
                    val size = dis.readUnsignedShort()
                    val data = ByteArray(size)
                    dis.readFully(data)
                    UhidOutput(id, data)
                }
                else -> throw IllegalArgumentException("Unknown device message type: $type")
            }
        }
    }
}
