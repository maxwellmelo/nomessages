# app/src/test/kotlin/dev/mx3/nomessages/runtime/TransportSupervisorTest.kt

## 2026-09-14 — T2.3: teste JVM da reconexão (arquivo novo)

### Como era antes

Não havia teste nenhum de ativação do transporte. A suíte JVM do módulo `app` tinha 17 casos
(`MessagingPolicyTest`, `SensitiveBufferTest`, `UiLogicTest`) e nenhum deles tocava em
`NetworkStatus` além do contrato de UI. A única forma de observar uma falha de ativação era rodar
Tor de verdade.

### Como ficou

Cinco casos JUnit4 (o mesmo estilo dos testes existentes, executados pelo motor vintage) que
injetam `attempt`, `hold` e `sleep` falsos e conferem a sequência exata de estados e de esperas:

```kotlin
private fun newSupervisor(sleep: suspend (Long) -> Unit = { slept += it }) =
    TransportSupervisor(onStatus = { emitted += it }, sleep = sleep)
```

| Caso | O que fixa |
|---|---|
| `failedActivationRetriesAndThenComesOnline` | STARTING→RETRYING→ONLINE com uma espera de 5 s e contador de falhas zerado ao final |
| `publishingIsReportedBeforeOnlineWithinTheSameAttempt` | PUBLISHING sai de dentro da tentativa, antes de ONLINE, sem gerar espera |
| `lockCancelsTheBackoffAndLeavesNoStaleBanner` | STARTING→RETRYING→lock: o `CancellationException` lançado de dentro do `sleep` propaga e o estado final é OFF |
| `backoffGrowsToAFiveMinuteCeiling` | 5 s, 10 s, 20 s, 60 s, 120 s, 300 s e saturação no teto a partir da sexta falha |
| `transportLostWhileOnlineReconnectsWithoutInheritingOldFailures` | queda depois de ONLINE reinicia o cronograma em 5 s em vez de herdar o degrau de 20 s |

O laço de supervisão nunca retorna sozinho, então os testes que precisam terminar lançam uma
exceção própria (`StopLoop`) de dentro do `hold`, deixando o `CancellationException` reservado
exclusivamente para representar o lock.

### Vantagens

- O critério "Feito quando" de T2.3 (STARTING→RETRYING→ONLINE e STARTING→RETRYING→lock, sem tocar em
  Tor real) fica coberto por asserções de sequência, não por inspeção de log.
- O cronograma de backoff vira contrato verificado: qualquer alteração futura dos degraus ou do teto
  quebra um teste em vez de passar despercebida.
- Sem relógio real: os cinco casos rodam em milissegundos, mesmo verificando esperas de 5 minutos.

### Por que a mudança foi feita

T2.3 exige teste JVM novo em `app/src/test` cobrindo a máquina de estados sem Tor real.

### Execução

`./gradlew --offline :app:testDebugUnitTest` no WSL Ubuntu (JDK 21.0.12, SDK em
`~/nomessages-tools/android-sdk`, Gradle 9.3.1): **BUILD SUCCESSFUL**, 22 casos, zero falhas —
`TransportSupervisorTest` 5/5 e a regressão completa dos 17 já existentes
(`MessagingPolicyTest` 8, `SensitiveBufferTest` 3, `UiLogicTest` 6). O mesmo build compilou
`:app:compileDebugKotlin` com `allWarningsAsErrors`, o que também valida
`NoMessagesController.kt`, `MessagingEngine.kt` e `TransportSupervisor.kt`.

## 2026-09-15 — Caso novo: alcançabilidade reportada nos dois sentidos

### Como era antes

Cinco casos, todos sobre a sequência de ativação (STARTING → RETRYING → ONLINE, cancelamento pelo
lock, teto do backoff, perda depois de ONLINE). Nenhum cobria mudança de status **durante** o
`hold`, porque não havia como emiti-la.

### Como ficou

Caso novo `reachabilityIsReportedBothWaysWhileTheSameTransportIsHeld`: o `hold` chama
`reachable()` (redundante, já está ONLINE), depois `publishing()` e depois `reachable()` de novo.

Asserções: `STARTING, ONLINE, PUBLISHING, ONLINE` — exatamente quatro emissões —, uma única
tentativa, nenhuma soneca de backoff e `failureCount == 0`.

### Vantagens

- Prova as três propriedades de uma vez: a deduplicação (o `reachable()` redundante não repinta), a
  ida e a volta do banner, e o fato de que uma queda de alcançabilidade **não** conta como falha
  nem dispara reconexão.
- Continua sem tocar em Tor real: o `hold` é um lambda, como nos outros casos.

### Por que a mudança foi feita

Cobrir a API nova exigida pelo achado P2 (`NoMessagesController.kt:343`).
