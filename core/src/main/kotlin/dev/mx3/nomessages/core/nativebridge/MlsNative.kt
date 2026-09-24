package dev.mx3.nomessages.core.nativebridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class MlsKind { KEY_PACKAGE, GROUP_CREATED, COMMIT, APPLICATION }
data class MlsMember(val identity: ByteArray, val signatureKey: ByteArray, val leafIndex: Int) {
    override fun toString(): String = "MlsMember(leafIndex=$leafIndex)"
}
data class MlsResult(
    val state: ByteArray, val groupId: ByteArray, val message: ByteArray,
    val welcome: ByteArray, val application: ByteArray, val senderIdentity: ByteArray,
    val members: List<MlsMember>, val kind: MlsKind,
) {
    fun wipe() { state.fill(0); application.fill(0) }
    override fun toString(): String = "MlsResult(kind=$kind, memberCount=${members.size})"
}

/** Synchronous, stateless MLS transactions. Persist state + outbox atomically before transmitting. */
object MlsNative {
    init { System.loadLibrary("nomessages") }
    private const val MAX_STATE = 16 * 1024 * 1024
    private const val MAX_FRAME = 12 * 1024 * 1024
    private external fun transact(operation: Int, state: ByteArray, arguments: ByteArray): ByteArray

    fun keyPackage(identitySeed32: ByteArray): MlsResult {
        require(identitySeed32.size == 32)
        return call(0, byteArrayOf()) { bytes(identitySeed32) }
    }
    fun create(state: ByteArray): MlsResult = call(1, state) {}
    fun add(state: ByteArray, keyPackages: List<ByteArray>, expectedMembers: List<ByteArray>): MlsResult =
        call(2, state) { list(keyPackages, MAX_FRAME, 99); identities(expectedMembers) }
    fun join(state: ByteArray, welcome: ByteArray, expectedMembers: List<ByteArray>, coordinatorIdentity: ByteArray): MlsResult =
        call(3, state) { bytes(welcome); identities(expectedMembers); identity(coordinatorIdentity) }
    fun encrypt(state: ByteArray, plaintext: ByteArray): MlsResult = call(4, state) { bytes(plaintext) }
    fun process(state: ByteArray, message: ByteArray, expectedMembers: List<ByteArray>, coordinatorIdentity: ByteArray): MlsResult =
        call(5, state) { bytes(message); identities(expectedMembers); identity(coordinatorIdentity) }
    fun remove(state: ByteArray, removeIdentities: List<ByteArray>, expectedMembers: List<ByteArray>): MlsResult =
        call(6, state) { identities(removeIdentities); identities(expectedMembers) }

    private class Arguments : ByteArrayOutputStream() {
        private val writer = DataOutputStream(this)
        fun bytes(value: ByteArray) {
            require(value.size <= MAX_FRAME && count + value.size + 4 <= MAX_STATE)
            writer.writeInt(value.size); writer.write(value)
        }
        fun identity(value: ByteArray) { require(value.size == 32); bytes(value) }
        fun identities(values: List<ByteArray>) { require(values.size <= 100 && values.all { it.size == 32 }); list(values, 32, 100) }
        fun list(values: List<ByteArray>, maxSize: Int, maxCount: Int) {
            require(values.size <= maxCount && values.all { it.size <= maxSize })
            writer.writeInt(values.size); values.forEach(::bytes)
        }
        fun wipe() { buf.fill(0); reset() }
    }
    private fun call(operation: Int, state: ByteArray, build: Arguments.() -> Unit): MlsResult {
        require(state.size <= MAX_STATE)
        val builder = Arguments()
        var args: ByteArray? = null
        var encoded: ByteArray? = null
        val decoded = ArrayList<ByteArray>()
        var completed = false
        try {
            builder.build(); args = builder.toByteArray()
            encoded = transact(operation, state, args)
            require(encoded.size <= MAX_STATE + 3 * MAX_FRAME)
            val input = DataInputStream(ByteArrayInputStream(encoded))
            val kind = MlsKind.entries.getOrNull(input.readInt()) ?: error("Invalid MLS response")
            fun field(cap: Int): ByteArray {
                val size = input.readInt(); require(size in 0..cap && size <= input.available())
                return ByteArray(size).also { decoded += it; input.readFully(it) }
            }
            val newState = field(MAX_STATE); val group = field(256); val message = field(MAX_FRAME)
            val welcome = field(MAX_FRAME); val application = field(MAX_FRAME); val sender = field(32)
            val count = input.readInt(); require(count in 1..100)
            val members = List(count) { MlsMember(field(32), field(32), input.readInt()) }
            require(input.available() == 0)
            return MlsResult(newState, group, message, welcome, application, sender, members, kind).also { completed = true }
        } finally {
            if (!completed) decoded.forEach { it.fill(0) }
            builder.wipe(); args?.fill(0); encoded?.fill(0)
        }
    }
}
