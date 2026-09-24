package dev.mx3.nomessages.runtime

import dev.mx3.nomessages.core.crypto.Crypto
import dev.mx3.nomessages.core.files.EncryptedFiles
import dev.mx3.nomessages.core.groups.CliquePolicy
import dev.mx3.nomessages.core.messaging.*
import dev.mx3.nomessages.core.nativebridge.MlsKind
import dev.mx3.nomessages.core.nativebridge.MlsNative
import dev.mx3.nomessages.core.protocol.FrameAssembler
import dev.mx3.nomessages.core.protocol.FrameCodec
import dev.mx3.nomessages.core.protocol.PairingIdentity
import dev.mx3.nomessages.core.vault.VaultSession
import dev.mx3.nomessages.core.vault.VaultSlot
import dev.mx3.nomessages.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One unlocked vault. Public synchronous actions are called with [mutex] held. */
class MessagingEngine(
    private val crypto: Crypto,
    private val db: ChatDatabase,
    private val identity: PairingIdentity,
    private val session: VaultSession,
    private val directory: Path,
    /** T4.1: fixed per-slot media budget, mirroring [dev.mx3.nomessages.storage.AndroidVaultStorage.mediaCapacityBytes].
     * Checked in [storeAttachment] against the slot actually growing; see that function's doc. */
    private val mediaCapacityBytes: Long,
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val files = EncryptedFiles(crypto)
    /**
     * The published transport, or null while the controller is still connecting or retrying. Every
     * network loop parks on null, so the outbox stays queued instead of burning its per-item
     * cooldown against a transport nobody can reach.
     */
    @Volatile private var transport: TorConnection? = null
    private val jobs = mutableListOf<Job>()
    @Volatile private var closed = false
    private var started = false
    private val streamDeadlines = ConcurrentHashMap<Long, Long>()
    private val expectedPeers = ConcurrentHashMap<Long, String>()
    private val finishedStreams = ConcurrentHashMap<Long, Long>()
    private val trialCursors = linkedMapOf<String, MessagingTrialCursor>()
    private val recentPeers = linkedMapOf<String, Long>()
    private val trialLimiter = MessagingTrialLimiter()
    private val bundleLimiter = MessagingBundleLimiter()
    /**
     * Streams this device opened with [fetchPeerBundle] and is still waiting on. The receive loop
     * hands the assembled wire straight to the waiter instead of routing it through [accept]: a
     * BundleResponse belongs to no Signal session, so there is nothing for [accept] to authenticate
     * it against.
     */
    private val bundleWaiters = ConcurrentHashMap<Long, CompletableDeferred<ByteArray>>()
    /**
     * Supplies the encoded key bundle behind one of this device's own live pairing offers, or null.
     * Installed by `NoMessagesController` from its `PairingEngine`; null whenever no pairing screen
     * is open, in which case every incoming BundleRequest is answered with silence.
     */
    @Volatile private var pairingBundles: ((ByteArray) -> ByteArray?)? = null
    /**
     * The doorbell knock currently in flight for each contact, keyed by `ContactRecord.id` (T4.17).
     *
     * One entry is one running attempt, and a contact that already has an entry is skipped outright.
     * Kept apart from [jobs] because knocks are started from inside coroutines - [jobs] is a plain
     * list, written only by [start] and read by [close] - and because [close] has to be able to
     * cancel them individually.
     */
    private val knocks = ConcurrentHashMap<String, Job>()
    private val packageTtl = 24 * 60 * 60 * 1000L
    private class RejectedControl(val reason: ControlRejectionReason) : IllegalArgumentException("Controle recusado")
    private val mediaRoot get() = directory.resolve(if (session.slot == VaultSlot.REAL) "real.files" else "decoy.files")
    private val createdFiles = mutableListOf<Path>()
    private val deferredErrors = mutableListOf<String>()
    private fun now() = System.currentTimeMillis()
    private fun id() = UUID.randomUUID().toString()
    /** Throws [MessagingError] for a failure `NoMessagesController` is willing to show distinctly
     * (T4.6). See [MessagingErrorCode] for which call sites use this and why the receive loop never
     * does. */
    private fun fail(code: MessagingErrorCode): Nothing = throw MessagingError(code, code.name)
    private fun requireCode(condition: Boolean, code: MessagingErrorCode) { if (!condition) fail(code) }

    /**
     * Handed the live transport once its onion descriptor is published, and null again whenever the
     * controller tears a failed one down. Sending is paused, never failed, while this is null.
     */
    fun attachTransport(connection: TorConnection?) {
        transport = connection
    }

    /** True once a reachable transport has been published, i.e. a Tor fetch can be attempted. */
    val transportAttached: Boolean get() = transport != null

    /**
     * Installs (or clears) the source of answers to an incoming pairing [Envelope.BundleRequest].
     *
     * The lambda is invoked from [accept], i.e. already under the engine mutex the controller owns,
     * which is the same lock its `PairingEngine` is mutated under - so the pairing state it reads
     * cannot change underneath it.
     */
    fun attachPairingBundles(source: ((ByteArray) -> ByteArray?)?) {
        pairingBundles = source
    }

    /**
     * Fetches the peer's PQXDH key bundle for [nonce] from [onion] over the live transport.
     *
     * Deliberately **not** an `atomic`/mutex-holding action: while this suspends, the peer is
     * running the mirror-image fetch against *this* device, and answering it needs the very same
     * mutex. Holding the lock across a 5-40 s circuit build would deadlock the pairing of two
     * devices that scanned each other at the same time.
     *
     * Returns the raw encoded bundle. The caller is the one that verifies it against the hash the
     * peer signed (`PairingEngine.acceptPeerBundle`); nothing here grants it any trust, and the
     * caller wipes the returned buffer.
     */
    suspend fun fetchPeerBundle(onion: String, nonce: ByteArray, timeoutMillis: Long): ByteArray {
        check(!closed) { "Cofre bloqueado" }
        require(nonce.size == EnvelopeLimits.PAIRING_NONCE_BYTES)
        require(timeoutMillis > 0)
        val live = transport ?: error("Rede Tor indisponível")
        val request = EnvelopeCodec.encode(Envelope.BundleRequest(id(), now(), nonce))
        val frames = try { FrameCodec.encode(WirePacket.encodeBundle(request)) } finally { request.fill(0) }
        val waiter = CompletableDeferred<ByteArray>()
        var connection: Long? = null
        try {
            connection = live.send(onion, frames.first())
            bundleWaiters[connection] = waiter
            for (index in 1 until frames.size) live.reply(connection, frames[index])
            val wire = withTimeoutOrNull(timeoutMillis) { waiter.await() }
                ?: error("O outro aparelho não respondeu pela rede Tor")
            try {
                val packet = WirePacket.decode(wire)
                require(packet.kind == PacketKind.BUNDLE) { "Resposta de pacote de chaves inválida" }
                require(packet.payload.size <= maxBundlePayload) { "Resposta de pacote de chaves inválida" }
                val envelope = EnvelopeCodec.decode(packet.payload)
                require(envelope is Envelope.BundleResponse && envelope.nonce.contentEquals(nonce)) {
                    "Resposta de pacote de chaves inválida"
                }
                return envelope.bundle
            } finally { wire.fill(0) }
        } finally {
            connection?.let { bundleWaiters.remove(it); closeStream(it) }
            frames.forEach { it.fill(0) }
        }
    }

    /**
     * Answers an incoming pairing [Envelope.BundleRequest], or returns null to say nothing at all.
     *
     * Silence, not an error envelope, is the answer to an unknown nonce: an error would confirm that
     * this onion is running a pairing screen, which a bare nonce probe must not learn.
     */
    private fun answerBundleRequest(payload: ByteArray): ByteArray? {
        if (payload.size > maxBundlePayload) return null
        val source = pairingBundles ?: return null
        if (!bundleLimiter.admit()) return null
        val envelope = try { EnvelopeCodec.decode(payload) } catch (_: IllegalArgumentException) { return null }
        if (envelope !is Envelope.BundleRequest) return null
        val bundle = source(envelope.nonce) ?: return null
        val response = EnvelopeCodec.encode(Envelope.BundleResponse(id(), now(), envelope.nonce, bundle))
        return try { WirePacket.encodeBundle(response) } finally { response.fill(0); bundle.fill(0) }
    }

    fun start() {
        check(!closed)
        if (started) return
        if (Files.isDirectory(mediaRoot)) {
            Files.newDirectoryStream(mediaRoot).use { entries ->
                entries.filter { it.fileName.toString().matches(Regex("\\.[0-9a-f]{32}\\.part")) }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
        started = true
        jobs += scope.launch(Dispatchers.IO) { receiveLoop() }
        jobs += scope.launch(Dispatchers.IO) { outboxLoop() }
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive && !closed) {
                finishedStreams.entries.removeIf { now() - it.value > 120_000 }
                val expired = streamDeadlines.entries.filter { it.value <= now() }
                coroutineScope {
                    expired.map { entry -> launch {
                        if (streamDeadlines.remove(entry.key, entry.value)) {
                            expectedPeers.remove(entry.key)
                            rememberClosed(entry.key)
                            closeQuietly(entry.key)
                        }
                    } }.joinAll()
                }
                delay(1_000)
            }
        }
    }

    /**
     * Sends a text message.
     *
     * [forwarded] is set by the caller that is relaying an existing message into this chat. It is a
     * brand-new envelope with a brand-new id and timestamp - a forward is a fresh send, not a
     * re-delivery of the original - and it takes the ordinary outbox path, so it is queued and
     * drained by `outboxLoop` under exactly the same Tor-availability rules as any other message.
     */
    fun sendText(chatId: String, text: String, forwarded: Boolean = false) {
        requireCode(text.isNotBlank(), MessagingErrorCode.EMPTY_MESSAGE)
        atomic { sendApplication(chatId, Envelope.Text(id(), now(), text, forwarded = forwarded)) }
    }

    /**
     * Returns the sent attachment's file id (matching `MessageUi.attachmentId`'s hex encoding).
     *
     * See [sendText] for [forwarded]. A forwarded attachment is re-encrypted under a fresh file key
     * and file id, so the recipient learns nothing that links it to the conversation it came from.
     */
    fun sendAttachment(chatId: String, name: String, mime: String, bytes: ByteArray, forwarded: Boolean = false): String {
        requireCode(bytes.size <= 8 * 1024 * 1024, MessagingErrorCode.FILE_TOO_LARGE)
        val fileId = crypto.random(16)
        val key = files.newFileKey()
        val ciphertext = ByteArrayOutputStream()
        try {
            files.encrypt(ByteArrayInputStream(bytes), ciphertext, key, fileId, session.epoch, bytes.size.toLong())
            val envelope = Envelope.Attachment(id(), now(), name, mime, fileId, session.epoch, key, ciphertext.toByteArray(), forwarded = forwarded)
            try { atomic { storeAttachment(envelope); sendApplication(chatId, envelope) } }
            finally { envelope.ciphertext.fill(0) }
            return fileId.toHexId()
        } finally { key.fill(0); ciphertext.reset() }
    }

    private fun ByteArray.toHexId(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun decryptAttachment(fileId: String): Pair<FileRecord, ByteArray> {
        check(!closed)
        val record = db.getFile(fileId) ?: fail(MessagingErrorCode.FILE_UNAVAILABLE)
        val key = crypto.open(session.keys.fileKey, record.encryptedParameters, fileId.toByteArray())
            ?: fail(MessagingErrorCode.FILE_INVALID)
        val output = SecretOutput()
        try {
            require(record.size <= 8 * 1024 * 1024)
            val path = safeMediaPath(record.relativePath)
            Files.newInputStream(path).use { files.decrypt(it, output, key, hexBytes(record.id), record.epoch) }
            val bytes = output.toByteArray()
            require(bytes.size.toLong() == record.size)
            return record to bytes
        } finally { key.fill(0); output.wipe() }
    }

    fun syncEvidence(peer: String) = atomic {
        evidence().chunked(256).forEach { queueSignal(peer, Envelope.Evidence(id(), now(), it)) }
    }

    fun createGroup(name: String, members: List<String>): String = atomic {
        require(name.isNotBlank() && name.length <= 128)
        require(activeGroupCount() < 128) { "Limite de grupos atingido" }
        val all = (members + identity.id).distinct().sorted()
        require(members.distinct().size == members.size) { "Membro repetido" }
        all.filter { it != identity.id }.forEach(::contact)
        CliquePolicy(crypto).requireClique(all, evidence(all))
        val seed = identity.signingSeed()
        val kp = try { MlsNative.keyPackage(seed) } finally { seed.fill(0) }
        val created = try { MlsNative.create(kp.state) } finally { kp.wipe() }
        val groupId = id()
        try {
            db.putGroup(GroupRecord(groupId, name.trim(), created.state, all, identity.id, now()))
            db.putBlob("group_pending", groupId, byteArrayOf(1))
            all.filter { it != identity.id }.forEach { peer ->
                val request = id()
                db.putBlob("group_request", request, pack(groupId.toByteArray(), peer.toByteArray()))
                queueSignal(peer, Envelope.KeyPackageRequest(id(), now(), request))
            }
        } finally { created.wipe() }
        groupId
    }

    fun removeMember(groupId: String, member: String) = atomic { removeMemberInternal(groupId, member) }

    fun leaveGroup(groupId: String) = atomic {
        if (db.getBlob("group_pending", groupId) != null) {
            cancelPendingGroup(groupId)
            return@atomic
        }
        val group = activeGroup(groupId)
        try {
            if (group.coordinator == identity.id) {
                val remaining = group.members.filter { it != identity.id }.sorted()
                if (remaining.isEmpty()) {
                    db.putBlob("group_left", groupId, byteArrayOf(1))
                    db.putGroup(group.copy(state = byteArrayOf()))
                    return@atomic
                }
                val transfer = Envelope.GroupLeave(id(), now(), groupId)
                remaining.forEach { queueSignal(it, transfer) }
                db.putBlob("group_left", groupId, byteArrayOf(1))
                db.putGroup(group.copy(state = byteArrayOf(), coordinator = remaining.first()))
            } else {
                queueSignal(group.coordinator, Envelope.GroupLeave(id(), now(), groupId))
                db.putBlob("group_left", groupId, byteArrayOf(1))
                db.putGroup(group.copy(state = byteArrayOf()))
            }
        } finally { group.state.fill(0) }
    }

    private fun cancelPendingGroup(groupId: String) {
        val group = db.getGroup(groupId) ?: fail(MessagingErrorCode.GROUP_UNAVAILABLE)
        try {
            require(group.coordinator == identity.id)
            db.listBlobs("outbox_control").forEach { (pending, saved) ->
                val fields = try { unpack(saved) } finally { saved.fill(0) }
                try {
                    if (fields[0].toString(Charsets.UTF_8) == groupId) {
                        db.acknowledgePendingFrame(pending)
                        db.removeBlob("attempts", pending); db.removeBlob("outbox_lanes", pending)
                        db.removeBlob("outbox_control", pending)
                    }
                } finally { fields.forEach { it.fill(0) } }
            }
            db.listBlobs("group_request").forEach { (request, saved) ->
                val fields = try { unpack(saved) } finally { saved.fill(0) }
                try {
                    if (fields[0].toString(Charsets.UTF_8) == groupId) db.removeBlob("group_request", request)
                } finally { fields.forEach { it.fill(0) } }
            }
            group.members.forEach { db.removeBlob("group_packages", "$groupId:$it") }
            db.removeBlob("group_pending", groupId); db.removeBlob("group_error", groupId)
            db.putBlob("group_left", groupId, byteArrayOf(1))
            db.putGroup(group.copy(state = byteArrayOf()))
        } finally { group.state.fill(0) }
    }

    private fun removeMemberInternal(groupId: String, member: String) {
        val group = activeGroup(groupId)
        try {
            require(group.coordinator == identity.id) { "Somente o coordenador pode remover membros" }
            require(member in group.members && member != identity.id) { "Use sair do grupo para transferir a coordenação" }
            val remaining = group.members - member
            requireCode(remaining.isNotEmpty(), MessagingErrorCode.NO_OTHER_MEMBERS)
            val proofs = evidence(remaining)
            requireMembership(remaining, proofs)
            val result = MlsNative.remove(group.state, listOf(hexBytes(member)), remaining.map(::hexBytes))
            try {
                db.putGroup(group.copy(state = result.state, members = remaining))
                if (db.getBlob("group_error", groupId)?.toString(Charsets.UTF_8)?.substringBefore(':') == member) db.removeBlob("group_error", groupId)
                val commit = Envelope.GroupCommit(id(), now(), groupId, remaining, proofs, result.message)
                remaining.filter { it != identity.id }.forEach { queueSignal(it, commit) }
            } finally { result.wipe() }
        } finally { group.state.fill(0) }
    }

    private fun sendApplication(chatId: String, envelope: Envelope) {
        val group = db.getGroup(chatId)
        if (group == null) queueSignal(chatId, envelope)
        else {
            try {
            requireCode(db.getBlob("group_error", chatId) == null, MessagingErrorCode.GROUP_SYNC_FAILED)
            requireCode(db.getBlob("group_pending", chatId) == null, MessagingErrorCode.GROUP_WAITING_ONLINE)
            requireCode(db.getBlob("group_left", chatId) == null && identity.id in group.members && group.state.isNotEmpty(), MessagingErrorCode.LEFT_GROUP)
            val encoded = EnvelopeCodec.encode(envelope)
            val result = try { MlsNative.encrypt(group.state, encoded) } finally { encoded.fill(0) }
            try {
                db.putGroup(group.copy(state = result.state))
                val wire = WirePacket.encodeMls(result.message)
                group.members.filter { it != identity.id }.forEach { queue(it, envelope.id, wire, MessagingPolicy.groupLane(chatId)) }
            } finally { result.wipe() }
            } finally { group.state.fill(0) }
        }
        db.insertMessage(MessageRecord(envelope.id, chatId, MessageDirection.OUTGOING, envelope.timestamp, history(envelope), if (group != null && group.members.all { it == identity.id }) MessageStatus.DELIVERED else MessageStatus.PENDING, envelopeForwarded(envelope)))
    }

    /**
     * The `forwarded` bit of a chat envelope, or false for every control envelope (Ack, Evidence,
     * the MLS/key-package family): only TEXT and ATTACHMENT carry the concept, and only those two
     * ever reach [db].insertMessage. Keeping the column and the stored envelope derived from the
     * same source guarantees they can never disagree.
     */
    private fun envelopeForwarded(envelope: Envelope): Boolean = when (envelope) {
        is Envelope.Text -> envelope.forwarded
        is Envelope.Attachment -> envelope.forwarded
        else -> false
    }

    private fun history(envelope: Envelope): ByteArray = EnvelopeCodec.encode(
        if (envelope is Envelope.Attachment) envelope.copy(ciphertext = byteArrayOf(), fileKey = ByteArray(32)) else envelope
    )

    private fun queueSignal(peer: String, envelope: Envelope): ByteArray {
        val plain = EnvelopeCodec.encode(envelope)
        val cipher = try { identity.sessions.encrypt(peer, plain) } finally { plain.fill(0) }
        val wire = WirePacket.encodeSignal(cipher)
        val lane = MessagingPolicy.lane(envelope)
        queue(peer, envelope.id, wire, lane)
        val groupId = when (envelope) {
            is Envelope.GroupInvite -> envelope.groupId
            is Envelope.GroupCommit -> envelope.groupId
            is Envelope.GroupLeave -> envelope.groupId
            is Envelope.KeyPackageRequest -> db.getBlob("group_request", envelope.requestId)?.let { unpack(it)[0].toString(Charsets.UTF_8) } ?: ""
            else -> ""
        }
        val requestNonce = when (envelope) {
            is Envelope.KeyPackageRequest -> envelope.requestId
            is Envelope.KeyPackage -> envelope.requestId
            else -> ""
        }
        if (envelope is Envelope.GroupInvite || envelope is Envelope.GroupCommit || envelope is Envelope.GroupLeave ||
            envelope is Envelope.KeyPackageRequest || envelope is Envelope.KeyPackage) {
            db.putBlob("outbox_control", "${envelope.id}:$peer", pack(groupId.toByteArray(), requestNonce.toByteArray()))
        }
        return wire
    }

    private fun queue(peer: String, messageId: String, wire: ByteArray, lane: String = "direct") {
        val previous = db.getMeta("outbox_sequence")?.toString(Charsets.UTF_8)?.toLongOrNull() ?: 0L
        val sequence = maxOf(now(), previous + 1)
        db.putMeta("outbox_sequence", sequence.toString().toByteArray())
        db.putPendingFrame(PendingFrame("$messageId:$peer", contact(peer).onion, wire, sequence))
        db.putBlob("outbox_lanes", "$messageId:$peer", lane.toByteArray())
    }

    private fun contact(peer: String): ContactRecord = db.getContact(peer)?.also {
        requireCode(!it.displayOnly, MessagingErrorCode.CONTACT_UNAVAILABLE)
    } ?: fail(MessagingErrorCode.CONTACT_UNPAIRED)

    private fun activeGroup(groupId: String): GroupRecord {
        val group = db.getGroup(groupId) ?: fail(MessagingErrorCode.GROUP_UNAVAILABLE)
        try {
        requireCode(db.getBlob("group_pending", groupId) == null, MessagingErrorCode.GROUP_WAITING_ONLINE)
        requireCode(db.getBlob("group_left", groupId) == null && identity.id in group.members && group.state.isNotEmpty(), MessagingErrorCode.LEFT_GROUP)
        return group
        } catch (failure: Throwable) { group.state.fill(0); throw failure }
    }

    private fun <T> atomic(action: () -> T): T {
        check(!closed)
        val before = identity.export()
        createdFiles.clear()
        deferredErrors.clear()
        val result: T
        try {
            result = db.transaction {
                val value = action()
                val saved = identity.export()
                try { db.putMeta("identity", saved) } finally { saved.fill(0) }
                value
            }
            createdFiles.clear()
        } catch (failure: Throwable) {
            identity.restoreInPlace(before)
            createdFiles.forEach { runCatching { Files.deleteIfExists(it) } }
            createdFiles.clear()
            deferredErrors.clear()
            throw failure
        } finally { before.fill(0) }
        // A rendering failure cannot roll back already committed ratchet state.
        runCatching { onChanged() }
        deferredErrors.forEach { error -> runCatching { onError(error) } }
        deferredErrors.clear()
        return result
    }

    private suspend fun outboxLoop() = coroutineScope {
        val lastAttempt = mutableMapOf<String, Long>()
        val sending = mutableMapOf<String, Job>()
        try {
            while (currentCoroutineContext().isActive && !closed) {
                // Paused until the controller reports a reachable transport: an attempt made while
                // Tor is down only spends the 30 s per-item cooldown and rewrites `attempts`.
                val live = transport
                if (live == null) { delay(500); continue }
                val ids = mutex.withLock {
                    if (closed) emptyList() else {
                    try { flushEvidenceGossip() } catch (_: StorageCapacityException) { /* Existing outbox delivery releases capacity. */ }
                    db.query(MessagingPolicy.readyQuery).map { it["id"] as String }
                    }
                }
                lastAttempt.keys.retainAll(ids.toSet())
                sending.entries.removeAll { it.value.isCompleted }
                for (pendingId in ids) {
                    if (sending.size >= 4) break
                    if (sending.keys.any { it.substringAfter(':') == pendingId.substringAfter(':') }) continue
                    if (pendingId in sending || now() - (lastAttempt[pendingId] ?: 0) < 30_000) continue
                    lastAttempt[pendingId] = now()
                    mutex.withLock { if (!closed) db.putBlob("attempts", pendingId, now().toString().padStart(16, '0').toByteArray()) }
                    sending[pendingId] = launch {
                        val item = mutex.withLock {
                            if (closed) null else db.getPendingFrame(pendingId)
                        } ?: return@launch
                        var connection: Long? = null
                        var frames: List<ByteArray> = emptyList()
                        try {
                            frames = FrameCodec.encode(item.frame)
                            connection = live.send(item.destinationOnion, frames.first())
                            if (!closed && !finishedStreams.containsKey(connection)) {
                                streamDeadlines[connection] = Long.MAX_VALUE
                                expectedPeers[connection] = item.id.substringAfter(':')
                                if (closed || finishedStreams.containsKey(connection)) {
                                    streamDeadlines.remove(connection); expectedPeers.remove(connection)
                                }
                            }
                            for (i in 1 until frames.size) live.reply(connection, frames[i])
                            streamDeadlines.computeIfPresent(connection) { _, _ -> now() + 60_000 }
                            mutex.withLock {
                                if (!closed) {
                                    val messageId = item.id.substringBefore(':')
                                    if (db.getMessage(messageId)?.status == MessageStatus.PENDING) db.updateMessageStatus(messageId, MessageStatus.SENT)
                                    // The frame reached this peer's messaging onion, so it is awake:
                                    // the doorbell schedule drops back to its floor (T4.17).
                                    clearKnockFailures(item.id.substringAfter(':'))
                                    onChanged()
                                }
                            }
                            // The incoming worker closes the stream after the authenticated ACK.
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            connection?.let { closeStream(it) }
                            // Every delivery failure is a doorbell candidate. Nothing on this path
                            // distinguishes "the peer's onion is unreachable" from any other
                            // transient error - `live.send` reports one opaque failure - so the
                            // faithful reading of what the outbox knows is "this destination did
                            // not take the frame". Over-ringing is bounded by the schedule in
                            // `DoorbellKnockPolicy`, while under-ringing would defeat the feature.
                            ringDoorbell(item.id.substringAfter(':'))
                        }
                        finally { item.frame.fill(0); frames.forEach { it.fill(0) } }
                    }
                }
                delay(2_000)
            }
        } finally { sending.values.forEach { it.cancel() } }
    }

    private data class Assembly(val assembler: FrameAssembler, var updated: Long, var bytes: Int = 0)
    private suspend fun receiveLoop() {
        val assemblies = mutableMapOf<Long, Assembly>()
        try {
            while (currentCoroutineContext().isActive && !closed) {
                val expired = assemblies.filterValues { now() - it.updated > 60_000 }.keys.toList()
                expired.forEach { assemblies.remove(it)?.assembler?.reset(); closeStream(it) }
                val live = transport
                if (live == null) { delay(200); continue }
                val result = withTimeoutOrNull(1_000) { live.incoming.receiveCatching() } ?: continue
                // A closed channel means that child process is gone. The controller supervises the
                // replacement, so this loop parks instead of dying together with one transport.
                val incoming = result.getOrNull()
                if (incoming == null) { delay(200); continue }
                streamDeadlines[incoming.connection] = now() + 60_000
                var complete: ByteArray? = null
                try {
                    require(assemblies.size < 16 || incoming.connection in assemblies)
                    val assembly = assemblies.getOrPut(incoming.connection) { Assembly(FrameAssembler(), now()) }
                    assembly.updated = now()
                    assembly.bytes += incoming.frame.size
                    require(assembly.bytes <= 16 * 1024 * 1024 && assemblies.values.sumOf { it.bytes.toLong() } <= 32L * 1024 * 1024)
                    complete = assembly.assembler.accept(incoming.frame)
                    val wire = complete ?: continue
                    assemblies.remove(incoming.connection)
                    // A stream this device opened for a pairing bundle fetch is answered to its
                    // waiter, never to `accept`: there is no session to authenticate it against.
                    val waiter = bundleWaiters.remove(incoming.connection)
                    if (waiter != null) {
                        waiter.complete(wire.copyOf())
                        closeStream(incoming.connection)
                        continue
                    }
                    val ack = mutex.withLock { if (closed) null else accept(wire, expectedPeers[incoming.connection]) }
                    if (ack != null) FrameCodec.encode(ack).forEach { live.reply(incoming.connection, it) }
                    closeStream(incoming.connection)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { assemblies.remove(incoming.connection)?.assembler?.reset(); closeStream(incoming.connection) }
                finally { incoming.frame.fill(0); complete?.fill(0) }
            }
        } finally { assemblies.values.forEach { it.assembler.reset() }; assemblies.clear() }
    }

    private fun accept(wire: ByteArray, expectedPeer: String?): ByteArray? {
        val packet = WirePacket.decode(wire)
        // Answered before the receipt cache and before `atomic`: a pairing bundle request touches no
        // database row, no ratchet and no identity export, so it must not open a vault transaction -
        // it is the one packet an unpaired stranger can legitimately send.
        if (packet.kind == PacketKind.BUNDLE) return answerBundleRequest(packet.payload)
        val hash = hex(crypto.hash(wire))
        db.getBlob("receipts", hash)?.let { return it.takeIf { bytes -> bytes.isNotEmpty() } }
        require(trialLimiter.admit(packet.kind, expectedPeer != null)) { "Transporte ocupado" }
        trialCursors.entries.removeAll { now() - it.value.touched > (if (it.value.bytes > 64 * 1024) 15 * 60_000 else 120_000) }
        if (hash !in trialCursors) {
            while (trialCursors.isNotEmpty() && (trialCursors.size >= 16 || trialCursors.values.sumOf { it.memoryBytes } + MessagingPolicy.maximumCursorBytes > 24L * 1024 * 1024)) {
                trialCursors.remove(trialCursors.keys.first())
            }
        }
        return atomic {
            var peer: String? = null
            var group: GroupRecord? = null
            var plain: ByteArray? = null
            when (packet.kind) {
                PacketKind.SIGNAL -> {
                    if (expectedPeer != null) {
                        val result = identity.sessions.decryptCandidates(listOf(expectedPeer), packet.payload, 1)
                            ?: error("Mensagem não autenticada")
                        peer = result.peerId; plain = result.plaintext
                    } else {
                        val eligible = db.listContacts().filter { !it.displayOnly }.map { it.id }
                            .sortedWith(compareByDescending<String> { recentPeers[it] ?: 0L }.thenBy { it }).take(MessagingPolicy.maxContacts)
                        val cursor = trialCursors.getOrPut(hash) { MessagingTrialCursor(eligible, now(), wire.size) }
                        val ordered = cursor.refresh(eligible)
                        while (trialCursors.size > 16 || trialCursors.values.sumOf { it.memoryBytes } > 24L * 1024 * 1024) {
                            trialCursors.remove(trialCursors.keys.first())
                        }
                        val budget = MessagingPolicy.trialLimit(wire.size)
                        val result = identity.sessions.decryptCandidates(ordered, packet.payload, budget)
                        if (result == null) {
                            cursor.missed(budget, now())
                            error("Mensagem não autenticada")
                        }
                        peer = result.peerId; plain = result.plaintext
                        trialCursors.remove(hash)
                    }
                    recentPeers[peer] = now()
                    while (recentPeers.size > MessagingPolicy.maxContacts) recentPeers.remove(recentPeers.keys.first())
                }
                // Unreachable: `accept` returns on a BUNDLE packet before opening this transaction.
                // Spelled out rather than folded into an `else` so that adding a third real packet
                // kind is a compile error here instead of a silent fall-through into "unauthenticated".
                PacketKind.BUNDLE -> error("Pacote de pareamento fora do caminho de pareamento")
                PacketKind.MLS -> {
                    val eligible = db.query("SELECT g.id FROM chat_groups g WHERE length(g.mls_state)>0 AND NOT EXISTS " +
                        "(SELECT 1 FROM opaque_blobs b WHERE b.k=g.id AND b.namespace IN ('group_left','group_pending')) LIMIT 128")
                        .map { it["id"] as String }
                    val cursor = trialCursors.getOrPut(hash) { MessagingTrialCursor(eligible, now(), wire.size) }
                    val budget = MessagingPolicy.trialLimit(wire.size)
                    val groupIds = cursor.refresh(eligible).take(budget)
                    for (groupId in groupIds) {
                        if (db.getBlob("group_pending", groupId) != null || db.getBlob("group_left", groupId) != null) continue
                        val candidate = db.getGroup(groupId) ?: continue
                        try {
                            if (candidate.state.isEmpty()) continue
                            val result = try {
                                MlsNative.process(candidate.state, packet.payload, candidate.members.map(::hexBytes), hexBytes(candidate.coordinator))
                            } catch (_: Exception) { continue }
                            try {
                                require(result.kind == MlsKind.APPLICATION)
                                peer = hex(result.senderIdentity)
                                require(peer in candidate.members && peer != identity.id)
                                plain = result.application.copyOf()
                                group = candidate.copy(state = byteArrayOf())
                                db.putGroup(candidate.copy(state = result.state))
                            } finally { result.wipe() }
                            trialCursors.remove(hash)
                            break
                        } finally { candidate.state.fill(0) }
                    }
                    if (peer == null) {
                        cursor.missed(budget, now())
                    }
                }
            }

            val sender = peer ?: error("Mensagem não autenticada")
            val bytes = plain ?: error("Mensagem inválida")
            val envelope = try { EnvelopeCodec.decode(bytes) } finally { bytes.fill(0) }
            try {
                require(group == null || envelope is Envelope.Text || envelope is Envelope.Attachment) { "Controle de grupo exige canal pareado" }
                val seen = db.getBlob("accepted_ids", envelope.id)
                var rejected = db.getBlob("rejected_ids", envelope.id)
                if (seen != null) require(seen.toString(Charsets.UTF_8) == sender) { "Identificador repetido" }
                else {
                    try { handle(sender, group, envelope) }
                    catch (failure: RejectedControl) {
                        require(group == null)
                        rejected = queueSignal(sender, Envelope.ControlRejected(id(), now(), envelope.id, failure.reason))
                        db.putBlob("rejected_ids", envelope.id, rejected)
                    }
                    db.putBlob("accepted_ids", envelope.id, sender.toByteArray())
                }
                val ack = rejected ?: if (envelope is Envelope.Ack) byteArrayOf() else {
                    val encoded = EnvelopeCodec.encode(Envelope.Ack(id(), now(), envelope.id))
                    try { WirePacket.encodeSignal(identity.sessions.encrypt(sender, encoded)) } finally { encoded.fill(0) }
                }
                db.putBlob("receipts", hash, ack)
                ack.takeIf { it.isNotEmpty() }
            } finally {
                if (envelope is Envelope.Attachment) { envelope.fileKey.fill(0); envelope.ciphertext.fill(0) }
            }
        }
    }

    private fun handle(sender: String, group: GroupRecord?, envelope: Envelope) {
        when (envelope) {
            is Envelope.Text, is Envelope.Attachment -> {
                if (envelope is Envelope.Attachment) storeAttachment(envelope)
                db.insertMessage(MessageRecord(envelope.id, group?.id ?: sender, MessageDirection.INCOMING, envelope.timestamp, history(envelope), MessageStatus.DELIVERED, envelopeForwarded(envelope)))
            }
            is Envelope.Ack -> {
                // An authenticated acknowledgement is the strongest possible sign of life from this
                // peer, so its doorbell schedule returns to the floor (T4.17).
                clearKnockFailures(sender)
                db.acknowledgePendingFrame("${envelope.receivedId}:$sender")
                db.removeBlob("attempts", "${envelope.receivedId}:$sender")
                db.removeBlob("outbox_lanes", "${envelope.receivedId}:$sender")
                db.removeBlob("outbox_control", "${envelope.receivedId}:$sender")
                // Delivered means all recipient outbox entries were authenticated and acknowledged.
                if (db.query("SELECT id FROM outbox WHERE id LIKE ? LIMIT 1", arrayOf("${envelope.receivedId}:%")).isEmpty())
                    db.getMessage(envelope.receivedId)?.takeIf { it.direction == MessageDirection.OUTGOING }?.let { db.updateMessageStatus(it.id, MessageStatus.DELIVERED) }
            }
            is Envelope.ControlRejected -> {
                val pendingId = "${envelope.requestId}:$sender"
                val stored = db.getBlob("outbox_control", pendingId)
                // An authenticated peer may only reject its own outstanding control, never application delivery.
                if (stored != null && db.query("SELECT 1 FROM outbox WHERE id=?", arrayOf(pendingId)).isNotEmpty()) {
                    val metadata = try { unpack(stored) } finally { stored.fill(0) }
                    try {
                        val groupId = metadata[0].toString(Charsets.UTF_8)
                        val requestNonce = metadata[1].toString(Charsets.UTF_8)
                        db.acknowledgePendingFrame(pendingId)
                        db.removeBlob("attempts", pendingId); db.removeBlob("outbox_lanes", pendingId)
                        db.removeBlob("outbox_control", pendingId)
                        db.putBlob("failed_controls", pendingId, envelope.reason.name.toByteArray())
                        if (groupId.isNotEmpty()) db.putBlob("group_error", groupId, "$sender:${envelope.reason.name}".toByteArray())
                        if (requestNonce.isNotEmpty()) {
                            db.removeBlob("key_packages", requestNonce)
                            db.removeBlob("group_request", requestNonce)
                        }
                        deferredErrors += "Could not complete the group operation."
                    } finally { metadata.forEach { it.fill(0) } }
                }
            }
            is Envelope.Evidence -> importEvidence(envelope.proofs)
            is Envelope.KeyPackageRequest -> {
                val packages = pruneKeyPackages()
                MessagingPolicy.packageRefusal(packages, sender)?.let { throw RejectedControl(it) }
                if (db.getBlob("key_packages", envelope.requestId) != null) throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
                val seed = identity.signingSeed()
                val result = try { MlsNative.keyPackage(seed) } finally { seed.fill(0) }
                try {
                    db.putBlob("key_packages", envelope.requestId, pack(sender.toByteArray(), result.state, now().toString().toByteArray()))
                    queueSignal(sender, Envelope.KeyPackage(id(), now(), envelope.requestId, result.message))
                } finally { result.wipe() }
            }
            is Envelope.KeyPackage -> acceptKeyPackage(sender, envelope)
            is Envelope.GroupInvite -> acceptInvite(sender, envelope)
            is Envelope.GroupCommit -> acceptCommit(sender, envelope)
            // Pairing bundle traffic is answered in `accept` before any session lookup and is
            // never queued into a Signal lane, so reaching `handle` means a paired peer wrapped one
            // in its ratchet - which no honest build does.
            is Envelope.BundleRequest, is Envelope.BundleResponse ->
                throw IllegalArgumentException("Envelope de pareamento fora do fluxo de pareamento")
            is Envelope.GroupLeave -> {
                val existing = db.getGroup(envelope.groupId) ?: throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
                try {
                if (sender !in existing.members) return
                if (sender == existing.coordinator) {
                    val replacement = existing.members.filter { it != sender }.sorted().first()
                    db.putGroup(existing.copy(coordinator = replacement))
                    if (replacement == identity.id) removeMemberInternal(existing.id, sender)
                } else {
                    require(existing.coordinator == identity.id)
                    removeMemberInternal(existing.id, sender)
                }
                } finally { existing.state.fill(0) }
            }
        }
    }

    private fun acceptKeyPackage(sender: String, envelope: Envelope.KeyPackage) {
        val binding = unpack(db.getBlob("group_request", envelope.requestId) ?: throw RejectedControl(ControlRejectionReason.REQUEST_EXPIRED))
        val groupId = binding[0].toString(Charsets.UTF_8)
        if (binding[1].toString(Charsets.UTF_8) != sender) throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
        val group = db.getGroup(groupId) ?: error("Grupo desconhecido")
        try {
            if (group.coordinator != identity.id || db.getBlob("group_pending", groupId) == null) throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
            db.putBlob("group_packages", "$groupId:$sender", envelope.packageBytes)
            val others = group.members.filter { it != identity.id }
            val packages = others.map { db.getBlob("group_packages", "$groupId:$it") ?: return }
            val proofs = evidence(group.members)
            CliquePolicy(crypto).requireClique(group.members, proofs)
            val added = MlsNative.add(group.state, packages, group.members.map(::hexBytes))
            try {
                db.putGroup(group.copy(state = added.state))
                val requests = db.listBlobs("group_request")
                for (peer in others) {
                    val request = requests.entries.single { (_, value) -> unpack(value).let { it[0].toString(Charsets.UTF_8) == groupId && it[1].toString(Charsets.UTF_8) == peer } }.key
                    queueSignal(peer, Envelope.GroupInvite(id(), now(), groupId, group.name, identity.id, group.members, proofs, added.welcome, request))
                    db.removeBlob("group_request", request)
                    db.removeBlob("group_packages", "$groupId:$peer")
                }
                db.removeBlob("group_pending", groupId)
            } finally { added.wipe(); packages.forEach { it.fill(0) } }
        } finally { group.state.fill(0) }
    }

    private fun acceptInvite(sender: String, envelope: Envelope.GroupInvite) {
        if (activeGroupCount() >= 128) throw RejectedControl(ControlRejectionReason.GROUP_CAPACITY)
        if (sender != envelope.coordinator || db.query("SELECT 1 FROM chat_groups WHERE id=?", arrayOf(envelope.groupId)).isNotEmpty())
            throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
        if (identity.id !in envelope.members || envelope.members.any { it != identity.id && db.getContact(it)?.displayOnly != false })
            throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
        try { CliquePolicy(crypto).requireClique(envelope.members, envelope.proofs) }
        catch (_: IllegalArgumentException) { throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE) }
        val saved = db.getBlob("key_packages", envelope.requestId) ?: throw RejectedControl(ControlRejectionReason.REQUEST_EXPIRED)
        val local = try { unpack(saved) } finally { saved.fill(0) }
        val joined = try {
            if (local[0].toString(Charsets.UTF_8) != sender) throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
            val created = local.getOrNull(2)?.toString(Charsets.UTF_8)?.toLongOrNull() ?: 0L
            if (now() - created >= packageTtl) {
                db.removeBlob("key_packages", envelope.requestId)
                throw RejectedControl(ControlRejectionReason.REQUEST_EXPIRED)
            }
            MlsNative.join(local[1], envelope.welcome, envelope.members.map(::hexBytes), hexBytes(sender))
        } finally { local.forEach { it.fill(0) } }
        try {
            db.putGroup(GroupRecord(envelope.groupId, envelope.name, joined.state, envelope.members, sender, envelope.timestamp))
            importEvidence(envelope.proofs, gossip = false)
            db.removeBlob("key_packages", envelope.requestId)
        } finally { joined.wipe() }
    }

    private fun acceptCommit(sender: String, envelope: Envelope.GroupCommit) {
        val group = db.getGroup(envelope.groupId) ?: error("Grupo aguardando os participantes ficarem online")
        try {
            // A new coordinator's Commit can outrun the old coordinator's authenticated transfer.
            if (sender != group.coordinator && sender in group.members) error("Grupo aguardando os participantes ficarem online")
            if (group.state.isEmpty() || db.getBlob("group_pending", group.id) != null || db.getBlob("group_left", group.id) != null ||
                sender != group.coordinator || identity.id !in envelope.members || !envelope.members.all { it in group.members } ||
                group.members.size - envelope.members.size != 1) throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE)
            try { requireMembership(envelope.members, envelope.proofs) }
            catch (_: IllegalArgumentException) { throw RejectedControl(ControlRejectionReason.INVALID_PREREQUISITE) }
            val changed = MlsNative.process(group.state, envelope.commit, envelope.members.map(::hexBytes), hexBytes(sender))
            try {
                require(changed.kind == MlsKind.COMMIT)
                val coordinator = if (sender in envelope.members) sender else envelope.members.sorted().first()
                db.putGroup(group.copy(state = changed.state, members = envelope.members, coordinator = coordinator))
                importEvidence(envelope.proofs, gossip = false)
            } finally { changed.wipe() }
        } finally { group.state.fill(0) }
    }

    private fun activeGroupCount(): Long = db.query(
        "SELECT count(*) AS n FROM chat_groups g WHERE length(g.mls_state)>0 AND NOT EXISTS " +
            "(SELECT 1 FROM opaque_blobs b WHERE b.namespace='group_left' AND b.k=g.id)",
    ).first()["n"] as Long

    /** Expire orphaned one-time packages; admission is capped globally and per paired peer. */
    private fun pruneKeyPackages(): List<String> {
        val owners = mutableListOf<String>()
        db.listBlobs("key_packages").forEach { (request, saved) ->
            val fields = try { unpack(saved) } finally { saved.fill(0) }
            try {
                val created = fields.getOrNull(2)?.toString(Charsets.UTF_8)?.toLongOrNull() ?: 0L
                if (now() - created >= packageTtl) db.removeBlob("key_packages", request)
                else owners += fields[0].toString(Charsets.UTF_8)
            } finally { fields.forEach { it.fill(0) } }
        }
        return owners
    }

    private fun evidence(members: Collection<String>? = null): List<ByteArray> {
        val peers = members?.toList()
        if (peers == null) return db.listPairEvidence().map { it.evidence }
        if (peers.isEmpty()) return emptyList()
        val marks = peers.joinToString(",") { "?" }
        return db.query("SELECT evidence FROM pair_graph WHERE first_id IN ($marks) AND second_id IN ($marks)",
            (peers + peers).toTypedArray()).map { it["evidence"] as ByteArray }
    }

    private fun importEvidence(proofs: List<ByteArray>, gossip: Boolean = true) {
        require(proofs.size <= 4950)
        val known = db.listContacts().filter { !it.displayOnly }.map { it.id }.toSet() + identity.id
        val additions = mutableListOf<ByteArray>()
        val stored = db.query("SELECT count(*) AS n FROM pair_graph").first()["n"] as Long
        proofs.forEach { bytes ->
            val proof = dev.mx3.nomessages.core.protocol.PairEvidence.decode(bytes)
            if (proof.statement.first !in known || proof.statement.second !in known) return@forEach
            require(proof.valid(crypto)) { "Prova de pareamento inválida" }
            if (db.getPairEvidence(proof.statement.first, proof.statement.second) == null) {
                require(stored + additions.size < 16_384) { "Limite de provas de pareamento atingido" }
                db.putPairEvidence(PairEvidence(proof.statement.first, proof.statement.second, bytes, now()))
                additions += bytes
            }
        }
        if (gossip && additions.isNotEmpty()) {
            val recipients = known.filter { it != identity.id }.sorted().joinToString("\n").toByteArray()
            additions.chunked(256).forEach { chunk ->
                val batch = Envelope.Evidence(id(), now(), chunk)
                db.putBlob("evidence_gossip", batch.id, pack(EnvelopeCodec.encode(batch), recipients))
            }
        }
    }

    /** Encrypt at most four deferred proof recipients per tick instead of exploding one receive transaction. */
    private fun flushEvidenceGossip() {
        val key = db.query("SELECT k FROM opaque_blobs WHERE namespace='evidence_gossip' ORDER BY k LIMIT 1")
            .firstOrNull()?.get("k") as String? ?: return
        val saved = db.getBlob("evidence_gossip", key) ?: return
        val fields = try { unpack(saved) } finally { saved.fill(0) }
        try {
            val envelope = EnvelopeCodec.decode(fields[0]) as Envelope.Evidence
            val recipients = fields[1].toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
            atomic {
                recipients.take(4).forEach { queueSignal(it, envelope) }
                val remaining = recipients.drop(4)
                if (remaining.isEmpty()) db.removeBlob("evidence_gossip", key)
                else db.putBlob("evidence_gossip", key, pack(fields[0], remaining.joinToString("\n").toByteArray()))
            }
        } finally { fields.forEach { it.fill(0) } }
    }

    private fun requireMembership(members: List<String>, proofs: List<ByteArray>) {
        require(members.isNotEmpty() && members.size <= 100 && members.distinct().size == members.size)
        val pairs = proofs.map { bytes ->
            val proof = dev.mx3.nomessages.core.protocol.PairEvidence.decode(bytes)
            require(proof.valid(crypto))
            setOf(proof.statement.first, proof.statement.second)
        }.toSet()
        for (a in members.indices) for (b in a + 1 until members.size) require(setOf(members[a], members[b]) in pairs)
    }

    private fun storeAttachment(envelope: Envelope.Attachment) {
        val fileId = hex(envelope.fileId)
        requireCode(db.getFile(fileId) == null, MessagingErrorCode.DUPLICATE_FILE)
        val info = files.decrypt(ByteArrayInputStream(envelope.ciphertext), object : OutputStream() { override fun write(value: Int) {} ; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }, envelope.fileKey, envelope.fileId, envelope.fileEpoch)
        require(info.plaintextLength <= 8 * 1024 * 1024)
        val relative = "$fileId.bin"
        val target = safeMediaPath(relative)
        val existing = Files.exists(target)
        if (existing) {
            requireCode(Files.isRegularFile(target) && Files.size(target) == envelope.ciphertext.size.toLong(), MessagingErrorCode.EXISTING_FILE_MISMATCH)
            val prior = Files.readAllBytes(target)
            try { requireCode(java.security.MessageDigest.isEqual(prior, envelope.ciphertext), MessagingErrorCode.EXISTING_FILE_MISMATCH) }
            finally { prior.fill(0) }
        } else {
            // T4.1: fixed per-slot media reservation, checked against the slot that is actually
            // about to grow, before any byte of it is written. `coverSiblingSlot` below is what keeps
            // the *other* slot's total in lockstep, so it never needs a check of its own - by
            // induction from both slots starting equal (`AndroidVaultStorage.alignAllocations`,
            // enforced at setup/reset) and `coverSiblingSlot` now being idempotent per `fileId`
            // (T4.1 crash-consistency follow-up, see its doc comment below), every write here keeps
            // both at or under `mediaCapacityBytes`.
            requireCode(mediaDirectoryBytes(mediaRoot) + envelope.ciphertext.size <= mediaCapacityBytes, MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED)
        }
        Files.createDirectories(mediaRoot)
        val temporary = mediaRoot.resolve(".$fileId.part")
        try {
            if (!existing) {
                FileOutputStream(temporary.toFile()).use { it.write(envelope.ciphertext); it.fd.sync() }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                createdFiles.add(target)
            }
            FileChannel.open(mediaRoot, StandardOpenOption.READ).use { it.force(true) }
            val wrapped = crypto.seal(session.keys.fileKey, envelope.fileKey, fileId.toByteArray())
            db.putFile(FileRecord(fileId, relative, wrapped, info.plaintextLength, envelope.fileEpoch, envelope.name, envelope.mime))
        } finally { Files.deleteIfExists(temporary) }
        // T4.1 crash-consistency follow-up: no longer gated on `!existing`. The primary write above
        // (fsync + atomic rename) is durable the instant it completes, independent of whether the
        // *enclosing* `atomic{}`/SQL transaction this whole function runs inside ever commits. A
        // process death after that fsync but before the transaction commits leaves `target` on disk
        // with nothing recorded in `db` - so a retry (the sender's retransmission, or a redelivered
        // receive) re-enters this function, finds `existing == true` and, under the old
        // `if (!existing) coverSiblingSlot(...)` gate, permanently skipped the cover that attempt was
        // supposed to write, unbalancing `real.files`/`decoy.files` forever. `coverSiblingSlot` is
        // idempotent per `fileId` now, so it is always safe to call here regardless of `existing`.
        coverSiblingSlot(fileId, envelope.ciphertext.size)
    }

    private fun safeMediaPath(relative: String): Path {
        val path = mediaRoot.resolve(relative).normalize()
        require(path.startsWith(mediaRoot) && path.parent == mediaRoot && !Files.isSymbolicLink(path))
        return path
    }

    private fun siblingMediaRoot(): Path =
        directory.resolve(if (session.slot == VaultSlot.REAL) "decoy.files" else "real.files")

    /** Total bytes currently on disk under [root] (0 if it does not exist yet - a brand-new vault's
     * media directories are empty, not absent, but this stays defensive rather than assuming that). */
    private fun mediaDirectoryBytes(root: Path): Long {
        if (!Files.isDirectory(root)) return 0L
        return Files.newDirectoryStream(root).use { entries -> entries.sumOf { entry -> if (Files.isRegularFile(entry)) Files.size(entry) else 0L } }
    }

    /**
     * Writes [size] bytes of random, ciphertext-shaped filler into the *other* slot's media
     * directory, under a filename indistinguishable in shape from a real attachment
     * (`<random-hex>.bin`) but with no [FileRecord] in that slot's own database - inert (nothing ever
     * opens it as an attachment; [decryptAttachment] only ever looks up an id present in [db]) but
     * still counted by [mediaDirectoryBytes], which is what keeps `real.files`/`decoy.files` equal in
     * total size continuously rather than only right after `VaultManager.create`/`resetPanicPassword`
     * (`AndroidVaultStorage.alignAllocations`). See `docs/security-model.md` ("Fixed media reservation
     * and blind cover growth", T4.1).
     *
     * T4.1 crash-consistency follow-up: [storeAttachment] used to gate this call on `!existing`
     * (skip on a retry that finds the primary file already durable), which was wrong - the primary
     * write's fsync+rename is durable independent of whether the enclosing transaction that is
     * supposed to also record this cover ever commits, so a crash in that window meant the cover for
     * that [fileId] was permanently lost while the primary file remained. This is now called
     * unconditionally and is idempotent per [fileId]: the cover's filename is derived deterministically
     * from `mac(this session's own fileKey, fileId)`, not from [fileId] itself, specifically so a
     * retry can find and verify its *own* previous attempt (same key, same fileId, same name) while
     * an outside inspector who does not hold this session's fileKey - including one who has fully
     * compromised the *other* slot and so knows its real `fileId`s in plaintext - cannot compute or
     * recognise the corresponding cover name in this slot's sibling directory. (A name derived from
     * [fileId] directly, as suggested by the original review finding, was rejected for exactly this
     * reason: real attachment filenames are already `$fileId.bin`, so reusing the bare, publicly
     * visible `fileId` for the cover name in the sibling directory would let anyone who has decrypted
     * one slot spot the matching cover in the other slot by filename alone, without needing that
     * slot's key at all - the opposite of what a blind cover is for.)
     */
    private fun coverSiblingSlot(fileId: String, size: Int) {
        val sibling = siblingMediaRoot()
        Files.createDirectories(sibling)
        val name = crypto.mac(session.keys.fileKey, ("cover:$fileId").toByteArray()).copyOf(16).toHexId()
        val target = sibling.resolve("$name.bin").normalize()
        check(target.startsWith(sibling) && target.parent == sibling && !Files.isSymbolicLink(target))
        if (Files.exists(target)) {
            // A previous attempt for this fileId already wrote (and fsync'd) this cover - most likely
            // this call is itself a post-crash retry of `storeAttachment` for the same fileId. Verify
            // rather than re-write: re-writing would either duplicate the cover (if the previous
            // attempt's write is still counted) or is simply redundant I/O for no benefit.
            check(Files.isRegularFile(target) && Files.size(target) == size.toLong()) {
                "Vault blind cover size mismatch for an existing fileId"
            }
            return
        }
        val temporary = sibling.resolve(".$name.part")
        val filler = crypto.random(size)
        try {
            FileOutputStream(temporary.toFile()).use { it.write(filler); it.fd.sync() }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            FileChannel.open(sibling, StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary); filler.fill(0) }
    }

    private fun rememberClosed(connection: Long) {
        finishedStreams[connection] = now()
        while (finishedStreams.size > 4096) finishedStreams.keys.firstOrNull()?.let { finishedStreams.remove(it) }
    }

    private suspend fun closeStream(connection: Long) {
        rememberClosed(connection)
        streamDeadlines.remove(connection)
        expectedPeers.remove(connection)
        closeQuietly(connection)
    }

    /**
     * Closing a stream is best effort and must never take a loop down with it. The `:tor` child can
     * die at any instant, and every caller here runs inside a long-lived loop: a `DeadObjectException`
     * escaping a teardown used to kill `receiveLoop`/`outboxLoop`/the sweeper permanently, so the
     * controller could hand a healthy replacement transport to an engine that no longer reads it.
     * Only genuine cancellation (the lock) is allowed through.
     */
    private suspend fun closeQuietly(connection: Long) {
        try { transport?.close(connection) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    // --- Doorbell (T4.17): waking a contact whose messaging onion refused a delivery. ---

    /** Where to knock and what to present, read once under the mutex and wiped by the caller. */
    private class KnockTarget(val onion: String, val token: ByteArray)

    /**
     * Starts one doorbell knock for [peer], unless one is already running or the schedule says no.
     *
     * Deliberately fire-and-forget on the engine [scope] instead of inside the outbox worker that
     * observed the failure: a knock costs up to 240 s, and the outbox admits only four workers at a
     * time and at most one per peer, so waiting for a knock in there would stall that contact's
     * whole queue for four minutes - including the retry the knock is supposed to make succeed.
     *
     * The job is registered before it runs (`CoroutineStart.LAZY`) so that two failures landing at
     * the same instant cannot both pass the "already knocking?" test: the loser cancels a job that
     * has never executed a line.
     */
    private fun ringDoorbell(peer: String) {
        if (closed || peer.isEmpty()) return
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { knock(peer) }
        if (knocks.putIfAbsent(peer, job) != null) { job.cancel(); return }
        job.invokeOnCompletion { knocks.remove(peer, job) }
        job.start()
    }

    /**
     * One complete attempt: decide, record the attempt, ring, record the outcome.
     *
     * No retry of its own - the native opcode already spends a full circuit budget on a single
     * knock - and no error ever escapes: an unreachable doorbell is an ordinary outcome of this
     * feature, not a fault, and it must not take the engine's scope down with it.
     */
    private suspend fun knock(peer: String) {
        try {
            val live = transport ?: return
            val target = mutex.withLock { if (closed) null else prepareKnock(peer, now()) } ?: return
            val acknowledged = try { live.doorbellKnock(target.onion, target.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { target.token.fill(0) }
            // A knock that failed while this device's own transport was dying says nothing about
            // the peer, so it does not stretch that peer's schedule. The attempt stamp written by
            // `prepareKnock` stays, so a local outage still cannot produce a knock storm.
            if (!acknowledged && !live.alive) return
            mutex.withLock { if (!closed) recordKnock(peer, acknowledged) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    /**
     * Admits or refuses one knock, and persists the attempt before it happens.
     *
     * Called with the engine mutex held. The attempt is stamped here, not after the result is
     * known, because the rate limit has to hold for the very case the doorbell exists for: a peer
     * that is off, whose doorbell is therefore just as unreachable as its messaging onion. Stamping
     * on success only would turn every outbox retry into a fresh 240 s knock.
     *
     * A contact paired before this feature has no doorbell columns; that is not an error, it simply
     * has no doorbell agreed with this device, so it is skipped silently.
     */
    private fun prepareKnock(peer: String, now: Long): KnockTarget? {
        val contact = db.getContact(peer) ?: return null
        try {
            if (contact.displayOnly) return null
            if (contact.doorbellOnion.isEmpty() || contact.doorbellToken.size != DoorbellKnockPolicy.TOKEN_BYTES) return null
            val state = knockState(peer)
            if (!DoorbellKnockPolicy.ready(state, now)) return null
            val next = state?.attempted(now) ?: DoorbellKnockState(now, 0)
            val encoded = DoorbellKnockPolicy.encode(next)
            try { db.putBlob(DoorbellKnockPolicy.NAMESPACE, peer, encoded) } finally { encoded.fill(0) }
            return KnockTarget(contact.doorbellOnion, contact.doorbellToken.copyOf())
        } finally { contact.doorbellToken.fill(0); contact.doorbellTokenIssued.fill(0) }
    }

    /**
     * Files the outcome, keeping the attempt stamp [prepareKnock] wrote: only the failure counter
     * moves. Keeping the stamp is what makes the floor survive an acknowledged knock - a peer that
     * answers must still not be knocked again within ten minutes.
     */
    private fun recordKnock(peer: String, acknowledged: Boolean) {
        val state = knockState(peer) ?: return
        val next = if (acknowledged) state.confirmed() else state.failed()
        if (next == state) return
        val encoded = DoorbellKnockPolicy.encode(next)
        try { db.putBlob(DoorbellKnockPolicy.NAMESPACE, peer, encoded) } finally { encoded.fill(0) }
    }

    /**
     * Any successful delivery to [peer] proves it is reachable again, so the growth this schedule
     * accumulated while it was gone is dropped - the floor stays. Writes nothing for a contact that
     * was never knocked, so reachable contacts never accumulate a row here.
     */
    private fun clearKnockFailures(peer: String) {
        val state = knockState(peer) ?: return
        if (state.failures == 0) return
        val encoded = DoorbellKnockPolicy.encode(state.confirmed())
        try { db.putBlob(DoorbellKnockPolicy.NAMESPACE, peer, encoded) } finally { encoded.fill(0) }
    }

    private fun knockState(peer: String): DoorbellKnockState? {
        val stored = db.getBlob(DoorbellKnockPolicy.NAMESPACE, peer) ?: return null
        return try { DoorbellKnockPolicy.decode(stored) } finally { stored.fill(0) }
    }

    override fun close() {
        closed = true
        pairingBundles = null
        knocks.values.forEach { it.cancel() }
        knocks.clear()
        bundleWaiters.values.forEach { it.cancel() }
        bundleWaiters.clear()
        jobs.forEach { it.cancel() }
        jobs.clear()
        trialCursors.clear(); recentPeers.clear()
        val connections = streamDeadlines.keys.toList()
        streamDeadlines.clear(); expectedPeers.clear(); finishedStreams.clear()
        val live = transport
        transport = null
        scope.launch(NonCancellable + Dispatchers.IO) {
            coroutineScope { connections.map { launch { runCatching { live?.close(it) } } }.joinAll() }
        }
    }

    /**
     * Ceiling for a `PacketKind.BUNDLE` payload in either direction. A BundleResponse is
     * ~1.9 KB (a 1832-byte bundle plus envelope header); 8 KiB leaves room without letting an
     * unauthenticated stream push megabytes at the envelope decoder.
     */
    private val maxBundlePayload = 8 * 1024

    private class SecretOutput : ByteArrayOutputStream() { fun wipe() { buf.fill(0); reset() } }

    private fun pack(vararg fields: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { stream -> fields.forEach { stream.writeInt(it.size); stream.write(it) } }
    }.toByteArray()
    private fun unpack(bytes: ByteArray): List<ByteArray> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        buildList { while (input.available() > 0) { val size = input.readInt(); require(size in 0..16 * 1024 * 1024 && size <= input.available()); add(ByteArray(size).also(input::readFully)) } }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hexBytes(text: String): ByteArray {
        require(text.length % 2 == 0 && text.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
