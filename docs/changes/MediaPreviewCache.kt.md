# app/src/main/kotlin/dev/mx3/nomessages/ui/MediaPreviewCache.kt

## 2026-09-16 — Novo arquivo: cache limitado de prévias de mídia (T4.8, mídia inline — parte A: áudio)

### Motivo

A bolha de áudio inline precisa desenhar uma forma de onda de ~48 barras sem redecodificar o
áudio inteiro a cada recomposição/reabertura da conversa. Como não existia nenhum `LruCache` no
repositório, este é o primeiro. A tarefa exige explicitamente que ele seja pequeno e limitado (~24
entradas / ~8 MiB) e que seja **esvaziado de forma síncrona quando o cofre bloqueia** — o mesmo
padrão de invariante já aplicado a `_state.value.attachment?.bytes?.fill(0)` em
`NoMessagesController.kt`. Também foi desenhado para ser reutilizável por um payload de miniatura de
foto/vídeo que um agente futuro vai adicionar, em vez de forçar esse agente a construir um segundo
cache.

### Como é (arquivo novo)

```kotlin
internal sealed class MediaPreviewPayload {
    abstract val approximateByteSize: Int
    data class Waveform(val buckets: FloatArray, val durationMs: Int) : MediaPreviewPayload() { ... }
    // Reservado: data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload()
}

internal object MediaPreviewCache {
    private const val MAX_ENTRIES = 24
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    private val entries = LinkedHashMap<String, MediaPreviewPayload>(16, 0.75f, true) // LRU
    fun get(key: String): MediaPreviewPayload?
    fun put(key: String, value: MediaPreviewPayload)
    fun clear() // síncrono, chamado no lock()
}
```

- Chave: o id hex do anexo (`MessageUi.attachmentId`), o mesmo identificador já usado por
  `openAttachment`/`decryptAttachment`.
- Eviction por LRU real: `LinkedHashMap(accessOrder = true)`, então tanto o limite de contagem
  quanto o de bytes aproximados removem sempre a entrada menos recentemente tocada primeiro — nunca
  uma entrada recém-lida.
- **Nunca guarda bytes decifrados brutos** — só o payload derivado pequeno (forma de onda: 48
  `Float` + 1 `Int` ≈ 196 bytes por clipe). Os bytes decifrados usados para calcular a forma de onda
  são zerados pelo chamador (`NoMessagesController.loadAudioPreview`) logo depois do cálculo.
- `clear()` é chamado em `NoMessagesController.lock()` (no ponto síncrono onde o estado já é zerado) e
  em `closeSession()` (que cobre também `cleanupFailedActivation`, `acceptExport` e
  `changePanicPassword`, que passam por `closeOwnedSession()` → `closeSession()`).

### Vantagens

- Testável em JVM puro (sem depender de Android): `UiLogicTest.kt` cobre a eviction por LRU e o
  `clear()` síncrono sem precisar de emulador.
- Ponto de extensão único e documentado (`MediaPreviewPayload` selado) para os agentes de foto/vídeo
  reaproveitarem em vez de duplicar a infraestrutura de cache.
- Separa claramente "dado derivado pequeno, cacheável" de "bytes decifrados, nunca cacheados" — a
  distinção que sustenta o invariante 2 da tarefa.

## 2026-09-16 — `MediaPreviewPayload.Thumbnail` e `dispose()` (T4.8, mídia inline — parte B: fotos)

### Motivo

O lugar reservado para a miniatura de foto (`// Reserved for a later agent: ... Thumbnail(...)`)
precisava ser preenchido. Diferente de `Waveform` (só `FloatArray`/`Int`, memória gerenciada pela
JVM normalmente), um `Bitmap` é o tipo de recurso que este projeto já trata como "precisa ser
zerado e liberado ativamente, nunca só desreferenciado" — o mesmo padrão de
`Bitmap.wipeAndRecycle()` que `AttachmentViewer.kt` já usa para o bitmap em tela cheia (invariante 2
da tarefa: nenhum bitmap decodificado pode esperar o GC para ser reciclado). Como o cache não tinha
nenhum gancho de "isto está saindo do cache, libere o que precisar" — só removia a entrada do
`LinkedHashMap` — esse gancho precisou ser criado antes de o payload poder existir com segurança.

### Como era antes

```kotlin
internal sealed class MediaPreviewPayload {
    abstract val approximateByteSize: Int
    data class Waveform(val buckets: FloatArray, val durationMs: Int) : MediaPreviewPayload() { ... }
    // Reserved for a later agent: e.g. `data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload()`
}
// evict()/clear() só removiam entradas do LinkedHashMap, sem noção de "descartar o payload".
```

### Como é agora

```kotlin
internal sealed class MediaPreviewPayload {
    abstract val approximateByteSize: Int
    open fun dispose() {}                              // Waveform herda o no-op

    data class Waveform(val buckets: FloatArray, val durationMs: Int) : MediaPreviewPayload() { ... }

    data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload() {
        override val approximateByteSize: Int get() = bitmap.byteCount
        override fun dispose() {
            if (!bitmap.isRecycled) {
                if (bitmap.isMutable) bitmap.eraseColor(Color.TRANSPARENT)
                bitmap.recycle()
            }
        }
    }
}
```

`evict()` e `clear()` agora chamam `value.dispose()`/`it.dispose()` no exato instante em que cada
entrada sai do cache (antes de removê-la do `LinkedHashMap`), e `put()` também descarta o payload
antigo que está sendo substituído por uma chave já existente (quando não é o mesmo objeto). Como
`dispose()` é `open fun {}` por padrão, `Waveform` não precisou de nenhuma mudança de comportamento.

### Vantagens

- Fecha o invariante 2 da tarefa de forma centralizada: qualquer chamador que faça
  `MediaPreviewCache.put(id, Thumbnail(bitmap))` (hoje só `NoMessagesController.loadImagePreview`)
  ganha a garantia "este bitmap será erased+recycled quando sair do cache" de graça, sem precisar
  lembrar disso em cada call site.
- Ponto único e reaproveitável para um futuro payload de vídeo (poster frame) que também precisará
  descartar um `Bitmap` — basta seguir o mesmo `dispose()`.
- Não muda o comportamento de `Waveform`/o teste de eviction já existente (`dispose()` como no-op
  por padrão), então nenhuma asserção anterior precisou mudar.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

## 2026-09-16 — `MediaPreviewPayload.VideoPoster` e `Bitmap.wipeAndRecycle()` compartilhado (T4.8, mídia inline — parte C: vídeo, agente 3 de 3)

### Motivo

O ponto de extensão que a parte B já deixava documentado ("Ponto único e reaproveitável para um
futuro payload de vídeo (poster frame) que também precisará descartar um `Bitmap`") precisava ser
preenchido: a bolha de vídeo inline (`ChatScreen.kt`) e o visualizador em tela cheia precisam de um
poster frame + duração cacheáveis, com o mesmo descarte ativo que `Thumbnail` já garante para
imagem — nunca esperar o GC reciclar um `Bitmap`.

### Como era antes

```kotlin
data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload() {
    override val approximateByteSize: Int get() = bitmap.byteCount
    override fun dispose() {
        if (!bitmap.isRecycled) {
            if (bitmap.isMutable) bitmap.eraseColor(Color.TRANSPARENT)
            bitmap.recycle()
        }
    }
}
```

### Como é agora

```kotlin
data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload() {
    override val approximateByteSize: Int get() = bitmap.byteCount
    override fun dispose() = bitmap.wipeAndRecycle()
}

/** A poster-frame bitmap decoded from a video attachment ... plus the clip's duration. */
data class VideoPoster(val bitmap: Bitmap, val durationMs: Int) : MediaPreviewPayload() {
    override val approximateByteSize: Int get() = bitmap.byteCount + Int.SIZE_BYTES
    override fun dispose() = bitmap.wipeAndRecycle()
}
```

com o erase+recycle extraído para uma extensão de arquivo compartilhada pelos dois casos:

```kotlin
private fun Bitmap.wipeAndRecycle() {
    if (!isRecycled) {
        if (isMutable) eraseColor(Color.TRANSPARENT)
        recycle()
    }
}
```

`VideoPoster.approximateByteSize` soma `Int.SIZE_BYTES` pela duração, espelhando exatamente o que
`Waveform.approximateByteSize` já faz para o seu próprio `Int` de duração. A extensão privada tem o
mesmo nome da já existente em `AttachmentViewer.kt` (`Bitmap.wipeAndRecycle()`), mas Kotlin escopa
`private fun` de nível de arquivo por arquivo — não há colisão nem reuso cruzado; são dois pontos
que precisam ser mantidos em sincronia manualmente (documentado no comentário da extensão nova),
não uma dependência entre os dois arquivos.

### Vantagens

- Elimina a duplicação literal do bloco erase+recycle entre `Thumbnail` e `VideoPoster` dentro deste
  arquivo, sem tocar em `AttachmentViewer.kt` (uma preocupação diferente: decodificação para o
  visualizador em tela cheia, não eviction de cache).
- Fecha o invariante 2 da tarefa também para vídeo: qualquer `MediaPreviewCache.put(id,
  VideoPoster(...))` (hoje só `NoMessagesController.loadVideoPreview`) ganha descarte ativo de graça.
- Nenhuma mudança de comportamento em `Thumbnail`/`Waveform` nem nos testes de eviction já
  existentes — só o `dispose()` de `Thumbnail` foi reescrito para chamar a extensão compartilhada,
  com o mesmo corpo de antes.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Defeito corrigido: `recycle()` de bitmap ainda em uso pela UI (crash real)

**Como era (47afa3e):**

```kotlin
data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload() {
    override fun dispose() = bitmap.wipeAndRecycle()   // eraseColor + recycle()
}
...
fun put(key, value) { entries.remove(key)?.let { ...; if (it !== value) it.dispose() }; ... }
fun clear()          { entries.values.forEach { it.dispose() }; ... }
private fun evict()  { ...; eldest.value.dispose(); iterator.remove() }
```

**Como ficou:**

```kotlin
data class Thumbnail(val bitmap: Bitmap) : MediaPreviewPayload() {
    override fun wipe() = bitmap.wipeIfMutable()       // eraseColor, NUNCA recycle()
}
...
fun put(key, value) { entries.remove(key)?.let { totalBytes -= it.approximateByteSize }; ... }  // so desvincula
fun clear()          { entries.values.forEach { runCatching { it.wipe() } }; ... }              // apaga pixels, nao recicla
private fun evict()  { ...; iterator.remove() }                                                 // so desvincula
```

**Por que era necessario.** `MediaPreviewCache.get()` devolve o proprio `Bitmap`, e
`ImageBubble`/`VideoBubble` (`ChatScreen.kt:424-453` e `465-525`) o guardam em estado `remember`ado e
o desenham via `asImageBitmap()`. O cache, porem, reciclava esse mesmo bitmap ao despeja-lo por LRU -
em outra thread (`Dispatchers.IO`, dentro de `loadImagePreview`) e sem qualquer contagem de uso.
Desenhar um bitmap reciclado lanca
`java.lang.IllegalArgumentException: Canvas: trying to use a recycled bitmap` no proximo frame.

Nao e um caminho raro: uma miniatura 512x512 ARGB_8888 ocupa exatamente 1 MiB, logo o orcamento de
8 MiB comeca a despejar a partir da **nona** previa, muito antes do limite de 24 entradas. Rolar uma
conversa com mais de oito fotos/videos recicla bitmaps de bolhas que ainda estao compostas e
visiveis. O mesmo valia para `put()` substituindo uma chave e para `clear()` chamado de
`closeSession()` - que roda em `Dispatchers.IO`, concorrente com o desenho.

**Garantia restaurada.** Ausencia de crash por corrida entre despejo do cache e desenho. O limite do
cache (24 entradas **e** 8 MiB) e o esvaziamento sincrono no lock continuam intactos; o que mudou e
apenas *como* uma entrada sai do cache.

**Observacao honesta sobre a limpeza de memoria.** `BitmapFactory` devolve bitmaps **imutaveis** por
padrao, e `wipeAndRecycle()` fazia `if (isMutable) eraseColor(...)` - ou seja, o "zero-and-recycle"
descrito no `security-model.md` nunca chegava a zerar nada para miniaturas; so reciclava. Agora
`NoMessagesController.decodeBoundedThumbnail` decodifica com `inMutable = true`, entao o `eraseColor` no
lock passa a ser real pela primeira vez. O poster de video do `MediaMetadataRetriever` continua
imutavel e so pode ser desvinculado (documentado no KDoc do payload). Bitmaps despejados por LRU
passam a depender do GC - um custo de higiene de memoria aceito em troca de eliminar o crash, e que
nao afeta a fronteira "nenhum byte decifrado toca o disco".

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.
