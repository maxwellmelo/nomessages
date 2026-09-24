# Runtime lifecycle review — 2026-09-13

## Supervised transport activation — 2026-09-14 (T2.3)

Activation is no longer one-shot. `NoMessagesController.superviseTransport` runs a
`TransportSupervisor` inside the session scope: every failed attempt publishes
`NetworkStatus.RETRYING` and waits 5 s, 10 s, 20 s, 60 s, 120 s and then 5 min
between attempts, and a transport lost while online is replaced at once with the
schedule restarted. `NetworkStatus.ERROR` is now reserved for supervision itself
dying, which is the only case where the old "lock and reopen" notice still holds.

Lifecycle consequences that the device gates in this document must now cover:

- The loop is cancelled, not polled, by the lock: it lives in `sessionScope`, so
  `lock()` ends it at the next suspension point. Coroutine timeouts are classified
  before cancellation (`TimeoutCancellationException` first), so a blown 180 s
  bootstrap deadline is a retry and only a real lock tears the loop down.
- Each attempt owns one `TorConnection`, registered under `lifecycleGuard` *before*
  it is started, so whatever the lock captures is also what it kills. `transport`
  is null between attempts and before the first one, so `lock()` during STARTING
  now legitimately captures null.
- A retry only proceeds after the previous child's death is confirmed
  (`recycleTransport`), because `TorService` refuses a second START and a surviving
  child would be rebound by `bindService`. The confirmed kill at lock is unchanged.
- The onion seed is re-read from the vault per attempt and wiped right after
  `TorConnection.start`, so no plaintext seed survives between attempts; `activate`
  wipes its own copy as soon as the onion address is derived.
- While not ONLINE the outbox is paused rather than failed (see `messaging-api.md`),
  so scenario 2 of T3.2 must now also assert that no send is attempted between
  RETRYING and ONLINE, and that a lock during a backoff wait leaves no `:tor` child.

## Supervision hardening — 2026-09-15 (T2.1/T2.3 review fixes)

Four defects found by review on the supervision delivered above are closed here.
They change what the device gates must observe, not the shape of the loop:

- **Readiness is tri-state.** `TorConnection.awaitReady(budget)` returns
  `TorReadiness.READY | PUBLISHING | LOST` instead of a boolean. `PUBLISHING`
  means the budget elapsed while the child is alive and still publishing, and it
  no longer destroys the child: `attemptTransport` keeps the same bootstrapped
  Arti client for up to three 300 s publication budgets before falling back to a
  full recycle, so a slow directory costs waiting, not a repeated 180 s bootstrap.
  Only `LOST` is a failed attempt.
- **Guard history is checkpointed on every transport transition**, not only at
  lock: right after a successful `TorConnection.start`, after each elapsed
  publication budget, and — the case the security model promised — right after
  `recycleTransport` confirms the child's death, before `transport` is cleared.
  All of them go through `checkpointTorState(token)`, which still honours the
  generation token and `shuttingDown`, so a newer session never writes for an
  older one.
- **Death is awaited, not sampled.** `holdTransport` races the 60 s heartbeat
  against `TorConnection.awaitDeath()`. A child that dies one second after a
  checkpoint is now detected at once instead of up to a minute later, which is
  the window in which the outbox was still spending its per-item cooldown
  against a dead transport.
- **Reachability is reported in both directions.** After each heartbeat a 30 s
  probe maps `READY` to `TransportSupervisor.reachable()` (ONLINE) and a
  `PUBLISHING` result back to `publishing()`. Until now the banner could only
  ever move from PUBLISHING to ONLINE, so an onion that stopped being reachable
  kept claiming "Tor conectado" — the exact lie T2.1 exists to remove. The
  status is deduplicated by the supervisor, so no repaint happens while nothing
  changes, and the transport is *not* restarted for a reachability drop.

Device gate consequence: scenario 2 of T3.2 must now also observe the banner
returning to PUBLISHING while the service is unreachable and back to ONLINE
without a new `:tor` process being created in between (one pid across the whole
transition), and that a `:tor` kill is reflected in under a heartbeat.

## Final cleanup-outcome recheck

Re-read the latest controller cleanup, startup gate and bounded graceful Tor STOP
changes. The snapshot-cleanup P2 below is addressed in source: export/reset record
their committed result immediately after the operation returns; owned-session
cleanup runs independently, and metadata cleanup retries with the UI locked/busy
before publishing that original outcome. Lock and failed activation use the same
cleanup retry path, rather than losing their worker to a deletion exception.
Startup recovery failure retains the shutdown/busy gate. Tor shutdown allows a
bounded 2.5-second flush opportunity, then retains forced process-death checking.

No additional concrete critical issue was found in this final scoped source
recheck. No compiler, device, fault-injection or socket test was run by this
reviewer; the execution gates in the earlier sections still apply.

## Tor guard snapshot and startup-gate recheck

Source-only review of TorStateSnapshot, its controller integration, TorService's
restored-directory handling, and the application SCREEN_OFF receiver. No compiler
or device execution was performed.

The helper allows only three bounded regular files, validates JSON syntax and a
nonempty sampled guard array, checks a versioned bounded snapshot/digest, rejects
duplicate/unlisted paths and symlinks, restores into an empty directory, and wipes
temporary owned snapshots. The native owner confirmed the three paths and literal
`guards` array against the pinned Arti sources. Hidden-service state, key stores,
lock files and the public directory cache are excluded from restore. Startup
attempts orphan Tor death before clearing state/recovering the vault; activation
restores only the selected SQLCipher metadata before binding. The application
registers SCREEN_OFF only in the main process. Capture failures/null results and
database write errors preserve the previous encrypted checkpoint.

**Remaining P2 in this integration snapshot:** controller export/reset invokes
`TorStateSnapshot.clear` after the archive or new panic credentials have already
committed. A deletion exception enters the generic operation-failed path and can
falsely report that reset failed. Ordinary lock also invokes clear after closing
the session but before its final shutdown-state publication; an exception there
escapes the worker and leaves shutdown/busy set indefinitely. Separate committed
operation outcomes from metadata cleanup and handle cleanup through its own
locked retry/status path. Do not imply credentials rolled back, and do not leave
an unobserved failed cleanup coroutine as the only route to finishing lock. This
was sent directly to root.

## Final scoped source recheck

The later application-owned controller, Activity result-owner IDs, recorder
cleanup and publication changes were re-read without running compilers or
Android tests. The concrete findings in the second-integration section below
are addressed in this source snapshot:

- The manifest selects `NoMessagesApplication`, whose process-lived ViewModelStore
  owns one lazy controller. MainActivity obtains that same controller; a finished
  Activity therefore cannot leave an old controller teardown racing a fresh
  controller. The Tor child does not access the lazy controller.
- MainActivity saves/restores its result-owner ID across configuration changes.
  Attaching a different owner or detaching the current finishing Activity
  abandons pending request ownership. Each document/permission callback checks
  the owner before consuming current state. Old Activity detach does not clear
  a newer attached platform, and immutable photo request IDs remain checked.
- TorConnection checks `stopped` again inside the Main binding block. Recorder
  startup failure releases hardware and clears its failed controller request;
  read-loop/completion exits stop the device through idempotent ownership;
  discard uses NonCancellable with final PCM wiping.
- `publish(token)` checks generation and shutdown under `lifecycleGuard` for
  network, error and import publications. Group wizard proof checks now filter
  to the selected identities.

No additional critical lifecycle defect was identified in this scoped recheck.
This remains source evidence only; pending-result restoration, Android hardware
failure/cancellation and process/socket teardown still need the device scenarios
listed below. The separate Tor state-snapshot helper was not yet available for
this snapshot and is not covered by this conclusion.

## Second integration recheck

Static review of the 664-line controller snapshot, TorConnection,
MemoryAudioRecorder and SensitiveBuffer after the second integration fixes.
No Gradle command, Android execution or race test was run. Later edits may move
these lines; this section records the reviewed snapshot, not a final test result.

The serialized `discardAudio`, cleanup token ownership, guarded attachment
publication, job-completion password/media wipes, acquisition lifetime around
onion seed loading, and bounded PCM buffer address the corresponding earlier
findings. Active recording cleanup now clears its completed request while
preserving a still-outstanding permission tombstone. Export and reset wait for
the transport shutdown helper before archive/reset work.

Remaining concrete findings sent to the integration owner:

1. **Important — an old controller can kill a newer controller's Tor process.**
   `NoMessagesController.kt:653-660` launches asynchronous onCleared teardown using
   an instance-local mutex. A fresh Activity/ViewModel has another mutex and can
   unlock before that teardown executes. `TorConnection.kt:86-94` stops the
   process-wide service and kills all same-UID `:tor` processes, including the
   newly activated one. Use one application-owned runtime/controller lifetime,
   or a process-wide ownership/teardown barrier before new activation. The
   proposed application-owned ViewModelStore is a reasonable minimal solution;
   its implementation was not yet reviewed in this snapshot.

2. **Important — recorder startup failure loses its hardware owner.**
   `MemoryAudioRecorder.kt:25-26` stores an AudioRecord then calls
   `startRecording`; if that call throws, `NoMessagesController.kt:580` never
   completes its `.also` assignment, so controller cleanup cannot reach the
   recorder. The request also remains set while `audio == null`, blocking later
   recording. Make start release its local hardware/buffer on failure and clear
   the failed request; publish ownership only after successful start.

3. **Important — recorder cleanup is interruptible after ownership is lost.**
   `NoMessagesController.kt:596` clears `audio` before `discard`, while
   `MemoryAudioRecorder.kt:60-63` suspends at `cancelAndJoin` before wiping PCM.
   Cancellation there skips the wipe and leaves no controller-owned recorder
   for subsequent teardown to find. Make discard noncancellable, with finally
   cleanup for the buffer and hardware even if a join/stop/release fails. The
   read loop's error/size-limit exit at `32-39` also leaves AudioRecord started;
   release it on loop exit through synchronized/idempotent device ownership.

4. **Important — a queued bind can occur after shutdown's no-process check.**
   `TorConnection.kt:56-60` checks `stopped` before dispatching to Main, but does
   not check again inside the `bindService` block. Shutdown can mark stopped,
   finish its Main cleanup and observe no child before that delayed block binds
   the service. The later stopped callback sends STOP, but teardown has already
   reported completion and may overlap the next session. Check `!stopped` again
   in the Main binding block and keep startup/teardown under the same runtime
   owner.

5. **Moderate — a few state publications still check generation separately.**
   Tor network callbacks at `192-195` can pause after their token check, then
   update a newly unlocked session's network state after the old lock finishes.
   Put the generation comparison inside the StateFlow update transform.
   Import success at `420` similarly assigns a new UiState after a separate
   check and can overwrite lock's busy state; guard check and publication with
   the lifecycle boundary.

The proposed process-lived controller also changes request ownership: a finishing
Activity may never deliver a picker/permission result. Retaining that Activity's
attachment/export/import/audio tombstone forever would disable the corresponding
feature in the next Activity. Associate result ownership with an Activity/result
owner ID, preserve it through configuration recreation, and clear abandoned
owners only while ensuring their delayed callbacks cannot consume a newer
request. This is a constraint on the proposed lifetime change, not a claim that
the earlier Activity-scoped implementation already has that new behavior.

Recommended focused checks: old onCleared paused across a new Activity unlock;
queued bind resumed after shutdown; injected startRecording failure; cancellation
inside discard/join; PCM cap/error exit; finishing an Activity with each picker
pending; and delayed old network/import publication across lock/unlock.

## Recheck after the first integration patch

The following supersedes the original findings below where explicitly marked.
This was another static review; no build/device/test success is asserted. The
reviewed controller now has 633 lines. Production files were not modified.

The synchronous password copies, `lifecycleGuard` around activation publication,
lock idempotence, immutable camera request ID captured by `MainActivity`, pairing
rollback scope, nested session cleanup, microphone manifest permission, identity
export wipe, Tor seed job completion wipe, and bounded wipeable attachment buffer
address the corresponding original defects. Tor shutdown now confirms absence
and retries rather than ignoring its timeout. Attachment/export/import tombstones
prevent a newer request from consuming an old ActivityResult. PrivacyNotifications
constructs title-only notifications with no content text or sender metadata.

Remaining Critical/Important findings:

1. **Important — audio startup can race past lock cleanup.**
   `NoMessagesController.kt:222–224,551–558`: lock discards the recorder before
   acquiring the mutex, while permission completion constructs, starts and assigns
   a recorder under that mutex. An action already past its generation check can
   assign/start audio after lock observed `audio == null`. `closeSession` does not
   discard it. The microphone and retained recorder can therefore survive lock.
   Put recorder ownership and discard inside the same serialized boundary, and
   invalidate the request before starting hardware. Audit export/reset/onCleared
   for the same ordering. Confidence: high.

2. **Important — recording tombstones permanently disable later recording.**
   `NoMessagesController.kt:213,222,545–558`: lock preserves `audioRequest` for
   ActivityResult safety even after permission already completed and recording
   started. It discards `audio` but never clears that completed request. On the
   next unlock `startAudio` rejects because `audioRequest != null`; `stopAudio`
   cannot clear it because `audio == null`. Separate pending permission ownership
   from an active recording request, preserving tombstones only for callbacks
   still outstanding. Confidence: high; normal record → lock → unlock triggers it.

3. **Important — failed activation cleanup is not generation-owned and does
   not restore locked UI.** `NoMessagesController.kt:116–118,141–146,258–261`.
   Cleanup closes resources without replacing an already published unlocked
   UiState; a refresh failure after publication leaves `unlocked=true` with no
   session and prevents password unlock. Separately, old failed activation A can
   enter delayed cleanup while a concurrent explicit lock completes; after B
   unlocks, A closes the current globals, including B. Pass generation ownership
   into this cleanup and participate in the same guarded shutdown transition;
   stale cleanup must not touch newer resources. Publish locked state only for
   the generation whose cleanup completed. Confidence: high.

4. **Important — ordinary action publication can overwrite lock state or
   retain fresh plaintext after its wipe.** `NoMessagesController.kt:189–192,
   286,293,576–580` and other IO-side `value = value.copy(...)` writes. The
   generation check precedes the whole action; lock can occur while decryption
   runs. `openAttachment` then publishes newly decrypted bytes after lock's
   initial wipe. A read-copy-write of an unlocked UiState can also race the main
   thread's locked replacement and restore the old unlocked flag. The guarded
   `refresh().update` fixes only that one publication site. Centralize atomic,
   generation-checked publication for actions/errors/network callbacks and wipe
   attachment results rejected after decryption. Confidence: high.

5. **Important — secret ownership still has uncovered cancellation/acquisition
   paths.** `NoMessagesController.kt:104–124,133–148,393–402,458–472,536–543`
   places owned password/media wipes only in the launched coroutine body. If
   onCleared cancels the job before IO dispatch enters the body, its finally
   never runs. Apply a completion wipe as already done for the Tor seed.
   Also identity restoration and onion seed acquisition at 154–156 precede the
   activation try/finally; an onion seed database read/write failure leaks the
   newly acquired local identity/seed. Enclose acquisition in its ownership
   cleanup lifetime. MemoryAudioRecorder's growing ByteArrayOutputStream also
   still leaves old PCM backing arrays unzeroed when it grows; use a bounded
   wipeable buffer there too. Confidence: high.

Recommended focused device regressions: block AudioRecord startup while locking;
record-lock-unlock-record; inject failure after unlocked publication; delay old
activation cleanup across a newer unlock; block attachment decryption across
lock; clear a ViewModel before its queued password/media coroutine begins.

## Original review snapshot

Scope: static review of the initially written `NoMessagesController`, `TorService`,
`TorConnection`, `MemoryAudioRecorder`, `CameraCaptureScreen`, `MainActivity`, and
`UiContract`, against SPEC sections 4.3–4.4, 5.2, 7, and 9 and the development API
contracts. Supporting UI password call sites and the manifest were read to check
the integration boundaries. Line numbers below refer to the reviewed snapshot;
the integration owner may subsequently change them. No application source was
edited. No Android build, emulator, socket measurement, or runtime test was run
by this reviewer; these findings do not establish release readiness.

## Blocking findings

1. **Password ownership is incompatible with UI callers.**
   `NoMessagesController.kt:82–109,112–130,307–326,368–391` retains caller-provided
   password arrays in asynchronous work or across the document picker.
   `SetupLockScreens.kt:57–64,144–147` and `SettingsScreen.kt:250` erase those
   arrays immediately after the action returns. Consequently setup/unlock,
   import, and panic reset can consume zeroed or concurrently changing input.
   Setup copies only the later unlock password, not the passwords passed to
   create. Copy every asynchronously consumed password synchronously at the
   UiActions boundary, wipe the owned copy on every exit, and document the
   borrowing/copying contract in UiContract. Confidence: high.

2. **Activation and lock do not share an atomic generation boundary.**
   `NoMessagesController.kt:119–122,134–170,173–193`. The generation check occurs
   before activation. If screen-off/lock occurs while activation is restoring
   identity, lock can capture a null transport and null session scope; activation
   then installs a new scope/transport, sets `unlocked=true`, and starts Tor.
   The cleanup closes the database but never shuts down the newly installed Tor
   transport, because it retained the earlier transport value. Repeated lock
   cleanup jobs can also reset state or close a newer session after an earlier
   cleanup publishes `busy=false`. Serialize resource installation and teardown
   under one lifecycle owner; recheck the generation before publication and
   resource start; ensure stale activations clean up their own resources and
   stale teardown cannot mutate a newer session. Confidence: high.

3. **Camera callbacks can cross session/chat boundaries.**
   `CameraCaptureScreen.kt:59–67`, `MainActivity.kt:43`,
   `NoMessagesController.kt:437–447`. A capture callback has only the stable
   controller method reference. It reads the current `photoRequest` when the
   result arrives, rather than carrying the request that produced the image.
   Cancel/lock, unlock another vault, and start a new capture: a delayed old
   capture can consume the new request and send the old photo into the new
   vault/chat. Capture an immutable request ID plus generation in the callback;
   reject/wipe stale results without clearing newer requests. Camera disposal
   should independently invalidate callbacks. Apply the same request-specific
   ownership principle to permission/document callbacks. Confidence: high for
   the missing identity check; callback timing requires device regression tests.

4. **Partial setup/activation failures do not consistently close resources.**
   `NoMessagesController.kt:100–104` catches failures after installing `session`
   without teardown. `closeSession():197–203` can itself skip identity and
   database/key cleanup if engine or identity closure throws. Unlock's failure
   branch closes the session but does not cancel its installed session scope or
   shut down its transport. Make failed activation clean up all resources it
   owns; make each independent cleanup run using nested `finally` blocks. Do
   not publish a usable locked state until teardown has completed. Confidence:
   high for missing cleanup; which exceptional path occurs is dependency-specific.

5. **Unconfirmed Tor shutdown is silently accepted as lock completion.**
   `TorConnection.kt:80–90` can throw while waiting for Binder death, including
   its five-second timeout. `NoMessagesController.kt:184–191` ignores that failure
   and publishes the ordinary locked state. The unknown-PID bootstrap case
   relies only on queued STOP/unbind and can reach this path before a death
   confirmation. Preserve fail-closed teardown state and retry/confirm process
   death rather than reporting successful completion. Cleanup of pending
   requests/channel/scope must also run if the wait throws. Confidence: high
   for the accepted failure path; actual socket persistence must be measured.

## Important correctness findings

6. **Pairing rollback extends past its committed transaction.**
   `NoMessagesController.kt:242–258` runs `syncEvidence` after committing contact,
   pairing evidence and identity, but inside the catch that restores the old
   in-memory identity snapshot. If evidence synchronization fails (for example
   a capacity error), disk has the new contact/session while memory rolls back.
   Restrict rollback to the finish/transaction operation. Run synchronization
   after that success scope and keep its failure separate. Confidence: high.

7. **Audio permission is absent from the manifest.**
   `MainActivity.kt:57` requests `RECORD_AUDIO`, but the reviewed manifest
   declares only INTERNET and CAMERA. Add the microphone permission before
   validating the memory audio capture flow. Confidence: high.

8. **Several app-owned secret/plaintext buffers have no wipe path.**
   `NoMessagesController.kt:142` passes `localIdentity.export()` directly into the
   database without erasing the returned secret array afterward. The onion seed
   at lines 140–169 is erased only inside a coroutine that may never start if
   its scope is cancelled; earlier synchronous activation failure also bypasses
   that erasure. Attachment input at 416–426 uses an ordinary growing
   ByteArrayOutputStream whose internal plaintext buffer is never erased,
   including failed/oversized reads. Give each owned secret a synchronous
   try/finally lifetime, and use a bounded wipeable attachment buffer. This is
   distinct from the documented upstream native/immutable JVM limitations.
   Confidence: high.

## Verification needed after fixes

- Setup/unlock/import/reset through the actual Compose callers, not only direct
  manager tests, to detect buffer borrowing errors.
- Hold activation between identity restore and publication; trigger screen-off
  and repeated lock; resume it and assert no unlocked publication, live Tor
  child, socket, open database, or surviving newer-session teardown.
- Deliver an old camera callback after cancel/lock and after creating a new
  camera request in a different session; ensure bytes are wiped and the new
  request remains intact.
- Inject failures after session installation, during each close operation, and
  before Tor's HELLO/death confirmation; verify cleanup is complete or remains
  visibly pending rather than claiming a completed lock.
- Fail evidence synchronization after pairing commit and verify persisted and
  in-memory identity/session states still agree.

Positive static observations: FLAG_SECURE is installed before content;
background timeout is capped at 30 seconds; normal lock clears UI attachment
bytes and request references; Tor uses a separate non-exported process with
same-UID message checks and a process-kill teardown path; media capture paths
do not intentionally write plaintext to files. These observations do not
override the blocking races above or substitute for the SPEC device gates.
