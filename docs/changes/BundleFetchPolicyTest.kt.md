# app/src/test/kotlin/dev/mx3/nomessages/runtime/BundleFetchPolicyTest.kt

## 2026-09-18 — Teste JVM da janela de retentativa da busca do pacote de chaves (arquivo novo, T4.18)

### Como era antes

Não havia teste nenhum da busca do pacote de chaves em nenhum módulo. A cerimônia inteira de T4.16
foi validada só por execução ao vivo (run6b e a corrida em aparelho físico de 2026-09-18), e a
aritmética do prazo — `while (System.currentTimeMillis() < deadline)` dentro de
`NoMessagesController.startBundleFetch()` — não tinha como ser exercitada sem Android, SQLCipher e
Tor. A suíte JVM do módulo `app` tinha 74 casos, nenhum deles sobre pareamento.

### Como ficou

9 casos cobrindo a política extraída em `BundleFetchPolicy.kt`:

| Caso | O que fixa |
| --- | --- |
| `realWindowIsTwoHundredAndFortySeconds` | A janela real é 240 000 ms — trava a afirmação "240 s, não 300 s" do KDoc e do roadmap |
| `retriesWhileTheExchangeIsStillOpen` | Dentro do prazo (início e `deadline - 1`) → tenta de novo |
| `deadlineIsExclusiveSoTheBoundaryDoesNotRetry` | Exatamente no limite → **não** tenta (escolha documentada: exclusivo, igual a `PairingEngine.get`) |
| `doesNotRetryAfterTheDeadline` | Depois do prazo → não tenta |
| `earlyAttemptsGetTheFullPerAttemptBudget` | Tentativa cedo recebe os 60 s inteiros, e cabem ≥ 4 delas na janela |
| `lateAttemptIsClampedToWhatIsLeftOfTheExchange` | Tentativa tardia recebe só o que sobra |
| `budgetNeverGoesNegativePastTheDeadline` | Orçamento satura em 0, nunca negativo |
| `pausesBetweenAttemptsWhileThereIsRoomForAnother` | Com espaço, a pausa é 3 s |
| `givesUpInsteadOfSleepingPastTheDeadline` | Sem espaço para pausa + tentativa, devolve `null` (desiste na hora) |

O detalhe que importa é de onde vem o prazo:

```kotlin
private val window = PairingEngine.CONFIRMATION_TTL_SECONDS * 1000
```

### Vantagens

- **Não dessincroniza.** O prazo é lido da constante que o produz, em `core`, em vez de `240_000L`
  solto. Se alguém mudar `CONFIRMATION_TTL_SECONDS` um dia, `realWindowIsTwoHundredAndFortySeconds`
  falha e obriga a revisitar o KDoc de `BundleFetchPolicy` e a entrada T4.18 do roadmap, em vez de
  deixar documentação mentindo em silêncio.
- A borda do prazo (inclusivo × exclusivo) passa a ser um comportamento **escrito e testado**, não
  um acidente do operador `<` que alguém poderia "corrigir" para `<=` numa refatoração futura.
- Roda em milissegundos, sem `Thread.sleep`, sem emulador, sem Tor: entra no `:app:testDebugUnitTest`
  que já roda em todo build.

### Por que a mudança foi feita

T4.18. A tarefa exigia explicitamente teste JVM da política de retentativa, incluindo o caso de borda
com o prazo real.
