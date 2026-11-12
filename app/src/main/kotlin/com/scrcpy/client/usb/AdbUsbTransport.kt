package com.scrcpy.client.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal enum class BulkFailureAction {
    WAIT_TIMEOUT,
    EOF_CLOSED,
    ERROR_DISCONNECTED,
    ERROR_IO
}

internal object UsbBulkTransferHelper {
    const val BULK_READ_TIMEOUT_MS = 1000
    const val BULK_WRITE_TIMEOUT_MS = 5000

    /**
     * Classifies a negative return value from UsbDeviceConnection.bulkTransfer().
     * On Android/libusbhost, an idle read timeout waits ~timeoutMs before returning -1,
     * whereas genuine I/O errors (ENODEV, EPIPE, EIO, EOVERFLOW) return almost immediately.
     */
    fun classifyNegativeTransfer(
        elapsedMs: Long,
        timeoutMs: Int,
        isClosed: Boolean,
        isDeviceAttached: Boolean
    ): BulkFailureAction {
        if (isClosed) {
            return BulkFailureAction.EOF_CLOSED
        }
        if (!isDeviceAttached) {
            return BulkFailureAction.ERROR_DISCONNECTED
        }
        val minTimeoutThresholdMs = (timeoutMs * 3L) / 4L
        return if (elapsedMs >= minTimeoutThresholdMs) {
            BulkFailureAction.WAIT_TIMEOUT
        } else {
            BulkFailureAction.ERROR_IO
        }
    }
}

/**
 * Implements ADB transport over Android USB Host Mode (Pixel as host, Xperia as device).
 */
class AdbUsbTransport(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection
) : AutoCloseable {

    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null
    private val closed = AtomicBoolean(false)

    private val readBuffer: ByteArray
    private var readBufferOffset = 0
    private var readBufferLength = 0

    init {
        var foundAdbInterface = false
        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (isAdbInterface(intf)) {
                foundAdbInterface = true
                var candidateIn: UsbEndpoint? = null
                var candidateOut: UsbEndpoint? = null
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            candidateIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            candidateOut = ep
                        }
                    }
                }
                if (candidateIn == null || candidateOut == null) {
                    throw IOException("ADB USB interface found, but bulk IN/OUT endpoints are missing")
                }
                if (!connection.claimInterface(intf, true)) {
                    throw IOException("Failed to claim USB ADB interface. Close conflicting USB apps or reconnect the cable.")
                }
                usbInterface = intf
                inEndpoint = candidateIn
                outEndpoint = candidateOut
                break
            }
        }

        if (!foundAdbInterface) {
            throw IOException("No ADB interface (class=255, subclass=66, protocol=1) found on USB device")
        }

        val packetSize = inEndpoint?.maxPacketSize?.takeIf { it > 0 } ?: 512
        readBuffer = ByteArray(maxOf(packetSize, 16 * 1024))
    }

    private fun isDeviceAttached(): Boolean {
        return try {
            usbManager.deviceList.values.any { it.deviceId == usbDevice.deviceId }
        } catch (_: Exception) {
            false
        }
    }

    val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val res = read(b, 0, 1)
            return if (res < 0) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException("Invalid offset/length for buffer of size ${b.size}")
            }
            if (len == 0) return 0
            if (closed.get()) return -1

            if (readBufferOffset < readBufferLength) {
                val available = readBufferLength - readBufferOffset
                val toCopy = minOf(len, available)
                System.arraycopy(readBuffer, readBufferOffset, b, off, toCopy)
                readBufferOffset += toCopy
                return toCopy
            }

            val ep = inEndpoint ?: return -1

            while (!closed.get()) {
                val startNs = System.nanoTime()
                val transferred = connection.bulkTransfer(
                    ep,
                    readBuffer,
                    readBuffer.size,
                    UsbBulkTransferHelper.BULK_READ_TIMEOUT_MS
                )
                if (transferred > 0) {
                    val toCopy = minOf(len, transferred)
                    System.arraycopy(readBuffer, 0, b, off, toCopy)
                    readBufferOffset = toCopy
                    readBufferLength = transferred
                    return toCopy
                }

                if (transferred == 0) {
                    // Zero-length packet (ZLP) or empty read while idle; verify attachment and keep waiting
                    if (closed.get()) return -1
                    if (!isDeviceAttached()) {
                        throw IOException("USB device disconnected")
                    }
                    continue
                }

                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                when (
                    UsbBulkTransferHelper.classifyNegativeTransfer(
                        elapsedMs = elapsedMs,
                        timeoutMs = UsbBulkTransferHelper.BULK_READ_TIMEOUT_MS,
                        isClosed = closed.get(),
                        isDeviceAttached = isDeviceAttached()
                    )
                ) {
                    BulkFailureAction.WAIT_TIMEOUT -> continue
                    BulkFailureAction.EOF_CLOSED -> return -1
                    BulkFailureAction.ERROR_DISCONNECTED -> throw IOException("USB device disconnected")
                    BulkFailureAction.ERROR_IO -> throw IOException(
                        "USB bulk read failed (code=$transferred after ${elapsedMs}ms)"
                    )
                }
            }

            return -1
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException("Invalid offset/length for buffer of size ${b.size}")
            }
            if (len == 0) return
            if (closed.get()) throw IOException("USB transport is closed")

            val ep = outEndpoint ?: throw IOException("USB OUT endpoint unavailable")
            var sent = 0
            while (sent < len) {
                if (closed.get()) throw IOException("USB transport is closed")
                val remaining = len - sent
                val chunkSize = minOf(remaining, 16 * 1024)
                val chunk = if (off + sent == 0 && chunkSize == b.size) {
                    b
                } else {
                    b.copyOfRange(off + sent, off + sent + chunkSize)
                }

                val transferred = connection.bulkTransfer(
                    ep,
                    chunk,
                    chunk.size,
                    UsbBulkTransferHelper.BULK_WRITE_TIMEOUT_MS
                )
                if (transferred < 0) {
                    if (!isDeviceAttached()) {
                        throw IOException("USB device disconnected during write")
                    }
                    throw IOException("USB bulk write failed (code=$transferred)")
                }
                if (transferred == 0) {
                    throw IOException("USB bulk write stalled (0 bytes transferred)")
                }
                sent += transferred
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            usbInterface?.let {
                try {
                    connection.releaseInterface(it)
                } catch (_: Exception) {}
            }
            try {
                connection.close()
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val ADB_CLASS = 255
        const val ADB_SUBCLASS = 66
        const val ADB_PROTOCOL = 1

        fun isAdbInterface(intf: UsbInterface): Boolean {
            return intf.interfaceClass == ADB_CLASS &&
                intf.interfaceSubclass == ADB_SUBCLASS &&
                intf.interfaceProtocol == ADB_PROTOCOL
        }
    }
}
