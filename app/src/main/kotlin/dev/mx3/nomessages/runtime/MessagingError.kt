package dev.mx3.nomessages.runtime

/**
 * A [MessagingEngine]/[MemoryAudioRecorder] failure specific enough that the person using the app
 * deserves more than the generic "could not complete" fallback - "type a message" is not the same
 * problem as "this contact is not paired yet", and conflating them into one string was T4.6's lint
 * finding (`UnusedResources`: ~20 `error_*` strings in `strings.xml` had no caller, because every
 * catch around [MessagingEngine] discarded [Throwable.message] and always showed the same generic
 * text).
 *
 * [MessagingErrorCode] carries only what a call site is willing to distinguish - never the raw
 * message text - so `runtime/` stays plain JVM/Android-framework code with no `R.string` reference:
 * `NoMessagesController` (the one place that has a `Context`) is the only thing that maps a code to
 * a resource id, right before showing it. Every [MessagingError] still carries an English debug
 * [message] for logs; that string is never shown to the user, only [code] is.
 *
 * Deliberately **not** used for failures a person never sees: `MessagingEngine`'s receive loop
 * (`receiveLoop`/`accept`/`handle`/`acceptKeyPackage`/`acceptInvite`/`acceptCommit`) processes
 * untrusted network input on its own coroutine and swallows every exception itself, by design, so a
 * peer's malformed frame cannot become an oracle - see `receiveLoop`'s `catch (_: Exception)`. Those
 * call sites keep throwing a plain [IllegalArgumentException]/[IllegalStateException] with an
 * untranslated debug message, because no [MessagingErrorCode] mapping would ever be read: the
 * exception is dropped before reaching any UI-observing code, real or decoy.
 */
enum class MessagingErrorCode {
    /** [MessagingEngine.sendText] with blank text. */
    EMPTY_MESSAGE,
    /** [MessagingEngine.sendAttachment] over the 8 MiB cap, or [MemoryAudioRecorder] producing one. */
    FILE_TOO_LARGE,
    /** [MessagingEngine.decryptAttachment]: no [dev.mx3.nomessages.storage.FileRecord] for that id. */
    FILE_UNAVAILABLE,
    /** [MessagingEngine.decryptAttachment]: the file's wrapped key does not open (wrong epoch/tampered). */
    FILE_INVALID,
    /** [MessagingEngine.removeMember] would leave the group with only its coordinator. */
    NO_OTHER_MEMBERS,
    /** Sending into a group whose last MLS operation was rejected by a peer (`group_error`). */
    GROUP_SYNC_FAILED,
    /** Sending into (or acting on) a group still waiting for every invited member to join. */
    GROUP_WAITING_ONLINE,
    /** Acting on a group this identity already left (read-only from here on). */
    LEFT_GROUP,
    /** Sending to a contact that exists only as a forward/display target, never paired. */
    CONTACT_UNAVAILABLE,
    /** Sending to a peer id with no contact record at all. */
    CONTACT_UNPAIRED,
    /** [MessagingEngine.removeMember]/[MessagingEngine.leaveGroup] on an id with no group record. */
    GROUP_UNAVAILABLE,
    /** [MessagingEngine.sendAttachment]: this file id already exists with different ciphertext. */
    DUPLICATE_FILE,
    /** [MessagingEngine.sendAttachment]: this file id exists with a size/content mismatch. */
    EXISTING_FILE_MISMATCH,
    /** [MemoryAudioRecorder.finish] captured zero PCM bytes. */
    NO_AUDIO_RECORDED,
    /** [MessagingEngine.sendAttachment]/[MessagingEngine.storeAttachment]: this slot's fixed media
     * reservation (`AndroidVaultStorage.mediaCapacityBytes`, T4.1) is full. */
    MEDIA_CAPACITY_EXHAUSTED,
}

/** Thrown instead of a plain [IllegalStateException]/[IllegalArgumentException] wherever the cause
 * is one [NoMessagesController] is willing to show distinctly. [message] is a debug string for logs
 * only - never shown to the user - so it stays in whatever language is convenient (English, to match
 * the rest of the codebase's debug/log text), independent of [code], which is what selects the
 * user-facing, localized string. */
class MessagingError(val code: MessagingErrorCode, message: String) : IllegalStateException(message)
