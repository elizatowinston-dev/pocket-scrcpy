package com.scrcpy.protocol

import java.io.DataInputStream
import java.io.InputStream

data class AudioStreamHeader(
    val codecId: Int,
    val isDisabled: Boolean,
    val isConfigError: Boolean
) {
    companion object {
        const val CODEC_RAW = 0x00726177 // "raw\0"
        const val CODEC_OPUS = 0x6f707573 // "opus"
        const val CODEC_AAC = 0x00616163 // "aac\0"
        const val CODEC_FLAC = 0x666c6163 // "flac"

        const val DISABLED_EXPLICIT = 0
        const val DISABLED_CONFIG_ERROR = 1

        fun read(inputStream: InputStream): AudioStreamHeader {
            val dis = DataInputStream(inputStream)
            val id = dis.readInt()
            return when (id) {
                DISABLED_EXPLICIT -> AudioStreamHeader(id, isDisabled = true, isConfigError = false)
                DISABLED_CONFIG_ERROR -> AudioStreamHeader(id, isDisabled = true, isConfigError = true)
                else -> AudioStreamHeader(id, isDisabled = false, isConfigError = false)
            }
        }
    }
}
