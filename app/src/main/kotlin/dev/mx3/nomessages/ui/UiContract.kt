package dev.mx3.nomessages.ui

import android.graphics.Bitmap

data class ChatUi(val id: String, val title: String, val preview: String, val time: String, val unread: Int = 0, val group: Boolean = false, val lastOutgoing: Boolean = false, val lastStatus: String = "")
/** [forwarded] drives the "Encaminhada" label on the bubble; it is provenance only, with no link back to the origin chat. */
data class MessageUi(val id: String, val text: String, val time: String, val outgoing: Boolean, val status: String, val attachmentId: String? = null, val attachmentName: String? = null, val mimeType: String? = null, val forwarded: Boolean = false)
data class ContactUi(val id: String, val alias: String, val fingerprint: String, val pairedAt: String)
/**
 * [expiresAt] is epoch milliseconds and carries whichever deadline is in force: `now + 120 s` while
 * only a local offer is on screen, and the engine's 300 s exchange deadline once [sas] is non-null.
 * See [PairingLifecycle] for why the two are different and how the screen tells them apart.
 *
 * [bundleStatus] reports the Tor fetch of the peer's PQXDH key bundle, which QR format 2 moved out
 * of the QR and onto the network (2026-09-17, T4.16).
 */
data class PairingUi(
    val offer: String?,
    val sas: String? = null,
    val peerFingerprint: String? = null,
    val expiresAt: Long,
    val waitingForPeer: Boolean = false,
    val completed: Boolean = false,
    val bundleStatus: PairingBundleStatus = PairingBundleStatus.NONE,
)

/**
 * Progress of the pairing key-bundle fetch over Tor.
 *
 * [WAITING_FOR_TOR] is kept apart from [FETCHING] for the same reason `NetworkStatus.PUBLISHING` is
 * kept apart from `STARTING`: "your own onion is not reachable yet" and "the other phone is not
 * answering" are different problems with different remedies, and one banner for both would tell the
 * user nothing actionable.
 */
enum class PairingBundleStatus { NONE, WAITING_FOR_TOR, FETCHING, READY, FAILED }
/** PUBLISHING sits between bootstrap and reachability: Tor is up, the onion descriptor is not yet published. */
enum class NetworkStatus { OFF, STARTING, PUBLISHING, ONLINE, RETRYING, ERROR }
enum class GroupStatus { NONE, PENDING, READY, FAILED, LEFT }
data class UiState(
    val configured: Boolean = false,
    val unlocked: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val displayName: String = "",
    val chats: List<ChatUi> = emptyList(),
    val contacts: List<ContactUi> = emptyList(),
    val selectedChat: String? = null,
    val selectedTitle: String = "",
    val selectedGroupMembers: List<ContactUi> = emptyList(),
    val canManageSelectedGroup: Boolean = false,
    val selectedGroupStatus: GroupStatus = GroupStatus.NONE,
    val messages: List<MessageUi> = emptyList(),
    val pairing: PairingUi? = null,
    val onion: String = "",
    val network: NetworkStatus = NetworkStatus.OFF,
    val missingPairs: List<String> = emptyList(),
    val groupCheckRevision: Long = 0,
    val groupCheckMembers: Set<String> = emptySet(),
    val notice: String? = null,
    val lockTimeoutSeconds: Int = 30,
    val bridges: String = "",
    /**
     * Whether this vault keeps its doorbell onion serving while the app is locked (T4.17).
     *
     * Read from the open vault on activation, like [lockTimeoutSeconds] and [bridges]. The default
     * here is ON to match the controller's own default for an absent key: a state published before
     * activation finishes must not render the switch off and then flip it, which would read as the
     * app having silently turned the notice off. Neither the read nor this default branches on the
     * vault slot, so the decoy shows the same setting as the real vault.
     */
    val doorbellEnabled: Boolean = true,
    /**
     * Whether the "trocar senha de pânico" button shows on the settings screen.
     *
     * Fixed at `true` once unlocked (was `opened.slot == VaultSlot.REAL` until T4.7, 2026-09-23):
     * that branch was a plaintext, code-visible oracle a coerced inspection could read straight off
     * the settings screen even without ever unlocking with the real password, since the *absence* of
     * the button on the decoy is itself the tell. `NoMessagesController.changePanicPassword` still
     * branches on the session's actual slot - real replaces the panic password, decoy pays the same
     * KDF cost and discards it - but that branch never reaches this field. See security-model.md
     * ("Oráculos de isca", T4.7).
     */
    val canChangePanicPassword: Boolean = false,
    val recordingAudio: Boolean = false,
    val attachment: AttachmentUi? = null,
    /**
     * Ordered ids of every image attachment in the currently open chat, only populated while
     * [attachment] is itself an image - empty otherwise, and empty when there is only one image in
     * the chat (nothing to swipe between). Lets [AttachmentViewer] swipe to a sibling image by
     * calling [UiActions.openAttachment] again with the next/previous id - see `imageGalleryIds` in
     * `UiLogic.kt` for how this is derived from [messages].
     */
    val imageGallery: List<String> = emptyList(),
    /**
     * Every chat a message can be forwarded into: paired, non-display-only contacts plus groups
     * whose state is [GroupStatus.READY]. Recomputed on each refresh, so a group that goes pending,
     * fails or is left disappears from it without any extra bookkeeping. This is what the
     * recipient picker renders. The chat the message came from is not excluded: forwarding back
     * into the same conversation is allowed, exactly like WhatsApp.
     */
    val forwardTargets: List<ForwardTargetUi> = emptyList()
)
/** One selectable destination in the forward picker. [id] is a chat id: a contact id, or a group id when [isGroup]. */
data class ForwardTargetUi(val id: String, val title: String, val isGroup: Boolean)
data class AttachmentUi(val id: String, val name: String, val mimeType: String, val bytes: ByteArray)
/** A cached-or-freshly-decoded waveform/duration for an inline audio bubble. */
data class AudioPreviewUi(val waveform: FloatArray, val durationMs: Int)
/** Decrypted bytes for inline audio playback. Transient: the caller wipes [bytes] once done. */
data class AudioBytesUi(val mimeType: String, val bytes: ByteArray)
/**
 * A cached-or-freshly-decoded bounded thumbnail bitmap for an inline image bubble. The bitmap
 * itself lives in [MediaPreviewCache] as a [MediaPreviewPayload.Thumbnail] - this is just a handle
 * to it, never a second owner: it must not be recycled by the caller.
 */
data class ImagePreviewUi(val bitmap: Bitmap)
/**
 * A cached-or-freshly-decoded bounded poster-frame bitmap plus clip duration for an inline video
 * bubble. The bitmap lives in [MediaPreviewCache] as a [MediaPreviewPayload.VideoPoster] - this is
 * just a handle to it, never a second owner: it must not be recycled by the caller.
 */
data class VideoPreviewUi(val poster: Bitmap, val durationMs: Int)

interface UiActions {
    fun setup(name: String, password: CharArray, confirm: CharArray, panicPassword: CharArray, panicConfirm: CharArray)
    fun unlock(password: CharArray)
    fun lock()
    fun clearError()
    fun openChat(id: String)
    fun closeChat()
    fun sendText(text: String)
    fun showPairing()
    fun readPairing(code: String)
    fun confirmPairing(alias: String)
    fun cancelPairing()

    /** Re-runs a pairing key-bundle fetch that failed, while the exchange deadline still allows it. */
    fun retryPairingBundle()
    fun createGroup(name: String, members: List<String>)
    fun checkGroup(members: List<String>)
    fun renameContact(id: String, alias: String)
    fun removeMember(groupId: String, contactId: String)
    fun leaveGroup(groupId: String)
    fun exportVault()
    fun importVault(password: CharArray)
    fun pickAttachment()
    fun capturePhoto()
    fun startAudio()
    fun stopAudio()
    fun openAttachment(id: String)
    fun closeAttachment()
    /**
     * Re-sends message [messageId] into each chat in [targetChatIds], marked as forwarded.
     *
     * Called once with the whole multi-selection made in the recipient picker; duplicates in the
     * list are collapsed. Each destination gets a brand-new envelope (new id, new timestamp, and for
     * an attachment a new file key and file id), so nothing ties the copies to each other or to the
     * conversation they came from. Anything that is not a text or attachment message is ignored.
     * Sends go through the normal outbox, so they simply wait when Tor is unavailable.
     */
    fun forwardMessage(messageId: String, targetChatIds: List<String>)
    /**
     * Waveform/duration for an inline audio bubble, served from [MediaPreviewCache] when already
     * cached, otherwise decrypted and decoded off the main thread and cached for next time. Returns
     * null (locked, missing, or decode failure) rather than throwing, so the bubble degrades to a
     * flat placeholder instead of breaking the chat.
     */
    suspend fun loadAudioPreview(attachmentId: String): AudioPreviewUi?
    /**
     * Decrypts an audio attachment for inline playback. Never cached: the caller owns and wipes the
     * returned bytes once playback ends. Returns null on the same conditions as [loadAudioPreview].
     */
    suspend fun loadAudioBytes(attachmentId: String): AudioBytesUi?
    /**
     * Bounded (~512px max side) thumbnail for an inline image bubble, served from
     * [MediaPreviewCache] when already cached, otherwise decrypted, downsampled off the main thread
     * and cached for next time. Returns null (locked, missing, or decode failure) rather than
     * throwing, so the bubble falls back to the generic file-icon row instead of breaking the chat.
     */
    suspend fun loadImagePreview(attachmentId: String): ImagePreviewUi?
    /**
     * Bounded (~512px max side) poster frame plus duration for an inline video bubble, served from
     * [MediaPreviewCache] when already cached, otherwise decrypted and decoded (via
     * `MediaMetadataRetriever`) off the main thread and cached for next time. Returns null (locked,
     * missing, or decode failure) rather than throwing, so the bubble falls back to the generic
     * file-icon row instead of breaking the chat.
     */
    suspend fun loadVideoPreview(attachmentId: String): VideoPreviewUi?
    fun setLockTimeout(seconds: Int)
    fun setBridges(bridges: String)

    /**
     * Turns the locked-state doorbell notice on or off for the open vault (T4.17).
     *
     * Persisted per vault and applied at the next lock: the decision is taken while the vault is
     * still open, so flipping this switch never touches a doorbell that is already serving.
     */
    fun setDoorbellEnabled(enabled: Boolean)
    fun changePanicPassword(password: CharArray, confirm: CharArray)
}
