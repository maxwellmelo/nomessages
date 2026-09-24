package dev.mx3.nomessages.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Enforces "only one inline audio player is active at a time", app-wide, across every audio bubble
 * rendered by `ChatScreen`, and owns the single hook that tears every live player down when the
 * vault locks.
 *
 * This object never owns a `MediaPlayer` or decrypted bytes itself - each bubble stays the sole
 * owner of its own player and only hands over callbacks. Requesting playback pauses whichever
 * bubble was previously playing (its position is preserved, matching the WhatsApp-style
 * pause/resume expectation) before recording the new active id.
 *
 * ## Why the teardown registry exists
 *
 * [reset] is called from `NoMessagesController.lock()` and `closeSession()`. It used to only drop the
 * pause callback *without invoking it*, on the assumption that the composition would be torn down
 * by the `unlocked = false` state flip and each bubble's `DisposableEffect` would release its own
 * `MediaPlayer`. That assumption does not hold for the auto-lock path, which is the one that
 * matters: `onBackground()` arms `backgroundLock` and calls `lock()` after the timeout while the
 * app is in the **background**, where the window's `Recomposer` has had its frame clock paused
 * since `ON_STOP` and therefore does not recompose - so nothing disposes the bubble, the
 * `MediaPlayer` keeps playing out loud, and its `MemoryMediaDataSource` keeps the clip's decrypted
 * bytes live long after the vault reports itself locked.
 *
 * [registerPlayer]/[unregisterPlayer] therefore track every *live* player (playing or merely
 * prepared-and-paused, since a paused one still holds decrypted audio), keyed by an identity token
 * rather than the attachment id so that an inline bubble and the full-screen viewer for the same
 * attachment cannot displace each other. [reset] runs all of them synchronously.
 */
internal object AudioPlaybackCoordinator {
    var activeId by mutableStateOf<String?>(null)
        private set

    private val lock = Any()
    private var pauseCurrent: (() -> Unit)? = null
    private val teardowns = LinkedHashMap<Any, () -> Unit>()

    /**
     * Registers a live player's full teardown (stop, release, and close/zero its decrypted byte
     * source). [token] is an identity handle owned by the caller, not an attachment id.
     */
    fun registerPlayer(token: Any, teardown: () -> Unit) {
        synchronized(lock) { teardowns[token] = teardown }
    }

    /** Drops a teardown whose player the caller has already released itself. */
    fun unregisterPlayer(token: Any) {
        synchronized(lock) { teardowns.remove(token) }
    }

    fun requestPlay(id: String, pause: () -> Unit) {
        // The previous pause callback is invoked outside the monitor: it re-enters this object
        // through release(), and callbacks must never run while the registry lock is held.
        val previous = synchronized(lock) {
            val previousPause = if (activeId != id) pauseCurrent else null
            pauseCurrent = pause
            activeId = id
            previousPause
        }
        previous?.invoke()
    }

    fun release(id: String) {
        synchronized(lock) {
            if (activeId == id) {
                activeId = null
                pauseCurrent = null
            }
        }
    }

    /**
     * Vault lock / session teardown: synchronously stops **and** releases every registered player
     * and wipes its decrypted source, then forgets all of them. Callable from any thread (it runs
     * on the main thread from `lock()` and on `Dispatchers.IO` from `closeSession()`); a failing
     * teardown never prevents the remaining ones from running.
     */
    fun reset() {
        val pending = synchronized(lock) {
            activeId = null
            pauseCurrent = null
            val snapshot = teardowns.values.toList()
            teardowns.clear()
            snapshot
        }
        pending.forEach { teardown -> runCatching { teardown() } }
    }
}
