package dev.mx3.nomessages.runtime

import dev.mx3.nomessages.core.messaging.ControlRejectionReason
import dev.mx3.nomessages.core.messaging.Envelope
import dev.mx3.nomessages.core.messaging.PacketKind

/** Resource and queue policies shared by the runtime and regression tests. */
internal object MessagingPolicy {
    // Matches the bounded candidate set accepted by SignalSessions.decryptCandidates.
    const val maxContacts = 1024
    val readyQuery = """
        SELECT o.id FROM outbox o
        LEFT JOIN opaque_blobs a ON a.namespace='attempts' AND a.k=o.id
        LEFT JOIN opaque_blobs ol ON ol.namespace='outbox_lanes' AND ol.k=o.id
        WHERE NOT EXISTS (
            SELECT 1 FROM outbox p
            LEFT JOIN opaque_blobs pl ON pl.namespace='outbox_lanes' AND pl.k=p.id
            WHERE p.dest_onion=o.dest_onion
              AND COALESCE(pl.value,cast('direct' AS BLOB))=COALESCE(ol.value,cast('direct' AS BLOB))
              AND (p.created_at<o.created_at OR (p.created_at=o.created_at AND p.id<o.id))
        )
        ORDER BY a.value,o.created_at,o.id LIMIT 1000
    """.trimIndent()

    fun groupLane(groupId: String) = "group:$groupId"
    fun lane(envelope: Envelope): String = when (envelope) {
        is Envelope.GroupInvite -> groupLane(envelope.groupId)
        is Envelope.GroupCommit -> groupLane(envelope.groupId)
        is Envelope.GroupLeave -> groupLane(envelope.groupId)
        is Envelope.KeyPackageRequest -> "request:${envelope.requestId}"
        is Envelope.KeyPackage -> "request:${envelope.requestId}"
        is Envelope.Evidence -> "evidence"
        is Envelope.ControlRejected -> "rejection"
        else -> "direct"
    }

    const val maximumCursorBytes = 256L + maxContacts * 256L
    fun cursorMemoryBytes(peers: List<String>): Long = 256L + peers.sumOf { 128L + it.length * 2L }

    fun trialLimit(bytes: Int) = if (bytes <= 64 * 1024) 64 else 8
    fun packageRefusal(owners: List<String>, sender: String): ControlRejectionReason? =
        if (owners.size >= 128 || owners.count { it == sender } >= 4) ControlRejectionReason.KEY_PACKAGE_QUOTA else null
}

/**
 * Admission control for **unauthenticated** pairing bundle requests (2026-09-17, T4.16).
 *
 * `PacketKind.BUNDLE` arrives before any Signal session exists, so it cannot go through
 * [MessagingTrialLimiter], which classifies by "is this a peer we already know". The real defence
 * is structural and lives in `PairingEngine.bundleFor`: an answer needs a nonce this device itself
 * minted, inside that offer's own validity window, and each nonce is answered exactly once. This
 * bucket exists only so that a flood of *wrong* nonces cannot make the receive loop decode
 * envelopes at line rate; the same 1/s sustained, burst 4 shape as the trial limiter.
 */
internal class MessagingBundleLimiter(private val ticks: () -> Long = System::nanoTime) {
    private var tokens = 4.0
    private var previous = ticks()

    fun admit(): Boolean {
        val now = ticks()
        tokens = minOf(4.0, tokens + maxOf(0L, now - previous) / 1_000_000_000.0)
        previous = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

internal class MessagingTrialLimiter(private val ticks: () -> Long = System::nanoTime) {
    private var tokens = 4.0
    private var previous = ticks()

    fun admit(kind: PacketKind, knownPeer: Boolean): Boolean {
        require(kind == PacketKind.SIGNAL || kind == PacketKind.MLS)
        if (knownPeer) return true
        val now = ticks()
        tokens = minOf(4.0, tokens + maxOf(0L, now - previous) / 1_000_000_000.0 * 2.0)
        previous = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

/** A stable scan over the currently eligible set; newly paired/joined candidates are tried first. */
internal class MessagingTrialCursor(candidates: List<String>, var touched: Long, val bytes: Int) {
    var peers: List<String> = candidates
        private set
    var offset: Int = 0
        private set
    val memoryBytes: Long get() = MessagingPolicy.cursorMemoryBytes(peers)

    fun refresh(eligible: List<String>): List<String> {
        val next = eligible.toSet()
        if (next != peers.toSet()) {
            val previous = peers.toSet()
            val newlyEligible = eligible.filter { it !in previous }
            val unscannedFirst = peers.drop(offset) + peers.take(offset)
            peers = newlyEligible + unscannedFirst.filter { it in next }
            offset = 0
        }
        return peers.drop(offset) + peers.take(offset)
    }

    fun missed(trials: Int, now: Long) {
        if (peers.isNotEmpty()) offset = (offset + trials) % peers.size
        touched = now
    }
}
