# app/src/androidTest/kotlin/dev/mx3/nomessages/ui/VideoPreviewDecodeTest.kt

## 2026-09-16 — Novo arquivo: teste instrumentado de poster frame + duração (T4.8, mídia inline — parte C: vídeo, agente 3 de 3)

### Motivo

`NoMessagesController.loadVideoPreview`/`decodeVideoPoster` dependem de `MediaMetadataRetriever` e
`MediaCodec`, que a sandbox de teste JVM não fornece de verdade (a diferença que já levou
`MemoryAudioEncoderTest.kt` a viver em `androidTest`, não em `test`). A tarefa pede um teste
instrumentado opcional, no mesmo estilo de `MemoryAudioEncoderTest.kt`, que valide a extração de
poster frame + duração contra um vídeo sintético em memória — sem precisar de nenhum asset binário
no repositório.

### Como é (arquivo novo)

```kotlin
@RunWith(AndroidJUnit4::class)
class VideoPreviewDecodeTest {
    @Test
    fun decodesPosterAndDurationFromSyntheticClipOrSkipsCleanly() {
        val clip = encodeSyntheticMp4() ?: return  // tolerated: see below

        val dataSource = MemoryMediaDataSource(clip)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(dataSource)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            val poster = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertTrue(poster != null)
            // + dimension/bound/duration assertions
        } finally { retriever.release(); dataSource.close() }
    }

    private fun encodeSyntheticMp4(): ByteArray? {
        // MediaCodec (H.264, COLOR_FormatYUV420Flexible) -> MediaMuxer -> MemoryFd.createEmpty(...)
        // ... mirrors MemoryAudioEncoder's structure, for video instead of AAC audio.
        // Returns null instead of throwing on any failure.
    }
}
```

Duas peças:

- **`encodeSyntheticMp4`**: gera, em runtime, um clipe H.264/MP4 minúsculo (64×64px, 8 quadros a
  4 fps = 2s) com `MediaCodec` (modo buffer, `COLOR_FormatYUV420Flexible`) escrevendo num
  `MediaMuxer` que grava num `memfd` (via `MemoryFd`, o mesmo utilitário que `MemoryAudioEncoder.kt`
  já usa para o áudio AAC) — nunca um asset de vídeo binário gravado no repositório. Devolve `null`
  em vez de lançar em qualquer falha.
- **O teste em si**: se a síntese falhar (`null`), o teste retorna sem assinalar mais nada — o mesmo
  padrão de tolerância que `MemoryAudioEncoderTest.encodesToPlayableAacOrReturnsNullWithoutThrowing`
  já usa para o encoder de áudio, documentado no comentário do arquivo. Quando a síntese funciona,
  o teste afirma o contrato real de `decodeVideoPoster`: um poster não-nulo, com dimensões dentro do
  limite de 512px, e uma duração decodificada dentro de uma faixa de tolerância generosa em torno dos
  2000ms codificados.

### Por que a codificação de vídeo é tolerante a falha aqui (e por que ela **não** virou uma fixture de produção)

Codificação de vídeo em modo buffer via `MediaCodec` é bem menos portável entre aparelhos que o
caminho de áudio: a expectativa exata de layout do buffer de entrada varia por codificador de
hardware/software, e muitos encoders de vídeo só aceitam entrada via `Surface`, não via
`ByteBuffer.put()` direto. O próprio comentário de `MemoryAudioEncoder.kt` já registra que **o
combo `MediaMuxer`+`memfd` mais simples (áudio) já foi visto falhando ocasionalmente no emulador
deste projeto** — o caso de vídeo, estritamente mais complexo, tem um risco de falha maior ainda.

Isso é aceitável **aqui** porque o teste trata uma falha de síntese como resultado válido (idêntico
ao padrão já estabelecido por `MemoryAudioEncoderTest`) — mas seria inaceitável como fixture de
produção: ver a seção "Fixture de vídeo do cofre-isca — lacuna documentada, não implementada" em
`docs/changes/AndroidVaultStorage.kt.md` para o raciocínio completo de por que este mesmo padrão de
codificação **não** foi usado para popular `AndroidVaultStorage.MEDIA_FIXTURES`.

### Vantagens

- Valida, num aparelho/emulador real, a suposição arriscada por trás de `decodeVideoPoster`
  (`getScaledFrameAtTime` sobre um `MemoryMediaDataSource` em memória) sem depender de nenhum asset
  binário externo.
- Espelha a estrutura e o estilo de tolerância a falha já estabelecidos por
  `MemoryAudioEncoderTest.kt`, então quem já entende aquele teste entende este.
- `MemoryMediaDataSource`/`MemoryFd`, ambos `internal`, são reaproveitados sem duplicação — o mesmo
  padrão de acesso `internal` entre módulo de produção e `androidTest` que `MemoryAudioEncoderTest.kt`
  já usa para `MemoryAudioEncoder`/`decodeAudioWaveform`.

### Validação

`:app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL` (compila). **Não executado** nesta tarefa — sem
acesso a emulador nesta sessão; a execução real fica para o agente final de
teste/emulador/consolidação de documentação, junto da suíte instrumentada completa.
