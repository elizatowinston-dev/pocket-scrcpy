package com.scrcpy.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class StreamDemuxerTest {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

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
    fun testUpstreamVideoCodecIds() {
        val cases = listOf(
            "68323634" to StreamPacket.CODEC_H264,
            "68323635" to StreamPacket.CODEC_H265,
            "00617631" to StreamPacket.CODEC_AV1,
            "00767038" to StreamPacket.CODEC_VP8,
            "00767039" to StreamPacket.CODEC_VP9
        )
        for ((hex, expectedId) in cases) {
            val codecId = StreamPacket.readCodecId(ByteArrayInputStream(hexToBytes(hex)))
            assertEquals(expectedId, codecId)
        }
    }

    @Test
    fun testUpstreamSessionPacketFixture12BytesNoPayload() {
        // Upstream Streamer.writeSessionMeta(1080, 2520, false):
        // flags=0x80000000, width=1080 (0x00000438), height=2520 (0x000009d8)
        val initialSessionHex = "80000000 00000438 000009d8"
        // Followed immediately by a 12-byte client-resized session packet:
        // Streamer.writeSessionMeta(2520, 1080, true):
        // flags=0x80000001, width=2520 (0x000009d8), height=1080 (0x00000438)
        val resizedSessionHex = "80000001 000009d8 00000438"

        val input = ByteArrayInputStream(hexToBytes(initialSessionHex + resizedSessionHex))

        val first = StreamPacket.readPacket(input) as StreamPacket.Session
        assertEquals(1080, first.width)
        assertEquals(2520, first.height)
        assertFalse(first.clientResized)

        val second = StreamPacket.readPacket(input) as StreamPacket.Session
        assertEquals(2520, second.width)
        assertEquals(1080, second.height)
        assertTrue(second.clientResized)
        assertEquals(0, input.available())
    }

    @Test
    fun testUpstreamFullVideoHandshakeAndFramesFixture() {
        val bos = ByteArrayOutputStream()
        // 1. Dummy byte (send_dummy_byte=true)
        bos.write(0x00)
        // 2. 64-byte device meta (send_device_meta=true)
        val name = "XQ-CQ54".toByteArray(Charsets.UTF_8)
        bos.write(name)
        bos.write(ByteArray(64 - name.size))
        // 3. 4-byte codec id (send_stream_meta=true): "h264"
        bos.write(hexToBytes("68323634"))
        // 4. Initial 12-byte session packet: 1080x2520, clientResized=false
        bos.write(hexToBytes("80000000 00000438 000009d8"))
        // 5. Config packet (SPS/PPS): ptsAndFlags = 1L << 62 (0x4000000000000000), size = 8
        bos.write(hexToBytes("40000000 00000000 00000008"))
        val spsPps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f)
        bos.write(spsPps)
        // 6. Keyframe packet: pts=33333 (0x8235) | (1L << 61) -> 0x2000000000008235, size = 7
        bos.write(hexToBytes("20000000 00008235 00000007"))
        val iframe = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        bos.write(iframe)
        // 7. Mid-stream rotation session packet: 2520x1080, clientResized=false
        bos.write(hexToBytes("80000000 000009d8 00000438"))
        // 8. Regular P-frame: pts=66666 (0x1046a), flags=0 -> 0x000000000001046a, size = 5
        bos.write(hexToBytes("00000000 0001046a 00000005"))
        val pframe = byteArrayOf(0, 0, 0, 1, 0x41)
        bos.write(pframe)

        val input = ByteArrayInputStream(bos.toByteArray())

        assertEquals(0, StreamPacket.readDummyByte(input))
        assertEquals("XQ-CQ54", StreamPacket.readDeviceMeta(input))
        assertEquals(StreamPacket.CODEC_H264, StreamPacket.readCodecId(input))

        val initialSession = StreamPacket.readPacket(input) as StreamPacket.Session
        assertEquals(1080, initialSession.width)
        assertEquals(2520, initialSession.height)
        assertFalse(initialSession.clientResized)

        val pConfig = StreamPacket.readPacket(input) as StreamPacket.Media
        assertTrue(pConfig.isConfig)
        assertFalse(pConfig.isKeyFrame)
        assertEquals(0L, pConfig.pts)
        assertArrayEquals(spsPps, pConfig.data)

        val pKey = StreamPacket.readPacket(input) as StreamPacket.Media
        assertFalse(pKey.isConfig)
        assertTrue(pKey.isKeyFrame)
        assertEquals(33333L, pKey.pts)
        assertArrayEquals(iframe, pKey.data)

        val rotatedSession = StreamPacket.readPacket(input) as StreamPacket.Session
        assertEquals(2520, rotatedSession.width)
        assertEquals(1080, rotatedSession.height)
        assertFalse(rotatedSession.clientResized)

        val pDelta = StreamPacket.readPacket(input) as StreamPacket.Media
        assertFalse(pDelta.isConfig)
        assertFalse(pDelta.isKeyFrame)
        assertEquals(66666L, pDelta.pts)
        assertArrayEquals(pframe, pDelta.data)

        assertEquals(0, input.available())
    }

    @Test(expected = IOException::class)
    fun testZeroLengthMediaPacketThrows() {
        val bytes = hexToBytes("00000000 00000001 00000000")
        StreamPacket.readPacket(ByteArrayInputStream(bytes))
    }

    @Test(expected = IOException::class)
    fun testZeroDimensionSessionPacketThrows() {
        val bytes = hexToBytes("80000000 00000000 00000438")
        StreamPacket.readPacket(ByteArrayInputStream(bytes))
    }
}
