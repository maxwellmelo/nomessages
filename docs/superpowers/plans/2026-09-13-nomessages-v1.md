# NoMessages v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Track evidence in the ledger. Independent modules may be implemented concurrently with exclusive file ownership, as directed by the session developer instructions.

**Goal:** Construir o aplicativo Android NoMessages definido no SPEC, incluindo validação reproduzível e identificação explícita de gates externos pendentes.

**Architecture:** Kotlin/JVM core, Android Compose app, Rust/JNI libsodium/Arti/OpenMLS. Persistência cifrada; uma sessão ativa; rede disponível somente com vault aberto.

**Tech Stack:** Kotlin 2.x, Compose, SQLCipher 4, libsignal-client, libsodium, Arti, OpenMLS, Gradle e Cargo com versões fixas.

**Spec:** `SPEC.md` e `docs/superpowers/specs/2026-09-13-implementation-design.md`.

## Global Constraints

- Package `dev.mx3.nomessages`, minSdk 31, somente ABIs 64 bits.
- PT-BR default e EN secundário; ícone próprio, paleta clássica especificada.
- Sem contas, Firebase, mailbox, plaintext em disco, chaves em preferências, backup automático ou fallback de rede direta.
- Argon2id >=64 MiB, p=1, calibração ~2500 ms; ambos os slots sempre derivados.
- Senhas >=16 caracteres e zxcvbn >=4; senha de pânico distinta e obrigatória.
- AEAD com nonces aleatórios; arquivos chunks 64KiB com identidade/índice/epoch autenticados.
- QR de uso único, TTL120s, SAS confirmado por ambos; grupo 3..100 com provas de todas as arestas.
- Nenhum teste ou gate pode ser declarado aprovado sem execução observada.

### Task 1: Toolchain e builds
**Files:** Gradle settings/root/core/app build scripts, wrapper, manifest, scripts/build-android.sh, native Cargo configuration.
**Interfaces:** módulos `:core`, `:app`; bibliotecas de protocolo solicitadas pelas tarefas 3/4; JNI `libnomessages.so`.
- [ ] Fixar dependências oficiais e instalar ferramentas em diretório próprio com limite de recursos.
- [ ] Compilar núcleo JVM, JNI host, app Android e gerar APK de desenvolvimento.
- [ ] Validar manifest, ABIs, ausência de backup e de permissões desnecessárias.

### Task 2: Vault e arquivos
**Files:** `core/.../vault`, `core/.../files`, respectivos testes; contratos descritos em `docs/development/contracts.md`.
**Interfaces:** `Crypto`, `VaultManager`, `VaultSession`, `VaultArchive`, `EncryptedFiles`; armazenamento fornecido pelo app.
- [ ] Escrever e executar teste que rejeita chave incorreta/adulteração antes da implementação.
- [ ] Implementar header versionado, wraps independentes, calibração e fechamento com descarte de chaves.
- [ ] Implementar export/import delimitado e seguro e AEAD de chunks com tamanho total autenticado.
- [ ] Executar isolamento real/pânico, tamper, truncamento, export/import e limites.

### Task 3: Pareamento, mensagens e grupos
**Files:** `core/.../protocol`, `core/.../groups`, schemas protobuf, testes.
**Interfaces:** `PairingEngine`, `SignalSessions`, `PairingGraph`, `FrameCodec`; byte arrays serializáveis cifrados pelo vault.
- [ ] Testar TTL/replay/assinaturas e clique incompleto antes de implementar.
- [ ] Implementar protobuf limitado e transcript canonical CBOR; SAS vinculado a ambas ofertas.
- [ ] Integrar libsignal oficial com armazenamento serializável, confiança explícita e rollback em falhas.
- [ ] Implementar provas bilaterais de arestas e validar 3..100 membros, inclusão e exclusão.
- [ ] Executar dois participantes, replay, persistência, ratchet e testes de frames adversariais.

### Task 4: Native crypto, Tor e MLS
**Files:** `native/src/*`, `core/.../crypto`, contratos JNI.
**Interfaces:** primitives `Crypto`; Tor handle start/send/poll/stop; MLS byte-array request/state exchange.
- [ ] Integrar libsodium sem primitives próprias, testes de round-trip e vetores oficiais.
- [ ] Integrar Arti onion services com keystore cifrado e lifecycle encerrável.
- [ ] Integrar OpenMLS create/welcome/commit/application/remove e persistência cifrada.
- [ ] Validar host build; compilar ABIs Android; executar testes nativos offline.

### Task 5: Interface Android
**Files:** `app/.../ui`, recursos strings/ícone, tela raiz Compose.
**Interfaces:** `UiState`, `UiActions` definidos em `docs/development/contracts.md` e `app/.../ui/UiContract.kt`.
- [ ] Implementar setup/lock, lista/conversa, QR/SAS, anexos/viewer, contatos, wizard de grupos e settings.
- [ ] Expor estados de erro, espera offline, confirmação de SAS e import/export reais.
- [ ] Aplicar PT-BR/EN, acessibilidade, FLAG_SECURE e eliminar dados de UI ao bloquear.

### Task 6: Integração Android e armazenamento
**Files:** `app/.../storage`, `app/.../runtime`, `MainActivity.kt`, `NoMessagesApplication.kt`.
**Interfaces:** consome tarefas2..5; toda alteração de estado criptográfico + outbox em transação.
- [ ] Implementar SQLCipher, setup decoy, padding simétrico e repositório de mensagens/arquivos.
- [ ] Conectar ações da UI a operações reais; implementar CameraX/QR, SAF e viewer interno.
- [ ] Implementar lifecycle, lock por screen-off/background, serviço Tor isolado e cancelamento de jobs.
- [ ] Executar testes de integração e revisar todos os fluxos contra o SPEC.

### Task 7: Threat gates, documentação e revisão
**Files:** testes instrumentados, `scripts/threat-gates.sh`, README, SECURITY, checklist de release, CI.
- [ ] Executar todos os testes/builds disponíveis e preservar relatórios.
- [ ] Revisar confidencialidade, autenticidade, persistência e concorrência; corrigir achados relevantes.
- [ ] Registrar para cada gate: evidência executada ou motivo concreto da pendência.
- [ ] Entregar fontes, APK se build concluído, comandos reprodutíveis e limites de validação.
