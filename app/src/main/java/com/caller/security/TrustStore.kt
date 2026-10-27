package com.caller.security

import java.io.File

enum class TrustStatus { TRUSTED, REVOKED }

/** [id] must equal the fingerprint of [publicKey]; enforced on construction. */
data class TrustedDevice(
    val id: String,
    val name: String,
    val publicKey: String,
    val status: TrustStatus,
    val addedAt: Long,
) {
    init {
        val pub = Crypto.publicKeyFromEncoded(Crypto.unb64(publicKey))
        require(id == Crypto.fingerprint(pub)) { "device id does not match its public key" }
    }
}

interface TrustStore {
    fun get(id: String): TrustedDevice?
    fun put(device: TrustedDevice)
    fun revoke(id: String): Boolean
    fun all(): List<TrustedDevice>
}

class InMemoryTrustStore : TrustStore {
    private val map = LinkedHashMap<String, TrustedDevice>()
    @Synchronized override fun get(id: String): TrustedDevice? = map[id]
    @Synchronized override fun put(device: TrustedDevice) { map[device.id] = device }
    @Synchronized override fun revoke(id: String): Boolean {
        val d = map[id] ?: return false
        map[id] = d.copy(status = TrustStatus.REVOKED)
        return true
    }
    @Synchronized override fun all(): List<TrustedDevice> = map.values.toList()
}

/** Persistent store. Corrupt lines are skipped, so a damaged entry fails closed (device denied). */
class FileTrustStore(private val file: File) : TrustStore {
    private val map = LinkedHashMap<String, TrustedDevice>()

    init {
        if (file.exists()) {
            for (line in file.readLines()) {
                val p = line.split('\t')
                if (p.size != 5) continue
                val dev = runCatching {
                    TrustedDevice(
                        p[0],
                        String(Crypto.unb64(p[1]), Charsets.UTF_8),
                        p[2],
                        TrustStatus.valueOf(p[3]),
                        p[4].toLong(),
                    )
                }.getOrNull() ?: continue
                map[dev.id] = dev
            }
        }
    }

    @Synchronized override fun get(id: String): TrustedDevice? = map[id]

    @Synchronized override fun put(device: TrustedDevice) {
        map[device.id] = device
        save()
    }

    @Synchronized override fun revoke(id: String): Boolean {
        val d = map[id] ?: return false
        map[id] = d.copy(status = TrustStatus.REVOKED)
        save()
        return true
    }

    @Synchronized override fun all(): List<TrustedDevice> = map.values.toList()

    private fun save() {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(
            map.values.joinToString("\n", postfix = "\n") {
                listOf(it.id, Crypto.b64(it.name.toByteArray(Charsets.UTF_8)), it.publicKey, it.status.name, it.addedAt.toString())
                    .joinToString("\t")
            },
        )
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
