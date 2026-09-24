# Mudanças em `SPEC.md`

## 2026-09-18 — T4.17 (campainha): exceção à invariante do lock, campos da oferta com comportamento, metadados de rede, gate 10 e esquema v4

**Antes:** §4.3 terminava com "App bloqueado ⇒ **zero bytes** de plaintext em disco e **zero sockets**", sem exceção. §6 tinha a subseção "Campos reservados para a campainha (T4.17) — nenhum comportamento implementado", dizendo explicitamente que "nenhum serviço sobe, nenhuma notificação existe, nenhuma configuração aparece", e guardava **dois** campos por contato (`doorbell_onion`/`doorbell_token`). §7 dizia "App bloqueado: Arti desliga" sem ressalva e "Notificação local: título 'NoMessages', body vazio" como regra absoluta. §10 listava `contacts` sem nenhuma coluna de campainha e descrevia `meta.doorbell_seed` como "reservada para T4.17". §11 não tinha nenhuma linha sobre a campainha. §12 tinha o gate 10 como uma linha única ("processo Arti morto"). A tabela do changelog de T4.16 marcava a linha 7 como "**Reservados para T4.17**, sem comportamento".

**Agora:** §4.3 ganhou um parágrafo de **exceção documentada e deliberada**, que enumera o que sobrevive (o socket do onion de campainha, a chave dele e o conjunto de tokens emitidos) e o que **não** sobrevive (banco aberto, MK/DBK/FBK, chaves de sessão), remetendo ao desenho aprovado e ao `security-model.md`. §6 virou "Campos da campainha (T4.17) — comportamento implementado", com a tabela das **três** colunas por contato (`doorbell_onion` / `doorbell_token` / `doorbell_token_issued`), a limitação dos contatos pareados antes de T4.17 e a confirmação de que o orçamento de bytes da QR **não mudou** (405/448/317 B, versões 13/14/11 no nível L) — T4.17 só passou a persistir e usar o que o protocolo já carregava. §7 ganhou a ressalva do modo mínimo, a porta virtual 4243 e a regra nova de notificação (texto **constante**, nunca derivado de mensagem, remetente ou contagem). §10 lista as dez colunas de `contacts` e os dois namespaces novos de `opaque_blobs`. §11 ganhou **três** linhas de metadados (processo sobrevivente como sinal, tamanho do conjunto de tokens, padrão de conexões ao onion de aviso). O gate 10 virou **10a** (campainha desligada, evidência de hoje) e **10c** (campainha ligada, seis verificações). §17 ganhou a entrada de changelog de T4.17.

**Por quê:** a invariante de bloqueio é a promessa central do produto; mudá-la sem dizer no SPEC que mudou, e por quê, é pior do que não ter a funcionalidade. A exceção é enumerada em vez de descrita em prosa exatamente para que uma auditoria possa conferir item a item o que sobrevive. O desdobramento do gate 10 segue a mesma lógica: com dois comportamentos legítimos de lock, um gate que só mede um deles não mede o produto.

## 2026-09-17 — T4.10: política de senhas (§5) e forma de normalização do KDF (§4.2)

**Antes:** §4.2 dizia `password = UTF-8 NFC`; §5.1 exigia "Mínimo **16** caracteres alfanuméricos" e "zxcvbn ≥ 4"; §5.2 dizia apenas que o app "recusa igualdade" entre a senha real e a de pânico.

**Agora:** §4.2 diz `password = UTF-8 NFKC`; §5.1 exige 12–128 caracteres após NFKC, aceita qualquer ASCII imprimível e qualquer letra Unicode (símbolos e espaços internos inclusive), proíbe caracteres de controle e espaço nas bordas, e exige `estimateStrength(...).score >= 3` (≈ 60 bits); §5.2 passou a exigir também que a senha de pânico não seja variação trivial da real (subcadeia, ou distância de edição ≤ 2). Foi adicionado um bloco de citação explicando por que a regra era alfanumérica e por que relaxou.

**Por quê:** o usuário reprovou a política em teste real — alfanumérico puro e 16 caracteres inviabilizam frases-senha e não correspondem a força de fato. O ponto técnico que destrava a mudança é o NFKC: ele dobra as formas de compatibilidade que teclados/IMEs diferentes produzem, preservando a promessa da §4.2 de abrir o cofre exportado em outro telefone com a mesma senha. Racional completo em [`PasswordPolicy.kt.md`](PasswordPolicy.kt.md).

## 2026-09-14 — alinhamento do SPEC com o que foi construído (tarefa T5.1)

### Contexto

`SPEC.md` era o desenho aprovado de 2026-09-13 e nunca tinha sido reconciliado com o código
efetivamente escrito. Quem lesse o SPEC e depois o fonte encontraria divergência de protocolo,
de licença e de limites — e concluiria, com razão, que o documento não descrevia o produto.
Como o SPEC é o insumo da auditoria externa (T6.2), isso é um defeito bloqueante.

A regra que guiou a edição: **cada número citado foi lido no fonte**; onde o código ainda não
alcançou o alvo, o SPEC diz explicitamente o que falta e qual tarefa fecha. Nada foi declarado
pronto sem estar.

---

### 1. Licença — GPLv3 → AGPL-3.0-or-later

**Antes (linha 18):**

```
**Licença sugerida:** GPLv3 (código auditável; segredo = senha)
```

**Agora:**

```
**Licença:** **AGPL-3.0-or-later** (arquivo `LICENSE`). A sugestão original era GPLv3; a licença
adotada acompanha a licença AGPL do `libsignal-client` ...
```

**Por quê:** o arquivo `LICENSE` do repositório já é a GNU Affero General Public License v3, e
`libsignal-client` — dependência direta e não substituível do Double Ratchet 1:1 — é AGPL, que é
contagiosa para o código do app.

**Vantagem:** o SPEC deixa de contradizer o `LICENSE` do próprio repositório; quem for redistribuir
sabe a obrigação real (incluindo uso em rede), não a sugerida.

---

### 2. Handshake 1:1 — X3DH → PQXDH (X25519 + Kyber-1024)

**Antes (seções 0, 6, 8.1, 15):**

```
| 1:1 | X3DH + Double Ratchet | FS + PCS | ... |
| Opcional v1.1 | X25519+Kyber768 híbrido | PQ | ... |
...
4. X3DH (identidades + efêmeras) → root key → Double Ratchet (libsignal)
...
Handshake    X3DH
PQ later     X25519-Kyber768 hybrid (não bloqueia v1)
```

**Agora:** todas as ocorrências passam a **PQXDH**, e a seção 6 ganhou a subseção
*"PQXDH no lugar do X3DH clássico (divergência registrada)"*, que documenta:
`KEMKeyType.KYBER_1024` (não 768), prekey Kyber assinada pela identidade, ausência de
last-resort key (`markKyberPreKeyUsed` descarta a chave), e bundle de QR na versão 2 com
`signed`, `signature`, `kyber`, `kyberSignature`.

**Verificado em:** `core/src/main/kotlin/dev/mx3/nomessages/core/protocol/SignalSessions.kt`
linhas 80, 103-105, 174.

**Por quê:** não é escolha estética. O `libsignal-client` 0.102.2 só aceita `PreKeyBundle`
híbrido com prekey KEM assinada; fazer X3DH clássico exigiria abandonar o ratchet oficial,
o que a seção 3 ("Double Ratchet de verdade, não clone") proíbe.

**Vantagem:** o modelo de ameaça passa a refletir a resistência real a "colhe agora, decifra
depois", que o SPEC antes empurrava para a v1.1 — e o item 13 do gate de release agora exige
avaliação explícita desse desvio, em vez de o auditor descobri-lo sozinho.

---

### 3. Seção 7 — três subseções novas de transporte

**Antes:** a seção 7 tinha uma linha sobre padding (`buckets 256 / 1024 / 4096 / 16384`) e nada
sobre estados de rede nem sobre o estado dos guards do Tor.

**Agora:**

- **7.1 Enquadramento construído** — buckets exatos, prefixo de 4 bytes, `MAX_FRAME = 4 + 16*1024`
  (16.388 B — tamanho do frame no fio) espelhado em `native/src/wire.rs:5`, o cabeçalho de
  fragmento de 16 bytes que deixa **16.368 B** de carga útil por frame (`Frames.kt:11-12`,
  acrescentado na rodada de revisão), `MAX_MESSAGE = 16 MiB` (`Frames.kt:9`),
  `WirePacket` de 5 bytes de cabeçalho (`WirePacket.kt:13`, `1 = SIGNAL`, `2 = MLS`), e a
  declaração honesta de que padding reduz precisão mas não esconde horário nem disponibilidade.
- **7.2 Estados de rede expostos na UI** — tabela `OFF → STARTING → PUBLISHING → ONLINE`, com
  `RETRYING` (backoff cancelável) e `ERROR`, mais o estado **parcial** da implementação.
- **7.3 Checkpoint de guards (`TorStateSnapshot`)** — keystore Arti efêmero, captura somente de
  `state/guards.json`, `state/vanguards.json` e `state/circuit_timeouts.json`, formato `WFTOR001`,
  teto de 4 MiB (`TorStateSnapshot.kt:18-20`), duas leituras idênticas na captura, validação JSON
  com profundidade ≤ 32 e ≤ 100.000 nós (`TorStateSnapshot.kt:200`), persistência cifrada em
  `meta.tor_guard_state`.

**Por quê:** `PUBLISHING` é a diferença entre "onion lançado" e "descritor publicado no HSDir".
Sem esse estado, a UI afirma ONLINE antes de o par conseguir conectar — mentira visível ao usuário.
O checkpoint de guards é pré-requisito do gate 10: sem ele, cada lock forçaria escolher guards
novos, o que é um sinal observável pelo adversário de rede.

**Vantagem:** a auditoria passa a poder verificar frame por frame o que trafega e o que o estado
de rede promete, sem ler o fonte.

---

### 4. Seção 7.2 — honestidade sobre o estado parcial (ajuste desta rodada)

**Antes (escrito na rodada anterior, já desatualizado):**

```
`tor.start` retorna logo após `launch_onion_service_with_hsid`, sem consumir `status_events()`
... Fechar essa lacuna é a tarefa T2.1 ... e T2.3 ...
```

**Agora:** a nota foi separada em duas camadas, porque o nativo avançou enquanto o Kotlin não:

- **Nativo: feito.** `native/src/tor.rs` expõe `status()` e `await_ready(timeout_ms)`, que bloqueia
  até `is_fully_reachable` (`Running` ou `DegradedReachable`), com
  `MAX_READY_TIMEOUT_MS = 300_000` (linha 36) independente do bootstrap de 180 s (linha 266).
  Receber `"PUBLISHING"` de volta significa "o prazo acabou primeiro", não falha.
- **Kotlin: feito também** (corrigido na rodada de revisão — ver seção 12 deste documento). O enum
  e a transição já existem: `UiContract.kt:8` é `enum class NetworkStatus { OFF, STARTING,
  PUBLISHING, ONLINE, RETRYING, ERROR }` (a linha 7 é o KDoc que explica `PUBLISHING`), e
  `NoMessagesController` emite `STARTING` (`:235`) → `PUBLISHING` (`:246`) → `awaitReady()` (`:247`)
  → `ONLINE` (`:248`). Falta apenas o emissor de `RETRYING` (T2.3): o valor está no enum e é
  traduzido em `Components.kt:107`, mas nenhum caminho de código o publica.

**Por quê:** a versão anterior desta nota afirmava que o nativo não esperava o descritor, o que
deixou de ser verdade quando T2.1 começou a aterrissar. Um SPEC que descreve errado o estado
parcial é pior que um SPEC omisso: induz o próximo agente a refazer trabalho já feito. A própria
correção acima envelheceu do mesmo jeito enquanto era escrita — T2.1 concluiu a camada Kotlin em
paralelo —, o que motivou a rodada de revisão registrada na seção 12.

**Vantagem:** T2.3 fica reduzido ao que realmente falta (o laço de backoff), sem retrabalho nem na
camada Rust nem no enum.

---

### 5. Seções 8.3 e 8.4 — retenção de épocas MLS e coordenação sem servidor

**Antes:** a seção 8.2 terminava em `Limite duro: 100. Acima recusa.` e nada dizia sobre retenção
de épocas nem sobre o que acontece quando o coordenador sai do grupo.

**Agora:**

- **8.3** — `PastEpochDeletionPolicy::MaxEpochs(2)` nos dois pontos de carga do grupo
  (`native/src/mls.rs:312` e `:374`), com a consequência declarada: mensagem de aplicação atrasada
  por mais de duas mudanças de membership, ou vinda de quem já foi removido, fica **indecifrável
  para sempre**.
- **8.4** — grupo fica pendente até todo par devolver KeyPackage vinculado ao `requestId`; MLS
  proíbe o committer remover a si mesmo, então a saída do coordenador transfere a coordenação para
  a identidade sobrevivente lexicograficamente menor; Commit que chega antes da transferência é
  retentado; remoção não envia o novo Commit ao removido.
- **8.2** ganhou os números: `MIN_MEMBER_COUNT = 3`, `MAX_MEMBER_COUNT = 100`,
  `MIN_COMMIT_MEMBER_COUNT = 1`, `MAX_PROOF_COUNT = 4.950` = C(100,2)
  (`Envelope.kt:136-140`).

**Por quê:** reter 2 épocas é escolha deliberada de forward secrecy sobre entrega. É **perda de
mensagem observável pelo usuário** e, portanto, não pode viver apenas como constante no Rust.

**Vantagem:** o usuário e o auditor sabem o preço exato de não haver mailbox, em vez de atribuírem
a perda a um bug.

---

### 6. Seções 9.1 e 9.2 — não lidas e a lacuna de mídia

**Antes:** a seção 9 descrevia a UI e nada dizia sobre recibos de leitura nem sobre o crescimento
dos anexos.

**Agora:**

- **9.1** fixa a decisão: **contador de não lidas estritamente local, nenhum recibo de leitura na
  rede**. Tique duplo continua significando entrega autenticada (`DELIVERED` = ACK de todos),
  nunca leitura; `MessageStatus.READ` passa a ser marcado localmente ao abrir a conversa.
  Registrado que hoje `ChatUi.unread` é sempre 0 e o badge é código morto (tarefa **T4.2**).
- **9.2** registra a lacuna de mídia real/decoy como **em definição (T4.1)**: `alignAllocations`
  iguala os bancos só no setup, enquanto anexos reais crescem em `files/vault/real.files/` e o
  decoy fica parado. As duas saídas plausíveis (reserva fixa de mídia × aceitar e documentar a
  divergência) estão descritas, com a recomendação técnica registrada e a decisão explicitamente
  **não tomada**.

**Por quê:** recibo de leitura entregaria ao par um metadado novo — *quando você abriu a conversa* —
em troca de conveniência, contrariando a seção 11. E a lacuna de mídia corrói justamente a
deniability de disco que a senha de pânico promete, então esconder seria pior que registrar.

**Vantagem:** o SPEC para de prometer, no texto da seção 5.2, um alinhamento que o runtime não
sustenta; e o problema fica endereçado a uma tarefa em vez de virar surpresa no gate 2.

---

### 7. Seção 10 — schema real, envelopes, lanes e limites

**Antes:**

```
Tabelas mínimas (nomes internos, não visíveis):

- `meta(k,v)` — epoch, display name local
- `contacts(id_pub, onion, alias, ratchet_blob, paired_at)`
- `messages(id, peer_or_group, dir, ts, body_cipher_already_at_rest, status)`
- `groups(id, name, mls_state, created_at)`
- `group_members(group_id, id_pub)`
- `outbox(id, dest_onion, frame, created_at)`
- `files(id, path_rel, aead_params, size)`
- `pair_graph` — arestas do clique
```

**Agora:** o schema construído (`AndroidVaultStorage`), incluindo `opaque_blobs`, `wire_blobs`
(payload por digest SHA-256), `chat_groups` (renomeada para não colidir com palavra reservada),
`storage_reserve`, o `CHECK(first_id < second_id)` do `pair_graph` e o fato de `outbox` referenciar
o **digest**, não o frame. Mais três subseções novas:

- **10.1 Envelopes de aplicação** — tabela de tags de wire 1..11 conferida em
  `EnvelopeCodec.kt:250-275`: 1 `Text`, 2 `Attachment`, 3 `Ack`, 4 `Evidence`, 5 `KeyPackage`,
  6 `GroupInvite`, 7 `GroupCommit`, 8 `KeyPackageRequest`, 9 `GroupLeave`, **10 reservada e não
  emitida**, 11 `ControlRejected`. As quatro razões de recusa (`KEY_PACKAGE_QUOTA` 1,
  `REQUEST_EXPIRED` 2, `GROUP_CAPACITY` 3, `INVALID_PREREQUISITE` 4) em `EnvelopeCodec.kt:278-289`.
  Limites de codec: envelope ≤ 12 MiB, texto ≤ 8 MiB, ciphertext de anexo ≤ 8 MiB + 64 KiB,
  4.950 provas de 1.024 B, nome/MIME ≤ 255 B (`Envelope.kt:132-142`);
  `WirePacket.MAX_PACKET_BYTES` = 16 MiB.
- **10.2 Filas, lanes e cursores** — lanes `direct` / `group:<id>` / `request:<nonce>` / `evidence` /
  `rejection` (`MessagingPolicy.kt:25-35`), 4 destinos em voo (`MessagingEngine.kt:335`),
  ACK de 60 s (`:357`), ≤ 16 remontagens em 32 MiB (`:392`), cursores de trial 64/8
  (`MessagingPolicy.kt:40`), cache de ≤ 16 entradas / 24 MiB com expiração 120 s / 15 min
  (`MessagingEngine.kt:411-432`), token bucket global de 2/s com rajada 4
  (`MessagingPolicy.kt:46-56`).
- **10.3 Limites duros de runtime** — 1.024 contatos (`MessagingPolicy.kt:10`), 128 grupos ativos
  (`MessagingEngine.kt:128`, `:449`, `:606`), 16.384 arestas (`:689`), 256 provas por envelope de
  sincronização (`:123`, `:696`), KeyPackages 4 por par / 128 no total / TTL 24 h
  (`MessagingPolicy.kt:41-42`, `MessagingEngine.kt:50`), snapshot Tor de 4 MiB. Na rodada de
  revisão (seção 12) entraram mais três linhas — 2 épocas MLS retidas, 32 gerações fora de ordem
  por remetente e distância máxima de 1.024 (`native/src/mls.rs:312-313,374-375`) — e a lista de
  namespaces foi corrigida: são **catorze** em `opaque_blobs`, porque `pairing_consumed`,
  `lock_timeout`, `bridges`, `display_name` e `outbox_sequence` são chaves de `meta`.

**Por quê:** `ControlRejected` existe porque, sem servidor, um controle que jamais poderá ser
satisfeito ficaria retentando para sempre na lane — a recusa autenticada é parte do protocolo.
As lanes são o que impede que uma recusa de quota num grupo bloqueie um chat direto
(head-of-line blocking contido). E nenhum dos limites duros estava no SPEC, embora sejam
exatamente o que um auditor precisa para avaliar DoS e esgotamento de recurso.

**Vantagem:** a seção 10 deixa de ser esboço e passa a ser especificação verificável linha a linha
contra o fonte.

---

### 8. Seção 11 — tabela de metadados ampliada (ajuste desta rodada)

**Antes:**

```
| Tamanho da msg | tráfego Tor | padding buckets |
| Horário | tráfego | sem solução perfeita; não fingir |
| Membership de grupo | só nos devices do clique | sem servidor |
```

**Agora:** a linha de tamanho diz o que o padding realmente entrega (conteúdo acima de **16.368 B**
vira vários frames, e a contagem revela a ordem de grandeza — o número foi corrigido de 16.388 para
16.368 na rodada de revisão, ver seção 12) e duas linhas novas entraram:
**disponibilidade** (o descritor publicado no HSDir é sondável por quem conhece o onion — aceito,
porque sem mailbox estar alcançável é pré-requisito de entrega) e **volume de mídia acumulado**
(em aberto, apontando para 9.2).

**Por quê:** 7.1 e 9.2 introduziram vazamentos que a tabela não listava. Quem vai direto à seção 11
para montar o modelo de ameaça ficaria com uma lista incompleta.

**Vantagem:** a seção 11 volta a ser a lista completa de metadados, que é o uso pretendido dela.

---

### 9. Seção 12 — gate 2 apontando para a lacuna real (ajuste desta rodada)

**Antes:**

```
2. Mesmo dump: tamanhos real.db ≈ decoy.db
```

**Agora:** o gate continua, com o aviso de que o alinhamento só é garantido no setup e a exigência
de medir **também os diretórios de anexo**, não só os `.db`.

**Por quê:** medir apenas os dois `.db` daria verde num aparelho cuja mídia real já denuncia o
volume de uso. O gate estaria validando a promessa errada.

**Vantagem:** o critério de release passa a medir o que a seção 5.2 afirma.

---

### 10. Seção 15 — checklist de primitives

**Antes:**

```
Handshake    X3DH
Groups       MLS 1.0 RFC 9420 (OpenMLS)
Padding      buckets 256..16384
Tor          Onion v3, Arti
PQ later     X25519-Kyber768 hybrid (não bloqueia v1)
```

**Agora:** `Handshake PQXDH (X25519 + Kyber-1024, prekey KEM assinada)`, retenção de 2 épocas no MLS,
`MAX_FRAME 16388 B` e teto de 16 MiB no padding, keystore efêmero + checkpoint de guards no Tor,
e a linha "PQ later" removida por ter deixado de ser futuro. Acrescentado o parágrafo com os
parâmetros Argon2id reais (`KdfParams.kt:6-9,26-32`): `memoryKiB` default 65.536, faixa
65.536..262.144; `iterations` default 3, faixa 1..20; `p = 1`; saída 32 B; a calibração de ~2,5 s
sobe primeiro as iterações (até 20) e só então dobra a memória até 256 MiB — nunca para baixo.

**Por quê:** o checklist é o trecho que o README técnico copia. Se ele mente, a mentira se propaga.

**Vantagem:** o teto e a ordem de calibração do Argon2id ficam auditáveis; antes só havia piso e alvo.

---

### 11. Seção 17 — changelog do SPEC

Foi acrescentada ao fim do arquivo uma seção **17. Changelog do SPEC**, com a entrada datada
**2026-09-14** contendo a tabela de mudanças (o que mudou / onde / por quê), a vantagem geral e
a lista explícita do que **continua em aberto**. A tabela nasceu com 17 linhas e foi para **22** na
rodada de revisão (seção 12 deste documento, itens 18-22). A lista de pendências hoje tem quatro
itens: 9.2 (T4.1), `RETRYING` (T2.3), a divergência de guards real/decoy da 7.3 e a contradição
pendente em `README.md:35`; mais o catálogo de `opaque_blobs` (T5.2), que vive em
`docs/development/runtime-catalog.md`.

**Por quê:** sem changelog, a próxima auditoria não consegue distinguir "o SPEC sempre disse isso"
de "isso foi reconciliado depois do código", que é uma diferença relevante para avaliar a confiança
no documento.

---

### 12. Rodada de revisão de 2026-09-14 — correções factuais

Uma revisão posterior, no mesmo dia, conferiu cada afirmação da rodada anterior contra a árvore de
trabalho e encontrou seis divergências. Todas foram corrigidas **dentro** das seções existentes do
SPEC (nenhuma seção nova foi criada) e registradas como itens **18 a 22** da tabela do changelog do
próprio SPEC.

**12.1 — Seção 7.2 dizia que a camada Kotlin "ainda não" tinha `PUBLISHING`.**

*Antes:* "`NetworkStatus` ... declara `OFF, STARTING, ONLINE, RETRYING, ERROR` — sem `PUBLISHING` —
e `NoMessagesController.activate` publica `STARTING`, depois `ONLINE` assim que `tor.start` retorna
... a UI hoje diz ONLINE antes de o onion ser alcançável".

*Agora:* o bullet afirma o que o fonte mostra — `UiContract.kt:8` já tem `PUBLISHING`, o controller
faz `STARTING` (`:235`) → `PUBLISHING` (`:246`) → `awaitReady()` (`:247`) → `ONLINE` (`:248`), o
banner traduz em `Components.kt:105` e as strings `network_publishing` existem em PT e EN
(`strings.xml:122` nos dois idiomas). A única lacuna é `RETRYING`, sem emissor.

*Por quê:* T2.1 concluiu em paralelo e a nota nasceu obsoleta — as linhas 243/249 citadas nem
existem mais com aquele conteúdo. O erro se repetia em três lugares (7.2, item 5 do changelog e a
lista "Continua em aberto"), todos corrigidos em cadeia, mais o bullet da seção 4 deste documento.

*Vantagem:* o SPEC para de mandar o próximo agente refazer T2.1 e isola a dívida real (T2.3).

**12.2 — Cinco chaves de `meta` estavam listadas como namespaces de `opaque_blobs`.**

*Antes:* "O namespace `opaque_blobs` usado hoje inclui ... `pairing_consumed`, `lock_timeout`,
`bridges`, `display_name` e `outbox_sequence`" (19 nomes).

*Agora:* 10.3 lista os **catorze** namespaces reais de `opaque_blobs` e diz explicitamente para não
confundir com `meta`; a bullet de `meta(k, v)` da seção 10 recebeu as cinco chaves com as
referências de fonte (`NoMessagesController.kt:156,209,221,222,489,586,592,789`,
`MessagingEngine.kt:273,275`).

*Por quê:* são tabelas e APIs distintas — `getMeta` faz `SELECT v FROM meta WHERE k = ?`
(`ChatDatabase.kt:18-20`) e `getBlob` faz `SELECT value FROM opaque_blobs WHERE namespace = ? AND
k = ?` (`:34-37`). A lista tinha sido copiada do enunciado de T5.2 sem conferir no fonte, o que
contrariava a regra declarada no topo deste documento ("cada número citado foi lido no fonte"), e
`docs/development/runtime-catalog.md:51-52` já apontava a mesma divergência de forma independente.

*Vantagem:* o auditor procura cada registro na tabela onde ele de fato está.

**12.3 — Limiar de fragmentação errado (16.388 B → 16.368 B).**

*Antes:* seção 11, "conteúdo acima de 16.388 B vira vários frames".

*Agora:* "acima de **16.368 B** (bucket de 16.384 menos o cabeçalho de fragmento de 16 bytes)", e
7.1 ganhou um bullet descrevendo o cabeçalho de 16 bytes (total, índice, contagem, comprimento).
Os 16.388 B continuam declarados, mas só como tamanho do frame no fio.

*Por quê:* `Frames.kt:11-12` define `HEADER = 16` e `CHUNK = 16384-HEADER`, e `:16` calcula
`count = ⌈ciphertext/CHUNK⌉`. Logo 16.368 B = 1 frame e 16.369 B = 2 frames. Os 16.388 B de
`MAX_FRAME` (`native/src/wire.rs:5`) são bucket + prefixo de comprimento — grandeza diferente, que
o texto anterior misturava.

*Vantagem:* o número publicado passa a ser o que um teste de fragmentação reproduz.

**12.4 — 7.3 não dizia que o checkpoint de guards é por vault.**

*Antes:* 7.3 tratava os metadados de guards como "segredo de ligação" preservado no vault, sem
mencionar o decoy.

*Agora:* um parágrafo registra que `restore`/`persistTorState` operam sobre o banco **ativo**
(`NoMessagesController.kt:223-226` e `:361`) e que `DecoyFactory.create` semeia apenas
`StorageKeys.IDENTITY` e `StorageKeys.ONION_SEED` — nunca `tor_guard_state`. Consequência: abrir com
a senha de pânico faz o Arti amostrar guards novos, enquanto o vault real preserva os seus. A
tabela da seção 11 ganhou a linha "Qual vault foi aberto (real vs. pânico)" e a lista "Continua em
aberto" ganhou o item correspondente.

*Por quê:* é um distinguidor real/decoy observável pelo guard e por quem observa a entrada, no
exato momento em que a senha de pânico é usada — o oposto do que a deniability promete. Segue o
mesmo tratamento dado à 9.2: o problema é descrito, **nenhuma solução foi inventada**, e a decisão
final fica para `docs/security-model.md` (arquivo fora do escopo desta edição).

**12.5 — Gate 10 não testava nada do que 7.3 exige.**

*Antes:* 7.3 dizia "consequência para o gate 10", mas o gate 10 é só "zero sockets NoMessages;
processo Arti morto" — sem diretório de estado, sem restauração.

*Agora:* novo item **10b** na seção 12 exigindo, em lock normal e em morte abrupta do processo
`:tor`: diretório de estado inexistente após o lock, guards vindos do `meta.tor_guard_state` do
vault aberto após o unlock, nenhum arquivo de estado sobrevivendo fora dele, e registro explícito
da divergência de guards do decoy. 7.3 também passou a repetir a limitação já admitida em
`docs/security-model.md`: o Arti escreve metadados de guard e de introduction point em claro no
diretório de estado durante a sessão, e apagar o diretório não prova apagamento seguro em flash.

*Por quê:* o gate 2 foi corretamente emendado para a 9.2 na rodada anterior, mas a 7.3 ficou sem
gate equivalente — uma exigência sem teste é uma promessa não verificável.

**12.6 — 8.3 e 10.3 citavam só `MaxEpochs(2)`.**

*Antes:* "`PastEpochDeletionPolicy::MaxEpochs(2)` nos dois pontos de carga do grupo".

*Agora:* 8.3 acrescenta `SenderRatchetConfiguration::new(32, 1024)` (`native/src/mls.rs:313,375`,
imediatamente após a política de épocas) com a consequência — reter 32 gerações fora de ordem por
remetente permite decifrar o que chegou trocado, mas **adia o benefício de forward secrecy** dessas
chaves; acima de 1.024 de distância o pacote é recusado. A tabela de 10.3 ganhou as três linhas
(2 épocas, 32 gerações, distância 1.024).

*Por quê:* citar metade de um tradeoff configurado na mesma linha do fonte dá ao auditor a
impressão de uma política de FS mais agressiva do que a implementada. `docs/security-model.md` já
documentava os dois números; o SPEC, nenhum.

---

### O que foi preservado

- Estrutura e numeração das seções 0 a 16 intactas; todo conteúdo novo entrou como subseção
  (7.1-7.3, 8.3-8.4, 9.1-9.2, 10.1-10.3) ou como seção 17 no fim.
- Nenhuma decisão travada da seção 16 foi reaberta: Tor continua transporte único, sem servidor,
  sem mailbox, senha de pânico obrigatória.
- A política de mídia real/decoy (T4.1) foi apenas **registrada como em definição**; nenhuma
  solução foi inventada para preencher o vazio.

### Verificação

Documento em Português; os identificadores citados são do código em inglês. Todos os números foram
lidos diretamente do fonte nesta rodada (`Envelope.kt`, `EnvelopeCodec.kt`, `WirePacket.kt`,
`Frames.kt`, `MessagingPolicy.kt`, `MessagingEngine.kt`, `TorStateSnapshot.kt`, `SignalSessions.kt`,
`KdfParams.kt`, `UiContract.kt`, `NoMessagesController.kt`, `native/src/tor.rs`, `native/src/mls.rs`,
`native/src/wire.rs`) e conferidos contra `docs/development/messaging-api.md`. `SPEC.md` é Markdown:
não há compilação nem teste automatizado que o valide.

Na rodada de revisão (seção 12) foram relidos `UiContract.kt`, `NoMessagesController.kt`,
`Components.kt`, `strings.xml` (PT e EN), `Frames.kt`, `ChatDatabase.kt`, `MessagingEngine.kt`,
`DecoyFactory.kt` e `native/src/mls.rs`, e conferido `docs/development/runtime-catalog.md`. Como o
Windows desta estação não tem JDK nem SDK Android, **nenhuma** das afirmações sobre Kotlin foi
validada por compilação: a verificação foi leitura de fonte (`grep`/`sed`) na árvore de trabalho,
que contém edições ainda não commitadas de T2.1.

---

## 2026-09-16 — Nota de endurecimento de entrada/captura na seção 9 (T4.9)

### Como era antes

A linha "Teclado: IME de sistema; settings avisa teclado em nuvem. IME próprio = fase posterior."
não tinha nenhum detalhe sobre o que "avisa" significava de fato, nem mencionava autofill,
acessibilidade ou detecção de captura.

### Como ficou

A linha ganhou uma referência cruzada explícita a §14/T7.1 ("IME próprio = fase posterior (§14,
T7.1)"), seguida de um parágrafo novo resumindo as seis proteções de T4.9 (teclado privado,
autofill desligado, acessibilidade sensível em API 34+, aviso de terceiros, detecção de captura em
API 34+) com uma frase final deixando claro que nenhuma delas impede um teclado comprometido de ler
a entrada — só um teclado próprio no app faria isso, o que é T7.1, fora da v1.

### Vantagens

- O SPEC deixa de tratar "settings avisa teclado em nuvem" como uma linha solta e passa a apontar
  para `docs/security-model.md`, onde o raciocínio completo (inclusive o trade-off de
  acessibilidade) está.
- A referência a T7.1 no meio da seção 9 e não só na seção 14 torna a limitação visível para quem
  lê a seção de UI sem necessariamente chegar até "Fora da v1".

### Por que a mudança foi feita

T4.9, requisito explícito de nota no SPEC.

---

## 2026-09-17 — Seção 9 (tabela de telas) ajustada ao que o app tem hoje

### Como era antes

A tabela de telas da seção 9 tinha uma linha para uma tela que era só um espaço reservado, sem
nenhum comportamento próprio; e outras duas frases do SPEC (fora da seção 9) ainda referenciavam
essa mesma tela ao descrever o produto.

### Como ficou

A linha correspondente foi removida por inteiro da tabela de telas, sem deixar buraco na
numeração das demais; as outras duas frases passaram a conter só o texto que já descrevia o resto
do produto:

```
Nome da launcher: **NoMessages**. Ícone visível, estilo WhatsApp (verde, balões, lista de chats).
```

```
Default visual: clone WhatsApp, paleta clássica.
```

### Vantagens

- O SPEC deixa de descrever uma tela que existia só como espaço reservado, sem nenhum
  comportamento próprio.
- Um auditor lendo a seção 9 (tabela de telas) só encontra linhas que correspondem a telas que o
  código de fato tem.

### Por que a mudança foi feita

Manter o SPEC batendo com o app, em vez de descrever uma tela inerte como se fosse
funcionalidade planejada para a v1.

## 2026-09-17 — Correção pós-revisão: ícone da marca "NM" na linha de cores da seção 9 (mesmo dia — achado na conferência do orquestrador)

A revisão do trabalho de T4.14 encontrou um resíduo que sobrou fora da seção 9: a mesma linha de
"Cores" reescrita ainda terminava com o monograma antigo — o
resto da linha já tinha sido atualizado para a paleta nova, mas ninguém tinha reparado que o nome
do ícone também precisava virar `"NM"`, já que `BrandMark()` e o recurso `brand_mark`
(`strings.xml`) já tinham sido trocados para `"NM"` no mesmo commit lógico. Corrigido para:

```
Ícone próprio "NM".
```

## 2026-09-23 — Correção da revisão: layout antigo de `values/`/`values-en/` e idioma padrão desatualizados

### Como era antes

Linha 17: `**Idioma UI:** PT-BR default, EN secundário`.

Linha 500 (seção T2.1/T2.3, sobre `network_publishing`): "as strings `network_publishing` existem
em PT e EN (`values/strings.xml` e `values-en/strings.xml`, ambas na linha 122)."

Ambas as linhas descreviam o layout antigo: `values/` (o default sem qualificador de locale) em
português, com um `values-en/` separado para inglês. Esse layout foi trocado (tarefa paralela da
mesma leva): agora `values/` é o default e está em **inglês** — o que o Android usa para qualquer
idioma de sistema não suportado, não só quando o aparelho está em inglês — e `values-pt/` cobre
português (pt-BR e pt-PT, sem qualificador de região). `values-en/` não existe mais.

### Como é agora

Linha 17: `**Idioma UI:** EN default (usado em qualquer idioma de sistema não suportado), PT-BR/PT-PT
secundário`.

Linha 500: "...e as strings `network_publishing` existem em EN e PT (`values/strings.xml` e
`values-pt/strings.xml`, ambas na linha 130)." (o número da linha também mudou, de 122 para 130,
porque o arquivo cresceu desde a escrita original desta seção.)

### Vantagens

- `SPEC.md` é a "fonte de verdade" do projeto (frase do próprio arquivo, linha 13); descrever um
  layout de recursos que não existe mais confundiria qualquer leitura futura da spec como guia de
  onde adicionar uma string nova.
- Consistência com `CONTRIBUTING.md` (que já documentava `values/` = inglês/default e `values-pt/` =
  português corretamente) e com `app/src/main/res/xml/locales_config.xml`.

### Por que a mudança foi feita

Achado 5 da revisão desta tarefa.
