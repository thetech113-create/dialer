package com.caller.security

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    const val PROTOCOL_VERSION: Int = 1
    private const val EC_CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val ECDH_ALGORITHM = "ECDH"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val AES_KEY_LENGTH_BYTES = 32

    private val secureRandom = SecureRandom()

    fun randomBytes(length: Int): ByteArray {
        require(length > 0) { "Length must be positive" }
        return ByteArray(length).also { secureRandom.nextBytes(it) }
    }

    fun randomNonceB64(byteLength: Int = 24): String =
        encodeB64(randomBytes(byteLength))

    fun generateSixDigitPairingCode(): String {
        val value = secureRandom.nextInt(900_000) + 100_000
        return value.toString()
    }

    fun encodeB64(bytes: ByteArray): String = try {
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    } catch (_: Throwable) {
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decodeB64(encoded: String): ByteArray {
        require(encoded.isNotBlank()) { "Encoded Base64 string must not be blank" }
        return try {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            java.util.Base64.getUrlDecoder().decode(encoded)
        }
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String =
        sha256(data).joinToString("") { "%02x".format(it) }

    fun publicKeyFingerprint(publicKeySpkiB64: String): String {
        val raw = decodeB64(publicKeySpkiB64)
        val digest = sha256(raw)
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun shortSafetyNumber(publicKeySpkiB64: String): String {
        val digest = sha256(decodeB64(publicKeySpkiB64))
        val num = ((digest[0].toInt() and 0xFF) shl 16) or
            ((digest[1].toInt() and 0xFF) shl 8) or
            (digest[2].toInt() and 0xFF)
        val num2 = ((digest[3].toInt() and 0xFF) shl 16) or
            ((digest[4].toInt() and 0xFF) shl 8) or
            (digest[5].toInt() and 0xFF)
        return "%06d-%06d".format(num % 1_000_000, num2 % 1_000_000)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    fun constantTimeEqualsUtf8(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8)
        )

    fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), secureRandom)
        return kpg.generateKeyPair()
    }

    fun loadOrGenerateAndroidKeyStoreSigningKey(alias: String): KeyPair {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                if (entry != null) {
                    return KeyPair(entry.certificate.publicKey, entry.privateKey)
                }
            }
            val keyGenSpecClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
            val keyPropertiesClass = Class.forName("android.security.keystore.KeyProperties")
            val purposeSign = keyPropertiesClass.getField("PURPOSE_SIGN").getInt(null)
            val purposeVerify = keyPropertiesClass.getField("PURPOSE_VERIFY").getInt(null)
            val digestSha256 = keyPropertiesClass.getField("DIGEST_SHA256").get(null) as String

            val builder = keyGenSpecClass
                .getConstructor(String::class.java, Int::class.javaPrimitiveType)
                .newInstance(alias, purposeSign or purposeVerify)
            keyGenSpecClass.getMethod("setAlgorithmParameterSpec", java.security.spec.AlgorithmParameterSpec::class.java)
                .invoke(builder, ECGenParameterSpec(EC_CURVE))
            keyGenSpecClass.getMethod("setDigests", Array<String>::class.java)
                .invoke(builder, arrayOf(digestSha256))
            val spec = keyGenSpecClass.getMethod("build").invoke(builder) as java.security.spec.AlgorithmParameterSpec

            val kpg = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            kpg.initialize(spec)
            kpg.generateKeyPair()
        } catch (_: Throwable) {
            // Standard JCA EC P-256 fallback for JVM unit tests or environments without AndroidKeyStore
            generateEcKeyPair()
        }
    }

    fun parsePublicKeySpkiB64(spkiB64: String): PublicKey {
        val bytes = decodeB64(spkiB64)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(bytes))
    }

    fun parsePrivateKeyPkcs8B64(pkcs8B64: String): PrivateKey {
        val bytes = decodeB64(pkcs8B64)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    fun signEcdsa(privateKey: PrivateKey, transcript: ByteArray): String {
        val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
        sig.initSign(privateKey, secureRandom)
        sig.update(transcript)
        return encodeB64(sig.sign())
    }

    fun verifyEcdsa(publicKeySpkiB64: String, transcript: ByteArray, signatureB64: String): Boolean {
        if (publicKeySpkiB64.isBlank() || signatureB64.isBlank()) return false
        return try {
            val pubKey = parsePublicKeySpkiB64(publicKeySpkiB64)
            val sigBytes = decodeB64(signatureB64)
            val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
            sig.initVerify(pubKey)
            sig.update(transcript)
            sig.verify(sigBytes)
        } catch (_: Throwable) {
            false
        }
    }

    fun computeEcdhSharedSecret(privateKey: PrivateKey, peerPublicKeySpkiB64: String): ByteArray {
        val peerPub = parsePublicKeySpkiB64(peerPublicKeySpkiB64)
        val ka = KeyAgreement.getInstance(ECDH_ALGORITHM)
        ka.init(privateKey)
        ka.doPhase(peerPub, true)
        return ka.generateSecret()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * RFC 5869 HKDF-SHA256 Extract-and-Expand.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLengthBytes: Int = AES_KEY_LENGTH_BYTES): ByteArray {
        require(outputLengthBytes in 1..255 * 32) { "Invalid HKDF output length" }
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(actualSalt, ikm)
        var t = ByteArray(0)
        val okm = ByteBuffer.allocate(outputLengthBytes)
        var counter = 1
        while (okm.remaining() > 0) {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, okm.remaining())
            okm.put(t, 0, toCopy)
            counter++
        }
        return okm.array()
    }

    data class AesGcmCiphertext(
        val ivB64: String,
        val ciphertextAndTagB64: String
    )

    fun encryptAesGcm(key32Bytes: ByteArray, plaintext: ByteArray, aad: ByteArray): AesGcmCiphertext {
        require(key32Bytes.size == AES_KEY_LENGTH_BYTES) { "AES-256 key must be 32 bytes" }
        val iv = randomBytes(GCM_IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32Bytes, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        return AesGcmCiphertext(
            ivB64 = encodeB64(iv),
            ciphertextAndTagB64 = encodeB64(encrypted)
        )
    }

    fun decryptAesGcm(key32Bytes: ByteArray, ivB64: String, ciphertextAndTagB64: String, aad: ByteArray): ByteArray {
        if (key32Bytes.size != AES_KEY_LENGTH_BYTES) {
            throw GeneralSecurityException("Invalid AES-256 key length")
        }
        val iv = decodeB64(ivB64)
        if (iv.size != GCM_IV_LENGTH_BYTES) {
            throw GeneralSecurityException("Invalid GCM IV length")
        }
        val ciphertext = decodeB64(ciphertextAndTagB64)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key32Bytes, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Length-prefixed canonical transcript builder to prevent delimiter collision attacks.
     */
    fun canonicalTranscript(vararg fields: String): ByteArray {
        val utf8Fields = fields.map { it.toByteArray(StandardCharsets.UTF_8) }
        val totalSize = utf8Fields.sumOf { 4 + it.size }
        val buffer = ByteBuffer.allocate(totalSize)
        for (fieldBytes in utf8Fields) {
            buffer.putInt(fieldBytes.size)
            buffer.put(fieldBytes)
        }
        return buffer.array()
    }
}

class DeviceIdentity private constructor(
    val deviceId: String,
    val deviceName: String,
    private val signingKeyPair: KeyPair,
    private val kemKeyPair: KeyPair
) {
    val id: String get() = deviceId
    val publicKeyB64: String get() = signingPublicKeyB64
    val signingPublicKeyB64: String = Crypto.encodeB64(signingKeyPair.public.encoded)
    val kemPublicKeyB64: String = Crypto.encodeB64(kemKeyPair.public.encoded)
    val fingerprint: String = Crypto.publicKeyFingerprint(signingPublicKeyB64)
    val safetyNumber: String = Crypto.shortSafetyNumber(signingPublicKeyB64)

    fun sign(transcript: ByteArray): String =
        Crypto.signEcdsa(signingKeyPair.private, transcript)

    fun deriveSharedSecret(peerKemPublicKeyB64: String): ByteArray =
        Crypto.computeEcdhSharedSecret(kemKeyPair.private, peerKemPublicKeyB64)

    fun exportSerializableSecrets(): SerializedIdentity = SerializedIdentity(
        deviceId = deviceId,
        deviceName = deviceName,
        signingPubB64 = signingPublicKeyB64,
        signingPrivB64 = signingKeyPair.private.encoded?.let { Crypto.encodeB64(it) } ?: "",
        kemPubB64 = kemPublicKeyB64,
        kemPrivB64 = Crypto.encodeB64(kemKeyPair.private.encoded)
    )

    data class SerializedIdentity(
        val deviceId: String,
        val deviceName: String,
        val signingPubB64: String,
        val signingPrivB64: String,
        val kemPubB64: String,
        val kemPrivB64: String
    )

    companion object {
        fun generate(): DeviceIdentity = generateNew("Android-Device")

        fun loadOrCreate(file: File): DeviceIdentity {
            if (file.exists()) {
                runCatching {
                    val text = file.readText()
                    if (text.startsWith("{")) {
                        val obj = org.json.JSONObject(text)
                        val data = SerializedIdentity(
                            deviceId = obj.optString("deviceId"),
                            deviceName = obj.optString("deviceName"),
                            signingPubB64 = obj.optString("signingPubB64"),
                            signingPrivB64 = obj.optString("signingPrivB64"),
                            kemPubB64 = obj.optString("kemPubB64"),
                            kemPrivB64 = obj.optString("kemPrivB64")
                        )
                        return fromSerialized(data)
                    } else {
                        val lines = text.lines().filter { it.isNotBlank() }
                        if (lines.size >= 2) {
                            val signingPub = Crypto.parsePublicKeySpkiB64(lines[0])
                            val signingPriv = Crypto.parsePrivateKeyPkcs8B64(lines[1])
                            val kemKp = Crypto.generateEcKeyPair()
                            val devId = "dev_" + Crypto.publicKeyFingerprint(lines[0])
                            return DeviceIdentity(devId, "Android-Device", KeyPair(signingPub, signingPriv), kemKp)
                        }
                    }
                }
            }
            val identity = generateNew("Android-Device")
            file.parentFile?.mkdirs()
            val secrets = identity.exportSerializableSecrets()
            val json = org.json.JSONObject().apply {
                put("deviceId", secrets.deviceId)
                put("deviceName", secrets.deviceName)
                put("signingPubB64", secrets.signingPubB64)
                put("signingPrivB64", secrets.signingPrivB64)
                put("kemPubB64", secrets.kemPubB64)
                put("kemPrivB64", secrets.kemPrivB64)
            }.toString()
            file.writeText(json)
            return identity
        }

        fun generateNew(deviceName: String, keyStoreAlias: String? = null): DeviceIdentity {
            val signingKp = if (!keyStoreAlias.isNullOrBlank()) {
                Crypto.loadOrGenerateAndroidKeyStoreSigningKey(keyStoreAlias)
            } else {
                Crypto.generateEcKeyPair()
            }
            val kemKp = Crypto.generateEcKeyPair()
            val pubSpkiB64 = Crypto.encodeB64(signingKp.public.encoded)
            val derivedDeviceId = "dev_" + Crypto.publicKeyFingerprint(pubSpkiB64)
            return DeviceIdentity(
                deviceId = derivedDeviceId,
                deviceName = deviceName.trim().ifEmpty { "Android-Dialer" },
                signingKeyPair = signingKp,
                kemKeyPair = kemKp
            )
        }

        fun fromSerialized(data: SerializedIdentity): DeviceIdentity {
            val signingPub = Crypto.parsePublicKeySpkiB64(data.signingPubB64)
            val signingPriv = Crypto.parsePrivateKeyPkcs8B64(data.signingPrivB64)
            val kemPub = Crypto.parsePublicKeySpkiB64(data.kemPubB64)
            val kemPriv = Crypto.parsePrivateKeyPkcs8B64(data.kemPrivB64)
            val expectedDeviceId = "dev_" + Crypto.publicKeyFingerprint(data.signingPubB64)
            require(data.deviceId == expectedDeviceId) {
                "DeviceId does not match public key fingerprint"
            }
            return DeviceIdentity(
                deviceId = data.deviceId,
                deviceName = data.deviceName,
                signingKeyPair = KeyPair(signingPub, signingPriv),
                kemKeyPair = KeyPair(kemPub, kemPriv)
            )
        }
    }
}
