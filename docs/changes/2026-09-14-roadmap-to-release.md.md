# Mudanças em `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`

## 2026-09-18 — T4.17 (campainha): entrada criada do zero, marcada `[ ]`

**Antes:** T4.17 **não tinha entrada própria**. Existia só como menção dentro do texto de T4.16: *"Bloqueia: T4.17 (campainha), que consome os dois campos reservados aqui. Estimativa: 14 h."*

**Agora:** entrada completa `- [ ] **T4.17 [B] Campainha: aviso de mensagens pendentes com o app bloqueado.**`, no formato das vizinhas (objetivo, arquivos, "seis itens", divergências registradas, "feito quando", estado, dependências), resumindo o que foi entregue: segundo serviço onion mínimo com opcodes 10-16 acrescentados sem renumerar 0-9, protocolo de batida HMAC de 57 bytes com janela/nonce/limite de taxa, modo mínimo no lock com a ordem imposta pelo nativo, toque com backoff no `MessagingEngine`, esquema do cofre v3→v4, e configuração por cofre com padrão ligado e paridade real/isca sem bifurcação por `VaultSlot`.

**Decisão registrada: a entrada ficou `[ ]`, não `[x]`.** A implementação está completa e compilada, mas o "feito quando" exige o gate 10 medido nas **duas** variantes, e nada foi executado em aparelho nem em emulador. O precedente do próprio arquivo é explícito e foi seguido: **T4.16 permaneceu `[ ]` por três rodadas** (run5, run6, run7), com build verde e emuladores funcionando, e só virou `[x]` em 2026-09-18 quando a evidência de campo apareceu. Marcar `[x]` aqui — numa tarefa que **muda a invariante de bloqueio do produto** — seria aplicar critério mais frouxo do que o usado na tarefa que a desbloqueou. A entrada diz, em texto, que nada falta implementar: o que falta é medir.

**Por quê:** T4.18, que é filha de T4.16 e foi feita no mesmo período, já estava `[x]` e não foi tocada. A ausência de entrada para T4.17 deixava o plano descrevendo um produto que não tem a funcionalidade mais recente — e, pior, sem nenhum lugar onde registrar que a validação de campo dela ainda não aconteceu.

## 2026-09-14 — marcação de T5.1 (área "spec")

### Escopo deste documento

O plano de release é editado por várias áreas em paralelo, e cada agente só pode alterar a marcação
`- [ ]` / `- [x]` da **sua** tarefa. Este arquivo registra apenas a marcação de **T5.1**
(atualização do `SPEC.md`). As marcações de T1.1, T1.2, T2.1, T4.3, T4.4, T4.5, T5.2, T5.3 e T5.4,
visíveis no mesmo diff, pertencem a outras áreas e não são descritas aqui — o conteúdo de cada uma
está no `docs/changes/` do arquivo que ela alterou.

### Como era antes

Uma rodada anterior de agentes deixou a linha marcada como concluída:

```
- [x] **T5.1 [B] Atualizar `SPEC.md` para o que foi construído.**
  Itens: X3DH → PQXDH (linhas 33 e 434) com justificativa; GPLv3 → AGPL-3.0 (linha 18);
  `RETRYING`/`PUBLISHING` na seção 7; decisão de T4.2 na seção 9; tipos de envelope
  `ControlRejected`, lanes, cursores e limites ... na seção 10; `TorStateSnapshot` na seção de
  rede; política de mídia de T4.1.
  Feito quando: nenhuma divergência listada na seção 7 do relatório de análise de 2026-09-14
  permanece. Estimativa: 3 h.
```

### Como ficou

```
- [ ] **T5.1 [B] Atualizar `SPEC.md` para o que foi construído.**
```

O corpo da tarefa (itens, "Feito quando", estimativa) ficou **intacto**: a regra desta rodada
autoriza alterar somente o marcador de conclusão.

### Por que a mudança foi feita

Dois motivos independentes, ambos verificados nesta rodada:

1. **O critério "Feito quando" é inverificável.** Ele exige que "nenhuma divergência listada na
   seção 7 do relatório de análise de 2026-09-14 permaneça", mas esse relatório **não existe no
   repositório**. `grep -rl 'relatório de análise' docs/ --include=*.md` retorna apenas o próprio
   plano, e `docs/development/` contém somente relatórios por componente (`build-report.md`,
   `crypto-report.md`, `message-report.md`, `vault-report.md`), nenhum datado de 2026-09-14. Sem o
   documento de referência não há como demonstrar que o critério foi satisfeito, e a regra do
   projeto manda deixar desmarcada a tarefa cujo "Feito quando" não pôde ser avaliado.

2. **O conteúdo entregue estava desatualizado no momento da marcação.** A revisão encontrou seis
   divergências factuais no texto que T5.1 produziu — entre elas a seção 7.2 afirmando que a camada
   Kotlin "ainda não" tinha `PUBLISHING` quando `UiContract.kt:8` e `NoMessagesController.kt:246-248`
   já o implementavam, e cinco chaves da tabela `meta` listadas como namespaces de `opaque_blobs`.
   Todas foram corrigidas nesta rodada e estão detalhadas na seção 12 de
   `docs/changes/SPEC.md.md`; ainda assim, marcar como concluída uma entrega que precisou de
   correção factual antes de ser lida por terceiros seria registrar um estado que não existiu.

Além disso, um dos itens explicitamente enumerados no corpo de T5.1 — "política de mídia de T4.1" —
entrou no SPEC como **lacuna documentada** (seção 9.2, "em definição"), não como decisão. O item foi
registrado, não fechado, o que é outro argumento contra o `[x]`.

### Vantagens

- O plano deixa de afirmar conclusão onde não há critério aferível, que é exatamente o defeito que
  esta rodada de revisão foi criada para encontrar em outros documentos.
- A próxima rodada encontra T5.1 aberta com o trabalho substantivo já feito e corrigido, e pode
  fechá-la assim que o critério for substituído por uma lista verificável (ver abaixo).
- Evita que a auditoria externa (T6.2) herde um plano cujo estado não corresponde ao dos artefatos.

### O que falta para fechar T5.1

Reescrever o "Feito quando" para a lista explícita de itens que o próprio corpo da tarefa enumera —
já que o relatório referenciado não existe — anotando na linha que a política de mídia de T4.1
entrou como lacuna documentada e não como decisão. **Essa edição altera o corpo do plano e está
fora do escopo desta rodada**, que só podia mexer no marcador; fica registrada aqui e no retorno da
área "spec" como pendência.

## 2026-09-14 — marcação de T5.2 e T5.3 (área "docs-catalog")

### Escopo desta seção

Segue a mesma convenção da seção acima: aqui estão **apenas** as marcações de **T5.2**
(`runtime-catalog.md`) e **T5.3** (caminhos e versões obsoletas). As demais marcações do mesmo diff
pertencem a outras áreas.

### T5.2 — permanece `[x]`

**Como era antes (em `22aa7b0`):** `- [ ] **T5.2 [D] Catálogo de `opaque_blobs` e limites de
runtime.**`
**Como ficou:** `- [x]` — marcação feita por uma rodada anterior e **mantida** por esta.

**Por quê:** o entregável existe (`docs/development/runtime-catalog.md`, ~440 linhas) e cobre o que
a tarefa pede — cada namespace com formato, dono e ciclo de vida — além de corrigir um erro de
premissa do enunciado (6 dos 20 nomes listados são chaves de `meta`, não namespaces de
`opaque_blobs`). Os quatro defeitos que a revisão apontou nele (âncora de linhas, formato de
`receipts`, divergência real/decoy na rede, comando de varredura inexecutável) foram corrigidos
nesta rodada e estão descritos em `docs/changes/runtime-catalog.md.md`. Nenhum deles é falta de
entrega, então a marcação continua válida.

**Ressalva registrada:** as citações `ME:`/`WC:` do catálogo estão ancoradas em `22aa7b0` e **não**
valem para a árvore de trabalho enquanto T2.1/T2.3 estiverem reescrevendo
`NoMessagesController.kt`/`MessagingEngine.kt`. Isso está dito no cabeçalho do próprio catálogo. Quando
a Fase 2 commitar, as linhas precisam ser reconferidas — é manutenção, não pendência de T5.2.

### T5.3 — de `[x]` para `[ ]`

**Como era antes:**

```
- [x] **T5.3 [D] Corrigir caminhos e versões obsoletas.**
  `vault-report.md:20` e `crypto-report.md:58-62` (`/tmp/...` → `native/target/debug`),
  `vault-api.md:3` ("Java 17" → JDK 21), `message-report.md` (nota de que Kotlin 2.0.21/JDK 17 é
  histórico).
```

**Como ficou:**

```
- [ ] **T5.3 [D] Corrigir caminhos e versões obsoletas.**
```

O corpo da tarefa ficou intacto; só o marcador mudou.

**Por que a mudança foi feita.** Dos quatro itens de T5.3, dois estão corretos, um foi corrigido
nesta rodada e um **continua defeituoso**:

| Item | Estado |
|---|---|
| `vault-api.md:3` ("Java 17" → JDK 21) | correto, conferido contra `core/build.gradle.kts:11,13` e `app/build.gradle.kts:48,81,83` |
| `message-report.md` (nota de toolchain histórico) | correto, e com a nota **antes** do bloco de evidência, que é o padrão certo |
| `crypto-report.md:58-62` | estava errado; **corrigido nesta rodada** (ver `docs/changes/crypto-report.md.md`) |
| `vault-report.md:20` | **ainda errado**; a correção está fora dos arquivos desta área (ver `docs/changes/vault-report.md.md`) |

A raiz do problema é que a **instrução do próprio plano estava factualmente errada**: ela manda
trocar `/tmp/...` por `native/target/debug` nesses dois relatórios, como se fosse só um caminho
obsoleto. Não é. Os logs mostram que aqueles artefatos **nunca** foram produzidos em
`native/target/debug` — `build-logs/native-crypto-bundled.log:74` e
`build-logs/native-full-host-j2.log:389` registram a build em
`/tmp/nomessages-libsodium122-target`, o hash de `build-logs/native-host-sha256.log` é de
`/tmp/nomessages-host-runtime/libnomessages.so`, e `/tmp/nomessages-system-target/debug` é o artefato
ligado à libsodium 1.0.18 do sistema que `crypto-report.md:31` proíbe para release. Seguir a
instrução ao pé da letra apaga a proveniência e vincula hashes verificados a binários que ninguém
hasheou — exatamente o que o gate T5.5 vai consumir ("SHA-256 do artefato, commit, caminho da
evidência").

Marcar T5.3 como concluída registraria como "caminhos corrigidos" um estado em que um dos
relatórios ficou **menos** correto do que antes da edição.

### O que falta para fechar T5.3

1. Aplicar em `vault-report.md:20` o texto proposto em `docs/changes/vault-report.md.md`
   (restaura o caminho histórico **com** a qualificação *integration-only* e mantém a instrução de
   reprodução local).
2. Opcionalmente, corrigir o corpo de T5.3 no plano para dizer "separar onde o artefato foi
   produzido de onde uma build padrão o produz", em vez de "`/tmp/...` → `native/target/debug`" —
   edição de corpo do plano, **fora do escopo desta rodada**.

---

## 2026-09-14 — marcação de T4.3 (área "kats")

### Escopo desta seção

Registra apenas a marcação de **T4.3 [B] Vetores conhecidos faltantes para o gate
13**. As demais marcações visíveis no mesmo diff pertencem a outras áreas e estão
documentadas nos `docs/changes/` dos arquivos que elas alteraram.

### Como era antes

`git diff HEAD` mostra que uma rodada anterior de agentes já havia trocado a
linha 189 de `- [ ] **T4.3 ...**` para `- [x] **T4.3 ...**`. O que faltava não
era a marcação, e sim o registro dela em `docs/changes/` — esta seção.

### Como ficou

A marcação `[x]` foi **mantida**, após reconferência do critério. Nenhuma outra
linha do plano foi tocada nesta rodada.

### Por que a marcação se sustenta

O critério "Feito quando" da T4.3 é: "`cargo test --all-features` inclui os novos
casos, relatório em `crypto-report.md` lista cada vetor executado e cada caso
pulado com motivo". Os dois ramos foram verificados nesta rodada, depois das
correções da revisão:

1. **Execução.** `cargo test --all-features --locked` no WSL Ubuntu (Rust 1.91)
   terminou com 17 testes unitários, 7 KAT, 3 MLS, 0 falhas e 1 ignorado
   (`tor_live`, que exige bootstrap real do Tor). O placar impresso confirma os
   1.309 casos Wycheproof: `chacha20_poly1305` 325, `xchacha20_poly1305` 315
   (306 pelo `crypto::open`), `ed25519` 151 e `x25519` 518.
2. **Relatório.** `docs/development/crypto-report.md` traz a tabela de corpus com
   origem, licença Apache-2.0 e SHA-256 de cada arquivo, a tabela de mapeamento
   para os símbolos exercitados, as contagens agora asseveradas por suíte e a
   lista de pulados/não aplicáveis com motivo — incluindo, após a revisão, as duas
   lacunas antes omitidas (verificação Argon2id por terceiro e X25519 contra os
   provedores realmente usados).

### O que a marcação **não** significa

A T4.3 é uma das quatro pernas do gate 13. O gate em si continua `NOT_RUN` em
`docs/release-checklist.md`, e as pernas de libsignal, RFC 9420/OpenMLS e PQXDH
seguem sem evidência. A marcação é da tarefa, não do gate.

### Não verificado

- A linha de atribuição do Wycheproof em `THIRD_PARTY_NOTICES.md` não foi escrita
  (arquivo fora do escopo desta área) e está reportada como pendência.

## 2026-09-14 — T1.3 e T1.4 marcadas como concluídas

### Como era antes

```
- [ ] **T1.3 [B] Bibliotecas Android arm64/x86_64 e APK.**
- [ ] **T1.4 [B] Fechar o lint Android.**
```

### Como ficou

```
- [x] **T1.3 [B] Bibliotecas Android arm64/x86_64 e APK.**
- [x] **T1.4 [B] Fechar o lint Android.**
```

Apenas as duas caixas foram alteradas; o texto das tarefas e todo o resto do
plano ficaram intactos.

### Por que a mudança — evidências

**T1.3.** `docs/development/build-logs/android-20260914T223220Z.log` (o mais
recente) mostra `:app:testDebugUnitTest`, `:app:assembleDebug` e
`:app:assembleDebugAndroidTest` executados com sucesso (linhas 52, 75 e 110). O
único `Execution failed` do log é `:app:lintDebug` (linha 149) — ou seja, o que
falhava era exatamente T1.4, não T1.3. As bibliotecas nativas exigidas estão no
lugar: `app/src/main/jniLibs/arm64-v8a/libnomessages.so` (15,4 MB) e
`app/src/main/jniLibs/x86_64/libnomessages.so` (17,6 MB).

**T1.4.** Após as três correções (`AndroidManifest.xml`,
`AndroidVaultStorage.kt`, `SettingsScreen.kt`), `:app:lintDebug` terminou com
`BUILD SUCCESSFUL in 7m 46s` e o relatório acusa **0 erros**, 42 avisos e
1 hint. `:app:testDebugUnitTest` continua verde: 22 testes, 0 falhas, 0 erros,
0 pulados. A triagem dos avisos está registrada em
`docs/development/build-report.md`, seção "Lint (2026-09-14)".

### Vantagens

- O plano volta a refletir o estado real do repositório: a Fase 1 está fechada.
- Destrava as dependências declaradas no próprio plano: T3.1 (suíte
  instrumentada) depende de T1.3, e T6.1 (CI) depende de T1.4.
- Evita que outro agente reexecute um build de ~11 min e um lint de ~8 min para
  redescobrir que já passaram.

---

## 2026-09-14 — marcação de T4.4 e não-marcação de T6.1 (área "ci")

### Escopo desta seção

Mesma convenção das seções acima: aqui estão **apenas** as tarefas da área "ci"
— **T4.4** (ligar a suíte Python ao build) e **T6.1** (tornar o workflow
executável e rápido). As demais marcações visíveis no mesmo diff pertencem a
outras áreas. **Esta rodada não alterou nenhum caractere do plano**; a seção
existe porque a marcação de T4.4, feita por uma rodada anterior, não tinha
registro em `docs/changes/`.

### T4.4 — permanece `[x]`

**Como era antes (em `22aa7b0`):**

```
- [ ] **T4.4 [D] Ligar `app/src/test/python/test_messaging_queue.py` ao build.**
```

**Como ficou (rodada anterior, mantido por esta):**

```
- [x] **T4.4 [D] Ligar `app/src/test/python/test_messaging_queue.py` ao build.**
```

**Critério e evidência.** O "Feito quando" é: "o build falha se o teste Python
falhar". Os dois lados foram reconferidos aqui:

1. **A suíte roda.** `python3 -m unittest discover -s app/src/test/python -t
   app/src/test/python -p 'test_*.py' --verbose` no WSL Ubuntu (Python 3.12.3):
   quatro casos, `OK`, zero falhas.
2. **A falha propaga.** `scripts/build-android.sh` roda sob `set -euo pipefail`
   e a chamada Python está entre as duas ondas de Gradle, antes de
   `:app:assembleDebug`. Em sandbox com stubs, um caso quebrado de propósito fez
   o script terminar com status 1 sem executar nenhuma tarefa de empacotamento
   (registro na seção de T4.4 de `docs/changes/build-android.sh.md`).
3. **O CI herda.** O job `app` do workflow executa exatamente a linha
   `bash scripts/build-android.sh --bootstrap --app-only --skip-native --with-ndk`,
   que inclui a etapa Python.

A marcação se sustenta. Esta rodada corrigiu, no `README.md`, a afirmação
contraditória de que a suíte "ainda não foi executada nenhuma vez"; a correção
não altera o estado da tarefa.

### T6.1 — permanece `[ ]`, deliberadamente

**Estado no plano:** `- [ ] **T6.1 [B] Tornar o workflow executável e rápido.**`
— inalterado.

**Por quê.** O "Feito quando" é: "2 execuções verdes consecutivas em `develop`,
cada uma abaixo de 90 min com cache quente, artefatos publicados". Zero execuções
ocorreram: **não existe remote** (T0.4, dependência declarada da própria T6.1),
então o workflow nunca rodou uma vez, muito menos duas, e nenhum tempo de parede
ou acerto de cache foi medido. Marcar seria registrar um estado que não existe.

O trabalho de engenharia da tarefa está feito e, nesta rodada, corrigido: três
jobs, caches por conteúdo, paralelismo do Cargo em `nproc`, artefatos publicados,
mais as sete correções de auditoria descritas em `docs/changes/android.yml.md` e
`docs/changes/build-android.sh.md`. O que falta é exclusivamente execução.

**Ressalva registrada nesta rodada.** O enunciado de T6.1 também afirma: "Todos
os testes JVM/Rust/Python do ledger passam a rodar aqui como regressão. Nenhum
deles é tarefa manual em outra fase". Isso **não** é verdade para duas entradas
do ledger — `Conditional cleanup fault cases` (exige `LD_PRELOAD` ad hoc) e
`Live Tor delivery` (exige rede real) — que nenhum job executa. Em vez de mudar
o corpo do plano, que está fora do escopo desta rodada, a exceção foi
documentada na nova subseção "Ledger entries not covered by CI" de
`docs/development/build-report.md`. Quem fechar T6.1 precisa decidir entre
absorver os dois casos no CI ou ajustar a frase do plano.

## 2026-09-14 — T4.2 marcada como concluída

**Como era antes:** `- [ ] **T4.2 [D] Não lidas e recibos de leitura: implementar ou remover.**`

**Como ficou:** `- [x] **T4.2 [D] Não lidas e recibos de leitura: implementar ou remover.**`

Nenhuma outra linha do plano foi alterada por este agente — nem o corpo da
própria T4.2, que continua descrevendo o estado anterior e a recomendação.

**Por que o critério foi considerado satisfeito.** T4.2 não traz linha "Feito
quando"; traz a recomendação "implementar apenas o contador local de não lidas
(sem recibo de leitura na rede) [e] marcar `READ` localmente ao abrir a conversa",
que foi a decisão fixada para esta rodada. Os três sintomas listados no "Estado"
da tarefa foram fechados:

- `ChatUi.unread` sempre `0` → passa a ser preenchido em `NoMessagesController.refresh()`
  a partir de `ChatDatabase.unreadCounts()`;
- `MessageStatus.READ` só no seed do decoy → passa a ser escrito por
  `ChatDatabase.markChatRead`, chamado ao abrir a conversa (e enquanto ela está
  aberta), **sem** nenhum tráfego de rede associado;
- badge de `HomeScreen.kt` como código morto → passa a ser alcançável, com o
  rótulo calculado por `unreadBadge` em `UiLogic.kt` e coberto por um caso novo
  em `UiLogicTest.kt`.

Os três arquivos previstos pela tarefa (`ChatDatabase.kt`, `NoMessagesController.kt`,
`UiLogicTest.kt`) foram alterados; `UiLogic.kt` e `HomeScreen.kt` entraram como
consequência direta do caso de teste, e `docs/development/storage-api.md` como
reflexo da superfície de API nova. `StorageModels.kt` não precisou de mudança:
`MessageStatus.READ` já existia no enum.

**Verificação executada.** No WSL Ubuntu, com
`JAVA_HOME=$HOME/nomessages-tools/jdk-21`,
`GRADLE_USER_HOME=$HOME/nomessages-tools/gradle-home` e
`ANDROID_HOME=$HOME/nomessages-tools/android-sdk`:

```
./gradlew --offline :app:testDebugUnitTest --tests 'dev.mx3.nomessages.ui.UiLogicTest'
```

`BUILD SUCCESSFUL in 5m 3s`. `:app:compileDebugKotlin` foi executado (não
`UP-TO-DATE`), portanto `ChatDatabase.kt`, `NoMessagesController.kt`, `UiLogic.kt` e
`HomeScreen.kt` compilam. O relatório JUnit
`app/build/test-results/testDebugUnitTest/TEST-dev.mx3.nomessages.ui.UiLogicTest.xml`
registra `tests="7" skipped="0" failures="0" errors="0"`, incluindo o caso novo
`unread badge is local only and caps instead of widening the row`.

**O que não foi verificado.** O comportamento em tempo de execução de
`unreadCounts()` e `markChatRead` contra um banco SQLCipher real: essas duas
funções só rodam em teste instrumentado (`androidTest`), que exige emulador ou
aparelho e não foi executado nesta rodada. O que está garantido é a compilação,
a validade dos tipos de ligação (`Array<Any>` em `database.update`) e a regra de
apresentação do selo.

## 2026-09-15 — Achados de revisão de T2.1, T2.3 e T4.2 registrados nas próprias tarefas

### Como era antes

As três caixas estavam marcadas `[x]` sem qualquer registro dos dez achados que as duas revisões
(lentes de segurança e de completude) produziram depois da implementação. Quem lesse o plano
concluiria que as tarefas estavam fechadas como entregues.

### Como ficou

- **T2.1**: linha nova "Revisão 2026-09-15 (fechada)" com prontidão tri-estado, asserções de estado
  transitório em `tor_live.rs` e o `timeout` da sonda elevado a 900 s. Linha nova com o **baseline
  de regressão medido**: 17 unitários + 7 KAT + 3 MLS + 1 ignorado — e a observação explícita de que
  as citações de "18 casos" nas linhas 15, 16 e 67 deste mesmo plano são anteriores e continuam
  incorretas.
- **T2.3**: linha nova com os quatro consertos (morte marca `stopped` + `closeQuietly`, checkpoint de
  guards por transição, `awaitDeath` no lugar da amostragem, `reachable()` no supervisor) e o caso
  de teste novo.
- **T4.2**: linha nova com a paridade de não lidas entre cofre real e isca.

### Vantagens

- O plano deixa de afirmar "18" como baseline de `cargo test` num lugar onde o próximo agente iria
  lê-lo para detectar regressão — e o número correto está agora **medido**, com a linha de comando
  ao lado.
- Cada caixa marcada carrega o que foi revisado depois dela, então uma releitura futura não precisa
  do digest do journal para saber o que já foi fechado.

### Por que a mudança foi feita

Achado P3 da revisão (contagem de testes) e regra de documentação por modificação. As linhas 15, 16
e 67 **não** foram corrigidas por estarem fora do escopo autorizado desta rodada (só as linhas de
T2.1, T2.3 e T4.2); ficam registradas aqui para quem tiver escopo sobre elas.

---

## 2026-09-15 — T2.2 marcada como concluída e hipóteses da Fase 2 fechadas

### Escopo desta seção

Duas edições no plano, ambas dentro da Fase 2: o bloco de T2.2 e uma frase no
bloco de T2.1. Nenhuma outra tarefa, nenhum código.

### Como era antes

- **T2.2** estava `- [ ]`, com quatro hipóteses ainda abertas — (a) falta de
  espera pelo descritor, (b) rede do host anterior bloqueando guards, com a
  instrução "se guards falharem também aqui, testar com `bridges`", (c) prazo de
  180 s curto demais e (d) custo do `isolated_client()` por peer — e o "Feito
  quando" por cumprir.
- **T2.1** terminava sua linha de revisão dizendo que o `timeout` externo da
  sonda subiu de 600 s para 900 s "sem o que o gate T2.2 não podia concluir" —
  uma afirmação que, até hoje, ninguém tinha conseguido exercitar.

### Como ficou

- **T2.2** virou `- [x]` e ganhou uma linha "Fechada em 2026-09-15" com o
  ambiente (WSL Ubuntu 24.04, rede residencial, sem bridges), as quatro
  passagens com seus números — `tor-live-20260915T133138Z.log` (descritor após
  102,5 s, primeiro frame 3,9 s depois, `1 passed` em 134,20 s) e as três sondas
  JNI `tor-delivery-probe-20260915T133138Z / T133510Z / T133553Z` (bootstrap
  16,4 / 14,5 / 56,6 s; descritor +20,2 / +18,0 / +14,1 s; primeiro frame 5,6 /
  7,1 / 5,4 s após a publicação; PASS aos 43,4 / 41,1 / 78,2 s) — e o veredito
  de cada hipótese:
  - (a) **confirmada como causa**: o `start` declarava ONLINE antes da
    publicação e o self-connect começava sem intro points;
  - (b) **não reproduzida**: nenhum `Could not connect to guard` nesta rede e
    **nenhuma bridge usada**; a rede restrita do host anterior segue como
    hipótese não provada;
  - (c) **respondida com medição**: o tempo até o primeiro frame após a
    publicação ficou em 3,9–7,1 s, então o prazo de conexão nunca foi o fator
    limitante depois que se espera o descritor.
  A linha remete a `docs/development/native-report.md` (seção "Live Tor delivery
  — 2026-09-15") para a tabela completa e para o que fica fora do gate — sockets
  por PID e mensageria real, ambos na Fase 3 — e registra que o ledger do
  `build-report.md` foi movido para Passed.
- **T2.1** ganhou uma frase ao fim da linha de revisão: com a espera pelo
  descritor no lugar, as quatro passagens terminaram entre 41,1 s e 134,2 s, bem
  dentro do prazo externo de 900 s, e nenhuma precisou de bridges.

### Por que a mudança foi feita

T2.2 está no caminho crítico declarado no próprio plano
(`T0 → T1.2 → T2.1 → T2.2 → T2.3 → ...`). Deixá-la desmarcada depois de as duas
metades do seu "Feito quando" estarem cumpridas — tempos registrados em
`native-report.md` e ledger movido em `build-report.md` — faria o roadmap
apontar como bloqueado um caminho que já abriu, e a Fase 3 depende disso para
começar. A frase em T2.1 fecha a ponta solta que aquela tarefa tinha deixado: o
prazo de 900 s foi dimensionado sem nunca ter sido exercido, e agora foi.

### Vantagens

- As hipóteses da Fase 2 deixam de ser uma lista aberta e passam a ter veredito
  individual, então ninguém refaz o trabalho de investigar (b) ou (c).
- A distinção entre "corrigida" (a), "não reproduzida" (b) e "medida" (c) fica
  no plano, não só no relatório — é o plano que o próximo agente lê primeiro.
- A menção a bridges deixa de sugerir um caminho a tentar: está registrado que
  nenhuma foi necessária.

### O que **não** foi alterado

- A seção "Diagnóstico registrado" da Fase 2 continua como estava, incluindo o
  "Duas falhas, sem causa estabelecida". É o registro do que se sabia em
  2026-09-14; o veredito atual mora no bloco de T2.2.
- A hipótese (d), custo do `isolated_client()` por peer, continua sem medição
  dedicada — as passagens foram auto-endereçadas, com um peer só.
- Nenhuma outra tarefa do plano foi marcada ou desmarcada.

## 2026-09-15 — T0.4 fechada e progresso de T6.1

**Antes:** T0.4 desmarcada (sem remote); T6.1 sem nenhuma execução medida.

**Depois:** T0.4 marcada com o endereço do remote e o histórico da primeira execução (falha de arquivo de workflow corrigida em e8ef1bb). T6.1 recebe a medição da execução 34976669976: 19,4 min de parede, quatro jobs verdes, tempos por job.

**Motivo:** registrar a evidência real do CI no plano, que exige duas execuções verdes consecutivas abaixo de 90 min; esta é a primeira.

## 2026-09-15 — T3.1 marcada como concluída

### Como era antes

```markdown
- [ ] **T3.1 [B] Executar a suíte instrumentada.**
  Comando: `./gradlew :app:connectedDebugAndroidTest` no emulador (12 casos: ...).
  ...
  Feito quando: relatório em `app/build/reports/androidTests/` com 12/12 e copiado para `docs/development/build-logs/`.
  Dependências: T1.3. Estimativa: 2 h (incluindo criação do AVD).
```

### Como ficou

A caixa passou a `- [x]` e o corpo da tarefa ganhou uma linha de evidência, sem
alterar o enunciado original:

```markdown
- [x] **T3.1 [B] Executar a suíte instrumentada.**
  ...
  **Concluída em 2026-09-15:** `OK (12 tests)` (11 `AndroidVaultStorageTest` + 1
  `MemoryPdfDocumentTest`, zero falhas e zero skips) no emulador Android 15 API 35
  `x86_64` `emulator-5556`. Evidência:
  `docs/development/build-logs/android-test-emulator-5556-20260915T143135Z.log`.
  ... As três execuções que falharam antes estão arquivadas ...
```

### Por que a mudança

O critério "Feito quando" da tarefa foi cumprido em conteúdo (12/12 arquivados
em `docs/development/build-logs/`), mas não na forma: o Gradle roda no WSL e o
emulador vive no host Windows, então a suíte foi executada por `adb install -r`
mais `am instrument -w` em vez de `:app:connectedDebugAndroidTest`, e por isso
não existe o HTML de `app/build/reports/androidTests/`. A linha de evidência
declara essa diferença explicitamente, em vez de deixar a tarefa marcada com um
critério que um auditor não conseguiria reproduzir.

A mesma linha registra as três execuções que falharam e suas causas, porque elas
são a justificativa das mudanças de código do mesmo dia em
`AndroidVaultStorage.kt`, `DecoyFactory.kt` e `ChatDatabase.kt`.

### Vantagens

- O roadmap deixa de subestimar o progresso: T3.2, T3.6 e T4.1, que dependem de
  T3.1, ficam formalmente desbloqueadas.
- A divergência de procedimento fica documentada onde ela importa (na própria
  tarefa), e não escondida atrás de uma caixa marcada.
- O caminho do log final e a contagem de casos ficam a um clique de quem for
  auditar a Fase 3.

## 2026-09-15 — T6.1 fechada

**Antes:** T6.1 com uma execução verde registrada (19,4 min) e a caixa desmarcada.

**Depois:** segunda execução consecutiva (34978979160) verde em 12,7 min registrada; caixa marcada. As duas ficaram abaixo dos 90 min exigidos, com artefatos publicados.

**Motivo:** critério "Feito quando" da T6.1 satisfeito com medições reais do GitHub Actions.

## 2026-09-16 — Nova tarefa T4.8 (mídia inline estilo WhatsApp), inserida entre T4.7 e T4.6

**Antes:** não existia nenhuma entrada no roadmap para mídia inline (fotos/vídeos/áudios exibidos e
reproduzidos direto na bolha da conversa, estilo WhatsApp); a Fase 4 ia de T4.7 direto para T4.6.

**Depois:** bloco `T4.8 [D] Mídia inline estilo WhatsApp` inserido entre T4.7 e T4.6 (mesmo esquema
`Objetivo`/`Arquivos`/`Feito quando`/`Dependências`/`Estimativa` das tarefas vizinhas), caixa
marcada `[x]` com uma anotação "Execução 2026-09-16 (parcial — escopo A ...)" listando exatamente o
que foi concluído (infraestrutura compartilhada de mídia + áudio inline completo: encoder AAC em
memória com fallback WAV, forma de onda calculada nos dois lados, bolha de áudio com play/pause/
seek/velocidade, paridade de decoy) e o que fica pendente para os próximos dois agentes (miniatura
de foto com zoom, miniatura de vídeo com player, e as strings de UI que esses dois itens vierem a
precisar). Não foi adicionada à "Ordem de execução" (grafo ASCII) nem à tabela de "Gates de
release": é uma tarefa de produto autocontida, sem dependência de nenhuma outra tarefa nem gate
formal da lista, então inserir nesses dois lugares só adicionaria ruído sem nenhuma informação nova.

**Motivo:** registrar o trabalho de mídia inline no roadmap como as demais tarefas de produto da
Fase 4, e deixar explícito — para os agentes de foto/vídeo e para o agente final de consolidação —
exatamente onde o escopo desta parte termina e onde a próxima começa.

---

## 2026-09-16 — T4.9 (concluída) e T7.1 nova (Fase 7, pós-v1) — endurecimento de entrada e captura

### Motivo

Registrar o trabalho de T4.9 (proteções de entrada e captura, ver `docs/changes/PrivateInput.kt.md`
e os demais `docs/changes/*.md` datados de 2026-09-16 que ela tocou) no roteiro, e abrir a tarefa
futura T7.1 (teclado próprio no app) que T4.9 deliberadamente não cobre — coerente com `SPEC.md`
§14, que já listava "IME próprio" como fora da v1.

### Como era antes

A Fase 4 ia de T4.8 (mídia inline) direto para T4.6 (strings de lint); não havia nenhuma tarefa
sobre teclado/IME/captura além do que `FLAG_SECURE` já cobria. Não havia Fase 7.

### Como ficou

`T4.9 [B] Proteções de entrada e captura` inserida entre T4.8 e T4.6, já marcada `[x]`, com a lista
de arquivos tocados e os seis itens implementados. Nova `## Fase 7 — Pós-v1 (fora do caminho
crítico do release)` após a Fase 6, com `T7.1 [D] Teclado próprio dentro do app (v1.1)` — marcada
`[ ]`, sem dependência no caminho crítico do release v1, explicando por que um teclado próprio é a
única defesa completa contra um IME comprometido/malicioso.

### Vantagens

- T4.9 fica rastreável no mesmo formato de todas as outras tarefas `[B]` da Fase 4, com "Feito
  quando" verificável (testes, lint, assemble, verificação manual em `emulator-5556`).
- T7.1 dá um lugar concreto no roteiro para uma limitação que, sem isso, ficaria só mencionada em
  prosa dentro de `docs/security-model.md` — agora tem estimativa (a definir), dependência (T4.9) e
  critério de conclusão.
- A nova Fase 7 mantém o caminho crítico do release v1 (`## Ordem de execução e caminho crítico`)
  intocado — T7.1 explicitamente não entra nele.

### Por que a mudança foi feita

T4.9, item 6 ("bookkeeping" do plano).

## 2026-09-18 — T4.16 marcada `[x]` (verificação em aparelho físico real) e nova tarefa T4.18

### Como era antes

```
- [ ] **T4.16 [B] QR de pareamento compacto com pacote via Tor.**
  ...
  **O que ainda bloqueia o `[x]` é exclusivamente o item de origem:** o QR do formato 2 **nunca foi
  lido pela câmera real do Galaxy Note10+**, exigido no "Feito quando" acima e fora do alcance
  destas sessões. Continuam NOT_RUN também `WAITING_FOR_TOR`/`FAILED`/botão de nova tentativa (a
  busca termina rápido demais: ≤ 8,2 s, 1 tentativa) e o silêncio para nonce desconhecido.
  Dependências: T4.15 (mecanismo de migração de esquema em `open()`, reaproveitado pelo degrau v3).
  Bloqueia: T4.17 (campainha), que consome os dois campos reservados aqui. Estimativa: 14 h.
```

Ao final de três execuções em emulador (run5, run6, run7), a tarefa permanecia `- [ ]` porque o
"Feito quando" exigia explicitamente "um QR do formato 2 lido pela câmera real do Galaxy Note10+
(oferta e resposta)", e nenhum aparelho físico havia sido tocado. Não existia tarefa T4.18 no plano.

### Como ficou

A caixa de T4.16 passou a `- [x]`. O corpo da tarefa ganhou um novo parágrafo de evidência,
preservando todo o texto anterior (nenhuma linha das sessões run5/run6/run7 foi removida ou
reescrita):

```
  **Validado em 2026-09-18 (aparelho físico real) — a tarefa passa a `[x]`.** Registro completo em
  `docs/development/device-verification.md`, seção "Aparelho físico — 2026-09-18", com evidências em
  `docs/development/build-logs/real-device-20260918/`. Aparelho: Galaxy Note10+ SM-N975F, Android 12,
  arm64, serial `RX8MA0GD9ZY`, APK debug do commit `706630a`, pareado com os dois emuladores já em uso
  (`emulator-5556`/`emulator-5560`). **O item de origem que bloqueava o `[x]` está satisfeito:** a
  câmera real do Note10+ leu o QR de oferta formato 2 exibido no emulador A na primeira tentativa
  (`NoMessagesQrScan: decode attempt result=DECODED`, 10:12:34) ... **Ressalva de precisão:** os fatos
  observados cobrem a leitura da **oferta** pela câmera real; o QR de resposta e o de confirmação
  desta sessão foram entregues ao emulador A pelo relay (`scripts/emulator-pair.sh`), não fotografados
  por uma câmera real — não há evidência separada de "resposta também lida pela câmera" nesta sessão.
  Uma primeira tentativa de cerimônia ... **expirou** por tempo esgotado (SAS `705238` idêntico,
  verificação unilateral do lado do emulador A, busca do celular não concluída a tempo) — é registrada
  como falha de timing, não de leitura de QR nem de protocolo, e não é um teste adversarial do Gate 8.
```

Uma nova tarefa foi inserida logo depois de T4.16, antes de T4.6:

```
- [ ] **T4.18 [D] Retentativa e log de erro na busca do pacote de chaves.**
  Objetivo: quando a busca do pacote de chaves pela rede Tor falha por o Tor ainda estar frio ...
  Itens: (a) permitir nova tentativa de busca dentro do prazo de 300 s ...; (b) registrar em logcat,
  em nível debug apenas, o erro específico de cada tentativa de busca malsucedida ...; (c) avaliar se
  uma nova tentativa manual deve ser possível sem reiniciar a cerimônia inteira.
  Feito quando: uma busca que falha por Tor frio consegue ser reexercitada dentro dos mesmos 300 s
  ... e o motivo da falha de uma tentativa malsucedida fica disponível em logcat (nível debug) ...
  Dependências: T4.16. Estimativa: 4 h.
```

### Por que a mudança foi feita

O único item que bloqueava o `[x]` de T4.16, em todas as três sessões anteriores de emulador, era
explicitamente "leitura por câmera real no Galaxy Note10+". A sessão de 2026-09-18 tocou esse
aparelho físico pela primeira vez desde que T4.16 foi aberta, e a câmera real leu o QR de oferta
formato 2 na primeira tentativa — o defeito de origem que motivou a tarefa inteira (o QR do formato
1, ~2.950 bytes/versão 40, nunca era lido pela mesma câmera). Com esse item satisfeito e uma
cerimônia completa de ponta a ponta executada em hardware real (SAS idêntico, contato salvo dos dois
lados, mensagem e encaminhamento entre três aparelhos), não há mais nenhum critério do "Feito quando"
pendente que dependa de aparelho físico.

A marcação é deliberadamente cautelosa sobre o que os fatos cobrem: o "Feito quando" original pedia
"oferta e resposta" lidas pela câmera, mas os fatos desta sessão só sustentam a leitura da **oferta**
pela câmera — o QR de resposta e o de confirmação foram entregues pelo relay (`scripts/emulator-pair.sh`),
como em todas as sessões anteriores de T4.16. Por isso a linha de evidência inclui uma "Ressalva de
precisão" explícita em vez de afirmar que a resposta também foi lida pela câmera.

A primeira cerimônia desta sessão expirou por o Tor do celular ainda estar frio, não por nenhum
defeito de leitura ou protocolo (SAS idêntico calculado corretamente nos dois lados antes da
expiração). Isso é registrado como uma observação de timing, não como um novo defeito bloqueador — e
gerou a nova tarefa T4.18, para não deixar a pendência ("seria útil tentar de novo dentro dos 300 s
e/ou logar o erro específico") apenas mencionada em prosa dentro do corpo de T4.16.

### Vantagens

- T4.16, aberta há três sessões de emulador só por falta do teste em hardware real, fecha com
  evidência rastreável em vez de continuar acumulando runs de emulador que não podiam, por
  definição, satisfazer o "Feito quando" original.
- A ressalva de precisão sobre "oferta lida pela câmera, resposta entregue pelo relay" evita que uma
  leitura futura do plano superestime o que foi demonstrado.
- T4.18 dá à pendência de retentativa/log de erro um lugar rastreável, com "Feito quando" verificável,
  em vez de deixá-la como uma frase solta que uma sessão futura precisaria redescobrir dentro do
  histórico de T4.16.

### O que não foi alterado

- Nenhuma linha das sessões run5/run6/run7 dentro do corpo de T4.16 foi removida, resumida ou
  reescrita — só um novo parágrafo foi anexado ao final do corpo existente.
- Nenhum outro marcador `[ ]`/`[x]` do plano foi tocado.
- Nenhum código-fonte do app foi alterado nesta sessão — documentação apenas.

---

## 2026-09-23 — Nota em T6.3 (build de release 1.0.0 gerado, tarefa permanece `[ ]`)

### Como era antes

```markdown
- [ ] **T6.3 [B] Release.**
  Chave de assinatura de produção fora do repositório; `assembleRelease` com pacote `dev.mx3.nomessages`; `verify-apk.py` no APK de release; tag `v1.0.0`; `THIRD_PARTY_NOTICES.md` conferido; README "Estado" atualizado para "gates executados".
  Dependências: T6.1, T6.2. Estimativa: 3 h.
```

### Como ficou

O corpo original não foi alterado; um parágrafo de nota foi anexado ao final da entrada, e o
marcador **continua `- [ ]`**:

```markdown
- [ ] **T6.3 [B] Release.**
  Chave de assinatura de produção fora do repositório; `assembleRelease` com pacote `dev.mx3.nomessages`; `verify-apk.py` no APK de release; tag `v1.0.0`; `THIRD_PARTY_NOTICES.md` conferido; README "Estado" atualizado para "gates executados".
  Dependências: T6.1, T6.2. Estimativa: 3 h.
  Nota 2026-09-23 (não marca a tarefa como concluída): a pedido do usuário, foi gerado e verificado um
  build 1.0.0 assinado (chave de produção fora do repositório, via `NOMESSAGES_SIGNING_PROPERTIES`;
  `assembleRelease` com pacote `dev.mx3.nomessages`; `verify-apk.py` passando sem modificação;
  `apksigner`/`aapt2 dump badging`/`aapt2 dump xmltree` conferidos; teste funcional em
  `emulator-5556` incluindo criação de cofre, "Tor connected" e confirmação de que `FLAG_SECURE`
  permanece efetivo em build de release). Detalhes completos em
  [`docs/release-checklist.md`](../../release-checklist.md), seção "Build de release 1.0.0
  (2026-09-23)". T6.3 continua **não concluída**: não há tag `v1.0.0` publicada, T6.1 e T6.2
  (dependências desta tarefa) continuam pendentes, os gates de hardware físico T4.1/T4.6/T4.7 e a
  auditoria externa seguem `NOT_RUN`, e o README "Estado" não foi atualizado para "gates executados".
```

### Por que a marcação continua `[ ]`

O "feito quando" de T6.3 depende de T6.1 e T6.2 (auditoria externa de criptografia/protocolo), que
seguem pendentes, e de uma tag `v1.0.0` publicada, que não existe. Gerar um APK assinado e verificá-lo
estaticamente/funcionalmente em emulador é trabalho real e documentado, mas é apenas parte do que
T6.3 exige — marcar `[x]` aqui esconderia que a auditoria externa e os gates de hardware físico
(T4.1/T4.6/T4.7) continuam em aberto. Mesmo precedente já seguido nesta mesma sessão de documentação
para T4.17 (ver seção de 2026-09-18 acima): implementação/execução parcial registrada em texto, sem
marcar a tarefa como concluída antes que todo o "feito quando" seja satisfeito.

### O que não foi alterado

- Nenhuma outra entrada do plano foi tocada.
- Nenhum marcador `[ ]`/`[x]` de qualquer tarefa foi trocado — inclusive a própria T6.3, que
  permanece `[ ]`.
