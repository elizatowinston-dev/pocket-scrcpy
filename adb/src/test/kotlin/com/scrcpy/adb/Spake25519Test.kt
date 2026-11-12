package com.scrcpy.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.interfaces.RSAPublicKey
import javax.crypto.AEADBadTagException

class Spake25519Test {

    @Test(timeout = 5000)
    fun testSpake2KeyAgreement() {
        val clientName = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val serverName = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
        val password = "test-password-123456".toByteArray(Charsets.UTF_8)

        val client = Spake25519(
            isClient = true,
            myName = clientName,
            theirName = serverName,
            password = password
        )

        val server = Spake25519(
            isClient = false,
            myName = serverName,
            theirName = clientName,
            password = password
        )

        assertEquals(32, client.myMsg.size)
        assertEquals(32, server.myMsg.size)

        val clientSharedKey = client.processMessage(server.myMsg)
        val serverSharedKey = server.processMessage(client.myMsg)

        assertArrayEquals("Client and Server shared keys must match", clientSharedKey, serverSharedKey)
    }

    @Test(timeout = 5000)
    fun testPairingCipherAes128Gcm() {
        val keyMaterial = ByteArray(64) { it.toByte() }
        val senderCipher = PairingCipher(keyMaterial)
        val receiverCipher = PairingCipher(keyMaterial)

        val message = "Hello from Android to Xperia pairing!".toByteArray()
        val encrypted = senderCipher.encrypt(message)
        val decrypted = receiverCipher.decrypt(encrypted)

        assertArrayEquals(message, decrypted)
    }

    @Test(timeout = 5000)
    fun testFullPairingProtocolSuccess() {
        val clientPipeOut = PipedOutputStream()
        val serverPipeIn = PipedInputStream(clientPipeOut)

        val serverPipeOut = PipedOutputStream()
        val clientPipeIn = PipedInputStream(serverPipeOut)

        val pairingCode = "654321"
        val exportedKey = ByteArray(64) { (it + 1).toByte() }

        val clientKeys = AdbCrypto.generateKeyPair()
        val serverKeys = AdbCrypto.generateKeyPair()

        val exporter = object : TlsExporter {
            override fun exportKeyingMaterial(label: String, context: ByteArray?, length: Int): ByteArray {
                return exportedKey
            }
        }

        val client = AdbPairingClient("localhost", 0, pairingCode, clientKeys, exporter)

        val serverThread = Thread {
            val pswd = pairingCode.toByteArray(Charsets.UTF_8) + exportedKey
            val spake2 = Spake25519(
                isClient = false,
                myName = AdbPairingClient.SERVER_NAME,
                theirName = AdbPairingClient.CLIENT_NAME,
                password = pswd
            )

            // Read client SPAKE2 msg
            val (cType, cLen) = AdbPairingClient.readHeader(serverPipeIn)
            assertEquals(AdbPairingClient.TYPE_SPAKE2_MSG, cType)
            val clientMsg = ByteArray(cLen)
            java.io.DataInputStream(serverPipeIn).readFully(clientMsg)

            // Send server SPAKE2 msg
            AdbPairingClient.writeHeader(serverPipeOut, AdbPairingClient.TYPE_SPAKE2_MSG, spake2.myMsg.size)
            serverPipeOut.write(spake2.myMsg)
            serverPipeOut.flush()

            // Initialize cipher
            val keyMaterial = spake2.processMessage(clientMsg)
            val cipher = PairingCipher(keyMaterial)

            // Read client PeerInfo
            val (pType, pLen) = AdbPairingClient.readHeader(serverPipeIn)
            assertEquals(AdbPairingClient.TYPE_PEER_INFO, pType)
            val encPeer = ByteArray(pLen)
            java.io.DataInputStream(serverPipeIn).readFully(encPeer)
            val clientPeerInfo = cipher.decrypt(encPeer)
            assertEquals(AdbPairingClient.MAX_PEER_INFO_SIZE, clientPeerInfo.size)

            // Send server PeerInfo
            val srvPubBlob = AdbCrypto.getAdbPublicKeyString(serverKeys.public as RSAPublicKey)
            val srvPeerInfo = AdbPairingClient.createPeerInfo(srvPubBlob)
            val encSrv = cipher.encrypt(srvPeerInfo)
            AdbPairingClient.writeHeader(serverPipeOut, AdbPairingClient.TYPE_PEER_INFO, encSrv.size)
            serverPipeOut.write(encSrv)
            serverPipeOut.flush()
        }
        serverThread.start()

        val success = client.executePairingProtocol(clientPipeIn, clientPipeOut, exportedKey)
        serverThread.join(3000)

        assertTrue("Full pairing exchange must succeed with correct pairing code", success)
    }

    @Test(timeout = 5000)
    fun testPairingFailsWithWrongPairingCode() {
        val clientPipeOut = PipedOutputStream()
        val serverPipeIn = PipedInputStream(clientPipeOut)

        val serverPipeOut = PipedOutputStream()
        val clientPipeIn = PipedInputStream(serverPipeOut)

        val correctCode = "123456"
        val wrongCode = "999999"
        val exportedKey = ByteArray(64) { 42.toByte() }

        val clientKeys = AdbCrypto.generateKeyPair()
        val serverKeys = AdbCrypto.generateKeyPair()

        val exporter = object : TlsExporter {
            override fun exportKeyingMaterial(label: String, context: ByteArray?, length: Int): ByteArray {
                return exportedKey
            }
        }

        // Client configured with wrong code
        val client = AdbPairingClient("localhost", 0, wrongCode, clientKeys, exporter)

        val serverThread = Thread {
            // Server expects correctCode
            val pswd = correctCode.toByteArray(Charsets.UTF_8) + exportedKey
            val spake2 = Spake25519(
                isClient = false,
                myName = AdbPairingClient.SERVER_NAME,
                theirName = AdbPairingClient.CLIENT_NAME,
                password = pswd
            )

            val (cType, cLen) = AdbPairingClient.readHeader(serverPipeIn)
            val clientMsg = ByteArray(cLen)
            java.io.DataInputStream(serverPipeIn).readFully(clientMsg)

            AdbPairingClient.writeHeader(serverPipeOut, AdbPairingClient.TYPE_SPAKE2_MSG, spake2.myMsg.size)
            serverPipeOut.write(spake2.myMsg)
            serverPipeOut.flush()

            val keyMaterial = spake2.processMessage(clientMsg)
            val cipher = PairingCipher(keyMaterial)

            val (pType, pLen) = AdbPairingClient.readHeader(serverPipeIn)
            val encPeer = ByteArray(pLen)
            java.io.DataInputStream(serverPipeIn).readFully(encPeer)

            try {
                cipher.decrypt(encPeer)
            } catch (_: Exception) {
                // Server expected to fail decrypting client PeerInfo
            }

            val srvPubBlob = AdbCrypto.getAdbPublicKeyString(serverKeys.public as RSAPublicKey)
            val srvPeerInfo = AdbPairingClient.createPeerInfo(srvPubBlob)
            val encSrv = cipher.encrypt(srvPeerInfo)
            AdbPairingClient.writeHeader(serverPipeOut, AdbPairingClient.TYPE_PEER_INFO, encSrv.size)
            serverPipeOut.write(encSrv)
            serverPipeOut.flush()
        }
        serverThread.start()

        try {
            client.executePairingProtocol(clientPipeIn, clientPipeOut, exportedKey)
            fail("Pairing must throw AEADBadTagException or fail when code is incorrect")
        } catch (e: Exception) {
            // Expected failure (e.g. AEADBadTagException)
            assertTrue(e is AEADBadTagException || e.cause is AEADBadTagException || e is IllegalStateException)
        } finally {
            serverThread.join(2000)
        }
    }
}
