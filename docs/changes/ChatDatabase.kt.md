# app/src/main/kotlin/dev/mx3/nomessages/storage/ChatDatabase.kt

## 2026-09-14 — T4.2: contagem local de não lidas e marcação de leitura

Tarefa: T4.2 de `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.
Decisão fixada antes da implementação: **somente contador local**. Nenhum recibo
de leitura trafega na rede e nenhum tipo de envelope novo foi criado.

### Como era antes

O contrato de `ChatDatabase` tinha o enum `MessageStatus` com `READ` desde o
início, mas nenhuma consulta o usava. As únicas operações sobre status eram:

```kotlin
fun listChats(): List<ChatRecord> { /* ... última mensagem por chat ... */ }

fun updateMessageStatus(id: String, status: MessageStatus): Boolean {
    validateKey(id)
    return mutate(0) {
        val values = ContentValues().apply { put("status", status.ordinal) }
        database.update("messages", SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(id)) > 0
    }
}
```

`READ` só aparecia na semeadura da isca (`AndroidVaultStorage.seedDecoy`), e não
havia como responder "quantas mensagens novas existem neste chat?". Consequência:
`ChatUi.unread` ficava sempre em `0` e o selo de `HomeScreen.kt` era código morto.

### Como ficou

Duas funções novas, nenhuma alteração nas existentes e nenhuma mudança de schema
(o índice `messages_chat_time ON messages(peer_or_group,ts DESC,id DESC)` já
existia e atende as duas).

Contagem, colocada logo depois de `listChats()`:

```kotlin
fun unreadCounts(): Map<String, Int> {
    requireOpen()
    return database.rawQuery(
        "SELECT peer_or_group,COUNT(*) FROM messages WHERE direction = ? AND status <> ? GROUP BY peer_or_group",
        arrayOf(MessageDirection.INCOMING.ordinal.toLong(), MessageStatus.READ.ordinal.toLong()),
    ).use { cursor ->
        buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1)) }
    }
}
```

Marcação, colocada logo depois de `updateMessageStatus`:

```kotlin
fun markChatRead(peerOrGroup: String): Int {
    validatePeerOrGroup(peerOrGroup)
    return mutate(0) {
        val values = ContentValues().apply { put("status", MessageStatus.READ.ordinal) }
        database.update(
            "messages",
            SQLiteDatabase.CONFLICT_ABORT,
            values,
            "peer_or_group = ? AND direction = ? AND status <> ?",
            arrayOf<Any>(peerOrGroup, MessageDirection.INCOMING.ordinal.toLong(), MessageStatus.READ.ordinal.toLong()),
        )
    }
}
```

Detalhes que importam:

- Só a direção `INCOMING` é tocada. A direção `OUTGOING` continua sendo escrita
  exclusivamente pelo `MessagingEngine` (`PENDING` → `SENT` → `DELIVERED`), de
  modo que o tique duplo que o remetente vê continua significando "entregue" e
  não passa a significar "lido" por efeito colateral.
- `mutate(0)`: a estimativa de escrita é zero, igual a `updateMessageStatus`.
  `consumeReserve` retorna imediatamente quando a estimativa é `0`, então
  abrir uma conversa **nunca** consome página da reserva nem pode falhar com
  `StorageCapacityException` num banco cheio.
- `validatePeerOrGroup` é a mesma validação usada por `listMessages`, então um
  id inválido é rejeitado antes de chegar ao SQL, como no resto do arquivo.
- Os argumentos vão ligados (`?`), nunca interpolados, seguindo a regra do
  arquivo de manter segredo e dado fora do texto SQL.

### Isca: mesmo comportamento, sem canal de tempo

`AndroidVaultStorage.seedDecoy` já semeia **todas** as mensagens da isca com
`MessageStatus.READ`. Logo:

- `unreadCounts()` devolve mapa vazio na isca — nenhum selo aparece, que é
  exatamente a aparência de um aparelho com o histórico todo lido;
- `markChatRead` na isca casa zero linhas e não escreve nada.

Não existe ramo de código, tabela, índice ou consulta que diferencie real de
isca: é o mesmo enunciado SQL nos dois cofres, decidido pelo conteúdo da tabela
e não pelo cofre. A única diferença de tempo observável é entre "conversa com
mensagens novas" e "conversa já lida", e essa diferença existe igualmente dentro
do cofre real — abrir uma conversa já lida no cofre real também não escreve nada.

`unreadCounts()` é uma varredura agrupada única que responde por **todos** os
chats de uma vez, e não uma consulta por chat: o custo não varia conforme qual
conversa está selecionada.

### Vantagens

- `ChatUi.unread` passa a ter fonte de dados; o selo de `HomeScreen.kt` deixa de
  ser código morto sem que nada precise ser removido da interface.
- Zero metadado novo na rede: a leitura é decidida e guardada só no aparelho, o
  que preserva a propriedade de que um par não descobre se ou quando foi lido.
- Custo constante por `refresh()` (uma varredura agrupada), em vez de uma
  consulta por conversa.
- Uma escrita que não consome reserva pode ser feita no caminho de abertura da
  conversa sem risco de falhar por capacidade.

### Por que a mudança foi feita

T4.2 apontava três sintomas do mesmo buraco: `ChatUi.unread` constante em `0`,
`MessageStatus.READ` existindo apenas na isca e o selo de não lidas inalcançável.
A alternativa — recibo de leitura na rede — foi descartada na própria tarefa por
adicionar metadado e um tipo de envelope; o contador local fecha os três sintomas
sem tocar no protocolo.

## 2026-09-15 — Correção da seção "Isca: mesmo comportamento, sem canal de tempo"

`ChatDatabase.kt` **não** mudou nesta data. O que mudou foi a premissa da seção acima: ela afirmava
que a isca semear tudo como `READ` era benigno ("nenhum selo aparece, que é exatamente a aparência
de um aparelho com o histórico todo lido").

A revisão de segurança mostrou que isso é um distinguidor estável: um cofre real em uso normal quase
sempre tem alguma conversa não lida, e a isca **nunca** podia mostrar uma. Por isso
`AndroidVaultStorage.seedDecoy` passou a semear duas das quatro conversas com a cauda de entrada em
`MessageStatus.DELIVERED` e carimbo de horas (ver `docs/changes/AndroidVaultStorage.kt.md`,
2026-09-15).

O que continua valendo palavra por palavra: não existe ramo de código, tabela, índice ou consulta que
diferencie real de isca — `unreadCounts()` e `markChatRead` são o mesmo enunciado SQL nos dois cofres,
decidido pelo conteúdo da tabela. Na isca, `markChatRead` agora casa uma linha em vez de zero, que é
precisamente o comportamento do cofre real na mesma situação.

## 2026-09-15 — T3.1: `rawQuery` exige spread quando os argumentos não são `String`

Correção das duas últimas falhas da suíte instrumentada
(`docs/development/build-logs/android-test-emulator-5556-20260915T141801Z.log`):

> `android.database.sqlite.SQLiteDatatypeMismatchException: datatype mismatch (code 20)`
> em `ChatDatabase.listPendingFrames` e `ChatDatabase.listPendingFrameIds`

### Como era antes

```kotlin
// unreadCounts()
database.rawQuery(
    "SELECT peer_or_group,COUNT(*) FROM messages WHERE direction = ? AND status <> ? GROUP BY peer_or_group",
    arrayOf(MessageDirection.INCOMING.ordinal.toLong(), MessageStatus.READ.ordinal.toLong()),
)

// listPendingFrames() / listPendingFrameIds()
database.rawQuery(
    "SELECT id FROM outbox ORDER BY created_at,id LIMIT ?",
    arrayOf(limit.toLong()),
)

// putPendingFrame()
val alreadyStored = database.rawQuery("SELECT 1 FROM wire_blobs WHERE hash = ?", arrayOf(hash))
    .use { it.moveToFirst() }
```

### Como ficou

```kotlin
// The arguments are spread on purpose. SQLCipher offers both rawQuery(String, String[]) and
// rawQuery(String, Object...): an Array<String> picks the first overload, but any other
// array type picks the vararg one and is passed as a single argument, so the array object
// itself gets bound - as text, via toString - instead of its elements. That silently
// changes the bound value and the argument count, which is how this query used to fail.
database.rawQuery(
    "SELECT peer_or_group,COUNT(*) FROM messages WHERE direction = ? AND status <> ? GROUP BY peer_or_group",
    *arrayOf<Any>(MessageDirection.INCOMING.ordinal.toLong(), MessageStatus.READ.ordinal.toLong()),
)

database.rawQuery(
    "SELECT id FROM outbox ORDER BY created_at,id LIMIT ?",
    // Spread, see unreadCounts: an unspread Long array binds as text and LIMIT rejects it.
    *arrayOf<Any>(limit.toLong()),
)

// Spread, see unreadCounts: an unspread blob array would never match a stored hash.
val alreadyStored = database.rawQuery("SELECT 1 FROM wire_blobs WHERE hash = ?", *arrayOf<Any>(hash))
    .use { it.moveToFirst() }
```

O SQL não mudou: os parâmetros continuam ligados, nada foi interpolado.

### Por que a mudança

`net.zetetic.database.sqlcipher.SQLiteDatabase` declara duas sobrecargas:

```java
public Cursor rawQuery(String sql, String[] selectionArgs);
public Cursor rawQuery(String sql, Object... selectionArgs);
```

Em Kotlin, `arrayOf("x")` tem tipo `Array<String>` e casa exatamente com a
primeira — por isso todas as consultas que ligam apenas textos sempre
funcionaram. Já `arrayOf(1L)` tem tipo `Array<Long>`, não casa com `String[]` e
cai no vararg; **sem o operador de spread, o array inteiro vira um único
argumento**, e não um argumento por elemento. O SQLCipher então classifica esse
argumento com `DatabaseUtils.getTypeOfObject`, que só reconhece `byte[]`,
`Float`/`Double` e os inteiros boxed; um `Long[]` cai no caso padrão e é ligado
como texto, com o `toString` do array (`"[Ljava.lang.Long;@..."`).

Daí os três sintomas, todos em código de produção:

1. `LIMIT ?` recebia um texto não numérico. O SQLite exige inteiro nessa posição
   e responde `SQLITE_MISMATCH` — a exceção observada. (Um texto numérico teria
   passado por afinidade; o `toString` de um array, não.)
2. `unreadCounts` mandava 1 argumento para uma consulta de 2 marcadores, o que
   falha na verificação de aridade do binding em tempo de execução. O contador
   local de não lidas de T4.2 nunca funcionaria no aparelho.
3. A deduplicação de `putPendingFrame` comparava a coluna BLOB `hash` com um
   texto, então `alreadyStored` era sempre `false` e a estimativa de bytes
   consumidos da reserva ficava inflada a cada reenvio do mesmo quadro.

Nenhum desses três é visível em JVM: o caminho só existe no SQLCipher real.

### Vantagens

- `listPendingFrames`/`listPendingFrameIds` (leitura da outbox pelo transporte)
  voltam a funcionar no Android; a suíte instrumentada fechou 12/12.
- Dois defeitos silenciosos foram eliminados junto: contador de não lidas e
  deduplicação de quadros da outbox — ambos falhavam sem exceção visível.
- O comentário no ponto mais antigo do arquivo documenta a armadilha de
  sobrecarga, para que a próxima consulta com argumento não textual já nasça
  correta.

## 2026-09-17 — Coluna `forwarded` nas consultas e no insert de `messages` (Parte B: Encaminhar)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). `MessageRecord` ganhou o
campo `forwarded` (ver `docs/changes/StorageModels.kt.md`) e a tabela `messages` ganhou a coluna
homônima pela migração de schema v2 (ver `docs/changes/AndroidVaultStorage.kt.md`). Este arquivo é
quem lê e escreve essa coluna.

### O que NÃO mudou

A definição de `CREATE TABLE messages(...)` **não** está neste arquivo e não foi tocada: ela vive em
`AndroidVaultStorage.createSchemaV1`, que é um registro congelado do que a v1 era. A coluna nova
nasce exclusivamente do `ALTER TABLE` da migração.

### Como era antes

```kotlin
// getMessage
"SELECT id,peer_or_group,direction,ts,body,status FROM messages WHERE id = ?"

// listMessages (dois ramos)
sql = "SELECT id,peer_or_group,direction,ts,body,status FROM messages WHERE peer_or_group = ? ORDER BY ts DESC,id DESC LIMIT ?"
sql = "SELECT id,peer_or_group,direction,ts,body,status FROM messages WHERE peer_or_group = ? AND ts < ? ORDER BY ts DESC,id DESC LIMIT ?"

// insertMessage
"INSERT INTO messages(id,peer_or_group,direction,ts,body,status) VALUES(?,?,?,?,?,?)",
arrayOf(message.id, message.peerOrGroup, message.direction.ordinal.toLong(),
        message.timestamp, message.body, message.status.ordinal.toLong())

// Cursor.message()
private fun Cursor.message() = MessageRecord(
    id = getString(0), peerOrGroup = getString(1),
    direction = enumValue<MessageDirection>(getInt(2)), timestamp = getLong(3),
    body = getBlob(4).copyOf(), status = enumValue<MessageStatus>(getInt(5)),
)
```

### Como é agora

```kotlin
// getMessage e os DOIS ramos de listMessages
"SELECT id,peer_or_group,direction,ts,body,status,forwarded FROM messages ..."

// insertMessage
"INSERT INTO messages(id,peer_or_group,direction,ts,body,status,forwarded) VALUES(?,?,?,?,?,?,?)",
arrayOf(..., message.status.ordinal.toLong(),
        // Stored as 0/1, matching the column's CHECK constraint.
        if (message.forwarded) 1L else 0L)

// Cursor.message()
// Column order must match every "SELECT ... FROM messages" that feeds this mapper:
// id, peer_or_group, direction, ts, body, status, forwarded.
private fun Cursor.message() = MessageRecord(
    ..., status = enumValue<MessageStatus>(getInt(5)),
    forwarded = getLong(6) != 0L,
)
```

### Conferência de ordem de colunas

`Cursor.message()` lê por índice posicional, então todo `SELECT` que o alimenta precisa projetar as
mesmas colunas na mesma ordem. Os três — `getMessage` e os dois ramos de `listMessages` — foram
atualizados juntos, e um comentário no mapper registra a ordem canônica. Nenhuma outra consulta a
`messages` alimenta esse mapper:

| consulta | usa `Cursor.message()`? | alterada? |
| --- | --- | --- |
| `getMessage` | sim | **sim** |
| `listMessages` (sem `beforeTimestamp`) | sim | **sim** |
| `listMessages` (com `beforeTimestamp`) | sim | **sim** |
| `listChats` (JOIN com `messages`, monta `ChatRecord`) | não | não |
| `unreadCounts` (`COUNT(*) GROUP BY`) | não | não |
| `updateMessageStatus` / `markChatRead` (`UPDATE`) | não | não |

### Vantagens

- Nenhuma nova consulta e nenhum novo índice: a coluna viaja de carona nas consultas que a tela de
  conversa já fazia, custo adicional de um `INTEGER` por linha.
- `if (message.forwarded) 1L else 0L` casa exatamente com o `CHECK(forwarded IN (0,1))` da coluna, do
  mesmo jeito que `display_only` já fazia em `putContact` — nada de `Boolean` cru indo para o
  binder, que trataria tipos de forma inconsistente com o resto do arquivo.
- O comentário sobre a ordem posicional torna explícito um acoplamento que antes era só convenção.

## 2026-09-17 — Colunas `doorbell_onion`/`doorbell_token` em `contacts`: consultas, insert e validação (T4.16)

### Motivo

`ContactRecord` ganhou `doorbellOnion`/`doorbellToken` (ver `docs/changes/StorageModels.kt.md`) e a
tabela `contacts` ganhou as colunas homônimas pela migração de schema v3 (ver
`docs/changes/AndroidVaultStorage.kt.md`). Este arquivo é quem lê, escreve e valida essas colunas —
reservadas para o T4.17, mas já protegidas por regras próprias porque o dado entra pela rede.

### Como era antes

```kotlin
// listContacts
"SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only FROM contacts ORDER BY alias,id_pub"

// getContact
"SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only FROM contacts WHERE id_pub = ?"

// putContact
mutate(contact.alias.length + contact.onion.length + contact.identityPublic.size + contact.signalPeer.size) {
    database.execSQL(
        "INSERT INTO contacts(id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only) VALUES(?,?,?,?,?,?,?) " +
            "ON CONFLICT(id_pub) DO UPDATE SET alias=excluded.alias,onion=excluded.onion," +
            "identity_public=excluded.identity_public,signal_peer=excluded.signal_peer," +
            "paired_at=excluded.paired_at,display_only=excluded.display_only",
        arrayOf(contact.id, contact.alias, contact.onion, contact.identityPublic,
                contact.signalPeer, contact.pairedAt, if (contact.displayOnly) 1L else 0L),
    )
}

// Cursor.contact()
private fun Cursor.contact() = ContactRecord(
    id = getString(0), alias = getString(1), onion = getString(2),
    identityPublic = getBlob(3).copyOf(), signalPeer = getBlob(4).copyOf(),
    pairedAt = getLong(5), displayOnly = getLong(6) != 0L,
)

// validateContact — sem nenhuma regra de doorbell
```

### Como ficou

```kotlin
// listContacts e getContact
"SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only,doorbell_onion,doorbell_token " +
    "FROM contacts ..."

// putContact
mutate(
    contact.alias.length + contact.onion.length + contact.identityPublic.size +
        contact.signalPeer.size + contact.doorbellOnion.length + contact.doorbellToken.size,
) {
    database.execSQL(
        "INSERT INTO contacts(id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only," +
            "doorbell_onion,doorbell_token) VALUES(?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(id_pub) DO UPDATE SET alias=excluded.alias,onion=excluded.onion," +
            "identity_public=excluded.identity_public,signal_peer=excluded.signal_peer," +
            "paired_at=excluded.paired_at,display_only=excluded.display_only," +
            "doorbell_onion=excluded.doorbell_onion,doorbell_token=excluded.doorbell_token",
        arrayOf(contact.id, contact.alias, contact.onion, contact.identityPublic, contact.signalPeer,
                contact.pairedAt, if (contact.displayOnly) 1L else 0L,
                contact.doorbellOnion, contact.doorbellToken),
    )
}

// Column order must match every "SELECT ... FROM contacts" that feeds this mapper:
// id_pub, alias, onion, identity_public, signal_peer, paired_at, display_only,
// doorbell_onion, doorbell_token.
private fun Cursor.contact() = ContactRecord(
    id = getString(0), alias = getString(1), onion = getString(2),
    identityPublic = getBlob(3).copyOf(), signalPeer = getBlob(4).copyOf(),
    pairedAt = getLong(5), displayOnly = getLong(6) != 0L,
    doorbellOnion = getString(7), doorbellToken = getBlob(8).copyOf(),
)
```

O comentário de ordem posicional acima de `Cursor.contact()` espelha o que `Cursor.message()` já
tinha desde o T4.15 (ver a seção "Coluna `forwarded`" acima, "Conferência de ordem de colunas") — o
mesmo problema (mapper lê por índice, todo `SELECT` que o alimenta precisa projetar as mesmas
colunas na mesma ordem) e a mesma solução.

### `validateContact`: três regras novas

```kotlin
// Reserved for T4.17 but validated on the way in, because a malformed value would only be
// discovered much later, by the feature that finally reads it. Empty means "not recorded":
// a contact paired before schema v3, or a display-only one.
if (contact.doorbellOnion.isNotEmpty()) validateOnion(contact.doorbellOnion)
require(contact.doorbellToken.isEmpty() || contact.doorbellToken.size == DOORBELL_TOKEN_BYTES) {
    "Invalid doorbell token"
}
require(contact.doorbellOnion.isNotEmpty() == contact.doorbellToken.isNotEmpty()) {
    "A doorbell address and its token are recorded together or not at all"
}
```

com `private const val DOORBELL_TOKEN_BYTES = 32` no `companion object` ("Matches the doorbell token
minted by `PairingEngine`").

- **Endereço, quando presente, é validado como onion** — mesma `validateOnion` já usada para
  `contact.onion`, então precisa passar pela checagem de formato/checksum v3 de
  `docs/changes/OnionAddress.kt.md` por baixo dela.
- **Token, quando presente, tem exatamente 32 bytes** (`DOORBELL_TOKEN_BYTES`) — o tamanho que
  `PairingEngine` sempre mints para o campo `doorbellToken` da oferta.
- **Os dois juntos ou nenhum dos dois** — não existe estado válido de "tenho endereço de campainha
  mas não sei o token dele" nem o inverso; ambos vêm do mesmo campo assinado ou de nenhum.

### Por que validar um campo reservado, ainda sem leitor

A tentação óbvia é adiar a validação para quando o T4.17 finalmente ler essas colunas. A escolha
aqui é a oposta: `putContact` é o **único** ponto de entrada desse dado vindo da rede (a oferta de
pareamento do peer), e é exatamente onde um valor malformado — token truncado, endereço com checksum
errado, um campo presente sem o outro — é barato de recusar, com uma mensagem que aponta para o
pareamento que acabou de acontecer. Adiar a checagem para o recurso que um dia lerá o dado significa
que um contato com lixo nessas colunas vive no cofre, silenciosamente, até T4.17 existir — e nesse
ponto o erro já não tem contexto nenhum de onde veio.

### Vantagens

- Nenhuma nova consulta e nenhum novo índice: as duas colunas viajam de carona nas mesmas consultas
  que já existiam para `contacts`.
- `validateContact` fecha a porta a dado malformado no momento em que ele entra, em vez de deixar a
  descoberta para quando T4.17 o ler pela primeira vez.
- O comentário de ordem posicional em `Cursor.contact()` documenta o mesmo acoplamento que
  `Cursor.message()` já documentava, com o mesmo nível de explicitação.

## 2026-09-18 — Coluna `doorbell_token_issued` em `contacts`: schema v4 e a assimetria do par de campainha (T4.17)

### Motivo

Fase 2 da feature "campainha" (T4.17) — armazenamento + pareamento. O esquema do cofre subiu de v3
para v4 com a coluna `contacts.doorbell_token_issued` (`BLOB NOT NULL DEFAULT x''`), acrescentada por
`ALTER TABLE` no degrau `4 ->` de `AndroidVaultStorage.migrateStep` (ver
`docs/changes/AndroidVaultStorage.kt.md`).

A leitura de código que motivou a coluna: a v3 guardava só metade do par — `doorbell_onion` (onde
bater) e `doorbell_token` (o que apresentar quando NÓS tocamos a campainha do contato). O token que
ESTE aparelho cunhou PARA aquele contato — o que uma batida vinda dele vai apresentar, e portanto o
único valor contra o qual um ouvinte local poderia verificar — era gerado em
`PairingEngine.newOffer()` (`crypto.random(32)`), publicado dentro da nossa própria oferta assinada e
descartado junto com ela. O cofre sabia tocar e não sabia atender. Cada lado cunha um aleatório
independente por troca: o valor não é derivável de nada e não existe segundo canal autenticado para
combiná-lo depois — o pareamento presencial com SAS é a única janela. `PairingEngine.finish()` agora
devolve esse valor como `PairedContact.doorbellTokenIssued`, e `ContactRecord` ganhou o campo homônimo
(ver `docs/changes/StorageModels.kt.md`, `docs/changes/Pairing.kt.md`). Este arquivo é quem lê,
escreve e valida a coluna nova.

### Como era antes

```kotlin
// listContacts / getContact
"SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only,doorbell_onion,doorbell_token " +
    "FROM contacts ..."

// putContact
mutate(
    contact.alias.length + contact.onion.length + contact.identityPublic.size +
        contact.signalPeer.size + contact.doorbellOnion.length + contact.doorbellToken.size,
) {
    database.execSQL(
        "INSERT INTO contacts(id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only," +
            "doorbell_onion,doorbell_token) VALUES(?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(id_pub) DO UPDATE SET alias=excluded.alias,onion=excluded.onion," +
            "identity_public=excluded.identity_public,signal_peer=excluded.signal_peer," +
            "paired_at=excluded.paired_at,display_only=excluded.display_only," +
            "doorbell_onion=excluded.doorbell_onion,doorbell_token=excluded.doorbell_token",
        arrayOf(..., contact.doorbellOnion, contact.doorbellToken),
    )
}

// Column order must match every "SELECT ... FROM contacts" that feeds this mapper:
// id_pub, alias, onion, identity_public, signal_peer, paired_at, display_only,
// doorbell_onion, doorbell_token.
private fun Cursor.contact() = ContactRecord(
    ..., doorbellOnion = getString(7), doorbellToken = getBlob(8).copyOf(),
)

// validateContact
require(contact.doorbellOnion.isNotEmpty() == contact.doorbellToken.isNotEmpty()) {
    "A doorbell address and its token are recorded together or not at all"
}
```

### Como ficou

```kotlin
// listContacts e getContact
"SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only,doorbell_onion," +
    "doorbell_token,doorbell_token_issued FROM contacts ..."

// putContact
mutate(
    contact.alias.length + contact.onion.length + contact.identityPublic.size +
        contact.signalPeer.size + contact.doorbellOnion.length + contact.doorbellToken.size +
        contact.doorbellTokenIssued.size,
) {
    database.execSQL(
        "INSERT INTO contacts(id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only," +
            "doorbell_onion,doorbell_token,doorbell_token_issued) VALUES(?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(id_pub) DO UPDATE SET alias=excluded.alias,onion=excluded.onion," +
            "identity_public=excluded.identity_public,signal_peer=excluded.signal_peer," +
            "paired_at=excluded.paired_at,display_only=excluded.display_only," +
            "doorbell_onion=excluded.doorbell_onion,doorbell_token=excluded.doorbell_token," +
            "doorbell_token_issued=excluded.doorbell_token_issued",
        arrayOf(..., contact.doorbellOnion, contact.doorbellToken, contact.doorbellTokenIssued),
    )
}

// Column order must match every "SELECT ... FROM contacts" that feeds this mapper:
// id_pub, alias, onion, identity_public, signal_peer, paired_at, display_only,
// doorbell_onion, doorbell_token, doorbell_token_issued.
private fun Cursor.contact() = ContactRecord(
    ..., doorbellOnion = getString(7), doorbellToken = getBlob(8).copyOf(),
    doorbellTokenIssued = getBlob(9).copyOf(),
)
```

O comentário de ordem posicional acima de `Cursor.contact()` — o mesmo mecanismo já usado para
`doorbell_onion`/`doorbell_token` no T4.16 e para `forwarded` no T4.15 — ganhou o décimo nome.

### `validateContact`: a regra de tamanho é reuso, a de correlação é nova e deliberadamente assimétrica

```kotlin
// The token this device issued to the contact (schema v4). Same 32 bytes when present, same
// "empty means not recorded" convention.
require(contact.doorbellTokenIssued.isEmpty() || contact.doorbellTokenIssued.size == DOORBELL_TOKEN_BYTES) {
    "Invalid issued doorbell token"
}
// An implication, deliberately NOT the equivalence the two peer columns get.
require(contact.doorbellTokenIssued.isEmpty() || contact.doorbellToken.isNotEmpty()) {
    "An issued doorbell token requires the peer's doorbell address and token"
}
```

**(a) Tamanho.** `DOORBELL_TOKEN_BYTES` (32) já existia — reservada em 2026-09-17 só para
`doorbell_token` — e foi **reusada**, não duplicada, para `doorbell_token_issued`. O KDoc da constante
passou de "Matches the doorbell token minted by `PairingEngine` (reserved for T4.17)" para deixar
explícito que ela cobre as duas metades do par, ambas `crypto.random(32)`.

**(b) Correlação — implicação, não equivalência.** A regra do par (`doorbellOnion.isNotEmpty() ==
doorbellToken.isNotEmpty()`, T4.16) segue intacta e não foi tocada. A regra nova enforça só um
sentido: token emitido não-vazio EXIGE a metade do par presente. A razão é que este aparelho só pode
ter cunhado um `doorbellTokenIssued` dentro de uma troca de pareamento, e é essa mesma troca que traz
o endereço e o token do par — um registro com token emitido e sem a metade do par não poderia ter
sido produzido por nenhum caminho de código existente, então é recusado como impossível.

A recíproca deliberadamente NÃO é exigida, porque é um estado legítimo e real: todo contato pareado
antes da v4 tem a metade do par e não tem a metade emitida — o segredo foi cunhado, publicado dentro
da oferta antiga e descartado com ela, e não há como recuperá-lo agora. A migração v3→v4 backfilla
`doorbell_token_issued` com `x''` para essas linhas, e não pode inventar o que já foi perdido. Exigir
"as três colunas juntas ou nenhuma" transformaria cada uma dessas linhas honestas e pré-existentes
numa escrita que lança `IllegalArgumentException` na primeira vez que qualquer coisa a regravasse (por
exemplo, um `putContact` que só atualiza a alcunha) — uma regra de validação que quebraria dado
correto só porque ele nasceu antes da feature existir.

### Vantagens

- A validação pega valor malformado na entrada (o único ponto de entrada desse dado é `putContact`,
  alimentado por `PairingEngine.finish`), em vez de deixar a descoberta para quando o T4.17
  finalmente ler a coluna.
- A regra de correlação é forte onde pode ser (recusa o estado impossível: token emitido sem a metade
  do par) e permissiva onde precisa ser (não quebra a linha legitimamente migrada da v3, que nunca
  teve como ter o token emitido).
- `DOORBELL_TOKEN_BYTES` continua sendo fonte única de verdade para o tamanho dos dois tokens, em vez
  de uma segunda constante idêntica.
- Nenhuma nova consulta nem novo índice: a coluna viaja de carona nas mesmas consultas de `contacts`
  que já existiam.
- O dado é capturado sem nenhuma mudança adicional no formato de QR — o token já viajava assinado
  dentro da oferta desde o T4.16; só faltava este lado gravá-lo.
