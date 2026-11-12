package com.scrcpy.protocol

data class TouchPoint(val x: Float, val y: Float)

data class TouchPointerInput(
    val pointerId: Long,
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f
)

enum class TouchEventAction {
    DOWN,
    POINTER_DOWN,
    UP,
    POINTER_UP,
    MOVE,
    CANCEL,
    OTHER;

    companion object {
        // Matches android.view.MotionEvent actionMasked constants without Android SDK dependency
        private const val ANDROID_ACTION_DOWN = 0
        private const val ANDROID_ACTION_UP = 1
        private const val ANDROID_ACTION_MOVE = 2
        private const val ANDROID_ACTION_CANCEL = 3
        private const val ANDROID_ACTION_POINTER_DOWN = 5
        private const val ANDROID_ACTION_POINTER_UP = 6

        fun fromAndroidActionMasked(actionMasked: Int): TouchEventAction = when (actionMasked) {
            ANDROID_ACTION_DOWN -> DOWN
            ANDROID_ACTION_UP -> UP
            ANDROID_ACTION_MOVE -> MOVE
            ANDROID_ACTION_CANCEL -> CANCEL
            ANDROID_ACTION_POINTER_DOWN -> POINTER_DOWN
            ANDROID_ACTION_POINTER_UP -> POINTER_UP
            else -> OTHER
        }
    }
}

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

    /**
     * Converts an Android MotionEvent snapshot into scrcpy InjectTouchEvent messages.
     * - ACTION_MOVE sends all active pointers so multi-touch gestures (pinch/scroll/drag) update every finger.
     * - ACTION_CANCEL sends ACTION_CANCEL followed by ACTION_UP (pressure=0) for each active pointer
     *   so upstream Controller/PointersState clears all pointers cleanly.
     */
    fun buildTouchControlMessages(
        action: TouchEventAction,
        actionIndex: Int,
        pointers: List<TouchPointerInput>,
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int
    ): List<ControlMessage.InjectTouchEvent> {
        if (pointers.isEmpty() || viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
            return emptyList()
        }

        return when (action) {
            TouchEventAction.DOWN, TouchEventAction.POINTER_DOWN -> {
                val pointer = pointers.getOrNull(actionIndex) ?: return emptyList()
                val mapped = mapCoordinates(pointer.x, pointer.y, viewWidth, viewHeight, videoWidth, videoHeight)
                    ?: return emptyList()
                listOf(
                    ControlMessage.InjectTouchEvent(
                        action = ControlMessage.ACTION_DOWN,
                        pointerId = pointer.pointerId,
                        x = mapped.x.toInt(),
                        y = mapped.y.toInt(),
                        screenWidth = videoWidth,
                        screenHeight = videoHeight,
                        pressure = pointer.pressure
                    )
                )
            }
            TouchEventAction.UP, TouchEventAction.POINTER_UP -> {
                val pointer = pointers.getOrNull(actionIndex) ?: return emptyList()
                val mapped = mapCoordinates(pointer.x, pointer.y, viewWidth, viewHeight, videoWidth, videoHeight)
                    ?: return emptyList()
                listOf(
                    ControlMessage.InjectTouchEvent(
                        action = ControlMessage.ACTION_UP,
                        pointerId = pointer.pointerId,
                        x = mapped.x.toInt(),
                        y = mapped.y.toInt(),
                        screenWidth = videoWidth,
                        screenHeight = videoHeight,
                        pressure = 0f
                    )
                )
            }
            TouchEventAction.MOVE -> {
                pointers.mapNotNull { pointer ->
                    val mapped = mapCoordinates(pointer.x, pointer.y, viewWidth, viewHeight, videoWidth, videoHeight)
                        ?: return@mapNotNull null
                    ControlMessage.InjectTouchEvent(
                        action = ControlMessage.ACTION_MOVE,
                        pointerId = pointer.pointerId,
                        x = mapped.x.toInt(),
                        y = mapped.y.toInt(),
                        screenWidth = videoWidth,
                        screenHeight = videoHeight,
                        pressure = pointer.pressure
                    )
                }
            }
            TouchEventAction.CANCEL -> {
                val mappedPointers = pointers.mapNotNull { pointer ->
                    val mapped = mapCoordinates(pointer.x, pointer.y, viewWidth, viewHeight, videoWidth, videoHeight)
                        ?: return@mapNotNull null
                    pointer to mapped
                }
                if (mappedPointers.isEmpty()) return emptyList()

                val messages = ArrayList<ControlMessage.InjectTouchEvent>(mappedPointers.size + 1)
                val (primaryPointer, primaryMapped) = mappedPointers.first()
                messages.add(
                    ControlMessage.InjectTouchEvent(
                        action = ControlMessage.ACTION_CANCEL,
                        pointerId = primaryPointer.pointerId,
                        x = primaryMapped.x.toInt(),
                        y = primaryMapped.y.toInt(),
                        screenWidth = videoWidth,
                        screenHeight = videoHeight,
                        pressure = 0f
                    )
                )
                for ((pointer, mapped) in mappedPointers) {
                    messages.add(
                        ControlMessage.InjectTouchEvent(
                            action = ControlMessage.ACTION_UP,
                            pointerId = pointer.pointerId,
                            x = mapped.x.toInt(),
                            y = mapped.y.toInt(),
                            screenWidth = videoWidth,
                            screenHeight = videoHeight,
                            pressure = 0f
                        )
                    )
                }
                messages
            }
            TouchEventAction.OTHER -> emptyList()
        }
    }
}
