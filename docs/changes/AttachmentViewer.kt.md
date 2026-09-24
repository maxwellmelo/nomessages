# app/src/main/kotlin/dev/mx3/nomessages/ui/AttachmentViewer.kt

## 2026-09-16 — `MemoryMediaDataSource` passa a ser `internal` para reuso (T4.8, mídia inline — parte A: áudio)

### Motivo

A bolha de áudio inline nova (`ChatScreen.kt`) e o decodificador de forma de onda do lado receptor
(`AudioWaveformDecoder.kt`, novo) precisam do mesmo padrão "bytes decifrados envolvidos como
`MediaDataSource`, zerados no `close()`" que este arquivo já usava para o visualizador de mídia em
tela cheia. Em vez de duplicar a classe, ela foi tornada visível para o resto do pacote `ui`.

### Como era antes

```kotlin
private class MemoryMediaDataSource(private val bytes: ByteArray) : MediaDataSource() { ... }
```

### Como é agora

```kotlin
/**
 * Wraps an already-decrypted in-memory byte array as a [MediaDataSource] ...
 * Internal (not private) so the inline audio bubble player in `ChatScreen.kt` and the
 * receiver-side waveform decoder in `AudioWaveformDecoder.kt` can reuse the exact same
 * memory-only pattern instead of duplicating it.
 */
internal class MemoryMediaDataSource(private val bytes: ByteArray) : MediaDataSource() { ... }
```

Nenhum outro comportamento deste arquivo mudou: `MediaPreview`, `PdfPreview`, `ImagePreview`,
`TextPreview` continuam exatamente como estavam. A visibilidade `internal` (em vez de `public`)
mantém o tipo fora da API pública do módulo — só acessível dentro do módulo `app`, que é exatamente
onde `ChatScreen.kt` e `AudioWaveformDecoder.kt` vivem.

### Vantagens

- Elimina a duplicação que existiria se a bolha de áudio e o decodificador de forma de onda tivessem
  cada um sua própria cópia da mesma classe "bytes em memória como `MediaDataSource`".
- Preserva o invariante de segurança que esta classe já garantia (zera os bytes no `close()`) num
  único lugar auditável, agora reaproveitado por três consumidores em vez de um.

## 2026-09-16 — Pinch-zoom, pan e swipe entre imagens (T4.8, mídia inline — parte B: fotos, itens B2/B3)

### Motivo

`ImagePreview` já fazia decodificação limitada em `OwnedResource` (via `ImageDecoder` +
`decodeBoundedImage`), mas não tinha zoom nem qualquer jeito de navegar para outra imagem da mesma
conversa sem fechar o visualizador. B2 pede pinch-to-zoom/pan (1x–6x); B3 pede deslizar entre as
imagens de uma conversa, decifrando sob demanda — nunca pré-carregando uma vizinha.

### Como era antes

```kotlin
internal fun AttachmentViewer(attachment: AttachmentUi, onClose: () -> Unit) {
    ...
    AttachmentKind.IMAGE -> ImagePreview(attachment.bytes)
    ...
}

private fun ImagePreview(bytes: ByteArray) {
    ...
    Image(
        bitmap = requireNotNull(bitmap).asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentScale = ContentScale.Fit,
    )
}
```

### Como é agora

```kotlin
internal fun AttachmentViewer(
    attachment: AttachmentUi,
    gallery: List<String> = emptyList(),
    onNavigate: (String) -> Unit = {},
    onClose: () -> Unit,
) {
    var pendingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attachment.id) { if (attachment.id == pendingId) pendingId = null }
    ...
    AttachmentKind.IMAGE -> ImagePreview(
        bytes = attachment.bytes,
        attachmentId = attachment.id,
        gallery = gallery,
        onNavigate = { targetId ->
            if (pendingId == null && targetId != attachment.id) { pendingId = targetId; onNavigate(targetId) }
        },
    )
    ...
    if (pendingId != null) CircularProgressIndicator() // spinner over the still-visible image while the sibling decrypts
}

private fun ImagePreview(bytes: ByteArray, attachmentId: String, gallery: List<String>, onNavigate: (String) -> Unit) {
    ...
    var scale by remember(bytes) { mutableFloatStateOf(MIN_IMAGE_ZOOM) }
    var offsetX by remember(bytes) { mutableFloatStateOf(0f) }
    var offsetY by remember(bytes) { mutableFloatStateOf(0f) }
    ...
    Image(
        ...,
        modifier = Modifier.fillMaxSize().padding(16.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY)
            .pointerInput(attachmentId, gallery) {
                awaitEachGesture {
                    // 2+ fingers: pinch-zoom/pan. 1 finger: pan only if already zoomed in,
                    // otherwise accumulate horizontal drag towards a swipe-navigate decision
                    // made once every pointer lifts.
                    ...
                }
            },
        contentScale = ContentScale.Fit,
    )
}
```

O gesto foi escrito à mão (`awaitEachGesture` + `awaitPointerEvent()`, contando `event.changes.size`)
em vez de empilhar `detectTransformGestures` (biblioteca) com um `detectDragGestures` separado para o
swipe: `detectTransformGestures` trata até um arrasto de um dedo só como "pan", então os dois
detectores disputariam o mesmo evento de ponteiro pela mesma direção horizontal, e o swipe perderia a
corrida aleatoriamente. Com contagem de ponteiros decidida manualmente: 2+ dedos sempre fazem
pinça/zoom; 1 dedo só move a imagem se já estiver com `scale > 1x`; caso contrário só acumula o
deslocamento horizontal, decidindo ao soltar (`horizontalDrag <= -120px` → próxima imagem,
`>= 120px` → anterior, contra `gallery.getOrNull(index ± 1)`, sem `wrap-around`).

Navegar simplesmente chama `onNavigate(targetId)`, que `NoMessagesApp.kt` conecta a
`actions::openAttachment` (ver `docs/changes/NoMessagesApp.kt.md`) — o mesmo caminho de decifrar/zerar
que `openAttachment` já usa para o anexo atual, então **só a imagem sendo vista por vez tem bytes
decifrados residentes**; nenhuma vizinha é pré-decifrada. `pendingId` em `AttachmentViewer` mostra um
spinner sobre a imagem atual enquanto a vizinha ainda está sendo decifrada, e se limpa sozinho assim
que `attachment.id` alcança o id pedido. Zoom/pan são resetados (`remember(bytes)`) sempre que os
bytes trocam — seja abrindo um anexo diferente, seja deslizando para uma imagem irmã.

### Vantagens

- Zoom/pan e swipe nunca competem pelo mesmo evento de ponteiro — a decisão de pointer-count é feita
  uma vez, no mesmo laço, em vez de dois detectores de gesto independentes torcendo pelo mesmo toque.
- B3 não introduz nenhum buffer de galeria: `AttachmentUi` continua guardando os bytes de um único
  anexo por vez, e `imageGallery` (ver `docs/changes/UiContract.kt.md`) é só uma lista de ids.
- `AttachmentViewer`/`ImagePreview` continuam funcionando sem `gallery`/`onNavigate` (parâmetros com
  valor padrão) para qualquer chamador que não precise de navegação — não quebra nenhum outro uso.
- `PdfPreview`, `TextPreview`, `MediaPreview` (áudio/vídeo) não foram tocados.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`. As três novas variáveis de estado
(`scale`/`offsetX`/`offsetY`) usam `mutableFloatStateOf` (não `mutableStateOf<Float>`) desde o
início, evitando o apontamento `AutoboxingStateCreation` que `lintDebug` já assinala em código
pré-existente deste mesmo arquivo (`pageIndex` em `PdfPreview`, não tocado aqui).

## 2026-09-16 — Barra de progresso arrastável no player em tela cheia + "só um player por vez" (T4.8, mídia inline — parte C: vídeo, agente 3 de 3)

### Motivo

O SPEC pede explicitamente "tocar abre player em tela cheia com progresso arrastável", mas
`MediaPreview` (áudio e vídeo em tela cheia) só tinha um botão play/pause — sem barra de progresso,
sem rótulos de tempo, e sem nenhuma integração com `AudioPlaybackCoordinator`: abrir o player em tela
cheia podia deixar uma bolha de áudio inline tocando por baixo, violando a regra "só um player por
vez" que a parte A já implementou para as bolhas.

### Como era antes

```kotlin
@Composable
private fun MediaPreview(bytes: ByteArray, video: Boolean) {
    ...
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        player.setOnPreparedListener {
            prepared = true
            if (video) { it.start(); playing = true }
        }
        player.setOnCompletionListener { playing = false }
        ...
        onDispose { ...; player.release(); dataSource.close() }
    }
    ...
    Column(...) {
        if (video) VideoSurface(player, ...)
        Button(
            enabled = prepared,
            onClick = {
                if (player.isPlaying) { player.pause(); playing = false }
                else { player.start(); playing = true }
            },
        ) { Icon(...); Text(...) }
    }
}
```

Chamada: `AttachmentKind.AUDIO -> MediaPreview(attachment.bytes, video = false)` /
`AttachmentKind.VIDEO -> MediaPreview(attachment.bytes, video = true)` — sem id, sem como se
registrar no coordenador.

### Como é agora

```kotlin
@Composable
private fun MediaPreview(bytes: ByteArray, attachmentId: String, video: Boolean) {
    ...
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    fun pausePlayback() { runCatching { player.pause() }; playing = false }

    DisposableEffect(player) {
        player.setOnPreparedListener {
            prepared = true
            durationMs = it.duration.coerceAtLeast(0)
            if (video) {
                AudioPlaybackCoordinator.requestPlay(attachmentId, ::pausePlayback)
                it.start(); playing = true
            }
        }
        player.setOnCompletionListener {
            playing = false; positionMs = 0
            runCatching { it.seekTo(0) }
            AudioPlaybackCoordinator.release(attachmentId)
        }
        ...
        onDispose { AudioPlaybackCoordinator.release(attachmentId); ...; player.release(); dataSource.close() }
    }

    // Another player (an inline audio bubble, or a different full-screen viewer) taking over the
    // coordinator pauses this one.
    LaunchedEffect(attachmentId) {
        snapshotFlow { AudioPlaybackCoordinator.activeId }.collect { active ->
            if (active != attachmentId && playing) { runCatching { player.pause() }; playing = false }
        }
    }
    LaunchedEffect(playing) {
        while (playing) { positionMs = player.currentPosition; delay(200) }
    }
    ...
    Column(...) {
        if (video) VideoSurface(player, ...)
        Button(
            onClick = {
                if (player.isPlaying) { pausePlayback(); AudioPlaybackCoordinator.release(attachmentId) }
                else { AudioPlaybackCoordinator.requestPlay(attachmentId, ::pausePlayback); player.start(); playing = true }
            },
        ) { Icon(...); Text(...) }
        if (prepared && durationMs > 0) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                valueRange = 0f..durationMs.toFloat(),
                onValueChange = { value -> positionMs = value.toInt(); runCatching { player.seekTo(positionMs) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${formatDurationMs(positionMs)} / ${formatDurationMs(durationMs)}", ...)
        }
    }
}
```

Chamada atualizada: `MediaPreview(attachment.bytes, attachmentId = attachment.id, video = false/true)`.

Três peças:

- **Barra + rótulos**: um `Slider` do Material3 (`onValueChange` já move o `positionMs` mostrado e
  chama `player.seekTo` a cada arrasto — mesma sensação "buscar enquanto arrasta" da forma de onda da
  bolha de áudio inline) mais um rótulo `posição / duração` com `formatDurationMs` (`UiLogic.kt`), a
  mesma função que a bolha de áudio já usa. `positionMs` é atualizado por um `LaunchedEffect(playing)`
  que sondam `player.currentPosition` a cada 200ms — o mesmo padrão de polling de `AudioBubble`
  (`LaunchedEffect(playing) { while (playing) { ...; delay(120) } }`), só com um intervalo levemente
  maior porque tela cheia não precisa da mesma granularidade que uma forma de onda de 48 barras.
  Deliberadamente **não** restrita a `video`: áudio em tela cheia ganha a mesma barra "de graça", como
  a tarefa sugeria como polimento opcional, sem nenhum código extra.
- **Coordenador**: `MediaPreview` agora participa do mesmo `AudioPlaybackCoordinator` que as bolhas de
  áudio já usam, chaveado por `attachmentId`. Tocar (autoplay de vídeo ao preparar, ou o botão para
  qualquer um dos dois) chama `requestPlay`, que pausa quem quer que estivesse tocando antes —
  cobrindo o invariante 8 da tarefa ("abrir um player em tela cheia pausa uma bolha de áudio tocando")
  sem precisar de nenhum canal novo de comunicação entre `ChatScreen.kt` e `AttachmentViewer.kt`: os
  dois já falam com o mesmo objeto `internal object` compartilhado. Um `snapshotFlow` simétrico ao da
  `AudioBubble` cobre o sentido inverso (uma bolha assumindo o coordenador pausa este player).
- **Descarte**: `AudioPlaybackCoordinator.release(attachmentId)` no `onDispose` garante que fechar o
  visualizador nunca deixa um ponteiro "tocando" morto no coordenador.

### Vantagens

- Fecha o requisito do `SPEC.md` ("progresso arrastável") que estava pendente desde antes desta
  tarefa - `MediaPreview` já existia e já funcionava, só faltava a barra.
- "Só um player por vez" passa a valer literalmente em todo o app (bolhas inline + os dois players em
  tela cheia), não só entre bolhas - fechando o item pendente do invariante 8 da tarefa, sem introduzir
  nenhum estado novo no coordenador em si (`AudioPlaybackCoordinator.kt`, não tocado: sua API já era
  genérica o bastante - "id" + callback de pausa - para servir os dois casos).
- `PdfPreview`, `TextPreview`, `ImagePreview`, `VideoSurface` não foram tocados.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Correcao: o player de tela cheia tambem e desmontado no lock

**Como era (47afa3e):** `MediaPreview` guardava `copiedBytes = bytes.copyOf()` e um
`MemoryMediaDataSource` sobre essa copia, e so os liberava no `onDispose` do `DisposableEffect`.
O coordenador recebia apenas um callback de `pause()`, que o `reset()` do lock descartava sem
invocar.

**Como ficou:**

```kotlin
val playerToken = remember(player) { Any() }

DisposableEffect(player) {
    AudioPlaybackCoordinator.registerPlayer(playerToken) {
        runCatching { player.pause() }
        runCatching { player.stop() }
        playing = false
        dataSource.close()      // zera copiedBytes
    }
    ...
    onDispose {
        AudioPlaybackCoordinator.unregisterPlayer(playerToken)
        AudioPlaybackCoordinator.release(attachmentId)
        ...
    }
}
```

**Por que era necessario.** `NoMessagesController.lock()` faz `_state.value.attachment?.bytes?.fill(0)`,
mas isso zera apenas o array original do estado - **nao** a `copiedBytes` que o visualizador de tela
cheia mantem viva para o `MediaDataSource`. Com o auto-lock em segundo plano (onde nao ha
recomposicao e portanto nao ha `onDispose`), um video/audio aberto continuava reproduzindo e a copia
decifrada permanecia na memoria depois do cofre bloqueado.

**Garantia restaurada.** Reproducao de tela cheia interrompida imediatamente no lock e a copia
decifrada dos bytes do anexo zerada no mesmo passo.

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.
