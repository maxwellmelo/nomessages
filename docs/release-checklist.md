# Release checklist and threat evidence

**Release status: NOT_RUN.** A build, unit test or empty marker scan does not establish these device guarantees. Record device model, Android/API version, ABI, APK SHA-256, source commit, date, exact steps and artifact paths for each result. Use `PASSED`, `FAILED` or `NOT_RUN`; never silently treat unavailable tooling or a skipped test as success. The security boundary and current deviations are described in [security-model.md](security-model.md).

Use two explicitly authorized disposable test devices with harmless test conversations and separate real/panic passwords. Do not put production messages, passwords or personal notifications in the evidence environment. An optional third test identity is needed to exercise a complete three-member clique. Evidence archives can reveal a defect or contain device metadata; keep them private with restrictive permissions.

## Read-only collector

Prepare a unique harmless marker file without a trailing newline, place exactly that marker into a test conversation, then lock NoMessages using its own lock action. Keep Android itself unlocked and USB debugging authorized. `--device-ready` attests the operator has done that; it does not remotely unlock anything. `--app-state` records an operator observation, not a programmatic proof of app lock.

```sh
printf '%s' 'nomessages-harmless-marker-2026-09-13-case01' > /tmp/nomessages-marker.txt
scripts/threat-gates.sh --serial TEST_DEVICE_SERIAL --device-ready \
  --package dev.mx3.nomessages.debug --app-state locked \
  --marker-file /tmp/nomessages-marker.txt --output /tmp/nomessages-evidence-case01
```

The script requires an explicit serial. It invokes only device reads: package/window/notification/process/socket snapshots, APK/manifest inspection when `aapt2` is available, and `run-as` tar collection of the app's private directory. It never installs, starts, stops, clears, erases or unlocks the app/device. There is no `adb root`, `pm clear` or backup command. It never extracts archive entries or prints marker matches. Partial/unavailable collections are `NOT_RUN`. Dump size, entry count, command duration and parser reads are bounded. No real device is selected or used by the repository's script self-checks.

Archive collection includes all app-private data, including cache/state files: calling an archive “encrypted” is a hypothesis to test, not an assumption that excludes unexpected plaintext files. The real/decoy ciphertext databases are `files/vault/real.db` and `files/vault/decoy.db`; the header and encrypted attachment directories are siblings. The collector records their lengths, but does not open SQLCipher with any key. `report.json` keeps individual observations separate from the 13 specification gates. Its release status remains `NOT_RUN` unless a detected counterexample makes it `FAILED`.

## Specification gates

| Gate | Required experiment and passing evidence | Current device status |
|---|---|---|
| 1. Locked dump and wrong keys | Populate messages/files with harmless markers. Lock app, obtain a complete authorized private-data dump, and scan all regular files plus DB sidecars/caches. Separately use the **same SQLCipher build/settings** to open copied DBs with empty key, Android PIN and literal `password`; a query of `sqlite_master` must fail for every wrong key, while a controlled positive fixture proves the harness can open valid SQLCipher data. Document coverage and expected exception type. No real password in command lines or logs. | NOT_RUN |
| 2. Real/decoy sizes | Measure both encrypted DBs **and their media directories** (`real.files`/`decoy.files`) after equivalent lifecycle points and checkpointing; report exact bytes and the adopted comparison threshold. Since T4.1 (2026-09-23), attachments no longer grow only the slot actually in use: `MessagingEngine.storeAttachment` mirrors every real write with a same-size blind-cover blob in the sibling slot's directory, and `AndroidVaultStorage.mediaCapacityBytes` fixes each slot's reservation (default 512 MiB, `mediaCapacityMiB`). Parity now needs checking at three levels, not just the `.db` files: (a) `real.db`/`decoy.db` byte-exact, as before; (b) `real.files`/`decoy.files` total directory size byte-exact; (c) file **count** in each directory equal (the cover write creates exactly one file per real write, under an indistinguishable `<random-hex>.bin` name). The collector can mark the narrow observation of equal lengths/counts passed. Repeat after message/file volume changes, including at least one attempt that exceeds `mediaCapacityBytes` to also confirm `MEDIA_CAPACITY_EXHAUSTED` fails cleanly without leaving the two directories unequal; a single equality is not forensic deniability. See `docs/security-model.md`, "Fixed media reservation and blind cover growth (2026-09-23, T4.1)", and the two `AndroidVaultStorageTest` cases it cites for the JVM/instrumented-level evidence this gate's device run still needs to reproduce on hardware. | NOT_RUN |
| 3. Unlock timing | Run at least 100 randomized trials each for real, panic and wrong passwords on a fixed device/build. Record monotonic durations without passwords, labels only in private analysis. Publish distributions, device thermals, ordering and an explicit jitter criterion before accepting parity. Include Argon2 parameters and both derivations per trial. | NOT_RUN |
| 4. Secure screenshots | On lock screen, chat, attachment viewer, pairing QR and every dialog, record target-window `FLAG_SECURE`; use authorized screenshots/screen recording and verify content is black/omitted or capture fails. Test Android 12+ physical devices. A flag in source alone does not pass. | NOT_RUN |
| 5. Recents | Put a harmless marker on screen, open Recents, switch apps, rotate and restore. Inspect thumbnails for marker/content. Record screen evidence without exposing secrets. | NOT_RUN |
| 6. Notifications | Trigger foreground/service and incoming-message notifications while app is open/backgrounded/locked. Privately inspect actual unredacted test notification extras and visible UI: title only `NoMessages`, no sender/body. A redacted `dumpsys` result cannot prove an empty body. Since T4.17 there are exactly three notification ids and two of them carry text that is **constant**, never derived from a message, sender or count — verify each literally, in both locales: id 1, the message notice, title `NoMessages` and **empty body**; id 2, the doorbell pending notice, title `NoMessages` and the fixed sentence `doorbell_pending_body` and nothing else — no count, no sender, no preview, present only after an accepted knock and cancelled on unlock; id 3, the `:tor` foreground notice in minimal mode, title `NoMessages`, **no body**, channel `background_service`, `IMPORTANCE_LOW`, silent and ongoing. All three must be `VISIBILITY_SECRET` and local-only. A body appearing on id 1 or id 3, or any id 2 body other than that exact string, fails this gate. | NOT_RUN as a gate — partial evidence for two of the three ids, English locale only. Live run of 2026-09-18 on two Android 15 x86_64 emulators (B = `emulator-5560`, debug build), unredacted `dumpsys notification`; file paths below are relative to `docs/development/build-logs/doorbell-20260918/` and the narrative with UTC timestamps is in [development/device-verification.md](development/device-verification.md), "T4.17 — campainha, validação ao vivo em dois emuladores (2026-09-18)". **id 3 PASSED (locked state, en)**: right after an explicit lock the only record of the package is id=3, channel `background_service`, `importance=2`, `vis=SECRET`, `ONGOING`, `ONLY_ALERT_ONCE`, `NO_CLEAR`, `FOREGROUND_SERVICE`, `LOCAL_ONLY`, title `NoMessages`, no body — `12-B-notifications-after-lock.txt` (18:21:26Z). **id 2 PASSED (locked state, en)**: after an accepted knock, id=2, channel `private_messages`, `importance=3`, `vis=SECRET`, `LOCAL_ONLY`, `android.title=NoMessages`, `android.text=You have messages waiting. Open NoMessages.` and no other text extra — `14-B-doorbell-notification.txt`, `15-B-notification-shade-doorbell.png` (18:22:46Z), reproduced after the T4.19 fix in `28-B-acceptance-after-fix.txt` (20:16:30Z); absent before any knock (same `12-…`) and cancelled on unlock, leaving no notification record of the package — `16-B-pids-and-notifications-after-unlock.txt` (18:23:44Z). **Still NOT_RUN**: id 1 in any locale; all three texts in the **pt-BR** locale; the app-open and app-backgrounded trigger states; and any physical-device repetition — every observation above is from emulators. |
| 7. Backup refusal | Inspect compiled manifest `allowBackup=false`, backup/extraction XML and cloud/device-transfer exclusions. On a supported authorized test OS verify attempted backup excludes app data; distinguish a missing/deprecated `adb backup` command from an app refusing backup. Record OEM transfer behavior as applicable. | NOT_RUN |
| 8. QR expiry/replay | Execute both real devices' offer/response/SAS/confirmation flow. Scan after 120 seconds: reject. Scan a consumed offer again, including after app restart: reject. Mismatched SAS, modified payload, unexpected signer and substituted Signal bundle/identity must not create a contact. Both peers display the same six digits and require explicit approval. On 2026-09-18 a real Galaxy Note10+ camera read a compact pairing QR and a complete end-to-end pairing ceremony with a real device succeeded (`docs/development/device-verification.md`, "Aparelho físico — 2026-09-18"); the adversarial experiments this gate specifically requires — deliberately expired-QR rejection, replay of a consumed offer, malformed/substituted SAS or bundle — remain untested. | NOT_RUN |
| 9. Clique | Using three test identities, create only AB and AC edges: group creation reports BC missing. A forged unilateral BC claim still fails. Pair B/C with bilateral evidence: group creation succeeds. Verify deterministic missing pairs, 3/100 bounds, duplicate and 101-member rejection; attach core test reports and a device UI trace. | NOT_RUN |
| 10. Lock closes network | Since T4.17 the lock has **two** legitimate behaviours, selected by the per-vault *Pending message notice* setting (default **on**), and this gate requires evidence for **both**. Neither mode is a weaker variant of the other; a run that exercises only one does not pass. **(a) Doorbell OFF — unchanged behaviour, unchanged evidence.** Establish Tor sessions, exchange 10 messages, then separately trigger explicit lock, background timeout and screen-off. Record process IDs before/after; the `:tor` process **must terminate**. Use authorized privileged per-process FD/socket accounting or packet capture to attribute **all** app network sockets. A shell `ss` listing without PID visibility is inconclusive. Test in-flight send/download cancellation and no unlocked-state resurrection. **(b) Doorbell ON — minimal mode.** Same three lock triggers. The `:tor` process **may** stay alive, as a foreground service; what must be shown is that it serves *only* the doorbell: (i) the messaging onion is gone — its descriptor is no longer published and a peer's delivery attempt to it fails, with no accepted connection attributable to that service; (ii) the only service answering is the doorbell onion, on virtual port **4243**, and a connection to it that carries anything other than a valid 57-byte knock is closed with **zero** bytes written back; (iii) no session or database data is reachable — repeat gate 1's locked-dump scan and wrong-key trials against this state and get the same result; (iv) the foreground notification is present and is the expected one (channel `background_service`, `IMPORTANCE_LOW`, silent, title `NoMessages`, no body, no count, no sender) and is removed on unlock; (v) a valid knock from a paired contact raises the pending-message notice (fixed sentence, no sender, no count) and an invalid one raises nothing; (vi) unlocking the **same** vault reuses the live child rather than rebuilding it, and unlocking a **different** vault, or entering the panic password, tears the child down completely — record PIDs for each. Both modes additionally: verify the encrypted guard checkpoint restores the same sampled guard history after a normal lock, preserves the previous good checkpoint when capture fails, and survives abrupt process death up to its last checkpoint. Confirm live Arti metadata is removed only after process death; record the remaining flash-remanence limitation, and note that mode (b) keeps that metadata live for the duration of the locked session. | NOT_RUN as a gate — mode (a) entirely NOT_RUN, mode (b) partially PASSED. Every observation below comes from the live run of 2026-09-18 on two Android 15 x86_64 emulators (A = `emulator-5556`, B = `emulator-5560`, debug build, notice **on**, explicit "Lock now" as the only trigger); file paths are relative to `docs/development/build-logs/doorbell-20260918/` and the narrative with UTC timestamps is in [development/device-verification.md](development/device-verification.md), "T4.17 — campainha, validação ao vivo em dois emuladores (2026-09-18)" plus its two addenda. Premise of mode (b) observed: the `:tor` process survives the lock with the same PID `18555` — `10-B-pids-before-lock.txt` (18:20:42Z) and `11-B-pids-after-lock.txt` (18:21:15Z). **(iv) PASSED** — `12-B-notifications-after-lock.txt` (18:21:26Z) records the foreground notice id=3 present, channel `background_service`, `importance=2`, `vis=SECRET`, ongoing, title `NoMessages`, no body, no count, no sender, and `16-B-pids-and-notifications-after-unlock.txt` (18:23:44Z) records no notification of the package left after unlock. **(v) PASSED for the valid-knock half only** — a message sent from A at 18:22:20Z (`13-A-send-timestamp.txt`) failed direct delivery and raised the pending notice on the locked B 26 s later, with the fixed sentence and no sender or count (`14-B-doorbell-notification.txt`, `15-B-notification-shade-doorbell.png`), and the message itself arrived only after unlock (`17-B-chatlist-message-after-unlock.png`, `18-B-chat-campainha-delivered.png`, 18:24:21Z); the sequence was re-run end to end after the T4.19 fix (`28-B-acceptance-after-fix.txt`, `29-B-acceptance-delivered-after-fix.png`). The "an invalid one raises nothing" half — wrong token, replay, out-of-window knock — was never exercised. **(vi) PASSED for the same-vault half only**, after the T4.19 native fix (`native/src/tor.rs`, `native/src/doorbell.rs`) — the isolated probe repeated lock/unlock of the **same** vault five times and the `:tor` PID `23229` was identical at every sample, where the pre-fix build lost it in 5 of 5 runs (`27-B-pid-stable-after-fix.txt`, 20:03–20:08Z; diagnosis in `22-handoff-root-cause.txt`, `23-B-handoff-probe-instrumented.txt`, `24-B-logcat-native-error.txt`), and that same process then carried a sixth cycle with the full acceptance scenario (`28-B-acceptance-after-fix.txt`). **NOT_RUN**: all of mode (a); (i), (ii) and (iii) of mode (b); the invalid-knock half of (v); the different-vault and panic-password teardowns of (vi); the background-timeout and screen-off lock triggers; the encrypted guard checkpoint clauses required of both modes; and any physical-device repetition. |
| 11. Real export/import | With harmless real conversations/files and established contacts on A, export the closed/checkpointed ciphertext vault using the app. Import that artifact into a separately prepared test installation on B and unlock using only the real password. Compare messages, attachment hashes, identities and contact/session continuity. No account, original device keystore or extra QR may be required. | NOT_RUN |
| 12. Panic export/import | Import the same ciphertext artifact into the independently prepared test installation. Unlock with the panic password: only decoy conversations/files/identity/onion exist; known real markers and contacts must not appear. Repeat lock/unlock and network checks with the decoy. Clearing a device for a later trial requires separate explicit operator authorization; this collector never does it. | NOT_RUN |
| 13. Cryptographic vectors | Attach exact reports for RFC 8439/7748/8032/9106 and relevant Wycheproof cases, actual official libsignal exchange/restore/tamper tests, and RFC 9420/OpenMLS vectors. Record skipped/missing cases. Round-trip tests alone do not satisfy all known-answer vectors or an external audit. Current mandatory PQXDH deviation must be assessed explicitly. Partial evidence on 2026-09-14 (task T4.3): `native/tests/kat.rs` executes RFC 8439 section 2.8.2, draft-irtf-cfrg-xchacha section A.3.1 and 1,309 Wycheproof cases (`chacha20_poly1305`, `xchacha20_poly1305`, `x25519`, `ed25519`) from the digest-pinned corpus in `native/tests/vectors/`, alongside the existing FIPS SHA-256, RFC 8032 vector 1 and Argon2id `p=1` vectors; sources, SHA-256 digests, symbol mapping and every skipped case (RFC 9106 section 5.3 is unreachable through libsodium) are in [development/crypto-report.md](development/crypto-report.md). Still missing for this gate: RFC 7748 section 6.1 evidence from the JVM side; official libsignal exchange/restore/tamper reports; RFC 9420/OpenMLS vectors; the explicit PQXDH deviation assessment; **an independently verified Argon2id vector** — RFC 9106 section 5.3 is unreachable through libsodium (`p=4` plus a secret key and associated data, none of them exposed) and the executed `p=1` vector is self-generated with the same libsodium build it validates, not third-party verified; and **X25519 vectors against the implementations the product actually uses** — the 518 Wycheproof cases run against bundled libsodium, which no product code path calls, while MLS uses `x25519-dalek` (via `openmls_rust_crypto`) and Arti uses `curve25519-dalek` (via `tor-llcrypto`). | NOT_RUN |

## Two-device messaging and membership procedure

1. Record APK hash, build logs, device serials and ABI/API versions. Manually prepare each test vault with its own strong real/panic passwords. Never record passwords in artifacts.
2. Keep both apps unlocked and wait for their distinct real onion services to become ready. A displays an offer; B scans and displays its response QR. A scans it. Compare SAS aloud; both explicitly approve, then exchange confirmation QRs. Contact creation must occur only after matching peer confirmation and storage commit.
3. Send five numbered harmless messages from A to B and five from B to A, including one long message crossing a frame boundary and one encrypted attachment with a known SHA-256. Compare exact contents/hashes and acknowledgement states; retain only harmless transcripts. Verify no alias/sender field is added outside authenticated ciphertext.
4. Take B offline, send a message from A, and inspect pending state. Restore B online while A remains unlocked: delivery resumes from A's encrypted outbox. Both offline must not imply successful delivery; there is no third-party mailbox.
5. Repeat Tor readiness, messaging, lock and zero-socket experiments after app restarts. Run QR expiry/replay and failed-SAS trials separately so unsuccessful ceremonies cannot be mistaken for established contacts.
6. Add the third test identity to exercise the missing BC edge and subsequent complete clique. Welcome/Commit traffic must use existing authenticated 1:1 channels; application messages must use MLS, not pairwise fanout. Remove a member and verify a new epoch prevents that member from decrypting subsequent application messages.
7. Execute independent real-password and panic-password export/import trials above. A resumed imported identity is the same identity: these tests are sequential migration trials, not a claim that live simultaneous multi-device operation is supported.

## Evidence interpretation

Keep the machine JSON, untouched artifacts, APK/build hashes, manual observation record and test reports together. Record failures immediately; rerun after a fix with a new directory and retain the earlier failing evidence. The collector's stdout contains statuses only. `PASSED` refers to the named observation on the named build/device. Missing protected data in one dump does not establish absence in RAM, flash remnants, OEM backups or another lifecycle state. A full external security audit and device gate completion remain separate release requirements.

## Build de release 1.0.0 (2026-09-23)

**Este build assinado NÃO substitui os gates de dispositivo físico nem a auditoria externa de
segurança.** Os itens T4.1, T4.6 e T4.7 do roadmap (ver
`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`) permanecem **PENDENTES** para hardware
físico — o trabalho descrito neles até aqui foi feito/validado em emulador, não em aparelho real — e
a auditoria externa de segurança independente também permanece **PENDENTE**. Todas as linhas
`NOT_RUN` na tabela de gates acima continuam `NOT_RUN`; nada neste build as altera.

### Como gerar o build assinado

1. A chave de assinatura de release **não fica no repositório**. Ela é resolvida em tempo de
   configuração do Gradle a partir da variável de ambiente `NOMESSAGES_SIGNING_PROPERTIES` (ou da
   propriedade de projeto `-Pnomessages.signing=...`), que deve apontar para um arquivo
   `signing.properties` local contendo `storeFile`, `storePassword`, `keyAlias` e `keyPassword`.
2. Se a variável/propriedade não estiver definida, ou o arquivo não existir, ou faltar alguma das
   quatro chaves, o build type `release` simplesmente fica **sem assinatura** (comportamento padrão
   do AGP) — isso nunca quebra `assembleRelease`, `lintRelease`, builds de debug ou o CI, que não
   define essa variável.
3. Com a variável definida corretamente, `./gradlew :app:assembleRelease` produz um APK assinado,
   gerando `app/build/outputs/apk/release/app-release.apk`. Na build 1.0.0 gerada em 2026-09-23, o
   `apksigner` confirmou apenas o esquema v2 habilitado (v1/v3/v3.1/v4 desabilitados) — ver resultado
   detalhado abaixo.
4. Nenhum conteúdo do `signing.properties` (em especial a senha) é impresso, logado ou versionado em
   nenhuma etapa deste processo.

### Onde fica a chave

`C:\Users\maxwe\.nomessages-release\` (apenas o caminho é registrado aqui; o conteúdo do keystore e
do `signing.properties`, incluindo qualquer senha, nunca é exposto em documentação, logs ou
histórico de comandos).

**Aviso crítico:** esta é a única chave de assinatura de release do NoMessages. **Sem um backup
seguro dela (keystore `.p12` + `signing.properties`), não é possível publicar nenhuma atualização
futura do app** — o Android exige que toda atualização de um pacote instalado seja assinada com a
mesma chave que assinou a versão original; perder a chave significa não poder mais atualizar
instalações existentes de `dev.mx3.nomessages`, apenas publicar um pacote novo (com um novo
histórico de confiança) que os usuários teriam que reinstalar do zero.

### Fingerprint do certificado

```
CN=NoMessages, O=NoMessages
SHA-256: 28:9D:C1:88:5B:BE:A0:96:97:94:74:09:3A:64:F8:53:50:42:27:CC:62:41:A1:B3:84:AC:15:E0:10:39:39:99
```

### Artefato final

- Caminho: `artifacts/nomessages-1.0.0.apk` (cópia de
  `app/build/outputs/apk/release/app-release.apk`)
- Tamanho: ~61,9 MB
- versionName: `1.0.0` / versionCode: `1`
- SHA-256: `dfadbcb7c500e25cd9e46a5d5c916e5b3534a4cb5edd278687e6a4751343d729`
  (calculado de forma independente por `scripts/verify-apk.py` e por `certutil -hashfile ... SHA256`
  no PowerShell — os dois valores batem)

### Resultado resumido das verificações estáticas

Evidências completas em `docs/development/build-logs/release-1.0.0/`.

| Verificação | Resultado |
|---|---|
| `apksigner verify --verbose --print-certs` | PASSOU. Assinado com o esquema v2 (v1, v3, v3.1 e v4 desabilitados); DN `CN=NoMessages, O=NoMessages`; certificado SHA-256 confere com o fingerprint acima; chave RSA de 4096 bits. |
| `aapt2 dump badging` | PASSOU. `package=dev.mx3.nomessages`, `versionCode=1`, `versionName=1.0.0`, sem `application-debuggable`. |
| `aapt2 dump xmltree --file AndroidManifest.xml` | PASSOU. Nenhum `DebugQrReceiver`, nenhuma permissão `INJECT_QR`/`DUMP_QR`; `android:allowBackup=false`; `android:usesCleartextTraffic=false`; `android:fullBackupContent=false`. |
| `scripts/verify-apk.py --tools <toolchain WSL>` | PASSOU sem nenhuma modificação no script — ele já aceitava tanto `dev.mx3.nomessages` quanto `dev.mx3.nomessages.debug` como pacote esperado (linha `require(package is not None and package[1] in (...))`). Checou: ELF64/16 KiB em todas as libs nativas (arm64-v8a e x86_64), manifesto empacotado (API 31, sem backup, sem cleartext, sem inicializadores/activities auxiliares indesejados), exclusões compiladas de cloud-backup/device-transfer para todos os domínios de armazenamento do app, alinhamento ZIP para páginas de 16 KiB e a assinatura do APK. |

### Resultado resumido do teste funcional (emulator-5556)

Detalhes completos, incluindo o troubleshooting da UI, em
`docs/development/build-logs/release-1.0.0/emulator-test.md`.

- Instalação do release lado a lado com o app de debug: OK, sem conflito de pacote.
- Criação de cofre pela UI (senha real + senha de pânico/isca fornecidas pelo operador, não
  registradas em nenhum artefato): OK.
- Status "Tor connected" alcançado ~60s após a criação do cofre.
- Com `debug.nomessages.allow_capture=1` setado, force-stop, reabertura e desbloqueio com a senha
  real: o app desbloqueou normalmente (prova de que a senha funciona), mas `screencap` produziu uma
  imagem **100% preta** (0 de 2.592.000 pixels acima do limiar de intensidade 10, medido com PIL) —
  confirma que `FLAG_SECURE` permanece efetivo no build de release e que o gancho de debug de captura
  é **inerte** fora de builds de debug. Nenhuma captura com conteúdo real do cofre foi salva.

### Pendências explícitas

- Gates de hardware físico T4.1, T4.6 e T4.7 do roadmap: **PENDENTES**.
- Auditoria externa de segurança: **PENDENTE**.
- Todos os 13 gates de especificação na tabela acima permanecem `NOT_RUN` neste checklist; este build
  não os satisfaz.
