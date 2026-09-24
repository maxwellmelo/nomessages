# app/src/test/kotlin/dev/mx3/nomessages/runtime/MessagingPolicyTest.kt

## 2026-09-17 — `unauthenticatedBundleRequestsHaveTheirOwnBurstAndRefill` (T4.16)

### Motivo

`MessagingBundleLimiter` (ver `docs/changes/MessagingPolicy.kt.md`) é um balde de tokens novo,
dedicado a `PacketKind.BUNDLE`, deliberadamente separado de `MessagingTrialLimiter`. A propriedade
que mais importa validar não é "o balde funciona" isoladamente — é "os dois baldes não vazam
orçamento um para o outro", que é exatamente o motivo pelo qual eles existem em separado.

### Como era antes

Não havia teste: `MessagingBundleLimiter` não existia antes desta tarefa.

### Como ficou

```kotlin
/**
 * Pairing bundle requests (T4.16) get their own budget: they arrive before any Signal session
 * exists, so the trial limiter's "is this a peer we already know" shortcut does not apply to
 * them, and sharing one bucket would let a pairing flood starve ordinary message admission.
 */
@Test fun unauthenticatedBundleRequestsHaveTheirOwnBurstAndRefill() {
    var now = 0L
    val bundles = MessagingBundleLimiter { now }
    val trials = MessagingTrialLimiter { now }
    repeat(4) { assertTrue(bundles.admit()) }
    assertFalse(bundles.admit())
    // Exhausting the bundle bucket must not have touched the message bucket.
    repeat(4) { assertTrue(trials.admit(PacketKind.SIGNAL, false)) }
    // One token per second, capped at the burst of four.
    now += 1_000_000_000L
    assertTrue(bundles.admit())
    assertFalse(bundles.admit())
    now += 60_000_000_000L
    repeat(4) { assertTrue(bundles.admit()) }
    assertFalse(bundles.admit())
    // A clock that appears to run backwards must not mint tokens.
    now -= 60_000_000_000L
    assertFalse(bundles.admit())
}
```

O teste injeta um relógio falso (`{ now }`, o mesmo parâmetro `ticks` que `MessagingTrialLimiterTest`
já usava para o outro limitador), sem `Thread.sleep` nem tempo real, e cobre em sequência:

- **Rajada de 4.** As quatro primeiras chamadas a `bundles.admit()` são aceitas (o teto de tokens
  inicial); a quinta, no mesmo instante, é recusada.
- **Isolamento entre baldes.** Depois de esgotar o balde de bundle, as quatro primeiras chamadas a
  `trials.admit(PacketKind.SIGNAL, false)` — um balde `MessagingTrialLimiter` totalmente
  independente, no mesmo instante `now` — são aceitas normalmente. Se os dois limitadores
  compartilhassem estado (por exemplo, se `MessagingBundleLimiter` fosse implementado como um alias
  para o mesmo balde), esta linha falharia.
- **Reposição de 1 token/s, com teto no burst.** Avançar o relógio falso em exatamente 1 s repõe
  exatamente 1 token: a próxima chamada é aceita, a seguinte (no mesmo instante) é recusada de novo.
  Avançar 60 s a mais e verificar que só 4 chamadas passam (não mais) confirma o teto de acúmulo em
  `minOf(4.0, tokens + ...)` — o balde não guarda crédito ilimitado por ficar muito tempo ocioso.
- **Relógio que anda para trás não cunha tokens.** `now -= 60_000_000_000L` simula um retrocesso de
  relógio (por exemplo, um ajuste de NTP ou um `System.nanoTime()` que, por algum motivo de
  plataforma, produza uma leitura menor que a anterior). `admit()` usa
  `maxOf(0L, now - previous)`, então um delta negativo vira zero tokens repostos, não tokens
  negativos que se tornariam positivos e drenariam o balde de forma imprevisível na próxima
  reposição real. A asserção espera `false` porque o balde já estava esgotado (a rajada anterior
  consumiu os 4 tokens do passo anterior) e nenhum token novo foi cunhado.

### Vantagens

- Cobre exatamente a propriedade de design que motivou o limitador separado (isolamento de
  orçamento), não só o comportamento genérico de um token bucket.
- Reaproveita o padrão de injeção de relógio já estabelecido por `MessagingTrialLimiterTest`, então
  o teste é determinístico e roda em milissegundos, sem depender de tempo real.
- O caso de relógio retrocedendo é o mesmo tipo de defesa que `MessagingTrialLimiter` já tinha;
  testá-lo aqui também evita que uma futura refatoração que unifique os dois limitadores perca essa
  garantia silenciosamente.

### Validação

`:app:testDebugUnitTest` — 60 testes, 0 falhas, 0 erros, 1 pulado (`BUILD SUCCESSFUL`, 2026-09-17).
