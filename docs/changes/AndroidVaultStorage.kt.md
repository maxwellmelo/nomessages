# AndroidVaultStorage.kt — mudanças

## 2026-09-14 — Correção de crash em Android 12/13 (`NewApi`, T1.4)

### Como era antes

Em `beforeExport`, ao varrer os sidecars do SQLCipher (`-wal`, `-shm`,
`-journal`):

```kotlin
val sidecar = Path.of(database.toString() + suffix)
```

O `:app:lintDebug` reprovava com erro:

> `NewApi`: Call requires API level 34 (current min is 31):
> `java.nio.file.Path#of` — `AndroidVaultStorage.kt:109`

### Como ficou

```kotlin
// Path.of requires API 34; resolveSibling is available since API 26.
val sidecar = database.resolveSibling(database.fileName.toString() + suffix)
```

### Por que a mudança

Este **não** era um aviso de estilo: era um bug de runtime real e já presente.

`java.nio.file.Path.of` é uma API de Java 11 que o Android só expõe a partir do
**API 34**. O `minSdk` do projeto é **31**. Em qualquer aparelho Android 12 ou
13, esta linha lançaria `NoSuchMethodError` — e ela está no caminho de
`beforeExport`, ou seja, o crash aconteceria exatamente no momento em que o
usuário tenta **exportar o cofre**, a operação em que perder o processo é mais
caro. Como a suíte instrumentada ainda não rodou em aparelho (Fase 3 do plano),
nada tinha exercitado esse caminho.

`Path.resolveSibling` existe desde o API 26 e é semanticamente idêntico aqui: o
sidecar sempre fica no mesmo diretório do `.db`. A versão nova ainda é mais
correta em intenção — declara "irmão deste arquivo" em vez de reconstruir um
caminho absoluto por concatenação de string, o que evita qualquer dependência do
formato textual do caminho.

### Verificação de abrangência

`Path.of` foi procurado em todo o módulo `app/`: esta era a **única** ocorrência.
`core/src/main` não usa `Path.of` (e, sendo JVM puro, poderia). Nenhum outro
ponto do app precisa de correção equivalente.

### Vantagens

- Elimina um `NoSuchMethodError` certo em Android 12/13 na exportação do cofre.
- `:app:lintDebug` fica com zero erros.
- Mesma semântica, API mínima 26, sem manipulação textual de caminho.
- Nenhuma mudança de comportamento em API ≥ 34: o resultado é byte a byte o
  mesmo caminho.

### Regressão

`:app:testDebugUnitTest` — 22 testes, 0 falhas, 0 erros, 0 pulados.

## 2026-09-15 — Revisão T4.2: a isca também precisa poder mostrar selo de não lida (P3)

### Como era antes

```kotlin
contacts.forEachIndexed { contactIndex, contact ->
    conversations[contactIndex].forEachIndexed { messageIndex, body ->
        val timestamp = now - (contactIndex * 9L + conversations[contactIndex].size - messageIndex) * DAY_MILLIS
        ...
        status = MessageStatus.READ,
    )
}
```

**Todas** as mensagens semeadas na isca nasciam `READ`. Com o contador local de não lidas de T4.2,
`unreadCounts()` devolve mapa vazio no cofre isca, sempre: nenhum selo, nenhum carimbo destacado,
em nenhuma circunstância. Isso é um distinguidor real/isca estável — um cofre real em uso normal
quase sempre tem pelo menos uma conversa não lida, e um cofre que **nunca** consegue mostrar uma se
denuncia sob inspeção coagida.

### Como ficou

```kotlin
private val UNREAD_DECOY_CHATS = setOf(0, 2)
private const val HOUR_MILLIS = 60L * 60 * 1000
```

```kotlin
val unreadTail = contactIndex in UNREAD_DECOY_CHATS &&
    messageIndex == conversations[contactIndex].lastIndex
val timestamp = if (unreadTail) now - (contactIndex + 1L) * HOUR_MILLIS
    else now - (contactIndex * 9L + conversations[contactIndex].size - messageIndex) * DAY_MILLIS
...
status = if (unreadTail) MessageStatus.DELIVERED else MessageStatus.READ,
```

Duas das quatro conversas (Ana e Família) terminam numa mensagem de entrada recente — 1 h e 3 h
atrás — em `DELIVERED`. As demais continuam idênticas.

### Vantagens

- A isca passa a apresentar duas conversas com selo "1" e carimbo recente, como um cofre real em
  uso. O distinguidor deixa de existir sem inventar nada novo: `DELIVERED` numa mensagem de entrada
  é exatamente o estado que o motor real grava até a conversa ser aberta.
- A última mensagem de cada conversa semeada já era `INCOMING` (índice 2, `2 % 2 == 0`), então
  nenhuma direção mudou — só o status e o carimbo da cauda.
- Abrir a conversa na isca marca como lida pelo mesmo `markChatRead` do cofre real: o comportamento
  seguinte também é indistinguível, não só o estado inicial.
- Nenhuma linha nova é inserida, então a alocação fixa do `.db` (`databaseBytes`) não muda, e o
  gate 2 (bytes exatos de `real.db`/`decoy.db`) continua valendo.
- `seedDecoy` roda só na criação do cofre, então não há migração: cofres já existentes seguem como
  estão e não há caminho de reescrita a proteger.

### Por que a mudança foi feita

Achado P3 da revisão (`ChatDatabase.kt:168` / `AndroidVaultStorage.seedDecoy`), levantado pela
lente de segurança sobre o contador local de não lidas de T4.2.

## 2026-09-15 — T3.1: PRAGMA que devolve linha não pode usar `execSQL` (SQLCipher 4)

Correção do defeito que derrubou 11 dos 12 casos da primeira execução da suíte
instrumentada (`docs/development/build-logs/android-test-emulator-5556-20260915T140427Z.log`):

> `android.database.sqlite.SQLiteException: unknown error (code 0): Queries can
> be performed using SQLiteDatabase query or rawQuery methods only.`
> em `AndroidVaultStorage.openConfigured` -> `initialize`

### Como era antes

```kotlin
database.rawQuery("PRAGMA journal_mode=DELETE").use { cursor ->
    check(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true))
}
database.execSQL("PRAGMA temp_store=MEMORY")
database.execSQL("PRAGMA secure_delete=ON")
database.execSQL("PRAGMA auto_vacuum=NONE")
database.execSQL("PRAGMA foreign_keys=ON")
database.execSQL("PRAGMA synchronous=FULL")
database.execSQL("PRAGMA cipher_memory_security=ON")
...
database.rawQuery("PRAGMA max_page_count=$maxPageCount").use { cursor ->
    check(cursor.moveToFirst() && cursor.getLong(0) == maxPageCount.toLong())
}
```

E, em `migrate`:

```kotlin
database.execSQL("PRAGMA user_version=$SCHEMA_VERSION")
```

### Como ficou

```kotlin
// Keep the original order: the cipher pragmas and the journal mode must settle before
// any table is touched, and max_page_count is applied last so it caps an already
// configured database.
val journalMode = applyPragma(database, "journal_mode=DELETE")
check(journalMode.equals("delete", ignoreCase = true)) {
    "SQLite journal mode is '$journalMode' instead of 'delete'"
}
applyPragma(database, "temp_store=MEMORY")
applyPragma(database, "secure_delete=ON")
applyPragma(database, "auto_vacuum=NONE")
applyPragma(database, "foreign_keys=ON")
applyPragma(database, "synchronous=FULL")
applyPragma(database, "cipher_memory_security=ON")
check(pragmaLong(database, "cipher_memory_security") == 1L) { "SQLCipher memory security is unavailable" }
check(pragmaLong(database, "temp_store") == 2L) { "SQLite temporary storage is not memory-only" }
check(pragmaLong(database, "secure_delete") == 1L) { "SQLite secure delete is unavailable" }
check(pragmaLong(database, "auto_vacuum") == 0L) { "SQLite auto-vacuum must be disabled" }
check(pragmaLong(database, "foreign_keys") == 1L) { "SQLite foreign keys are not enforced" }
check(pragmaLong(database, "synchronous") == 2L) { "SQLite synchronous mode is not FULL" }
val appliedPageCount = applyPragma(database, "max_page_count=$maxPageCount")?.toLongOrNull()
check(appliedPageCount == maxPageCount.toLong()) {
    "SQLite page limit is $appliedPageCount instead of $maxPageCount"
}
```

Com o auxiliar novo:

```kotlin
/**
 * Runs a pragma and returns the first column of the row it produced, or null when the pragma
 * produced no row.
 * ...
 */
private fun applyPragma(database: SQLiteDatabase, statement: String): String? =
    database.rawQuery("PRAGMA $statement").use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
```

E, em `migrate`:

```kotlin
applyPragma(database, "user_version=$SCHEMA_VERSION")
check(pragmaLong(database, "user_version") == SCHEMA_VERSION.toLong()) {
    "Vault database version was not persisted"
}
```

### Por que a mudança

`net.zetetic:sqlcipher-android:4.19.0` implementa `execSQL` sobre a rotina JNI
`executeNonQuery`: ela chama `sqlite3_step` uma vez e, se a resposta for
`SQLITE_ROW` em vez de `SQLITE_DONE`, lança a exceção citada acima. Ou seja,
`execSQL` é válido apenas para statements que não produzem linhas.

Boa parte das PRAGMAs **produz linha mesmo na forma de atribuição**, porque
devolve o valor que passou a valer: toda a família `cipher_*` do SQLCipher e,
na build usada, também `temp_store=`, `secure_delete=` e `max_page_count=`. Como
a sequência de `openConfigured` roda na criação e na abertura de qualquer cofre,
a primeira PRAGMA nessa condição inviabilizava o produto inteiro no Android —
não só o teste. O erro nunca apareceu em JVM porque os testes de unidade não
abrem um banco SQLCipher real.

Padronizar em `rawQuery` com o cursor consumido e fechado (`use`) é seguro nos
dois casos: quando a PRAGMA devolve linha, ela é lida; quando não devolve, o
cursor apenas fica vazio. A ordem de execução foi preservada porque as PRAGMAs
de cifra precisam valer antes de qualquer acesso a tabela.

### Vantagens

- O cofre volta a abrir no Android (11 casos instrumentados deixaram de falhar).
- O valor efetivo passou a ser conferido onde ele tem significado de segurança:
  `journal_mode` (sem WAL, para não deixar sidecar legível), `foreign_keys`,
  `synchronous`, `max_page_count` (tamanho fixo do arquivo, base da negação
  plausível) e `user_version` (migração de fato persistida). Antes, três desses
  valores eram apenas escritos e nunca verificados.
- As mensagens de erro passaram a dizer qual valor veio no lugar do esperado,
  em vez de um `IllegalStateException` sem texto.
- Um único ponto (`applyPragma`) concentra a regra do SQLCipher, então uma
  PRAGMA nova não reintroduz o defeito.

## 2026-09-15 — Revisão adversarial do commit 8c7f0f7: exceção original mascarada por falha no `close()`

Revisão de segurança pedida sobre o commit que introduziu `applyPragma` (seção acima). Confirmado
adversarialmente, por dois agentes independentes: nenhuma PRAGMA de cifra roda depois de um acesso a
tabela (o único `cipher_*` do arquivo continua antes do primeiro `SELECT`), todos os cursores são
fechados com `.use` em todos os caminhos (5/5 neste arquivo, 22/22 em `ChatDatabase.kt`), e
`openConfigured` não tem nenhum branch condicionado a cofre real vs. isca — mesmas PRAGMAs, mesmos
`check(...)`, mesmas mensagens de erro, nenhum log, para os dois. Nenhum defeito nesses três pontos.

Um defeito de correção menor, porém real, apareceu no tratamento de erro do próprio
`openConfigured`:

### Como era antes

```kotlin
} catch (error: Throwable) {
    database.close()
    throw error
}
```

### Como ficou

```kotlin
} catch (error: Throwable) {
    // close() itself can throw (e.g. a native SQLCipher teardown failure); swallowing that
    // into `error` would replace the diagnostic that says which pragma/check actually
    // failed with an unrelated one. Attach it instead so both are visible.
    try {
        database.close()
    } catch (closeError: Throwable) {
        error.addSuppressed(closeError)
    }
    throw error
}
```

### Por que a mudança

Se qualquer `check(...)` da sequência de PRAGMAs falhasse (dizendo, por exemplo, qual valor de
`synchronous` veio errado) e o `database.close()` do bloco `catch` também lançasse uma exceção — um
cenário plausível, já que o banco pode estar num estado inconsistente exatamente por causa da falha
anterior — o `throw error` original nunca seria alcançado: a exceção de `close()` se propagaria no
lugar dela, e o diagnóstico real (qual PRAGMA/checagem falhou) se perderia. Não é uma diferença
observável entre cofre real e isca (o mesmo código roda para os dois, então não é um oráculo), mas é
uma perda real de diagnóstico exatamente no caminho que este commit reforçou com mensagens
específicas por PRAGMA.

`addSuppressed` preserva as duas exceções: a original continua sendo a lançada (mantendo o
comportamento e o tipo para quem chama), e a falha de `close()` fica anexada a ela, visível em
qualquer log de stack trace.

### Achados fora do escopo deste commit (não corrigidos aqui)

A mesma revisão levantou dois pontos que **não** foram introduzidos por este commit e ficam fora do
diff revisado, registrados para acompanhamento em tarefa própria:

- `cipher_memory_security=ON` (linha do `applyPragma` já existente) só liga a segurança de memória
  do SQLCipher **depois** de `SQLiteDatabase.openOrCreateDatabase(...)` já ter aplicado a chave
  internamente — ou seja, a chave de 32 bytes pode ser alocada antes de o alocador seguro (mlock +
  wipe) estar ativo. A ordem já era essa antes deste commit (só a forma de emitir a PRAGMA mudou);
  corrigir exigiria trocar para a sobrecarga com `SQLiteDatabaseHook` e mover a PRAGMA para dentro de
  `preKey()`, uma mudança maior que o escopo desta revisão.
- Fora deste arquivo: `NoMessagesController.kt:239` / `SettingsScreen.kt:110` escondem o botão "trocar
  senha de pânico" quando o cofre aberto é a isca. Isso é um oráculo visível na tela de
  Configurações — independe de qualquer PRAGMA ou timing — que permite identificar o cofre-isca sem
  medir nada. Também pré-existente e fora do diff deste commit.

### Validação

`:app:testDebugUnitTest --rerun` (módulo inteiro, sem filtro): `BUILD SUCCESSFUL`, sem falhas.

## 2026-09-16 — Terceira fixture do cofre-isca: um clipe de áudio sintético (T4.8, mídia inline — parte A: áudio, item A4)

### Motivo

A tarefa de mídia inline exige paridade visual completa entre o cofre real e o cofre-isca: se uma
bolha de áudio real mostra forma de onda e duração, a bolha de áudio de uma conversa-isca também
precisa mostrar — nunca um espaço vazio ou um placeholder que denuncie "isto é decoy". `MEDIA_FIXTURES`
só tinha duas imagens PNG minúsculas; não havia nenhuma fixture de áudio.

### Como era antes

```kotlin
private val MEDIA_FIXTURES = listOf(
    MediaFixture(Base64.getDecoder().decode("...PNG..."), "foto-almoço.png", "image/png"),
    MediaFixture(Base64.getDecoder().decode("...PNG..."), "receita.png", "image/png"),
)
```

### Como é agora

```kotlin
private val MEDIA_FIXTURES = listOf(
    MediaFixture(..., "foto-almoço.png", "image/png"),
    MediaFixture(..., "receita.png", "image/png"),
    MediaFixture(syntheticVoiceMessageWav(), "mensagem-voz.wav", "audio/wav"),
)

private fun syntheticVoiceMessageWav(): ByteArray {
    // 2s de PCM16 mono 16 kHz, um tom senoidal de 220 Hz em amplitude baixa (3000 de 32767),
    // embrulhado no mesmo cabeçalho WAV de 44 bytes que MemoryAudioRecorder usa.
    ...
}
```

Gerado em código, no momento em que a fixture é construída — não é um asset binário gravado no
repositório, exatamente como a tarefa pediu ("generate it programmatically at fixture-build time").
WAV em vez de AAC: não precisa de nenhum `MediaCodec`/`MediaMuxer` para ser produzido (é aritmética
pura sobre um `ByteArray`), e `attachmentKind`/o roteamento por mime tratam `"audio/wav"` como áudio
de verdade exatamente da mesma forma que `"audio/mp4"` — a bolha de áudio não diferencia os dois.

`createSetupMedia`/`seedDecoy` não precisaram de nenhuma mudança: já iteram `MEDIA_FIXTURES` por
índice e já usam `contacts[index]` (a lista de contatos-isca tem 4 entradas — Ana, Carlos, Família,
Marina — então um terceiro índice continua dentro dos limites).

### Impacto em teste

`AndroidVaultStorageTest.kt` assumia exatamente 2 anexos, todos PNG; foi ajustado para 3 anexos com
verificação por mime (ver `docs/changes/AndroidVaultStorageTest.kt.md`).

### Vantagens

- Paridade real/isca também no áudio, não só em imagem — fecha uma lacuna que a tarefa aponta
  explicitamente como invariante de segurança (cofre coagido não pode ser distinguido pela UI).
- Nenhum asset binário novo no repositório: a fixture é 100% gerada em Kotlin, revisável como texto.
- Reaproveita o mesmo formato de cabeçalho WAV que `MemoryAudioRecorder.buildWav` já usa, então
  qualquer teste ou decodificador que já entende o WAV real do app entende esta fixture sem tratamento
  especial.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`. `AndroidVaultStorageTest` (instrumentado)
verificado por compilação (`:app:assembleDebugAndroidTest`), não executado nesta tarefa.

## 2026-09-16 — Fixture de vídeo do cofre-isca: lacuna documentada, não implementada (T4.8, mídia inline — parte C: vídeo, agente 3 de 3)

### Motivo e decisão

A tarefa pedia, como polimento de paridade real/isca (o mesmo espírito da fixture de áudio WAV
sintética acima), uma quarta fixture em `MEDIA_FIXTURES` — um clipe de vídeo minúsculo — para que uma
conversa-isca também exiba uma bolha de vídeo com poster+duração reais, e explicitamente autorizava
documentar a lacuna em vez de forçar a implementação caso ela se mostrasse impraticável. Depois de
avaliar o caminho, a decisão foi **não implementar** e registrar o motivo aqui — nenhuma mudança de
código neste arquivo para este item.

### Por que o caminho da fixture de áudio (WAV sintético) não se repete para vídeo

A fixture de áudio (seção acima) deliberadamente **evitou** `MediaCodec`/`MediaMuxer`: WAV é
aritmética pura sobre um `ByteArray`, sem nenhum codec de hardware/software envolvido. Não existe
equivalente "sem codec" para vídeo — qualquer contêiner que `MediaMetadataRetriever`/`AttachmentViewer`
consigam decodificar como vídeo de verdade (MP4/H.264, o único caminho plausível sem novas
dependências) exige um encoder de vídeo real via `MediaCodec` + `MediaMuxer`.

O problema não é a complexidade em si — é **onde** essa complexidade rodaria. `MEDIA_FIXTURES` é um
`private val` de `companion object`, avaliado de forma síncrona e antecipada na primeira vez que a
classe `AndroidVaultStorage` é tocada (via `storage by lazy { AndroidVaultStorage(...) }` em
`NoMessagesController`), o que acontece em **toda** criação de cofre e, na prática, em qualquer
inicialização do storage — não só na primeira vez que o app roda. Qualquer exceção lançada durante a
construção de `MEDIA_FIXTURES` derruba `AndroidVaultStorage` inteiro, ou seja, impediria criar
*qualquer* cofre (real ou isca) para *todo* usuário, não só quebraria uma bolha de vídeo isolada.

Isso é qualitativamente diferente do caminho AAC de áudio, que **já é conhecido por falhar
ocasionalmente** no emulador deste projeto (ver `docs/changes/MemoryAudioEncoder.kt.md` e o
comentário de `MemoryAudioEncoder.encode`) — e mesmo assim é seguro, porque `MemoryAudioEncoder`
roda por mensagem enviada, tem fallback explícito para WAV, e nunca é chamado de um inicializador
estático. Repetir o mesmo padrão arriscado (codec que pode falhar dependendo do aparelho) num
inicializador estático sem fallback teria um raio de explosão incomparavelmente maior: de "uma
mensagem de áudio comprimida vira WAV sem comprimir" para "ninguém consegue abrir o cofre".

Codificação de vídeo em modo buffer também é inerentemente menos portátil que áudio: o layout de
buffer de entrada esperado varia por codificador, e boa parte dos encoders de hardware só aceita
entrada via `Surface`, não via `ByteBuffer` direto — tornando um "funciona no emulador de referência,
falha em outro aparelho" ainda mais provável do que já é para o áudio.

### O que foi feito em vez disso

- `docs/changes/VideoPreviewDecodeTest.kt.md` / `app/src/androidTest/kotlin/dev/mx3/nomessages/ui/VideoPreviewDecodeTest.kt`
  exercitam a mesma técnica (`MediaCodec` → `MediaMuxer` → `memfd`) num teste instrumentado, onde uma
  falha de codificação é um resultado tolerado e não tem nenhum raio de explosão além do próprio
  teste — validando a suposição arriscada (que `getScaledFrameAtTime` funciona sobre um
  `MemoryMediaDataSource` em memória) sem herdar o risco de produção.
- `MEDIA_FIXTURES` permanece com 2 imagens PNG + 1 áudio WAV; nenhuma quarta entrada de vídeo.
  `createSetupMedia`/`seedDecoy` continuam funcionando exatamente como antes (iteram por índice,
  nenhuma mudança de comportamento).

### Impacto

- O cofre-isca **não** tem uma conversa com anexo de vídeo. Isto não é uma regressão (a isca nunca
  teve uma), mas é uma lacuna de paridade real/isca que fica registrada aqui explicitamente: se um
  usuário real eventualmente enviar/receber um vídeo numa conversa real, a conversa-isca equivalente
  não tem uma bolha de vídeo espelhando essa possibilidade. Fica para uma tarefa futura, se o produto
  decidir que vale o risco de mover essa codificação para um caminho assíncrono e opcional (por
  exemplo, gerada sob demanda na primeira leitura em vez de no construtor síncrono de
  `AndroidVaultStorage`, com fallback silencioso para "sem vídeo na isca" em caso de falha — o mesmo
  espírito de fallback que `MemoryAudioEncoder` já usa, mas fora do caminho síncrono de inicialização).
- Fotos e áudio da isca continuam com paridade completa (itens B e A, respectivamente) — esta lacuna
  é isolada ao tipo de mídia vídeo.

### Validação

Nenhuma mudança de código neste arquivo para este item; `:app:testDebugUnitTest :app:lintDebug` e
`:app:assembleDebugAndroidTest` continuam `BUILD SUCCESSFUL` (ver `docs/changes/VideoPreviewDecodeTest.kt.md`).


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Defeito corrigido: as fotos da isca eram PNGs de 1x1 pixel (isca distinguivel)

**Como era (47afa3e):**

```kotlin
private val MEDIA_FIXTURES = listOf(
    MediaFixture(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQ..."), "foto-almoco.png", "image/png"),
    MediaFixture(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQ..."), "receita.png",     "image/png"),
    MediaFixture(syntheticVoiceMessageWav(), "mensagem-voz.wav", "audio/wav"),
)
```

Os dois blobs base64 decodificam para PNGs de **1x1 pixel** (68 bytes cada, `IHDR width=1 height=1`).

**Como ficou:**

```kotlin
MediaFixture(syntheticPhotoPng(width = 960,  height = 1280, seed = 0x5EED_1), "foto-almoco.png", "image/png"),
MediaFixture(syntheticPhotoPng(width = 1280, height = 960,  seed = 0x5EED_2), "receita.png",     "image/png"),
```

com `syntheticPhotoPng` gerando, em tempo de construcao da fixture (mesmo padrao ja usado por
`syntheticVoiceMessageWav`), uma imagem deterministica em proporcao de camera de celular - gradiente
vertical, manchas translucidas e vinheta - codificada em PNG, com verificacao de que cabe no mesmo
teto de 8 MiB que `MessagingEngine.sendAttachment` impoe a um envio real.

**Por que era necessario.** Enquanto anexos so apareciam como uma linha com o nome do arquivo, um PNG
1x1 era invisivel. O commit 47afa3e passou a renderizar fotos **inline**: `ImageBubble` desenha a
miniatura com `ContentScale.FillWidth` e `heightIn(max = 220.dp)`. Um pixel unico esticado por
`FillWidth` vira um bloco de cor chapado ocupando toda a largura da bolha. Resultado: o cofre real
mostra fotografias na conversa e o cofre isca mostra dois retangulos monocromaticos - uma diferenca
**visual imediata**, perceptivel por qualquer pessoa que abra os dois sob coacao, que e exatamente o
tipo de assimetria que a "plausible deniability" documentada em `docs/security-model.md` proibe. E o
mesmo raciocinio que o proprio commit aplicou ao adicionar a fixture de audio sintetico para que a
bolha de voz da isca desenhasse uma forma de onda real.

O caminho de codigo e identico ao de midia real: a isca passa por
`NoMessagesController.loadImagePreview` -> `decodeBoundedThumbnail` -> `MediaPreviewCache`, sem nenhum
ramo especifico para isca, e agora com dimensoes na mesma ordem de grandeza - portanto sem
distinguidor por tempo de decodificacao nem por tamanho de arquivo trivialmente pequeno.

**Limitacao assumida.** Uma imagem procedural continua nao sendo uma fotografia real sob pericia
detalhada; a restricao do projeto e nao versionar assets binarios. Isto elimina o distinguidor
*visual imediato*, nao transforma a isca em conteudo forense indistinguivel. A isca tambem continua
sem nenhum video - registrado como observacao, ja que sem video nao ha bolha de video e portanto nao
ha assimetria visivel, apenas ausencia.

**Garantia restaurada.** Indistinguibilidade visual entre a galeria inline do cofre isca e a de um
cofre real.

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.

## 2026-09-17 — Schema v2: coluna `messages.forwarded` via ALTER TABLE + uma mensagem encaminhada no decoy (Parte B)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). O flag `forwarded` precisa
ser persistido numa coluna própria da tabela `messages`, e o cofre decoy precisa exibir pelo menos
uma mensagem encaminhada para não se distinguir de um cofre real em uso.

---

### 1. Versionamento de schema: 1 → 2

**Como era antes**

```kotlin
private const val SCHEMA_VERSION = 1
...
private fun migrate(database: SQLiteDatabase) {
    val version = ...PRAGMA user_version...
    require(version in 0..SCHEMA_VERSION) { "Unsupported vault database version" }
    if (version == SCHEMA_VERSION) return
    database.beginTransaction()
    try {
        if (version < 1) createSchemaV1(database)
        applyPragma(database, "user_version=$SCHEMA_VERSION")
        ...
    } finally { database.endTransaction() }
}
```

**Como é agora**

```kotlin
/**
 * v1 - as tabelas originais (createSchemaV1).
 * v2 - messages.forwarded, adicionada exclusivamente pelo ALTER TABLE em migrate.
 */
private const val SCHEMA_VERSION = 2
...
        if (version < 1) createSchemaV1(database)
        // v2: the "forwarded" provenance bit of a chat message. Added by ALTER TABLE rather than
        // folded into createSchemaV1 so the upgrade path is the same one an already-created
        // vault would take, and so createSchemaV1 stays a faithful record of what v1 was.
        if (version < 2) {
            database.execSQL(
                "ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN (0,1))",
            )
        }
        applyPragma(database, "user_version=$SCHEMA_VERSION")
```

**Como a migração funciona**

- `migrate()` lê `PRAGMA user_version` e replica, em ordem, cada passo que falta, tudo dentro de
  **uma única transação** (o `beginTransaction`/`endTransaction` já existente). Um cofre novo começa
  em `user_version = 0`, então roda os dois passos: cria a v1 e em seguida aplica o `ALTER TABLE`.
  Um cofre que já estivesse em 1 rodaria só o segundo.
- `NOT NULL DEFAULT 0` faz o SQLite preencher toda linha pré-existente com o default seguro ("não
  encaminhada"), que é exatamente como um envelope de wire versão 1 decodifica (ver
  `docs/changes/EnvelopeCodec.kt.md`). `CHECK(forwarded IN (0,1))` espelha o que `display_only` já
  fazia na tabela `contacts`.
- `ALTER TABLE ... ADD COLUMN` é válido em tabela `WITHOUT ROWID` desde que a coluna não tenha
  `PRIMARY KEY`/`UNIQUE` e, sendo `NOT NULL`, tenha um default não nulo — as duas condições são
  satisfeitas. `CHECK` é permitido.

**Por que a coluna NÃO entrou em `createSchemaV1`**

Esse é justamente o ponto do exercício de versionamento: `createSchemaV1` continua sendo um registro
fiel do que a versão 1 era. Se a coluna fosse adicionada lá, um cofre novo nunca exercitaria o
caminho de `ALTER TABLE`, e o primeiro cofre real a precisar dele seria o primeiro a testá-lo.

**Limitação conhecida, registrada de propósito**

`open()` chama apenas `requireSchema()`, que exige `version == SCHEMA_VERSION` e recusa qualquer
outra coisa; `migrate()` só roda dentro de `initialize()`. Ou seja: um cofre "v1" hipotético **não**
seria migrado ao ser aberto — ele seria rejeitado. Isso não foi alterado porque não foi pedido e
porque, na prática, não existe cofre v1 no mundo: nada foi publicado e todo cofre de teste é criado
do zero por `initialize()`, que roda a migração completa. Se algum dia um cofre precisar ser
atualizado no lugar, o ajuste é trocar o `requireSchema(raw)` de `open()` por `migrate(raw)` — a
função de migração já está escrita de forma incremental e idempotente para isso. A decisão está
documentada também no KDoc de `migrate()`.

---

### 2. Decoy: exatamente uma mensagem marcada como encaminhada

**Como era antes** (dentro de `seedDecoy`)

```kotlin
val messageId = decoyUuid("text:$contactIndex:$messageIndex")
database.insertMessage(
    MessageRecord(
        id = messageId,
        peerOrGroup = contact.id,
        direction = if (messageIndex % 2 == 0) MessageDirection.INCOMING else MessageDirection.OUTGOING,
        timestamp = timestamp,
        body = EnvelopeCodec.encode(Envelope.Text(messageId, timestamp, body)),
        status = if (unreadTail) MessageStatus.DELIVERED else MessageStatus.READ,
    ),
)
```

**Como é agora**

```kotlin
val forwarded = contactIndex == FORWARDED_DECOY_CHAT && messageIndex == FORWARDED_DECOY_MESSAGE
database.insertMessage(
    MessageRecord(
        ...
        body = EnvelopeCodec.encode(Envelope.Text(messageId, timestamp, body, forwarded = forwarded)),
        status = if (unreadTail) MessageStatus.DELIVERED else MessageStatus.READ,
        forwarded = forwarded,
    ),
)
```

com, no `companion object`:

```kotlin
private const val FORWARDED_DECOY_CHAT = 0
private const val FORWARDED_DECOY_MESSAGE = 1
```

**Qual mensagem foi escolhida e por quê**

A **segunda mensagem da conversa com o primeiro contato (Ana)**: `contactIndex == 0`,
`messageIndex == 1`, texto **"Sim, levo o pão."**. Motivos:

- **É `OUTGOING`** (`messageIndex % 2 == 1`), que é a direção que o próprio usuário produz ao
  encaminhar algo. Uma mensagem recebida marcada como encaminhada também seria plausível, mas a
  saída é a que combina com a história que o decoy conta: o dono do aparelho usa o app.
- **Não é a cauda não lida de nenhuma conversa.** `UNREAD_DECOY_CHATS = setOf(0, 2)` marca a
  **última** mensagem das conversas 0 e 2 como `DELIVERED` para gerar um badge de não lidas; a
  mensagem escolhida é a do meio, então o badge, os timestamps e a propriedade "duas de quatro
  conversas com não lidas" continuam exatamente como estavam.
- **Não é um anexo.** As três mensagens de anexo do decoy (duas fotos PNG e um áudio WAV sintéticos)
  seguem intocadas, com `forwarded = false`; marcar uma delas exigiria também pensar no visualizador
  em tela cheia, sem nenhum ganho.
- **Exatamente uma, nem zero, nem várias.** Pela mesma razão que dois de quatro chats têm não lidas:
  um cofre em que *nenhuma* mensagem jamais foi encaminhada é um distinguidor — encaminhar é um gesto
  cotidiano, e um cofre real em uso diário quase sempre tem pelo menos uma. Mais de uma começaria a
  chamar atenção no sentido oposto.

**Envelope e coluna sincronizados.** As duas escritas usam a **mesma** variável `forwarded`, do mesmo
jeito que `MessagingEngine.envelopeForwarded` faz no envio real. Uma linha cujo `body` decodificasse
para `forwarded = false` enquanto a coluna dissesse `true` seria, por si só, um sinal de que aquele
banco foi fabricado.

### Vantagens

- O caminho de migração passa a existir de verdade e é exercitado por todo cofre criado (e por um
  teste instrumentado dedicado — ver `docs/changes/AndroidVaultStorageTest.kt.md`).
- `createSchemaV1` permanece imutável, então a história do schema fica auditável no código.
- O decoy ganha paridade visual com o cofre real na nova funcionalidade sem mexer em nenhuma das
  propriedades de indistinguibilidade que já tinham sido conquistadas (não lidas, mídia, timestamps).

## 2026-09-17 — Correção: `open()` não migrava o esquema (cofre v1 real recusado no aparelho)

### O defeito, observado em aparelho físico

Galaxy Note10+, Android 12, atualização do app instalada por cima. Um cofre criado pela versão
anterior (esquema v1) passou a **não abrir**:

```
E/NoMessagesController: unlock failed: java.lang.IllegalStateException: Unsupported vault database version
  at dev.mx3.nomessages.storage.AndroidVaultStorage.requireSchema(AndroidVaultStorage.kt:230)
  at dev.mx3.nomessages.storage.AndroidVaultStorage.open(AndroidVaultStorage.kt:72)
  at dev.mx3.nomessages.core.vault.VaultManager.unlock(VaultManager.kt:74)
```

Ou seja: exatamente a "limitação conhecida e assumida" registrada na seção anterior
(2026-09-17 / T4.12) — documentada sob a premissa de que "nada foi publicado, não existe cofre real
v1 no mundo". A premissa deixou de valer. Isso não é cosmético: é uma falha de **disponibilidade**
do cofre — o usuário fica trancado fora dos próprios dados.

### Causa raiz

`migrate()` já era correta e incremental, mas só era chamada por `initialize()` (criação do cofre).
`open()` chamava `requireSchema()`, que exigia `version == SCHEMA_VERSION` e recusava qualquer outra
coisa — inclusive um cofre v1 legítimo que só precisava ser atualizado. A função que sabia migrar
nunca era executada no único caminho em que a migração importa.

---

### 1. `open()` passa a migrar

**Como era antes** (`AVS:72`)

```kotlin
val raw = openConfigured(path, keys.dbKey)
try {
    requireSchema(raw)
    check(Files.size(path) == databaseBytes) { "Vault database allocation changed" }
    val selected = ChatDatabase(raw, maxPageCount) { ... }
```

**Como é agora** (`AVS:75-76`)

```kotlin
val raw = openConfigured(path, keys.dbKey)
try {
    // Upgrade in place before anything reads a table: a vault created by an older build
    // has to open, not be refused. migrate() returns immediately when the database is
    // already current, so the common open costs one PRAGMA user_version read.
    migrate(raw)
    check(Files.size(path) == databaseBytes) { "Vault database allocation changed" }
    val selected = ChatDatabase(raw, maxPageCount) { ... }
```

A migração acontece **antes** da verificação de tamanho fixo (que assim revalida o arquivo já
migrado) e **antes** de `ChatDatabase` existir — nenhuma consulta tipada roda contra um esquema
velho.

Custo no caso comum: `migrate()` retorna em `if (version == SCHEMA_VERSION) return` (`AVS:227`),
depois de uma única leitura de `PRAGMA user_version`. Nenhuma transação é aberta.

### 2. `requireSchema()` apagada

```kotlin
// removido por completo
private fun requireSchema(database: SQLiteDatabase) {
    val version = database.rawQuery("PRAGMA user_version").use { ... }
    check(version == SCHEMA_VERSION) { "Unsupported vault database version" }
}
```

Depois da mudança ela ficaria sem nenhum chamador no repositório. Código morto que ainda parece uma
política de segurança é pior que ausência de código: a próxima pessoa a ler `AndroidVaultStorage`
teria duas funções concorrentes dizendo coisas opostas sobre versões de esquema.

A leitura da versão, que as duas funções faziam de forma idêntica, virou uma função só
(`readUserVersion`, `AVS:189`).

### 3. `migrate(from, to)`: um degrau por versão em vez de uma corrente de `if`

**Como era antes**

```kotlin
private fun migrate(database: SQLiteDatabase) {
    val version = database.rawQuery("PRAGMA user_version").use { ... }
    require(version in 0..SCHEMA_VERSION) { "Unsupported vault database version" }
    if (version == SCHEMA_VERSION) return
    database.beginTransaction()
    try {
        if (version < 1) createSchemaV1(database)
        if (version < 2) { database.execSQL("ALTER TABLE messages ADD COLUMN forwarded ...") }
        applyPragma(database, "user_version=$SCHEMA_VERSION")
        ...
    } finally { database.endTransaction() }
}
```

**Como é agora** (`AVS:220-249`, `AVS:252-273`)

```kotlin
private fun migrate(database: SQLiteDatabase) {
    val version = readUserVersion(database)
    require(version >= 0) { "Unsupported vault database version" }
    check(version <= SCHEMA_VERSION) {
        "Vault database was created by a newer app version " +
            "(schema version $version, this build supports up to $SCHEMA_VERSION)"
    }
    if (version == SCHEMA_VERSION) return
    database.beginTransaction()
    try {
        for (from in version until SCHEMA_VERSION) migrateStep(database, from = from, to = from + 1)
        applyPragma(database, "user_version=$SCHEMA_VERSION")
        check(pragmaLong(database, "user_version") == SCHEMA_VERSION.toLong()) {
            "Vault database version was not persisted"
        }
        database.setTransactionSuccessful()
    } finally {
        database.endTransaction()
    }
    check(pragmaLong(database, "max_page_count") == maxPageCount.toLong()) {
        "SQLite page limit changed during the vault schema migration"
    }
}

private fun migrateStep(database: SQLiteDatabase, from: Int, to: Int) {
    check(to == from + 1) { "Schema migration steps must advance exactly one version" }
    when (to) {
        1 -> createSchemaV1(database)
        2 -> {
            reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
            database.execSQL(
                "ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN (0,1))",
            )
        }
        else -> error("No migration step defined for target schema version $to")
    }
}
```

O comportamento para `version in 0..SCHEMA_VERSION` é **byte a byte o mesmo**: v0 roda os dois
degraus (cria a v1, depois o `ALTER TABLE`), v1 roda só o segundo, v2 retorna cedo. A diferença é
estrutural: acrescentar v3 passa a ser acrescentar um ramo `3 -> ...` em `migrateStep`, não mais
uma linha nova numa corrente de `if` que cresce para sempre. `check(to == from + 1)` impede que um
degrau futuro pule uma versão silenciosamente, e o `else -> error(...)` transforma "esqueci de
escrever o degrau" em falha imediata e nomeada em vez de um `user_version` gravado sem que o
esquema correspondente exista.

### 4. Banco de uma versão **mais nova** é recusado antes de qualquer transação

Antes, `require(version in 0..SCHEMA_VERSION)` tratava "velho demais" e "novo demais" com a mesma
mensagem genérica. Agora são dois casos distintos:

| `user_version` | comportamento | mensagem |
|---|---|---|
| `< 0` | recusa (inalterado) | `Unsupported vault database version` |
| `0..SCHEMA_VERSION` | migra (ou retorna cedo) | — |
| `> SCHEMA_VERSION` | recusa **antes** de `beginTransaction`, sem tocar em nenhuma tabela | `Vault database was created by a newer app version (schema version N, this build supports up to 2)` |

Por que antes da transação: um banco escrito por um build futuro pode conter tabelas/colunas que
este build não entende. A única jogada segura é não encostar nele — nem para abrir uma transação.
E a mensagem precisa ser acionável: um usuário com o app desatualizado tem de saber que o remédio é
atualizar o app, não formatar o cofre. `Unsupported vault database version` não dizia isso.

### 5. Política de páginas: a migração nunca cresce o arquivo

O cofre tem tamanho de arquivo **fixo** (`databaseBytes`, verificado por
`check(Files.size(path) == databaseBytes)`). Toda escrita normal do app passa por
`ChatDatabase.consumeReserve` (`CD:488-509`): confere `PRAGMA freelist_count` e, se faltar página,
apaga linhas de `storage_reserve` (blobs `zeroblob`) para devolver páginas à freelist — nunca
cresce o arquivo. `ALTER TABLE ... ADD COLUMN ... DEFAULT <constante>` é, no SQLite, uma operação
**apenas de metadado** (não reescreve linha nenhuma), então em princípio não precisaria de página
alguma. Mesmo assim o degrau v1→v2 passou a sacar da reserva, por defesa:

```kotlin
private fun reserveMigrationPages(database: SQLiteDatabase, estimatedBytes: Int) {
    require(estimatedBytes >= 0) { "Invalid migration page estimate" }
    if (estimatedBytes == 0) return
    val requiredPages = (estimatedBytes.toLong() + PAGE_BYTES - 1) / PAGE_BYTES
    val freePages = pragmaLong(database, "freelist_count")
    val headroomPages = maxPageCount.toLong() - pragmaLong(database, "page_count")
    if (freePages + headroomPages >= requiredPages) return
    val neededBytes = (requiredPages - freePages - headroomPages) * PAGE_BYTES
    val reserveIds = ArrayList<Long>()
    var releasedBytes = 0L
    database.rawQuery("SELECT id,length(payload) FROM storage_reserve ORDER BY id").use { cursor ->
        while (releasedBytes < neededBytes && cursor.moveToNext()) {
            reserveIds += cursor.getLong(0)
            releasedBytes += cursor.getLong(1) + PAGE_BYTES
        }
    }
    if (releasedBytes < neededBytes) throw StorageCapacityException("Encrypted database capacity exhausted")
    reserveIds.forEach { id -> database.execSQL("DELETE FROM storage_reserve WHERE id = ?", arrayOf(id)) }
}
```

Três decisões que valem registro:

- **Por que uma cópia da lógica e não uma chamada a `ChatDatabase.consumeReserve`.** No momento da
  migração `ChatDatabase` ainda não existe — `open()` migra o `SQLiteDatabase` cru antes de
  construí-lo, justamente para que nenhuma consulta tipada rode contra esquema velho. A política é
  intencionalmente idêntica (mesma ordem `ORDER BY id`, mesma contabilidade
  `length(payload) + PAGE_BYTES`, mesma `StorageCapacityException`).
- **Por que `headroomPages` conta como disponível.** No caminho de `open()` não há folga nenhuma: o
  arquivo já está na capacidade fixa, `page_count == max_page_count`, e portanto toda página vem da
  reserva. No caminho de `initialize()` o banco ainda **não** foi preenchido (`fillReserveToCapacity`
  roda *depois* da migração, `AVS:55`) e não existe linha de reserva para apagar — ali o degrau
  simplesmente usa a alocação a que já tem direito. Sem esse termo, criar um cofre novo passaria a
  lançar `StorageCapacityException` no degrau v2. Em ambos os casos `max_page_count` é o teto e o
  arquivo nunca passa de `databaseBytes`.
- **Estimativa minúscula de propósito.** `SCHEMA_METADATA_ESTIMATE_BYTES = 512` (`AVS:500`) —
  uma definição de coluna mais o `CHECK` acrescentam algumas dezenas de bytes ao texto do esquema;
  meia página arredonda para exatamente uma página. Superprovisionar aqui seria devolver páginas da
  reserva do usuário sem necessidade.

### 6. Verificação pós-migração de `max_page_count`

`max_page_count` é pragma **por conexão**, aplicado por `openConfigured` (`AVS:143-146`), e nada num
degrau de migração deveria movê-lo. Mas a garantia de tamanho fixo inteira se apoia nele, então
`migrate()` reconfere depois de fechar a transação (`AVS:242`) em vez de presumir. Falha com
`SQLite page limit changed during the vault schema migration`, que diz exatamente o que aconteceu.

Logo em seguida, no chamador, o `check(Files.size(path) == databaseBytes)` de `open()` revalida o
tamanho observável do arquivo no cofre já migrado.

### 7. Simetria real/isca

`open()` é **uma** implementação parametrizada por `slot: VaultSlot`, e `VaultManager.unlock`
(`core/.../VaultManager.kt:65-79`) a chama exatamente uma vez por destravamento, para o slot que a
senha digitada resolveu. Não há — e é proibido haver — ramo por slot no código de migração:
`migrate`/`migrateStep`/`reserveMigrationPages` não recebem `VaultSlot` e não o consultam. Consertar
`open()` uma vez conserta o cofre real e o isca com o mesmo código.

**Sobre o tempo.** O primeiro `open()` de um banco ainda não migrado, depois de uma atualização do
app, é mensuravelmente mais lento que uma abertura normal (uma transação extra mais uma escrita de
esquema). Todo `open()` seguinte daquele mesmo banco cai no retorno cedo
(`version == SCHEMA_VERSION`), que é uma leitura de pragma. Isso é esperado e **não** é um
distinguidor real-versus-isca: o custo depende só de "este arquivo de banco específico já foi
migrado?", nunca de qual slot ele é. Os dois bancos são migrados de forma independente, cada um na
primeira vez em que *ele* for aberto depois da atualização, e os dois percorrem exatamente o mesmo
caminho quando isso acontece. Registrado também em `docs/security-model.md` e em
`docs/development/runtime-catalog.md` (seção 7.2).

### Vantagens

- **Corrige a falha de disponibilidade real**, em vez de contorná-la: o cofre v1 do aparelho abre e
  é atualizado no lugar, dentro de uma transação, preservando todas as mensagens existentes.
- **Fecha a limitação para sempre**, não só para v1→v2: qualquer mudança futura de esquema já nasce
  com caminho de atualização funcionando nos dois pontos de entrada.
- **Diagnóstico acionável** para o caso "app velho, banco novo", que antes vinha com a mesma
  mensagem genérica do caso oposto.
- **Nenhuma garantia enfraquecida**: `journal_mode`, `foreign_keys`, `synchronous`, `max_page_count`
  e a invariante de tamanho fixo continuam todas verificadas — `max_page_count` passou a ser
  verificado *mais* uma vez, não menos.
- **Extensibilidade real**: um degrau por versão, com falha nomeada para degrau faltante.

### Validação (2026-09-17)

- `gradle-wsl.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
  → `BUILD SUCCESSFUL in 4m 55s`. JVM: **49 testes, 1 ignorado, 0 falhas, 0 erros**. Lint: **0
  erros** (42 avisos, 6 hints — mesmo baseline de antes da mudança). APKs:
  `app/build/outputs/apk/debug/app-debug.apk` e
  `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
- Suite instrumentada completa em `emulator-5556` (`t31-devices.sh emulator-5556`) → `PASS`,
  `OK (18 tests)`, 0 falhas — os 16 anteriores mais os dois novos
  (`vaultCreatedBySchemaVersionOneIsMigratedInPlaceWhenOpened`,
  `vaultFromANewerAppVersionIsRejectedWithAnExplicitMessage`). Log em
  `docs/development/build-logs/android-test-emulator-5556-20260917T193532Z.log`.

## 2026-09-17 — Schema v3: `contacts.doorbell_onion`/`doorbell_token`, reservadas para o T4.17 (T4.16)

### Motivo

O T4.16 redesenhou o QR de pareamento (formato 2, ver `docs/changes/Pairing.kt.md`) para tirar o
bundle de chaves da imagem. Na sobra de espaço que isso abriu, a oferta assinada passou a carregar
também dois campos de um recurso ainda não implementado — a "campainha" do T4.17:
`doorbellKey` (chave de identidade de um segundo onion, dedicado, do par) e `doorbellToken`
(segredo de 32 bytes, novo a cada oferta). Os dois chegam ao aparelho já dentro da oferta assinada
no momento do pareamento; precisam de uma coluna própria em `contacts` para não se perderem antes de
T4.17 existir para os usar. Schema v2 → v3.

### Como era antes

```kotlin
private fun migrateStep(database: SQLiteDatabase, from: Int, to: Int) {
    check(to == from + 1) { "Schema migration steps must advance exactly one version" }
    when (to) {
        1 -> createSchemaV1(database)
        2 -> {
            reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
            database.execSQL(
                "ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN (0,1))",
            )
        }
        else -> error("No migration step defined for target schema version $to")
    }
}
```

```kotlin
/**
 * v1 - the original tables ([createSchemaV1]).
 * v2 - `messages.forwarded`, added exclusively by the ALTER TABLE in [migrateStep]; the v1
 *      statement list is frozen and intentionally does not mention the column.
 */
private const val SCHEMA_VERSION = 2
```

### Como ficou

```kotlin
when (to) {
    1 -> createSchemaV1(database)
    2 -> { /* forwarded, inalterado */ }
    // v3 (2026-09-17, T4.16): the peer's doorbell address and token, which arrive inside the
    // signed pairing offer and are reserved for T4.17. Same shape as the v2 step for the
    // same reasons - two metadata-only ALTER TABLEs with constant defaults, so no row is
    // rewritten and an existing contact backfills to "" / x'' meaning "not recorded",
    // exactly what a contact paired before format 2 legitimately is.
    3 -> {
        reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
        database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_onion TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_token BLOB NOT NULL DEFAULT x''")
    }
    else -> error("No migration step defined for target schema version $to")
}
```

```kotlin
/**
 * v1 - the original tables ([createSchemaV1]).
 * v2 - `messages.forwarded`, added exclusively by the ALTER TABLE in [migrateStep]; the v1
 *      statement list is frozen and intentionally does not mention the column.
 * v3 - `contacts.doorbell_onion` and `contacts.doorbell_token`, reserved for T4.17 and
 *      populated from the format-2 pairing offer (T4.16). Added the same way, for the same
 *      reason: [createSchemaV1] stays a faithful record of what v1 was, and an existing
 *      vault takes exactly the upgrade path a freshly created one takes.
 */
private const val SCHEMA_VERSION = 3
```

### Por que isto REAPROVEITA o mecanismo do T4.15 em vez de um caminho paralelo

O degrau `3 -> { ... }` não é um mecanismo novo — é a *mesma* engrenagem que o T4.15 construiu para
`messages.forwarded`, usada uma segunda vez:

- `migrate()` continua sendo chamada a partir de `open()` (a correção do T4.15, "cofre v1 atualizado
  no lugar" — seção anterior deste arquivo), não só de `initialize()`. Um cofre v2 real criado antes
  do T4.16 é migrado para v3 na próxima vez em que for aberto, sem exigir reinstalação.
- Continua sendo **um degrau por versão** dentro de `migrateStep`, com `check(to == from + 1)`
  impedindo pular versão e `else -> error(...)` transformando "esqueci de escrever o degrau" em
  falha nomeada e imediata — não um `user_version` gravado sem que o esquema exista.
- `reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)` é sacado **antes** dos dois
  `ALTER TABLE`, exatamente como no degrau v2, e pela mesma razão: por mais que um `ALTER TABLE
  ... ADD COLUMN ... DEFAULT <constante>` seja, no SQLite, uma operação só de metadado (não
  reescreve nenhuma linha), o degrau continua sacando da reserva por defesa, em vez de presumir que
  a operação não vai custar página nenhuma.
- As duas colunas são `NOT NULL DEFAULT` com um valor **constante** (`''` e `x''`) — não uma
  expressão, não uma subconsulta — então nenhuma linha de `contacts` é reescrita e o arquivo de
  capacidade fixa (`databaseBytes`) não cresce, do mesmo jeito que `messages.forwarded` não cresceu.
- A mensagem de recusa de banco **mais novo** que o build suporta não mudou de forma nenhuma —
  continua vindo de `check(version <= SCHEMA_VERSION)` em `migrate()` — só o número que ela relata
  mudou, porque `SCHEMA_VERSION` mudou: um cofre com `user_version = 4` hoje é recusado com
  `"...this build supports up to 3"` em vez de `"...up to 2"`.

Nada nisso foi inventado para o T4.16: é o mesmo `migrate()`/`migrateStep()`/`reserveMigrationPages()`
documentado em detalhe na seção "2026-09-17 — Correção: `open()` não migrava o esquema" acima, agora
com um terceiro degrau.

### Por que os defaults vazios são o valor correto, não um placeholder

`''` e `x''` não são um "ainda não implementado" temporário — são o valor semanticamente certo para
todo contato pareado **antes** do formato de QR 2 existir. Esse contato nunca recebeu uma oferta com
`doorbellKey`/`doorbellToken` porque o campo não existia na oferta que os pareou; "não tenho endereço
de campainha registrado" é exatamente o estado real dele, e precisa ser representável sem exceção,
não corrigido depois. `ChatDatabase.validateContact` (ver `docs/changes/ChatDatabase.kt.md`) trata
essas duas colunas vazias como o caso normal, não como um erro a ser rejeitado.

### Reservadas para o T4.17 — nada lê estas colunas hoje

`doorbell_onion` e `doorbell_token` são escritas por `AndroidVaultStorage`/`ChatDatabase` (via
`ContactRecord`, ver `docs/changes/StorageModels.kt.md`) e por `DecoyFactory` (ver
`docs/changes/DecoyFactory.kt.md`), mas nenhum código de runtime as lê de volta ainda — o recurso de
campainha em si (T4.17) não existe neste commit. A migração e a validação nasceram junto com o
formato de QR que traz o dado, para não exigir uma segunda migração de esquema quando T4.17 for
implementado.

### Vantagens

- Zero mecanismo novo: quem já entende o degrau v1→v2 já entende o v2→v3 inteiro.
- Cofre v2 real de usuário migra para v3 na próxima abertura, sem re-parear nenhum contato.
- Arquivo de capacidade fixa nunca cresce — a migração salda da mesma reserva que toda escrita normal
  usa.
- `createSchemaV1` continua sendo um registro fiel do que a v1 era; a história do esquema inteiro
  (v1 → v2 → v3) fica auditável em `migrateStep` e no KDoc de `SCHEMA_VERSION`.

## 2026-09-18 — Schema v4: `contacts.doorbell_token_issued`, a outra metade do par (T4.17)

### Motivo

Fase 2 da feature "campainha" (T4.17) — camada de armazenamento e pareamento. Leitura do código
existente expôs uma assimetria: o esquema v3 (T4.16) grava por contato `doorbell_onion` e
`doorbell_token` — a metade do PAR que descreve **o outro lado**: onde bater quando somos nós que
tocamos a campainha dele, e o que apresentar lá. O token que **este** aparelho cunhou para aquele
contato — o que uma batida vinda dele vai apresentar, e portanto o único valor contra o qual um
ouvinte local poderia verificar — era gerado em `PairingEngine.newOffer()` (`crypto.random(32)`),
publicado dentro da nossa própria oferta assinada e depois descartado junto com ela. Não havia
coluna, tabela nem chave `meta` guardando "o token que emiti para o contato X". O cofre sabia tocar
e não sabia atender.

O valor não é derivável de nada — cada lado cunha um segredo aleatório independente por troca — e
não existe um segundo canal autenticado para combinar um token depois: o pareamento presencial com
SAS é a única janela em que os dois lados se veem. Schema v3 → v4.

### Como era antes

```kotlin
private fun migrateStep(database: SQLiteDatabase, from: Int, to: Int) {
    check(to == from + 1) { "Schema migration steps must advance exactly one version" }
    when (to) {
        1 -> createSchemaV1(database)
        2 -> { /* forwarded, inalterado */ }
        3 -> {
            reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
            database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_onion TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_token BLOB NOT NULL DEFAULT x''")
        }
        else -> error("No migration step defined for target schema version $to")
    }
}
```

```kotlin
/**
 * v1 - the original tables ([createSchemaV1]).
 * v2 - `messages.forwarded`, ...
 * v3 - `contacts.doorbell_onion` and `contacts.doorbell_token`, reserved for T4.17 and
 *      populated from the format-2 pairing offer (T4.16). ...
 */
private const val SCHEMA_VERSION = 3
```

### Como ficou

```kotlin
when (to) {
    1 -> createSchemaV1(database)
    2 -> { /* forwarded, inalterado */ }
    3 -> { /* doorbell_onion / doorbell_token, inalterado */ }
    // v4 (2026-09-18, T4.17): the other half of the doorbell pair - the token THIS device
    // minted for that contact, which is what an incoming ring from it must present. v3 only
    // recorded the peer's half (where to knock, and what to say there), so the vault could
    // ring but could not answer. The value exists for a few seconds inside the pairing
    // exchange and nowhere else - there is no second authenticated channel on which to agree
    // one later - so it has to be captured at pairing time, exactly like the v3 columns.
    //
    // Same shape as the v2 and v3 steps for the same reasons: one metadata-only ALTER TABLE
    // with a constant default, so no row of `contacts` is rewritten and the fixed-capacity
    // file does not grow. An existing contact backfills to x'' meaning "not recorded",
    // which is the truth for every contact paired before this build: its token was minted,
    // published in our offer and then discarded. Such a contact can still ring us, it just
    // cannot be recognised - re-pairing is the only way to recover it, and the doorbell
    // feature is expected to treat an empty column as "no doorbell with this contact".
    4 -> {
        reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)
        database.execSQL("ALTER TABLE contacts ADD COLUMN doorbell_token_issued BLOB NOT NULL DEFAULT x''")
    }
    else -> error("No migration step defined for target schema version $to")
}
```

```kotlin
/**
 * v1 - the original tables ([createSchemaV1]).
 * v2 - `messages.forwarded`, ...
 * v3 - `contacts.doorbell_onion` and `contacts.doorbell_token`, reserved for T4.17 ...
 * v4 - `contacts.doorbell_token_issued`, the mirror image of v3's `doorbell_token`: the
 *      secret *this* device minted for that contact, which is what an incoming ring from it
 *      has to present (T4.17). v3 stored only the peer's half, so the vault could ring a
 *      doorbell but had nothing to verify one against.
 */
private const val SCHEMA_VERSION = 4
```

### Por que isto REAPROVEITA o mecanismo do T4.15 em vez de um caminho paralelo

O degrau `4 -> { ... }` não é um mecanismo novo — é a *mesma* engrenagem que o T4.15 construiu para
`messages.forwarded` e que o T4.16 reusou para `doorbell_onion`/`doorbell_token`, usada uma terceira
vez:

- `migrate()` continua sendo chamada tanto de `initialize()` quanto de `open()` (a correção do
  T4.15, "cofre v1 atualizado no lugar" — seção acima). O laço `for (from in version until
  SCHEMA_VERSION) migrateStep(database, from, from + 1)` faz um cofre em v1, v2 **ou** v3 subir até
  v4 na próxima vez em que for aberto — sem código adicional para cada ponto de partida, e sem
  re-parear ninguém: o degrau novo é coberto automaticamente porque o laço já percorre a cauda que
  falta, seja ela de um ou de três degraus.
- `check(to == from + 1)` continua impedindo que um degrau pule versão, e `else -> error(...)`
  continua transformando "esqueci de escrever o degrau" em falha nomeada e imediata, em vez de um
  `user_version` gravado sem que o esquema correspondente exista.
- `reserveMigrationPages(database, SCHEMA_METADATA_ESTIMATE_BYTES)` é sacado **antes** do
  `ALTER TABLE`, exatamente como nos degraus v2 e v3, e pela mesma razão: por mais que
  `ALTER TABLE ... ADD COLUMN ... DEFAULT <constante>` seja, no SQLite, uma operação só de metadado
  (nenhuma linha é reescrita), uma migração nunca pode ser a única escrita do app que faz o arquivo
  de capacidade fixa crescer — o degrau saca da reserva por defesa, em vez de presumir que a
  operação não vai custar página nenhuma. O default é a **constante** `x''` — não uma expressão, não
  uma subconsulta — então nenhuma linha de `contacts` é reescrita.
- A mensagem de recusa de banco **mais novo** que o build suporta não mudou de forma — continua
  vindo de `check(version <= SCHEMA_VERSION)` em `migrate()` — só o número que ela relata mudou:
  um cofre com `user_version = 5` hoje é recusado com `"...this build supports up to 4"` em vez de
  `"...up to 3"`.

Nada nisso foi inventado para o T4.17: é o mesmo `migrate()`/`migrateStep()`/`reserveMigrationPages()`
documentado em detalhe na seção "2026-09-17 — Correção: `open()` não migrava o esquema" acima, agora
com um quarto degrau.

### Por que o default vazio é o valor CORRETO, não um placeholder

`x''` não é um "ainda não implementado" temporário — é o valor semanticamente certo para **todo
contato pareado antes deste build**. Todo contato assim teve seu token cunhado em
`PairingEngine.newOffer()`, publicado dentro da nossa oferta assinada e descartado junto com ela: não
existe, em lugar nenhum do aparelho, um segredo para preencher ali. `x''` = "não registrado" é
literalmente a verdade sobre essa linha, e o segredo perdido não pode ser inventado pela migração —
inventar um valor seria pior do que deixá-lo vazio, porque pareceria um token válido.

A consequência precisa ficar escrita, porque é honesta e não é um bug: esse contato continua
conseguindo **tocar** a nossa campainha normalmente (o par `doorbell_onion`/`doorbell_token` dele
está intacto desde a v3), mas não poderá ser **reconhecido** quando for ele quem toca a nossa — só um
novo pareamento recupera isso. Espera-se que a feature de campainha (T4.17, ainda não implementada)
trate `doorbell_token_issued` vazio como "sem campainha registrada com este contato", do mesmo jeito
que já trata `doorbell_onion`/`doorbell_token` vazios como "sem campainha" no sentido oposto.

### Vantagens

- Zero mecanismo novo: quem entende o degrau v2→v3 entende o v3→v4 inteiro.
- Cofre real em v1, v2 ou v3 migra até v4 na próxima abertura, sem re-parear nenhum contato.
- Arquivo de capacidade fixa nunca cresce — a migração salda da mesma reserva que toda escrita normal
  usa.
- `createSchemaV1` continua sendo um registro fiel do que a v1 era; a história do esquema inteiro
  (v1 → v2 → v3 → v4) fica auditável em `migrateStep` e no KDoc de `SCHEMA_VERSION`, em um lugar só.
- A assimetria "sei tocar, não sei atender" deixa de existir sem nenhuma mudança de formato de QR —
  os três campos continuam vindo da mesma oferta assinada de sempre (dois do par, um da nossa
  própria oferta), só a coluna que faltava foi acrescentada.

## 2026-09-23 — `cipher_memory_security = ON` antes da chave, via `SQLiteDatabaseHook` (T4.7b)

### Motivo

A revisão de 2026-09-15 encontrou uma lacuna de tempo em `openConfigured`: `PRAGMA
cipher_memory_security = ON` só era aplicado **depois** de `SQLiteDatabase.openOrCreateDatabase`
retornar, mas essa própria chamada já processa a `key` internamente (executa o `PRAGMA key = '...';`
do SQLCipher como parte de abrir a conexão) antes de devolver o controle. `cipher_memory_security`
existe para que o alocador do SQLCipher mantenha memória sensível a cifra (inclusive a chave que uma
derivação de `PRAGMA key` retém) em alocações protegidas (`mlock`, zeradas ao liberar); aplicado só
depois, a própria derivação da chave rodava sob o alocador comum, sem essa proteção. Afetava
`initialize()` e `open()` igualmente, para `VaultSlot.REAL` e `VaultSlot.DECOY`, já que os dois
passam por este único `openConfigured`.

### Como era antes

```kotlin
private fun openConfigured(path: Path, key: ByteArray): SQLiteDatabase {
    val database = SQLiteDatabase.openOrCreateDatabase(path.toFile(), key, null, null)
    try {
        val journalMode = applyPragma(database, "journal_mode=DELETE")
        ...
        applyPragma(database, "cipher_memory_security=ON")
        check(pragmaLong(database, "cipher_memory_security") == 1L) { "SQLCipher memory security is unavailable" }
        ...
```

`cipher_memory_security=ON` era o **primeiro** ponto em que esse pragma era aplicado — depois de a
chave já ter sido processada pela chamada de abertura.

### Como ficou

```kotlin
private val cipherMemorySecurityHook = object : SQLiteDatabaseHook {
    override fun preKey(connection: SQLiteConnection) {
        connection.execute("PRAGMA cipher_memory_security = ON;", null, null)
    }
    override fun postKey(connection: SQLiteConnection) = Unit
}

private fun openConfigured(path: Path, key: ByteArray): SQLiteDatabase {
    val database = SQLiteDatabase.openOrCreateDatabase(path.toFile(), key, null, null, cipherMemorySecurityHook)
    try {
        val journalMode = applyPragma(database, "journal_mode=DELETE")
        ...
        applyPragma(database, "cipher_memory_security=ON")
        check(pragmaLong(database, "cipher_memory_security") == 1L) { "SQLCipher memory security is unavailable" }
        ...
```

O pragma pós-abertura e o `check` continuam exatamente como estavam — agora como verificação de
"a configuração se manteve", não mais como a única vez em que o pragma é aplicado.

### Evidência de que a API se comporta assim

`net.zetetic:sqlcipher-android` 4.19 não publica `-sources.jar`; a assinatura foi confirmada
decodificando diretamente o pool de constantes e a tabela de métodos dos `.class` dentro do AAR
(`~/nomessages-tools/gradle-home/caches/modules-2/files-2.1/net.zetetic/sqlcipher-android/4.19.0/`):
`SQLiteDatabaseHook` expõe `preKey(SQLiteConnection)`/`postKey(SQLiteConnection)`, e
`SQLiteDatabase` tem a sobrecarga `openOrCreateDatabase(File, byte[], CursorFactory?,
DatabaseErrorHandler?, SQLiteDatabaseHook)`. Pelo próprio contrato do hook do SQLCipher, `preKey`
roda na conexão **antes** do `PRAGMA key` interno; `postKey`, logo depois. `SQLiteConnection` expõe
`execute(String, Array<Any?>?, CancellationSignal?)`, usado para rodar o pragma.

### Vantagens

- Fecha a lacuna de tempo: a derivação/retenção da chave agora roda inteira sob o alocador
  protegido do SQLCipher, em vez de só a partir do primeiro pragma pós-abertura.
- Vale para os dois fluxos (`initialize`/`open`) e os dois slots (`REAL`/`DECOY`) automaticamente,
  porque ambos passam por este único `openConfigured` — nenhuma duplicação de lógica por slot.
- O `check` pós-abertura já existente vira uma segunda linha de defesa (verificação), em vez de ser
  removido — nenhuma garantia anterior foi perdida.
- Coberto por `AndroidVaultStorageTest.cipherMemorySecurityIsOnImmediatelyAfterOpenForBothSlots`
  (instrumentado, ver `docs/changes/AndroidVaultStorageTest.kt.md`).

Ver também `docs/security-model.md` ("Decoy oracles fixed...", T4.7) e
`docs/changes/security-model.md.md`.

## 2026-09-23 — `mediaCapacityBytes` e paridade de mídia em `beforeExport` (T4.1)

### Motivo

`alignAllocations` só igualava `real.files`/`decoy.files` no `create`/`resetPanicPassword`; nada
verificava que continuassem iguais durante o uso normal do cofre, e `beforeExport` só comparava os
`.db`. Ver `docs/security-model.md` ("Fixed media reservation and blind cover growth", T4.1) e
`docs/changes/MessagingEngine.kt.md` para a metade da correção que fica no motor de mensagens.

### Como era

```kotlin
class AndroidVaultStorage(
    context: Context,
    private val crypto: Crypto,
    capacityMiB: Int = DEFAULT_CAPACITY_MIB,
) : VaultStorage {
    val databaseBytes: Long
    val maxPageCount: Int
    ...
    init {
        require(capacityMiB in MIN_CAPACITY_MIB..MAX_CAPACITY_MIB) { "Unsupported database capacity" }
        databaseBytes = capacityMiB.toLong() * 1024 * 1024
        maxPageCount = (databaseBytes / PAGE_BYTES).toInt()
        loadSqlCipher()
    }

    override fun beforeExport(directory: Path) {
        synchronized(activeLock) { check(active == null) { "Close the vault database before export" } }
        for (slot in VaultSlot.entries) {
            // ...checa só o .db de cada slot...
        }
    }
```

### Como ficou

```kotlin
class AndroidVaultStorage(
    context: Context,
    private val crypto: Crypto,
    capacityMiB: Int = DEFAULT_CAPACITY_MIB,
    mediaCapacityMiB: Int = DEFAULT_MEDIA_CAPACITY_MIB,
) : VaultStorage {
    val databaseBytes: Long
    val maxPageCount: Int
    val mediaCapacityBytes: Long
    ...
    init {
        require(capacityMiB in MIN_CAPACITY_MIB..MAX_CAPACITY_MIB) { "Unsupported database capacity" }
        require(mediaCapacityMiB in MIN_MEDIA_CAPACITY_MIB..MAX_MEDIA_CAPACITY_MIB) { "Unsupported media capacity" }
        databaseBytes = capacityMiB.toLong() * 1024 * 1024
        mediaCapacityBytes = mediaCapacityMiB.toLong() * 1024 * 1024
        maxPageCount = (databaseBytes / PAGE_BYTES).toInt()
        loadSqlCipher()
    }

    override fun beforeExport(directory: Path) {
        synchronized(activeLock) { check(active == null) { "Close the vault database before export" } }
        for (slot in VaultSlot.entries) { /* ...igual a antes... */ }
        // Novo: mesma checagem de paridade de mídia que `alignAllocations` já fazia no setup/reset,
        // agora também aqui - fecha o gate que só olhava os .db.
        val realMedia = mediaAllocatedBytes(mediaDirectory(directory, VaultSlot.REAL))
        val decoyMedia = mediaAllocatedBytes(mediaDirectory(directory, VaultSlot.DECOY))
        check(realMedia == decoyMedia) { "Vault media allocations differ" }
    }
```

Novas constantes no companion object: `DEFAULT_MEDIA_CAPACITY_MIB = 512`, `MIN_MEDIA_CAPACITY_MIB =
16`, `MAX_MEDIA_CAPACITY_MIB = 4096` — mesmo padrão de `DEFAULT_CAPACITY_MIB`/`MIN_CAPACITY_MIB`/
`MAX_CAPACITY_MIB` já existentes para o banco.

### Por que 512 MiB por padrão

64 vezes o limite de 8 MiB por anexo que `MessagingEngine.sendAttachment` já impõe — generoso para
uso diário, mas dentro de `MAX_MEDIA_CAPACITY_MIB` para nunca esgotar sozinho o armazenamento de um
aparelho de teste pequeno. `NoMessagesController` usa o valor calculado do `AndroidVaultStorage` que
já constrói (sem parâmetro explícito), então o app de produção usa o padrão.

### Vantagens

- Espelha exatamente o padrão já existente para `databaseBytes`/`capacityMiB` — sem primitivo novo,
  só mais um par (valor calculado, validação de faixa).
- `beforeExport` agora protege o cenário que faltava: exportar um cofre que já foi usado, não só um
  recém-criado.
- Quem chama `AndroidVaultStorage(...)` sem passar `mediaCapacityMiB` (todo código de produção)
  continua funcionando sem mudança nenhuma — parâmetro com valor padrão, compatível com testes
  existentes.

### Verificação

`:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL`. Suíte instrumentada em `emulator-5556`: `OK (23
tests)`, incluindo os dois casos novos de T4.1 (ver `docs/changes/AndroidVaultStorageTest.kt.md`).

Ver também `docs/changes/MessagingEngine.kt.md`, `docs/changes/MessagingError.kt.md` e
`docs/changes/NoMessagesController.kt.md`.

## 2026-09-23 (revisão P1 do T4.1) — `padMediaToMatch`: preenchimento cego para o reset de senha de pânico

### Motivo

Ver `docs/changes/VaultManager.kt.md` ("`resetPanicPassword` volta a passar em cofres com mídia
real") para o achado completo. Resumo: `VaultManager.resetPanicPassword` reconstrói `decoy.files` do
zero e precisa completá-lo até igualar `real.files` (que pode ter crescido durante o uso normal)
antes de `alignAllocations` checar a paridade — senão a troca de senha de pânico falhava em qualquer
cofre que já tivesse mídia real. `VaultStorage` ganhou um método novo para isso
(`docs/changes/VaultSession.kt.md`); esta é a implementação de produção.

### Como ficou

```kotlin
override fun padMediaToMatch(directory: Path, slot: VaultSlot, matchSlot: VaultSlot) {
    val target = mediaAllocatedBytes(mediaDirectory(directory, matchSlot))
    val current = mediaAllocatedBytes(mediaDirectory(directory, slot))
    if (current >= target) return
    writeBlindFiller(mediaDirectory(directory, slot), target - current)
}

private fun writeBlindFiller(directory: Path, deficitBytes: Long) {
    require(deficitBytes > 0)
    var remaining = deficitBytes
    while (remaining > 0) {
        val chunk = minOf(remaining, PAD_CHUNK_BYTES).toInt()
        val name = "${crypto.random(16).toHex()}.bin"
        val target = directory.resolve(name)
        val temporary = directory.resolve(".$name.part")
        val filler = crypto.random(chunk)
        try {
            Files.newOutputStream(temporary, CREATE_NEW, WRITE).use { it.write(filler) }
            Files.move(temporary, target, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
            filler.fill(0)
        }
        remaining -= chunk
    }
}
```

`PAD_CHUNK_BYTES` (4 MiB) mantém cada chamada a `crypto.random` bem abaixo de `Int.MAX_VALUE`, mesmo
que o déficit teórico chegue perto de `MAX_MEDIA_CAPACITY_MIB` (4096 MiB, que sozinho já excede
`Int.MAX_VALUE` em bytes) — por isso o preenchimento é feito em um ou mais arquivos, nunca uma única
alocação gigante.

### Por que é seguro

- Reaproveita `mediaAllocatedBytes`/`mediaDirectory`, já existentes e já usados por
  `alignAllocations`/`beforeExport` — a mesma noção de "tamanho de um slot de mídia" em todo lugar.
- Cada blob de preenchimento tem nome independentemente aleatório (`crypto.random(16)`, o mesmo
  padrão que `MessagingEngine.coverSiblingSlot` já usa para seus blobs cegos) — sem relação com
  nenhum identificador real, então não introduz nenhuma correlação nova entre `real.files` e
  `decoy.files`.
- Só escreve no diretório de mídia de `slot` (aqui, sempre `stage/decoy.files`, um diretório de
  estágio descartável até o `Files.move` final de `resetPanicPassword`); `matchSlot`'s directory
  (`real.files`) só é lido, nunca escrito.
- No-op quando `current >= target` — cobre `create()` (os dois slots começam vazios, então já são
  iguais) sem nenhum efeito colateral.

### Vantagens

- Reaproveita a mesma técnica de "blob cego, nome aleatório" que `MessagingEngine.coverSiblingSlot`
  já usa, em vez de inventar um segundo mecanismo de preenchimento.
- Encapsulado atrás de `VaultStorage` — `VaultManager` só chama `padMediaToMatch`, sem conhecer nada
  sobre como a mídia é representada em disco.

### Verificação

`:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL`. Suíte instrumentada em `emulator-5556`, incluindo
o caso novo `resetPanicPasswordSucceedsAfterMediaHasGrownPastTheSetupFixtureSize` (ver
`docs/changes/AndroidVaultStorageTest.kt.md`).

Ver também `docs/changes/VaultManager.kt.md`, `docs/changes/VaultSession.kt.md` e
`docs/security-model.md` ("Fixed media reservation and blind cover growth — follow-up 2026-09-23",
T4.1).
