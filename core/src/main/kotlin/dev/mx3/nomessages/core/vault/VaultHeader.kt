package dev.mx3.nomessages.core.vault

import dev.mx3.nomessages.core.crypto.Crypto
import java.nio.ByteBuffer
import java.security.MessageDigest

internal class UnlockedHeader(val slot: VaultSlot, val epoch: Long, val keys: VaultKeys, val authKey: ByteArray, val wrappingKey: ByteArray) : AutoCloseable {
    override fun close() { keys.close(); authKey.fill(0); wrappingKey.fill(0) }
}

internal object VaultHeader {
    private const val MAGIC = 0x4E4D5347 // "NMSG" vault/header magic.
    private const val PREFIX_SIZE = 4 + 2 + 4 + 4 + 4 + 8 + 16 + 16
    private const val WRAP_SIZE = 128 + 40
    const val SIZE = PREFIX_SIZE + 2 * WRAP_SIZE + 32

    fun requireStructure(encoded: ByteArray) {
        if (encoded.size != SIZE) throw SecurityException("Invalid vault")
        val buffer = ByteBuffer.wrap(encoded)
        if (buffer.int != MAGIC || buffer.short.toInt() != 1) throw SecurityException("Invalid vault")
        try { KdfParams(buffer.int, buffer.int) } catch (_: IllegalArgumentException) { throw SecurityException("Invalid vault") }
        if (buffer.int != 1 || buffer.long < 0) throw SecurityException("Invalid vault")
    }

    fun create(crypto: Crypto, params: KdfParams, epoch: Long, realPassword: ByteArray, panicPassword: ByteArray,
               real: VaultKeys, decoy: VaultKeys): ByteArray {
        val salts = listOf(crypto.random(16), crypto.random(16))
        val prefix = ByteBuffer.allocate(PREFIX_SIZE).putInt(MAGIC).putShort(1).putInt(params.memoryKiB)
            .putInt(params.iterations).putInt(1).putLong(epoch).put(salts[0]).put(salts[1]).array()
        val authKey = crypto.random(32)
        val keys = listOf(real, decoy)
        val passwords = listOf(realPassword, panicPassword)
        val wraps = ArrayList<ByteArray>(2)
        try {
            for (i in 0..1) {
                val mk = crypto.derive(passwords[i], salts[i], params.memoryKiB, params.iterations)
                val bundle = ByteBuffer.allocate(128).put(keys[i].dbKey).put(keys[i].fileKey).put(keys[i].identityKey).put(authKey).array()
                try { wraps += crypto.seal(mk, bundle, prefix + i.toByte()) }
                finally { crypto.wipe(mk); crypto.wipe(bundle) }
            }
            check(wraps.all { it.size == WRAP_SIZE })
            val body = prefix + wraps[0] + wraps[1]
            return body + crypto.mac(authKey, body)
        } finally { crypto.wipe(authKey) }
    }

    /**
     * The Argon2id cost parameters recorded in the header prefix, readable without any password:
     * [requireStructure] already parses and discards a [KdfParams] just to validate the header
     * shape, and [unlock]/[resetPanic] both read the same two ints at the same offset before any
     * derivation. Exists so [VaultManager.fakePanicPasswordChange] can pay the *real* calibrated
     * KDF cost for a decoy-slot "change panic password" without ever opening a session — see
     * `security-model.md` ("Oráculos de isca", T4.7).
     */
    fun kdfParams(encoded: ByteArray): KdfParams {
        requireStructure(encoded)
        val buffer = ByteBuffer.wrap(encoded)
        buffer.position(6)
        return KdfParams(buffer.int, buffer.int)
    }

    fun unlock(crypto: Crypto, encoded: ByteArray, password: ByteArray): UnlockedHeader {
        if (encoded.size != SIZE) throw SecurityException("Unable to unlock vault")
        val buffer = ByteBuffer.wrap(encoded)
        if (buffer.int != MAGIC || buffer.short.toInt() != 1) throw SecurityException("Unable to unlock vault")
        val params = try { KdfParams(buffer.int, buffer.int) } catch (_: IllegalArgumentException) { throw SecurityException("Unable to unlock vault") }
        if (buffer.int != 1) throw SecurityException("Unable to unlock vault")
        val epoch = buffer.long
        if (epoch < 0) throw SecurityException("Unable to unlock vault")
        val salts = List(2) { ByteArray(16).also(buffer::get) }
        val wraps = List(2) { ByteArray(WRAP_SIZE).also(buffer::get) }
        val mac = ByteArray(32).also(buffer::get)
        val prefix = encoded.copyOfRange(0, PREFIX_SIZE)
        val masters = arrayOfNulls<ByteArray>(2)
        val opened = arrayOfNulls<ByteArray>(2)
        val valid = BooleanArray(2)
        var derivationFailed = false
        try {
            // Do not short circuit: each password attempt pays both KDFs even on a native allocation error.
            for (i in 0..1) {
                try { masters[i] = crypto.derive(password, salts[i], params.memoryKiB, params.iterations) }
                catch (_: Exception) { derivationFailed = true }
            }
            for (i in 0..1) {
                val mk = masters[i] ?: ByteArray(32)
                try { opened[i] = crypto.open(mk, wraps[i], prefix + i.toByte()) }
                catch (_: Exception) { opened[i] = null }
                val bundle = opened[i]
                val authKey = if (bundle?.size == 128) bundle.copyOfRange(96, 128) else ByteArray(32)
                try {
                    val expected = crypto.mac(authKey, encoded.copyOfRange(0, SIZE - 32))
                    valid[i] = MessageDigest.isEqual(expected, mac) && bundle?.size == 128
                } finally { crypto.wipe(authKey) }
            }
            if (derivationFailed || valid.count { it } != 1) throw SecurityException("Unable to unlock vault")
            val index = if (valid[0]) 0 else 1
            val material = opened[index]!!
            return UnlockedHeader(VaultSlot.entries[index], epoch,
                VaultKeys(material.copyOfRange(0, 32), material.copyOfRange(32, 64), material.copyOfRange(64, 96)),
                material.copyOfRange(96, 128), masters[index]!!.copyOf())
        } finally {
            masters.filterNotNull().forEach(crypto::wipe)
            opened.filterNotNull().forEach(crypto::wipe)
        }
    }

    fun resetPanic(crypto: Crypto, encoded: ByteArray, session: VaultSession, newPassword: ByteArray, newKeys: VaultKeys): ByteArray {
        check(!session.isClosed && session.slot == VaultSlot.REAL)
        val body = encoded.copyOfRange(0, SIZE - 32)
        if (!MessageDigest.isEqual(crypto.mac(session.authKey, body), encoded.copyOfRange(SIZE - 32, SIZE))) {
            throw SecurityException("Vault changed during session")
        }
        val prefix = encoded.copyOfRange(0, PREFIX_SIZE)
        val buffer = ByteBuffer.wrap(prefix)
        buffer.position(6)
        val params = KdfParams(buffer.int, buffer.int)
        buffer.position(26)
        val realSalt = ByteArray(16).also(buffer::get)
        val samePasswordKey = crypto.derive(newPassword, realSalt, params.memoryKiB, params.iterations)
        try { require(!MessageDigest.isEqual(samePasswordKey, session.wrappingKey)) { "Passwords must be distinct" } }
        finally { crypto.wipe(samePasswordKey) }
        val newSalt = crypto.random(16)
        newSalt.copyInto(prefix, 42)
        val newMaster = crypto.derive(newPassword, newSalt, params.memoryKiB, params.iterations)
        try {
            fun wrap(keys: VaultKeys, master: ByteArray, index: Int): ByteArray {
                val plain = ByteBuffer.allocate(128).put(keys.dbKey).put(keys.fileKey).put(keys.identityKey).put(session.authKey).array()
                try { return crypto.seal(master, plain, prefix + index.toByte()) } finally { crypto.wipe(plain) }
            }
            val updated = prefix + wrap(session.keys, session.wrappingKey, 0) + wrap(newKeys, newMaster, 1)
            return updated + crypto.mac(session.authKey, updated)
        } finally { crypto.wipe(newMaster) }
    }
}
