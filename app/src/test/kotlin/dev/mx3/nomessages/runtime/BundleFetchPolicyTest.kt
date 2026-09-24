package dev.mx3.nomessages.runtime

import dev.mx3.nomessages.core.protocol.PairingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleFetchPolicyTest {
    /**
     * The real exchange window, read from the constant that produces it instead of hard-coded, so
     * this test starts failing the day `CONFIRMATION_TTL_SECONDS` moves and the policy's KDoc (and
     * the roadmap entry for T4.18) go stale.
     */
    private val window = PairingEngine.CONFIRMATION_TTL_SECONDS * 1000
    private val staged = 1_700_000_000_000L
    private val deadline = staged + window

    @Test fun realWindowIsTwoHundredAndFortySeconds() {
        // Guards the "240 s, not 300 s" claim in BundleFetchPolicy's KDoc and in the roadmap.
        assertEquals(240_000L, window)
    }

    @Test fun retriesWhileTheExchangeIsStillOpen() {
        assertTrue(BundleFetchPolicy.shouldAttempt(deadline, staged))
        assertTrue(BundleFetchPolicy.shouldAttempt(deadline, deadline - 1))
    }

    @Test fun deadlineIsExclusiveSoTheBoundaryDoesNotRetry() {
        // Chosen to match PairingEngine.get, which refuses at `now() >= expires`: an attempt started
        // exactly on the boundary could never be accepted.
        assertFalse(BundleFetchPolicy.shouldAttempt(deadline, deadline))
    }

    @Test fun doesNotRetryAfterTheDeadline() {
        assertFalse(BundleFetchPolicy.shouldAttempt(deadline, deadline + 1))
        assertFalse(BundleFetchPolicy.shouldAttempt(deadline, deadline + window))
    }

    @Test fun earlyAttemptsGetTheFullPerAttemptBudget() {
        assertEquals(BundleFetchPolicy.ATTEMPT_BUDGET_MILLIS, BundleFetchPolicy.attemptBudget(deadline, staged))
        // Several full-budget attempts fit inside one 240 s exchange.
        assertTrue(window / BundleFetchPolicy.ATTEMPT_BUDGET_MILLIS >= 4)
    }

    @Test fun lateAttemptIsClampedToWhatIsLeftOfTheExchange() {
        val now = deadline - 5_000L
        assertEquals(5_000L, BundleFetchPolicy.attemptBudget(deadline, now))
    }

    @Test fun budgetNeverGoesNegativePastTheDeadline() {
        assertEquals(0L, BundleFetchPolicy.attemptBudget(deadline, deadline))
        assertEquals(0L, BundleFetchPolicy.attemptBudget(deadline, deadline + 60_000L))
    }

    @Test fun pausesBetweenAttemptsWhileThereIsRoomForAnother() {
        assertEquals(BundleFetchPolicy.RETRY_DELAY_MILLIS, BundleFetchPolicy.retryDelay(deadline, staged))
        val tight = deadline - (BundleFetchPolicy.RETRY_DELAY_MILLIS + 1)
        assertEquals(BundleFetchPolicy.RETRY_DELAY_MILLIS, BundleFetchPolicy.retryDelay(deadline, tight))
    }

    @Test fun givesUpInsteadOfSleepingPastTheDeadline() {
        // Exactly one pause left is still a give-up: waiting it out would land on the boundary,
        // where shouldAttempt is already false.
        assertNull(BundleFetchPolicy.retryDelay(deadline, deadline - BundleFetchPolicy.RETRY_DELAY_MILLIS))
        assertNull(BundleFetchPolicy.retryDelay(deadline, deadline - 1))
        assertNull(BundleFetchPolicy.retryDelay(deadline, deadline + 1))
    }
}
