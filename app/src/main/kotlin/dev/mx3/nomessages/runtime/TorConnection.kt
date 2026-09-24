package dev.mx3.nomessages.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.app.ActivityManager
import android.os.*
import android.util.Log
import dev.mx3.nomessages.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a readiness wait observed, kept apart on purpose.
 *
 * [PUBLISHING] is *not* a failure: the client is bootstrapped and alive, the descriptor simply is
 * not out yet. Collapsing it into a failure costs a whole 180s bootstrap for nothing, because the
 * replacement child would have to reach exactly the same point again.
 */
enum class TorReadiness { READY, PUBLISHING, LOST }

class TorConnection(private val context: Context) {
    companion object {
        /** Descriptor publication budget, deliberately separate from the 180s bootstrap budget. */
        const val READY_TIMEOUT_MILLIS = 300_000L
        private const val READY_SLICE_MILLIS = 5_000
        private const val TAG = "TorConnection"
    }
    data class Incoming(val connection: Long, val frame: ByteArray)
    val incoming = Channel<Incoming>(128)
    private val requests = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val sequence = AtomicInteger()
    private val bound = CompletableDeferred<Messenger>()
    private val dead = CompletableDeferred<Unit>()
    @Volatile private var remote: Messenger? = null
    @Volatile private var stopped = false
    /**
     * First reason this connection stopped answering, kept only so a failed doorbell handoff can
     * say *why* the child was considered gone instead of only that it was.
     *
     * Deliberately a fixed label out of a closed set - never an address, a pid or any vault-derived
     * value - because it is printed to logcat in debug builds.
     */
    @Volatile private var deathCause: String? = null
    private var childPid = 0
    private var registered = false
    private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != context.applicationInfo.uid) return@Handler true
        when (message.what) {
            TorService.RESULT -> requests.remove(message.arg1)?.complete(message.data)
            TorService.ERROR -> requests.remove(message.arg1)?.completeExceptionally(IllegalStateException("Transporte indisponível"))
            TorService.FRAME -> {
                val bytes = message.data.getByteArray("frame") ?: return@Handler true
                if (!stopped && incoming.trySend(Incoming(message.data.getLong("connection"), bytes)).isFailure) {
                    scope.launch { shutdown() }
                }
            }
        }
        true
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val messenger = Messenger(service)
            remote = messenger
            try { service.linkToDeath({ onDeath("binder-died") }, 0) } catch (_: RemoteException) { onDeath("link-failed") }
            bound.complete(messenger)
            if (stopped) {
                try { messenger.send(Message.obtain(null, TorService.STOP)) } catch (_: RemoteException) { }
                if (registered) { context.unbindService(this); registered = false }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) = onDeath("service-disconnected")
        override fun onBindingDied(name: ComponentName?) = onDeath("binding-died")
        override fun onNullBinding(name: ComponentName?) = onDeath("null-binding")
    }

    suspend fun start(seed: ByteArray, bridges: List<String>): String {
        check(!stopped)
        withContext(Dispatchers.Main.immediate) {
            check(!stopped)
            registered = context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(registered) { "Transporte indisponível" }
        }
        withTimeout(10_000) { bound.await() }
        val hello = request(TorService.HELLO)
        childPid = hello.getInt("pid")
        check(childPid > 0 && childPid != Process.myPid())
        return withTimeout(180_000) {
            request(TorService.START, Bundle().apply { putByteArray("seed", seed); putStringArray("bridges", bridges.toTypedArray()) }).getString("onion")
                ?: error("Transporte indisponível")
        }
    }

    /**
     * Starts the messaging transport again inside a child that is already bound and alive.
     *
     * This is the unlock half of a doorbell handoff: the child kept running in minimal mode, so its
     * Arti host is still bootstrapped and [TorService] has cleared its one-START latch. Reusing it
     * skips a whole 180 s bootstrap, which is the difference between an unlock that is online at
     * once and one that spends three minutes reaching the state this process never left.
     *
     * Deliberately not folded into [start]: that path also binds, HELLO-handshakes and learns the
     * child pid, none of which may run twice on one connection.
     */
    suspend fun restart(seed: ByteArray, bridges: List<String>): String {
        check(!stopped && remote != null) { "Transporte indisponível" }
        return withTimeout(180_000) {
            request(TorService.START, Bundle().apply { putByteArray("seed", seed); putStringArray("bridges", bridges.toTypedArray()) }).getString("onion")
                ?: error("Transporte indisponível")
        }
    }

    /** False once the child died or was stopped; a dead transport answers nothing. */
    val alive: Boolean get() = !stopped && !dead.isCompleted

    /**
     * Why this connection is (not) alive, as a fixed label. Diagnostic only, and carries nothing
     * secret: the three booleans of [alive] plus the first cause recorded by [onDeath]/[shutdown].
     */
    val diagnostics: String get() = "stopped=$stopped dead=${dead.isCompleted} cause=${deathCause ?: "none"}"

    /** Returns as soon as the child process is gone, so a caller can race it against a deadline. */
    suspend fun awaitDeath() {
        if (stopped) return
        dead.await()
    }

    /**
     * Waits for the onion descriptor to be published, up to [budgetMillis] in total.
     * The native wait is sliced so cancellation by lock is observed within one slice instead of
     * parking the child process for the whole budget.
     *
     * The three outcomes are reported apart because the caller must treat them apart: only [LOST]
     * justifies throwing a bootstrapped Arti client away, while [PUBLISHING] means the very same
     * connection should keep waiting. Errors are classified here instead of thrown, so nobody has
     * to guess whether an exception meant "still publishing" or "child is gone"; only real
     * cancellation (the lock) propagates.
     */
    suspend fun awaitReady(budgetMillis: Long = READY_TIMEOUT_MILLIS): TorReadiness {
        val deadline = SystemClock.elapsedRealtime() + budgetMillis
        while (true) {
            if (!alive) return TorReadiness.LOST
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return if (alive) TorReadiness.PUBLISHING else TorReadiness.LOST
            val slice = remaining.coerceAtMost(READY_SLICE_MILLIS.toLong()).toInt()
            val status = try {
                withTimeout(slice + 15_000L) {
                    request(TorService.READY, Bundle().apply { putInt("timeout", slice) }).getString("status")
                }
            } catch (cancelled: CancellationException) {
                // A blown slice deadline is a TimeoutCancellationException: the child holds the
                // Binder but stopped answering at all, which is a loss, not slow publication.
                if (cancelled is TimeoutCancellationException) return TorReadiness.LOST
                throw cancelled
            } catch (_: Exception) { return TorReadiness.LOST }
            if (status == "READY") return TorReadiness.READY
        }
    }

    suspend fun send(onion: String, frame: ByteArray): Long = withTimeout(45_000) {
        request(TorService.SEND, Bundle().apply { putString("onion", onion); putByteArray("frame", frame) }).getLong("connection")
    }
    suspend fun reply(connection: Long, frame: ByteArray) = withTimeout(45_000) {
        request(TorService.REPLY, Bundle().apply { putLong("connection", connection); putByteArray("frame", frame) }); Unit
    }
    // --- Doorbell (T4.17). The client half of TorService's DOORBELL_* messages; every one of them
    // runs the native opcode inside `:tor`, where the library is loaded and the sockets live. ---

    /**
     * Starts (or adopts) the doorbell onion service and returns its address.
     *
     * Budgeted like [start] because a doorbell that has to build its own Arti host pays the same
     * bootstrap; when it joins the host the messaging transport already bootstrapped - the only
     * path used today, since minimal mode is entered from an open vault - it answers immediately.
     */
    suspend fun doorbellStart(seed: ByteArray, bridges: List<String>): String = withTimeout(180_000) {
        request(TorService.DOORBELL_START, Bundle().apply {
            putByteArray("seed", seed); putStringArray("bridges", bridges.toTypedArray())
        }).getString("onion") ?: error("Transporte indisponível")
    }

    /** Address of the running doorbell; throws when there is none. */
    suspend fun doorbellOnion(): String = withTimeout(15_000) {
        request(TorService.DOORBELL_ONION).getString("onion") ?: error("Transporte indisponível")
    }

    /** Replaces the accepted-knock token set. The caller keeps ownership of (and wipes) [tokens]. */
    suspend fun doorbellTokens(tokens: List<ByteArray>) {
        val packed = TorService.packTokens(tokens)
        try { withTimeout(15_000) { request(TorService.DOORBELL_TOKENS, Bundle().apply { putByteArray("tokens", packed) }) } }
        finally { packed.fill(0) }
    }

    /**
     * Waits up to [timeoutMillis] for accepted knocks and returns how many were drained.
     *
     * The local budget is the native one plus a margin: the child is expected to answer at the
     * deadline it was given, and only a child that stopped answering at all blows the margin.
     */
    suspend fun doorbellPoll(timeoutMillis: Int): Int = withTimeout(timeoutMillis + 15_000L) {
        request(TorService.DOORBELL_POLL, Bundle().apply { putInt("timeout", timeoutMillis) }).getInt("knocks")
    }

    /**
     * Enters minimal mode: the messaging transport dies, the doorbell survives, and the child
     * promotes itself to a foreground service. Returns whether that promotion succeeded - false
     * means the doorbell is running unprotected and may be reclaimed by the system.
     */
    suspend fun doorbellMinimal(): Boolean = withTimeout(60_000) {
        // Best effort, and deliberately not fatal: an explicitly started service is unambiguous
        // about outliving the binding, but the platform refuses this call from the background, and
        // the binding alone is enough for the handoff that follows.
        try { withContext(Dispatchers.Main.immediate) { context.startService(Intent(context, TorService::class.java)) } }
        catch (_: Exception) { }
        request(TorService.DOORBELL_MINIMAL).getBoolean("foreground")
    }

    /**
     * Stops the doorbell. When it owns the shared Arti host this disposes of the whole runtime, so
     * on unlock it is always called AFTER [restart] has adopted that host, never before.
     */
    suspend fun doorbellStop() = withTimeout(30_000) { request(TorService.DOORBELL_STOP); Unit }

    /**
     * Rings [onion]'s doorbell with the token that peer issued to this device.
     *
     * The budget covers a cold client bootstrap plus the circuit the native side builds for this
     * single knock; a peer that is simply offline costs the full wait exactly once per attempt.
     */
    suspend fun doorbellKnock(onion: String, token: ByteArray): Boolean = withTimeout(240_000) {
        request(TorService.DOORBELL_KNOCK, Bundle().apply { putString("onion", onion); putByteArray("token", token) })
            .getBoolean("acknowledged")
    }

    suspend fun close(connection: Long) {
        if (!stopped) withTimeoutOrNull(2_000) { request(TorService.CLOSE, Bundle().apply { putLong("connection", connection) }) }
    }
    suspend fun shutdown() = withContext(NonCancellable) {
        if (deathCause == null) deathCause = "shutdown"
        if (BuildConfig.DEBUG) Log.d(TAG, "shutdown() requested; $diagnostics")
        stopped = true
        try { remote?.send(Message.obtain(null, TorService.STOP)) } catch (_: RemoteException) { }
        // Give Arti a bounded opportunity to flush guard history before the forced teardown.
        if (remote?.binder?.isBinderAlive == true) withTimeoutOrNull(2_500) { dead.await() }
        withContext(Dispatchers.Main.immediate) {
            if (registered) { context.unbindService(connection); registered = false }
            context.stopService(Intent(context, TorService::class.java))
        }
        withTimeout(5_000) {
            while (true) {
                val children = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses.orEmpty()
                    .filter { it.uid == context.applicationInfo.uid && it.processName == "${context.packageName}:tor" }
                children.forEach { if (it.pid != Process.myPid()) Process.killProcess(it.pid) }
                val binderAlive = remote?.binder?.isBinderAlive == true
                if (binderAlive && childPid > 0 && childPid != Process.myPid()) Process.killProcess(childPid)
                if (!binderAlive && children.isEmpty()) break
                delay(50)
            }
        }
        incoming.close()
        onDeath()
        scope.cancel()
    }
    private suspend fun request(kind: Int, bundle: Bundle = Bundle()): Bundle {
        check(!stopped)
        val id = sequence.incrementAndGet()
        val result = CompletableDeferred<Bundle>()
        requests[id] = result
        try {
            (remote ?: bound.await()).send(Message.obtain(null, kind, id, 0).apply { data = bundle; replyTo = reply })
            return result.await()
        } finally { requests.remove(id) }
    }
    /**
     * A dead child is a stopped transport. Marking it here is what makes [close] and [request]
     * short-circuit instead of reaching for a dead Binder: a `DeadObjectException` raised from a
     * teardown path used to escape into the caller's loop and kill it for good, which is exactly
     * the permanent outage the supervised reconnection exists to prevent.
     */
    private fun onDeath(cause: String = "unknown") {
        if (deathCause == null) deathCause = cause
        if (BuildConfig.DEBUG) Log.d(TAG, "onDeath($cause); $diagnostics")
        stopped = true
        dead.complete(Unit)
        bound.completeExceptionally(IllegalStateException("Transporte encerrado"))
        requests.values.forEach { it.completeExceptionally(IllegalStateException("Transporte encerrado")) }
        requests.clear()
        incoming.close()
    }
}
