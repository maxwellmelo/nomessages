# Android SQLCipher storage report

The Android adapter is implemented under
`app/src/main/kotlin/dev/mx3/nomessages/storage`. It uses SQLCipher 4.19.0 through
the byte-key `openOrCreateDatabase(File, ByteArray, ...)` API and loads only the
`sqlcipher` native library. Keys are never formatted into SQL or logged.

The schema is version 1 and migration is deterministic through
`PRAGMA user_version`. It stores metadata, opaque protocol state, contacts,
messages, groups and members, pair evidence, file envelopes, and an outbox.
Outbox ciphertext is normalized into `wire_blobs` by SHA-256 so one MLS packet
queued for many destinations occupies one body allocation. Removing the last
outbox reference removes the body in the same transaction.

Production databases have a fixed 256 MiB logical allocation made from 4 KiB
encrypted SQLCipher pages. Tests can select a 16 MiB allocation. Both slots are
filled to the same page count at setup, capped with `max_page_count`, and use an
encrypted reserve table. A write first accounts for existing freelist pages,
then releases only the reserve chunks needed for the estimated record size in
the same transaction. A failed or over-capacity operation rolls back both the
reserve release and application data. Deletions and fixed-size status updates
do not consume reserve. No raw bytes are appended to a SQLCipher file, no
automatic vacuum can shrink it, and no record is evicted to make room.

Connections use in-memory temporary storage, DELETE journals, full synchronous
writes, secure delete, foreign keys, SQLCipher memory security, and no WAL.
Only one selected `ChatDatabase` is exposed by an adapter. Its idempotent close
closes SQLCipher before clearing `active`; export rejects an open database or
unresolved journal/WAL sidecar.

Setup creates the schema for both slots. The decoy contains four synthetic
cryptographically paired contacts, four mundane conversations across roughly one month, and two small
encrypted PNG attachments linked through normal encoded attachment history, so
the in-app viewer can open them. Setup runs the complete bilateral pairing
ceremony in memory, persists only the decoy-side identity, Signal sessions,
contact records, and signed pair evidence, then closes and wipes each synthetic
peer identity. Synthetic onion addresses have valid v3 encoding but no running
service, so messages queue as authentic Signal ciphertext for offline peers.
The real media directory contains matching unreferenced encrypted cover files,
giving both media roots the same filenames, entry count, and ciphertext byte
total at setup.

The synthetic peers are permanently offline because their private identities
are deliberately discarded. This is still a behavioral clue under prolonged
interactive examination, so this implementation does not claim that decoy
plausibility has been proven. The selected decoy remains a normal vault: a user
can QR-pair real contacts into it and exchange messages with those contacts
using its separate identity and protocol state. Synthetic generation is used
only while initializing the decoy slot and is never used for the real vault.

Instrumentation coverage is in
`app/src/androidTest/kotlin/dev/mx3/nomessages/storage/AndroidVaultStorageTest.kt`.
It exercises wrong-key rejection, fixed and balanced allocation, absence of
known plaintext in the encrypted database, selected-vault isolation, synthetic
decoy Signal-session persistence, typed/opaque round trips with caller-owned byte arrays, outbox
deduplication and cleanup, transactional rollback including reserve state,
growth without file-size drift, active-handle cleanup, and the closed-export
guard.

Runtime media files live outside SQLCipher. Setup balances two small encrypted
objects, but ongoing attachment growth needs root-owned maintenance to exchange
fixed-capacity encrypted cover objects while both vault keys are available.
Database reserve replenishment likewise requires maintenance with both keys;
normal runtime access never opens the unselected database.

Maximum-group capacity correction: `GroupCapacityProbe.java` created a real
100-member MLS group and verified joining/decryption by member 100. With the
4,950 fixed-format pairing proofs, each encoded invitation was 1,369,584 bytes.
The 99 invitations, stored graph and MLS state alone require 137,014,557 bytes
(130.67 MiB), before Signal and SQL overhead. The earlier 128 MiB default could
not hold this transaction even in an otherwise empty vault. The default is now
256 MiB per vault, with equal real/decoy allocation (512 MiB combined). The
16 MiB instrumentation fixture override and 1 GiB export limits remain compatible.
Evidence: `build-logs/group-capacity-probe.log`. This probe measures serialized
storage needs; actual SQLCipher/device capacity remains an instrumentation gate.
