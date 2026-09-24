# docs/development/lifecycle-review.md

## 2026-09-14 — T2.3: seção sobre ativação supervisionada

### Como era antes

O documento abria direto na revisão de 2026-09-13 ("Final cleanup-outcome recheck") e descrevia a
ativação como uma operação única. Vários achados do próprio documento partem dessa premissa — por
exemplo, que `lock()` sempre captura um transporte não nulo, e que uma ativação falha simplesmente
limpa os recursos que criou.

### Como ficou

Uma seção nova no topo, "Supervised transport activation — 2026-09-14 (T2.3)", registra o retry e as
cinco consequências de ciclo de vida que os gates de aparelho precisam passar a cobrir:

1. o laço é cancelado pelo lock (vive no `sessionScope`), com `TimeoutCancellationException`
   classificado antes de `CancellationException`;
2. cada tentativa tem a sua `TorConnection`, registrada sob `lifecycleGuard` antes de iniciar, e
   `transport` é nulo entre tentativas — portanto `lock()` durante STARTING agora captura nulo
   legitimamente;
3. uma nova tentativa só ocorre após a morte confirmada do filho anterior;
4. a semente é relida do vault por tentativa e zerada em seguida;
5. enquanto não ONLINE a outbox fica pausada, o que acrescenta asserções ao cenário 2 de T3.2.

O registro de que o kill confirmado no lock permanece inalterado está explícito.

### Vantagens

- Quem for executar T3.2 e T3.3 encontra, no mesmo documento das revisões anteriores, o que mudou
  desde que aquelas revisões foram escritas, em vez de revalidar premissas obsoletas.
- Documenta a armadilha de `TimeoutCancellationException` ser subclasse de `CancellationException`,
  que é exatamente o tipo de defeito que uma revisão estática posterior procuraria.

### Por que a mudança foi feita

Regra do roteiro para T2.3: nota de que o retry existe em `lifecycle-review.md`.

## 2026-09-15 — Seção "Supervision hardening" (revisão T2.1/T2.3)

### Como era antes

O documento terminava a descrição da supervisão na seção de 2026-09-14: backoff, uma
`TorConnection` por tentativa, morte confirmada antes de um retry, semente relida por tentativa e
outbox pausado. Nada dizia sobre checkpoint de guards por transição, sobre o significado de um
prazo de publicação esgotado nem sobre alcançabilidade reversível — e as três coisas mudam o que os
gates de dispositivo precisam observar.

### Como ficou

Seção nova "Supervision hardening — 2026-09-15 (T2.1/T2.3 review fixes)" com os quatro pontos
fechados (prontidão tri-estado, checkpoint de guards em cada transição, morte esperada em vez de
amostrada, alcançabilidade reportada nos dois sentidos) e uma consequência explícita para o gate:
o cenário 2 de T3.2 passa a exigir que o banner volte a PUBLISHING e retorne a ONLINE **sem** um
`:tor` novo no meio (um único pid ao longo da transição) e que a morte do `:tor` apareça em menos
de um heartbeat.

### Vantagens

- Quem for executar T3.2 agora tem o critério observável escrito, incluindo o "um único pid", que é
  a única forma de distinguir uma recuperação de alcançabilidade de uma reconexão.
- A seção antiga continua intacta e datada: a evolução do desenho fica legível em ordem.

### Por que a mudança foi feita

Regra de documentação por modificação, para os quatro achados P2 do controller.
