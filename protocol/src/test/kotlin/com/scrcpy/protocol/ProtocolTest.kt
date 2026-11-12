package com.scrcpy.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ProtocolTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun testInjectKeycode() {
        val msg = ControlMessage.InjectKeycode(
            action = 0,
            keycode = 66,
            repeat = 0,
            metaState = 0
        )
        val expectedHex = "0000000000420000000000000000"
        assertEquals(expectedHex, msg.toByteArray().toHex())
    }

    @Test
    fun testInjectText() {
        val msg = ControlMessage.InjectText("hello")
        val expectedHex = "010000000568656c6c6f"
        assertEquals(expectedHex, msg.toByteArray().toHex())
    }

    @Test
    fun testInjectTouchEvent() {
        val msg = ControlMessage.InjectTouchEvent(
            action = 0,
            pointerId = 0L,
            x = 100,
            y = 200,
            screenWidth = 1080,
            screenHeight = 2400,
            pressure = 1.0f,
            actionButton = 0,
            buttons = 0
        )
        val expectedHex = "0200000000000000000000000064000000c804380960ffff0000000000000000"
        assertEquals(expectedHex, msg.toByteArray().toHex())
    }

    @Test
    fun testBackOrScreenOn() {
        val msg = ControlMessage.BackOrScreenOn(0)
        val expectedHex = "0400"
        assertEquals(expectedHex, msg.toByteArray().toHex())
    }

    @Test
    fun testSetClipboard() {
        val msg = ControlMessage.SetClipboard(
            sequence = 42L,
            paste = true,
            text = "copied text"
        )
        val expectedHex = "09000000000000002a010000000b636f706965642074657874"
        assertEquals(expectedHex, msg.toByteArray().toHex())
    }

    @Test
    fun testSetDisplayPower() {
        val msg = ControlMessage.SetDisplayPower(false)
        val expectedHex = "0a00"
        assertEquals(expectedHex, msg.toByteArray().toHex())
    }

    @Test
    fun testDeviceMessageClipboard() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeByte(DeviceMessage.TYPE_CLIPBOARD)
        val text = "test-clipboard"
        val bytes = text.toByteArray()
        dos.writeInt(bytes.size)
        dos.write(bytes)
        dos.flush()

        val parsed = DeviceMessage.parse(ByteArrayInputStream(bos.toByteArray()))
        assertTrue(parsed is DeviceMessage.Clipboard)
        assertEquals(text, (parsed as DeviceMessage.Clipboard).text)
    }

    @Test
    fun testDeviceMessageAckClipboard() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeByte(DeviceMessage.TYPE_ACK_CLIPBOARD)
        dos.writeLong(123456789L)
        dos.flush()

        val parsed = DeviceMessage.parse(ByteArrayInputStream(bos.toByteArray()))
        assertTrue(parsed is DeviceMessage.AckClipboard)
        assertEquals(123456789L, (parsed as DeviceMessage.AckClipboard).sequence)
    }

    @Test
    fun testStreamPacketParsing() {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        val pts = 500000L
        val ptsAndFlags = pts or StreamPacket.PACKET_FLAG_KEY_FRAME
        val testData = byteArrayOf(1, 2, 3, 4, 5)
        dos.writeLong(ptsAndFlags)
        dos.writeInt(testData.size)
        dos.write(testData)
        dos.flush()

        val packet = StreamPacket.readPacket(ByteArrayInputStream(bos.toByteArray()))
        assertTrue(packet is StreamPacket.Media)
        val media = packet as StreamPacket.Media
        assertEquals(pts, media.pts)
        assertTrue(media.isKeyFrame)
        assertEquals(false, media.isConfig)
        assertArrayEquals(testData, media.data)
    }
}
