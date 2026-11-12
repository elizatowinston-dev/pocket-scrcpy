package com.scrcpy.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ServerStartupHelperTest {

    @Test
    fun testOpenWithRetrySucceedsAfterTransientFailures() = runBlocking {
        var now = 0L
        var attempts = 0
        val result = ServerStartupHelper.openWithRetry(
            timeoutMs = 5_000L,
            retryIntervalMs = 100L,
            nowMs = { now },
            sleepFn = { ms -> now += ms },
            hasServerExited = { false },
            serverOutput = { "" }
        ) {
            attempts++
            if (attempts < 4) {
                throw IllegalStateException("Stream closed prematurely")
            }
            "connected-stream"
        }

        assertEquals("connected-stream", result)
        assertEquals(4, attempts)
        assertEquals(300L, now)
    }

    @Test
    fun testEarlyServerExitAbortsRetryAndSurfacesShellOutput() = runBlocking {
        val shellLogs = "[server] ERROR: Invalid option: bad_opt\nException in thread \"main\""
        val monitor = ServerShellMonitor(ByteArrayInputStream(shellLogs.toByteArray(Charsets.UTF_8)))
        monitor.readLoop()
        assertTrue(monitor.hasExited)

        var attempts = 0
        try {
            ServerStartupHelper.openWithRetry(
                timeoutMs = 5_000L,
                retryIntervalMs = 100L,
                hasServerExited = { monitor.hasExited },
                serverOutput = { monitor.output() }
            ) {
                attempts++
                throw IllegalStateException("Stream closed prematurely")
            }
            fail("Expected ServerStartupException")
        } catch (e: ServerStartupException) {
            assertTrue(e.earlyExit)
            assertEquals(0, attempts)
            assertTrue(e.message!!.contains("scrcpy-server exited early"))
            assertTrue(e.message!!.contains("Invalid option: bad_opt"))
        }
    }

    @Test
    fun testEarlyExitMidRetryLoopSurfacesShellOutput() = runBlocking {
        var now = 0L
        var attempts = 0
        var exited = false

        try {
            ServerStartupHelper.openWithRetry(
                timeoutMs = 5_000L,
                retryIntervalMs = 100L,
                nowMs = { now },
                sleepFn = { ms -> now += ms },
                hasServerExited = { exited },
                serverOutput = { if (exited) "ERROR: Failed to start server" else "" }
            ) {
                attempts++
                if (attempts == 2) {
                    exited = true
                }
                throw IllegalStateException("Stream closed prematurely")
            }
            fail("Expected ServerStartupException")
        } catch (e: ServerStartupException) {
            assertTrue(e.earlyExit)
            assertEquals(2, attempts)
            assertTrue(e.message!!.contains("Failed to start server"))
        }
    }

    @Test
    fun testTimeoutAfterFiveSecondsIncludesDetails() = runBlocking {
        var now = 0L
        var attempts = 0

        try {
            ServerStartupHelper.openWithRetry(
                timeoutMs = 5_000L,
                retryIntervalMs = 100L,
                nowMs = { now },
                sleepFn = { ms -> now += ms },
                hasServerExited = { false },
                serverOutput = { "[server] INFO: Device: Sony XQ-CQ54" }
            ) {
                attempts++
                throw IOException("Connection refused")
            }
            fail("Expected ServerStartupException")
        } catch (e: ServerStartupException) {
            assertFalse(e.earlyExit)
            assertEquals(51, attempts)
            assertEquals(5_000L, now)
            assertTrue(e.message!!.contains("Timed out after 5000ms"))
            assertTrue(e.message!!.contains("Connection refused"))
            assertTrue(e.message!!.contains("Sony XQ-CQ54"))
        }
    }
}
