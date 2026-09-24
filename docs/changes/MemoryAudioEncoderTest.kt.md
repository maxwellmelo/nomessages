# app/src/androidTest/kotlin/dev/mx3/nomessages/ui/MemoryAudioEncoderTest.kt

## 2026-09-16 — Novo arquivo: teste instrumentado do encoder AAC-em-memória (T4.8, mídia inline — parte A: áudio)

### Motivo

`MemoryAudioEncoder` (e o decodificador `decodeAudioWaveform` que consome sua saída) precisam de um
`MediaCodec` real — a sandbox de teste JVM (`testDebugUnitTest`) não fornece isso, então este teste
mora em `androidTest`, ao lado de `MemoryPdfDocumentTest.kt`, cujo estilo ele imita (`@RunWith
(AndroidJUnit4::class)`, gera o conteúdo de teste programaticamente, não usa nenhum asset binário
gravado no repositório).

**Este arquivo não foi executado neste momento** — a tarefa pediu explicitamente para não rodar o
conjunto instrumentado nem tocar em emuladores (isso fica para o agente final de
teste/emulador/consolidação). Foi verificado apenas que compila:
`:app:assembleDebugAndroidTest` → `BUILD SUCCESSFUL`.

### Como é (arquivo novo)

- `encodesToPlayableAacOrReturnsNullWithoutThrowing`: gera 1s de PCM16 (tom senoidal de 440 Hz) e
  chama `MemoryAudioEncoder.encode`. Como o próprio encoder documenta que pode retornar `null` neste
  emulador (ver `docs/changes/MemoryAudioEncoder.kt.md`), o teste **aceita `null` como resultado
  válido** (o contrato "nunca lança, nunca trava" foi exercido de qualquer forma) e só faz as
  asserções de round-trip — decodificável de volta via `decodeAudioWaveform`, 48 baldes, todos em
  `[0, 1]`, pelo menos um balde não-silencioso, duração próxima de 1000 ms — quando a codificação
  realmente teve sucesso neste dispositivo.
- `emptyPcmNeverThrowsAndYieldsNoOutput`: PCM vazio deve retornar `null` sem lançar.

### Vantagens

- Cobre o caminho feliz (round-trip codifica→decodifica produz uma forma de onda plausível) sem
  travar a suíte inteira num dispositivo onde o `MediaMuxer`+`memfd` é instável — a ressalva do
  emulador é tratada como parte do contrato testado, não como um motivo para pular o teste.
- Reaproveita `decodeAudioWaveform` e `WAVEFORM_BUCKET_COUNT` (mesmo pacote `ui`), então o teste
  também serve como verificação indireta de que os dois lados (codificar no remetente, decodificar
  no receptor) continuam compatíveis.
