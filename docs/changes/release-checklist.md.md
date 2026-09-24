# docs/release-checklist.md

## 2026-09-18 — T4.17 (campainha): gate 10 desdobrado em dois modos e gate 6 com os três ids de notificação

**Antes:** o gate 10 ("Lock closes network") tinha um único critério — *"the `:tor` process must terminate"* — e status `NOT_RUN`. O gate 6 exigia "title only `NoMessages`, no sender/body", sem enumerar quais notificações existem.

**Agora:** o gate 10 exige evidência dos **dois** comportamentos legítimos de lock, com o aviso de que nenhum é variante enfraquecida do outro e que uma rodada que exercite só um **não passa**: **(a)** campainha desligada, texto e evidência de hoje preservados integralmente, processo `:tor` terminando; **(b)** campainha ligada, com seis verificações — onion de mensagens fora do ar e sem conexão aceita, só a 4243 respondendo e fechando com **zero** bytes qualquer coisa que não seja uma batida válida de 57 bytes, dump bloqueado repetindo o resultado do gate 1, notificação de foreground presente/correta/removida no desbloqueio, batida válida produzindo o aviso e inválida não produzindo nada, e reaproveitamento do filho ao destravar o mesmo cofre contra teardown total ao destravar outro ou entrar com a senha de pânico. O status virou `NOT_RUN (neither mode)`. O gate 6 passou a enumerar os **três** ids (1 mensagem, 2 aviso da campainha, 3 foreground do `:tor`), com canal, importância e texto exato de cada um, e a dizer o que **reprova**.

**Por quê:** o desenho aprovado (`docs/development/doorbell-design.md`) já dizia que "a evidência do gate deve mostrar os dois modos". Sem o desdobramento, uma rodada que medisse só o modo desligado poderia ser lida como aprovação do produto inteiro. O gate 6 precisou mudar porque a campainha introduziu a primeira notificação do app **com corpo** — corpo de texto constante, nunca derivado de mensagem, remetente ou contagem —, e a redação anterior ("no body") a reprovaria por engano. Status **não** alterado: esta é uma fase de documentação, sem nenhuma evidência de campo nova.

## 2026-09-14 — T4.3: evidência parcial no gate 13

### Como era antes

A linha 38 da tabela de gates descrevia apenas o que era exigido, sem nenhum
apontamento de evidência já produzida:

```markdown
| 13. Cryptographic vectors | Attach exact reports for RFC 8439/7748/8032/9106
and relevant Wycheproof cases, actual official libsignal exchange/restore/tamper
tests, and RFC 9420/OpenMLS vectors. Record skipped/missing cases. Round-trip
tests alone do not satisfy all known-answer vectors or an external audit.
Current mandatory PQXDH deviation must be assessed explicitly. | NOT_RUN |
```

### Como ficou

O texto exigido continua **idêntico**; foram acrescentadas duas frases ao fim da
célula de descrição, e só ela:

```markdown
... Partial evidence on 2026-09-14 (task T4.3): `native/tests/kat.rs` executes
RFC 8439 section 2.8.2, draft-irtf-cfrg-xchacha section A.3.1 and 1,309
Wycheproof cases (`chacha20_poly1305`, `xchacha20_poly1305`, `x25519`,
`ed25519`) from the digest-pinned corpus in `native/tests/vectors/`, alongside
the existing FIPS SHA-256, RFC 8032 vector 1 and Argon2id `p=1` vectors;
sources, SHA-256 digests, symbol mapping and every skipped case (RFC 9106
section 5.3 is unreachable through libsodium) are in
[development/crypto-report.md](development/crypto-report.md). Still missing for
this gate: RFC 7748 section 6.1 evidence from the JVM side, official libsignal
exchange/restore/tamper reports, RFC 9420/OpenMLS vectors and the explicit PQXDH
deviation assessment. | NOT_RUN |
```

O status **permanece `NOT_RUN`**. Nenhuma outra linha do arquivo foi tocada.

### Por que a mudança foi feita

O gate 13 cobre quatro frentes (KATs próprios, libsignal, MLS e PQXDH); a T4.3
fecha apenas a primeira. Sem registrar o que já existe, o próximo executor
refaz o trabalho; marcando `PASSED`, mentiria sobre as outras três. A redação
adotada diz exatamente o que foi feito, onde está a evidência e o que falta.

### Vantagens

- Aponta o caminho da evidência (`native/tests/kat.rs`,
  `native/tests/vectors/`, `docs/development/crypto-report.md`) direto da tabela
  de gates, que é por onde o auditor entra.
- Enumera nominalmente o que ainda falta, de modo que o gate não possa ser
  fechado por engano só porque "os vetores rodaram".
- Preserva a política do documento: `NOT_RUN` até haver evidência completa de
  dispositivo/terceiros.

### Não verificado

Nada a executar neste arquivo. A parte do gate referente a libsignal, RFC 9420 e
PQXDH não foi avaliada nesta tarefa e continua declarada como pendente.

---

## 2026-09-14 — Revisão: o que falta no gate 13 estava incompleto

### Como era antes

A célula do gate 13 terminava assim:

```
Still missing for this gate: RFC 7748 section 6.1 evidence from the JVM side,
official libsignal exchange/restore/tamper reports, RFC 9420/OpenMLS vectors and
the explicit PQXDH deviation assessment.
```

Dois problemas confirmados:

1. **O RFC 9106 sumia da enumeração.** O texto obrigatório do gate exige
   "reports for RFC 8439/7748/8032/**9106**", e a frase acrescentada citava
   "Argon2id `p=1` vectors" como evidência já existente. Um leitor concluía que a
   perna RFC 9106 estava coberta. Na verdade: o vetor oficial do §5.3 é
   inalcançável pelo libsodium (`p=4` + chave secreta + dados associados, nenhum
   deles exposto) e o único KAT Argon2id executável tem o digest `3fde0023…`,
   gerado com a **mesma** libsodium que ele valida — auto-consistência, não
   verificação por terceiro.
2. **Os 518 casos X25519 eram citados como evidência de caminhos que não os
   executam.** `grep -rn 'scalarmult|curve25519' native/src/` não retorna
   nenhuma ocorrência: nenhum código de produção chama o X25519 do libsodium. O
   MLS usa `x25519-dalek` (via `openmls_rust_crypto`) e o Arti usa
   `curve25519-dalek` (via `tor-llcrypto`).

### Como ficou

A enumeração final passou a ser explícita nos dois pontos, acrescentando:

- **"an independently verified Argon2id vector"** — com a razão: o §5.3 do RFC
  9106 é inalcançável pelo libsodium e o vetor `p=1` executado é auto-gerado com
  a mesma build que ele valida, não verificado por terceiro.
- **"X25519 vectors against the implementations the product actually uses"** —
  com a razão: os 518 casos rodam contra o libsodium embutido, que nenhum caminho
  de produção chama, enquanto o MLS usa `x25519-dalek` e o Arti, `curve25519-dalek`.

O status **permanece `NOT_RUN`** e nenhuma outra linha do arquivo foi tocada. A
primeira metade da célula (o que já foi executado em T4.3) não mudou.

### Por que a mudança foi feita

Uma célula de gate é lida como inventário de pendências. Omitir duas pendências
reais enquanto se lista evidência parcial é o modo mais fácil de fechar um gate
por engano — precisamente o risco que este documento existe para eliminar.

### Vantagens

- O RFC 9106 deixa de parecer coberto por um vetor que não é de terceiros.
- A limitação de escopo do corpus X25519 fica na tabela de gates, que é por onde
  o auditor entra, e não só no relatório técnico.
- As duas lacunas ficam acionáveis: nomeiam o provedor concreto contra o qual
  faltam vetores.

### Não verificado

Nada a executar neste arquivo. As pernas de libsignal, RFC 9420/OpenMLS e PQXDH
continuam sem avaliação nesta rodada, como já estava declarado.

## 2026-09-18 — Gates 6 e 10: status real da campainha, item a item, com citação de evidência

### Como era antes

As duas células de status eram literais únicos, sem nenhuma referência a arquivo:

```
| 6. Notifications | … | NOT_RUN |
| 10. Lock closes network | … | NOT_RUN (neither mode) |
```

A coluna "Required experiment" do gate 10 já havia sido reescrita numa fase anterior desta mesma
sessão para descrever os modos (a)/(b) e os sub-itens (i)–(vi), e a do gate 6 para descrever as três
notificações (id 1, id 2 da campainha, id 3 de foreground). O que faltava era o outro lado: a
validação ao vivo em dois emuladores de 2026-09-18 já tinha medido parte desses sub-itens, e o
checklist — que é por onde um auditor entra — continuava dizendo que nada havia sido medido.

### Como ficou

Só as duas células de status mudaram. Nenhum outro gate, nenhum texto de requisito, nenhum parágrafo
fora da tabela foi tocado. Cada sub-item promovido nomeia o arquivo de evidência (caminho relativo a
`docs/development/build-logs/doorbell-20260918/`) e o horário UTC correspondente na seção
"T4.17 — campainha, validação ao vivo em dois emuladores (2026-09-18)" de
`docs/development/device-verification.md`.

**Gate 10** — continua `NOT_RUN` como gate; modo (a) `NOT_RUN` inteiro; modo (b) parcial:

| Sub-item | Status novo | Evidência citada na célula |
|---|---|---|
| premissa do modo (b): `:tor` sobrevive ao bloqueio | observada | `10-B-pids-before-lock.txt` / `11-B-pids-after-lock.txt` (PID `18555`, 18:20:42Z / 18:21:15Z) |
| (iv) notificação de foreground presente e removida no desbloqueio | **PASSED** | `12-B-notifications-after-lock.txt` (18:21:26Z), `16-B-pids-and-notifications-after-unlock.txt` (18:23:44Z) |
| (v) batida válida levanta o aviso | **PASSED só a metade válida** | `13-A-send-timestamp.txt`, `14-B-doorbell-notification.txt`, `15-B-notification-shade-doorbell.png`, `17-…`, `18-B-chat-campainha-delivered.png`, e a repetição `28-B-acceptance-after-fix.txt` / `29-…png` |
| (vi) desbloqueio do **mesmo** cofre reaproveita o filho | **PASSED só a metade "mesmo cofre"** | `27-B-pid-stable-after-fix.txt` (PID `23229` em 5/5 rodadas, 20:03–20:08Z), `28-B-acceptance-after-fix.txt`; diagnóstico em `22-`, `23-`, `24-` |
| (i), (ii), (iii); metade inválida do (v); cofre diferente e senha de pânico do (vi); gatilhos por timeout e tela apagada; checkpoint de guardas | `NOT_RUN` | — |

**Gate 6** — continua `NOT_RUN` como gate; `PASSED` apenas para **id=2** e **id=3** no estado
bloqueado e em **inglês**, com o dump literal de `dumpsys notification --noredact`
(`12-B-notifications-after-lock.txt`, `14-B-doorbell-notification.txt`,
`15-B-notification-shade-doorbell.png`, `16-B-pids-and-notifications-after-unlock.txt`,
`28-B-acceptance-after-fix.txt`). Seguem `NOT_RUN` a **id=1**, os três textos em **pt-BR** e os
estados "app aberto" e "app em segundo plano".

Ambas as células declaram, no fim, que tudo foi medido em **emuladores** e que nenhuma repetição em
aparelho físico existe.

### Por que a mudança foi feita

T4.17 fechou com validação ao vivo real, e a T4.19 (correção no nativo) fez o sub-item (vi) sair de
falhando para passando. Deixar as duas células em `NOT_RUN` absoluto passou a ser tão impreciso
quanto marcá-las `PASSED`: esconde trabalho medido e, pior, esconde *onde* a medição parou. A
granularidade por sub-item, com arquivo citado, é o único formato em que os dois erros ficam
impossíveis.

### Vantagens

- Cada afirmação de "passou" aponta para um arquivo verificável e um horário; quem duvidar abre o
  arquivo, não precisa confiar na prosa.
- As metades **não** medidas dos sub-itens (v) e (vi) ficam explícitas — batida inválida, cofre
  diferente, senha de pânico —, que é exatamente onde um resumo descuidado arredondaria para cima.
- O gate continua `NOT_RUN` como um todo: nenhuma leitura do documento sugere que o gate 10 ou o 6
  esteja fechado.
- A limitação de ambiente (emulador, build de debug, só o bloqueio explícito) fica na própria célula,
  não só no relatório de sessão.

### Não verificado

Nada foi executado nesta mudança: é consolidação documental a partir de evidência que já existia. Os
itens listados como `NOT_RUN` continuam sem qualquer medição.

## 2026-09-23 — T4.1: gate 2 passa a exigir os diretórios de mídia, não só os `.db`

### Como era antes

A célula do gate 2 só falava dos bancos:

```
| 2. Real/decoy sizes | Measure both encrypted DBs after equivalent lifecycle
points and checkpointing; report exact bytes and the adopted comparison
threshold. The collector can mark the narrow observation of nonzero equal
lengths passed. Repeat after message/file volume changes; a single equality is
not forensic deniability. | NOT_RUN |
```

Isso já estava desatualizado antes mesmo desta tarefa: `real.db`/`decoy.db` sempre tiveram tamanho
fixo (`alignAllocations`), então medir só os dois bancos nunca teria como flagrar o problema real —
`real.files`/`decoy.files` (os anexos cifrados) crescem em disco conforme o uso e, até T4.1, só eram
igualados no setup/reset, nunca durante o uso. Um executor que seguisse a célula ao pé da letra
mediria os `.db`, os acharia iguais (porque sempre foram) e marcaria o gate 2 como coberto sem nunca
olhar para o diretório que de fato vazava.

### Como ficou

A célula passou a exigir a medição dos diretórios de mídia em três níveis, além dos `.db`:

```
| 2. Real/decoy sizes | Measure both encrypted DBs **and their media
directories** (`real.files`/`decoy.files`) after equivalent lifecycle points
and checkpointing; report exact bytes and the adopted comparison threshold.
Since T4.1 (2026-09-23), attachments no longer grow only the slot actually in
use: `MessagingEngine.storeAttachment` mirrors every real write with a
same-size blind-cover blob in the sibling slot's directory, and
`AndroidVaultStorage.mediaCapacityBytes` fixes each slot's reservation
(default 512 MiB, `mediaCapacityMiB`). Parity now needs checking at three
levels, not just the `.db` files: (a) `real.db`/`decoy.db` byte-exact, as
before; (b) `real.files`/`decoy.files` total directory size byte-exact; (c)
file **count** in each directory equal (the cover write creates exactly one
file per real write, under an indistinguishable `<random-hex>.bin` name). The
collector can mark the narrow observation of equal lengths/counts passed.
Repeat after message/file volume changes, including at least one attempt that
exceeds `mediaCapacityBytes` to also confirm `MEDIA_CAPACITY_EXHAUSTED` fails
cleanly without leaving the two directories unequal; a single equality is not
forensic deniability. See `docs/security-model.md`, "Fixed media reservation
and blind cover growth (2026-09-23, T4.1)", and the two
`AndroidVaultStorageTest` cases it cites for the JVM/instrumented-level
evidence this gate's device run still needs to reproduce on hardware. |
NOT_RUN |
```

O status **permanece `NOT_RUN`**: esta tarefa fecha a política de crescimento (código + testes
instrumentados em `emulator-5556`), mas a medição em aparelho físico continua dependente de T3.5, como
o próprio plano já registrava. Nenhuma outra linha da tabela foi tocada.

### Por que a mudança foi feita

T4.1 mudou o que existe para medir: antes, "os dois bancos iguais" já era, por construção, sempre
verdade e não testava nada; agora a garantia de paridade depende de um mecanismo ativo (a cobertura
cega a cada escrita) que pode ter bugs — um `off-by-one` no tamanho do blob, uma escrita que falha
silenciosamente, uma contagem de arquivos que diverge. O gate é o documento por onde um auditor entra;
deixá-lo descrevendo só os `.db` esconderia exatamente a superfície que T4.1 foi escrita para fechar, e
um "PASSED" obtido medindo só os bancos seria uma aprovação vazia.

### Vantagens

- O gate agora aponta para a peça que de fato pode vazar (`.files/`, não `.db`), e diz explicitamente
  os três níveis de comparação (bytes do banco, bytes do diretório, contagem de arquivos).
- Cita `docs/security-model.md` e os dois testes instrumentados novos, então o executor do gate em
  aparelho físico tem onde conferir o mecanismo e o que já foi verificado em JVM/emulador antes de
  repetir em hardware.
- Acrescenta um caso de medição que a redação antiga nunca previa: tentar esgotar
  `mediaCapacityBytes` e confirmar que a falha limpa não deixa os dois diretórios desiguais.
- Mantém a política do documento: `NOT_RUN` até haver evidência de dispositivo físico, mesmo com a
  política de código já fechada e testada.

### Não verificado

A medição em aparelho físico continua não executada (depende de T3.5, fora do escopo desta tarefa,
como o plano já registra para T4.1). Nenhum outro gate da tabela foi avaliado ou alterado.

---

## 2026-09-23 — Nova seção "Build de release 1.0.0 (2026-09-23)"

### Como era antes

O documento continha apenas a tabela dos 13 gates de especificação (todos `NOT_RUN`, exceto
observações parciais nos gates 2, 6, 10 e 13), o procedimento de dois dispositivos e a interpretação
de evidência. Não havia nenhuma seção sobre a geração e verificação do artefato de release em si —
apenas sobre os gates de comportamento em dispositivo.

### Como ficou

Nova seção `## Build de release 1.0.0 (2026-09-23)`, inserida ao final do documento, depois de
"Evidence interpretation". Contém, nesta ordem:

1. Um aviso de abertura em negrito deixando explícito que este build **não substitui** os gates de
   hardware físico T4.1/T4.6/T4.7 nem a auditoria externa de segurança, e que todas as linhas
   `NOT_RUN` da tabela de gates permanecem `NOT_RUN`.
2. Como gerar o build assinado: a variável de ambiente `NOMESSAGES_SIGNING_PROPERTIES` (ou a
   propriedade Gradle `nomessages.signing`), o comportamento de degradar para build sem assinatura
   quando ausente, e o comando `assembleRelease`.
3. Onde fica a chave (apenas o caminho `C:\Users\maxwe\.nomessages-release\`, sem nenhum detalhe do
   conteúdo) e um aviso crítico: sem backup dessa chave, não é possível publicar nenhuma atualização
   futura do app, porque o Android exige que toda atualização de um pacote instalado seja assinada
   com a mesma chave da versão original.
4. O fingerprint do certificado (`CN=NoMessages, O=NoMessages`, SHA-256
   `28:9D:C1:88:5B:BE:A0:96:97:94:74:09:3A:64:F8:53:50:42:27:CC:62:41:A1:B3:84:AC:15:E0:10:39:39:99`).
5. O caminho e SHA-256 do artefato final (`artifacts/nomessages-1.0.0.apk`,
   `dfadbcb7c500e25cd9e46a5d5c916e5b3534a4cb5edd278687e6a4751343d729`), calculado de duas formas
   independentes (`scripts/verify-apk.py` e `certutil -hashfile ... SHA256`) com resultado idêntico.
6. Uma tabela resumindo o resultado de cada verificação estática (`apksigner`, `aapt2 dump badging`,
   `aapt2 dump xmltree`, `scripts/verify-apk.py`), com link para as evidências completas em
   `docs/development/build-logs/release-1.0.0/`.
7. Um resumo do teste funcional no `emulator-5556`: instalação lado a lado com o app de debug,
   criação de cofre, "Tor connected" em ~60s, e o resultado do teste de `FLAG_SECURE` — captura de
   tela 100% preta (0 de 2.592.000 pixels acima do limiar 10) mesmo com o cofre desbloqueado e a
   propriedade de debug `debug.nomessages.allow_capture=1` setada, provando que o gancho de bypass é
   inerte em build de release.
8. Uma lista final de pendências explícitas (gates de hardware físico, auditoria externa, os 13 gates
   da tabela).

### Vantagens

- Registra em um único lugar auditável como reproduzir o processo de assinatura sem nunca expor o
  conteúdo do `signing.properties` (a variável é citada por nome, nunca o valor).
- O aviso sobre perda da chave de assinatura evita que uma futura sessão de trabalho gere uma nova
  chave "substituta" sem perceber que isso quebraria a atualização de instalações existentes — um
  erro difícil de reverter depois de publicado.
- Mantém a disciplina do restante do documento: nenhum gate de comportamento é marcado como aprovado
  por causa deste build: a nova seção é aditiva e cita explicitamente, no próprio texto, quais
  pendências continuam de pé.
- Dá ao README (`docs/changes/README.md.md`, seção 2026-09-23) uma fonte única de verdade para o
  SHA-256 e o fingerprint publicados na seção de instalação.

### Motivo da mudança

Pedido explícito do usuário em 2026-09-23 para gerar, verificar e documentar o primeiro build de
release 1.0.0 assinado do NoMessages. Nenhuma linha da tabela de gates de especificação foi alterada
por esta tarefa — ela documenta apenas o artefato assinado e suas verificações estáticas/funcionais,
que são um pré-requisito de distribuição, não um substituto para os gates de comportamento em
dispositivo.
