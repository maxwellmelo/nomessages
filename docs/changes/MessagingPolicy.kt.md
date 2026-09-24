# app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingPolicy.kt

## 2026-09-17 — `MessagingBundleLimiter`: balde de tokens próprio para pedidos de bundle não autenticados (QR formato 2, T4.16)

### Motivo

O formato 2 do QR de pareamento (`docs/development/doorbell-design.md`,
`core/.../protocol/Pairing.kt`) tirou o bundle PQXDH do próprio QR e passou a buscá-lo sobre Tor via
um novo `PacketKind.BUNDLE`. Esse pacote chega em `MessagingEngine.accept` **antes** de qualquer
sessão Signal existir — é, por construção, o único tipo de pacote que um estranho não pareado pode
legitimamente enviar a este onion (ver `docs/changes/MessagingEngine.kt.md`). Ele precisava de
alguma admissão para não deixar o decodificador de envelope trabalhar a taxa de linha contra um
fluxo de nonces aleatórios, mas o limitador existente não servia para esse caso.

### Como era antes

`MessagingPolicy.kt` só tinha `MessagingTrialLimiter`, usado por `MessagingEngine.accept` para todo
pacote que chega:

```kotlin
internal class MessagingTrialLimiter(private val ticks: () -> Long = System::nanoTime) {
    private var tokens = 4.0
    private var previous = ticks()
    fun admit(kind: PacketKind, known: Boolean): Boolean { ... }
}
```

Não havia nenhum limitador específico para tráfego de pareamento — porque, antes do formato 2, não
existia tráfego de pareamento sobre a rede: tudo cabia dentro do próprio QR.

### Como ficou

```kotlin
/**
 * Admission control for **unauthenticated** pairing bundle requests (2026-09-17, T4.16).
 *
 * `PacketKind.BUNDLE` arrives before any Signal session exists, so it cannot go through
 * [MessagingTrialLimiter], which classifies by "is this a peer we already know". The real defence
 * is structural and lives in `PairingEngine.bundleFor`: an answer needs a nonce this device itself
 * minted, inside that offer's own validity window, and each nonce is answered exactly once. This
 * bucket exists only so that a flood of *wrong* nonces cannot make the receive loop decode
 * envelopes at line rate; the same 1/s sustained, burst 4 shape as the trial limiter.
 */
internal class MessagingBundleLimiter(private val ticks: () -> Long = System::nanoTime) {
    private var tokens = 4.0
    private var previous = ticks()

    fun admit(): Boolean {
        val now = ticks()
        tokens = minOf(4.0, tokens + maxOf(0L, now - previous) / 1_000_000_000.0)
        previous = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
```

`MessagingEngine` mantém uma instância própria (`bundleLimiter = MessagingBundleLimiter()`),
separada do `trialLimiter` já existente, e a consulta em `answerBundleRequest` antes de decodificar
o envelope do pedido:

```kotlin
private fun answerBundleRequest(payload: ByteArray): ByteArray? {
    if (payload.size > maxBundlePayload) return null
    val source = pairingBundles ?: return null
    if (!bundleLimiter.admit()) return null
    ...
```

### Por que este balde precisa ser separado do `MessagingTrialLimiter`

`MessagingTrialLimiter.admit(kind, known)` classifica pelo atalho "este é um par que eu já
conheço" (`known`, derivado de `expectedPeers[connection]` — uma sessão Signal já em curso). Um
`BundleRequest` chega, por definição, de alguém que **ainda não é** um par conhecido: o pareamento
é exatamente o processo que estabelece esse conhecimento. Ou seja, o atalho central que o limitador
de mensagens usa para dar mais orçamento a tráfego confiável simplesmente não se aplica aqui — todo
`BundleRequest`, mesmo o legítimo vindo do par que este dispositivo está de fato pareando agora,
cairia sempre no lado "desconhecido" desse limitador.

Compartilhar um único balde entre os dois tráfegos teria uma consequência concreta e ruim: uma
enxurrada de pedidos de bundle (de nonces aleatórios, sondando o onion) esgotaria o mesmo orçamento
que mensagens comuns também disputam, derrubando a admissão de tráfego normal — e o inverso também
vale, uma sessão de mensagens muito ativa poderia esgotar o orçamento e impedir que o próprio par
legítimo em processo de pareamento consiga completar sua busca de bundle. Dois baldes independentes,
cada um com seu próprio teto de rajada (4) e reposição (1 token/s), isolam completamente os dois
tipos de pressão.

### A defesa real não é este limitador — é a regra estrutural de nonce

O KDoc do próprio `MessagingBundleLimiter` é explícito sobre isso, e vale repetir aqui: este balde
só limita **trabalho de decodificação** — ele impede que um fluxo de nonces errados force o laço de
recepção a chamar `EnvelopeCodec.decode` a taxa de linha. Ele não é, e não pretende ser, a barreira
que decide quem recebe um bundle de verdade. Essa decisão é inteiramente de `PairingEngine.bundleFor`
(`core/.../protocol/Pairing.kt`): um pedido só é atendido quando o nonce é de 16 bytes, corresponde a
uma oferta que **este próprio dispositivo mintou** (a que está na tela, a que está dentro da troca
pendente, ou a única oferta `superseded` que o auto-refresh acabou de substituir), essa oferta ainda
está dentro da sua própria janela `PENDING_TTL_SECONDS`, e ainda **não foi respondida antes**.
Qualquer coisa fora disso devolve `null` de `bundleFor`, e `MessagingEngine` responde com silêncio,
não com erro (ver `docs/changes/MessagingEngine.kt.md`). Um atacante que passasse pelo token bucket
sem esbarrar no limite continuaria sem conseguir nada além de silêncio, porque não tem — e não pode
forjar — um nonce que este dispositivo mintou.

### Vantagens

- Isola a pressão de admissão de pareamento da de mensagens comuns, nos dois sentidos: uma enxurrada
  de um tipo não pode esgotar o orçamento do outro.
- Reaproveita a mesma forma comprovada de balde de tokens (`MessagingTrialLimiter`) sem herdar sua
  regra de classificação por "par conhecido", que não faz sentido antes de existir sessão.
- Documenta explicitamente, no próprio código, que este limitador é só uma segunda linha de defesa
  contra custo de decodificação — a defesa que de fato importa é estrutural e vive em
  `PairingEngine.bundleFor`, então uma futura revisão de segurança sabe onde olhar primeiro.

### Validação

`:app:testDebugUnitTest` — 60 testes, 0 falhas, 0 erros, 1 pulado (`BUILD SUCCESSFUL`, 2026-09-17;
ver `docs/changes/MessagingPolicyTest.kt.md` para o teste dedicado a este limitador).
