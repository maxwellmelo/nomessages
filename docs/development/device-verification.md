# Device verification ledger

Per-scenario PASSED/FAILED/BLOCKED record for the device/emulator verification tasks in
`docs/superpowers/plans/2026-09-14-roadmap-to-release.md` (Fase 3). `release-checklist.md` remains
the authority for the 13 specification gates; this file tracks the underlying scenarios and hands-on
execution notes that feed those gates.

## T4.16 — Compact pairing QR with the key bundle fetched over Tor (2026-09-17)

> **Superseded in part by "T4.16 — run5" at the bottom of this file (same day, later session).**
> Everything below this line is the *pre-device* record and is kept verbatim as the statement of
> what was outstanding. Run5 executed most of it on two live emulators and reports the real results,
> including three defects it had to fix to get there. The one row run5 did **not** change is the
> real-camera scan on the Galaxy Note10+, which remains NOT_RUN.

**Nothing in this row was executed on a device or an emulator.** It is recorded so the follow-up
run knows exactly what is outstanding and does not have to re-derive it. Do not read any line below
as evidence of device behaviour.

Change under test: pairing QR format 2 (`nomessages:2:`), which removes the ~1832-byte PQXDH bundle
from the QR and fetches it over the peer's onion, authenticated by a SHA-256 hash inside the signed
offer. Motivation is itself a device finding, reported by the user on his own hardware: on a Galaxy
Note10+ with the real camera, the format-1 QR (~2950 bytes, QR version 40, 177 modules per side)
**could not be read at all**, even filling an entire monitor.

| Scenario | Result | Evidence | Notes |
|---|---|---|---|
| Host build gate: `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | PASSED | this session's console output | `BUILD SUCCESSFUL in 8m 35s`. `:core:test` 95 tests / 0 failures / 2 skipped; `:app:testDebugUnitTest` 60 tests / 0 failures / 1 skipped; lint **0 errors** (42 warnings, 6 hints — same baseline as T4.15). Both APKs produced. |
| QR symbol version of the format-2 payloads, measured with ZXing at EC level L | PASSED (host measurement, not a camera test) | `ProtocolTest.formatTwoQrPayloadsStayWithinTheVersionFourteenBudgetAtLevelL` | offer 405 bytes / **version 13**; response 448 bytes / **version 14**; confirmation 317 bytes / **version 11**. Version 14 is 73 modules per side, against format 1's 177. The response has 10 bytes of slack before version 15. |
| **Real-camera scan of a format-2 QR on the Galaxy Note10+** (the defect this task exists to fix) | **NOT_RUN** | — | The whole point of T4.16. Until a real camera reads a format-2 offer *and* response off a phone screen, the original defect is not demonstrated fixed — only made much smaller on paper. Highest-priority item for the follow-up run. |
| Live two-emulator pairing with the Tor bundle fetch (`BundleRequest`/`BundleResponse` over a real circuit) | **NOT_RUN** | — | No emulator was used in this session. `scripts/emulator-pair.sh` needed no change (it relays QR bytes through the `DUMP_QR`/`INJECT_QR` broadcast hook and never hard-codes the prefix), so the existing relay method should apply unchanged. |
| Tor bundle fetch inside the 300 s exchange deadline, both directions | **NOT_RUN** | — | The new `PENDING_TTL_SECONDS = 300` budget was chosen from the documented 5–40 s circuit-build cost plus margin; it has **not** been measured against a real emulator bootstrap. Emulator Tor bootstrap has historically been slow here (run2 recorded "Tor connected" on both emulators without an exact timing). If the fetch routinely needs more than 300 s in an emulator, the deadline — not the design — is what has to be revisited. |
| Automatic QR regeneration after the 120 s reading window, with the screen open | **NOT_RUN** on device | `PairingLifecycleTest` (10 JVM cases) | The decision logic is a pure state machine and is covered on the host. What is unverified is the wiring: that the screen really re-renders the new QR, that the countdown resets, and that a peer who scanned the previous QR in its last seconds still completes its fetch against the retained superseded offer. |
| UI states `WAITING_FOR_TOR` / `FETCHING` / `READY` / `FAILED` and the retry button | **NOT_RUN** | — | No screenshots were captured; screenshot capture was explicitly out of scope for this session. |
| Vault schema v2 → v3 migration (`contacts.doorbell_onion`, `contacts.doorbell_token`) | **NOT_RUN** | test written, never executed | `AndroidVaultStorageTest.doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration` exists and compiles into `app-debug-androidTest.apk`, but **no instrumented run happened**. T4.15's in-place migration path is the mechanism being reused; an upgrade-over-an-existing-v2-vault check on a real device is the meaningful gate. |
| Doorbell fields (`doorbellKey` / `doorbellToken`) | **NOT_RUN**, and **not applicable yet** | — | Reserved for T4.17; no service listens, no notification exists, no setting is shown. Only their presence in the signed offer and their storage on the contact row are implemented. Nothing about doorbell *runtime* behaviour should be gated here. |
| Gate 8 (expired/replayed QR rejected) under format 2 | **NOT_RUN** | `ProtocolTest` (host) | Host coverage exists (`formatOneQrCodesAreRejected`, the 171-case malformed-QR corpus adapted to the new prefix, replay/expiry cases). Gate 8 stays NOT_RUN as a device gate, unchanged. |

### What the follow-up run should watch for

1. **Tor bootstrap and reachability inside the deadline.** Both devices must be `READY` before a
   fetch can succeed. The UI now distinguishes "waiting for Tor" from "fetching"; if a run spends
   most of its 300 s in `WAITING_FOR_TOR`, the finding is about emulator bootstrap time, not about
   the fetch.
2. **Whether a single fetch attempt suffices.** Each attempt has a 60 s budget and there is a 3 s
   pause between attempts, retried until the exchange deadline. Record how many attempts a real
   pairing needs — that number is the honest input for tuning the deadline.
3. **The mirror-image fetch.** Both devices fetch from each other at roughly the same time. The
   engine deliberately does not hold its mutex while fetching, precisely so each can answer the
   other; a hang on both sides at once would point straight at that.
4. **The superseded-offer race.** Scan a QR in its last few seconds on purpose, let the shower's
   screen auto-refresh, and confirm the fetch still completes.
5. **A stale/unknown nonce gets silence.** A `BundleRequest` for an unknown nonce must produce no
   reply at all, not an error — worth confirming on the wire, since it is a deliberate
   information-disclosure choice.

## T3.3 — Two-device messaging and membership procedure, executed on two emulators (2026-09-15)

Executed against `docs/release-checklist.md` § "Two-device messaging and membership procedure",
steps 1-5, using two official Android 15 API 35 `x86_64` emulators (AVDs `nomessages35` and
`nomessages35b`, serials `emulator-5556`/`emulator-5560`) as devices A/B, per
`docs/superpowers/plans/2026-09-14-roadmap-to-release.md` task T3.3. **Gates 8 and 10 remain
NOT_RUN and are explicitly deferred to physical devices**, as T3.3 requires; this run's purpose was
to prove/disprove the emulator-pair method itself and execute as much of steps 1-5 as the
environment allows. Full chronological log, raw evidence (PNGs, uiautomator XML dumps) and command
transcripts: `docs/development/build-logs/two-emulator-20260915/` (see `session-log.md` there for
full detail). APK under test: `artifacts/nomessages-1.0.0-dev-debug.apk`, SHA-256
`c42b35c48202fdf731eebcddec8d044f1fffcc948632789a20220ff99169a537`, commit `ca46aba`. No app code
was modified during this session.

| Step | Result | Evidence | Notes |
|---|---|---|---|
| Environment: second AVD (`nomessages35b`) created and configured to match `nomessages35` | PASSED | `docs/development/build-logs/two-emulator-20260915/emuB-boot.log` | Cold boot 40.9 s; A's warm boot (existing AVD, restarted windowed) 26.2 s. |
| Environment: `scripts/emulator-with-qr.ps1` adapted for `-AvdName`/`-Port` and windowed mode | PASSED | `scripts/emulator-with-qr.ps1`, `docs/changes/emulator-with-qr.ps1.md` (2026-09-15 section) | Both instances booted in parallel without touching BlueStacks ports 5554/5555. |
| Environment: both emulator windows captured via GDI (`System.Drawing`/`GetWindowRect`), positioned side by side, non-overlapping | PASSED | `session-log.md` control-test screenshots | Confirms the capture pipeline itself is correct and live (home screen content changes across captures). |
| 1. Record APK hash, build logs, device serials/ABI/API | PASSED | this table; `artifacts/artifacts.json` | A: `emulator-5556`, API 35, x86_64. B: `emulator-5560`, API 35, x86_64. |
| 1. Manually prepare each test vault (distinct strong real/panic passwords, ≥16 chars) | FAILED (app bug) | `docs/development/build-logs/two-emulator-20260915/06-*.xml`, `07-*.xml`, `08-*.xml` | Vault creation fails on **both** emulators via the real Setup UI, with weak *and* with independently-generated high-entropy 24-char alphanumeric passwords (`secrets.choice`). Generic `error_create_vault` dialog after ~11-17 s. See "Bug found" below — this blocks every subsequent step of the procedure. |
| 2. Pairing QR pixel relay between the two emulators (capture A's/B's displayed QR, inject into the other's virtual camera) | BLOCKED (environment limitation, not an app defect) | `docs/development/build-logs/two-emulator-20260915/01..04-*.png` | `MainActivity`'s `FLAG_SECURE` (`MainActivity.kt:33`) blanks/freezes the captured frame in **both** `adb screencap`/`screenrecord` (even `adb root`) **and** live GDI capture of the emulator's own host window — a control test proves the same capture pipeline shows live, correct content when NoMessages is not foregrounded. No text/clipboard/log channel exposes the QR payload as an alternative. Without app-code changes (out of scope) there is no way to relay the QR pixels between two emulator instances. This is a genuine, reproducible discovery about the emulator-pair method itself, not a security defect — if anything it confirms `FLAG_SECURE` is even more effective than assumed against this class of host-side capture. |
| 2. Bilateral pairing (offer/response/SAS/confirmation) | BLOCKED | — | Depends on both the vault-creation bug (no unlocked vault to pair from) and the QR relay blocker above. |
| 3. Ten numbered messages each direction, one long message, one attachment with known SHA-256 | BLOCKED | — | Depends on an established contact (step 2). |
| 4. Offline queue (lock B, send from A, verify pending; unlock B, verify delivery resumes) | BLOCKED | — | Depends on an established contact (step 2). |
| 5. Repeat readiness/messaging/lock after app restart; QR expiry/replay and failed-SAS trials (gate 8) | BLOCKED | — | Depends on steps 2-4. Gate 8 remains NOT_RUN. |
| Tor bootstrap readiness (both onion services reach ONLINE) | BLOCKED | — | The network only activates after a successful `setup`/`unlock` (`NoMessagesController.activate`); with vault creation failing, Tor bootstrap timing could not be measured on either emulator in this session. |
| Gate 10 (lock closes network, PIDs/sockets) | NOT_RUN (deferred per plan) | — | Explicitly deferred to physical devices per T3.3; not attempted here regardless of the blockers above. |

### Bug found: vault creation fails silently from the real Setup UI (blocker)

- **Where:** `SetupScreen` → `UiActions.setup` → `NoMessagesController.setup()`
  (`app/src/main/kotlin/dev/mx3/nomessages/runtime/NoMessagesController.kt:131-168`) → `VaultManager.
  create()` (`core/src/main/kotlin/dev/mx3/nomessages/core/vault/VaultManager.kt:26-63`).
- **Repro:** Fresh install (`adb install -r`) of `artifacts/nomessages-1.0.0-dev-debug.apk` on a clean
  Android 15 x86_64 emulator (`pm clear` beforehand, or a brand-new AVD). Launch, fill in a display
  name and two distinct passwords ≥16 alphanumeric characters each for main/panic, tap "Create
  vault"/"Criar cofre". Reproduced independently on two separate AVDs (`nomessages35`, `nomessages35b`),
  three times total (once with weak/predictable 20-21 char passwords, twice with independently
  generated 24-char high-entropy alphanumeric passwords).
- **Symptom:** UI shows "Working…" for roughly 11-17 seconds, then "Could not complete — The vault
  could not be created. Use strong, distinct passwords with at least 16 characters." (`error_
  create_vault` string), regardless of password strength.
- **What was ruled out:** disk space (4.8 GiB free of 6 GiB, 22% used); password predictability
  (reproduced with 24-char `secrets.choice`-generated passwords that should score maximum on the
  app's zxcvbn check); device-specific corruption (reproduced on a second, independently created
  AVD with a fresh install); native library load failure or crash (no tombstone, no `Fatal signal`,
  `libnomessages.so` loads successfully per logcat `nativeloader`); UI-automation artifacts (confirmed
  the "Create vault" tap registers — "Working…" appears — before the failure).
- **Root cause not fully isolated:** `NoMessagesController.kt:159-161` swallows the real exception
  (`catch (_: Exception) { cleanupFailedActivation(token); error(context.getString(R.string.
  error_create_vault), token) }`) without logging it anywhere, so no stack trace reaches logcat.
  No `.vault-create-*` staging directory was ever observed under `/data/data/dev.mx3.nomessages.
  debug/files` while polling every ~1.5 s during the failure window, meaning the failure happens at
  or before `Files.createTempDirectory` in `VaultManager.create()` — most likely in
  `KdfCalibrator.calibrate()`'s native Argon2 call or `newKeys()`'s native `crypto.random()`, given
  the ~11-17 s duration matches calibration timing. `PasswordPolicy`/zxcvbn itself is exercised
  successfully by the JVM `core` test suite (`VaultTest.kt`) on the host build of `libnomessages.so`,
  and `AndroidVaultStorageTest` (T3.1, instrumented, 11/11 passed) calls `AndroidVaultStorage.
  initialize()` directly — bypassing `VaultManager.create()`, `PasswordPolicy`, and
  `KdfCalibrator` entirely — which is consistent with this defect existing on the real UI path
  without T3.1's automated suite having caught it.
- **Recommendation:** attach a JDWP debugger (Android Studio, or `jdb`/`adb forward jdwp:<pid>`
  from a shell that supports interactive stdin against a native `jdb.exe`, which Git Bash's
  `mkfifo` could not drive in this session) to `dev.mx3.nomessages.debug` and set a breakpoint/
  exception catchpoint around `VaultManager.create()` to get the real stack trace. Independently,
  add a debug/verbose log line at `NoMessagesController.kt:159-161` before discarding the exception
  (without ever logging password material) — this defect class (a completely silent failure with
  a generic message) will otherwise keep costing debugging time.
- **Severity:** blocker for T3.3 and every downstream Fase 3 task that assumes a working vault
  (T3.4 through T3.7); the app is unusable past first run until this is fixed.

### Non-blocking observations

- App UI rendered in English (`values-en`) rather than PT-BR on these AVDs (en-US locale); this is
  locale-driven, not a defect — see `session-log.md` for detail. The task's PT-BR string lookups in
  `values/strings.xml` needed to be substituted with the English equivalents actually shown.
- The "Create vault" button can end up visually covered by the on-screen keyboard right after the
  last password field is filled (`imePadding()` in `SetupScreen` did not always reserve enough
  space before the IME finished animating in this environment); dismissing the keyboard first
  avoided the issue. Not confirmed as device-independent; noted for whoever repeats this manually.
- `adb shell input keyevent KEYCODE_BACK` showed inconsistent behavior (dismiss-keyboard-only vs.
  exit-the-Setup-screen-entirely) between otherwise-identical attempts; most likely a synthetic
  key-event timing artifact interacting with Android 15's predictive back, not an app defect.

## Emulators left running

| Role | AVD | Serial | Port | State |
|---|---|---|---|---|
| A | nomessages35 | emulator-5556 | 5556 | NoMessages foregrounded, "Could not complete" vault-creation error dialog on screen |
| B | nomessages35b | emulator-5560 | 5560 | NoMessages foregrounded, "Could not complete" vault-creation error dialog on screen |

**Status update (2026-09-15, later session):** root cause confirmed via debug-only exception logging added to `NoMessagesController.kt` (see `docs/changes/NoMessagesController.kt.md`) — the real exception captured on rebuild was `IllegalArgumentException: Password must be alphanumeric` from `PasswordPolicy.validate`, showing the blocker was password-input-dependent rather than a fixed engine failure; with genuinely alphanumeric ≥16-char passwords, vault creation then succeeded cleanly on both `emulator-5556` and `emulator-5560` on a HEAD (`ca46aba`) rebuild that already includes commit `017403c` (synthetic decoy onions now use real Ed25519 public keys instead of raw random bytes) — the failing APK this table describes was built at 11:31, before that 11:59 fix landed, and `DecoyFactory`'s pre-fix ~50%-invalid-curve-point onions is the best-supported explanation for the original silent failure. No further source change to `AndroidVaultStorage.kt`, `ChatDatabase.kt`, `DecoyFactory.kt` or `KdfCalibrator`/`KdfParams.kt` was needed or made.

## T3.3 — run2: two-emulator two-device procedure, second session (2026-09-15)

Continuation of the run above, executed against the same two official Android 15 API 35 `x86_64`
emulators (A = `emulator-5556`/`nomessages35`, B = `emulator-5560`/`nomessages35b`), HEAD build at commit
`cf1b1e2` (adds the debug-only `debug.nomessages.allow_capture` screen-capture switch referenced
below and swallowed-exception debug logging). Inherited state at session start: vault created and
unlocked on both emulators, both showing "Tor connected", no paired contacts — i.e. both blockers
from the run1 table above (silent vault-creation failure, `FLAG_SECURE` blocking host-side capture)
were already resolved by prior work. Full chronological log and all raw evidence (PNGs, XML dumps,
the crop/decode helper script): `docs/development/build-logs/two-emulator-20260915/run2/`
(`session-log.md` there for narrative detail).

| Step | Result | Evidence | Notes / timing |
|---|---|---|---|
| Enable debug-only capture bypass (`setprop debug.nomessages.allow_capture 1` + `hidden_api_policy 1`) on both, restart app | PASSED | `run2/01-*-after-restart.xml` | Applied at 18:46 UTC; both emulators showed the lock screen after restart, confirming the property is read at process start as documented in `MainActivity.kt`. |
| Unlock both vaults with real passwords | PASSED | `run2/03-*-after-unlock2.xml`, `run2/04-*-home-after-unlock.xml` | Tap→"Working…"→home observed in an ~16-20 s window (18:47:24–18:47:44), consistent with the previously-documented Argon2 calibration time. First unlock-button tap on each device missed because the on-screen keyboard shifted the button's bounds upward ~410 px; retried at the keyboard-adjusted coordinates — same IME-covers-button interaction noted in the run1 table, confirmed reproducible on a fresh restart. |
| Tor bootstrap on both after unlock | PASSED | `run2/04-*-home-after-unlock.xml` (bootstrapping), later poll (untracked file, see session-log) | Both reached "Tor connected". Exact bootstrap duration not pinned down precisely — a local shell path bug (`/tmp` resolving to the wrong drive under Git Bash with `MSYS2_ARG_CONV_EXCL=*`) wasted the first ~2.5 min poll window; by the time polling worked both were already connected (first successful check at 18:51:31, i.e. ≤3 min 47 s after unlock, real time likely much shorter). |
| **QR capture via `adb screencap` with the debug bypass active, app in foreground** | **PASSED — run1 blocker (a) resolved** | raw captures not retained; see `session-log.md` | A's "Show my QR" screen captured with a fully live, correct, non-black/non-frozen frame (unlike run1's `FLAG_SECURE`-blocked captures). Cropped to a tight 719×719 square with white margin and decoded independently with `zxing-cpp`/Pillow (Python) confirming a valid `nomessages:1:...` payload, 2708 characters. Round trip (screencap → crop → verify-decode) took a few seconds interactively; a scripted version would comfortably fit inside the 120 s QR expiry window. |
| B scans A's offer QR via `adb -s <B> emu virtualscene-image wall <cropped.png>` | **FAILED (new, distinct blocker)** | raw captures not retained; `run2/16-emuB-after-scan2.xml` | First injection attempt used a relative Windows path from `cygpath -w` on a relative input, which the emulator process resolved against its own cwd (not silently failing — `virtualscene-image` returned `OK` but the poster stayed blank); fixed by passing an absolute path. With the absolute path the poster correctly showed the QR (confirmed visually via `screencap` on B), moderately skewed by the fixed camera pose the `Walk_to_image_room` automation macro leaves the scene in, but never decoded by the app's scanner even after 15-90+ s of exposure across two independent offers (one expired before decoding, forcing a re-generation on A). |
| Diagnose the scan failure (rule out QR density/skew) | Root cause **not fully isolated**; environment-level, reproducible | raw captures not retained; logcat extracts in `session-log.md` | A minimal 11-character diagnostic QR (independently verified decodable with `zxing-cpp`), framed cleanly and almost frontally inside the scanner's on-screen guide box, **also** never decoded — nor did mirrored or 90°/270°-rotated variants of it, across 4 more independent injections (7 total across both QRs). Logcat shows `CameraX`/`CXCP` binding both `Preview` and `ImageAnalysis` successfully (`StreamSpec resolution=640x480`, `Camera 10: Opened`, no errors or exceptions logged afterward), and `PreviewView` visibly reflects live, correct virtual-scene content (confirmed by distinct consecutive screenshots). This isolates the failure to *something* between the 640×480 YUV `ImageAnalysis` stream and `ImageProxy.decodeQr()` in `PairingScreen.kt` — most plausibly a virtual-camera-specific YUV buffer quirk in this emulator build, since ruling out rotation/mirroring (tested explicitly) did not fix it either. Not confirmed without app instrumentation, which was out of scope (no app code was changed). |
| 2. Bilateral pairing (offer/response/SAS/confirmation) | BLOCKED | — | Blocked by the camera-relay failure above: B never received A's offer, so `readPairing()`'s `receive()` branch was never exercised end-to-end on real devices in this session. |
| 3. Ten numbered messages each direction, one attachment | BLOCKED | — | Depends on an established contact (step 2). |
| 4. Offline queue (lock B, send from A, verify pending; unlock B, verify delivery resumes) | BLOCKED | — | Depends on an established contact (step 2). |
| 5. Repeat after restart; QR expiry/replay; failed-SAS trials (gate 8) | BLOCKED | — | Depends on steps 2-4. Gate 8 remains NOT_RUN. |
| Gate 10 (lock closes network, PIDs/sockets) | NOT_RUN (deferred per plan) | — | Explicitly deferred to physical devices per T3.3; not attempted here. |
| Cleanup: back out of pairing UI on both, confirm home screen + "Tor connected" | PASSED | `run2/24-emuA-cleanup.xml`, `run2/25-emuB-cleanup.xml`, final home dumps | Both left unlocked, on the home screen, "Tor connected", zero paired contacts, ready for a future session to resume without redoing setup. |

### New finding: camera-based QR relay between two emulator instances does not decode (blocker for the emulator-pair method)

- **What changed vs. run1:** run1 could not even get a live QR to leave the phone (`FLAG_SECURE`
  blocked host-side capture). That is now fixed (debug-only capture switch, commit `cf1b1e2`).
  This run proves the capture/crop/verify half of the relay works perfectly. The **new** failure
  is downstream: getting the peer emulator's virtual camera to feed a decodable frame to the
  app's own QR scanner (`CameraPreview`/`ImageAnalysis` in `PairingScreen.kt`).
- **Repro:** With B on the "Scan QR" screen (camera permission granted) and any QR PNG injected via
  `adb -s <B> emu virtualscene-image wall <absolute-path.png>`, the poster renders correctly and
  visibly in B's live preview (confirmed via `adb exec-out screencap -p`), but B never leaves the
  scanner screen — `ImageAnalysis`'s `analyzer` callback either never fires or `decodeQr()` never
  returns a `Result`, for 7/7 independent attempts (the real dense pairing QR, an upscaled variant
  of it, and a minimal single-module-dense diagnostic QR in its normal/mirrored/90°/270° forms).
- **What was ruled out:** QR density/version (a trivial 11-character QR failed identically to the
  2708-character pairing QR); coarse framing (the diagnostic QR sat almost frontally, fully inside
  the on-screen guide box, with far less perspective skew than the pairing QR); camera permission
  (`dumpsys package` confirms `android.permission.CAMERA: granted=true`); camera bind/crash
  (logcat shows a clean `CameraX`/`CXCP` bind of `Preview`+`ImageAnalysis` at start, camera ID 10
  opened, no subsequent errors, no `cameraFailed`/`camera_unavailable` UI state ever shown);
  `Preview` itself (confirmed live and accurate via screenshots, ruling out a fully broken virtual
  camera); simple rotation/mirror mismatch in `ImageProxy.decodeQr()` (`PairingScreen.kt:400-435`)
  as the sole cause (tested 0°, mirrored, +90°, and -90°/270° source variants — none decoded,
  though a genuine 640×480 YUV buffer corruption/stride quirk specific to the emulator's
  virtualscene backend remains the most likely unconfirmed explanation, alongside the possibility
  that `decodeQr()`'s narrow two-orientation fallback still does not match whatever the real
  buffer layout is).
- **Root cause not confirmed:** would need either an instrumented debug build (adding a temporary
  log of `ImageProxy` dimensions/format/rotationDegrees and a raw dump of one analysis frame) or a
  real second physical device to determine whether this is (1) an emulator-only virtual-camera
  defect unrelated to the app, or (2) a real `PairingScreen.kt` scanner bug that would also affect
  real devices under some camera orientation/format combination. No app code was changed to
  investigate further, per this task's scope.
- **Recommendation:** either test QR pairing on two real physical devices (where the standard
  camera path is far better validated), or — if physical devices remain unavailable — add a
  temporary debug log line in `ImageProxy.decodeQr()` (image width/height/format/rotationDegrees,
  gated the same way as the existing `debug.nomessages.allow_capture` switch) to capture what the
  virtual camera is actually delivering, then remove it before release.
- **Severity:** blocks the two-emulator method specifically for the camera-scan half of pairing;
  does not by itself indicate a defect reachable on real hardware. Downstream of this, steps 2-5 of
  the two-device procedure and gates 8/10 remain unexecuted here exactly as in run1, but for a
  different, narrower, and better-isolated reason.

## T3.3 — run3: QR scanner diagnosis with app instrumentation, code fix, and residual finding (2026-09-15)

Continuation of run2, same two emulators (A = `emulator-5556`/`nomessages35`, B = `emulator-5560`/
`nomessages35b`). This run added temporary debug instrumentation to `PairingScreen.kt` (per-frame log
+ a one-shot PGM dump of the analysis frame's Y-plane, both gated `BuildConfig.DEBUG`), rebuilt,
and used it to get ground-truth evidence instead of continuing to guess from logcat alone.

### Root cause, confirmed with evidence

1. **Primary, confirmed code bug — dead rotation fallback.** The pre-existing scanner code called
   `PlanarYUVLuminanceSource.isRotateSupported()`/`rotateCounterClockwise()` expecting them to
   rotate the frame. Disassembling this project's pinned `com.google.zxing:core:3.5.4` jar
   (`javap` on the extracted class) proves `PlanarYUVLuminanceSource` does **not** override either
   method — it only overrides `isCropSupported()`/`crop()` — so it silently inherits
   `LuminanceSource`'s defaults (`isRotateSupported()` returns `false`). The "rotation fallback"
   was dead code: it never rotated anything, ever. Live proof: a debug PGM dump of the real
   analysis frame (1280x720, `rotationDegrees=90`, `rowStride=1280`, `pixelStride=1` — the
   `ResolutionSelector` fix below was already active) showed a complete, high-contrast, correctly
   exposed QR (independently decoded from the exact same byte buffer by `zxing-cpp`/Python:
   `"HELLO-QR12"`), yet the app's own decoder returned nothing — because the frame's actual
   orientation (~-89 degrees per `zxing-cpp`'s own detection) was never attempted. Fixed in
   `QrDecoding.kt` by rotating the raw luminance buffer manually (0/90/180/270 degrees, each also
   tried inverted) instead of relying on `LuminanceSource` rotation support. See
   `docs/changes/QrDecoding.kt.md` for the full disassembly evidence and fix.
2. **Independently necessary — insufficient `ImageAnalysis` resolution.** Confirmed via a new JVM
   unit test (`QrDecodingTest.kt`) using a real version-40-L QR fixture: 640x480 gives at best
   ~2.5 px/module for this QR size (177 modules/side), well under the ~3-4 px/module reliable-decode
   threshold, even filling nearly the whole frame height. Fixed with a `ResolutionSelector`
   requesting 1920x1080 (`FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`) in `PairingScreen.kt`; on this
   emulator's virtual camera the actual negotiated resolution came back as 1280x720 (the closest
   available), confirmed live in the debug frame log. See `docs/changes/PairingScreen.kt.md` and
   `docs/development/ui-report.md` (real-phone-risk note).
3. **Residual finding, not resolved by (1) or (2) — classic ZXing detector vs. emulator
   perspective skew.** Even after both fixes, the live scan of the same well-framed, high-contrast,
   single-QR analysis frame described above still failed in classic ZXing Java across an exhaustive
   sweep tried outside the app (4 rotations x mirror x invert = 16 combinations, with and without
   `TRY_HARDER`/hints, cropped tightly to just the poster, and 2x upscaled) — while `zxing-cpp`
   decoded the identical byte buffer immediately every time. The `zxing-cpp` detection's own corner
   geometry shows a small but real projective (keystone) distortion (~1.65 degrees of
   non-parallelism between nominally opposite QR edges) consistent with the virtual-scene camera's
   fixed pose. This is a known real-world gap between the classic ZXing Java detector (used by this
   app, as required by this task's scope) and modern detectors (`zxing-cpp`, ML Kit) that handle
   projective distortion far better; it is not something rotation, inversion, or resolution can fix
   (would need homography estimation, out of scope here). **End-to-end confirmation with the real
   pairing QR:** captured live from B's own "Show my QR" screen (`adb exec-out screencap -p`,
   cropped to a clean 729x729 square, independently verified as the correct 2708-character
   `nomessages:1:...` payload via `zxing-cpp`), injected into A's virtual camera
   (`adb emu virtualscene-image wall`), scanned live through the app for ~25 s: 668 decode attempts
   logged, 0 successes, app remained fully responsive throughout (no hang, no crash, no error
   dialog — correct behavior while no match is found). **Conclusion: the real pairing QR does not
   decode end-to-end through this emulator's virtual-scene camera, even with both fixes applied.**
   This is assessed as a harness/library-detector limitation specific to the two-emulator method,
   not a defect expected to reproduce on real phone hardware (where the standard camera path is far
   better validated and the user visually aligns the QR flat-on, without a fixed synthetic
   perspective skew).

### What was ruled out this run (superseding two secondhand hypotheses raised mid-session)

- **Camera-positioning macro not reapplied / poster obscured by scene geometry (table/chair):**
  ruled out directly — a live `Preview` screencap and the debug analysis-frame PGM dump both show
  the QR fully in frame, centered in the guide box, all three finder patterns and quiet zone
  intact, not obscured by any scene object.
- **Stray second QR texture on the "table" virtual-scene object:** observed once during this
  session (self-inflicted, from an earlier diagnostic step that injected an image into `table` to
  compare framing) and cleared; in this emulator/AVD build, `wall` and `table` virtualscene targets
  appear to share the same underlying texture regardless, so this is a harness quirk worth knowing
  about for a future session, not a finding that changes the root cause above (the clean single-QR
  PGM dump used to diagnose the rotation bug was captured before `table` was ever touched).

### Tests / lint / instrumented suite (this run)

- `:app:testDebugUnitTest` — all cases pass, including the 4 new `QrDecodingTest` cases (1920x1080
  decode, 640x480 documented via `assumeTrue` with a message explaining the resolution requirement,
  90-degree-rotation recovery, and a version-40 fixture sanity check).
- `:app:lintDebug` — passes.
- Instrumented suite on `emulator-5556` (`t31-devices.sh emulator-5556`) — 12/12 passed after
  reinstalling with `-r -t` (vault preserved).

### Operational note: emulator-5560's vault was recreated this run

Mid-session, a UI-navigation mistake (`adb shell input tap` landed on "Lock now" instead of the
intended icon — B's home tab has a different icon set than A's "no paired contacts" screen) locked
B's vault. B's real password was never recorded in any doc from prior sessions (by design — see the
"never log password material" note elsewhere in this file), so it could not be recovered; B had 0
paired contacts and 0 messages, so its vault was recreated from scratch via the real Setup UI
(`pm clear` + fresh setup, new random 24-character alphanumeric main/panic passwords, not recorded
here either). This does not affect A (`emulator-5556`), whose vault and password were preserved
throughout via `-r -t` installs as required. B was left unlocked, "Tor connected", 0 paired
contacts — equivalent state to before, just with an unrecorded new password.

## T3.3 — run4: debug-only QR injection hook, full A↔B pairing, and bidirectional messaging (2026-09-15)

Continuation of run3, same two emulators (A = `emulator-5556`/`nomessages35`, B = `emulator-5560`/
`nomessages35b`, both Android 15 / API 35). Run3 ended with a hard blocker: the emulator's virtual
camera **cannot** decode the real pairing QR (668 decode attempts, 0 successes — see "Residual
finding" above), so the pairing ceremony could not be exercised at all without two physical phones
and a human operator. This run removed the camera from the loop instead of continuing to fight the
detector, and then ran the ceremony and the messaging procedure end to end.

**T3.3 is NOT complete.** Gates 8 and 10 remain NOT_RUN and deferred to physical devices; group
membership and attachments were not exercised this run. See "Still pending after run4" below.

### What was implemented

| File | Change |
|---|---|
| `app/src/main/kotlin/dev/mx3/nomessages/ui/QrScannerHooks.kt` | **new** — two `@Volatile` fields (`scanSink`, `shownPayload`) that the pairing UI publishes and only debug-only code ever invokes |
| `app/src/debug/kotlin/dev/mx3/nomessages/debug/DebugQrReceiver.kt` | **new** (and a new `src/debug/kotlin` source set) — `BroadcastReceiver` with `INJECT_QR` / `DUMP_QR` |
| `app/src/debug/AndroidManifest.xml` | `<receiver>` with `android:exported="true"` + `android:permission="android.permission.WRITE_SECURE_SETTINGS"` |
| `app/src/main/kotlin/dev/mx3/nomessages/ui/PairingScreen.kt` | two `DisposableEffect` registrations (in `PairingQr` and `QrScanner`) |
| `app/src/main/kotlin/dev/mx3/nomessages/MainActivity.kt` | `debugCaptureProperty()` generalized into `internal fun readSystemProperty(name, fallback)`; behavior unchanged |
| `scripts/emulator-pair.sh` | **new** — `relay` / `dump` / `payload` / `inject` over adb |

`app/build.gradle.kts` was **not** touched: AGP + the Kotlin Android plugin already include
`src/<buildType>/kotlin` by default, confirmed by `DebugQrReceiver.kt` compiling and the
`<receiver>` appearing in the debug merged manifest with zero build-script changes.

Per-file before/after rationale in `docs/changes/{QrScannerHooks.kt,DebugQrReceiver.kt,PairingScreen.kt,MainActivity.kt,AndroidManifest.xml,emulator-pair.sh}.md`;
the security note is in `docs/security-model.md` ("Debug-only QR injection hook (2026-09-15)").

### The UID guard as specified does not work — measured, not assumed

The requested guard was "process the broadcast only if `Binder.getCallingUid()` is
`Process.SHELL_UID` (2000) or `Process.ROOT_UID` (0)". It was implemented, a temporary probe was
built into a debug APK, and the broadcast was fired with `adb shell am broadcast`:

```
action=dev.mx3.nomessages.debug.DUMP_QR binder=10212 sent=-1 myUid=10212 shown=false sink=false
```

- `Binder.getCallingUid()` = **10212**, this app's own uid — not 2000. `onReceive` runs on a
  handler with no active binder transaction, and the API then returns the current process's uid.
- `BroadcastReceiver.getSentFromUid()` (API 34+) = **-1**; the sender must opt in with
  `BroadcastOptions.setShareIdentityEnabled(true)`, which `am broadcast` does not do.

A UID-only guard would therefore reject 100% of broadcasts while looking like protection — and
would invite a future "fix" comparing against `Process.myUid()`, which would accept everyone. The
intent ("shell/root only") was implemented with mechanisms that actually deliver it: an
OS-enforced `android:permission="android.permission.WRITE_SECURE_SETTINGS"` on the receiver plus an
explicit `debug.nomessages.allow_qr_inject=1` opt-in (writing `debug.*` is SELinux-restricted to
`shell`/`su`). The UID check is kept only to **reject** a reported sender that is not shell/root.
The probe was removed before the final build.

### Step-by-step results

| # | Step | Result | Evidence | Time |
|---|---|---|---|---|
| 1 | `:app:assembleDebug :app:assembleDebugAndroidTest` (first build with the hook) | PASS | `scratchpad/run4-assembleDebug.log` | 3m19s |
| 2 | Temporary UID probe build + measurement | Guard as specified proven non-functional (see above) | probe output quoted above; `docs/changes/DebugQrReceiver.kt.md` | 1m42s build |
| 3 | Final `:app:assembleDebug` with the three-layer guard | PASS | `scratchpad/run4-assembleDebug2.log` | 1m16s |
| 4 | Install `-r -t` on both devices (A's existing vault preserved) | PASS | `Success` on both | — |
| 5 | Guard negative control (`allow_qr_inject=0`) | `DUMP_QR` ignored, `cache/qr-shown.bin` absent, `am broadcast` still prints `result=0` | `60-release-manifest-proof.txt`, `docs/changes/DebugQrReceiver.kt.md` | — |
| 6 | Guard positive control (`allow_qr_inject=1`) | `cache/qr-shown.bin` written, 2708 bytes | same | — |
| 7 | `DUMP_QR` round-trip (`emulator-pair.sh payload`) | Exact 2708-char `nomessages:1:...` payload recovered as text | terminal output; sha256 verified device-vs-host | 1.4s |
| 8 | B's vault recreated via the real Setup UI (`pm clear` + form) | PASS, alias `TesteB` | `40-contacts-emulator-5560.xml` | ~2 min incl. retries |
| 9 | Pairing attempt 1 | SAS matched (`620569`) but session lost — automation pressed BACK on a stale IME reading and left the pairing screen | see "Automation traps" | — |
| 10 | Pairing attempt 2 | SAS matched (`209638`); A completed, **B rejected A's confirmation QR** (`Could not complete`) — confirmation QR had expired and the ordering was wrong | — | 78s (too slow) |
| 11 | **Pairing attempt 3 — full success**, both confirm SAS *before* exchanging confirmation QRs | **PASS** — both devices show "Contact verified and saved." | `30-pairing-A-final.xml`, `30-pairing-B-final.xml`, `31-pairing-timeline.txt` | **57s total** |
| 12 | SAS compared byte-for-byte (not visually) | **IDENTICAL**: `852215`, bytes `['0x38','0x35','0x32','0x32','0x31','0x35']` on both, same sha256 | `31-pairing-timeline.txt`, `20-sas-*.xml` | — |
| 13 | Contact saved on **both** sides | PASS — A: `TesteB` fp `316a b760 … 2222 13ca`; B: `TesteA` fp `08df d414 … b058 72e1` (each is the peer's fingerprint, consistent with the pairing screens) | `40-contacts-emulator-5556.xml`, `40-contacts-emulator-5560.xml` | — |
| 14 | 10 messages A → B over Tor | **10/10 delivered**, all with ✓✓ | `50-messages-a2b.txt` | min 2.3s, max 4.9s, avg 4.6s |
| 15 | 10 messages B → A over Tor (clean round `b2a-11..20`) | **10/10 delivered**, all with ✓✓ | `51-messages-b2a.txt` | min 6.0s, max 12.1s, avg 9.3s |
| 16 | Offline queue: B's vault locked, 3 messages sent from A | Queued on A with **no delivery tick** | `52-offline-queue.txt` | — |
| 17 | Offline queue: B unlocked | **3/3 delivered ~6s after unlock**; A's ticks flipped to ✓✓ | `52-offline-queue.txt` | unlock ~27s, then 6s |
| 18 | `:app:processReleaseManifest` + release/debug manifest grep | **Release grep empty**; debug grep finds the receiver (5 matches) | `60-release-manifest-proof.txt`, `62-processReleaseManifest-lintDebug.log` | 21s |
| 19 | `:app:testDebugUnitTest --rerun-tasks` | **PASS** — 34 tests, 0 failures, 1 skipped (the pre-existing documented `assumeTrue` for 640x480) | `61-testDebugUnitTest.log` | 4m02s |
| 20 | `:app:lintDebug` | **PASS** — 0 errors (45 issues, all Warning/Hint, none from the new debug source set, no `ExportedReceiver`) | `62-processReleaseManifest-lintDebug.log`, `63-lint-results-debug.txt` | — |
| 21 | Instrumented suite on `emulator-5556` (`t31-devices.sh`) | **12/12 PASS** (`OK (12 tests)`, 56.0s) | `docs/development/build-logs/android-test-emulator-5556-20260915T211646Z.log` | 56s |

Relay timings and payload sizes, every relay integrity-checked by comparing the on-device
`sha256sum` with the host-side `sha256sum` of the decoded base64 (**all matched, every time**):

| Direction | Payload | Size | Relay time |
|---|---|---|---|
| A → B | pairing offer | 2708 B | ~1.4s |
| B → A | pairing response | 2750 B | ~1.4s |
| A → B / B → A | confirmation | 314 B | ~1.5s |

### Release inaccessibility — the actual commands and output

```
$ wsl -d Ubuntu -- bash .../gradle-wsl.sh :app:processReleaseManifest :app:testDebugUnitTest :app:lintDebug --console=plain
BUILD SUCCESSFUL in 21s

$ grep -r "DebugQrReceiver\|INJECT_QR" app/build/intermediates/merged_manifest/release/
(no output, exit 1)

$ grep -rc "WRITE_SECURE_SETTINGS" app/build/intermediates/merged_manifest/release/
app/build/.../release/processReleaseMainManifest/AndroidManifest.xml:0

$ grep -rc "DebugQrReceiver\|INJECT_QR" app/build/intermediates/merged_manifest/debug/
app/build/.../debug/processDebugMainManifest/AndroidManifest.xml:5

$ grep -rn "scanSink?.invoke\|shownPayload?.invoke" app/src/main/ | grep -vE ':[0-9]+: *(\*|//)'
(no output — no non-comment line in src/main invokes either hook)

$ grep -rn "QrScannerHooks\." app/src/debug/ | grep -vE ':[0-9]+: *(\*|//)'
DebugQrReceiver.kt:106:            QrScannerHooks.scanSink?.invoke(payload)
DebugQrReceiver.kt:120:        val payload = QrScannerHooks.shownPayload?.invoke()
```

Full output: `docs/development/build-logs/two-emulator-20260915/run3/60-release-manifest-proof.txt`.

### Protocol behavior learned this run (not a defect — worth recording)

**Both peers must confirm the SAS before either confirmation QR is exchanged.** Delivering a
confirmation QR to a device that has not yet tapped "The codes match" makes the app reject the
payload with "Could not complete". Attempt 2 failed exactly this way. Attempt 3 confirmed on both
sides first, then exchanged confirmation QRs, and succeeded.

**The 2-minute QR expiry is the real budget for the whole ceremony.** The relay itself costs ~1.4s;
everything else is UI navigation. Attempt 2 spent 78s and the confirmation QR expired mid-flow.
Attempt 3, with the confirmations reordered and the waits tightened, finished in 57s.

### Duplicate "a2b-01" in the conversation — **duplicata: causa = automação**

Both devices show `a2b-01` twice. This is **not** an app defect. Evidence:

1. **The automation sent it twice, by design of my own test sequence.** `msg-test.py a2b 1` was run
   first as a one-message calibration (to learn the Send control), sending `a2b-01`; the full
   `msg-test.py a2b 10` run then sent `a2b-01` again as its first message. Two independent user
   sends, ~seconds apart.
2. **The timing log corroborates it.** In the 10-message run, `a2b-01` measured **2.3s** while every
   other message measured 4.7–4.9s. That outlier is the detector matching the bubble already on
   screen from the calibration run, not a faster delivery.
3. **Both rows carry their own ✓✓ on the sender** and their own timestamp row — two acknowledged
   deliveries, not one message rendered twice.
4. **The outbox cannot dedupe this, and should not.** `MessagingEngine.queue()` calls
   `db.putPendingFrame(PendingFrame("$messageId:$peer", …))`, and `ChatDatabase.putPendingFrame`
   dedupes with `INSERT INTO outbox(id,…) ON CONFLICT(id) DO UPDATE` — keyed on the *message id*.
   That collapses a **re-queue of the same message** (a retry after a lost ACK), which is exactly
   the case it must handle. Two separate user sends produce two different message ids, hence two
   outbox rows and two delivered messages. There is no content-level dedupe, and adding one would
   be wrong (a user may legitimately send the same text twice).

Conclusion: automation artifact. No code change made or needed.

Related automation artifacts left in the conversation for the same reason: two `audio.wav` messages
(an automation tap landed on "Record audio" — the same button is "Send" only once the input field
is non-empty) and two diagnostic messages `manual-2` / `manual-3`. The app offers no message
deletion UI, so they remain in the TesteA↔TesteB chat.

### Tor banner "Publishing the onion address — cannot receive yet." on A

Observed transiently on **both** devices; it always returned to "Tor connected" on its own, and was
never stuck. Timeline from this run's UI polls (local device clock, ~20s poll resolution):

| Time | Device | Observation |
|---|---|---|
| ~20:31 | B | Right after the vault was created: "Publishing the onion address — cannot receive yet." |
| ~20:32 | B | "Tor connected" — self-recovered within ~40s (2 poll cycles), no intervention |
| ~20:44 | A | On opening the chat screen: "Publishing…" |
| ~20:46 | A | "Tor connected" — self-recovered within ~20–40s, no intervention |
| ~21:07 | A | "Publishing…" again, observed mid-run (screenshot of the user's at 17:52 local shows this same state) |
| 21:09 | A | "Tor connected" (full-screen screenshot) — self-recovered within ~2 min, no intervention |
| 21:11–21:18 | A and B | "Tor connected" continuously through the clean B→A round and the offline-queue test |

**Never stuck; always recovered without intervention.** The banner reflects A's *own* onion-service
publication, i.e. its ability to **receive**; A could still **send** while it was displayed (all 10
A→B messages were acknowledged). Messages inbound to A during a PUBLISHING window were not lost —
the earlier `b2a-01..07` batch all show ✓✓ on B after the fact. The app writes no Tor state to
logcat (by design, no metadata leakage), so these UI observations are the only available evidence;
this is expected behavior under the real-run scope of T2.1/T2.3 and is recorded here, not treated
as a new defect.

### Automation traps found this run (harness, not app — but they cost real time)

1. **The soft keyboard steals taps.** With the IME open, `input tap` at a y below the keyboard top
   lands on a *key* and inserts a character into the focused field, while `uiautomator dump` still
   reports the app's full-height layout. This silently corrupted the vault-setup password fields
   (24 chars became 25, 48, 97) and made "Create vault" taps type instead of click. Fixed by
   disabling every IME (`adb shell ime disable …`) — `input text` injects key events directly and
   keeps working without an IME.
2. **`BACK` to dismiss the keyboard destroyed a pairing session.** A stale `mInputShown=true` from
   `dumpsys input_method` made the helper press BACK when no keyboard was up, which navigated away
   from the pairing screen and cancelled the in-flight pairing (attempt 1). Never use BACK as a
   keyboard dismissal in this flow.
3. **The Send button sits inside the gesture-navigation strip.** Its center (y=2319 on a 1080x2400
   screen) is within the bottom gesture inset; taps there were sometimes consumed as a navigation
   gesture (the app was pushed to background, `TO_BACK` visible in logcat). Tapping ~12px below the
   button's top edge is stable.
4. **The chat list renders newest-first and does not auto-scroll**, so an arriving message lands
   *outside* the viewport once the conversation has history. This produced a full round of false
   "NOT DELIVERED" readings for messages that had in fact been delivered (✓✓ on the sender). The
   measurement loop now scrolls to the top before each read. (Message ordering itself is being
   addressed separately by another agent in `ChatScreen.kt`, which this run did not modify.)
5. **Two concurrent `uiautomator dump` calls crash the tool**
   (`IllegalStateException: UiAutomationService … already registered!`) and return an *empty* dump,
   which looks exactly like a dead app. Do not poll a device from two processes at once.
6. **Emulator screen sleep silently swallows input.** `input tap`/`input text` go nowhere while the
   display is off, but `uiautomator dump` still works — so the automation appears to run and
   nothing happens. Mitigated with `svc power stayon true` + a long `screen_off_timeout`.

### Re-verification after the `reverseLayout` chat-ordering commit (ac91ef5)

The chat-ordering fix (`ChatScreen.kt` + `UiLogic.kt`, `reverseLayout = true`) landed from a
separate agent after the run4 measurements. Everything above was re-checked against that tree:

| Check | Result |
|---|---|
| `:app:processReleaseManifest :app:processDebugManifest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | BUILD SUCCESSFUL (1m56s) |
| `grep -rnE "DebugQrReceiver\|INJECT_QR\|DUMP_QR"` over `merged_manifest/release/` | **absent** (exit 1) |
| Same grep over `merged_manifest/debug/` | **present** — `AndroidManifest.xml:69 android:name="dev.mx3.nomessages.debug.DebugQrReceiver"`, `:74 <action android:name="dev.mx3.nomessages.debug.INJECT_QR" />`, `:75 <action android:name="dev.mx3.nomessages.debug.DUMP_QR" />` |
| `:app:testDebugUnitTest` | 34 tests, 0 failures, 1 skipped — now including the new `scrollIndexForNewestMessage` cases |
| `:app:lintDebug` | 0 errors (43 Warning, 1 Hint) |
| Reinstall `-r -t` on both emulators | Success on both; **both vaults and both paired contacts survived** |
| Chat ordering on screen | **Fixed** — newest message (`off-03`) now renders at the bottom next to the composer, older messages scroll up, and the view opens anchored at the newest |

Evidence: `64-release-proof-rerun.txt`, `72-chat-order-*.xml`, `73-chat-order-*.png`.

Note for the record: the run4 message-delivery timings above were measured **before** this fix, with
a scroll-to-top workaround in the harness. The delivery results themselves are unaffected (they were
confirmed by the sender's ✓✓ and by the receiver's own rows), but a future measurement run no longer
needs that workaround.

### Still pending after run4

- **Gates 8 and 10: NOT_RUN**, still deferred to physical devices per the T3.3 plan.
- **Group membership (complete-clique / MLS) not exercised** on two devices.
- **Attachments not exercised** (explicitly out of scope for this round).
- **Real-camera pairing still unverified.** The camera path remains blocked on these emulators by
  the run3 residual finding; this run bypassed the camera rather than fixing it. Pairing through an
  actual camera still needs two physical phones.
- **Message ordering / auto-scroll in the chat UI** is a real usability defect observed here
  (newest at the top, no auto-scroll); being handled separately.
- The two `audio.wav` and two `manual-*` messages, plus the duplicated `a2b-01`, remain in the
  TesteA↔TesteB conversation as automation residue (no deletion UI).
- `debug.nomessages.allow_qr_inject` does not survive an emulator reboot; `scripts/emulator-pair.sh`
  reapplies it automatically, but any other tooling must do the same.

## Emulators left running (updated after run4)

| Role | AVD | Serial | Port | State |
|---|---|---|---|---|
| A | nomessages35 | emulator-5556 | 5556 | NoMessages foregrounded, chat with `TesteB` open, vault unlocked (**original real password preserved** throughout via `-r -t` installs), "Tor connected", **1 paired contact (`TesteB`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, every soft IME disabled (`ime disable`), `svc power stayon true`, run4 build installed |
| B | nomessages35b | emulator-5560 | 5560 | NoMessages foregrounded, chat with `TesteA` open, vault **recreated this run** via the real Setup UI (alias `TesteB`; password held by the orchestrator, deliberately not recorded here), "Tor connected", **1 paired contact (`TesteA`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, every soft IME disabled, `svc power stayon true`, run4 build installed |

Both devices had every soft input method disabled with `adb shell ime disable …` so that automated
taps stop landing on the on-screen keyboard (see "Automation traps" under run4). `input text` still
works without an IME. To restore normal manual use:
`adb -s <serial> shell ime enable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`.

`debug.nomessages.allow_qr_inject` does not survive a reboot; `scripts/emulator-pair.sh` reapplies it.

### Previous state (after run3, superseded)

#### Emulators left running (as recorded after run3)

| Role | AVD | Serial | Port | State |
|---|---|---|---|---|
| A | nomessages35 | emulator-5556 | 5556 | NoMessages foregrounded, home screen, vault unlocked (original real password preserved), "Tor connected", 0 paired contacts, `debug.nomessages.allow_capture=1`, HEAD build with resolution+rotation fixes installed |
| B | nomessages35b | emulator-5560 | 5560 | NoMessages foregrounded, home screen, vault **recreated this run** (new, unrecorded password), "Tor connected", 0 paired contacts, `debug.nomessages.allow_capture=1`, HEAD build with resolution+rotation fixes installed |

## T4.16 — run5: validação ao vivo do QR compacto com busca do bundle por Tor, em dois emuladores (2026-09-17)

Primeira execução em aparelho do formato 2 de pareamento (`nomessages:2:`). A linha "T4.16" no topo
deste arquivo registrava **tudo** como NOT_RUN; esta seção é a execução que ela pedia. Mesmos dois
emuladores de T3.3 (A = `emulator-5556`/`nomessages35`, B = `emulator-5560`/`nomessages35b`, Android
15 / API 35 / x86_64). O aparelho físico `RX8MA0GD9ZY` (Galaxy Note10+) **não recebeu nenhum comando
nesta sessão**.

Log cronológico completo, com horários UTC exatos, comandos e medições:
`docs/development/build-logs/pairing-v2-20260917/session-log.md`. Todas as evidências (PNGs, XML,
logcat) estão no mesmo diretório.

**Objetivo:** provar, ou desprovar, que o pareamento formato 2 funciona de ponta a ponta entre dois
aparelhos reais em execução — incluindo a busca do pacote de chaves pela rede Tor, que era o custo
nunca medido do desenho — e medir quanto essa busca custa de fato.

**Resultado resumido:** o fluxo **funciona de ponta a ponta**, e as buscas por Tor custaram **menos
de 10 s em cada sentido**, contra um orçamento de 300 s. Chegar lá exigiu corrigir **três defeitos
reais**, dois deles bloqueadores absolutos que nenhum gate de host podia ter pego. **O gate da
câmera real do Note10+ continua NOT_RUN**, e ele é a razão de origem de T4.16.

### Gates

| Cenário | Resultado | Evidência | Notas |
|---|---|---|---|
| Rebuild `:app:assembleDebug` + `:app:assembleDebugAndroidTest` | PASSED | console desta sessão | `BUILD SUCCESSFUL`. Primeira tentativa abortou por tradução de caminho do MSYS (`/mnt/c/...` para `C:/Program Files/Git/mnt/c/...`) ao chamar `wsl` pela ferramenta Bash; pelo PowerShell funciona. |
| Suíte instrumentada em `emulator-5556`, 1a execução | **FAILED — 15 falhas de 19** | `build-logs/android-test-emulator-5556-20260917T211600Z.log` | `IllegalArgumentException: Invalid doorbell identity key`. **Defeito 1**, abaixo. |
| Suíte instrumentada em `emulator-5556`, 2a execução | **FAILED — 1 falha de 19** | `build-logs/android-test-emulator-5556-20260917T212235Z.log` | `SQLiteException: duplicate column name: doorbell_onion`. **Defeito 2**, abaixo. |
| **Suíte instrumentada em `emulator-5556`, 3a execução** | **PASSED — `OK (19 tests)`, 60,3 s** | `build-logs/android-test-emulator-5556-20260917T212519Z.log` | Os 19 nomes foram conferidos **no corpo do log**, não só na linha de resumo. São 19 e não 18 porque o caso novo `doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration` entrou com T4.16. |
| Migração de esquema v2 para v3 em aparelho (`contacts.doorbell_onion` / `doorbell_token`) | **PASSED** | `doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration` no log acima | Antes NOT_RUN. |
| Migração **v1 para v3 in-place**, sobre um banco com dados | **PASSED** | `vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened` | Só passou depois da correção da fixture (defeito 2). |
| Criação de cofre pela UI real de Setup em B (`pm clear` + formulário) | **PASSED**, alias `TesteB` | `03-B-home-fresh-vault.png` | ~60-70 s de "Working…". **Antes da correção do defeito 1 isto era impossível em qualquer aparelho.** |
| Bootstrap do Tor | **PASSED** | dumps de UI | B (cofre novo) **<= 40 s**; A (destrancamento) **<= 23 s**. |
| **Pareamento ao vivo A-B com busca do bundle por Tor** | **PASSED na 2a tentativa** | `session-log.md` secao 21:44:31 | 1a tentativa falhou (**defeito 3**, abaixo). Cerimônia completa em **3 min 04 s**, quase toda latência da automação. |
| **Busca do bundle por Tor dentro do prazo de 300 s, nos dois sentidos** | **PASSED, com folga enorme** | `session-log.md`, secao "Medidas de Tor" | **B recebe de A: <= 9,8 s** (e <= 6,5 s na 1a tentativa). **A recebe de B: <= 8,5 s.** Todos são **limites superiores**. Uma única tentativa bastou nas três medições; `WAITING_FOR_TOR` nunca foi observado em amostra nenhuma. Usou-se **cerca de 3% do orçamento**. |
| SAS idêntico nos dois aparelhos | **PASSED** | `08-A-sas-screen.png`, dumps | `355284` nos dois. |
| Contato salvo nos dois lados, com a impressão digital do par | **PASSED** | `09-A-contact-saved.png`, `10-B-contact-saved.png` | A vê `TesteB` fp `7f8b c646 … 6bc2 a4cd`; B vê `TesteA` fp `e3fc 4e3d … 67f4 5c2f`. `e3fc4e3d…` bate byte a byte com o campo `ed` decodificado da oferta de A — conferência independente, não visual. |
| Uma mensagem entregue em **cada** direção | **PASSED** | `11-A-chat-both-messages.png`, `12-B-chat-both-messages.png` | `a2b-t416-01` (dois ticks em ~2 s) e `b2a-t416-01` (dois ticks em ~3 s). Repetido no build final: `a2b-t416-02`, `b2a-t416-02`. |
| QR visivelmente menos denso | **PASSED — medido, não olhado** | `04-A-offer-qr-format2.png` | **69 módulos por lado = versão 13**, medido em Python/PIL sobre a captura real (módulo de 9,86 px num render de 678 px). Contra **177 módulos** do formato 1: o módulo renderizado é **2,6x maior**. Tamanhos de carga medidos no aparelho pelo `emulator-pair.sh`: **oferta 405 B, resposta 448 B, confirmação 317 B**, contra 2708/2750/314 B em run4. |
| Regeneração automática do QR após a janela de 120 s | **PASSED no motor, FAILED na UI, corrigido e reverificado** | `05-A-offer-expired-label.png`, `15-A-countdown-after-regeneration-fixed.png` | **Defeito 4**, abaixo. |
| Estados de UI `WAITING_FOR_TOR` / `FETCHING` / `READY` / `FAILED` e botão de repetir | **PARCIAL** | `08-A-sas-screen.png`, `06-A-could-not-complete.png` | `READY` ("Key bundle received and verified.") observado nos dois aparelhos e capturado. `WAITING_FOR_TOR` e `FETCHING` **nunca foram observados**: a busca terminou antes da primeira amostragem possível. `FAILED` e o botão de repetir **não foram exercitados** — a falha da 1a tentativa foi de correspondência de oferta, não de busca. |
| **Leitura de um QR formato 2 pela câmera real do Galaxy Note10+** | **NOT_RUN** | — | **O defeito que originou T4.16.** Proibido tocar em `RX8MA0GD9ZY` nesta sessão, e nada foi tocado. Os 69 módulos/lado tornam a leitura *plausível*, mas plausível não é demonstrado. **Item de maior prioridade remanescente.** |
| Corrida da oferta substituída (escanear o QR nos últimos segundos e deixar a tela renovar) | **FAILED — lacuna real de projeto** | `session-log.md`, secao 21:34:49 | **Defeito 3**, abaixo. Exatamente o cenário no. 4 que a seção "What the follow-up run should watch for" mandava testar. |
| Nonce desconhecido/velho recebe silêncio | **NOT_RUN** | — | Exigiria instrumentar o transporte; fora do alcance desta sessão. |
| Campos de doorbell (`doorbellKey` / `doorbellToken`) | **PARCIAL** | oferta decodificada em `session-log.md`, secao 21:42:36 | Presentes, não nulos e assinados na oferta real de um aparelho (`f7`/`f8`, 32 B cada). O runtime continua reservado para T4.17; nada escuta. |
| Gate 8 (QR expirado/repetido recusado) | **NOT_RUN como gate de aparelho** | `ProtocolTest` (host) | Inalterado. Observação lateral: a 1a tentativa de pareamento **de fato** exercitou a recusa de uma resposta fora do prazo — o app recusou, como devia. |
| Gate 10 | NOT_RUN (adiado por plano) | — | Inalterado. |

### Defeito 1 (BLOQUEADOR, corrigido) — nenhum cofre podia ser criado em aparelho nenhum

`DecoyFactory.pair` zerava as duas chaves de doorbell **no `finally` do `try` que só constrói os
`PairingEngine`**:

```kotlin
try {
    local = PairingEngine(crypto, localIdentity, localOnion, localDoorbell)
    remote = PairingEngine(crypto, syntheticPeer, syntheticOnion(), remoteDoorbell)
} finally { crypto.wipe(localDoorbell); crypto.wipe(remoteDoorbell) }
```

`PairingEngine` guarda `doorbellKey:ByteArray` **por referência** e só a lê depois, dentro de
`createOffer()`. O `wipe` zerava o array antes disso, a oferta publicava 32 bytes nulos, e
`Offer.decode` — que corretamente recusa uma identidade de doorbell toda zero (`Pairing.kt:356`) —
lançava `IllegalArgumentException: Invalid doorbell identity key` a partir de `respond()`.

O caminho atingido é `VaultManager.create()` para `AndroidVaultStorage.initialize()` para
`seedDecoy()`, ou seja **toda criação de cofre falhava**, em qualquer aparelho, no app inteiro — não
apenas em teste. Nenhum gate de host podia pegar isso: `DecoyFactory` só é exercitada pela suíte
instrumentada.

Correção: as chaves vão direto ao construtor e **não** são zeradas. Não se perde nada — é uma chave
**pública** Ed25519 cuja metade privada `syntheticOnionKey()` já descartou, e ela é gravada na linha
do contato poucas linhas abaixo como `doorbellOnion`. Detalhe em `docs/changes/DecoyFactory.kt.md`.

**Recomendação não aplicada (deliberadamente, para não mexer em `core` alheio):** fazer
`PairingEngine` guardar `doorbellKey.copyOf()` em vez do array do chamador. Elimina a classe inteira
de uso-após-wipe; hoje o contrato "o chamador não pode zerar esse array" não está escrito em lugar
nenhum e não é verificável.

### Defeito 2 (corrigido) — a fixture de rebobinar para a v1 não desfazia a v3

`AndroidVaultStorageTest.downgradeToSchemaVersionOne` reconstruía `messages` sem `forwarded`
(desfazendo a v2) e rebobinava `PRAGMA user_version=1`, mas **não** removia as duas colunas que o
passo v3 acrescenta em `contacts`. O banco resultante não era um banco v1 de verdade, e o replay
v1-v2-v3 batia em `duplicate column name: doorbell_onion` no passo v3.

É defeito **de fixture**, não de produção: um banco v1 real no campo não tem essas colunas, e o
`migrateStep` está correto. A fixture agora reconstrói também `contacts` na forma v1 (cópia literal
do DDL de `createSchemaV1`), com 8 páginas de reserva liberadas em vez de 4.

**Lição para a próxima versão de esquema:** toda coluna que uma versão futura acrescenta tem de ser
desfeita nesse helper, ou o caso falha por um motivo de fixture que se parece exatamente com um
defeito de migração. O comentário do helper agora diz isso explicitamente.

### Defeito 3 (NÃO corrigido) — a oferta regenerada invalida a resposta que já estava a caminho

> **Corrigido depois e validado ao vivo em "T4.16 — run6", no fim deste arquivo.** O texto abaixo é
> mantido como o registro de quando o defeito foi encontrado. Run6 confirmou a aceitação da oferta
> antiga em aparelho, duas vezes, **e** descobriu um limite de orçamento que a correção não cobre
> (com duas rotações sobra menos de 1 minuto para a fase humana) — ver "Gate estrutural novo" lá.

`PairingEngine.processResponse` (`Pairing.kt:146-158`) exige, simultaneamente, que:

1. a oferta **local corrente** (`offer`, nunca `superseded`) tenha menos de `OFFER_TTL_SECONDS`
   (120 s); **e**
2. a resposta do par responda ao digest **dessa** oferta; **e**
3. a própria resposta tenha menos de 120 s (`readOffer` chama `fresh`).

Mas a regeneração automática de QR — introduzida por T4.16 — troca `offer` por uma oferta nova a
cada 120 s e joga a anterior em `superseded`. A resposta do par responde ao digest da oferta
**antiga**. `bundleFor()` **consulta** `superseded` (linhas 199-208), então a busca do bundle
sobrevive à troca; `processResponse` **não consulta**, então o QR de resposta — o passo seguinte do
mesmo fluxo — não sobrevive.

Resultado observado às 21:38:44: `Could not complete` genérico em A, com a resposta de B a 175 s de
idade e A já na sua segunda oferta.

Isso torna visível uma incoerência de prazos que também é de projeto: depois do staging o usuário vê
um relógio de **300 s** (`PENDING_TTL_SECONDS`; B mostrava "Expires in 04:02"), mas a janela real
para *entregar o QR de resposta* continua sendo de **120 s** a partir da criação de cada oferta. Os
dois números discordam, e a discordância se manifesta como um erro genérico.

Agravante operacional: **o app não escreve nada em logcat** durante a falha (por projeto, para não
vazar metadados), então a análise acima veio do código e da linha do tempo, não de um log. Em campo
não há como diagnosticar isso.

Na prática, nesta sessão, a 1a tentativa também foi lenta por culpa do harness (gastei cerca de
2,5 min amostrando B entre os dois relays). Com a cerimônia inteira dentro de 120 s, a 2a tentativa
passou sem tocar nesse caminho. **Não foi corrigido** por ser uma decisão de projeto do motor de
pareamento, não um descuido: quem escolher entre "`processResponse` também aceita `superseded`",
"a resposta ganha o mesmo prazo de 300 s" ou "a contagem de 300 s não deve ser mostrada enquanto a
janela real for de 120 s" precisa fazê-lo no desenho, não em uma correção de passagem.

### Defeito 4 (corrigido e reverificado ao vivo) — a contagem regressiva travava em "expirado"

Depois da primeira expiração o rótulo ficava permanentemente `"Expires in expired"`, **mesmo com o
QR sendo regenerado corretamente a cada 120 s**. Provado às 21:42:36: o QR na tela de A decodificava
para uma oferta criada **15 s antes**, sob um rótulo dizendo "expirado".

Causa: `ExpiryLabel` (`PairingScreen.kt`) usava
`produceState(initialValue = remainingMillis(expiresAt), expiresAt) { while (value > 0) { … } }`.
`produceState` usa `initialValue` **uma vez só**, na primeira composição; ao mudar a chave ele
**relança o bloco preservando `value`**. Na primeira expiração `value` chega a 0 e, na relança,
`while (value > 0)` nem entra. O motor e `PairingLifecycle` estavam corretos — `PairingLifecycleTest`
cobre a lógica pura e passa; o defeito está no acoplamento Compose, fora do alcance de todo teste de
host.

Correção: re-semear `value = remainingMillis(expiresAt)` no início do bloco.

Verificado ao vivo em A com o build corrigido (`install -r -t`, cofre preservado):

```
22:00:00Z  Expires in 00:02
22:00:03Z  Expires in 01:59   + "The QR expired and a new one was generated…"
22:00:47Z  Expires in 01:15
```

Impacto antes da correção: era **cosmético no protocolo, grave na usabilidade** — o usuário via
"expirado" sob um QR perfeitamente válido, que é exatamente o contrário do que a regeneração
automática existe para comunicar.

### Armadilhas de automação novas (somam-se às de run4)

1. `wsl -d Ubuntu -- bash /mnt/c/...` pela ferramenta Bash quebra: o MSYS traduz o caminho para
   `C:/Program Files/Git/mnt/c/...`. Use PowerShell ou `MSYS_NO_PATHCONV=1`.
2. **`pm clear` reabilita o IME de voz do Google.** Redesabilite após todo `pm clear`.
3. **O IME de voz voltou sozinho em A depois de reinstalações**, reproduzindo a armadilha no. 1 de
   run4: `uiautomator dump` reporta o layout de tela cheia enquanto o IME cobre a metade de baixo, e
   o toque em "The codes match" simplesmente não acontece. Confira
   `dumpsys input_method | grep mInputShown` antes de culpar o app; desabilite o IME, **nunca**
   pressione BACK.
4. **`adb pull` de `/sdcard/...` pelo Git Bash pode falhar silenciosamente** e deixar no lugar um
   arquivo **antigo**, que parece um dump válido de uma tela que já não existe (li um dump de run4
   sem perceber). Use `adb exec-out cat`, e valide que o `uiautomator dump` imprimiu "dumped to".
5. **O formulário de Setup se reorganiza enquanto é preenchido** (mensagens de força de senha
   empurram os campos). Releia as coordenadas entre cada campo.
6. **Capture o QR de confirmação com `dump` antes de o aparelho trocar para o scanner** — ao entrar
   no scanner o QR mostrado deixa de existir para o hook `DUMP_QR`.

### Sobre o estado de `emulator-5556` encontrado nesta sessão

A tabela "Emulators left running (updated after run4)" acima descreve A com **1 contato pareado
(`TesteB`)**. **Isso já não valia no início desta sessão:** A abriu com o cofre trancado e, uma vez
destrancado com a senha real do usuário, mostrava **"No paired contacts"** e "No chats yet" — zero
contatos, exatamente como o plano desta tarefa previa. O cofre de A foi evidentemente recriado por
alguma sessão entre run4 e hoje. **Nada foi feito a respeito**: nenhuma ação destrutiva em A em
momento algum, nenhum `pm clear`, apenas `install -r -t` e destrancamento com a senha existente. A
discrepância é da documentação antiga, não uma anomalia encontrada agora, e fica registrada aqui.

### Ainda pendente depois de run5

- **Leitura por câmera real no Galaxy Note10+ (oferta *e* resposta): NOT_RUN.** É o defeito que
  originou T4.16 e o único item que impede marcar a tarefa como feita. Nenhuma medida de host e
  nenhum emulador substitui isso.
- **Defeito 3 em aberto** (oferta regenerada invalida a resposta em trânsito; prazos de 120 s e
  300 s discordam na UI). Precisa de decisão de projeto.
- **`WAITING_FOR_TOR`, `FETCHING`, `FAILED` e o botão "tentar de novo" nunca foram vistos na tela** —
  a busca sempre terminou rápido demais. Para exercitá-los seria preciso degradar a rede de
  propósito.
- **Nonce desconhecido/velho recebendo silêncio: NOT_RUN** (exigiria instrumentar o transporte).
- `PairingEngine` continua guardando `doorbellKey` por referência; a recomendação de `copyOf()`
  defensivo não foi aplicada.
- Gates 8 e 10 seguem NOT_RUN como gates de aparelho, inalterados.
- Grupos e anexos não foram exercitados nesta sessão.

### Emuladores deixados em execução (atualizado após run5)

| Papel | AVD | Serial | Porta | Estado |
|---|---|---|---|---|
| A | nomessages35 | emulator-5556 | 5556 | app em primeiro plano, **conversa com `TesteB` aberta**, cofre **destrancado** com a senha real original (preservada o tempo todo por `install -r -t`; **nunca** `pm clear`), "Tor connected", **1 contato pareado (`TesteB`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build final desta sessão instalado |
| B | nomessages35b | emulator-5560 | 5560 | app em primeiro plano, **conversa com `TesteA` aberta**, cofre **recriado nesta sessão** pela UI real (alias `TesteB`, senha principal `EncaminharTesteGamma03`, pânico `PanicoTesteGamma04`), "Tor connected", **1 contato pareado (`TesteA`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build final instalado |

O aparelho físico `RX8MA0GD9ZY` (Galaxy Note10+) **não recebeu nenhum comando desta sessão**.

## T4.16 — run6: validação ao vivo da correção de sobreposição de ofertas (2026-09-17)

Sessão seguinte a run5, mesmos dois emuladores (A = `emulator-5556`, B = `emulator-5560`, Android 15
/ API 35 / x86_64). Objetivo único: exercitar em aparelho a correção do **defeito 3 de run5** —
`processResponse` recusava uma resposta ligada a uma oferta que a tela já havia substituído.

O aparelho físico `RX8MA0GD9ZY` (Galaxy Note10+) **não recebeu nenhum comando nesta sessão**.

Log cronológico completo, com horários UTC, hashes de cada oferta e todas as medições:
`docs/development/build-logs/pairing-v2-20260917-run6/session-log.md`.

**O que mudou no código entre run5 e run6** (feito por quem mantém `Pairing.kt`, não por esta
sessão): a oferta corrente + uma substituída (2 posições) virou um conjunto propriamente delimitado,
`emitted:MutableList<LocalOffer>`, podado pelo prazo próprio de cada entrada e limitado a
`MAX_LIVE_OFFERS = 3` apenas como rede de segurança; o descarte das ofertas não usadas passou a
acontecer no staging. `PairingEngine` também passou a guardar `doorbellKey.copyOf()` — a recomendação
deixada em aberto no fim de run5. Na UI, a contagem regressiva não montada passou a ler
"New QR in MM:SS", deixou de ficar vermelha ao zerar, e ganhou a legenda fixa "Anyone who already
scanned the previous QR can still finish pairing."; o aviso antigo de "peça para escanear de novo"
foi removido por ter virado conselho errado.

### Resultado resumido

**A correção funciona**, comprovada duas vezes de forma independente. **E a sessão descobriu um
limite estrutural novo**, que só aparece em aparelho: com duas rotações, o pareamento aceita a oferta
antiga mas **não tem mais orçamento para ser concluído**.

| Cenário | Intervalo entre relays | Rotações | Aceitou a oferta antiga | Concluiu o pareamento |
|---|---|---|---|---|
| **6a** | **250 s** | **2** | ✅ SAS `929007` nos dois | ❌ prazo esgotado (restavam 4 s) |
| **6b** | **135 s** | **1** | ✅ SAS `971309` nos dois | ✅ **completo** |
| **6c** | **253 s** | **2** | ✅ SAS `615458` nos dois (com captura) | ❌ prazo esgotado (restavam 35 s) |

### Sobre o ajuste de 150 s para 250 s

O ajuste recebido estava certo no diagnóstico: **150 s força apenas uma rotação**, que a correção
anterior de 2 posições já tratava em parte; só a partir de ~240 s existem três ofertas
simultaneamente válidas, que é o caso que a correção nova fechou. 6a e 6c usaram 250 s por isso.

O que o ajuste não previa é que **a 250 s o pareamento não pode terminar**, por aritmética do prazo
(ver "Gate estrutural novo" abaixo). Então 250 s prova a aceitação mas nunca produziria a evidência
de "pareamento concluído, contato salvo, mensagem em cada direção" que o mesmo roteiro pedia; e os
150 s originais produzem essa evidência mas não exercitam a sobreposição tripla. **Os dois números
respondem a metades diferentes da pergunta e nenhum sozinho fecha o roteiro**, então os dois foram
executados. A tabela acima é o resultado combinado.

### Gates

| Cenário | Resultado | Evidência | Notas |
|---|---|---|---|
| Gate de build no build exato que foi instalado | PASSED | console desta sessão | `:core:test` **100/0/2 pulados** (eram 95 em run5); `:app:testDebugUnitTest` **61/0/1**; lint **0 erros**; os dois APKs. SHA-256 do APK instalado `973bde2e1bc95c82…`. |
| Instalação em A e B (`-r -t`) | PASSED | `Success` nos dois | Os dois cofres e o contato de run5 sobreviveram. |
| Suíte instrumentada em A | PASSED — **`OK (19 tests)`**, 66,9 s | `build-logs/android-test-emulator-5556-20260917T224751Z.log` | **São 19, não 22.** Contei os nomes distintos no corpo do log. Os 5 casos novos entraram em `:core:test` (95 → 100), que é JVM; o APK de androidTest ficou `UP-TO-DATE`, coerente com nenhuma fonte de androidTest ter mudado. |
| Re-pareamento sem apagar nada | PASSED | `session-log.md`, "Decisão sobre o passo 4" | **Nenhum `pm clear` foi executado**, em nenhum aparelho. O código suporta re-parear um contato existente: `readPairing` isenta explicitamente um `getContact(...) != null` do limite de contatos, e `putContact`/`putPairEvidence` são `ON CONFLICT … DO UPDATE`. Confirmado na prática: cada aparelho terminou com **um** contato, não dois. |
| **Aceitação de uma resposta ligada a uma oferta duas rotações atrás** | **PASSED, duas vezes** | dump de UI de 6a; **`13-A-6c-accepted-offer-after-two-rotations.png`** | 6a: resposta ligada à oferta `09c58ed2…` aceita enquanto a tela exibia `92f5454658…`. 6c: resposta ligada a `a3d6c56e…` aceita enquanto a tela exibia `9c1b9e9c…`. **É o defeito 3 de run5, fechado.** |
| Rotações comprovadas objetivamente, não presumidas | PASSED | `session-log.md`, tabelas de 6a e 6c | Cada oferta exibida foi extraída pelo hook `DUMP_QR` e decodificada: três `sha256` distintos, três `nonce` distintos, três `created` espaçados de 120 s, em cada um dos dois cenários — mais a contagem regressiva reiniciando (`00:01` → `01:58`). |
| **Pareamento concluído de ponta a ponta** | **PASSED (6b)** | `06-A-chat-run6-final.png`, `07-B-chat-run6-final.png` | SAS `971309` idêntico; os dois confirmaram; confirmação de 317 B nos dois sentidos; **"Contact verified and saved." nos dois**; cerimônia completa em 3 min 16 s. |
| Contato salvo dos dois lados | PASSED | mesmas capturas | A vê `TesteB` fp `7f8b c646 … 6bc2 a4cd`; B vê `TesteA` fp `e3fc 4e3d … 67f4 5c2f`, que é o campo `ed` decodificado das ofertas de A — conferência independente. |
| Uma mensagem em cada direção | PASSED | `06`/`07` | `a2b-run6-01` e `b2a-run6-01`, ambas com **✓✓** e exibidas do outro lado. Repetido depois de 6c: `a2b-run6-02` também entregue. |
| **Estado de UI `FETCHING`** | **PASSED — capturado pela primeira vez** | `13-…png` | *"Fetching the other device's key bundle over Tor… This can take up to 40 seconds. It keeps trying automatically — no action needed."* Run5 registrou este estado como **nunca observado**. A mesma captura mostra "The codes match" corretamente **desabilitado** enquanto o bundle do par não chegou (`PairingLifecycle.canConfirmSas`). |
| Mensagem de prazo esgotado | PASSED | `05-A-deadline-expired-message.png` | **"Time ran out to finish pairing. Start again whenever you're ready."** nos dois aparelhos — específica e correta, bem melhor que o `Could not complete` genérico que run5 recebeu no mesmo caminho. |
| Troca expirada não corrompe nada | PASSED | `session-log.md`, "Sanidade pós-execução" | A sessão Signal e o contato sobreviveram a **três** trocas, das quais **duas expiraram**; `a2b-run6-02` entregue depois de tudo. Uma troca que estoura o prazo não chama `finish()` e não escreve no cofre — confirmado na prática. |
| Nenhum crash | PASSED | `10-A-logcat-fatals.txt`, `11-B-logcat-fatals.txt` | Zero `FATAL`/`AndroidRuntime` do app (as linhas `AndroidRuntime` presentes são do processo uiautomator, uid 2000). |
| `WAITING_FOR_TOR` / `FAILED` / botão de nova tentativa | **NOT_RUN** | — | A busca continua terminando rápido demais para que apareçam. Exigiria degradar a rede de propósito. |
| **Leitura de um QR formato 2 pela câmera real do Galaxy Note10+** | **NOT_RUN** | — | Inalterado desde run5. Continua sendo **o único item** entre "funciona em emulador" e "o defeito de origem está corrigido", e continua fora do alcance destas sessões. |

### Medidas de Tor

| Medida | Valor |
|---|---|
| B ← A (6a) | ≤ 13,5 s |
| B ← A (6b) | ≤ 14,2 s |
| B ← A (6c) | **≤ 4,2 s** |
| A ← B (6b) | ≤ 1,6 s |
| A ← B (6c) | ≤ 2 s |
| Tentativas necessárias | **1**, nas cinco medições |

Limites superiores. A←B é sistematicamente mais rápido porque o circuito para o onion de B já está
quente de quando B buscou de A segundos antes.

### Gate estrutural novo descoberto nesta sessão (em aberto)

> **Corrigido depois e validado ao vivo em "T4.16 — run7", no fim deste arquivo.** A fase de
> confirmação ganhou orçamento próprio (`CONFIRMATION_TTL_SECONDS = 240`, contado do staging). O
> texto abaixo fica como o registro de quando o limite foi encontrado. Run7 confirmou os 240 s
> frescos em aparelho **e** mostrou que o gargalo migrou para o aparelho que faz staging primeiro,
> cujo relógio corre durante toda a espera.

`PairingEngine.stage` calcula `expiresAt = minOf(first.created, second.created) + PENDING_TTL_SECONDS`
— o prazo da troca é ancorado na oferta **mais antiga**. Com `OFFER_TTL_SECONDS = 120` e
`PENDING_TTL_SECONDS = 300`:

| Rotações antes de a resposta chegar | Idade da oferta antiga | Sobra para a fase humana |
|---|---|---|
| 0 | 0–120 s | 180–300 s |
| 1 | 120–240 s | 60–180 s |
| **2** | **240–300 s** | **0–60 s** |
| 3 | ≥ 300 s | recusada, fora do prazo |

A correção torna a oferta antiga **aceitável**, mas o orçamento restante encolhe na mesma proporção.
Com duas rotações sobra menos de um minuto para duas pessoas compararem seis dígitos em voz alta,
digitarem apelidos e trocarem dois QRs de confirmação. Medido: **6a chegou com 4 s de sobra, 6c com
35 s**; nenhum concluiu. Com uma rotação (6b) sobraram **147 s** e concluiu com folga.

Não invalida a correção — sem ela nada disso chegava sequer à tela de SAS. É um limite do
**orçamento**, não da aceitação, e fica em aberto para decisão de desenho (por exemplo ancorar o
prazo na oferta mais **nova**, ou dar à fase de confirmação um prazo próprio a partir do staging).

### Armadilhas de automação novas (somam-se às de run4 e run5)

1. **O destino de um relay precisa estar na tela de leitura ANTES do relay.** `INJECT_QR entregue` só
   diz que o broadcast chegou ao receiver; sem o composable `QrScanner` montado, o `scanSink` é nulo
   e o payload é descartado **em silêncio**. Custou 34 s em 6a.
2. **Capture a tela logo após o relay, antes de qualquer `uiautomator dump`** — cada dump custa 2–4 s
   e, num cenário com 35 s de folga, é a diferença entre capturar o SAS e capturar a tela de prazo
   esgotado (foi o que aconteceu com `04-…png` em 6a, e o motivo de 6c existir).
3. **Campos de texto retêm conteúdo entre tentativas de pareamento** (`TesteA` virou `TesteATesteA`).
   Limpe com `KEYCODE_MOVE_END` + `KEYCODE_DEL` antes de digitar.
4. **O tamanho do payload identifica a tela de origem sem precisar de dump:** 405 B = oferta,
   448 B = resposta, 317 B = confirmação. Foi assim que ficou provado, em 6c, que o toque de
   confirmação em B não havia registrado.

### Emuladores deixados em execução (atualizado após run6)

| Papel | AVD | Serial | Porta | Estado |
|---|---|---|---|---|
| A | nomessages35 | emulator-5556 | 5556 | app em primeiro plano, **conversa com `TesteB` aberta**, cofre **destrancado** com a senha real original (**nunca** `pm clear`, só `install -r -t`), "Tor connected", **1 contato pareado (`TesteB`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build run6 instalado |
| B | nomessages35b | emulator-5560 | 5560 | app em primeiro plano, **conversa com `TesteA` aberta**, cofre **destrancado**, o mesmo criado em run5 (`EncaminharTesteGamma03`) — **não foi apagado nesta sessão**, "Tor connected", **1 contato pareado (`TesteA`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build run6 instalado |

O aparelho físico `RX8MA0GD9ZY` (Galaxy Note10+) **não recebeu nenhum comando desta sessão**.

## T4.16 — run7: validação ao vivo do orçamento de confirmação separado (2026-09-17)

Sessão seguinte a run6, mesmos dois emuladores. Objetivo: exercitar em aparelho a correção do achado
estrutural de run6 — a busca do bundle por Tor e a fase humana passaram a ter orçamento próprio,
`CONFIRMATION_TTL_SECONDS = 240`, contado do **staging**, em vez de dividirem os 300 s ancorados na
criação da oferta mais antiga. `PENDING_TTL_SECONDS = 300` continua sendo o orçamento de
**aquisição**, inalterado desde run6.

`RX8MA0GD9ZY` **não recebeu nenhum comando**. **Nenhum `pm clear`** em nenhum dos dois emuladores.

Log cronológico completo, com hashes de cada oferta e os dois relógios acompanhados minuto a minuto:
`docs/development/build-logs/pairing-v2-20260917-run7/session-log.md`.

### Resultado resumido

**O pareamento de duas rotações completou de ponta a ponta pela primeira vez** — em run6 as duas
tentativas de 250 s foram aceitas e morreram sem orçamento. A correção entrega o que promete: quem
faz staging por último recebe **240 s inteiros**, medidos na tela (`Expires in 03:59` e `03:52`)
contra os 4 s e 35 s de run6a/6c.

**E a sessão descobriu que o gargalo mudou de lado em vez de desaparecer.** O relógio de confirmação
de cada aparelho começa no **seu próprio** staging, e no roteiro de 250 s o staging do respondedor
acontece no **começo** da espera. Repetir 6a literalmente (B escaneando logo no início) já não é um
cenário completável: os 240 s de B terminam **antes** de a resposta chegar a A.

| | Quando B escaneou | Intervalo entre relays | Rotações | A aceitou | Concluiu |
|---|---|---|---|---|---|
| **7a** | T0+26 s (cedo) | 250 s | 2 | — (B já expirada) | ❌ **B expirou durante a espera** |
| **7b** | T0+102 s (tarde) | 168 s | 2 | ✅ SAS `065700`, `Expires in 03:59` | ❌ erro de automação meu |
| **7c** | T0+103 s (tarde) | **246 s** | **2** | ✅ SAS `030958`, `Expires in 03:52` | ✅ **COMPLETO** |

Não é um defeito: é a consequência direta e já documentada de cada lado contar do próprio staging. O
que muda é a leitura do cenário — **o que decide o sucesso não é o intervalo entre os relays, é em
que ponto da janela de 120 s o outro aparelho escaneou**. O orçamento compartilhado é
`240 − (instante do relay de volta − instante do staging de B)`: escaneando cedo (7a, T0+26) dá
**negativo**; escaneando tarde (7c, T0+103) sobram ~75 s.

### Gates

| Cenário | Resultado | Evidência | Notas |
|---|---|---|---|
| Gate de build no APK exatamente instalado | PASSED | console | `:core:test` **102/0/2** (eram 100 em run6); `:app:testDebugUnitTest` **61/0/1**; lint **0 erros**; os dois APKs. SHA-256 `cba4779f85d9b472…`. |
| Instalação em A e B (`-r -t`) | PASSED | `Success` nos dois | Cofres e contato de run6 intactos. |
| **Aceitação de resposta ligada a oferta duas rotações atrás** | **PASSED, duas vezes** | `03-A-7b-sas-fresh-240s-after-two-rotations.png`; 7c no log | Rotações comprovadas por hash: três `sha256`/`nonce`/`created` distintos por cenário, extraídos do QR realmente exibido via `DUMP_QR`. |
| **Orçamento de confirmação fresco de 240 s a partir do staging** | **PASSED** | mesma captura (`Expires in 03:59`); 7c (`Expires in 03:52`) | O objetivo desta sessão. Contra `00:04` (run6a) e `00:35` (run6c). |
| **Pareamento completo com duas rotações** | **PASSED (7c)** | `05-A-contact-verified-and-saved.png`, `06-B-contact-verified-and-saved.png` | Relay de volta **245,8 s** após o relay de ida, **T0+268,9 s**, duas rotações. SAS `030958` idêntico; os dois confirmaram; confirmação de 317 B nos dois sentidos; **"Contact verified and saved." nos dois**. |
| Contato salvo dos dois lados | PASSED | mesmas capturas | **Um** contato em cada (upsert). A vê `TesteB` fp `7f8b c646 … 6bc2 a4cd`; B vê `TesteA` fp `e3fc 4e3d … 67f4 5c2f`. |
| Uma mensagem em cada direção | PASSED | `07-A-chat-run7-final.png`, `08-B-chat-run7-final.png` | `a2b-run7-01` e `b2a-run7-01`, ambas com **✓✓** e exibidas do outro lado. |
| Verificação explícita de que A está na tela de leitura antes de injetar | PASSED | log de 7a, 7b e 7c | Passo 3 do roteiro. Em todas as três tentativas o dump confirmou `Point the camera` = 1 **antes** do relay. O erro de 6a não se repetiu. |
| Estado `FETCHING` | PASSED, **três vezes** | dumps de 7a e 7c; captura em 7b | Em run5 constava como nunca observado; run6 capturou uma vez; aqui aparece de forma consistente. |
| **Expiração do respondedor durante a espera** | **observado (novo)** | `02-B-expired-during-wait.png` | 7a: B contou `03:08 → 02:25 → 01:31 → 00:53 → 00:14` sem interrupção enquanto A rotacionava, e expirou ~4 s antes do relay de volta. Mensagem correta: "Time ran out to finish pairing. Start again whenever you're ready." |
| Nenhum crash | PASSED | `11-A-logcat-fatals.txt`, `12-B-logcat-fatals.txt` | Zero `FATAL` do app nos dois. |
| **Leitura de um QR formato 2 pela câmera real do Galaxy Note10+** | **NOT_RUN** | — | Inalterado desde run5. Continua sendo o **único** item entre "funciona em emulador" e "o defeito de origem está corrigido". |
| `WAITING_FOR_TOR` / `FAILED` / botão de nova tentativa | **NOT_RUN** | — | A busca continua terminando rápido demais (≤ 8,2 s, 1 tentativa). Exigiria degradar a rede de propósito. |

### Margem de confirmação medida em 7c

| | Início do relógio | Prazo | Concluiu | **Margem restante** |
|---|---|---|---|---|
| **A** (staging por último) | 23:51:40 | 23:55:40 | 23:52:38 | **~182 s (76 %)** |
| **B** (staging primeiro) | 23:48:55 | **23:52:52** | 23:52:50 | **~2 s (1 %)** |

A **fase de confirmação em si levou 70 s** — duas confirmações de SAS, dois apelidos digitados e
verificados, duas trocas de QR de confirmação, com um dump de verificação antes de cada toque. Deixou
de ser uma corrida, que era exatamente o objetivo. Os 2 s de B não foram gastos pela cerimônia: foram
os **165 s** em que B ficou parada entre o seu staging e o relay de volta.

### Leitura honesta

1. A correção faz o que promete para quem faz staging por último.
2. O gargalo migrou para quem faz staging **primeiro**, cujo relógio corre durante toda a espera.
3. **No uso real isto é invisível**, como a própria nota de desenho antecipa: duas pessoas juntas
   escaneiam e respondem com segundos de diferença, e os dois relógios ficam alinhados. O cenário de
   duas rotações com 250 s é deliberadamente patológico — existe para forçar a sobreposição tripla de
   ofertas, não porque descreva um encontro plausível.
4. Ainda assim vale registrar, porque é o tipo de coisa que volta como relatório de campo: quem
   escaneia primeiro e depois espera muito (a outra pessoa se distrai, o telefone cai, alguém precisa
   digitar uma senha) queima o próprio orçamento em silêncio, e **o aparelho que expira é o que não
   fez nada de errado**.
5. **A única forma realista de isto morder em campo é a entrega do QR de resposta ser lenta.** O
   relógio do respondedor é, na prática, "240 s desde que B escaneou até A terminar de escanear a
   resposta de B". Tudo o que atrasa esse segundo escaneamento — foco de câmera teimoso, alguém sem
   jeito de enquadrar, pouca luz — consome o orçamento de B, e não o de A. Vale notar que isso é uma
   variante do defeito que originou T4.16 (um QR que a câmera não lê), o que reforça o mesmo gate
   pendente em vez de abrir um novo: se a leitura por câmera real for rápida, este risco não se
   materializa; se for lenta, ele aparece aqui antes de aparecer em qualquer outro lugar. Registrado
   como nota de risco, não como item de redesenho — a assimetria em si está correta e é deliberada.

### Armadilha de automação nova

**Espere o painel de status assentar antes de ler coordenadas.** O painel "Fetching the other
device's key bundle over Tor…" encolhe quando a busca termina e empurra todo o formulário abaixo dele
**~210 px para cima** (o campo de apelido saiu de y=1875 para y=1665). Coordenadas lidas durante a
busca ficam erradas segundos depois — foi o que fez 7b falhar: o toque foi para a posição antiga, o
`input text` não entrou em campo nenhum, e **"The codes match" é inerte com o apelido vazio, sem
nenhuma mensagem de erro na tela**. Releia as coordenadas imediatamente antes de cada toque e
**verifique o conteúdo do campo depois de digitar**.

### Emuladores deixados em execução (atualizado após run7)

| Papel | AVD | Serial | Porta | Estado |
|---|---|---|---|---|
| A | nomessages35 | emulator-5556 | 5556 | app em primeiro plano, **conversa com `TesteB` aberta**, cofre **destrancado** com a senha real original (**nunca** `pm clear`), "Tor connected", **1 contato pareado (`TesteB`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, IMEs desabilitados, `svc power stayon true`, build run7 instalado |
| B | nomessages35b | emulator-5560 | 5560 | app em primeiro plano, **conversa com `TesteA` aberta**, cofre **destrancado**, o mesmo de run5 (`EncaminharTesteGamma03`), "Tor connected", **1 contato pareado (`TesteA`)**, `debug.nomessages.allow_capture=1`, `debug.nomessages.allow_qr_inject=1`, IMEs desabilitados, `svc power stayon true`, build run7 instalado |

O aparelho físico `RX8MA0GD9ZY` (Galaxy Note10+) **não recebeu nenhum comando desta sessão**.

### Situação da mecânica de pareamento após run7

Com run7, **a mecânica de pareamento está validada em emulador de ponta a ponta**: aquisição com até
três ofertas simultaneamente válidas, busca do bundle pelos dois lados via Tor, SAS idêntico,
confirmação dos dois lados, contato salvo dos dois lados, mensagens nos dois sentidos, e os dois
caminhos de expiração (aquisição e confirmação) terminando com mensagem correta e sem corromper nada.
O que permanece fora dessa afirmação: **a câmera real do Galaxy Note10+**, `WAITING_FOR_TOR`/`FAILED`/
botão de nova tentativa, o silêncio para nonce desconhecido, e os gates 8 e 10.

## Aparelho físico — 2026-09-18

Primeira execução de T4.16 tocando um aparelho físico real: Galaxy Note10+ SM-N975F, Android 12,
arm64, serial `RX8MA0GD9ZY`, com o APK debug do commit `706630a` instalado — pareado com os dois
emuladores já em uso nas sessões anteriores (A = `nomessages35`/`emulator-5556`, Android 15 x86_64;
B = `nomessages35b`/`emulator-5560`, Android 15 x86_64). Evidências visuais em
`docs/development/build-logs/real-device-20260918/` (ver o `README.md` desse diretório).

**Isto é a primeira leitura bem-sucedida de um QR de pareamento pela câmera real de um Galaxy
Note10+**, satisfazendo a parte de câmera real do "Feito quando" de T4.16 no que se refere à leitura
da oferta. Ver a seção "O que isto cobre e o que não cobre" abaixo para o que ainda não está
demonstrado.

| Passo | Resultado | Evidência | Tempos |
|---|---|---|---|
| Leitura do QR formato 1 (versão 40, ~2.950 bytes) pela câmera real do Note10+, janela do emulador maximizada no monitor | **FALHOU** (achado histórico que originou T4.16) | registrado nas seções de topo deste arquivo | não medido — a câmera nunca decodificou o QR |
| Leitura do QR formato 2 (`nomessages:2:`, oferta do emulador A, QR versão 13) pela câmera real do Note10+ | **PASSOU, na primeira tentativa** | logcat do celular: `NoMessagesQrScan: decode attempt result=DECODED`, frame 1920x1080, `rotationDegrees=90`; captura `docs/development/build-logs/real-device-20260918/a-qr.png` | 10:12:34 (hora do celular) |
| 1ª cerimônia de pareamento (celular ↔ emulador A) | **FALHOU — expirou por tempo esgotado**, não por defeito de leitura de QR ou de protocolo | ver narrativa abaixo | não medido com precisão; o processo `:tor` do celular tinha ~1 min de vida (Tor "frio") |
| 2ª cerimônia de pareamento (Tor já aquecido nos dois aparelhos) | **PASSOU, completa** | SAS `432057` idêntico nos dois lados; "Contact verified and saved." no emulador A; contato "Teste A" com impressão digital exibida no celular | não medido com precisão |
| Envio de mensagem de texto, celular → emulador A | **PASSOU** | mensagem "ola" entregue | ~10 s |
| Encaminhamento da mesma mensagem, emulador A → emulador B (toque longo → "Forward to" → TesteB → Send), com o Tor de B inicialmente bloqueado | **PASSOU, após desbloquear o Tor de B** | conversa em B mostra "↪ Forwarded \| ola \| 1:27 PM"; captura `docs/development/build-logs/real-device-20260918/b-forwarded.png` | ~15 s após o Tor de B ser desbloqueado |

### Leitura do QR formato 2 pela câmera real (fato 2)

Com o APK do commit 706630a (que já usa o QR compacto formato 2 de T4.16), a câmera do Note10+ leu o
QR de oferta exibido no emulador A **na primeira tentativa**. Isso contrasta diretamente com o
achado histórico de topo deste arquivo: o QR do formato 1 (versão 40, 177 módulos por lado,
~2.950 bytes) nunca foi lido pela mesma câmera, mesmo ocupando um monitor inteiro. Os 69 módulos por
lado do formato 2 (medidos em runs anteriores) tornavam a leitura *plausível*; esta sessão é a
primeira vez que isso foi *demonstrado* em câmera real.

### 1ª cerimônia de pareamento — falhou por Tor frio, não por defeito (fato 3)

O celular mostrou o QR de resposta e a mensagem "Buscando o pacote de chaves do outro aparelho pela
rede Tor…"; o QR de resposta foi levado ao emulador A manualmente pelo relay (`scripts/emulator-pair.sh`,
448 bytes, sha256 iniciando em `e5de51a6…`); os dois lados exibiram o mesmo código SAS **705238**; o
emulador A recebeu e verificou o pacote de chaves do celular ("Key bundle received and verified"),
mas o celular **não conseguiu** buscar o pacote de chaves do emulador dentro do prazo — o processo
`:tor` do celular havia subido há apenas ~1 minuto (Tor ainda "frio") — e os dois lados **expiraram**
com mensagem de tempo esgotado.

Isto é registrado como uma **falha/observação**, não como um sucesso. O SAS idêntico e a verificação
unilateral do lado do emulador A mostram que o protocolo e a leitura de QR funcionaram corretamente
até esse ponto; a causa da expiração foi puramente de timing (Tor do celular ainda inicializando),
não um defeito de leitura de câmera nem de protocolo de pareamento.

**Pendência em aberto, não resolvida nesta sessão:** seria útil que o app tentasse novamente a busca
do pacote de chaves dentro dos 300 s (em vez de desistir numa única tentativa) e/ou registrasse em
logcat o erro específico da falha de busca (nível debug) para diagnóstico. Nenhuma retentativa
automática foi implementada ou medida nesta sessão, e nenhum log de erro específico foi capturado —
ver T4.18 em `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.

### 2ª cerimônia de pareamento — completa (fato 4)

Com o Tor já aquecido nos dois aparelhos, a leitura pela câmera foi novamente bem-sucedida, o QR de
resposta foi novamente levado pelo relay, e o SAS **432057** saiu idêntico nos dois lados. "Pacote de
chaves recebido e conferido" apareceu no celular e "Key bundle received and verified" no emulador A;
o usuário confirmou no celular usando o apelido "Teste A"; o QR de confirmação do celular foi levado
ao emulador A pelo relay (424 caracteres base64); o emulador A mostrou "Contact verified and saved.".
O celular, após tocar em "Concluir", passou a listar o contato "Teste A" com impressão digital
exibida. O emulador A salvou o celular como contato "Teste C".

### Mensagem e encaminhamento entre três aparelhos (fato 5)

A mensagem de texto "ola", enviada do celular para o emulador A, chegou em **~10 s**. Em seguida foi
encaminhada do emulador A para o contato TesteB (emulador B) por toque longo → "Forward to" → TesteB
→ Send. O emulador B estava com o Tor desligado (bloqueado) nesse momento; depois de desbloqueado, a
mensagem chegou em **~15 s**, e a conversa no emulador B passou a mostrar "↪ Forwarded | ola |
1:27 PM". Isso comprova o encaminhamento entre três aparelhos distintos (celular → emulador A →
emulador B), com a etiqueta "Forwarded" aparecendo **apenas no destino do encaminhamento**, não na
mensagem original — captura `docs/development/build-logs/real-device-20260918/b-forwarded.png`.

### O que isto cobre e o que não cobre

- **Cobre:** a leitura de um QR de pareamento formato 2 pela câmera real de um Galaxy Note10+ — o
  defeito específico que originou T4.16 — está demonstrada pela primeira vez (fato 2, oferta lida
  pela câmera do celular). Um pareamento completo de ponta a ponta entre celular e emulador
  aconteceu (2ª cerimônia), com mensagens e encaminhamento entre três aparelhos distintos.
- **Não cobre / não foi medido nesta sessão:** os fatos acima descrevem explicitamente a leitura da
  **oferta** pela câmera do celular; não há, nos fatos observados, uma medição separada e explícita
  de "a resposta também foi lida pela câmera" do emulador A — no fluxo de pareamento por QR deste
  app, quem lê a oferta usa a câmera, e o QR de resposta e o de confirmação desta sessão foram
  entregues ao emulador A **pelo relay** (`scripts/emulator-pair.sh`), não fotografados por uma
  câmera real apontada para a tela do celular. Não foi medida retentativa automática de busca do
  pacote de chaves dentro dos 300 s (pendência registrada acima, não implementada nem exercitada).
  Não foi capturado log de erro específico da falha de busca por Tor. Os estados de UI
  `WAITING_FOR_TOR`/`FETCHING`/`FAILED` e o botão de nova tentativa não foram objeto desta sessão.
  Os gates 8 (expiração/replay adversarial) e 10 (fechamento de rede ao bloquear) continuam
  `NOT_RUN` — nada nesta sessão foi um teste adversarial deliberado do protocolo; a expiração da 1ª
  cerimônia foi um efeito colateral de timing, não um teste de Gate 8.

## T4.17 — campainha, validação ao vivo em dois emuladores (2026-09-18)

Fase 8 (última) de T4.17: **build e medição**, sem implementação nova além de dois comentários de
KDoc desatualizados em `Pairing.kt`. Emuladores: A = AVD `nomessages35` / `emulator-5556`
(Android 15, x86_64), B = AVD `nomessages35b` / `emulator-5560` (Android 15, x86_64). O aparelho
físico `RX8MA0GD9ZY` estava conectado ao adb e **não foi tocado**. Evidências brutas e linha do tempo
completa em `docs/development/build-logs/doorbell-20260918/` (ver o `session-log.md` daquele
diretório).

### Build e testes (todos verdes, números reais)

| Suíte | Resultado |
|---|---|
| `cargo test --all-features --locked` (host, WSL Ubuntu) | **45 passaram, 0 falharam**, 1 ignorado (`onion_self_roundtrip_replies_and_stops`, exige Tor ao vivo) |
| `.so` Android | reconstruídas para `arm64-v8a` e `x86_64`; ELF64 / páginas de 16 KiB conferidos |
| `:core:test` | **102 testes, 0 falhas**, 2 pulados — o `UnsatisfiedLinkError` das fases anteriores **sumiu** assim que a biblioteca JNI de host passou a ser construída |
| `:app:testDebugUnitTest` | **83 testes, 0 falhas**, 1 pulado |
| Regressão Python de SQL | **4 testes, OK** |
| `:app:lintDebug` | **0 erros, 43 avisos, 6 dicas** — histograma **idêntico**, item a item, ao da fase anterior: nenhum aviso novo |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` | `BUILD SUCCESSFUL`; APKs gerados |
| Suíte instrumentada, `emulator-5556` | **OK (20 testes)** em 120,5 s — eram 19 antes de T4.17; as duas novas são as migrações de esquema v3 e v4 da campainha |

APKs: `app/build/outputs/apk/debug/app-debug.apk` (118.403.848 bytes, sha256
`e0295f8fb83dc2e9f5044112754ca6d68cea1af43f4d19d56701d35108ca4210`) e
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` (2.522.083 bytes).
Log do Gradle: `docs/development/build-logs/android-20260918T154542Z.log`.
Log da suíte instrumentada: `docs/development/build-logs/android-test-emulator-5556-20260918T125244Z.log`.

### Por que os dois cofres foram recriados

Os contatos que já existiam nos emuladores vinham de pareamentos anteriores ao esquema v4 e tinham
`doorbell_token_issued` vazio — limitação assumida e sem correção retroativa
(`docs/development/runtime-catalog.md` §8.3). Sem esse campo o lado que recebe a batida não tem o que
reconhecer. `pm clear` nos dois aparelhos, cofres recriados e **pareamento novo** A↔B pelo relay
`scripts/emulator-pair.sh` (QRs: oferta 405 B, resposta 448 B, confirmações 317 B; SAS **806080**
idêntico nos dois lados; "Contact verified and saved" nos dois).

### O cenário de aceite, de ponta a ponta

| Passo | Resultado | Evidência |
|---|---|---|
| Aviso "Pending message notice" num cofre recém-criado | **LIGADO por padrão** — switch `checked=true` e o texto do modo ligado na tela | `09-B-settings-pending-message-notice-on.png` |
| B bloqueado (botão "Lock now") | `:tor` **sobrevive, com o mesmo PID `18555`** — modo mínimo | `10-…`/`11-B-pids-*-lock.txt` |
| Notificações em B logo após o bloqueio | só a **id=3**, canal `background_service`, `importance=2`, `vis=SECRET`, `ONGOING|FOREGROUND_SERVICE|LOCAL_ONLY`; **nenhuma id=2** | `12-B-notifications-after-lock.txt` |
| A envia "campainha" para TesteB às **18:22:20Z**; entrega direta falha (B bloqueado) | a falha dispara a batida | `13-A-send-timestamp.txt` |
| Aviso da campainha em B às **18:22:46Z — 26 s depois** | id=2, canal `private_messages`, `importance=3`, `vis=SECRET`, `LOCAL_ONLY`, título `NoMessages`, corpo exatamente `You have messages waiting. Open NoMessages.` — **sem remetente, sem contagem, sem prévia** | `14-B-doorbell-notification.txt`, `15-B-notification-shade-doorbell.png` |
| B destrancado com a senha do cofre às 18:23:29Z | **id=2 cancelada** — nenhum registro de notificação do app sobra | `16-B-pids-and-notifications-after-unlock.txt` |
| Mensagem entregue em B às **18:24:21Z** | "campainha", 6:22 PM, na conversa com TesteA | `17-…`, `18-B-chat-campainha-delivered.png` |

Isto cobre o gate 6 para a **id=2** e a **id=3** verificadas literalmente em inglês (o texto em
português não foi exercitado nesta sessão) e os sub-itens **(iv)** e **(v)** do gate 10(b).

### Achado: o filho `:tor` não é reaproveitado ao destravar o mesmo cofre

O `:tor` sobrevive ao bloqueio com o mesmo PID, mas ao **destravar o mesmo cofre, no mesmo processo
de app**, o filho é derrubado e um novo sobe (`18555 → 19421`, e de novo `19421 → 19636` numa sonda
isolada sem batida alguma), com a UI mostrando "Reconnecting to Tor — sends are waiting", ou seja um
bootstrap completo em vez da adoção de um host já pronto. Contraria `prepareDoorbellHandoff`
(`NoMessagesController.kt:599-609`) e o sub-item **(vi)** do gate 10(b).

Não é crash: buffer `crash` vazio, sem tombstone, sem `SIGABRT`. O logcat mostra
`ActivityManager: Process dev.mx3.nomessages.debug:tor (pid 19421) has died: vis BTOP` seguido de
`Scheduling restart of crashed service … TorService … for connection` — a assinatura de
`shutdownTransport` → `TorConnection.shutdown()` matando o filho com um cliente ainda ligado. Ou
seja, o ramo de **destruição** de `prepareDoorbellHandoff` foi tomado. Como `VaultSlot` é um enum de
dois valores, `kept.slot == slot` não pode falhar para o mesmo cofre; sobram `kept == null` e
`kept.transport.alive == false` (`TorConnection.kt:107`). Discriminar as duas exigiria instrumentar o
código, o que está fora do escopo desta fase — **nada foi corrigido**.

Impacto: nenhum sobre o critério de aceite da funcionalidade; o custo é bateria e latência, porque
todo desbloqueio volta a pagar o bootstrap do Tor (~40–60 s medidos aqui), que é exatamente o que o
reaproveitamento existe para evitar.

### O que isto cobre e o que não cobre

- **Cobre:** o caminho inteiro da campainha ao vivo entre dois aparelhos — pareamento novo gerando os
  tokens, aviso ligado por padrão, bloqueio preservando só a campainha, falha de entrega disparando a
  batida, batida válida produzindo o aviso correto, desbloqueio cancelando o aviso e entregando a
  mensagem. Gate 6 para id=2 e id=3 (em inglês) e gate 10(b) (iv) e (v).
- **Não cobre, continua `NOT_RUN`:** o gate 10 **(a)**, com o aviso **desligado** (o `:tor` tem de
  **terminar**), com contagem de PIDs e atribuição privilegiada de sockets; os sub-itens **(i)**,
  **(ii)** e **(iii)** do gate 10(b) — que o onion de mensagens some, que só a porta virtual 4243
  responde e que uma conexão com qualquer coisa que não seja uma batida de 57 bytes é fechada com
  **zero** bytes de volta, e a varredura de dump bloqueado do gate 1; o sub-item **(vi)**, que
  **falhou** (acima); batida **inválida** não produzindo aviso; senha de pânico; disparo por timeout
  de segundo plano e por tela apagada (só o bloqueio explícito foi exercitado); a id=1; e a
  verificação em **português** dos três textos de notificação.

### Adendo (2026-09-18, mais tarde no mesmo dia) — causa raiz do sub-item (vi), confirmada

O achado acima ("o filho `:tor` não é reaproveitado ao destravar o mesmo cofre") foi instrumentado e
**a causa raiz está confirmada** — e não é a que se supunha. As duas suspeitas registradas antes
(`minimal` nulo, ou a `TorConnection` preservada marcada como parada pelo próprio `lock()`) estão
**ambas descartadas**: em cinco rodadas da sonda isolada o log de decisão diz
`doorbell handoff: reuse=true sameSlot=true hasSlot=true alive=true (stopped=false dead=false cause=none)`,
isto é, a adoção **é** decidida e o transporte chega vivo e intocado ao desbloqueio.

O que falha é o segundo `START` dentro do filho, em ~3 ms: `tor::start` chama
`launch_onion_service_with_hsid` com o nickname fixo `"nomessages"`, o que reinsere a chave HsId no
keystore efêmero do `TorClient` **compartilhado** que a campainha manteve vivo durante o bloqueio, e
o Arti recusa com `Key already exists` — `tor::stop()` larga o `RunningOnionService` mas não remove a
chave. A tentativa adotiva vira `FAILED`, o supervisor espera 5 s, a tentativa seguinte já não
encontra handoff e `recycleTransport()` mata o filho da campainha: daí o PID novo.

A correção é no **nativo** (`native/src/tor.rs`), com implicações de keystore e de ciclo de vida do
serviço onion, e por isso **não foi tentada** — fica para decisão sob **T4.19**. O sub-item **(vi)**
do gate 10(b) continua **FALHANDO**, agora com diagnóstico fechado em vez de hipótese.

Evidência nova, em `docs/development/build-logs/doorbell-20260918/`:

| Arquivo | O que prova |
|---|---|
| `22-handoff-root-cause.txt` | A cadeia completa, do log de decisão ao erro do Arti, e o que foi descartado |
| `23-B-handoff-probe-instrumented.txt` | Cinco rodadas da sonda isolada com o log de decisão |
| `24-B-logcat-native-error.txt` | Logcat literal com o texto real do erro do Rust (build temporário, já revertido) |
| `25-acceptance-after-diagnosis.txt` | Cenário de aceite completo re-executado: bloquear → bater de A → notificar (28 s) → destravar → entregar |
| `26-B-acceptance-delivered.png` | A mensagem `aceite-pos-diagnostico` entregue em B depois do desbloqueio |

O cenário de aceite da campainha **continua passando** depois da instrumentação: nada mudou de
comportamento, só entraram logs guardados por `BuildConfig.DEBUG` em `NoMessagesController.kt`,
`TorConnection.kt` e `TorService.kt`.

### Adendo (2026-09-18, fim do dia) — o sub-item (vi) do gate 10(b) **passou**

A causa raiz registrada no adendo anterior foi corrigida no nativo (`native/src/tor.rs` e, pelo
motivo explicado abaixo, `native/src/doorbell.rs`; registro completo em `docs/changes/tor.rs.md` e
`docs/changes/doorbell.rs.md`, seções de 2026-09-18). Em resumo: `launch_onion_service_with_hsid`
**insere** a chave HsId no keystore do `TorClient` compartilhado e recusa sobrescrever; derrubar o
`RunningOnionService` não removia essa entrada, então o segundo `start` no mesmo processo morria com
`Key already exists`. O teardown passou a esquecer a chave junto com o serviço
(`tor::forget_onion_key`). A chave é derivada da seed do cofre, então removê-la e reinseri-la
devolve **o mesmo** endereço onion.

A campainha precisou da mesma correção porque `NoMessagesController.kt:369` chama `doorbellStop()`
em todo desbloqueio: com o `:tor` passando a sobreviver aos ciclos, o segundo bloqueio do processo
tropeçaria na chave "nomessages-doorbell" deixada pelo primeiro.

Resultado ao vivo, no emulator-5560, com o mesmo roteiro que antes falhava 5/5:

| Antes (23-…) | Depois (27-…) |
|---|---|
| PID do `:tor` mudava em **5 de 5** rodadas | PID **idêntico** em 5 de 5 rodadas (23229 do início ao fim) |
| Uma `transport attempt: failed` e uma `reusingDoorbellChild=false` por rodada | **zero** de cada, nas três rodadas com logcat |
| 5 s de backoff + morte do filho da campainha + bootstrap de 40–60 s | sem backoff, sem morte, **16 s** até "Tor connected" |

O cenário de aceite completo foi re-executado por cima disso (sexto ciclo de bloqueio/desbloqueio no
**mesmo** processo `:tor`) e passa ponto a ponto: bloqueio preservando só a campainha, entrega direta
falhando, batida, aviso **id=2** em 25 s com o texto fixo e sem remetente, desbloqueio cancelando o
aviso, mensagem entregue. A entrega pelo caminho direto, sobre o onion que A guardou no pareamento
muito antes desta correção existir, é a prova ao vivo de que o endereço onion de mensagens **não
mudou** com as seis remoções e reinserções da chave.

Evidência nova, em `docs/development/build-logs/doorbell-20260918/`:

| Arquivo | O que prova |
|---|---|
| `27-B-pid-stable-after-fix.txt` | Cinco rodadas da sonda isolada com o PID do `:tor` estável, o log de decisão limpo e o tempo de desbloqueio |
| `28-B-acceptance-after-fix.txt` | Cenário de aceite completo re-executado no mesmo processo, com horários e o dump literal da notificação id=2 |
| `29-B-acceptance-delivered-after-fix.png` | A mensagem `campainha-pos-correcao` entregue em B depois do desbloqueio |

O que **continua** `NOT_RUN` ou não coberto é exatamente a lista do adendo anterior, menos o
sub-item **(vi)**: o gate 10 **(a)** com o aviso desligado, os sub-itens **(i)**, **(ii)** e **(iii)**
do gate 10(b), a varredura de dump bloqueado do gate 1, batida inválida não produzindo aviso, senha
de pânico, disparo por timeout de segundo plano e por tela apagada, a id=1, e a verificação dos três
textos de notificação em português.

O aparelho físico `RX8MA0GD9ZY` seguiu conectado ao adb durante toda esta fase e **não foi tocado**.

Os status resultantes foram transcritos para `docs/release-checklist.md` em 2026-09-18, nas células
dos gates **6** e **10**, com as citações de arquivo desta seção: passam apenas o gate 10(b) **(iv)**,
a metade de batida **válida** do **(v)**, a metade de **mesmo cofre** do **(vi)** e, no gate 6, as
notificações **id=2** e **id=3** em inglês. Todo o resto continua `NOT_RUN`, e nenhum dos dois gates
passa como um todo.
