# Android storage API

`AndroidVaultStorage` is the Android SQLCipher implementation of the core
`VaultStorage` lifecycle contract. Calls are synchronous and must stay on the
application's single serialized IO dispatcher. A storage instance exposes at
most one selected database through `active`.

```kotlin
class AndroidVaultStorage(context: Context, crypto: Crypto, capacityMiB: Int = 256) : VaultStorage {
    val active: ChatDatabase?
    val databaseBytes: Long
    val maxPageCount: Int
    override fun initialize(directory: Path, slot: VaultSlot, keys: VaultKeys)
    override fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable
    override fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys)
    override fun beforeExport(directory: Path)
}
```

The handle returned by `open` owns the selected `ChatDatabase`. Closing it
closes SQLCipher and atomically clears `active`. `beforeExport` rejects an open
handle. SQLCipher keys are passed as `ByteArray`; they are never interpolated
into SQL or logged.

The typed database contract is:

```kotlin
enum class MessageDirection { INCOMING, OUTGOING }
enum class MessageStatus { PENDING, SENT, DELIVERED, READ, FAILED }

data class ContactRecord(
    val id: String,                 // lowercase Ed25519 public-key hex
    val alias: String,
    val onion: String,
    val identityPublic: ByteArray,
    val signalPeer: ByteArray,
    val pairedAt: Long,
    val displayOnly: Boolean = false,
)
data class ChatRecord(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val lastMessage: ByteArray?,
    val lastTimestamp: Long?,
    val lastOutgoing: Boolean = false,
    val lastStatus: MessageStatus? = null,
)
data class MessageRecord(
    val id: String,
    val peerOrGroup: String,
    val direction: MessageDirection,
    val timestamp: Long,
    val body: ByteArray,
    val status: MessageStatus,
)
data class PendingFrame(val id: String, val destinationOnion: String, val frame: ByteArray, val createdAt: Long)
data class FileRecord(
    val id: String,
    val relativePath: String,
    val encryptedParameters: ByteArray,
    val size: Long,
    val epoch: Long,
    val displayName: String,
    val mimeType: String,
)
data class GroupRecord(
    val id: String,
    val name: String,
    val state: ByteArray,
    val members: List<String>,
    val coordinator: String,
    val createdAt: Long,
)
data class PairEvidence(val firstId: String, val secondId: String, val evidence: ByteArray, val pairedAt: Long)

class ChatDatabase : AutoCloseable {
    fun getMeta(key: String): ByteArray?
    fun putMeta(key: String, value: ByteArray)
    fun getBlob(namespace: String, key: String): ByteArray?
    fun putBlob(namespace: String, key: String, value: ByteArray)
    fun removeBlob(namespace: String, key: String): Boolean
    fun listBlobs(namespace: String): Map<String, ByteArray>
    fun query(sql: String, bindArgs: Array<out Any?> = emptyArray()): List<Map<String, Any?>>

    fun listContacts(): List<ContactRecord>
    fun getContact(id: String): ContactRecord?
    fun putContact(contact: ContactRecord)
    fun renameContact(id: String, alias: String): Boolean

    fun listChats(): List<ChatRecord>
    fun unreadCounts(): Map<String, Int>
    fun getMessage(id: String): MessageRecord?
    fun listMessages(peerOrGroup: String, beforeTimestamp: Long? = null, limit: Int = 100): List<MessageRecord>
    fun insertMessage(message: MessageRecord)
    fun updateMessageStatus(id: String, status: MessageStatus): Boolean
    fun markChatRead(peerOrGroup: String): Int

    fun listPendingFrames(limit: Int = 100): List<PendingFrame>
    fun listPendingFrameIds(limit: Int = 1000): List<String>
    fun getPendingFrame(id: String): PendingFrame?
    fun putPendingFrame(frame: PendingFrame)
    fun acknowledgePendingFrame(id: String): Boolean

    fun getFile(id: String): FileRecord?
    fun putFile(file: FileRecord)
    fun getGroup(id: String): GroupRecord?
    fun listGroups(): List<GroupRecord>
    fun putGroup(group: GroupRecord)
    fun getPairEvidence(firstId: String, secondId: String): PairEvidence?
    fun listPairEvidence(): List<PairEvidence>
    fun putPairEvidence(evidence: PairEvidence)

    fun <T> transaction(block: ChatDatabase.() -> T): T
    override fun close()
}

object StorageKeys {
    const val IDENTITY = "identity"
    const val ONION_SEED = "onion_seed"
}
```

Returned byte arrays are copies owned by the caller. IDs, relative paths,
onion destinations, aliases, names, MIME types, limits, and timestamps are
validated before SQL execution. `query` is an integration escape hatch for
root-owned runtime code; callers own the SQL, while bound values continue to
keep secrets out of SQL text.

## Contador de não lidas (apenas local)

`MessageStatus.READ` é estado **exclusivamente local**: nenhum recibo de leitura
entra ou sai pela rede e nenhum tipo de envelope novo foi criado para isso. Um
par nunca descobre se, ou quando, a mensagem dele foi lida — decisão registrada
em T4.2 do roteiro de release.

`unreadCounts()` devolve, por chat, quantas mensagens `INCOMING` ainda não estão
em `READ`. É uma única varredura agrupada (`GROUP BY peer_or_group`), atendida
pelo índice `messages_chat_time`, e o mesmo enunciado SQL roda no cofre real e
no cofre isca: o custo não depende de qual conversa está aberta nem de qual
cofre está selecionado.

`markChatRead(peerOrGroup)` grava `READ` nas mensagens `INCOMING` daquele chat e
devolve quantas linhas mudaram. Só a direção de entrada é tocada, de modo que os
tiques de entrega que o remetente vê continuam significando exatamente o que
significavam antes. A estimativa de escrita é de zero bytes, como em
`updateMessageStatus`: a coluna de status é reescrita no lugar, nenhuma página da
reserva é consumida e a chamada não falha por capacidade esgotada.

A isca é semeada com todas as mensagens em `READ` (`AndroidVaultStorage.seedDecoy`),
portanto abre sem nenhum selo e `markChatRead` não altera linha alguma ali. A
diferença de tempo observável é entre "conversa com mensagens novas" e "conversa
já lida", que existe igualmente nos dois cofres; não há ramo de código, consulta
ou tabela que distinga real de isca.

`FileRecord.id` is the lowercase hex encoding of the encrypted file codec's
16-byte file ID. `encryptedParameters` wraps the fresh 32-byte per-file key as
`crypto.seal(vaultFileKey, perFileKey, fileRecord.id.toByteArray())`.

## Política de migração de esquema

A versão do esquema vive no próprio arquivo, em `PRAGMA user_version`, e a
migração é determinística a partir dela. As regras, todas em
`AndroidVaultStorage`:

- **Roda automaticamente nos dois pontos de entrada.** `open()` migra todo cofre
  que abre, e `initialize()` migra o cofre recém-criado (que entra em
  `user_version = 0` e portanto roda todos os degraus). Um cofre escrito por um
  build anterior é **atualizado no lugar** ao ser destravado, nunca recusado.
- **Uma única transação.** Todos os degraus que faltam mais a gravação do novo
  `user_version` acontecem dentro de um só `beginTransaction`/
  `setTransactionSuccessful`, e a versão é relida depois de gravada. Uma
  interrupção no meio deixa o cofre na versão antiga, íntegro, pronto para
  tentar de novo — nunca num estado meio migrado.
- **Incremental e extensível.** Um degrau por versão (`migrateStep(from, to)`),
  replicados em ordem desde o que estiver em disco até `SCHEMA_VERSION`. Um
  degrau declarado para uma versão de destino inexistente falha com nome
  próprio, em vez de gravar uma versão cujo esquema não existe.
- **Versão mais nova que o build é recusada**, com mensagem explícita ("created
  by a newer app version"), **antes** de abrir qualquer transação e antes de
  tocar em qualquer tabela: um banco escrito por um build futuro pode conter
  tabelas ou colunas que este build não entende.
- **Nunca cresce o arquivo de capacidade fixa.** Se um degrau precisar de
  páginas, ele as saca de `storage_reserve` exatamente como qualquer outra
  escrita (mesma política de `ChatDatabase.consumeReserve`); `max_page_count`
  continua sendo o teto e é reconferido depois da migração, e `open()` revalida
  `Files.size(...) == databaseBytes` no cofre já migrado.
- **Idêntica para os dois slots.** Não existe parâmetro `VaultSlot` nem ramo
  real/isca no código de migração — requisito de indistinguibilidade. Cada um
  dos dois bancos é migrado de forma independente, na primeira vez em que ele
  próprio for aberto depois da atualização; essa primeira abertura é mais lenta
  (uma transação extra), as seguintes caem no retorno imediato
  `version == SCHEMA_VERSION`. O custo depende do arquivo já ter sido migrado ou
  não, jamais de qual cofre ele é.

Databases use
4 KiB encrypted pages, `temp_store=MEMORY`, `journal_mode=DELETE`,
`secure_delete=ON`, no auto-vacuum, a 256 MiB `max_page_count`, and an encrypted
reserve table. Each typed mutation releases conservative reserve chunks inside
the same transaction before writing. When reserve is exhausted, the whole
mutation fails and rolls back; the database is never grown beyond the fixed
allocation. Maintenance must replenish and realign both vaults while both keys
are available.

Instrumentation may construct the adapter with `capacityMiB = 16` to keep test
setup bounded. Production uses the 256 MiB default. Outbox payloads are stored
once in `wire_blobs` by SHA-256 digest; destination rows reference the digest.
Acknowledging the last reference removes the payload in the same transaction.
Capacity exhaustion fails the whole write without eviction.

Setup writes two equal-size encrypted media objects under both media roots.
The decoy database references viewable synthetic attachments; the real
copies are unreferenced allocation cover. Runtime media growth requires a
separate fixed-capacity reserve policy in root-owned file lifecycle code.
Four decoy contacts are created through the complete bilateral PairingEngine
ceremony. Only the selected decoy identity, its official Signal sessions,
signed pair evidence, contacts, and onion seed are persisted; each synthetic
peer identity is closed and discarded, leaving a valid permanently-offline
contact rather than a fake session implementation.
