package dev.mx3.nomessages.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The doorbell schedule, exercised with an injected clock and no coroutine: every case below is a
 * pure `(state, now) -> decision`, in the same spirit as `TransportSupervisorTest`.
 */
class DoorbellKnockPolicyTest {
    private val floor = DoorbellKnockPolicy.FLOOR_MILLIS
    private val start = 1_700_000_000_000L

    @Test fun aContactThatWasNeverKnockedIsEligible() {
        assertTrue(DoorbellKnockPolicy.ready(null, start))
        assertTrue(DoorbellKnockPolicy.ready(null, 0L))
    }

    @Test fun neverKnocksBeforeTenMinutesHavePassed() {
        val state = DoorbellKnockState(start, failures = 0)
        assertFalse(DoorbellKnockPolicy.ready(state, start))
        assertFalse(DoorbellKnockPolicy.ready(state, start + 1))
        assertFalse(DoorbellKnockPolicy.ready(state, start + floor - 1))
    }

    @Test fun knocksExactlyAtTheTenMinuteBoundary() {
        val state = DoorbellKnockState(start, failures = 0)
        assertTrue(DoorbellKnockPolicy.ready(state, start + floor))
        assertTrue(DoorbellKnockPolicy.ready(state, start + floor + 1))
        assertEquals(start + floor, DoorbellKnockPolicy.nextEligible(state))
    }

    @Test fun backoffDoublesWithConsecutiveFailuresUpToATwoHourCeiling() {
        assertEquals(
            listOf(10L, 20L, 40L, 80L, 120L).map { it * 60_000L },
            (0..4).map { DoorbellKnockPolicy.delayFor(it) },
        )
        assertEquals(DoorbellKnockPolicy.MAX_BACKOFF_MILLIS, DoorbellKnockPolicy.delayFor(5))
        assertEquals(DoorbellKnockPolicy.MAX_BACKOFF_MILLIS, DoorbellKnockPolicy.delayFor(99))
        assertEquals(DoorbellKnockPolicy.MAX_BACKOFF_MILLIS, DoorbellKnockPolicy.delayFor(Int.MAX_VALUE))
        assertEquals(floor, DoorbellKnockPolicy.delayFor(0))
    }

    @Test fun eachFailureStretchesTheWaitAndTheCeilingStopsIt() {
        var state = DoorbellKnockState(start, failures = 0)
        // Two failures in a row: 10 min, then 20 min, then 40 min.
        state = state.attempted(start).failed()
        assertFalse(DoorbellKnockPolicy.ready(state, start + 20 * 60_000L - 1))
        assertTrue(DoorbellKnockPolicy.ready(state, start + 20 * 60_000L))

        state = state.attempted(start + 20 * 60_000L).failed()
        assertEquals(2, state.failures)
        assertFalse(DoorbellKnockPolicy.ready(state, start + 20 * 60_000L + 40 * 60_000L - 1))
        assertTrue(DoorbellKnockPolicy.ready(state, start + 20 * 60_000L + 40 * 60_000L))

        // Far past the ceiling: the wait stops growing at two hours.
        var saturated = DoorbellKnockState(start, failures = 0)
        repeat(50) { saturated = saturated.failed() }
        assertEquals(50, saturated.failures)
        assertEquals(DoorbellKnockPolicy.MAX_BACKOFF_MILLIS, DoorbellKnockPolicy.nextEligible(saturated) - start)
    }

    @Test fun aConfirmedKnockResetsTheGrowthButNotTheFloor() {
        val state = DoorbellKnockState(start, failures = 4).confirmed()
        assertEquals(0, state.failures)
        assertEquals(start, state.lastAttempt)
        // Back to the floor - and the floor still applies, an answered peer is not knocked again at once.
        assertFalse(DoorbellKnockPolicy.ready(state, start + floor - 1))
        assertTrue(DoorbellKnockPolicy.ready(state, start + floor))
    }

    @Test fun theFailureCounterSaturatesInsteadOfOverflowing() {
        val state = DoorbellKnockState(start, failures = Int.MAX_VALUE).failed()
        assertEquals(Int.MAX_VALUE, state.failures)
        assertEquals(DoorbellKnockPolicy.MAX_BACKOFF_MILLIS, DoorbellKnockPolicy.delayFor(state.failures))
    }

    @Test fun aRecordStampedInTheFutureDoesNotSilenceTheDoorbellForever() {
        val state = DoorbellKnockState(start + 7 * 24 * 60 * 60_000L, failures = 3)
        assertTrue(DoorbellKnockPolicy.ready(state, start))
        // And dispatching rewrites the stamp with the current clock, so it costs one knock at most.
        assertFalse(DoorbellKnockPolicy.ready(state.attempted(start), start))
    }

    @Test fun anAttemptOnlyMovesTheStampAndNeverTheCounter() {
        val state = DoorbellKnockState(start, failures = 2).attempted(start + 1234)
        assertEquals(start + 1234, state.lastAttempt)
        assertEquals(2, state.failures)
    }

    @Test fun aNegativeFailureCountIsRejected() {
        try {
            DoorbellKnockState(start, failures = -1)
            org.junit.Assert.fail("a negative failure count must not be representable")
        } catch (_: IllegalArgumentException) {
        }
        try {
            DoorbellKnockPolicy.delayFor(-1)
            org.junit.Assert.fail("a negative failure count has no schedule")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun theStoredRecordIsTwelveBigEndianBytesAndRoundTrips() {
        val state = DoorbellKnockState(start, failures = 3)
        val encoded = DoorbellKnockPolicy.encode(state)
        assertEquals(DoorbellKnockPolicy.ENCODED_BYTES, encoded.size)
        assertArrayEquals(
            byteArrayOf(0, 0, 0x01, 0x8b.toByte(), 0xcf.toByte(), 0xe5.toByte(), 0x68, 0x00, 0, 0, 0, 3),
            encoded,
        )
        assertEquals(state, DoorbellKnockPolicy.decode(encoded))
    }

    @Test fun anUnreadableRecordCountsAsNeverKnockedInsteadOfBlockingTheDoorbell() {
        assertNull(DoorbellKnockPolicy.decode(null))
        assertNull(DoorbellKnockPolicy.decode(ByteArray(0)))
        assertNull(DoorbellKnockPolicy.decode(ByteArray(DoorbellKnockPolicy.ENCODED_BYTES - 1)))
        assertNull(DoorbellKnockPolicy.decode(ByteArray(DoorbellKnockPolicy.ENCODED_BYTES + 1)))
        assertTrue(DoorbellKnockPolicy.ready(DoorbellKnockPolicy.decode(null), start))
    }

    @Test fun aCorruptFailureCountIsReadBackAsZeroRatherThanRejected() {
        val corrupt = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        assertEquals(DoorbellKnockState(0L, 0), DoorbellKnockPolicy.decode(corrupt))
    }
}
