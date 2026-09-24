package dev.mx3.nomessages.core.nativebridge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

data class TorFrame(val connectionId: Long, val payload: ByteArray) {
    override fun toString(): String = "TorFrame(connectionId=$connectionId, payloadBytes=${payload.size})"
}

/** Call only from the dedicated :tor service, except address(), which opens no sockets. */
object TorNative {
    init { System.loadLibrary("nomessages") }
    private const val MAX_FRAME = 4 + 16 * 1024
    /** Mirrors the native readiness cap; see `native/src/tor.rs`. */
    const val MAX_READY_TIMEOUT_MILLIS = 300_000
    /** Doorbell token bounds, mirroring `MIN_TOKEN_LEN`/`MAX_TOKEN_LEN` in `native/src/doorbell.rs`. */
    const val MIN_DOORBELL_TOKEN = 16
    const val MAX_DOORBELL_TOKEN = 64
    /** Mirrors the native `MAX_TOKENS`; one token per paired contact. */
    const val MAX_DOORBELL_TOKENS = 256
    private external fun transact(operation: Int, arguments: ByteArray): ByteArray

    fun address(seed32: ByteArray): String {
        require(seed32.size == 32)
        return call(0) { bytes(seed32) }.toString(Charsets.US_ASCII)
    }
    fun start(seed32: ByteArray, stateDir: String, cacheDir: String, bridgeLines: Array<String> = emptyArray()): String {
        require(seed32.size == 32 && bridgeLines.size <= 16)
        return call(1) {
            bytes(seed32); bytes(stateDir.toByteArray(Charsets.UTF_8)); bytes(cacheDir.toByteArray(Charsets.UTF_8))
            int(bridgeLines.size); bridgeLines.forEach { require(it.length <= 1024); bytes(it.toByteArray(Charsets.UTF_8)) }
        }.toString(Charsets.US_ASCII)
    }
    fun onion(): String = call(2).toString(Charsets.US_ASCII)
    /** BOOTSTRAPPING, PUBLISHING, READY or STOPPED. Only READY means the onion descriptor is published. */
    fun status(): String = call(3).toString(Charsets.US_ASCII)
    /**
     * Blocks until the onion descriptor is published, at most [timeoutMillis] (300s cap, independent
     * of the bootstrap budget). Returns the status reached: PUBLISHING means the deadline elapsed
     * while publication is still in progress, which is not a failure and may be awaited again.
     */
    fun awaitReady(timeoutMillis: Int = MAX_READY_TIMEOUT_MILLIS): String {
        require(timeoutMillis in 0..MAX_READY_TIMEOUT_MILLIS)
        return call(9) { int(timeoutMillis) }.toString(Charsets.US_ASCII)
    }
    fun send(onion: String, frame: ByteArray): Long {
        require(onion.length == 62 && frame.isNotEmpty() && frame.size <= MAX_FRAME)
        val result = call(4) { bytes(onion.toByteArray(Charsets.US_ASCII)); bytes(frame) }
        require(result.size == 8); return ByteBuffer.wrap(result).long
    }
    fun poll(timeoutMillis: Int): TorFrame? {
        require(timeoutMillis in 0..30000)
        val result = call(5) { int(timeoutMillis) }
        if (result.isEmpty()) return null
        require(result.size in 9..MAX_FRAME + 8)
        return TorFrame(ByteBuffer.wrap(result, 0, 8).long, result.copyOfRange(8, result.size))
    }
    /** Waits for the frame to flush on the existing connection (40s maximum, cancellable by stop). */
    fun reply(connectionId: Long, frame: ByteArray) {
        require(connectionId > 0 && frame.isNotEmpty() && frame.size <= MAX_FRAME)
        call(6) { long(connectionId); bytes(frame) }
    }
    fun closeConnection(connectionId: Long) { require(connectionId > 0); call(7) { long(connectionId) } }
    fun stop() { call(8) }

    // ---------------------------------------------------------------------------------------
    // Doorbell (T4.17), opcodes 10-16.
    //
    // Appended after `awaitReady`, never interleaved, exactly like the native side: every opcode
    // from 0 to 9 keeps its number and its wire shape. The doorbell is a second onion service
    // living on the SAME Arti host as the messaging transport, so ownership of that host is what
    // dictates the call order documented on each function below.
    // ---------------------------------------------------------------------------------------

    /**
     * Starts the doorbell onion service (or returns the address of the one already running for
     * this same seed) and returns its 62-character address.
     *
     * Same argument shape as [start] because the doorbell may have to build the Arti host itself
     * when the messaging transport is not running; when a host already exists it is reused and the
     * directories/bridges are ignored by the native side.
     */
    fun doorbellStart(seed32: ByteArray, stateDir: String, cacheDir: String, bridgeLines: Array<String> = emptyArray()): String {
        require(seed32.size == 32 && bridgeLines.size <= 16)
        return call(10) {
            bytes(seed32); bytes(stateDir.toByteArray(Charsets.UTF_8)); bytes(cacheDir.toByteArray(Charsets.UTF_8))
            int(bridgeLines.size); bridgeLines.forEach { require(it.length <= 1024); bytes(it.toByteArray(Charsets.UTF_8)) }
        }.toString(Charsets.US_ASCII)
    }

    /** Address of the running doorbell; throws when it is not running, which is also how a caller tests it. */
    fun doorbellOnion(): String = call(11).toString(Charsets.US_ASCII)

    /**
     * Replaces the whole set of accepted knock tokens.
     *
     * The tokens are opaque here: this layer never derives, compares or logs them, it only bounds
     * their size. An empty list is legal and means "accept nobody", which is what a vault with no
     * paired contact loads - the service still runs, so no observer can tell the two cases apart.
     */
    fun doorbellTokens(tokens: List<ByteArray>) {
        require(tokens.size <= MAX_DOORBELL_TOKENS && tokens.all { it.size in MIN_DOORBELL_TOKEN..MAX_DOORBELL_TOKEN })
        call(12) { int(tokens.size); tokens.forEach { bytes(it) } }
    }

    /**
     * Blocks up to [timeoutMillis] for accepted knocks and returns how many were drained since the
     * previous poll; 0 means nothing rang. `0` as a timeout is a non-blocking drain.
     *
     * No identity, no timestamp and no token ever comes back - only a count, which is all the
     * notification needs and the most an observer of this process can learn.
     */
    fun doorbellPoll(timeoutMillis: Int): Int {
        require(timeoutMillis in 0..30000)
        val result = call(13) { int(timeoutMillis) }
        if (result.isEmpty()) return 0
        require(result.size == 4)
        val count = ByteBuffer.wrap(result).int
        // The native counter is a u32; anything that does not fit an Int still means "somebody rang".
        return if (count <= 0) 1 else count
    }

    /**
     * Tears the MESSAGING service down (same effect as [stop]) while the doorbell keeps serving on
     * the shared host. Fails unless the doorbell is already running, so the only correct order is
     * [doorbellStart] (plus [doorbellTokens]) and only then this.
     */
    fun doorbellMinimal() { call(14) }

    /**
     * Stops the doorbell only.
     *
     * When the doorbell is the sole owner of the shared Arti host, this also disposes of the
     * runtime and the client. So on unlock the order is inverted and never reversed: [start] first,
     * which adopts the still-live host and skips the 180 s bootstrap, and this afterwards.
     */
    fun doorbellStop() { call(15) }

    /**
     * Rings [onion]'s doorbell with [token] - the token THAT PEER issued to this device - and
     * reports whether it was acknowledged. The frame (version, timestamp, nonce and HMAC) is built
     * and verified natively; this layer only forwards the destination and the raw token.
     */
    fun doorbellKnock(onion: String, token: ByteArray): Boolean {
        require(onion.length == 62 && token.size in MIN_DOORBELL_TOKEN..MAX_DOORBELL_TOKEN)
        val result = call(16) { bytes(onion.toByteArray(Charsets.US_ASCII)); bytes(token) }
        require(result.size == 1)
        return result[0].toInt() == 1
    }

    // Never grow a buffer containing the onion seed: BAOS growth would leave a retired copy.
    private class Arguments : ByteArrayOutputStream(32 * 1024) {
        private val out = DataOutputStream(this)
        fun bytes(value: ByteArray) { require(value.size <= MAX_FRAME && count + 4 + value.size <= buf.size); out.writeInt(value.size); out.write(value) }
        fun int(value: Int) { require(count + 4 <= buf.size); out.writeInt(value) }
        fun long(value: Long) { require(count + 8 <= buf.size); out.writeLong(value) }
        fun wipe() { buf.fill(0); reset() }
    }
    private fun call(operation: Int, build: Arguments.() -> Unit = {}): ByteArray {
        val builder = Arguments(); var arguments: ByteArray? = null
        try { builder.build(); arguments = builder.toByteArray(); return transact(operation, arguments) }
        finally { builder.wipe(); arguments?.fill(0) }
    }
}
