package dev.mx3.nomessages.runtime

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import dev.mx3.nomessages.BuildConfig
import dev.mx3.nomessages.core.nativebridge.TorNative
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer

/** Tor's runtime and sockets exist exclusively in this disposable Android process. */
class TorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: Messenger? = null
    private var clientDeath: IBinder.DeathRecipient? = null
    // Volatile: these three used to be touched only by the Handler thread, but minimal mode now
    // clears the START latch and toggles the foreground state from the service scope's workers.
    @Volatile private var started = false
    /**
     * The messaging frame pump started by [START], kept apart from the service scope so
     * [DOORBELL_MINIMAL] can end it alone while the doorbell keeps this process alive.
     */
    @Volatile private var messaging: Job? = null
    @Volatile private var foregrounded = false
    private lateinit var stateDirectory: File
    private val inbox by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.sendingUid != applicationInfo.uid) return@Handler true
            when (message.what) {
                HELLO -> {
                    client = message.replyTo
                    val death = IBinder.DeathRecipient { terminate() }
                    clientDeath = death
                    try { client?.binder?.linkToDeath(death, 0) } catch (_: RemoteException) { terminate() }
                    respond(message.arg1, Bundle().apply { putInt("pid", Process.myPid()) })
                }
                START -> {
                    if (started) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "START refused: a messaging transport is already started in this process")
                        fail(message.arg1); return@Handler true
                    }
                    started = true
                    val request = message.arg1
                    val seed = message.data.getByteArray("seed") ?: return@Handler true
                    val bridges = message.data.getStringArray("bridges") ?: emptyArray()
                    messaging = scope.launch {
                        try {
                            stateDirectory = sessionDirectory()
                            val cache = directoryCache()
                            if (BuildConfig.DEBUG) Log.d(TAG, "START: native transport slot before start = ${TorNative.status()}")
                            val onion = TorNative.start(seed, stateDirectory.absolutePath, cache.absolutePath, bridges)
                            seed.fill(0)
                            // Messaging is back, so the process no longer needs the persistent
                            // notification that only existed to keep the doorbell alive.
                            leaveForeground()
                            respond(request, Bundle().apply { putString("onion", onion) })
                            while (isActive) {
                                val frame = TorNative.poll(500) ?: continue
                                event(Bundle().apply {
                                    putLong("connection", frame.connectionId)
                                    putByteArray("frame", frame.payload)
                                })
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "messaging start/pump ended: ${e.javaClass.name}: ${e.message}")
                            fail(request)
                        }
                        finally { seed.fill(0) }
                    }
                }
                // Readiness is polled in bounded slices so a lock can tear the process down without
                // waiting out the full native budget.
                READY -> {
                    val request = message.arg1
                    val slice = message.data.getInt("timeout")
                    scope.launch {
                        try { respond(request, Bundle().apply { putString("status", TorNative.awaitReady(slice)) }) }
                        catch (_: Exception) { fail(request) }
                    }
                }
                SEND, REPLY, CLOSE -> {
                    val command = message.what
                    val request = message.arg1
                    val data = message.data
                    scope.launch {
                        try {
                            val connection = when (command) {
                                SEND -> TorNative.send(requireNotNull(data.getString("onion")), requireNotNull(data.getByteArray("frame")))
                                REPLY -> { TorNative.reply(data.getLong("connection"), requireNotNull(data.getByteArray("frame"))); data.getLong("connection") }
                                else -> { TorNative.closeConnection(data.getLong("connection")); 0L }
                            }
                            respond(request, Bundle().apply { putLong("connection", connection) })
                        } catch (_: Exception) { fail(request) }
                    }
                }
                STOP -> scope.launch {
                    try { TorNative.stop() } catch (_: Exception) { }
                    finally { terminate() }
                }
                // --- Doorbell (T4.17). One message per native opcode 10-16, all appended after the
                // existing numbering so nothing above changes value. ---
                DOORBELL_START -> {
                    val request = message.arg1
                    val seed = message.data.getByteArray("seed") ?: return@Handler true
                    val bridges = message.data.getStringArray("bridges") ?: emptyArray()
                    scope.launch {
                        try {
                            val onion = TorNative.doorbellStart(seed, sessionDirectory().absolutePath, directoryCache().absolutePath, bridges)
                            respond(request, Bundle().apply { putString("onion", onion) })
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "doorbell start failed: ${e.javaClass.name}: ${e.message}")
                            fail(request)
                        }
                        finally { seed.fill(0) }
                    }
                }
                DOORBELL_ONION -> {
                    val request = message.arg1
                    scope.launch {
                        try { respond(request, Bundle().apply { putString("onion", TorNative.doorbellOnion()) }) }
                        catch (_: Exception) { fail(request) }
                    }
                }
                DOORBELL_TOKENS -> {
                    val request = message.arg1
                    val packed = message.data.getByteArray("tokens") ?: return@Handler true
                    scope.launch {
                        val tokens = try { unpackTokens(packed) } catch (_: Exception) { null }
                        try {
                            TorNative.doorbellTokens(requireNotNull(tokens))
                            respond(request, Bundle())
                        } catch (_: Exception) { fail(request) }
                        // The set now lives inside the native verifier; no plaintext copy is left
                        // behind in this process, neither the marshalled blob nor the split list.
                        finally { packed.fill(0); tokens?.forEach { it.fill(0) } }
                    }
                }
                DOORBELL_POLL -> {
                    val request = message.arg1
                    val timeout = message.data.getInt("timeout")
                    scope.launch {
                        try { respond(request, Bundle().apply { putInt("knocks", TorNative.doorbellPoll(timeout)) }) }
                        catch (_: Exception) { fail(request) }
                    }
                }
                DOORBELL_MINIMAL -> {
                    val request = message.arg1
                    scope.launch {
                        try {
                            TorNative.doorbellMinimal()
                            // The messaging transport is gone natively, so its frame pump has
                            // nothing left to drain - and clearing `started` is what allows the
                            // next unlock to reuse this very process (and its bootstrapped Arti
                            // host) with a second START instead of paying a fresh bootstrap.
                            messaging?.cancel()
                            messaging = null
                            started = false
                            if (BuildConfig.DEBUG) Log.d(TAG, "minimal mode: messaging pump cancelled, START latch cleared")
                            respond(request, Bundle().apply { putBoolean("foreground", enterForeground()) })
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "minimal mode failed: ${e.javaClass.name}: ${e.message}")
                            fail(request)
                        }
                    }
                }
                DOORBELL_STOP -> {
                    val request = message.arg1
                    scope.launch {
                        try { TorNative.doorbellStop(); leaveForeground(); respond(request, Bundle()) }
                        catch (_: Exception) { fail(request) }
                    }
                }
                DOORBELL_KNOCK -> {
                    val request = message.arg1
                    val onion = message.data.getString("onion")
                    val token = message.data.getByteArray("token") ?: return@Handler true
                    scope.launch {
                        try { respond(request, Bundle().apply { putBoolean("acknowledged", TorNative.doorbellKnock(requireNotNull(onion), token)) }) }
                        catch (_: Exception) { fail(request) }
                        finally { token.fill(0) }
                    }
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = inbox.binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onUnbind(intent: Intent?): Boolean { terminate(); return false }
    override fun onDestroy() { terminate(); super.onDestroy() }

    private fun sessionDirectory() = File(cacheDir, "tor-session").apply { check(isDirectory || mkdirs()) }
    private fun directoryCache() = File(cacheDir, "tor-directory").apply { mkdirs() }

    /**
     * Promotes this process to a foreground service for the duration of minimal (doorbell-only)
     * mode, and reports whether the promotion took.
     *
     * Only here, never during ordinary use: while the vault is open the app is in front and this
     * child already inherits enough importance from the binding, so a permanent notification would
     * be pure exposure. In minimal mode the opposite is true - the app is locked, both processes
     * are background, and without this the doorbell would be reclaimed at the system's convenience.
     *
     * A refusal (the platform's background foreground-service start restriction) is reported, not
     * thrown: the doorbell is already serving at this point and is worth keeping even unprotected.
     */
    private suspend fun enterForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (foregrounded) return@withContext true
        try {
            startForeground(
                PrivacyNotifications.BACKGROUND_NOTIFICATION_ID,
                PrivacyNotifications.backgroundNotification(this@TorService),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            foregrounded = true
            true
        } catch (_: Exception) { false }
    }

    private fun leaveForeground() {
        if (!foregrounded) return
        foregrounded = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
    }

    private fun respond(request: Int, data: Bundle) = transmit(RESULT, request, data)
    private fun fail(request: Int) = transmit(ERROR, request, Bundle())
    private fun event(data: Bundle) = transmit(FRAME, 0, data)
    private fun transmit(kind: Int, request: Int, data: Bundle) {
        try { client?.send(Message.obtain(null, kind, request, 0).apply { this.data = data }) }
        catch (_: RemoteException) { terminate() }
    }
    private fun terminate() {
        scope.cancel()
        leaveForeground()
        // Killing this UID-owned child closes every descriptor, including a bootstrap racing lock.
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val TAG = "TorService"
        const val HELLO = 1
        const val START = 2
        const val SEND = 3
        const val REPLY = 4
        const val CLOSE = 5
        const val STOP = 6
        const val READY = 7
        const val RESULT = 10
        const val ERROR = 11
        const val FRAME = 12
        // Doorbell requests start at 20 so they never collide with the RESULT/ERROR/FRAME replies
        // that share this same `what` space, and so a future messaging request can still be
        // appended after READY without renumbering anything here.
        const val DOORBELL_START = 20
        const val DOORBELL_ONION = 21
        const val DOORBELL_TOKENS = 22
        const val DOORBELL_POLL = 23
        const val DOORBELL_MINIMAL = 24
        const val DOORBELL_STOP = 25
        const val DOORBELL_KNOCK = 26

        /**
         * One Bundle key carries the whole token set as `count` followed by length-prefixed blobs.
         * A single primitive array keeps the secrets out of Parcelable/Serializable machinery and
         * lets both sides wipe exactly one buffer afterwards.
         */
        fun packTokens(tokens: List<ByteArray>): ByteArray {
            val buffer = ByteBuffer.allocate(4 + tokens.sumOf { 4 + it.size })
            buffer.putInt(tokens.size)
            tokens.forEach { buffer.putInt(it.size); buffer.put(it) }
            return buffer.array()
        }

        /** Inverse of [packTokens]; every bound is re-checked because this side is the receiver. */
        fun unpackTokens(packed: ByteArray): List<ByteArray> {
            val buffer = ByteBuffer.wrap(packed)
            val count = buffer.int
            require(count in 0..TorNative.MAX_DOORBELL_TOKENS)
            return List(count) {
                val size = buffer.int
                require(size in TorNative.MIN_DOORBELL_TOKEN..TorNative.MAX_DOORBELL_TOKEN)
                ByteArray(size).also { token -> buffer.get(token) }
            }
        }
    }
}
