# app/src/main/kotlin/dev/mx3/nomessages/runtime/TorConnection.kt

## 2026-09-14 — T2.1: `awaitReady` fatiado

### Como era antes

A classe expunha `start`, `send`, `reply`, `close` e `shutdown`. Depois de `start` retornar o endereço onion, o chamador não tinha nenhuma informação adicional: presumia-se prontidão.

### Como ficou

```kotlin
companion object {
    /** Descriptor publication budget, deliberately separate from the 180s bootstrap budget. */
    const val READY_TIMEOUT_MILLIS = 300_000L
    private const val READY_SLICE_MILLIS = 5_000
}
```

```kotlin
suspend fun awaitReady(): Boolean {
    val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MILLIS
    while (!stopped) {
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(READY_SLICE_MILLIS.toLong())
        if (remaining <= 0) return false
        val slice = remaining.toInt()
        val status = withTimeout(slice + 15_000L) {
            request(TorService.READY, Bundle().apply { putInt("timeout", slice) }).getString("status")
        }
        if (status == "READY") return true
    }
    return false
}
```

### Vantagens

- `SystemClock.elapsedRealtime()` não anda para trás nem é afetado por mudança de relógio, então o orçamento de 300 s é real mesmo com o aparelho em doze/suspensão.
- As fatias de 5 s dão ao lock um ponto de cancelamento frequente: `stopped` é reavaliado a cada volta e a morte do Binder já resolve as requisições pendentes com exceção.
- O `withTimeout(slice + 15 s)` é uma rede de segurança para um `:tor` travado, no mesmo estilo dos 45 s de SEND sobre os 40 s nativos.
- O orçamento fica separado dos 180 s de `start`, como o roteiro pede.

### Por que a mudança foi feita

T2.1: o controller precisa distinguir "Tor bootstrapado" de "endereço onion publicado", e essa distinção só existe se a conexão com o `:tor` souber esperar.

## 2026-09-15 — Revisão T2.1/T2.3: morte marca `stopped`, prontidão tri-estado e `awaitDeath`

### Como era antes

```kotlin
suspend fun awaitReady(): Boolean { ... if (status == "READY") return true ... return false }

private fun onDeath() {
    dead.complete(Unit)
    bound.completeExceptionally(IllegalStateException("Transporte encerrado"))
    ...
}
```

Três problemas:

1. `onDeath()` não marcava `stopped`. Com o filho `:tor` morto, `close(connection)` passava
   pela guarda `if (!stopped)` e chegava a um Binder morto: a `DeadObjectException` subia para o
   `closeStream` do `MessagingEngine` e matava `receiveLoop`/`outboxLoop` de vez (P1).
2. `awaitReady(): Boolean` devolvia `false` tanto para "prazo de 300 s esgotado, ainda
   publicando" quanto para "transporte morto". O controller tratava os dois como falha e recriava
   o processo do zero, jogando fora um cliente Arti já bootstrapado.
3. Não havia como esperar a morte do filho; quem quisesse detectá-la tinha de amostrar.

### Como ficou

```kotlin
enum class TorReadiness { READY, PUBLISHING, LOST }

val alive: Boolean get() = !stopped && !dead.isCompleted

suspend fun awaitDeath() { if (stopped) return; dead.await() }

suspend fun awaitReady(budgetMillis: Long = READY_TIMEOUT_MILLIS): TorReadiness {
    val deadline = SystemClock.elapsedRealtime() + budgetMillis
    while (true) {
        if (!alive) return TorReadiness.LOST
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) return if (alive) TorReadiness.PUBLISHING else TorReadiness.LOST
        ...
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) return TorReadiness.LOST
            throw cancelled
        } catch (_: Exception) { return TorReadiness.LOST }
        if (status == "READY") return TorReadiness.READY
    }
}

private fun onDeath() {
    stopped = true
    dead.complete(Unit)
    ...
}
```

### Vantagens

- `stopped = true` em `onDeath()` faz `close()` e `request()` curto-circuitarem: nenhum caminho de
  teardown volta a tocar um Binder morto, e a `DeadObjectException` deixa de existir na origem em
  vez de ser apenas capturada mais acima.
- O estado tri-estado devolve ao chamador a mesma distinção que a API nativa já documenta
  (`await_ready` retorna `"PUBLISHING"` quando o prazo esgota, e isso **não** é falha).
- A classificação de erro acontece aqui, onde se sabe se o Binder está vivo. O chamador não precisa
  adivinhar o que uma exceção significava; só cancelamento real (o lock) propaga.
- `budgetMillis` permite reusar a mesma função como sonda curta de alcançabilidade (30 s) sem
  duplicar a lógica de fatias de 5 s.
- `awaitDeath()` transforma detecção de morte em espera, não em amostragem.

### Por que a mudança foi feita

Achados P1 (`MessagingEngine.kt:796`) e P2 (`TorConnection.kt:83`) da revisão de segurança e
completude desta rodada.


## 2026-09-18 — T4.17 fase 3: cliente das mensagens de campainha e `restart`

### Motivo

`TorService` ganhou sete mensagens novas; `TorConnection` é o único cliente legítimo delas. E o
reaproveitamento do processo no desbloqueio precisa de um caminho que **não** refaça o bind nem o
HELLO.

### Como era antes

O cliente conhecia `start`, `awaitReady`, `send`, `reply`, `close` e `shutdown`. `start` sempre fazia
tudo: `bindService`, HELLO (aprendendo o pid do filho) e só então o `START`. Não havia forma de
mandar um segundo `START` para um filho já vinculado.

### Como ficou

**`restart(seed, bridges)`** — apenas o `START`, exigindo um filho já vinculado e vivo:

```kotlin
suspend fun restart(seed: ByteArray, bridges: List<String>): String {
    check(!stopped && remote != null) { "Transporte indisponível" }
    return withTimeout(180_000) { request(TorService.START, ...).getString("onion") ?: error(...) }
}
```

Não foi dobrado dentro de `start` de propósito: `bindService`/HELLO/aprender o pid são passos que não
podem rodar duas vezes na mesma conexão, e `start` é o caminho testado do fluxo normal.

**Sete métodos de campainha**, cada um com o mesmo tratamento de timeout/erro dos existentes:

| Método | Orçamento | Por quê |
|---|---|---|
| `doorbellStart` | 180 s | pode ter que bootstrapar um host Arti próprio |
| `doorbellOnion` | 15 s | consulta local |
| `doorbellTokens` | 15 s | escrita em memória no nativo |
| `doorbellPoll` | `timeout + 15 s` | o filho deve responder no prazo que recebeu; só um filho mudo estoura a margem |
| `doorbellMinimal` | 60 s | derruba o serviço de mensagens e promove o processo |
| `doorbellStop` | 30 s | pode dispor do runtime inteiro |
| `doorbellKnock` | 240 s | bootstrap frio + circuito dedicado da batida |

`doorbellTokens` empacota com `TorService.packTokens`, manda **um** buffer e zera a cópia no
`finally`; a lista original continua sendo do chamador, que também a zera.

`doorbellMinimal` faz um `startService` best-effort antes do IPC: um serviço explicitamente iniciado
é inequívoco quanto a sobreviver ao unbind. A plataforma recusa essa chamada com o app em background,
então a exceção é engolida — o vínculo sozinho já basta para o handoff.

### Vantagens

- O controller nunca fala com `TorService` diretamente: toda a assimetria de Binder continua em um
  arquivo só.
- Um filho morto responde `IllegalStateException("Transporte encerrado")` pelas mesmas vias já
  existentes, então o laço de poll da campainha termina sozinho sem tratamento especial.
- `restart` torna o reaproveitamento explícito e auditável em vez de um `start` com efeito colateral.

### Por que a mudança foi feita

T4.17 fase 3: o processo principal precisa ligar, alimentar, drenar e parar a campainha, e precisa
reaproveitar o `:tor` vivo ao sair do modo mínimo.

---

## 2026-09-18 — T4.19: `deathCause`/`diagnostics`, para uma conexão poder dizer *por que* não está viva

Fase de diagnóstico do handoff da campainha (ver `docs/changes/NoMessagesController.kt.md`, seção de
mesma data). `alive` respondia sim/não e nada mais, o que é exatamente a informação que falta quando
um handoff é recusado: "não está viva" pode ser um `shutdown()` deliberado, um Binder que morreu, um
`onBindingDied` da plataforma ou um `linkToDeath` que nem chegou a ser instalado — e cada um desses
aponta para um culpado diferente.

### Antes

```kotlin
private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder) {
        ...
        try { service.linkToDeath({ onDeath() }, 0) } catch (_: RemoteException) { onDeath() }
        ...
    }
    override fun onServiceDisconnected(name: ComponentName?) = onDeath()
    override fun onBindingDied(name: ComponentName?) = onDeath()
    override fun onNullBinding(name: ComponentName?) = onDeath()
}

val alive: Boolean get() = !stopped && !dead.isCompleted

private fun onDeath() {
    stopped = true
    ...
}
```

### Depois

```kotlin
/**
 * First reason this connection stopped answering, kept only so a failed doorbell handoff can
 * say *why* the child was considered gone instead of only that it was.
 *
 * Deliberately a fixed label out of a closed set - never an address, a pid or any vault-derived
 * value - because it is printed to logcat in debug builds.
 */
@Volatile private var deathCause: String? = null

try { service.linkToDeath({ onDeath("binder-died") }, 0) } catch (_: RemoteException) { onDeath("link-failed") }
override fun onServiceDisconnected(name: ComponentName?) = onDeath("service-disconnected")
override fun onBindingDied(name: ComponentName?) = onDeath("binding-died")
override fun onNullBinding(name: ComponentName?) = onDeath("null-binding")

val alive: Boolean get() = !stopped && !dead.isCompleted

/**
 * Why this connection is (not) alive, as a fixed label. Diagnostic only, and carries nothing
 * secret: the three booleans of [alive] plus the first cause recorded by [onDeath]/[shutdown].
 */
val diagnostics: String get() = "stopped=$stopped dead=${dead.isCompleted} cause=${deathCause ?: "none"}"

private fun onDeath(cause: String = "unknown") {
    if (deathCause == null) deathCause = cause
    if (BuildConfig.DEBUG) Log.d(TAG, "onDeath($cause); $diagnostics")
    stopped = true
    ...
}
```

`shutdown()` também marca (`cause = "shutdown"`) e loga uma linha antes de começar o teardown, de
modo que um filho que morre "sozinho" é distinguível de um que foi morto por nós.

### Por que primeiro-vencedor, e não último

`deathCause` só é escrito quando ainda está `null`. Uma morte de Binder dispara três callbacks quase
simultâneos (`binder-died`, `service-disconnected`, e o `onDeath()` final de `shutdown`), e o que
interessa é o **primeiro** — os outros são consequência. Guardar o último apagaria justamente a
causa.

### O que isto provou

Nas cinco rodadas da sonda, a `TorConnection` preservada pelo modo mínimo chega ao desbloqueio com
`stopped=false dead=false cause=none`: **nada** a tocou entre o `lock()` e a adoção. Foi isso que
eliminou a hipótese de que o `lock()` estaria derrubando o transporte que acabara de preservar, e
empurrou a investigação para o segundo `START` dentro do filho — onde o defeito de fato está
(`native/src/tor.rs`, keystore do Arti; ver
`docs/development/build-logs/doorbell-20260918/22-handoff-root-cause.txt`).

### Vantagens

- Um handoff recusado passa a ser auto-explicativo, em uma linha, em vez de exigir bisecção.
- Custo zero em release: tudo sob `BuildConfig.DEBUG`, e `deathCause` é um `String?` por conexão.
- Rótulos de um conjunto fechado e escrito à mão — nenhum endereço onion, pid, seed ou token entra
  na string.

### Validação

`:app:testDebugUnitTest` 83 testes / 0 falhas / 1 pulado; `:core:test` 102 / 0 / 2. Sonda e cenário
de aceite em `docs/development/build-logs/doorbell-20260918/` (arquivos 22 a 26).
