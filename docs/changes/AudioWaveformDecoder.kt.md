# app/src/main/kotlin/dev/mx3/nomessages/ui/AudioWaveformDecoder.kt

## 2026-09-16 — Novo arquivo: decodificação de forma de onda no lado receptor (T4.8, mídia inline — parte A: áudio)

### Motivo

Quando a bolha de áudio de uma mensagem recebida (ou de uma mensagem antiga, enviada antes desta
tarefa) ainda não tem forma de onda em `MediaPreviewCache`, ela precisa ser calculada a partir dos
bytes decifrados do anexo — que podem ser WAV (fallback antigo/de dispositivos sem `MediaMuxer`
funcional) ou AAC/M4A (formato novo). Nenhum decodificador desses formatos existia fora do player de
mídia completo em `AttachmentViewer.kt`.

### Como é (arquivo novo)

`decodeAudioWaveform(bytes, mimeType): DecodedAudioPreview?` (nunca lança, retorna `null` em
qualquer falha de decodificação):

- **WAV**: parser manual de RIFF/WAVE (procura os chunks `fmt ` e `data`, faz downmix para mono se
  necessário) — não depende de `MediaCodec`, é só aritmética sobre o `ByteArray`.
- **AAC/M4A e qualquer outro `audio/*`**: `MediaExtractor` + `MediaCodec` decoder, alimentados via
  `MemoryMediaDataSource` (a mesma classe que `AttachmentViewer.kt` já usava para tocar mídia sem
  nunca gravar em disco — ver abaixo). O PCM decodificado é acumulado com um teto de 64 MiB e um
  teto de iterações do laço do codec, como proteção defensiva contra um decoder que nunca sinaliza
  fim de stream em algum dispositivo.
- Em ambos os casos, o PCM final vira uma forma de onda via `computeWaveformBuckets` (função pura
  nova em `UiLogic.kt`).

### Mudança relacionada em `AttachmentViewer.kt`

`MemoryMediaDataSource` era `private class`; virou `internal class` (mesmo pacote `ui`) para que
este arquivo novo e a bolha de áudio inline em `ChatScreen.kt` reaproveitem exatamente o mesmo
padrão "bytes decifrados envolvidos como `MediaDataSource`, zerados no `close()`" em vez de
duplicá-lo. Nenhum outro comportamento de `AttachmentViewer.kt` mudou.

### Vantagens

- Zero escrita em disco: o caminho AAC usa `MemoryMediaDataSource` (memória), não um `memfd`
  temporário — mais simples que replicar o padrão `MemoryFd` só para leitura de um `MediaExtractor`,
  que já aceita `MediaDataSource` diretamente desde a API 23.
- Falha graciosa: um clipe corrompido ou um formato que o dispositivo não sabe decodificar produz
  `null` (a bolha cai para uma forma de onda "achatada"), nunca uma exceção que quebraria a tela de
  conversa.
- Reaproveita a mesma função pura de bucketing (`computeWaveformBuckets`) usada no lado do remetente
  (`MemoryAudioRecorder`), então o formato do dado cacheado é idêntico nos dois caminhos.


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Defeito corrigido: PCM decifrado nao era zerado

**Como era (47afa3e):**

```kotlin
// caminho WAV
val pcm = if (channels == 1) bytes.copyOfRange(dataOffset, dataOffset + dataSize)
          else downmixToMono16(bytes, dataOffset, dataSize, channels)
return DecodedAudioPreview(computeWaveformBuckets(pcm), pcmDurationMs(pcm.size, sampleRate))

// caminho comprimido
val pcmOut = ByteArrayOutputStream()
...
val chunk = ByteArray(bufferInfo.size); outputBuffer.get(chunk); pcmOut.write(chunk)
...
val pcm = pcmOut.toByteArray()
val mono = if (channels <= 1) pcm else downmixToMono16(...)
return DecodedAudioPreview(computeWaveformBuckets(mono), ...)
```

**Como ficou:**

```kotlin
// caminho WAV
val pcm = ...
try { return DecodedAudioPreview(computeWaveformBuckets(pcm), pcmDurationMs(pcm.size, sampleRate)) }
finally { pcm.fill(0) }

// caminho comprimido
val pcmOut = SensitiveBuffer(MAX_DECODED_PCM_BYTES)      // criado antes do try
...
if (bufferInfo.size > 0 && pcmOut.size() + bufferInfo.size <= MAX_DECODED_PCM_BYTES) {
    val chunk = ByteArray(bufferInfo.size)
    try { ...; outputBuffer.get(chunk); pcmOut.write(chunk, 0, chunk.size) } finally { chunk.fill(0) }
}
...
val pcm = pcmOut.toByteArray()
try {
    val mono = if (channels <= 1) pcm else downmixToMono16(...)
    try { return DecodedAudioPreview(computeWaveformBuckets(mono), ...) }
    finally { if (mono !== pcm) mono.fill(0) }
} finally { pcm.fill(0) }
...
finally { ...; runCatching { pcmOut.close() } }
```

**Por que era necessario.** O projeto tem um padrao explicito de zeragem
(`runtime/SensitiveBuffer.kt`, `MemoryAudioRecorder.finish()` com `finally { raw.fill(0) }`,
`MessagingEngine.sendAttachment` com `key.fill(0)`), e `MemoryMediaDataSource.close()` zera os bytes
que recebe. O decodificador de forma de onda era a unica peca nova do commit que ignorava esse
padrao: o PCM **decifrado** de uma mensagem de voz ficava na heap em tres formas - cada chunk do
codec, cada geracao descartada do buffer que dobrava de tamanho dentro do `ByteArrayOutputStream`, e
o array final - ate o GC eventualmente sobrescreve-las. `SensitiveBuffer` zera cada geracao no
crescimento e no `close()`, que e exatamente o comportamento que faltava.

Tambem trocado `pcmOut.size() < MAX` por `pcmOut.size() + bufferInfo.size <= MAX`: o teto de 64 MiB
antes podia ser ultrapassado em um chunk, e o `SensitiveBuffer` recusa uma escrita que cruze o limite.

**Garantia restaurada.** "Buffers sensiveis (PCM/audio) sao zerados apos uso" passa a valer tambem no
caminho do receptor (que decodifica audio recebido de um contato), e nao so no do remetente.

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.
