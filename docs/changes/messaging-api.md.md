# docs/development/messaging-api.md

## 2026-09-18 — T4.17 (campainha): seção `Doorbell knock`

**Antes:** o arquivo não mencionava a campainha. Uma falha de entrega no `outboxLoop` era descrita apenas pela retentativa de 30 s e pelo prazo de ACK de 60 s.

**Agora:** entrou `### Doorbell knock (2026-09-18, T4.17)`, no mesmo estilo da seção já existente sobre `fetchPeerBundle`/`MessagingBundleLimiter` — prosa, com o "por quê" ao lado de cada número: quando dispara (toda falha de entrega para um contato com `doorbell_onion` preenchido e `doorbell_token` de 32 bytes), **qual** token usa (o do par, nunca o `doorbell_token_issued`, que é o desta ponta), o orçamento de **240 s** por tentativa e por que isso obriga a chamada a ser assíncrona fora do worker de outbox, a exclusão de um job por contato, a curva **10/20/40/80/120 min** com a justificativa de piso e teto, e o registro de 12 bytes em `opaque_blobs`/`doorbell_knocks` carimbado **ao despachar** e não ao acertar — com os três casos de borda (registro corrompido, carimbo no futuro, falha durante a morte do transporte **local**) e o que cada um resolve.

**Por quê:** o agendamento é a parte da campainha mais fácil de quebrar sem perceber: ele parece uma política de retentativa comum, mas uma decisão errada aqui ou faz uma tempestade de batidas (cada uma custando 240 s de circuito e anunciando a quem observa o onion que alguém tem tráfego para o dono) ou silencia a funcionalidade. Documentar por que o carimbo é do **envio** e não do **acerto** é o item que mais protege contra uma "correção" futura que pareça óbvia.

## 2026-09-14 — T2.3: assinatura sem transporte e pausa da outbox

### Como era antes

A primeira linha do contrato declarava o transporte como dependência de construção e nada dizia
sobre o que acontece com a fila quando o Tor não está alcançável:

```text
`MessagingEngine(crypto, db, identity, session, directory: Path, transport: TorConnection,
scope: CoroutineScope, mutex: Mutex, onChanged: () -> Unit, onError: (String) -> Unit)` belongs to
one unlocked vault.
```

### Como ficou

A assinatura perdeu `transport`, e uma seção nova ("Transport attachment and outbox pause")
documenta `attachTransport(connection: TorConnection?)`, o significado do campo nulo (pausa, nunca
falha), os intervalos de estacionamento dos trabalhadores (500 ms na outbox, 200 ms na recepção) e a
razão de uma conexão por tentativa: `TorService` recusa um segundo START, então cada reconexão traz
um processo filho novo e o trabalhador precisa reler o campo a cada tique em vez de capturá-lo.

O documento segue em inglês, como o restante de `docs/development/`.

### Vantagens

- O contrato volta a descrever o código: a assinatura publicada estava errada a partir do momento em
  que o parâmetro saiu do construtor.
- Deixa explícito para quem for executar os gates de aparelho (T3.2/T3.3) que uma mensagem composta
  durante RETRYING deve permanecer `PENDING` sem tentativa de envio — é um comportamento observável
  e verificável, não um detalhe interno.

### Por que a mudança foi feita

Regra do roteiro para T2.3: registrar que a pausa da outbox e o retry existem.

## 2026-09-15 — Fechamento de stream é "best effort" e nunca propaga

### Como era antes

A seção "Transport attachment and outbox pause" descrevia a pausa do outbox e o fato de um canal
fechado estacionar o laço de recepção, mas não dizia nada sobre o caminho de **fechamento** de
stream — que era justamente por onde uma `DeadObjectException` do `:tor` morto matava os laços.

### Como ficou

Parágrafo novo ao fim da seção: todo teardown de stream (`closeStream`, o varredor de expiração e
`close()`) passa por uma chamada guardada única; só cancelamento real (o lock) propaga.

### Vantagens

- O contrato fica explícito para quem acrescentar um quarto caminho de teardown no futuro.
- Explica o **porquê** (o motor deixaria de ler o transporte substituto), não só o quê.

### Por que a mudança foi feita

Achado P1 da revisão (`MessagingEngine.kt:796`).
