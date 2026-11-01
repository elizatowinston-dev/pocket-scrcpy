package com.scrcpy.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbClientTest {

    @Test(timeout = 5000)
    fun testAdbConnectAndShellStream() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val keyPair = AdbCrypto.generateKeyPair()

        val serverThread = Thread {
            val socket = serverSocket.accept()
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. Read CNXN
            val cnxn = AdbMessage.read(input)
            assertEquals(AdbMessage.CMD_CNXN, cnxn.command)

            // 2. Send AUTH token
            val token = ByteArray(20) { (it + 1).toByte() }
            val authMsg = AdbMessage(AdbMessage.CMD_AUTH, AdbMessage.AUTH_TYPE_TOKEN, 0, token)
            authMsg.write(output)

            // 3. Read signed AUTH signature
            val sigMsg = AdbMessage.read(input)
            assertEquals(AdbMessage.CMD_AUTH, sigMsg.command)
            assertEquals(AdbMessage.AUTH_TYPE_SIGNATURE, sigMsg.arg0)

            // 4. Send CNXN (accepted)
            val devCnxn = AdbMessage(AdbMessage.CMD_CNXN, 0x01000000, 256 * 1024, "device::ro.product.model=Xperia\u0000".toByteArray(Charsets.US_ASCII))
            devCnxn.write(output)

            // 5. Read OPEN for shell:echo hi
            val openMsg = AdbMessage.read(input)
            assertEquals(AdbMessage.CMD_OPEN, openMsg.command)
            val dest = String(openMsg.data).trimEnd('\u0000')
            assertEquals("shell:echo hi", dest)

            val clientLocalId = openMsg.arg0
            val serverLocalId = 99

            // Send OKAY
            val okayMsg = AdbMessage(AdbMessage.CMD_OKAY, serverLocalId, clientLocalId, AdbMessage.EMPTY_DATA)
            okayMsg.write(output)

            // Send WRTE with reply
            val replyData = "hi\n".toByteArray(Charsets.UTF_8)
            val wrteMsg = AdbMessage(AdbMessage.CMD_WRTE, serverLocalId, clientLocalId, replyData)
            wrteMsg.write(output)

            // Read OKAY ack from client
            val ackMsg = AdbMessage.read(input)
            assertEquals(AdbMessage.CMD_OKAY, ackMsg.command)

            // Send CLSE
            val clseMsg = AdbMessage(AdbMessage.CMD_CLSE, serverLocalId, clientLocalId, AdbMessage.EMPTY_DATA)
            clseMsg.write(output)

            socket.close()
        }
        serverThread.start()

        val socket = Socket("localhost", port)
        val client = AdbClient(socket, keyPair)
        client.connect()

        val stream = client.shell("echo hi")
        val response = ByteArray(10)
        val count = stream.inputStream.read(response)
        val respStr = String(response, 0, count, Charsets.UTF_8)
        assertEquals("hi\n", respStr)

        client.close()
        serverThread.join()
        serverSocket.close()
    }

    @Test(timeout = 5000)
    fun testAdbStreamRefusedSurfacesError() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val keyPair = AdbCrypto.generateKeyPair()

        val serverThread = Thread {
            val socket = serverSocket.accept()
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. Read CNXN
            AdbMessage.read(input)

            // Reply directly with CNXN
            val devCnxn = AdbMessage(AdbMessage.CMD_CNXN, 0x01000000, 256 * 1024, "device::test\u0000".toByteArray(Charsets.US_ASCII))
            devCnxn.write(output)

            // Read OPEN
            val openMsg = AdbMessage.read(input)
            val clientLocalId = openMsg.arg0

            // Refuse immediately by sending CLSE instead of OKAY
            val clseMsg = AdbMessage(AdbMessage.CMD_CLSE, 0, clientLocalId, AdbMessage.EMPTY_DATA)
            clseMsg.write(output)

            socket.close()
        }
        serverThread.start()

        val socket = Socket("localhost", port)
        val client = AdbClient(socket, keyPair)
        client.connect()

        try {
            client.open("nonexistent_stream:")
            fail("Opening a refused stream must throw exception, not hang")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Stream closed") == true || e.cause?.message?.contains("Stream closed") == true)
        }

        client.close()
        serverThread.join()
        serverSocket.close()
    }
}
