package com.scrcpy.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
