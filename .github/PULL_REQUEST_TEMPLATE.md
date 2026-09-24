<!--
Thanks for contributing to NoMessages! Please read CONTRIBUTING.md before opening this PR,
especially the "Security-sensitive changes" section. Fill in every section below; delete
only what genuinely does not apply and say why.
-->

## Description

<!-- What does this PR change, and why? Link the issue it addresses, if any (e.g. "Closes #123"). -->

## Type of change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that would change existing behavior)
- [ ] Documentation only
- [ ] Build, CI, or tooling
- [ ] Refactor (no functional change)

## Checklist

- [ ] I opened an issue to discuss this change first (required for new features and any
      crypto/protocol/pairing/vault-format change).
- [ ] I branched from `develop` and this PR targets `develop` (not `main`).
- [ ] Tests: I added or updated tests that cover this change, and `bash scripts/build-android.sh
      --bootstrap` (or the relevant subset) passes locally.
- [ ] Lint: `lintDebug` is clean for the code I touched (`bash scripts/build-android.sh
      --app-only`).
- [ ] Strings: if I added or changed any user-facing string, I updated both
      `app/src/main/res/values/strings.xml` (English) and
      `app/src/main/res/values-pt/strings.xml` (Portuguese).
- [ ] Docs: I updated `SPEC.md`/`docs/` if this change affects behavior, the security model, or
      build/setup steps.
- [ ] Commits: my commits follow [Conventional Commits](https://www.conventionalcommits.org/) and
      are signed off (`git commit -s`), per the DCO.
- [ ] All code, comments, and commit messages are in English.

## Security / privacy impact

<!--
Required. If this PR touches crypto, the pairing protocol, the vault format, logging, real/decoy
vault parity, or network behavior, describe the impact explicitly. If there is truly none, say so.
-->

- [ ] No new logging of sensitive data (passwords, keys, message content, contact identities,
      onion addresses).
- [ ] No plaintext/decrypted data newly written to disk.
- [ ] Real and decoy vault behavior remains equivalent (growth, timing, error paths), if this PR
      touches vault code.
- [ ] No new network dependency introduced beyond the existing Tor transport.

## How was this tested?

<!-- Commands run, devices/emulators used, and what you observed. -->

## Additional context

<!-- Screenshots, benchmarks, related PRs/issues, anything else a reviewer should know. -->
