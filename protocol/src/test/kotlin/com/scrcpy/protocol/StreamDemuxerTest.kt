package com.scrcpy.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class StreamDemuxerTest {

    @Test
    fun testDeviceMetaParsing() {
        val bos = ByteArrayOutputStream()
        val deviceName = "Sony Xperia 5 IV"
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        bos.write(nameBytes)
        bos.write(ByteArray(64 - nameBytes.size)) // zero pad to 64 bytes

        val parsedName = StreamPacket.readDeviceMeta(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(deviceName, parsedName)
    }

    @Test
    fun testCodecIdParsing() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        // H264 fourcc = 0x68323634
        dos.writeInt(0x68323634)
        dos.flush()

        val codecId = StreamPacket.readCodecId(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(0x68323634, codecId)
    }

    @Test
    fun testConfigAndKeyframePackets() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // Config packet (SPS/PPS)
        val spsPps = byteArrayOf(0, 0, 0, 1, 0x67.toByte(), 0x42.toByte(), 0, 0x1f.toByte())
        dos.writeLong(StreamPacket.PACKET_FLAG_CONFIG)
        dos.writeInt(spsPps.size)
        dos.write(spsPps)

        // I-frame packet
        val iframeData = byteArrayOf(0, 0, 0, 1, 0x65.toByte(), 0x88.toByte(), 0x84.toByte())
        val pts = 33333L
        dos.writeLong(pts or StreamPacket.PACKET_FLAG_KEY_FRAME)
        dos.writeInt(iframeData.size)
        dos.write(iframeData)
        dos.flush()

        val input = ByteArrayInputStream(bos.toByteArray())

        val p1 = StreamPacket.readPacket(input)
        assertTrue(p1.isConfig)
        assertFalse(p1.isKeyFrame)
        assertArrayEquals(spsPps, p1.data)

        val p2 = StreamPacket.readPacket(input)
        assertFalse(p2.isConfig)
        assertTrue(p2.isKeyFrame)
        assertEquals(pts, p2.pts)
        assertArrayEquals(iframeData, p2.data)
    }
}
