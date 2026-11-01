package com.scrcpy.adb

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android 11+ Wireless Debugging Pairing client.
 * Implements full TLS 1.3 handshake, RFC 5705 key export, Ed25519 SPAKE2 with BoringSSL constants,
 * HKDF-SHA256, AES-128-GCM, and PeerInfo RSA public key exchange.
 */
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val adbKeyPair: KeyPair = AdbCrypto.generateKeyPair(),
    private val customTlsExporter: TlsExporter? = null
) {
    companion object {
        const val HEADER_VERSION: Byte = 1
        const val TYPE_SPAKE2_MSG: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1

        const val PEER_INFO_TYPE_ADB_RSA_PUB_KEY: Byte = 0
        const val PEER_INFO_TYPE_ADB_DEVICE_GUID: Byte = 1
        const val MAX_PEER_INFO_SIZE = 8192

        val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        fun createPeerInfo(pubKeyBlob: ByteArray): ByteArray {
            val peerInfo = ByteArray(MAX_PEER_INFO_SIZE)
            peerInfo[0] = PEER_INFO_TYPE_ADB_RSA_PUB_KEY
            System.arraycopy(pubKeyBlob, 0, peerInfo, 1, minOf(pubKeyBlob.size, MAX_PEER_INFO_SIZE - 1))
            return peerInfo
        }

        fun writeHeader(os: OutputStream, type: Byte, payloadSize: Int) {
            val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            buf.put(HEADER_VERSION)
            buf.put(type)
            buf.putInt(payloadSize)
            os.write(buf.array())
        }

        fun readHeader(inputStream: InputStream): Pair<Byte, Int> {
            val dis = DataInputStream(inputStream)
            val version = dis.readByte()
            if (version != HEADER_VERSION) {
                error("Unsupported pairing header version: $version")
            }
            val type = dis.readByte()
            val payloadSize = dis.readInt()
            return Pair(type, payloadSize)
        }

        fun extractExportedKey(socket: SSLSocket, exporter: TlsExporter?): ByteArray {
            if (exporter != null) {
                return exporter.exportKeyingMaterial("adb-label\u0000", null, 64)
            }

            // Check if socket itself or Conscrypt can export keying material
            try {
                // Conscrypt static method: org.conscrypt.Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int)
                val conscryptClass = Class.forName("org.conscrypt.Conscrypt")
                val method = conscryptClass.getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType
                )
                val res = method.invoke(null, socket, "adb-label\u0000", null, 64) as? ByteArray
                if (res != null && res.size == 64) {
                    return res
                }
            } catch (_: Exception) {}

            try {
                // Direct method on socket implementation
                val method = socket.javaClass.getMethod("exportKeyingMaterial", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
                val res = method.invoke(socket, "adb-label\u0000", null, 64) as? ByteArray
                if (res != null && res.size == 64) {
                    return res
                }
            } catch (_: Exception) {}

            throw IllegalStateException("TLS key export unavailable: pairing cannot work without Conscrypt or RFC 5705 exporter")
        }
    }

    fun pair(): Boolean {
        val tlsKeyPair = AdbCrypto.generateKeyPair()
        val cert = generateSelfSignedCertificate(tlsKeyPair)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", tlsKeyPair.private, "password".toCharArray(), arrayOf(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "password".toCharArray())
        }

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        // Try Conscrypt provider if present
        val sslContext = try {
            val conscryptClass = Class.forName("org.conscrypt.Conscrypt")
            val newProviderMethod = conscryptClass.getMethod("newProvider")
            val provider = newProviderMethod.invoke(null) as java.security.Provider
            SSLContext.getInstance("TLSv1.3", provider)
        } catch (_: Exception) {
            SSLContext.getInstance("TLSv1.3")
        }

        sslContext.init(kmf.keyManagers, trustAll, SecureRandom())

        val socket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        socket.startHandshake()

        return socket.use { sslSock ->
            val exportedKey = extractExportedKey(sslSock, customTlsExporter)
            executePairingProtocol(sslSock.inputStream, sslSock.outputStream, exportedKey)
        }
    }

    fun executePairingProtocol(inputStream: InputStream, outputStream: OutputStream, exportedKey: ByteArray): Boolean {
        // Password = pairingCode bytes + exportedKeyMaterial
        val pswd = pairingCode.toByteArray(Charsets.UTF_8) + exportedKey

        val spake2 = Spake25519(
            isClient = true,
            myName = CLIENT_NAME,
            theirName = SERVER_NAME,
            password = pswd
        )

        // 1. Send SPAKE2 message
        writeHeader(outputStream, TYPE_SPAKE2_MSG, spake2.myMsg.size)
        outputStream.write(spake2.myMsg)
        outputStream.flush()

        // 2. Receive peer's SPAKE2 message
        val (theirType, theirLen) = readHeader(inputStream)
        if (theirType != TYPE_SPAKE2_MSG || theirLen != 32) {
            throw IllegalStateException("Invalid SPAKE2 response header: type=$theirType len=$theirLen")
        }
        val theirMsg = ByteArray(theirLen)
        DataInputStream(inputStream).readFully(theirMsg)

        // 3. Process message and initialize AES-128-GCM cipher
        val keyMaterial = spake2.processMessage(theirMsg)
        val cipher = PairingCipher(keyMaterial)

        // 4. Exchange PeerInfo: prepare our RSA public key blob
        val pubBlob = AdbCrypto.getAdbPublicKeyString(adbKeyPair.public as RSAPublicKey)
        val ourPeerInfo = createPeerInfo(pubBlob)
        val encryptedPeerInfo = cipher.encrypt(ourPeerInfo)

        // Send our encrypted PeerInfo
        writeHeader(outputStream, TYPE_PEER_INFO, encryptedPeerInfo.size)
        outputStream.write(encryptedPeerInfo)
        outputStream.flush()

        // 5. Receive peer's encrypted PeerInfo
        val (peerType, peerLen) = readHeader(inputStream)
        if (peerType != TYPE_PEER_INFO || peerLen <= 0) {
            throw IllegalStateException("Invalid PeerInfo response header: type=$peerType len=$peerLen")
        }
        val encPeer = ByteArray(peerLen)
        DataInputStream(inputStream).readFully(encPeer)

        // Decrypt peer's PeerInfo: wrong pairing code causes AEADBadTagException here
        val decryptedPeerInfo = cipher.decrypt(encPeer)
        if (decryptedPeerInfo.size != MAX_PEER_INFO_SIZE) {
            throw IllegalStateException("Invalid decrypted PeerInfo size: ${decryptedPeerInfo.size}")
        }
        val receivedType = decryptedPeerInfo[0]
        if (receivedType != PEER_INFO_TYPE_ADB_RSA_PUB_KEY && receivedType != PEER_INFO_TYPE_ADB_DEVICE_GUID) {
            throw IllegalStateException("Invalid PeerInfo type: $receivedType")
        }

        return true
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 24 * 60 * 60 * 1000)
        val endDate = Date(now + 365L * 24 * 60 * 60 * 1000)
        val serial = BigInteger.valueOf(now)
        val dnName = X500Name("CN=scrcpy-android")

        val certBuilder = JcaX509v3CertificateBuilder(
            dnName,
            serial,
            startDate,
            endDate,
            dnName,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
    }
}
