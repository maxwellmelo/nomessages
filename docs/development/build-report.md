# Build and toolchain report

Updated: 2026-09-14

## Reproducible environment

- Gradle 9.3.1 (wrapper SHA-256 pinned), Android Gradle Plugin 9.1.1.
- Eclipse Temurin JDK 21.0.12.1+1, downloaded with a pinned SHA-256.
- Kotlin 2.3.21; JVM bytecode target 21. The current libsignal 0.102.2
  artifacts require Java 21.
- Android compile SDK 37.0, target SDK 35, build tools 36.0.0, platform tools, min SDK 31.
- Tool and dependency caches default to the ignored project directory
  `.tools/toolchains`; native build outputs use `native/target`. Override with
  `NOMESSAGES_TOOLS_DIR` and `CARGO_TARGET_DIR` when needed. Use a persistent
  filesystem with sufficient space; deleting active caches interrupts a build.
- Gradle uses one worker, no daemon, in-process Kotlin compilation, a
  768 MiB maximum heap and a 768 MiB maximum metaspace.

## Disk budget

`scripts/build-android.sh` runs under `scripts/guard-build.py`. The current
budget is 8 GiB for generated project data and at least 10 GiB free on the
relevant filesystems. The monitor samples allocated disk usage every second
and stops at 7 GiB used or 11 GiB free, reserving 1 GiB for writes between
measurements and shutdown. This is process monitoring, not a filesystem quota.
It counts tool/dependency caches, native and Gradle outputs, APK artifacts,
JNI inputs and build logs; it never deletes data or signals unrelated builds.

For selected Gradle tasks, use the same guard explicitly:

```bash
export JAVA_HOME="$PWD/.tools/toolchains/jdk-21"
export ANDROID_HOME="$PWD/.tools/toolchains/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$PWD/.tools/toolchains/gradle-home"
export ANDROID_USER_HOME="$PWD/.tools/toolchains/android-user"
export TMPDIR="$PWD/.tools/toolchains/tmp"
mkdir -p "$ANDROID_USER_HOME" "$TMPDIR"
python3 scripts/guard-build.py -- ./gradlew --no-daemon --max-workers=1 \
  "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMPDIR" \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

An exhausted budget returns status 75. Preserve completed artifacts, stop the
build, and remove only disposable intermediates before continuing. Do not
restart already-passing tests just to recover deleted reports. The guard was
checked with the current disk state, a rejected preflight that never launched
its command, and artificial crossings that terminated owned children, including a
grandchild detached with `setsid`, without allocating large files. Gradle
detaches its daemon into another process group; the guard tracks descendants
by parentage and process creation time, including after reparenting.

Bootstrap and build:

```bash
scripts/build-android.sh --bootstrap
```

The normal command is the complete path: it builds/tests the host native
library, cross-builds both Android native libraries, runs core and app unit
tests plus Android lint, verifies both JNI inputs, and assembles the app and
instrumentation-test APKs. Building the test APK does not execute device tests.
It refuses to assemble an APK if either NoMessages JNI library is missing.

Host-only core tests:

```bash
scripts/build-android.sh --core-only
```

For Kotlin/Android source diagnostics before a native build, explicitly skip
native work and invoke compile/test tasks without assembling an incomplete APK:

```bash
scripts/build-android.sh --skip-native --core-only
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --max-workers=1
```

Include the full host JNI, Tor, and MLS test suite after the native module is available:

```bash
scripts/build-android.sh --core-only --with-native-host
```

Build the full native library for both Android ABIs and package it in the APK:

```bash
scripts/build-android.sh --bootstrap --with-native-android
```

This native path installs a capacity-limited subset of NDK r27d, adds only the
two required Rust Android standard libraries, builds one target at a time, and
records ELF headers for 16 KiB page-size inspection.
After assembly, `scripts/verify-apk.py` checks the packaged architectures,
required JNI entry points (including Tor and MLS), native segment and ZIP
alignment, APK signatures, and the compiled backup/cleartext manifest policy. Its hash identifies
the inspected APK. This inspection does not launch the app or satisfy device gates.

Each build preserves its terminal output under `docs/development/build-logs/`.
The checked GitHub Actions workflow runs the same pinned bootstrap/build path on
an Ubuntu runner and uploads the debug-signed APK, test/lint reports, manifest
dump, ABI listing, and build logs without signing secrets. It is split across
three jobs; see "Continuous integration" at the end of this report.

## Dependency contract

- Core: kotlinx-coroutines 1.10.2, libsignal-client 0.102.2,
  JUnit Jupiter 5.13.4. The `com.google.protobuf` Gradle plugin and the
  `protobuf-javalite` runtime were removed (task T4.5): no generated class
  was ever consumed, and `Wire.kt` parses the bounded protobuf subset by
  hand. The schema now lives at `docs/development/pairing.proto` as
  reference material. `:core:test` was rerun after the removal on
  2026-09-14 (WSL Ubuntu, Temurin JDK 21.0.12.1, Gradle 9.3.1,
  `--offline` against the bootstrapped `gradle-home` cache): 64 cases,
  zero failures, two conditional skips. `:core:dependencies
  --configuration runtimeClasspath` resolves completely and contains no
  `com.google.protobuf` coordinate; libsignal-client 0.102.2 brings only
  kotlinx-coroutines, kotlinx-serialization and kotlin-stdlib.
- Android: Compose BOM 2026.06.01 and Material 3, Activity Compose 1.13.0,
  Lifecycle 2.10.0, CameraX 1.6.1, ZXing 3.5.4, AndroidX SQLite 2.6.2,
  SQLCipher Android 4.19.0, and matching libsignal-android 0.102.2.
- APK Java resources exclude desktop Signal `.so`, `.dylib`, and `.dll` files.
  Android JNI libraries come from the matching Android artifact; the host
  library remains available to JVM tests.
- Maven repositories are restricted to Google, Maven Central, Gradle Plugin
  Portal for plugins, and Signal's official build-artifact repository limited
  to the `org.signal` group.

## Validation ledger

| Gate | Status | Evidence or blocker |
|---|---|---|
| JDK 21 and Android SDK bootstrap | Passed | Temurin `java` and `javac` 21.0.12.1, `android-37.0/android.jar`, build tools 36.0.0, and platform tools 37.0.1 verified. Gradle 9.3.1 wrapper bootstrapped successfully. `build-logs/sdk37-bootstrap.log` and `build-logs/gradle93-bootstrap.log`. |
| Core production/test compilation | Passed | Kotlin 2.3.21 with warnings as errors completed under JDK 21; `docs/development/build-logs/core-jdk21-20260914T004451Z.log`. |
| Core JVM tests | Passed: 62; skipped: 2 | BOM-aligned JUnit launcher fixed discovery. `core/build/test-results/test/` records 64 cases, zero failures/errors, and two conditional Linux cleanup fault-injection cases skipped. `build-logs/core-app-jdk21-sdk36.log`. Re-executed on 2026-09-14 after the protobuf plugin removal (T4.5) with `./gradlew --offline :core:test` and `NOMESSAGES_NATIVE_DIR` pointing at the bootstrapped host `libnomessages.so`: identical result (64 cases, zero failures/errors, two skips) in `core/build/test-results/test/`. |
| Conditional cleanup fault cases | Passed: 2 | The two previously skipped cases were executed separately with JDK 21, the compiled production/test classes, the full host JNI and `vault_cleanup_faults.c` via `LD_PRELOAD`. Both passed with actual deletion denial; the original Gradle XML remains intact. `build-logs/vault-cleanup-fault-gate.log`. |
| Android production and JVM-test Kotlin compilation | Passed | AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.3.21 / SDK 37.0, with warnings treated as errors. `build-logs/app-icons-fix.log`. Later document-picker and storage-capacity changes also compiled successfully in `build-logs/apk-assembly.log`, before the host restart interrupted packaging. |
| App JVM tests | Passed: 26 | Oito messaging-policy, três sensitive-buffer, sete transport-supervisor, seis UI/resource-ownership e, desde 2026-09-15, dois casos de `Sha3_256Test` (vetores FIPS 202 e comparação com o provedor do JDK); zero falhas ou skips. Contagem original da linha, de 17 casos: The successful Gradle output survives in `build-logs/app-icons-fix.log`; the per-case XML was inspected before the host restart and was subsequently lost with the temporary app build directory. |
| Native host crypto, Tor and MLS | Passed: 18 | Ten bundled-libsodium crypto cases, five offline Tor/wire cases and three MLS integration cases. Separate Kotlin/JNI probes passed three-member integration and 100-member join/decryption. See `native-report.md` and `crypto-report.md`. |
| Live Tor delivery | Passed: 4 runs | 2026-09-15, residential network under WSL Ubuntu 24.04, no bridges. One Rust `tor_live` run (`build-logs/tor-live-20260915T133138Z.log`: descriptor published after 102.5 s, first frame 3.9 s later, `1 passed` in 134.20 s) and three Kotlin/JNI probe runs (`build-logs/tor-delivery-probe-20260915T133138Z.log`, `...T133510Z.log`, `...T133553Z.log`: bootstrap 16.4/14.5/56.6 s, descriptor 20.2/18.0/14.1 s later, first frame 5.6/7.1/5.4 s after publication, PASS at 43.4/41.1/78.2 s). Earlier failure (`build-logs/native-tor-diagnostics.log`) was caused by declaring ONLINE before descriptor publication, fixed in T2.1. Device socket accounting and real messaging remain separate gates. See `native-report.md`. |
| Android arm64 native runtime | Passed | Full release build with Tor, MLS and crypto completed in 63m29s. 15,052,720-byte library; ELF64, all 12 JNI exports and 16 KiB load alignment verified. `build-logs/native-arm64-verification.log`. |
| Android x86_64 native runtime | Passed | Full release build completed in 48m04s. 17,105,200-byte library; ELF64, all 12 JNI exports and 16 KiB load alignment verified. `build-logs/native-x86_64-verification.log`. |
| Debug APK | Assembled | `build-logs/apk-selected-icons-assembly.log`: `assembleDebug` and `assembleDebugAndroidTest` completed. App APK: 101,043,251 bytes; test APK: 2,449,872 bytes. Packaged verification is being completed separately. |
| Android lint | Failed: tool memory limit | The same run later failed at `lintAnalyzeDebug` with `OutOfMemoryError: Metaspace` at the 384 MiB cap. The cap is now 768 MiB, with the heap still 768 MiB. Only the failed lint task needs another execution; this is not a product lint result. |
| Manifest backup/cleartext policy | Passed packaged inspection | Explicit false backup/cleartext attributes, minimum API 31, compiled cloud-backup and device-transfer exclusions and removed font initializer/auxiliary activities verified in the APK. `build-logs/apk-packaged-verification.log`. Device backup behavior remains a separate gate. |
| Packaged ABI contents and signature | Passed | All 12 packaged native libraries are ELF64 and compatible with 16 KiB pages; both NoMessages libraries retain all required JNI exports. ZIP alignment and debug APK v2 signature verified. No 32-bit, testing or desktop JNI is packaged. `build-logs/apk-packaged-verification.log`. |
| Android instrumented tests | Passed: 12 | Emulador Android 15 (API 35) `x86_64` com WHPX, `emulator-5556`. `OK (12 tests)` em `build-logs/android-test-emulator-5556-20260915T143135Z.log`. Três execuções anteriores falharam e estão arquivadas; ver "Instrumented tests (2026-09-15)". |
| Android runtime/device gates | Parcial: emulador executado, aparelho pendente | O emulador Android 15 `x86_64` cobriu a suíte instrumentada (linha acima). Os gates que exigem hardware real (3, 4, 5, 8, 10) continuam pendentes de dois aparelhos arm64 descartáveis. |

The normal bootstrap intentionally omits the Android NDK. The explicit native
mode pins NDK r27d and selectively extracts its compiler, linker, required LLVM
runtime files, and sysroot under the configured tools directory. It avoids the
multi-gigabyte full NDK extraction while preserving a real Android cross-linker.

The earlier SDK 36 bootstrap was superseded by the dependency-required SDK 37.0.
The app keeps minSdk 31 and targetSdk 35. NDK r27d and both Rust Android targets
were installed successfully; its required libc++/unwind files are included.
Full Android native linking subsequently passed for both supported ABIs.
Evidence: `build-logs/ndk-bootstrap.log`, `build-logs/native-android-arm64.log`
and `build-logs/native-android-x86_64.log`.

Android dependency alignment: SQLCipher 4.19.0 AAR metadata requires API 37,
and libsignal-android 0.102.2 requires core library desugaring. The app now uses
AGP 9.1.1, Gradle 9.3.1, SDK 37.0 r02, and desugar_jdk_libs 2.1.5.
Android uses AGP built-in Kotlin; the shared Kotlin/Compose version remains
2.3.21. NDK r27d stays explicitly pinned. The earlier core JVM results were
obtained with Gradle 8.13; the Android production/test compilation and 17 app
unit tests subsequently passed with Gradle 9.3.1. Reserved-resource and deprecated
Compose-icon diagnostics were fixed without suppressing warnings.
Sources: [AGP 9.1.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes),
[built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin),
[desugaring](https://developer.android.com/studio/write/java8-support).

## Interrupted packaging and recovery

The host restarted during DEX assembly on 2026-09-14. The interrupted
`build-logs/apk-assembly.log` has no Gradle completion result and is not an APK
pass. Temporary tools, app intermediates and the host JNI probe binary were
erased. Source, core test reports, build logs and both verified Android native
libraries remain in the workspace. Recovery reuses those Android libraries and
reinstalls the pinned JDK/SDK; previously passing test suites are not rerun just
to reconstruct deleted reports. The controlled fresh-identity Tor probe requires
rebuilding its lost host JNI library before execution.

A second attempt failed after files under `/tmp` disappeared during Gradle
configuration, without another host restart. The first causal errors were
`NoSuchFileException` for instrumentation caches and the configuration report,
not a Kotlin diagnostic; the excluded log is summarized below. The root
filesystem then had 31 GiB available. Recovery was moved to
`.tools/toolchains`, `app/build` was restored as a real directory, and the lost
host JNI library was rebuilt under `native/target` in 39m01s
(`build-logs/native-host-recovery.log`). Neither previously passing JVM tests
nor Android native compilation are needed again for this recovery.

That attempt produced `apk-recovery-assembly.log`, a 2.9 MB, 25,297-line failure
log excluded from Git by `.gitignore`. Summary of its content:

- Command: `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`,
  Gradle 9.3.1 re-downloading its distribution into a single-use daemon, with
  `GRADLE_USER_HOME` still under `/tmp/nomessages-tools/gradle-home`.
- Failure 1, root project configuration: 68 dependency artifacts (AGP
  `gradle-settings-api`, ASM, protobuf, tink, jaxb, JNA, Kotlin build
  statistics and others) failed `MergeInstrumentationAnalysisTransform` with
  `IllegalStateException: Could not serialize types map to a file` on
  `.../transforms/<hash>/transformed/analysis/instrumentation-dependencies.bin`.
- Failure 2, build teardown: `NoSuchFileException` on
  `/tmp/nomessages-tools/gradle-home/.tmp/configuration-cache-report<n>.html` while
  Gradle moved its spooled configuration-cache report out of the temporary
  directory.
- Cause: every one of the 71 `NoSuchFileException` occurrences (69 distinct
  paths) lives under `/tmp`, which the host cleaned while Gradle held those
  files open. It is an environment fault, not a source defect: the log contains
  no Kotlin or Android compiler diagnostic and no task ever started.
- Recovered from the attempt: nothing was built. The run ended with
  `BUILD FAILED in 8m 45s` on line 25,296, followed only by
  `Configuration cache entry stored.` as the last line of the file.
  Recovery came from the following runs, which are versioned:
  `build-logs/persistent-tools-recovery.log` (JDK 21, SDK 37.0 r02 and build
  tools 36.0.0 reinstalled under `.tools/toolchains`),
  `build-logs/persistent-gradle-recovery.log` (Gradle 9.3.1 running on the
  persistent JDK) and `build-logs/native-host-recovery.log` (host JNI library
  rebuilt). The bulk of the excluded file is the repetition of those two Java
  stack traces; nothing else is lost by keeping only this summary.

## Icon dependency reduction

The guarded recovery build completed production Kotlin compilation, then spent
an extended period converting the Material extended icon collection to DEX.
The active compiler had `material-icons-extended-release-runtime.jar` open:
87,547,989 bytes and 11,105 classes, while the app imports only 26 icons. Thread
and heap snapshots showed active D8 work, with no full GC or confirmed OOM.
The build was deliberately stopped to remove this unnecessary input; the
resulting daemon-disappeared message in `build-logs/apk-guarded-assembly.log`
records that interruption, not an unexplained crash.

The app now depends on `material-icons-core:1.7.8` and includes the 19 required
extended vectors as local Kotlin source (51,130 bytes). The other seven come
from the core collection. Selected source was taken from Google's official
matching sources JAR, with Apache notices preserved and only packages relocated.
A one-time comparison confirmed byte-identical drawing source after namespace
normalization and that every changed import resolves. No messaging, vault or
native source changed, and previously passing suites were not rerun. License
and source hash are recorded in `THIRD_PARTY_NOTICES.md`.

This addresses a documented cost of the large icon artifact while retaining
the specified classic appearance; see [Android icon guidance](https://developer.android.com/develop/ui/compose/graphics/images/material).
The follow-up assembly reused completed transforms and native libraries under
the same disk budget. Production and instrumentation Kotlin compilation and
both APK assemblies passed; the later lint failure was specifically the
metaspace limit, now increased as recorded above. APK inspection subsequently
passed after correcting the verifier to accept the decimal minimum-SDK value
printed by pinned aapt2. The first verifier failure is preserved in
`build-logs/apk-verification.log`; it did not indicate a bad APK.

Verified app APK SHA-256:
`c42b35c48202fdf731eebcddec8d044f1fffcc948632789a20220ff99169a537`.
It uses the development package and Android debug signing certificate; it is
not a production-signed release or a device-security pass.

The changed dependency graph caused Gradle to extract a second identical
Signal AAR native tree. All eight duplicate library pairs were checked with
SHA-256 and atomically replaced with hard links to the identical immutable
cache inputs, preserving every original path. This reclaimed 947.2 MiB without
deleting the native compilation cache. Evidence:
`build-logs/signal-cache-dedup.log`.

## Continuous integration

`.github/workflows/android.yml` (task T6.1) is split into three jobs on
`ubuntu-24.04`, each with `timeout-minutes: 180` and the workflow-wide
`permissions: contents: read`. No job needs a wider token: artifacts are
published through the `actions/upload-artifact` service API. Every action is
pinned to a 40-character commit SHA.

| Job | `needs` | Command | Produces |
|---|---|---|---|
| `host-core` | — | `build-android.sh --bootstrap --core-only --with-native-host` | `native/target/debug/libnomessages.so`, `cargo test --all-features --locked`, `:core:test`, `:core:nativeBridgeProbe`, `:core:groupCapacityProbe` |
| `native-android` (matrix `arm64-v8a`, `x86_64`) | — | `build-android.sh --bootstrap --skip-gradle --no-native-host --with-native-android --abi <abi>` | one `libnomessages.so` per ABI, uploaded as artifact `nomessages-jni-<abi>` |
| `app` | `native-android` | `build-android.sh --bootstrap --app-only --skip-native --with-ndk` | `:app:testDebugUnitTest`, the Python outbox regression, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, `verify-apk.py`, then the `unzip -Z1` ABI listing and the `aapt2` manifest policy check |

`app` depends on `native-android` alone. It consumes the `nomessages-jni-*`
artifacts and nothing `host-core` produces, so adding `host-core` to `needs`
would serialize packaging behind the longest job of the workflow for no gain. A
`host-core` failure still fails the workflow run.

The two `aapt2` assertions in the `app` job are a backstop, not the primary
manifest gate: `scripts/verify-apk.py` runs earlier in the same job and owns the
policy (`allowBackup`, `fullBackupContent`, `usesCleartextTraffic`, `minSdk`,
package name, absent auxiliary activities, backup rules). Both `grep` patterns
are anchored on the attribute *value*; the earlier form
(`android:allowBackup.*0x0`) matched the `0x0` inside the attribute resource id
`(0x0101000d)` and therefore could not fail, even for `=true`.

The `app` job downloads both `nomessages-jni-*` artifacts and installs them under
`app/src/main/jniLibs/<abi>/libnomessages.so` before Gradle runs, so the ABI matrix
is cross-built once, in parallel, and never twice inside the packaging job.
`verifyNoMessagesNativeLibraries` in `app/build.gradle.kts` still refuses assembly
if either library is missing, which makes a lost artifact a build failure rather
than a silently thinner APK.

### Caches

Three `actions/cache` entries, all keyed on content rather than on a run number:

| Path | Key inputs | Note |
|---|---|---|
| `~/.cargo/registry/{index,cache}`, `~/.cargo/git/db` | `native/Cargo.lock` | shared by `host-core` and `native-android` |
| `.tools/toolchains` (excluding `tmp`, `downloads`, `android-user`) | `scripts/bootstrap-tools.sh`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.properties`, the four `*.gradle.kts` files | two key namespaces: `toolchain-base-*` (no NDK, `host-core`) and `toolchain-ndk-*` (`native-android` and `app`), so the NDK-bearing tree never overwrites the smaller one; the restore-key ladder still lets each job warm-start from the other |
| `native/target` | `native/Cargo.lock` plus the hash of `native/Cargo.toml`, `native/src/**` and `native/tests/**` | separate key per job (`native-target-host-*`, `native-target-<abi>-*`); restore keys fall back to the lock hash alone |

The NDK-bearing toolchain used to be stored under three keys
(`toolchain-ndk-*-<abi>` per matrix entry plus `toolchain-ndk-app-*`), although
the three trees are identical: `scripts/build-android.sh` calls
`bootstrap-tools.sh --with-ndk` without forwarding any ABI, and that script
always extracts the same NDK subset and adds both rustup targets. The per-ABI
and per-job suffixes were removed so the same bytes are not stored three times
against the GitHub 10 GiB per-repository budget.

`CARGO_INCREMENTAL=0` and the three `CARGO_PROFILE_*_DEBUG=0` variables reduce
the size of those target directories; whether the resulting set of caches fits
the GitHub 10 GiB per-repository budget has not been measured (see "Not yet
verified"). Caches are evicted LRU, so an over-budget repository degrades speed,
never correctness.

`ANDROID_USER_HOME` is set to `${{ runner.temp }}/android-user` in every job.
AGP generates the debug signing keystore there, and the runner temp directory
appears in no `path:` of any cache entry, so that private key cannot reach a
restorable Actions cache. `scripts/build-android.sh` honours an inherited value
(`${ANDROID_USER_HOME:-$TOOLS_DIR/android-user}`); the
`!.tools/toolchains/android-user` exclusion is kept as a redundant defence
rather than as the only one.

### Parallelism and the disk guard

`scripts/build-android.sh` used to pass `--jobs 1` to every Cargo invocation,
which is what made the Rust stage take about 150 minutes — inside a 180-minute
budget with no margin. The default is now `nproc`, overridable through
`CARGO_BUILD_JOBS` or `--jobs <n|auto>`. The workflow deliberately leaves
`CARGO_BUILD_JOBS` unset so the runner's own CPU count is used. Gradle
parallelism is unchanged (`--max-workers=1`, overridable with
`NOMESSAGES_GRADLE_WORKERS`): the 768 MiB heap/metaspace profile recorded above is
the one that is known to pass, and raising it needs its own evidence.

`scripts/guard-build.py` defaults (8 GiB of build storage, 10 GiB free) were
tuned for a developer machine. The workflow retunes them through the new
`NOMESSAGES_GUARD_ARGS` variable (`--max-gib 24 --min-free-gib 6 --headroom-gib 2`)
and each job first reclaims runner disk by removing the preinstalled .NET, GHC
and Android SDK trees, none of which this build uses.

Each job also runs `chmod +x gradlew scripts/*.sh`: those files are recorded in
Git with mode `100644`, so a fresh checkout cannot execute them directly. That
is a workaround, not a fix; the index modes still need to be corrected with
`git update-index --chmod=+x gradlew scripts/build-android.sh
scripts/bootstrap-tools.sh scripts/tor-delivery-probe.sh scripts/threat-gates.sh`.
Outside CI nobody runs that `chmod`, so `scripts/build-android.sh` now invokes
`scripts/bootstrap-tools.sh` and `./gradlew` through `bash`, and `README.md`
documents `bash scripts/build-android.sh …`. A clean clone therefore works with
no manual `chmod`, whichever way the index modes end up being fixed.

### Ledger entries not covered by CI

Two rows of the validation ledger above are **not** regression-tested by
`.github/workflows/android.yml` and stay manual:

| Ledger entry | Why it is not in CI | Where it is covered |
|---|---|---|
| Conditional cleanup fault cases | The two cases need `core/src/test/native/vault_cleanup_faults.c` compiled and injected with `LD_PRELOAD` around a hand-built classpath; no Gradle task or script in the repository performs that (`grep -rn vault_cleanup_faults --include=*.kts --include=*.sh` finds nothing). Inside `:core:test` they remain the two conditional skips. | `build-logs/vault-cleanup-fault-gate.log`, executed by hand on 2026-09-14 |
| Live Tor delivery | Requires real network access and a live onion service; `scripts/tor-delivery-probe.sh` and `core/src/test/native/TorDeliveryProbe.java` are invoked by no job. The Rust `tor_live` test is `#[ignore]` for the same reason. | Phase 2 of `docs/superpowers/plans/2026-09-14-roadmap-to-release.md` (tasks T2.x), on a real device/network |

Everything else in the ledger — the Rust suite, `:core:test`, the two JVM
probes, `:app:testDebugUnitTest`, the Python outbox regression, both
cross-builds, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`,
`verify-apk.py` and the packaged-APK inspection — runs in the workflow. The 13
device gates of `docs/release-checklist.md` are out of scope for CI by
definition: they need physical hardware.

### Not yet verified

The workflow has never executed: there is no remote (task T0.4). What has been
checked here is static only — `bash -n` on the script, a YAML parse with PyYAML,
confirmation that every action reference is a 40-hex SHA and every
`timeout-minutes` is at most 180, and a sandbox run of all four
`build-android.sh` command lines used by the YAML against stubbed
`bootstrap-tools.sh`, `cargo`, `gradlew` and `verify-apk.py`. Wall-clock times,
cache hit rates and the "two consecutive green runs under 90 minutes" criterion
of T6.1 remain unmeasured.

Specifically unmeasured, and therefore not asserted anywhere above:

- **Total cache size.** Nobody has measured `.tools/toolchains` (JDK + SDK +
  NDK subset + Gradle home) or `native/target` on a runner, so the claim that
  the cache set fits the GitHub 10 GiB per-repository budget is a design intent,
  not a measurement. Merging the three NDK key namespaces into one removes two
  redundant copies, which can only help.
- **The `!` exclusion patterns** in `path:` (`!.tools/toolchains/tmp`,
  `downloads`, `android-user`) follow the `@actions/glob` documentation and have
  not been exercised. The debug keystore no longer depends on them.
- **`~/.rustup` is not cached.** `bootstrap-tools.sh --with-ndk` re-runs
  `rustup target add aarch64-linux-android x86_64-linux-android` in every job
  that asks for the NDK. Caching the toolchain directory would trade roughly a
  gigabyte of cache for a download of about a hundred megabytes, which is the
  wrong trade against the same 10 GiB budget; the cost is seconds per job.
- **`native-android` installs more than it needs.** `--bootstrap` runs
  `bootstrap-tools.sh`, which always installs the JDK, `platforms;android-37.0`,
  `build-tools;36.0.0` and platform-tools, even though the job only cross-builds
  Rust with the NDK. Dropping `--bootstrap` would not help: the
  `--with-ndk` call that follows is the same script and installs the same
  packages. Skipping them requires an NDK-only mode in
  `scripts/bootstrap-tools.sh`, which is outside the files of this task and is
  recorded as pending.

### Python regression in the build (task T4.4)

`app/src/test/python/test_messaging_queue.py` extracts `readyQuery` from
`app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingPolicy.kt` and replays the
production outbox query against an in-memory SQLite database (four cases: quota
request versus invites, membership-commit ordering across epochs, attempted
versus untried peers, and one offline recipient not blocking another in the same
group). It needs only CPython with the standard `sqlite3` module.

`scripts/build-android.sh` now runs Gradle in two waves so the suite sits
exactly where the task requires it: `:core:*` plus `:app:testDebugUnitTest`,
then `python3 -m unittest discover -s app/src/test/python -p 'test_*.py'`, then
`assembleDebug`, `assembleDebugAndroidTest` and `lintDebug`, then
`verify-apk.py`. With `set -o pipefail` a failing assertion aborts the build
before anything is packaged. Verified locally on Python 3.12.3: four cases,
zero failures; an intentionally failing case was injected and the script exited
1 without reaching assembly. The suite's log is written to
`docs/development/build-logs/python-tests-<stamp>.log`.

## Lint (2026-09-14)

Task T1.4. `:app:lintDebug` completed for the first time (the 384 MiB Metaspace
cap that used to abort it is now 768 MiB in `gradle.properties`). The first
complete run reported **3 errors and 43 warnings**; after the fixes below the
run is **0 errors, 42 warnings, 1 hint** (`BUILD SUCCESSFUL in 7m 46s`).
Reports: `app/build/reports/lint-results-debug.{xml,html,txt}`.
`:app:testDebugUnitTest` stays green afterwards: 22 tests, 0 failures, 0 errors,
0 skipped.

### Errors fixed

| # | Issue | Location | Fix |
|---|---|---|---|
| 1 | `MissingClass` — `androidx.startup.InitializationProvider` not resolvable | `app/src/main/AndroidManifest.xml:45` | `tools:ignore="MissingClass"` on the provider, with the justification inline |
| 2 | `NewApi` — `java.nio.file.Path#of` requires API 34, minSdk is 31 | `AndroidVaultStorage.kt:109` | replaced with `database.resolveSibling(database.fileName.toString() + suffix)` (API 26) |
| 3 | `LocalContextGetResourceValueCall` — `context.getString` inside a click lambda | `SettingsScreen.kt:137` | hoisted to `val copiedNotice = stringResource(R.string.copied)` in composition |

Notes on each:

1. The provider block is load-bearing and was kept. `androidx.emoji2:emoji2`
   **is** in the dependency graph (1.4.0, transitive), and without the nested
   `<meta-data android:name="androidx.emoji2.text.EmojiCompatInitializer"
   tools:node="remove"/>` the merged manifest would start EmojiCompat's
   downloadable-font provider. `scripts/verify-apk.py:140` asserts that
   initializer is absent from the packaged manifest, so the security intent
   ("never initialize a downloadable font provider") is verified by the build,
   not only by review. `tools:node="remove"` on the whole provider was rejected:
   the merged manifest shows the same provider also carrying
   `ProcessLifecycleInitializer` and `ProfileInstallerInitializer`, which would
   be silently disabled. The lint error is a false positive of classpath scope:
   `androidx.startup:startup-runtime:1.1.1` reaches the app as a *runtime*
   transitive dependency of `lifecycle-process`/`profileinstaller`, so the class
   is in the APK but not on the compile classpath lint resolves against.
   Declaring the dependency directly was rejected as widening the compile
   surface purely to satisfy a checker.
2. A real crash, not a style issue: `Path.of` is a Java 11 API that Android only
   exposes from API 34, and `beforeExport` runs on every vault export. On
   Android 12/13 (minSdk 31) it would have thrown `NoSuchMethodError` at the
   worst possible moment — right before an export. `resolveSibling` is
   semantically identical here (the sidecar always lives next to the database)
   and is available since API 26. `Path.of` appears nowhere else under `app/`
   (`core/` is plain JVM and does not use it either).
3. `LocalContext.current` reads are not invalidated by a `Configuration` change,
   so the toast could show the previous locale's text after an in-place language
   or configuration switch. `stringResource` recomposes correctly.

### Warning triage (43)

Legend: **(a)** fix now, **(b)** accept with justification, **(c)** deferred
(owned by another file/agent or needs a device gate).

| Id | Count | Where | Class | Decision |
|---|---|---|---|---|
| `UnusedResources` | 30 | `values/strings.xml` (26), `values/colors.xml` (2), `drawable/ic_launcher_background.xml` (1), `values-en` mirror | (c) | 26 of these are `R.string.error_*`, `scan_again`, `retry`, `offline_message_note`, `group_info`, `open_attachment`, `media_unsupported`, `brand_mark` — localized copy that no caller resolves yet. Deleting them would be wrong: the finding is that those error paths currently surface **unlocalized** text. Owned by `strings.xml`, edited by another agent in this cycle. `R.color.wf_bubble`/`wf_surface` and `R.drawable.ic_launcher_background` are genuinely dead (the adaptive icon uses `@color/wf_green`) and can be deleted in a dedicated pass. |
| `PluralsCandidate` | 8 | `values/strings.xml` and `values-en/strings.xml`, lines 80, 84, 128, 130 | (c) | `%d selecionados`, `%d pareamentos`, `%d segundos`, `%d minutos` are correct in PT-BR/EN for the values actually used (5/15/30 s, counts ≥ 2), but converting them to `<plurals>` is the right i18n fix for other locales. Both files are owned by another agent this cycle; it also touches `UiLogic`/`Components` call sites. |
| `UseKtx` | 2 | `ui/MemoryPdfDocument.kt:52`, `ui/PairingScreen.kt:265` | (c) | Pure style (`Bitmap.createBitmap` → `createBitmap` KTX). Both files are outside this task's ownership. |
| `OldTargetApi` | 1 | `app/build.gradle.kts:16` | (b) | `targetSdk = 35` against `compileSdk = 37` is deliberate. Raising it opts the app into new platform behaviours (background/foreground service, notification and storage changes) that are exactly what the device gates in Phase 3 exist to validate. Bumping it before T3.1–T3.5 would invalidate them. Revisit after Phase 3. |
| `ObsoleteSdkInt` | 1 | `res/mipmap-anydpi-v26/` | (c) | Correct: minSdk 31 makes the `-v26` qualifier redundant, so `ic_launcher.xml`/`ic_launcher_round.xml` should move to `mipmap-anydpi/`. Harmless, but it moves launcher resources and is better done with the `UnusedResources` cleanup in one resource pass. |
| `AutoboxingStateCreation` (hint) | 1 | `ui/AttachmentViewer.kt:178` | (c) | `mutableStateOf` on an `Int` should be `mutableIntStateOf`; one boxed allocation per state write. File outside this task's ownership. |

No lint baseline file was created and no check was disabled: the 43 warnings
stay visible in the report. Suggested follow-up, one task: a resource pass
deleting `wf_bubble`, `wf_surface` and `ic_launcher_background`, flattening
`mipmap-anydpi-v26`, converting the four plural strings, and deciding per
`error_*` string whether to wire it up or drop it.

## Instrumented tests (2026-09-15)

Primeira execução da suíte instrumentada do app (T3.1). Alvo: AVD Android 15
(API 35) `x86_64` com aceleração WHPX, serial `emulator-5556`, adb 37.0.1 do
Windows. Os APKs foram recompilados no WSL Ubuntu com o mesmo toolchain do
restante do relatório (`:app:assembleDebug` e `:app:assembleDebugAndroidTest`) e
instalados com `adb install -r`; a suíte roda por `am instrument -w` sobre
`dev.mx3.nomessages.debug.test/androidx.test.runner.AndroidJUnitRunner`.

Resultado final: **`OK (12 tests)`** — 11 casos de `AndroidVaultStorageTest` e 1
de `MemoryPdfDocumentTest`, sem falhas e sem skips, em 151,3 s.

### Execuções arquivadas

| # | Log | Resultado | Causa |
|---|---|---|---|
| 1 | `build-logs/android-test-emulator-5556-20260915T140427Z.log` | 1/12 (11 falhas) | `SQLiteException: Queries can be performed using SQLiteDatabase query or rawQuery methods only.` em `AndroidVaultStorage.openConfigured` |
| 2 | `build-logs/android-test-emulator-5556-20260915T141204Z.log` | 1/12 (11 falhas) | `NoSuchAlgorithmException: SHA3-256 MessageDigest not available` em `DecoyFactory.syntheticOnion` |
| 3 | `build-logs/android-test-emulator-5556-20260915T141801Z.log` | 10/12 (2 falhas) | `SQLiteDatatypeMismatchException: datatype mismatch (code 20)` em `ChatDatabase.listPendingFrames`/`listPendingFrameIds` |
| 4 | `build-logs/android-test-emulator-5556-20260915T143135Z.log` | **12/12 `OK`** | — |

As três falhas eram defeitos reais do app, nenhuma era defeito de teste: todas
estão em caminhos de produção (criação do cofre, geração da isca e leitura da
outbox) e nenhuma é observável nos testes JVM, porque dependem do SQLCipher real
e dos provedores de segurança do Android.

### Causa raiz 1 — PRAGMA que devolve linha via `execSQL` (SQLCipher 4)

`net.zetetic:sqlcipher-android:4.19.0` implementa `SQLiteDatabase.execSQL` sobre
`executeNonQuery` no JNI: a função executa o `sqlite3_step` e, se a resposta for
`SQLITE_ROW` em vez de `SQLITE_DONE`, lança
`SQLiteException: unknown error (code 0): Queries can be performed using
SQLiteDatabase query or rawQuery methods only.`.

Vários PRAGMAs devolvem uma linha com o valor que efetivamente ficou valendo —
tanto a família `cipher_*` do SQLCipher quanto a forma de atribuição de
`journal_mode`, `temp_store`, `secure_delete` e `max_page_count`. Em
`openConfigured` eles eram emitidos por `execSQL`, então a primeira PRAGMA com
resultado derrubava a criação do cofre e, com ela, os 11 casos de
`AndroidVaultStorageTest`. A correção passa todas as PRAGMAs por `rawQuery` com
o cursor consumido e fechado (helper `applyPragma`), e passou a conferir o valor
lido de `journal_mode`, `foreign_keys`, `synchronous`, `max_page_count` e
`user_version` com mensagem de erro explícita.

### Causa raiz 2 — SHA3-256 não existe no Android

O checksum de um endereço `.onion` v3 é SHA3-256. `DecoyFactory` o obtinha de
`MessageDigest.getInstance("SHA3-256")`, que resolve em JVM de desktop (JDK 9+,
provedor SUN) mas lança `NoSuchAlgorithmException` no Android: o Conscrypt não
implementa a família SHA-3 e o BouncyCastle reempacotado da plataforma a removeu.
O digest passou a ser implementado no próprio app (`Sha3_256.kt`, Keccak-f[1600]
com taxa de 136 bytes), com teste JVM contra os vetores do FIPS 202 e contra o
provedor do JDK. Isso mantém o endereço da isca byte a byte igual ao que a rede
Tor valida, sem depender do nível de API.

### Causa raiz 3 — sobrecarga de `rawQuery` escolhida por tipo de array

`SQLiteDatabase` expõe `rawQuery(String, String[])` **e**
`rawQuery(String, Object...)`. Em Kotlin, `arrayOf("a")` é `Array<String>` e casa
com a primeira sobrecarga, mas `arrayOf(1L)` é `Array<Long>`, não casa com
`String[]` e cai no vararg — onde, sem o operador de spread, o **array inteiro**
vira um único argumento. O SQLCipher então classifica esse argumento com
`DatabaseUtils.getTypeOfObject`, que não reconhece `Long[]` e o trata como texto,
ligando `"[Ljava.lang.Long;@…"` ao parâmetro. Em `LIMIT ?` o SQLite exige um
inteiro e responde `SQLITE_MISMATCH` (`datatype mismatch`, código 20).

O mesmo defeito, silencioso, existia em outros dois pontos: `unreadCounts`
passava um `Array<Long>` de dois elementos para uma consulta de dois parâmetros
(1 argumento para 2 marcadores, erro de binding em tempo de execução) e a
deduplicação de `putPendingFrame` comparava `hash = ?` com um `Array<ByteArray>`
convertido em texto, ou seja, nunca encontrava o blob já armazenado. Os quatro
pontos passaram a usar o spread (`*arrayOf<Any>(…)`); os que já passavam
`Array<String>` continuam corretos pela sobrecarga `String[]`.

## BlueStacks (2026-09-15)

Investigação de por que o emulador BlueStacks 5 (instância "Tiramisu64",
Android 13 `x86_64`, 8 GB RAM / 4 CPUs, `bst.instance.Tiramisu64.status.adb_port
= 5558`, versão de instalador `5.22.100.1024`, host Windows 11 / i9-13900HX)
derrubava o processo `HD-Player.exe` inteiro assim que `am instrument`
começava, deixando a saída da instrumentação vazia. O problema já tinha
ocorrido duas vezes antes desta sessão; nesta sessão foi reproduzido mais duas
vezes, de forma 100% determinística, isolando a causa exata.

### Onde a VM morre

Não é em `am instrument`, nem em `am start`, nem em nenhum código nativo do
app. A VM morre durante a **instalação do APK de teste**
(`app-debug-androidTest.apk`, pacote `dev.mx3.nomessages.debug.test`), cerca de
0,9–1,6 s depois que o `pm install` termina no lado Android — antes de
qualquer comando de instrumentação ser emitido. Nas duas tentativas originais
do usuário (log vazio), o padrão bate: os dois APKs reportam "Success" porque
o `pm install` do Android convidado realmente termina bem, mas o host
BlueStacks morre logo em seguida, então `am instrument` nunca chega a rodar e
não há nada para gravar no log.

### Evidência: quatro reproduções idênticas

`C:\ProgramData\BlueStacks_nxt\Logs\Player.log` registra, nas quatro
ocorrências (duas anteriores a esta sessão, às 13:56:09 e 14:01:24, e duas
desta sessão, às 14:03:17 e 14:05:50), a mesma sequência exata:

```
HCALL  Tiramisu64 [Ready] I: hcallOnAppInstalledClbk : jsonData = {"pkg":"dev.mx3.nomessages.debug.test","activity":"","label":"","versionCode":0,"iconFileName":"","source":"user", ...}
...
WERH    I: UnhandledException. Exception Code : 0x3221226505 ExceptionAddress: 0x00007FFE73DB527E
```

`0x3221226505` é o valor decimal do código de exceção do Windows
`0xC0000409` = `STATUS_STACK_BUFFER_OVERRUN` — o mecanismo `/GS` do Windows
detectou um estouro de buffer de pilha e encerrou o processo via fail-fast.
O `ExceptionAddress` é **idêntico nas quatro ocorrências**
(`0x00007FFE73DB527E`), ou seja, é sempre o mesmo caminho de código, não uma
condição de corrida ou esgotamento de recurso.

O gatilho é sempre o mesmo: o callback `hcallOnAppInstalledClbk` recebe um
pacote com `activity`, `label` e `iconFileName` vazios e `versionCode: 0` —
exatamente a forma de um APK `androidTest`, que não declara nenhuma activity
`MAIN`/`LAUNCHER` (não é um app lançável, é só o executor de testes). O
logcat do lado Android confirma que o guest processa a instalação
normalmente e sem erro (`PackageManager`, `ActivityManager`,
`BstCommandProcessor-PackageIntentReceiver` terminam limpos); é só depois,
no host, na rotina de criação de atalho/ícone/label do `HD-Player.exe`
(módulo `PLR`), que o Windows aborta o processo por estouro de pilha ao lidar
com os campos vazios.

### Testes de confirmação (isolando o gatilho)

1. Instância reiniciada por linha de comando, só o `app-debug.apk`
   instalado (sem o APK de teste): instalação `Success`, VM estável.
2. `am start -n dev.mx3.nomessages.debug/dev.mx3.nomessages.MainActivity`: a
   activity chegou a `topResumedActivity`, socket AGA aberto para o processo
   do app, VM estável — ou seja, o app em si, incluindo a biblioteca nativa
   `libnomessages.so` (libsodium, OpenMLS, Arti), carrega e roda normalmente sob
   BlueStacks.
3. Com o app já rodando, instalação do `app-debug-androidTest.apk`: `Success`
   no `adb install`, VM ainda viva 3 s depois — e morta ~5 s depois, com o
   mesmo padrão exato de `hcallOnAppInstalledClbk` + `WERH` no Player.log
   (quarta reprodução idêntica).

### Hipóteses descartadas (com evidência)

- **Conflito de hipervisor com o emulador oficial (WHPX)**: `emulator-5556`
  ficou rodando durante as quatro reproduções sem variação no resultado; o
  crash é determinístico e ligado ao conteúdo do pacote instalado, não a
  tempo ou contenção de CPU/memória. Não foi necessário derrubar o emulador
  oficial para descartar essa hipótese.
- **`memfd_create`/seccomp (`MemoryPdfDocumentTest`) ou qualquer outro teste
  instrumentado**: nunca chegaram a rodar — o crash acontece antes de
  qualquer `am instrument`.
- **Alinhamento de página de 16 KiB da `.so`**: descartado — `getconf
  PAGE_SIZE` retorna `4096` tanto no BlueStacks quanto no `emulator-5556`.
- **Falta de AVX/instruções da libsodium**: descartado — o `/proc/cpuinfo`
  do BlueStacks tem `avx`, `avx2`, `aes`, `pclmulqdq`, `sse4_2` (faltam só
  `fma`, `f16c`, `sha_ni`, `avx_vnni` frente ao emulador, nenhuma delas
  exigida pela libsodium); e o teste de confirmação #2 provou que o app com
  toda a stack nativa carrega e roda sem derrubar a VM.
- **Alocação dos bancos de 256 MiB / memória insuficiente**: descartado —
  `/proc/meminfo` mostra 8 GB total e 6–7 GB livres; o crash é instantâneo e
  disparado por conteúdo específico (pacote sem label/ícone), não por pressão
  gradual de memória.

### Recomendação

**BlueStacks (instância Tiramisu64, versão de instalador 5.22.100.1024) não é
utilizável para rodar a suíte instrumentada do NoMessages.** A instalação do APK
`androidTest` — obrigatória para qualquer `am instrument` — derruba a VM
inteira de forma 100% reproduzível, antes que qualquer teste rode, por um bug
do próprio host do BlueStacks (estouro de pilha ao criar atalho/ícone para um
pacote sem label) e não por nada específico do NoMessages ou da sua stack nativa.

É utilizável, porém, para uso manual/exploratório do app: o `app-debug.apk`
instala e roda normalmente, com a biblioteca nativa funcionando (Passo de
confirmação #2 acima).

Ajustes que poderiam contornar o bug, não testados nesta sessão (não foi
alterado nada do app nem reinstalado o BlueStacks):

- Atualizar o BlueStacks para a versão mais recente — é um bug genérico de
  processamento de metadados de pacote, não específico do NoMessages,
  reproduzível com qualquer APK `androidTest`/sem ícone.
- Procurar, nas configurações do BlueStacks, alguma opção que desative a
  criação automática de atalho na área de trabalho ao instalar um app (o
  gatilho é exatamente essa rotina).

Enquanto isso, a suíte instrumentada continua rodando 12/12 no emulador
oficial (`emulator-5556`, ver seção "Instrumented tests (2026-09-15)" acima);
os gates de hardware real (arm64) seguem pendentes de aparelho físico —
BlueStacks não substitui essa necessidade.
