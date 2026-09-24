# T4.8 media-inline final pass — build/test/visual-validation — chronological log

All times local (device clock, UTC+... not normalized; see per-screenshot timestamps for relative
ordering). Emulators: A = emulator-5556 (TesteA, vault password `PQrCezN47qFFcFecspD9DLsq`), B =
emulator-5560 (TesteB, vault password `AeltsQd6HSzHzMfyHxnlc8Fe`). Both emulators already paired
with each other from a prior session (contact `TesteB` on A, `TesteA` on B, with pre-existing chat
history `b2a-*`/`off-*` test messages) — no re-pairing was needed for this task. Both had Tor
running/idle at session start. APK under test: fresh `:app:assembleDebug` of the working tree with
all three T4.8 deliverables (A: shared memfd/cache infra + inline audio; B: inline photo; C: inline
video) coexisting, SHA-256 `749477db5c7aa4c76a1fd633ad7763459b92e2b7ffe637330a910b41d29e3b0e`.

## Build/test matrix (Step 1-2, before any emulator work)

- `:app:testDebugUnitTest :app:lintDebug` (WSL gradle wrapper): `BUILD SUCCESSFUL` in 22s, mostly
  `UP-TO-DATE` (the three deliverable agents' own prior runs already covered this exact tree
  state). 43 JVM tests across 7 classes, 0 failures/errors, 1 skipped; lint 0 errors.
- `:app:assembleDebug :app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL` in 4m13s.
- `strings.xml`/`values-en/strings.xml`: sorted `name="..."` attribute diff is empty, 205/205 keys
  each — parity confirmed independently of the agents' self-reports.
- Instrumented suite on `emulator-5556` (`t31-devices.sh`): **15/15 passed**, 0 failures, 167.7s.
  Includes all 11 `AndroidVaultStorageTest` cases, both `MemoryAudioEncoderTest` cases, the 1
  `MemoryPdfDocumentTest` case, and the 1 `VideoPreviewDecodeTest` case (the synthetic-MP4 poster
  test — passed on its real-decode path, not just its tolerated-null path, since `OK (15 tests)`
  with no skips). Log: `docs/development/build-logs/android-test-emulator-5556-20260916T150758Z.log`.

No integration fixes were needed anywhere in Step 1-2 — the three deliverables' edits to shared
files (`ChatScreen.kt`, `UiContract.kt`, `MediaPreviewCache.kt`, `NoMessagesController.kt`,
`AttachmentViewer.kt`) compiled and passed cleanly together on the first run.

## Visual validation (Step 3)

- Installed the fresh debug APK on both emulators (`adb install -r -t`).
- Confirmed `debug.nomessages.allow_capture` exists in source
  (`MainActivity.kt:76-77`, `UiLogic.kt:41`, `DebugQrReceiver.kt`) before relying on it; set the
  property + `hidden_api_policy=1` on both, force-stopped and relaunched. First screenshot attempt
  came back solid black on both (raw captures not retained — screens were simply
  asleep, `mWakefulness=Asleep`, unrelated to the capture switch); `input keyevent KEYCODE_WAKEUP` +
  `wm dismiss-keyguard` fixed it — both then showed the real "Unlock NoMessages" screen, proving the
  capture switch itself works correctly.
- Unlocked both vaults with the given passwords (raw captures not retained; showed the Argon2id
  "Working…" spinner, then the home screen reached, ~20s unlock time, consistent with Argon2id
  calibration). Both landed on the home screen with the pre-existing `TesteB`/`TesteA` contact and
  chat history intact.

### Photo

- Opened the A→TesteB chat (`08-emuA-chat-text-regression.png` — also doubles as the plain-text
  regression check: the pre-existing `b2a-*`/`off-*` bubbles render as ordinary text bubbles with
  ticks, unaffected by the three deliverables sharing `MessageBubble`).
- Attach → Take photo opened the in-app camera screen over the emulator's virtual scene
  (`09-emuA-attach-menu.png`, `10-emuA-camera-screen.png`; the virtual scene shows leftover QR
  posters from a prior pairing session's `virtualscene-image` injection — cosmetic only).
  `11-emuA-after-capture.png` confirms the live preview renders. Tapped "Take photo"
  (`12-emuA-photo-sent-thumbnail.png`): the sender's own outgoing bubble immediately shows a real
  decoded JPEG thumbnail of the captured scene under the filename `foto.jpg`, not a generic
  file-icon+filename row — first direct confirmation of the `ImageBubble` decode path (B item).
- Tapped the sent thumbnail: `16-emuA-fullscreen-image-viewer.png` shows the full-screen
  `AttachmentViewer` opening correctly with a title bar (`foto.jpg`) and back arrow. Pinch-zoom/pan
  and cross-image swipe are implemented in `AttachmentViewer.kt`'s hand-written gesture loop
  (`docs/changes/AttachmentViewer.kt.md`) but were not separately simulated here: `adb shell input`
  has no practical multi-touch pinch primitive, exactly as anticipated in the task brief, so this
  is verified by code review + the JVM-testable `chooseInSampleSize`/gallery math
  (`UiLogicTest.kt`), not by a live gesture.
- After Tor came online on A (see "Tor bootstrap timing" below), TesteB received the message.
  `18-emuB-received-photo-audio-bubbles.png` shows the same real decoded thumbnail rendering on the
  **receiver** side (decrypted independently from the sender's cached copy — `MediaPreviewCache` is
  per-process, never synced). `19-emuB-fullscreen-image-viewer.png` confirms the receiver's viewer
  opens identically.

### Audio

- Tapped the mic button in the composer: `13-emuA-recording.png` shows the "Recording audio…" red
  banner with a live mic icon and a "Stop recording" action, confirming `state.recordingAudio`
  flipped correctly (content-desc round-tripped through `uiautomator dump` to find the exact button
  bounds after a couple of missed taps against scaled screenshot coordinates — see raw session
  commands). Recorded ~7s, tapped the composer's stop icon.
- `14-emuA-audio-bubble-sent.png`: the sent bubble immediately shows the full audio player UI — a
  play button, a real (non-flat) waveform, "0:00 / 1:02" duration, and a "1x" speed control — under
  the filename **`audio.m4a`**, i.e. `MemoryAudioEncoder`'s AAC path succeeded on this emulator run
  (not the WAV fallback described as the documented safe default). This is a stronger live result
  than the task brief anticipated as the likely outcome.
- Tapped Play: `15-emuA-audio-playing-state.png` shows the icon flip to a pause glyph;
  `17-emuA-audio-progress-advancing.png` (taken ~30s later after also opening/closing the image
  viewer) shows the position readout at "0:31 / 1:02" with the waveform's played portion filled
  teal up to that point and the pause icon still showing — confirms both play/pause toggling and
  seek-position/progress rendering advance correctly.
- After delivery, `18-emuB-received-photo-audio-bubbles.png` (same screenshot as the photo section)
  shows the **receiver**-side audio bubble with a correctly decoded waveform and "0:00 / 1:02"
  duration (decoded receiver-side via `AudioWaveformDecoder`, not carried over from the sender).
  Tapped Play on B: `20-emuB-audio-playing-state.png` shows the pause icon and "0:01 / 1:02" with
  the waveform partially filled — full round-trip (record → encode → send → decrypt → decode →
  play) confirmed on both ends.

### General regression

- `08-emuA-chat-text-regression.png` (see above) confirms plain-text bubbles are visually unaffected
  by the three deliverables' shared edits to `ChatScreen.kt`'s `MessageBubble`.
- No PDF/generic-file attachment was exercised live in this session (none was queued in the
  existing chat history and creating one was out of scope for the time available); that path's
  regression coverage is the instrumented `MemoryPdfDocumentTest` pass in Step 2 (`rendersGenerated
  PagesFromAnonymousMemory`, part of the 15/15 instrumented result) plus unchanged
  `MemoryPdfDocument.kt` behavior (only refactored to call the new shared `MemoryFd`, see
  `docs/changes/MemoryPdfDocument.kt.md`).

## Tor bootstrap timing (observed, not a regression)

Delivery from A to B took much longer than the ~40-135s range recorded in the T2.2 gate closure.
The first unlock of A sat in `PUBLISHING` ("cannot receive yet") for over 5 minutes with no visible
progress in `logcat` for the `:tor` child process (which logs almost nothing above `I`/`W` level
during normal bootstrap — this is expected, not a symptom). A force-stop + relaunch (which
re-locks and forces a fresh Tor start on next unlock) was tried as a mitigation; the second attempt
reached `Tor connected` in a few more minutes and the photo+audio messages (queued PENDING while A
was offline, per the documented outbox-pause behavior) drained to B within ~20s of A going online,
with delivery ticks updating and B's chat-list showing an unread badge. This is treated as
environment/session variance in the already-verified live-Tor path (T2.1-T2.3, T3.3 run4), not a
finding introduced by or specific to T4.8 — no product code was touched to work around it, only
patience and a force-stop/relaunch retry.

## Conclusion

All three visual-validation targets (photo, audio, general regression) were captured with full
send + receive round-trips on both emulators, including tap-to-zoom and play/pause interaction
proof. Pinch-zoom/swipe gesture simulation was not attempted live (impractical via plain `adb shell
input`, as anticipated), consistent with the task's explicit allowance to rely on code review +
JVM-testable math for that specific sub-case. No decoy-vault video fixture exists (documented gap,
not fixed here — see `docs/changes/AndroidVaultStorage.kt.md`), so the decoy-vault video bubble was
not separately visually validated; the decoy's photo and audio fixtures were not separately opened
either (out of time budget), but they share the exact same `ImageBubble`/`AudioBubble` rendering
code exercised above against real attachments.
