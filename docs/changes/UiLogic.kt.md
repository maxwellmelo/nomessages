# app/src/main/kotlin/dev/mx3/nomessages/ui/UiLogic.kt

## 2026-09-14 — T4.2: rótulo do selo de não lidas

Tarefa: T4.2 de `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.

### Como era antes

O arquivo reunia as decisões puras da interface (`attachmentKind`, `deliveryMark`,
`validGroupSelection`, `completedGroupCheck`), mas a formatação do selo de não
lidas não estava aqui: ficava embutida em `HomeScreen.kt`, dentro do Composable,
como `chat.unread.coerceAtMost(999).toString()`. Era a única regra de
apresentação do arquivo de interface sem cobertura de teste, justamente porque
estava presa a um Composable.

### Como ficou

```kotlin
/** Above this the exact number stops being useful and would widen the chat row. */
internal const val MAX_UNREAD_BADGE = 999

/**
 * Label for the local unread counter. An empty label means no badge at all, which is also the
 * answer for a negative count, so a corrupted counter degrades to "nothing new" instead of
 * rendering a nonsensical badge.
 */
internal fun unreadBadge(count: Int): String = when {
    count <= 0 -> ""
    count > MAX_UNREAD_BADGE -> "$MAX_UNREAD_BADGE+"
    else -> count.toString()
}
```

Duas mudanças de comportamento em relação ao que estava no Composable:

- acima de 999 o rótulo passa a ser `"999+"` em vez de `"999"`. O texto anterior
  afirmava um número exato que era falso;
- contagem negativa (que não deveria ocorrer, mas seria o resultado de um
  contador corrompido) devolve rótulo vazio em vez de `"-3"`, degradando para
  "nada novo" em vez de desenhar um selo sem sentido.

### Vantagens

- A regra de apresentação sai do Composable e passa a ser testável em JVM pura,
  como já é o caso de `deliveryMark` e `validGroupSelection`.
- O teto vira a constante nomeada `MAX_UNREAD_BADGE`, usada tanto pelo código
  quanto pelo teste, em vez de um `999` solto no meio do layout.
- O sufixo `+` deixa o selo honesto quando a contagem estoura o teto.

### Por que a mudança foi feita

T4.2 previa "1 caso novo" em `UiLogicTest.kt`. Para haver o que testar sem
instrumentação de Compose, a decisão de formatação precisava existir como função
pura neste arquivo — que é exatamente o papel dele no módulo de interface.

## 2026-09-15 — `shouldApplySecureFlag`: decisão pura para a chave de captura de tela em debug

Escopo desta mudança: apenas a nova função `shouldApplySecureFlag`, acrescentada ao final do
arquivo. Nenhuma função existente (`attachmentKind`, `deliveryMark`, `unreadBadge`,
`validGroupSelection`, `completedGroupCheck`) foi tocada.

### Como era antes

O arquivo não tinha nenhuma função relacionada a `FLAG_SECURE`; essa decisão nem existia — a flag
era sempre aplicada incondicionalmente em `MainActivity.kt`.

### Como ficou

```kotlin
internal fun shouldApplySecureFlag(isDebug: Boolean, propertyValue: String?): Boolean =
    !(isDebug && propertyValue == "1")
```

Verificado antes de criar: já existia `UiLogic.kt` como o local convencional do módulo `:app` para
lógica de UI pura e testável (é onde `attachmentKind`, `deliveryMark`, `unreadBadge`,
`validGroupSelection` e `completedGroupCheck` já viviam) — nenhum arquivo novo foi criado.

Quatro casos cobertos por `UiLogicTest.kt`:

| `isDebug` | `propertyValue` | resultado | significado |
|---|---|---|---|
| `true` | `"1"` | `false` | debug + opt-in explícito: não aplica `FLAG_SECURE` |
| `true` | `"0"` ou `null` | `true` | debug sem opt-in: aplica (padrão seguro) |
| `false` | `"1"` | `true` | release ignora a propriedade por completo |
| `false` | `null` | `true` | release, padrão inalterado |

### Vantagens

- A regra ("só debug, só com o valor exato `\"1\"`") é uma função pura de duas entradas, sem
  `Context`/`Window`/reflexão — testável em JVM puro, sem instrumentação, seguindo o mesmo padrão
  já usado neste arquivo para `deliveryMark`/`unreadBadge`/`validGroupSelection`.
- Qualquer valor de propriedade que não seja exatamente `"1"` (incluindo `null`, `""`, `"true"`,
  `"yes"`) cai no lado seguro (`true`, aplica a flag) — a comparação é uma igualdade estrita com um
  único valor aceito, não uma lista de valores "falsy".

### Por que a mudança foi feita

Tarefa 4 da investigação de 2026-09-15: extrair a lógica de decisão da chave de captura de tela em
`MainActivity.kt` (`docs/changes/MainActivity.kt.md`) para uma função pura testável, no local já
convencional do projeto para esse tipo de código.

## 2026-09-15 — Decisões puras de rolagem para corrigir a ordem das mensagens do chat

Contexto completo do bug e da escolha de layout em `docs/changes/ChatScreen.kt.md`. Escopo aqui:
três funções novas, acrescentadas ao final do arquivo. Nenhuma função existente foi tocada.

### Como era antes

O arquivo não tinha nenhuma função relacionada a rolagem de lista; a decisão de "para onde rolar" e
"deve rolar automaticamente" estava embutida (e, no caso do índice, invertida) diretamente em
`ChatScreen.kt`:

```kotlin
LaunchedEffect(state.messages.size) {
    if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
}
```

### Como ficou

```kotlin
internal fun scrollIndexForNewestMessage(itemCount: Int, reverseLayout: Boolean): Int {
    require(itemCount > 0) { "Cannot compute a scroll target for an empty message list" }
    return if (reverseLayout) 0 else itemCount - 1
}

internal fun isAtBottom(firstVisibleItemIndex: Int, newestMessageIndex: Int = 0): Boolean =
    firstVisibleItemIndex == newestMessageIndex

internal fun shouldAutoScrollToNewMessage(wasAtBottom: Boolean): Boolean = wasAtBottom
```

Verificado antes de criar: `UiLogic.kt` já existia como o local convencional do módulo `:app` para
lógica de UI pura e testável — nenhum arquivo novo foi criado, as três funções só foram acrescentadas
ao que já havia.

- `scrollIndexForNewestMessage` fica explícita quanto a `reverseLayout` (em vez de fixar `0` direto
  em `ChatScreen.kt`) para continuar correta — e testável — caso o layout volte a ser cronológico
  (`reverseLayout = false`) no futuro, onde a mensagem mais nova seria o último índice, não o
  primeiro.
- `isAtBottom` e `shouldAutoScrollToNewMessage` isolam, em uma função cada, a leitura do estado de
  rolagem (`firstVisibleItemIndex == 0`) e a regra de negócio (só rolar automaticamente se já
  estava no fundo) — a segunda existe separada da primeira porque é ela que documenta a regra do
  `SPEC.md` ("não puxar o usuário para baixo se ele estava lendo mensagens antigas").

### Vantagens

- Nenhuma das três funções depende de `Compose`/`LazyListState` real — todas recebem `Int`/`Boolean`
  simples, testáveis em JVM pura, seguindo o mesmo padrão de `unreadBadge`/`shouldApplySecureFlag`
  já usado neste arquivo.
- `scrollIndexForNewestMessage` documenta, num único lugar, a relação entre a ordem DESC da consulta
  `messages_chat_time` e o índice que `reverseLayout = true` espera — em vez de esse conhecimento
  ficar implícito num `0` ou num `.lastIndex` soltos dentro do Composable.

### Por que a mudança foi feita

Correção do bug de ordem das mensagens do chat (mensagem mais recente aparecia no topo da tela em
vez de perto da caixa de texto). A tarefa pedia explicitamente extrair a lógica de decisão de
rolagem para uma função pura testável neste arquivo, em vez de deixá-la só dentro do Composable.

## 2026-09-16 — Funções puras de forma de onda/tempo para a bolha de áudio (T4.8, mídia inline — parte A: áudio, item A2)

### Motivo

A2 pede que o cálculo da forma de onda (remetente, a partir do PCM bruto, e receptor, a partir do
PCM decodificado) seja uma função pura testável em JVM, não algo enterrado dentro de código Compose
ou `MediaCodec`. A bolha de áudio também precisa formatar duração (`mm:ss`) e rótulo de velocidade
(`1x`/`1.5x`/`2x`) — a mesma lógica que este arquivo já segue para `unreadBadge`/`deliveryMark`.

### Como é agora (adicionado ao final do arquivo)

```kotlin
internal const val WAVEFORM_BUCKET_COUNT = 48
internal const val PCM_SAMPLE_RATE_HZ = 16_000

internal fun computeWaveformBuckets(pcm16: ByteArray, bucketCount: Int = WAVEFORM_BUCKET_COUNT): FloatArray
internal fun pcmDurationMs(pcmByteCount: Int, sampleRateHz: Int = PCM_SAMPLE_RATE_HZ): Int
internal fun formatDurationMs(durationMs: Int): String   // "65_000" -> "1:05"
internal fun formatSpeedLabel(speed: Float): String       // 1.5f -> "1.5x", 2f -> "2x"
```

`computeWaveformBuckets` lê PCM16 little-endian diretamente (sem alocar um `ShortArray`
intermediário), agrupa em `bucketCount` baldes por pico (máximo absoluto por balde, não média — mais
fiel ao visual do WhatsApp), e normaliza pelo balde mais alto; um buffer vazio/curto demais degrada
para uma forma de onda achatada em vez de lançar.

Usado tanto por `MemoryAudioRecorder.finish()` (remetente, sobre o PCM bruto que já tem em mãos)
quanto por `AudioWaveformDecoder.decodeAudioWaveform()` (receptor, sobre o PCM que acabou de
decodificar de WAV ou AAC) — o mesmo formato de saída dos dois lados é o que permite cachear e
comparar as duas fontes sem tratamento especial.

### Vantagens

- Cobertura JVM pura em `UiLogicTest.kt` (normalização, contagem de baldes, casos de borda) sem
  precisar de `MediaCodec`/emulador — só o encoder/decoder reais (que consomem esta função) precisam
  do teste instrumentado.
- Reaproveitada nos dois lados (remetente e receptor), então uma forma de onda cacheada por um lado é
  visualmente idêntica à recalculada pelo outro.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL` (17 casos em `UiLogicTest`, 7 novos
cobrindo estas funções e o `MediaPreviewCache`).

## 2026-09-16 — `chooseInSampleSize` e `imageGalleryIds` (T4.8, mídia inline — parte B: fotos)

### Motivo

Duas decisões puras que a bolha/visualizador de imagem precisavam e que não deviam ficar presas em
código Android: (1) quanto reduzir a amostragem de um bitmap grande antes de decodificá-lo de
verdade — a receita clássica de `BitmapFactory.Options.inSampleSize`, fácil de errar num "off-by-one"
de potência de dois; (2) a lista ordenada de ids de imagem de uma conversa, usada para popular
`UiState.imageGallery` (ver `docs/changes/UiContract.kt.md`) — puramente uma filtragem de
`List<MessageUi>`, sem nenhuma dependência de `Context`/banco.

### Como é agora (adicionado ao final do arquivo)

```kotlin
internal fun chooseInSampleSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int = 512): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || maxDimension <= 0) return 1
    var inSampleSize = 1
    var halfWidth = sourceWidth / 2
    var halfHeight = sourceHeight / 2
    while (halfWidth / inSampleSize >= maxDimension && halfHeight / inSampleSize >= maxDimension) {
        inSampleSize *= 2
    }
    return inSampleSize
}

internal fun imageGalleryIds(messages: List<MessageUi>): List<String> =
    messages.mapNotNull { message ->
        val id = message.attachmentId ?: return@mapNotNull null
        val mime = message.mimeType ?: return@mapNotNull null
        id.takeIf { attachmentKind(mime) == AttachmentKind.IMAGE }
    }
```

`chooseInSampleSize` só recebe `Int`s (sem `BitmapFactory.Options`), o que permite testá-la
diretamente em JVM; `NoMessagesController.decodeBoundedThumbnail` (ver
`docs/changes/NoMessagesController.kt.md`) é quem a conecta ao `BitmapFactory` de verdade — primeiro
lendo só as dimensões (`inJustDecodeBounds = true`), depois decodificando de verdade com o
`inSampleSize` calculado. `imageGalleryIds` preserva a ordem que a lista de entrada já tem — quem
decide a ordem (hoje, mais nova primeiro, como documentado em `docs/changes/ChatScreen.kt.md`) é o
chamador, não esta função.

### Vantagens

- `chooseInSampleSize` é a peça mais fácil de acertar errado nessa receita (limite de potência de
  dois, dimensão já pequena, entrada inválida) — isolá-la permite fixar os casos de borda em teste
  JVM sem precisar de um `Bitmap`/`BitmapFactory` real.
- `imageGalleryIds` reaproveita `attachmentKind`/`AttachmentKind.IMAGE`, já definidos neste mesmo
  arquivo, em vez de duplicar a checagem de mime type feita em `ChatScreen.kt`/`AttachmentViewer.kt`.
- As duas funções não têm estado nem efeito colateral, então o controlador só precisa chamá-las —
  nada de mock de `Context` ou de banco para testar a lógica.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL` (19 casos em `UiLogicTest`, 2 novos
cobrindo estas duas funções).


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Defeito corrigido: `chooseInSampleSize` nao limitava memoria (decompression bomb)

**Como era (47afa3e):**

```kotlin
internal fun chooseInSampleSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int = 512): Int {
    ...
    while (halfWidth / inSampleSize >= maxDimension && halfHeight / inSampleSize >= maxDimension) {
        inSampleSize *= 2
    }
    return inSampleSize
}
```

**Como ficou:**

```kotlin
internal const val PREVIEW_MAX_PIXELS: Long = 1024L * 1024L

internal fun chooseInSampleSize(
    sourceWidth: Int, sourceHeight: Int,
    maxDimension: Int = 512,
    maxPixels: Long = PREVIEW_MAX_PIXELS,
): Int {
    ...
    while (halfWidth / inSampleSize >= maxDimension && halfHeight / inSampleSize >= maxDimension) inSampleSize *= 2
    if (maxPixels > 0) {
        while (inSampleSize < MAX_IN_SAMPLE_SIZE &&
            (sourceWidth.toLong() / inSampleSize) * (sourceHeight.toLong() / inSampleSize) > maxPixels) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
```

**Por que era necessario.** A receita classica do Android e um limite **por dimensao**, nao um limite
de memoria: ela para de dividir assim que o **menor** lado cairia abaixo de `maxDimension`. Para uma
imagem 40000x500, `500/2 = 250 < 512` ja na primeira iteracao, entao `inSampleSize` permanece `1` e o
`BitmapFactory` decodifica 20 milhoes de pixels (~80 MiB em ARGB_8888). Um PNG dessas dimensoes com
conteudo quase uniforme ocupa poucos KB no fio - bem dentro do limite de 8 MiB por anexo - e as
dimensoes declaradas vem de um contato remoto, ou seja, sao escolhidas pelo atacante. Como
`OutOfMemoryError` e `Error` e nao `Exception`, o `catch (_: Exception)` de `loadImagePreview` nao o
conteria: o processo morre. O comentario do commit afirmava "miniaturas <= 512px", o que so era
verdade para proporcoes proximas de 1:1.

**Garantia restaurada.** Bound real de memoria por previa (<= 1.048.576 px, ~4 MiB), independente da
proporcao; protecao contra decompression bomb vinda de um contato. O passo novo nunca *reduz* o
`inSampleSize` escolhido pela receita classica, entao fotos de proporcao normal nao mudam de
comportamento (verificado nos testes existentes, que continuam passando sem alteracao).

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.

---

## 2026-09-16 — `isSystemIme`: função pura para o aviso de teclado de terceiros (T4.9)

### Motivo

T4.9 pede um aviso discreto quando o teclado (IME) ativo não pertence a um app de sistema. A regra
de decisão em si — "este id de IME pertence a um dos pacotes de sistema informados?" — não precisa
de nenhuma chamada Android para ser expressa, então, seguindo a convenção já estabelecida neste
arquivo (regra pura aqui, dados de entrada capturados onde precisam de API Android — que aqui
ficam em `MainActivity.kt`), ela entra aqui como função pura testável em JVM.

### Como era antes

Não existia nenhuma lógica de detecção de teclado de terceiros no app.

### Como ficou

```kotlin
internal fun isSystemIme(imeId: String, systemPackages: Set<String>): Boolean =
    imeId.substringBefore('/') in systemPackages
```

`imeId` é o formato `"pacote/Classe"` devolvido por `Settings.Secure.DEFAULT_INPUT_METHOD` e por
`InputMethodInfo.getId()`. `systemPackages` já vem filtrado pelo chamador (`MainActivity.kt`,
`computeThirdPartyImeActive`/`isSystemPackage`) por `ApplicationInfo.FLAG_SYSTEM` ou
`FLAG_UPDATED_SYSTEM_APP` — um teclado de sistema atualizado via Play Store (ex.: Gboard) conta
como confiável do mesmo jeito que um teclado de fábrica, porque ambos continuam fazendo parte da
imagem do sistema revisada pelo fabricante/Google, ao contrário de um teclado de terceiros que o
usuário instalou por conta própria.

### Vantagens

- Testável em JVM sem nenhum mock de `Context`/`PackageManager`/`InputMethodManager` — os três
  casos pedidos pela tarefa (pacote de sistema, pacote de terceiros, caso
  `FLAG_UPDATED_SYSTEM_APP`) viram três `assertEquals` diretos em `UiLogicTest.kt`.
- Um id malformado (sem `"/"`) degrada para tratar o id inteiro como nome de pacote, em vez de
  lançar exceção.

### Por que a mudança foi feita

T4.9, item 4.

## 2026-09-17 — `toggleForwardTarget` e `canConfirmForward`: a regra do seletor de encaminhamento

### Motivo

A parte C de "Encaminhar mensagem" (UI) precisa de duas decisões no diálogo de destinatários: marcar
/desmarcar um destino e saber se o botão "Enviar" pode ser habilitado. Escritas inline no
`Composable`, essas duas regras só seriam verificáveis com teste instrumentado de UI; extraídas para
`UiLogic.kt` viram duas funções puras, sem nenhum tipo Android, testáveis em JVM — o mesmo padrão já
usado por `validGroupSelection`, `unreadBadge` e `imageGalleryIds`.

### Como era antes

Não existiam. `ChatScreen.kt` não tinha seletor de destinatários.

### Como ficou (acrescentado ao final do arquivo)

```kotlin
internal fun toggleForwardTarget(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id

internal fun canConfirmForward(selectedTargets: Set<String>): Boolean = selectedTargets.isNotEmpty()
```

`toggleForwardTarget` devolve um **conjunto novo** (`selected - id` / `selected + id`) em vez de
mutar um `MutableSet`: o chamador guarda o resultado num `mutableStateOf`, e é a troca de instância
que dispara a recomposição. Um `MutableSet` mutado no lugar seria invisível para o Compose e o
checkbox não reagiria ao toque.

`canConfirmForward` é hoje um `isNotEmpty()`, mas mora ao lado do toggle de propósito: a regra
"quando o envio pode acontecer" fica num único ponto óbvio, e um limite futuro (um teto de
destinatários por encaminhamento, por exemplo) entra aqui, com teste, em vez de ser espalhado no
`Composable`.

### Testes (`app/src/test/kotlin/dev/mx3/nomessages/ui/UiLogicTest.kt`, JUnit 5)

- `forward target toggle adds an unticked recipient and removes a ticked one` — id ausente é
  adicionado, id presente é removido, alternar duas vezes volta ao estado original e o conjunto de
  entrada não é mutado.
- `forwarding can only be confirmed once at least one recipient is ticked` — conjunto vazio é
  `false`; um ou mais destinos é `true`.

### Vantagens

- Regra do seletor testável em milissegundos na JVM, sem emulador nem `ComposeTestRule`.
- Imutabilidade explícita: o conjunto de entrada nunca é alterado, o que casa com o modelo de estado
  do Compose e elimina uma classe inteira de bug de recomposição.
- Consistência com o resto de `UiLogic.kt`: nenhuma dependência de `android.*`.

### Por que a mudança foi feita

Pedido do usuário: encaminhar mensagem no estilo WhatsApp (parte C — camada de UI).

---

### Adendo (2026-09-17, mesmo dia) — `shouldMarkForwarded`: a etiqueta só vale entre conversas diferentes

**Motivo**

Regra de produto adicional pedida pelo usuário depois da Parte C: reenviar uma mensagem **para a
própria conversa onde ela já está** não é encaminhar nada — é indistinguível de digitar o mesmo
texto de novo. Marcar essas cópias com a etiqueta "Encaminhada" só produzia ruído visual e uma
afirmação de proveniência falsa ("veio de outro lugar") sobre uma mensagem que nunca saiu do chat.

**Como era antes**

Não existia regra nenhuma: `NoMessagesController.forwardMessage` passava `forwarded = true`
incondicionalmente para todos os destinos, inclusive o chat de origem.

**Como ficou (acrescentado ao final do arquivo, ao lado de `canConfirmForward`)**

```kotlin
/**
 * Whether a forwarded copy sent to [targetChatId] should carry the "Forwarded" label, given the
 * chat the original message lives in ([sourceChatId]). Resending into the same conversation the
 * message already belongs to is indistinguishable from typing it again - it only counts as a
 * forward once it crosses into a different conversation.
 */
internal fun shouldMarkForwarded(sourceChatId: String, targetChatId: String): Boolean =
    sourceChatId != targetChatId
```

**Testes (`app/src/test/kotlin/dev/mx3/nomessages/ui/UiLogicTest.kt`, JUnit 5)**

- `resending into the source chat is not labelled as forwarded` — origem igual ao destino
  (contato e grupo) devolve `false`.
- `forwarding into a different chat is labelled as forwarded` — contato → outro contato,
  contato → grupo e grupo → contato devolvem `true`.

**Vantagens**

- A decisão de **quando** ligar o bit fica numa função pura testável na JVM, no mesmo arquivo das
  outras regras do encaminhamento, em vez de virar um `if` dentro do controlador.
- Nada muda no formato do envelope, na coluna `forwarded` do banco nem na UI: só muda o valor
  calculado no momento do envio. Não houve mudança de esquema nem de wire.
- O seletor de destinatários continua ofertando o próprio chat: o usuário **pode** reenviar ali, só
  não ganha a etiqueta. Filtrar o chat de origem da lista seria tirar uma capacidade do usuário para
  resolver um problema que é só de rotulagem.
