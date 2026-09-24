package dev.mx3.nomessages.runtime

import dev.mx3.nomessages.core.messaging.ControlRejectionReason
import dev.mx3.nomessages.core.messaging.Envelope
import dev.mx3.nomessages.core.messaging.PacketKind
import org.junit.Assert.*
import org.junit.Test

class MessagingPolicyTest {
    @Test fun mlsAndSignalShareOneUnknownPacketBudget() {
        var now = 0L
        val limiter = MessagingTrialLimiter { now }
        repeat(4) { assertTrue(limiter.admit(PacketKind.MLS, false)) }
        assertFalse(limiter.admit(PacketKind.MLS, false))
        assertFalse(limiter.admit(PacketKind.SIGNAL, false))
        assertTrue(limiter.admit(PacketKind.SIGNAL, true))
        assertFalse(limiter.admit(PacketKind.MLS, false))
        now += 500_000_000L
        assertTrue(limiter.admit(PacketKind.SIGNAL, false))
        assertFalse(limiter.admit(PacketKind.MLS, false))
        now += 10_000_000_000L
        repeat(4) { assertTrue(limiter.admit(PacketKind.MLS, false)) }
        assertFalse(limiter.admit(PacketKind.MLS, false))
    }

    /**
     * Pairing bundle requests (T4.16) get their own budget: they arrive before any Signal session
     * exists, so the trial limiter's "is this a peer we already know" shortcut does not apply to
     * them, and sharing one bucket would let a pairing flood starve ordinary message admission.
     */
    @Test fun unauthenticatedBundleRequestsHaveTheirOwnBurstAndRefill() {
        var now = 0L
        val bundles = MessagingBundleLimiter { now }
        val trials = MessagingTrialLimiter { now }
        repeat(4) { assertTrue(bundles.admit()) }
        assertFalse(bundles.admit())
        // Exhausting the bundle bucket must not have touched the message bucket.
        repeat(4) { assertTrue(trials.admit(PacketKind.SIGNAL, false)) }
        // One token per second, capped at the burst of four.
        now += 1_000_000_000L
        assertTrue(bundles.admit())
        assertFalse(bundles.admit())
        now += 60_000_000_000L
        repeat(4) { assertTrue(bundles.admit()) }
        assertFalse(bundles.admit())
        // A clock that appears to run backwards must not mint tokens.
        now -= 60_000_000_000L
        assertFalse(bundles.admit())
    }

    @Test fun largeSignalAndMlsPacketsHaveOnlyEightTrials() {
        assertEquals(64, MessagingPolicy.trialLimit(64 * 1024))
        assertEquals(8, MessagingPolicy.trialLimit(64 * 1024 + 1))
        assertEquals(8, MessagingPolicy.trialLimit(8 * 1024 * 1024))
    }

    @Test fun concurrentLargeTransfersRetainTheirHashCursorsWithoutKeepingPayloads() {
        val candidates = List(1024) { "a".repeat(64) }
        val oneCursor = MessagingPolicy.cursorMemoryBytes(candidates)
        assertTrue(oneCursor <= MessagingPolicy.maximumCursorBytes)
        assertTrue(16 * oneCursor < 24L * 1024 * 1024)
        // Cursor budget depends on retained identities, not each transfer's discarded 8 MiB packet.
        assertTrue(4 * oneCursor < 4L * 8 * 1024 * 1024)
    }

    @Test fun mlsApplicationRetryFindsGroupJoinedAfterFirstReceipt() {
        val cursor = MessagingTrialCursor(listOf("old-group"), 0, 8 * 1024 * 1024)
        assertEquals(listOf("old-group"), cursor.refresh(listOf("old-group")))
        cursor.missed(8, 30_000)
        assertEquals("newly-joined-group", cursor.refresh(listOf("old-group", "newly-joined-group")).first())
    }

    @Test fun signalRetryFindsPeerWhosePairingCompletedAfterFirstReceipt() {
        val cursor = MessagingTrialCursor(emptyList(), 0, 256)
        cursor.missed(64, 30_000)
        assertEquals(listOf("newly-paired-peer"), cursor.refresh(listOf("newly-paired-peer")))
    }

    @Test fun recencyReorderingCannotRestartAnExistingCandidateScan() {
        val cursor = MessagingTrialCursor(listOf("a", "b", "c"), 0, 256)
        cursor.missed(1, 30_000)
        assertEquals(listOf("b", "c", "a"), cursor.refresh(listOf("c", "a", "b")))
        assertEquals(listOf("new", "b", "c", "a"), cursor.refresh(listOf("a", "new", "c", "b")))
        cursor.missed(1, 60_000)
        assertEquals(listOf("b", "c", "a", "new"), cursor.refresh(listOf("c", "a", "b", "new")))
    }

    @Test fun onePeerCannotPermanentlyConsumeAllKeyPackages() {
        val owners = List(4) { "peer-a" }
        assertEquals(ControlRejectionReason.KEY_PACKAGE_QUOTA, MessagingPolicy.packageRefusal(owners, "peer-a"))
        assertNull(MessagingPolicy.packageRefusal(owners, "peer-b"))
        assertEquals(ControlRejectionReason.KEY_PACKAGE_QUOTA, MessagingPolicy.packageRefusal(List(128) { "peer-$it" }, "new-peer"))
    }

    @Test fun rejectedRequestAndOtherGroupControlsUseDifferentLanes() {
        val uuid = "00000000-0000-4000-8000-000000000001"
        val other = "00000000-0000-4000-8000-000000000002"
        val request = Envelope.KeyPackageRequest(uuid, 0, other)
        val commit = Envelope.GroupCommit(uuid, 0, other, emptyList(), emptyList(), byteArrayOf())
        val rejection = Envelope.ControlRejected(uuid, 0, other, ControlRejectionReason.KEY_PACKAGE_QUOTA)
        assertNotEquals(MessagingPolicy.lane(request), MessagingPolicy.lane(commit))
        assertNotEquals(MessagingPolicy.lane(request), MessagingPolicy.lane(rejection))
        assertEquals(MessagingPolicy.groupLane(other), MessagingPolicy.lane(commit))
    }
}
