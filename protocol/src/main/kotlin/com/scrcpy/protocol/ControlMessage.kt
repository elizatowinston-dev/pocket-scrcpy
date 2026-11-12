package com.scrcpy.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

sealed class ControlMessage {
    abstract val type: Int
    abstract fun toByteArray(): ByteArray

    data class InjectKeycode(
        val action: Int,
        val keycode: Int,
        val repeat: Int = 0,
        val metaState: Int = 0
    ) : ControlMessage() {
        override val type: Int get() = TYPE_INJECT_KEYCODE

        override fun toByteArray(): ByteArray {
            val bos = ByteArrayOutputStream(14)
            val dos = DataOutputStream(bos)
            dos.writeByte(type)
            dos.writeByte(action)
            dos.writeInt(keycode)
            dos.writeInt(repeat)
            dos.writeInt(metaState)
            dos.flush()
            return bos.toByteArray()
        }
    }

    data class InjectText(val text: String) : ControlMessage() {
        override val type: Int get() = TYPE_INJECT_TEXT

        override fun toByteArray(): ByteArray {
            val textBytes = text.toByteArray(StandardCharsets.UTF_8)
            val bos = ByteArrayOutputStream(5 + textBytes.size)
            val dos = DataOutputStream(bos)
            dos.writeByte(type)
            dos.writeInt(textBytes.size)
            dos.write(textBytes)
            dos.flush()
            return bos.toByteArray()
        }
    }

    data class InjectTouchEvent(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val pressure: Float = 1.0f,
        val actionButton: Int = 0,
        val buttons: Int = 0
    ) : ControlMessage() {
        override val type: Int get() = TYPE_INJECT_TOUCH_EVENT

        override fun toByteArray(): ByteArray {
            val bos = ByteArrayOutputStream(32)
            val dos = DataOutputStream(bos)
            dos.writeByte(type)
            dos.writeByte(action)
            dos.writeLong(pointerId)
            dos.writeInt(x)
            dos.writeInt(y)
            dos.writeShort(screenWidth and 0xFFFF)
            dos.writeShort(screenHeight and 0xFFFF)
            val u16Pressure = if (pressure >= 1.0f) 0xFFFF else (pressure * 65536.0f).toInt()
            dos.writeShort(u16Pressure and 0xFFFF)
            dos.writeInt(actionButton)
            dos.writeInt(buttons)
            dos.flush()
            return bos.toByteArray()
        }
    }

    data class InjectScrollEvent(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val hScroll: Float,
        val vScroll: Float,
        val buttons: Int = 0
    ) : ControlMessage() {
        override val type: Int get() = TYPE_INJECT_SCROLL_EVENT

        override fun toByteArray(): ByteArray {
            val bos = ByteArrayOutputStream(21)
            val dos = DataOutputStream(bos)
            dos.writeByte(type)
            dos.writeInt(x)
            dos.writeInt(y)
            dos.writeShort(screenWidth and 0xFFFF)
            dos.writeShort(screenHeight and 0xFFFF)
            val hNorm = (hScroll / 16f).coerceIn(-1f, 1f)
            val vNorm = (vScroll / 16f).coerceIn(-1f, 1f)
            val hFp = if (hNorm >= 1.0f) 0x7FFF else (hNorm * 32768.0f).toInt()
            val vFp = if (vNorm >= 1.0f) 0x7FFF else (vNorm * 32768.0f).toInt()
            dos.writeShort(hFp and 0xFFFF)
            dos.writeShort(vFp and 0xFFFF)
            dos.writeInt(buttons)
            dos.flush()
            return bos.toByteArray()
        }
    }

    data class BackOrScreenOn(val action: Int) : ControlMessage() {
        override val type: Int get() = TYPE_BACK_OR_SCREEN_ON

        override fun toByteArray(): ByteArray = byteArrayOf(type.toByte(), action.toByte())
    }

    data class GetClipboard(val copyKey: Int = COPY_KEY_NONE) : ControlMessage() {
        override val type: Int get() = TYPE_GET_CLIPBOARD

        override fun toByteArray(): ByteArray = byteArrayOf(type.toByte(), copyKey.toByte())
    }

    data class SetClipboard(
        val sequence: Long,
        val paste: Boolean,
        val text: String
    ) : ControlMessage() {
        override val type: Int get() = TYPE_SET_CLIPBOARD

        override fun toByteArray(): ByteArray {
            val textBytes = text.toByteArray(StandardCharsets.UTF_8)
            val bos = ByteArrayOutputStream(14 + textBytes.size)
            val dos = DataOutputStream(bos)
            dos.writeByte(type)
            dos.writeLong(sequence)
            dos.writeByte(if (paste) 1 else 0)
            dos.writeInt(textBytes.size)
            dos.write(textBytes)
            dos.flush()
            return bos.toByteArray()
        }
    }

    data class SetDisplayPower(val on: Boolean) : ControlMessage() {
        override val type: Int get() = TYPE_SET_DISPLAY_POWER

        override fun toByteArray(): ByteArray = byteArrayOf(type.toByte(), if (on) 1 else 0)
    }

    data class Empty(override val type: Int) : ControlMessage() {
        override fun toByteArray(): ByteArray = byteArrayOf(type.toByte())
    }

    companion object {
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_EXPAND_SETTINGS_PANEL = 6
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_GET_CLIPBOARD = 8
        const val TYPE_SET_CLIPBOARD = 9
        const val TYPE_SET_DISPLAY_POWER = 10
        const val TYPE_ROTATE_DEVICE = 11
        const val TYPE_UHID_CREATE = 12
        const val TYPE_UHID_INPUT = 13
        const val TYPE_UHID_DESTROY = 14
        const val TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15
        const val TYPE_START_APP = 16
        const val TYPE_RESET_VIDEO = 17
        const val TYPE_CAMERA_SET_TORCH = 18
        const val TYPE_CAMERA_ZOOM_IN = 19
        const val TYPE_CAMERA_ZOOM_OUT = 20
        const val TYPE_RESIZE_DISPLAY = 21
        const val TYPE_SCAN_FILE = 22

        const val COPY_KEY_NONE = 0
        const val COPY_KEY_COPY = 1
        const val COPY_KEY_CUT = 2

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3

        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_APP_SWITCH = 187
        const val KEYCODE_POWER = 26
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25

        fun expandNotificationPanel() = Empty(TYPE_EXPAND_NOTIFICATION_PANEL)
        fun expandSettingsPanel() = Empty(TYPE_EXPAND_SETTINGS_PANEL)
        fun collapsePanels() = Empty(TYPE_COLLAPSE_PANELS)
        fun rotateDevice() = Empty(TYPE_ROTATE_DEVICE)
    }
}
