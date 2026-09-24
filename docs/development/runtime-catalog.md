# Catálogo de `opaque_blobs`, chaves de `meta` e limites de runtime

Data: 2026-09-14. Tarefa: T5.2 do `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.

Este documento foi produzido por leitura direta de:

- `app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingEngine.kt`
- `app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingPolicy.kt`
- `app/src/main/kotlin/dev/mx3/nomessages/runtime/NoMessagesController.kt`
- `app/src/main/kotlin/dev/mx3/nomessages/storage/AndroidVaultStorage.kt`
- `app/src/main/kotlin/dev/mx3/nomessages/storage/ChatDatabase.kt`
- `app/src/main/kotlin/dev/mx3/nomessages/storage/DecoyFactory.kt`

Toda linha tem `caminho:linha`. Nada aqui é inferido de especificação: onde o código e o
`SPEC.md`/plano divergem, o código manda e a divergência está registrada na seção final.

> **Âncora dos números de linha — leia antes de conferir qualquer citação.**
> Todas as referências `arquivo:linha` deste documento foram lidas e conferidas em
> **`HEAD` (commit `22aa7b0`)**. Para reproduzir qualquer uma delas use
> `git show 22aa7b0:<arquivo>`, e **não** o arquivo da árvore de trabalho.
>
> Isto não é uma precaução para o futuro: `MessagingEngine.kt` e `NoMessagesController.kt`
> **já divergem de `22aa7b0` agora**, porque T2.1/T2.3 estão reescrevendo o caminho de
> ativação do Tor (`NetworkStatus.PUBLISHING`, `awaitReady`, `superviseTransport`,
> reconexão com backoff) em edições ainda não commitadas. As linhas `ME:` e `WC:` deste
> catálogo **não valem para a árvore de trabalho** enquanto essas tarefas não fecharem:
> o deslocamento não é constante e muda a cada rodada de edição. Quando T2.x for
> commitada, reconfira `ME:` e `WC:` contra o novo commit e atualize esta âncora — o
> conteúdo descrito (namespaces, formatos, chaves, ciclo de vida) só muda se a própria
> lógica mudar. No momento desta revisão nenhuma dessas edições cria ou remove namespace
> ou chave: a única mudança de acesso a dados no diff é **um novo ponto de leitura** de
> `getMeta("onion_seed")` no caminho de reconexão, sobre uma chave já catalogada aqui.
>
> `MP` (`MessagingPolicy.kt`), `CD` (`ChatDatabase.kt`), `AVS` (`AndroidVaultStorage.kt`)
> e `DF` (`DecoyFactory.kt`) não estão sob edição (`git diff HEAD --numstat` não os
> lista) e suas linhas valem tanto para `22aa7b0` quanto para a árvore de trabalho.

Abreviações usadas nas tabelas:

| Sigla | Arquivo |
|---|---|
| `ME` | `app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingEngine.kt` |
| `MP` | `app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingPolicy.kt` |
| `WC` | `app/src/main/kotlin/dev/mx3/nomessages/runtime/NoMessagesController.kt` |
| `CD` | `app/src/main/kotlin/dev/mx3/nomessages/storage/ChatDatabase.kt` |
| `AVS` | `app/src/main/kotlin/dev/mx3/nomessages/storage/AndroidVaultStorage.kt` |
| `DF` | `app/src/main/kotlin/dev/mx3/nomessages/storage/DecoyFactory.kt` |

---

## 1. Correção importante ao enunciado da tarefa

A lista de T5.2 no plano trata 20 nomes como se fossem todos namespaces de `opaque_blobs`.
Não são. O esquema tem **duas** tabelas chave-valor distintas, criadas em
`AVS:180-181`:

```sql
CREATE TABLE meta(k TEXT PRIMARY KEY NOT NULL, v BLOB NOT NULL) WITHOUT ROWID
CREATE TABLE opaque_blobs(namespace TEXT NOT NULL, k TEXT NOT NULL, value BLOB NOT NULL,
                          PRIMARY KEY(namespace,k)) WITHOUT ROWID
```

- **14 namespaces** existem de fato em `opaque_blobs` (seção 2).
- **6 dos nomes listados no plano** (`tor_guard_state`, `pairing_consumed`, `lock_timeout`,
  `bridges`, `display_name`, `outbox_sequence`) são chaves da tabela `meta`, gravadas por
  `CD:23` (`putMeta`) e não por `CD:40` (`putBlob`). Estão na seção 3, junto com
  `identity` e `onion_seed`, que o plano não listou mas pertencem ao mesmo conjunto.

O acesso a `opaque_blobs` passa por `CD:34` (`getBlob`), `CD:40` (`putBlob`),
`CD:53` (`removeBlob`) e `CD:59` (`listBlobs`); o nome do namespace é validado contra
`[a-z0-9][a-z0-9._-]{0,63}` em `CD:563` e `CD:578`.

---

## 2. Namespaces de `opaque_blobs`

### 2.1 Visão geral

| Namespace | Chave | Escreve | Lê | Apaga | No decoy? |
|---|---|---|---|---|---|
| `group_pending` | `groupId` | `ME:139` | `ME:152,229,287,449,455,584,636`; `WC:795` | `ME:198,600` | Não |
| `group_left` | `groupId` | `ME:161,167,171,199` | `ME:230,288,449,455,636,653`; `WC:793` | nunca | Não |
| `group_error` | `groupId` | `ME:537` | `ME:216,228`; `WC:794` | `ME:198,216` | Não |
| `group_request` | `requestId` | `ME:142` | `ME:191,257,579,593` | `ME:194,540,597` | Não |
| `group_packages` | `groupId:peerId` | `ME:585` | `ME:587` | `ME:197,598` | Não |
| `key_packages` | `requestId` | `ME:554` | `ME:550,613,659` | `ME:539,619,627,663` | Não |
| `outbox_control` | `envelopeId:peerId` | `ME:267` | `ME:181,526` | `ME:187,519,535` | Não |
| `outbox_lanes` | `messageId:peerId` | `ME:277` | `MP:14,17` | `ME:186,518,534` | Não |
| `attempts` | `messageId:peerId` | `ME:339` | `MP:13,22` | `ME:186,517,534` | Não |
| `accepted_ids` | `envelope.id` | `ME:495` | `ME:485` | **nunca** | Não |
| `rejected_ids` | `envelope.id` | `ME:493` | `ME:486` | **nunca** | Não |
| `receipts` | SHA-256 hex do wire | `ME:501` | `ME:408` | **nunca** | Não |
| `failed_controls` | `envelopeId:peerId` | `ME:536` | **ninguém** | **nunca** | Não |
| `evidence_gossip` | id do lote | `ME:698,716` | `ME:705,707` | `ME:715` | Não |
| `vault_settings` † | `doorbell_enabled` | `WC:826` | `WC:831` | **nunca** | Não — e não precisa (ver §8) |
| `doorbell_knocks` † | `id_pub` do contato | `ME:1043,1058,1070` | `ME:1074` | **nunca** | Não |

† Namespaces de T4.17. As citações de linha dos dois estão na **árvore de trabalho** de 2026-09-18,
não em `22aa7b0` como o resto desta tabela — mesma ressalva da seção 7. Detalhe na **seção 8**.

Nenhum namespace de `opaque_blobs` existe no vault decoy recém-criado: `AVS:56-57`
chama `createSetupMedia` e `seedDecoy`, e `seedDecoy` (`AVS:236-288`) só usa
`putFile`, `insertMessage` e `DecoyFactory.create`; `DF:10-29` só usa `putMeta`,
`putPairEvidence` e `putContact`. Consequência para o gate 2 na seção 5.

### 2.2 Detalhe por namespace

#### `group_pending`
- **Formato do valor:** 1 byte, `byteArrayOf(1)` (`ME:139`). Sentinela; o conteúdo nunca é lido.
- **Escrita:** `ME:139`, dentro de `createGroup`, junto do `putGroup` inicial.
- **Leitura:** bloqueia envio de aplicação (`ME:229`), bloqueia `activeGroup` (`ME:287`),
  exclui o grupo da varredura MLS (`ME:449` via SQL e `ME:455`), condiciona
  `acceptKeyPackage` (`ME:584`) e `acceptCommit` (`ME:636`), decide o ramo de
  `leaveGroup` (`ME:152`) e produz `GroupStatus.PENDING` na UI (`WC:795`).
- **Remoção:** `ME:600` quando o último KeyPackage chega e o Welcome é enfileirado;
  `ME:198` quando o coordenador cancela o grupo pendente.
- **Ciclo de vida:** existe só entre a criação do grupo e a formação efetiva do grupo MLS.

#### `group_left`
- **Formato do valor:** 1 byte, `byteArrayOf(1)` (`ME:161`, `ME:167`, `ME:171`, `ME:199`).
- **Escrita:** em cada um dos três caminhos de `leaveGroup` (coordenador sem restantes,
  coordenador com transferência, membro comum) e no cancelamento de grupo pendente.
- **Leitura:** `ME:230`, `ME:288`, `ME:455`, `ME:636`; no SQL de `activeGroupCount`
  (`ME:653`) e da varredura MLS (`ME:449`); `WC:793` para `GroupStatus.LEFT`.
- **Remoção:** **nenhuma**. É uma lápide permanente; o `mls_state` correspondente é zerado
  (`ME:162`, `ME:168`, `ME:172`, `ME:200`), mas a linha de grupo e a lápide ficam.
- **Consequência:** o custo em bytes de um grupo abandonado nunca é devolvido ao reserve.

#### `group_error`
- **Formato do valor:** `"<senderId>:<ControlRejectionReason.name>"` em UTF-8 (`ME:537`),
  ex.: `"ab..ef:KEY_PACKAGE_QUOTA"`. O `senderId` tem 64 hex (`CD:576`).
- **Escrita:** `ME:537`, ao processar um `ControlRejected` autenticado referente a um
  controle de grupo ainda pendente na outbox.
- **Leitura:** `ME:228` bloqueia envio no grupo; `ME:216` compara o prefixo (o membro
  culpado) durante `removeMemberInternal`; `WC:794` produz `GroupStatus.FAILED`.
- **Remoção:** `ME:216` quando o membro culpado é removido do grupo; `ME:198` no
  cancelamento do grupo pendente.

#### `group_request`
- **Formato do valor:** `pack(groupId, peerId)` (`ME:142`) — dois campos de
  `[int32 big-endian tamanho][bytes]` produzidos por `ME:791-793` e lidos por `ME:794-796`.
- **Chave:** nonce de requisição, um `UUID.randomUUID().toString()` (`ME:141` via `ME:56`).
- **Escrita:** `ME:142`, um por convidado, em `createGroup`.
- **Leitura:** `ME:257` (para descobrir o grupo ao registrar `outbox_control`),
  `ME:579` (vínculo requisição↔remetente em `acceptKeyPackage`), listagens em
  `ME:191` e `ME:593`.
- **Remoção:** `ME:597` quando o convite é emitido, `ME:194` no cancelamento,
  `ME:540` quando o par rejeita o controle.

#### `group_packages`
- **Formato do valor:** bytes brutos do KeyPackage MLS recebido (`envelope.packageBytes`,
  `ME:585`); tamanho limitado por `CD:570` (16 MiB) como qualquer blob.
- **Chave:** `"$groupId:$sender"` (`ME:585`).
- **Escrita:** `ME:585`, ao receber `Envelope.KeyPackage` de um convidado.
- **Leitura:** `ME:587`, que só prossegue quando todos os convidados já responderam.
- **Remoção:** `ME:598` (após o `add` MLS e a emissão dos convites) e `ME:197`
  (cancelamento do grupo pendente).

#### `key_packages`
- **Formato do valor:** `pack(senderId, kp.state, createdMillis)` (`ME:554`) — o estado
  privado do KeyPackage local mais o instante de criação em decimal ASCII.
- **Chave:** o `requestId` recebido do coordenador.
- **Escrita:** `ME:554`, ao atender um `KeyPackageRequest` admitido.
- **Leitura:** `ME:550` (rejeita reuso do mesmo `requestId`), `ME:613` (consome no
  `GroupInvite`), `ME:659` (varredura de expiração).
- **Remoção:** `ME:627` (join bem-sucedido), `ME:619` (TTL vencido no momento do convite),
  `ME:663` (TTL vencido na varredura), `ME:539` (rejeição de controle).
- **TTL:** 24 h — `ME:50` (`packageTtl`), comparado em `ME:618` e `ME:663`.

#### `outbox_control`
- **Formato do valor:** `pack(groupId, requestNonce)` (`ME:267`); qualquer um dos dois
  campos pode ser vazio (`ME:253-264`).
- **Chave:** `"${envelope.id}:$peer"` (`ME:267`) — casa com o id do `PendingFrame` (`ME:276`).
- **Escrita:** `ME:265-268`, apenas para `GroupInvite`, `GroupCommit`, `GroupLeave`,
  `KeyPackageRequest` e `KeyPackage`.
- **Leitura:** `ME:526`, para validar que um `ControlRejected` se refere a um controle
  realmente pendente; listagem em `ME:181`.
- **Remoção:** `ME:519` (ACK), `ME:535` (rejeição processada), `ME:187` (cancelamento).
- **Papel:** é o que impede um par autenticado de "rejeitar" entrega de aplicação —
  só há linha para controles (`ME:527` documenta isso no código).

#### `outbox_lanes`
- **Formato do valor:** nome da lane em UTF-8 (`ME:277`). Valores possíveis, de `MP:25-35`:
  `direct`, `group:<groupId>`, `request:<requestId>`, `evidence`, `rejection`.
- **Chave:** `"$messageId:$peer"` (`ME:277`).
- **Escrita:** `ME:277`, em toda chamada de `queue` (`ME:272`).
- **Leitura:** apenas pelo SQL `MessagingPolicy.readyQuery`, em `MP:14` e `MP:17`, onde
  a ausência da linha equivale a `direct` via `COALESCE` (`MP:19`).
- **Remoção:** `ME:518` (ACK), `ME:534` (rejeição), `ME:186` (cancelamento).
- **Papel:** serializa a outbox por (destino, lane): só o item mais antigo de cada par
  fica elegível (`MP:15-21`).

#### `attempts`
- **Formato do valor:** `now()` em decimal ASCII com zero-padding para 16 caracteres
  (`ME:339`), de modo que a ordenação lexicográfica de `a.value` em `MP:22` seja a
  ordenação temporal.
- **Chave:** o mesmo `pendingId` (`"$messageId:$peer"`).
- **Escrita:** `ME:339`, imediatamente antes de disparar a tentativa de envio.
- **Leitura:** só pelo `ORDER BY a.value` de `MP:22` (join em `MP:13`) — quem nunca foi
  tentado tem `NULL` e vem primeiro.
- **Remoção:** `ME:517` (ACK), `ME:534` (rejeição), `ME:186` (cancelamento).

#### `accepted_ids`
- **Formato do valor:** id do remetente em UTF-8, 64 hex (`ME:495`).
- **Chave:** `envelope.id` (UUID do envelope recebido).
- **Escrita:** `ME:495`, uma vez por envelope aceito (ou rejeitado com `ControlRejected`).
- **Leitura:** `ME:485`; se existir, o envelope repetido só é aceito se vier do **mesmo**
  remetente (`ME:487`) — é a defesa contra um par reivindicar o id de outro.
- **Remoção:** **nenhuma no código lido**. Cresce monotonicamente com o número de
  mensagens recebidas; é um dos consumidores do reserve fixo de 256 MiB.

#### `rejected_ids`
- **Formato do valor:** o pacote wire completo do `ControlRejected` já cifrado para o
  remetente (o retorno de `queueSignal`, `ME:269` → `ME:492-493`).
- **Chave:** `envelope.id` do controle rejeitado.
- **Escrita:** `ME:493`.
- **Leitura:** `ME:486`, para reenviar a mesma rejeição em uma retransmissão.
- **Remoção:** **nenhuma no código lido**.

#### `receipts`
- **Formato do valor:** três casos, decididos no mesmo `?:` de `ME:497`:
  (a) o pacote wire do `ControlRejected` **já cifrado**, quando `rejected` não é nulo —
  vindo de `ME:486` (rejeição anterior recarregada) ou de `ME:492-493` (rejeição nova);
  (b) **um array vazio**, quando o envelope recebido era ele mesmo um `Ack`;
  (c) o pacote wire do `Ack` cifrado (`ME:498-499`).
  `ME:408` trata o array vazio como "sem resposta" (`takeIf { it.isNotEmpty() }`).
- **Chave:** SHA-256 do wire recebido, em hex minúsculo (`ME:407`, `ME:797`).
- **Escrita:** `ME:501`.
- **Leitura:** `ME:408`, primeira linha de `accept` — é o cache de idempotência que
  responde retransmissões sem reprocessar.
- **Remoção:** **nenhuma no código lido**. Cresce com cada wire distinto recebido.

#### `failed_controls`
- **Formato do valor:** `ControlRejectionReason.name` em UTF-8 (`ME:536`).
- **Chave:** o `pendingId` do controle que falhou.
- **Escrita:** `ME:536`.
- **Leitura:** **nenhuma**. Uma busca por `"failed_controls"` em `app/src` e `core/src`
  só encontra `ME:536`. É diagnóstico write-only.
- **Remoção:** **nenhuma**.
- **Observação honesta:** ou vira leitura real (diagnóstico na UI/relatório) ou deveria
  ser removido; hoje é armazenamento permanente sem consumidor.

#### `evidence_gossip`
- **Formato do valor:** `pack(EnvelopeCodec.encode(Envelope.Evidence), destinatários)`
  (`ME:698`), onde os destinatários são ids de 64 hex separados por `\n` (`ME:695`).
  Reescrito com a lista encurtada a cada rodada (`ME:716`).
- **Chave:** `batch.id`, o UUID do envelope `Evidence` do lote (`ME:697-698`).
- **Escrita:** `ME:698` (ao importar provas novas com `gossip = true`), `ME:716`
  (persistência do restante).
- **Leitura:** `ME:705` escolhe o menor `k` pendente; `ME:707` carrega o valor.
- **Remoção:** `ME:715`, quando a lista de destinatários se esvazia.
- **Ciclo de vida:** consumido pelo `outboxLoop` via `flushEvidenceGossip` (`ME:328`),
  4 destinatários por tick (`ME:713`).

### 2.3 Namespace que **não** é de produção

`signal` aparece apenas em `app/src/androidTest/kotlin/dev/mx3/nomessages/storage/AndroidVaultStorageTest.kt:185`
e `:194`, como fixture de isolamento real/decoy. Nenhum código de produção o grava ou lê.

---

## 3. Chaves da tabela `meta`

| Chave | Formato | Escreve | Lê | Apaga | No decoy? |
|---|---|---|---|---|---|
| `identity` | `PairingIdentity.export()` (binário) | `WC:207`, `WC:483`, `ME:303`, `DF:19` | `WC:196` | nunca | **Sim** (`DF:19`) |
| `onion_seed` | 32 bytes aleatórios | `WC:204`, `DF:20` | `WC:202` | nunca | **Sim** (`DF:20`) |
| `display_name` | UTF-8, ≤ 80 caracteres (`WC:151`) | `WC:156` | `WC:784` | nunca | **Não** |
| `lock_timeout` | decimal US-ASCII, 5..30 | `WC:581` | `WC:221` | nunca | Não |
| `bridges` | UTF-8, ≤ 16 384 chars, ≤ 32 linhas | `WC:587` | `WC:222` | nunca | Não |
| `pairing_consumed` | US-ASCII, linhas de 64 hex, ≤ 4096 | `WC:484` | `WC:209` | nunca | Não |
| `outbox_sequence` | decimal ASCII monotônico | `ME:275` | `ME:273` | nunca | Não |
| `tor_guard_state` | binário `WFTOR001` + entradas + SHA-256 | `WC:356` | `WC:224` | nunca | Não |
| `doorbell_seed` † | 32 bytes aleatórios | `WC:787` | `WC:785` | nunca | **Sim, mas não pelo `DecoyFactory`** — ver nota |

Notas por chave:

- **`identity`** — reescrito em **toda** transação de `MessagingEngine.atomic` (`ME:303`)
  porque o estado do duplo ratchet libsignal avança a cada mensagem; e por
  `persistIdentity` (`WC:483`) em cada passo do pareamento. Restaurado em `WC:197`;
  em caso de rollback, `ME:308` devolve o snapshot anterior em memória.
- **`onion_seed`** — gravado uma única vez, só se ausente (`WC:203-204`). O endereço
  `.onion` é derivado dele em `WC:205` e nunca é persistido.
- **`display_name`** — gravado **apenas no setup** (`WC:156`), que roda com o vault real
  destravado. O decoy nunca recebe essa chave, então `WC:784` devolve string vazia lá.
  Ver seção 5.
- **`lock_timeout`** — lido com `coerceIn(5, 30)` e default 30 (`WC:221`); a escrita já
  valida `5..30` (`WC:580`). Usado como `segundos * 1000` no lock de background (`WC:377`).
- **`bridges`** — string crua das pontes; quebrada em linhas e passada a `tor.start`
  em `WC:241`.
- **`pairing_consumed`** — os nonces de oferta já consumidos, para impedir replay de QR
  entre sessões. Carregado com `take(4096)` (`WC:210`) e salvo com `takeLastBounded(4096)`
  (`WC:484`, `WC:815`), ou seja, **a janela é limitada e nonces antigos caem fora**.
- **`outbox_sequence`** — `maxOf(now(), anterior + 1)` (`ME:274`), garantindo
  `created_at` estritamente crescente mesmo com relógio recuado; é o que a ordenação
  de `MP:20` e `MP:22` assume.
- **`tor_guard_state`** — checkpoint dos guards Arti, capturado por `TorStateSnapshot.capture`
  e só gravado quando não-nulo (`WC:355`); falha de capacidade preserva o snapshot
  anterior em vez de apagá-lo (`WC:357`).
- **`doorbell_seed`** † — seed do onion da campainha (T4.17). Cunhada **na primeira utilização**,
  não no setup: `doorbellSeed()` lê a chave e, se ausente, grava 32 bytes novos (`WC:784-788`).
  Seed **própria**, distinta de `onion_seed`, de propósito: uma única seed para os dois endereços
  permitiria a quem conhece um deles confirmar que pertencem ao mesmo aparelho. Fica em `meta` e não
  em `opaque_blobs` por ser identidade de longa duração, na mesma família de `identity`/`onion_seed`.
  O **cofre-isca também a tem**, mas pelo mesmo caminho preguiçoso do cofre real e não por escrita do
  `DecoyFactory` — o isca a cunha na primeira vez em que ela é necessária. Não é distinguidor: o que
  decide a existência da chave é aquele cofre já ter chegado a essa função, e os dois chegam pelo
  mesmo código, sem ramo por `VaultSlot`.

Nenhuma dessas chaves tem caminho de remoção nos arquivos lidos: elas desaparecem apenas
com o arquivo de banco inteiro (import de vault, reset de pânico, desinstalação).

---

## 4. Limites numéricos de runtime

### 4.1 Mensageria e envelopes

| Limite | Valor | Definido em |
|---|---|---|
| TTL de KeyPackage | 24 h | `ME:50` (aplicado em `ME:618`, `ME:663`) |
| Tamanho máximo de anexo (envio) | 8 MiB | `ME:94` |
| Tamanho máximo de anexo (leitura) | 8 MiB | `ME:113` |
| Tamanho máximo de anexo (recebimento) | 8 MiB | `ME:735` |
| Buffer de anexo na UI | 8 MiB | `WC:826` (`MAX_ATTACHMENT`), usado em `WC:649`, `WC:655` |
| Bloco de leitura do anexo | 64 KiB | `WC:656` |
| Nome de grupo | ≤ 128 caracteres | `ME:127` |
| Grupos ativos | < 128 | `ME:128` (criação), `ME:606` (convite recebido) |
| Membros por grupo | 1..100 | `ME:722`, `CD:545` |
| Provas por envelope `Evidence` | ≤ 4950 | `ME:680` |
| Lote de provas enviado | 256 por envelope | `ME:123`, `ME:696` |
| Arestas do grafo de pareamento | < 16 384 | `ME:689` |
| Destinatários de gossip por tick | 4 | `ME:713` |
| Campo de `pack`/`unpack` | 0..16 MiB | `ME:795` |
| Contatos elegíveis por tentativa | 1024 | `MP:10` (`maxContacts`), usado em `ME:429`, `ME:445`, `WC:435` |
| Grupos candidatos por varredura MLS | 128 | `ME:449` (`LIMIT 128`) |

### 4.2 Outbox e transporte

| Limite | Valor | Definido em |
|---|---|---|
| Envios simultâneos | 4 | `ME:335` |
| Intervalo mínimo entre tentativas do mesmo item | 30 s | `ME:337` |
| Tick do `outboxLoop` | 2 s | `ME:371` |
| Tick do reaper de streams | 1 s | `ME:83` |
| Prazo de stream após envio completo | 60 s | `ME:357` |
| Prazo de stream em recepção | 60 s | `ME:385` |
| Memória de streams encerrados | 120 s | `ME:72` |
| Entradas de streams encerrados | 4096 | `ME:767` |
| Timeout de leitura do canal de entrada | 1 s | `ME:383` |
| Expiração de montagem de frames incompleta | 60 s | `ME:381` |
| Montagens simultâneas | < 16 | `ME:388` |
| Bytes por montagem | ≤ 16 MiB | `ME:392` |
| Bytes somados de todas as montagens | ≤ 32 MiB | `ME:392` |
| Itens elegíveis retornados por varredura | 1000 | `MP:22` (`LIMIT 1000`) |

### 4.3 Tentativa cega de decifração (trial decryption)

| Limite | Valor | Definido em |
|---|---|---|
| Orçamento de tentativas, wire ≤ 64 KiB | 64 candidatos | `MP:40` |
| Orçamento de tentativas, wire > 64 KiB | 8 candidatos | `MP:40` |
| Cursores simultâneos | 16 | `ME:413`, `ME:432` |
| Memória total de cursores | 24 MiB | `ME:413`, `ME:432` |
| Memória máxima de um cursor | 256 + 1024×256 B | `MP:37` (`maximumCursorBytes`) |
| Fórmula de memória por cursor | 256 + Σ(128 + 2×len) | `MP:38` |
| TTL de cursor, wire > 64 KiB | 15 min | `ME:411` |
| TTL de cursor, wire ≤ 64 KiB | 120 s | `ME:411` |
| Balde de tokens para pares desconhecidos | 4 tokens | `MP:46`, `MP:53` |
| Recarga do balde | 2 tokens/s | `MP:53` |
| Cota global de KeyPackages admitidos | 128 | `MP:42` |
| Cota de KeyPackages por par | 4 | `MP:42` |

### 4.4 Banco de dados (`ChatDatabase`)

| Limite | Valor | Definido em |
|---|---|---|
| Bloco do reserve | 1 MiB | `CD:568` (`RESERVE_CHUNK_BYTES`) |
| Sobrecarga estimada por escrita | 8 KiB | `CD:569` |
| Blob máximo | 16 MiB | `CD:570` |
| Estimativa máxima de escrita | 17 MiB | `CD:571` |
| Texto exibível (alias, nome de grupo, nome de arquivo) | 256 | `CD:572` |
| Chave de armazenamento | 512 | `CD:573` |
| Limite de consulta (`listMessages`, outbox) | 1000 | `CD:574`, aplicado em `CD:173`, `CD:217`, `CD:231` |
| SQL bruto | 64 KiB | `CD:575` |
| Página de mensagens (default) | 100 | `CD:171` |
| `listPendingFrames` (default) | 100 | `CD:216` |
| `listPendingFrameIds` (default) | 1000 | `CD:230` |
| Regex de id de identidade | `[0-9a-f]{64}` | `CD:576` |
| Regex de endereço onion | `[a-z2-7]{56}\.onion` | `CD:577` |
| Regex de namespace | `[a-z0-9][a-z0-9._-]{0,63}` | `CD:578` |
| Regex de nome de arquivo | `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` | `CD:579` |
| Degraus de preenchimento do reserve | 300 / 24 páginas, piso 512 B | `CD:398-400`, `CD:411` |

### 4.5 Armazenamento e vault (`AndroidVaultStorage`)

| Limite | Valor | Definido em |
|---|---|---|
| Capacidade padrão do banco | 256 MiB | `AVS:331` (`DEFAULT_CAPACITY_MIB`) |
| Capacidade mínima | 16 MiB | `AVS:332` |
| Capacidade máxima | 512 MiB | `AVS:333` |
| Tamanho de página | 4096 B | `AVS:334` |
| Versão de esquema | **2** (era `1` em `22aa7b0`) | `AVS:335` em `22aa7b0`; `AVS:416` na árvore de trabalho — ver **seção 7** |
| Deslocamento do epoch no header | 18 | `AVS:336` |
| Contatos sintéticos do decoy | 3..5 (usa 4) | `DF:11`, `AVS:239` |
| Fixtures de mídia do decoy | 2 | `AVS:339-350` |

### 4.6 Controller e ciclo de vida

| Limite | Valor | Definido em |
|---|---|---|
| Nome do usuário no setup | ≤ 80 | `WC:151` |
| Alias na confirmação de pareamento | 1..80 | `WC:467` |
| Validade do QR de oferta na UI | 120 s | `WC:427` |
| Nonces de pareamento consumidos (carga) | 4096 | `WC:210` |
| Nonces de pareamento consumidos (gravação) | 4096 | `WC:484`, `WC:815` |
| Timeout de lock por background | 5..30 s | `WC:221`, `WC:580`; aplicado em `WC:377` |
| Período de persistência do estado Tor | 60 s | `WC:246` |
| Pontes: tamanho | ≤ 16 384 caracteres | `WC:585` |
| Pontes: linhas | ≤ 32 | `WC:586` |
| Nome de anexo truncado | 160 | `WC:669` |
| Corpo de mensagem exibido | 4000 | `WC:779` |
| Prévia de conversa | 120 | `WC:809` |
| Snapshot de estado Tor | 4 MiB | `core/src/main/kotlin/dev/mx3/nomessages/core/nativebridge/TorStateSnapshot.kt:18` |
| Profundidade do JSON de guards | 32 níveis / 100 000 nós | `core/.../TorStateSnapshot.kt:200` |

---

## 5. Consequências observadas (não corrigidas aqui)

Registradas para quem executar T3.5 (gate 2) e T4.1; nenhuma delas foi alterada por
esta tarefa.

1. **Assimetria estrutural real/decoy em `opaque_blobs`.** O decoy nasce com zero linhas
   em `opaque_blobs` (`AVS:236-288`, `DF:10-29`), enquanto o vault real acumula
   `accepted_ids`, `receipts`, `rejected_ids` e `failed_controls` sem nenhum caminho de
   remoção. Os arquivos `.db` continuam do mesmo tamanho em bytes — a alocação é fixa
   (`AVS:61`, `AVS:93`) e o crescimento consome o `storage_reserve` (`CD:435-456`) —
   mas o **número de páginas livres** diverge. O gate 2 mede `Files.size`, não
   `freelist_count`; a medição de T3.5 deveria registrar ambos.
2. **`display_name` ausente no decoy** (`WC:156` só roda no setup do vault real). Ao
   destravar com a senha de pânico, `WC:784` devolve string vazia. É um marcador
   observável de qual slot está aberto e deveria ser semeado em `seedDecoy`.
3. **`bridges`, `tor_guard_state` e `lock_timeout` ausentes no decoy — divergência
   observável na rede, não só na UI.** `activate` lê essas chaves do vault **atualmente
   aberto** (`WC:195`, `val database = db()`): `WC:222` (`bridges`), `WC:224`
   (`tor_guard_state`) e `WC:221` (`lock_timeout`). As escritas correspondentes —
   `WC:587` (`putMeta("bridges", …)`), `WC:356` (`putMeta(TorStateSnapshot.META_KEY, …)`)
   e `WC:581` (`putMeta("lock_timeout", …)`) — só rodam com o vault real em uso, e
   `seedDecoy` (`AVS:236-288`) / `DecoyFactory.create` (`DF:10-29`) gravam apenas
   `identity` e `onion_seed` (`DF:19-20`). Consequência: ao destravar com a senha de
   pânico, `WC:222` devolve string vazia e `WC:224` devolve `null`, então `WC:241`
   sobe o Tor **sem pontes e com bootstrap de guards do zero**, enquanto o vault real
   sobe com as pontes configuradas e restaurando o checkpoint salvo. Um observador de
   rede — ou o adversário que coagiu o desbloqueio e cronometra o bootstrap — distingue
   os dois slots pelo padrão de conexão, sem tocar no disco. `lock_timeout` some junto
   (cai para o default 30 s de `WC:221`), o que é menos grave mas é outro marcador.
   Ação sugerida: `seedDecoy` deveria semear `bridges`, `lock_timeout` e um
   `tor_guard_state` plausível junto com `display_name`; e T3.5 deveria medir o padrão
   de bootstrap dos dois slots, não só `Files.size`.
4. **`failed_controls` não tem leitor** (`ME:536` é a única ocorrência).
5. **`group_left` é lápide permanente**; grupos abandonados nunca liberam as páginas
   que ocuparam.
6. **`pairing_consumed` é uma janela de 4096**, não um conjunto completo: um QR cujo
   nonce saiu da janela volta a ser aceitável entre sessões. Relevante para o gate 8
   ("replay após restart") em T3.3.

---

## 6. Identificadores de protocolo

Os identificadores de protocolo/formato abaixo são formatos fixados em testes de
known-answer (KAT), round-trip ou paridade de corpora: mudar os bytes/strings quebraria
compatibilidade de dados já gravados (cofres, envelopes) ou os vetores de teste que os
verificam, por isso permanecem estáveis entre versões.

### 6.1 Nomes de esquema e de arquivo persistidos

| Identificador | Valor | Onde | Por quê |
|---|---|---|---|
| Nomes de arquivo do cofre | `header.bin`, `real.db`, `decoy.db` | `core/.../vault/StrictZip.kt`, `core/.../vault/VaultArchive.kt`, `core/.../vault/VaultManager.kt`, `app/.../storage/AndroidVaultStorage.kt` | Nomes de entrada do arquivo ZIP estrito do cofre e dos bancos SQLite extraídos; formam o manifesto validado por `StrictZip`/`VaultArchive`. |
| Manifesto do arquivo do cofre | `VaultArchive.MANIFEST` (constante de nome de arquivo) | `core/.../vault/VaultArchive.kt` | Mesma razão acima: nome de entrada fixo no ZIP estrito. |
| Tabela e colunas SQL | `opaque_blobs(namespace, k, value)` | `app/src/main/kotlin/dev/mx3/nomessages/storage/AndroidVaultStorage.kt:216`, `ChatDatabase.kt` | Nome de tabela/colunas de um banco SQLite já persistido em dispositivos; renomear exigiria migração de esquema, fora do escopo. |

### 6.2 Identificadores de formato de fio

| Identificador | Valor | Onde |
|---|---|---|
| Magic do envelope de mensagens | bytes `'N','M','F','M'` ("NMFM") | `core/src/main/kotlin/dev/mx3/nomessages/core/messaging/EnvelopeCodec.kt:12` (fonte única: a mesma `val magic` é usada em `encode` e em `decode`, linha 76) |
| Magic do `VaultHeader` | `0x4E4D5347` ("NMSG") | `core/src/main/kotlin/dev/mx3/nomessages/core/vault/VaultHeader.kt:12` (fonte única: a mesma `MAGIC` é usada em `requireStructure`, `create` e `unlock`; big-endian via `ByteBuffer.putInt`/`buffer.int`) |
| Prefixo do QR de oferta de pareamento | `OFFER_PREFIX = "nomessages:1:"` (13 bytes) | `core/src/main/kotlin/dev/mx3/nomessages/core/protocol/Pairing.kt` — bem abaixo do teto rígido de 8192 bytes de `Wire.kt::fromQr` e do orçamento prático de ~2880 bytes do QR40-L |
| Prefixo do QR de confirmação de pareamento | `CONFIRM_PREFIX = "nomessages-confirm:1:"` | `core/src/main/kotlin/dev/mx3/nomessages/core/protocol/Pairing.kt` |
| Domain separator CBOR — transcript | `"nomessages-transcript-v1"` | `core/src/main/kotlin/dev/mx3/nomessages/core/protocol/Pairing.kt` (em `stage`) |
| Domain separator CBOR — oferta | `"nomessages-offer-v1"` | `core/src/main/kotlin/dev/mx3/nomessages/core/protocol/Pairing.kt` (`Offer.canonical()`); coberto por `ProtocolTest.kt::rejectsSignedOfferBundleSubstitution`, que usa o mesmo literal |
| Domain separator CBOR — evidência de pareamento | `"nomessages-pair-evidence-v1"` | `core/src/main/kotlin/dev/mx3/nomessages/core/protocol/Pairing.kt` (`PairStatement.canonical()`) |

---

## 7. Esquema do banco do cofre: coluna `messages.forwarded` e migração v1 → v2 (2026-09-17)

> **Âncora desta seção:** ao contrário do resto do documento, as citações `AVS:`, `CD:` e `ME:` abaixo foram
> lidas na **árvore de trabalho** de 2026-09-17 (T4.12), não em `22aa7b0` — a coluna descrita aqui
> não existe naquele commit. Quando esta mudança for commitada, reconfira as linhas contra o novo
> commit.

### 7.1 A coluna

A tabela `messages` do cofre passou de seis para sete colunas:

```sql
-- v1 (congelado em createSchemaV1, AVS:238)
CREATE TABLE messages(id TEXT PRIMARY KEY NOT NULL, peer_or_group TEXT NOT NULL,
                      direction INTEGER NOT NULL CHECK(direction BETWEEN 0 AND 1),
                      ts INTEGER NOT NULL, body BLOB NOT NULL,
                      status INTEGER NOT NULL CHECK(status BETWEEN 0 AND 4)) WITHOUT ROWID

-- v2 acrescenta, e nada mais (AVS:212)
ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN (0,1))
```

| Item | Valor | Onde |
|---|---|---|
| Nome da coluna | `forwarded` | `AVS:212` |
| Tipo / restrições | `INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN (0,1))` | `AVS:212` |
| Domínio efetivo | `0` = normal, `1` = encaminhada | `CD:230`, `CD:532` |
| Campo Kotlin correspondente | `MessageRecord.forwarded: Boolean = false` | `StorageModels.kt` |
| Escrita | `insertMessage` (`INSERT INTO messages(id,peer_or_group,direction,ts,body,status,forwarded)`) | `CD:220`, `CD:230` |
| Leitura | `getMessage` e as duas SQLs de `listMessages` (primeira página e paginada), coluna de índice **6** | `CD:189`, `CD:204`, `CD:207`, `CD:532` |
| Índice | nenhum novo; `messages_chat_time(peer_or_group, ts DESC, id DESC)` continua o único | `AVS:239` |

**Por que existe uma coluna, já que o bit também está no envelope.** O `body` é o envelope
serializado e cifrado pelo cofre; a lista de conversa teria de decodificar cada envelope só para
saber se desenha a etiqueta "Encaminhada". A coluna é uma projeção do mesmo bit para leitura barata.
As duas gravações são sempre feitas juntas, por `MessagingEngine.envelopeForwarded` (`ME:280-281`),
que devolve o bit para `Envelope.Text`/`Envelope.Attachment` e `false` para todo envelope de
controle — de modo que a coluna nunca diverge do corpo. Vale para os dois sentidos: envio e
recebimento.

### 7.2 A migração

| Item | Valor | Onde |
|---|---|---|
| Constante | `SCHEMA_VERSION = 2` | `AVS:493` |
| Onde fica a versão em disco | `PRAGMA user_version`, lido por `readUserVersion` | `AVS:189-193`, `AVS:231-233` |
| Quem migra | `migrate(database)`, chamada por **`initialize()` e `open()`** | `AVS:54`, `AVS:75`, `AVS:220` |
| Um degrau por versão | `migrateStep(database, from, to)` — `1 -> createSchemaV1`, `2 -> ALTER TABLE ...`, `else -> error(...)` | `AVS:252-273` |
| Transação | degraus + gravação do `user_version` dentro de uma única `beginTransaction`/`setTransactionSuccessful` | `AVS:228-241` |
| Guarda "velho demais" | `require(version >= 0)`; retorno imediato se já estiver na versão | `AVS:222`, `AVS:227` |
| Guarda "novo demais" | `check(version <= SCHEMA_VERSION)` com mensagem própria, **antes** da transação e de tocar em qualquer tabela | `AVS:223-226` |
| Reserva de páginas | `reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)` no degrau v1→v2 — mesma política de `ChatDatabase.consumeReserve` | `AVS:266`, `AVS:290-308`, `CD:488-509` |
| Verificação pós-escrita | relê `user_version` e falha se não persistiu | `AVS:232-234` |
| Verificação pós-migração | `max_page_count` ainda é `maxPageCount`; e `open()` revalida `Files.size(...) == databaseBytes` | `AVS:242-244`, `AVS:76` |

`migrate` é um replay de degraus, não um salto: um cofre novo entra com `user_version = 0` e roda
todos (`migrateStep(.., to = 1)` cria a v1, `migrateStep(.., to = 2)` aplica o `ALTER TABLE`); um
cofre existente roda só a cauda que falta. O `ALTER TABLE` foi mantido **fora** de `createSchemaV1`
de propósito — assim a lista de statements de v1 continua sendo um registro fiel do que v1 era, e o
caminho de atualização é o mesmo que um cofre já criado percorreria. O `NOT NULL DEFAULT 0` é o que
faz o backfill: toda linha escrita antes da coluna existir passa a valer `0`, que é exatamente como
um envelope de wire version 1 decodifica (`forwarded = false`).

**A migração ao abrir (corrigido em 2026-09-17).** Até esta data `open()` **não** migrava: chamava
`requireSchema`, que exigia `user_version == SCHEMA_VERSION` e recusava qualquer outro valor, de modo
que um cofre criado por um build v1 falhava ao abrir. A limitação foi registrada como "conhecida e
assumida" sob a premissa de que nenhum cofre real existia — premissa que caiu quando um Galaxy
Note10+ com Android 12 recusou o cofre do usuário depois de uma atualização do app. Hoje `open()`
chama `migrate(raw)` (`AVS:75`), antes da verificação de tamanho fixo e antes de `ChatDatabase`
existir, e `requireSchema` foi **removida** (ficou sem chamador). Custo no caso comum: uma leitura de
`PRAGMA user_version` e retorno imediato.

Consequências que valem fixar:

- **Nunca cresce o arquivo.** Um degrau que precise de páginas as saca de `storage_reserve` via
  `reserveMigrationPages` (`AVS:290-308`), espelhando `ChatDatabase.consumeReserve` — cópia, e não
  chamada, porque na hora da migração o `ChatDatabase` ainda não existe. Folga abaixo de
  `max_page_count` conta como disponível, o que é o que mantém o caminho de `initialize()`
  funcionando (lá `fillReserveToCapacity` só roda *depois* da migração, `AVS:55`).
- **Banco mais novo é recusado com nome.** `Vault database was created by a newer app version
  (schema version N, this build supports up to 2)`, antes de qualquer transação.
- **Sem ramo real/isca.** `migrate`/`migrateStep`/`reserveMigrationPages` não recebem `VaultSlot`.
  A primeira abertura de um banco ainda não migrado é mais lenta (uma transação extra); as seguintes
  caem no retorno imediato. Não é distinguidor: o custo depende só de *aquele arquivo* já ter sido
  migrado, e os dois cofres são migrados independentemente, cada um na primeira vez em que ele
  próprio é aberto, pelo mesmo código.

Coberto por `vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened` e
`vaultFromANewerAppVersionIsRejectedWithAnExplicitMessage`
(`app/src/androidTest/kotlin/dev/mx3/nomessages/storage/AndroidVaultStorageTest.kt`). Registrado
também em `docs/security-model.md`, `docs/development/storage-api.md` e
`docs/changes/AndroidVaultStorage.kt.md`.

### 7.3 Efeito no cofre-isca

`seedDecoy` marca **exatamente uma** mensagem como encaminhada: a segunda mensagem (índice 1,
portanto **outgoing**) da primeira conversa — "Sim, levo o pão." —, selecionada por
`FORWARDED_DECOY_CHAT = 0` / `FORWARDED_DECOY_MESSAGE = 1` (`AVS:521-522`, aplicados em `AVS:400`).
A gravação passa pelo mesmo par envelope+coluna de um envio real (`AVS:407`, `AVS:409`), de modo que
o corpo decodificado e a coluna concordam.

**Por quê:** um cofre em que nenhuma mensagem foi jamais encaminhada é um distinguidor da mesma
família dos já catalogados na seção 5 — o isca precisa poder exibir todo estado de UI que o cofre
real exibe, incluindo a etiqueta "Encaminhada". A mensagem escolhida é outgoing (a direção que o
usuário produz ao encaminhar), não é a cauda não lida de nenhum chat
(`UNREAD_DECOY_CHATS = {0, 2}`, `AVS:509`) e não é um anexo, então marcá-la não perturba nenhuma das
outras propriedades do isca — badges de não lidas, timestamps e fixtures de mídia ficam intactos.

---

## 8. Campainha: registros, esquema v4 e ids de notificação (T4.17, 2026-09-18)

> **Âncora desta seção:** como na seção 7, as citações `AVS:`, `CD:`, `ME:`, `WC:` e `PN:` abaixo
> foram lidas na **árvore de trabalho** de 2026-09-18, não em `22aa7b0`. Quando esta mudança for
> commitada, reconfira as linhas contra o novo commit. `PN:` é
> `app/src/main/kotlin/dev/mx3/nomessages/runtime/PrivacyNotifications.kt`.

### 8.1 `vault_settings` / `doorbell_enabled`

| Item | Valor | Onde |
|---|---|---|
| Namespace | `vault_settings` — preferências de usuário **por cofre** | `WC:1630` |
| Chave | `doorbell_enabled` | `WC:1631` |
| Formato | 1 byte: `1` ligado, qualquer outro valor desligado | `WC:826`, `WC:831-832` |
| Default | **LIGADO** — a **ausência** da chave devolve ligado | `WC:831` |
| Escreve | `setDoorbellEnabled` | `WC:826` |
| Lê | `doorbellEnabled(database)`, e `isDoorbellEnabled()` pelo cofre ativo | `WC:830-832`, `WC:811` |
| Apaga | nunca | — |

**Por que o default é ligado.** O aviso *é* a funcionalidade: um cofre que bloqueia com a campainha
desligada não avisa nada, que é exatamente o problema que T4.17 existe para resolver. Decisão de
produto, registrada em `docs/development/doorbell-design.md`.

**Por que `opaque_blobs` e não `meta`.** `meta` é identidade e estado de longa duração; isto é
preferência de usuário, e um namespace de preferências por cofre é extensível sem inventar uma
chave de `meta` nova a cada opção. Consequência a fixar: como todo namespace de `opaque_blobs`, ele
**não existe num cofre-isca recém-criado** (ver §2.1) — e não precisa existir, porque a ausência já
significa "ligado", que é o mesmo valor efetivo do cofre real recém-criado. Os dois cofres se
comportam de forma idêntica sem nenhum ramo por `VaultSlot`.

### 8.2 `doorbell_knocks`

| Item | Valor | Onde |
|---|---|---|
| Namespace | `doorbell_knocks` | `DoorbellKnockPolicy.NAMESPACE` |
| Chave | `ContactRecord.id`, ou seja o `id_pub` do contato — estável entre sessões | `ME:1043` |
| Formato | **12 bytes**: `long` big-endian de epoch millis do último toque **tentado** + `int` big-endian de falhas consecutivas | `DoorbellKnockPolicy.ENCODED_BYTES` |
| Escreve | ao despachar um toque (`prepareKnock`) e ao registrar o resultado (`recordKnock` / confirmação) | `ME:1043`, `ME:1058`, `ME:1070` |
| Lê | `knockState(peer)` | `ME:1074` |
| Apaga | **nunca** — é reescrito, não removido | — |
| Curva | 10 / 20 / 40 / 80 / 120 min | `DoorbellKnockPolicy.BACKOFF_MILLIS` |

Um valor de qualquer outro tamanho decodifica para `null`, que significa "nunca tocou" e portanto
**elegível**: um registro corrompido não pode silenciar a campainha para sempre. Carimbo no futuro
também conta como elegível (relógio recuado por NTP, fuso ou cofre restaurado). Detalhe de
comportamento em `docs/development/messaging-api.md`, seção "Doorbell knock".

### 8.3 Esquema do cofre v3 → v4

```sql
-- v4 acrescenta, e nada mais (AVS:297)
ALTER TABLE contacts ADD COLUMN doorbell_token_issued BLOB NOT NULL DEFAULT x''
```

| Item | Valor | Onde |
|---|---|---|
| Constante | `SCHEMA_VERSION = 4` | `AVS:529` |
| Degrau | `4 -> reserveMigrationPages(...)` + o `ALTER TABLE` acima | `AVS:295-298` |
| Quem migra | `migrate(database)`, a partir de **`initialize()` e `open()`** — mecanismo de T4.15, inalterado | `AVS:220-232` |
| Reserva de páginas | `reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)` — mesma política do degrau v1→v2 | `AVS:296` |
| Domínio | vazio (`x''`) = nenhuma campainha acordada com este contato; caso contrário 32 bytes | `CD:616` |
| Invariante | `doorbell_token_issued` não-vazio exige `doorbell_token` não-vazio | `CD:632` |
| Leitura/escrita | as três colunas de campainha entram nas SQLs de `listContacts`/`getContact`/`putContact` | `CD:93,102,116,121,132,538` |

As **três** colunas de campainha por contato, e o que cada uma é:

| Coluna | De quem | Para quê | Esquema |
|---|---|---|---|
| `doorbell_onion` | do par | **onde** bater | v3 |
| `doorbell_token` | do par | **o que apresentar** ao bater | v3 |
| `doorbell_token_issued` | **deste** aparelho | o que **aceitar** quando o par bate aqui | **v4** |

**Por que a v4 foi necessária.** Até T4.16 o token local cunhado em `PairingEngine.newOffer()` era
destruído junto com a oferta; o aparelho sabia bater e não sabia reconhecer quem batia — uma
assimetria sem volta. `PairingEngine.finish()` passou a devolvê-lo em
`PairedContact.doorbellTokenIssued`. **Limitação assumida:** contato pareado antes de T4.17 fica com
a coluna vazia e não consegue tocar a campainha deste aparelho; o valor só existia dentro daquela
oferta e não há correção retroativa — só um novo pareamento resolve. O código trata a coluna vazia
como "sem campainha acordada", não como erro.

**Efeito no cofre-isca.** `DecoyFactory` preenche as **três** colunas de todo contato sintético
(`DF:79-89`), pelo mesmo motivo da seção 7.3: coluna em branco em todo contato do isca, enquanto os
contatos reais carregam valor, seria um distinguidor por simples inspeção do dump. A identidade de
campainha de cada contato sintético é uma chave Ed25519 real com a metade privada descartada, do
mesmo jeito que os onions do isca.

### 8.4 Ids de notificação

Não havia catálogo de notificações neste documento antes de T4.17; a tabela abaixo existe porque os
ids passaram de um para três e "qual id é qual" virou informação de runtime que o gate 6 do
`docs/release-checklist.md` precisa conferir literalmente.

| Id | O que é | Canal | Importância | Texto |
|---|---|---|---|---|
| 1 | Mensagem recebida (pré-existente) | `private_messages` | `IMPORTANCE_DEFAULT` | título `NoMessages`, **sem corpo** |
| 2 | Aviso da campainha: alguém tocou com o cofre bloqueado | `private_messages` (o **mesmo** de cima, de propósito) | `IMPORTANCE_DEFAULT` | título `doorbell_pending_title` (= `NoMessages`), corpo `doorbell_pending_body` — frase fixa, sem remetente, sem contagem, sem prévia |
| 3 | Foreground do processo `:tor` em modo mínimo | `background_service` | `IMPORTANCE_LOW` | título `NoMessages`, **sem corpo**, silenciosa e contínua |

`PN:70-74` fixa os três ids; `PN:85` fixa o canal de fundo. Os três são `VISIBILITY_SECRET` e
`setLocalOnly(true)`.

**Por que 2 reusa o canal de 1.** É a única notificação de campainha que **precisa** chegar ao
usuário — se ela for silenciosa, a funcionalidade inteira não faz nada —, então herda som, vibração
e importância padrão do sistema, além da visibilidade secreta que o canal já carrega. Id próprio, e
não o mesmo de 1, para que as duas coexistam em vez de uma substituir a outra. A notificação 3 é o
oposto exato e por isso tem canal próprio: silenciosa, contínua, `IMPORTANCE_LOW`. O nome visível do
canal 3 é neutro ("Segundo plano" / "Background"): ele aparece na lista de configurações do app, onde
um nome como "Campainha" ou "Tor" descreveria o que o produto faz para quem abrir aquela tela.

A notificação 2 é cancelada explicitamente no início de `activate()` (`WC:243`), de forma
incondicional e idempotente: ela não some sozinha, e não pode sobreviver a um desbloqueio — inclusive
quando ninguém tocou. A 3 é removida por `leaveForeground()` quando o modo mínimo termina.
