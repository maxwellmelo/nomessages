# app/src/main/kotlin/dev/mx3/nomessages/runtime/TransportSupervisor.kt

## 2026-09-14 — T2.3: máquina de estados de reconexão (arquivo novo)

### Como era antes

O arquivo não existia. Toda a política de ativação do transporte estava embutida em uma única
`scope.launch` dentro de `NoMessagesController.activate`, sem estado próprio e sem forma de ser testada
sem Android e sem Tor:

```kotlin
scope.launch {
    try {
        tor.start(seed, bridges...)
        publish(token) { it.copy(network = NetworkStatus.ONLINE) }
        while (isActive) { ...persistTorState(); delay(60_000) }
    } catch (_: Exception) {
        publish(token) { it.copy(network = NetworkStatus.ERROR, ...) }
    }
}
```

Uma falha era terminal: `NetworkStatus.ERROR` permanecia até o usuário bloquear e reabrir o
aplicativo. `NetworkStatus.RETRYING` existia no contrato de UI e nunca era emitido.

### Como ficou

Uma classe `internal` sem Android, sem Tor, sem relógio e sem dispatcher. Tudo o que toca o mundo
externo é injetado, então a máquina de estados inteira roda em teste de JVM:

```kotlin
internal class TransportSupervisor(
    private val onStatus: (NetworkStatus) -> Unit,
    private val sleep: suspend (Long) -> Unit,
    private val backoff: List<Long> = DEFAULT_BACKOFF_MILLIS,
) {
    suspend fun supervise(attempt: suspend () -> TransportAttempt, hold: suspend () -> Unit) {
        try {
            emit(NetworkStatus.STARTING)
            while (true) {
                if (attempt() == TransportAttempt.ONLINE) {
                    failures = 0
                    emit(NetworkStatus.ONLINE)
                    hold()
                    emit(NetworkStatus.RETRYING)
                } else {
                    failures++
                    emit(NetworkStatus.RETRYING)
                    sleep(delayFor(failures))
                }
            }
        } catch (cancelled: CancellationException) { emit(NetworkStatus.OFF); throw cancelled }
    }
}
```

- `DEFAULT_BACKOFF_MILLIS = [5 s, 10 s, 20 s, 60 s, 120 s, 300 s]` e `delayFor` satura no último
  degrau, que é o teto de 5 minutos verificado no `init`.
- `emit` só chama `onStatus` quando o estado muda, então a UI não pisca e o `StateFlow` não recebe
  repetição de valor.
- `publishing()` deixa a tentativa reportar PUBLISHING (cliente bootstrapado, descritor ainda não
  publicado) pelo mesmo canal, em vez de a UI ter duas fontes de estado.
- Uma perda ocorrida já em ONLINE reconecta imediatamente (`failures = 0` antes de `hold()`), porque
  uma queda nova não é uma falha repetida; só falhas consecutivas crescem o backoff.
- O cancelamento (o lock cancelando o `sessionScope`) é a única saída do laço e deixa o estado em
  OFF antes de propagar, de modo que uma sessão destruída não deixa faixa de rede obsoleta na tela.
- O KDoc registra explicitamente que `attempt`/`hold` precisam classificar `TimeoutCancellationException`
  como falha: por ser subclasse de `CancellationException`, um prazo estourado chegaria aqui
  disfarçado de lock e mataria o laço.

### Vantagens

- A política de reconexão passa a ser um objeto testável: os dois caminhos exigidos pelo roteiro
  (STARTING→RETRYING→ONLINE e STARTING→RETRYING→lock) são exercitados sem Tor, sem Android e sem
  relógio real, com backoff determinístico (sem jitter, que em um único aparelho não protege nada e
  só tornaria o teste probabilístico).
- O teto de 5 minutos é um requisito de privacidade e de bateria: um aparelho fora de cobertura não
  pode ficar reiniciando o `:tor` indefinidamente, mas ainda se recupera sozinho, sem depender de
  bloquear e reabrir.
- Separar a política do efeito deixa `NoMessagesController` com apenas a parte que precisa mesmo de
  Android (processo filho, vault, mutex, geração de sessão).

### Por que a mudança foi feita

T2.3 do roteiro `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`: "hoje `NoMessagesController.activate`
chama `tor.start` uma vez; falha → `NetworkStatus.ERROR` permanente até lock/unlock", com entrega de
backoff exponencial limitado, cancelável pelo lock, emitindo `RETRYING`, e teste JVM da máquina de
estados.

## 2026-09-15 — Revisão T2.1: `reachable()`, o contraponto de `publishing()`

### Como era antes

```kotlin
/** Published from inside an attempt: the client bootstrapped, the descriptor is not out yet. */
fun publishing() = emit(NetworkStatus.PUBLISHING)
```

`PUBLISHING` era um estado de mão única: só existia dentro de uma tentativa, antes do primeiro
ONLINE. Depois de a sessão ficar online, nenhum caminho conseguia voltar o banner para PUBLISHING
nem trazê-lo de volta para ONLINE sem reiniciar o transporte.

### Como ficou

```kotlin
fun publishing() = emit(NetworkStatus.PUBLISHING)

fun reachable() = emit(NetworkStatus.ONLINE)
```

### Vantagens

- Alcançabilidade é reversível no Arti (`tor-hsservice` pode voltar a publicar muito depois do
  primeiro descritor). Com os dois métodos, `holdTransport` reporta a queda e a recuperação sem
  tocar no transporte: nenhuma tentativa é repetida e nenhum backoff é dormido.
- `emit` já deduplica, então chamar `reachable()` com o status já em ONLINE é um no-op — o banner
  não pisca a cada heartbeat.
- A classe continua pura (sem Android, sem Tor, sem relógio), então a regra nova é exercitada por
  teste JVM.

### Por que a mudança foi feita

Achado P2 da revisão (`NoMessagesController.kt:343`): sem um contraponto público, uma queda de
alcançabilidade depois de ONLINE nunca chegava à UI.
