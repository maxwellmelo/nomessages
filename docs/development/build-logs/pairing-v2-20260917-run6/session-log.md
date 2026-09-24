# T4.16 — run6: validação ao vivo da correção de sobreposição de ofertas (2026-09-17)

Registro cronológico da sessão que exercitou, em dois emuladores reais em execução, a correção do
defeito 3 relatado em run5: `processResponse` não aceitava uma resposta ligada a uma oferta que a
tela já havia substituído. Horários em **UTC**, lidos do host com `date -u`. Nenhum comando foi
emitido sem `-s <serial>` explícito; o aparelho físico `RX8MA0GD9ZY` (Galaxy Note10+) **não recebeu
nenhum comando**.

| Papel | Serial | Cofre |
|---|---|---|
| A | `emulator-5556` | cofre "real" do usuário, **preservado**; nunca `pm clear` |
| B | `emulator-5560` | cofre criado em run5; **não precisou ser apagado nesta sessão** (ver "Decisão sobre o passo 4") |

---

## Resumo executivo

**A correção funciona.** Em duas execuções independentes, A aceitou uma resposta de B ligada a uma
oferta que a tela de A já havia rotacionado **duas vezes** — 250 s e 253 s entre os dois relays, com
duas rotações comprovadas por hash e não por suposição. O SAS bateu nos dois aparelhos nas duas
vezes. O pareamento foi concluído de ponta a ponta, com contato salvo dos dois lados e uma mensagem
entregue em cada direção.

**Mas há um achado novo, estrutural, que o cenário de 250 s expõe e que nenhum teste de host mostra:**
com duas rotações, o pareamento **não consegue ser concluído**, porque o prazo de 300 s da troca é
ancorado na criação da oferta **mais antiga**. Duas rotações consomem 240 dos 300 s e sobram ~35–60 s
para a fase humana (comparar o SAS em voz alta, digitar um apelido, trocar os dois QRs de
confirmação). A aceitação foi corrigida; o orçamento não acompanha.

Por isso esta sessão rodou **três cenários**, não um:

| | Intervalo entre relays | Rotações | Aceitação da oferta antiga | Pareamento concluído |
|---|---|---|---|---|
| **6a** | **250 s** | **2** | ✅ SAS `929007` nos dois | ❌ prazo esgotado (restavam 4 s) |
| **6b** | **135 s** | **1** | ✅ SAS `971309` nos dois | ✅ **completo**, contato salvo, mensagens nos dois sentidos |
| **6c** | **253 s** | **2** | ✅ SAS `615458` nos dois (com captura de tela) | ❌ prazo esgotado (restavam 35 s) |

### Sobre o ajuste de 150 s para 250 s — e por que os dois números eram necessários

O ajuste recebido estava certo no diagnóstico: **150 s força apenas uma rotação**, e uma rotação já
era parcialmente tratada pela correção anterior de 2 posições; só a partir de ~240 s existem três
ofertas simultaneamente válidas, que é o caso que a correção nova (`emitted`, podada pelo prazo
próprio de cada entrada, limitada a `MAX_LIVE_OFFERS = 3`) fechou. Os 250 s provam exatamente isso, e
foi por isso que 6a e 6c usaram 250 s.

O que o ajuste não previa é que **a 250 s o pareamento não pode terminar**, pelo motivo aritmético
acima. Então 250 s prova a *aceitação* mas nunca produziria a evidência de "pareamento concluído,
contato salvo, mensagem em cada direção" que o roteiro também pedia. Os 150 s do coordenador, por
sua vez, produzem essa evidência mas não exercitam a sobreposição tripla.

Os dois números respondem a metades diferentes da mesma pergunta, e nenhum sozinho fecha o roteiro.
Foram executados os dois: **6a/6c a 250 s para a aceitação, 6b a 135 s para a conclusão.** A tabela
acima é o resultado combinado.

---

## Passo 1 — Gate de build

```
wsl -d Ubuntu -- bash .../gradle-wsl.sh :core:test :app:testDebugUnitTest :app:lintDebug \
    :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL
```

| Gate | Resultado |
|---|---|
| `:core:test` | **100 testes, 0 falhas, 2 pulados** (eram 95 em run5 — os 5 novos casos da correção) |
| `:app:testDebugUnitTest` | **61 testes, 0 falhas, 1 pulado** |
| `:app:lintDebug` | **0 erros** (42 avisos, 6 hints) |
| APKs | ambos gerados |

SHA-256 do `app-debug.apk` instalado: `973bde2e1bc95c82dc7530befa47fda6baf9571043af53506c10075b83daaa80`.

## Passo 2 — Instalação nos dois emuladores

`22:47:36Z` — `install -r -t` em A e em B, **`Success`** nos dois. Os dois cofres e o contato pareado
de run5 sobreviveram.

## Passo 3 — Suíte instrumentada em A

`22:47:51Z → 22:49:02Z`:

```
=== [emulator-5556] sdk_gphone64_x86_64 Android 15 abi=x86_64 ===
Time: 66.919
OK (19 tests)
=== [emulator-5556] PASS
```

Log: `docs/development/build-logs/android-test-emulator-5556-20260917T224751Z.log`.

**Correção de expectativa, verificada no log e não presumida:** o roteiro dizia "3 more tests were
added since run5's 19". São **19**, iguais a run5 — contei os nomes distintos no corpo do log. Os
5 casos novos entraram em **`:core:test` (95 → 100)**, que é JVM, não instrumentado. O APK de
androidTest inclusive ficou `UP-TO-DATE` no build (mesmo carimbo de run5), o que é consistente:
nenhuma fonte de androidTest mudou.

## Decisão sobre o passo 4 — não foi preciso apagar B

O roteiro autorizava `pm clear` em B, mas pedia antes que eu lesse o código para achar a opção menos
destrutiva. Li, e **o re-pareamento de um contato já existente é suportado por desenho**:

- `NoMessagesController.readPairing` (linha ~636) faz
  `require(db().getContact(current.peerId) != null || db().listContacts().size < MessagingPolicy.maxContacts)` —
  o `!= null` existe justamente para isentar um contato já pareado do limite;
- `ChatDatabase.putContact` é `INSERT … ON CONFLICT(id_pub) DO UPDATE SET …`;
- `ChatDatabase.putPairEvidence` é `INSERT … ON CONFLICT(first_id,second_id) DO UPDATE SET …`;
- a tela de contato oferece "Verify QR again".

Ou seja, re-parear sobrescreve a linha do contato e a evidência de pareamento em vez de duplicar.
**Nenhum `pm clear` foi executado nesta sessão, em nenhum dos dois aparelhos.** Confirmado na prática
ao final: cada aparelho continua com **exatamente um** contato, não dois.

---

## 6a — 250 s entre relays, duas rotações (22:51:50Z → 22:57:14Z)

| Hora | Evento | Dado medido |
|---|---|---|
| 22:51:50 | **T0** — A exibe a oferta #1 | `sha256=09c58ed2…` `nonce=c9d1079d…` `created=22:51:50Z` |
| 22:52:33.2 → 22:52:34.5 | relay A→B, **oferta 405 B**, integridade `sha256 09c58ed2…` conferida | 1,3 s |
| **22:52:48.0** | B: "Key bundle received and verified.", SAS **`929007`** | **busca por Tor em B ≤ 13,5 s** |
| **22:53:52** | **ROTAÇÃO 1** — contagem `00:01` → `01:58` | oferta #2: `sha256=f39dd271…` `nonce=bf14dfd2…` `created=22:53:50Z` |
| **22:55:51** | **ROTAÇÃO 2** — contagem `00:02` → `01:59` | oferta #3: `sha256=92f5454658…` `nonce=b34018a4…` `created=22:55:51Z` |
| 22:56:09.6 | relay B→A — **desperdiçado**, ver "erro de harness" abaixo | — |
| **22:56:43.2** | relay B→A com A no scanner, **resposta 448 B** | **250 s após o relay A→B** |
| **22:56:49.8** | **A ACEITOU** — `Verification code 929007`, `Expires in 00:04`, botão "The codes match" presente | ✅ |
| 22:56:50 | prazo da troca (22:51:50 + 300 s) | — |
| 22:57:09–22:57:14 | os dois aparelhos: **"Time ran out to finish pairing. Start again whenever you're ready."** | ❌ conclusão |

**Este é o resultado central.** A resposta que A aceitou foi calculada por B sobre a oferta **#1**
(`09c58ed2…`), enquanto a tela de A já estava exibindo a oferta **#3** (`92f5454658…`) — duas
rotações e três ofertas simultaneamente vivas. Antes da correção isto era exatamente o
`Could not complete` de run5.

As três ofertas são objetivamente distintas: três `sha256` diferentes, três `nonce` diferentes, três
`created` espaçados de 120 s, decodificados do QR que o aparelho estava de fato exibindo (via o hook
`DUMP_QR`). As rotações não foram inferidas da passagem do tempo.

**Erro de harness, meu, não do app:** o primeiro relay B→A às 22:56:09.6 foi entregue
(`INJECT_QR entregue`) mas **descartado**, porque A ainda estava na tela "Mostrar meu QR" e o
`scanSink` do hook só é registrado pelo composable `QrScanner`. Custou 34 s do orçamento. O relay
seguinte, com A no scanner, funcionou. Lição para o próximo roteiro: **o destino de um relay tem de
estar na tela de leitura antes do relay**, e a entrega bem-sucedida do broadcast não significa que o
app consumiu o payload.

Captura `04-A-6a-timeout-after-deadline.png` foi tirada 14 s tarde demais e pegou a tela de prazo
esgotado, não a do SAS. A evidência de 6a é, portanto, o dump de UI transcrito acima — e foi
justamente por isso que 6c repetiu o cenário só para obter a captura.

## 6b — 135 s entre relays, uma rotação: **pareamento completo** (22:57:43Z → 23:02:37Z)

| Hora | Evento | Dado medido |
|---|---|---|
| 22:57:43 | **T0'** — oferta #1 | `sha256=005ed21a…` |
| 22:57:56.6 → 22:57:57.9 | relay A→B, 405 B | 1,3 s |
| **22:58:12.1** | B: bundle verificado, SAS **`971309`** | **busca por Tor em B ≤ 14,2 s** |
| **22:59:46** | **ROTAÇÃO 1** — `00:00` → `01:57` | oferta #2: `sha256=24624a42…` `created=22:59:44Z` |
| **23:00:12.0** | relay B→A (A no scanner), resposta 448 B | **135 s após o relay A→B** |
| **23:00:13.6** | **A ACEITOU** — SAS **`971309`**, bundle de A já buscado, `Expires in 02:27` | ✅ |
| 23:00:35.0 | A confirma (apelido `TesteB`) | |
| 23:00:37.2 | B confirma (apelido `TesteA`) → "Confirmation recorded. Now scan each other's confirmation QR." | |
| 23:00:56.7 | relay B→A da **confirmação, 317 B** → A: **"Contact verified and saved."** | |
| 23:01:13.2 | injeção da confirmação de A em B → B: **"Contact verified and saved."** | |
| 23:02:00.2 | A → B `a2b-run6-01` | **✓✓**, recebida e exibida em B |
| 23:02:25.5 | B → A `b2a-run6-01` | **✓✓**, recebida e exibida em A |

Cerimônia inteira (relay A→B até contato salvo nos dois): **3 min 16 s**.

Contatos após a conclusão — **um em cada aparelho, não dois**, confirmando o upsert:

- A vê `TesteB`, fp `7f8b c646 d95b 448c 736a 0ec2 9be7 a8e7 40da ca0a f204 4b64 552d 354f 6bc2 a4cd`
- B vê `TesteA`, fp `e3fc 4e3d 0f07 4f23 0fde c20f e0e8 a111 2f33 fcad 03d5 ebd7 8149 3d51 67f4 5c2f`

`e3fc4e3d…` é o campo `ed` decodificado das ofertas de A nesta sessão — conferência independente.

## 6c — repetição de 6a para obter a captura de tela (23:04:06Z → 23:09:11Z)

| Hora | Evento | Dado medido |
|---|---|---|
| 23:04:06 | **T0''** — oferta #1 | `sha256=a3d6c56e…` `nonce=4f6adb24…` |
| 23:04:16.3 | relay A→B, 405 B | |
| **23:04:20.5** | B: bundle verificado, SAS **`615458`** | **busca por Tor em B ≤ 4,2 s** (melhor medida da sessão) |
| **23:06:10** | **ROTAÇÃO 1** — `00:02` → `01:59` | oferta #2: `sha256=d9d8e324…` `nonce=38bcca7e…` |
| **23:08:11** | **ROTAÇÃO 2** — `00:01` → `01:58` | oferta #3: `sha256=9c1b9e9c…` `nonce=6d97a3c4…` |
| **23:08:29.6** | relay B→A (A já no scanner), resposta 448 B | **253 s após o relay A→B; 263 s após T0''** |
| **23:08:30** | **A ACEITOU** — captura `13-A-6c-accepted-offer-after-two-rotations.png` | ✅ |
| 23:08:31.4 | dump: `Expires in 00:35`, SAS **`615458`**, "Key bundle received and verified." | |
| 23:08:54–23:09:11 | tentativa de concluir em 35 s: falhou | ❌ |
| 23:09:06 | prazo da troca (23:04:06 + 300 s) | |

A captura `13-…png` é a melhor evidência única desta sessão. Ela mostra, na mesma tela:

1. **`Verification code 615458`** — A aceitou a resposta ligada à oferta #1, duas rotações depois;
2. **o estado `FETCHING` renderizado**: *"Fetching the other device's key bundle over Tor… This can
   take up to 40 seconds. It keeps trying automatically — no action needed."* — run5 registrou este
   estado como **nunca observado**; aqui ele está capturado;
3. **"The codes match" corretamente desabilitado** enquanto o bundle do par não chegou, que é a regra
   de `PairingLifecycle.canConfirmSas` funcionando na tela.

**Artefato de um quadro, registrado por honestidade:** o rótulo nessa captura diz `Expires in 01:37`,
enquanto o dump 1 s depois diz `Expires in 00:35`. 01:37 corresponde ao prazo de 120 s da oferta #3
*não montada* (23:08:06 + 120 = 23:10:06), ou seja, o `screencap` pegou um quadro em que o painel de
baixo já tinha recomposto com o SAS mas o rótulo ainda não havia reiniciado com o novo `expiresAt`.
É um artefato transitório de recomposição, de um único quadro; o valor correto (00:35) aparece na
leitura seguinte. Não é o defeito 4 de run5 (aquele travava permanentemente) e não foi tratado como
defeito — fica registrado para que ninguém leia a captura e conclua que o prazo estava errado.

**Por que a conclusão falhou em 6c** (dois motivos, ambos consequência dos 35 s restantes): o toque
de confirmação em B não chegou a registrar — evidência objetiva: o relay seguinte leu **600 chars de
base64 (448 B)**, que é o tamanho de uma *resposta*, não de uma *confirmação* (317 B / 424 chars),
logo B ainda exibia a resposta. Reinjetar essa resposta em A produziu `Could not complete`, que é a
**proteção de replay funcionando** (`consumed` já continha o hash). Em seguida B bateu no prazo.

## Sanidade pós-execução

`23:09:56.6` — A → B `a2b-run6-02`: **✓✓**, recebida e exibida em B às 23:10:08. Ou seja, a sessão
Signal e o contato sobreviveram a **três** trocas de pareamento nesta sessão, das quais **duas
expiraram** no meio. Uma troca que esgota o prazo não chama `finish()` e portanto não escreve nada no
cofre — confirmado na prática, não só pelo código.

---

## Medidas de Tor nesta sessão

| Medida | Valor |
|---|---|
| Busca do bundle por Tor, B ← A (6a) | **≤ 13,5 s** |
| Busca do bundle por Tor, B ← A (6b) | **≤ 14,2 s** |
| Busca do bundle por Tor, B ← A (6c) | **≤ 4,2 s** |
| Busca do bundle por Tor, A ← B (6b) | **≤ 1,6 s** (já concluída no primeiro dump após o relay) |
| Busca do bundle por Tor, A ← B (6c) | **≤ 2 s** (a captura pegou o `FETCHING`, o dump 1 s depois já dizia verificado) |
| Tentativas necessárias | **1**, em todas as cinco medições |
| Orçamento por tentativa / total | 60 s / 300 s |

Todos são limites superiores. As buscas de A←B são consistentemente mais rápidas porque o circuito
para o onion de B já está quente de quando B buscou de A segundos antes.

---

## Gate estrutural novo descoberto nesta sessão

`PairingEngine.stage` calcula `expiresAt = minOf(first.created, second.created) + PENDING_TTL_SECONDS`,
isto é, o prazo da troca é ancorado na oferta **mais antiga** das duas. Consequência aritmética, com
`OFFER_TTL_SECONDS = 120` e `PENDING_TTL_SECONDS = 300`:

| Rotações antes da resposta chegar | Idade da oferta antiga | Sobra para a fase humana |
|---|---|---|
| 0 | 0–120 s | 180–300 s |
| 1 | 120–240 s | 60–180 s |
| **2** | **240–300 s** | **0–60 s** |
| 3 | ≥ 300 s | recusada (fora do prazo) |

Ou seja: a correção torna a oferta antiga **aceitável**, mas o orçamento restante encolhe na mesma
proporção. Com duas rotações sobra menos de 1 minuto para duas pessoas compararem seis dígitos em voz
alta, digitarem apelidos e trocarem dois QRs de confirmação. Medido: 6a chegou com **4 s** de sobra,
6c com **35 s**; nenhum dos dois concluiu. O cenário de uma rotação (6b) chegou com **147 s** e
concluiu com folga.

Isto não invalida a correção — sem ela nada disso chegava sequer à tela de SAS. É um limite do
**orçamento**, não da aceitação, e continua em aberto.

---

## Arquivos de evidência (caminhos absolutos)

Todos em `E:\Vibe Coding\NoMessages\docs\development\build-logs\pairing-v2-20260917-run6\`:

| Arquivo | O que prova |
|---|---|
| `01-A-offer1-t0.png` | 6a: oferta #1 na tela em T0, rótulo **"New QR in …"** (texto novo) e a legenda "Anyone who already scanned the previous QR can still finish pairing." |
| `02-B-response-and-sas.png` | 6a: B com seu QR de resposta e o SAS após a busca por Tor |
| `03-A-offer2-after-rotation1.png` | 6a: A já exibindo a oferta #2, depois da rotação 1 |
| `04-A-6a-timeout-after-deadline.png` | 6a: a tela de prazo esgotado (captura tardia — ver texto) |
| `05-A-deadline-expired-message.png` | 6a: mensagem "Time ran out to finish pairing…" |
| `06-A-chat-run6-final.png`, `07-B-chat-run6-final.png` | **6b: conversa com as mensagens nos dois sentidos** |
| `08-A-logcat-run6.txt`, `09-B-logcat-run6.txt` | logcat filtrado da janela de pareamento |
| `10-A-logcat-fatals.txt`, `11-B-logcat-fatals.txt` | **nenhum `FATAL`/crash do app** (as linhas `AndroidRuntime` são do processo uiautomator, uid 2000) |
| `12-A-6c-offer2-mid-wait.png` | 6c: A a meio da espera, já na oferta #2 |
| **`13-A-6c-accepted-offer-after-two-rotations.png`** | **a evidência central**: SAS `615458` aceito com a oferta duas rotações atrás, mais o estado `FETCHING` e o botão de confirmação corretamente desabilitado |
| `14-A-final-state.png`, `15-B-final-state.png` | estado final dos dois aparelhos |
| `session-log.md` | este arquivo |

Suíte instrumentada: `E:\Vibe Coding\NoMessages\docs\development\build-logs\android-test-emulator-5556-20260917T224751Z.log`.

---

## Estado em que os emuladores foram deixados

| Papel | Serial | Estado |
|---|---|---|
| A | `emulator-5556` | app em primeiro plano, **conversa com `TesteB` aberta**, cofre **destrancado** com a senha real original (**nunca** `pm clear`, só `install -r -t`), "Tor connected", **1 contato pareado (`TesteB`)**, `allow_capture=1`, `allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build run6 instalado |
| B | `emulator-5560` | app em primeiro plano, **conversa com `TesteA` aberta**, cofre **destrancado** (o mesmo de run5, `EncaminharTesteGamma03`; **não foi apagado nesta sessão**), "Tor connected", **1 contato pareado (`TesteA`)**, `allow_capture=1`, `allow_qr_inject=1`, todo IME desabilitado, `svc power stayon true`, build run6 instalado |

O aparelho físico `RX8MA0GD9ZY` **não recebeu nenhum comando desta sessão**.

## Armadilhas de automação novas (somam-se às de run4 e run5)

1. **O destino de um relay precisa estar na tela de leitura ANTES do relay.** `INJECT_QR entregue` só
   diz que o broadcast chegou ao receiver; se o composable `QrScanner` não estiver montado, o
   `scanSink` é nulo e o payload é descartado **em silêncio**. Custou 34 s em 6a.
2. **Capture a tela imediatamente após o relay, antes de qualquer `uiautomator dump`.** Cada dump
   custa 2–4 s, e num cenário com 35 s de folga isso é a diferença entre capturar o SAS e capturar a
   tela de prazo esgotado (foi o que aconteceu com `04-…png`).
3. **Campos de texto retêm o conteúdo entre tentativas de pareamento.** Em 6b o campo de apelido de B
   já continha `TesteA` e o `input text` acabou produzindo `TesteATesteA`. Limpe com
   `KEYCODE_MOVE_END` + `KEYCODE_DEL` antes de digitar.
4. **O tamanho do payload identifica a tela de origem sem precisar de dump:** 405 B = oferta,
   448 B = resposta, 317 B = confirmação. Em 6c foi assim que ficou provado que o toque de
   confirmação em B não havia registrado.
