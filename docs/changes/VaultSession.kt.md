# core/src/main/kotlin/dev/mx3/nomessages/core/vault/VaultSession.kt

## 2026-09-23 (revisão P1 do T4.1) — `VaultStorage.padMediaToMatch`, novo método de interface

### Motivo

Ver `docs/changes/VaultManager.kt.md` ("`resetPanicPassword` volta a passar em cofres com mídia
real") para o achado completo: `resetPanicPassword` reconstrói `decoy.files` do zero e precisa de um
jeito de completá-lo até igualar `real.files` (que pode ter crescido através do uso normal) antes de
`alignAllocations` checar a paridade. Essa operação depende da representação em disco de cada slot de
mídia, que só a implementação concreta de `VaultStorage` conhece — `VaultManager` (módulo `core`,
sem dependência de Android) não pode implementá-la diretamente, do mesmo jeito que já não implementa
`alignAllocations`/`initialize`/`beforeExport` diretamente.

### Como era

```kotlin
interface VaultStorage {
    fun initialize(directory: Path, slot: VaultSlot, keys: VaultKeys)
    fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable
    fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys)
    fun beforeExport(directory: Path)
}
```

### Como ficou

```kotlin
interface VaultStorage {
    fun initialize(directory: Path, slot: VaultSlot, keys: VaultKeys)
    fun open(directory: Path, slot: VaultSlot, keys: VaultKeys): AutoCloseable
    fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys)
    fun beforeExport(directory: Path)

    /**
     * Tops up [slot]'s media directory with blind filler ... until its total allocated size
     * matches [matchSlot]'s. A no-op when [slot] is already at least as large as [matchSlot].
     * ...
     */
    fun padMediaToMatch(directory: Path, slot: VaultSlot, matchSlot: VaultSlot)
}
```

Dois implementadores no repositório, ambos atualizados junto com a interface:

- `AndroidVaultStorage` (produção) — preenche com blobs cegos de bytes aleatórios, mesmo estilo do
  `MessagingEngine.coverSiblingSlot`. Ver `docs/changes/AndroidVaultStorage.kt.md`.
- `TestStorage` (fake de `VaultTest.kt`, só JVM) — `Unit`, o mesmo tratamento que seu
  `alignAllocations` já recebia, porque esse fake nunca modela alocação de mídia de verdade. Ver
  `docs/changes/VaultTest.kt.md`.

### Vantagens

- Mantém `VaultManager` livre de qualquer detalhe de sistema de arquivos específico de plataforma —
  a mesma separação que já existia para `initialize`/`alignAllocations`/`beforeExport`.
- Contrato pequeno e específico (um "top up até igualar", não um "reescreva tudo"), reaproveitável por
  qualquer reconstrução futura de slot a partir do zero.
