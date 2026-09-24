package dev.mx3.nomessages.ui

/**
 * The pure decision table behind the pairing screen's countdown, its automatic QR refresh and its
 * Tor bundle-fetch feedback (2026-09-17, T4.16).
 *
 * It has no Android dependency on purpose. The screen is a Compose composable driven by a one-second
 * ticker, and "when exactly does a QR get thrown away and replaced" is precisely the kind of rule
 * that is expensive to test through a composable and cheap to test here - so the composable only
 * renders and dispatches, while every deadline lives in this file and in `PairingLifecycleTest`.
 *
 * ## The three deadlines
 *
 * They are different things and must not be collapsed:
 *
 * - [OFFER_TTL_MILLIS] (120 s) is how long a QR may still be **scanned**. It is enforced by
 *   `PairingEngine.fresh`, and this object only mirrors it for the countdown and the refresh.
 * - [PENDING_TTL_MILLIS] (300 s) is the **acquisition** budget: how long an emitted offer stays
 *   answerable, so how long matching a response against it may take. It no longer covers what
 *   happens after staging.
 * - [CONFIRMATION_TTL_MILLIS] (240 s) is the **confirmation** budget, counted from staging: the
 *   Tor fetch of
 *   the peer's key bundle, the spoken SAS comparison, and the two confirmation QRs. It exists
 *   because QR format 2 added a network round trip to a freshly published onion service, which
 *   routinely costs 5-40 s and occasionally more - time the format-1 flow never had to spend,
 *   because the whole bundle was inside the QR.
 *
 * `UiState.pairing.expiresAt` carries whichever of the two is in force: the controller publishes
 * `now + 120 s` for a bare offer and the engine's `PairingProgress.expiresAt` once an exchange is
 * staged. That is why [nextAction] decides which deadline it is looking at from
 * [PairingUi.sas] - a non-null SAS means the exchange is staged - rather than from a second field
 * that could drift out of agreement with the first.
 */
internal object PairingLifecycle {
    const val OFFER_TTL_MILLIS: Long = 120_000L
    const val PENDING_TTL_MILLIS: Long = 300_000L

    /**
     * Mirrors `PairingEngine.CONFIRMATION_TTL_SECONDS`. Anchored on the moment of staging, not on
     * either offer's creation: T4.16 run6 measured that a two-rotation acquisition left only 4-35 s
     * of the old offer-anchored budget for the whole human phase, so both live attempts were
     * accepted by the protocol and then ran out of time. Total wall clock from first offer to a
     * completed pairing can therefore now exceed 300 s.
     */
    const val CONFIRMATION_TTL_MILLIS: Long = 240_000L

    /**
     * What the screen must do at [nowMillis].
     *
     * - [PairingQrAction.REGENERATE] - the displayed offer aged out while nobody had scanned it yet.
     *   The screen asks for a brand-new offer (new nonce, new bundle) and stays open, instead of
     *   leaving a dead QR on screen for the other person to keep failing to scan.
     * - [PairingQrAction.CANCEL] - either the screen is being left, or a staged exchange ran out of
     *   time. Pending key material is burned rather than left half-established.
     * - [PairingQrAction.NONE] - nothing to do.
     *
     * A completed pairing is never touched: its confirmation QR must stay on screen until the user
     * dismisses it, because the other phone may still have to scan it.
     */
    fun nextAction(pairing: PairingUi?, screenOpen: Boolean, nowMillis: Long): PairingQrAction {
        if (pairing == null) return PairingQrAction.NONE
        if (pairing.completed) return PairingQrAction.NONE
        if (!screenOpen) return PairingQrAction.CANCEL
        if (remainingMillis(pairing.expiresAt, nowMillis) > 0) return PairingQrAction.NONE
        // A staged exchange cannot be restarted unilaterally - the peer already holds a transcript
        // over this exact pair of offers - so an expired one is cancelled, not refreshed.
        return if (pairing.sas == null) PairingQrAction.REGENERATE else PairingQrAction.CANCEL
    }

    /**
     * Milliseconds left before [expiresAt], never negative.
     *
     * [expiresAt] is accepted in epoch milliseconds, but a value small enough to be epoch *seconds*
     * is promoted: `PairingProgress.expiresAt` is in seconds, and mixing the two units silently
     * produced a countdown that was either already expired or 1000x too long.
     */
    fun remainingMillis(expiresAt: Long, nowMillis: Long): Long {
        val millis = if (expiresAt in 1 until SECONDS_CEILING) expiresAt * 1_000 else expiresAt
        return (millis - nowMillis).coerceAtLeast(0)
    }

    /** `mm:ss` for a live countdown, or null once it has run out (the caller shows "expirado"). */
    fun countdownLabel(remainingMillis: Long): String? {
        if (remainingMillis <= 0) return null
        val totalSeconds = remainingMillis / 1_000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    /**
     * Whether the on-screen countdown should switch to its "running out" warning color.
     *
     * A countdown that stays neutral for its whole life and then jumps straight to the error color
     * the instant it hits zero reads as more alarming than a brief early warning - the color change
     * itself becomes the surprise, right when [nextAction] is about to regenerate or cancel anyway.
     * [EXPIRY_WARNING_MILLIS] gives the last few seconds a calmer, distinct amber state instead (UX
     * polish pass, 2026-09-17).
     */
    fun isExpiringSoon(remainingMillis: Long): Boolean = remainingMillis in 1..EXPIRY_WARNING_MILLIS

    /** @see isExpiringSoon */
    const val EXPIRY_WARNING_MILLIS: Long = 15_000L

    /**
     * Whether the "try again" button under a failed bundle fetch should be offered.
     *
     * Only for a staged exchange whose fetch actually failed and that still has time left: retrying
     * after the deadline would spend a Tor circuit on an exchange `PairingEngine` will refuse
     * anyway.
     */
    fun canRetryBundleFetch(pairing: PairingUi?, nowMillis: Long): Boolean {
        if (pairing == null || pairing.completed) return false
        if (pairing.bundleStatus != PairingBundleStatus.FAILED) return false
        return remainingMillis(pairing.expiresAt, nowMillis) > 0
    }

    /**
     * Whether the SAS confirmation button may be pressed.
     *
     * The peer's key bundle is required *before* confirming rather than at `finish`, so the user
     * finds out that the other phone is unreachable while the retry is still on screen, instead of
     * after the confirmation QRs have been exchanged.
     */
    fun canConfirmSas(pairing: PairingUi?): Boolean =
        pairing != null && !pairing.completed && pairing.sas != null &&
            !pairing.waitingForPeer && pairing.bundleStatus == PairingBundleStatus.READY

    /** Any epoch value below this is seconds, not milliseconds (it is the year 2286 in seconds). */
    private const val SECONDS_CEILING = 10_000_000_000L
}

/** @see PairingLifecycle.nextAction */
internal enum class PairingQrAction { NONE, REGENERATE, CANCEL }
