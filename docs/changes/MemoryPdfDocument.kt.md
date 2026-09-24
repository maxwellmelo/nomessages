# app/src/main/kotlin/dev/mx3/nomessages/ui/MemoryPdfDocument.kt

## 2026-09-16 — Refatorado para usar o novo `MemoryFd` compartilhado (T4.8, mídia inline — parte A: áudio)

### Motivo

A tarefa de mídia inline precisava do mesmo padrão "criar `memfd` → escrever → rebobinar → (mais
tarde) zerar/truncar/fechar" para o encoder AAC (`MemoryAudioEncoder.kt`). Em vez de copiar a lógica
que já existia aqui, ela foi extraída para `MemoryFd.kt` (ver
`docs/changes/MemoryFd.kt.md`) e este arquivo passou a chamá-la.

### Como era antes

```kotlin
import android.system.Os
import android.system.OsConstants
...
val memoryFile = Os.memfd_create("nomessages-pdf", OsConstants.MFD_CLOEXEC)
...
writeAll(memoryFile, bytes)
Os.lseek(memoryFile, 0L, OsConstants.SEEK_SET)
...
companion object {
    private fun writeAll(file: FileDescriptor, bytes: ByteArray) { ... }
    private fun wipeAndClose(file: FileDescriptor, byteCount: Int) { ... }
}
```

### Como é agora

```kotlin
val memoryFile = MemoryFd.create(bytes, name = "nomessages-pdf")
...
MemoryFd.wipeAndClose(memoryFile, byteCount)
```

Os métodos privados `writeAll`/`wipeAndClose` e os imports de `android.system.Os`/`OsConstants`
foram removidos deste arquivo — vivem só em `MemoryFd.kt` agora.

### Validação

movida de lugar); `app/src/androidTest/kotlin/dev/mx3/nomessages/ui/MemoryPdfDocumentTest.kt` foi
relido para confirmar que continua descrevendo o mesmo contrato (`pageCount`, dimensões do bitmap
renderizado) e não precisou de nenhuma alteração. `:app:testDebugUnitTest`/`:app:lintDebug`:
`BUILD SUCCESSFUL`. O teste instrumentado em si não foi executado nesta tarefa (ver nota no relatório
final), só verificado por compilação (`:app:assembleDebugAndroidTest`).

### Vantagens

- Um único lugar (`MemoryFd`) possui a única lógica de baixo nível deste app que toca
  `android.system.Os` diretamente — menos superfície para um bug de manuseio de `FileDescriptor`.
- Este arquivo fica menor e mais focado em `PdfRenderer`/paginação, sem a aritmética de wipe/I/O
  bruto misturada.
