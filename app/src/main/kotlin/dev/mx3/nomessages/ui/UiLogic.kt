package dev.mx3.nomessages.ui

internal enum class AttachmentKind { IMAGE, TEXT, PDF, AUDIO, VIDEO, UNSUPPORTED }

internal fun attachmentKind(mimeType: String): AttachmentKind = when {
    mimeType.startsWith("image/") -> AttachmentKind.IMAGE
    mimeType == "text/plain" -> AttachmentKind.TEXT
    mimeType == "application/pdf" -> AttachmentKind.PDF
    mimeType.startsWith("audio/") -> AttachmentKind.AUDIO
    mimeType.startsWith("video/") -> AttachmentKind.VIDEO
    else -> AttachmentKind.UNSUPPORTED
}

internal fun deliveryMark(status: String): String = when (status.lowercase()) {
    "sent" -> "✓"
    "delivered", "read" -> "✓✓"
    else -> ""
}

/** Above this the exact number stops being useful and would widen the chat row. */
internal const val MAX_UNREAD_BADGE = 999

/**
 * Label for the local unread counter. An empty label means no badge at all, which is also the
 * answer for a negative count, so a corrupted counter degrades to "nothing new" instead of
 * rendering a nonsensical badge.
 */
internal fun unreadBadge(count: Int): String = when {
    count <= 0 -> ""
    count > MAX_UNREAD_BADGE -> "$MAX_UNREAD_BADGE+"
    else -> count.toString()
}

internal fun validGroupSelection(selectedContacts: Int): Boolean = selectedContacts in 2..99

/**
 * Whether the IME identified by [imeId] (the `"package/ComponentClass"` format returned by
 * `Settings.Secure.DEFAULT_INPUT_METHOD` and `InputMethodInfo.getId()`) belongs to one of
 * [systemPackages].
 *
 * Pure by design - no `android.*` types - so it is unit-testable on the JVM. The caller (in
 * `MainActivity`) is responsible for building [systemPackages] from
 * `InputMethodManager.getEnabledInputMethodList()` filtered by `ApplicationInfo.FLAG_SYSTEM` or
 * `ApplicationInfo.FLAG_UPDATED_SYSTEM_APP` - both count as "trusted enough not to warn about",
 * since a Play-updated system keyboard (e.g. Gboard updated from the Play Store) is still shipped
 * and reviewed as part of the OS image, not a third-party app the user separately installed.
 *
 * A malformed [imeId] with no `"/"` is treated as its own package name, so it still resolves to a
 * sensible (non-system, unless literally listed) answer instead of throwing.
 */
internal fun isSystemIme(imeId: String, systemPackages: Set<String>): Boolean =
    imeId.substringBefore('/') in systemPackages

/**
 * Whether `MainActivity` should keep `FLAG_SECURE` applied to the window.
 *
 * The default is always secure: a release build ignores the property outright, and a debug build
 * without the property (absent, or any value other than exactly `"1"`) stays secure too. Only a
 * debug build with `debug.nomessages.allow_capture` read as the literal string `"1"` is allowed to
 * drop the flag - a manual, local opt-in for capturing screenshots/recordings while developing
 * (e.g. to relay a pairing QR between two emulators), never a state that can happen by accident or
 * survive into a release APK.
 */
internal fun shouldApplySecureFlag(isDebug: Boolean, propertyValue: String?): Boolean =
    !(isDebug && propertyValue == "1")

internal fun completedGroupCheck(
    baselineRevision: Long,
    requestedMembers: Set<String>?,
    resultRevision: Long,
    resultMembers: Set<String>,
): Boolean = requestedMembers != null && resultRevision > baselineRevision && resultMembers == requestedMembers

/**
 * Index of the item the chat message list should be scrolled to in order to reveal the newest
 * message.
 *
 * `state.messages` keeps the same order the `messages_chat_time` query returns it (`ORDER BY ts
 * DESC, id DESC` - newest first), and `ChatScreen`'s `LazyColumn` renders it with `reverseLayout =
 * true`, which anchors index 0 at the bottom of the screen, next to the composer, the same spot
 * WhatsApp keeps the newest message. That makes the newest message index 0 whenever the layout is
 * reversed. The `reverseLayout` parameter is kept explicit (rather than hard-coded) so this stays
 * correct - and testable - if the list is ever rendered top-to-bottom instead, where the newest
 * message would be the last index rather than the first.
 */
internal fun scrollIndexForNewestMessage(itemCount: Int, reverseLayout: Boolean): Int {
    require(itemCount > 0) { "Cannot compute a scroll target for an empty message list" }
    return if (reverseLayout) 0 else itemCount - 1
}

/** Whether the reader is currently looking at the newest message, i.e. the bottom of the chat. */
internal fun isAtBottom(firstVisibleItemIndex: Int, newestMessageIndex: Int = 0): Boolean =
    firstVisibleItemIndex == newestMessageIndex

/**
 * Whether a newly arrived or sent message should auto-scroll the chat list into view.
 *
 * Auto-scroll only happens when the reader was already at the bottom of the conversation, so a
 * message that arrives while someone is reading older history further up never yanks them back down
 * to the newest message.
 */
internal fun shouldAutoScrollToNewMessage(wasAtBottom: Boolean): Boolean = wasAtBottom

/** Bar count used by the inline audio bubble waveform, WhatsApp-style. */
internal const val WAVEFORM_BUCKET_COUNT = 48

/** 16-bit mono PCM sample rate used by [dev.mx3.nomessages.runtime.MemoryAudioRecorder]. */
internal const val PCM_SAMPLE_RATE_HZ = 16_000

/**
 * A normalized `[0, 1]` peak waveform computed directly from 16-bit little-endian mono PCM samples,
 * bucketed into [bucketCount] bars.
 *
 * Pure and allocation-light so it can run both on the sender's raw recording buffer and on a
 * receiver's freshly decoded clip. An empty or too-short buffer degrades to a flat all-zero
 * waveform rather than throwing, since a corrupt/truncated clip should still render *something*.
 */
internal fun computeWaveformBuckets(pcm16: ByteArray, bucketCount: Int = WAVEFORM_BUCKET_COUNT): FloatArray {
    require(bucketCount > 0) { "Bucket count must be positive" }
    val peaks = FloatArray(bucketCount)
    val sampleCount = pcm16.size / 2
    if (sampleCount <= 0) return peaks
    val samplesPerBucket = maxOf(1, sampleCount / bucketCount)
    var maxPeak = 0
    for (bucket in 0 until bucketCount) {
        val start = bucket * samplesPerBucket
        if (start >= sampleCount) break
        val end = minOf(sampleCount, start + samplesPerBucket)
        var peak = 0
        for (sample in start until end) {
            val offset = sample * 2
            val lo = pcm16[offset].toInt() and 0xFF
            val hi = pcm16[offset + 1].toInt()
            val magnitude = kotlin.math.abs((hi shl 8) or lo)
            if (magnitude > peak) peak = magnitude
        }
        peaks[bucket] = peak.toFloat()
        if (peak > maxPeak) maxPeak = peak
    }
    if (maxPeak <= 0) return peaks
    for (i in peaks.indices) peaks[i] = (peaks[i] / maxPeak).coerceIn(0f, 1f)
    return peaks
}

/** Clip duration, in milliseconds, of a 16-bit mono PCM buffer at [sampleRateHz]. */
internal fun pcmDurationMs(pcmByteCount: Int, sampleRateHz: Int = PCM_SAMPLE_RATE_HZ): Int {
    require(sampleRateHz > 0) { "Sample rate must be positive" }
    val sampleCount = (pcmByteCount / 2).toLong()
    return ((sampleCount * 1000) / sampleRateHz).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

/** Formats a duration for the audio bubble's time labels, e.g. `65_000` -> `"1:05"`. */
internal fun formatDurationMs(durationMs: Int): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Label for the cycling playback-speed control, e.g. `1.5f` -> `"1.5x"`, `2f` -> `"2x"`. */
internal fun formatSpeedLabel(speed: Float): String {
    val rounded = kotlin.math.round(speed * 10) / 10.0
    val text = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    return "${text}x"
}

/**
 * Hard ceiling on the pixel count of a decoded inline preview: 1024 * 1024 pixels, i.e. at most
 * ~4 MiB of ARGB_8888 pixel data for any single thumbnail regardless of the source's aspect ratio.
 *
 * This exists because the classic `inSampleSize` recipe below is a *per-dimension* bound and is
 * therefore not, on its own, a bound on decoded memory: it stops halving as soon as the **shorter**
 * side would fall below [maxDimension], so a deliberately extreme aspect ratio (a 40000x500 PNG is
 * a few KB on the wire, well inside the 8 MiB attachment limit) would be decoded at full
 * resolution - 20 million pixels, ~80 MiB - and `OutOfMemoryError` is an `Error`, not an
 * `Exception`, so the `catch (_: Exception)` around the decode would not contain it. An attachment
 * arrives from a remote contact, so the source dimensions are attacker-chosen.
 */
internal const val PREVIEW_MAX_PIXELS: Long = 1024L * 1024L

/** Stops the pixel-budget loop below from ever overflowing `Int` on a pathological input. */
private const val MAX_IN_SAMPLE_SIZE = 1 shl 20

/**
 * The largest power-of-two `inSampleSize` such that both dimensions stay at or above [maxDimension]
 * after dividing by it - the standard `BitmapFactory.Options.inSampleSize` recipe - further raised
 * until the decoded pixel count fits inside [maxPixels]. Plain ints, no Android classes, so it is
 * directly unit-testable and reused by both the decode-bounds pass and the real decode pass in
 * `loadImagePreview` (`NoMessagesController.kt`).
 *
 * An already-small source image (either dimension already at or below [maxDimension]) returns 1: no
 * downsampling. A malformed source (a non-positive dimension) also returns 1 rather than looping
 * forever or dividing by zero.
 *
 * The [maxPixels] pass is what makes this a real bound on decoded memory rather than only on the
 * shorter side - see [PREVIEW_MAX_PIXELS]. It never *lowers* the sample size the classic recipe
 * picked, so ordinary photo aspect ratios are unaffected.
 */
internal fun chooseInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxDimension: Int = 512,
    maxPixels: Long = PREVIEW_MAX_PIXELS,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || maxDimension <= 0) return 1
    var inSampleSize = 1
    val halfWidth = sourceWidth / 2
    val halfHeight = sourceHeight / 2
    while (halfWidth / inSampleSize >= maxDimension && halfHeight / inSampleSize >= maxDimension) {
        inSampleSize *= 2
    }
    if (maxPixels > 0) {
        while (
            inSampleSize < MAX_IN_SAMPLE_SIZE &&
            (sourceWidth.toLong() / inSampleSize) * (sourceHeight.toLong() / inSampleSize) > maxPixels
        ) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Ordered ids of every image attachment in [messages] (already in whatever order the caller keeps
 * its message list, oldest/newest-first alike - this simply preserves it), used to populate
 * `UiState.imageGallery` so the full-screen viewer can swipe between a conversation's images.
 * Messages without an attachment, without a mime type, or whose mime type is not an image are
 * skipped entirely.
 */
internal fun imageGalleryIds(messages: List<MessageUi>): List<String> =
    messages.mapNotNull { message ->
        val id = message.attachmentId ?: return@mapNotNull null
        val mime = message.mimeType ?: return@mapNotNull null
        id.takeIf { attachmentKind(mime) == AttachmentKind.IMAGE }
    }

/**
 * Toggles [id] in the forward picker's selection: ticked if it was not there, unticked if it was.
 *
 * Returns a new set rather than mutating one, so the caller can hold it in Compose state and have
 * recomposition triggered by the identity change.
 */
internal fun toggleForwardTarget(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id

/**
 * Whether the forward picker's send button is enabled: only once at least one recipient is ticked.
 *
 * Kept here, next to [toggleForwardTarget], so the picker's whole "what is selected / can it be
 * sent" rule is one testable pair instead of an `isNotEmpty()` buried in a composable - and so that
 * a future rule (a cap on recipients per forward, say) has an obvious single place to live.
 */
internal fun canConfirmForward(selectedTargets: Set<String>): Boolean = selectedTargets.isNotEmpty()

/**
 * Whether a forwarded copy sent to [targetChatId] should carry the "Forwarded" label, given the
 * chat the original message lives in ([sourceChatId]). Resending into the same conversation the
 * message already belongs to is indistinguishable from typing it again - it only counts as a
 * forward once it crosses into a different conversation.
 */
internal fun shouldMarkForwarded(sourceChatId: String, targetChatId: String): Boolean =
    sourceChatId != targetChatId
