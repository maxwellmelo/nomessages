# Security model and current limits

NoMessages is an Android client with a visible launcher, password-encrypted real/decoy vaults, QR-authenticated contacts, Tor onion transport and MLS group membership restricted to a complete pairing graph. This repository is an implementation under verification, not a claim of completed security certification. Device evidence is tracked in [release-checklist.md](release-checklist.md); unresolved gates remain `NOT_RUN`.

## Intended boundaries

The vault password derives wrapping material with Argon2id. SQLCipher protects database contents at rest; attachments use authenticated chunk encryption. Real and panic vaults use separate keys, identities and onion seeds. Export copies the encrypted vault and is intended to remain usable with the password on another device. Android Keystore is not the sole secret needed to open an exported vault.

**Password normalization and acceptance (2026-09-17, task T4.10).** The bytes handed to Argon2id are the password normalized with **NFKC**, not NFC. That choice exists for the export promise above: different keyboards and IMEs emit visually identical characters as different Unicode compatibility forms (full-width versus half-width Latin and digits, ligatures, non-breaking versus ordinary space, several hyphen code points), and NFC preserves those distinctions, so the "same" password typed on the second device could derive a different key and silently fail to open an imported vault. NFKC folds them to one canonical form, which is what makes it safe to widen the accepted character set at all. NFC and NFKC are identical on plain ASCII, so no existing vault changes its derived key. Acceptance is now 12 to 128 normalized characters drawn from printable ASCII or any Unicode letter, with no control characters and no leading/trailing whitespace, **and** a score of at least 3 (roughly 60 estimated bits) from `estimateStrength` in `core/src/main/kotlin/dev/mx3/nomessages/core/vault/PasswordStrength.kt` — an embedded, offline, dependency-free estimator that replaced the previous `zxcvbn` score-4 gate and the previous 16-character alphanumeric-only rule. It is an engineering estimate driving a strength meter and an acceptance floor, not a proof of guessing cost. The panic password must additionally not be a trivial variation of the real one: rejected when either is a substring of the other or their Levenshtein distance is at most 2, so an adversary who obtains the real password under coercion cannot derive the panic one from it.

The unlocked app is an endpoint that can read its current messages and files. This design does not defeat a compromised/rooted OS, accessibility malware, a malicious keyboard, invasive live-memory acquisition or coercion that obtains the real password. A visible installed package is not hidden from forensics. A decoy vault can reduce some disclosure risks but is not proof of indistinguishability or resistance to coercion.

`FLAG_SECURE` requests screenshot and insecure-display protection. It is not a complete defense against malicious overlays or a compromised device, and actual activity/Recents behavior must be tested. [Android's sensitive-activity guidance](https://developer.android.com/security/fraud-prevention/activities)

**Debug-only capture escape hatch (2026-09-15).** `MainActivity` can skip applying `FLAG_SECURE` only when the running build is a debug build (`BuildConfig.DEBUG == true`) **and** the device system property `debug.nomessages.allow_capture` reads back exactly `"1"` (via `android.os.SystemProperties.get`, by reflection, with any failure treated as absent). The decision is a pure function, `shouldApplySecureFlag` in `app/src/main/kotlin/dev/mx3/nomessages/ui/UiLogic.kt`, covered by a JVM unit test for all four `(isDebug, propertyValue)` combinations. The default — property absent, or any release build regardless of the property — is unchanged from before this existed: `FLAG_SECURE` is always applied. This exists purely so a developer can locally capture a screenshot/recording (e.g. to relay a pairing QR between two emulators) with an explicit `adb shell setprop debug.nomessages.allow_capture 1`, which requires no root (`debug.*` properties are readable by any app). Any release-gate check of this security model's screenshot protection (Gate 4 or equivalent) must run with the property absent or `"0"`, and against a release build, where the flag cannot be disabled at all. On some `userdebug`/`eng` Android 15 emulator images the `setprop` alone is not sufficient: Android's hidden-API policy can block the `SystemProperties` reflection outright (falling back to the secure default, `FLAG_SECURE` still applied) unless `adb shell settings put global hidden_api_policy 1` (compat mode) is also set on that device — see `docs/changes/MainActivity.kt.md` for the confirmed repro. See also `docs/changes/UiLogic.kt.md`.

**Debug-only QR injection hook (2026-09-15).** A debug build additionally carries `dev.mx3.nomessages.debug.DebugQrReceiver`, a `BroadcastReceiver` that can hand a pairing QR's bytes straight to the scanner (`INJECT_QR`, extra `payload_b64`) and write the bytes of the QR currently on screen to `cacheDir/qr-shown.bin` (`DUMP_QR`). It exists so the pairing ceremony can be exercised on two emulators whose virtual camera provably cannot decode the real payload (see the "Residual finding" in `docs/development/device-verification.md`, T3.3 run3); everything after the decode - parsing, Ed25519 signature checks, SAS derivation, confirmation - runs the production path unchanged. Access is controlled by three independent layers: (1) the class lives only in `app/src/debug/kotlin` and the `<receiver>` only in `app/src/debug/AndroidManifest.xml`, so neither is compiled or merged into a release APK; (2) the receiver declares `android:permission="android.permission.WRITE_SECURE_SETTINGS"`, so the **operating system** refuses to deliver the broadcast unless the *sender* holds that `signature|privileged` permission - `com.android.shell` (uid 2000, which runs `adb shell am broadcast`) and root hold it, no installable third-party app can; (3) the receiver stays inert unless `debug.nomessages.allow_qr_inject` reads back exactly `"1"`, and writing `debug.*` properties is restricted by SELinux to the `shell`/`su` domains - the same manual, local opt-in pattern as `debug.nomessages.allow_capture` above, read through the same `readSystemProperty` reflection helper whose failure mode is fail-closed. A rejected broadcast is dropped silently: no log, no toast, no exception, no result code, so a hostile app cannot even distinguish "present and refused" from "absent" (verified: `am broadcast` prints the identical `result=0` with the property at `"0"` and at `"1"`; only with `"1"` does the dump file appear). **The originally intended `Binder.getCallingUid()` guard was implemented, measured on device, and does not work**: inside `onReceive` on API 35 it returned this app's own uid (10212, not 2000) because there is no active binder transaction, and `BroadcastReceiver.getSentFromUid()` returned `-1` because the sender must opt in via `BroadcastOptions.setShareIdentityEnabled(true)`, which `am broadcast` does not - so a UID-only guard would reject every broadcast while appearing to protect. The UID check is retained only for what it can actually do: reject a *reported* sender that is not shell/root. The two hook fields (`QrScannerHooks.scanSink`, `QrScannerHooks.shownPayload`) live in `src/main` because `PairingScreen.kt` must compile in release, but nothing in `src/main` ever reads or invokes them - the composables only publish and clear a callback, and `DebugQrReceiver` is the project's only caller. Release inaccessibility is proven mechanically, not asserted: `:app:processReleaseManifest` followed by `grep -r "DebugQrReceiver\|INJECT_QR"` over `app/build/intermediates/merged_manifest/release/` returns empty (and `WRITE_SECURE_SETTINGS` appears zero times there), while the same grep over the debug merged manifest finds the receiver; a source grep confirms no non-comment line under `app/src/main/` invokes either hook. Full output in `docs/development/build-logs/two-emulator-20260915/run3/60-release-manifest-proof.txt`. Any release-gate check must be run against a release build, where neither the component nor the class exists at all. See `docs/changes/DebugQrReceiver.kt.md`, `docs/changes/QrScannerHooks.kt.md` and `docs/changes/emulator-pair.sh.md`.

Database encryption must be tested with a valid-key positive control and wrong-key queries against the shipped SQLCipher configuration. The absence of a plaintext marker in `strings` or an archive scan is only one observation; it does not show that every field was encrypted or that a wrong key was rejected. [SQLCipher API and key verification guidance](https://www.zetetic.net/sqlcipher/sqlcipher-api/)

Backup flags and extraction rules require device verification. Android backup and device-transfer behavior depends on OS/target settings and can differ across manufacturers; an unavailable `adb backup` command does not demonstrate that app data is excluded. [Android backup documentation](https://developer.android.com/identity/data/autobackup)

## Pairing and message cryptography

The maintained official libsignal 0.102.2 API requires a Kyber prekey. This implementation therefore uses its supported PQXDH and Signal ratchets instead of silently pretending to implement the specification's classical-only X3DH. The Ed25519-signed QR binds the **hash of** the complete bundle, together with the Ed25519 identity, onion, timestamp and nonce (see the next section: before 2026-09-17 it bound the bundle itself). Both peers derive the same SAS, explicitly confirm it, and exchange signed confirmation before contact finalization. Six digits are a human authentication check, not an “unbreakable” cryptographic claim. [PQXDH specification](https://signal.org/docs/specifications/pqxdh/), [pinned official Java sources](https://build-artifacts.signal.org/libraries/maven/org/signal/libsignal-client/0.102.2/libsignal-client-0.102.2-sources.jar)

Only previously verified Signal identities are trusted; an incoming message cannot silently install or replace a contact key. State updates must be committed in the same SQL transaction as message/outbox changes, with in-memory rollback when commit fails. Replaying or changing authenticated ciphertext must fail. The protobuf and frame decoders impose explicit byte/count limits. Padding reduces size precision; it does not conceal timing, availability or all message-size information.

The complete clique is a membership rule, not the group cipher. Ed25519 evidence must include signatures from both endpoints of every edge. OpenMLS handles group state and epochs; pairwise sessions distribute membership traffic. Two colluding members can sign a false claim about their physical ceremony, so evidence does not independently prove proximity. MLS requires authenticated membership/distribution choices in addition to its cryptographic protocol. [RFC 9420](https://www.rfc-editor.org/rfc/rfc9420.html)

## Pairing QR format 2: hash in the QR, bundle over Tor (2026-09-17, T4.16)

Mandatory Kyber-1024 put a 1569-byte public key inside the pairing QR. The format-1 payload reached
~2950 bytes — QR version 40, 177 modules per side — and the user's Galaxy Note10+ camera could not
read it even when the QR filled an entire monitor. A pairing ceremony that cannot be completed is
not a security property; it is an availability failure that pushes people toward a worse channel.
Format 2 therefore carries `bundleHash = SHA-256(SignalBundle.encode())` and fetches the bundle
itself from the onion the QR already names.

### The identity ↔ onion ↔ bundle binding is intact

`ed`, `onion`, `created`, `nonce`, `bundleHash`, `doorbellKey`, `doorbellToken` and `reply` are all
inside **one** Ed25519 signature that the scanning device verifies before anything else happens. A
hash is a binding commitment: committing to `SHA-256(bundle)` binds exactly the same bundle that
embedding the bytes would have bound. Nothing was moved outside the signature's reach — only its
representation changed, from 1832 bytes to 32.

`SignalBundle` is consequently self-contained at wire version 3: it now carries `identity` and
`pre` inside the blob. Format 1 could omit them because the QR repeated both as signed outer fields,
which is what blocked substituting a bundle under someone else's identity. Format 2 blocks the same
substitution with a single comparison — the hash covers the whole bundle, identity and one-time
prekey included — so the duplication would buy nothing and cost 66 bytes.

The six-digit SAS is unchanged: it is `SHA-256(transcript)[0:3]` over the canonical encoding of
**both complete offers**. Because each offer commits to its own bundle hash, the number the two
people read to each other transitively authenticates both PQXDH bundles, even though neither device
had seen a bundle when the QR was scanned. The SAS derivation was deliberately **not** changed to
include the bundle directly; it did not need to be.

### Fetching over Tor introduces no MITM

The transport delivers bytes and grants no trust. `PairingEngine.acceptPeerBundle` accepts a bundle
only when `SHA-256(received) == bundleHash` from the offer it already verified. A transport that
swaps, replays, truncates or fabricates the bundle produces a mismatch, and the exchange is
**cancelled** rather than downgraded — there is no half-verified state and no fallback path. Tor's
own stream encryption is incidental to this argument: the bundle is public key material, and its
integrity rests on the signed hash, not on the channel. The security of the ceremony is identical
whether the bytes arrive by QR, by Tor, or written on paper.

The one thing that did change is liveness: an attacker who can block the fetch can stop a pairing
from completing. That is a denial of service against a ceremony the two people are performing in
person, and it is visible to them — the screen says the other device could not be reached and
offers a retry. It cannot be turned into a downgrade, because there is nothing to downgrade to.

### Directionality and readiness

Whoever just read a QR fetches from whoever showed it. The responder's onion is already known from
the QR, so the initial offer's fetch runs from the QR reader to the QR shower; for the response QR,
the original shower fetches from the responder. No discovery, no third party, no directory.

Both sides need Tor reachable (`READY`). If it is not yet, the UI waits with explicit feedback
(“waiting for Tor” is reported separately from “fetching”, because “your own onion is not published
yet” and “the other phone is not answering” have different remedies) up to the exchange deadline,
rather than failing immediately.

### Rate limiting and what a stranger can learn

`BundleRequest` is answered **only** for a 16-byte nonce this device itself minted and published in
a QR, **only** while that offer is inside its own 300-second window, and **exactly once**. Bundles
belonging to offers that are not currently live are never revealed. An unknown, stale or
already-consumed nonce is answered with **silence, not an error** — an error would confirm to
someone merely probing nonces that this onion has a pairing screen open.

One superseded offer is deliberately kept answerable for the rest of its own window, so that a peer
who scanned the QR in its last seconds can still complete a fetch after the screen's automatic
refresh. It is still one-shot and still an offer this device minted; the exposure is one extra
one-time prekey bundle, bounded to one.

A `PacketKind.BUNDLE` packet is the only packet an unpaired stranger may legitimately send. It is
handled before the receipt cache and **before the vault transaction**: it touches no database row,
no ratchet, no identity export. Its payload is capped at 8 KiB, and admission goes through
`MessagingBundleLimiter`, a token bucket separate from the message-trial bucket (1/s sustained,
burst 4) so a pairing flood cannot starve ordinary message admission and vice versa. That bucket
bounds decoder work only; the real defence is the structural nonce rule above.

Two envelopes, `BundleRequest` and `BundleResponse`, are the only ones exchanged before a Signal
session exists and therefore the only ones not carried inside Signal ciphertext. Nothing secret may
ever be placed in them. Neither is ever queued into a Signal lane; a paired peer that wraps one in
its ratchet is rejected.

What a passive observer of the onion learns that it did not learn before: that a connection was made
to a pairing-capable service. It does not learn whether the nonce was valid, because the reply to an
invalid one is no reply.

### Doorbell fields and runtime (T4.17)

`doorbellKey` and `doorbellToken` were added to format 2 ahead of the feature, purely to avoid a
second QR-format migration when the doorbell (`development/doorbell-design.md`) was built. **Since
T4.17 they carry behaviour**: a second onion service listens, a notification exists and a per-vault
setting is shown. The QR format itself did not change — fields 7 and 8 are the same 32 bytes in the
same signed positions — so the byte budget measured in T4.16 is untouched.

`doorbellKey` is the Ed25519 identity key of a second v3 onion, derived from `meta.doorbell_seed` —
a **separate** 32-byte seed from `onion_seed`. Sharing one seed would let anyone holding either
address confirm the two belong to the same device; two seeds make them unlinkable. `doorbellToken`
is minted fresh per offer, so only that exchange's peer ever learns it and two contacts can never
present each other's token. Both are inside the canonical signed bytes and therefore inside the SAS
transcript, with no separate hashing or signing path.

Each side stores **three** values per contact, and they are not interchangeable: the peer's
`doorbell_onion` (where to knock) and `doorbell_token` (what to present), both from the peer's
signed offer and therefore inside the SAS transcript, plus this device's own
`doorbell_token_issued` — the token minted inside *this* device's offer, which is what a local
listener has to recognise. The first two arrived in vault schema v3, the third in **v4**. They are
recorded at pairing time because there is no second authenticated channel on which to obtain them
later; that is also why the gap is unrepairable in the other direction. **Known limitation:** a
contact paired before T4.17 has an empty `doorbell_token_issued` and simply cannot ring this
device's doorbell — the value existed only inside that offer and is gone. Only a fresh pairing
fixes it, and the code treats the empty column as "no doorbell agreed", not as an error.

The decoy vault populates all three: an all-blank doorbell column on every decoy contact while real
contacts carry one would make the two distinguishable by inspection alone.

#### What stays exposed in minimal mode

"Minimal mode" is the state a lock leaves behind when the vault being locked has the doorbell
enabled. The **messaging** service is destroyed exactly as in an ordinary lock — onion service,
`HsId` keypair, connection map, per-peer isolated clients, every session — and only then are the
vault keys zeroized. The `:tor` child then survives as a foreground service, holding:

- the **shared Arti host** (Tokio runtime, bootstrapped client, guards, directory circuits,
  descriptor cache), which is what makes the doorbell able to serve at all; and
- the doorbell's **own onion identity key**, derived from `meta.doorbell_seed`; and
- the **set of tokens this device issued** to its contacts — every non-empty
  `ContactRecord.doorbellTokenIssued`, loaded once as opaque blobs through the native token opcode.
  The verifier never learns which contact a token belongs to.

Explicitly **not** present in that state: no MK, no DBK, no FBK; no open SQLCipher database and no
database key; no Signal ratchet state and no MLS group state; no messaging identity and no
`meta.onion_seed`; no message plaintext and no attachment key. Kotlin never sees a token either —
tokens cross into native code once and never come back, and a knock surfaces to the app as an opaque
"somebody rang" counter with no identity and no timestamp. A native panic on any opcode tears down
**both** services rather than leaving unknown state serving.

#### Threat model of the knock protocol

A knock is one fixed 57-byte frame per connection:
`version(1) ‖ timestamp u64 BE(8) ‖ nonce(16) ‖ HMAC-SHA256(token, version‖timestamp‖nonce)(32)`.
The version byte is inside the MAC, so bumping it invalidates every older frame.

- **Forgery / brute force.** Acceptance requires an HMAC-SHA256 tag under a 32-byte token that only
  the paired peer ever learned, compared in constant time; every loaded token is tried with no early
  exit, so the time spent reveals neither which token matched nor whether any did. There is no
  oracle to grind against: a wrong tag is answered with silence, identical to every other failure.
- **Replay.** The timestamp must be within ±300 s of the receiver's clock, and the nonce is
  remembered for 600 s. The nonce TTL is deliberately longer than the window, so a captured frame
  expires by timestamp before its nonce is forgotten — there is no gap in which it becomes
  replayable again.
- **Signalling / flooding.** At most **one accepted knock per token per 600 s**, keyed by
  `SHA-256(token)` rather than by list position, so reloading the token set after a new pairing does
  not reset the ledger — and neither does stopping and restarting the service, because the verifier
  outlives any individual generation. Concurrency is capped (32 in-flight knocks) and the pending
  event queue is bounded (64), because an unbounded queue would itself be a memory oracle. The user
  only ever sees one notification regardless of how many knocks a burst carried.
- **Contact enumeration by an outsider.** A network observer, or anyone who knows the address, can
  connect to the doorbell onion and see that a connection happened. It cannot tell a valid knock
  from an invalid one: a wrong length, an unknown token, a stale timestamp, a replay and a throttled
  contact all close the stream **without writing a single byte**, and the only observable difference
  is the one confirmation byte handed back to the author of a knock that passed every check — i.e.
  to somebody who already held a valid token. The size of the token set never leaves the native
  process and is not probeable from outside.
- **Linking the two onions.** The doorbell address derives from `meta.doorbell_seed`, separate from
  `onion_seed`, and an outbound knock is sent over a **fresh isolated client** per knock, so a
  doorbell circuit is not linked to that contact's messaging circuits.

#### Known limitations of the runtime

- **The surviving process is itself a signal.** With the doorbell on, the `:tor` child does not die
  at lock and carries a persistent foreground notification; without it, the child terminates. Anyone
  holding the locked device can see that difference. It does **not** say which vault is open — real
  and decoy raise the doorbell through the same `lock()`/`activate()` path, with no slot branch
  anywhere on it — nor which contacts exist, nor that any message is actually waiting. The panic
  password is the deliberate exception in the other direction: it tears the child down
  unconditionally, with no minimal-mode carve-out, and the decoy's own doorbell comes up afterwards
  through the ordinary path.
- **Arti state on disk.** Minimal mode keeps the Arti state and cache directories live for longer
  than an ordinary lock does, so the guard/introduction-point metadata described under
  `TorStateSnapshot` above remains in the clear for the duration of the locked session rather than
  being cleared at lock. This is the same forensic limitation already recorded for an unlocked
  session, extended in time; it is not new material, and it contains no app plaintext and no onion
  private key.
- **Battery and reachability.** A doorbell that the system reclaims rings nobody. The foreground
  promotion is what prevents that, and a platform refusal to enter foreground is reported rather
  than thrown — the doorbell is kept serving even unprotected, which means "enabled" is best effort,
  not a guarantee of delivery. The notice is advisory in every case: it never claims how many
  messages wait, or from whom.
- **Not measured on a device.** Everything in this subsection is established by source review, 18
  new Rust unit tests over the verifier and frame layout, and the JVM tests over
  `DoorbellKnockPolicy`. The live behaviour — foreground survival across a real lock, an accepted
  knock between two real devices, the absence of messaging-onion reachability in minimal mode — is
  gate **10c** in `release-checklist.md` and remains `NOT_RUN`.

### Not validated on a device

Everything in this section is established by source review and host JVM tests (`ProtocolTest`,
`EnvelopeCodecTest`, `MessagingPolicyTest`, `PairingLifecycleTest`). **Live two-emulator pairing
with a real Tor bundle fetch has not been run**, and neither has the new instrumented schema-v3
test. Tor bootstrap time, onion reachability inside the 300-second window and the behaviour of the
fetch under a flaky circuit remain device gates. See `development/device-verification.md`, row
T4.16.

## Memory, Tor and storage limits

The app-owned Signal store keeps serialized byte arrays and wipes retained secrets on close. Official libsignal Java `SimpleOwner` wrappers, however, release native allocations through finalizers and do not expose deterministic close for every key/session type. Immediate erasure of every native ratchet allocation therefore cannot be claimed. Managed-language temporary copies, compiler optimizations and OS memory behavior also limit universal zeroization claims. Unsafe manual freeing of upstream handles is not used. See `NativeHandleGuard` in the [pinned official sources](https://build-artifacts.signal.org/libraries/maven/org/signal/libsignal-client/0.102.2/libsignal-client-0.102.2-sources.jar) and [protocol implementation report](development/protocol-report.md).

Arti 0.46 is configured with an ephemeral primary secret keystore. The onion identity is reconstructed from a dedicated vault-held seed, so the onion private identity is not intentionally stored as an Arti private-key file. **Arti still writes plaintext guard and introduction-point metadata in its session state directory, and public Tor directory documents in its cache.** A bounded whitelist of guard, vanguard and circuit-timeout state is checkpointed inside SQLCipher after bootstrap, periodically while unlocked, and after confirmed Tor process death. The last good checkpoint is restored before a later Tor start. This preserves guard history across normal locks; onion-service introduction state is excluded because its ephemeral private keys are not restored. Abrupt process death can lose changes since the last durable checkpoint, and Arti may not yet have flushed every in-memory update. The Tor design deliberately preserves sampled guards across invocations; the possible anonymity cost of lost history is an inference from the [Tor guard specification](https://spec.torproject.org/guard-spec/algorithm.html). Removing live state directories on lock/start does not prove secure erasure of flash remnants. Active-session plaintext metadata and checkpoint lag remain explicit limitations of the strict “no plaintext metadata at rest” ambition. The checkpoint integration still requires compiled and device verification. [Arti keystore configuration](https://docs.rs/tor-keymgr/0.46.0/tor_keymgr/config/enum.ArtiKeystoreKind.html), [native implementation report](development/native-report.md)

Stopping Arti tasks and dropping a runtime is not by itself proof of zero open sockets. The Android `:tor` process must terminate on lock and an authorized device test must account for its sockets/FDs. Tor conceals the peer's network address from the app's ordinary connection path; it does not eliminate timing/volume analysis or protect against a compromised endpoint. With no mailbox, the sender and receiver must overlap online; undelivered ciphertext stays in the sender's encrypted outbox.

For reordered delivery, the configured MLS policy retains **two past epochs**, up to 32 out-of-order sender generations, and a maximum forward distance of 1024. Retaining recent epoch secrets delays their forward-secrecy benefit; this is an explicit availability/security tradeoff. Older messages and messages from removed senders fail closed. [OpenMLS past-epoch policy](https://docs.rs/openmls/0.9.0/openmls/group/enum.PastEpochDeletionPolicy.html)

The vendored OpenMLS memory store explicitly wipes replaced/removed serialized key bytes and owned scratch/state buffers. That focused change does not establish that every Rust allocation, cryptographic library copy or OS page is synchronously erased. Live Tor, two-device messaging, Android ABI loading and all release threat gates need their own evidence; host unit tests are not substitutes.

## Inline media preview caches (2026-09-16)

Inline photo/video/audio bubbles (T4.8) add two small bounded in-memory caches, both cleared
synchronously in `NoMessagesController.lock()` alongside the existing `_state.value.attachment?.bytes
?.fill(0)` wipe. `MediaPreviewCache` is an LRU bounded to ~24 entries / ~8 MiB, keyed by attachment
id, holding only derived summaries — waveform sample buckets (floats), and downsampled
thumbnail/poster `Bitmap`s — never raw decrypted attachment bytes. **Corrected 2026-09-16:** an
earlier revision of this paragraph claimed an explicit "zero-and-recycle step on eviction or
replacement". That was both unsafe and, for thumbnails, inert. Unsafe because `MediaPreviewCache.get`
hands the caller the bitmap itself and the inline bubble keeps drawing it, so recycling an evicted
entry — which the 8 MiB budget triggers after roughly eight 512×512 previews — crashed the next draw
pass with `Canvas: trying to use a recycled bitmap`; inert because `BitmapFactory` returns an
immutable bitmap, on which the `eraseColor` half was skipped outright. The cache now *unlinks*
evicted/replaced entries without touching their pixels, and erases (never recycles) on `clear()`
only, with thumbnails decoded `inMutable` so that erase is real. Bitmaps dropped by LRU pressure are
reclaimed by the garbage collector on its own schedule; nothing about them reaches disk. See
`docs/changes/MediaPreviewCache.kt.md`. `AudioPlaybackCoordinator` holds only the currently-playing
attachment id, a pause callback, and a teardown callback per live player (never a `MediaPlayer` or
decrypted bytes) to enforce one playing bubble at a time app-wide **and** to stop, release and wipe
every live player synchronously on lock — the earlier version merely dropped the pause callback
without invoking it, which left a voice message audibly playing, and its decrypted bytes live, after
a background auto-lock, because a stopped window's `Recomposer` never disposes the bubble that owns
the player. See `docs/changes/AudioPlaybackCoordinator.kt.md`. Neither cache is a new exception to the "attachment content is not written to
disk" boundary: both live in process memory only and both are gone at lock.

Voice messages are encoded to AAC-LC via `MemoryAudioEncoder`, which muxes entirely inside an
anonymous `memfd` (never a file path) and deterministically wipes/truncates/closes that `memfd` in
a `finally` block regardless of success. On a device where that `MediaCodec`/`MediaMuxer`+`memfd`
combination fails partway (observed occasionally on this project's emulator target), the encoder
returns `null` instead of throwing, and the caller falls back to uncompressed WAV rather than
failing the send. Both the AAC and WAV paths stay memory-only end to end, so the failure mode
carries no risk of partial plaintext ever reaching disk.

## Received attachments never leave the app, and in-app forwarding (2026-09-17, T4.11/T4.12)

**Why "no saving or sharing of received attachments" is a security property, not a UI preference.**
Everything this design protects — SQLCipher at rest, AEAD attachment chunks, a decoy vault, Tor
transport — protects content *inside* the vault boundary. A single "save to gallery" or "share via…"
affordance would carry a decrypted photo, video, voice note or document across that boundary in one
tap, into storage and into processes this app does not control: `MediaStore`/`Downloads` (world- or
backup-readable, indexed by the system, picked up by cloud photo sync), or an arbitrary receiving
app chosen from a system chooser, which then owns a plaintext copy forever. That copy survives the
lock, survives the panic password, and is exactly what a device seizure or a backup extraction
finds. So the exfiltration surface for received media is kept at zero by construction: media is
*viewable* in the in-app viewer (decrypted bytes in process memory only) and there is no mechanism
to do anything else with it. The only path a file takes out of the app remains the encrypted vault
export, which is useless without the password.

A 2026-09-17 audit of `app/src/main` confirmed that none of the seven usual Android media-export
vectors exists in this codebase: no `Intent.ACTION_SEND`/`ACTION_SEND_MULTIPLE`, no `ACTION_VIEW`
handoff, no `Intent.createChooser`, no `FileProvider` or any `<provider>` element exporting the
vault's media directories, no `MediaStore`/Downloads/gallery write, and no "save"/"share"/"export"/
"open with" control on any screen. The app was already clean on all six of those; the one code
change was preventive hardening of the seventh: incoming message bodies are now rendered inside
`DisableSelection` in `ChatScreen.kt`. That closes no live leak — a Compose `Text` is not selectable
unless some ancestor opts it in with a `SelectionContainer` — it makes the guarantee structural, so
that a future screen introducing a `SelectionContainer` above the message list cannot silently
reopen select → copy → paste-into-another-app for received text. Outgoing bubbles (the user's own
words) and the composer field keep normal copy/paste. This is a boundary about *mechanism*, not
about a compromised endpoint: it does nothing against a rooted OS, a screen-scraping accessibility
service or a malicious keyboard, all of which remain out of scope above.

**Forwarding: the `forwarded` bit is informational metadata, not a security primitive.** Long-pressing
a bubble can re-send that message into other paired chats, and it arrives tagged "Encaminhada" /
"Forwarded". That tag is carried by a single boolean, `forwarded`, added to `Envelope.Text` and
`Envelope.Attachment` (wire version 2; see `docs/development/message-api.md`) and mirrored into a
`messages.forwarded` column. It changes nothing about cryptography or authentication: the flag sits
inside the application plaintext, so the whole envelope is still encoded, then encrypted and
authenticated end to end by Signal or MLS exactly as before, and the flag is only readable after
authenticated decryption. No security decision is taken from its value — it drives a label. Treat it
as it is meant: a peer who tampers with their own client can set or clear the bit on what they send,
just as they can send any other content they like. It is not a provenance proof and must never be
presented to the user as one.

What the flag deliberately does **not** carry is the security-relevant part: there is no origin chat
id, no original author, and no "forwarded N times" counter. Any of those would hand the recipient a
metadata edge into the sender's *other* conversations, which is precisely the kind of new metadata
this design refuses to create. Forwarding an already-forwarded message keeps the bit `true`; it never
accumulates.

**Forwarding an attachment never writes it outside the vault.** `NoMessagesController.forwardMessage`
reads the stored row, decrypts the attachment through the existing `MessagingEngine.decryptAttachment`
path into process memory, re-sends it with `sendAttachment`, and wipes the plaintext buffer in a
`finally` block (the stored envelope body is wiped too). Each destination gets a brand-new envelope
with a fresh message id, fresh timestamp and a **freshly generated file key and file id**, because
the attachment is re-encrypted from plaintext rather than relayed as stored ciphertext — so the
recipient can see that the message was forwarded and still learns nothing about where it came from.
No temporary file, no cache entry and no `content://` URI is created anywhere in that path, so
forwarding is not a new exception to the "attachment content is not written to disk" boundary. It
also reuses the ordinary outbox, so with Tor down the copies simply queue as `PENDING`, under the
same transport-pause behavior described above.

**Vault-schema migration (the limitation this change introduced, fixed 2026-09-17).** Adding the
column moved the vault database to `SCHEMA_VERSION = 2`. `AndroidVaultStorage.migrate` performs the
upgrade (`ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN
(0,1))`) inside a single transaction. As first written it ran **only** from `initialize()`, while
`open()` called `requireSchema`, which demanded an exact `PRAGMA user_version` match and refused
anything else — so a vault created by an older build failed to open rather than being migrated in
place. That was accepted at the time on the premise that nothing had shipped; the premise stopped
holding when a real v1 vault on a physical device (Galaxy Note10+, Android 12) refused to unlock
after an app update, which is an **availability/durability** failure of the vault, not a cosmetic
one. It is now fixed: `open()` calls the same `migrate()`/`migrateStep()` used at creation, so a
vault is upgraded in place the moment it is unlocked, inside one transaction, before any typed query
runs against it; `requireSchema` was deleted. A migration step that needs pages draws them from
`storage_reserve` exactly as every other write does, so the fixed-capacity file never grows —
`max_page_count` is re-verified after the migration and `open()` re-checks the file size on the
migrated vault. A `user_version` **greater** than this build's `SCHEMA_VERSION` is still refused,
now with an explicit "created by a newer app version" message and before any transaction is opened,
because a database written by a newer build may contain tables or columns this build cannot
interpret. The safe default matters for the same reason the wire rule does: a row written before the
column existed backfills to `0`, matching how a wire-version-1 envelope decodes.

**Timing parity of the migration.** The first `open()` of a given vault database after an app update
is measurably slower than a normal open — one extra transaction plus a schema write. Every
subsequent open of that same database takes the fast path (`user_version == SCHEMA_VERSION` returns
immediately after one pragma read). This is not a real-vs-decoy distinguisher: `migrate` takes no
`VaultSlot`, contains no real/decoy branch, and `VaultManager.unlock` calls `open` once per unlock
for whichever slot the entered password resolved to. The real vault and the decoy vault are migrated
**independently**, each the first time *it* is individually opened, through the exact same code
path; the cost therefore depends only on whether that particular database file has already been
migrated, never on which slot it is.

## Decoy oracles fixed, and the cipher key-derivation timing gap closed (2026-09-23, T4.7)

Two pre-existing issues found in the 2026-09-15 review, neither introduced by the T3.1 fixes it
reviewed.

**(a) The "trocar senha de pânico" button was a plaintext, code-visible decoy oracle.**
`NoMessagesController.activate()` used to publish `canChangePanicPassword = opened.slot ==
VaultSlot.REAL`, and `SettingsScreen.kt` hid the button entirely when that field was `false`. Only
the real vault has a panic password to replace - a decoy vault does not itself own one, the real
vault's panic password is what *unlocks* the decoy - so this was not a bug in what the button did,
but the button's mere presence or absence was still a real/decoy tell readable straight off the
settings screen, by source review or by a screenshot taken during a coerced inspection, without ever
needing to unlock with the real password at all. `canChangePanicPassword` is now fixed at `true` once
unlocked, for both slots, exactly like `doorbellEnabled` a few lines above it in `UiContract.kt`
already was. The button, dialog, password meter and validation are pixel-for-pixel identical between
real and decoy, because they are the same composable reached the same way in both cases.

What changes behind the now-identical button: `changePanicPassword` in `NoMessagesController.kt`
still reads `session?.slot`, captured once before the async teardown so nothing further in the flow
can branch on it again, and picks between two `VaultManager` calls under the same `mutex.withLock`
block, after the same `discardAudio()` / `engine?.close()` / `pairing?.cancel()` /
`persistIdentity()` / `persistTorState()` sequence runs unconditionally for both:

- **Real:** `manager.resetPanicPassword(directory, session, password)` - unchanged, generates a
  fresh decoy identity and rewraps the header exactly as before T4.7.
- **Decoy:** `manager.fakePanicPasswordChange(directory, password)` (`VaultManager.kt`) - runs
  `PasswordPolicy().validate(password)`, the identical acceptance floor `resetPanicPassword` enforces
  (same length/score/symbol rules, so the same passwords are accepted and rejected either way), reads
  the vault's own calibrated `KdfParams` straight from the header prefix (readable without a
  password - `VaultHeader.kdfParams`, the same two ints `unlock`/`resetPanic` already read at the
  same offset before any derivation), and pays Argon2id twice against a fresh random salt each time
  - one derivation for what would be the "must differ from the current password" check, one for what
  would be the replacement master key, mirroring `VaultHeader.resetPanic`'s own two calls exactly.
  Both results are wiped immediately (`crypto.wipe`) and never touch a key, a comparison or a
  branch: nothing is compared against the real password, nothing is compared against the panic
  password, and nothing under the vault's own directory is ever written - no header rewrite, no new
  decoy generation, no database touched. The password argument itself is zero-filled in the same
  place the real path zero-fills it. Both branches finish by setting `committed = true` and showing
  the identical `notice_panic_password_changed` toast.

Why a fake KDF pass and not simply skipping the work: skipping it would reintroduce exactly the same
class of oracle one layer down - a real reset takes roughly two Argon2id derivations' worth of wall
clock (`KdfCalibrator` targets ~2.5 s per derivation, so ~5 s total) before the success notice
appears, and a decoy that returned instantly would let a coerced inspection distinguish real from
decoy purely by how long "change panic password" takes to finish, without reading any code at all.

**Follow-up (2026-09-23): the KDF-only match still left a disk-I/O oracle, now closed.** A review of
this same T4.7(a) fix found that matching only the Argon2id call count was not enough:
`resetPanicPassword` does not spend its time solely in `VaultHeader.resetPanic`'s two derivations -
it also runs `storage.beforeExport`, `copyCiphertextTree(absolute, stage)` (a full `Files.copy` of
every file under the vault directory: `real.db`, `decoy.db`, and everything under `real.files/` and
`decoy.files/`), deletes and recreates `decoy.files`, calls `storage.initialize` (which rewrites
`decoy.db` fresh up to its full `capacityMiB` allocation and writes the decoy media fixtures),
`storage.alignAllocations`, two atomic directory moves, and finally `discardCiphertext(backup)`,
which deletes the entire old ciphertext tree it just copied. None of that is bounded by a constant:
`AndroidVaultStorage.alignAllocations` keeps `real.files`/`decoy.files` equal in size to each other,
not small, so on a vault with any real media content `resetPanicPassword`'s I/O cost scales with
actual vault size while the original `fakePanicPasswordChange` did zero file copies, zero database
writes and zero deletes - a wall-clock (and, from `mtime`, on-disk) oracle exactly of the kind T4.7
set out to close, just one layer lower than the button itself. `fakePanicPasswordChange` now pays
equivalent disk I/O: it copies the same ciphertext tree into a throwaway staging directory with the
same `copyCiphertextTree` the real path uses, rewrites that copy's `decoy.db` the same way
`storage.initialize` rewrites the real path's (`Files.copy` of `real.db` onto `decoy.db`, same
full-capacity size), and discards the staging directory the same way `discardCiphertext(backup)`
discards the real path's old tree - all of it scaled to the vault's actual on-disk size, read from
the filesystem the decoy session already has legitimate access to, and none of it ever written under
the vault's own directory (only the throwaway staging copy is touched, and it is fully discarded
before the call returns). With that in place, the two branches are bounded by the same two Argon2id
derivations *and* the same ciphertext-tree copy/rewrite/discard cost, so wall-clock time and disk
bytes touched track each other regardless of vault/media size; neither branch can be distinguished
from the other by outcome, message, wall-clock time or bytes written. Covered by
`VaultTest.fakePanicPasswordChangeCopiesAndDiscardsAVaultSizedStagingTreeLikeResetDoes`, which seeds
the vault with media content, runs both branches, and asserts (a) the vault's own directory is
byte-for-byte unchanged after `fakePanicPasswordChange` and (b) the two branches' wall-clock times
stay within the same order of magnitude of each other on the same seeded content (a loose bound, to
avoid flaking on shared CI hardware, not a claim of exact parity) - not just that both call
`crypto.derive` twice.

Other real/decoy branches found by grepping `ui/` and `runtime/` for `isDecoy`/`decoy`/`slot`/`panic`
(none of them a UI-observable oracle, unlike the button above):

- `MessagingEngine.kt` (`mediaRoot`): picks `real.files/` vs `decoy.files/` as the directory *this*
  open session's own attachments read from and write to - necessary correctness (a decoy session
  must never write into the real vault's media directory or vice versa), not anything exposed to the
  UI or observable by comparing the two vaults' behavior from outside.
- `NoMessagesController.kt` (`prepareDoorbellHandoff`, `sameSlot`): internal bookkeeping for which
  in-flight `:tor` child a doorbell handoff is allowed to reuse: it is reused only for a matching
  slot. Never renders anything and never changes the doorbell notice or timing the peer/UI sees; see
  the doorbell section above ("no slot branch anywhere on it").
- `SetupLockScreens.kt`: the two-password *setup* screen (choosing the real and panic passwords
  together, before a vault exists). Not a real-vs-decoy branch at all - there is no open vault yet to
  differentiate - and out of scope for a "does the *open* vault leak its slot" review.

**(b) `cipher_memory_security` now turns on before SQLCipher ever sees the key, not after.**
`AndroidVaultStorage.openConfigured` used to call
`SQLiteDatabase.openOrCreateDatabase(path.toFile(), key, null, null)` - no hook - which issues
SQLCipher's own internal `PRAGMA key = '...';` from `key` as part of opening the connection, and only
*then* returned control to `openConfigured`, whose `applyPragma(database,
"cipher_memory_security=ON")` ran strictly after that. `cipher_memory_security` tells SQLCipher's
allocator to hold cipher-sensitive memory (including the key material a `PRAGMA key` derives and
retains) behind `mlock`-backed, zeroed-on-free allocations; applied only after the key was already
derived and resident, the derivation itself ran under the ordinary, unprotected allocator. This
affected `initialize()` (vault creation) and `open()` (vault unlock) identically, for both
`VaultSlot.REAL` and `VaultSlot.DECOY`, since both call through the one `openConfigured`.

`net.zetetic:sqlcipher-android` 4.19 ships `net.zetetic.database.sqlcipher.SQLiteDatabaseHook`
(`preKey(SQLiteConnection)` / `postKey(SQLiteConnection)`) together with a five-argument
`SQLiteDatabase.openOrCreateDatabase(File, byte[], CursorFactory?, DatabaseErrorHandler?,
SQLiteDatabaseHook)` overload - confirmed directly against the class files inside
`sqlcipher-android-4.19.0.aar` (`~/nomessages-tools/gradle-home/caches/modules-2/files-2.1/net.zetetic/
sqlcipher-android/4.19.0/`, no `-sources.jar` published for this artifact, so the constant pool and
method table of `SQLiteDatabaseHook.class`/`SQLiteDatabase.class`/`SQLiteConnection.class` were
decoded directly), because per SQLCipher's own hook contract `preKey` runs on the connection *before*
that internal `PRAGMA key`, and `postKey` right after. `openConfigured` now passes
`cipherMemorySecurityHook`, a `SQLiteDatabaseHook` whose `preKey` runs `PRAGMA cipher_memory_security
= ON;` via `SQLiteConnection.execute(String, Array<Any?>?, CancellationSignal?)` on the not-yet-keyed
connection; `postKey` is a no-op, since nothing here needs to run strictly after keying that the
existing post-open pragma block does not already cover. The post-open
`applyPragma(database, "cipher_memory_security=ON")` and its `check(pragmaLong(database,
"cipher_memory_security") == 1L)` stay exactly as they were: now a same-session verification that the
setting held, not the first and only place it was ever applied. Covered by
`AndroidVaultStorageTest.cipherMemorySecurityIsOnImmediatelyAfterOpenForBothSlots` (instrumented,
checks the pragma reads back `1` immediately after `manager.unlock` for both slots - device evidence
turned up that SQLCipher answers this particular pragma with SQLite column type TEXT rather than
INTEGER, unlike e.g. `PRAGMA user_version`, so the assertion compares by string; `pragmaLong` inside
`AndroidVaultStorage` already copes with that through `cursor.getLong`'s type coercion); the ordering
claim itself - that `preKey` genuinely runs before SQLCipher's own `PRAGMA key` - is a property of
`openOrCreateDatabase`'s implementation documented above with its AAR evidence, not independently
observable from a JVM or instrumented test.

## Fixed media reservation and blind cover growth (2026-09-23, T4.1)

`AndroidVaultStorage.alignAllocations` has always kept `real.db`/`decoy.db` and `real.files`/
`decoy.files` equal in size - but only at the two moments it is called, `VaultManager.create` and
`VaultManager.resetPanicPassword`. Between those moments, every attachment
`MessagingEngine.storeAttachment` writes lands in only one slot's media directory (whichever the open
session's `VaultSlot` is), and nothing kept the other slot's directory growing to match. A vault in
daily use - the ordinary case, not the moment right after setup - would drift: `real.files` and
`decoy.files` would differ in total size by however many attachments the real vault had sent or
received since the last reset, a forensic distinguisher a coerced inspection or a `du`/byte count of
the two directories could read directly. `beforeExport` (the guard that runs right before an export)
checked only the two fixed-size `.db` files, never the media directories, so this gap was invisible
to that check too - a plan review's "gate 2" measurement (byte-exact `real.db`/`decoy.db` after a
volume of messages/attachments) was going to surface it without pointing at the actual cause,
`.files/`, not `.db`.

**The fix has three parts, closing the gap at its actual source instead of only at the export guard:**

1. **A fixed per-slot media reservation.** `AndroidVaultStorage` gained `mediaCapacityBytes` (from a
   new `mediaCapacityMiB` constructor parameter, default 512 MiB, validated the same way
   `capacityMiB` already was), mirroring the database's own fixed-size allocation
   (`databaseBytes`/`capacityMiB`). Real and decoy each get this budget.
2. **Blind cover growth on every write, not just at setup/reset.** `MessagingEngine.storeAttachment`
   - the one place that writes into `real.files`/`decoy.files`, reached by both `sendAttachment` (the
   user's own send) and the receive loop's `handle` (an incoming attachment) - now writes a same-size
   blob of random, ciphertext-shaped bytes into the *other* slot's media directory immediately after
   a real write succeeds, under a filename indistinguishable in shape from a real attachment
   (`<random-hex>.bin`) but with no `FileRecord` in that slot's own database, so it is inert: nothing
   ever opens it as an attachment, `decryptAttachment` only ever looks up an id present in `db`. This
   uses the generic `Crypto` the open session already holds for randomness - never the sibling slot's
   file key, which that session does not have and must never derive or need to. Because both slots
   start equal (`alignAllocations`, enforced at setup/reset) and every subsequent write on either side
   is mirrored on the other, the two directories' total byte counts stay equal continuously, by
   induction, not just right after the next reset.
3. **Clean failure when the reservation is exhausted.** Before writing, `storeAttachment` checks the
   *growing* slot's own current directory size against `mediaCapacityBytes`; if the new attachment
   would exceed it, it throws `MessagingError(MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED)` before any
   byte is written or any file created - `NoMessagesController.errorMessage` maps that to
   `error_media_capacity` ("O espaço reservado para mídia deste cofre está cheio."/"This vault's
   reserved media space is full."), the same clean, localized-failure pattern T4.6 built for every
   other `MessagingEngine` failure a person can see. The check runs only against the slot actually
   growing; by the same induction as above, the sibling slot - which only ever receives a matching
   cover write - never drifts ahead of it, so it never needs a check of its own.
4. **`beforeExport` now also checks media parity**, not only the two databases: it calls the same
   `mediaAllocatedBytes` comparison `alignAllocations` already used, so an export taken between two
   `alignAllocations` calls - i.e. any export of a vault that has actually been used - is checked
   too, closing the gap the old export guard left open.

**Why a blind cover blob and not, say, padding the database instead:** the database is already a
fixed, content-independent allocation (`databaseBytes`) - messages themselves never grow it. Media is
the one thing in a vault whose on-disk footprint scales with real usage, so it is the one thing that
needed an active, per-write mechanism to keep the two slots' footprints matched, not a one-time
allocation. A blob rather than, say, a cover *message* was chosen because it needs no valid envelope,
no database row, no second identity to originate from - just bytes the same size as what the real
write just cost, written and forgotten by the one function already responsible for the real write.

Covered by `AndroidVaultStorageTest.sendAttachmentGrowsBothSlotsEquallyViaABlindCoverOnTheOtherSlot`
(sends a real attachment through a real `MessagingEngine` against a real `AndroidVaultStorage`-backed
vault with pre-existing seeded fixture media in both slots, and checks both directories grow by
exactly the same number of new bytes, under different filenames, and that `beforeExport` still
accepts the result afterwards) and
`AndroidVaultStorageTest.sendAttachmentFailsCleanlyWhenTheMediaReservationIsFull` (a small
`mediaCapacityMiB`, two attachments that fit, a third that does not and throws
`MessagingError(MEDIA_CAPACITY_EXHAUSTED)` with neither slot's directory left bigger than the two
that succeeded). Both run on `emulator-5556`, the real `AndroidVaultStorage`/SQLCipher stack, not a
JVM fake. See `docs/changes/AndroidVaultStorage.kt.md`, `docs/changes/MessagingEngine.kt.md`,
`docs/changes/MessagingError.kt.md`, `docs/changes/NoMessagesController.kt.md` and
`docs/changes/AndroidVaultStorageTest.kt.md`.

### Follow-up (2026-09-23): two P1s found by code review, both fixed

A code review of the section above found two real defects, both fixed the same day, both covered by
new `AndroidVaultStorageTest` cases against the real `AndroidVaultStorage`/`MessagingEngine`/SQLCipher
stack:

1. **`VaultManager.resetPanicPassword` broke on any vault with real media.** It rebuilds `decoy.files`
   from scratch (`storage.initialize`, the small fixed fixture set) but only *copies* `real.files`,
   never rebuilds it - so on any vault that had ever sent or received an attachment, `real.files` was
   still the way ordinary use had grown it (point 2 above), bigger than the freshly rebuilt
   `decoy.files`. The very next line, `storage.alignAllocations`, has required media parity since this
   section's point 4 above and threw `IllegalStateException("Vault media allocations differ")` on that
   mismatch - and `NoMessagesController.changePanicPassword`'s blanket `catch (_: Exception) {}` turned
   that into a silent, generic failure: **the real panic-password change simply never applied.**
   Fixed by a new `VaultStorage.padMediaToMatch(directory, slot, matchSlot)` method, called right after
   the decoy rebuild and right before `alignAllocations`: it tops the freshly rebuilt slot back up to
   the reference slot's actual current size with the same blind-filler technique point 2 uses, so
   `alignAllocations`'s parity check is satisfied by construction instead of relaxed. See
   `docs/changes/VaultManager.kt.md`, `docs/changes/VaultSession.kt.md` and
   `docs/changes/AndroidVaultStorage.kt.md`.
2. **The blind cover write was not crash-consistent.** The primary attachment write (`fsync` +
   `ATOMIC_MOVE`) is durable the instant it completes, independent of whether the enclosing
   `atomic{}`/`db.transaction{}` this whole call runs inside ever commits. `storeAttachment` gated its
   cover write on `!existing` (`existing` = the primary file already being on disk), so a process death
   between the primary write's `fsync` and the transaction's commit left the primary file durable with
   nothing recorded in `db`; a retry (retransmission, or a redelivered receive) found `existing == true`
   and, under the old gate, **permanently skipped the cover that attempt owed** - defeating exactly the
   real/decoy byte-count distinguisher this section exists to close, after an ordinary mobile-OS event
   (OOM kill, force-stop, battery pull), not an exotic edge case.
   Fixed by making the cover write idempotent per `fileId` instead of conditional on `existing`: its
   filename is now `mac(this session's own fileKey, "cover:" + fileId)`, truncated to 16 bytes, not a
   fresh random name. A retry (same key, same `fileId`) recomputes the same name and finds - or,
   if the earlier attempt never got that far, now writes - its own cover exactly once; `storeAttachment`
   calls it unconditionally. Deriving the name from the bare `fileId` directly, as one plausible fix
   would, was rejected: real attachment filenames are already `"$fileId.bin"` and `fileId` is public
   (visible on the wire and in the real slot's own filenames), so that would let anyone who has
   decrypted *either* slot spot the matching cover in the *other* slot by filename alone, without that
   slot's key at all - reintroducing exactly the cross-slot correlation a blind cover exists to
   prevent. The MAC construction keeps idempotency without that leak: computing or recognising the
   cover name requires the covering session's own `fileKey`, which an inspector who has not compromised
   *that* slot does not have. See `docs/changes/MessagingEngine.kt.md`.

Neither fix changes the public shape of `VaultStorage`/`MessagingEngine` beyond the one new interface
method (`padMediaToMatch`) and one new private-function parameter (`coverSiblingSlot(fileId, size)`).
Full rebuild/retest (`:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` plus the instrumented suite on `emulator-5556`) is recorded in
`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`, T4.1.

## Input/keyboard hardening and capture detection (2026-09-16, T4.9)

`FLAG_SECURE` (see above) blocks screenshots, screen recording and the Recents thumbnail; it says
nothing about what the *keyboard* sees while composing input, what an *accessibility service* can
read from the view tree, what an *autofill service* could offer to save, or whether the user is
told when someone still tries to capture the screen despite the flag. T4.9 adds the remaining
layers Android exposes for those four gaps:

- **Private IME input.** Every `TextField`/`OutlinedTextField` in the app now combines
  `privateKeyboardOptions()` (Compose-side `autoCorrectEnabled = false`) with `PrivateImeScope`
  (`app/src/main/kotlin/dev/mx3/nomessages/ui/PrivateInput.kt`), which uses
  `InterceptPlatformTextInput` to OR `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` and
  `InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS` onto the platform `EditorInfo` every field's IME
  session builds, without dropping bits the field already set (password variation, IME action,
  etc.). This asks the *system* IME not to learn from or suggest based on what is typed — it is a
  request the IME honors voluntarily, not an enforced boundary; see T7.1 below for what a
  compromised/malicious keyboard can still do regardless.
- **Autofill disabled.** `MainActivity.onCreate` sets
  `window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`,
  which excludes the entire Compose view hierarchy from the autofill tree, so no autofill service
  is ever offered a saved-password/message suggestion for this app or asked to save one.
- **Accessibility data-sensitive (API 34+) — trade-off.**
  `window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)` is set
  in `onCreate`, guarded to API 34+. Per the platform contract, this withholds
  `AccessibilityNodeInfo` content from any accessibility service **other than the user's
  currently-selected default service** (e.g. TalkBack, Switch Access, Voice Access) — those keep
  working normally, since the OS treats the user's own chosen assistive service as trusted by
  definition. A second, non-default accessibility service (including a malicious one that abuses
  the permission, or one the user enabled for an unrelated purpose) does not receive this app's
  node tree. This is deliberately **not** a global `importantForAccessibility = NO` on the root
  view: that removes the nodes from the accessibility tree entirely, for every service including
  the default one, which would break the user's own screen reader/switch-access setup and this
  project's own Compose UI tests that walk the semantics tree — a strictly worse trade for a
  narrower benefit. The flag is set once on `window.decorView` (the root of the whole window,
  above the entire Compose hierarchy) so every descendant inherits it, rather than needing to be
  threaded through each screen individually.
- **Third-party keyboard disclosure.** `isSystemIme(imeId, systemPackages)` in
  `app/src/main/kotlin/dev/mx3/nomessages/ui/UiLogic.kt` is a pure function (unit-tested in
  `UiLogicTest.kt`) that decides whether an IME id belongs to one of a caller-supplied set of
  system packages. `MainActivity.kt` gathers the impure half — `Settings.Secure.DEFAULT_INPUT_METHOD`
  plus `InputMethodManager.getEnabledInputMethodList()` filtered by
  `ApplicationInfo.FLAG_SYSTEM`/`FLAG_UPDATED_SYSTEM_APP` — recomputed on every `onStart`, and
  drives a discreet warning ("Teclado de terceiros ativo: ele pode ver o que você digita" /
  English equivalent) in the message composer and the setup/lock screens whenever the active
  keyboard is not part of the OS image. This is a **disclosure**, not a mitigation: it does not
  stop a third-party keyboard from reading input, it only tells the user one is active. A
  first-party keyboard the user separately chose to install (e.g. a well-known alternative from
  the Play Store) still triggers it — the signal is "not shipped/reviewed as part of the OS
  image", not "known malicious".
- **Screen-capture detection (API 34+).** `MainActivity` registers/unregisters
  `Activity.ScreenCaptureCallback` in `onStart`/`onStop` and shows a non-blocking `Snackbar`
  ("Tentativa de captura de tela detectada" / English equivalent) when the callback fires. The
  normal permission `android.permission.DETECT_SCREEN_CAPTURE` is declared in the main manifest.
  `FLAG_SECURE` already blocks the capture itself (the callback would not fire for anything it
  successfully blocked in the way screenshots/screen-record are already blocked); this is purely a
  notice for whenever a capture is still attempted through some other path, and only on API 34+
  devices — there is no equivalent detection API on lower API levels, so on API 31-33 an attempted
  capture (if any got through FLAG_SECURE at all) goes unnoticed by the app.

None of the five items above changes what `shouldApplySecureFlag` decides, and none of them is a
substitute for it. See T7.1 in `docs/superpowers/plans/2026-09-14-roadmap-to-release.md` for the
one gap none of this closes: a compromised or deliberately malicious system keyboard can still read
everything typed into this app, because Android provides no way to sandbox IME input per app - the
only complete defense is an in-app keyboard that never hands input to the system IME for the most
sensitive fields, which `SPEC.md` §14 explicitly places out of v1 scope.

## Maintenance, audit and distribution

Use official pinned dependencies, preserve source/license notices, verify native artifacts for each shipped ABI, and review API/security changes when updating. Signal documents that external use of libsignal is unsupported; its API and supported protocols evolve. This is an integration-maintenance obligation, not evidence that NoMessages has received Signal's audit or endorsement. [Official libsignal project](https://github.com/signalapp/libsignal)

No independent full-system audit or product/branding legal clearance is claimed here. The app should use its own name, icon and assets; distribution and branding review are separate from cryptographic correctness. Do not describe it as invisible, forensically undetectable, immune to coercion, equivalent to blockchain encryption, or impossible to break. Release claims must match reproducible evidence and the documented threat boundary.
