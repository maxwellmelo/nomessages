package dev.mx3.nomessages.runtime

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mx3.nomessages.BuildConfig
import dev.mx3.nomessages.R
import dev.mx3.nomessages.core.crypto.NativeCrypto
import dev.mx3.nomessages.core.groups.CliquePolicy
import dev.mx3.nomessages.core.messaging.Envelope
import dev.mx3.nomessages.core.messaging.EnvelopeCodec
import dev.mx3.nomessages.core.nativebridge.TorNative
import dev.mx3.nomessages.core.nativebridge.TorStateSnapshot
import dev.mx3.nomessages.core.protocol.*
import dev.mx3.nomessages.core.vault.*
import dev.mx3.nomessages.storage.*
import dev.mx3.nomessages.storage.PairEvidence
import dev.mx3.nomessages.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

interface PlatformActions {
    fun chooseExport()
    fun chooseImport()
    fun chooseAttachment()
    fun requestMicrophone()
}

/** One controller, one serial mutation boundary, one unlocked session generation. */
class NoMessagesController(application: Application) : AndroidViewModel(application), UiActions {
    private val context = application
    private val notifications by lazy { PrivacyNotifications(context) }
    @Volatile private var foreground = true
    private val directory = File(context.filesDir, "vault").toPath()
    private val torStateDirectory = File(context.cacheDir, "tor-session")
    private var torStatePrepared = false
    private val crypto by lazy { NativeCrypto() }
    private val storage by lazy { AndroidVaultStorage(context, crypto) }
    private val manager by lazy { VaultManager(crypto, storage) }
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val lifecycleGuard = Any()
    @Volatile private var shuttingDown = false
    private val requestSequence = AtomicLong()
    private var session: VaultSession? = null
    private var identity: PairingIdentity? = null
    private var pairing: PairingEngine? = null
    private var progress: PairingProgress? = null
    private var pairingAlias = ""
    private var confirmed = false
    /**
     * The in-flight Tor fetch of the peer's PQXDH key bundle (QR format 2, T4.16). At most one runs
     * at a time: a new exchange, a retry, a cancellation and a lock all replace or cancel it, so a
     * fetch belonging to an abandoned exchange can never land on a newer one.
     */
    private var bundleFetch: Job? = null
    private val consumed = mutableSetOf<String>()
    private var transport: TorConnection? = null
    /**
     * The `:tor` child a doorbell lock kept alive, together with the vault slot whose issued tokens
     * are loaded into it.
     *
     * The slot - not the seed, not the onion, not the identity - is how "same vault" is decided on
     * the next unlock. It is in-memory only and never written anywhere: comparing anything derived
     * from the vault itself would mean holding vault material while locked, which is the one thing
     * the lock exists to prevent, and persisting it would leave on disk a record of which vault was
     * last open. If the process dies, this field dies with it and the child dies with the process -
     * the conservative outcome, never a wrong reuse.
     */
    private class MinimalDoorbell(val slot: VaultSlot, val transport: TorConnection)
    @Volatile private var minimal: MinimalDoorbell? = null
    /** The reused child handed from a doorbell lock to the activation that follows it. */
    private var handoff: TorConnection? = null
    /** Drains accepted knocks while minimal mode lasts; outlives the session scope on purpose. */
    private var doorbellWatch: Job? = null
    private var engine: MessagingEngine? = null
    private var sessionScope: CoroutineScope? = null
    private var backgroundLock: Job? = null
    private var pendingImportPassword: CharArray? = null
    private data class MediaRequest(
        val generation: Long,
        val chat: String,
        val id: Long,
        @Volatile var cancelled: Boolean = false,
        @Volatile var awaitingMicrophonePermission: Boolean = false,
    )
    private var attachmentRequest: MediaRequest? = null
    private var photoRequest: MediaRequest? = null
    @Volatile private var audioRequest: MediaRequest? = null
    private var exportGeneration: Long? = null
    private var importPending = false
    @Volatile private var audio: MemoryAudioRecorder? = null
    private var platform: PlatformActions? = null
    private var resultOwner = 0L
    private val _cameraRequest = MutableStateFlow<Long?>(null)
    val cameraRequest = _cameraRequest.asStateFlow()
    private val _state = MutableStateFlow(UiState(configured = Files.exists(directory.resolve("header.bin")), busy = true))
    val state = _state.asStateFlow()

    fun attachPlatform(actions: PlatformActions, restoredOwner: Long?): Long {
        if (restoredOwner == null || restoredOwner != resultOwner) {
            abandonPlatformRequests()
            resultOwner = requestSequence.incrementAndGet()
        }
        platform = actions
        return resultOwner
    }

    fun detachPlatform(actions: PlatformActions, finishing: Boolean) {
        if (platform !== actions) return
        platform = null
        if (finishing) { abandonPlatformRequests(); resultOwner = 0L }
    }

    private fun abandonPlatformRequests() {
        attachmentRequest = null
        exportGeneration = null
        pendingImportPassword?.fill('\u0000'); pendingImportPassword = null
        importPending = false
        if (audio == null) audioRequest = null
        cancelPhoto()
    }

    init {
        // A previous app process may have died before its Tor child received Binder death.
        // Keep unlock disabled until orphan teardown and interrupted-reset recovery finish.
        shuttingDown = true
        viewModelScope.launch(Dispatchers.IO) {
            var startupError: String? = null
            try {
                shutdownTransport(TorConnection(context))
                clearTorStateUntilDone()
                if (Files.exists(directory.resolveSibling(".${directory.fileName}.reset-backup"))) {
                    mutex.withLock { manager.recover(directory) }
                }
            } catch (_: Exception) { startupError = context.getString(R.string.error_pending_recovery) }
            finally { synchronized(lifecycleGuard) {
                shuttingDown = startupError != null
                _state.value = UiState(configured = Files.exists(directory.resolve("header.bin")), busy = shuttingDown, error = startupError)
            } }
        }
    }

    override fun setup(name: String, password: CharArray, confirm: CharArray, panicPassword: CharArray, panicConfirm: CharArray) {
        if (_state.value.busy || shuttingDown || _state.value.configured) {
            listOf(password, confirm, panicPassword, panicConfirm).forEach { it.fill('\u0000') }; return
        }
        if (!password.contentEquals(confirm) || !panicPassword.contentEquals(panicConfirm)) {
            listOf(password, confirm, panicPassword, panicConfirm).forEach { it.fill('\u0000') }
            error(context.getString(R.string.error_password_confirmation_mismatch))
            return
        }
        confirm.fill('\u0000'); panicConfirm.fill('\u0000')
        val ownedPassword = password.copyOf()
        val ownedPanicPassword = panicPassword.copyOf()
        val unlockCopy = password.copyOf()
        password.fill('\u0000'); panicPassword.fill('\u0000')
        val token = generation.get()
        _state.update { previous -> if (shuttingDown) previous else previous.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    check(!Files.exists(directory))
                    require(name.length <= 80)
                    manager.create(directory, ownedPassword, ownedPanicPassword)
                    val opened = manager.unlock(directory, unlockCopy)
                    if (generation.get() != token) { opened.close(); return@withLock }
                    session = opened
                    // A brand-new vault can never inherit a doorbell: `null` means "reuse nothing",
                    // so any child left over from a previous installation state is destroyed here.
                    prepareDoorbellHandoff(null)
                    db().putMeta("display_name", name.trim().ifEmpty { context.getString(R.string.default_you) }.toByteArray())
                    activate(opened, token)
                }
            } catch (e: Exception) {
                // Never log password or key material here - only the exception's own type/message,
                // and only in debug builds. Release keeps today's fully silent behavior.
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "setup failed: ${e.javaClass.name}: ${e.message}", e)
                }
                cleanupFailedActivation(token)
                error(context.getString(R.string.error_create_vault), token)
            }
            finally {
                listOf(ownedPassword, ownedPanicPassword, unlockCopy).forEach { it.fill('\u0000') }
                publish(token) { it.copy(configured = Files.exists(directory.resolve("header.bin")), busy = false) }
            }
        }.invokeOnCompletion { listOf(ownedPassword, ownedPanicPassword, unlockCopy).forEach { it.fill('\u0000') } }
    }

    override fun unlock(password: CharArray) {
        if (_state.value.busy || _state.value.unlocked || shuttingDown) { password.fill('\u0000'); return }
        val ownedPassword = password.copyOf()
        password.fill('\u0000')
        val token = generation.get()
        _state.update { previous -> if (shuttingDown) previous else previous.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val opened = manager.unlock(directory, ownedPassword)
                    if (generation.get() != token) { opened.close(); return@withLock }
                    session = opened
                    // Before anything touches the transport: either this unlock adopts the child a
                    // doorbell lock left running, or that child is destroyed outright. The panic
                    // password lands here too - it opens the decoy, a different slot, so it takes
                    // the destroy branch unconditionally.
                    prepareDoorbellHandoff(opened.slot)
                    activate(opened, token)
                }
            } catch (e: Exception) {
                // Never log password or key material here - only the exception's own type/message,
                // and only in debug builds. Release keeps today's fully silent behavior.
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "unlock failed: ${e.javaClass.name}: ${e.message}", e)
                }
                cleanupFailedActivation(token)
                error(context.getString(R.string.error_open_vault), token)
            } finally {
                ownedPassword.fill('\u0000')
                publish(token) { it.copy(busy = false) }
            }
        }.invokeOnCompletion { ownedPassword.fill('\u0000') }
    }

    private fun activate(opened: VaultSession, token: Long) {
        // Unconditional and idempotent: the doorbell notice must never survive an unlock, whether
        // or not anybody rang and whichever vault is being opened.
        notifications.cancelDoorbellPending()
        val database = db()
        val storedIdentity = database.getMeta("identity")
        val localIdentity = try { if (storedIdentity == null) PairingIdentity.create(crypto) else PairingIdentity.restore(storedIdentity) }
            finally { storedIdentity?.fill(0) }
        var seed = ByteArray(0)
        var published = false
        try {
        val storedSeed = database.getMeta("onion_seed")
        seed = storedSeed ?: crypto.random(32)
        if (storedSeed == null) database.putMeta("onion_seed", seed)
        val onion = TorNative.address(seed)
        // Every activation attempt re-reads the seed from the vault and wipes it again, so no copy
        // in the clear survives between retries.
        seed.fill(0)
        val identityBytes = localIdentity.export()
        try { database.putMeta("identity", identityBytes) } finally { identityBytes.fill(0) }
        consumed.clear()
        database.getMeta("pairing_consumed")?.let { blob ->
            try { consumed.addAll(blob.toString(Charsets.US_ASCII).lineSequence().filter { it.length == 64 }.take(4096).toList()) }
            finally { blob.fill(0) }
        }
        val localPairing = PairingEngine(crypto, localIdentity, onion, doorbellIdentityKey(database), consumed = consumed)
        val scope = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.IO)
        val localEngine = MessagingEngine(crypto, database, localIdentity, opened, directory, storage.mediaCapacityBytes, scope, mutex,
            onChanged = { synchronized(lifecycleGuard) {
                if (generation.get() == token && session != null && !shuttingDown) { refresh(); if (!foreground) notifications.show() }
            } },
            onError = { error(context.getString(R.string.error_group_operation), token) })
        val timeout = database.getMeta("lock_timeout")?.toString(Charsets.US_ASCII)?.toIntOrNull()?.coerceIn(5, 30) ?: 30
        val bridges = database.getMeta("bridges")?.toString(Charsets.UTF_8).orEmpty()
        // Read beside the other per-vault preferences, through the same accessor the lock path uses
        // (`database` here IS `storage.active`, see `db()` above), so there is exactly one reader of
        // the preference and one place where the ON default lives. No slot test on this path, so
        // the decoy publishes exactly what the real vault publishes.
        val doorbell = isDoorbellEnabled()
        // Skipped for a doorbell handoff: the child that is about to be reused never stopped, and
        // its Arti client still owns these files. Clearing them, or writing the vault's snapshot
        // over them, would pull the guard state out from under a live client - the very history
        // the snapshot exists to preserve. The checkpoints resume as soon as the reused transport
        // reports online, so nothing is lost by leaving the directory alone here.
        if (synchronized(lifecycleGuard) { handoff } == null) {
            TorStateSnapshot.clear(torStateDirectory)
            val savedTorState = database.getMeta(TorStateSnapshot.META_KEY)
            try { TorStateSnapshot.restore(torStateDirectory, savedTorState) }
            finally { savedTorState?.fill(0) }
        }
        torStatePrepared = true
        synchronized(lifecycleGuard) {
        if (generation.get() != token || shuttingDown) { scope.cancel(); localIdentity.close(); seed.fill(0); return }
        identity = localIdentity
        pairing = localPairing
        // The engine answers incoming BundleRequests straight out of the pairing engine. It is
        // called from `accept`, i.e. under this controller's mutex, which is the only lock
        // `localPairing` is ever mutated under.
        localEngine.attachPairingBundles { nonce -> localPairing.bundleFor(nonce) }
        sessionScope = scope
        // The transport is registered by the supervisor, one instance per attempt: a child process
        // that already ran START cannot be reused, so a retry always brings a fresh one.
        engine = localEngine
        // Not slot-branched, for the same reason `doorbell` just above is not: a decoy that hid this
        // button behind `opened.slot == VaultSlot.REAL` was a plaintext, code-visible oracle a
        // coerced inspection could read straight off the settings screen (T4.7). `changePanicPassword`
        // below still branches on the *session's* slot, but only to choose between the real reset and
        // an equally expensive no-op - never to change what the UI shows.
        _state.value = UiState(configured = true, unlocked = true, onion = onion, network = NetworkStatus.STARTING,
            lockTimeoutSeconds = timeout, bridges = bridges, doorbellEnabled = doorbell,
            canChangePanicPassword = true)
        published = true
        localEngine.start()
        scope.launch { superviseTransport(token, localEngine, bridges) }
        }
        refresh()
        } finally { if (!published) { seed.fill(0); localIdentity.close() } }
    }

    /**
     * Activation is supervised instead of one-shot. A bootstrap that fails, or a descriptor that
     * never gets published, now becomes RETRYING with a bounded exponential backoff, and a transport
     * that dies later is replaced. The loop runs inside the session scope, so the lock cancels it at
     * the next suspension point and no further child process is created.
     */
    private suspend fun superviseTransport(token: Long, localEngine: MessagingEngine, bridges: String) {
        val lines = bridges.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val supervisor = TransportSupervisor(
            // No notice on RETRYING: the banner already states that sends are waiting, while
            // `notice_tor_unavailable` tells the user to lock and reopen, which is only true for
            // the terminal ERROR below now that reconnection is automatic.
            onStatus = { status -> publish(token) { it.copy(network = status) } },
            sleep = { millis -> delay(millis) },
        )
        try {
            supervisor.supervise(
                attempt = { attemptTransport(token, localEngine, lines, supervisor) },
                hold = { holdTransport(token, supervisor) },
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            publish(token) { it.copy(network = NetworkStatus.ERROR,
                notice = context.getString(R.string.notice_tor_unavailable)) }
        }
    }

    /** One attempt: a fresh child process, the bootstrap, then the wait for a published descriptor. */
    private suspend fun attemptTransport(
        token: Long,
        localEngine: MessagingEngine,
        bridges: List<String>,
        supervisor: TransportSupervisor,
    ): TransportAttempt {
        // A doorbell handoff is consumed exactly once, by the first attempt: the child is already
        // bound, already bootstrapped and already registered as `transport`, so recycling it would
        // kill precisely what is being reused. Any later attempt finds no handoff and goes back to
        // the ordinary fresh-child path.
        val reused = synchronized(lifecycleGuard) { handoff.also { handoff = null } }?.takeIf { it.alive }
        if (BuildConfig.DEBUG) Log.d(TAG, "transport attempt: reusingDoorbellChild=${reused != null}")
        if (reused == null && !recycleTransport(token, localEngine)) return TransportAttempt.FAILED
        val tor = reused ?: TorConnection(context)
        synchronized(lifecycleGuard) {
            // Registered before it is started: whatever the lock captures here, it also kills.
            if (generation.get() != token || shuttingDown) return TransportAttempt.FAILED
            transport = tor
        }
        return try {
            // Not `error(...)`: this class has its own `error` member, which publishes to the UI.
            val seed = torSeed(token) ?: throw IllegalStateException("Sessão encerrada")
            try { if (reused != null) tor.restart(seed, bridges) else tor.start(seed, bridges) } finally { seed.fill(0) }
            // Order imposed by the native side: the messaging service must adopt the shared Arti
            // host BEFORE the doorbell releases it. Inverted, stopping the doorbell - the host's
            // only owner at that instant - disposes of the runtime and the unlock pays a full
            // bootstrap. Failure to stop it is not fatal: the doorbell is idle from here on.
            if (reused != null) try { tor.doorbellStop() } catch (_: Exception) { }
            // The guard set exists from the moment the client bootstraps. Checkpointing it here
            // means an unclean death during publication no longer costs the guards that the next
            // attempt reuses - and reusing them is what keeps a retry from looking, to the network,
            // like a brand new client picking fresh guards on every outage.
            checkpointTorState(token)
            supervisor.publishing()
            // A blown publication budget is not a dead transport. Up to PUBLICATION_ROUNDS budgets
            // are spent on the SAME bootstrapped client, because recreating the child would only
            // buy another 180s bootstrap before reaching this exact point again. The bound still
            // exists: an onion service that is wedged rather than slow is only repaired by a fresh
            // process, and the supervisor's backoff takes over once these rounds are used up.
            repeat(PUBLICATION_ROUNDS) {
                when (tor.awaitReady()) {
                    TorReadiness.READY -> { localEngine.attachTransport(tor); return TransportAttempt.ONLINE }
                    TorReadiness.PUBLISHING -> checkpointTorState(token)
                    TorReadiness.LOST -> return TransportAttempt.FAILED
                }
            }
            TransportAttempt.FAILED
        }
        // A blown bootstrap deadline is a TimeoutCancellationException, so it must be classified
        // before the cancellation branch: it is the failure this whole loop exists for, not a lock.
        catch (timeout: TimeoutCancellationException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "transport attempt: timed out (reused=${reused != null})")
            TransportAttempt.FAILED
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "transport attempt: failed (reused=${reused != null}): ${e.javaClass.name}: ${e.message}", e)
            TransportAttempt.FAILED
        }
    }

    /**
     * True once no `:tor` child is left behind. [TorService] refuses a second START in one process,
     * and a surviving child would be rebound by the next attempt, so a retry is only allowed after
     * the previous death is confirmed.
     */
    private suspend fun recycleTransport(token: Long, localEngine: MessagingEngine): Boolean {
        localEngine.attachTransport(null)
        val dying = synchronized(lifecycleGuard) { transport } ?: return true
        repeat(3) {
            try {
                dying.shutdown()
                // A confirmed death is the last moment the guard history on disk is known to be
                // complete and nobody is writing it, which is the same evidence `lock()` uses
                // before checkpointing. Without this, every reconnection silently discarded the
                // guard set promised by docs/security-model.md.
                checkpointTorState(token)
                // Identity plus generation: a newer session's transport is never unregistered here.
                synchronized(lifecycleGuard) {
                    if (generation.get() == token && transport === dying) transport = null
                }
                return true
            } catch (_: TimeoutCancellationException) { delay(1_000) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { delay(1_000) }
        }
        return false
    }

    /** The onion seed stays in the vault between attempts; the caller wipes this copy. */
    private suspend fun torSeed(token: Long): ByteArray? = mutex.withLock {
        if (generation.get() != token || shuttingDown) null else storage.active?.getMeta("onion_seed")
    }

    /** Guard history reaches SQLCipher only through here, and only for the session that owns it. */
    private suspend fun checkpointTorState(token: Long) {
        mutex.withLock { if (generation.get() == token && !shuttingDown) persistTorState() }
    }

    /**
     * Keeps guard history durable while the transport lives, reports reachability changes to the
     * UI, and returns as soon as the transport is gone so the supervisor can replace it.
     */
    private suspend fun holdTransport(token: Long, supervisor: TransportSupervisor) {
        while (true) {
            checkpointTorState(token)
            val tor = synchronized(lifecycleGuard) {
                if (generation.get() != token || shuttingDown) null else transport
            } ?: return
            // Death is awaited, not sampled once a minute: a child that dies right after a
            // checkpoint used to stay attached to the engine for the rest of the interval, with the
            // outbox spending its per-item cooldown against a transport nobody can reach.
            if (withTimeoutOrNull(HEARTBEAT_MILLIS) { tor.awaitDeath() } != null) return
            // Reachability is reversible: tor-hsservice can drop back to publishing long after the
            // first descriptor went out. Reporting it keeps the banner honest instead of leaving
            // "Tor conectado" on screen while nobody can reach this onion - the exact lie T2.1 was
            // written to remove. It is not a restart: the same client is still the right one.
            when (tor.awaitReady(REACHABILITY_PROBE_MILLIS)) {
                TorReadiness.READY -> supervisor.reachable()
                TorReadiness.PUBLISHING -> supervisor.publishing()
                TorReadiness.LOST -> return
            }
        }
    }

    override fun lock() {
        val lockToken: Long
        val oldTransport: TorConnection?
        synchronized(lifecycleGuard) {
        if (shuttingDown) return
        // Already locked in minimal mode: there is no session to close and the `:tor` child must be
        // left exactly as it is. Running the teardown again would clear the Tor state directory the
        // live doorbell is serving from.
        if (minimal != null && session == null) return
        shuttingDown = true
        lockToken = generation.incrementAndGet()
        backgroundLock?.cancel()
        notifications.clear()
        _state.value.attachment?.bytes?.fill(0)
        MediaPreviewCache.clear()
        AudioPlaybackCoordinator.reset()
        _cameraRequest.value = null
        _state.value = UiState(configured = Files.exists(directory.resolve("header.bin")), busy = true)
        pendingImportPassword?.fill('\u0000'); pendingImportPassword = null
        photoRequest = null
        sessionScope?.cancel()
        oldTransport = transport
        }
        viewModelScope.launch(Dispatchers.IO) {
            // The doorbell decision comes first, while the vault is still open: the seed and the
            // issued tokens are only readable here, and the `:tor` child is still alive. If it
            // takes, this lock keeps the child instead of killing it - everything below (the
            // forced teardown and the Tor state wipe) is exactly what must NOT happen then.
            var kept = false
            try { mutex.withLock {
                kept = startMinimalDoorbell(oldTransport)
                if (kept) closeOwnedSession()
            } } catch (_: Exception) { }
            if (BuildConfig.DEBUG) Log.d(TAG, "lock: minimal doorbell kept=$kept (${oldTransport?.diagnostics ?: "no transport"})")
            if (kept) {
                synchronized(lifecycleGuard) {
                    if (generation.get() == lockToken) {
                        shuttingDown = false
                        _state.value = UiState(configured = Files.exists(directory.resolve("header.bin")))
                    }
                }
                return@launch
            }
            var confirmedDeath = false
            try { oldTransport?.shutdown(); confirmedDeath = true }
            catch (_: Exception) { }
            finally { try { mutex.withLock {
                try { if (confirmedDeath) persistTorState() } finally { closeOwnedSession() }
            } } catch (_: Exception) { } }
            if (!confirmedDeath) shutdownTransport(oldTransport)
            clearTorStateUntilDone()
            synchronized(lifecycleGuard) {
                if (generation.get() == lockToken) {
                    shuttingDown = false
                    _state.value = UiState(configured = Files.exists(directory.resolve("header.bin")))
                }
            }
        }
    }

    /**
     * Turns an ordinary lock into a doorbell lock, or reports that it could not.
     *
     * Runs under the mutex with the vault still open, because everything it needs disappears one
     * statement later: the doorbell seed from `meta`, the tokens this device issued to each contact
     * from `contacts`, and the bridge lines the shared Arti host was built with.
     *
     * The order is forced by the native side and is not interchangeable: start the doorbell, load
     * the token set, and only then tear the messaging service down. `doorbellMinimal` refuses to
     * run while no doorbell is serving, precisely so this sequence cannot be written backwards.
     *
     * No branch anywhere in here looks at the vault slot. The decoy locks into minimal mode exactly
     * like the real vault, with its own seed, its own address and its own (usually empty) token
     * set - which is what makes the two indistinguishable to anybody holding the phone. For the
     * same reason an empty token set is NOT a reason to skip: a vault whose contacts predate the
     * feature still shows the same persistent notification as one that can actually be rung.
     */
    private suspend fun startMinimalDoorbell(tor: TorConnection?): Boolean {
        val opened = session ?: return false
        val database = storage.active ?: return false
        if (tor == null || !tor.alive) return false
        if (!doorbellEnabled(database)) return false
        return try {
            val bridges = database.getMeta("bridges")?.toString(Charsets.UTF_8).orEmpty()
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val seed = doorbellSeed(database)
            try { tor.doorbellStart(seed, bridges) } finally { seed.fill(0) }
            val tokens = database.listContacts().map { it.doorbellTokenIssued }.filter { it.size == DOORBELL_TOKEN_SIZE }
            try { tor.doorbellTokens(tokens) } finally { tokens.forEach { it.fill(0) } }
            tor.doorbellMinimal()
            synchronized(lifecycleGuard) { minimal = MinimalDoorbell(opened.slot, tor) }
            watchDoorbell(tor)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Half a doorbell is worse than none: a child left with a live onion and no watcher
            // would keep a notification on screen that nothing can ever clear.
            try { tor.doorbellStop() } catch (_: Exception) { }
            false
        }
    }

    /**
     * Drains accepted knocks for as long as minimal mode lasts and turns each batch into the single
     * fixed notice.
     *
     * Deliberately NOT in the session scope: that scope is cancelled by the very lock that starts
     * this loop. It belongs to the view model instead and is cancelled by hand on unlock or on
     * teardown. Long polls, so the process sleeps between knocks instead of waking on a timer.
     *
     * Any failure ends the loop silently. The only failures reachable here are a child that died
     * and a doorbell that was stopped, and in both cases there is nothing left to poll and nothing
     * the user could act on.
     */
    private fun watchDoorbell(tor: TorConnection) {
        doorbellWatch?.cancel()
        doorbellWatch = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    if (tor.doorbellPoll(DOORBELL_POLL_MILLIS) > 0) notifications.showDoorbellPending()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }

    /**
     * Decides what happens to a child kept alive by a doorbell lock, right before the vault that is
     * being opened is activated.
     *
     * Same vault ([slot] equal to the one recorded at lock time): the child is handed to the
     * activation, which restarts messaging on its still-bootstrapped host and only then stops the
     * doorbell.
     *
     * Any other case - a different slot, a `null` slot, a child that died meanwhile: total
     * teardown. This is the panic path as well, and it is not a gentler stop but the same forced
     * kill an ordinary lock performs, because the tokens loaded in that child belong to the vault
     * that is NOT being opened: leaving it serving would answer knocks addressed to a vault the
     * user just walked away from, in front of whoever made them type the panic password.
     */
    private suspend fun prepareDoorbellHandoff(slot: VaultSlot?) {
        val kept = synchronized(lifecycleGuard) { minimal.also { minimal = null } }
        doorbellWatch?.cancel()
        doorbellWatch = null
        if (kept == null) {
            // Only the booleans of the decision, never the slot's meaning to a bystander: which of
            // the two vaults is being opened is exactly what must not reach a log.
            if (BuildConfig.DEBUG) Log.d(TAG, "doorbell handoff: no kept child (minimal=null)")
            return
        }
        val sameSlot = slot != null && kept.slot == slot
        val alive = kept.transport.alive
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "doorbell handoff: reuse=${sameSlot && alive} sameSlot=$sameSlot " +
                "hasSlot=${slot != null} alive=$alive (${kept.transport.diagnostics})")
        }
        if (sameSlot && alive) {
            synchronized(lifecycleGuard) { handoff = kept.transport; transport = kept.transport }
            return
        }
        shutdownTransport(kept.transport)
    }

    private fun closeSession() {
        sessionScope?.cancel()
        _state.value.attachment?.bytes?.fill(0)
        // Every teardown path that reaches this point - lock(), a failed activation, an export, or
        // a panic-password change, all funnel through here via closeOwnedSession() - so clearing the
        // bounded audio-preview cache and the "who's playing" pointer here covers all of them.
        MediaPreviewCache.clear()
        AudioPlaybackCoordinator.reset()
        try {
            try { engine?.close() } finally { engine = null }
        } finally {
            try {
                try { bundleFetch?.cancel() } catch (_: Exception) { }
                bundleFetch = null
                try { pairing?.cancel(); persistIdentity() } catch (_: Exception) { }
                pairing = null; progress = null; confirmed = false; pairingAlias = ""
                try { identity?.close() } finally { identity = null }
            } finally {
                // `handoff` is cleared with the rest: a reuse that was offered but never consumed
                // (an activation that failed before the first attempt) must not be handed to the
                // next unlock, which may well be a different vault.
                try { session?.close() } finally { session = null; transport = null; handoff = null; consumed.clear(); torStatePrepared = false }
            }
        }
    }

    private suspend fun cleanupFailedActivation(token: Long) {
        val ownedTransport: TorConnection?
        synchronized(lifecycleGuard) {
            if (generation.get() != token || shuttingDown) return
            shuttingDown = true
            sessionScope?.cancel()
            _state.value.attachment?.bytes?.fill(0)
            _state.value = UiState(configured = Files.exists(directory.resolve("header.bin")), busy = true)
            ownedTransport = transport
        }
        shutdownTransport(ownedTransport)
        try { mutex.withLock { if (generation.get() == token) {
            try { persistTorState() } finally { closeOwnedSession() }
        } } } catch (_: Exception) { }
        clearTorStateUntilDone()
        synchronized(lifecycleGuard) { if (generation.get() == token) shuttingDown = false }
    }

    private suspend fun discardAudio() {
        val recorder = audio
        audio = null
        // A tombstone is needed only while Android still owes us a permission result.
        // Once consumed, a queued start must not survive a lock or a closed chat.
        if (audioRequest?.awaitingMicrophonePermission != true) audioRequest = null
        recorder?.discard()
    }

    private suspend fun closeOwnedSession() {
        try { discardAudio() } finally { closeSession() }
    }

    private suspend fun clearTorStateUntilDone() {
        while (true) {
            try { TorStateSnapshot.clear(torStateDirectory); return }
            catch (_: Exception) {
                _state.value = UiState(configured = Files.exists(directory.resolve("header.bin")), busy = true,
                    notice = context.getString(R.string.notice_cleanup_pending))
                delay(1000)
            }
        }
    }

    /** Never replace known guard history with an empty or inconsistent live snapshot. */
    private fun persistTorState() {
        if (!torStatePrepared) return
        val database = storage.active ?: return
        val snapshot = try { TorStateSnapshot.capture(torStateDirectory) } catch (_: Exception) { null } ?: return
        try { database.putMeta(TorStateSnapshot.META_KEY, snapshot) }
        catch (_: Exception) { /* Retain the last durable snapshot on capacity/I/O failure. */ }
        finally { snapshot.fill(0) }
    }

    private suspend fun shutdownTransport(tor: TorConnection?) {
        if (tor == null) return
        while (true) {
            try { tor.shutdown(); return }
            catch (_: Exception) {
                _state.value = UiState(configured = Files.exists(directory.resolve("header.bin")), busy = true,
                    notice = context.getString(R.string.notice_cleanup_pending))
                delay(1000)
            }
        }
    }

    fun onBackground() {
        foreground = false
        backgroundLock?.cancel()
        if (_state.value.unlocked || _state.value.busy) backgroundLock = viewModelScope.launch {
            delay(_state.value.lockTimeoutSeconds * 1000L)
            lock()
        }
    }
    fun onForeground() { foreground = true; backgroundLock?.cancel(); notifications.clear() }
    override fun clearError() { _state.update { previous -> if (shuttingDown) previous else previous.copy(error = null, notice = null) } }
    private fun publish(token: Long, transform: (UiState) -> UiState) {
        synchronized(lifecycleGuard) {
            if (generation.get() == token && !shuttingDown) _state.update(transform)
        }
    }
    private fun error(message: String, token: Long = generation.get()) {
        publish(token) { it.copy(error = message, busy = false) }
    }
    private fun db(): ChatDatabase = checkNotNull(storage.active) { context.getString(R.string.error_vault_locked) }
    private fun activeEngine(): MessagingEngine = checkNotNull(engine) { context.getString(R.string.error_vault_locked) }
    /**
     * T4.6: the localized string for a caught [MessagingEngine]/[MemoryAudioRecorder] failure. Every
     * catch site around them used to discard [Throwable.message] entirely and show the same generic
     * [fallback] regardless of cause - which is why `strings.xml` carried ~20 `error_*` strings with
     * no caller (`UnusedResources`). A [MessagingError] carries a [MessagingErrorCode] specific
     * enough to show distinctly (see that type's doc for which failures do, and why the receive loop
     * never throws one); anything else - including a plain [IllegalStateException]/
     * [IllegalArgumentException] the way `MessagingEngine` still throws for a failure nobody
     * observes - keeps showing [fallback], the same generic text this call site always showed.
     *
     * The code -> resource-id mapping itself lives in [errorResource], a plain function with no
     * [android.content.Context], so it can be unit tested on the JVM
     * (`NoMessagesControllerErrorMappingTest`) without Robolectric.
     */
    private fun errorMessage(failure: Throwable, fallback: Int): String =
        context.getString(errorResource((failure as? MessagingError)?.code, fallback))
    private fun action(block: () -> Unit) {
        val token = generation.get()
        if (!_state.value.unlocked) return
        viewModelScope.launch(Dispatchers.IO) {
            try { mutex.withLock { if (token == generation.get() && session != null) { block(); refresh() } } }
            catch (failure: Exception) { error(errorMessage(failure, R.string.error_operation_failed), token) }
        }
    }

    // `action` refreshes after the block, and `refresh` is what clears the local unread counter of
    // the selected chat, so opening a conversation marks it read without a second code path.
    override fun openChat(id: String) = action { _state.update { previous -> if (shuttingDown) previous else previous.copy(selectedChat = id) } }
    override fun closeChat() {
        val request = audioRequest
        request?.cancelled = true
        if (request != null) viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (audioRequest?.id == request.id) {
                    discardAudio()
                    publish(request.generation) { it.copy(recordingAudio = false) }
                }
            }
        }
        _state.update { previous -> if (shuttingDown) previous else previous.copy(selectedChat = null, messages = emptyList(),
            selectedGroupMembers = emptyList(), canManageSelectedGroup = false, selectedGroupStatus = GroupStatus.NONE) }
    }
    override fun sendText(text: String) {
        val recipient = _state.value.selectedChat ?: return
        action { activeEngine().sendText(recipient, text) }
    }
    override fun renameContact(id: String, alias: String) = action { db().renameContact(id, alias.trim()) }

    /**
     * This device's doorbell onion identity key, minting and storing its dedicated seed on first use.
     *
     * Reserved for T4.17: no service listens on this address yet, and nothing outside the pairing
     * offer reads it. It exists now so the QR format does not have to change a second time.
     *
     * The seed is its own `meta` record rather than a reuse of `onion_seed`, so the doorbell address
     * and the messaging address are unlinkable: deriving both from one seed would let anyone holding
     * either address confirm they belong to the same device. `meta` and not an `opaque_blobs`
     * namespace because `onion_seed` - the record this is a sibling of - lives in `meta`, and
     * `SPEC.md` §10 enumerates which table each runtime record belongs to; a sibling in the other
     * table would contradict it for no gain. A vault created before T4.16 simply has no such key
     * and mints one here the first time it is unlocked, which is the same on-demand generation the
     * messaging seed already uses.
     *
     * Never returns the seed: the caller gets the public key, and the seed copy is wiped here.
     */
    private fun doorbellIdentityKey(database: ChatDatabase): ByteArray {
        val seed = doorbellSeed(database)
        return try { OnionAddress.publicKey(TorNative.address(seed)) } finally { seed.fill(0) }
    }

    /**
     * This vault's doorbell seed, minted and stored on first use.
     *
     * The only place that hands the seed itself out, and it hands out a copy the caller MUST wipe -
     * the same contract `activate` already honours for `onion_seed`. Both readers wipe immediately
     * after the seed has crossed into the native layer, so no cleartext copy outlives the call:
     * [doorbellIdentityKey] needs it only to derive the public address, and [startMinimalDoorbell]
     * only to hand it to the `:tor` child that will serve on it.
     */
    private fun doorbellSeed(database: ChatDatabase): ByteArray {
        val stored = database.getMeta(DOORBELL_SEED_KEY)
        val seed = stored ?: crypto.random(32)
        if (stored == null) database.putMeta(DOORBELL_SEED_KEY, seed)
        return seed
    }

    /**
     * Whether this vault keeps its doorbell serving while locked. Defaults to ON.
     *
     * ON by default because the notice IS the feature: a vault that locks with the doorbell off
     * behaves exactly like one that never had it, and a user who never opens Settings would only
     * ever discover the feature by missing messages. The cost of the default is one silent
     * notification while locked, which the same user can switch off in one tap; the cost of the
     * opposite default is a feature that only works for whoever went looking for it.
     *
     * Stored in `opaque_blobs` under [VAULT_SETTINGS_NAMESPACE] rather than in `meta`, because
     * `meta` holds structural runtime records - seeds, identity, guard history, the outbox sequence
     * - while this is a user preference of the same kind the settings screen already edits. The
     * namespace is new: none of the 14 catalogued ones (`group_*`, `outbox_*`, `attempts`,
     * `accepted_ids`, `rejected_ids`, `receipts`, `failed_controls`, `evidence_gossip`, ...) is
     * about user preferences, and reusing one of them would make `listBlobs` on it return a row no
     * reader of that namespace expects.
     *
     * Reads the vault that is open, whichever it is: an absent key is the default in both, so the
     * decoy answers exactly like the real vault without a single slot test.
     */
    fun isDoorbellEnabled(): Boolean = try { storage.active?.let { doorbellEnabled(it) } ?: DOORBELL_DEFAULT_ENABLED }
        catch (_: Exception) { DOORBELL_DEFAULT_ENABLED }

    /**
     * Persists the preference for the open vault and republishes it to the settings screen.
     *
     * The state update mirrors [setLockTimeout]: the switch is driven by [UiState], so without it
     * the toggle would snap back to the stored value until the next activation. It is skipped while
     * `shuttingDown`, for the same reason the other setters skip it - a lock/panic in flight has
     * already published the state the next screen must show, and this must not overwrite it.
     *
     * No slot test: the write lands in whichever vault is open, exactly like the read in
     * [isDoorbellEnabled], which is what makes the decoy indistinguishable here.
     */
    override fun setDoorbellEnabled(enabled: Boolean) = action {
        db().putBlob(VAULT_SETTINGS_NAMESPACE, DOORBELL_ENABLED_KEY, byteArrayOf(if (enabled) 1 else 0))
        _state.update { previous -> if (shuttingDown) previous else previous.copy(doorbellEnabled = enabled) }
    }

    private fun doorbellEnabled(database: ChatDatabase): Boolean {
        val stored = database.getBlob(VAULT_SETTINGS_NAMESPACE, DOORBELL_ENABLED_KEY) ?: return DOORBELL_DEFAULT_ENABLED
        return try { stored.isNotEmpty() && stored[0].toInt() != 0 } finally { stored.fill(0) }
    }

    /**
     * Publishes a fresh offer QR.
     *
     * Called both by the button and by the screen's automatic refresh once the 120 s reading window
     * of the previous QR runs out (see `PairingLifecycle.nextAction`). `createOffer` burns the
     * pending exchange but retains the offer it replaces as answerable, so a peer that scanned the
     * previous QR in its last seconds can still complete its bundle fetch.
     */
    override fun showPairing() = action {
        bundleFetch?.cancel(); bundleFetch = null
        progress = null; confirmed = false; pairingAlias = ""
        val code = checkNotNull(pairing).createOffer()
        persistIdentity()
        _state.update { previous -> if (shuttingDown) previous else previous.copy(
            pairing = PairingUi(code, expiresAt = System.currentTimeMillis() + PairingLifecycle.OFFER_TTL_MILLIS)) }
    }
    override fun readPairing(code: String) = action {
        if (_state.value.pairing?.completed == true) return@action
        val pairingEngine = checkNotNull(pairing)
        // Reference the engine constant instead of repeating the literal, so this copy can never drift
        // out of sync with what PairingEngine actually emits and silently fall through to the offer path.
        if (code.startsWith(PairingEngine.CONFIRM_PREFIX)) {
            val current = requireNotNull(progress)
            check(confirmed)
            require(db().getContact(current.peerId) != null || db().listContacts().size < MessagingPolicy.maxContacts) {
                context.getString(R.string.error_contact_limit)
            }
            val snapshot = checkNotNull(identity).export()
            try {
                val contact = pairingEngine.finish(current.handle, code)
                db().transaction {
                    // The peer's doorbell address and token come from its signed offer, so they are
                    // covered by the SAS the two humans just compared. They are stored now because
                    // there is no second authenticated channel to obtain them on later; nothing
                    // reads them until T4.17.
                    //
                    // `doorbellTokenIssued` is the other direction of the same exchange: the secret
                    // THIS device minted inside its own offer for this one peer, which is what an
                    // incoming ring from it will present. `finish` is the last moment it exists -
                    // it lived only inside the pairing offer, which is discarded here - so failing
                    // to persist it in this very statement loses it for good and leaves the contact
                    // able to be rung but unable to be answered. Schema v4 exists for this column.
                    putContact(ContactRecord(contact.peerId, pairingAlias.ifBlank { context.getString(R.string.default_contact_alias, contact.peerId.take(6)) },
                        contact.onion, contact.publicKey, checkNotNull(identity).sessions.peerIdentity(contact.peerId), contact.pairedAt * 1000,
                        doorbellOnion = OnionAddress.address(contact.doorbellKey), doorbellToken = contact.doorbellToken,
                        doorbellTokenIssued = contact.doorbellTokenIssued))
                    val myId = checkNotNull(identity).id
                    putPairEvidence(PairEvidence(minOf(myId, contact.peerId), maxOf(myId, contact.peerId), contact.evidence, contact.pairedAt * 1000))
                    persistIdentity()
                }
                progress = null; confirmed = false
                bundleFetch?.cancel(); bundleFetch = null
                _state.update { previous -> if (shuttingDown) previous else previous.copy(
                    pairing = previous.pairing?.copy(completed = true, waitingForPeer = false),
                    notice = context.getString(R.string.notice_contact_saved)) }
            } catch (failure: Exception) {
                checkNotNull(identity).restoreInPlace(snapshot)
                throw failure
            } finally { snapshot.fill(0) }
            activeEngine().syncEvidence(current.peerId)
        } else {
            val next = pairingEngine.receive(code)
            progress = next; confirmed = false
            persistIdentity()
            _state.update { previous -> if (shuttingDown) previous else previous.copy(pairing = PairingUi(next.responseQr, next.sas, next.peerId,
                expiresAt = next.expiresAt * 1000, bundleStatus = PairingBundleStatus.WAITING_FOR_TOR)) }
            // QR format 2 carries only the hash of the peer's key bundle, so the bundle itself is
            // fetched from the onion the QR already named. Directionality falls out of who scanned:
            // whoever just read a QR fetches from whoever showed it.
            startBundleFetch(next)
        }
    }
    override fun confirmPairing(alias: String) = action {
        if (_state.value.pairing?.completed == true || confirmed) return@action
        require(alias.trim().length in 1..80)
        val current = checkNotNull(progress)
        // Checked here and not only at `finish`: the user should learn that the other phone is
        // unreachable while the retry button is still on screen, not after both confirmation QRs
        // have been exchanged and there is nothing left to do but start over.
        require(current.peerBundleReady) { context.getString(R.string.error_pairing_bundle_missing) }
        val confirmation = checkNotNull(pairing).confirm(current.handle, true)
        pairingAlias = alias.trim(); confirmed = true
        persistIdentity()
        _state.update { previous -> if (shuttingDown) previous else previous.copy(pairing = checkNotNull(_state.value.pairing).copy(offer = confirmation, waitingForPeer = true)) }
    }
    override fun cancelPairing() = action {
        bundleFetch?.cancel(); bundleFetch = null
        pairing?.cancel(); progress = null; confirmed = false; persistIdentity()
        _state.update { previous -> if (shuttingDown) previous else previous.copy(pairing = null) }
    }

    override fun retryPairingBundle() {
        val current = progress ?: return
        if (current.peerBundleReady) return
        if (bundleFetch?.isActive == true) return
        startBundleFetch(current)
    }

    /**
     * Fetches the peer's key bundle over Tor and hands it to the pairing engine.
     *
     * Runs in the session scope **without** the mutex while it waits. The peer is running the
     * mirror-image fetch against this device at the same time, and answering it needs that same
     * mutex, so holding it across the circuit build would deadlock two phones that scanned each
     * other. The lock is taken only for the short verification/persist step at the end.
     *
     * Failures are retried until the exchange deadline rather than surfaced immediately: a freshly
     * published onion service often needs a few attempts before it is reachable, and the deadline
     * is exactly the budget `PairingEngine` will honour anyway. The arithmetic of that window -
     * "is there time for another attempt", "how much budget does this one get", "how long to pause"
     * - lives in [BundleFetchPolicy], which is pure and unit-tested; this function only performs it.
     */
    private fun startBundleFetch(target: PairingProgress) {
        val token = generation.get()
        val scope = sessionScope ?: return
        val handle = target.handle
        val onion = target.peerOnion
        val nonce = target.peerBundleNonce.copyOf()
        val deadline = target.expiresAt * 1000
        bundleFetch?.cancel()
        bundleFetch = scope.launch(Dispatchers.IO) {
            try {
                while (BundleFetchPolicy.shouldAttempt(deadline, System.currentTimeMillis())) {
                    val live = engine ?: break
                    if (!live.transportAttached) {
                        publishBundleStatus(token, handle, PairingBundleStatus.WAITING_FOR_TOR)
                        delay(TOR_POLL_MILLIS)
                        continue
                    }
                    publishBundleStatus(token, handle, PairingBundleStatus.FETCHING)
                    val budget = BundleFetchPolicy.attemptBudget(deadline, System.currentTimeMillis())
                    if (budget <= 0L) break
                    val bundle = try {
                        live.fetchPeerBundle(onion, nonce, budget)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        // T4.18: without this line a ceremony that expired to a cold Tor left no
                        // trace at all, so field diagnosis could not tell "onion not published yet"
                        // from "circuit timed out" from "peer answered garbage". Exception type and
                        // message only, and only in debug builds: the onion, the nonce, the bundle
                        // and every other piece of pairing metadata stay out of logcat, and release
                        // keeps today's fully silent behavior.
                        if (BuildConfig.DEBUG) {
                            Log.e(TAG, "bundle fetch attempt failed: ${e.javaClass.name}: ${e.message}")
                        }
                        val pause = BundleFetchPolicy.retryDelay(deadline, System.currentTimeMillis()) ?: break
                        delay(pause)
                        continue
                    }
                    var accepted = false
                    try {
                        mutex.withLock {
                            if (token != generation.get() || session == null || progress?.handle != handle) return@withLock
                            // A mismatch here means the bytes did not hash to what the peer signed:
                            // acceptPeerBundle burns the exchange, and this must surface as a
                            // pairing failure, never as another retry.
                            progress = checkNotNull(pairing).acceptPeerBundle(handle, bundle)
                            persistIdentity()
                            accepted = true
                            refresh()
                        }
                    } finally { bundle.fill(0) }
                    if (accepted) publishBundleStatus(token, handle, PairingBundleStatus.READY)
                    return@launch
                }
                publishBundleStatus(token, handle, PairingBundleStatus.FAILED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishBundleStatus(token, handle, PairingBundleStatus.FAILED)
            } finally { nonce.fill(0) }
        }
    }

    /** Only touches the banner while [handle] is still the exchange on screen. */
    private fun publishBundleStatus(token: Long, handle: String, status: PairingBundleStatus) {
        publish(token) { previous ->
            val current = previous.pairing
            if (current == null || current.completed || progress?.handle != handle) previous
            else previous.copy(pairing = current.copy(bundleStatus = status))
        }
    }
    private fun persistIdentity() {
        val local = identity ?: return
        val database = storage.active ?: return
        val bytes = local.export()
        try {
            database.putMeta("identity", bytes)
            database.putMeta("pairing_consumed", consumed.takeLastBounded(4096).joinToString("\n").toByteArray(Charsets.US_ASCII))
        } finally { bytes.fill(0) }
    }

    override fun checkGroup(members: List<String>) {
        val requestedMembers = members.toList()
        action {
            val all = listOf(checkNotNull(identity).id) + requestedMembers
            val selected = all.toSet()
            val missing = if (all.size < 3) listOf(context.getString(R.string.group_select_two_contacts)) else
                CliquePolicy(crypto).missingPairs(all, db().listPairEvidence().filter { it.firstId in selected && it.secondId in selected }.map { it.evidence }).map {
                    "${alias(it.first)} ↔ ${alias(it.second)}"
                }
            _state.update { previous -> if (shuttingDown) previous else previous.copy(missingPairs = missing,
                groupCheckRevision = previous.groupCheckRevision + 1, groupCheckMembers = requestedMembers.toSet()) }
        }
    }
    override fun createGroup(name: String, members: List<String>) {
        val requestedMembers = members.toList()
        action {
            val groupId = activeEngine().createGroup(name, requestedMembers)
            _state.update { previous -> if (shuttingDown) previous else previous.copy(selectedChat = groupId,
                notice = context.getString(R.string.notice_group_requested), missingPairs = emptyList()) }
        }
    }
    override fun removeMember(groupId: String, contactId: String) = action { activeEngine().removeMember(groupId, contactId) }
    override fun leaveGroup(groupId: String) = action { activeEngine().leaveGroup(groupId); closeChat() }
    private fun alias(id: String) = if (id == identity?.id) context.getString(R.string.default_you) else db().getContact(id)?.alias ?: id.take(12)

    override fun exportVault() {
        if (_state.value.unlocked && exportGeneration == null) { exportGeneration = generation.get(); platform?.chooseExport() }
    }
    override fun importVault(password: CharArray) {
        if (_state.value.configured || _state.value.busy || importPending) { password.fill('\u0000'); return }
        pendingImportPassword?.fill('\u0000'); pendingImportPassword = password.copyOf()
        password.fill('\u0000')
        importPending = true
        platform?.chooseImport() ?: run { pendingImportPassword?.fill('\u0000'); pendingImportPassword = null; importPending = false }
    }
    fun acceptImport(owner: Long, uri: Uri?) {
        if (owner != resultOwner || owner == 0L) return
        importPending = false
        val password = pendingImportPassword ?: return
        pendingImportPassword = null
        if (uri == null) { password.fill('\u0000'); return }
        _state.update { previous -> if (shuttingDown) previous else previous.copy(busy = true, error = null) }
        val token = generation.get()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    check(session == null && !Files.exists(directory))
                    context.contentResolver.openInputStream(uri).use { input -> VaultArchive(crypto, storage).import(requireNotNull(input), directory, password) }
                }
                publish(token) { UiState(configured = true, notice = context.getString(R.string.notice_vault_imported)) }
            } catch (_: Exception) { error(context.getString(R.string.error_vault_import), token) }
            finally { password.fill('\u0000'); publish(token) { it.copy(busy = false) } }
        }.invokeOnCompletion { password.fill('\u0000') }
    }
    fun acceptExport(owner: Long, uri: Uri?) {
        if (owner != resultOwner || owner == 0L) return
        val requested = exportGeneration
        exportGeneration = null
        if (uri == null || !_state.value.unlocked || requested != generation.get()) return
        synchronized(lifecycleGuard) { if (shuttingDown) return; shuttingDown = true; generation.incrementAndGet() }
        _state.value.attachment?.bytes?.fill(0)
        _state.value = UiState(configured = true, busy = true)
        sessionScope?.cancel()
        val tor = transport
        viewModelScope.launch(Dispatchers.IO) {
            var committed = false
            try {
                shutdownTransport(tor)
                mutex.withLock {
                    discardAudio()
                    val opened = checkNotNull(session)
                    persistTorState()
                    engine?.close(); engine = null
                    pairing?.cancel(); persistIdentity()
                    context.contentResolver.openOutputStream(uri, "wt").use { output ->
                        VaultArchive(crypto, storage).export(directory, opened, requireNotNull(output))
                    }
                    committed = true
                }
            } catch (_: Exception) { }
            finally {
                try { mutex.withLock { closeOwnedSession() } } catch (_: Exception) { }
                clearTorStateUntilDone()
                synchronized(lifecycleGuard) {
                    shuttingDown = false
                    _state.value = if (committed) UiState(configured = true, notice = context.getString(R.string.notice_vault_exported))
                        else UiState(configured = true, error = context.getString(R.string.error_vault_export))
                }
            }
        }
    }
    override fun setLockTimeout(seconds: Int) = action {
        require(seconds in 5..30)
        db().putMeta("lock_timeout", seconds.toString().toByteArray())
        _state.update { previous -> if (shuttingDown) previous else previous.copy(lockTimeoutSeconds = seconds) }
    }
    override fun setBridges(bridges: String) = action {
        require(bridges.length <= 16_384)
        require(bridges.lineSequence().count() <= 32)
        db().putMeta("bridges", bridges.toByteArray())
        _state.update { previous -> if (shuttingDown) previous else previous.copy(bridges = bridges, notice = context.getString(R.string.notice_bridges_saved)) }
    }
    override fun changePanicPassword(password: CharArray, confirm: CharArray) {
        if (!password.contentEquals(confirm) || !_state.value.canChangePanicPassword) {
            password.fill('\u0000'); confirm.fill('\u0000'); error(context.getString(R.string.error_confirmation_invalid)); return
        }
        confirm.fill('\u0000')
        val ownedPassword = password.copyOf()
        password.fill('\u0000')
        synchronized(lifecycleGuard) {
            if (shuttingDown) { ownedPassword.fill('\u0000'); return }
            shuttingDown = true; generation.incrementAndGet()
        }
        _state.value.attachment?.bytes?.fill(0)
        _state.value = UiState(configured = true, busy = true)
        sessionScope?.cancel()
        val tor = transport
        // Captured before the teardown below can touch `session`: which branch runs must be decided
        // by the slot this session actually opened, not by anything the UI passed in - the dialog and
        // its `onSubmit` are identical for both (see `canChangePanicPassword` above).
        val isRealSession = session?.slot == VaultSlot.REAL
        viewModelScope.launch(Dispatchers.IO) {
            var committed = false
            try {
                shutdownTransport(tor)
                mutex.withLock {
                    discardAudio()
                    engine?.close(); engine = null
                    pairing?.cancel(); persistIdentity()
                    persistTorState()
                    if (isRealSession) {
                        manager.resetPanicPassword(directory, checkNotNull(session), ownedPassword)
                    } else {
                        // Decoy: no real session to replace a panic password on - only the real vault
                        // has one. Pays the identical Argon2id cost `resetPanicPassword` pays and
                        // touches nothing on disk; see `VaultManager.fakePanicPasswordChange` and
                        // security-model.md ("Oráculos de isca", T4.7).
                        manager.fakePanicPasswordChange(directory, ownedPassword)
                    }
                    committed = true
                }
            } catch (_: Exception) { }
            finally {
                ownedPassword.fill('\u0000')
                try { mutex.withLock { closeOwnedSession() } } catch (_: Exception) { }
                clearTorStateUntilDone()
                synchronized(lifecycleGuard) {
                    shuttingDown = false
                    _state.value = if (committed) UiState(configured = true, notice = context.getString(R.string.notice_panic_password_changed))
                        else UiState(configured = true, error = context.getString(R.string.error_panic_password_change))
                }
            }
        }.invokeOnCompletion { ownedPassword.fill('\u0000') }
    }

    override fun pickAttachment() {
        val chat = _state.value.selectedChat ?: return
        if (_state.value.unlocked && attachmentRequest == null) {
            attachmentRequest = MediaRequest(generation.get(), chat, requestSequence.incrementAndGet()); platform?.chooseAttachment()
        }
    }
    fun acceptAttachment(owner: Long, uri: Uri?) {
        if (owner != resultOwner || owner == 0L) return
        val request = attachmentRequest ?: return
        attachmentRequest = null
        if (uri == null || request.generation != generation.get()) return
        val token = request.generation
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var name = "arquivo"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0) ?: name
                        if (!cursor.isNull(1)) require(cursor.getLong(1) <= MAX_ATTACHMENT)
                    }
                }
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    val output = SensitiveBuffer(MAX_ATTACHMENT)
                    val chunk = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            check(token == generation.get())
                            output.write(chunk, 0, count)
                        }
                        output.toByteArray()
                    } finally { chunk.fill(0); output.close() }
                }
                try { mutex.withLock {
                    if (token == generation.get() && session != null) {
                        activeEngine().sendAttachment(request.chat, name.take(160), mime, bytes)
                        refresh()
                    }
                } } finally { bytes.fill(0) }
            } catch (failure: Exception) { error(errorMessage(failure, R.string.error_attachment_limit), token) }
        }
    }
    override fun capturePhoto() {
        val chat = _state.value.selectedChat ?: return
        if (_state.value.unlocked) {
            photoRequest = MediaRequest(generation.get(), chat, requestSequence.incrementAndGet())
            _cameraRequest.value = photoRequest?.id
        }
    }
    fun cancelPhoto() { _cameraRequest.value = null; photoRequest = null }
    fun acceptPhoto(requestId: Long, bytes: ByteArray) {
        val request = photoRequest
        if (request == null || request.id != requestId || request.generation != generation.get()) { bytes.fill(0); return }
        _cameraRequest.value = null
        photoRequest = null
        sendMediaBytes("foto.jpg", "image/jpeg", bytes, request)
    }
    private fun sendMediaBytes(name: String, mime: String, bytes: ByteArray, request: MediaRequest) {
        val token = request.generation
        viewModelScope.launch(Dispatchers.IO) {
            try { mutex.withLock {
                if (token == generation.get() && session != null) {
                    activeEngine().sendAttachment(request.chat, name, mime, bytes); refresh()
                }
            } } catch (failure: Exception) { error(errorMessage(failure, R.string.error_attachment_send), token) }
            finally { bytes.fill(0) }
        }.invokeOnCompletion { bytes.fill(0) }
    }
    override fun startAudio() {
        val chat = _state.value.selectedChat ?: return
        val currentPlatform = platform ?: return
        if (_state.value.unlocked && audioRequest == null) {
            val request = MediaRequest(generation.get(), chat, requestSequence.incrementAndGet(), awaitingMicrophonePermission = true)
            audioRequest = request
            try { currentPlatform.requestMicrophone() }
            catch (_: Exception) {
                if (audioRequest === request) audioRequest = null
                error(context.getString(R.string.error_microphone_permission), request.generation)
            }
        }
    }
    fun microphonePermission(owner: Long, granted: Boolean) {
        if (owner != resultOwner || owner == 0L) return
        val request = audioRequest ?: return
        if (!request.awaitingMicrophonePermission) return
        request.awaitingMicrophonePermission = false
        if (!_state.value.unlocked || request.generation != generation.get() || request.cancelled) {
            if (audioRequest === request) audioRequest = null
            return
        }
        action {
        if (audioRequest !== request) return@action
        if (request.generation != generation.get() || request.cancelled) { audioRequest = null; return@action }
        if (!granted) { audioRequest = null; error(context.getString(R.string.error_microphone_permission)); return@action }
        audio?.let { return@action }
        try { audio = MemoryAudioRecorder().also { it.start(checkNotNull(sessionScope)) } }
        catch (failure: Throwable) { audioRequest = null; throw failure }
        _state.update { previous -> if (shuttingDown) previous else previous.copy(recordingAudio = true) }
        }
    }
    override fun stopAudio() {
        val token = generation.get()
        viewModelScope.launch(Dispatchers.IO) {
            try { mutex.withLock {
                val current = audio ?: return@withLock
                val request = audioRequest
                try {
                    val recording = current.finish()
                    try {
                        if (request != null && !request.cancelled && request.generation == token && generation.get() == token && !shuttingDown && session != null) {
                            // AAC/M4A first (smaller, and playable everywhere the app itself plays
                            // audio); WAV only if this device's MediaCodec/MediaMuxer/memfd combo
                            // failed to produce it (see MemoryAudioEncoder's doc comment).
                            val aac = recording.aacBytes
                            val fileId = if (aac != null) activeEngine().sendAttachment(request.chat, "audio.m4a", "audio/mp4", aac)
                                else activeEngine().sendAttachment(request.chat, "audio.wav", "audio/wav", recording.wavBytes)
                            // The sender already has the waveform from the raw PCM it just recorded -
                            // cache it under the new attachment's id so this device's own bubble never
                            // has to re-decrypt-and-decode its own outgoing message.
                            MediaPreviewCache.put(fileId, MediaPreviewPayload.Waveform(recording.waveform, recording.durationMs))
                        }
                    } finally { recording.wavBytes.fill(0); recording.aacBytes?.fill(0) }
                } finally { audio = null; audioRequest = null; current.discard() }
                _state.update { previous -> if (shuttingDown) previous else previous.copy(recordingAudio = false) }
                if (token == generation.get() && session != null) refresh()
            } } catch (failure: Exception) { error(errorMessage(failure, R.string.error_audio_finish), token) }
        }
    }
    override fun openAttachment(id: String) = action {
        val token = generation.get()
        _state.value.attachment?.bytes?.fill(0)
        val (record, bytes) = activeEngine().decryptAttachment(id)
        // Sibling image ids for the swipe-between-images gallery: only populated when the opened
        // attachment is itself an image, and only when there is more than one to swipe to. Derived
        // straight from the already-loaded message list - no extra query - via the pure
        // `imageGalleryIds` in UiLogic.kt.
        val gallery = if (attachmentKind(record.mimeType) == AttachmentKind.IMAGE) {
            imageGalleryIds(_state.value.messages).takeIf { it.size > 1 } ?: emptyList()
        } else emptyList()
        synchronized(lifecycleGuard) {
            if (token == generation.get() && !shuttingDown && _state.value.unlocked)
                _state.update { previous -> if (shuttingDown) previous else previous.copy(attachment = AttachmentUi(id, record.displayName, record.mimeType, bytes), imageGallery = gallery) }
            else bytes.fill(0)
        }
    }
    override fun closeAttachment() {
        _state.value.attachment?.bytes?.fill(0)
        _state.update { previous -> if (shuttingDown) previous else previous.copy(attachment = null, imageGallery = emptyList()) }
    }
    /**
     * Forwards one stored message into every selected chat.
     *
     * Runs inside [action], so it already holds the session mutex and refreshes the UI afterwards.
     * Each destination gets a brand-new envelope built by [MessagingEngine]: a new message id, a new
     * timestamp and - for an attachment - a freshly generated file key and file id, since the
     * attachment is re-encrypted from its plaintext rather than relayed as stored ciphertext. So a
     * recipient can tell the message was forwarded (the flag) but learns nothing about where from.
     *
     * The flag itself is decided per destination by [shouldMarkForwarded]: a copy that lands back in
     * the very chat the original already lives in (`record.peerOrGroup`) is indistinguishable from
     * retyping the message, so it is sent as an ordinary, unlabelled message. The same chat may
     * still be picked as a destination - it just does not earn the "Forwarded" label.
     *
     * Forwarding is a normal send: [MessagingEngine.sendApplication] queues into the outbox, which
     * `outboxLoop` drains only while a Tor transport exists, so with the network down every copy
     * simply waits as PENDING - no extra pause handling is needed here.
     */
    override fun forwardMessage(messageId: String, targetChatIds: List<String>) = action {
        // One send per chat even if the picker somehow yields the same chat twice.
        val targets = targetChatIds.distinct().filter { it.isNotBlank() }
        if (targets.isEmpty()) return@action
        val record = db().getMessage(messageId) ?: return@action
        val decoded = try { EnvelopeCodec.decode(record.body) } finally { record.body.fill(0) }
        when (decoded) {
            is Envelope.Text -> targets.forEach { target ->
                activeEngine().sendText(target, decoded.body, forwarded = shouldMarkForwarded(record.peerOrGroup, target))
            }
            is Envelope.Attachment -> {
                // Stored history keeps an empty ciphertext and a zeroed file key (see
                // MessagingEngine.history), so the bytes have to come from the encrypted file store.
                val attachmentId = decoded.fileId.joinToString("") { "%02x".format(it.toInt() and 255) }
                val (file, bytes) = activeEngine().decryptAttachment(attachmentId)
                try {
                    targets.forEach { target ->
                        activeEngine().sendAttachment(
                            target, file.displayName, file.mimeType, bytes,
                            forwarded = shouldMarkForwarded(record.peerOrGroup, target))
                    }
                } finally { bytes.fill(0) }
            }
            // Only chat messages ever become a MessageUi, so no other envelope should reach this
            // point; ignoring it keeps a malformed or foreign row from turning into an error toast.
            else -> Unit
        }
    }
    override suspend fun loadAudioPreview(attachmentId: String): AudioPreviewUi? {
        (MediaPreviewCache.get(attachmentId) as? MediaPreviewPayload.Waveform)?.let {
            return AudioPreviewUi(it.buckets, it.durationMs)
        }
        if (!_state.value.unlocked) return null
        val token = generation.get()
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    if (token != generation.get() || shuttingDown || session == null) return@withLock null
                    val (record, bytes) = activeEngine().decryptAttachment(attachmentId)
                    try {
                        val decoded = decodeAudioWaveform(bytes, record.mimeType) ?: return@withLock null
                        MediaPreviewCache.put(attachmentId, MediaPreviewPayload.Waveform(decoded.waveform, decoded.durationMs))
                        AudioPreviewUi(decoded.waveform, decoded.durationMs)
                    } finally { bytes.fill(0) }
                }
            } catch (_: Exception) { null }
        }
    }
    override suspend fun loadAudioBytes(attachmentId: String): AudioBytesUi? {
        if (!_state.value.unlocked) return null
        val token = generation.get()
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    if (token != generation.get() || shuttingDown || session == null) return@withLock null
                    val (record, bytes) = activeEngine().decryptAttachment(attachmentId)
                    AudioBytesUi(record.mimeType, bytes)
                }
            } catch (_: Exception) { null }
        }
    }
    override suspend fun loadImagePreview(attachmentId: String): ImagePreviewUi? {
        (MediaPreviewCache.get(attachmentId) as? MediaPreviewPayload.Thumbnail)?.let {
            return ImagePreviewUi(it.bitmap)
        }
        if (!_state.value.unlocked) return null
        val token = generation.get()
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    if (token != generation.get() || shuttingDown || session == null) return@withLock null
                    val (record, bytes) = activeEngine().decryptAttachment(attachmentId)
                    try {
                        val thumbnail = decodeBoundedThumbnail(bytes) ?: return@withLock null
                        MediaPreviewCache.put(attachmentId, MediaPreviewPayload.Thumbnail(thumbnail))
                        ImagePreviewUi(thumbnail)
                    } finally { bytes.fill(0) }
                }
            } catch (_: Exception) { null }
        }
    }

    override suspend fun loadVideoPreview(attachmentId: String): VideoPreviewUi? {
        (MediaPreviewCache.get(attachmentId) as? MediaPreviewPayload.VideoPoster)?.let {
            return VideoPreviewUi(it.bitmap, it.durationMs)
        }
        if (!_state.value.unlocked) return null
        val token = generation.get()
        return withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    if (token != generation.get() || shuttingDown || session == null) return@withLock null
                    val (record, bytes) = activeEngine().decryptAttachment(attachmentId)
                    try {
                        val (poster, durationMs) = decodeVideoPoster(bytes) ?: return@withLock null
                        MediaPreviewCache.put(attachmentId, MediaPreviewPayload.VideoPoster(poster, durationMs))
                        VideoPreviewUi(poster, durationMs)
                    } finally { bytes.fill(0) }
                }
            } catch (_: Exception) { null }
        }
    }

    /**
     * Poster-frame + duration decode for an inline/full-screen video preview. [bytes] is wrapped
     * (never copied) in a [MemoryMediaDataSource], which takes ownership and wipes it on [close] -
     * the caller's own `finally { bytes.fill(0) }` after this returns is then a harmless no-op,
     * matching the same belt-and-suspenders wipe pattern used elsewhere in this class. The retriever
     * itself is always released, success or failure. Bounded to [THUMBNAIL_MAX_DIMENSION] on the
     * longer side via `getScaledFrameAtTime`'s bounded-size overload (available since API 27, well
     * under this project's minSdk 31), falling back to the unbounded `getFrameAtTime` only if that
     * overload itself declines to produce a frame. Returns null instead of throwing on any failure
     * (corrupt/unsupported video, no video track, etc.).
     */
    private fun decodeVideoPoster(bytes: ByteArray): Pair<Bitmap, Int>? {
        if (bytes.isEmpty()) return null
        val dataSource = MemoryMediaDataSource(bytes)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(dataSource)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val frame = retriever.getScaledFrameAtTime(
                0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_MAX_DIMENSION, THUMBNAIL_MAX_DIMENSION,
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { it to durationMs }
        } catch (_: Exception) { null }
        // A hostile clip's frame dimensions are attacker-chosen and the unbounded getFrameAtTime
        // fallback above allocates one full frame; an OutOfMemoryError is an Error, so without this
        // it would escape the caller's catch (_: Exception) and kill the process.
        catch (failure: OutOfMemoryError) {
            Log.w(TAG, "Video poster decode ran out of memory; falling back to the generic row", failure)
            null
        }
        finally {
            retriever.release()
            dataSource.close()
        }
    }

    /**
     * Bounded thumbnail decode: reads dimensions only first (`inJustDecodeBounds`), computes the
     * smallest power-of-two `inSampleSize` that keeps the result at or above
     * [THUMBNAIL_MAX_DIMENSION] on its shorter side **and** inside [PREVIEW_MAX_PIXELS] overall via
     * [chooseInSampleSize], then decodes for real at that sample size. Never decodes a
     * full-resolution bitmap into memory just to downscale it afterwards.
     *
     * The pixel budget is the decompression-bomb guard: the attachment's declared dimensions come
     * from a remote contact, and a per-dimension bound alone does not cap decoded memory for an
     * extreme aspect ratio (see [PREVIEW_MAX_PIXELS]).
     *
     * `inMutable = true` so the cached bitmap's pixels can actually be erased on vault lock
     * (`MediaPreviewPayload.wipe`); a default `BitmapFactory` result is immutable, which makes
     * `eraseColor` illegal and silently turns that wipe into a no-op.
     *
     * Returns null instead of throwing on a malformed/undecodable image, including the
     * `OutOfMemoryError` a decoder can still raise on a hostile input despite the budget above -
     * that is an `Error`, so the caller's `catch (_: Exception)` would not otherwise contain it.
     */
    private fun decodeBoundedThumbnail(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = chooseInSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_DIMENSION)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (failure: OutOfMemoryError) {
            Log.w(TAG, "Thumbnail decode ran out of memory; falling back to the generic row", failure)
            null
        }
    }

    private fun refresh() {
        val database = db()
        if (!_state.value.unlocked) return
        // The records are kept alongside their UI projection because ContactUi deliberately does not
        // expose `displayOnly` - the forward picker needs it to leave out the decoy's display-only
        // contacts, which have no onion address and therefore nothing to send to.
        val contactRecords = database.listContacts()
        val contacts = contactRecords.map { it.toUi() }
        val selected = _state.value.selectedChat
        // Reading is decided locally and is never announced to the peer: the open conversation is
        // marked READ before the counters are read back, so it also covers a message that arrives
        // while the user is already looking at it. The decoy history is seeded READ, so its rows
        // are already in this state and no vault is singled out by doing extra work here.
        if (selected != null) database.markChatRead(selected)
        val unread = database.unreadCounts()
        val chats = database.listChats().map { chat ->
            ChatUi(chat.id, chat.title, chat.lastMessage?.let { preview(it) }.orEmpty(), time(chat.lastTimestamp), group = chat.isGroup,
                unread = unread[chat.id] ?: 0,
                lastOutgoing = chat.lastOutgoing, lastStatus = chat.lastStatus?.name.orEmpty())
        }
        val messages = if (selected == null) emptyList() else database.listMessages(selected).map { record ->
            val decoded = try { EnvelopeCodec.decode(record.body) } catch (_: Exception) { null }
            val attachment = decoded as? Envelope.Attachment
            MessageUi(record.id, (decoded as? Envelope.Text)?.body ?: attachment?.name ?: record.body.toString(Charsets.UTF_8).take(4000),
                time(record.timestamp), record.direction == MessageDirection.OUTGOING, record.status.name,
                attachmentId = attachment?.fileId?.joinToString("") { "%02x".format(it.toInt() and 255) }, attachmentName = attachment?.name, mimeType = attachment?.mime,
                // From the column, not from the decoded envelope: the row is authoritative and is
                // readable even when the body fails to decode (a truncated or foreign envelope).
                forwarded = record.forwarded)
        }
        val group = selected?.let { database.getGroup(it) }
        val displayName = database.getMeta("display_name")?.toString(Charsets.UTF_8).orEmpty()
        val groupMembers = group?.members?.map { member -> contacts.firstOrNull { it.id == member } ?: ContactUi(member, alias(member), member, "") }.orEmpty()
        // The flag blobs are read for their presence only; their contents are wiped immediately,
        // never returned and never logged. `groupId` defaults to the open chat so the status block
        // below reads exactly as it did before the forward picker needed the same test per group.
        fun hasGroupFlag(namespace: String, groupId: String? = selected): Boolean {
            val bytes = groupId?.let { database.getBlob(namespace, it) } ?: return false
            bytes.fill(0)
            return true
        }
        fun statusOf(groupId: String): GroupStatus = when {
            hasGroupFlag("group_left", groupId) -> GroupStatus.LEFT
            hasGroupFlag("group_error", groupId) -> GroupStatus.FAILED
            hasGroupFlag("group_pending", groupId) -> GroupStatus.PENDING
            else -> GroupStatus.READY
        }
        val groupStatus = if (group == null) GroupStatus.NONE else statusOf(group.id)
        val canManage = group != null && group.coordinator == identity?.id && groupStatus in setOf(GroupStatus.READY, GroupStatus.FAILED)
        group?.state?.fill(0)
        // Forward destinations: reachable contacts first, then every group that is actually usable.
        // `listGroups` materialises each group's MLS state, so every one of them is wiped as soon as
        // its id and name have been read - the same discipline as `group?.state?.fill(0)` above.
        val forwardTargets = contactRecords.filter { !it.displayOnly }.map { ForwardTargetUi(it.id, it.alias, isGroup = false) } +
            database.listGroups().mapNotNull { candidate ->
                try {
                    ForwardTargetUi(candidate.id, candidate.name, isGroup = true)
                        .takeIf { statusOf(candidate.id) == GroupStatus.READY && candidate.state.isNotEmpty() }
                } finally { candidate.state.fill(0) }
            }
        _state.update { current -> if (!current.unlocked || shuttingDown) current else current.copy(displayName = displayName,
            chats = chats, contacts = contacts, messages = messages,
            selectedTitle = chats.firstOrNull { it.id == selected }?.title
                ?: contacts.firstOrNull { it.id == selected }?.alias.orEmpty(),
            selectedGroupMembers = groupMembers, canManageSelectedGroup = canManage, selectedGroupStatus = groupStatus,
            forwardTargets = forwardTargets) }
    }
    private fun ContactRecord.toUi() = ContactUi(id, alias, id.chunked(4).joinToString(" "), DateFormat.getDateInstance().format(Date(pairedAt)))
    private fun preview(bytes: ByteArray): String = try {
        when (val envelope = EnvelopeCodec.decode(bytes)) {
            is Envelope.Text -> envelope.body.take(120)
            is Envelope.Attachment -> "📎 ${envelope.name}"
            else -> ""
        }
    } catch (_: Exception) { bytes.toString(Charsets.UTF_8).take(120) }
    private fun time(timestamp: Long?): String = timestamp?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty()
    private fun Set<String>.takeLastBounded(maximum: Int): List<String> = toList().takeLast(maximum)
    override fun onCleared() {
        pendingImportPassword?.fill('\u0000')
        _state.value.attachment?.bytes?.fill(0)
        generation.incrementAndGet()
        sessionScope?.cancel()
        // The doorbell only makes sense while something is left to watch it: this controller owns
        // the poll loop and the notification, so a child that outlived it would ring into the void.
        doorbellWatch?.cancel()
        val kept = synchronized(lifecycleGuard) { minimal.also { minimal = null } }?.transport
        CoroutineScope(Dispatchers.IO).launch {
            try { kept?.shutdown() } catch (_: Exception) { }
            try { transport?.shutdown() } finally { mutex.withLock { discardAudio(); closeSession() } }
        }
        super.onCleared()
    }
    companion object {
        private const val TAG = "NoMessagesController"
        private const val MAX_ATTACHMENT = 8 * 1024 * 1024
        /** Longer-side cap for a decoded inline-bubble image thumbnail (see [decodeBoundedThumbnail]). */
        private const val THUMBNAIL_MAX_DIMENSION = 512
        /**
         * Publication budgets spent on one bootstrapped client before the `:tor` child is recycled.
         * Three times 300 s: a slow directory is ordinary, a wedged onion service is not, and only
         * the second one is worth another 180 s bootstrap.
         */
        private const val PUBLICATION_ROUNDS = 3
        /**
         * Poll interval while the pairing key-bundle fetch waits for the transport to attach. Not a
         * fetch attempt and therefore not part of [BundleFetchPolicy]: nothing has been tried yet.
         */
        private const val TOR_POLL_MILLIS = 1_000L
        /** `meta` key of the doorbell onion seed; see [doorbellIdentityKey] and [doorbellSeed]. */
        private const val DOORBELL_SEED_KEY = "doorbell_seed"
        /** `opaque_blobs` namespace for per-vault user preferences; see [isDoorbellEnabled]. */
        private const val VAULT_SETTINGS_NAMESPACE = "vault_settings"
        private const val DOORBELL_ENABLED_KEY = "doorbell_enabled"
        /** Absent key means ON: see [isDoorbellEnabled] for why the default is not OFF. */
        private const val DOORBELL_DEFAULT_ENABLED = true
        /** Length of `contacts.doorbell_token_issued`; a shorter column is a pre-T4.17 row. */
        private const val DOORBELL_TOKEN_SIZE = 32
        /**
         * One doorbell poll slice. Just under the native 30 s ceiling, so the process sleeps
         * through almost the whole interval and a knock is noticed within one round trip, while
         * the loop still returns often enough to observe cancellation by an unlock.
         */
        private const val DOORBELL_POLL_MILLIS = 25_000
        /** How long an online transport is held before the next guard checkpoint. */
        private const val HEARTBEAT_MILLIS = 60_000L
        /** Budget of the reachability probe that runs after each heartbeat. */
        private const val REACHABILITY_PROBE_MILLIS = 30_000L

        /**
         * T4.6: pure `code -> string resource id` mapping, split out of [errorMessage] so the mapping
         * itself - the part a lint pass or a future error code can silently fall out of sync with -
         * has a JVM test independent of [android.content.Context]/`getString`. See [MessagingError]'s
         * doc for why some failures (the receive loop's) never carry a [MessagingErrorCode] and
         * always fall through to [fallback] here.
         */
        internal fun errorResource(code: MessagingErrorCode?, fallback: Int): Int = when (code) {
            MessagingErrorCode.EMPTY_MESSAGE -> R.string.error_type_message
            MessagingErrorCode.FILE_TOO_LARGE -> R.string.error_attachment_limit
            MessagingErrorCode.FILE_UNAVAILABLE -> R.string.error_file_unavailable
            MessagingErrorCode.FILE_INVALID -> R.string.error_file_invalid
            MessagingErrorCode.NO_OTHER_MEMBERS -> R.string.error_no_other_members
            MessagingErrorCode.GROUP_SYNC_FAILED -> R.string.error_group_sync_failed
            MessagingErrorCode.GROUP_WAITING_ONLINE -> R.string.error_group_waiting_online
            MessagingErrorCode.LEFT_GROUP -> R.string.error_left_group
            MessagingErrorCode.CONTACT_UNAVAILABLE -> R.string.error_contact_unavailable
            MessagingErrorCode.CONTACT_UNPAIRED -> R.string.error_contact_unpaired
            MessagingErrorCode.GROUP_UNAVAILABLE -> R.string.error_group_unavailable
            MessagingErrorCode.DUPLICATE_FILE -> R.string.error_duplicate_file
            MessagingErrorCode.EXISTING_FILE_MISMATCH -> R.string.error_existing_file_mismatch
            MessagingErrorCode.NO_AUDIO_RECORDED -> R.string.error_no_audio_recorded
            MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED -> R.string.error_media_capacity
            null -> fallback
        }
    }
}
