package dev.mx3.nomessages.runtime

import java.nio.ByteBuffer

/**
 * What this vault remembers about ringing one contact's doorbell (T4.17).
 *
 * [lastAttempt] is the wall clock of the last knock that was **started**, not of the last one that
 * was confirmed. Recording the attempt is what makes the rate limit hold even when the knock fails,
 * which is the common case: a contact whose messaging onion is unreachable is usually a contact
 * whose doorbell is unreachable too, and an attempt that is only recorded on success would let a
 * dead peer be knocked at every outbox tick forever.
 *
 * [failures] counts knocks that came back unconfirmed since the last sign of life. It only ever
 * stretches the interval; it never shortens it.
 */
internal data class DoorbellKnockState(val lastAttempt: Long, val failures: Int) {
    init { require(failures >= 0) { "Contador de falhas inválido" } }

    /** The state to persist the moment a knock is dispatched. */
    fun attempted(now: Long): DoorbellKnockState = copy(lastAttempt = now)

    /** A knock the peer acknowledged: it is awake, so the schedule returns to the 10 min floor. */
    fun confirmed(): DoorbellKnockState = copy(failures = 0)

    /** A knock that was not acknowledged. Saturates instead of overflowing into a negative count. */
    fun failed(): DoorbellKnockState = copy(failures = if (failures == Int.MAX_VALUE) failures else failures + 1)
}

/**
 * When this device may ring a contact's doorbell again.
 *
 * Pure Kotlin on purpose, exactly like [TransportSupervisor]: no Android, no database, no Tor, no
 * wall clock and no coroutine. `now` is a parameter, so the entire schedule - floor, growth and
 * ceiling - is exercised by JVM unit tests with no mocks and no `Thread.sleep`.
 *
 * The schedule exists because a knock is expensive and loud. Each attempt costs the native side up
 * to 240 s of circuit building (`TorConnection.doorbellKnock`), and each one tells whoever is
 * watching the doorbell onion that somebody has traffic for its owner. The outbox, meanwhile,
 * retries a queued frame every 30 s: without a schedule of its own, one unreachable contact would
 * produce a knock per retry.
 */
internal object DoorbellKnockPolicy {
    /** `opaque_blobs` namespace of this schedule; the key is `ContactRecord.id`. */
    const val NAMESPACE = "doorbell_knocks"

    /**
     * Length of `contacts.doorbell_token`. A contact row whose token is any other length was
     * written before schema v3/v4 and has no doorbell agreed with this device at all.
     */
    const val TOKEN_BYTES = 32

    /** Serialised size of one [DoorbellKnockState]: big-endian `long` epoch millis plus `int` count. */
    const val ENCODED_BYTES = 12

    /**
     * 10, 20, 40, 80 and then a 120 min ceiling.
     *
     * The first step is the floor the feature requires - at most one knock per contact per 10 min -
     * and it is also what an *acknowledged* knock returns to, because a peer that answered is a peer
     * that can be woken again cheaply. The doubling covers the case the doorbell exists for: a
     * device that has been off for hours. Bounded at two hours so an unattended sender still wakes
     * a peer that comes back the next morning within one ceiling, instead of converging on a delay
     * longer than the outage itself.
     */
    val BACKOFF_MILLIS: List<Long> = listOf(10L, 20L, 40L, 80L, 120L).map { it * 60_000L }

    /** The floor every schedule starts at, and the value an acknowledged knock resets to. */
    const val FLOOR_MILLIS = 10 * 60_000L

    /** The ceiling every long outage converges to. */
    const val MAX_BACKOFF_MILLIS = 120 * 60_000L

    /** Interval required after a knock that was followed by [failures] unconfirmed ones. */
    fun delayFor(failures: Int): Long {
        require(failures >= 0) { "Contador de falhas inválido" }
        return BACKOFF_MILLIS[minOf(failures, BACKOFF_MILLIS.size - 1)]
    }

    /** Wall clock from which the next knock to this contact is allowed. */
    fun nextEligible(state: DoorbellKnockState): Long = state.lastAttempt + delayFor(state.failures)

    /**
     * Whether a knock may be dispatched now. A contact with no record has never been knocked and is
     * always eligible.
     *
     * A record stamped in the future is treated as eligible rather than as a very long wait: the
     * clock moved backwards (a timezone/NTP correction, or a restored vault), and the alternative
     * is a contact that can never be woken again until the clock catches up. It costs at most one
     * extra knock, because dispatching one rewrites the stamp with the current clock.
     */
    fun ready(state: DoorbellKnockState?, now: Long): Boolean {
        if (state == null) return true
        if (now < state.lastAttempt) return true
        return now - state.lastAttempt >= delayFor(state.failures)
    }

    fun encode(state: DoorbellKnockState): ByteArray =
        ByteBuffer.allocate(ENCODED_BYTES).putLong(state.lastAttempt).putInt(state.failures).array()

    /**
     * Reads back a stored schedule, or null for anything this version cannot interpret (an absent
     * row, or a row of another size). Null means "never knocked", i.e. the contact is eligible: a
     * corrupt schedule must not be able to silence the doorbell permanently.
     */
    fun decode(bytes: ByteArray?): DoorbellKnockState? {
        if (bytes == null || bytes.size != ENCODED_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        return DoorbellKnockState(buffer.long, maxOf(0, buffer.int))
    }
}
