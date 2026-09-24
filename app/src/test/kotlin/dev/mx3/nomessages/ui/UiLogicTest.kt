package dev.mx3.nomessages.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UiLogicTest {
    @Test
    fun `attachment classifier keeps active content out of renderable categories`() {
        assertEquals(AttachmentKind.IMAGE, attachmentKind("image/png"))
        assertEquals(AttachmentKind.TEXT, attachmentKind("text/plain"))
        assertEquals(AttachmentKind.PDF, attachmentKind("application/pdf"))
        assertEquals(AttachmentKind.AUDIO, attachmentKind("audio/ogg"))
        assertEquals(AttachmentKind.VIDEO, attachmentKind("video/mp4"))
        assertEquals(AttachmentKind.UNSUPPORTED, attachmentKind("text/html"))
        assertEquals(AttachmentKind.UNSUPPORTED, attachmentKind("application/javascript"))
    }

    @Test
    fun `message status maps only known delivery states to ticks`() {
        assertEquals("✓", deliveryMark("sent"))
        assertEquals("✓✓", deliveryMark("delivered"))
        assertEquals("✓✓", deliveryMark("read"))
        assertEquals("", deliveryMark("queued"))
    }

    @Test
    fun `unread badge is local only and caps instead of widening the row`() {
        assertEquals("", unreadBadge(0))
        assertEquals("", unreadBadge(-1))
        assertEquals("1", unreadBadge(1))
        assertEquals("999", unreadBadge(MAX_UNREAD_BADGE))
        assertEquals("999+", unreadBadge(MAX_UNREAD_BADGE + 1))
    }

    @Test
    fun `group selection accounts for local member in three through one hundred total`() {
        assertEquals(false, validGroupSelection(1))
        assertEquals(true, validGroupSelection(2))
        assertEquals(true, validGroupSelection(99))
        assertEquals(false, validGroupSelection(100))
    }

    @Test
    fun `group check requires a newer result for the exact requested members`() {
        val requested = setOf("ana", "bia")
        assertEquals(false, completedGroupCheck(7, requested, 7, requested))
        assertEquals(false, completedGroupCheck(7, requested, 8, setOf("ana", "caio")))
        assertEquals(false, completedGroupCheck(7, null, 8, requested))
        assertEquals(true, completedGroupCheck(7, requested, 8, requested))
    }

    @Test
    fun `secure flag is dropped only for a debug build with the exact opt-in value`() {
        assertEquals(false, shouldApplySecureFlag(isDebug = true, propertyValue = "1"))
        assertEquals(true, shouldApplySecureFlag(isDebug = true, propertyValue = "0"))
        assertEquals(true, shouldApplySecureFlag(isDebug = true, propertyValue = null))
        assertEquals(true, shouldApplySecureFlag(isDebug = false, propertyValue = "1"))
        assertEquals(true, shouldApplySecureFlag(isDebug = false, propertyValue = null))
    }

    @Test
    fun `third-party keyboard detection only trusts packages the caller marked as system`() {
        val systemPackages = setOf("com.google.android.inputmethod.latin", "com.android.inputmethod.pinyin")

        // System package (e.g. shipped with the OS image) -> no banner.
        assertEquals(true, isSystemIme("com.google.android.inputmethod.latin/.LatinIME", systemPackages))

        // Non-system package (a third-party keyboard the user installed) -> banner.
        assertEquals(false, isSystemIme("com.thirdparty.keyboard/.KeyboardService", systemPackages))

        // FLAG_UPDATED_SYSTEM_APP case: the caller includes a Play-updated system keyboard (still
        // shipped/reviewed as part of the OS image, just no longer bit-identical to the factory
        // image) in systemPackages exactly like a plain FLAG_SYSTEM package - it must still count
        // as trusted, not trigger the banner.
        val updatedSystemPackages = setOf("com.google.android.inputmethod.latin")
        assertEquals(true, isSystemIme("com.google.android.inputmethod.latin/.LatinIME", updatedSystemPackages))

        // Malformed id with no "/" is treated as its own package name rather than throwing.
        assertEquals(false, isSystemIme("com.thirdparty.keyboard", systemPackages))
        assertEquals(true, isSystemIme("com.google.android.inputmethod.latin", systemPackages))
    }

    @Test
    fun `newest message sits at index 0 when reversed, at the last index otherwise`() {
        assertEquals(0, scrollIndexForNewestMessage(itemCount = 12, reverseLayout = true))
        assertEquals(11, scrollIndexForNewestMessage(itemCount = 12, reverseLayout = false))
        assertEquals(0, scrollIndexForNewestMessage(itemCount = 1, reverseLayout = false))
    }

    @Test
    fun `auto-scroll to a new message only follows a reader who was already at the bottom`() {
        assertEquals(true, isAtBottom(firstVisibleItemIndex = 0))
        assertEquals(false, isAtBottom(firstVisibleItemIndex = 3))
        assertEquals(true, shouldAutoScrollToNewMessage(wasAtBottom = true))
        assertEquals(false, shouldAutoScrollToNewMessage(wasAtBottom = false))
    }

    @Test
    fun `resource created after disposal is released exactly once`() {
        val releases = AtomicInteger()
        val owner = OwnedResource<Any> { releases.incrementAndGet() }

        owner.dispose()
        owner.install(Any())
        owner.dispose()

        assertEquals(1, releases.get())
    }

    @Test
    fun `concurrent install and disposal always releases exactly once`() {
        repeat(200) {
            val releases = AtomicInteger()
            val owner = OwnedResource<Any> { releases.incrementAndGet() }
            val start = CountDownLatch(1)
            val install = Thread {
                start.await()
                owner.install(Any())
            }
            val dispose = Thread {
                start.await()
                owner.dispose()
            }
            install.start()
            dispose.start()
            start.countDown()
            install.join()
            dispose.join()
            owner.dispose()
            assertEquals(1, releases.get())
        }
    }

    @Test
    fun `waveform buckets are normalized to the loudest bucket and count exactly as requested`() {
        val silence = ByteArray(2 * 480)
        assertEquals(FloatArray(WAVEFORM_BUCKET_COUNT) { 0f }.toList(), computeWaveformBuckets(silence).toList())

        // Two buckets, first quiet (magnitude 100), second loud (magnitude 20000): the loud bucket
        // normalizes to 1.0 and the quiet one scales down proportionally, never past it.
        val samples = shortArrayOf(100, 100, 20_000, 20_000)
        val pcm = pcm16Of(samples)
        val buckets = computeWaveformBuckets(pcm, bucketCount = 2)
        assertEquals(2, buckets.size)
        assertEquals(100f / 20_000f, buckets[0], 0.0001f)
        assertEquals(1f, buckets[1], 0.0001f)
    }

    @Test
    fun `waveform bucket count is honored even for a buffer too short to fill every bucket`() {
        val buckets = computeWaveformBuckets(pcm16Of(shortArrayOf(1000)), bucketCount = WAVEFORM_BUCKET_COUNT)
        assertEquals(WAVEFORM_BUCKET_COUNT, buckets.size)
        assertEquals(true, buckets.all { it in 0f..1f })
    }

    @Test
    fun `pcm duration follows sample count and sample rate, not byte layout`() {
        assertEquals(1000, pcmDurationMs(pcmByteCount = 16_000 * 2, sampleRateHz = 16_000))
        assertEquals(500, pcmDurationMs(pcmByteCount = 16_000, sampleRateHz = 16_000))
        assertEquals(0, pcmDurationMs(pcmByteCount = 0, sampleRateHz = 16_000))
    }

    @Test
    fun `duration formatting is mm colon ss with zero padded seconds`() {
        assertEquals("0:00", formatDurationMs(0))
        assertEquals("0:05", formatDurationMs(5_000))
        assertEquals("1:05", formatDurationMs(65_000))
        assertEquals("0:00", formatDurationMs(-1))
    }

    @Test
    fun `speed labels drop a trailing zero fraction but keep a real one`() {
        assertEquals("1x", formatSpeedLabel(1f))
        assertEquals("1.5x", formatSpeedLabel(1.5f))
        assertEquals("2x", formatSpeedLabel(2f))
    }

    @Test
    fun `media preview cache evicts least recently used entries past its entry cap`() {
        MediaPreviewCache.clear()
        try {
            repeat(30) { index ->
                MediaPreviewCache.put("clip-$index", MediaPreviewPayload.Waveform(FloatArray(4), durationMs = 1_000))
            }
            // Touch the oldest surviving entry so it is no longer the least-recently-used one, then
            // add one more: eviction should drop the entry that was never touched, not this one.
            val survivorKey = "clip-6"
            assertEquals(true, MediaPreviewCache.get(survivorKey) != null)
            MediaPreviewCache.put("clip-30", MediaPreviewPayload.Waveform(FloatArray(4), durationMs = 1_000))
            assertEquals(true, MediaPreviewCache.get(survivorKey) != null)
            assertEquals(true, MediaPreviewCache.get("clip-7") == null)
        } finally {
            MediaPreviewCache.clear()
        }
    }

    @Test
    fun `media preview cache clear synchronously drops every entry`() {
        MediaPreviewCache.put("clip", MediaPreviewPayload.Waveform(FloatArray(4), durationMs = 1_000))
        MediaPreviewCache.clear()
        assertEquals(null, MediaPreviewCache.get("clip"))
    }

    @Test
    fun `in sample size halves until either dimension would drop below the target`() {
        // Already at or below the target: no downsampling.
        assertEquals(1, chooseInSampleSize(512, 512, maxDimension = 512))
        assertEquals(1, chooseInSampleSize(100, 50, maxDimension = 512))

        // Exact power-of-two boundaries: 1024 halves to exactly 512, still >= target, so it takes
        // the step; one halving further (256) would drop below it, so it stops at 2.
        assertEquals(2, chooseInSampleSize(1024, 1024, maxDimension = 512))
        // 2048 -> 1024 -> 512, all >= 512: stops at 4.
        assertEquals(4, chooseInSampleSize(2048, 2048, maxDimension = 512))

        // Non-power-of-two source: the limiting (smaller) dimension governs the stopping point.
        assertEquals(2, chooseInSampleSize(1500, 1200, maxDimension = 512))

        // A very large source keeps halving.
        assertEquals(16, chooseInSampleSize(8192, 8192, maxDimension = 512))

        // Malformed input degrades to no downsampling rather than looping or dividing by zero.
        assertEquals(1, chooseInSampleSize(0, 100, maxDimension = 512))
        assertEquals(1, chooseInSampleSize(100, -1, maxDimension = 512))
    }

    @Test
    fun `in sample size also bounds total pixels so an extreme aspect ratio cannot be a decompression bomb`() {
        // The per-dimension recipe alone stops immediately here (250 < 512 after one halving), so
        // without the pixel budget a 40000x500 attachment - a few KB on the wire, well inside the
        // 8 MiB limit, and its dimensions are chosen by the remote contact - would be decoded at
        // full resolution: 20 million pixels, ~80 MiB of ARGB_8888.
        val bomb = chooseInSampleSize(40_000, 500, maxDimension = 512)
        assertEquals(true, bomb > 1)
        assertEquals(true, (40_000L / bomb) * (500L / bomb) <= PREVIEW_MAX_PIXELS)

        // Same for the mirrored orientation and for a square worst case.
        val tall = chooseInSampleSize(500, 40_000, maxDimension = 512)
        assertEquals(true, (500L / tall) * (40_000L / tall) <= PREVIEW_MAX_PIXELS)
        val huge = chooseInSampleSize(65_535, 65_535, maxDimension = 512)
        assertEquals(true, (65_535L / huge) * (65_535L / huge) <= PREVIEW_MAX_PIXELS)

        // Ordinary photo aspect ratios are untouched: the budget never lowers what the classic
        // recipe already chose, and never raises it for an image that already fits.
        assertEquals(1, chooseInSampleSize(512, 512, maxDimension = 512))
        assertEquals(2, chooseInSampleSize(1500, 1200, maxDimension = 512))
        assertEquals(4, chooseInSampleSize(4032, 3024, maxDimension = 512))
    }

    @Test
    fun `image gallery ids keep only image attachments, in message order`() {
        val messages = listOf(
            MessageUi("m1", "", "", outgoing = false, status = "read", attachmentId = "img-1", mimeType = "image/png"),
            MessageUi("m2", "hello", "", outgoing = true, status = "sent"),
            MessageUi("m3", "", "", outgoing = false, status = "read", attachmentId = "clip-1", mimeType = "audio/wav"),
            MessageUi("m4", "", "", outgoing = true, status = "delivered", attachmentId = "img-2", mimeType = "image/jpeg"),
            MessageUi("m5", "", "", outgoing = false, status = "read", attachmentId = "doc-1", mimeType = "application/pdf"),
        )
        assertEquals(listOf("img-1", "img-2"), imageGalleryIds(messages))
        assertEquals(emptyList<String>(), imageGalleryIds(emptyList()))
    }

    @Test
    fun `forward target toggle adds an unticked recipient and removes a ticked one`() {
        assertEquals(setOf("ana"), toggleForwardTarget(emptySet(), "ana"))
        assertEquals(setOf("ana", "bia"), toggleForwardTarget(setOf("ana"), "bia"))
        assertEquals(setOf("bia"), toggleForwardTarget(setOf("ana", "bia"), "ana"))
        assertEquals(emptySet<String>(), toggleForwardTarget(setOf("ana"), "ana"))

        // Toggling twice is a no-op, and the input set is never mutated in place.
        val selected = setOf("ana")
        assertEquals(selected, toggleForwardTarget(toggleForwardTarget(selected, "bia"), "bia"))
        assertEquals(setOf("ana"), selected)
    }

    @Test
    fun `forwarding can only be confirmed once at least one recipient is ticked`() {
        assertEquals(false, canConfirmForward(emptySet()))
        assertEquals(true, canConfirmForward(setOf("ana")))
        assertEquals(true, canConfirmForward(setOf("ana", "group-1")))
    }

    @Test
    fun `resending into the source chat is not labelled as forwarded`() {
        assertEquals(false, shouldMarkForwarded("ana", "ana"))
        assertEquals(false, shouldMarkForwarded("group-1", "group-1"))
    }

    @Test
    fun `forwarding into a different chat is labelled as forwarded`() {
        assertEquals(true, shouldMarkForwarded("ana", "bia"))
        assertEquals(true, shouldMarkForwarded("ana", "group-1"))
        assertEquals(true, shouldMarkForwarded("group-1", "ana"))
    }

    private fun pcm16Of(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
