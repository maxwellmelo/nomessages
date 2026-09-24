# T4.16 — validação ao vivo do QR compacto com busca do bundle por Tor (2026-09-17)

Registro cronológico da sessão que executou, em dois emuladores reais em execução, o pareamento
formato 2 introduzido por T4.16. Todos os horários são **UTC**, lidos do host com
`date -u`. Nenhum comando foi emitido sem `-s <serial>` explícito; o aparelho físico
`RX8MA0GD9ZY` (Galaxy Note10+) **nunca** foi alvo de nenhum comando desta sessão.

Aparelhos:

| Papel | Serial | AVD | Vault |
|---|---|---|---|
| A | `emulator-5556` | nomessages35 | cofre "real" preservado (senha do usuário), **nunca** `pm clear` |
| B | `emulator-5560` | nomessages35b | `pm clear` autorizado + cofre novo criado pela UI real |

Ferramentas: `adb` em `C:\Users\maxwe\.nomessages-tools\platform-tools\adb.exe`;
build via `wsl -d Ubuntu -- bash .../scratchpad/gradle-wsl.sh`; relay de QR via
`scripts/emulator-pair.sh`.

---

## Resumo executivo

O pareamento formato 2 **funciona de ponta a ponta** entre dois emuladores: oferta de **405 B**,
resposta de **448 B**, confirmação de **317 B** (contra 2708/2750/314 B do formato 1 em T3.3 run4),
busca do bundle por Tor concluída **nos dois sentidos em menos de 10 s** — muito dentro do orçamento
de 300 s —, SAS idêntico nos dois lados, contato salvo nos dois lados e uma mensagem entregue em
cada direção.

Para chegar lá, **três defeitos reais foram encontrados e corrigidos**, dois deles bloqueadores
absolutos que a bateria de testes de host (`:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`)
não podia ter pego:

1. **`DecoyFactory` zerava a chave de doorbell antes de usá-la** → **nenhum cofre podia ser criado**
   em aparelho nenhum. 15 de 19 casos instrumentados falhavam.
2. **A fixture `downgradeToSchemaVersionOne` não desfazia as colunas da v3** → o caso de migração
   v1→v3 falhava com `duplicate column name: doorbell_onion`.
3. **O rótulo da contagem regressiva travava em "expirado"** depois da primeira expiração, mesmo com
   o QR sendo regenerado corretamente a cada 120 s.

**O gate de câmera real do Galaxy Note10+ — que é o defeito que originou T4.16 — continua NOT_RUN**,
e por isso T4.16 permanece `[ ]`.

---

## Cronologia

### 18:14–21:16 — Leitura obrigatória e preparação

- Lidos na íntegra `docs/development/device-verification.md` (com atenção às seções T3.3 run3 e
  run4), `scripts/emulator-pair.sh`, `docs/changes/emulator-pair.sh.md`.
- Armadilhas de automação de run4 aplicadas **desde o início**, não redescobertas:
  `svc power stayon true`, `settings put system screen_off_timeout 1800000`,
  `ime disable` em todo IME habilitado, nunca dois `uiautomator dump` concorrentes, nunca `BACK`
  para fechar teclado.
- `21:15` — estado inicial: **os dois emuladores com o cofre TRANCADO**. A mostrava a logo `NM`
  (build novo); B mostrava o monograma antigo (build desatualizado, ainda não reinstalado).

**Primeira tentativa de build falhou por um motivo de ambiente, não de código:** rodar
`wsl -d Ubuntu -- bash /mnt/c/...` pela ferramenta Bash (Git Bash) faz o MSYS traduzir
`/mnt/c/...` para `C:/Program Files/Git/mnt/c/...`. O mesmo comando pelo PowerShell funciona.
Registrado aqui porque custa tempo toda vez que alguém tropeça nisso.

### 21:16:00 — Suíte instrumentada em A: **FALHA, 15 de 19 casos**

```
bash scratchpad/t31-devices.sh emulator-5556
Tests run: 19,  Failures: 15
```

Todos os 15 com o mesmo stack:

```
java.lang.IllegalArgumentException: Invalid doorbell identity key
    at dev.mx3.nomessages.core.protocol.Offer$Companion.decode(Pairing.kt:356)
    at dev.mx3.nomessages.core.protocol.PairingEngine.readOffer(Pairing.kt:269)
    at dev.mx3.nomessages.core.protocol.PairingEngine.respond(Pairing.kt:141)
    at dev.mx3.nomessages.storage.DecoyFactory.pair(DecoyFactory.kt:47)
    at dev.mx3.nomessages.storage.DecoyFactory.create(DecoyFactory.kt:15)
    at dev.mx3.nomessages.storage.AndroidVaultStorage.seedDecoy(AndroidVaultStorage.kt:380)
    at dev.mx3.nomessages.storage.AndroidVaultStorage.initialize(AndroidVaultStorage.kt:57)
    at dev.mx3.nomessages.core.vault.VaultManager.create(VaultManager.kt:52)
```

Log: `docs/development/build-logs/android-test-emulator-5556-20260917T211600Z.log`.

**Causa raiz (defeito 1).** `DecoyFactory.pair` fazia:

```kotlin
try {
    local = PairingEngine(crypto, localIdentity, localOnion, localDoorbell)
    remote = PairingEngine(crypto, syntheticPeer, syntheticOnion(), remoteDoorbell)
} finally { crypto.wipe(localDoorbell); crypto.wipe(remoteDoorbell) }
```

`PairingEngine` guarda `doorbellKey:ByteArray` **por referência** e só a lê depois, dentro de
`createOffer()` → `newOffer()` → `doorbellKey.copyOf()`. O `wipe` no `finally` zerava o array
**antes** disso, então a oferta publicava 32 bytes zerados, e `Offer.decode` — que corretamente
recusa uma identidade de doorbell toda zero (`Pairing.kt:356`) — estourava.

Consequência real, muito além dos testes: **`VaultManager.create()` falhava sempre**, ou seja,
*nenhum cofre novo podia ser criado no app*. A regressão não aparecia em nenhum gate de host porque
`DecoyFactory` só é exercitada pela suíte instrumentada.

Correção aplicada em `app/src/main/kotlin/dev/mx3/nomessages/storage/DecoyFactory.kt`: as chaves são
passadas direto ao construtor e **não** são zeradas. Não se perde nada — é uma chave **pública**
Ed25519 cuja metade privada `syntheticOnionKey()` já descartou, e ela é gravada na linha do contato
logo abaixo como `doorbellOnion`.

### 21:22:35 — Suíte instrumentada em A: **1 falha restante de 19**

```
android.database.sqlite.SQLiteException: duplicate column name: doorbell_onion (code 1):
  , while compiling: ALTER TABLE contacts ADD COLUMN doorbell_onion TEXT NOT NULL DEFAULT ''
    at dev.mx3.nomessages.storage.AndroidVaultStorage.migrateStep(AndroidVaultStorage.kt:278)
    at ...AndroidVaultStorageTest.vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened:293
```

Log: `docs/development/build-logs/android-test-emulator-5556-20260917T212235Z.log`.

**Causa raiz (defeito 2) — defeito de fixture, não de produção.** O helper
`downgradeToSchemaVersionOne` reconstrói `messages` sem a coluna `forwarded` (desfazendo a v2) e
rebobina `PRAGMA user_version=1`, mas **não** desfazia as duas colunas que o passo v3 acrescenta em
`contacts`. O banco resultante não era um banco v1 de verdade, e o replay v1→v2→v3 batia em
`duplicate column name` no passo v3. Um banco v1 real no campo não tem essas colunas, então o
caminho de produção está correto; o que estava errado era a fixture.

Correção em `app/src/androidTest/kotlin/.../AndroidVaultStorageTest.kt`: a fixture agora reconstrói
também `contacts` na forma v1 (cópia literal do DDL de `createSchemaV1`), e a reserva liberada subiu
de 4 para 8 páginas por causa da segunda reconstrução de tabela.

### 21:25:19 — Suíte instrumentada em A: **OK (19 tests)** ✅

```
Time: 60.29
OK (19 tests)
=== [emulator-5556] PASS
```

Log: `docs/development/build-logs/android-test-emulator-5556-20260917T212519Z.log`.

Os 19 casos que rodaram, conferidos **no conteúdo do log**, não só na linha de resumo:

```
 1 createProducesFixedEncryptedDatabasesAndBalancedMedia
 2 decodesPosterAndDurationFromSyntheticClipOrSkipsCleanly
 3 decoyAttachmentsUseNormalHistoryAndDecryptForTheViewer
 4 doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration   <- novo (T4.16)
 5 emptyGroupIsVisibleAndItsPreviewUpdatesAfterFirstMessage
 6 emptyPcmNeverThrowsAndYieldsNoOutput
 7 encodesToPlayableAacOrReturnsNullWithoutThrowing
 8 exportGuardRequiresTheSelectedDatabaseToBeClosed
 9 forwardedFlagRoundTripsAfterTheSchemaVersionTwoMigration
10 identicalOutboxPayloadsShareOneEncryptedDatabaseBlob
11 rendersGeneratedPagesFromAnonymousMemory
12 repeatedWritesConsumeReserveWithoutChangingObservableFileSize
13 selectedVaultsAreIsolatedAndCloseClearsActive
14 sqlCipherRejectsAnUnrelatedKey
15 syntheticDecoyContactsHavePersistedSignalSessions
16 transactionRollsBackBothReserveAndDataOnFailure
17 typedRecordsAndOpaqueStateRoundTripAsCopies
18 vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened      <- v1 -> v3 in-place
19 vaultFromANewerAppVersionIsRejectedWithAnExplicitMessage
```

> O plano desta tarefa previa 18 casos. São **19**: o caso novo
> `doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration` entrou com T4.16.

### 21:26:39 — APK atual instalado em B

`adb -s emulator-5560 install -r -t app/build/outputs/apk/debug/app-debug.apk` → `Success`.
SHA-256 do APK instalado nesse momento: `18033278b258dcfe029000c2f758845b88eee1a9d94c77f37c75e92718516cac`.

### 21:26:49 — B zerado e cofre novo pela UI real

- `adb -s emulator-5560 shell pm clear dev.mx3.nomessages.debug` → `Success`.
- **`pm clear` restaura o IME de voz do Google** (`com.google.android.tts/...VoiceInputMethodService`).
  Desabilitado outra vez em seguida. Armadilha nova, não registrada em run4.
- Formulário de Setup preenchido pela UI real, campo a campo, com um `uiautomator dump` **entre cada
  campo**: as mensagens de validação ("Strong", "Avoid common words", "Passwords do not match")
  **empurram os campos de baixo para baixo** conforme aparecem, então coordenadas colhidas de uma
  única leitura ficam obsoletas no meio do preenchimento. A primeira tentativa despejou as três
  senhas restantes todas dentro do campo "Confirm main password" por causa disso.
- Alias `TesteB`, senha principal `EncaminharTesteGamma03`, senha de pânico `PanicoTesteGamma04`.
- `21:29:50` — "Create vault" pressionado.
- `21:30:21` — ainda "Working…".
- `~21:31:03` — cofre criado (diálogo de permissão de notificações na tela). **Criação do cofre
  ≈ 60–70 s** (calibração do Argon2 + semeadura dos decoys).
- `21:31:40` — home, **"Tor connected"**, 0 conversas. Evidência: `03-B-home-fresh-vault.png`.

Esta é a prova em aparelho de que a correção do defeito 1 realmente destrava a criação de cofre.

### 21:33:07 — A destrancado

Senha real do usuário (não registrada aqui). `21:33:17` "Connecting to Tor…" → `21:33:30`
**"Tor connected"**. **Bootstrap do Tor em A: ≤ 23 s** após o destrancamento.

**Verificação de contatos de A (pedida explicitamente):** A mostrava **"No paired contacts"** e
"No chats yet" — **zero contatos, como esperado**. Nada foi feito em cima disso; nenhuma ação
destrutiva em A em momento nenhum. Vale registrar que a tabela "Emulators left running" de run4
ainda descreve A com 1 contato (`TesteB`); esse estado já não valia no início desta sessão (o cofre
de A foi evidentemente recriado por alguma sessão entre run4 e hoje). A discrepância é da
documentação antiga, não uma anomalia encontrada agora.

### 21:34:49 — Tentativa 1 de pareamento: **FALHOU** (latência do harness + lacuna real do motor)

- `21:34:49` A em "Show my QR": QR de oferta na tela, **"Expires in 01:54"**.
  Evidência: `04-A-offer-qr-format2.png`.
- `21:35:26` B em "Scan QR".
- `21:35:37.4 → 21:35:38.9` relay A→B:

```
[emulator-pair] emulator-5556: QR exibido tem 405 bytes
[emulator-pair] integridade confirmada (sha256 52f8e057a3dce729138508cde6e1bbb099f54686667e9afb049a86b7f9449515)
[emulator-pair] emulator-5560: INJECT_QR entregue (540 chars de base64)
```

- `21:35:45.4` — **primeira leitura de B já mostrava "Key bundle received and verified."** e o SAS
  `976187`. Ou seja, **a busca do bundle por Tor em B levou no máximo 6,5 s** (limite superior: a
  resolução da amostragem perdeu o instante exato).
- Em seguida gastei ~2,5 min amostrando B. **Erro meu**, e ele expôs o problema abaixo.
- `21:38:34.4` relay B→A (resposta de **448 bytes**).
- `21:38:44.8` — A: **"Could not complete"**. Evidência: `06-A-could-not-complete.png`.

**Análise (defeito 3, de projeto, não corrigido nesta sessão).** `PairingEngine.processResponse`
(`Pairing.kt:146-158`) faz:

```kotlin
val local = offer ?: error("No active offer")
fresh(local.offer)                                   // exige idade < OFFER_TTL_SECONDS (120)
val remote = readOffer(responseQr)                   // readOffer TAMBÉM chama fresh(remote)
require(remote.reply.contentEquals(crypto.hash(local.offer.encode())))
```

Duas coisas se combinam:

1. A oferta de A expirou em `21:36:49` e a **regeneração automática funcionou**: `createOffer()`
   colocou a oferta antiga em `superseded` e publicou uma nova. Mas `processResponse` só olha
   `offer`, **nunca `superseded`** — e a resposta de B responde ao *digest da oferta antiga*. O
   `require` falha com "Response does not match offer". `bundleFor()` **consulta** `superseded`
   (linhas 199-208), então a intenção documentada ("quem escaneou o QR nos últimos segundos ainda
   completa a busca contra a oferta substituída retida") cobre só a busca do bundle, não o QR de
   resposta que vem logo depois no mesmo fluxo.
2. Mesmo que (1) fosse tratado, `readOffer(responseQr)` aplica `fresh()` **na resposta de B**, que
   tem o mesmo teto de 120 s. A resposta de B nasceu em ~`21:35:39` e chegou a A com 175 s.

Ou seja: o relógio que o usuário vê após o staging é de **300 s** (`PENDING_TTL_SECONDS`, e B
mostrava "Expires in 04:02"), mas a janela real para *entregar o QR de resposta* continua sendo de
**120 s** a partir da criação de cada oferta. As duas coisas discordam, e a discordância se
manifesta exatamente como um "Could not complete" genérico.

Nenhuma linha do app foi escrita em logcat durante a falha (por projeto, o app não registra nada de
Tor/pareamento para não vazar metadados), então a análise acima vem do código e da linha do tempo,
não de um log — o que também é um achado: **não há como diagnosticar essa falha em campo.**

### 21:42:36 — Verificação de que a regeneração automática realmente regenera

Dump do QR exibido em A, decodificado no host:

```
payload chars: 405   prefixo: nomessages:2:CAI
f1 varint=2                      (version = 2)
f2 len=32 e3fc4e3d0f074f23...    (ed25519 de A)
f3 len=62 tcfuhdg3kcpiokrjspczvjdi...onion
f4 varint=1789681370  created=2026-09-17 21:42:50Z age=15s
f5 len=16 bb7492b96aabe20a274e08d9d3c3fe75   (nonce)
f6 len=32 6683e91241866dc0...    (bundleHash)
f7 len=32 b5030b4ba9fedb24...    (doorbellKey — NÃO zerada, correção 1 confirmada em campo)
f8 len=32 3f74caaa6f15be0d...    (doorbellToken)
```

A oferta tinha **15 s de idade** — o QR na tela estava vivo — enquanto o rótulo dizia
**"Expires in expired"**. Isso isolou o defeito 3 do defeito 4 abaixo.

### 21:44:31 — Tentativa 2 de pareamento: **SUCESSO COMPLETO** ✅

| Hora (UTC) | Evento | Medida |
|---|---|---|
| 21:44:28 | A → "Show my QR", **"Expires in 01:55"** | oferta nova |
| 21:44:31.2 → 21:44:32.5 | relay A→B, **oferta 405 B**, sha256 `4b099dfd…d7c74`, integridade OK | 1,3 s |
| **21:44:42.3** | B: **"Key bundle received and verified."**, SAS `355284` | **busca do bundle por Tor em B: ≤ 9,8 s** |
| 21:44:56.6 → 21:44:58.2 | relay B→A, **resposta 448 B**, sha256 `8c3d8dc4…557c`, integridade OK | 1,6 s |
| **21:45:06.7** | A: **"Key bundle received and verified."**, SAS `355284` | **busca do bundle por Tor em A: ≤ 8,5 s** |
| 21:46:19.9 | A: alias `TesteB` + "The codes match" → A passa a exibir seu QR de confirmação | |
| 21:46:49.5 | B: alias `TesteA` + "The codes match" → "Confirmation recorded. Now scan each other's confirmation QR." | |
| 21:47:14.7 | relay B→A da **confirmação, 317 B** → A: **"Contact verified and saved."** | |
| 21:47:35.1 | injeção da confirmação de A em B (capturada antes de A trocar de tela) → B: **"Contact verified and saved."** | |

**Cerimônia completa: 3 min 04 s**, quase inteiramente latência da minha automação (os três relays
somam ~4,4 s e as duas buscas por Tor somam ≤ 18,3 s).

**SAS idêntico nos dois aparelhos: `355284`.**

Impressões digitais cruzadas, lidas da UI dos dois lados:

- A vê `TesteB`, fp `7f8b c646 d95b 448c 736a 0ec2 9be7 a8e7 40da ca0a f204 4b64 552d 354f 6bc2 a4cd`
- B vê `TesteA`, fp `e3fc 4e3d 0f07 4f23 0fde c20f e0e8 a111 2f33 fcad 03d5 ebd7 8149 3d51 67f4 5c2f`

`e3fc4e3d…` é exatamente o `f2` (chave Ed25519 de A) decodificado da oferta de A às 21:42:36 —
conferência independente, não visual.

Sobre a ordem: a lição de run4 ("os dois lados confirmam o SAS **antes** de qualquer QR de
confirmação trafegar") continua valendo e foi seguida à risca. Além disso, **é preciso capturar o QR
de confirmação de A com `dump` antes de A tocar em "Scan QR"** — ao entrar no scanner, o QR
mostrado deixa de existir para o hook `DUMP_QR`, e a confirmação de A seria perdida.

Evidências: `08-A-sas-screen.png`, `09-A-contact-saved.png`, `10-B-contact-saved.png`.

### 21:48:49 / 21:49:20 — Mensagens nos dois sentidos

| Hora | Direção | Texto | Resultado |
|---|---|---|---|
| 21:48:49.6 | A → B | `a2b-t416-01` | **✓✓ no remetente em ~2 s**; recebida e exibida em B às 21:48 |
| 21:49:20.1 | B → A | `b2a-t416-01` | **✓✓ no remetente em ~3 s**; recebida e exibida em A |

Evidências: `11-A-chat-both-messages.png`, `12-B-chat-both-messages.png`.

### 21:50–21:57 — Defeito 4 isolado e corrigido: contagem regressiva travada

**Sintoma.** Depois da primeira expiração, o rótulo ficava permanentemente
`"Expires in expired"`, mesmo com o QR sendo regenerado a cada 120 s (provado às 21:42:36: oferta
com 15 s de idade sob um rótulo dizendo "expirado"). Evidência: `05-A-offer-expired-label.png`.

**Causa raiz.** `ExpiryLabel` em `app/src/main/kotlin/dev/mx3/nomessages/ui/PairingScreen.kt`:

```kotlin
val remaining by produceState(initialValue = remainingMillis(expiresAt), expiresAt) {
    while (value > 0) { delay(1_000); value = remainingMillis(expiresAt) }
}
```

`produceState` usa `initialValue` **uma única vez**, na primeira composição; quando a chave
(`expiresAt`) muda, ele **relança o bloco mas preserva `value`**. Na primeira expiração `value`
chega a 0; na relança seguinte, `while (value > 0)` nem entra, e o rótulo fica em 0 para sempre. O
motor e o `PairingLifecycle` estavam certos — `PairingLifecycleTest` cobre a lógica pura e passa; o
defeito está no acoplamento Compose, que nenhum teste de host alcança.

**Correção:** re-semear `value = remainingMillis(expiresAt)` no início do bloco, tornando o produtor
idempotente em relação a quantas vezes já rodou.

**Verificação ao vivo, em A**, com o build corrigido reinstalado (`install -r -t`, cofre preservado):

```
21:59:57Z  Expires in 00:04
22:00:00Z  Expires in 00:02
22:00:03Z  Expires in 01:59   + "The QR expired and a new one was generated. Ask the other person to scan it."
22:00:16Z  Expires in 01:45
22:00:47Z  Expires in 01:15
```

A contagem **reinicia em 01:59** na regeneração, em vez de travar. Evidência:
`15-A-countdown-after-regeneration-fixed.png`.

### 22:02–22:03 — Reverificação de mensagens sobre o build final

Depois de `install -r -t` do build corrigido nos dois emuladores: **os dois cofres e o contato
pareado sobreviveram**, e uma nova rodada de mensagens passou nos dois sentidos.

| Hora | Direção | Texto | Resultado |
|---|---|---|---|
| 22:02:58.8 | A → B | `a2b-t416-02` | ✓ → **✓✓** em ~10 s; exibida em B |
| 22:03:25.2 | B → A | `b2a-t416-02` | **✓✓** em ~5 s; exibida em A |

Evidências: `16-A-chat-final.png`, `17-B-chat-final.png`.

---

## O QR realmente ficou menos denso — medido, não olhado

Medição feita em Python/PIL sobre `04-A-offer-qr-format2.png` (captura real da tela de A):

```
QR bbox 201,663 .. 878,1340   ->  678 x 678 px na tela
run externo do finder pattern = 69 px  ->  módulo = 9,86 px
módulos por lado = 678 / 9,86  =  68,8  ->  69
```

**69 módulos por lado = QR versão 13** (17 + 4×13 = 69), exatamente a versão que
`ProtocolTest.formatTwoQrPayloadsStayWithinTheVersionFourteenBudgetAtLevelL` previu no host para uma
oferta de 405 B.

| | formato 1 (T3.3 run4) | formato 2 (hoje) |
|---|---|---|
| oferta | 2708 B, versão 40, **177 módulos/lado** | **405 B, versão 13, 69 módulos/lado** |
| resposta | 2750 B | **448 B** |
| confirmação | 314 B | **317 B** |
| módulo renderizado em 678 px | ~3,83 px | **~9,86 px** |

O módulo renderizado é **2,6× maior**. É essa razão — não o tamanho em bytes — que decide se uma
câmera real consegue ler, e é por isso que o defeito original (Note10+ não lia o QR nem ocupando um
monitor inteiro) tem chance real de estar resolvido. **Chance, não prova**: a prova exige a câmera
do Note10+, que não foi tocada nesta sessão.

---

## Medidas de Tor (o número não medido que T4.16 pediu)

| Medida | Valor | Observação |
|---|---|---|
| Bootstrap do Tor em B (cofre recém-criado) | **≤ 40 s** | de "Create vault" a "Tor connected" na home |
| Bootstrap do Tor em A (destrancamento) | **≤ 23 s** | 21:33:07 → 21:33:30 |
| **Busca do bundle por Tor, B ← A (tentativa 1)** | **≤ 6,5 s** | limite superior |
| **Busca do bundle por Tor, B ← A (tentativa 2)** | **≤ 9,8 s** | limite superior |
| **Busca do bundle por Tor, A ← B (tentativa 2)** | **≤ 8,5 s** | limite superior |
| Orçamento por tentativa | 60 s | **nunca chegou perto** |
| Orçamento total da troca | 300 s | **usado ≈ 3 %** |

Todos os valores são **limites superiores**: o estado já estava em `READY` na primeira amostragem
pós-injeção, e cada `uiautomator dump` custa 2–3 s. Nenhuma tentativa de busca precisou de repetição
— **1 tentativa bastou nas três medições**, e o estado `WAITING_FOR_TOR` nunca foi observado em
nenhuma amostra. Os 300 s de `PENDING_TTL_SECONDS` são folgadíssimos para este ambiente.

Ressalva honesta: dois emuladores no mesmo host constroem circuitos para serviços onion recém-
publicados em condições muito melhores do que dois celulares em redes móveis diferentes. Estes
números são um piso otimista, não o caso típico de campo.

---

## Arquivos de evidência (caminhos absolutos)

Todos em `E:\Vibe Coding\NoMessages\docs\development\build-logs\pairing-v2-20260917\`:

| Arquivo | O que prova |
|---|---|
| `00-A-initial.xml`, `00-B-initial.xml` | estado inicial: os dois cofres trancados |
| `01-B-after-clear.png` | B após `pm clear`, tela "Create your vault" |
| `02-B-setup-filled.png` | o deslocamento de layout que corrompeu o primeiro preenchimento |
| `03-B-home-fresh-vault.png` | cofre novo de B criado, "Tor connected", 0 conversas |
| `04-A-offer-qr-format2.png` | **o QR de oferta formato 2**, 69 módulos/lado, "Expires in 01:53" |
| `05-A-offer-expired-label.png` | o defeito "Expires in expired" antes da correção |
| `06-A-could-not-complete.png` | falha da tentativa 1 |
| `07-A-logcat-attempt1.txt` | logcat completo de A na janela da tentativa 1 (app não registra nada) |
| `08-A-sas-screen.png` | SAS `355284` + fingerprint em A (e o IME de voz cobrindo a tela) |
| `09-A-contact-saved.png`, `10-B-contact-saved.png` | contato salvo dos dois lados, fingerprints cruzadas |
| `11-A-chat-both-messages.png`, `12-B-chat-both-messages.png` | mensagens nos dois sentidos |
| `13-A-logcat-pairing-window.txt`, `14-B-logcat-pairing-window.txt` | logcat filtrado da janela de pareamento |
| `15-A-countdown-after-regeneration-fixed.png` | contagem regressiva reiniciada após regeneração |
| `16-A-chat-final.png`, `17-B-chat-final.png` | estado final das conversas no build corrigido |
| `18-A-logcat-app-errors.txt`, `19-B-logcat-app-errors.txt` | nenhum `FATAL`/`AndroidRuntime` do app |
| `session-log.md` | este arquivo |

Logs da suíte instrumentada, em `E:\Vibe Coding\NoMessages\docs\development\build-logs\`:

- `android-test-emulator-5556-20260917T211600Z.log` — **15 falhas de 19** (defeito 1)
- `android-test-emulator-5556-20260917T212235Z.log` — **1 falha de 19** (defeito 2)
- `android-test-emulator-5556-20260917T212519Z.log` — **OK (19 tests)** ✅

---

## Estado em que os emuladores foram deixados

| Papel | Serial | Estado |
|---|---|---|
| A | `emulator-5556` | app em primeiro plano, **conversa com `TesteB` aberta**, cofre **destrancado** com a senha real original (preservada o tempo todo por `install -r -t`; **nunca** `pm clear`), "Tor connected", **1 contato pareado (`TesteB`)**, `allow_capture=1`, `allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build final desta sessão instalado |
| B | `emulator-5560` | app em primeiro plano, **conversa com `TesteA` aberta**, cofre **destrancado**, criado nesta sessão pela UI real (alias `TesteB`, senha `EncaminharTesteGamma03`, pânico `PanicoTesteGamma04`), "Tor connected", **1 contato pareado (`TesteA`)**, `allow_capture=1`, `allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build final instalado |

O aparelho físico `RX8MA0GD9ZY` **não recebeu nenhum comando** desta sessão.

Para devolver B ao uso manual com teclado:
`adb -s emulator-5560 shell ime enable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`.

`debug.nomessages.allow_qr_inject` não sobrevive a reboot; `scripts/emulator-pair.sh` o reaplica
sozinho.

---

## Armadilhas de automação novas desta sessão (somam-se às de run4)

1. **`wsl -d Ubuntu -- bash /mnt/c/...` pela ferramenta Bash quebra**: o MSYS traduz o caminho para
   `C:/Program Files/Git/mnt/c/...`. Use PowerShell, ou `MSYS_NO_PATHCONV=1`.
2. **`pm clear` reabilita o IME de voz do Google.** Redesabilite depois de todo `pm clear`.
3. **O IME de voz voltou sozinho em A também**, depois de reinstalações — e reproduziu exatamente a
   armadilha #1 de run4: `uiautomator dump` reporta o layout de tela cheia enquanto o IME cobre a
   metade de baixo, e um toque em "The codes match" simplesmente não acontece. Confira
   `dumpsys input_method | grep mInputShown` antes de culpar o app; desabilite o IME, **nunca**
   pressione BACK (armadilha #2 de run4 destruiu uma sessão de pareamento assim).
4. **`adb pull` de `/sdcard/...` falha silenciosamente pelo Git Bash mesmo com `MSYS_NO_PATHCONV=1`
   em algumas invocações**, e pior: deixa no lugar um arquivo **antigo**, que parece um dump válido
   de uma tela que já não existe. Perdi tempo lendo um dump da sessão de run4. `adb exec-out cat` é
   confiável; e sempre valide que o `uiautomator dump` imprimiu "dumped to" antes de ler o arquivo.
5. **O formulário de Setup se reorganiza enquanto é preenchido.** Re-leia as coordenadas entre cada
   campo.
6. **Capture o QR de confirmação antes de trocar de tela** (ver 21:47 acima).
