# app/src/main/kotlin/dev/mx3/nomessages/runtime/MemoryAudioRecorder.kt

## 2026-09-16 — `finish()` agora produz WAV + AAC opcional + forma de onda (T4.8, mídia inline — parte A: áudio, itens A1/A2)

### Motivo

A1 pede um encoder AAC-em-memória com fallback seguro para WAV; A2 pede que a forma de onda de ~48
baldes seja calculada no remetente, a partir do PCM bruto que este arquivo já tem em mãos antes de
zerá-lo. As duas coisas precisam do mesmo PCM bruto no mesmo instante, então foram combinadas num

### Como era antes

```kotlin
suspend fun finish(): ByteArray {
    stopDevice()
    job?.join()
    return synchronized(pcm) {
        val raw = pcm.toByteArray()
        pcm.close()
        try {
            require(raw.isNotEmpty()) { "Nenhum áudio gravado" }
            val header = ByteBuffer.allocate(44 + raw.size)...
            header.array()
        } finally { raw.fill(0) }
    }
}
```

`NoMessagesController.stopAudio()` chamava isso e enviava sempre `"audio.wav"` / `"audio/wav"`.

### Como é agora

```kotlin
data class AudioRecording(
    val wavBytes: ByteArray,
    val aacBytes: ByteArray?,   // null se este aparelho falhou ao codificar/muxar (ver MemoryAudioEncoder)
    val waveform: FloatArray,  // 48 baldes normalizados [0,1], calculados do PCM bruto
    val durationMs: Int,
)

suspend fun finish(): AudioRecording {
    stopDevice()
    job?.join()
    val raw = synchronized(pcm) { val bytes = pcm.toByteArray(); pcm.close(); bytes }
    try {
        require(raw.isNotEmpty()) { "Nenhum áudio gravado" }
        val waveform = computeWaveformBuckets(raw)
        val durationMs = pcmDurationMs(raw.size, SAMPLE_RATE_HZ)
        val wav = buildWav(raw)
        val aac = try { MemoryAudioEncoder.encode(raw, SAMPLE_RATE_HZ) } catch (failure: Throwable) { null }
        return AudioRecording(wav, aac, waveform, durationMs)
    } finally { raw.fill(0) }
}
```

`NoMessagesController.stopAudio()` agora envia `"audio.m4a"`/`"audio/mp4"` quando `aacBytes != null`, e
cai para `"audio.wav"`/`"audio/wav"` só quando a codificação falhou neste aparelho. Também cacheia
imediatamente a forma de onda já calculada em `MediaPreviewCache`, contra o id do anexo que acabou
de ser enviado — a própria bolha do remetente nunca precisa redecifrar sua própria mensagem para
desenhar a forma de onda.

### Decisão de design: uma função combinada em vez de `finish()` + `finishCompressed()` separados

O enunciado da tarefa sugeria `suspend fun finishCompressed(): ByteArray?` como um método adicional
ao lado de `finish()`. Não foi implementado literalmente assim porque o `finish()` original já
zerava o PCM bruto (`raw.fill(0)`) antes de retornar — uma segunda chamada a `finishCompressed()``
não teria mais PCM para codificar. Combinar os dois numa única passada evita esse bug de
"consumido duas vezes" e também evita decifrar/computar a forma de onda duas vezes. A troca é
documentada aqui porque diverge do enunciado ao pé da letra, ainda que cumpra a mesma intenção
(WAV sempre disponível, AAC quando possível, sem nunca travar o envio).

### Vantagens

- Anexo de voz menor (AAC) no caminho feliz, com o mesmo teto de 8 MiB.
- A forma de onda do remetente nunca exige uma redecifração da própria mensagem.
- Um só ponto de zeragem do PCM bruto (`raw.fill(0)` no `finally`), sem risco de "usar depois de
  liberar": WAV, AAC e forma de onda são todos derivados de `raw` antes do `finally` rodar.

### Validação

`:app:testDebugUnitTest`/`:app:lintDebug`: `BUILD SUCCESSFUL`. `MemoryAudioEncoderTest.kt` (novo,
`androidTest`) verificado por compilação (`:app:assembleDebugAndroidTest`), não executado nesta
tarefa.

## 2026-09-23 — `finish()` lança `MessagingError(NO_AUDIO_RECORDED)` em vez de texto PT cru (T4.6)

### Motivo

Ver `docs/changes/MessagingError.kt.md`: `"Nenhum áudio gravado"` era descartado pelo catch genérico
de `NoMessagesController.stopAudio` (sempre mostrava `error_audio_finish`, "Não foi possível
finalizar o áudio."), deixando `R.string.error_no_audio_recorded` sem chamador.

### Como era

```kotlin
require(raw.isNotEmpty()) { "Nenhum áudio gravado" }
```

### Como ficou

```kotlin
if (raw.isEmpty()) throw MessagingError(MessagingErrorCode.NO_AUDIO_RECORDED, "no audio recorded")
```

Mesma condição, mesmo ponto do `finally`/limpeza ao redor (inalterado). `NoMessagesController.stopAudio`
agora distingue esse caso do de "falha técnica ao finalizar" via `errorMessage(failure, ...)` — ver
`docs/changes/NoMessagesController.kt.md`.

### Vantagens

- "Nenhum áudio gravado" (gravação vazia/silenciosa) é informação diferente de "falha ao finalizar o
  áudio" (erro técnico de codec/IO) — o usuário agora vê qual dos dois aconteceu.
- Fecha o achado de lint (`R.string.error_no_audio_recorded` sem chamador) sem mudar quando a
  gravação é considerada vazia.

### Verificação

`:app:testDebugUnitTest`/`:app:lintDebug`: `BUILD SUCCESSFUL`, zero `UnusedResources` remanescente
para esta string.
