package com.scrcpy.protocol

data class TouchPoint(val x: Float, val y: Float)

object TouchMapper {
    /**
     * Maps display/touch coordinates to the target video coordinates.
     * Takes into account view dimensions, video aspect ratio letterboxing/pillarboxing,
     * and insets (cutouts / navigation bars).
     */
    fun mapCoordinates(
        touchX: Float,
        touchY: Float,
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int
    ): TouchPoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return null
        }

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect = viewWidth / viewHeight

        val renderedWidth: Float
        val renderedHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (viewAspect > videoAspect) {
            // Pillarboxed (bars on left and right)
            renderedHeight = viewHeight
            renderedWidth = viewHeight * videoAspect
            offsetX = (viewWidth - renderedWidth) / 2f
            offsetY = 0f
        } else {
            // Letterboxed (bars on top and bottom)
            renderedWidth = viewWidth
            renderedHeight = viewWidth / videoAspect
            offsetX = 0f
            offsetY = (viewHeight - renderedHeight) / 2f
        }

        // Clamp to rendered content area
        val relX = (touchX - offsetX).coerceIn(0f, renderedWidth)
        val relY = (touchY - offsetY).coerceIn(0f, renderedHeight)

        val targetX = (relX / renderedWidth) * videoWidth
        val targetY = (relY / renderedHeight) * videoHeight

        return TouchPoint(
            x = targetX.coerceIn(0f, videoWidth.toFloat()),
            y = targetY.coerceIn(0f, videoHeight.toFloat())
        )
    }
}
