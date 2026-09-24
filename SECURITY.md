# Security Policy

NoMessages is a privacy- and security-focused Android messaging app. We take reports of
vulnerabilities seriously and appreciate the effort of anyone who reports one responsibly.

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                 |

Only the latest `1.0.x` release receives security fixes. There is no long-term support branch at
this time.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull
requests.**

Instead, use GitHub's **Private Vulnerability Reporting**:

1. Go to the repository's **Security** tab.
2. Select **Report a vulnerability**.
3. Fill in as much detail as you can (see below).

If for some reason you cannot use that flow, open a normal issue asking the maintainer
([@maxwellmelo](https://github.com/maxwellmelo)) to open a private channel — without any
vulnerability details in the issue itself.

### What to include

- A clear description of the vulnerability and its impact.
- Steps to reproduce, or a proof of concept, if you have one.
- The app build (release or debug) and version, Android version, and device or emulator used.
- Whether the issue requires a rooted/compromised device or malicious accessibility service to
  exploit (see [Out of scope](#out-of-scope) below) — if so, please still report it if you believe
  it's more severe than our stated threat model assumes.

### What to expect

- **Acknowledgement:** we aim to acknowledge new reports within a few days.
- **Assessment and updates:** we aim to provide an initial assessment (severity, whether it's
  accepted, and a rough plan) within two weeks, and to keep you updated as work progresses.
- **Fix and disclosure:** timelines depend on severity and complexity. We will coordinate an
  appropriate disclosure timeline with you; we ask for reasonable time to ship a fix before any
  public disclosure.
- There is currently no bug bounty program; reports are appreciated but not financially
  compensated. With your permission, we're happy to credit you in the release notes.

## Scope

In scope:

- The NoMessages Android application (`dev.mx3.nomessages` and its debug variant).
- The pairing protocol and wire format (QR pairing, SAS confirmation, bundle exchange over Tor,
  the doorbell mechanism).
- The native cryptographic and transport code under `native/` (Tor integration, message framing).
- The vault format and its encryption (Argon2id key derivation, SQLCipher, attachment chunk
  encryption), and the real/decoy vault separation.
- The Android app's use of libsignal and OpenMLS.

## Out of scope

The following are explicitly out of scope, consistent with the threat model documented in
[docs/security-model.md](docs/security-model.md):

- A rooted, jailbroken, or otherwise compromised device (including a device with an installed
  malicious app or a malicious keyboard/accessibility service that can read the screen or input).
- Live-memory acquisition or forensic access to an already-unlocked vault.
- Coercion attacks that obtain the real password directly from the user (the panic/decoy vault
  reduces some disclosure risk but is not a defense against this).
- Physical attacks that don't rely on a software or protocol flaw.
- Vulnerabilities in third-party dependencies that are already publicly known and tracked
  upstream — please report those to the upstream project, and optionally let us know so we can
  track the update.
- Social engineering against the maintainer or contributors.

If you're not sure whether something is in scope, report it anyway — we'd rather triage a
borderline report than miss a real one.

## Security-relevant design references

Before reporting, you may find it useful to review:

- [docs/security-model.md](docs/security-model.md) — the current threat model, intended
  boundaries, and known limits.
- [SPEC.md](SPEC.md) — the full product and protocol specification.
- [docs/release-checklist.md](docs/release-checklist.md) — the security gate tracking for
  releases.
