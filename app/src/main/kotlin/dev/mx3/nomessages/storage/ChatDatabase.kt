package dev.mx3.nomessages.storage

import android.content.ContentValues
import android.database.Cursor
import java.security.MessageDigest
import net.zetetic.database.sqlcipher.SQLiteDatabase

class StorageCapacityException(message: String) : IllegalStateException(message)

class ChatDatabase internal constructor(
    private val database: SQLiteDatabase,
    private val maxPageCount: Int,
    private val onClosed: (ChatDatabase) -> Unit = {},
) : AutoCloseable {
    @Volatile
    private var closed = false

    fun getMeta(key: String): ByteArray? {
        validateKey(key)
        return singleBlob("SELECT v FROM meta WHERE k = ?", key)
    }

    fun putMeta(key: String, value: ByteArray) {
        validateKey(key)
        requireBlob(value)
        mutate(value.size) {
            database.execSQL(
                "INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
                arrayOf(key, value),
            )
        }
    }

    fun getBlob(namespace: String, key: String): ByteArray? {
        validateNamespace(namespace)
        validateKey(key)
        return singleBlob("SELECT value FROM opaque_blobs WHERE namespace = ? AND k = ?", namespace, key)
    }

    fun putBlob(namespace: String, key: String, value: ByteArray) {
        validateNamespace(namespace)
        validateKey(key)
        requireBlob(value)
        mutate(value.size) {
            database.execSQL(
                "INSERT INTO opaque_blobs(namespace,k,value) VALUES(?,?,?) " +
                    "ON CONFLICT(namespace,k) DO UPDATE SET value=excluded.value",
                arrayOf(namespace, key, value),
            )
        }
    }

    fun removeBlob(namespace: String, key: String): Boolean {
        validateNamespace(namespace)
        validateKey(key)
        return mutate(0) { database.delete("opaque_blobs", "namespace = ? AND k = ?", arrayOf(namespace, key)) > 0 }
    }

    fun listBlobs(namespace: String): Map<String, ByteArray> {
        validateNamespace(namespace)
        requireOpen()
        return database.rawQuery(
            "SELECT k,value FROM opaque_blobs WHERE namespace = ? ORDER BY k",
            arrayOf(namespace),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1).copyOf())
            }
        }
    }

    fun query(sql: String, bindArgs: Array<out Any?> = emptyArray()): List<Map<String, Any?>> {
        requireOpen()
        require(sql.isNotBlank() && sql.length <= MAX_SQL_LENGTH) { "Invalid query" }
        val normalized = bindArgs.map(::normalizeBindArg).toTypedArray()
        return database.rawQuery(sql, *normalized).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(buildMap {
                        for (column in 0 until cursor.columnCount) {
                            put(cursor.getColumnName(column), cursor.valueAt(column))
                        }
                    })
                }
            }
        }
    }

    fun listContacts(): List<ContactRecord> {
        requireOpen()
        return database.rawQuery(
            "SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only,doorbell_onion," +
                "doorbell_token,doorbell_token_issued FROM contacts ORDER BY alias,id_pub",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.contact()) } }
    }

    fun getContact(id: String): ContactRecord? {
        validateIdentityId(id)
        requireOpen()
        return database.rawQuery(
            "SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only,doorbell_onion," +
                "doorbell_token,doorbell_token_issued FROM contacts WHERE id_pub = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.contact() else null }
    }

    fun putContact(contact: ContactRecord) {
        validateContact(contact)
        mutate(
            contact.alias.length + contact.onion.length + contact.identityPublic.size +
                contact.signalPeer.size + contact.doorbellOnion.length + contact.doorbellToken.size +
                contact.doorbellTokenIssued.size,
        ) {
            database.execSQL(
                "INSERT INTO contacts(id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only," +
                    "doorbell_onion,doorbell_token,doorbell_token_issued) VALUES(?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id_pub) DO UPDATE SET alias=excluded.alias,onion=excluded.onion," +
                    "identity_public=excluded.identity_public,signal_peer=excluded.signal_peer," +
                    "paired_at=excluded.paired_at,display_only=excluded.display_only," +
                    "doorbell_onion=excluded.doorbell_onion,doorbell_token=excluded.doorbell_token," +
                    "doorbell_token_issued=excluded.doorbell_token_issued",
                arrayOf(
                    contact.id,
                    contact.alias,
                    contact.onion,
                    contact.identityPublic,
                    contact.signalPeer,
                    contact.pairedAt,
                    if (contact.displayOnly) 1L else 0L,
                    contact.doorbellOnion,
                    contact.doorbellToken,
                    contact.doorbellTokenIssued,
                ),
            )
        }
    }

    fun renameContact(id: String, alias: String): Boolean {
        validateIdentityId(id)
        validateDisplayText(alias, "alias")
        return mutate(alias.length) {
            val values = ContentValues().apply { put("alias", alias) }
            database.update("contacts", SQLiteDatabase.CONFLICT_ABORT, values, "id_pub = ?", arrayOf(id)) > 0
        }
    }

    fun listChats(): List<ChatRecord> {
        requireOpen()
        return database.rawQuery(
            "SELECT c.id_pub,c.alias,0,m.body,m.ts,m.direction,m.status FROM contacts c " +
                "JOIN messages m ON m.id=(SELECT x.id FROM messages x WHERE x.peer_or_group=c.id_pub ORDER BY x.ts DESC,x.id DESC LIMIT 1) " +
                "UNION ALL SELECT g.id,g.name,1,m.body,COALESCE(m.ts,g.created_at),m.direction,m.status FROM chat_groups g " +
                "LEFT JOIN messages m ON m.id=(SELECT x.id FROM messages x WHERE x.peer_or_group=g.id ORDER BY x.ts DESC,x.id DESC LIMIT 1) " +
                "ORDER BY 5 DESC,1",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ChatRecord(
                            id = cursor.getString(0),
                            title = cursor.getString(1),
                            isGroup = cursor.getLong(2) != 0L,
                            lastMessage = if (cursor.isNull(3)) null else cursor.getBlob(3).copyOf(),
                            lastTimestamp = if (cursor.isNull(4)) null else cursor.getLong(4),
                            lastOutgoing = !cursor.isNull(5) && cursor.getInt(5) == MessageDirection.OUTGOING.ordinal,
                            lastStatus = if (cursor.isNull(6)) null else enumValue<MessageStatus>(cursor.getInt(6)),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Local unread counters, keyed by chat id: incoming messages that were never marked READ on
     * this device. There is no read receipt on the wire, so READ is purely local bookkeeping and
     * the counter never leaks anything to a peer.
     *
     * One grouped scan answers every chat at once and the statement is identical in the real and
     * the decoy vault, so the cost does not depend on which chat, or which vault, is open.
     */
    fun unreadCounts(): Map<String, Int> {
        requireOpen()
        // The arguments are spread on purpose. SQLCipher offers both rawQuery(String, String[]) and
        // rawQuery(String, Object...): an Array<String> picks the first overload, but any other
        // array type picks the vararg one and is passed as a single argument, so the array object
        // itself gets bound - as text, via toString - instead of its elements. That silently
        // changes the bound value and the argument count, which is how this query used to fail.
        return database.rawQuery(
            "SELECT peer_or_group,COUNT(*) FROM messages WHERE direction = ? AND status <> ? GROUP BY peer_or_group",
            *arrayOf<Any>(MessageDirection.INCOMING.ordinal.toLong(), MessageStatus.READ.ordinal.toLong()),
        ).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1)) }
        }
    }

    fun getMessage(id: String): MessageRecord? {
        validateKey(id)
        requireOpen()
        return database.rawQuery(
            "SELECT id,peer_or_group,direction,ts,body,status,forwarded FROM messages WHERE id = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.message() else null }
    }

    fun listMessages(peerOrGroup: String, beforeTimestamp: Long? = null, limit: Int = 100): List<MessageRecord> {
        validatePeerOrGroup(peerOrGroup)
        require(limit in 1..MAX_QUERY_LIMIT) { "Invalid message limit" }
        require(beforeTimestamp == null || beforeTimestamp >= 0) { "Invalid timestamp" }
        requireOpen()
        val sql: String
        val args: Array<out Any>
        // Both branches project the same columns, in the same order, as getMessage - Cursor.message()
        // reads them positionally and is shared by all three.
        if (beforeTimestamp == null) {
            sql = "SELECT id,peer_or_group,direction,ts,body,status,forwarded FROM messages WHERE peer_or_group = ? ORDER BY ts DESC,id DESC LIMIT ?"
            args = arrayOf<Any>(peerOrGroup, limit.toLong())
        } else {
            sql = "SELECT id,peer_or_group,direction,ts,body,status,forwarded FROM messages WHERE peer_or_group = ? AND ts < ? ORDER BY ts DESC,id DESC LIMIT ?"
            args = arrayOf<Any>(peerOrGroup, beforeTimestamp, limit.toLong())
        }
        return database.rawQuery(sql, *args).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.message()) } }
    }

    fun insertMessage(message: MessageRecord) {
        validateKey(message.id)
        validatePeerOrGroup(message.peerOrGroup)
        require(message.timestamp >= 0) { "Invalid timestamp" }
        requireBlob(message.body)
        mutate(message.body.size + message.id.length + message.peerOrGroup.length) {
            database.execSQL(
                "INSERT INTO messages(id,peer_or_group,direction,ts,body,status,forwarded) VALUES(?,?,?,?,?,?,?)",
                arrayOf(
                    message.id,
                    message.peerOrGroup,
                    message.direction.ordinal.toLong(),
                    message.timestamp,
                    message.body,
                    message.status.ordinal.toLong(),
                    // Stored as 0/1, matching the column's CHECK constraint; see the schema v2
                    // migration in AndroidVaultStorage.
                    if (message.forwarded) 1L else 0L,
                ),
            )
        }
    }

    fun updateMessageStatus(id: String, status: MessageStatus): Boolean {
        validateKey(id)
        return mutate(0) {
            val values = ContentValues().apply { put("status", status.ordinal) }
            database.update("messages", SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(id)) > 0
        }
    }

    /**
     * Marks every incoming message of one chat as READ locally and returns how many rows changed.
     * Nothing is sent to the peer: the outgoing side of the conversation is left untouched, so the
     * delivery ticks the sender sees keep meaning exactly what they meant before.
     *
     * The estimate is zero bytes, like [updateMessageStatus]: a status column is rewritten in
     * place, so no reserve page is consumed and the call cannot fail on a full database.
     */
    fun markChatRead(peerOrGroup: String): Int {
        validatePeerOrGroup(peerOrGroup)
        return mutate(0) {
            val values = ContentValues().apply { put("status", MessageStatus.READ.ordinal) }
            database.update(
                "messages",
                SQLiteDatabase.CONFLICT_ABORT,
                values,
                "peer_or_group = ? AND direction = ? AND status <> ?",
                arrayOf<Any>(peerOrGroup, MessageDirection.INCOMING.ordinal.toLong(), MessageStatus.READ.ordinal.toLong()),
            )
        }
    }

    fun listPendingFrames(limit: Int = 100): List<PendingFrame> {
        require(limit in 1..MAX_QUERY_LIMIT) { "Invalid outbox limit" }
        requireOpen()
        return database.rawQuery(
            "SELECT o.id,o.dest_onion,w.frame,o.created_at FROM outbox o " +
                "JOIN wire_blobs w ON w.hash=o.wire_hash ORDER BY o.created_at,o.id LIMIT ?",
            // Spread, see unreadCounts: an unspread Long array binds as text and LIMIT rejects it.
            *arrayOf<Any>(limit.toLong()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PendingFrame(cursor.getString(0), cursor.getString(1), cursor.getBlob(2).copyOf(), cursor.getLong(3)))
            }
        }
    }

    fun listPendingFrameIds(limit: Int = 1000): List<String> {
        require(limit in 1..MAX_QUERY_LIMIT) { "Invalid outbox limit" }
        requireOpen()
        return database.rawQuery(
            "SELECT id FROM outbox ORDER BY created_at,id LIMIT ?",
            // Spread, see unreadCounts: an unspread Long array binds as text and LIMIT rejects it.
            *arrayOf<Any>(limit.toLong()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    fun getPendingFrame(id: String): PendingFrame? {
        validateKey(id)
        requireOpen()
        return database.rawQuery(
            "SELECT o.id,o.dest_onion,w.frame,o.created_at FROM outbox o " +
                "JOIN wire_blobs w ON w.hash=o.wire_hash WHERE o.id = ?",
            arrayOf(id),
        ).use { cursor ->
            if (cursor.moveToFirst()) PendingFrame(cursor.getString(0), cursor.getString(1), cursor.getBlob(2).copyOf(), cursor.getLong(3))
            else null
        }
    }

    fun putPendingFrame(frame: PendingFrame) {
        validateKey(frame.id)
        validateOnion(frame.destinationOnion)
        requireBlob(frame.frame)
        require(frame.createdAt >= 0) { "Invalid timestamp" }
        val hash = MessageDigest.getInstance("SHA-256").digest(frame.frame)
        // Spread, see unreadCounts: an unspread blob array would never match a stored hash.
        val alreadyStored = database.rawQuery("SELECT 1 FROM wire_blobs WHERE hash = ?", *arrayOf<Any>(hash)).use { it.moveToFirst() }
        val estimatedBytes = frame.id.length + frame.destinationOnion.length + if (alreadyStored) 0 else frame.frame.size
        mutate(estimatedBytes) {
            database.execSQL(
                "INSERT INTO wire_blobs(hash,frame) VALUES(?,?) ON CONFLICT(hash) DO NOTHING",
                arrayOf(hash, frame.frame),
            )
            database.execSQL(
                "INSERT INTO outbox(id,dest_onion,wire_hash,created_at) VALUES(?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET dest_onion=excluded.dest_onion,wire_hash=excluded.wire_hash,created_at=excluded.created_at",
                arrayOf(frame.id, frame.destinationOnion, hash, frame.createdAt),
            )
            database.execSQL("DELETE FROM wire_blobs WHERE hash NOT IN (SELECT wire_hash FROM outbox)")
        }
    }

    fun acknowledgePendingFrame(id: String): Boolean {
        validateKey(id)
        return mutate(0) {
            val removed = database.delete("outbox", "id = ?", arrayOf(id)) > 0
            if (removed) database.execSQL("DELETE FROM wire_blobs WHERE hash NOT IN (SELECT wire_hash FROM outbox)")
            removed
        }
    }

    fun getFile(id: String): FileRecord? {
        validateKey(id)
        requireOpen()
        return database.rawQuery(
            "SELECT id,path_rel,aead_params,size,epoch,display_name,mime_type FROM files WHERE id = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.fileRecord() else null }
    }

    fun putFile(file: FileRecord) {
        validateFile(file)
        mutate(file.relativePath.length + file.encryptedParameters.size + file.displayName.length + file.mimeType.length) {
            database.execSQL(
                "INSERT INTO files(id,path_rel,aead_params,size,epoch,display_name,mime_type) VALUES(?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET path_rel=excluded.path_rel,aead_params=excluded.aead_params," +
                    "size=excluded.size,epoch=excluded.epoch,display_name=excluded.display_name,mime_type=excluded.mime_type",
                arrayOf(file.id, file.relativePath, file.encryptedParameters, file.size, file.epoch, file.displayName, file.mimeType),
            )
        }
    }

    fun getGroup(id: String): GroupRecord? {
        validateKey(id)
        requireOpen()
        return database.rawQuery(
            "SELECT id,name,mls_state,coordinator,created_at FROM chat_groups WHERE id = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.group(this) else null }
    }

    fun listGroups(): List<GroupRecord> {
        requireOpen()
        return database.rawQuery(
            "SELECT id,name,mls_state,coordinator,created_at FROM chat_groups ORDER BY name,id",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.group(this@ChatDatabase)) } }
    }

    fun putGroup(group: GroupRecord) {
        validateGroup(group)
        transaction {
            consumeReserve(group.state.size + group.name.length + group.members.sumOf(String::length))
            database.execSQL(
                "INSERT INTO chat_groups(id,name,mls_state,coordinator,created_at) VALUES(?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET name=excluded.name,mls_state=excluded.mls_state," +
                    "coordinator=excluded.coordinator,created_at=excluded.created_at",
                arrayOf(group.id, group.name, group.state, group.coordinator, group.createdAt),
            )
            database.delete("group_members", "group_id = ?", arrayOf(group.id))
            group.members.forEach { member ->
                database.execSQL("INSERT INTO group_members(group_id,id_pub) VALUES(?,?)", arrayOf(group.id, member))
            }
        }
    }

    fun getPairEvidence(firstId: String, secondId: String): PairEvidence? {
        val (first, second) = normalizedPair(firstId, secondId)
        requireOpen()
        return database.rawQuery(
            "SELECT first_id,second_id,evidence,paired_at FROM pair_graph WHERE first_id = ? AND second_id = ?",
            arrayOf(first, second),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.pairEvidence() else null }
    }

    fun listPairEvidence(): List<PairEvidence> {
        requireOpen()
        return database.rawQuery(
            "SELECT first_id,second_id,evidence,paired_at FROM pair_graph ORDER BY first_id,second_id",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.pairEvidence()) } }
    }

    fun putPairEvidence(evidence: PairEvidence) {
        val (first, second) = normalizedPair(evidence.firstId, evidence.secondId)
        requireBlob(evidence.evidence)
        require(evidence.pairedAt >= 0) { "Invalid timestamp" }
        mutate(evidence.evidence.size) {
            database.execSQL(
                "INSERT INTO pair_graph(first_id,second_id,evidence,paired_at) VALUES(?,?,?,?) " +
                    "ON CONFLICT(first_id,second_id) DO UPDATE SET evidence=excluded.evidence,paired_at=excluded.paired_at",
                arrayOf(first, second, evidence.evidence, evidence.pairedAt),
            )
        }
    }

    fun <T> transaction(block: ChatDatabase.() -> T): T {
        requireOpen()
        if (database.inTransaction()) return block()
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            try {
                database.close()
            } finally {
                onClosed(this)
            }
        }
    }

    internal fun fillReserveToCapacity() {
        requireOpen()
        var maximumPayload = RESERVE_CHUNK_BYTES
        while (pageCount() < maxPageCount) {
            val remainingPages = maxPageCount - pageCount()
            val preferredBytes = when {
                remainingPages > 300 -> RESERVE_CHUNK_BYTES
                remainingPages > 24 -> 32 * 1024
                else -> 512
            }
            val payloadBytes = minOf(preferredBytes, maximumPayload)
            try {
                database.execSQL(
                    "INSERT INTO storage_reserve(payload) VALUES(zeroblob(?))",
                    arrayOf(payloadBytes.toLong()),
                )
            } catch (error: RuntimeException) {
                if (pageCount() == maxPageCount) break
                if (payloadBytes == 512) throw error
                maximumPayload = if (payloadBytes > 32 * 1024) 32 * 1024 else 512
            }
        }
        check(pageCount() == maxPageCount) { "Unable to allocate fixed database capacity" }
    }

    internal fun pageCount(): Int =
        database.rawQuery("PRAGMA page_count").use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun groupMembers(groupId: String): List<String> =
        database.rawQuery("SELECT id_pub FROM group_members WHERE group_id = ? ORDER BY id_pub", arrayOf(groupId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun singleBlob(sql: String, vararg args: Any): ByteArray? {
        requireOpen()
        return database.rawQuery(sql, *args).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0).copyOf() else null }
    }

    private fun <T> mutate(estimatedBytes: Int, action: () -> T): T = transaction {
        consumeReserve(estimatedBytes)
        action()
    }

    private fun consumeReserve(estimatedBytes: Int) {
        require(estimatedBytes in 0..MAX_WRITE_ESTIMATE_BYTES) { "Write exceeds database record limit" }
        if (estimatedBytes == 0) return
        val requiredPages = (estimatedBytes.toLong() + WRITE_OVERHEAD_BYTES + AndroidVaultStorage.PAGE_BYTES - 1) /
            AndroidVaultStorage.PAGE_BYTES
        val freePages = database.rawQuery("PRAGMA freelist_count").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        if (freePages >= requiredPages) return
        val neededBytes = (requiredPages - freePages) * AndroidVaultStorage.PAGE_BYTES
        val reserveIds = ArrayList<Long>()
        var releasedBytes = 0L
        database.rawQuery("SELECT id,length(payload) FROM storage_reserve ORDER BY id").use { cursor ->
            while (releasedBytes < neededBytes && cursor.moveToNext()) {
                reserveIds += cursor.getLong(0)
                releasedBytes += cursor.getLong(1) + AndroidVaultStorage.PAGE_BYTES
            }
        }
        if (releasedBytes < neededBytes) throw StorageCapacityException("Encrypted database capacity exhausted")
        reserveIds.forEach { id -> database.execSQL("DELETE FROM storage_reserve WHERE id = ?", arrayOf(id)) }
    }

    private fun requireOpen() = check(!closed && database.isOpen) { "Database is closed" }

    // Column order must match every "SELECT ... FROM contacts" that feeds this mapper:
    // id_pub, alias, onion, identity_public, signal_peer, paired_at, display_only,
    // doorbell_onion, doorbell_token, doorbell_token_issued.
    private fun Cursor.contact() = ContactRecord(
        id = getString(0),
        alias = getString(1),
        onion = getString(2),
        identityPublic = getBlob(3).copyOf(),
        signalPeer = getBlob(4).copyOf(),
        pairedAt = getLong(5),
        displayOnly = getLong(6) != 0L,
        doorbellOnion = getString(7),
        doorbellToken = getBlob(8).copyOf(),
        doorbellTokenIssued = getBlob(9).copyOf(),
    )

    // Column order must match every "SELECT ... FROM messages" that feeds this mapper:
    // id, peer_or_group, direction, ts, body, status, forwarded.
    private fun Cursor.message() = MessageRecord(
        id = getString(0),
        peerOrGroup = getString(1),
        direction = enumValue<MessageDirection>(getInt(2)),
        timestamp = getLong(3),
        body = getBlob(4).copyOf(),
        status = enumValue<MessageStatus>(getInt(5)),
        forwarded = getLong(6) != 0L,
    )

    private fun Cursor.fileRecord() = FileRecord(
        id = getString(0),
        relativePath = getString(1),
        encryptedParameters = getBlob(2).copyOf(),
        size = getLong(3),
        epoch = getLong(4),
        displayName = getString(5),
        mimeType = getString(6),
    )

    private fun Cursor.group(owner: ChatDatabase) = GroupRecord(
        id = getString(0),
        name = getString(1),
        state = getBlob(2).copyOf(),
        members = owner.groupMembers(getString(0)),
        coordinator = getString(3),
        createdAt = getLong(4),
    )

    private fun Cursor.pairEvidence() = PairEvidence(getString(0), getString(1), getBlob(2).copyOf(), getLong(3))

    private fun Cursor.valueAt(column: Int): Any? = when (getType(column)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(column)
        Cursor.FIELD_TYPE_STRING -> getString(column)
        Cursor.FIELD_TYPE_BLOB -> getBlob(column).copyOf()
        else -> error("Unsupported SQLite column type")
    }

    private fun normalizeBindArg(value: Any?): Any? = when (value) {
        null, is String, is ByteArray, is Long, is Double -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is Float -> value.toDouble()
        else -> throw IllegalArgumentException("Unsupported query binding")
    }

    private inline fun <reified T : Enum<T>> enumValue(ordinal: Int): T =
        enumValues<T>().getOrNull(ordinal) ?: throw IllegalStateException("Invalid stored enum value")

    private fun validateContact(contact: ContactRecord) {
        validateIdentityId(contact.id)
        validateDisplayText(contact.alias, "alias")
        require(contact.identityPublic.size == 32) { "Invalid identity public key" }
        require(contact.signalPeer.size <= MAX_BLOB_BYTES) { "Signal peer state is too large" }
        require(contact.pairedAt >= 0) { "Invalid pairing timestamp" }
        if (contact.displayOnly) require(contact.onion.isEmpty()) { "Display-only contacts cannot have a network address" }
        else validateOnion(contact.onion)
        // Reserved for T4.17 but validated on the way in, because a malformed value would only be
        // discovered much later, by the feature that finally reads it. Empty means "not recorded":
        // a contact paired before schema v3, or a display-only one.
        if (contact.doorbellOnion.isNotEmpty()) validateOnion(contact.doorbellOnion)
        require(contact.doorbellToken.isEmpty() || contact.doorbellToken.size == DOORBELL_TOKEN_BYTES) {
            "Invalid doorbell token"
        }
        require(contact.doorbellOnion.isNotEmpty() == contact.doorbellToken.isNotEmpty()) {
            "A doorbell address and its token are recorded together or not at all"
        }
        // The token this device issued to the contact (schema v4). Same 32 bytes when present, same
        // "empty means not recorded" convention.
        require(contact.doorbellTokenIssued.isEmpty() || contact.doorbellTokenIssued.size == DOORBELL_TOKEN_BYTES) {
            "Invalid issued doorbell token"
        }
        // An implication, deliberately NOT the equivalence the two peer columns get.
        //
        // One direction is a genuine invariant and is enforced: this device can only have minted a
        // token for a contact inside a pairing exchange, and that same exchange is the only place
        // the peer's address and token can come from - so an issued token with no peer half is a
        // record that could not have been produced by any code path and is refused.
        //
        // The converse is a real, legitimate state and must not be refused: every contact paired
        // before schema v4 has the peer's half and no issued half, because the value was minted,
        // published inside our offer and then dropped (see `PairingEngine.finish`). The v4 migration
        // backfills those rows with x'' and cannot invent the lost secret. Demanding all three
        // together would turn each of those rows into a write that throws the first time anything
        // re-saves it - a validation rule that bricks pre-existing, perfectly honest data.
        require(contact.doorbellTokenIssued.isEmpty() || contact.doorbellToken.isNotEmpty()) {
            "An issued doorbell token requires the peer's doorbell address and token"
        }
    }

    private fun validateFile(file: FileRecord) {
        validateKey(file.id)
        require(FILE_NAME.matches(file.relativePath)) { "Invalid relative file path" }
        requireBlob(file.encryptedParameters)
        require(file.size >= 0 && file.epoch >= 0) { "Invalid file metadata" }
        validateDisplayText(file.displayName, "file name")
        require(MIME_TYPE.matches(file.mimeType)) { "Invalid MIME type" }
    }

    private fun validateGroup(group: GroupRecord) {
        validateKey(group.id)
        validateDisplayText(group.name, "group name")
        requireBlob(group.state)
        require(group.members.size in 1..100 && group.members.distinct().size == group.members.size) { "Invalid group members" }
        group.members.forEach(::validateIdentityId)
        validateIdentityId(group.coordinator)
        require(group.coordinator in group.members) { "Coordinator must be a group member" }
        require(group.createdAt >= 0) { "Invalid group timestamp" }
    }

    private fun normalizedPair(firstId: String, secondId: String): Pair<String, String> {
        validateIdentityId(firstId)
        validateIdentityId(secondId)
        require(firstId != secondId) { "Pair edge must contain two identities" }
        return if (firstId < secondId) firstId to secondId else secondId to firstId
    }

    private fun validateIdentityId(value: String) = require(IDENTITY_ID.matches(value)) { "Invalid identity id" }
    private fun validateOnion(value: String) = require(ONION_ADDRESS.matches(value)) { "Invalid onion address" }
    private fun validatePeerOrGroup(value: String) = require(value.isNotBlank() && value.length <= MAX_KEY_LENGTH) { "Invalid peer or group id" }
    private fun validateKey(value: String) = require(value.isNotBlank() && value.length <= MAX_KEY_LENGTH && '\u0000' !in value) { "Invalid storage key" }
    private fun validateNamespace(value: String) = require(NAMESPACE.matches(value)) { "Invalid storage namespace" }
    private fun validateDisplayText(value: String, label: String) = require(value.isNotBlank() && value.length <= MAX_DISPLAY_LENGTH && '\u0000' !in value) { "Invalid $label" }
    private fun requireBlob(value: ByteArray) = require(value.size <= MAX_BLOB_BYTES) { "Blob exceeds database record limit" }

    companion object {
        internal const val RESERVE_CHUNK_BYTES = 1024 * 1024
        private const val WRITE_OVERHEAD_BYTES = 8 * 1024
        private const val MAX_BLOB_BYTES = 16 * 1024 * 1024
        private const val MAX_WRITE_ESTIMATE_BYTES = MAX_BLOB_BYTES + 1024 * 1024
        /**
         * Matches both doorbell tokens minted by `PairingEngine` - the peer's (`doorbell_token`)
         * and this device's own (`doorbell_token_issued`). Both are `crypto.random(32)`; reserved
         * for T4.17.
         */
        private const val DOORBELL_TOKEN_BYTES = 32
        private const val MAX_DISPLAY_LENGTH = 256
        private const val MAX_KEY_LENGTH = 512
        private const val MAX_QUERY_LIMIT = 1000
        private const val MAX_SQL_LENGTH = 64 * 1024
        private val IDENTITY_ID = Regex("[0-9a-f]{64}")
        private val ONION_ADDRESS = Regex("[a-z2-7]{56}\\.onion")
        private val NAMESPACE = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val MIME_TYPE = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    }
}
