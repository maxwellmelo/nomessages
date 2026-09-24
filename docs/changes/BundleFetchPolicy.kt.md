# app/src/main/kotlin/dev/mx3/nomessages/runtime/BundleFetchPolicy.kt

## 2026-09-18 — Política pura da retentativa da busca do pacote de chaves (arquivo novo, T4.18)

### Como era antes

O arquivo não existia. Toda a aritmética da janela de retentativa estava embutida no laço de
`NoMessagesController.startBundleFetch()`, misturada com corrotina, relógio de parede e I/O de rede:

```kotlin
private const val BUNDLE_ATTEMPT_MILLIS = 60_000L      // no companion do controller
private const val BUNDLE_RETRY_DELAY_MILLIS = 3_000L   // idem

while (System.currentTimeMillis() < deadline) {
    ...
    val remaining = deadline - System.currentTimeMillis()
    if (remaining <= 0) break
    val bundle = try {
        live.fetchPeerBundle(onion, nonce, remaining.coerceAtMost(BUNDLE_ATTEMPT_MILLIS))
    } catch (_: Exception) {
        delay(BUNDLE_RETRY_DELAY_MILLIS)
        continue
    }
```

Três decisões distintas — "ainda dá tempo de tentar de novo?", "quanto orçamento esta tentativa
recebe?" e "quanto esperar antes da próxima?" — sem nome, sem documentação própria e **sem nenhum
teste possível**: exercitá-las exigiria um `NoMessagesController` vivo, isto é, `AndroidViewModel`,
SQLCipher, Tor e instrumentação. Na prática, nunca foram testadas.

### Como ficou

Um arquivo pequeno, `internal`, sem Android, sem Tor, sem corrotina e sem relógio de parede — o
`now` é parâmetro. Mesma disciplina de `TransportSupervisor.kt` e `DoorbellKnockPolicy.kt`, pelo
mesmo motivo: a política inteira passa a ser exercida por teste JVM, sem mock nenhum.

```kotlin
internal object BundleFetchPolicy {
    const val ATTEMPT_BUDGET_MILLIS = 60_000L
    const val RETRY_DELAY_MILLIS = 3_000L

    fun shouldAttempt(deadlineAt: Long, now: Long): Boolean = now < deadlineAt

    fun attemptBudget(deadlineAt: Long, now: Long): Long =
        (deadlineAt - now).coerceIn(0L, ATTEMPT_BUDGET_MILLIS)

    fun retryDelay(deadlineAt: Long, now: Long): Long? =
        if (deadlineAt - now > RETRY_DELAY_MILLIS) RETRY_DELAY_MILLIS else null
}
```

Os dois valores (60 s por tentativa, 3 s de pausa) **não mudaram** — só mudaram de lugar, saindo do
`companion object` do controller para junto da regra que os usa.

### Decisões registradas no próprio código

- **O prazo é exclusivo.** `shouldAttempt(deadline, deadline)` é `false`. Isso não é gosto: o portão
  do motor de pareamento, `PairingEngine.get`, recusa em `now() >= p.expires`. Uma tentativa iniciada
  exatamente no limite jamais poderia ser aceita, então não é iniciada.
- **O prazo real é 240 s, não 300 s.** `PairingProgress.expiresAt` vem de
  `PairingEngine.CONFIRMATION_TTL_SECONDS` (240 s). O número 300 que circulava na documentação é o
  `PENDING_TTL_SECONDS`, que rege a fase *anterior* (aquisição da oferta), não esta.
- **`retryDelay` devolve `null` em vez de dormir para além do prazo.** Antes, uma falha faltando 1 s
  para o vencimento ainda dormia 3 s inteiros e só então o `while` reprovava: o banner de falha
  aparecia até 3 s depois de a troca já ter expirado. Agora o laço encerra na hora.

### Vantagens

- A regra passou a ter 9 casos de teste JVM (ver `BundleFetchPolicyTest.kt.md`), incluindo o
  comportamento exato na borda do prazo — antes, zero.
- O controller ficou com o "o quê" (tentar, aceitar, publicar status) e perdeu o "quando", que é
  justamente a parte aritmética onde erros de sinal e de `off-by-one` se escondem.
- O orçamento por tentativa agora é sempre não-negativo por construção (`coerceIn(0, ...)`), em vez
  de depender de um `if (remaining <= 0) break` correto no lugar certo do laço.
- A janela de 240 s está documentada onde a conta é feita, com a razão pela qual **não** pode ser
  alargada aqui (o `PairingEngine` aplica o mesmo teto por conta própria).

### Por que a mudança foi feita

T4.18. O item de plano pedia (a) retentativa dentro do prazo, (b) log de diagnóstico e (c)
retentativa manual. (a) e (c) já existiam desde T4.16; a extração desta política é o que torna (a)
verificável, e é exigência da suíte de validação do projeto para qualquer regra de agendamento nova.
