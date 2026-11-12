package com.scrcpy.client.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbBulkTransferHelperTest {

    @Test
    fun testTimeoutIsDistinguishedFromRealIoError() {
        assertEquals(
            BulkFailureAction.WAIT_TIMEOUT,
            UsbBulkTransferHelper.classifyNegativeTransfer(
                elapsedMs = 1000L,
                timeoutMs = 1000,
                isClosed = false,
                isDeviceAttached = true
            )
        )
        assertEquals(
            BulkFailureAction.ERROR_IO,
            UsbBulkTransferHelper.classifyNegativeTransfer(
                elapsedMs = 5L,
                timeoutMs = 1000,
                isClosed = false,
                isDeviceAttached = true
            )
        )
        assertEquals(
            BulkFailureAction.ERROR_DISCONNECTED,
            UsbBulkTransferHelper.classifyNegativeTransfer(
                elapsedMs = 1000L,
                timeoutMs = 1000,
                isClosed = false,
                isDeviceAttached = false
            )
        )
        assertEquals(
            BulkFailureAction.EOF_CLOSED,
            UsbBulkTransferHelper.classifyNegativeTransfer(
                elapsedMs = 2L,
                timeoutMs = 1000,
                isClosed = true,
                isDeviceAttached = true
            )
        )
    }
}
