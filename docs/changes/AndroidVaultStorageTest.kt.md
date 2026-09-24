# app/src/androidTest/kotlin/dev/mx3/nomessages/storage/AndroidVaultStorageTest.kt

## 2026-09-16 — Ajustado para a nova fixture de áudio do cofre-isca (T4.8, mídia inline — parte A: áudio, item A4)

### Motivo

`AndroidVaultStorage.kt` ganhou uma terceira fixture de mídia (um WAV sintético curto), além das
duas imagens PNG que já existiam, para que a bolha de áudio do cofre-isca também mostre uma forma de
onda/duração reais (ver `docs/changes/AndroidVaultStorage.kt.md`). Este teste assumia
explicitamente exatamente 2 anexos, todos PNG — ambas as suposições ficaram desatualizadas.

### Como era antes

```kotlin
val attachments = db.listContacts().flatMap { contact -> db.listMessages(contact.id) }
    .mapNotNull { record -> EnvelopeCodec.decode(record.body) as? Envelope.Attachment }
assertEquals(2, attachments.size)
attachments.forEach { attachment ->
    ...
    assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), plaintext.toByteArray().copyOf(8))
}
```

### Como é agora

```kotlin
assertEquals(3, attachments.size)
attachments.forEach { attachment ->
    ...
    val magicNumber = plaintext.toByteArray().copyOf(4)
    when (attachment.mime) {
        "image/png" -> assertArrayEquals(byteArrayOf(-119, 80, 78, 71), magicNumber)
        "audio/wav" -> assertArrayEquals("RIFF".toByteArray(), magicNumber)
        else -> throw AssertionError("Unexpected decoy attachment mime: ${attachment.mime}")
    }
}
```

### Vantagens

- O teste continua verificando que cada anexo do cofre-isca decifra para o conteúdo esperado do seu
  tipo, agora cobrindo os dois formatos (imagem e áudio) em vez de assumir que todo anexo é PNG.
- O `else -> throw AssertionError(...)` garante que uma futura quarta fixture de tipo desconhecido
  falhe o teste explicitamente, em vez de passar silenciosamente sem checar seu conteúdo.

### Validação

Não executado nesta tarefa (suíte instrumentada fica para o agente final de
teste/emulador/consolidação, por instrução explícita do escopo). Verificado por compilação:
`:app:assembleDebugAndroidTest` → `BUILD SUCCESSFUL`.

## 2026-09-17 — Teste da migração de schema v2 e do round-trip de `forwarded` (Parte B: Encaminhar)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). A coluna
`messages.forwarded` nasce de um `ALTER TABLE` na migração para o schema v2 (ver
`docs/changes/AndroidVaultStorage.kt.md`), não da definição congelada da v1. Precisa haver prova de
que o passo de migração realmente roda em um cofre criado do zero e de que o campo sobrevive ao
insert/leitura.

### Como era antes

Nenhum teste tocava em `PRAGMA user_version` nem no flag; os testes de mensagem
(`typedRecordsAndOpaqueStateRoundTripAsCopies`, `repeatedWritesConsumeReserve...`) só cobriam
`status`/`body`.

### Como é agora — teste novo `forwardedFlagRoundTripsAfterTheSchemaVersionTwoMigration`

Reaproveita integralmente o setup já existente do arquivo (`createVault()`, `manager.unlock(...)`,
`storage.active`, `REAL_PASSWORD`), sem inventar fixture nova:

```kotlin
val vault = createVault()
manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
    val db = requireNotNull(storage.active)
    assertEquals(2L, db.query("PRAGMA user_version").single().values.single())

    val peer = "55".repeat(32)
    db.insertMessage(MessageRecord(id = "forwarded-yes", ..., forwarded = true))
    // Sem argumento `forwarded`: o default do modelo tem que chegar na coluna como 0.
    db.insertMessage(MessageRecord(id = "forwarded-no", ...))

    assertTrue(requireNotNull(db.getMessage("forwarded-yes")).forwarded)
    assertFalse(requireNotNull(db.getMessage("forwarded-no")).forwarded)
    val listed = db.listMessages(peer).associateBy { it.id }
    assertEquals(2, listed.size)
    assertTrue(requireNotNull(listed["forwarded-yes"]).forwarded)
    assertFalse(requireNotNull(listed["forwarded-no"]).forwarded)
    assertEquals(1L, db.query("SELECT forwarded FROM messages WHERE id = ?", arrayOf("forwarded-yes")).single()["forwarded"])
    assertEquals(0L, db.query("SELECT forwarded FROM messages WHERE id = ?", arrayOf("forwarded-no")).single()["forwarded"])
}
```

### O que cada asserção cobre

| asserção | o que provaria se quebrasse |
| --- | --- |
| `PRAGMA user_version == 2` | o passo `if (version < 2) ALTER TABLE ...` não rodou, ou `SCHEMA_VERSION` não foi persistido |
| `getMessage(...).forwarded` | o `SELECT` de `getMessage` ou o índice 6 de `Cursor.message()` estão fora de sincronia |
| `listMessages(...)` (os dois registros) | o mesmo, no caminho de listagem da conversa (o que a tela de chat usa) |
| `MessageRecord(...)` sem o argumento | o default `false` do modelo não está chegando na coluna |
| `SELECT forwarded` cru = `1L`/`0L` | o valor está sendo gravado como texto/`NULL`/booleano em vez do inteiro 0/1 que o `CHECK(forwarded IN (0,1))` aceita |

`db.query("PRAGMA user_version").single().values.single()` segue o mesmo padrão que
`transactionRollsBackBothReserveAndDataOnFailure` e `repeatedWritesConsumeReserve...` já usavam para
ler pragmas.

### Vantagens

- Cobre a migração pelo efeito observável (a versão persistida e a coluna existindo e aceitando 0/1)
  em vez de inspecionar `sqlite_master`, o que a tornaria frágil a reformatações de DDL.
- Um único caso de teste, no estilo do arquivo, sem novo `@Before`/helper.

## 2026-09-17 — Migração ao abrir: cofre v1 atualizado no lugar e recusa de banco mais novo

### Motivo

Um cofre v1 real, num Galaxy Note10+ com Android 12, deixou de abrir depois de uma atualização do
app (`IllegalStateException: Unsupported vault database version`, de `requireSchema` chamada por
`open`). A correção move a migração para o caminho de `open()` — ver
`docs/changes/AndroidVaultStorage.kt.md`, seção "2026-09-17 — Correção: `open()` não migrava o
esquema".

O teste que já existia, `forwardedFlagRoundTripsAfterTheSchemaVersionTwoMigration` (documentado na
seção anterior deste arquivo), cobre **só o caminho de criação do zero**: `user_version` começa em 0
e `initialize()` roda todos os degraus. Ele passava antes e depois do defeito — não teria detectado
nada. O que faltava era prova do caminho de **atualização no lugar**, que é exatamente o que
quebrou no aparelho.

### Como era antes

Nenhum teste do arquivo abria um cofre cujo `user_version` fosse diferente de 2, e nenhum abria o
banco por fora de `AndroidVaultStorage` para escrever nele. O único teste que abria `real.db`
diretamente era `sqlCipherRejectsAnUnrelatedKey`, e só para provar que a chave errada falha.

### Como é agora — dois testes novos

#### 1. `vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened`

Cria o cofre normalmente, grava uma mensagem comum (sem `forwarded`, ou seja, dado de formato v1),
fecha a sessão, **rebaixa `real.db` para v1 na marra** e reabre pelo caminho normal:

```kotlin
val session = manager.unlock(vault, REAL_PASSWORD.copyOf())
val dbKey = session.keys.dbKey.copyOf()   // a sessão zera o material de chave no close()
try {
    requireNotNull(storage.active).insertMessage(MessageRecord(id = "pre-migration", ...))
    session.close()
    assertNull(storage.active)

    downgradeToSchemaVersionOne(vault.resolve("real.db"), dbKey)

    manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
        val db = requireNotNull(storage.active)
        assertEquals(2L, db.query("PRAGMA user_version").single().values.single())
        val restored = requireNotNull(db.getMessage("pre-migration"))
        assertArrayEquals(originalBody, restored.body)
        assertFalse(restored.forwarded)
        ...
        db.insertMessage(MessageRecord(id = "post-migration", ..., forwarded = true))
        // getMessage/listMessages/SELECT cru, no estilo do teste de round-trip já existente
    }
    assertEquals(storage.databaseBytes, Files.size(vault.resolve("real.db")))
} finally {
    dbKey.fill(0)
}
```

O rebaixamento vive em `downgradeToSchemaVersionOne(path, dbKey)`. O SQLite desta versão do
SQLCipher não tem `DROP COLUMN`, então a tabela é **reconstruída** a partir do DDL congelado de v1,
copiado literalmente de `AndroidVaultStorage.createSchemaV1`:

```kotlin
raw.execSQL("DELETE FROM storage_reserve WHERE id IN (SELECT id FROM storage_reserve ORDER BY id LIMIT 4)")
listOf(
    "CREATE TABLE messages_v1(id TEXT PRIMARY KEY NOT NULL,peer_or_group TEXT NOT NULL," +
        "direction INTEGER NOT NULL CHECK(direction BETWEEN 0 AND 1),ts INTEGER NOT NULL,body BLOB NOT NULL," +
        "status INTEGER NOT NULL CHECK(status BETWEEN 0 AND 4)) WITHOUT ROWID",
    "INSERT INTO messages_v1 SELECT id,peer_or_group,direction,ts,body,status FROM messages",
    "DROP TABLE messages",
    "ALTER TABLE messages_v1 RENAME TO messages",
    "CREATE INDEX messages_chat_time ON messages(peer_or_group,ts DESC,id DESC)",
).forEach(raw::execSQL)
raw.rawQuery("PRAGMA user_version=1").use { cursor -> cursor.moveToFirst() }
```

Dois detalhes que custaram atenção e ficam registrados:

- **O índice `messages_chat_time` cai junto com `DROP TABLE messages`** e precisa ser recriado, ou o
  cofre "v1" produzido pelo teste não seria um v1 fiel (e a listagem de conversa passaria a fazer
  varredura de tabela no reopen).
- **Apagar 4 linhas de `storage_reserve` antes.** O banco já está na capacidade fixa com a freelist
  vazia; reconstruir a tabela precisa de páginas. A conexão crua reaplica
  `PRAGMA max_page_count` (é pragma por conexão, e a crua não passa por `openConfigured`), então sem
  liberar reserva o próprio rebaixamento falharia — e, se `max_page_count` não fosse reaplicado, o
  arquivo cresceria e a asserção final de tamanho fixo viraria um falso negativo do teste em vez de
  uma propriedade do código. Isso é feito no helper `rawDatabase(path, dbKey) { ... }`, que também
  força `journal_mode=DELETE` para não deixar `-wal`/`-shm` para trás.

#### 2. `vaultFromANewerAppVersionIsRejectedWithAnExplicitMessage`

Cria o cofre, fecha a sessão, grava `PRAGMA user_version = 99` pela conexão crua e exige que o
destravamento falhe com a mensagem nova:

```kotlin
val error = assertThrows(IllegalStateException::class.java) {
    manager.unlock(vault, REAL_PASSWORD.copyOf())
}
val message = error.message.orEmpty()
assertTrue(message, message.contains("created by a newer app version"))
assertTrue(message, message.contains("schema version 99"))
assertNull(storage.active)
```

A asserção é contra o texto **novo**, não contra o antigo `Unsupported vault database version` — é
justamente a distinção entre "banco velho demais" (migra) e "banco novo demais" (recusa, e o usuário
precisa saber que deve atualizar o app) que a correção introduziu. `assertNull(storage.active)`
prova de quebra que a abertura falha limpa: `open()` fecha a conexão crua e não publica um
`ChatDatabase` meio aberto.

### O que cada asserção cobre

| asserção | o que provaria se quebrasse |
| --- | --- |
| reabrir não lança | `open()` voltou a recusar cofre fora da versão corrente — o defeito original |
| `PRAGMA user_version == 2` | a migração rodou mas não persistiu a versão, ou nem rodou |
| `pre-migration` legível, `forwarded == false` | o `ALTER TABLE` perdeu dados ou o backfill `DEFAULT 0` não chegou nas linhas antigas |
| `post-migration` com `forwarded == true` (get/list/`SELECT` cru) | a coluna criada pela migração no lugar não é equivalente à criada na criação do zero |
| `Files.size(real.db) == storage.databaseBytes` | a migração cresceu o arquivo de capacidade fixa em vez de sacar da reserva |
| mensagem "created by a newer app version" + "schema version 99" | a recusa de banco mais novo sumiu, ou voltou a usar o texto genérico |

### Convenções seguidas

- Reaproveita `manager`/`storage`/`crypto` do `setUp()` e o helper `createVault()` já existentes;
  nenhum `@Before` novo.
- Cópia de `session.keys.dbKey` é zerada em `finally` com `.fill(0)`, como
  `decoyAttachmentsUseNormalHistoryAndDecryptForTheViewer` já faz com a chave de arquivo que
  desembrulha.
- Leitura de pragma por `db.query("PRAGMA ...").single().values.single()`, o mesmo padrão de
  `transactionRollsBackBothReserveAndDataOnFailure` e `repeatedWritesConsumeReserve...`.
- Comentário KDoc acima de cada teste explicando **por que ele existe**, no tom do arquivo.

### Vantagens

- Cobre o caminho que efetivamente quebrou em produção, e não apenas o caminho feliz de criação.
- O rebaixamento usa o DDL congelado de v1 — se alguém um dia editar `createSchemaV1`, a divergência
  aparece aqui em vez de passar despercebida.
- Reforça a invariante de tamanho fixo no ponto mais arriscado de todos: uma escrita de esquema.

### Validação (2026-09-17)

`t31-devices.sh emulator-5556` → `PASS`, `OK (18 tests)`, 0 falhas (16 anteriores + 2 novos).
`:app:assembleDebugAndroidTest` dentro de `BUILD SUCCESSFUL`. Log em
`docs/development/build-logs/android-test-emulator-5556-20260917T193532Z.log`.

## 2026-09-17 — Schema v3: as duas asserções `PRAGMA user_version` migram para 3, novo teste de round-trip das colunas de campainha (T4.16)

### Motivo

O schema do cofre foi de v2 para v3 (`contacts.doorbell_onion`/`doorbell_token`, reservadas para o
T4.17 — ver `docs/changes/AndroidVaultStorage.kt.md`). Os dois testes deste arquivo que já liam
`PRAGMA user_version` esperavam `2L` codificado; precisam esperar `3L` agora, ou passariam a provar a
versão errada do esquema. E o novo par de colunas precisa de um teste de round-trip próprio, do
mesmo jeito que `forwardedFlagRoundTripsAfterTheSchemaVersionTwoMigration` provou o round-trip da v2.

### 1. As duas asserções existentes

**Como era antes**

```kotlin
assertEquals(2L, db.query("PRAGMA user_version").single().values.single())
```

nos dois testes que abrem um cofre recém-criado (o de round-trip de mensagens comuns e o de
round-trip de `forwarded`).

**Como ficou**

```kotlin
assertEquals(3L, db.query("PRAGMA user_version").single().values.single())
```

nos dois pontos. Um cofre criado do zero por `initialize()` roda todos os degraus de migração em
sequência (ver `docs/changes/AndroidVaultStorage.kt.md`), então `user_version` de um cofre recém-criado
é sempre `SCHEMA_VERSION` corrente — `3` agora, não mais `2`. Nenhum outro comportamento desses dois
testes muda; são os únicos dois números que dependiam do valor absoluto da versão do esquema.

### 2. Novo teste: `doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration`

```kotlin
/**
 * Schema v3 (2026-09-17, T4.16) adds `contacts.doorbell_onion` and `contacts.doorbell_token`,
 * reserved for T4.17 and populated from the format-2 pairing offer. Same ALTER TABLE shape as
 * v2, so the same two things are worth proving: the step ran, and the columns round trip.
 *
 * The empty defaults are asserted explicitly, because "" / x'' is the value every contact
 * paired before format 2 legitimately carries - it has to be representable, not rejected.
 *
 * NOT RUN as part of T4.16: this session had no emulator (instrumented validation is a
 * follow-up phase). See docs/development/device-verification.md, row T4.16.
 */
@Test
fun doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration() {
    val vault = createVault()
    manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
        val db = requireNotNull(storage.active)
        assertEquals(3L, db.query("PRAGMA user_version").single().values.single())

        val peer = "77".repeat(32)
        val doorbellOnion = "c".repeat(56) + ".onion"
        val token = ByteArray(32) { (it + 3).toByte() }
        db.putContact(
            ContactRecord(
                id = peer, alias = "com campainha", onion = "d".repeat(56) + ".onion",
                identityPublic = ByteArray(32) { it.toByte() },
                signalPeer = ByteArray(33) { (it + 1).toByte() },
                pairedAt = 9_000,
                doorbellOnion = doorbellOnion, doorbellToken = token,
            ),
        )
        val stored = requireNotNull(db.getContact(peer))
        assertEquals(doorbellOnion, stored.doorbellOnion)
        assertArrayEquals(token, stored.doorbellToken)
        assertEquals(doorbellOnion, db.listContacts().single { it.id == peer }.doorbellOnion)

        // A contact with no doorbell material at all - what every pre-format-2 row becomes.
        val legacy = "88".repeat(32)
        db.putContact(
            ContactRecord(
                id = legacy, alias = "sem campainha", onion = "e".repeat(56) + ".onion",
                identityPublic = ByteArray(32) { (it + 5).toByte() },
                signalPeer = ByteArray(33) { (it + 6).toByte() },
                pairedAt = 9_100,
            ),
        )
        val plain = requireNotNull(db.getContact(legacy))
        assertEquals("", plain.doorbellOnion)
        assertEquals(0, plain.doorbellToken.size)
    }
}
```

Duas coisas verificadas, deliberadamente as mesmas duas categorias que
`forwardedFlagRoundTripsAfterTheSchemaVersionTwoMigration` já verificava para a v2:

- **A migração rodou**: `PRAGMA user_version == 3` no cofre recém-criado.
- **As colunas fazem round trip pelos três caminhos de leitura**: `getContact` (leitura por chave),
  `listContacts` (leitura em lote) e, implicitamente, o `INSERT`/upsert de `putContact` — os mesmos
  três pontos que `docs/changes/ChatDatabase.kt.md` documenta terem sido alterados.
- **O caso vazio é verificado explicitamente**, não só implícito: um segundo contato, criado sem
  passar `doorbellOnion`/`doorbellToken` (usando os defaults de `ContactRecord`), tem que voltar do
  banco com `""` e `ByteArray(0)` — o estado real de todo contato pareado antes do formato de QR 2.
  Sem esse segundo contato, um bug que gravasse lixo em vez do default `''`/`x''` do `ALTER TABLE`
  passaria despercebido pelo teste.

### O que cada asserção prova, e o que quebraria se faltasse

| asserção | o que provaria se quebrasse |
| --- | --- |
| `PRAGMA user_version == 3` | o degrau `3 -> { ... }` não existe mais em `migrateStep`, ou `SCHEMA_VERSION` não foi atualizado |
| `stored.doorbellOnion`/`doorbellToken` (via `getContact`) | o `SELECT ... WHERE id_pub = ?` ou o mapper `Cursor.contact()` não inclui as novas colunas, ou a ordem posicional está errada |
| `listContacts().single { ... }.doorbellOnion` | o `SELECT` de `listContacts` ficou fora de sincronia com o de `getContact` |
| `plain.doorbellOnion == ""` / `plain.doorbellToken.size == 0` | o default `NOT NULL DEFAULT ''`/`x''` da migração não é o que a linha realmente recebe, ou `putContact` está sobrescrevendo o default com outra coisa |

### IMPORTANTE — este teste NUNCA FOI EXECUTADO nesta sessão

Ao contrário dos testes instrumentados anteriores deste arquivo (que têm seções de "Validação" com
log de execução em emulador — por exemplo a seção "2026-09-17 — Migração ao abrir" acima, validada em
`emulator-5556`), **esta sessão não teve nenhum emulador Android disponível**. O teste
`doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration` foi escrito, revisado quanto à
consistência com o schema v3 e com `ChatDatabase`, e compila (faz parte de
`:app:assembleDebugAndroidTest`, que teve `BUILD SUCCESSFUL` — ver a seção de testes do T4.16 em
`docs/changes/AndroidVaultStorage.kt.md` e a fact sheet do T4.16), mas **nunca rodou contra um banco
SQLCipher real**. Nenhuma das asserções acima foi, de fato, observada passando.

Isso não é uma formalidade: `AndroidVaultStorageTest` roda sobre `SQLiteDatabase`/SQLCipher via
JNI, que o JVM puro dos testes de unidade não fornece — só um dispositivo ou emulador Android exercita
esse caminho de verdade. A migração de schema, a leitura de `BLOB` via `getBlob`, e o comportamento
exato de `ALTER TABLE ... ADD COLUMN ... DEFAULT x''` são todos comportamentos do SQLite/SQLCipher
real, não simuláveis em JVM puro. A validação em aparelho/emulador deste teste específico fica como
**fase de acompanhamento**, registrada também em `docs/development/device-verification.md`.

### Vantagens

- Fecha a mesma lacuna de cobertura que o teste de `forwarded` fechou para a v2: prova que a migração
  v2→v3 roda e que as colunas novas são utilizáveis fim a fim, não só que o `ALTER TABLE` compila.
- O caso "sem campainha" torna explícito, em um teste, o estado que hoje é o mais comum no mundo real
  (todo contato pareado antes do T4.16) — não deixa esse caminho coberto só implicitamente por um
  default de schema que ninguém exercitou.
- Honestidade sobre o estado de validação: o teste existe e compila, mas a prova de que ele realmente
  passa em SQLCipher real ainda não foi obtida nesta sessão.

---

## 2026-09-17 (run5, validação em emulador) — a fixture de rebobinar para a v1 não desfazia as colunas da v3

### Como era

`downgradeToSchemaVersionOne` reconstruía apenas `messages`:

```kotlin
raw.execSQL("DELETE FROM storage_reserve WHERE id IN (SELECT id FROM storage_reserve ORDER BY id LIMIT 4)")
listOf(
    "CREATE TABLE messages_v1(...)",
    "INSERT INTO messages_v1 SELECT id,peer_or_group,direction,ts,body,status FROM messages",
    "DROP TABLE messages",
    "ALTER TABLE messages_v1 RENAME TO messages",
    "CREATE INDEX messages_chat_time ON messages(peer_or_group,ts DESC,id DESC)",
).forEach(raw::execSQL)
raw.rawQuery("PRAGMA user_version=1").use { cursor -> cursor.moveToFirst() }
```

### Como é

A mesma reconstrução, mais a de `contacts` na forma v1, e 8 páginas de reserva liberadas em vez de 4:

```kotlin
raw.execSQL("DELETE FROM storage_reserve WHERE id IN (SELECT id FROM storage_reserve ORDER BY id LIMIT 8)")
listOf(
    ... (messages, como antes) ...,
    "CREATE TABLE contacts_v1(id_pub TEXT PRIMARY KEY NOT NULL,alias TEXT NOT NULL,onion TEXT NOT NULL," +
        "identity_public BLOB NOT NULL,signal_peer BLOB NOT NULL,paired_at INTEGER NOT NULL," +
        "display_only INTEGER NOT NULL CHECK(display_only IN (0,1))) WITHOUT ROWID",
    "INSERT INTO contacts_v1 SELECT id_pub,alias,onion,identity_public,signal_peer,paired_at,display_only FROM contacts",
    "DROP TABLE contacts",
    "ALTER TABLE contacts_v1 RENAME TO contacts",
).forEach(raw::execSQL)
```

O KDoc do helper passou a dizer explicitamente que **toda** coluna acrescentada por uma versão
futura precisa ser desfeita ali.

### Por que a mudança foi feita

`vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened` falhava em aparelho com:

```
android.database.sqlite.SQLiteException: duplicate column name: doorbell_onion (code 1): ,
  while compiling: ALTER TABLE contacts ADD COLUMN doorbell_onion TEXT NOT NULL DEFAULT ''
    at dev.mx3.nomessages.storage.AndroidVaultStorage.migrateStep(AndroidVaultStorage.kt:278)
    at dev.mx3.nomessages.storage.AndroidVaultStorage.migrate(AndroidVaultStorage.kt:230)
    at dev.mx3.nomessages.storage.AndroidVaultStorage.open(AndroidVaultStorage.kt:75)
```

(`docs/development/build-logs/android-test-emulator-5556-20260917T212235Z.log`.)

O passo v3 de T4.16 acrescentou `contacts.doorbell_onion` e `contacts.doorbell_token`, mas o helper
continuou desfazendo só a v2. O banco que ele produzia **não era um banco v1**: tinha
`user_version = 1` e, ao mesmo tempo, as colunas da v3. O replay v1→v2→v3 então repetia um
`ALTER TABLE ADD COLUMN` que já tinha sido aplicado.

Este é um defeito **da fixture**, não do código de produção — e vale insistir nisso, porque a
mensagem de erro aponta para `migrateStep` e parece um defeito de migração. Um banco v1 real no
campo (escrito por um build anterior ao T4.16) não tem nenhuma dessas colunas, e o `migrateStep`
está correto. O que a fixture precisava era ser fiel ao que a v1 realmente era.

### Vantagens

- **O caso volta a testar o que promete:** uma migração v1 → v3 in-place sobre um banco que já tem
  dados de usuário, que é exatamente o caminho que um Galaxy Note10+ percorre ao atualizar o app.
  Confirmado: `OK (19 tests)` em `emulator-5556`
  (`android-test-emulator-5556-20260917T212519Z.log`).
- **A fixture volta a ser uma cópia fiel do `createSchemaV1` congelado**, que é a propriedade que a
  torna confiável; um "v1" que carrega colunas da v3 não prova nada sobre v1.
- **O KDoc agora carrega a regra de manutenção**, com o incidente concreto citado, para que a
  próxima versão de esquema não repita o mesmo ciclo de diagnóstico.
- SQLite não tem `DROP COLUMN` nesta versão, então reconstruir a tabela é a única forma; as 4
  páginas extras de reserva cobrem a segunda reconstrução sem que o arquivo de tamanho fixo cresça —
  a mesma troca que o app faz em toda escrita.

## 2026-09-18 — Schema v4: cobertura de `contacts.doorbell_token_issued` (T4.17)

### Motivo

Fase 2 da feature "campainha" (T4.17). `AndroidVaultStorage.kt` ganhou um quarto degrau de migração
(`docs/changes/AndroidVaultStorage.kt.md`) que acrescenta `contacts.doorbell_token_issued` — a
metade do par que faltava: o token que **este** aparelho cunhou para um contato, contra o qual uma
batida vinda dele deveria ser verificada. Schema v3 → v4.

### Como era antes — as três asserções de versão

Os três testes de migração afirmavam `SCHEMA_VERSION = 3`:

```kotlin
assertEquals(3L, db.query("PRAGMA user_version").single().values.single())
```

### Como ficou

As três viraram `4L` — senão os testes de migração existentes quebrariam no primeiro
`SCHEMA_VERSION` novo, denunciando um degrau que na verdade está correto:

```kotlin
assertEquals(4L, db.query("PRAGMA user_version").single().values.single())
```

### Teste novo: `issuedDoorbellTokenRoundTripsAfterTheSchemaVersionFourMigration`

```kotlin
/**
 * Schema v4 (2026-09-18, T4.17) adds `contacts.doorbell_token_issued`: the token THIS device
 * minted for that contact, which is what an incoming ring from it has to present. v3 stored only
 * the peer's half, so the vault could ring a doorbell but had nothing to check one against.
 *
 * Three things are proved here, and the third is the one that matters for existing users:
 *
 * 1. the step ran (`user_version = 4`) and the column round trips through put/get/list;
 * 2. a contact carrying the peer's half and **no** issued half is accepted - that is exactly
 *    what every row migrated from v3 looks like, since the v4 ALTER TABLE backfills x'' and
 *    cannot invent a secret that was already discarded;
 * 3. the one impossible combination - an issued token with no peer half - is refused, together
 *    with a wrong-sized token.
 *
 * NOT RUN as part of T4.17 phase 2: this session had no emulator (instrumented validation is a
 * follow-up phase). See docs/development/device-verification.md.
 */
@Test
fun issuedDoorbellTokenRoundTripsAfterTheSchemaVersionFourMigration() {
    val vault = createVault()
    manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
        val db = requireNotNull(storage.active)
        assertEquals(4L, db.query("PRAGMA user_version").single().values.single())

        val peer = "99".repeat(32)
        val peerToken = ByteArray(32) { (it + 3).toByte() }
        val issuedToken = ByteArray(32) { (it + 70).toByte() }
        val base = ContactRecord(
            id = peer,
            ...
            doorbellOnion = doorbellOnion,
            doorbellToken = peerToken,
            doorbellTokenIssued = issuedToken,
        )
        db.putContact(base)
        val stored = requireNotNull(db.getContact(peer))
        assertArrayEquals(peerToken, stored.doorbellToken)
        assertArrayEquals(issuedToken, stored.doorbellTokenIssued)
        // The two halves are independent secrets, never the same value.
        assertFalse(stored.doorbellToken.contentEquals(stored.doorbellTokenIssued))
        assertArrayEquals(issuedToken, db.listContacts().single { it.id == peer }.doorbellTokenIssued)

        // What a row migrated from v3 looks like: peer half present, issued half empty.
        val migrated = base.copy(id = "aa".repeat(32), doorbellTokenIssued = ByteArray(0))
        db.putContact(migrated)
        assertEquals(0, requireNotNull(db.getContact(migrated.id)).doorbellTokenIssued.size)

        // An issued token without the peer's half could not have come from any pairing.
        assertThrows(IllegalArgumentException::class.java) {
            db.putContact(base.copy(id = "bb".repeat(32), doorbellOnion = "", doorbellToken = ByteArray(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            db.putContact(base.copy(id = "cc".repeat(32), doorbellTokenIssued = ByteArray(31)))
        }
    }
}
```

### O que cada bloco de asserções prova

- **Round-trip** (`putContact` → `getContact` → `listContacts`): a coluna nova está de fato incluída
  no `SELECT` e no mapeador do cursor dos dois caminhos de leitura, na mesma posição, e sobrevive a
  `put`. Sem isso, um `ALTER TABLE` que compila mas nunca é lido de volta passaria despercebido.
- **As duas metades são valores independentes** (`assertFalse(...contentEquals...)`): confirma que o
  teste não está comparando a mesma constante consigo mesma por acidente — `doorbellToken` e
  `doorbellTokenIssued` são, propositalmente, dois arrays de 32 bytes diferentes, do mesmo jeito que
  dois segredos aleatórios cunhados por lados diferentes da troca seriam.
- **Contato com a metade do par e sem a metade emitida é aceito**: é o estado exato de **toda** linha
  migrada de v3, o caso mais comum no mundo real no dia em que este build chegar a um usuário. Se
  `ChatDatabase.validateContact` passasse a exigir as duas metades juntas, todo contato existente
  ficaria irrecuperável no próximo `putContact` — este teste é o que pegaria essa regressão.
- **A combinação impossível é recusada**: um `doorbellTokenIssued` presente sem `doorbellOnion`/
  `doorbellToken` não poderia ter vindo de pareamento nenhum (as três colunas nascem juntas, da mesma
  oferta assinada mais a nossa) — aceitar essa combinação seria aceitar um banco fabricado ou
  corrompido sem avisar. O token de tamanho errado (31 em vez de 32 bytes) cobre o mesmo
  `require(...)` pelo lado do tamanho.

### Por que `downgradeToSchemaVersionOne` não precisou de ajuste

Ao contrário da rodada da v3 (seção "2026-09-17, run5" acima, onde o helper de rebobinar precisou
aprender a desfazer `doorbell_onion`/`doorbell_token`), esta rodada não tocou em
`downgradeToSchemaVersionOne`. O helper reconstrói a tabela `contacts` inteira a partir do DDL
congelado da v1 (`CREATE TABLE contacts_v1(...)`, sem nenhuma das três colunas de campainha) — a
coluna nova (`doorbell_token_issued`) some junto com `doorbell_onion`/`doorbell_token` na mesma
reconstrução, porque nenhuma das três é mencionada no DDL v1. Não há um "esquecer de desfazer a v4"
possível aqui: o helper já reconstrói para a forma completa da v1, não degrau a degrau.

### IMPORTANTE — este teste NÃO FOI EXECUTADO nesta sessão

Assim como o teste de round-trip da v3, `issuedDoorbellTokenRoundTripsAfterTheSchemaVersionFourMigration`
foi escrito e revisado quanto à consistência com o schema v4 e com `ChatDatabase.validateContact`,
mas **esta sessão não teve nenhum emulador Android disponível**. Nenhuma das asserções acima foi, de
fato, observada passando contra um banco SQLCipher real. A validação instrumentada fica como fase
posterior, registrada em `docs/development/device-verification.md`.

### Vantagens

- Fecha a mesma lacuna de cobertura que os testes de `forwarded` (v2) e de
  `doorbell_onion`/`doorbell_token` (v3) fecharam nas respectivas versões: prova que a migração
  v3→v4 roda e que a coluna nova é utilizável fim a fim, não só que o `ALTER TABLE` compila.
- O caso mais comum do mundo real — contato com a metade do par e sem a metade emitida — é coberto
  explicitamente, não deixado implícito num default de schema que ninguém exercitou.
- A única combinação impossível das três colunas juntas é recusada por teste, não só por
  `require(...)` nunca exercitado.
- Honestidade sobre o estado de validação: os testes existem e compilam, mas a prova de que passam
  em SQLCipher real ainda não foi obtida nesta sessão.

## 2026-09-23 — `cipherMemorySecurityIsOnImmediatelyAfterOpenForBothSlots` (T4.7b)

### Motivo

`AndroidVaultStorage.openConfigured` passou a aplicar `PRAGMA cipher_memory_security = ON` via
`SQLiteDatabaseHook.preKey`, antes da chave ser processada, em vez de só depois (ver
`docs/changes/AndroidVaultStorage.kt.md`). O pedido do plano era explícito: "verificar PRAGMA
cipher_memory_security retorna 1 logo após abrir", em SQLCipher real — o único jeito de observar isso
de fato é um teste instrumentado, já que o comportamento depende da lib nativa.

### Como ficou

```kotlin
@Test
fun cipherMemorySecurityIsOnImmediatelyAfterOpenForBothSlots() {
    val vault = createVault()
    manager.unlock(vault, REAL_PASSWORD.copyOf()).use {
        val db = requireNotNull(storage.active)
        assertEquals("1", db.query("PRAGMA cipher_memory_security").single().values.single().toString())
    }
    manager.unlock(vault, PANIC_PASSWORD.copyOf()).use {
        val db = requireNotNull(storage.active)
        assertEquals("1", db.query("PRAGMA cipher_memory_security").single().values.single().toString())
    }
}
```

**Executado de fato em `emulator-5556` (AVD `nomessages35`, Android 15/API 35, x86_64) nesta sessão** —
a suíte instrumentada inteira (21 casos) passou, `OK (21 tests)`, log em
`docs/development/build-logs/android-test-emulator-5556-20260923T141311Z.log`.

### Achado de dispositivo durante a escrita deste teste

A primeira versão comparava com `1L` (Long), como os testes de `PRAGMA user_version` já faziam, e
falhou em SQLCipher real: `expected: java.lang.Long<1> but was: java.lang.String<1>`. Ao contrário de
`PRAGMA user_version`, o SQLCipher responde a este pragma específico com um valor de tipo de coluna
SQLite TEXT, não INTEGER — `AndroidVaultStorage.pragmaLong` já convivia com isso internamente porque
lê via `cursor.getLong`, que converte independente do tipo declarado; `ChatDatabase.query` preserva o
tipo nativo da coluna (`cursor.valueAt`), então a asserção aqui compara por string
(`.toString()`) em vez de assumir `Long`. Isto não é uma falha de app: é uma peculiaridade real do
SQLCipher que só apareceu ao rodar contra o binário nativo em vez de simular em JVM puro.

### Vantagens

- Prova, em SQLCipher real (não simulado), que o pragma está em efeito imediatamente após
  `manager.unlock`, para os dois slots — a garantia central que o T4.7b pedia.
- Documenta uma peculiaridade real de tipo do SQLCipher que outro teste futuro sobre pragmas
  poderia repetir sem essa observação.
- A afirmação mais forte — que `preKey` de fato roda **antes** do `PRAGMA key` interno, não só que o
  pragma está ativo depois — não é observável a partir de Kotlin; fica documentada com a evidência
  do AAR em `docs/security-model.md` e `docs/changes/AndroidVaultStorage.kt.md`, não fingida aqui
  como testada.

## 2026-09-23 — Dois casos novos para a reserva fixa de mídia e a cobertura cega (T4.1)

### Motivo

Ver `docs/security-model.md` ("Fixed media reservation and blind cover growth", T4.1) e
`docs/changes/MessagingEngine.kt.md`/`docs/changes/AndroidVaultStorage.kt.md`. A lógica em si
(checagem de capacidade, blob de cobertura) vive em `MessagingEngine.storeAttachment`, não em
`AndroidVaultStorage` — então os dois testes constroem um `MessagingEngine` de verdade (crypto,
`PairingIdentity` restaurada do `db.getMeta(StorageKeys.IDENTITY)` de um contato-isca já pareado com
sessão Signal persistida — o mesmo fixture que `syntheticDecoyContactsHavePersistedSignalSessions` já
usa) contra um `AndroidVaultStorage`/vault reais, em vez de testar só a camada de storage isolada.

### Como ficou

```kotlin
@Test
fun sendAttachmentGrowsBothSlotsEquallyViaABlindCoverOnTheOtherSlot() {
    val vault = createVault()
    val beforeReal = mediaBytes(realFiles); val beforeDecoy = mediaBytes(decoyFiles)
    assertEquals(beforeReal, beforeDecoy)
    val realNamesBefore = fileNames(realFiles); val decoyNamesBefore = fileNames(decoyFiles)

    val session = manager.unlock(vault, PANIC_PASSWORD.copyOf())
    // ...restaura a identidade da isca, constrói um MessagingEngine de verdade...
    val fileId = engine.sendAttachment(contactId, "photo.jpg", "image/jpeg", payload.copyOf())

    val grownBytes = mediaBytes(decoyFiles) - beforeDecoy
    assertTrue(grownBytes > 0)
    assertEquals(grownBytes, mediaBytes(realFiles) - beforeReal)
    assertEquals(mediaBytes(realFiles), mediaBytes(decoyFiles))
    assertEquals(setOf("$fileId.bin"), fileNames(decoyFiles) - decoyNamesBefore)
    assertEquals(1, (fileNames(realFiles) - realNamesBefore).size)

    session.close()
    storage.beforeExport(vault) // não lança: a checagem de paridade de mídia de T4.1 passa.
}

@Test
fun sendAttachmentFailsCleanlyWhenTheMediaReservationIsFull() {
    val smallStorage = AndroidVaultStorage(context, crypto, capacityMiB = 16, mediaCapacityMiB = 16)
    // ...duas escritas de ~7 MiB cabem, a terceira excede os 16 MiB e...
    val failure = assertThrows(MessagingError::class.java) { engine.sendAttachment(...) }
    assertEquals(MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED, failure.code)
    // ...e nenhum dos dois slots ficou maior que a reserva depois da tentativa que falhou.
}
```

### Uma armadilha descoberta e corrigida durante a escrita

A primeira versão do primeiro teste assumia que `real.files`/`decoy.files` estariam vazios antes do
envio e comparava o único arquivo de cada diretório contra `"$fileId.bin"`. Isso falhou na primeira
execução no `emulator-5556` (`expected:<[bbc11...bin]> but was:<[media-1.wff]>`): os dois diretórios
já têm mídia de fixture do setup (`createSetupMedia`, mais as fixtures extras do `seedDecoy`) antes
de qualquer teste rodar. A correção compara **conjuntos de nomes antes/depois** (`fileNames`, novo
helper) e verifica só o que é novo, não o diretório inteiro — mais robusto a qualquer fixture futura
que mude a contagem de arquivos semeados.

### Por que estes testes rodam contra o stack real, não um fake

`TestCrypto` (já existente neste arquivo) é uma implementação JVM pura de `Crypto`, mas
`PairingIdentity`/as sessões Signal são código puro do `core`, não JNI — então um `MessagingEngine`
completo (incluindo `identity.sessions.encrypt`, exercitado dentro de `sendApplication`) roda sem
depender de libsignal nativo nem de Tor. O que continua exigindo o emulador é
`AndroidVaultStorage`/SQLCipher em si (já a razão de este arquivo inteiro ser `androidTest`), não a
lógica de T4.1 adicionada.

### Vantagens

- Prova o comportamento fim a fim (envio real → checagem de capacidade → escrita → cobertura) contra
  o `MessagingEngine`/`AndroidVaultStorage` de produção, não uma reimplementação de teste da lógica.
- O caso de esgotamento prova a "falha limpa" com uma reserva pequena (16 MiB) em vez de precisar
  escrever centenas de megabytes para provar o limite.
- `fileNames`/`mediaBytes` (o segundo já existente, reaproveitado) ficam disponíveis para qualquer
  teste futuro que precise da mesma garantia de paridade de diretório.

### Verificação

Suíte instrumentada completa em `emulator-5556`: `OK (23 tests)`, log
`docs/development/build-logs/android-test-emulator-5556-20260923T153420Z.log` (a primeira tentativa,
antes da correção do fixture, falhou 1/23 e está em `...T152933Z.log`, mantida como evidência do
processo). `:app:testDebugUnitTest`/`:app:lintDebug`/`:app:assembleDebug`/
`:app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL`.

## 2026-09-23 (revisão P1 do T4.1) — Dois casos novos: reset de senha de pânico após crescimento, cobertura idempotente numa retentativa

### Motivo

Ver `docs/changes/VaultManager.kt.md` e `docs/changes/MessagingEngine.kt.md` para os dois achados P1
completos. Resumo: (1) `VaultManager.resetPanicPassword` falhava em qualquer cofre que já tivesse
mídia real, porque reconstruía `decoy.files` do zero (pequeno) enquanto `real.files` mantinha o
tamanho crescido; (2) `MessagingEngine.storeAttachment` podia perder a cobertura cega permanentemente
se o processo morresse entre a escrita primária (já durável) e o commit da transação que também
gravaria a cobertura, porque a chamada a `coverSiblingSlot` era condicionada a `!existing`.

### Caso novo 1 — `resetPanicPasswordSucceedsAfterMediaHasGrownPastTheSetupFixtureSize`

Desbloqueia como **isca** primeiro (`PANIC_PASSWORD`, não `REAL_PASSWORD`) e envia um anexo real por
esse lado — do mesmo jeito que `sendAttachmentGrowsBothSlotsEquallyViaABlindCoverOnTheOtherSlot` —
crescendo `decoy.files` diretamente e `real.files` via a cobertura cega de `storeAttachment` no slot
irmão. Um cofre recém-criado não tem `StorageKeys.IDENTITY` no slot real ainda (só o
`DecoyFactory`/`seedDecoy` pré-semeia essa chave, para a isca; a identidade real só é escrita depois
por `NoMessagesController.activate`, fora do escopo deste teste), então montar um `MessagingEngine`
exige a sessão isca — mas como a cobertura cega espelha o crescimento no slot irmão
independentemente de qual lado está aberto, `real.files` termina crescido de qualquer jeito, que é
exatamente o estado que este teste precisa antes de exercitar o `resetPanicPassword` de verdade.
Depois disso, desbloqueia como **real** e chama `manager.resetPanicPassword(vault, realSession,
novaSenha)` de verdade contra o `AndroidVaultStorage`/SQLCipher reais (não um fake). Antes da correção
em `VaultManager`/`AndroidVaultStorage`, esta chamada lançava `IllegalStateException("Vault media
allocations differ")` — o teste teria falhado de imediato. Depois: confirma que (a) não lança, (b)
`real.files` fica byte a byte do mesmo tamanho de antes do reset, (c) `decoy.files` reconstruído fica
igual a `real.files`, e (d) a nova senha de pânico realmente resolve para `VaultSlot.DECOY` e
`beforeExport` continua aceitando o cofre depois.

### Caso novo 2 — `storeAttachmentWritesTheCoverOnARetryThatFindsThePrimaryFileAlreadyDurable`

Simula a janela de crash diretamente, sem depender de matar o processo de verdade: coloca o arquivo
primário do anexo no disco manualmente (`Files.write(decoyFiles.resolve("$fileIdHex.bin"),
ciphertextBytes)`) — exatamente o que uma escrita primária durável (fsync + rename) de uma tentativa
anterior teria deixado — sem passar por `storeAttachment` nenhuma vez, e sem nenhum `FileRecord` em
`db` (`db.getFile(fileIdHex)` confirmado `null`, isto é, a transação da tentativa anterior nunca
commitou). Em seguida invoca o método privado `MessagingEngine.storeAttachment` uma única vez via
reflexão Kotlin/Java (`getDeclaredMethod(...).apply { isAccessible = true }.invoke(engine, envelope)`)
— exatamente a chamada que uma retentativa real (reenvio do remetente, mensagem redelivered) faria.
Confirma que a cobertura é escrita no slot irmão apesar de `existing == true` (antes da correção, o
gate `if (!existing)` teria pulado esta chamada e o teste teria falhado: `afterReal == beforeReal`
enquanto `afterDecoy > beforeDecoy`), que os dois slots crescem pelo mesmo tanto, e que
`db.getFile(fileIdHex)` passa a resolver (a retentativa também completa o `db.putFile` que a
tentativa anterior não tinha chegado a commitar).

Por que reflexão: `fileId` é gerado aleatoriamente dentro de `sendAttachment`/`storeAttachment` sem
nenhum ponto de injeção público, então não há como forçar duas chamadas públicas a colidirem no mesmo
`fileId` para simular a retentativa pela API pública. Chamar o método privado diretamente com um
`Envelope.Attachment` montado à mão (via `EncryptedFiles(crypto)`, os mesmos primitivos que
`sendAttachment` usa) reproduz exatamente o código sob teste sem precisar de um seam de teste novo em
produção.

### Vantagens

- Os dois casos provam exatamente os dois achados P1 contra o código de produção real
  (`VaultManager`/`AndroidVaultStorage`/`MessagingEngine`/SQLCipher), não uma reimplementação de
  teste da lógica corrigida.
- O caso 1 teria falhado imediatamente contra o código de antes da correção — não é um teste que só
  passaria a falhar por acidente futuro, mas um que já teria pego o bug relatado.
- O caso 2 prova a idempotência sem precisar de um seam de teste em produção nem de matar o processo
  de teste de verdade (o que não seria confiável/determinístico num androidTest).

### Correção de fixture no caso 1 (mesma sessão, antes da primeira execução completa)

A primeira versão do caso 1 desbloqueava como **real** (`REAL_PASSWORD`) para o passo de crescimento
e falhou de imediato com `IllegalArgumentException: Required value was null` em
`requireNotNull(db.getMeta(StorageKeys.IDENTITY))` — um cofre recém-criado não tem essa chave de meta
no slot real ainda (só `DecoyFactory`/`seedDecoy` a pré-semeiam, e só para a isca; a identidade real só
é escrita depois por `NoMessagesController.activate`, fora do escopo deste teste). Corrigido trocando
para `PANIC_PASSWORD` (isca) nesse passo — mesmo padrão que a referência
(`sendAttachmentGrowsBothSlotsEquallyViaABlindCoverOnTheOtherSlot`) já usa — já que a cobertura cega de
`storeAttachment` espelha o crescimento em `real.files` de qualquer jeito, independente de qual lado
está aberto. Um engano de fixture do próprio teste, não um defeito do código de produção sob teste;
documentado aqui porque é exatamente o tipo de detalhe que pareceria, à primeira vista, uma falha da
correção em si.

### Verificação

`:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL`. Suíte instrumentada em `emulator-5556`: `OK (25
tests)`, 0 falhas (`docs/development/build-logs/android-test-emulator-5556-20260923T170045Z.log`; a
primeira tentativa, antes da correção de fixture acima, falhou 1/25 exatamente no caso 1 novo, log
preservado como evidência em `...T165521Z.log`).
