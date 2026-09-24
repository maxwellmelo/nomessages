package dev.mx3.nomessages.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * A derived-data payload kept in [MediaPreviewCache]. Never the decrypted attachment itself - only
 * a small summary computed from it: a waveform, a bounded image thumbnail, or a bounded video
 * poster-frame bitmap plus duration.
 */
internal sealed class MediaPreviewPayload {
    /** Rough in-memory footprint, used to keep the cache under its total byte budget. */
    abstract val approximateByteSize: Int

    /**
     * Erases the payload's pixel content in place, without freeing it. Called only from
     * [MediaPreviewCache.clear] - i.e. on the vault-lock/session-teardown path, where the whole UI
     * that could still be holding this bitmap is being torn down anyway.
     *
     * Deliberately **not** `Bitmap.recycle()`: a cached bitmap handed out by
     * [MediaPreviewCache.get] is still referenced by whatever composable is drawing it (see
     * `ImageBubble`/`VideoBubble` in `ChatScreen.kt`, which keep it in `remember`ed state), and
     * recycling a bitmap that is still bound to a live `Image` node throws
     * `java.lang.IllegalArgumentException: Canvas: trying to use a recycled bitmap` on the next
     * draw pass. Erasing is safe to race with a draw (worst case a blank frame on a screen that is
     * being replaced by the lock screen); recycling is not.
     *
     * Most payloads (e.g. [Waveform], plain arrays) have nothing to erase.
     */
    open fun wipe() {}

    /** A ~48-bucket, 0..1 normalized peak waveform plus the clip duration it was computed from. */
    data class Waveform(val buckets: FloatArray, val durationMs: Int) : MediaPreviewPayload() {
        override val approximateByteSize: Int get() = buckets.size * Float.SIZE_BYTES + Int.SIZE_BYTES
    }

    /**
     * A small, bounded inline-preview thumbnail (see `chooseInSampleSize` in `UiLogic.kt` - decoded
     * at a max ~512px side and a bounded pixel count). Decoded with `inMutable = true` by
     * `NoMessagesController.decodeBoundedThumbnail` precisely so [wipe] below can actually erase its
     * pixels on lock - an immutable `BitmapFactory` result would silently skip the erase.
     */
    data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload() {
        override val approximateByteSize: Int get() = bitmap.byteCount
        override fun wipe() = bitmap.wipeIfMutable()
    }

    /**
     * A poster-frame bitmap decoded from a video attachment (via `MediaMetadataRetriever` in
     * `NoMessagesController.loadVideoPreview` - bounded to the same ~512px side as [Thumbnail]) plus the
     * clip's duration, used by the inline video bubble in `ChatScreen.kt`. `MediaMetadataRetriever`
     * returns an immutable bitmap, so [wipe] is a no-op for this payload and the frame is left for
     * GC once the cache and the UI both drop it.
     */
    data class VideoPoster(val bitmap: Bitmap, val durationMs: Int) : MediaPreviewPayload() {
        override val approximateByteSize: Int get() = bitmap.byteCount + Int.SIZE_BYTES
        override fun wipe() = bitmap.wipeIfMutable()
    }
}

/**
 * Erases a cached preview bitmap's pixels when it is mutable, and never recycles it - see
 * [MediaPreviewPayload.wipe] for why recycling a cache entry is unsafe here. This is intentionally
 * *not* the `Bitmap.wipeAndRecycle()` in `AttachmentViewer.kt`: there the bitmap is owned
 * exclusively by one `OwnedResource` whose `onDispose` runs after the composable that drew it is
 * gone, so recycling is both safe and correct.
 */
private fun Bitmap.wipeIfMutable() {
    if (!isRecycled && isMutable) eraseColor(Color.TRANSPARENT)
}

/**
 * Small, bounded, in-memory-only cache for [MediaPreviewPayload]s, keyed by attachment file id.
 *
 * Bounded by both entry count (~24) and total estimated bytes (~8 MiB) so it can never grow into a
 * meaningful copy of decrypted content - it only ever holds small derived summaries, never raw
 * attachment bytes. Eviction is plain LRU: the least recently touched entry is dropped first.
 *
 * [clear] is called synchronously from `NoMessagesController.lock()`/`closeSession()` so nothing
 * cached here survives a vault lock (see the callers for the exact hook points).
 */
internal object MediaPreviewCache {
    private const val MAX_ENTRIES = 24
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024

    private val lock = Any()

    // accessOrder = true turns iteration order into least-recently-used-first, which is exactly
    // what eviction below relies on.
    private val entries = LinkedHashMap<String, MediaPreviewPayload>(16, 0.75f, true)
    private var totalBytes = 0

    fun get(key: String): MediaPreviewPayload? = synchronized(lock) { entries[key] }

    fun put(key: String, value: MediaPreviewPayload) {
        synchronized(lock) {
            // A replaced entry is only unlinked, never wiped or recycled: the previous value may
            // still be the bitmap a live bubble is drawing (see MediaPreviewPayload.wipe).
            entries.remove(key)?.let { totalBytes -= it.approximateByteSize }
            entries[key] = value
            totalBytes += value.approximateByteSize
            evict()
        }
    }

    /**
     * Synchronously drops every cached preview, erasing the pixels of each one that can be erased
     * (see [MediaPreviewPayload.wipe]). Must stay cheap: it runs on the lock path.
     */
    fun clear() {
        synchronized(lock) {
            entries.values.forEach { runCatching { it.wipe() } }
            entries.clear()
            totalBytes = 0
        }
    }

    /**
     * LRU trim. Evicted entries are only unlinked - never wiped and never recycled - because an
     * entry can be evicted while the bubble that requested it is still on screen drawing it; see
     * [MediaPreviewPayload.wipe]. With 512x512 ARGB_8888 thumbnails at 1 MiB each the byte budget
     * alone starts evicting after ~8 previews, so this path is reached routinely, not rarely.
     */
    private fun evict() {
        val iterator = entries.entries.iterator()
        while ((entries.size > MAX_ENTRIES || totalBytes > MAX_TOTAL_BYTES) && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.approximateByteSize
            iterator.remove()
        }
    }
}
