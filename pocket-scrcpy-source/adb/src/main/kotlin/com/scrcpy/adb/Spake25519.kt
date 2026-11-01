package com.scrcpy.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 on Ed25519 as implemented in BoringSSL (spake25519.c) and used by Android ADB Pairing.
 */
class Spake25519(
    val isClient: Boolean,
    val myName: ByteArray,
    val theirName: ByteArray,
    val password: ByteArray
) {
    companion object {
        // Curve parameters: Ed25519
        // 2^255 - 19
        val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
        // d = -121665 * (121666)^(-1) mod P
        val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P)
        // Group order l
        val Q: BigInteger = BigInteger.valueOf(2).pow(252).add(BigInteger("27742317777372353535851937790883648493"))

        // Generator base point B
        val BY: BigInteger = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val BX: BigInteger = recoverX(BY, 0) ?: error("Failed to compute base point X")
        val B = Point(BX, BY)

        // Precomputed points N and M from BoringSSL spake25519.c
        val N = decodePoint(hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
        val M = decodePoint(hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))

        data class Point(val x: BigInteger, val y: BigInteger)

        fun pointAdd(p1: Point, p2: Point): Point {
            val (x1, y1) = p1
            val (x2, y2) = p2
            val dX1X2Y1Y2 = D.multiply(x1).multiply(x2).multiply(y1).multiply(y2).mod(P)
            val numX = x1.multiply(y2).add(y1.multiply(x2)).mod(P)
            val denX = BigInteger.ONE.add(dX1X2Y1Y2).modInverse(P)
            val x3 = numX.multiply(denX).mod(P)

            val numY = y1.multiply(y2).add(x1.multiply(x2)).mod(P)
            val denY = BigInteger.ONE.subtract(dX1X2Y1Y2).modInverse(P)
            val y3 = numY.multiply(denY).mod(P)
            return Point(x3, y3)
        }

        fun pointNeg(p: Point): Point = Point(P.subtract(p.x).mod(P), p.y)

        fun pointSub(p1: Point, p2: Point): Point = pointAdd(p1, pointNeg(p2))

        fun pointMul(scalar: BigInteger, p: Point): Point {
            var r = Point(BigInteger.ZERO, BigInteger.ONE) // Neutral element (0, 1)
            var base = p
            var s = scalar.mod(Q)
            while (s > BigInteger.ZERO) {
                if (s.testBit(0)) {
                    r = pointAdd(r, base)
                }
                base = pointAdd(base, base)
                s = s.shiftRight(1)
            }
            return r
        }

        fun recoverX(y: BigInteger, sign: Int): BigInteger? {
            if (y >= P) return null
            val y2 = y.multiply(y).mod(P)
            val num = y2.subtract(BigInteger.ONE).mod(P)
            val den = D.multiply(y2).add(BigInteger.ONE).modInverse(P)
            val x2 = num.multiply(den).mod(P)
            if (x2 == BigInteger.ZERO) {
                return if (sign == 0) BigInteger.ZERO else null
            }
            // x = x2^((P+3)/8) mod P
            val exp = P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))
            var x = x2.modPow(exp, P)
            if (x.multiply(x).subtract(x2).mod(P) != BigInteger.ZERO) {
                val exp2 = P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4))
                x = x.multiply(BigInteger.valueOf(2).modPow(exp2, P)).mod(P)
            }
            if (x.multiply(x).subtract(x2).mod(P) != BigInteger.ZERO) {
                return null
            }
            if ((x.testBit(0) && sign == 0) || (!x.testBit(0) && sign == 1)) {
                x = P.subtract(x)
            }
            return x
        }

        fun decodePoint(bytes: ByteArray): Point {
            require(bytes.size == 32)
            val leBytes = bytes.copyOf()
            val sign = ((leBytes[31].toInt() and 0x80) ushr 7)
            leBytes[31] = (leBytes[31].toInt() and 0x7F).toByte()
            val y = fromLittleEndian(leBytes)
            val x = recoverX(y, sign) ?: error("Invalid curve point")
            return Point(x, y)
        }

        fun encodePoint(p: Point): ByteArray {
            val leBytes = toLittleEndian(p.y, 32)
            if (p.x.testBit(0)) {
                leBytes[31] = (leBytes[31].toInt() or 0x80).toByte()
            }
            return leBytes
        }

        fun fromLittleEndian(bytes: ByteArray): BigInteger {
            val rev = bytes.reversedArray()
            return BigInteger(1, rev)
        }

        fun toLittleEndian(n: BigInteger, size: Int): ByteArray {
            val be = n.toByteArray()
            val le = ByteArray(size)
            var bIdx = be.size - 1
            var lIdx = 0
            while (bIdx >= 0 && lIdx < size) {
                le[lIdx++] = be[bIdx--]
            }
            return le
        }

        private fun hexToBytes(hex: String): ByteArray {
            val b = ByteArray(hex.length / 2)
            for (i in b.indices) {
                b[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return b
        }
    }

    private val privateKeyScalar: BigInteger
    private val passwordScalar: BigInteger
    private val passwordHash: ByteArray
    val myMsg: ByteArray

    init {
        // Random 64 bytes reduced mod Q and multiplied by 8
        val rand = SecureRandom()
        val privBytes = ByteArray(64)
        rand.nextBytes(privBytes)
        val privBig = fromLittleEndian(privBytes).mod(Q)
        privateKeyScalar = privBig.multiply(BigInteger.valueOf(8)).mod(Q)

        val md = MessageDigest.getInstance("SHA-512")
        passwordHash = md.digest(password)
        var pwScalar = fromLittleEndian(passwordHash).mod(Q)

        // BoringSSL password scalar hack:
        // Adjust bits so (scalar & 7) == 0
        var order = Q
        if (pwScalar.testBit(0)) {
            pwScalar = pwScalar.add(order)
        }
        order = order.shiftLeft(1)
        if (pwScalar.testBit(1)) {
            pwScalar = pwScalar.add(order)
        }
        order = order.shiftLeft(1)
        if (pwScalar.testBit(2)) {
            pwScalar = pwScalar.add(order)
        }
        passwordScalar = pwScalar

        // Compute myMsg = privateKey * B + passwordScalar * (M if Alice else N)
        val p = pointMul(privateKeyScalar, B)
        val maskPoint = if (isClient) M else N
        val mask = pointMul(passwordScalar, maskPoint)
        val myPoint = pointAdd(p, mask)
        myMsg = encodePoint(myPoint)
    }

    fun processMessage(theirMsg: ByteArray): ByteArray {
        val theirPoint = decodePoint(theirMsg)
        val unmaskPoint = if (isClient) N else M
        val unmask = pointMul(passwordScalar, unmaskPoint)
        val peerP = pointSub(theirPoint, unmask)
        val sharedPoint = pointMul(privateKeyScalar, peerP)
        val sharedEncoded = encodePoint(sharedPoint)

        val md = MessageDigest.getInstance("SHA-512")
        fun updateWithLengthPrefix(data: ByteArray) {
            val lenLe = ByteArray(8)
            var l = data.size.toLong()
            for (i in 0..7) {
                lenLe[i] = (l and 0xFF).toByte()
                l = l ushr 8
            }
            md.update(lenLe)
            md.update(data)
        }

        if (isClient) {
            updateWithLengthPrefix(myName)
            updateWithLengthPrefix(theirName)
            updateWithLengthPrefix(myMsg)
            updateWithLengthPrefix(theirMsg)
        } else {
            updateWithLengthPrefix(theirName)
            updateWithLengthPrefix(myName)
            updateWithLengthPrefix(theirMsg)
            updateWithLengthPrefix(myMsg)
        }
        updateWithLengthPrefix(sharedEncoded)
        updateWithLengthPrefix(passwordHash)

        return md.digest()
    }
}
