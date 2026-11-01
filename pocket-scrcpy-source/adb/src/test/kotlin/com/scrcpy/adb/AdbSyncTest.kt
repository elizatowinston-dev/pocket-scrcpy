package com.scrcpy.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbSyncTest {

    @Test(timeout = 5000)
    fun testSyncSendFileProtocol() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val fileContent = "scrcpy-server-jar-test-payload-123456789".toByteArray(Charsets.UTF_8)
        val remotePath = "/data/local/tmp/scrcpy-server.jar"

        var serverReceivedPath = ""
        var serverReceivedData = ByteArray(0)
        var serverReceivedDoneTime = 0

        val serverThread = Thread {
            val socket = serverSocket.accept()
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. Read CNXN
            AdbMessage.read(input)
            val cnxnResp = AdbMessage(AdbMessage.CMD_CNXN, 0x01000000, 256 * 1024, "device::test\u0000".toByteArray(Charsets.US_ASCII))
            cnxnResp.write(output)

            // 2. Read OPEN for sync:
            val openMsg = AdbMessage.read(input)
            assertEquals(AdbMessage.CMD_OPEN, openMsg.command)
            val clientLocalId = openMsg.arg0
            val serverLocalId = 77
            val okayMsg = AdbMessage(AdbMessage.CMD_OKAY, serverLocalId, clientLocalId, AdbMessage.EMPTY_DATA)
            okayMsg.write(output)

            // 3. Receive WRTE messages from client containing the sync commands
            val syncStreamData = ByteArrayOutputStream()
            while (true) {
                val msg = AdbMessage.read(input)
                if (msg.command == AdbMessage.CMD_WRTE) {
                    syncStreamData.write(msg.data)
                    // Ack WRTE
                    AdbMessage(AdbMessage.CMD_OKAY, serverLocalId, clientLocalId, AdbMessage.EMPTY_DATA).write(output)

                    // Check if we received DONE
                    val allBytes = syncStreamData.toByteArray()
                    if (allBytes.size >= 8) {
                        val dis = DataInputStream(ByteArrayInputStream(allBytes))
                        val header = ByteArray(8)
                        dis.readFully(header)
                        val hBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val sendTag = ByteArray(4)
                        hBuf.get(sendTag)
                        if (String(sendTag) == "SEND") {
                            val pathLen = hBuf.getInt()
                            if (allBytes.size >= 8 + pathLen + 8) {
                                val pathBytes = ByteArray(pathLen)
                                dis.readFully(pathBytes)
                                serverReceivedPath = String(pathBytes, Charsets.UTF_8)

                                // Check for DATA
                                val dataBos = ByteArrayOutputStream()
                                var foundDone = false
                                while (dis.available() >= 8) {
                                    dis.readFully(header)
                                    val chunkBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                                    val tag = ByteArray(4)
                                    chunkBuf.get(tag)
                                    val tagStr = String(tag)
                                    val lenOrTime = chunkBuf.getInt()
                                    if (tagStr == "DATA") {
                                        if (dis.available() >= lenOrTime) {
                                            val chunk = ByteArray(lenOrTime)
                                            dis.readFully(chunk)
                                            dataBos.write(chunk)
                                        } else {
                                            break
                                        }
                                    } else if (tagStr == "DONE") {
                                        serverReceivedDoneTime = lenOrTime
                                        foundDone = true
                                        break
                                    }
                                }
                                if (foundDone) {
                                    serverReceivedData = dataBos.toByteArray()
                                    // Reply with OKAY inside a WRTE message to the sync stream
                                    val syncOkay = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                    syncOkay.put("OKAY".toByteArray(Charsets.US_ASCII))
                                    syncOkay.putInt(0)
                                    val wrteResp = AdbMessage(AdbMessage.CMD_WRTE, serverLocalId, clientLocalId, syncOkay.array())
                                    wrteResp.write(output)
                                    break
                                }
                            }
                        }
                    }
                } else if (msg.command == AdbMessage.CMD_CLSE) {
                    break
                }
            }

            socket.close()
        }
        serverThread.start()

        val socket = Socket("localhost", port)
        val keyPair = AdbCrypto.generateKeyPair()
        val client = AdbClient(socket, keyPair)
        client.connect()

        val sync = client.openSync()
        sync.sendFile(ByteArrayInputStream(fileContent), remotePath)
        sync.close()
        client.close()

        serverThread.join()
        serverSocket.close()

        assertEquals("$remotePath,420", serverReceivedPath)
        assertArrayEquals(fileContent, serverReceivedData)
        assert(serverReceivedDoneTime > 0)
    }
}
