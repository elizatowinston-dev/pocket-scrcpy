package com.scrcpy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMapperTest {

    @Test
    fun testExactAspectMatch() {
        // View and video both 1080x2400
        val mapped = TouchMapper.mapCoordinates(
            touchX = 540f,
            touchY = 1200f,
            viewWidth = 1080f,
            viewHeight = 2400f,
            videoWidth = 1080,
            videoHeight = 2400
        )
        assertNotNull(mapped)
        assertEquals(540f, mapped!!.x, 0.01f)
        assertEquals(1200f, mapped.y, 0.01f)
    }

    @Test
    fun testLetterboxMapping() {
        // Video is 1080x1920 (9:16), view is 1080x2400 (taller)
        // Video renderedHeight = 1080 * 16 / 9 = 1920
        // offsetY = (2400 - 1920) / 2 = 240
        val mapped = TouchMapper.mapCoordinates(
            touchX = 540f,
            touchY = 1200f, // Center of view
            viewWidth = 1080f,
            viewHeight = 2400f,
            videoWidth = 1080,
            videoHeight = 1920
        )
        assertNotNull(mapped)
        assertEquals(540f, mapped!!.x, 0.01f)
        // 1200 in view - 240 offset = 960 -> 960/1920 * 1920 = 960
        assertEquals(960f, mapped.y, 0.01f)
    }

    @Test
    fun testPillarboxMapping() {
        // Video is 1080x2400 (tall), view is 2400x1080 (landscape)
        val mapped = TouchMapper.mapCoordinates(
            touchX = 1200f, // Center of view width
            touchY = 540f,  // Center of view height
            viewWidth = 2400f,
            viewHeight = 1080f,
            videoWidth = 1080,
            videoHeight = 2400
        )
        assertNotNull(mapped)
        // Center maps to video center: 540, 1200
        assertEquals(540f, mapped!!.x, 0.01f)
        assertEquals(1200f, mapped.y, 0.01f)
    }

    @Test
    fun testInvalidDimensions() {
        val mapped = TouchMapper.mapCoordinates(10f, 10f, 0f, 0f, 1080, 2400)
        assertNull(mapped)
    }

    @Test
    fun testActionMoveSendsAllActivePointers() {
        val pointers = listOf(
            TouchPointerInput(pointerId = 0L, x = 200f, y = 400f, pressure = 0.8f),
            TouchPointerInput(pointerId = 1L, x = 800f, y = 1600f, pressure = 0.6f),
            TouchPointerInput(pointerId = 2L, x = 540f, y = 1200f, pressure = 1.0f)
        )

        val messages = TouchMapper.buildTouchControlMessages(
            action = TouchEventAction.MOVE,
            actionIndex = 0, // MotionEvent.ACTION_MOVE always has actionIndex = 0
            pointers = pointers,
            viewWidth = 1080f,
            viewHeight = 2400f,
            videoWidth = 1080,
            videoHeight = 2400
        )

        assertEquals(3, messages.size)

        assertEquals(ControlMessage.ACTION_MOVE, messages[0].action)
        assertEquals(0L, messages[0].pointerId)
        assertEquals(200, messages[0].x)
        assertEquals(400, messages[0].y)
        assertEquals(0.8f, messages[0].pressure, 0.001f)

        assertEquals(ControlMessage.ACTION_MOVE, messages[1].action)
        assertEquals(1L, messages[1].pointerId)
        assertEquals(800, messages[1].x)
        assertEquals(1600, messages[1].y)
        assertEquals(0.6f, messages[1].pressure, 0.001f)

        assertEquals(ControlMessage.ACTION_MOVE, messages[2].action)
        assertEquals(2L, messages[2].pointerId)
        assertEquals(540, messages[2].x)
        assertEquals(1200, messages[2].y)
        assertEquals(1.0f, messages[2].pressure, 0.001f)
    }

    @Test
    fun testPointerDownAndPointerUpDispatchActionIndexOnly() {
        val pointers = listOf(
            TouchPointerInput(pointerId = 10L, x = 100f, y = 200f, pressure = 0.9f),
            TouchPointerInput(pointerId = 11L, x = 900f, y = 1800f, pressure = 0.7f)
        )

        val downMessages = TouchMapper.buildTouchControlMessages(
            action = TouchEventAction.POINTER_DOWN,
            actionIndex = 1,
            pointers = pointers,
            viewWidth = 1080f,
            viewHeight = 2520f,
            videoWidth = 1080,
            videoHeight = 2520
        )
        assertEquals(1, downMessages.size)
        assertEquals(ControlMessage.ACTION_DOWN, downMessages[0].action)
        assertEquals(11L, downMessages[0].pointerId)
        assertEquals(900, downMessages[0].x)
        assertEquals(1800, downMessages[0].y)
        assertEquals(0.7f, downMessages[0].pressure, 0.001f)

        val upMessages = TouchMapper.buildTouchControlMessages(
            action = TouchEventAction.POINTER_UP,
            actionIndex = 0,
            pointers = pointers,
            viewWidth = 1080f,
            viewHeight = 2520f,
            videoWidth = 1080,
            videoHeight = 2520
        )
        assertEquals(1, upMessages.size)
        assertEquals(ControlMessage.ACTION_UP, upMessages[0].action)
        assertEquals(10L, upMessages[0].pointerId)
        assertEquals(0f, upMessages[0].pressure, 0.001f)
    }

    @Test
    fun testActionCancelCancelsAndReleasesAllActivePointers() {
        val pointers = listOf(
            TouchPointerInput(pointerId = 0L, x = 300f, y = 600f, pressure = 0.5f),
            TouchPointerInput(pointerId = 1L, x = 700f, y = 1400f, pressure = 0.5f)
        )

        val messages = TouchMapper.buildTouchControlMessages(
            action = TouchEventAction.CANCEL,
            actionIndex = 0,
            pointers = pointers,
            viewWidth = 1080f,
            viewHeight = 2520f,
            videoWidth = 1080,
            videoHeight = 2520
        )

        assertEquals(3, messages.size)
        assertEquals(ControlMessage.ACTION_CANCEL, messages[0].action)
        assertEquals(0L, messages[0].pointerId)
        assertEquals(0f, messages[0].pressure, 0.001f)

        assertEquals(ControlMessage.ACTION_UP, messages[1].action)
        assertEquals(0L, messages[1].pointerId)
        assertEquals(0f, messages[1].pressure, 0.001f)

        assertEquals(ControlMessage.ACTION_UP, messages[2].action)
        assertEquals(1L, messages[2].pointerId)
        assertEquals(0f, messages[2].pressure, 0.001f)
    }

    @Test
    fun testAndroidMotionEventActionMappingAndIgnoredActions() {
        assertEquals(TouchEventAction.DOWN, TouchEventAction.fromAndroidActionMasked(0))
        assertEquals(TouchEventAction.UP, TouchEventAction.fromAndroidActionMasked(1))
        assertEquals(TouchEventAction.MOVE, TouchEventAction.fromAndroidActionMasked(2))
        assertEquals(TouchEventAction.CANCEL, TouchEventAction.fromAndroidActionMasked(3))
        assertEquals(TouchEventAction.OTHER, TouchEventAction.fromAndroidActionMasked(4)) // ACTION_OUTSIDE
        assertEquals(TouchEventAction.POINTER_DOWN, TouchEventAction.fromAndroidActionMasked(5))
        assertEquals(TouchEventAction.POINTER_UP, TouchEventAction.fromAndroidActionMasked(6))
        assertEquals(TouchEventAction.OTHER, TouchEventAction.fromAndroidActionMasked(7)) // ACTION_HOVER_MOVE

        val ignored = TouchMapper.buildTouchControlMessages(
            action = TouchEventAction.OTHER,
            actionIndex = 0,
            pointers = listOf(TouchPointerInput(0L, 100f, 100f, 1f)),
            viewWidth = 1080f,
            viewHeight = 2520f,
            videoWidth = 1080,
            videoHeight = 2520
        )
        assertTrue(ignored.isEmpty())
    }
}
