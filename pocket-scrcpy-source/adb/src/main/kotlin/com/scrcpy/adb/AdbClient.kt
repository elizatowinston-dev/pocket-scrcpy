package com.scrcpy.adb

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AdbClient(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val keyPair: KeyPair
) : AutoCloseable {

    constructor(socket: Socket, keyPair: KeyPair) : this(
        socket.getInputStream(),
        socket.getOutputStream(),
        keyPair
    )

    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val openWaiters = ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<AdbStream>>()

    @Volatile
    private var isRunning = true
    private var readerThread: Thread? = null

    fun connect() {
        // Send CNXN
        val cnxn = AdbMessage(
            command = AdbMessage.CMD_CNXN,
            arg0 = 0x01000000, // VERSION
            arg1 = AdbMessage.MAX_PAYLOAD,
            data = "host::scrcpy-android\u0000".toByteArray(Charsets.US_ASCII)
        )
        cnxn.write(outputStream)

        // Initial handshake loop
        while (true) {
            val resp = AdbMessage.read(inputStream)
            when (resp.command) {
                AdbMessage.CMD_CNXN -> {
                    // Connected!
                    startReaderLoop()
                    return
                }
                AdbMessage.CMD_AUTH -> {
                    if (resp.arg0 == AdbMessage.AUTH_TYPE_TOKEN) {
                        // Sign token
                        val sig = AdbCrypto.sign(keyPair.private, resp.data)
                        val authResp = AdbMessage(
                            command = AdbMessage.CMD_AUTH,
                            arg0 = AdbMessage.AUTH_TYPE_SIGNATURE,
                            arg1 = 0,
                            data = sig
                        )
                        authResp.write(outputStream)

                        // Next message could be CNXN (accepted) or another AUTH (need public key)
                        val nextResp = AdbMessage.read(inputStream)
                        if (nextResp.command == AdbMessage.CMD_CNXN) {
                            startReaderLoop()
                            return
                        } else if (nextResp.command == AdbMessage.CMD_AUTH) {
                            // Send public key
                            val pubKeyData = AdbCrypto.getAdbPublicKeyString(keyPair.public as RSAPublicKey)
                            val pubKeyResp = AdbMessage(
                                command = AdbMessage.CMD_AUTH,
                                arg0 = AdbMessage.AUTH_TYPE_RSAPUBLICKEY,
                                arg1 = 0,
                                data = pubKeyData
                            )
                            pubKeyResp.write(outputStream)
                        } else {
                            throw IllegalStateException("Unexpected ADB message after signature: ${nextResp.command}")
                        }
                    } else {
                        throw IllegalStateException("Unexpected AUTH arg0: ${resp.arg0}")
                    }
                }
                else -> throw IllegalStateException("Unexpected ADB message during handshake: ${resp.command}")
            }
        }
    }

    private fun startReaderLoop() {
        readerThread = Thread {
            try {
                while (isRunning) {
                    val msg = AdbMessage.read(inputStream)
                    handleMessage(msg)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    closeAllStreams()
                }
            }
        }.apply {
            isDaemon = true
            name = "adb-reader"
            start()
        }
    }

    private fun handleMessage(msg: AdbMessage) {
        when (msg.command) {
            AdbMessage.CMD_OKAY -> {
                val localId = msg.arg1
                val remoteId = msg.arg0
                val waiter = openWaiters.remove(localId)
                if (waiter != null) {
                    val stream = streams[localId]
                    if (stream != null) {
                        stream.remoteId = remoteId
                        waiter.complete(stream)
                    }
                }
            }
            AdbMessage.CMD_WRTE -> {
                val localId = msg.arg1
                val remoteId = msg.arg0
                val stream = streams[localId]
                if (stream != null) {
                    stream.onDataReceived(msg.data)
                    // Ack with OKAY
                    val ack = AdbMessage(
                        command = AdbMessage.CMD_OKAY,
                        arg0 = localId,
                        arg1 = remoteId,
                        data = AdbMessage.EMPTY_DATA
                    )
                    synchronized(outputStream) {
                        ack.write(outputStream)
                    }
                }
            }
            AdbMessage.CMD_CLSE -> {
                val localId = msg.arg1
                val stream = streams.remove(localId)
                stream?.onRemoteClosed()
                openWaiters.remove(localId)?.completeExceptionally(IllegalStateException("Stream closed prematurely"))
            }
        }
    }

    fun open(destination: String): AdbStream {
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(localId, 0, this)
        streams[localId] = stream

        val future = java.util.concurrent.CompletableFuture<AdbStream>()
        openWaiters[localId] = future

        val destBytes = (destination + "\u0000").toByteArray(Charsets.US_ASCII)
        val openMsg = AdbMessage(
            command = AdbMessage.CMD_OPEN,
            arg0 = localId,
            arg1 = 0,
            data = destBytes
        )
        synchronized(outputStream) {
            openMsg.write(outputStream)
        }

        return future.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun openSync(): AdbSync {
        val stream = open("sync:")
        return AdbSync(stream)
    }

    fun shell(command: String): AdbStream {
        return open("shell:$command")
    }

    internal fun sendWrite(stream: AdbStream, data: ByteArray) {
        val msg = AdbMessage(
            command = AdbMessage.CMD_WRTE,
            arg0 = stream.localId,
            arg1 = stream.remoteId,
            data = data
        )
        synchronized(outputStream) {
            msg.write(outputStream)
        }
    }

    internal fun sendClose(stream: AdbStream) {
        streams.remove(stream.localId)
        val msg = AdbMessage(
            command = AdbMessage.CMD_CLSE,
            arg0 = stream.localId,
            arg1 = stream.remoteId,
            data = AdbMessage.EMPTY_DATA
        )
        synchronized(outputStream) {
            try {
                msg.write(outputStream)
            } catch (_: Exception) {}
        }
    }

    private fun closeAllStreams() {
        for (s in streams.values) {
            s.onRemoteClosed()
        }
        streams.clear()
        openWaiters.values.forEach { it.completeExceptionally(IllegalStateException("ADB connection lost")) }
        openWaiters.clear()
    }

    override fun close() {
        isRunning = false
        closeAllStreams()
        try {
            inputStream.close()
        } catch (_: Exception) {}
        try {
            outputStream.close()
        } catch (_: Exception) {}
        readerThread?.interrupt()
    }
}
