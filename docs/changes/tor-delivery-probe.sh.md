# scripts/tor-delivery-probe.sh

## 2026-09-15 — Revisão T2.1: prazo externo derivado dos orçamentos da própria sonda (P2)

### Como era antes

```bash
# No Gradle daemon or compiler is needed during this network experiment.
# The outer deadline also bounds an unresponsive native call.
timeout --signal=TERM --kill-after=10s 600s \
```

Os 600 s foram escolhidos antes de T2.1. Depois que a sonda passou a esperar a publicação do
descritor, o orçamento interno dela ficou **maior** que o prazo externo:

| Etapa | Orçamento |
| --- | --- |
| bootstrap dentro de `tor::start` | 180 s |
| publicação do descritor (`MAX_READY_TIMEOUT_MILLIS`) | 300 s |
| tentativas de auto-conexão (`TorDeliveryProbe.java:51`) | 240 s |
| dois `poll(30000)` (quadro e ACK) | 60 s |
| **total** | **780 s** |

Com o teto em 600 s, o gate T2.2 não podia mais concluir: o `timeout` cortava uma execução
saudável e o resultado não dizia nada sobre o Tor.

### Como ficou

```bash
#   180s  bootstrap inside tor::start
# + 300s  descriptor publication (TorNative.MAX_READY_TIMEOUT_MILLIS)
# + 240s  self-connect retries after publication
# +  60s  two 30s polls for the frame and its acknowledgement
# + 120s  margin for JVM start, Arti teardown and a slow directory fetch
probe_deadline=$((180 + 300 + 240 + 60 + 120))
timeout --signal=TERM --kill-after=10s "${probe_deadline}s" \
```

900 s no total.

### Vantagens

- O número deixa de ser um palpite: a soma está escrita ao lado dele, com a origem de cada parcela,
  então a próxima pessoa que mexer em um dos orçamentos vê imediatamente o que precisa recalcular.
- A margem de 120 s cobre o que não é orçamento nominal: partida da JVM, `System.loadLibrary`,
  desmontagem do Arti e uma busca de diretório lenta.
- O `timeout` continua existindo pelo motivo que sempre teve — limitar uma chamada nativa que
  travou —, só que agora só dispara quando algo realmente travou.

### Por que a mudança foi feita

Achado P2 da revisão (`scripts/tor-delivery-probe.sh:36`).
