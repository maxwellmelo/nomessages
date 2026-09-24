# app/src/main/kotlin/dev/mx3/nomessages/runtime/MemoryAudioEncoder.kt

## 2026-09-16 — Novo arquivo: encoder AAC-em-memória (T4.8, mídia inline — parte A: áudio, item A1)

### Motivo

Até esta tarefa, `MemoryAudioRecorder.finish()` só produzia WAV sem compressão (PCM 16 kHz mono
embrulhado num cabeçalho de 44 bytes) — funcional, mas ~2x o tamanho de um AAC-LC equivalente, o que
importa porque o teto de anexo é 8 MiB (`Envelope`/`MessagingEngine`, não alterado por esta
tarefa). A1 pede um encoder AAC-LC mono 16 kHz ~32 kbps em contêiner MP4/M4A, via `MediaCodec` +
`MediaMuxer`, escrevendo num `FileDescriptor` de `memfd` (nunca um caminho de arquivo).

### Como é (arquivo novo)

`MemoryAudioEncoder.encode(pcm16, sampleRateHz): ByteArray?`, síncrono/bloqueante (chamado de uma
dispatcher de fundo por `MemoryAudioRecorder.finish()`):

1. Configura um `MediaCodec` encoder (`MediaFormat.MIMETYPE_AUDIO_AAC`, perfil `AACObjectLC`, 32
   kbps, mono).
2. Cria um `memfd` vazio via `MemoryFd.createEmpty()` e um `MediaMuxer(fd, MUXER_OUTPUT_MPEG_4)`
   apontando para ele.
3. Laço síncrono padrão de `MediaCodec`: alimenta PCM nos buffers de entrada, escreve os buffers de
   saída codificados no muxer (`addTrack`/`start()` assim que o primeiro `INFO_OUTPUT_FORMAT_CHANGED`
   chega), até o flag de fim de stream.
4. Para o codec e o muxer, lê o `memfd` inteiro de volta (`MemoryFd.readAll`) e **sempre** limpa e
   fecha o `memfd` (`MemoryFd.wipeAndClose`) no `finally`, tenha a codificação dado certo ou não.

### Comportamento em falha — a ressalva do emulador

`encode()` **nunca lança**: qualquer exceção em qualquer etapa (configurar o codec, escrever no
muxer, ler o `memfd`) é capturada, registrada com `Log.w`, e o método retorna `null`. Isso é
proposital e documentado no comentário do arquivo: o emulador usado neste projeto já foi observado
falhando ocasionalmente ao escrever no `MediaMuxer` quando o `FileDescriptor` de destino é um
`memfd` anônimo (em vez de um arquivo real). Em vez de tratar isso como fatal, o chamador
(`MemoryAudioRecorder.finish()`, ver `docs/changes/MemoryAudioRecorder.kt.md`) trata `null` como
"cai para o WAV sem compressão" — o envio da mensagem nunca trava nem falha por causa disso.

### Vantagens

- Anexo de áudio menor (AAC-LC ~32 kbps vs. PCM não comprimido), dentro do mesmo teto de 8 MiB já
  existente, sem tocar nesse teto.
- Zero escrita em disco durante a codificação: o contêiner MP4 é montado inteiramente num `memfd`.
- Robusto a um dispositivo/emulador com `MediaMuxer`+`memfd` instável: o pior caso é continuar
  enviando WAV, exatamente como antes desta tarefa — nunca uma mensagem de voz que falha ao ser
  enviada.
