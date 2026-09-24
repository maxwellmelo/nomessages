package dev.mx3.nomessages.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JVM coverage of the pairing screen's expiry/regeneration rules (2026-09-17, T4.16).
 *
 * The composable itself is not exercised here on purpose: everything worth asserting is a deadline
 * decision, and those all live in [PairingLifecycle] precisely so they can be tested without an
 * Android runtime.
 */
class PairingLifecycleTest {

    private val now = 1_700_000_000_000L

    private fun offerOnScreen(expiresAt: Long) = PairingUi(offer = "nomessages:2:AAA", expiresAt = expiresAt)

    private fun exchange(expiresAt: Long, status: PairingBundleStatus = PairingBundleStatus.FETCHING) = PairingUi(
        offer = "nomessages:2:BBB",
        sas = "123456",
        peerFingerprint = "ab".repeat(32),
        expiresAt = expiresAt,
        bundleStatus = status,
    )

    @Test
    fun `a live offer is left alone`() {
        assertEquals(PairingQrAction.NONE, PairingLifecycle.nextAction(offerOnScreen(now + 1), true, now))
        assertEquals(PairingQrAction.NONE, PairingLifecycle.nextAction(offerOnScreen(now + 119_000), true, now))
    }

    @Test
    fun `an offer nobody scanned is regenerated the moment it expires`() {
        // The whole point of T4.16's refresh: the screen stays open with a fresh, scannable QR
        // instead of a dead one the other person keeps failing to read.
        assertEquals(PairingQrAction.REGENERATE, PairingLifecycle.nextAction(offerOnScreen(now), true, now))
        assertEquals(PairingQrAction.REGENERATE, PairingLifecycle.nextAction(offerOnScreen(now - 5_000), true, now))
    }

    @Test
    fun `an expired staged exchange is cancelled, never regenerated`() {
        // A staged exchange cannot be restarted unilaterally: the peer already holds a transcript
        // over this exact pair of offers, so a new local offer would leave the two sides with
        // different SAS values.
        assertEquals(PairingQrAction.CANCEL, PairingLifecycle.nextAction(exchange(now), true, now))
        assertEquals(PairingQrAction.NONE, PairingLifecycle.nextAction(exchange(now + 1), true, now))
    }

    @Test
    fun `leaving the screen cancels whatever is in progress`() {
        assertEquals(PairingQrAction.CANCEL, PairingLifecycle.nextAction(offerOnScreen(now + 60_000), false, now))
        assertEquals(PairingQrAction.CANCEL, PairingLifecycle.nextAction(exchange(now + 60_000), false, now))
    }

    @Test
    fun `a completed pairing is never touched`() {
        // Its confirmation QR has to stay on screen until the user dismisses it: the other phone
        // may still need to scan it.
        val completed = exchange(now - 60_000).copy(completed = true)
        assertEquals(PairingQrAction.NONE, PairingLifecycle.nextAction(completed, true, now))
        assertEquals(PairingQrAction.NONE, PairingLifecycle.nextAction(completed, false, now))
        assertEquals(PairingQrAction.NONE, PairingLifecycle.nextAction(null, true, now))
        assertEquals(PairingQrAction.NONE, PairingLifecycle.nextAction(null, false, now))
    }

    @Test
    fun `the three deadlines are the ones the protocol enforces`() {
        assertEquals(120_000L, PairingLifecycle.OFFER_TTL_MILLIS)
        // Acquisition: per emitted offer, from its own creation.
        assertEquals(300_000L, PairingLifecycle.PENDING_TTL_MILLIS)
        // Counted from staging, not from offer creation - see PairingEngine.CONFIRMATION_TTL_SECONDS.
        assertEquals(240_000L, PairingLifecycle.CONFIRMATION_TTL_MILLIS)
    }

    @Test
    fun `remaining time clamps at zero and promotes epoch seconds`() {
        assertEquals(5_000L, PairingLifecycle.remainingMillis(now + 5_000, now))
        assertEquals(0L, PairingLifecycle.remainingMillis(now - 5_000, now))
        // PairingProgress.expiresAt is in seconds; treating it as milliseconds would show a
        // countdown that is either instantly expired or a thousand times too long.
        assertEquals(5_000L, PairingLifecycle.remainingMillis((now / 1_000) + 5, now))
        assertEquals(0L, PairingLifecycle.remainingMillis(0, now))
    }

    @Test
    fun `the countdown label is mm colon ss and goes away once it runs out`() {
        assertEquals("02:00", PairingLifecycle.countdownLabel(120_000))
        assertEquals("04:59", PairingLifecycle.countdownLabel(299_999))
        assertEquals("00:09", PairingLifecycle.countdownLabel(9_400))
        assertNull(PairingLifecycle.countdownLabel(0))
        assertNull(PairingLifecycle.countdownLabel(-1))
    }

    @Test
    fun `retry is offered only for a failed fetch that still has time left`() {
        assertTrue(PairingLifecycle.canRetryBundleFetch(exchange(now + 30_000, PairingBundleStatus.FAILED), now))
        assertFalse(PairingLifecycle.canRetryBundleFetch(exchange(now, PairingBundleStatus.FAILED), now))
        assertFalse(PairingLifecycle.canRetryBundleFetch(exchange(now + 30_000, PairingBundleStatus.FETCHING), now))
        assertFalse(PairingLifecycle.canRetryBundleFetch(exchange(now + 30_000, PairingBundleStatus.READY), now))
        assertFalse(PairingLifecycle.canRetryBundleFetch(null, now))
        assertFalse(
            PairingLifecycle.canRetryBundleFetch(
                exchange(now + 30_000, PairingBundleStatus.FAILED).copy(completed = true),
                now,
            ),
        )
    }

    @Test
    fun `the countdown warns before it errors, not the instant it hits zero`() {
        assertFalse(PairingLifecycle.isExpiringSoon(15_001))
        assertTrue(PairingLifecycle.isExpiringSoon(15_000))
        assertTrue(PairingLifecycle.isExpiringSoon(1))
        // Zero and below are "expired", a different color (error) than "expiring soon" (warning).
        assertFalse(PairingLifecycle.isExpiringSoon(0))
        assertFalse(PairingLifecycle.isExpiringSoon(-1))
    }

    @Test
    fun `the SAS cannot be confirmed before the peer key bundle has arrived`() {
        assertTrue(PairingLifecycle.canConfirmSas(exchange(now + 60_000, PairingBundleStatus.READY)))
        listOf(
            PairingBundleStatus.NONE,
            PairingBundleStatus.WAITING_FOR_TOR,
            PairingBundleStatus.FETCHING,
            PairingBundleStatus.FAILED,
        ).forEach { status ->
            assertFalse(PairingLifecycle.canConfirmSas(exchange(now + 60_000, status)), "status $status must block confirm")
        }
        // No SAS yet means no exchange at all; and once this device is waiting for the peer's
        // confirmation QR, its own confirmation has already been signed.
        assertFalse(PairingLifecycle.canConfirmSas(offerOnScreen(now + 60_000)))
        assertFalse(
            PairingLifecycle.canConfirmSas(
                exchange(now + 60_000, PairingBundleStatus.READY).copy(waitingForPeer = true),
            ),
        )
        assertFalse(PairingLifecycle.canConfirmSas(null))
    }
}
