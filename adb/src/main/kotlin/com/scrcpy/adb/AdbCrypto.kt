package com.scrcpy.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

object AdbCrypto {
    private const val ANDROID_RSA_MODULUS_LEN_WORDS = 64 // 2048 / 32
    private const val ANDROID_RSA_MODULUS_LEN_BYTES = ANDROID_RSA_MODULUS_LEN_WORDS * 4

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    fun sign(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)

        // ADB expects PKCS#1 v1.5 padding with SHA-1 digest info prefix
        val sha1DigestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val tokenHash = md.digest(token)

        val padded = ByteArray(256)
        padded[0] = 0x00
        padded[1] = 0x01
        val padLen = 256 - 3 - sha1DigestInfo.size - tokenHash.size
        for (i in 0 until padLen) {
            padded[2 + i] = 0xFF.toByte()
        }
        padded[2 + padLen] = 0x00
        System.arraycopy(sha1DigestInfo, 0, padded, 3 + padLen, sha1DigestInfo.size)
        System.arraycopy(tokenHash, 0, padded, 3 + padLen + sha1DigestInfo.size, tokenHash.size)

        return cipher.doFinal(padded)
    }

    /**
     * Converts an RSAPublicKey into the Android ADB public key blob format (524 bytes).
     * Format:
     * - int32_t len (modulus size in 32-bit words, i.e. 64)
     * - uint32_t n0inv
     * - uint8_t n[256] (modulus as little-endian bytes)
     * - uint8_t rr[256] (MontMult(R, R) as little-endian bytes, R = 2^2048 mod N)
     * - int32_t exponent (65537)
     */
    fun convertRsaPublicKeyToAdbBlob(publicKey: RSAPublicKey): ByteArray {
        val n = publicKey.modulus
        val r = BigInteger.valueOf(2).pow(2048)
        val rr = r.multiply(r).mod(n)

        val r32 = BigInteger.valueOf(2).pow(32)
        val n0inv = n.modInverse(r32).negate().add(r32).mod(r32).toLong()

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(ANDROID_RSA_MODULUS_LEN_WORDS)
        buf.putInt(n0inv.toInt())

        val nBytes = toLittleEndianByteArray(n, 256)
        buf.put(nBytes)

        val rrBytes = toLittleEndianByteArray(rr, 256)
        buf.put(rrBytes)

        buf.putInt(publicKey.publicExponent.toInt())
        return buf.array()
    }

    fun getAdbPublicKeyString(publicKey: RSAPublicKey, user: String = "scrcpy-android"): ByteArray {
        val blob = convertRsaPublicKeyToAdbBlob(publicKey)
        val base64 = Base64.getEncoder().encodeToString(blob)
        val full = "$base64 $user\u0000"
        return full.toByteArray(Charsets.UTF_8)
    }

    private fun toLittleEndianByteArray(bigInt: BigInteger, size: Int): ByteArray {
        val beBytes = bigInt.toByteArray()
        val leBytes = ByteArray(size)
        var beIndex = beBytes.size - 1
        var leIndex = 0
        while (beIndex >= 0 && leIndex < size) {
            leBytes[leIndex++] = beBytes[beIndex--]
        }
        return leBytes
    }
}
