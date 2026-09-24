# Android UI report

Updated: 2026-09-14

## Visual identity

Default (and only) theme: **"Grafite e Âmbar"** ("Graphite and Amber"), defined entirely through
`MaterialTheme.colorScheme` in `NoMessagesTheme.kt` — no hardcoded color constant remains anywhere
under `app/src/main/kotlin/dev/mx3/nomessages/ui/`. Every text/background pair below that carries text has been checked against WCAG AA
(contrast ratio ≥ 4.5:1) in both light and dark. Sent vs. received message bubbles are told apart
by shape/alignment (sent bubbles align right) *and* tone (`primaryContainer` vs.
`surface`/`surfaceVariant`), not by hue alone — relevant for readers with red-green color
blindness, since the app's previous green-vs-gray bubble pair sat closer together in that gap than
this one does.

| Role | Light | Dark | Used for |
|---|---|---|---|
| `background` / `onBackground` | `#FAFBFB` / `#1B1F22` | `#14171A` / `#ECEFF1` | screen root background |
| `surface` / `onSurface` | `#FFFFFF` / `#1B1F22` | `#1B1F22` / `#ECEFF1` | cards, received bubble, default text |
| `surfaceVariant` / `onSurfaceVariant` | `#F4F6F7` / `#3A4147` | `#2E353A` / `#B7C0C6` | received bubble fill, timestamps/captions |
| `outline` / `outlineVariant` | `#C7CDD1` / `#DDE2E5` | `#4A5157` / `#3A4147` | borders (defined; not yet consumed by any screen) |
| `primary` / `onPrimary` | `#23282C` / `#FFFFFF` | `#3A4147` / `#FFFFFF` | top app bar chrome, group avatar, brand mark |
| `primaryContainer` / `onPrimaryContainer` | `#F6DFB8` / `#1B1F22` | `#4A3419` / `#F3EDE3` | outgoing message bubble |
| `secondary` / `onSecondary` | `#D98E2B` / `#1B1F22` | `#F2A83D` / `#14171A` | accent — FAB, unread badge, action icon tints, 1:1 avatar |
| `tertiary` / `onTertiary` | `#2E7BB6` / `#FFFFFF` | `#6FB8E8` / `#05283C` | "read" delivery mark |
| `error` / `onError` | `#B3261E` / `#FFFFFF` | `#F2B8B5` / `#601410` | error states |

`docs/design/palette-preview.html` renders this palette side by side with the two rejected
candidate palettes that were evaluated for this rebrand. See `docs/changes/NoMessagesTheme.kt.md`
and the per-screen `docs/changes/*.kt.md` files for the full call-site-by-call-site migration off
the previous hardcoded color constants.

## Entry point and state ownership

`NoMessagesApp(state: UiState, actions: UiActions)` is the only UI entry point. It
renders setup, lock, or an unlocked subtree from the root state. The unlocked
subtree owns navigation and short-lived form state; replacing `UiState` with
`unlocked=false` removes that subtree and disposes drafts, aliases, selections,
QR bitmaps, media players, and attachment viewer copies. Message, contact,
pairing, and attachment content is never cached by the UI.

Setup, unlock, import, and panic-password forms convert text to `CharArray` only
at submission. The visible fields are cleared before invoking `UiActions`, and
the temporary arrays are overwritten after the synchronous action returns.

## Implemented flows

- Setup with display name, main password and confirmation, and mandatory panic
  password and confirmation.
- Full-screen lock with no recovery or biometric claim.
- Chat and empty states with classic NoMessages palette, delivery ticks, composer,
  file/photo actions, and runtime-owned audio recording state.
- Visible Tor state on chat lists, chats, the group wizard, and settings.
  Offline and retrying copy states that outgoing messages remain on the device;
  no mailbox is implied.
- Contact list, local alias editing, fingerprint/pairing date, and QR
  reverification. Contact info can open a conversation before any message
  history exists.
- In-person pairing with CameraX permission handling, ZXing scanning, QR
  generation, SAS confirmation, local alias, response/confirmation QR exchange,
  expiry countdown, and an explicit final surface that keeps the local public
  confirmation QR visible until the peer scans it and the user taps Done.
  Failed or expired confirmation submissions restore the alias controls.
- Three-step group creation with 2 through 99 selected contacts plus the local
  member, producing 3 through 100 total members. Creation stays blocked until
  `checkGroup` publishes a newer result for the exact selected-ID snapshot and
  returns no missing-pair edges; stale empty results show only a progress state.
  Group chats expose authorized member removal and leaving through runtime
  actions. Pending, failed, and left groups show explicit localized banners and
  disable sending; an active audio recording can always be stopped.
- Settings for immediate lock, 5/15/30-second timeout, ciphertext export, onion
  copy marked sensitive on Android 13+, Tor bridges, and gated panic-password
  reset. Password-authenticated import is offered only on virgin setup, as
  required by SPEC section 4.4. Both transfer surfaces explain that migration
  requires a fresh export and retirement of the old installation; simultaneous
  or older copies are explicitly unsupported because they can prevent delivery.

## Attachment policy

The viewer consumes only `AttachmentUi.bytes`. It creates no intent, URI,
MediaStore entry, or temporary file.

- Images decode off the main thread through `ImageDecoder`, which applies image
  orientation metadata, requests a mutable software bitmap, and sets a target
  of at most 4,096 pixels per side and 8 megapixels before allocating it;
  encoded input is capped at 32 MiB.
- Plain text is capped at 256 KiB. HTML and script MIME types are not rendered.
- Audio and video are capped at 64 MiB and use `MediaPlayer` with an in-memory
  `MediaDataSource`. The source is a private copy that is overwritten on close.
- PDFs up to 16 MiB and 128 pages are copied into an anonymous `memfd`, parsed
  and rendered off the main thread with `PdfRenderer`, and displayed one page
  at a time. Rendered pages are limited to 2,048 pixels per side and 4
  megapixels. The renderer closes before the memory file is overwritten,
  truncated, and closed; no plaintext path is created.
- Unsupported or oversized formats receive an honest internal message.

## Inline media (2026-09-16, T4.8)

Photo, video, and audio attachments render inline in the chat bubble, WhatsApp-style, instead of
the generic file-icon+filename row that every other attachment type still uses. As with the
existing viewer, nothing here creates an intent, URI, MediaStore entry, or temporary file — every
byte stays decrypted only in process memory, and every decoded preview is a small derived artifact
(a downsampled bitmap or a float array), never the raw attachment bytes.

- **Audio bubble** (`ChatScreen.kt`'s audio branch of `MessageBubble`, backed by
  `AudioPlaybackCoordinator.kt` and `AudioWaveformDecoder.kt`): play/pause, a draggable/seekable
  ~48-bar waveform rendered on a `Canvas`, current/total time, and a 1x/1.5x/2x speed cycle. Only
  one audio bubble plays at a time app-wide — starting playback anywhere pauses (not stops, so
  position is preserved) whatever was already playing, arbitrated by `AudioPlaybackCoordinator`,
  which holds only the currently-playing attachment id and a pause callback, never a `MediaPlayer`
  or decrypted bytes itself. Voice messages are recorded as AAC-LC mono 16 kHz (~32 kbps) via
  `MemoryAudioEncoder`, muxed entirely inside an anonymous `memfd`; on a device/emulator where that
  encode path fails (observed occasionally on the project's emulator target, `MediaMuxer`+`memfd`
  specifically), the recorder transparently falls back to uncompressed WAV instead of failing the
  send — both paths stay memory-only, so the failure mode never risks writing plaintext to disk.
- **Photo bubble** (`ImageBubble`): a bounded thumbnail decoded via `BitmapFactory` with
  `inJustDecodeBounds` + a computed `inSampleSize` (never the full-resolution bitmap just to show a
  small thumbnail), cached by attachment id. Tapping opens the existing full-screen
  `AttachmentViewer` with pinch-to-zoom/pan (1x-6x) and swipe navigation between the other images in
  the same conversation, decrypting each neighbor on demand as it's opened rather than
  pre-decrypting adjacent images.
- **Video bubble** (`VideoBubble`): a poster frame decoded via `MediaMetadataRetriever` (bounded to
  512px, falling back to an unbounded `getFrameAtTime` if the scaled call fails on a given device),
  a duration pill overlaid on the corner, and a play icon that opens the same full-screen viewer
  used for photos, where `MediaPreview` now also exposes a draggable seek `Slider` with
  position/duration labels — a byproduct that improved full-screen audio scrubbing too, not just
  video.
- **Memory-only guarantee and its actual bound.** All three bubbles' previews (waveform buckets,
  downsampled thumbnails, poster bitmaps) are cached by `MediaPreviewCache`, a small
  `LinkedHashMap`-backed LRU bounded to **~24 entries / ~8 MiB**, keyed by attachment id. Bitmap
  payloads carry their own `dispose()` (zero + `recycle()`) invoked on eviction, on replacement, and
  on `clear()` — no cached `Bitmap` is left for the garbage collector to reclaim on its own schedule.
  `MediaPreviewCache.clear()` and `AudioPlaybackCoordinator.reset()` are both called synchronously
  from `NoMessagesController.lock()` and `closeSession()`, in the same place `_state.value.attachment
  ?.bytes?.fill(0)` already wipes the full-screen viewer's copy. The sentence above stating
  "Message, contact, pairing, and attachment content is never cached by the UI" predates this
  bounded preview cache and is no longer literally accurate as written: derived, small preview
  artifacts (never raw attachment bytes) are now cached, deliberately, for the lifetime of an
  unlocked session or until evicted by the ~24-entry/~8 MiB bound — and that cache is always wiped
  synchronously at lock, so nothing survives a lock/relock cycle. The correct claim is narrower than
  the original sentence: no *raw* attachment content is ever cached, and no cached content of any
  kind — raw or derived — survives a lock.

## Input/keyboard hardening, autofill, and capture-detection banner (2026-09-16, T4.9)

`FLAG_SECURE` (see "Recents / screenshot" above) is unchanged by this pass. T4.9 adds the layers
Android exposes beyond it: keyboard/IME privacy, autofill exclusion, accessibility data-sensitivity,
a third-party-keyboard disclosure banner, and a screen-capture-attempt notice. Full rationale and
the accessibility trade-off are in `docs/security-model.md`; this section covers the UI-visible
surface.

- **Private IME input everywhere.** Every `TextField`/`OutlinedTextField` in the app — the setup
  display-name field, all four password fields (main/confirm/panic/confirm-panic, and every dialog
  that reuses `PasswordField`), the chat composer, the contact alias field, the group-name field,
  the pairing local-alias field, and the Tor bridges field — now wraps its field in the new
  `PrivateImeScope` (`app/src/main/kotlin/dev/mx3/nomessages/ui/PrivateInput.kt`) and passes
  `privateKeyboardOptions(...)` instead of a bare `KeyboardOptions(...)`. `PrivateImeScope` uses
  `InterceptPlatformTextInput` (Compose UI 1.11.4, this project's resolved compose-bom
  2026.06.01) to OR `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` and
  `InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS` onto whatever `EditorInfo` the field itself already
  builds, so password variation / IME action bits the field depends on are preserved. See
  `PrivateInput.kt`'s file-level KDoc for why this is a composable wrapper rather than a literal
  `Modifier` extension, and why `PlatformImeOptions`/`platformImeOptions` was evaluated and rejected
  for this purpose (string-only `privateImeOptions` extra, no typed way to OR the two bits this
  needs). The read-only onion-address field in Settings is intentionally left untouched — it never
  opens an IME.
- **Autofill excluded.** `MainActivity.onCreate` sets `window.decorView.importantForAutofill =
  View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` once, which covers the whole Compose
  hierarchy hosted under that decorView — no per-field Compose-level lever was needed on top of it
  on this Compose version.
- **Third-party-keyboard banner.** A discreet, error-colored `Text` line — "Teclado de terceiros
  ativo: ele pode ver o que você digita" / "Third-party keyboard active: it can see what you type"
  — appears above the composer in `ChatScreen.kt` and above the password field in both
  `SetupScreen` and `LockScreen` (`SetupLockScreens.kt`) whenever `MainActivity` determines the
  system's default IME is not part of the OS image (not `ApplicationInfo.FLAG_SYSTEM` or
  `FLAG_UPDATED_SYSTEM_APP`). The decision threads down as a plain `Boolean` parameter
  (`thirdPartyImeActive`, default `false`) from `MainActivity`'s `setContent` through
  `NoMessagesApp` → `SetupScreen`/`LockScreen`/`ChatScreen`, not through `UiState`/
  `NoMessagesController` — it is a platform signal, not app/vault state, and keeping it out of the
  controller kept this change from touching any vault/business logic. It is recomputed on every
  `onStart`, so switching the default keyboard while backgrounded is picked up on next foreground;
  see `docs/security-model.md` for the "not observed until next onStart" caveat and the pure
  `isSystemIme` logic backing it (`UiLogic.kt`, tested in `UiLogicTest.kt`).
- **Screen-capture-attempt Snackbar.** `MainActivity`'s `setContent` now wraps its root content in a
  `Box` with a `SnackbarHost` (previously no `SnackbarHostState` existed anywhere in the app — this
  is the first one, added at the activity root rather than inside `NoMessagesApp.kt`, so it stays
  independent of `UiState`/screen navigation and covers every screen including the camera-capture
  overlay). On API 34+, `registerScreenCaptureCallback`/`unregisterScreenCaptureCallback` in
  `onStart`/`onStop` shows "Tentativa de captura de tela detectada" / "Screen capture attempt
  detected" through it when fired. `FLAG_SECURE` already blocks the capture itself; this only fires
  for whatever still gets attempted.

## Resources and accessibility

Default strings are PT-BR and the complete `values-en` set supplies English.
The launcher uses an original “NM” vector mark and no Meta asset. Controls have
localized content descriptions, large touch targets, system typography, and
dark/light Compose color schemes based on the app's own "Grafite e Âmbar"
palette (see "Visual identity" above for the full token table) — no longer the
classic WhatsApp green palette this section used to describe.

Backup/device-transfer rules exclude all app domains. `FLAG_SECURE`, activity
lifecycle lock, SAF selection, microphone permission, and camera capture remain
owned by `MainActivity` and runtime integration as defined by the contracts.

The merged manifest originally included the transitive EmojiCompat startup
initializer. It is explicitly removed, so text uses the platform emoji font
without configuring a downloadable-font provider. This follows the
[AndroidX initializer contract](https://developer.android.com/reference/androidx/emoji2/text/EmojiCompatInitializer).
The unused Compose test-manifest dependency is removed, and the debug manifest
removes the tooling PreviewActivity. Packaged-manifest verification rejects
these auxiliary activities and the emoji initializer.

Runtime-originated Controller notices/errors resolve through the same Android
resources. The Context-free MessagingEngine callback collapses to a localized
group-operation message at the Controller boundary; synchronous engine and
protocol validation failures collapse to the localized generic operation error
instead of exposing internal diagnostics.

## Verification

- Compose production and JVM test compilation passed with AGP 9.1.1,
  Gradle 9.3.1, Kotlin 2.3.21, JDK 21 and SDK 37.0, with warnings as errors.
  Android resources also compiled successfully.
- All six UI JVM cases passed in the centralized 17-test app gate. They cover
  attachment classification, delivery-state ticks, group-size boundaries,
  disposal before a late worker install, concurrent install/disposal with
  exactly-once cleanup, and exact-snapshot asynchronous group-check completion.
  Evidence: `app/build/test-results/testDebugUnitTest/` and
  `docs/development/build-logs/app-icons-fix.log`.
- A focused Android instrumentation fixture covers opening and rendering a
  generated two-page PDF through the anonymous-memory implementation; it has
  not yet run on a device.
- Subsequent local-only document-picker changes are included in the APK build
  gate tracked in `docs/development/build-report.md`.
- **Inline media (2026-09-16):** the instrumented suite (15/15, 0 failures, including
  `MemoryAudioEncoderTest` and `VideoPreviewDecodeTest`) ran on `emulator-5556`, and the photo and
  audio bubbles were visually validated with a full send/receive round trip across two paired
  emulators (`emulator-5556`/`emulator-5556`), including tap-to-zoom and play/pause. Pinch-zoom/swipe
  gesture simulation was not exercised live (no practical multi-touch primitive via `adb shell
  input`); that sub-case remains verified by code review and JVM-testable math only. Evidence:
  `docs/development/build-logs/media-inline-20260916/` (`session-log.md` plus numbered
  screenshots).

Device tests still require an Android 12+ device or emulator. Camera focus and
QR exchange must be exercised on two physical displays because the largest
PQXDH offer approaches QR version 40 capacity. Audio/video codec support also
depends on the codecs installed on the device; playback failure remains inside
the viewer and does not fall back to another app.

## QR scanner minimum resolution (2026-09-15, T3.3 run3)

The real pairing offer QR is version-40 at error-correction level L (177x177
modules, ~2.9 KB payload) — the largest a `nomessages:1:...` offer can reach.
Investigating T3.3's emulator-pair scanner failures (see
`docs/development/device-verification.md`, "T3.3 — run3", and
`docs/changes/PairingScreen.kt.md`) established two concrete facts worth
keeping visible here, beyond the emulator-specific findings:

- **A version-40 QR needs roughly 3-4 px per module for a reliable decode**,
  i.e. at least ~700px of frame just for the QR region. `PairingScreen.kt`'s
  `ImageAnalysis` previously had no `ResolutionSelector` and silently
  defaulted to CameraX's 640x480 fallback — `QrDecodingTest.kt`'s dedicated
  640x480 test case computes this concretely: at best ~2.5 px/module for this
  QR size, well under the reliable-decode threshold, even filling nearly the
  entire frame height with the QR and even on a noise-free synthetic render.
  A real camera frame (lens blur, compression artifacts, motion, perspective
  skew) has no more headroom than that synthetic case, and usually less.
- **This is a real risk on physical phones, not just an emulator artifact.**
  Any device whose camera stack or CameraX version resolves `ImageAnalysis`
  to a low default resolution — a cheap/older phone, a camera HAL that
  doesn't advertise higher `ImageAnalysis` streams, or simply CameraX picking
  a conservative default the way it did here — would face the same
  insufficient-pixel-density problem scanning the real pairing QR, even with
  perfect framing and a steady hand. The fix (`ResolutionSelector` requesting
  1920x1080, falling back to the closest available resolution) is a real
  hardware-independent risk mitigation, not just an emulator workaround; see
  `docs/changes/PairingScreen.kt.md` for the exact change and
  `docs/development/device-verification.md` for evidence that it is
  independently necessary alongside the rotation-fallback bug fix found in
  the same session.
