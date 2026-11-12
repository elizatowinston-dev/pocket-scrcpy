package com.scrcpy.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

sealed class StreamPacket {

    data class Session(
        val width: Int,
        val height: Int,
        val clientResized: Boolean = false
    ) : StreamPacket()

    data class Media(
        val pts: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val data: ByteArray
    ) : StreamPacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Media) return false
            return pts == other.pts &&
                isConfig == other.isConfig &&
                isKeyFrame == other.isKeyFrame &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = pts.hashCode()
            result = 31 * result + isConfig.hashCode()
            result = 31 * result + isKeyFrame.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    companion object {
        const val HEADER_SIZE = 12
        const val DEVICE_NAME_FIELD_LENGTH = 64
        const val MAX_PACKET_SIZE = 64 * 1024 * 1024

        const val PACKET_FLAG_SESSION = 1L shl 63
        const val PACKET_FLAG_CONFIG = 1L shl 62
        const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        const val PACKET_PTS_MASK = PACKET_FLAG_KEY_FRAME - 1

        // Upstream VideoCodec FourCC values (scrcpy v5.0.1)
        const val CODEC_H264 = 0x68323634 // "h264"
        const val CODEC_H265 = 0x68323635 // "h265"
        const val CODEC_AV1 = 0x00617631  // "\0av1"
        const val CODEC_VP8 = 0x00767038  // "\0vp8"
        const val CODEC_VP9 = 0x00767039  // "\0vp9"

        const val STREAM_DISABLED_EXPLICIT = 0
        const val STREAM_DISABLED_CONFIG_ERROR = 1

        fun readDummyByte(inputStream: InputStream): Int {
            val value = inputStream.read()
            if (value < 0) {
                throw EOFException("Stream closed while reading dummy byte")
            }
            return value
        }

        fun readDeviceMeta(inputStream: InputStream): String {
            val dis = DataInputStream(inputStream)
            val bytes = ByteArray(DEVICE_NAME_FIELD_LENGTH)
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

        /**
         * Reads a single scrcpy v5.0.1 packet from a video or audio stream.
         *
         * Per upstream demuxer.c / Streamer.java:
         * - If bit 7 of byte 0 is 1 (MSB=1), the packet is a 12-byte video Session packet
         *   with no payload: [flags:4][width:4][height:4].
         * - Otherwise (MSB=0), the packet is a Media packet:
         *   [pts_and_flags:8][size:4][payload:size].
         */
        fun readPacket(inputStream: InputStream): StreamPacket {
            val dis = DataInputStream(inputStream)
            val header = ByteArray(HEADER_SIZE)
            dis.readFully(header)

            val isSession = (header[0].toInt() and 0x80) != 0
            val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)

            if (isSession) {
                val flags = buf.getInt()
                val width = buf.getInt()
                val height = buf.getInt()
                if (width <= 0 || height <= 0) {
                    throw IOException("Invalid session video dimensions: ${width}x${height}")
                }
                val clientResized = (flags and 1) != 0
                return Session(
                    width = width,
                    height = height,
                    clientResized = clientResized
                )
            }

            val ptsAndFlags = buf.getLong()
            val size = buf.getInt()
            if (size <= 0 || size > MAX_PACKET_SIZE) {
                throw IOException("Invalid packet size: $size")
            }

            val data = ByteArray(size)
            dis.readFully(data)

            val isConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
            val isKeyFrame = (ptsAndFlags and PACKET_FLAG_KEY_FRAME) != 0L
            val pts = if (isConfig) 0L else (ptsAndFlags and PACKET_PTS_MASK)

            return Media(
                pts = pts,
                isConfig = isConfig,
                isKeyFrame = isKeyFrame,
                data = data
            )
        }
    }
}
