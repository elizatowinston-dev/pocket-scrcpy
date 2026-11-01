package com.scrcpy.client.usb

import android.hardware.usb.*
import java.io.InputStream
import java.io.OutputStream

/**
 * Implements ADB transport over Android USB Host Mode (Pixel as host, Xperia as accessory/device).
 */
class AdbUsbTransport(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection
) : AutoCloseable {

    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null

    init {
        // Find ADB interface (class 255/vendor specific, subclass 66, protocol 1)
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                usbInterface = intf
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        inEndpoint = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = ep
                    }
                }
                connection.claimInterface(intf, true)
                break
            }
        }
    }

    val inputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val res = read(b, 0, 1)
            return if (res < 0) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val ep = inEndpoint ?: return -1
            val buffer = if (off == 0) b else ByteArray(len)
            val transferred = connection.bulkTransfer(ep, buffer, len, 5000)
            if (transferred > 0 && off != 0) {
                System.arraycopy(buffer, 0, b, off, transferred)
            }
            return transferred
        }
    }

    val outputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val ep = outEndpoint ?: throw IllegalStateException("USB Out Endpoint null")
            val buffer = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            val transferred = connection.bulkTransfer(ep, buffer, len, 5000)
            if (transferred < 0) {
                throw IllegalStateException("USB bulkTransfer failed")
            }
        }
    }

    override fun close() {
        usbInterface?.let { connection.releaseInterface(it) }
        connection.close()
    }
}
