package dev.mx3.nomessages.runtime

import dev.mx3.nomessages.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.6: [NoMessagesController.errorResource] is the pure `MessagingErrorCode -> R.string id`
 * mapping split out of `errorMessage` precisely so it can be exercised here, on the JVM, with no
 * [android.content.Context]/Robolectric - a plain `when` over an enum is exactly the kind of thing
 * that silently falls out of sync with [MessagingErrorCode] the day a case is added or renamed, and
 * this test is what turns that into a compile-time-adjacent JVM failure instead of a user seeing the
 * generic fallback string for a failure that meant to say more.
 *
 * One assertion per [MessagingErrorCode] value, plus the `null` (no [MessagingError], e.g. a plain
 * [IllegalStateException] from a call site the receive loop never surfaces) -> fallback case. The
 * list here is deliberately exhaustive rather than parameterized over `MessagingErrorCode.entries`:
 * a new enum constant should force a new line to be written here, not silently resolve to the
 * [MessagingErrorCode.EMPTY_MESSAGE] branch's `when` behavior (an unmapped constant is a compile
 * error in [NoMessagesController.errorResource] itself, since the `when` is exhaustive with no
 * `else`; this test only adds "and the value is the *right* one").
 */
class NoMessagesControllerErrorMappingTest {
    private val fallback = R.string.error_operation_failed

    @Test fun everyMessagingErrorCodeMapsToItsOwnLocalizedString() {
        assertEquals(R.string.error_type_message, NoMessagesController.errorResource(MessagingErrorCode.EMPTY_MESSAGE, fallback))
        assertEquals(R.string.error_attachment_limit, NoMessagesController.errorResource(MessagingErrorCode.FILE_TOO_LARGE, fallback))
        assertEquals(R.string.error_file_unavailable, NoMessagesController.errorResource(MessagingErrorCode.FILE_UNAVAILABLE, fallback))
        assertEquals(R.string.error_file_invalid, NoMessagesController.errorResource(MessagingErrorCode.FILE_INVALID, fallback))
        assertEquals(R.string.error_no_other_members, NoMessagesController.errorResource(MessagingErrorCode.NO_OTHER_MEMBERS, fallback))
        assertEquals(R.string.error_group_sync_failed, NoMessagesController.errorResource(MessagingErrorCode.GROUP_SYNC_FAILED, fallback))
        assertEquals(R.string.error_group_waiting_online, NoMessagesController.errorResource(MessagingErrorCode.GROUP_WAITING_ONLINE, fallback))
        assertEquals(R.string.error_left_group, NoMessagesController.errorResource(MessagingErrorCode.LEFT_GROUP, fallback))
        assertEquals(R.string.error_contact_unavailable, NoMessagesController.errorResource(MessagingErrorCode.CONTACT_UNAVAILABLE, fallback))
        assertEquals(R.string.error_contact_unpaired, NoMessagesController.errorResource(MessagingErrorCode.CONTACT_UNPAIRED, fallback))
        assertEquals(R.string.error_group_unavailable, NoMessagesController.errorResource(MessagingErrorCode.GROUP_UNAVAILABLE, fallback))
        assertEquals(R.string.error_duplicate_file, NoMessagesController.errorResource(MessagingErrorCode.DUPLICATE_FILE, fallback))
        assertEquals(R.string.error_existing_file_mismatch, NoMessagesController.errorResource(MessagingErrorCode.EXISTING_FILE_MISMATCH, fallback))
        assertEquals(R.string.error_no_audio_recorded, NoMessagesController.errorResource(MessagingErrorCode.NO_AUDIO_RECORDED, fallback))
        assertEquals(R.string.error_media_capacity, NoMessagesController.errorResource(MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED, fallback))
    }

    @Test fun everyMessagingErrorCodeMapsToADistinctString() {
        val resources = MessagingErrorCode.entries.map { NoMessagesController.errorResource(it, fallback) }
        assertEquals("each MessagingErrorCode must show a distinct string, or two failures would read as one", resources.size, resources.toSet().size)
        assertTrue(resources.none { it == fallback })
    }

    @Test fun aFailureWithNoMessagingErrorCodeFallsBackToTheCallSitesGenericString() {
        assertEquals(fallback, NoMessagesController.errorResource(null, fallback))
        assertEquals(R.string.error_operation_failed, NoMessagesController.errorResource(null, R.string.error_operation_failed))
        // A different call site's fallback is respected too - this is not a hard-coded constant.
        assertEquals(R.string.error_attachment_limit, NoMessagesController.errorResource(null, R.string.error_attachment_limit))
    }
}
