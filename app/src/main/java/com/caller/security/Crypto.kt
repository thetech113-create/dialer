package com.caller.security

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Thin wrappers over standard JCA primitives only (ECDSA P-256, SHA-256, HMAC-SHA256). */
object Crypto {
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun randomIndex(bound: Int): Int = rng.nextInt(bound)

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING)
    fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Unambiguous, versioned message encoding for anything that gets signed or MACed. */
    fun canon(vararg parts: String): ByteArray = parts.joinToString("\n").toByteArray(Charsets.UTF_8)

    fun publicKeyFromEncoded(encoded: ByteArray): PublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        require(key is ECPublicKey && key.params.curve.field.fieldSize == 256) { "not a P-256 key" }
        return key
    }

    fun fingerprint(pub: PublicKey): String = hex(sha256(pub.encoded))

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub)
        s.update(data)
        s.verify(sig)
    } catch (_: GeneralSecurityException) {
        false
    }
}

/** Reads at most [max] bytes; returns null if the stream is larger. */
internal fun readLimited(input: InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * Per-installation identity. The device id is the SHA-256 fingerprint of its public key.
 */
class DeviceIdentity(private val privateKey: PrivateKey, val publicKey: PublicKey) {
    val publicKeyB64: String = Crypto.b64(publicKey.encoded)
    val id: String = Crypto.fingerprint(publicKey)

    fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(data)
        return s.sign()
    }

    companion object {
        fun generate(): DeviceIdentity {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            val kp = g.generateKeyPair()
            return DeviceIdentity(kp.private, kp.public)
        }

        fun loadOrCreate(file: File): DeviceIdentity {
            if (file.exists()) {
                val lines = file.readLines()
                check(lines.size >= 2) { "corrupt identity file" }
                val kf = KeyFactory.getInstance("EC")
                val pub = kf.generatePublic(X509EncodedKeySpec(Crypto.unb64(lines[0])))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Crypto.unb64(lines[1])))
                return DeviceIdentity(priv, pub)
            }
            val id = generate()
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Crypto.b64(id.publicKey.encoded) + "\n" + Crypto.b64(id.privateKey.encoded) + "\n")
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            return id
        }
    }
}
