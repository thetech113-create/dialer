package com.caller.link

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire protocol shared with the PC app.
 *
 * Frame: 4-byte big-endian length, then the body. Handshake frames are plain JSON; after it
 * every body is IV(16) || AES-256-CBC ciphertext || HMAC-SHA256(seq || IV || ciphertext).
 */
object Proto {
    const val VERSION = 1
    const val TCP_PORT = 38571
    const val UDP_PORT = 38570
    const val DISCOVER_MAGIC = "PHON?1"
    const val MAX_FRAME = 4 * 1024 * 1024
}

class HandshakeException(message: String) : IOException(message)

class Identity(val id: String, val name: String)

interface PairStore {
    fun key(pcId: String): ByteArray?
    fun save(pcId: String, pcName: String, key: ByteArray)
    fun remove(pcId: String)
}

internal object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    /** Six-digit code both sides display so the user can confirm there is nobody in the middle. */
    fun sas(pairKey: ByteArray): String {
        val h = hmac(pairKey, ascii("phon-sas"))
        val v = ((h[0].toInt() and 0x7f) shl 24) or ((h[1].toInt() and 0xff) shl 16) or
            ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
        return String.format(Locale.US, "%06d", v % 1_000_000)
    }
}

/** Ephemeral P-256 key; the public key travels as raw X || Y (64 bytes). */
internal class EcdhKey {
    private val pair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    val publicBytes: ByteArray
        get() {
            val w = (pair.public as ECPublicKey).w
            return fixed32(w.affineX) + fixed32(w.affineY)
        }

    /** SHA-256 of the shared secret, matching CNG's hash KDF on the Windows side. */
    fun agree(peer: ByteArray): ByteArray {
        if (peer.size != 64) throw HandshakeException("bad public key")
        val params = (pair.public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, peer.copyOfRange(0, 32)), BigInteger(1, peer.copyOfRange(32, 64)))
        val peerKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(peerKey, true)
        return Crypto.sha256(agreement.generateSecret())
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - n, out, 32 - n, n)
        return out
    }
}

class SecureChannel(private val input: DataInputStream, private val output: DataOutputStream) {
    private lateinit var sendEnc: ByteArray
    private lateinit var sendMac: ByteArray
    private lateinit var recvEnc: ByteArray
    private lateinit var recvMac: ByteArray
    private var sendSeq = 0L
    private var recvSeq = 0L

    internal fun setKeys(sessionKey: ByteArray, isServer: Boolean) {
        val c2sEnc = Crypto.hmac(sessionKey, Crypto.ascii("c2s-enc"))
        val c2sMac = Crypto.hmac(sessionKey, Crypto.ascii("c2s-mac"))
        val s2cEnc = Crypto.hmac(sessionKey, Crypto.ascii("s2c-enc"))
        val s2cMac = Crypto.hmac(sessionKey, Crypto.ascii("s2c-mac"))
        if (isServer) {
            sendEnc = s2cEnc; sendMac = s2cMac; recvEnc = c2sEnc; recvMac = c2sMac
        } else {
            sendEnc = c2sEnc; sendMac = c2sMac; recvEnc = s2cEnc; recvMac = s2cMac
        }
    }

    internal fun readPlain(): JSONObject = JSONObject(String(readFrame(), Charsets.UTF_8))

    internal fun writePlain(msg: JSONObject) = writeFrame(msg.toString().toByteArray(Charsets.UTF_8))

    @Synchronized
    fun send(msg: JSONObject) {
        val iv = Crypto.randomBytes(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendEnc, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(msg.toString().toByteArray(Charsets.UTF_8))
        val mac = Crypto.hmac(sendMac, seqBytes(sendSeq++), iv, ct)
        writeFrame(iv + ct + mac)
    }

    fun receive(): JSONObject {
        val body = readFrame()
        if (body.size < 16 + 16 + 32) throw IOException("short frame")
        val iv = body.copyOfRange(0, 16)
        val ct = body.copyOfRange(16, body.size - 32)
        val mac = body.copyOfRange(body.size - 32, body.size)
        if (!MessageDigest.isEqual(mac, Crypto.hmac(recvMac, seqBytes(recvSeq++), iv, ct))) {
            throw IOException("bad MAC")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvEnc, "AES"), IvParameterSpec(iv))
        return JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8))
    }

    private fun readFrame(): ByteArray {
        val len = input.readInt()
        if (len <= 0 || len > Proto.MAX_FRAME) throw IOException("bad frame length $len")
        return ByteArray(len).also { input.readFully(it) }
    }

    private fun writeFrame(body: ByteArray) {
        synchronized(output) {
            output.writeInt(body.size)
            output.write(body)
            output.flush()
        }
    }

    private fun seqBytes(seq: Long) = ByteArray(8) { i -> (seq ushr (56 - 8 * i)).toByte() }
}

internal object Handshake {
    class Peer(val id: String, val name: String)

    /**
     * Runs the phone side. Resume proves both ends still hold the pairing key. Pair does an
     * ECDH exchange where the PC commits to its key first, then [confirm] shows the user the
     * code to compare; the key is stored only if they accept.
     */
    fun server(
        ch: SecureChannel,
        identity: Identity,
        store: PairStore,
        confirm: (pcName: String, sas: String) -> Boolean,
    ): Peer {
        val hello = ch.readPlain()
        if (hello.optString("t") != "hello" || hello.optInt("v") != Proto.VERSION) {
            throw HandshakeException("unsupported hello")
        }
        val pcId = hello.getString("id")
        val pcName = hello.optString("name", "PC")
        val reply = JSONObject().put("t", "hello").put("v", Proto.VERSION)
            .put("id", identity.id).put("name", identity.name)
        val nonceS = Crypto.randomBytes(16)

        when (hello.optString("mode")) {
            "resume" -> {
                val pairKey = store.key(pcId)
                if (pairKey == null) {
                    ch.writePlain(reply.put("mode", "unpaired"))
                    throw HandshakeException("unknown PC")
                }
                val nonceC = Crypto.unb64(hello.getString("nonce"))
                ch.writePlain(reply.put("mode", "resume").put("nonce", Crypto.b64(nonceS)))
                ch.setKeys(sessionKey(pairKey, nonceC, nonceS), isServer = true)
                expectAuth(ch)
                ch.send(JSONObject().put("t", "auth"))
            }
            "pair" -> {
                val commit = Crypto.unb64(hello.getString("commit"))
                val ecdh = EcdhKey()
                ch.writePlain(
                    reply.put("mode", "pair").put("pub", Crypto.b64(ecdh.publicBytes))
                        .put("nonce", Crypto.b64(nonceS))
                )
                val reveal = ch.readPlain()
                val pubC = Crypto.unb64(reveal.getString("pub"))
                val nonceC = Crypto.unb64(reveal.getString("nonce"))
                if (!MessageDigest.isEqual(commit, Crypto.sha256(pubC, nonceC))) {
                    throw HandshakeException("commitment mismatch")
                }
                val pairKey = Crypto.hmac(ecdh.agree(pubC), Crypto.ascii("phon-pair"), nonceC, nonceS)
                ch.setKeys(sessionKey(pairKey, nonceC, nonceS), isServer = true)
                expectAuth(ch)
                if (!confirm(pcName, Crypto.sas(pairKey))) throw HandshakeException("pairing declined")
                store.save(pcId, pcName, pairKey)
                ch.send(JSONObject().put("t", "auth").put("paired", true))
            }
            else -> throw HandshakeException("unknown mode")
        }
        return Peer(pcId, pcName)
    }

    private fun sessionKey(pairKey: ByteArray, nonceC: ByteArray, nonceS: ByteArray) =
        Crypto.hmac(pairKey, Crypto.ascii("phon-session"), nonceC, nonceS)

    private fun expectAuth(ch: SecureChannel) {
        if (ch.receive().optString("t") != "auth") throw HandshakeException("expected auth")
    }
}
