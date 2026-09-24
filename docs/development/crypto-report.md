# Native cryptography report

`dev.mx3.nomessages.core.crypto.NativeCrypto()` is the host-JVM and Android implementation of `Crypto`. Its companion initializer calls `System.loadLibrary("nomessages")`; there is no fallback implementation.

## Wire and key contracts

- `random(size)` returns `size` bytes from libsodium `randombytes`.
- `derive(password, salt, memoryKiB, iterations)` returns 32 bytes from libsodium Argon2id v1.3. The salt is exactly 16 bytes, memory is 65,536 through 262,144 KiB, iterations are 1 through 20, and libsodium fixes parallelism to 1.
- `seal(key, plaintext, aad)` uses XChaCha20-Poly1305-IETF with a fresh random 24-byte nonce. Its result is `nonce24 || ciphertext || tag16`.
- `open` returns `null` on an authentication failure. A malformed key or frame is an invalid argument.
- `hash` is SHA-256 with a 32-byte result.
- `mac` is keyed BLAKE2b with a 32-byte result. Libsodium key lengths 16 through 64 bytes are accepted.
- Ed25519 public keys are 32 bytes, secret keys are libsodium's 64-byte `seed || public key` representation, and detached signatures are 64 bytes.

JNI byte-array inputs and outputs are capped at 64 MiB, plus the 40-byte AEAD overhead where applicable. Passwords have a 1 MiB bridge cap. Negative sizes, invalid lengths, and invalid KDF parameters raise `IllegalArgumentException` with a generic message. Native initialization or operation failures raise `IllegalStateException` with a generic message. JNI entry points catch Rust unwinds, and no exception includes input or secret material.

Rust JNI input copies and sensitive intermediate/output copies use zeroizing guards on both success and error paths. `libsodium-rs` key types wipe their storage on drop. Kotlin also wipes the temporary combined signing-key array after splitting it into the public and secret arrays returned by `SigningKeys`.

## Native dependency

The native crate pins `libsodium-rs` 0.2.4 with its `minimal` feature. That maintained safe wrapper pins `libsodium-sys-stable` 1.24.0, whose default build verifies and compiles the current stable libsodium source. The superseded `sodiumoxide` dependency was removed because its repository has been archived since 2022 and its bundled libsodium release was too old for a shipping build. `sodiumoxide` is absent from `Cargo.lock`.

The default build does not opt into `fetch-latest` and does not depend on a host-installed shared library. This keeps dependency resolution reproducible and makes the JNI shared library carry the pinned libsodium implementation. The embedded, signed stable archive in `libsodium-sys-stable` declares `PACKAGE_VERSION='1.0.22'` and has SHA-256 `b20a92e7ec25b285eafa349d721a5bb27e3a8ba94c0816630a127883f1d1b3ab`. It is a stable snapshot and is not byte-identical to the upstream point-release archive, whose published SHA-256 is `adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349`.

[CVE-2025-69277](https://nvd.nist.gov/vuln/detail/CVE-2025-69277) describes atypical custom-cryptography use with untrusted input to `crypto_core_ed25519_is_valid_point` before upstream commit `ad3004e`. No claim is made here that NoMessages's nine exposed primitives exercise that affected path; moving the shipping dependency to the current fixed release removes the obsolete native version regardless.

## Verification

The focused native suite covers a FIPS SHA-256 vector, an independently generated keyed BLAKE2b-256 vector, a self-generated Argon2id vector with exact `m=65536 KiB`, `t=2`, `p=1` (not third-party verified; see the caveat under *Known-answer vectors*), RFC 8032 Ed25519 vector 1, XChaCha round trips, AAD and ciphertext tampering, key/signature/frame length rejection, KDF bounds, random/key lengths, and allocation caps.

The first executed host run passed all 10 focused tests with no failures. `cargo build --no-default-features` completed with exit status 0 and produced `libnomessages.so` (that run redirected `CARGO_TARGET_DIR` to `/tmp/nomessages-system-target`, a historical path on the previous host that no longer exists; the default output is `native/target/debug/libnomessages.so`). `nm -D` confirmed `JNI_OnLoad` and all nine `NativeCrypto` JNI entry points. That artifact intentionally used the installed libsodium 1.0.18 shared library to unblock JVM integration on a constrained host. It is an integration-only artifact and is not suitable for release or evidence of the current bundled dependency. Full captured evidence and the bundled-build status are in [`build-logs/native-crypto-host.log`](build-logs/native-crypto-host.log).

> **Historical toolchain note (2026-09-14).** The command block below is the verbatim
> record of how the bundled-libsodium host runs were driven on the previous host — the
> ones reported under "Bundled build verification" — and is kept as evidence of how that
> result was obtained. **Do not re-run it.** Its
> `CARGO_TARGET_DIR`/`NOMESSAGES_NATIVE_DIR` overrides point at `/tmp` directories that no
> longer exist, so copying it today builds into a path Gradle will not find. The current
> invocation needs no override: `cargo test --manifest-path native/Cargo.toml --locked
> --no-default-features crypto::tests` followed by `./gradlew :core:test`, because a
> default build writes `native/target/debug/libnomessages.so` and core's Gradle test task
> reads that directory by default.

From the repository root, as executed on that host:

```sh
CARGO_TARGET_DIR=/tmp/nomessages-libsodium122-target CARGO_BUILD_JOBS=1 \
  cargo test --manifest-path native/Cargo.toml --no-default-features crypto::tests

CARGO_TARGET_DIR=/tmp/nomessages-libsodium122-target CARGO_BUILD_JOBS=1 \
  cargo build --manifest-path native/Cargo.toml --no-default-features

NOMESSAGES_NATIVE_DIR=/tmp/nomessages-libsodium122-target/debug ./gradlew :core:test
```

Primary API references: [libsodium password hashing](https://doc.libsodium.org/password_hashing/default_phf), [XChaCha20-Poly1305](https://doc.libsodium.org/secret-key_cryptography/aead/chacha20-poly1305/xchacha20-poly1305_construction), [generic hashing](https://doc.libsodium.org/hashing/generic_hashing), [SHA-2](https://doc.libsodium.org/advanced/sha-2_hash_function), [public-key signatures](https://doc.libsodium.org/public-key_cryptography/public-key_signatures), the official [`libsodium-rs` repository](https://github.com/jedisct1/libsodium-rs), the official [`libsodium-sys-stable` repository](https://github.com/jedisct1/libsodium-sys-stable), and the [libsodium 1.0.22 release](https://github.com/jedisct1/libsodium/releases/tag/1.0.22-RELEASE).

## Bundled build verification (2026-09-14)

The migrated libsodium-rs 0.2.4 / libsodium-sys-stable 1.24.0 implementation now
passes all 10 crypto tests against its embedded libsodium 1.0.22 snapshot.
`cargo test --locked --no-default-features crypto::tests -- --test-threads=1`
and `cargo build --locked --no-default-features --jobs 1` both exited 0.
Evidence: `build-logs/native-crypto-bundled.log` and
`build-logs/native-crypto-bundled-build.log`.

Provenance of those two artifacts, stated as the logs record it rather than as a
default build would place them:

- The crypto-only host JNI library of that run **was written to**
  `/tmp/nomessages-libsodium122-target/debug/libnomessages.so`, by the `CARGO_TARGET_DIR`
  override quoted above (`build-logs/native-crypto-bundled.log:74` shows the test
  binary running from that tree). Its expected JNI exports were inspected with
  `nm -D`; `ldd` listed only libc and libgcc, so that artifact did not depend on the
  older system libsodium. A default build — no override — places the same artifact at
  `native/target/debug/libnomessages.so`.
- It was superseded by the full Tor/MLS/crypto host library, built in the same
  overridden tree (`build-logs/native-full-host-j2.log:389`) and then **staged on that
  host at `/tmp/nomessages-host-runtime/libnomessages.so`**, which is the path — and the only
  path — named by the single line of `build-logs/native-host-sha256.log`.

**The recorded SHA-256 `1004f7e4…` belongs to that staged `/tmp` binary, which no longer
exists. It does not apply to any `native/target/debug/libnomessages.so` produced locally,
and must not be reused as gate evidence:** for T5.5 the host library has to be rebuilt
here and re-hashed, with the new digest and its real path recorded. Full host Tor/MLS
compilation and eight additional offline tests passed on that host, as did the
Kotlin/JNI boundary probe. Live Tor and Android ABI verification remain separate gates;
see `native-report.md`.

## Known-answer vectors (2026-09-14)

Plan task T4.3 added the missing published vectors for release gate 13. They
live in `native/tests/kat.rs` and in the verbatim JSON corpus under
`native/tests/vectors/`. The suite has no new dependency: the Wycheproof files
are embedded with `include_str!` and read by a minimal JSON parser inside the
test, so `Cargo.lock` is unchanged and the tests compile under every feature
combination.

Each file is pinned by content: the test recomputes its SHA-256 with
`crypto::hash` and fails if it differs from the digest below. Carriage returns
are removed before hashing, so the digest is the one of the upstream LF file
(`.gitattributes` already forces LF in this repository).

### Corpus

The four files are redistributed verbatim from the Wycheproof project
([C2SP/wycheproof](https://github.com/C2SP/wycheproof)), which is licensed under
**Apache-2.0**. They are third-party data vendored into this AGPL-3.0
repository, so the Apache-2.0 notice must travel with them. The matching row in
`THIRD_PARTY_NOTICES.md` (`| Wycheproof (test vectors) | C2SP/wycheproof,
Apache-2.0 |`) is **still missing as of 2026-09-14** and has to be added before
release; this section is the interim record of origin and licence.

| File | Source (downloaded 2026-09-14) | SHA-256 | Cases |
|---|---|---|---|
| `chacha20_poly1305_test.json` | `raw.githubusercontent.com/C2SP/wycheproof/master/testvectors_v1/chacha20_poly1305_test.json` | `fe61d25f90e1bde4461d00eafe61049e5f29bd999f36b766df9cda90906ad53d` | 325 |
| `xchacha20_poly1305_test.json` | `.../testvectors_v1/xchacha20_poly1305_test.json` | `a79de072571b90eb40c3a63ce0c7f75dcb4b62323c8870228e1f61dcc61d63a9` | 315 |
| `x25519_test.json` | `.../testvectors_v1/x25519_test.json` | `35c3f5231cf25cc640b524d403461deee9e49441d5d915a3a25b2c8ff5adbe7d` | 518 |
| `ed25519_test.json` | `.../testvectors_v1/ed25519_test.json` | `752d2ea7d7c6cf4736381b6cbacb61f8182b126ab7cd9b058f00c50084975536` | 151 |

Total: **1,309 Wycheproof cases**, all executed on every `cargo test` run. The
two specification vectors below are *not* additional cases: they are `tcId 1` of
the ChaCha20-Poly1305 file (`comment: "RFC 7539"`) and of the
XChaCha20-Poly1305 file (`comment: "draft-arciszewski-xchacha-02"`), re-stated
explicitly in the test source and re-run through the encryption direction and
through the exported `crypto::open` frame, which the Wycheproof loop does not
do for them.

### Mapping to the exported symbols

| Vector | Executed against | Notes |
|---|---|---|
| RFC 8439 section 2.8.2 (ChaCha20-Poly1305 AEAD) | `libsodium_rs::crypto_aead::chacha20poly1305_ietf`, encrypt and decrypt | `crypto.rs` exports no IETF ChaCha20-Poly1305 entry point. The vector validates the same bundled libsodium build that `seal`/`open` use; XChaCha20-Poly1305 is that pipeline preceded by HChaCha20. |
| draft-irtf-cfrg-xchacha section A.3.1 (XChaCha20-Poly1305 AEAD) | `crypto::open` on a `nonce24 + ciphertext + tag16` frame, and libsodium `xchacha20poly1305::encrypt` for the ciphertext direction | `crypto::seal` always draws a fresh random nonce, so the encryption direction of a fixed-nonce vector cannot go through `seal`; the frame layout that `seal` emits is still exercised end to end by `open`. |
| Wycheproof `chacha20_poly1305` | `libsodium_rs::crypto_aead::chacha20poly1305_ietf` | Each `valid` case is also re-encrypted and compared with the vector ciphertext. |
| Wycheproof `xchacha20_poly1305` | `crypto::open` (306 cases) and libsodium `xchacha20poly1305::encrypt` for the ciphertext direction | The 9 malformed-nonce cases cannot be represented in the fixed 24-byte frame; see below. |
| Wycheproof `x25519` | `libsodium_rs::crypto_scalarmult::curve25519::scalarmult` | `crypto.rs` exports no X25519 primitive. The vectors validate the bundled libsodium build **only**, and no product code path uses that implementation today — see the scope note below. |
| Wycheproof `ed25519` | `crypto::verify` | Verification only; see the skipped list. |
| RFC 8032 Ed25519 vector 1 | `crypto::signing_public_key_from_seed`, `crypto::sign`, `crypto::verify` | Already present in `crypto.rs`; it remains the signing-direction vector. |
| FIPS 180-4 `abc` | `crypto::hash` | Already present in `crypto.rs`. |
| Argon2id `m=65536 KiB, t=2, p=1` | `crypto::derive` | Independently generated vector already present in `crypto.rs`. |

### Scope limit of the X25519 corpus (read before citing it)

`grep -rn 'scalarmult\|curve25519' native/src/` returns **no match**: nothing in
the product calls libsodium's X25519. The two X25519 code paths that do ship use
different implementations:

- **MLS** uses `openmls_rust_crypto::OpenMlsRustCrypto` (`native/src/mls.rs:6`)
  for the `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` ciphersuite
  (`native/src/mls.rs:13`); its DH comes from `x25519-dalek`.
- **Tor/Arti** uses `tor-llcrypto` (`native/Cargo.toml:26`), i.e.
  `curve25519-dalek`.

So the 518 cases pin the low-order, twist and non-canonical behaviour of
libsodium, and nothing more. A defect in either shipping provider would remain
invisible to this suite. Equivalent Wycheproof coverage against the OpenMLS
provider (for example through `openmls_traits::crypto::OpenMlsCrypto`) and
against `tor-llcrypto` is **missing** and is listed as such in
[`../release-checklist.md`](../release-checklist.md).

### Result and flag handling

Wycheproof `result` values are applied literally: `valid` must be accepted with
the exact expected plaintext or a successful signature check, `invalid` must be
rejected **outright**, and `acceptable` may go either way and is only counted.

"Outright" is load-bearing. An AEAD case where the tag authenticates but the
recovered plaintext differs from the vector is a forgery, not a rejection, so it
is tracked in its own `mismatched` counter and both AEAD suites end with
`assert_eq!(mismatched, 0)`; an `invalid` case that produced one would fail
rather than pass. No flag
(`Twist`, `LowOrderPublic`, `NonCanonicalPublic`, `SpecialPublicKey`,
`SignatureMalleability`, `TinkOverflow`, `ModifiedTag`, `EdgeCasePoly1305`,
`InvalidNonceSize`, and the others) was used to exempt a case. The only
flag-shaped rule is in X25519, where the expected shared secret decides the
expectation: an all-zero secret must be refused by libsodium (it rejects
low-order results) and any other secret must be reproduced byte for byte.

Every count below is asserted by the test, not only printed, so this section
cannot drift from the code without a red build.

Executed counts on the recorded run:

- `chacha20_poly1305`: 325 executed, 256 `valid`, 69 `invalid`, 0 `acceptable`,
  0 mismatched, 0 skipped. 9 of the `invalid` cases carry a nonce whose length
  is not 96 bits and are refused when the nonce is built.
- `xchacha20_poly1305`: 315 executed, 246 `valid`, 69 `invalid`, 0 `acceptable`,
  0 mismatched, 0 skipped. 306 run through `crypto::open`; the remaining 9 have
  nonces that are not 192 bits.
- `x25519`: 518 executed, 264 `valid`, 254 `acceptable`, 0 `invalid`. 31 of the
  `acceptable` cases have an all-zero shared secret and are rejected by
  libsodium; the other 223 (twist points, non-canonical and special public
  keys) are computed and match the expected secret exactly.
- `ed25519`: 151 executed, 88 `valid`, 63 `invalid`, 0 `acceptable`. 12 of the
  `invalid` cases have a signature whose length is not 64 bytes and are refused
  by the length contract before libsodium is called. `crypto::verify` can also
  return `InvalidArgument` for an oversized message or a public key that is not
  32 bytes, so the test decides the expected cause from the vector itself and
  fails on any other error instead of folding it into this counter (all 78
  groups in the corpus carry a 32-byte public key).

### Skipped or not applicable, with reasons

- **RFC 9106 section 5.3 (official Argon2id vector): not applicable.** It pins
  `p=4`, `m=32 KiB`, a secret key and associated data. Libsodium fixes
  parallelism at 1 and exposes neither the secret nor the associated data
  parameter, and `derive` additionally floors memory at 64 MiB, so the official
  digest cannot be reproduced by any input this API accepts. What is executable
  is only the memory half of that statement: the first case of
  `crypto::tests::argon2id_rejects_parameters_outside_the_contract` asserts both
  `MIN_MEMORY_KIB > 32` and `derive(.., 32, 3) == Err(InvalidArgument)`, so
  lowering the floor to the RFC value breaks the build. The other three
  blockers (`p=4`, the secret key, the associated data) are **not expressible**
  in `derive`'s signature and therefore cannot be asserted at all — their
  unreachability is a documented API fact, not a tested one. The `p=1` profile
  the product actually uses stays covered by the vector in `crypto.rs`.
- **Third-party verification of the Argon2id `p=1` vector: still missing.**
  Nothing capable of computing Argon2id was installed on the build host
  (OpenSSL 3.0.13 exposes no Argon2 KDF, and neither an `argon2` CLI nor a
  Python binding is present) and **no package was added for this task**, so the
  claim is "not attempted here", not "unavailable": the PHC reference CLI is
  packaged for the distribution (`apt-cache policy argon2` →
  `0~20190702+dfsg-4build1`) and computes exactly the `p=1, m=65536 KiB, t=2`
  profile. The current digest `3fde0023…` was generated with the same libsodium
  build it validates, which is self-consistency, not independent confirmation.
  A cross-implementation vector needs a 16-byte ASCII salt (the existing salt is
  the raw bytes `0x00..0x0f`, which the CLI cannot take on the command line) and
  should record the exact command and digest here.
- **Wycheproof `eddsa_test.json`: does not exist in `testvectors_v1`.** The
  download returns HTTP 404; that generation publishes the EdDSA verification
  cases as `ed25519_test.json`, which is the file embedded here.
- **Ed25519 signing direction in Wycheproof: not covered by the file.**
  `ed25519_test.json` is a verification suite (`eddsa_verify_schema_v1.json`)
  and carries no private keys. RFC 8032 vector 1 remains the signing KAT.
- **X25519 through the JNI surface: not applicable.** No X25519 primitive is
  exported by `crypto.rs` or by `NativeCrypto`, so the vectors are bound to the
  libsodium implementation instead of a product entry point.
- **X25519 against the providers the product actually uses: missing.** As
  detailed in *Scope limit of the X25519 corpus*, MLS uses `x25519-dalek` via
  `openmls_rust_crypto` and Arti uses `curve25519-dalek` via `tor-llcrypto`;
  neither is exercised by this corpus. This is a real gap in gate 13, not a
  "not applicable".
- **9 + 9 malformed-nonce AEAD cases: rejected by construction.** The 24-byte
  nonce of `crypto::seal`/`crypto::open` and the 12-byte nonce of the IETF
  primitive make a shorter or longer nonce unrepresentable; the cases are
  counted as rejections rather than executed through the cipher.
- **Poly1305, HChaCha20 and ChaCha20 block-function vectors: not added.** The
  product exposes no standalone MAC or stream-cipher entry point for them; they
  are exercised indirectly by the AEAD vectors above.
- **Gate 13 is broader than this corpus.** The libsignal, RFC 9420/OpenMLS and
  PQXDH deviation items of the gate are not part of this task and are still
  recorded as pending in [`../release-checklist.md`](../release-checklist.md).

### Command and result

```sh
export CARGO_TARGET_DIR="$HOME/nomessages-target"
cd native && cargo test --all-features --locked
```

Recorded run on WSL Ubuntu with Rust 1.91 (2026-09-14, after the review fixes):
17 unit tests, 7 KAT tests (1,309 Wycheproof cases, of which 2 are also re-run
explicitly as the specification vectors), 3 MLS round-trip tests, 0 failures,
and 1 ignored test (`tor_live`, which requires a live Tor bootstrap). Printed
scoreboard of the KAT binary:

```text
chacha20_poly1305: executed 325, acceptable 0, rejected by length 9, mismatched 0, skipped 0
xchacha20_poly1305: executed 315, through crypto::open 306, acceptable 0, rejected by length 9, mismatched 0, skipped 0
ed25519: executed 151, acceptable 0, rejected by length 12
x25519: executed 518, acceptable 254, low-order rejections 31
```

The unit-test count went from 18 to 17 because
`argon2id_rejects_the_rfc9106_section_5_3_profile` was folded into
`argon2id_rejects_parameters_outside_the_contract`: both asserted the same
`MIN_MEMORY_KIB` branch, so the separate test added a name but no coverage.

`cargo fmt` and `cargo clippy` were not run: neither component is installed in
the WSL toolchain.
