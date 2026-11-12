package com.scrcpy.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairingCipher(keyMaterial: ByteArray) {

    private val aesKey: ByteArray
    private var encSequence: Long = 0
    private var decSequence: Long = 0

    init {
        // HKDF-SHA256: ikm = keyMaterial, salt = zeros(32), info = "adb pairing_auth aes-128-gcm key"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(keyMaterial)

        val macExpand = Mac.getInstance("HmacSHA256")
        macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
        val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
        macExpand.update(info)
        macExpand.update(1.toByte())
        val okm = macExpand.doFinal()

        aesKey = okm.copyOfRange(0, 16)
    }

    @Synchronized
    fun encrypt(data: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(encSequence++)
            .putInt(0)
            .array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), spec)
        return cipher.doFinal(data)
    }

    @Synchronized
    fun decrypt(data: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(decSequence++)
            .putInt(0)
            .array()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), spec)
        return cipher.doFinal(data)
    }
}
