package dev.mx3.nomessages.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mx3.nomessages.core.crypto.Crypto
import dev.mx3.nomessages.core.crypto.SigningKeys
import dev.mx3.nomessages.core.files.EncryptedFiles
import dev.mx3.nomessages.core.messaging.Envelope
import dev.mx3.nomessages.core.messaging.EnvelopeCodec
import dev.mx3.nomessages.core.protocol.PairingIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import dev.mx3.nomessages.core.vault.KdfParams
import dev.mx3.nomessages.core.vault.VaultManager
import dev.mx3.nomessages.core.vault.VaultSlot
import dev.mx3.nomessages.runtime.MessagingEngine
import dev.mx3.nomessages.runtime.MessagingError
import dev.mx3.nomessages.runtime.MessagingErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVaultStorageTest {
    private lateinit var root: Path
    private lateinit var crypto: Crypto
    private lateinit var storage: AndroidVaultStorage
    private lateinit var manager: VaultManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "storage-test-")
        crypto = TestCrypto()
        storage = AndroidVaultStorage(context, crypto, capacityMiB = 16)
        manager = VaultManager(crypto, storage)
    }

    @After
    fun tearDown() {
        storage.active?.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun createProducesFixedEncryptedDatabasesAndBalancedMedia() {
        val vault = createVault()

        assertEquals(storage.databaseBytes, Files.size(vault.resolve("real.db")))
        assertEquals(Files.size(vault.resolve("real.db")), Files.size(vault.resolve("decoy.db")))
        assertEquals(mediaBytes(vault.resolve("real.files")), mediaBytes(vault.resolve("decoy.files")))

        val bytes = Files.readAllBytes(vault.resolve("decoy.db"))
        assertFalse(bytes.contains("Almoço em família".toByteArray()))
        assertFalse(bytes.contains("contacts".toByteArray()))
    }

    /**
     * T4.7(b): `cipher_memory_security` has to read back ON immediately after `manager.unlock`
     * returns, for both slots and right after `createVault` (which opens and closes both databases
     * through the same `openConfigured`). This proves the pragma is in effect by the time any code
     * outside `AndroidVaultStorage` can touch the connection; that `AndroidVaultStorage.openConfigured`
     * now applies it through `SQLiteDatabaseHook.preKey` - *before* SQLCipher's internal `PRAGMA key`
     * processes the database key, not after - is a property of `openOrCreateDatabase` itself and is
     * documented with its AAR-verified evidence in `AndroidVaultStorage.cipherMemorySecurityHook` and
     * `security-model.md` ("Oráculos de isca", T4.7); it is not independently observable from Kotlin.
     */
    @Test
    fun cipherMemorySecurityIsOnImmediatelyAfterOpenForBothSlots() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            // Unlike `PRAGMA user_version`, SQLCipher answers this one with SQLite column type TEXT
            // ("1"/"0", not an integer) - `AndroidVaultStorage.pragmaLong` copes with that by reading
            // through `cursor.getLong`, which coerces regardless of declared type; `ChatDatabase.query`
            // preserves the native type instead, so the assertion compares by string here.
            assertEquals("1", db.query("PRAGMA cipher_memory_security").single().values.single().toString())
        }
        manager.unlock(vault, PANIC_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            assertEquals("1", db.query("PRAGMA cipher_memory_security").single().values.single().toString())
        }
    }

    @Test
    fun sqlCipherRejectsAnUnrelatedKey() {
        val vault = createVault()

        assertThrows(Exception::class.java) {
            SQLiteDatabase.openOrCreateDatabase(
                vault.resolve("real.db").toFile(),
                ByteArray(32) { 0x55 },
                null,
                null,
            ).use { wrong ->
                wrong.rawQuery("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
            }
        }
    }

    @Test
    fun selectedVaultsAreIsolatedAndCloseClearsActive() {
        val vault = createVault()
        val session = manager.unlock(vault, REAL_PASSWORD.copyOf())
        val real = requireNotNull(storage.active)
        real.putContact(
            ContactRecord(
                id = "11".repeat(32),
                alias = "Contato reservado",
                onion = "a".repeat(56) + ".onion",
                identityPublic = ByteArray(32) { 1 },
                signalPeer = byteArrayOf(2, 3),
                pairedAt = 1234L,
            ),
        )
        session.close()
        assertNull(storage.active)

        manager.unlock(vault, PANIC_PASSWORD.copyOf()).use {
            val decoy = requireNotNull(storage.active)
            assertNull(decoy.getContact("11".repeat(32)))
            val displayContacts = decoy.listContacts()
            assertTrue(displayContacts.size in 3..5)
            assertTrue(displayContacts.none { contact -> contact.displayOnly })
            assertTrue(displayContacts.all { contact -> contact.onion.matches(Regex("[a-z2-7]{56}\\.onion")) })
            val evidence = decoy.listPairEvidence()
            assertEquals(displayContacts.size, evidence.size)
            assertTrue(
                evidence.all { record ->
                    dev.mx3.nomessages.core.protocol.PairEvidence.decode(record.evidence).valid(crypto)
                },
            )
            assertTrue(decoy.listChats().size in 3..5)
            assertTrue(decoy.listGroups().isEmpty())
        }
        assertNull(storage.active)
    }

    @Test
    fun decoyAttachmentsUseNormalHistoryAndDecryptForTheViewer() {
        val vault = createVault()
        manager.unlock(vault, PANIC_PASSWORD.copyOf()).use { session ->
            val db = requireNotNull(storage.active)
            val attachments = db.listContacts().flatMap { contact -> db.listMessages(contact.id) }
                .mapNotNull { record -> EnvelopeCodec.decode(record.body) as? Envelope.Attachment }
            // Two PNG image fixtures and one synthetic WAV audio fixture (so the decoy's audio
            // bubble also renders a real waveform/duration, not just its images).
            assertEquals(3, attachments.size)
            attachments.forEach { attachment ->
                val fileId = attachment.fileId.joinToString("") { "%02x".format(it.toInt() and 255) }
                val record = requireNotNull(db.getFile(fileId))
                val fileKey = requireNotNull(
                    crypto.open(session.keys.fileKey, record.encryptedParameters, fileId.toByteArray()),
                )
                try {
                    val plaintext = ByteArrayOutputStream()
                    Files.newInputStream(vault.resolve("decoy.files").resolve(record.relativePath)).use { input ->
                        EncryptedFiles(crypto).decrypt(input, plaintext, fileKey, attachment.fileId, record.epoch)
                    }
                    val magicNumber = plaintext.toByteArray().copyOf(4)
                    when (attachment.mime) {
                        "image/png" -> assertArrayEquals(byteArrayOf(-119, 80, 78, 71), magicNumber)
                        "audio/wav" -> assertArrayEquals("RIFF".toByteArray(), magicNumber)
                        else -> throw AssertionError("Unexpected decoy attachment mime: ${attachment.mime}")
                    }
                } finally {
                    fileKey.fill(0)
                }
            }
        }
    }

    @Test
    fun syntheticDecoyContactsHavePersistedSignalSessions() {
        val vault = createVault()
        manager.unlock(vault, PANIC_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            val encodedIdentity = requireNotNull(db.getMeta(StorageKeys.IDENTITY))
            PairingIdentity.restore(encodedIdentity).use { identity ->
                db.listContacts().forEach { contact ->
                    val ciphertext = identity.sessions.encrypt(contact.id, "offline message".toByteArray())
                    assertTrue(ciphertext.isNotEmpty())
                    ciphertext.fill(0)
                }
            }
            encodedIdentity.fill(0)
            assertEquals(32, db.getMeta(StorageKeys.ONION_SEED)?.size)
        }
    }

    @Test
    fun typedRecordsAndOpaqueStateRoundTripAsCopies() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            val message = MessageRecord(
                id = "m-1",
                peerOrGroup = "22".repeat(32),
                direction = MessageDirection.OUTGOING,
                timestamp = 3000,
                body = "mensagem privada".toByteArray(),
                status = MessageStatus.PENDING,
            )
            db.insertMessage(message)
            db.updateMessageStatus(message.id, MessageStatus.SENT)
            db.putBlob("signal", message.peerOrGroup, byteArrayOf(4, 5, 6))
            db.putPendingFrame(PendingFrame("f-1", "b".repeat(56) + ".onion", byteArrayOf(7, 8), 4000))

            val stored = db.listMessages(message.peerOrGroup)
            assertEquals(1, stored.size)
            assertEquals(MessageStatus.SENT, stored.single().status)
            assertArrayEquals(message.body, stored.single().body)
            stored.single().body.fill(0)
            assertArrayEquals("mensagem privada".toByteArray(), db.listMessages(message.peerOrGroup).single().body)
            assertArrayEquals(byteArrayOf(4, 5, 6), db.getBlob("signal", message.peerOrGroup))
            assertEquals("f-1", db.listPendingFrames().single().id)
            assertTrue(db.acknowledgePendingFrame("f-1"))
            assertTrue(db.listPendingFrames().isEmpty())
        }
    }

    /**
     * Schema v2 adds `messages.forwarded` through an ALTER TABLE in `AndroidVaultStorage.migrate`,
     * never through the frozen v1 CREATE TABLE. A freshly created vault runs every migration step
     * from `user_version = 0`, so this covers both that the step ran and that the column round trips
     * through insert/get/list.
     */
    @Test
    fun forwardedFlagRoundTripsAfterTheSchemaVersionTwoMigration() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            assertEquals(4L, db.query("PRAGMA user_version").single().values.single())

            val peer = "55".repeat(32)
            db.insertMessage(
                MessageRecord(
                    id = "forwarded-yes",
                    peerOrGroup = peer,
                    direction = MessageDirection.OUTGOING,
                    timestamp = 5_000,
                    body = "mensagem encaminhada".toByteArray(),
                    status = MessageStatus.PENDING,
                    forwarded = true,
                ),
            )
            // No `forwarded` argument at all: the model default has to reach the column as 0.
            db.insertMessage(
                MessageRecord(
                    id = "forwarded-no",
                    peerOrGroup = peer,
                    direction = MessageDirection.OUTGOING,
                    timestamp = 4_000,
                    body = "mensagem original".toByteArray(),
                    status = MessageStatus.PENDING,
                ),
            )

            assertTrue(requireNotNull(db.getMessage("forwarded-yes")).forwarded)
            assertFalse(requireNotNull(db.getMessage("forwarded-no")).forwarded)
            val listed = db.listMessages(peer).associateBy { it.id }
            assertEquals(2, listed.size)
            assertTrue(requireNotNull(listed["forwarded-yes"]).forwarded)
            assertFalse(requireNotNull(listed["forwarded-no"]).forwarded)
            // Stored as the 0/1 integer the column's CHECK constraint allows, not as a text or NULL.
            assertEquals(1L, db.query("SELECT forwarded FROM messages WHERE id = ?", arrayOf("forwarded-yes")).single()["forwarded"])
            assertEquals(0L, db.query("SELECT forwarded FROM messages WHERE id = ?", arrayOf("forwarded-no")).single()["forwarded"])
        }
    }

    /**
     * The upgrade-in-place path, which a real Galaxy Note10+ hit after an app update: a vault
     * written by the previous build (schema v1, no `messages.forwarded`) has to open on this build
     * and be migrated, not refused. `forwardedFlagRoundTripsAfterTheSchemaVersionTwoMigration` only
     * covers creation from scratch, where `user_version` starts at 0 and `initialize()` runs the
     * migration; this covers `open()` running it on a database that already holds user data.
     *
     * The v1 shape is reproduced by rebuilding `messages` from the frozen v1 DDL (SQLite here has no
     * `DROP COLUMN`) and rewinding `PRAGMA user_version`, so the reopen genuinely starts at 1.
     */
    @Test
    fun vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened() {
        val vault = createVault()
        val peer = "66".repeat(32)
        val originalBody = "mensagem anterior à atualização".toByteArray()
        val session = manager.unlock(vault, REAL_PASSWORD.copyOf())
        // The session wipes its key material on close, and the raw connection below needs it after
        // that; copy it here and wipe the copy in the finally block, as the attachment test does
        // with the file key it unwraps.
        val dbKey = session.keys.dbKey.copyOf()
        try {
            requireNotNull(storage.active).insertMessage(
                MessageRecord(
                    id = "pre-migration",
                    peerOrGroup = peer,
                    direction = MessageDirection.INCOMING,
                    timestamp = 1_000,
                    body = originalBody.copyOf(),
                    status = MessageStatus.DELIVERED,
                ),
            )
            session.close()
            assertNull(storage.active)

            downgradeToSchemaVersionOne(vault.resolve("real.db"), dbKey)

            manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
                val db = requireNotNull(storage.active)
                assertEquals(4L, db.query("PRAGMA user_version").single().values.single())

                // The row written before the column existed survives and backfills to "not
                // forwarded", which is how a wire-version-1 envelope decodes.
                val restored = requireNotNull(db.getMessage("pre-migration"))
                assertArrayEquals(originalBody, restored.body)
                assertFalse(restored.forwarded)
                assertEquals(
                    0L,
                    db.query("SELECT forwarded FROM messages WHERE id = ?", arrayOf("pre-migration")).single()["forwarded"],
                )

                db.insertMessage(
                    MessageRecord(
                        id = "post-migration",
                        peerOrGroup = peer,
                        direction = MessageDirection.OUTGOING,
                        timestamp = 2_000,
                        body = "mensagem encaminhada".toByteArray(),
                        status = MessageStatus.PENDING,
                        forwarded = true,
                    ),
                )
                assertTrue(requireNotNull(db.getMessage("post-migration")).forwarded)
                val listed = db.listMessages(peer).associateBy { record -> record.id }
                assertEquals(2, listed.size)
                assertTrue(requireNotNull(listed["post-migration"]).forwarded)
                assertFalse(requireNotNull(listed["pre-migration"]).forwarded)
                assertEquals(
                    1L,
                    db.query("SELECT forwarded FROM messages WHERE id = ?", arrayOf("post-migration")).single()["forwarded"],
                )
            }
            // The migration drew whatever it needed from the reserve: the fixed allocation is intact,
            // which is also what `open()` itself re-checks right after migrating.
            assertEquals(storage.databaseBytes, Files.size(vault.resolve("real.db")))
        } finally {
            dbKey.fill(0)
        }
    }

    /**
     * The opposite direction of the same guard: a database written by a *newer* build may contain
     * tables or columns this build cannot interpret, so it must be refused before any transaction is
     * opened, and the message has to say why - the old generic "Unsupported vault database version"
     * gave a user with an out-of-date app nothing to act on.
     */
    @Test
    fun vaultFromANewerAppVersionIsRejectedWithAnExplicitMessage() {
        val vault = createVault()
        val session = manager.unlock(vault, REAL_PASSWORD.copyOf())
        val dbKey = session.keys.dbKey.copyOf()
        try {
            session.close()
            rawDatabase(vault.resolve("real.db"), dbKey) { raw ->
                raw.rawQuery("PRAGMA user_version=99").use { cursor -> cursor.moveToFirst() }
            }

            val error = assertThrows(IllegalStateException::class.java) {
                manager.unlock(vault, REAL_PASSWORD.copyOf())
            }
            val message = error.message.orEmpty()
            assertTrue(message, message.contains("created by a newer app version"))
            assertTrue(message, message.contains("schema version 99"))
            assertNull(storage.active)
        } finally {
            dbKey.fill(0)
        }
    }

    @Test
    fun identicalOutboxPayloadsShareOneEncryptedDatabaseBlob() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            val frame = ByteArray(70_000) { (it % 251).toByte() }
            db.putPendingFrame(PendingFrame("shared:one", "c".repeat(56) + ".onion", frame, 1))
            db.putPendingFrame(PendingFrame("shared:two", "d".repeat(56) + ".onion", frame, 2))

            assertEquals(listOf("shared:one", "shared:two"), db.listPendingFrameIds())
            assertArrayEquals(frame, db.getPendingFrame("shared:two")?.frame)
            assertEquals(1L, db.query("SELECT count(*) AS count FROM wire_blobs").single()["count"])
            assertTrue(db.acknowledgePendingFrame("shared:one"))
            assertEquals(1L, db.query("SELECT count(*) AS count FROM wire_blobs").single()["count"])
            assertTrue(db.acknowledgePendingFrame("shared:two"))
            assertEquals(0L, db.query("SELECT count(*) AS count FROM wire_blobs").single()["count"])
        }
    }

    @Test
    fun emptyGroupIsVisibleAndItsPreviewUpdatesAfterFirstMessage() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            val member = "44".repeat(32)
            db.putGroup(GroupRecord("empty-group", "Grupo novo", byteArrayOf(9), listOf(member), member, 9_000))

            val empty = db.listChats().single { it.id == "empty-group" }
            assertTrue(empty.isGroup)
            assertNull(empty.lastMessage)
            assertEquals(9_000L, empty.lastTimestamp)
            assertFalse(empty.lastOutgoing)
            assertNull(empty.lastStatus)

            db.insertMessage(
                MessageRecord(
                    "first-group-message",
                    "empty-group",
                    MessageDirection.OUTGOING,
                    10_000,
                    "primeira mensagem".toByteArray(),
                    MessageStatus.PENDING,
                ),
            )
            val populated = db.listChats().single { it.id == "empty-group" }
            assertArrayEquals("primeira mensagem".toByteArray(), populated.lastMessage)
            assertEquals(10_000L, populated.lastTimestamp)
            assertTrue(populated.lastOutgoing)
            assertEquals(MessageStatus.PENDING, populated.lastStatus)
        }
    }

    @Test
    fun transactionRollsBackBothReserveAndDataOnFailure() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            val freeBefore = db.query("PRAGMA freelist_count").single().values.single() as Long
            assertThrows(IllegalStateException::class.java) {
                db.transaction {
                    putMeta("must-not-survive", ByteArray(70_000) { 3 })
                    error("abort")
                }
            }
            assertNull(db.getMeta("must-not-survive"))
            val freeAfter = db.query("PRAGMA freelist_count").single().values.single() as Long
            assertEquals(freeBefore, freeAfter)
        }
    }

    @Test
    fun repeatedWritesConsumeReserveWithoutChangingObservableFileSize() {
        val vault = createVault()
        val initialSize = Files.size(vault.resolve("real.db"))
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            repeat(100) { index ->
                db.insertMessage(
                    MessageRecord(
                        id = "growth-$index",
                        peerOrGroup = "33".repeat(32),
                        direction = MessageDirection.INCOMING,
                        timestamp = index.toLong(),
                        body = ByteArray(4096) { index.toByte() },
                        status = MessageStatus.DELIVERED,
                    ),
                )
            }
            assertEquals(storage.maxPageCount.toLong(), db.query("PRAGMA page_count").single().values.single())
        }
        assertEquals(initialSize, Files.size(vault.resolve("real.db")))
        assertEquals(Files.size(vault.resolve("real.db")), Files.size(vault.resolve("decoy.db")))
    }

    @Test
    fun exportGuardRequiresTheSelectedDatabaseToBeClosed() {
        val vault = createVault()
        val session = manager.unlock(vault, REAL_PASSWORD.copyOf())
        assertNotNull(storage.active)
        assertThrows(IllegalStateException::class.java) { storage.beforeExport(vault) }
        session.close()
        storage.beforeExport(vault)
        listOf("real.db", "decoy.db").forEach { name ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                assertFalse(Files.exists(vault.resolve(name + suffix)))
            }
        }
    }

    /**
     * T4.1: `alignAllocations`/the new media check in `beforeExport` only guarantee `real.files` and
     * `decoy.files` are equal in total bytes at setup/reset time. `MessagingEngine.storeAttachment`
     * is what keeps that true continuously, by writing a same-size blind cover blob into the slot it
     * did *not* just write to (`docs/security-model.md`, "Fixed media reservation and blind cover
     * growth"). This sends a real attachment on the open (decoy) slot and checks both media
     * directories grew by exactly the same number of bytes, that `beforeExport` still accepts the
     * result, and that the cover left in `real.files` has no [FileRecord] pointing at it.
     */
    @Test
    fun sendAttachmentGrowsBothSlotsEquallyViaABlindCoverOnTheOtherSlot() {
        val vault = createVault()
        val realFiles = vault.resolve("real.files")
        val decoyFiles = vault.resolve("decoy.files")
        val beforeReal = mediaBytes(realFiles)
        val beforeDecoy = mediaBytes(decoyFiles)
        assertEquals(beforeReal, beforeDecoy)
        // Both directories already hold the setup-time fixture media (`createSetupMedia`, one PNG
        // per slot plus the decoy's extra WAV/PNG fixtures) before this test writes anything, so new
        // entries are found by diffing names, not by assuming either directory starts empty.
        val realNamesBefore = fileNames(realFiles)
        val decoyNamesBefore = fileNames(decoyFiles)

        val session = manager.unlock(vault, PANIC_PASSWORD.copyOf())
        assertEquals(VaultSlot.DECOY, session.slot)
        val db = requireNotNull(storage.active)
        val encodedIdentity = requireNotNull(db.getMeta(StorageKeys.IDENTITY))
        val contactId = db.listContacts().first { !it.displayOnly }.id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val payload = ByteArray(64 * 1024) { it.toByte() }
        var fileId: String
        try {
            PairingIdentity.restore(encodedIdentity).use { identity ->
                val engine = MessagingEngine(
                    crypto, db, identity, session, vault, storage.mediaCapacityBytes, scope,
                    Mutex(), onChanged = {}, onError = {},
                )
                try {
                    fileId = engine.sendAttachment(contactId, "photo.jpg", "image/jpeg", payload.copyOf())
                } finally { engine.close() }
            }
        } finally { scope.cancel(); encodedIdentity.fill(0) }

        val afterReal = mediaBytes(realFiles)
        val afterDecoy = mediaBytes(decoyFiles)
        // storeAttachment writes AES-GCM ciphertext (plaintext + a fixed AEAD overhead), never the
        // raw plaintext length, so the exact grown byte count is read back rather than assumed to be
        // `payload.size`; what matters is that both slots grew by the *same* amount.
        val grownBytes = afterDecoy - beforeDecoy
        assertTrue(grownBytes > 0)
        assertEquals(grownBytes, afterReal - beforeReal)
        assertEquals(afterReal, afterDecoy)

        // The attachment itself is a tracked file in this (decoy) session's own database, filed
        // under `decoy.files/<fileId>.bin`. `real.files` grew by exactly one new file too, under a
        // *different* name - a cover blob, not a copy of the same attachment - which is all this
        // session can observe about it without the real vault's key.
        assertNotNull(db.getFile(fileId))
        val newDecoyNames = fileNames(decoyFiles) - decoyNamesBefore
        val newRealNames = fileNames(realFiles) - realNamesBefore
        assertEquals(setOf("$fileId.bin"), newDecoyNames)
        assertEquals(1, newRealNames.size)
        assertTrue(newRealNames.single() != "$fileId.bin")

        session.close()
        storage.beforeExport(vault) // does not throw: T4.1's media-parity check passes post-growth.
    }

    /**
     * T4.1: `MessagingEngine.storeAttachment` checks the open slot's own media directory against
     * `AndroidVaultStorage.mediaCapacityBytes` before writing a new attachment, and fails cleanly
     * (`MessagingError(MEDIA_CAPACITY_EXHAUSTED)`) instead of writing past it. Uses a small
     * `mediaCapacityMiB` so the test does not need to actually write hundreds of megabytes to prove
     * the limit is enforced.
     */
    @Test
    fun sendAttachmentFailsCleanlyWhenTheMediaReservationIsFull() {
        val smallStorage = AndroidVaultStorage(ApplicationProvider.getApplicationContext(), crypto, capacityMiB = 16, mediaCapacityMiB = 16)
        val smallManager = VaultManager(crypto, smallStorage)
        val vault = root.resolve("small-vault")
        smallManager.create(vault, REAL_PASSWORD.copyOf(), PANIC_PASSWORD.copyOf(), KdfParams())
        try {
            val session = smallManager.unlock(vault, PANIC_PASSWORD.copyOf())
            val db = requireNotNull(smallStorage.active)
            val encodedIdentity = requireNotNull(db.getMeta(StorageKeys.IDENTITY))
            val contactId = db.listContacts().first { !it.displayOnly }.id
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                PairingIdentity.restore(encodedIdentity).use { identity ->
                    val engine = MessagingEngine(
                        crypto, db, identity, session, vault, smallStorage.mediaCapacityBytes, scope,
                        Mutex(), onChanged = {}, onError = {},
                    )
                    try {
                        // 16 MiB capacity, ~7 MiB attachments (under the 8 MiB per-file cap): two
                        // fit, a third does not.
                        val chunk = ByteArray(7 * 1024 * 1024) { it.toByte() }
                        engine.sendAttachment(contactId, "one.bin", "application/octet-stream", chunk.copyOf())
                        engine.sendAttachment(contactId, "two.bin", "application/octet-stream", chunk.copyOf())
                        val failure = assertThrows(MessagingError::class.java) {
                            engine.sendAttachment(contactId, "three.bin", "application/octet-stream", chunk.copyOf())
                        }
                        assertEquals(MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED, failure.code)
                    } finally { engine.close() }
                }
            } finally { scope.cancel(); encodedIdentity.fill(0) }
            // The failed third attempt left neither slot's media directory bigger than the two that
            // succeeded - `storeAttachment`'s capacity check runs before any byte of it is written.
            assertTrue(mediaBytes(vault.resolve("decoy.files")) <= smallStorage.mediaCapacityBytes)
            assertEquals(mediaBytes(vault.resolve("real.files")), mediaBytes(vault.resolve("decoy.files")))
            session.close()
        } finally { smallStorage.active?.close() }
    }

    /**
     * P1 follow-up (2026-09-23 review of T4.1): `VaultManager.resetPanicPassword` rebuilds
     * `decoy.files` from scratch - `storage.initialize(stage, VaultSlot.DECOY, replacementKeys)`
     * repopulates it with only the small, fixed setup-fixture set - and then calls
     * `AndroidVaultStorage.alignAllocations`, which since T4.1 also requires `real.files` and
     * `decoy.files` to be equal in size. `real.files` is only copied during a reset, never rebuilt,
     * so any vault that has ever sent or received an attachment has `real.files` bigger than the
     * freshly rebuilt `decoy.files` at that point. Before this fix, `alignAllocations` threw
     * `IllegalStateException("Vault media allocations differ")` on every such reset, and
     * `NoMessagesController.changePanicPassword`'s blanket `catch (_: Exception) {}` turned that into
     * a silent failure - the real panic-password change never actually applied. This sends a real
     * attachment first (growing both media directories well past the fixture size, exactly like
     * `sendAttachmentGrowsBothSlotsEquallyViaABlindCoverOnTheOtherSlot` above), then exercises the
     * real `resetPanicPassword` path and asserts it completes, `real.files` is unchanged, the rebuilt
     * `decoy.files` was padded back up to match it, and the new panic password actually unlocks the
     * rebuilt decoy.
     *
     * The growth step unlocks as the *decoy* (like the reference test above), not the real slot: a
     * freshly created vault's real slot has no `StorageKeys.IDENTITY` meta yet (only
     * `DecoyFactory`/`seedDecoy` pre-seed one, for the decoy - a real identity is only written later
     * by `NoMessagesController.activate`, outside this test's scope), so building a `MessagingEngine`
     * needs the decoy session. `MessagingEngine.storeAttachment`'s blind-cover write mirrors the
     * growth onto the sibling slot regardless of which side is open, so `real.files` ends up grown
     * either way - which is exactly the state this test needs before exercising the real
     * `resetPanicPassword` path below.
     */
    @Test
    fun resetPanicPasswordSucceedsAfterMediaHasGrownPastTheSetupFixtureSize() {
        val vault = createVault()
        val realFiles = vault.resolve("real.files")
        val decoyFiles = vault.resolve("decoy.files")

        run {
            val session = manager.unlock(vault, PANIC_PASSWORD.copyOf())
            assertEquals(VaultSlot.DECOY, session.slot)
            val db = requireNotNull(storage.active)
            val encodedIdentity = requireNotNull(db.getMeta(StorageKeys.IDENTITY))
            val contactId = db.listContacts().first { !it.displayOnly }.id
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                PairingIdentity.restore(encodedIdentity).use { identity ->
                    val engine = MessagingEngine(
                        crypto, db, identity, session, vault, storage.mediaCapacityBytes, scope,
                        Mutex(), onChanged = {}, onError = {},
                    )
                    try {
                        engine.sendAttachment(contactId, "photo.jpg", "image/jpeg", ByteArray(64 * 1024) { it.toByte() })
                    } finally { engine.close() }
                }
            } finally { scope.cancel(); encodedIdentity.fill(0) }
            session.close()
        }

        val grownReal = mediaBytes(realFiles)
        val grownDecoy = mediaBytes(decoyFiles)
        assertEquals(grownReal, grownDecoy)
        assertTrue(grownReal > 0)

        val realSession = manager.unlock(vault, REAL_PASSWORD.copyOf())
        assertEquals(VaultSlot.REAL, realSession.slot)
        val newPanicPassword = "K3wQ8bN2xH6vR9mP4tY7uL5c".toCharArray()
        // Did not throw: the P1 fix (VaultManager.padMediaToMatch before alignAllocations) makes
        // this reset succeed on a vault with grown media, instead of throwing IllegalStateException.
        manager.resetPanicPassword(vault, realSession, newPanicPassword.copyOf())

        assertEquals(grownReal, mediaBytes(realFiles))
        assertEquals(mediaBytes(realFiles), mediaBytes(decoyFiles))

        manager.unlock(vault, newPanicPassword.copyOf()).use { session ->
            assertEquals(VaultSlot.DECOY, session.slot)
        }
        storage.beforeExport(vault) // still passes after the reset.
    }

    /**
     * P1 follow-up (2026-09-23 review of T4.1): `MessagingEngine.storeAttachment` used to gate its
     * blind-cover write on `!existing` (skip when the primary attachment file already exists on
     * disk). A process death after the primary file's fsync+atomic-rename but before the enclosing
     * SQL transaction commits leaves exactly that state - `target` durable on disk, nothing recorded
     * in `db` yet. A retry (the sender's retransmission, or a redelivered receive) used to find
     * `existing == true` and, under the old gate, permanently skip the cover that attempt was
     * supposed to write, unbalancing `real.files`/`decoy.files` forever.
     *
     * This simulates that exact crash window directly - manually placing the primary ciphertext file
     * (what a crashed first attempt's durable fsync+rename would have left behind) without going
     * through `storeAttachment` at all - then invokes the now-idempotent private `storeAttachment`
     * once via reflection, exactly as a retry would, and asserts the cover is written despite
     * `existing == true`, and that `db.getFile` resolves (the retry's own `db.putFile` still runs).
     */
    @Test
    fun storeAttachmentWritesTheCoverOnARetryThatFindsThePrimaryFileAlreadyDurable() {
        val vault = createVault()
        val realFiles = vault.resolve("real.files")
        val decoyFiles = vault.resolve("decoy.files")
        val beforeReal = mediaBytes(realFiles)
        val beforeDecoy = mediaBytes(decoyFiles)

        val session = manager.unlock(vault, PANIC_PASSWORD.copyOf())
        assertEquals(VaultSlot.DECOY, session.slot)
        val db = requireNotNull(storage.active)
        val encodedIdentity = requireNotNull(db.getMeta(StorageKeys.IDENTITY))
        val contactId = db.listContacts().first { !it.displayOnly }.id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            PairingIdentity.restore(encodedIdentity).use { identity ->
                val engine = MessagingEngine(
                    crypto, db, identity, session, vault, storage.mediaCapacityBytes, scope,
                    Mutex(), onChanged = {}, onError = {},
                )
                try {
                    val codec = EncryptedFiles(crypto)
                    val fileIdBytes = ByteArray(16) { (it + 9).toByte() }
                    val fileKey = codec.newFileKey()
                    val plaintext = ByteArray(32 * 1024) { it.toByte() }
                    val ciphertextStream = ByteArrayOutputStream()
                    codec.encrypt(ByteArrayInputStream(plaintext), ciphertextStream, fileKey, fileIdBytes, session.epoch, plaintext.size.toLong())
                    val ciphertextBytes = ciphertextStream.toByteArray()
                    val fileIdHex = fileIdBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val envelope = Envelope.Attachment(
                        id = "retry-test", timestamp = 1_000L, name = "a.bin", mime = "application/octet-stream",
                        fileId = fileIdBytes, fileEpoch = session.epoch, fileKey = fileKey, ciphertext = ciphertextBytes,
                    )

                    // What a crashed first attempt's durable primary write leaves behind: the target
                    // file on disk, but no FileRecord (the transaction never committed) and no cover.
                    Files.createDirectories(decoyFiles)
                    Files.write(decoyFiles.resolve("$fileIdHex.bin"), ciphertextBytes)
                    assertNull(db.getFile(fileIdHex))

                    val method = MessagingEngine::class.java.getDeclaredMethod("storeAttachment", Envelope.Attachment::class.java)
                    method.isAccessible = true
                    method.invoke(engine, envelope)

                    assertNotNull(db.getFile(fileIdHex))
                } finally { engine.close() }
            }
        } finally { scope.cancel(); encodedIdentity.fill(0) }

        val afterReal = mediaBytes(realFiles)
        val afterDecoy = mediaBytes(decoyFiles)
        // Exactly one new file per slot: the manually-placed primary in decoy.files, and the cover
        // this retry wrote into real.files despite finding `existing == true`.
        assertTrue(afterDecoy > beforeDecoy)
        assertEquals(afterDecoy - beforeDecoy, afterReal - beforeReal)
        assertEquals(afterReal, afterDecoy)

        session.close()
        storage.beforeExport(vault)
    }

    private fun createVault(): Path {
        val vault = root.resolve("vault")
        manager.create(vault, REAL_PASSWORD.copyOf(), PANIC_PASSWORD.copyOf(), KdfParams())
        return vault
    }

    /**
     * Opens a vault database directly, bypassing [AndroidVaultStorage], to stage a schema state the
     * app itself can no longer produce. `max_page_count` is re-applied on this connection (it is a
     * per-connection pragma) so the staging work is held to the same fixed allocation the app
     * enforces and cannot silently grow the file out from under the assertions.
     */
    private fun rawDatabase(path: Path, dbKey: ByteArray, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(path.toFile(), dbKey, null, null).use { raw ->
            raw.rawQuery("PRAGMA journal_mode=DELETE").use { cursor -> cursor.moveToFirst() }
            raw.rawQuery("PRAGMA max_page_count=${storage.maxPageCount}").use { cursor -> cursor.moveToFirst() }
            block(raw)
        }
    }

    /**
     * Schema v3 (2026-09-17, T4.16) adds `contacts.doorbell_onion` and `contacts.doorbell_token`,
     * reserved for T4.17 and populated from the format-2 pairing offer. Same ALTER TABLE shape as
     * v2, so the same two things are worth proving: the step ran, and the columns round trip.
     *
     * The empty defaults are asserted explicitly, because "" / x'' is the value every contact
     * paired before format 2 legitimately carries - it has to be representable, not rejected.
     *
     * NOT RUN as part of T4.16: this session had no emulator (instrumented validation is a
     * follow-up phase). See docs/development/device-verification.md, row T4.16.
     */
    @Test
    fun doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            assertEquals(4L, db.query("PRAGMA user_version").single().values.single())

            val peer = "77".repeat(32)
            val doorbellOnion = "c".repeat(56) + ".onion"
            val token = ByteArray(32) { (it + 3).toByte() }
            db.putContact(
                ContactRecord(
                    id = peer,
                    alias = "com campainha",
                    onion = "d".repeat(56) + ".onion",
                    identityPublic = ByteArray(32) { it.toByte() },
                    signalPeer = ByteArray(33) { (it + 1).toByte() },
                    pairedAt = 9_000,
                    doorbellOnion = doorbellOnion,
                    doorbellToken = token,
                ),
            )
            val stored = requireNotNull(db.getContact(peer))
            assertEquals(doorbellOnion, stored.doorbellOnion)
            assertArrayEquals(token, stored.doorbellToken)
            assertEquals(doorbellOnion, db.listContacts().single { it.id == peer }.doorbellOnion)

            // A contact with no doorbell material at all - what every pre-format-2 row becomes.
            val legacy = "88".repeat(32)
            db.putContact(
                ContactRecord(
                    id = legacy,
                    alias = "sem campainha",
                    onion = "e".repeat(56) + ".onion",
                    identityPublic = ByteArray(32) { (it + 5).toByte() },
                    signalPeer = ByteArray(33) { (it + 6).toByte() },
                    pairedAt = 9_100,
                ),
            )
            val plain = requireNotNull(db.getContact(legacy))
            assertEquals("", plain.doorbellOnion)
            assertEquals(0, plain.doorbellToken.size)
        }
    }

    /**
     * Schema v4 (2026-09-18, T4.17) adds `contacts.doorbell_token_issued`: the token THIS device
     * minted for that contact, which is what an incoming ring from it has to present. v3 stored only
     * the peer's half, so the vault could ring a doorbell but had nothing to check one against.
     *
     * Three things are proved here, and the third is the one that matters for existing users:
     *
     * 1. the step ran (`user_version = 4`) and the column round trips through put/get/list;
     * 2. a contact carrying the peer's half and **no** issued half is accepted - that is exactly
     *    what every row migrated from v3 looks like, since the v4 ALTER TABLE backfills x'' and
     *    cannot invent a secret that was already discarded;
     * 3. the one impossible combination - an issued token with no peer half - is refused, together
     *    with a wrong-sized token.
     *
     * NOT RUN as part of T4.17 phase 2: this session had no emulator (instrumented validation is a
     * follow-up phase). See docs/development/device-verification.md.
     */
    @Test
    fun issuedDoorbellTokenRoundTripsAfterTheSchemaVersionFourMigration() {
        val vault = createVault()
        manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
            val db = requireNotNull(storage.active)
            assertEquals(4L, db.query("PRAGMA user_version").single().values.single())

            val peer = "99".repeat(32)
            val doorbellOnion = "f".repeat(56) + ".onion"
            val peerToken = ByteArray(32) { (it + 3).toByte() }
            val issuedToken = ByteArray(32) { (it + 70).toByte() }
            val base = ContactRecord(
                id = peer,
                alias = "campainha dos dois lados",
                onion = "g".repeat(56) + ".onion",
                identityPublic = ByteArray(32) { it.toByte() },
                signalPeer = ByteArray(33) { (it + 1).toByte() },
                pairedAt = 9_200,
                doorbellOnion = doorbellOnion,
                doorbellToken = peerToken,
                doorbellTokenIssued = issuedToken,
            )
            db.putContact(base)
            val stored = requireNotNull(db.getContact(peer))
            assertArrayEquals(peerToken, stored.doorbellToken)
            assertArrayEquals(issuedToken, stored.doorbellTokenIssued)
            // The two halves are independent secrets, never the same value.
            assertFalse(stored.doorbellToken.contentEquals(stored.doorbellTokenIssued))
            assertArrayEquals(issuedToken, db.listContacts().single { it.id == peer }.doorbellTokenIssued)

            // What a row migrated from v3 looks like: peer half present, issued half empty.
            val migrated = base.copy(id = "aa".repeat(32), doorbellTokenIssued = ByteArray(0))
            db.putContact(migrated)
            assertEquals(0, requireNotNull(db.getContact(migrated.id)).doorbellTokenIssued.size)

            // An issued token without the peer's half could not have come from any pairing.
            assertThrows(IllegalArgumentException::class.java) {
                db.putContact(
                    base.copy(id = "bb".repeat(32), doorbellOnion = "", doorbellToken = ByteArray(0)),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                db.putContact(base.copy(id = "cc".repeat(32), doorbellTokenIssued = ByteArray(31)))
            }
        }
    }

    /**
     * Rewinds `real.db` to what schema v1 looked like: `messages` without the `forwarded` column,
     * `contacts` without `doorbell_onion`/`doorbell_token`, and `user_version = 1`. The DDL is copied
     * verbatim from `AndroidVaultStorage.createSchemaV1`, the frozen record of v1 - a `DROP COLUMN`
     * is not available in the SQLite this project targets, so each table is rebuilt and the v1 index
     * recreated after `DROP TABLE` takes the old one with it.
     *
     * Every column a later schema version adds has to be rewound here, or the replayed migration
     * hits `duplicate column name` on the step that adds it and the test fails for a fixture reason
     * that looks exactly like a migration defect. That is precisely what happened on 2026-09-17:
     * the v3 step landed with its two `contacts` columns, this helper still only undid v2, and the
     * case failed with `SQLiteException: duplicate column name: doorbell_onion`.
     */
    private fun downgradeToSchemaVersionOne(path: Path, dbKey: ByteArray) {
        rawDatabase(path, dbKey) { raw ->
            // The database is already at its fixed capacity with an empty freelist, so rebuilding
            // the tables needs pages released from the reserve first - the same trade the app makes
            // for every write, done by hand here because ChatDatabase is not in this path.
            raw.execSQL("DELETE FROM storage_reserve WHERE id IN (SELECT id FROM storage_reserve ORDER BY id LIMIT 8)")
            listOf(
                "CREATE TABLE messages_v1(id TEXT PRIMARY KEY NOT NULL,peer_or_group TEXT NOT NULL," +
                    "direction INTEGER NOT NULL CHECK(direction BETWEEN 0 AND 1),ts INTEGER NOT NULL,body BLOB NOT NULL," +
                    "status INTEGER NOT NULL CHECK(status BETWEEN 0 AND 4)) WITHOUT ROWID",
                "INSERT INTO messages_v1 SELECT id,peer_or_group,direction,ts,body,status FROM messages",
                "DROP TABLE messages",
                "ALTER TABLE messages_v1 RENAME TO messages",
                "CREATE INDEX messages_chat_time ON messages(peer_or_group,ts DESC,id DESC)",
                "CREATE TABLE contacts_v1(id_pub TEXT PRIMARY KEY NOT NULL,alias TEXT NOT NULL,onion TEXT NOT NULL," +
                    "identity_public BLOB NOT NULL,signal_peer BLOB NOT NULL,paired_at INTEGER NOT NULL," +
                    "display_only INTEGER NOT NULL CHECK(display_only IN (0,1))) WITHOUT ROWID",
                "INSERT INTO contacts_v1 SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only FROM contacts",
                "DROP TABLE contacts",
                "ALTER TABLE contacts_v1 RENAME TO contacts",
            ).forEach(raw::execSQL)
            raw.rawQuery("PRAGMA user_version=1").use { cursor -> cursor.moveToFirst() }
        }
    }

    private fun mediaBytes(path: Path): Long = Files.list(path).use { files -> files.mapToLong(Files::size).sum() }
    private fun fileNames(path: Path): Set<String> = Files.list(path).use { files -> files.map { it.fileName.toString() }.toList().toSet() }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        indices.any { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }

    companion object {
        private val REAL_PASSWORD = "E7vR9mQ2kL8sX4pN6cT3wY5z".toCharArray()
        private val PANIC_PASSWORD = "B4nD8hJ2qW6uK9xF3rS7mP5c".toCharArray()
    }
}

private class TestCrypto : Crypto {
    private val random = SecureRandom()

    override fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
        var value = password + salt
        repeat(iterations) { value = MessageDigest.getInstance("SHA-256").digest(value) }
        return value
    }

    override fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = random(24)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce.copyOf(12)))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    override fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? = try {
        if (sealed.size < 40) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed.copyOfRange(24, sealed.size))
        }
    } catch (_: Exception) {
        null
    }

    override fun hash(message: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(message)

    override fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    override fun signingKeyPair(): SigningKeys {
        val seed = random(32)
        val public = hash(seed)
        return SigningKeys(public, seed + public)
    }

    override fun sign(secretKey: ByteArray, message: ByteArray): ByteArray {
        val public = secretKey.copyOfRange(32, 64)
        val half = mac(public, message)
        return half + half
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val half = mac(publicKey, message)
        return signature.contentEquals(half + half)
    }
}
