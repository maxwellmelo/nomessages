# Native API (integration contract)

Library `nomessages`; Rust 1.91+, optional `tor` and `mls` Cargo features, both default. Kotlin APIs are synchronous and must be called off UI thread. They throw IllegalArgumentException for invalid inputs and IllegalStateException for operation failures. Never log arguments/results.

## TorNative

`TorNative.address(seed32: ByteArray): String` deterministically computes the stable onion without runtime, files or sockets; safe for offline vault setup.

`TorNative.start(seed32: ByteArray, stateDir: String, cacheDir: String, bridgeLines: Array<String> = emptyArray()): String` starts one process-local runtime, bootstraps, publishes stable onion, returns onion address. Idempotence is not implied; stop before a new start. Native secret keystore is ephemeral; seed must come from unlocked SQLCipher. Caller must run only in nonexported Android `:tor` service and kill that process at lock after stopping it. StateDir is per unlocked session and must be removed on stop/start; it contains Arti guard/introduction-point metadata, not private keys. Cache contains public Tor directory documents. Neither location contains app plaintext or onion private keys. State metadata is a documented remaining forensic limitation; Android ordinary cache directories are not RAM filesystems.

`onion(): String`; `status(): String` returns BOOTSTRAPPING/PUBLISHING/READY/STOPPED; `awaitReady(timeoutMillis: Int = TorNative.MAX_READY_TIMEOUT_MILLIS): String` blocks until the onion descriptor is published; `send(onion: String, frame: ByteArray): Long` returns connection ID after writing one framed payload; `poll(timeoutMillis: Int): TorFrame?`; `reply(connectionId: Long, frame: ByteArray)`; `closeConnection(connectionId: Long)`; `stop()`.

### Readiness states

`start` returns as soon as the Arti client is bootstrapped and the onion service has been launched; launching only begins introduction-point setup and descriptor upload. Status therefore moves BOOTSTRAPPING → PUBLISHING → READY, and PUBLISHING is reported the moment `start` returns. READY is set only by the `tor_hsservice::OnionService::status_events()` watcher, when `OnionServiceStatus::state().is_fully_reachable()` holds (`State::Running` or `State::DegradedReachable`, i.e. descriptor up to date and introduction points satisfied). The transition is reversible: a later status event that loses reachability moves READY back to PUBLISHING. STOPPED is terminal for its generation, so a status event arriving after `stop` can never resurrect a torn-down transport.

`awaitReady(timeoutMillis)` parks until READY, with `timeoutMillis` in `0..300000`; values outside that range throw. This 300-second budget is deliberately separate from the 180-second bootstrap timeout inside `start`. The call returns the status actually reached: `"READY"` on success and `"PUBLISHING"` when the deadline elapsed first — that is **not** a failure, publication keeps running and the caller may wait again. It throws once the transport is stopped or cancelled, which is how a lock aborts the wait. Callers should slice long waits (the Android `:tor` service uses 5-second slices) so process teardown is observed promptly instead of parking the child for the whole budget.

`send` is allowed in PUBLISHING as well as READY: an outbound rendezvous needs a bootstrapped client, not our own published descriptor. Only inbound reachability — being found by a peer — requires READY. Live gates (`native/tests/tor_live.rs`, `core/src/test/native/TorDeliveryProbe.java`) must nevertheless wait for READY before sending, so that a failure measures the transport and not the publisher's remaining work.

`data class TorFrame(val connectionId: Long, val payload: ByteArray)` contains no claimed sender. Incoming caller must trial authenticated decrypt against paired/pending sessions. Reply uses existing connection, never a cleartext claimed onion. Only canonical v3 onions accepted. Frame payload cap 16,388 bytes (four-byte application header plus 16 KiB padded body); maximum 128 active streams and bounded queue. Additional native length-prefix is u32 big endian (maximum stream record 16,392 bytes). Per-contact client isolation. Use no TCP/clearnet fallback. Tor status READY means the onion descriptor is published and the service is believed fully reachable; PUBLISHING means bootstrapped and launched but not yet reachable.

### Doorbell (T4.17)

A second, minimal onion service that survives the vault lock. It shares the process-wide Arti host with the messaging transport — same runtime, same bootstrapped client, same guards — but holds its own reference to it, so the two have **independent lifecycles**: tearing messaging down leaves the doorbell serving, and stopping the doorbell leaves messaging serving. The host itself dies only when its last owner lets go. Knocks are delivered on virtual port **4243**, deliberately distinct from messaging's **4242**, so a probe of one service can never be answered by the other even though both run on one client.

`doorbellStart(seed32: ByteArray, stateDir: String, cacheDir: String, bridgeLines: Array<String> = emptyArray()): String` launches the service and returns its 62-character onion address. The argument shape matches `start` because the doorbell may have to build the Arti host itself, in a process where messaging never ran. Idempotent for the same seed; a **different** seed throws rather than silently rebinding, because the address is published in pairing offers and must not change under the peers. `doorbellOnion(): String` returns the running address and throws when nothing is running — which is also the cheapest way for a caller to ask whether the doorbell is up.

`doorbellTokens(tokens: List<ByteArray>)` replaces the **whole** accepted-token set: at most 256 blobs of 16..64 bytes each. The tokens are opaque here; native code never learns which contact a token belongs to. Reloading the set (a new contact was paired) deliberately keeps the replay cache and the rate-limit ledger, so a reload is not a way to reset either defence. `doorbellStop()` clears the set.

`doorbellPoll(timeoutMillis: Int): Int` blocks up to `timeoutMillis` (`0..30000`, `0` being a non-blocking drain) and returns how many accepted knocks were drained since the previous poll; `0` means nothing rang. A burst collapses into one drain because the UI shows one notification either way. **Nothing else comes back** — no identity, no timestamp, no token — which is all a notification needs and the most an observer of this process can learn. The pending-marker queue is bounded at 64: an unbounded one would be a memory oracle for a flooder.

`doorbellMinimal()` destroys the messaging transport with exactly the effect of `stop()` — onion service, connections, per-peer isolated clients, session state — while the doorbell keeps serving on the surviving shared host. It **refuses to run while no doorbell is serving**, precisely so the lock sequence cannot be written backwards: start, load tokens, and only then go minimal. `doorbellStop()` stops the doorbell only; when the doorbell is the host's sole owner it disposes of the runtime and client as well, which is why unlock must call `start` **first** (it adopts the still-live host and skips the 180-second bootstrap) and `doorbellStop` afterwards, never the reverse.

`doorbellKnock(onion: String, token: ByteArray): Boolean` rings a peer's doorbell with the token **that peer issued to this device** and reports whether it was acknowledged. The frame is built natively and the token never crosses back out. A fresh isolated client is used per knock, so a doorbell circuit is not linked to that contact's messaging circuits. Budget: up to 180 seconds of bootstrap plus a 60-second exchange, so **240 seconds** per attempt — callers must schedule accordingly (see `messaging-api.md`). `false` covers "rejected", "not listening" and "closed without answering" alike, by design.

#### Knock frame

One frame per connection, fixed at **57 bytes**:

```
version(1) || timestamp u64 big-endian(8) || nonce(16) || HMAC-SHA256(token, version||timestamp||nonce)(32)
```

Fixed size on purpose: a length field is an extra parser state to get wrong, and a variable size would leak through padding. The version byte is inside the MAC, so bumping it invalidates every older frame. A frame that passes every check is answered with a single `0x01` byte and the stream closes. Every other outcome — wrong length, trailing bytes after the 57, unknown token, timestamp outside the ±300-second window, replayed nonce (remembered 600 s, deliberately longer than the window so a captured frame expires before its nonce is forgotten), or a token already accepted within the last 600 s — closes the stream **without writing a single byte**. The observable behaviour is identical for all of them: a knocker cannot learn which check failed. Tag comparison is constant time and every loaded token is tried with no early exit, so timing reveals neither the matching token's position nor whether any matched. Verification is HMAC-SHA256 (`hmac` 0.12.1, `sha2` 0.10.9 — promoted from transitive to direct dependencies; nothing new was downloaded). At most 32 knocks are served concurrently, each with a 15-second read timeout.

#### Error conventions

Doorbell opcodes throw `IllegalStateException` like every other native call, but with their **own** fixed strings chosen by opcode range: `"Doorbell operation failed"` and `"Doorbell output unavailable"`, against `"Tor operation failed"`/`"Tor output unavailable"` for opcodes 0-9. No error detail, token, knock byte or address ever reaches an exception message. A panic on any opcode leaves unknown state behind, so **both** services are torn down, not only the one whose opcode panicked.

## MlsNative

All methods return `MlsResult(state, groupId, message, welcome, application, senderIdentity, members, kind)`. `MlsMember(identity32, signatureKey32, leafIndex)` and `MlsKind(KEY_PACKAGE, GROUP_CREATED, COMMIT, APPLICATION)` are typed Kotlin values. Empty byte arrays represent absent outputs.

`keyPackage(identitySeed32: ByteArray): MlsResult` creates separate provider state + key package, returning key package in `message`. Seed is the paired Ed25519 identity seed, so MLS signing key matches credential identity.

`create(state: ByteArray): MlsResult` creates a group containing self from keyPackage state.

`add(state: ByteArray, keyPackages: List<ByteArray>, expectedMembers: List<ByteArray>): MlsResult` validates key packages and resulting exact expected identity set, adds members and merges own pending commit; returns commit in message and Welcome in welcome. Caller is designated coordinator and has already cryptographically checked clique proofs.

`join(state: ByteArray, welcome: ByteArray, expectedMembers: List<ByteArray>, coordinatorIdentity: ByteArray): MlsResult` consumes one-time KeyPackage state, validates resulting exact membership and Welcome signer, returns joined state.

`encrypt(state: ByteArray, plaintext: ByteArray): MlsResult` returns one MLS PrivateMessage in message; identical ciphertext can be sent to each member. Store sender plaintext separately inside SQLCipher.

`process(state: ByteArray, message: ByteArray, expectedMembers: List<ByteArray>, coordinatorIdentity: ByteArray): MlsResult` authenticates sender, returns application plaintext or validates coordinator + proposed exact membership before accepting a Commit. Standalone proposals and external joins are rejected. Supplying expectedMembers is the caller's explicit membership approval. Unapproved commits do not return changed state.

Both creation and join explicitly retain at most two past epochs for delayed application messages across membership commits. This modest reordering tolerance retains additional past secrets, weakening immediate epoch forward secrecy; it does not retain arbitrary history. Messages older than this window fail closed. Sender ratchets explicitly allow 32 out-of-order generations and a maximum forward distance of 1024; exceeding those bounds fails closed. Previously removed senders are rejected even for older epochs. Application outbox must still deliver commits in order and should drain earlier application messages before newer commits where possible.

`remove(state: ByteArray, removeIdentities: List<ByteArray>, expectedMembers: List<ByteArray>): MlsResult` coordinator operation; returns new state and removal commit.

Every returned state is secret material and must enter SQLCipher transaction with messages/outbox BEFORE network sends or ACKs. Input state remains valid on exceptions. No native global MLS sessions; per-call native transient state/buffers are dropped/zeroized. Caller wipes byte arrays after persistence and at lock. Snapshot cap 16 MiB, MLS message cap 12 MiB (plaintext 12 MiB minus 64 KiB overhead), group cap 100. Transport payload cap is 16,388 bytes; app fragments into padded 16 KiB bodies with a four-byte application header. Encrypted disk state is trusted but format/length parsing is bounded. Root owns coordinator authorization, clique proofs, transport ordering and crash-safe transaction handling.

### Internal binary encoding

All integers unsigned big endian u32 except Tor connection IDs u64. Byte strings have u32 length followed by bytes; lists have u32 count then byte strings. Trailing bytes always rejected. MLS `transact(op,state,args)` opcodes0 keyPackage(seed),1 create(),2 add(list packages,list approved),3 join(welcome,list approved,coordinator),4 encrypt(plaintext),5 process(message,list approved,coordinator),6 remove(list identities,list approved). Response: u32 kind(0..3), bytes state/groupId/message/welcome/application/senderIdentity, u32 member count, then each member bytes identity, bytes signatureKey, u32 leafIndex. Kotlin wrappers own validation/encoding; app should not construct native opcodes.

Tor `transact(op,args)` opcodes0 address(seed),1 start(seed,statePath,cachePath,list bridges),2 onion(),3 status(),4 send(onion,frame),5 poll(u32 timeout),6 reply(u64 id,frame),7 close(u64 id),8 stop(),9 awaitReady(u32 timeout). Opcode 9 was appended after `stop` so every previously published opcode keeps its number. Address/status/awaitReady responses are raw ASCII, send is u64 ID, poll is empty or u64 ID plus raw frame, stop/reply/close empty. Native errors deliberately use fixed messages without secret data.

The doorbell added opcodes **10-16**, appended by the same rule and never interleaved: 0-9 keep their numbers, their arguments and their behaviour.

| # | Operation | Arguments | Response |
|---|---|---|---|
| 10 | `doorbellStart` | `seed(32)`, `stateDir`, `cacheDir`, list of bridge lines | onion address, 62 raw US-ASCII bytes |
| 11 | `doorbellOnion` | none | onion address, 62 raw US-ASCII bytes |
| 12 | `doorbellTokens` | list of opaque token blobs, 16..64 bytes each, at most 256 | empty |
| 13 | `doorbellPoll` | `u32` timeout in ms, `0..30000` | empty when nothing rang, else `u32` big-endian count of accepted knocks drained |
| 14 | `doorbellMinimal` | none | empty |
| 15 | `doorbellStop` | none | empty |
| 16 | `doorbellKnock` | `onion(62)`, `token(16..64)` | one byte: `1` acknowledged, `0` not |

Same encoding rules as everything else: `u32` big-endian integers, byte strings as `u32` length plus bytes, lists as `u32` count then byte strings, trailing bytes always rejected. The Kotlin wrappers own validation and encoding; the app should not construct native opcodes. The Android `:tor` service mirrors these seven over Binder as `what` **20-26**, in the same order, starting at 20 so a doorbell request can never collide with a RESULT/ERROR/FRAME reply code.

## Guard-history vault snapshots

`core.nativebridge.TorStateSnapshot` is a pure JVM helper. Its `META_KEY` is `tor_guard_state`, `MAX_SNAPSHOT_BYTES` is 4 MiB, and APIs are:

- `capture(stateDirectory: File): ByteArray?`: captures only `state/guards.json`, `state/vanguards.json`, and `state/circuit_timeouts.json`. Returns null for absent/empty sampled guards or repeated instability; invalid data throws. Preserve the existing encrypted meta value on null or exception. Two identical bounded reads are a best-effort live checkpoint, not a guarantee of a transaction across independent Arti files.
- `restore(stateDirectory: File, snapshot: ByteArray?)`: validates size, binary format, integrity digest, exact path allowlist, bounded JSON syntax and nonempty sampled guard history before writing. The destination must be absent or empty, and Tor must be stopped. Null creates an empty private directory. Full schema validation remains Arti's responsibility.
- `clear(stateDirectory: File)`: after confirmed Tor process death, removes all session files, including onion-service instance records and locks. It never traverses a symbolic link and rejects a symlink root. This is logical cleanup, not flash-erasure proof.

Root restores from SQLCipher before binding/START, captures after bootstrap and periodically, and captures after confirmed process death before closing the vault. Every successful capture must be committed to the encrypted meta row and wiped. A normal STOP should attempt native shutdown before the final process kill to allow Arti's Drop-time state flush; a bounded forced kill remains mandatory. Abrupt death may lose updates after the last Arti flush/checkpoint. Never send the snapshot through Binder.

The allowlist follows tor-persist 0.46.0 `src/fs.rs` (`state/<key>.json`), tor-guardmgr `guards`/`vanguards` storage constants, and tor-circmgr `circuit_timeouts`. Onion-service state must NOT be restored: its introduction-point records require ephemeral keys that intentionally disappeared at lock (`tor-hsservice/src/ipt_mgr/persist.rs`, `IptExpectExistingKeys`). Public consensus cache is separate. Metadata remains plaintext in the active-session directory; encrypted guard checkpoints preserve history across normal locks without claiming memory-only Arti persistence.

Transport timing: connect plus initial frame flush has a 40-second native timeout, below the parent's 45-second SEND RPC deadline. Dropping the timed-out/cancelled future drops any partial stream and releases its connection permit. Reply also times out at 40 seconds and explicit close cancels pending writes. Android shutdown intentionally allows a 2.5-second graceful death wait, followed by a separate 5-second force-kill/death-verification window: up to 7.5 seconds plus dispatch overhead. Native runtime shutdown itself is capped at 2 seconds. The security boundary is confirmed process death, not an elapsed timer or STOP request.
