# Native Tor and MLS implementation

Implementation uses published Arti 0.46.0, OpenMLS 0.9.0 + provider 0.6.0, tls_codec 0.5.0, JNI 0.21.1, libsodium-rs 0.2.4 / libsodium-sys-stable 1.24.0 (bundled libsodium 1.0.22), Rust 1.91 minimum. See native-api.md for exact Kotlin integration APIs. Cargo.lock captures the complete graph; builds use one worker with debug info and incremental caches disabled.

Implemented: deterministic offline onion derivation; ephemeral Tor signing keystore; bounded onion-only TCP overlay with per-peer circuit isolation, reply connection IDs, cancellation and shutdown; optional plain bridges with no clearnet fallback; typed Kotlin JNI APIs. MLS includes genuine key packages, group creation, add/Welcome/join, application encrypt/process, removal, exact approved-membership validation, coordinator validation for incoming commits/Welcome, identity-to-signature-key binding, replay handling through OpenMLS, and bounded opaque state snapshots. JNI buffers and exported state snapshots are zeroized after use. Native panic payloads are suppressed; exceptions use fixed messages.

Persistence contract: application must atomically save returned MLS snapshot and outbox/message mutation in SQLCipher before sends/ACKs. Native code never writes MLS secrets to files. Application must preserve original state on failure, wipe JVM buffers at lock, enforce cryptographic clique proofs and designated-coordinator authorization, order commits, and stop/kill the separate Android :tor process. Merely dropping an Android Service is not the zero-socket gate.

Remaining limitation: Arti's supported high-level state directory persists guard/introduction-point metadata as filesystem data even with ephemeral keystore. The signing keystore is explicitly configured as ephemeral, so onion private keys are intended to remain in memory; filesystem/device verification is still pending. Public directory cache is also filesystem based. Android cache directories are not RAM filesystems; a `/proc/self/fd` memfd does not supply the directory operations Arti needs. Caller must use distinct session state paths, clear abandoned state on startup and delete after shutdown. This does not prove forensic absence of metadata or secure deletion on flash. No stronger claim is made. Tor designs sampled guard history to persist between invocations ([guard algorithm](https://spec.torproject.org/guard-spec/algorithm.html)). The new TorStateSnapshot helper preserves guard/vanguard/circuit-timeout JSON through encrypted vault metadata and restores it before startup. Root integration is implemented; validation remains pending; active-session plaintext metadata, flash remnants, and abrupt-death checkpoint lag remain limitations. Onion-service metadata is excluded because its introduction-point records require ephemeral keys that are unavailable after lock.

Current validation: the bundled libsodium 1.0.22 host build passed all 10 focused crypto tests and produced the JNI shared library; `ldd` confirms no dependency on system libsodium. Logs: `build-logs/native-crypto-bundled.log` and `build-logs/native-crypto-bundled-build.log`. This supersedes the historical system-libsodium 1.0.18 artifact. Full Tor/MLS host compilation then passed five Tor/wire unit tests and three MLS integration tests (build-logs/native-full-host-j2.log). A separate Kotlin/JNI probe passed group transaction/codecs, exception mapping and offline Tor identity (build-logs/native-jni-probe.log). Shipping Android builds remain separate gates. Tests cover 3 peers, persistent snapshots, authenticated delivery, replay rejection, unapproved membership, removal isolation and parser bounds. Tor offline tests cover stable onion derivation, destination filtering, generation isolation, bounded frames, flush backpressure and cancellation. An explicitly ignored live Tor self-roundtrip integration test is included; Android two-device delivery and socket accounting remain external release gates until actually run.

MLS creation/join explicitly retain two past epochs and use 32 out-of-order sender generations with maximum forward distance 1024. This tolerates limited delayed delivery at the cost of retaining recent epoch secrets; older messages fail closed. A dedicated roundtrip test checks acceptance within the window and rejection after eviction. Upstream memory storage is vendored under its MIT license with a narrow zeroization-only patch documented in native/vendor/openmls_memory_storage/NOMESSAGES-PATCH.md. Application license is AGPL-3.0-or-later.

Transport records now cap application payload at 16,388 bytes (four-byte application header plus 16 KiB padded body), with an additional native u32 prefix. At most 128 streams and 32 queued incoming frames bound unauthenticated frame allocation. Arti futures streams are explicitly adapted to Tokio I/O. Same-connection replies wait for actual flush, with cancellation and a 40-second timeout.

TorStateSnapshot has four JVM tests covering guard restore, exclusion of hss/keystore/cache, malformed and oversized inputs, digest/path rejection before writes, and symbolic-link safety. Live checkpoints compare two bounded captures and never replace a previous snapshot with absent/empty guards. The after-process-death capture is authoritative. The centralized JDK21/Kotlin2.3.21 run compiled core production and test sources with warnings treated as errors, including both native bridges and this helper. After aligning the JUnit launcher, all four TorStateSnapshot tests passed in the root Gradle run. Evidence: core/build/test-results/test/TEST-dev.mx3.nomessages.core.nativebridge.TorStateSnapshotTest.xml.

Backup limitation: MLS snapshots include sender generations and ratchet keys. Import supports fresh, sequential migration after the original installation is retired. Reusing stale backups or running cloned live states is unsupported and can cause key/generation reuse and replay rejection. OpenMLS randomizes a reuse guard before deriving the final message nonce, so stale restoration is not proof of deterministic nonce reuse. Recovery from stale group state requires an authenticated fresh join/rekey policy.

Transport review fixes: start-failure cleanup atomically compares/takes its own generation; explicit close cancels pending writes; Tor JNI argument buffers do not grow after receiving the seed; partially decoded MLS outputs and failed result snapshots are wiped. MLS snapshot/result encoding computes bounded capacity before copying secrets so vector growth cannot abandon intermediate secret buffers. Connect/first-flush and reply timeouts are 40 seconds under 45-second parent RPCs. Android shutdown uses 2.5-second grace plus 5-second death verification (7.5 seconds plus dispatch), with confirmed death required before final capture/cleanup. Regression tests for stale-generation cleanup and cancellation of an unflushed reply passed in the five-test Tor/wire unit gate recorded in `build-logs/native-full-host-j2.log`.

The first live Tor test failed before network bootstrap because Arti requires
the application to choose a Rustls CryptoProvider. The fix pins Rustls 0.23.44
with ring/std/tls12 and installs the ring provider before creating the runtime.
The focused regression passed bootstrap but timed out connecting to its synthetic
onion service after 350 seconds. The diagnostic run also completed bootstrap and
launched the onion service, reported guard connection failures, and timed out
without delivering a frame (449 seconds total). This is a failed live gate, not
evidence of successful delivery or a proven application defect. Diagnostics are
enabled only in the opt-in test. Further verification on a representative device
and network must distinguish connectivity, host/resource effects and transport
defects; production retains bounded timeouts. Live delivery is not yet verified;
previous offline suites are not being repeated. See build-logs/native-tor-live.log,
build-logs/native-tor-live-tls-fixed.log, build-logs/native-tor-diagnostics.log and
[the provider contract](https://docs.rs/rustls/latest/rustls/crypto/struct.CryptoProvider.html).

Maximum-size host gate passed: `GroupCapacityProbe.java` generated 100 real MLS
key packages, added 99 peers, joined member 100 and authenticated/decrypted an
application message. It measured a 36,049-byte Welcome and 118,941-byte group
snapshot, and calculated invitation storage with all 4,950 proof records.
Proof signatures in the sizing fixture are synthetic; this is not a replacement
for clique-authentication tests. No sockets were opened. Evidence:
`build-logs/group-capacity-probe.log`; Gradle entry point `:core:groupCapacityProbe`.

Full Android native release builds subsequently completed for both supported
ABIs, including crypto, Tor and MLS: arm64-v8a in 63m29s and x86_64 in 48m04s.
The retained libraries contain all 12 required JNI exports and have ELF64 load
segments aligned for 16 KiB pages. Their individual verification is recorded in
`build-logs/native-arm64-verification.log` and
`build-logs/native-x86_64-verification.log`. Packaging and running these libraries
inside an APK remain separate checks. After temporary host files were lost,
the full host JNI was recovered in `native/target/debug/libnomessages.so` in 39m01s
without rerunning the previously passing suites
(`build-logs/native-host-recovery.log`).

The subsequent controlled Kotlin/JNI probe used a fresh random onion identity
and fresh state/cache directories, after Gradle and native compilers exited.
It failed inside `TorNative.start` after 184.1 seconds, before reporting
bootstrap completion or attempting delivery. This duration is consistent with
the configured 180-second bootstrap deadline, but the fixed JNI exception does
not expose the underlying cause. Shutdown reached `STOPPED` and temporary
state was removed. This is a failed live gate; the cause is still unresolved.
No identical retry was launched. Evidence:
`build-logs/tor-delivery-isolated.log`.

## Live Tor delivery — 2026-09-15

The live gate now passes. Four consecutive runs completed on a residential
network under WSL Ubuntu 24.04, after the T2.1 fix (wait for onion descriptor
publication before declaring the transport usable). No bridges were configured
in any run; every circuit used ordinary guards. Each run used a fresh onion
identity and fresh state/cache directories, and removed them at the end.

| Run (log under `build-logs/`) | Harness | Bootstrap | Descriptor published | First frame after publication | Result |
|---|---|---|---|---|---|
| `tor-live-20260915T133138Z.log` | `cargo test --all-features --locked --test tor_live -- --ignored --nocapture` | cold, no guard checkpoint; not separately timestamped in this log | 102.5 s after bootstrap completion | 3.9 s | `test result: ok. 1 passed`, 134.20 s total |
| `tor-delivery-probe-20260915T133138Z.log` | `TorDeliveryProbe` via `scripts/tor-delivery-probe.sh` | 16.4 s | 20.2 s later, at 36.6 s | 5.6 s, at 42.2 s | PASS at 43.4 s |
| `tor-delivery-probe-20260915T133510Z.log` | `TorDeliveryProbe` via `scripts/tor-delivery-probe.sh` | 14.5 s | 18.0 s later, at 32.4 s | 7.1 s, at 39.5 s | PASS at 41.1 s |
| `tor-delivery-probe-20260915T133553Z.log` | `TorDeliveryProbe` via `scripts/tor-delivery-probe.sh` | 56.6 s | 14.1 s later, at 70.7 s | 5.4 s, at 76.1 s | PASS at 78.2 s |

Measured envelope across the three JNI probes: bootstrap 14.5–56.6 s,
descriptor publication 14.1–20.2 s after bootstrap, first frame 5.4–7.1 s after
publication, end-to-end PASS 41.1–78.2 s. The Rust test is the outlier on
publication (102.5 s) because it bootstrapped cold, with no persisted guard
checkpoint and with repeated directory-fetch retries visible in the log; its
time to first frame after publication (3.9 s) is the lowest of the four. Time
to first frame after publication is therefore in the single-digit seconds in
every run, and the previously configured 180-second connect budget was never
the binding constraint once publication was awaited.

Root cause of the earlier failures: `start` returned and marked the transport
ONLINE immediately after `launch_onion_service_with_hsid`, without observing
`status_events()`. The self-connect therefore began before the descriptor had
been uploaded to the HSDirs and before introduction points existed, so the
client had nothing to connect to and the attempt consumed the whole deadline.
T2.1 fixed this by waiting for publication and exposing a distinct `PUBLISHING`
readiness state. The guard connection failures recorded on the previous host
(`Could not connect to guard`, bootstrap exceeding 180 s) did **not** reproduce
here. The plausible explanation is a restrictive network on the previous host;
that is a hypothesis which was not proven, only not reproduced.

What this gate does **not** cover, as the probe itself states on its PASS line
("device socket accounting and messaging remain separate gates"): per-PID
socket accounting on a real Android device, and real messaging over the live
transport. Both remain Phase 3 gates. These runs are host-side (WSL) and
self-addressed: one process reaching its own onion service. Two-device
delivery, behaviour under mobile networks, and the Android `:tor` process
lifecycle are still unverified.
