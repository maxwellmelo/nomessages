# app/src/main/kotlin/dev/mx3/nomessages/runtime/DoorbellKnockPolicy.kt

## 2026-09-18 — Campainha, fase 4: agenda de toques (arquivo novo, T4.17)

### Como era antes

O arquivo não existia. Nas fases 1–3 a campainha ganhou o opcode nativo
(`native/src/doorbell.rs`, opcode `16 doorbellKnock`), as colunas por contato
(`ContactRecord.doorbellOnion` / `doorbellToken`) e a ponte IPC
(`TorConnection.doorbellKnock`) — mas **nenhuma regra sobre quando tocar**. Não havia piso, não
havia backoff e não havia memória de toque nenhum: o único agendamento existente no runtime era o
do transporte (`TransportSupervisor`), que trata de outra coisa.

Sem uma regra própria, o gatilho natural (uma falha de envio na outbox) tocaria a cada retentativa,
isto é, a cada 30 s por item na fila — com cada toque custando até 240 s de circuito Tor.

### Como ficou

Um arquivo pequeno, `internal`, sem Android, sem Tor, sem banco e sem relógio de parede: o `now` é
parâmetro. É a mesma disciplina de `TransportSupervisor.kt`, e pelo mesmo motivo — a política
inteira é exercida por teste JVM, sem mock nenhum.

```kotlin
internal data class DoorbellKnockState(val lastAttempt: Long, val failures: Int) {
    fun attempted(now: Long): DoorbellKnockState = copy(lastAttempt = now)
    fun confirmed(): DoorbellKnockState = copy(failures = 0)
    fun failed(): DoorbellKnockState = copy(failures = if (failures == Int.MAX_VALUE) failures else failures + 1)
}

internal object DoorbellKnockPolicy {
    const val NAMESPACE = "doorbell_knocks"
    const val TOKEN_BYTES = 32
    const val ENCODED_BYTES = 12
    val BACKOFF_MILLIS: List<Long> = listOf(10L, 20L, 40L, 80L, 120L).map { it * 60_000L }
    const val FLOOR_MILLIS = 10 * 60_000L
    const val MAX_BACKOFF_MILLIS = 120 * 60_000L

    fun delayFor(failures: Int): Long
    fun nextEligible(state: DoorbellKnockState): Long
    fun ready(state: DoorbellKnockState?, now: Long): Boolean
    fun encode(state: DoorbellKnockState): ByteArray
    fun decode(bytes: ByteArray?): DoorbellKnockState?
}
```

Quem consome: `MessagingEngine.prepareKnock` / `recordKnock` / `clearKnockFailures` / `knockState`
(ver `docs/changes/MessagingEngine.kt.md`).

### Decisões e por quê

**1. `lastAttempt` é a hora da tentativa, não a do sucesso.** O caso que a campainha existe para
resolver é um par com o aparelho desligado — e o aparelho desligado é exatamente o que não responde
à campainha. Um contador que só avançasse no sucesso deixaria esse caso sem limite algum.

**2. A curva é 10 → 20 → 40 → 80 → 120 min (teto).** O primeiro degrau é o piso exigido pela
especificação e é também o valor de retorno após um toque confirmado. A duplicação cobre ausências
longas; o teto de 2 h impede que o remetente convirja para um atraso maior que a própria ausência.
Mesma forma do `TransportSupervisor.DEFAULT_BACKOFF_MILLIS` (degraus crescentes, último degrau =
teto, constante pública para o teste).

**3. Um toque confirmado zera as falhas mas preserva o carimbo.** Se zerasse o carimbo também, um
par que responde à campainha poderia ser tocado de novo imediatamente; o piso de 10 min é
incondicional.

**4. Relógio para trás ⇒ elegível.** `ready` devolve `true` quando `now < lastAttempt`. A
alternativa (esperar o relógio alcançar um carimbo do futuro) deixaria um contato inatingível por
dias depois de uma correção de fuso/NTP ou da restauração de um cofre. Custa no máximo um toque
extra, porque despachá-lo reescreve o carimbo com o relógio atual.

**5. `decode` é tolerante, `DoorbellKnockState` é estrito.** O construtor recusa contador negativo
(`require(failures >= 0)`), mas `decode` satura em zero e devolve `null` para qualquer tamanho
diferente de 12 bytes. Um blob corrompido vale como "nunca tocado" — nunca como "silenciar este
contato para sempre".

**6. `TOKEN_BYTES = 32` mora aqui.** `NoMessagesController` tem a sua própria constante privada com
o mesmo valor, para validar `doorbellTokenIssued` ao subir a campainha local. Esta fase não pode
editar o controlador (fases anteriores fechadas), e as duas constantes descrevem os dois lados do
mesmo comprimento de coluna; a duplicação está documentada aqui em vez de escondida.

### Vantagens

- A regra que mais importa da feature (quando tocar, e quanto esperar depois de falhar) é uma
  função pura: 13 casos JUnit cobrem piso, limite exato, crescimento, reset, teto, saturação,
  relógio invertido e o formato binário — sem `Thread.sleep`, sem Robolectric, sem Tor.
- Trocar a curva é editar uma lista de constantes, não caçar números mágicos dentro do motor.
- O formato binário do registro persistido (`encode`/`decode`) mora junto da regra que o interpreta,
  então não existe leitor que discorde do escritor.

### Validação

Ver `docs/changes/MessagingEngine.kt.md` (mesma fase): `:app:compileDebugKotlin` e
`:app:testDebugUnitTest` com `BUILD SUCCESSFUL` — 74 testes, 0 falhas, 0 erros, 1 pulado
(pré-existente, em `QrDecodingTest`).
