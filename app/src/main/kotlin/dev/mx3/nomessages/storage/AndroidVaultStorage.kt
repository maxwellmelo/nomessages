package dev.mx3.nomessages.storage

import android.content.Context
import dev.mx3.nomessages.core.crypto.Crypto
import dev.mx3.nomessages.core.files.EncryptedFiles
import dev.mx3.nomessages.core.messaging.Envelope
import dev.mx3.nomessages.core.messaging.EnvelopeCodec
import dev.mx3.nomessages.core.vault.VaultKeys
import dev.mx3.nomessages.core.vault.VaultSlot
import dev.mx3.nomessages.core.vault.VaultStorage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

class AndroidVaultStorage(
    context: Context,
    private val crypto: Crypto,
    capacityMiB: Int = DEFAULT_CAPACITY_MIB,
    mediaCapacityMiB: Int = DEFAULT_MEDIA_CAPACITY_MIB,
) : VaultStorage {
    val databaseBytes: Long
    val maxPageCount: Int
    /**
     * T4.1: the fixed media-per-slot reservation `MessagingEngine.storeAttachment` enforces before
     * writing a new attachment, mirroring [databaseBytes]'s fixed-size allocation for the database
     * itself. Real and decoy start at zero media (`create`/`resetPanicPassword` require `real.files`/
     * `decoy.files` equal in size, see [alignAllocations]) and every write past that point keeps
     * them equal too - `storeAttachment` covers the slot it did **not** write to with a same-size
     * blind blob, so [mediaCapacityBytes] is checked once, against the slot that is actually
     * growing, and the other slot never drifts out of budget on its own. See
     * `docs/security-model.md` ("Fixed media reservation and blind cover growth", T4.1).
     */
    val mediaCapacityBytes: Long

    @Volatile
    var active: ChatDatabase? = null
        private set

    private val activeLock = Any()

    init {
        require(capacityMiB in MIN_CAPACITY_MIB..MAX_CAPACITY_MIB) { "Unsupported database capacity" }
        require(mediaCapacityMiB in MIN_MEDIA_CAPACITY_MIB..MAX_MEDIA_CAPACITY_MIB) { "Unsupported media capacity" }
        databaseBytes = capacityMiB.toLong() * 1024 * 1024
        mediaCapacityBytes = mediaCapacityMiB.toLong() * 1024 * 1024
        maxPageCount = (databaseBytes / PAGE_BYTES).toInt()
        loadSqlCipher()
    }

    override fun initialize(directory: Path, slot: VaultSlot, keys: VaultKeys) {
        validateKeys(keys)
        val databasePath = databasePath(directory, slot)
        require(!Files.exists(databasePath, NOFOLLOW_LINKS)) { "Vault database already exists" }
        val mediaDirectory = mediaDirectory(directory, slot)
        require(Files.isDirectory(mediaDirectory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(mediaDirectory)) {
            "Vault media directory is missing"
        }

        val raw = openConfigured(databasePath, keys.dbKey)
        val database = ChatDatabase(raw, maxPageCount)
        try {
            migrate(raw)
            database.fillReserveToCapacity()
            val media = createSetupMedia(mediaDirectory, keys, database, slot == VaultSlot.DECOY, readEpoch(directory))
            if (slot == VaultSlot.DECOY) seedDecoy(database, media)
        } finally {
            database.close()
        }
        check(Files.size(databasePath) == databaseBytes) { "Fixed database allocation failed" }
    }

    override fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable {
        validateKeys(keys)
        synchronized(activeLock) {
            check(active == null) { "A vault database is already open" }
            val path = databasePath(directory, slot)
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Vault database is missing" }
            val raw = openConfigured(path, keys.dbKey)
            try {
                // Upgrade in place before anything reads a table: a vault created by an older build
                // has to open, not be refused. migrate() returns immediately when the database is
                // already current, so the common open costs one PRAGMA user_version read.
                migrate(raw)
                check(Files.size(path) == databaseBytes) { "Vault database allocation changed" }
                val selected = ChatDatabase(raw, maxPageCount) { closed ->
                    synchronized(activeLock) {
                        if (active === closed) active = null
                    }
                }
                active = selected
                return selected
            } catch (error: Throwable) {
                raw.close()
                throw error
            }
        }
    }

    override fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys) {
        validateKeys(real)
        validateKeys(decoy)
        val realDatabase = databasePath(directory, VaultSlot.REAL)
        val decoyDatabase = databasePath(directory, VaultSlot.DECOY)
        check(Files.size(realDatabase) == databaseBytes && Files.size(decoyDatabase) == databaseBytes) {
            "Vault databases do not have fixed capacity"
        }
        val realMedia = mediaAllocatedBytes(mediaDirectory(directory, VaultSlot.REAL))
        val decoyMedia = mediaAllocatedBytes(mediaDirectory(directory, VaultSlot.DECOY))
        check(realMedia == decoyMedia) { "Vault media allocations differ" }
    }

    override fun padMediaToMatch(directory: Path, slot: VaultSlot, matchSlot: VaultSlot) {
        val target = mediaAllocatedBytes(mediaDirectory(directory, matchSlot))
        val current = mediaAllocatedBytes(mediaDirectory(directory, slot))
        if (current >= target) return
        writeBlindFiller(mediaDirectory(directory, slot), target - current)
    }

    /**
     * Writes [deficitBytes] worth of random, ciphertext-shaped filler into [directory] as one or
     * more inert files - same technique as `MessagingEngine.coverSiblingSlot`, chunked so a large
     * deficit (up to [MAX_MEDIA_CAPACITY_MIB]) never asks [Crypto.random] for more than
     * [PAD_CHUNK_BYTES] at once. Each file's name is independently random, carrying no relationship
     * to any other filename in the vault.
     */
    private fun writeBlindFiller(directory: Path, deficitBytes: Long) {
        require(deficitBytes > 0)
        var remaining = deficitBytes
        while (remaining > 0) {
            val chunk = minOf(remaining, PAD_CHUNK_BYTES).toInt()
            val name = "${crypto.random(16).toHex()}.bin"
            val target = directory.resolve(name)
            val temporary = directory.resolve(".$name.part")
            val filler = crypto.random(chunk)
            try {
                Files.newOutputStream(temporary, CREATE_NEW, WRITE).use { it.write(filler) }
                Files.move(temporary, target, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
                filler.fill(0)
            }
            remaining -= chunk
        }
    }

    override fun beforeExport(directory: Path) {
        synchronized(activeLock) { check(active == null) { "Close the vault database before export" } }
        for (slot in VaultSlot.entries) {
            val database = databasePath(directory, slot)
            check(Files.isRegularFile(database, NOFOLLOW_LINKS) && Files.size(database) == databaseBytes) {
                "Vault database is not ready for export"
            }
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                // Path.of requires API 34; resolveSibling is available since API 26.
                val sidecar = database.resolveSibling(database.fileName.toString() + suffix)
                if (Files.exists(sidecar, NOFOLLOW_LINKS)) {
                    check(Files.isRegularFile(sidecar, NOFOLLOW_LINKS) && Files.size(sidecar) == 0L) {
                        "Vault database recovery state must be resolved before export"
                    }
                    Files.delete(sidecar)
                }
            }
        }
        // T4.1: an export used to check only the two fixed-size databases, never the media
        // directories `storeAttachment` writes attachments into directly. `alignAllocations` already
        // enforces this same equality at setup/reset time; checking it again here closes the gap the
        // 2026-09-15 review's gate 2 measurement pointed at (`docs/security-model.md`, "Fixed media
        // reservation and blind cover growth") - an export taken between two `alignAllocations` calls
        // (i.e. any export of an in-use vault) is exactly the case that was never checked.
        val realMedia = mediaAllocatedBytes(mediaDirectory(directory, VaultSlot.REAL))
        val decoyMedia = mediaAllocatedBytes(mediaDirectory(directory, VaultSlot.DECOY))
        check(realMedia == decoyMedia) { "Vault media allocations differ" }
    }

    private fun openConfigured(path: Path, key: ByteArray): SQLiteDatabase {
        // `cipherMemorySecurityHook.preKey` runs `PRAGMA cipher_memory_security = ON;` on the raw
        // connection before SQLCipher's own internal `PRAGMA key` processes `key` - see the hook's
        // doc comment and security-model.md ("Oráculos de isca", T4.7) for why that ordering matters
        // and the later `cipher_memory_security=ON`/check below stays as a same-session verification,
        // not the first time it is ever applied.
        val database = SQLiteDatabase.openOrCreateDatabase(path.toFile(), key, null, null, cipherMemorySecurityHook)
        try {
            // Keep the original order: the cipher pragmas and the journal mode must settle before
            // any table is touched, and max_page_count is applied last so it caps an already
            // configured database.
            val journalMode = applyPragma(database, "journal_mode=DELETE")
            check(journalMode.equals("delete", ignoreCase = true)) {
                "SQLite journal mode is '$journalMode' instead of 'delete'"
            }
            applyPragma(database, "temp_store=MEMORY")
            applyPragma(database, "secure_delete=ON")
            applyPragma(database, "auto_vacuum=NONE")
            applyPragma(database, "foreign_keys=ON")
            applyPragma(database, "synchronous=FULL")
            applyPragma(database, "cipher_memory_security=ON")
            check(pragmaLong(database, "cipher_memory_security") == 1L) { "SQLCipher memory security is unavailable" }
            check(pragmaLong(database, "temp_store") == 2L) { "SQLite temporary storage is not memory-only" }
            check(pragmaLong(database, "secure_delete") == 1L) { "SQLite secure delete is unavailable" }
            check(pragmaLong(database, "auto_vacuum") == 0L) { "SQLite auto-vacuum must be disabled" }
            check(pragmaLong(database, "foreign_keys") == 1L) { "SQLite foreign keys are not enforced" }
            check(pragmaLong(database, "synchronous") == 2L) { "SQLite synchronous mode is not FULL" }
            val appliedPageCount = applyPragma(database, "max_page_count=$maxPageCount")?.toLongOrNull()
            check(appliedPageCount == maxPageCount.toLong()) {
                "SQLite page limit is $appliedPageCount instead of $maxPageCount"
            }
            database.rawQuery("SELECT count(*) FROM sqlite_master").use { cursor -> check(cursor.moveToFirst()) }
            return database
        } catch (error: Throwable) {
            // close() itself can throw (e.g. a native SQLCipher teardown failure); swallowing that
            // into `error` would replace the diagnostic that says which pragma/check actually
            // failed with an unrelated one. Attach it instead so both are visible.
            try {
                database.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    /**
     * Runs a pragma and returns the first column of the row it produced, or null when the pragma
     * produced no row.
     *
     * SQLCipher 4 routes [SQLiteDatabase.execSQL] through its native `executeNonQuery`, which
     * rejects any statement whose first `sqlite3_step` answers `SQLITE_ROW` with
     * "Queries can be performed using SQLiteDatabase query or rawQuery methods only.". Several
     * pragmas answer with the value they settled on - the SQLCipher `cipher_*` family and the
     * assignment form of `journal_mode`, `temp_store`, `secure_delete` and `max_page_count` among
     * them - so every pragma has to go through `rawQuery` with the cursor drained and closed.
     * Pragmas that return nothing are equally safe here: the cursor is simply empty.
     */
    private fun applyPragma(database: SQLiteDatabase, statement: String): String? =
        database.rawQuery("PRAGMA $statement").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun pragmaLong(database: SQLiteDatabase, name: String): Long =
        database.rawQuery("PRAGMA $name").use { cursor ->
            check(cursor.moveToFirst()) { "Required SQLite pragma is unavailable" }
            cursor.getLong(0)
        }

    /** Reads the schema version recorded in the database file itself. */
    private fun readUserVersion(database: SQLiteDatabase): Int =
        database.rawQuery("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst()) { "Vault database version is unavailable" }
            cursor.getInt(0)
        }

    /**
     * Brings a database from whatever schema version it is at up to [SCHEMA_VERSION], inside a
     * single transaction, by replaying one [migrateStep] per version in order. A brand-new vault
     * starts at `user_version = 0` and therefore runs every step; an existing one runs only the
     * missing tail.
     *
     * Runs from **both** entry points: [initialize] (a vault being created) and [open] (a vault
     * being unlocked). A real v1 vault produced by an earlier build must upgrade in place the moment
     * it is opened - refusing it would be an availability failure of the vault, not a cosmetic one.
     *
     * A `user_version` above [SCHEMA_VERSION] is rejected before any transaction is opened and
     * before any table is touched: a database written by a newer build may contain tables or columns
     * this build does not understand, so the only safe move is to not touch it at all.
     *
     * Slot symmetry: this is one function with no [VaultSlot] parameter and no real/decoy branch.
     * `VaultManager.unlock` calls `open` once per unlock for whichever slot the entered password
     * resolved to, so the real vault and the decoy vault take literally the same code path here.
     *
     * Timing: the first [open] of a not-yet-migrated database after an app update is measurably
     * slower than a normal open (one extra transaction plus a schema write); every open after that
     * takes the `version == SCHEMA_VERSION` early return, which is a single pragma read. That is
     * expected and is not a real/decoy distinguisher: the cost depends only on whether *this
     * particular database file* has already been migrated, never on which slot it is. Each of the
     * two databases is migrated independently, the first time that one is individually opened.
     */
    private fun migrate(database: SQLiteDatabase) {
        val version = readUserVersion(database)
        require(version >= 0) { "Unsupported vault database version" }
        check(version <= SCHEMA_VERSION) {
            "Vault database was created by a newer app version " +
                "(schema version $version, this build supports up to $SCHEMA_VERSION)"
        }
        if (version == SCHEMA_VERSION) return
        database.beginTransaction()
        try {
            for (from in version until SCHEMA_VERSION) migrateStep(database, from = from, to = from + 1)
            applyPragma(database, "user_version=$SCHEMA_VERSION")
            check(pragmaLong(database, "user_version") == SCHEMA_VERSION.toLong()) {
                "Vault database version was not persisted"
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        // Defense in depth: max_page_count is a per-connection pragma applied by openConfigured and
        // nothing in a migration step should be able to move it, but the fixed-size guarantee rests
        // on it, so verify rather than assume.
        check(pragmaLong(database, "max_page_count") == maxPageCount.toLong()) {
            "SQLite page limit changed during the vault schema migration"
        }
    }

    /**
     * One schema version's worth of work: brings the database from [from] to [to]. Steps are pure
     * upgrades, keyed by the version they produce, so adding v3 means adding a `3 -> ...` branch and
     * nothing else.
     */
    private fun migrateStep(database: SQLiteDatabase, from: Int, to: Int) {
        check(to == from + 1) { "Schema migration steps must advance exactly one version" }
        when (to) {
            1 -> createSchemaV1(database)
            // v2: the "forwarded" provenance bit of a chat message. Added by ALTER TABLE rather than
            // folded into createSchemaV1 so the upgrade path is the same one an already-created
            // vault would take, and so createSchemaV1 stays a faithful record of what v1 was.
            // NOT NULL DEFAULT 0 backfills every existing row with the safe default (not forwarded),
            // matching how a wire-version-1 envelope decodes.
            2 -> {
                // ALTER TABLE ... ADD COLUMN ... DEFAULT <constant> is metadata-only in SQLite (no
                // row is rewritten), so this should need no new page at all. The reserve draw is
                // deliberately conservative rather than absent: a migration must never be the one
                // write in the app that grows the fixed-capacity file.
                reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN (0,1))",
                )
            }
            // v3 (2026-09-17, T4.16): the peer's doorbell address and token, which arrive inside the
            // signed pairing offer and are reserved for T4.17. Same shape as the v2 step for the
            // same reasons - two metadata-only ALTER TABLEs with constant defaults, so no row is
            // rewritten and an existing contact backfills to "" / x'' meaning "not recorded",
            // exactly what a contact paired before format 2 legitimately is.
            3 -> {
                reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
                database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_onion TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_token BLOB NOT NULL DEFAULT x''")
            }
            // v4 (2026-09-18, T4.17): the other half of the doorbell pair - the token THIS device
            // minted for that contact, which is what an incoming ring from it must present. v3 only
            // recorded the peer's half (where to knock, and what to say there), so the vault could
            // ring but could not answer. The value exists for a few seconds inside the pairing
            // exchange and nowhere else - there is no second authenticated channel on which to agree
            // one later - so it has to be captured at pairing time, exactly like the v3 columns.
            //
            // Same shape as the v2 and v3 steps for the same reasons: one metadata-only ALTER TABLE
            // with a constant default, so no row of `contacts` is rewritten and the fixed-capacity
            // file does not grow. An existing contact backfills to x'' meaning "not recorded",
            // which is the truth for every contact paired before this build: its token was minted,
            // published in our offer and then discarded. Such a contact can still ring us, it just
            // cannot be recognised - re-pairing is the only way to recover it, and the doorbell
            // feature is expected to treat an empty column as "no doorbell with this contact".
            4 -> {
                reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
                database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_token_issued BLOB NOT NULL DEFAULT x''")
            }
            else -> error("No migration step defined for target schema version $to")
        }
    }

    /**
     * Releases enough pages back to the freelist for a migration step that needs [estimatedBytes],
     * mirroring `ChatDatabase.consumeReserve`: pages come from deleted `storage_reserve` rows, never
     * from growing the file.
     *
     * This runs on the raw [SQLiteDatabase] because no [ChatDatabase] exists yet at migration time,
     * so the instance method cannot be reused; the policy is identical on purpose.
     *
     * Headroom below `max_page_count` counts as available. On the [open] path there is none - the
     * file is already at its fixed capacity, so every page must come from the reserve. On the
     * [initialize] path the database has not been filled yet (`fillReserveToCapacity` runs after the
     * migration) and there is no reserve row to delete, so the step simply uses the allocation it is
     * entitled to. Either way `max_page_count` is the ceiling and the file never passes
     * [databaseBytes].
     */
    private fun reserveMigrationPages(database: SQLiteDatabase, estimatedBytes: Int) {
        require(estimatedBytes >= 0) { "Invalid migration page estimate" }
        if (estimatedBytes == 0) return
        val requiredPages = (estimatedBytes.toLong() + PAGE_BYTES - 1) / PAGE_BYTES
        val freePages = pragmaLong(database, "freelist_count")
        val headroomPages = maxPageCount.toLong() - pragmaLong(database, "page_count")
        if (freePages + headroomPages >= requiredPages) return
        val neededBytes = (requiredPages - freePages - headroomPages) * PAGE_BYTES
        val reserveIds = ArrayList<Long>()
        var releasedBytes = 0L
        database.rawQuery("SELECT id,length(payload) FROM storage_reserve ORDER BY id").use { cursor ->
            while (releasedBytes < neededBytes && cursor.moveToNext()) {
                reserveIds += cursor.getLong(0)
                releasedBytes += cursor.getLong(1) + PAGE_BYTES
            }
        }
        if (releasedBytes < neededBytes) throw StorageCapacityException("Encrypted database capacity exhausted")
        reserveIds.forEach { id -> database.execSQL("DELETE FROM storage_reserve WHERE id = ?", arrayOf(id)) }
    }

    private fun createSchemaV1(database: SQLiteDatabase) {
        val statements = listOf(
            "CREATE TABLE meta(k TEXT PRIMARY KEY NOT NULL,v BLOB NOT NULL) WITHOUT ROWID",
            "CREATE TABLE opaque_blobs(namespace TEXT NOT NULL,k TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(namespace,k)) WITHOUT ROWID",
            "CREATE TABLE contacts(id_pub TEXT PRIMARY KEY NOT NULL,alias TEXT NOT NULL,onion TEXT NOT NULL,identity_public BLOB NOT NULL,signal_peer BLOB NOT NULL,paired_at INTEGER NOT NULL,display_only INTEGER NOT NULL CHECK(display_only IN (0,1))) WITHOUT ROWID",
            "CREATE TABLE messages(id TEXT PRIMARY KEY NOT NULL,peer_or_group TEXT NOT NULL,direction INTEGER NOT NULL CHECK(direction BETWEEN 0 AND 1),ts INTEGER NOT NULL,body BLOB NOT NULL,status INTEGER NOT NULL CHECK(status BETWEEN 0 AND 4)) WITHOUT ROWID",
            "CREATE INDEX messages_chat_time ON messages(peer_or_group,ts DESC,id DESC)",
            "CREATE TABLE chat_groups(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,mls_state BLOB NOT NULL,coordinator TEXT NOT NULL,created_at INTEGER NOT NULL) WITHOUT ROWID",
            "CREATE TABLE group_members(group_id TEXT NOT NULL,id_pub TEXT NOT NULL,PRIMARY KEY(group_id,id_pub),FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE) WITHOUT ROWID",
            "CREATE TABLE wire_blobs(hash BLOB PRIMARY KEY NOT NULL,frame BLOB NOT NULL) WITHOUT ROWID",
            "CREATE TABLE outbox(id TEXT PRIMARY KEY NOT NULL,dest_onion TEXT NOT NULL,wire_hash BLOB NOT NULL,created_at INTEGER NOT NULL,FOREIGN KEY(wire_hash) REFERENCES wire_blobs(hash)) WITHOUT ROWID",
            "CREATE INDEX outbox_order ON outbox(created_at,id)",
            "CREATE TABLE files(id TEXT PRIMARY KEY NOT NULL,path_rel TEXT NOT NULL UNIQUE,aead_params BLOB NOT NULL,size INTEGER NOT NULL,epoch INTEGER NOT NULL,display_name TEXT NOT NULL,mime_type TEXT NOT NULL) WITHOUT ROWID",
            "CREATE TABLE pair_graph(first_id TEXT NOT NULL,second_id TEXT NOT NULL,evidence BLOB NOT NULL,paired_at INTEGER NOT NULL,CHECK(first_id < second_id),PRIMARY KEY(first_id,second_id)) WITHOUT ROWID",
            "CREATE TABLE storage_reserve(id INTEGER PRIMARY KEY,payload BLOB NOT NULL)",
        )
        statements.forEach(database::execSQL)
    }

    private fun createSetupMedia(
        directory: Path,
        keys: VaultKeys,
        database: ChatDatabase,
        referenced: Boolean,
        epoch: Long,
    ): List<String> {
        val codec = EncryptedFiles(crypto)
        return MEDIA_FIXTURES.mapIndexed { index, fixture ->
            val fileId = crypto.random(16)
            val fileKey = codec.newFileKey()
            val recordId = fileId.toHex()
            val relativePath = "media-${index + 1}.wff"
            try {
                Files.newOutputStream(directory.resolve(relativePath), CREATE_NEW, WRITE).use { output ->
                    codec.encrypt(ByteArrayInputStream(fixture.bytes), output, fileKey, fileId, epoch, fixture.bytes.size.toLong())
                }
                if (referenced) {
                    val wrappedKey = crypto.seal(keys.fileKey, fileKey, recordId.toByteArray())
                    database.putFile(
                        FileRecord(
                            id = recordId,
                            relativePath = relativePath,
                            encryptedParameters = wrappedKey,
                            size = fixture.bytes.size.toLong(),
                            epoch = epoch,
                            displayName = fixture.displayName,
                            mimeType = fixture.mimeType,
                        ),
                    )
                }
                recordId
            } finally {
                crypto.wipe(fileKey)
                crypto.wipe(fileId)
            }
        }
    }

    private fun seedDecoy(database: ChatDatabase, mediaIds: List<String>) {
        val now = System.currentTimeMillis()
        val contacts = database.transaction {
            DecoyFactory(crypto).create(this, listOf("Ana", "Carlos", "Família", "Marina"))
        }
        val conversations = listOf(
            listOf("Você chega às sete?", "Sim, levo o pão.", "Almoço em família no domingo."),
            listOf("O relatório ficou pronto.", "Obrigado, vejo amanhã.", "Combinado."),
            listOf("A receita deu certo!", "Guardei um pedaço para você.", "Que ótimo 😊"),
            listOf("Caminhada sábado cedo?", "Pode ser às oito.", "Até lá."),
        )
        contacts.forEachIndexed { contactIndex, contact ->
            conversations[contactIndex].forEachIndexed { messageIndex, body ->
                // Two of the four decoy chats end on a recent incoming message that was never
                // opened. A vault whose list can never show an unread badge is a distinguisher on
                // its own: the real vault in daily use almost always has one, so under a coerced
                // inspection the decoy has to be able to look the same. `DELIVERED` and not `READ`
                // is what the local counter reads, and the message is hours - not days - old so the
                // badge sits next to a plausible timestamp.
                val unreadTail = contactIndex in UNREAD_DECOY_CHATS &&
                    messageIndex == conversations[contactIndex].lastIndex
                val timestamp = if (unreadTail) now - (contactIndex + 1L) * HOUR_MILLIS
                    else now - (contactIndex * 9L + conversations[contactIndex].size - messageIndex) * DAY_MILLIS
                val messageId = decoyUuid("text:$contactIndex:$messageIndex")
                // Exactly one decoy message is marked as forwarded. A vault where no message was
                // ever forwarded is a distinguisher of the same kind as one that can never show an
                // unread badge: forwarding is an everyday gesture, so a real vault in daily use
                // almost always contains one, and the decoy has to be able to look the same under a
                // coerced inspection. One and only one keeps it ordinary rather than conspicuous.
                // The envelope flag and the column are set together, exactly as MessagingEngine
                // does on a real send - a row whose body decoded to forwarded = false while the
                // column said true would be a tell all by itself.
                val forwarded = contactIndex == FORWARDED_DECOY_CHAT && messageIndex == FORWARDED_DECOY_MESSAGE
                database.insertMessage(
                    MessageRecord(
                        id = messageId,
                        peerOrGroup = contact.id,
                        direction = if (messageIndex % 2 == 0) MessageDirection.INCOMING else MessageDirection.OUTGOING,
                        timestamp = timestamp,
                        body = EnvelopeCodec.encode(Envelope.Text(messageId, timestamp, body, forwarded = forwarded)),
                        status = if (unreadTail) MessageStatus.DELIVERED else MessageStatus.READ,
                        forwarded = forwarded,
                    ),
                )
            }
        }
        mediaIds.forEachIndexed { index, fileId ->
            val timestamp = now - (index + 1L) * DAY_MILLIS
            val messageId = decoyUuid("attachment:$index")
            val fixture = MEDIA_FIXTURES[index]
            val history = Envelope.Attachment(
                id = messageId,
                timestamp = timestamp,
                name = fixture.displayName,
                mime = fixture.mimeType,
                fileId = fileId.hexBytes(),
                fileEpoch = readEpochForRecord(database, fileId),
                fileKey = ByteArray(32),
                ciphertext = byteArrayOf(),
            )
            database.insertMessage(
                MessageRecord(
                    id = messageId,
                    peerOrGroup = contacts[index].id,
                    direction = MessageDirection.INCOMING,
                    timestamp = timestamp,
                    body = EnvelopeCodec.encode(history),
                    status = MessageStatus.READ,
                ),
            )
        }
    }

    private fun databasePath(directory: Path, slot: VaultSlot): Path =
        directory.resolve(if (slot == VaultSlot.REAL) "real.db" else "decoy.db")

    private fun mediaDirectory(directory: Path, slot: VaultSlot): Path =
        directory.resolve(if (slot == VaultSlot.REAL) "real.files" else "decoy.files")

    private fun readEpoch(directory: Path): Long {
        val header = Files.readAllBytes(directory.resolve("header.bin"))
        require(header.size >= HEADER_EPOCH_OFFSET + Long.SIZE_BYTES) { "Vault header is truncated" }
        return ByteBuffer.wrap(header, HEADER_EPOCH_OFFSET, Long.SIZE_BYTES).long.also {
            require(it >= 0) { "Invalid vault epoch" }
        }
    }

    private fun mediaAllocatedBytes(directory: Path): Long = Files.list(directory).use { paths ->
        paths.mapToLong { path ->
            check(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Invalid vault media entry" }
            Files.size(path)
        }.sum()
    }

    private fun validateKeys(keys: VaultKeys) {
        require(keys.dbKey.size == 32 && keys.fileKey.size == 32 && keys.identityKey.size == 32) { "Invalid vault keys" }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun String.hexBytes(): ByteArray {
        require(length == 32)
        return ByteArray(16) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun readEpochForRecord(database: ChatDatabase, id: String): Long =
        checkNotNull(database.getFile(id)) { "Seeded media record is missing" }.epoch

    private fun decoyUuid(seed: String): String = java.util.UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private data class MediaFixture(val bytes: ByteArray, val displayName: String, val mimeType: String)

    companion object {
        // A 100-member invitation fanout alone exceeds 130 MiB before Signal/SQL overhead.
        const val DEFAULT_CAPACITY_MIB = 256
        const val MIN_CAPACITY_MIB = 16
        const val MAX_CAPACITY_MIB = 512
        // T4.1: fixed per-slot media reservation (real and decoy each get this budget, kept equal by
        // `storeAttachment`'s blind cover write to the slot it did not just write to). 512 MiB is
        // generous against the 8 MiB per-attachment cap `MessagingEngine.sendAttachment` enforces -
        // 64 attachments' worth - while staying well under `MAX_MEDIA_CAPACITY_MIB` so a vault at the
        // default never risks exhausting a small test device's storage on its own.
        const val DEFAULT_MEDIA_CAPACITY_MIB = 512
        const val MIN_MEDIA_CAPACITY_MIB = 16
        const val MAX_MEDIA_CAPACITY_MIB = 4096
        // T4.1 follow-up: chunk size for `writeBlindFiller`'s deficit top-up, capped well under
        // Int.MAX_VALUE so a large deficit (theoretically up to MAX_MEDIA_CAPACITY_MIB) never asks
        // Crypto.random for a single allocation bigger than this.
        private const val PAD_CHUNK_BYTES = 4L * 1024 * 1024
        const val PAGE_BYTES = 4096L
        /**
         * Current vault schema version.
         *
         * v1 - the original tables ([createSchemaV1]).
         * v2 - `messages.forwarded`, added exclusively by the ALTER TABLE in [migrateStep]; the v1
         *      statement list is frozen and intentionally does not mention the column.
         * v3 - `contacts.doorbell_onion` and `contacts.doorbell_token`, reserved for T4.17 and
         *      populated from the format-2 pairing offer (T4.16). Added the same way, for the same
         *      reason: [createSchemaV1] stays a faithful record of what v1 was, and an existing
         *      vault takes exactly the upgrade path a freshly created one takes.
         * v4 - `contacts.doorbell_token_issued`, the mirror image of v3's `doorbell_token`: the
         *      secret *this* device minted for that contact, which is what an incoming ring from it
         *      has to present (T4.17). v3 stored only the peer's half, so the vault could ring a
         *      doorbell but had nothing to verify one against.
         */
        private const val SCHEMA_VERSION = 4

        /**
         * Reserve draw for a metadata-only migration step. A column definition plus its CHECK
         * constraint adds a few dozen bytes to the stored schema text; half a page is already more
         * than that step can possibly need and rounds up to exactly one page.
         */
        private const val SCHEMA_METADATA_ESTIMATE_BYTES = 512
        private const val HEADER_EPOCH_OFFSET = 18
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val HOUR_MILLIS = 60L * 60 * 1000

        /**
         * Decoy conversations left with an unread incoming tail. Two of four: enough that the list
         * always has a badge to show, few enough that it still reads like a quiet week.
         */
        private val UNREAD_DECOY_CHATS = setOf(0, 2)

        /**
         * The single decoy message that carries the forwarded flag: the second message of the first
         * conversation ("Sim, levo o pão." to Ana), an outgoing message deep in the history rather
         * than the last one of a chat.
         *
         * Why this one: it is outgoing (`messageIndex % 2 == 1`), which is the direction a user
         * produces by forwarding, and it is neither the unread tail of chat 0 nor an attachment, so
         * marking it disturbs none of the other decoy properties - the unread badges, the
         * timestamps and the media fixtures are all untouched.
         */
        private const val FORWARDED_DECOY_CHAT = 0
        private const val FORWARDED_DECOY_MESSAGE = 1

        private val MEDIA_FIXTURES = listOf(
            // Phone-camera-shaped, generated at fixture-build time for the same reason as the audio
            // fixture below. These used to be two hard-coded 1x1-pixel PNGs: invisible while
            // attachments only ever rendered as a file-name row, but a blatant distinguisher since
            // inline media bubbles landed (T4.8) - a real vault shows photographs in the chat while
            // the decoy showed a single pixel stretched by `ContentScale.FillWidth` into a flat
            // colour block. That is exactly the kind of "the decoy is obviously not a used phone"
            // tell the plausible-deniability requirement forbids.
            MediaFixture(
                syntheticPhotoPng(width = 960, height = 1280, seed = 0x5EED_1),
                "foto-almoço.png",
                "image/png",
            ),
            MediaFixture(
                syntheticPhotoPng(width = 1280, height = 960, seed = 0x5EED_2),
                "receita.png",
                "image/png",
            ),
            // Generated at fixture-build time (a quiet, short sine-wave clip), not a checked-in
            // binary asset - so the decoy vault's audio bubble renders a real waveform and duration
            // exactly like a genuine recording would, per the "decoy must be visually
            // indistinguishable from the real vault" requirement. WAV rather than AAC: it needs no
            // MediaCodec/MediaMuxer round trip to produce, and `attachmentKind`/mime routing treats
            // "audio/wav" as real audio identically to "audio/mp4".
            MediaFixture(
                syntheticVoiceMessageWav(),
                "mensagem-voz.wav",
                "audio/wav",
            ),
        )

        /**
         * A decoy-only photo: a soft, deterministic, camera-proportioned image (vertical gradient
         * plus a handful of translucent blobs and a vignette) encoded as PNG.
         *
         * Generated rather than checked in as a binary asset, matching [syntheticVoiceMessageWav]
         * below. [seed] makes each fixture a different picture while keeping every vault created by
         * this build byte-identical, so the decoy's media never varies per install in a way an
         * inspector could correlate. Decoding it takes the exact same
         * `NoMessagesController.decodeBoundedThumbnail` path, with the same timing, as a real photo -
         * there is no decoy-specific preview code anywhere.
         */
        private fun syntheticPhotoPng(width: Int, height: Int, seed: Int): ByteArray {
            require(width > 0 && height > 0) { "Invalid decoy photo size" }
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                val random = java.util.Random(seed.toLong())
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.shader = android.graphics.LinearGradient(
                    0f, 0f, width * 0.2f, height.toFloat(),
                    intArrayOf(
                        android.graphics.Color.rgb(214, 198, 170),
                        android.graphics.Color.rgb(151, 138, 112),
                        android.graphics.Color.rgb(74, 68, 58),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                repeat(18) {
                    paint.color = android.graphics.Color.argb(
                        24 + random.nextInt(40),
                        40 + random.nextInt(200),
                        40 + random.nextInt(200),
                        40 + random.nextInt(200),
                    )
                    canvas.drawCircle(
                        random.nextFloat() * width,
                        random.nextFloat() * height,
                        (0.08f + random.nextFloat() * 0.22f) * minOf(width, height),
                        paint,
                    )
                }
                paint.shader = android.graphics.RadialGradient(
                    width / 2f, height / 2f, maxOf(width, height) * 0.7f,
                    intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(120, 0, 0, 0)),
                    floatArrayOf(0.55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                val encoded = java.io.ByteArrayOutputStream()
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, encoded)) {
                    "Could not encode the decoy photo fixture"
                }
                return encoded.toByteArray().also {
                    // The same ceiling MessagingEngine.sendAttachment enforces on a real send: a
                    // decoy attachment that could not have been sent by the app is its own tell.
                    check(it.size in 1..(8 * 1024 * 1024)) { "Decoy photo fixture is out of bounds" }
                }
            } finally {
                bitmap.recycle()
            }
        }

        /** A short, quiet, synthetic mono 16 kHz PCM16 WAV clip - decoy-only fixture audio. */
        private fun syntheticVoiceMessageWav(): ByteArray {
            val sampleRateHz = 16_000
            val durationSeconds = 2
            val sampleCount = sampleRateHz * durationSeconds
            val frequencyHz = 220.0
            val amplitude = 3_000.0 // well under Short.MAX_VALUE: a soft tone, not a harsh beep
            val pcm = ByteArray(sampleCount * 2)
            for (sample in 0 until sampleCount) {
                val timeSeconds = sample.toDouble() / sampleRateHz
                val value = (kotlin.math.sin(2.0 * Math.PI * frequencyHz * timeSeconds) * amplitude).toInt()
                pcm[sample * 2] = (value and 0xFF).toByte()
                pcm[sample * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            val header = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(sampleRateHz).putInt(sampleRateHz * 2)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
            return header.array()
        }

        /**
         * Applies `cipher_memory_security = ON` *before* SQLCipher processes the database key,
         * instead of after, closing the T4.7 timing gap in `openConfigured`'s previous behaviour:
         * `SQLiteDatabase.openOrCreateDatabase(File, ByteArray, ...)` (no hook) issued its own
         * internal `PRAGMA key = '...';` from the `key` argument as part of opening the connection,
         * and only then returned control to this class, whose post-open `applyPragma(database,
         * "cipher_memory_security=ON")` therefore ran after the key had already been derived and
         * held in memory without SQLCipher's hardened allocator protecting it. `SQLiteDatabaseHook`
         * (`net.zetetic:sqlcipher-android` 4.19, confirmed against
         * `net/zetetic/database/sqlcipher/{SQLiteDatabaseHook,SQLiteConnection}.class` in the AAR)
         * calls [preKey] with the not-yet-keyed [SQLiteConnection] *before* that internal `PRAGMA
         * key`, and [postKey] with the same connection right after - exactly the seam needed. The
         * pragma is set in `preKey`, on both `initialize` (vault creation) and `open` (vault unlock),
         * for both `VaultSlot.REAL` and `VaultSlot.DECOY` alike, since both flow through this single
         * `openConfigured`. `postKey` is unused: nothing needs to happen strictly after keying that
         * isn't already covered by the pragmas `openConfigured` applies once `openOrCreateDatabase`
         * returns.
         */
        private val cipherMemorySecurityHook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {
                connection.execute("PRAGMA cipher_memory_security = ON;", null, null)
            }
            override fun postKey(connection: SQLiteConnection) = Unit
        }

        @Volatile
        private var loaded = false

        private fun loadSqlCipher() {
            if (loaded) return
            synchronized(AndroidVaultStorage::class.java) {
                if (!loaded) {
                    System.loadLibrary("sqlcipher")
                    loaded = true
                }
            }
        }
    }
}
