# T3.3 run2 — two-emulator two-device procedure — chronological log

All times UTC. Emulators: A = emulator-5556 (AVD nomessages35, TesteA), B = emulator-5560 (AVD nomessages35b, TesteB).
Both start this run already unlocked, vault created, home screen showing "Tor conectado" (state left by prior agent).
APK under test: HEAD build, commit e94c4fc (includes debug-only FLAG_SECURE capture switch and swallowed-exception logging).

## Timeline

- 18:44 — Session start. `adb devices` confirms both emulators attached. Home screens on both
  already show "Tor connected", "No chats yet" (state left by prior agent: vault created/unlocked).
- 18:44-18:45 — Read plan T3.3, release-checklist.md two-device procedure, prior
  device-verification.md ledger, PairingScreen.kt and NoMessagesController.kt pairing logic to derive
  the exact tap sequence (showPairing → receive → confirm → finish, two scan actions per side).
- 18:46:06 — `setprop debug.nomessages.allow_capture 1` + `settings put global hidden_api_policy 1` on
  both emulators (`getprop` confirms `1` on both).
- ~18:46:30 — `am force-stop` + `am start` MainActivity on both to pick up the property; both land
  on the lock screen (`01-*-after-restart.xml`).
- 18:47:0x — Unlock attempt 1: password typed correctly (dots shown) but the Unlock button tap
  missed because the IME shifted its bounds up ~410px; retried at the keyboard-adjusted coordinates
  (`03-*-after-unlock2.xml`, "Working…" at 18:47:28) → home reached by 18:47:44
  (`04-*-home-after-unlock.xml`), Tor bootstrapping ("Publishing the onion address").
- 18:47:44-18:51:31 — Attempted to poll for "Tor connected" on both; first three attempts (one
  backgrounded loop, one foreground retry) failed silently because `adb pull /tmp/...xml` under
  Git Bash with `MSYS2_ARG_CONV_EXCL=*` resolved `/tmp` against the current drive root
  (`E:\tmp`, not the real `/tmp` shown by plain `ls /tmp`), so the destination file never landed
  where `grep` looked. Fixed by using explicit `E:/tmp/...` paths. First successful check at
  18:51:31 shows "Tor connected" on both — actual bootstrap completion time between 18:47:44 and
  18:51:31, not pinned down more precisely due to this tooling gap.
- 18:51:56 — A: New chat → Show my QR → offer QR displayed, "Expires in 01:57" (approx. expiry
  18:53:56).
- 18:52:xx — `adb exec-out screencap -p` on A captures the QR screen live and correctly
  (`06-emuA-offer-raw.png`, `docs/development/build-logs/two-emulator-20260915/run2/06-*`).
  Confirms the run1 `FLAG_SECURE` capture blocker is fully resolved by the debug-only bypass.
- Cropping: initial heuristic (any row/col with >20 black pixels) over-captured the whole card
  (972×2212, includes buttons/text below). Tightened to: find the QR's column band by a high
  black-pixel-density threshold, then restrict the row search to only that column band and keep
  the single largest contiguous run of qualifying rows. Final crop: 719×719 with margin, decoded
  cleanly via `zxing-cpp` (`nomessages:1:...`, 2708 chars) — script:
  `crop_qr.py` (scratchpad, referenced by path in the final report).
- 18:53:16 — First injection into B: `adb -s emulator-5560 emu virtualscene-image wall
  "docs/development/.../07-emuA-offer-cropped.png"` via `cygpath -w` on a *relative* path →
  emulator process resolved it against its own cwd, poster stayed blank white
  (`10-`, `11-emuB-camera-preview*.png`). Root-caused by checking `cygpath -w` output directly
  (no drive letter) and confirming the emulator's actual cwd differs from the shell's.
- 18:53:16-18:54:26 — B on Scan QR screen throughout; original offer QR expired at ~18:53:56 before
  a successful decode was achieved (moot, since decode never happened anyway).
- 18:54:26-18:59:39 — Cancelled and regenerated a fresh offer on A (`14-`, `15-emuA-offer2-*.png`,
  same payload prefix as before — offer content is deterministic per identity, only the expiry
  differs). Injected with the **absolute** Windows path this time (`cygpath -w "$(pwd)/...")`)
  — poster now correctly showed the QR (`12-emuB-camera-preview3.png`, `17-*4.png`), moderately
  skewed by the fixed camera pose the `Walk_to_image_room` macro leaves active. Waited 15+ s,
  re-injected an upscaled (2160×2160, nearest-neighbor) version — still no decode
  (`16-emuB-after-scan2.xml`, `b_check5.xml`/`b_check6.xml`, not archived — ephemeral polls).
- 18:59:39-19:03:02 — Diagnostic isolation: generated a minimal 11-character QR
  (`19-diag-simple-qr.png`, independently verified decodable with `zxing-cpp` before injecting),
  injected it well-framed and nearly frontal (`20-emuB-diag-preview.png`) — still no decode after
  10s+. Checked `adb shell dumpsys package ... CAMERA` → `granted=true`. Checked
  `adb logcat -d | grep -iE "nomessages|camera|zxing|CameraX|ImageAnalysis"` → confirms clean
  `CameraX`/`CXCP` bind of `Preview`+`ImageAnalysis` (`StreamSpec resolution=640x480`, `Camera 10:
  Opened`), no errors after bind, no `AndroidRuntime`/`FATAL` crash. Tried mirrored, +90°, and
  270°-rotated source variants of the diagnostic QR (`21-`, `22-`, `23-*.png`) — none decoded
  either, weakening (but not fully disproving) a simple rotation/mirror-handling explanation in
  `ImageProxy.decodeQr()` (`PairingScreen.kt:400-435`).
- 19:03:02 — Backed out of pairing screens on both (`KEYCODE_BACK` ×2 each) back to home;
  confirmed both still show "Tor connected", 0 paired contacts, vault unlocked
  (`24-`, `25-emu*-cleanup.xml`, final home dumps). Session ends here — emulators left running.

## Conclusion

Two of the three blockers found in run1 (`FLAG_SECURE` capture, vault-creation) are confirmed
resolved. A new, narrower, well-isolated blocker was found specific to the two-emulator method:
the virtual-scene back camera's `ImageAnalysis` (640×480 YUV) stream never yields a frame the
app's `zxing`-based scanner can decode, for any QR content tried (dense real payload or a trivial
11-character diagnostic one), regardless of framing/skew/rotation/mirroring of the source poster.
`Preview` itself is confirmed live and correct throughout. Root cause not isolated without app
instrumentation (out of scope: no app code changed). See
`docs/development/device-verification.md` (`## T3.3 — run2`) for the full table, hypotheses ruled
out, and recommendation.
