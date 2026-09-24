# Contributing to NoMessages

Thank you for your interest in NoMessages. This is a privacy- and security-sensitive Android
messaging app (encrypted vaults, in-person QR pairing, Tor onion transport, libsignal and
OpenMLS), and every contribution is reviewed with that in mind. Please read this document before
opening an issue or a pull request.

All contributions are reviewed and merged solely at the maintainer's discretion. Opening a PR does
not guarantee it will be accepted.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you are
expected to uphold it.

## Reporting bugs

Before filing a bug, search [existing issues](../../issues) to avoid duplicates. Open a new issue
using the **Bug report** template and include:

- What you did, what you expected, and what happened instead.
- App version/build (release or debug), Android version, and device or emulator model.
- Logs only if you are certain they contain no message content, contact identities, passwords, or
  other vault secrets — see [Security-sensitive changes](#security-sensitive-changes) below. When
  in doubt, describe the log instead of pasting it.
- Steps to reproduce, if you have them.

**Do not report security vulnerabilities as public issues.** See [SECURITY.md](SECURITY.md) for
private disclosure.

## Proposing features

Open an issue with the **Feature request** template first, before writing code. This project has a
narrow, deliberately scoped security model ([SPEC.md](SPEC.md),
[docs/security-model.md](docs/security-model.md)); a feature that looks small can still be out of
scope or conflict with an existing guarantee. Getting agreement on the idea before a PR saves you
from rework or a decline.

For any change to cryptography, wire protocol, pairing ceremony, or vault format, open an issue
and get maintainer agreement on the approach **before** writing the implementation. This project
prefers discussing the design over reviewing a finished PR for something this sensitive.

## Development setup

NoMessages does not build natively on Windows. All commands below run inside **WSL2 with Ubuntu
24.04** (or a native Linux host); see [Windows (WSL2)](#windows-wsl2) below for the full setup,
including the WSL system packages and the pinned Rust installer commands. Summary of what you
need:

- JDK 21, Android SDK, and the Android NDK toolchain for the native libraries.
- Rust 1.91+ with the `aarch64-linux-android` and `x86_64-linux-android` targets.
- `build-essential`, `clang`, `cmake`, `pkg-config`, `python3`, `unzip`, `zip`, `curl`, `git`
  (`libsodium-rs` compiles libsodium from vendored C source, so a C toolchain is required).

`gradlew` and everything under `scripts/` are committed with mode `100644`, so a fresh clone
cannot execute them directly. Invoke them with an explicit `bash` prefix, exactly as the README and
CI do:

```bash
# One-shot bootstrap + full build: host native, Android native (arm64-v8a and
# x86_64), :core, :app, the Python outbox-queue regression suite, both APKs,
# lint, and the APK inspection.
bash scripts/build-android.sh --bootstrap

# Fast inner loop: JVM/Kotlin core only, reusing an already-built host native
# library.
bash scripts/build-android.sh --core-only --with-native-host

# Native (Rust) suite in isolation.
cargo test --manifest-path native/Cargo.toml --locked --jobs 1

# Full option list.
bash scripts/build-android.sh --help
```

`scripts/bootstrap-tools.sh` (invoked automatically by `--bootstrap`) downloads JDK, SDK platform
tools, and the NDK into `.tools/toolchains`, outside Git. Native build artifacts land in
`native/target`. Neither directory is committed; both survive a `git clean` because they live
under paths `.gitignore` excludes.

### Windows (WSL2)

Windows is not a build host. `scripts/bootstrap-tools.sh` downloads the JDK, command line tools,
platform tools and NDK in **Linux x64** builds, and the scripts depend on `bash`, `/proc`, `du`,
`flock` and `setsid`. All compilation happens inside WSL2 with **Ubuntu 24.04**.

System prerequisites, inside WSL:

```bash
sudo apt update && sudo apt install -y build-essential clang cmake pkg-config python3 unzip zip curl git
```

`apt update` is not optional: a freshly installed Ubuntu image in WSL starts with an empty
`/var/lib/apt/lists`, and `apt install` fails with "Unable to locate package" before downloading
anything.

`clang` and `build-essential` are required: `libsodium-rs` pulls in `libsodium-sys-stable`, which
compiles libsodium from vendored C source. Ubuntu 24.04's `python3` already ships `tomllib`, used
by `scripts/tor-delivery-probe.sh`.

Rust 1.91 and both Android targets. The installer is downloaded to a file and checked against a
digest, the same pattern `scripts/bootstrap-tools.sh` applies to the JDK, SDK and NDK — the
compiler that builds the crypto and transport layers does not go on the machine via `curl | sh`:

```bash
base=https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu
curl --proto '=https' --tlsv1.2 -fsSLO "$base/rustup-init"
curl --proto '=https' --tlsv1.2 -fsSL "$base/rustup-init.sha256" | sha256sum --check --status -
chmod +x rustup-init
./rustup-init -y --default-toolchain 1.91 --profile minimal
. "$HOME/.cargo/env"
rustup target add aarch64-linux-android x86_64-linux-android
```

The `.sha256` published next to the binary (format `<digest> *./rustup-init`) comes from the same
server, so it catches a corrupted or truncated download, not a compromised server. For the
stronger guarantee, pin the digest in the repository the way the bootstrap script does for the
JDK. Digest observed on 2026-09-14: `dda7234360b7f578ca8b0ddcb80145646fa61a67c1720a5abc7051b35c9fcb71`
(21,113,232 bytes) — verify before trusting it, because `rustup-init` gets republished.

Quick check: `rustc --version` should report 1.91.x, `clang --version` should respond, and
`python3 -c 'import tomllib'` should exit without error.

**Where to clone.** Prefer cloning inside WSL's own filesystem, for example in `~/nomessages`.
Gradle, Cargo and the NDK do a lot of small-file I/O, and working from `/mnt/<letter>` (the Windows
disk exposed through 9p/drvfs) makes the build several times slower.

If you need to keep the sources on the Windows disk, at least keep the caches and build artifacts
on the WSL disk, by exporting the two variables the scripts already honor:

```bash
export NOMESSAGES_TOOLS_DIR="$HOME/.nomessages-tools"
export CARGO_TARGET_DIR="$HOME/.nomessages-target"
```

`NOMESSAGES_TOOLS_DIR` (read by `scripts/bootstrap-tools.sh`, `scripts/build-android.sh`,
`scripts/verify-apk.py` and `scripts/tor-delivery-probe.sh`) replaces the default
`.tools/toolchains` and carries along `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`,
`ANDROID_USER_HOME` and `TMPDIR`, all derived from it in `scripts/build-android.sh`.
`CARGO_TARGET_DIR` replaces `native/target` and sets `NOMESSAGES_NATIVE_DIR` by default. Keeping
`TMPDIR` inside the tools directory is deliberate: an earlier build attempt failed when the host's
`/tmp` was cleared of Gradle caches mid-build (summarized in
[build-report.md](docs/development/build-report.md)).

Do not point `NOMESSAGES_TOOLS_DIR` or `CARGO_TARGET_DIR` at a path under `/mnt/...`: besides being
slow, those directories need POSIX permissions that drvfs does not preserve.

**Parallelism.** WSL usually exposes many more cores than the earlier Linux host, which ran the
native step with `--jobs 1` in 39 min. `scripts/build-android.sh` defaults to `nproc`
(`--jobs auto`); `CARGO_BUILD_JOBS` and `--jobs <n>` reduce that number when the machine runs low
on RAM, and `NOMESSAGES_GRADLE_WORKERS` (default `1`) controls Gradle workers separately. Raise
them one at a time: `scripts/guard-build.py`'s disk guard tracks aggregate usage, and higher
parallelism means more intermediate artifacts alive at once.

## Branching and pull requests

- Fork the repository.
- Branch from **`develop`** — not `main`. `main` only ever receives tagged releases; `develop` is
  the integration branch for ongoing work.
- Keep your branch focused on one change. Large, unrelated changes bundled together are harder to
  review and more likely to be declined.
- Open your pull request **against `develop`**.
- Rebase (don't merge `develop` into your branch) to keep history readable, unless a maintainer
  asks otherwise.

### Commit messages

This project uses [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <short summary>

<optional body>

<optional footer(s)>
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`, `perf`. Example:

```
fix(vault): reject panic password within edit distance 2 of the real one
```

### Developer Certificate of Origin (DCO)

Every commit must be signed off, certifying you have the right to submit the change under this
project's license ([developercertificate.org](https://developercertificate.org/)):

```bash
git commit -s -m "fix(vault): ..."
```

This adds a `Signed-off-by: Your Name <your.email@example.com>` trailer. PRs with unsigned commits
will not be merged until fixed (`git commit --amend -s` for the last commit, or
`git rebase --exec 'git commit --amend --no-edit -s' -i <base>` for a range).

### Pull request requirements

Before requesting review, make sure your PR:

- **Passes CI.** The [Android verification workflow](.github/workflows/android.yml) builds the
  native libraries, runs the Rust and JVM/Kotlin test suites, and lints the app. A red CI run will
  not be reviewed until it's green. You can run the same steps locally with
  `bash scripts/build-android.sh --bootstrap`.
- **Includes tests for behavior changes.** New functionality or a bug fix needs a test that would
  fail without your change (Rust `#[test]`, JVM/Kotlin unit or instrumented test, or the Python
  outbox-queue regression suite under `app/src/test/python/`, as appropriate).
- **Has clean lint.** `bash scripts/build-android.sh --app-only` runs `lintDebug`; resolve new
  warnings it introduces.
- **Keeps English/PT-BR string parity.** UI strings live in
  `app/src/main/res/values/strings.xml` (English, the app's default locale) and
  `app/src/main/res/values-pt/strings.xml` (Portuguese). Add or change a string in both files in
  the same PR; a PR that adds a string to only one locale will be asked to add the other.
- **Updates documentation** (`SPEC.md`, `docs/`) when it changes behavior, the security model, or
  build/setup steps that this document or the README describe.
- **Includes a DCO sign-off** on every commit (see above).

All code, code comments, commit messages, and documentation must be in **English**.

### Security-sensitive changes

This is a security- and privacy-focused app; a few rules apply beyond ordinary code review:

- **No logging of sensitive data.** Never log passwords, derived keys, message plaintext, contact
  identities, onion addresses, or other vault secrets, even at debug level. Existing debug-only
  hooks (see `docs/security-model.md`) are narrowly scoped and reviewed individually — don't extend
  their pattern casually.
- **Nothing decrypted touches disk.** Message plaintext, decrypted attachments, and derived keys
  must stay in memory; do not add caches, temp files, or logs that would write them out.
- **Real/decoy vault parity.** The real and panic (decoy) vaults must remain structurally and
  behaviorally indistinguishable — same growth, same timing, same code paths where the design
  requires it. A change that makes one vault observably different from the other (size, latency,
  error messages, feature availability) is a regression even if it "only" affects the decoy.
- **No new network dependencies beyond Tor.** All app network traffic goes over the Tor onion
  transport already in the project. Do not add a clearnet endpoint, telemetry/analytics SDK, crash
  reporter, or any other network dependency.
- **Discuss crypto/protocol changes before implementing them.** Anything touching pairing,
  key derivation, the Signal/MLS integration, or the wire format needs an issue and maintainer
  agreement first — see [Proposing features](#proposing-features).

If you're unsure whether a change qualifies as security-sensitive, ask in the issue before you
start.

## Review process

Every PR requires review and explicit approval from the maintainer ([@maxwellmelo](https://github.com/maxwellmelo))
before merging. Expect requests for changes, especially around the security-sensitive rules above;
that's normal and not a rejection.

## License

By contributing, you agree that your contributions are licensed under this project's
[AGPL-3.0-or-later](LICENSE) license, the same license covering the rest of the codebase.
