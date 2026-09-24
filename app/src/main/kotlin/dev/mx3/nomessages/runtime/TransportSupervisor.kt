package dev.mx3.nomessages.runtime

import dev.mx3.nomessages.ui.NetworkStatus
import kotlinx.coroutines.CancellationException

/** What one activation attempt reported back to the supervisor. */
internal enum class TransportAttempt { ONLINE, FAILED }

/**
 * Activation and reconnection policy for the onion transport.
 *
 * Pure Kotlin on purpose: no Android, no Tor, no wall clock, no dispatcher. Everything that touches
 * the outside world - running one attempt, holding an online transport, sleeping between attempts
 * and publishing a status - is injected, so the whole state machine is exercised by JVM unit tests.
 */
internal class TransportSupervisor(
    private val onStatus: (NetworkStatus) -> Unit,
    private val sleep: suspend (Long) -> Unit,
    private val backoff: List<Long> = DEFAULT_BACKOFF_MILLIS,
) {
    init {
        require(backoff.isNotEmpty() && backoff.all { it > 0 }) { "Backoff inválido" }
        require(backoff.last() <= MAX_BACKOFF_MILLIS) { "Backoff acima do teto" }
    }

    private var failures = 0

    /** Last status handed to [onStatus]. OFF once the session is cancelled by the lock. */
    var status: NetworkStatus = NetworkStatus.OFF
        private set

    /** Consecutive failures since the transport was last reachable. */
    val failureCount: Int get() = failures

    /** Delay before the [failure]-th (1-based) retry; the last step is the ceiling. */
    fun delayFor(failure: Int): Long {
        require(failure >= 1) { "Tentativa inválida" }
        return backoff[minOf(failure, backoff.size) - 1]
    }

    /** Published from inside an attempt: the client bootstrapped, the descriptor is not out yet. */
    fun publishing() = emit(NetworkStatus.PUBLISHING)

    /**
     * The counterpart of [publishing], published from inside [supervise]'s hold: the onion is
     * reachable again. Reachability is reversible - tor-hsservice can fall back to publishing long
     * after the first descriptor went out - so without this the banner would keep claiming a live
     * connection for the rest of the session once it had been online a single time.
     */
    fun reachable() = emit(NetworkStatus.ONLINE)

    /**
     * Drives activation until cancelled: STARTING, then ONLINE for as long as [hold] keeps the
     * transport, then RETRYING with a bounded exponential backoff after every failure. A transport
     * lost while online is replaced immediately, because a fresh outage is not a repeated failure.
     *
     * [attempt] and [hold] own their errors: they must map a failure to [TransportAttempt.FAILED]
     * or to a plain return instead of throwing. That includes coroutine timeouts, which arrive as a
     * cancellation subclass and would otherwise be mistaken here for the lock tearing the session
     * down. Only cancellation ends this loop, and it leaves the status at OFF so a torn-down
     * session never leaves a stale banner behind.
     */
    suspend fun supervise(attempt: suspend () -> TransportAttempt, hold: suspend () -> Unit) {
        try {
            emit(NetworkStatus.STARTING)
            while (true) {
                if (attempt() == TransportAttempt.ONLINE) {
                    failures = 0
                    emit(NetworkStatus.ONLINE)
                    hold()
                    emit(NetworkStatus.RETRYING)
                } else {
                    failures++
                    emit(NetworkStatus.RETRYING)
                    sleep(delayFor(failures))
                }
            }
        } catch (cancelled: CancellationException) {
            emit(NetworkStatus.OFF)
            throw cancelled
        }
    }

    private fun emit(next: NetworkStatus) {
        if (next == status) return
        status = next
        onStatus(next)
    }

    companion object {
        /**
         * 5 s, 10 s, 20 s, 60 s, 120 s and then a five minute ceiling. Bounded on purpose: a device
         * carried out of coverage must not spin the radio, and an unattended one must still recover
         * by itself without an unlock.
         */
        val DEFAULT_BACKOFF_MILLIS = listOf(5_000L, 10_000L, 20_000L, 60_000L, 120_000L, 300_000L)

        /** The ceiling every long outage converges to. */
        const val MAX_BACKOFF_MILLIS = 300_000L
    }
}
