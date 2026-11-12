package com.scrcpy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AudioStreamHeaderTest {

    @Test
    fun testRawCodecHeader() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(AudioStreamHeader.CODEC_RAW)
        dos.flush()

        val header = AudioStreamHeader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(AudioStreamHeader.CODEC_RAW, header.codecId)
        assertFalse(header.isDisabled)
        assertFalse(header.isConfigError)
    }

    @Test
    fun testAudioStreamDisabledExplicit() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(0) // code 0 = audio unavailable on device, continue video-only
        dos.flush()

        val header = AudioStreamHeader.read(ByteArrayInputStream(bos.toByteArray()))
        assertTrue(header.isDisabled)
        assertFalse(header.isConfigError)
    }

    @Test
    fun testAudioStreamConfigError() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeInt(1) // code 1 = configuration error
        dos.flush()

        val header = AudioStreamHeader.read(ByteArrayInputStream(bos.toByteArray()))
        assertTrue(header.isDisabled)
        assertTrue(header.isConfigError)
    }
}
