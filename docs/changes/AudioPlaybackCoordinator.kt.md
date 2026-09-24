# app/src/main/kotlin/dev/mx3/nomessages/ui/AudioPlaybackCoordinator.kt

## 2026-09-16 — Novo arquivo: "só um player de áudio por vez" (T4.8, mídia inline — parte A: áudio)

### Motivo

A tarefa exige explicitamente que iniciar a reprodução de uma bolha de áudio pause qualquer outra
bolha que já esteja tocando em qualquer lugar do app (igual ao WhatsApp). Cada bolha de áudio em
`ChatScreen.kt` é uma composable independente, dona do seu próprio `MediaPlayer` — não existia
nenhum estado compartilhado entre elas antes desta tarefa.

### Como é (arquivo novo)

```kotlin
internal object AudioPlaybackCoordinator {
    var activeId by mutableStateOf<String?>(null)
        private set
    private var pauseCurrent: (() -> Unit)? = null

    fun requestPlay(id: String, pause: () -> Unit) { ... pausa o anterior, guarda o novo callback ... }
    fun release(id: String) { ... limpa se `id` ainda for o ativo ... }
    fun reset() { ... usado no lock() ... }
}
```

Decisão de design: o coordenador **nunca guarda um `MediaPlayer` nem bytes decifrados** — só um
`String?` (o id do anexo tocando) e uma closure de `pause()` que a própria bolble fornece. Cada
bolha continua dona exclusiva do seu player e dos bytes que decifrou para tocar; o coordenador só
arbitra "quem está tocando agora" via um `mutableStateOf` observável por Compose
(`snapshotFlow { AudioPlaybackCoordinator.activeId }` em cada bolha).

### Vantagens

- Pausar (em vez de parar/destruir) o player anterior preserva a posição de reprodução dele —
  comportamento esperado ao alternar entre áudios, igual ao WhatsApp.
- Sem acoplamento entre bolhas: nenhuma bolha precisa conhecer as outras, só registrar/liberar seu
  callback no coordenador.
- `reset()` é chamado em `NoMessagesController.lock()`/`closeSession()` como defesa extra: garante que
  nenhuma closure fechando sobre um `MediaPlayer` (que por sua vez fecha sobre bytes decifrados)
  fique pendurada no objeto estático além do necessário, mesmo que a árvore de UI ainda não tenha
  sido recomposta para fora da tela de conversa.


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Defeito corrigido: o lock nao interrompia a reproducao

**Como era (47afa3e):**

```kotlin
/** Drops the held pause callback without invoking it. Used when the vault locks. */
@Synchronized
fun reset() {
    activeId = null
    pauseCurrent = null
}
```

**Como ficou:**

```kotlin
private val teardowns = LinkedHashMap<Any, () -> Unit>()

fun registerPlayer(token: Any, teardown: () -> Unit) { synchronized(lock) { teardowns[token] = teardown } }
fun unregisterPlayer(token: Any)                     { synchronized(lock) { teardowns.remove(token) } }

fun reset() {
    val pending = synchronized(lock) {
        activeId = null; pauseCurrent = null
        val snapshot = teardowns.values.toList(); teardowns.clear(); snapshot
    }
    pending.forEach { teardown -> runCatching { teardown() } }
}
```

**Por que era necessario.** `NoMessagesController.lock()` e `closeSession()` chamam `reset()`, e o
`reset()` anterior **descartava** o callback de pausa sem invoca-lo, apostando que a virada de estado
para `unlocked = false` desmontaria a composicao e que o `DisposableEffect` de cada bolha liberaria o
proprio `MediaPlayer`. Essa aposta falha justamente no caminho que importa: o auto-lock.
`onBackground()` arma `backgroundLock` e chama `lock()` apos o timeout com o app **em segundo
plano**, onde o `Recomposer` da janela esta com o frame clock pausado desde o `ON_STOP` e portanto
nao recompoe - nada e descartado, o `MediaPlayer` continua tocando em voz alta com o cofre ja
reportado como bloqueado, e o `MemoryMediaDataSource` correspondente continua segurando os bytes
**decifrados** do audio.

O registro e por token de identidade (nao pelo id do anexo) porque a bolha inline e o visualizador de
tela cheia podem ter players simultaneos do mesmo anexo, e um nao pode desalojar o outro do registro.
Players apenas *preparados e pausados* tambem sao registrados, pois tambem seguram plaintext. O
callback anterior de `requestPlay` passou a ser invocado fora do monitor, ja que ele reentra neste
objeto via `release()`.

**Garantia restaurada.** "Ao bloquear o app, a reproducao para imediatamente, sem vazar handle nem
continuar tocando em background" - agora com liberacao do `MediaPlayer` e zeragem dos bytes
decifrados no mesmo passo sincrono.

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.
