# app/src/main/kotlin/dev/mx3/nomessages/ui/MemoryFd.kt

## 2026-09-16 — Novo arquivo: utilitário compartilhado de `memfd` (T4.8, mídia inline — parte A: áudio)

### Motivo

`MemoryPdfDocument.kt` já tinha, só para si, a sequência completa "criar um `memfd` anônimo via
`Os.memfd_create`, escrever bytes nele, rebobinar para o início, e depois sobrescrever com zeros,
sincronizar, truncar e fechar" — o padrão que garante que bytes decifrados (o PDF, no caso) nunca
tocam disco e são apagados de forma determinística quando o consumidor termina. A tarefa T4.8 (mídia
inline estilo WhatsApp) precisa do mesmo padrão em dois lugares novos: o encoder AAC-em-memória
(`MemoryAudioEncoder`, que escreve o `MediaMuxer` num `memfd`) e, potencialmente, qualquer cache de
prévia futura. Duplicar a sequência pela segunda vez seria o sinal de que ela precisa virar uma
função compartilhada — então foi extraída antes de ser copiada.

### Como era antes

A lógica vivia inteira dentro de `MemoryPdfDocument.open()`/`wipeAndClose()`, como métodos privados
do `companion object`:

```kotlin
// MemoryPdfDocument.kt (antes)
val memoryFile = Os.memfd_create("nomessages-pdf", OsConstants.MFD_CLOEXEC)
...
writeAll(memoryFile, bytes)
Os.lseek(memoryFile, 0L, OsConstants.SEEK_SET)
...
private fun writeAll(file: FileDescriptor, bytes: ByteArray) { ... }
private fun wipeAndClose(file: FileDescriptor, byteCount: Int) { ... }
```

### Como é agora

`MemoryFd` (novo `internal object`) expõe `createEmpty(name)`, `create(bytes, name)` (escreve e
rebobina), `writeAll`, `rewind`, `currentSize` (via `Os.fstat`, necessário para o encoder AAC, cujo
tamanho de saída não é conhecido de antemão — diferente do PDF, que já sabe `bytes.size`),
`readAll` (lê o arquivo inteiro de volta, usado pelo encoder após o `MediaMuxer` terminar de
escrever) e `wipeAndClose` (agora calcula o tamanho real via `fstat` em vez de confiar cegamente no
tamanho que o chamador informou, cobrindo o caso em que o `MediaMuxer` escreveu mais bytes do que o
PCM de entrada). `MemoryPdfDocument.kt` foi refatorado para chamar `MemoryFd.create(bytes, name =
"nomessages-pdf")` e `MemoryFd.wipeAndClose(memoryFile, byteCount)` — mesmo comportamento observável,
confirmado por `MemoryPdfDocumentTest.kt` (não alterado, continua descrevendo o mesmo contrato).

### Vantagens

- Um único lugar testa/mantém a única operação de baixo nível que toca `android.system.Os`
  diretamente neste app — reduz a superfície onde um bug de manuseio de `FileDescriptor` (vazamento,
  wipe incompleto) poderia se esconder.
- `MemoryAudioEncoder.kt` (novo, ver `docs/changes/MemoryAudioEncoder.kt.md`) reaproveita
  `createEmpty`/`readAll`/`wipeAndClose` sem duplicar a aritmética de `Os.write`/`Os.read` em loop.
- Extensível: qualquer prévia futura (ex.: um thumbnail de vídeo grande demais para caber só em
  memória gerenciada) pode reusar o mesmo padrão sem reinventar o wipe.
