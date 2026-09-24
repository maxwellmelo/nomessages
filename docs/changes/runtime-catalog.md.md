# docs/development/runtime-catalog.md

## 2026-09-18 — T4.17 (campainha): dois namespaces, `meta.doorbell_seed`, esquema v4 e a primeira tabela de ids de notificação

**Antes:** a tabela de §2.1 tinha 14 namespaces de `opaque_blobs`; a de §3 tinha 8 chaves de `meta` e **não** incluía `doorbell_seed`, apesar de o `SPEC.md` já a citar. O documento ia até a §7 (coluna `messages.forwarded`, migração v1→v2) e não documentava notificação nenhuma.

**Agora:** §2.1 ganhou `vault_settings` e `doorbell_knocks`; §3 ganhou `doorbell_seed`, com a nota de que ela é cunhada **na primeira utilização** e não no setup, de que fica em `meta` por ser identidade de longa duração, e de que o cofre-isca também a tem — mas pelo mesmo caminho preguiçoso do real, **não** por escrita do `DecoyFactory`, o que a impede de ser distinguidor. Entrou a **§8** completa: §8.1 `vault_settings/doorbell_enabled` (1 byte, **ausência = ligado**, e por que `opaque_blobs` em vez de `meta`); §8.2 `doorbell_knocks` (12 bytes, chave `id_pub`, curva de backoff, decodificação falha = elegível); §8.3 o degrau **v3 → v4** com o `ALTER TABLE`, a tabela das três colunas de campainha e de quem é cada uma, a limitação sem correção retroativa e o efeito no isca; §8.4 a tabela dos **três ids de notificação** com canal, importância e texto.

**Por quê:** as três primeiras adições são o escopo declarado do documento — um namespace ou chave de `meta` fora do catálogo é exatamente o que o catálogo existe para não deixar acontecer. A §8.4 é a única adição fora do escopo original: **não havia** catálogo de notificações aqui antes. Foi incluída assim mesmo, em forma de tabela curta, porque os ids passaram de um para três e "qual id é qual" virou informação de runtime que o gate 6 do `release-checklist.md` precisa conferir literalmente — e o arquivo já é o lugar onde constantes de runtime desse tipo vivem. A §8 abre com a mesma ressalva de âncora da §7: as citações de linha foram lidas na árvore de trabalho de 2026-09-18, não no commit `22aa7b0` a que o resto do documento se ancora.

## 2026-09-14 — T5.2: catálogo de `opaque_blobs`, chaves de `meta` e limites de runtime

### Como era antes

O arquivo **não existia**. As informações que ele reúne estavam espalhadas em
seis arquivos-fonte e em nenhum documento:

- os nomes dos namespaces de `opaque_blobs` só apareciam como literais dentro de
  `MessagingEngine.kt` (`db.putBlob("group_pending", …)`, `db.getBlob("receipts", …)`, …);
- os limites numéricos (TTL de KeyPackage, 8 MiB de anexo, 128 grupos, 1024 contatos,
  16 384 arestas, 4950 provas, 24 MiB de cursores, 256 MiB de capacidade, …) só
  existiam como constantes e literais em `MessagingEngine.kt`, `MessagingPolicy.kt`,
  `NoMessagesController.kt`, `ChatDatabase.kt` e `AndroidVaultStorage.kt`;
- o `SPEC.md` cita alguns desses limites, mas sem `arquivo:linha` e com divergências
  conhecidas (é exatamente o que T5.1 vai reconciliar).

O enunciado de T5.2 no plano listava 20 nomes tratando todos como namespaces de
`opaque_blobs`.

### Como ficou

Documento novo de ~420 linhas, em Português, com cinco seções:

1. **Correção ao enunciado da tarefa** — o esquema tem duas tabelas chave-valor
   distintas (`AVS:180-181`): `meta(k,v)` e `opaque_blobs(namespace,k,value)`.
   Dos 20 nomes listados no plano, **14 são namespaces de `opaque_blobs`** e
   **6 são chaves de `meta`** (`tor_guard_state`, `pairing_consumed`, `lock_timeout`,
   `bridges`, `display_name`, `outbox_sequence`), gravadas por `putMeta` (`CD:23`)
   e não por `putBlob` (`CD:40`). Acrescentadas ao conjunto `identity` e
   `onion_seed`, que o plano não listou.
2. **Os 14 namespaces de `opaque_blobs`** — tabela de visão geral (chave, quem
   escreve, quem lê, quando apaga, existe no decoy) mais um bloco por namespace
   com formato exato do valor, formato da chave e ciclo de vida, cada afirmação
   com `arquivo:linha`.
3. **As 8 chaves de `meta`** — mesma estrutura, com notas sobre reescrita a cada
   transação (`identity`, `ME:303`) e sobre a ausência de caminho de remoção.
4. **Limites numéricos de runtime** — seis tabelas (mensageria/envelopes, outbox
   e transporte, trial decryption, `ChatDatabase`, `AndroidVaultStorage`,
   controller/ciclo de vida), todas com `arquivo:linha`.
5. **Consequências observadas** — cinco fatos que a leitura revelou e que não
   foram corrigidos aqui (pertencem a T3.3, T3.5 e T4.1).

Exemplo do nível de detalhe (namespace `attempts`):

```markdown
- **Formato do valor:** `now()` em decimal ASCII com zero-padding para 16 caracteres
  (`ME:339`), de modo que a ordenação lexicográfica de `a.value` em `MP:22` seja a
  ordenação temporal.
- **Remoção:** `ME:517` (ACK), `ME:534` (rejeição), `ME:186` (cancelamento).
```

Cabeçalho acrescentado nesta revisão fixando a âncora dos números de linha em
`HEAD` (commit `22aa7b0`), porque `MessagingEngine.kt` e `NoMessagesController.kt`
estavam sob edição paralela por T2.x; `MessagingPolicy.kt`, `ChatDatabase.kt`,
`AndroidVaultStorage.kt` e `DecoyFactory.kt` não estavam e suas linhas valem
também para a árvore de trabalho.

Duas citações erradas herdadas da rodada anterior foram corrigidas nesta revisão:

| Antes | Depois | Motivo |
|---|---|---|
| `ME:311` "devolve o snapshot anterior em memória" | `ME:308` | `ME:311` é `deferredErrors.clear()`; `identity.restoreInPlace(before)` está em `ME:308` |
| `MP:47` (balde de 4 tokens) | `MP:46` | `MP:47` é `previous = ticks()`; `private var tokens = 4.0` está em `MP:46` |

### Por que a mudança foi feita

T5.2 pede exatamente este artefato. A motivação prática é que `opaque_blobs` é o
único ponto do esquema sem forma declarada: a tabela aceita qualquer
`(namespace, k, value)` e a validação é só de sintaxe do namespace
(`[a-z0-9][a-z0-9._-]{0,63}`, `CD:578`). Sem catálogo, três perguntas ficam sem
resposta auditável:

- **Quanto disso cresce para sempre?** `accepted_ids`, `rejected_ids`, `receipts`
  e `failed_controls` não têm nenhum caminho de remoção, e o banco tem capacidade
  fixa de 256 MiB (`AVS:331`). Isso é um risco de esgotamento de `storage_reserve`
  (`CD:435-456`) que ninguém tinha escrito em lugar nenhum.
- **O decoy é indistinguível?** O vault decoy nasce com **zero** linhas em
  `opaque_blobs` (`AVS:236-288`, `DF:10-29`) e sem `display_name` — dois marcadores
  observáveis que o gate 2 (T3.5), que mede apenas `Files.size`, não pega.
- **Onde está cada limite que o SPEC afirma?** T5.1 precisa reconciliar o `SPEC.md`
  com o código; agora existe a lista de referência com `arquivo:linha`.

### Vantagens

- Toda afirmação é rastreável a `arquivo:linha` e foi conferida contra o código em
  `HEAD`; nada foi inferido do `SPEC.md` ou do plano.
- Cobertura verificada por varredura, com o comando exato (executável como está):

  ```sh
  grep -rhoE '(putBlob|getBlob|removeBlob|listBlobs)\("[a-z_]+"' app core \
    | sed -E 's/.*\("//;s/"//' | sort -u
  ```

  devolve exatamente os 14 namespaces de produção catalogados, mais `signal`, que só
  existe em `AndroidVaultStorageTest.kt:185,194` e está registrado como fixture de teste
  na seção 2.3. A única chamada com namespace **não literal** — que por definição escapa
  dessa varredura — é `NoMessagesController.kt:787` em `22aa7b0` (`getBlob(namespace, it)`,
  dentro de `hasGroupFlag`), cujo argumento vem dos três literais de `WC:793-795`
  (`group_left`, `group_error`, `group_pending`), todos já catalogados. Não há namespace
  fora do catálogo.
- Corrige um erro de premissa do plano (6 dos 20 nomes não são namespaces), evitando
  que T5.1 propague a confusão para o `SPEC.md`.
- Entrega a T3.5 e a T3.3 achados concretos e acionáveis, com a fonte de cada um.

### Não verificado

- Nenhum teste foi executado. O catálogo é resultado de leitura estática; não foi
  instrumentado nenhum `.db` real para confirmar empiricamente o conteúdo das linhas.
- A afirmação "cresce monotonicamente" para `accepted_ids`/`receipts` é uma leitura
  de ausência de `removeBlob` nos arquivos lidos, não uma medição de crescimento.
- Após os commits de T2.x, as linhas `ME:` e `WC:` precisam ser reconferidas; o
  documento avisa isso no cabeçalho (ver a revisão de correções abaixo, que reescreveu
  esse aviso porque a divergência **já existe** na árvore de trabalho).

## 2026-09-14 — revisão de correções (mesma data, rodada de revisão)

Quatro achados de revisão sobre a primeira entrega de T5.2 foram aplicados. Nenhuma
seção foi duplicada: as três primeiras correções são reescritas no lugar, a quarta é
um item novo na seção 5.

### 1. Âncora de linhas: de "risco futuro" para fato presente (P2)

**Como era antes** (`runtime-catalog.md:17-23`):

```markdown
> **Âncora dos números de linha.** Todas as referências ... foram lidas e conferidas
> em **`HEAD` (commit `22aa7b0`)** ... porque `MessagingEngine.kt` e
> `NoMessagesController.kt` estavam sendo alterados em paralelo pelas tarefas T2.x
> quando este catálogo foi escrito. Após aqueles commits, reconfira as linhas de
> `ME` e `WC` ...
```

O texto tratava a divergência como algo que aconteceria **depois** dos commits de T2.x.
Isso é falso: a divergência já está na árvore de trabalho, e é ela que será commitada
junto com o catálogo. Conferido nesta revisão:

| Citação (em `22aa7b0`) | Conteúdo | Onde estava na árvore de trabalho no momento da revisão |
|---|---|---|
| `WC:246` | `delay(60_000)` | 251 |
| `WC:356` | `putMeta(TorStateSnapshot.META_KEY, snapshot)` | 361 |
| `WC:483` | `putMeta("identity", bytes)` | 488 |
| `WC:795` | `hasGroupFlag("group_pending")` | 800 |

**Como ficou:** o bloco de âncora foi reescrito para dizer, sem rodeio, que as
referências valem para `22aa7b0` e **não** para a árvore de trabalho, com o comando
para lê-las (`git show 22aa7b0:<arquivo>`), e que `MP`/`CD`/`AVS`/`DF` valem para os
dois porque não estão sob edição.

**Por que não foi feito o contrário (repontar tudo para a árvore de trabalho):** foi
tentado e revertido dentro desta mesma revisão. As 27 citações `WC:` ≥ 243 chegaram a
ser deslocadas em +5 e conferidas uma a uma; poucos minutos depois, uma releitura do
mesmo arquivo mostrou `superviseTransport(token, localEngine, bridges)` na linha 242 e
`override fun lock()` na 248 — ou seja, T2.3 estava reescrevendo `NoMessagesController.kt`
**durante** esta revisão (`git diff HEAD --numstat` passou de `5 0` para `103 17`, e
`MessagingEngine.kt` passou de sem diff para `33 9`). Um alvo móvel não pode ser âncora:
qualquer número conferido contra a árvore de trabalho nasce obsoleto na edição seguinte.
O commit é o único ponto fixo enquanto a Fase 2 não fecha. O deslocamento foi desfeito
exatamente (`WC:` ≥ 248 → −5) e **todas as 42 citações `WC:` do catálogo foram
reconferidas contra `git show 22aa7b0:...`**, uma a uma: todas corretas.

**Vantagem:** quem for conferir o catálogo recebe o comando que reproduz a citação em
vez de encontrar uma linha que não bate e concluir que o documento está errado.

### 2. `receipts`: faltava o terceiro caso do formato do valor (P3)

**Como era antes:**

```markdown
- **Formato do valor:** o pacote wire do ACK cifrado (`ME:497-500`), **ou um array
  vazio** quando o envelope recebido era ele mesmo um `Ack` (`ME:497`) ...
```

Dois casos, num bloco que se apresenta como o formato exato do valor. Mas `ME:497` é
`val ack = rejected ?: if (envelope is Envelope.Ack) byteArrayOf() else { … }`: quando
`rejected` não é nulo (vindo de `ME:486` ou de `ME:492-493`), o que vai para
`receipts` é o pacote de `ControlRejected`, não um ACK.

**Como ficou:** três casos explícitos (a) `ControlRejected` cifrado, (b) array vazio,
(c) `Ack` cifrado, cada um com a linha que o decide.

**Vantagem:** quem for auditar o cache de idempotência não conclui que uma retransmissão
de controle rejeitado devolve um ACK — ela devolve a mesma rejeição.

### 3. Seção 5: divergência real/decoy observável na rede (P2)

**Como era antes:** a seção 5 (a lista que alimenta o gate 2 / T3.5) só registrava
`display_name` como divergência real/decoy, embora a própria tabela da seção 3 marque
`bridges`, `tor_guard_state` e `lock_timeout` como ausentes no decoy.

**Como ficou:** item 3 novo (os antigos 3, 4 e 5 viraram 4, 5 e 6), documentando que
`activate` lê essas chaves do vault **atualmente aberto** (`WC:195`), de modo que
destravar com a senha de pânico faz `WC:222` devolver string vazia e `WC:224` devolver
`null` — e `WC:241` sobe o Tor sem pontes e com bootstrap de guards do zero, enquanto o
vault real sobe com as pontes salvas e restaurando o checkpoint.

**Por que a mudança foi feita:** essa divergência é **mais grave** que a do
`display_name` e não estava listada para quem vai medir o gate. `display_name` é um
marcador na UI, visível para quem já coagiu o desbloqueio; o padrão de bootstrap do Tor
é observável na rede e no relógio, sem tocar no disco e sem olhar a tela. A seção agora
também diz o que fazer: semear `bridges`, `lock_timeout` e um `tor_guard_state`
plausível em `seedDecoy`, e medir o padrão de bootstrap dos dois slots em T3.5 — não só
`Files.size`.

### 4. Comando de varredura que não rodava (P3)

**Como era antes**, nas "Vantagens" deste próprio arquivo:

```markdown
`grep` de `\(putBlob\|getBlob\|removeBlob\|listBlobs\)("…"` em `app/` e `core/`
devolve exatamente os 14 namespaces
```

O `…` é um caractere literal (U+2026), então o regex casa zero linhas: a evidência de
cobertura era um comando que não roda.

**Como ficou:** o comando executável real (`grep -rhoE … | sed -E … | sort -u`), com o
resultado conferido, mais a menção honesta ao único call site com namespace não literal
(`NoMessagesController.kt:787` em `22aa7b0`, dentro de `hasGroupFlag`), que por construção
escapa de qualquer varredura por literal e cujos três argumentos possíveis já estão
catalogados.

### Não verificado nesta revisão

- Continua sendo leitura estática: nenhum `.db` foi instrumentado, nenhum teste rodou
  (Windows sem JDK/SDK; a área desta tarefa não toca em Rust).
- As linhas `ME:` e `WC:` foram conferidas contra `22aa7b0`, **não** contra a árvore de
  trabalho — que estava sendo reescrita por T2.1/T2.3 durante a revisão e mudou duas
  vezes no intervalo. Reconferir depois que a Fase 2 commitar.
- A afirmação de que o decoy sobe o Tor sem pontes é leitura de código
  (`WC:195,222,224,241` + `AVS:236-288` + `DF:10-29`), não uma medição de rede. A
  medição é justamente o que se pede a T3.5.
