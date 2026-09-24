package dev.mx3.nomessages.runtime

/**
 * When the pairing key-bundle fetch (T4.16) may try again, and with how much budget (T4.18).
 *
 * Pure Kotlin on purpose, exactly like [TransportSupervisor] and [DoorbellKnockPolicy]: no Android,
 * no Tor, no coroutine and no wall clock. `now` is a parameter, so the whole retry window - the
 * deadline test, the per-attempt budget and the pause between attempts - is exercised by JVM unit
 * tests with no mocks and no `Thread.sleep`.
 *
 * The single deadline every function here is measured against is `PairingProgress.expiresAt`, which
 * is `PairingEngine.CONFIRMATION_TTL_SECONDS` (**240 s**, not 300 s) from the moment the exchange
 * was staged. That is not a budget this policy may widen: `PairingEngine.get` applies the very same
 * ceiling independently, so an attempt started after it would be refused by the pairing engine even
 * if the loop still allowed it. Retrying past the deadline can only produce traffic that cannot be
 * accepted any more.
 */
internal object BundleFetchPolicy {
    /**
     * Budget for one attempt. A circuit to a fresh onion service commonly costs 5-40 s; 60 s per
     * attempt leaves room for a slow one while still fitting several attempts inside the 240 s
     * exchange deadline.
     */
    const val ATTEMPT_BUDGET_MILLIS = 60_000L

    /** Pause between attempts, so a peer whose onion is not published yet is not hammered. */
    const val RETRY_DELAY_MILLIS = 3_000L

    /**
     * Whether another attempt may still be started at [now].
     *
     * The deadline is **exclusive**: `now == deadlineAt` is already over, matching
     * `PairingEngine.get`, which refuses at `now() >= p.expires`. An attempt started exactly on the
     * boundary could never be accepted, so it is not started.
     */
    fun shouldAttempt(deadlineAt: Long, now: Long): Boolean = now < deadlineAt

    /**
     * Timeout to hand to one `fetchPeerBundle` call started at [now]: never more than
     * [ATTEMPT_BUDGET_MILLIS], never past the deadline, and never negative. `0` means the window is
     * closed and the caller must stop rather than issue a request that cannot be honoured.
     */
    fun attemptBudget(deadlineAt: Long, now: Long): Long =
        (deadlineAt - now).coerceIn(0L, ATTEMPT_BUDGET_MILLIS)

    /**
     * How long to wait after a failed attempt, or `null` when the remaining window is too short for
     * the pause plus another attempt.
     *
     * Returning `null` instead of sleeping into the deadline is what makes the failure banner appear
     * while the user is still looking at the pairing screen, rather than up to [RETRY_DELAY_MILLIS]
     * after the exchange has already expired.
     */
    fun retryDelay(deadlineAt: Long, now: Long): Long? =
        if (deadlineAt - now > RETRY_DELAY_MILLIS) RETRY_DELAY_MILLIS else null
}
