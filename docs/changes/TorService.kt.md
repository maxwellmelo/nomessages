# app/src/main/kotlin/dev/mx3/nomessages/runtime/TorService.kt

## 2026-09-14 — T2.1: mensagem READY no processo `:tor`

### Como era antes

O `Messenger` do serviço tratava HELLO, START, SEND/REPLY/CLOSE e STOP; as constantes iam de 1 a 6 mais 10/11/12. Não havia como o processo principal perguntar se o descritor já fora publicado — só o `status()` sincrônico, que o processo principal nunca chamava.

### Como ficou

Uma mensagem nova, numerada 7 (depois de STOP, sem mexer nas existentes):

```kotlin
// Readiness is polled in bounded slices so a lock can tear the process down without
// waiting out the full native budget.
READY -> {
    val request = message.arg1
    val slice = message.data.getInt("timeout")
    scope.launch {
        try { respond(request, Bundle().apply { putString("status", TorNative.awaitReady(slice)) }) }
        catch (_: Exception) { fail(request) }
    }
}
```

```kotlin
const val STOP = 6
const val READY = 7
```

### Vantagens

- A espera acontece dentro do processo descartável `:tor`, onde o runtime Arti vive; o processo principal nunca bloqueia uma thread nativa.
- Fatiar o prazo (o chamador envia fatias de 5 s) mantém o serviço responsivo a STOP e à morte forçada do processo no lock, que continua sendo a fronteira de segurança.
- Erros continuam virando `ERROR` genérico, sem vazar texto do Arti pelo Binder.

### Por que a mudança foi feita

T2.1: o estado real da publicação precisa chegar ao controller, e o único caminho legítimo até o Arti é este serviço isolado.


## 2026-09-18 — T4.17 fase 3: IPC da campainha e foreground service em modo mínimo

### Motivo

Os opcodes 10-16 só existem dentro do processo `:tor`, onde a biblioteca nativa está carregada. O
processo principal fala com ele por `Messenger`, então cada operação nova da campainha precisa de
uma mensagem IPC própria. Além disso, a campainha só tem valor se sobreviver ao bloqueio do cofre —
e um serviço em background comum é reciclado pelo sistema.

### Como era antes

- `what` ia de 1 a 7 (HELLO..READY) mais 10/11/12 (RESULT/ERROR/FRAME).
- `started` era um trinco de mão única: uma vez `true`, nenhum segundo `START` era aceito naquele
  processo, e a única saída era matar o processo.
- O laço de `poll` das mensagens ficava dentro do `scope.launch` do `START`, sem referência guardada:
  não havia como encerrar só ele.
- O serviço **nunca** era foreground; o processo era simplesmente morto no lock
  (`Process.killProcess`).

```kotlin
START -> {
    if (started) { fail(message.arg1); return@Handler true }
    started = true
    ...
    scope.launch { ... while (isActive) { val frame = TorNative.poll(500) ?: continue; ... } }
}
```

### Como ficou

**Sete mensagens novas**, numeradas a partir de 20 (e não 13) para não colidirem com o espaço
`RESULT/ERROR/FRAME` que compartilha o mesmo campo `what`, e para deixar 8/9 livres caso o lado de
mensagens precise de mais uma mensagem no futuro:

```kotlin
const val DOORBELL_START = 20
const val DOORBELL_ONION = 21
const val DOORBELL_TOKENS = 22
const val DOORBELL_POLL = 23
const val DOORBELL_MINIMAL = 24
const val DOORBELL_STOP = 25
const val DOORBELL_KNOCK = 26
```

Cada uma segue o padrão já existente: `arg1` é o id da requisição, o trabalho vai para `scope.launch`
e a resposta é `respond(...)`/`fail(...)`, sem jamais ecoar texto de erro nativo pelo Binder.

**O trinco `started` deixou de ser de mão única.** `DOORBELL_MINIMAL` agora:

```kotlin
TorNative.doorbellMinimal()
messaging?.cancel()
messaging = null
started = false
respond(request, Bundle().apply { putBoolean("foreground", enterForeground()) })
```

Isso é o que torna o reaproveitamento possível: depois do modo mínimo, o próximo desbloqueio manda um
**segundo** `START` para o mesmo processo, que adota o host Arti ainda bootstrapado em vez de pagar
outros 180 s. O laço de frames virou um `Job` guardado (`messaging`) exatamente para poder ser
encerrado sozinho, sem derrubar o `scope` inteiro.

**Foreground service só no modo mínimo**, com fallback:

```kotlin
private suspend fun enterForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
    if (foregrounded) return@withContext true
    try {
        startForeground(PrivacyNotifications.BACKGROUND_NOTIFICATION_ID,
            PrivacyNotifications.backgroundNotification(this@TorService),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        foregrounded = true; true
    } catch (_: Exception) { false }
}
```

`leaveForeground()` roda em três pontos: quando um `START` volta a subir o transporte de mensagens
(saída do modo mínimo), no `DOORBELL_STOP` e no `terminate()`. A recusa da plataforma
(`ForegroundServiceStartNotAllowedException`) **não** é fatal: vira `false` no `Bundle` e o
chamador decide — a campainha já está servindo nesse ponto e vale mais viva e desprotegida do que
derrubada.

`packTokens`/`unpackTokens` no companion transportam o conjunto de tokens como **um** `ByteArray`
com `count` + blobs length-prefixed, em vez de `Serializable`/`Parcelable`: um único buffer para
zerar dos dois lados, e os limites são revalidados na recepção porque este lado é o receptor.

### Vantagens

- Simetria total com o que já existia: mesma convenção de `what`, de `arg1`, de `respond/fail` e de
  silêncio em caso de erro.
- O processo `:tor` continua descartável no caminho normal; o foreground (e a notificação
  permanente) só aparece no exato momento em que passa a ser a única coisa segurando a campainha.
- Reaproveitar o processo no desbloqueio elimina o bootstrap de até 180 s que o usuário pagaria a
  cada ciclo bloqueia/desbloqueia com a campainha ligada.
- `@Volatile` em `started`, `messaging` e `foregrounded` porque agora eles são tocados pela thread do
  `Handler` **e** pelos workers do `scope`.

### Por que a mudança foi feita

T4.17 fase 3: expor os opcodes 10-16 ao processo principal e manter o `:tor` vivo enquanto o cofre
está bloqueado, sem transformá-lo em serviço permanente durante o uso normal.

---

## 2026-09-18 — T4.19: instrumentação do segundo `START` (o lado do filho no handoff da campainha)

Fase de diagnóstico. A promessa da seção anterior — "reaproveitar o processo no desbloqueio elimina o
bootstrap de até 180 s" — **não se cumpria na prática**, e o motivo estava deste lado do Binder, não
no controlador. O que entrou aqui é só instrumentação sob `BuildConfig.DEBUG`; nenhum comportamento
mudou.

### Antes

Os três pontos onde o segundo `START` poderia morrer eram mudos:

```kotlin
START -> {
    if (started) { fail(message.arg1); return@Handler true }
    ...
    messaging = scope.launch {
        try {
            stateDirectory = sessionDirectory()
            val cache = directoryCache()
            val onion = TorNative.start(seed, stateDirectory.absolutePath, cache.absolutePath, bridges)
            ...
        } catch (_: Exception) { fail(request) }
        finally { seed.fill(0) }
    }
}
...
DOORBELL_MINIMAL -> {
    ...
    started = false
    respond(request, Bundle().apply { putBoolean("foreground", enterForeground()) })
} catch (_: Exception) { fail(request) }
```

Um `ERROR` chegando ao cliente podia significar três coisas incompatíveis entre si: a trava
`started` recusou, o `TorNative.start` falhou, ou o modo mínimo nunca tinha limpado a trava.

### Depois

```kotlin
START -> {
    if (started) {
        if (BuildConfig.DEBUG) Log.d(TAG, "START refused: a messaging transport is already started in this process")
        fail(message.arg1); return@Handler true
    }
    ...
            val cache = directoryCache()
            if (BuildConfig.DEBUG) Log.d(TAG, "START: native transport slot before start = ${TorNative.status()}")
            val onion = TorNative.start(...)
    ...
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "messaging start/pump ended: ${e.javaClass.name}: ${e.message}")
            fail(request)
        }
...
    started = false
    if (BuildConfig.DEBUG) Log.d(TAG, "minimal mode: messaging pump cancelled, START latch cleared")
```

(mais uma linha equivalente no `catch` do `DOORBELL_START`, que compartilhava a mesma forma e por
isso levava a mesma mensagem genérica.)

### Por que `TorNative.status()` e não um booleano local

`status()` é o opcode 3 e devolve `"STOPPED"` quando o slot nativo `CURRENT` está vazio — ele
responde, do lado do Rust, a pergunta que a trava `started` responde do lado do Kotlin. Logar os dois
juntos é o que permitiu descartar, de uma vez, as duas recusas "óbvias" de um segundo `START`:

```
19:30:24.393  START: native transport slot before start = STOPPED
19:30:24.393  messaging start/pump ended: java.lang.IllegalStateException: Tor operation failed
```

Slot nativo vazio, trava Kotlin limpa (a mensagem "START refused" nunca apareceu em nenhuma das cinco
rodadas) — e mesmo assim o `start` morre no mesmo milissegundo. Com o erro real do Rust exposto por
um build local e temporário, já revertido, o texto é
`tor: bad API usage (bug): … Key already exists`: `launch_onion_service_with_hsid` não consegue
reinserir a chave HsId do nickname `"nomessages"` no keystore efêmero do `TorClient` compartilhado,
porque `tor::stop()` larga o serviço onion mas não remove a chave. Detalhes em
`docs/development/build-logs/doorbell-20260918/22-handoff-root-cause.txt`.

`status()` nunca lança (um `CURRENT` vazio devolve `"STOPPED"` em vez de erro), então a linha é segura
mesmo no caminho de falha, e roda uma vez por `START` apenas em build de debug.

### Vantagens

- As três causas possíveis de um `ERROR` no `START` passam a ser distinguíveis sem anexar depurador
  a um processo filho.
- A fronteira Kotlin/Rust fica observável no ponto exato em que as duas travas de "um START só" se
  encontram.
- Nada sensível: `e.message` de uma exceção do nativo é a string fixa de `tor_jni.rs` por construção,
  e `status()` devolve um de quatro rótulos.

### Validação

`:app:testDebugUnitTest` 83 testes / 0 falhas / 1 pulado; `:core:test` 102 / 0 / 2. Cenário de aceite
completo re-executado depois da instrumentação (bloquear com campainha, bater de A, notificar,
destravar, entregar) em `docs/development/build-logs/doorbell-20260918/25-acceptance-after-diagnosis.txt`.
