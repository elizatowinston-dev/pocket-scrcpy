package com.scrcpy.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

class AdbMessageTest {

    @Test
    fun testAdbMessageSerialization() {
        val msg = AdbMessage(
            command = AdbMessage.CMD_CNXN,
            arg0 = 0x01000000,
            arg1 = 256 * 1024,
            data = "host::test\u0000".toByteArray(Charsets.US_ASCII)
        )

        val bos = ByteArrayOutputStream()
        msg.write(bos)

        val bytes = bos.toByteArray()
        val parsed = AdbMessage.read(ByteArrayInputStream(bytes))

        assertEquals(msg.command, parsed.command)
        assertEquals(msg.arg0, parsed.arg0)
        assertEquals(msg.arg1, parsed.arg1)
        assertArrayEquals(msg.data, parsed.data)
        assertEquals(msg.magic, parsed.magic)
    }

    @Test
    fun testAdbCryptoBlobGeneration() {
        val keyPair = AdbCrypto.generateKeyPair()
        val pubKey = keyPair.public as RSAPublicKey
        val blob = AdbCrypto.convertRsaPublicKeyToAdbBlob(pubKey)

        assertEquals(524, blob.size)
        // Modulus words = 64
        assertEquals(64, blob[0].toInt())

        val pubKeyStr = AdbCrypto.getAdbPublicKeyString(pubKey)
        assert(pubKeyStr.isNotEmpty())
    }

    @Test
    fun testAdbSignature() {
        val keyPair = AdbCrypto.generateKeyPair()
        val token = ByteArray(20) { it.toByte() }
        val sig = AdbCrypto.sign(keyPair.private, token)
        assertEquals(256, sig.size)
    }
}
