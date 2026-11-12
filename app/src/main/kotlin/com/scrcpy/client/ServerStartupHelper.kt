package com.scrcpy.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.InputStream

internal class ServerStartupException(
    message: String,
    val earlyExit: Boolean,
    cause: Throwable? = null
) : Exception(message, cause)

internal class ServerShellMonitor(
    private val inputStream: InputStream
) {
    private val outputBuffer = StringBuilder()
    private val lock = Any()

    @Volatile
    var hasExited: Boolean = false
        private set

    fun readLoop() {
        val buf = ByteArray(1024)
        try {
            while (true) {
                val n = inputStream.read(buf)
                if (n < 0) break
                if (n > 0) {
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    synchronized(lock) {
                        if (outputBuffer.length < MAX_LOG_CHARS) {
                            val remaining = MAX_LOG_CHARS - outputBuffer.length
                            outputBuffer.append(
                                if (text.length <= remaining) text else text.substring(0, remaining)
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Stream closed on disconnect or transport failure
        } finally {
            hasExited = true
        }
    }

    fun output(): String = synchronized(lock) {
        outputBuffer.toString().trim()
    }

    companion object {
        private const val MAX_LOG_CHARS = 8192
    }
}

internal object ServerStartupHelper {
    const val STARTUP_TIMEOUT_MS = 5_000L
    const val RETRY_INTERVAL_MS = 100L
    private const val MAX_ERROR_OUTPUT_CHARS = 600

    suspend fun <T> openWithRetry(
        timeoutMs: Long = STARTUP_TIMEOUT_MS,
        retryIntervalMs: Long = RETRY_INTERVAL_MS,
        nowMs: () -> Long = { System.currentTimeMillis() },
        sleepFn: suspend (Long) -> Unit = { delay(it) },
        hasServerExited: () -> Boolean,
        serverOutput: () -> String,
        attemptOpen: () -> T
    ): T {
        val deadline = nowMs() + timeoutMs
        var lastError: Exception? = null

        while (true) {
            if (hasServerExited()) {
                throw ServerStartupException(
                    message = formatEarlyExitError(serverOutput(), lastError),
                    earlyExit = true,
                    cause = lastError
                )
            }

            try {
                return attemptOpen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }

            if (hasServerExited()) {
                throw ServerStartupException(
                    message = formatEarlyExitError(serverOutput(), lastError),
                    earlyExit = true,
                    cause = lastError
                )
            }

            val remaining = deadline - nowMs()
            if (remaining <= 0L) {
                throw ServerStartupException(
                    message = formatTimeoutError(timeoutMs, serverOutput(), lastError),
                    earlyExit = false,
                    cause = lastError
                )
            }

            sleepFn(minOf(retryIntervalMs, remaining))
        }
    }

    private fun truncateOutput(output: String): String {
        val trimmed = output.trim()
        return if (trimmed.length <= MAX_ERROR_OUTPUT_CHARS) {
            trimmed
        } else {
            trimmed.take(MAX_ERROR_OUTPUT_CHARS) + "..."
        }
    }

    fun formatEarlyExitError(output: String, lastError: Exception?): String {
        val snippet = truncateOutput(output)
        return when {
            snippet.isNotEmpty() -> "scrcpy-server exited early:\n$snippet"
            lastError?.message?.isNotBlank() == true -> "scrcpy-server exited early (${lastError.message})"
            else -> "scrcpy-server exited early with no output"
        }
    }

    fun formatTimeoutError(timeoutMs: Long, output: String, lastError: Exception?): String {
        val snippet = truncateOutput(output)
        val detail = lastError?.message?.takeIf { it.isNotBlank() } ?: "socket not ready"
        return if (snippet.isNotEmpty()) {
            "Timed out after ${timeoutMs}ms waiting for scrcpy-server ($detail):\n$snippet"
        } else {
            "Timed out after ${timeoutMs}ms waiting for scrcpy-server ($detail)"
        }
    }
}
