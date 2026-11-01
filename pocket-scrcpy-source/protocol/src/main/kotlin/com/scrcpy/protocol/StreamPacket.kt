package com.scrcpy.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class StreamPacket(
    val pts: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamPacket) return false
        return pts == other.pts && isConfig == other.isConfig && isKeyFrame == other.isKeyFrame && data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var result = pts.hashCode()
        result = 31 * result + isConfig.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        const val PACKET_FLAG_SESSION = 1L shl 63
        const val PACKET_FLAG_CONFIG = 1L shl 62
        const val PACKET_FLAG_KEY_FRAME = 1L shl 61

        fun readDeviceMeta(inputStream: InputStream): String {
            val dis = DataInputStream(inputStream)
            val bytes = ByteArray(64)
            dis.readFully(bytes)
            var len = 0
            while (len < bytes.size && bytes[len] != 0.toByte()) {
                len++
            }
            return String(bytes, 0, len, StandardCharsets.UTF_8)
        }

        fun readCodecId(inputStream: InputStream): Int {
            val dis = DataInputStream(inputStream)
            return dis.readInt()
        }

        fun readPacket(inputStream: InputStream): StreamPacket {
            val dis = DataInputStream(inputStream)
            val ptsAndFlags = dis.readLong()
            val size = dis.readInt()
            val data = ByteArray(size)
            dis.readFully(data)

            val isConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
            val isKeyFrame = (ptsAndFlags and PACKET_FLAG_KEY_FRAME) != 0L
            val pts = ptsAndFlags and (PACKET_FLAG_KEY_FRAME - 1)

            return StreamPacket(
                pts = pts,
                isConfig = isConfig,
                isKeyFrame = isKeyFrame,
                data = data
            )
        }
    }
}
