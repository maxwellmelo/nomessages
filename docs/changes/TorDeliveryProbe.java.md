# core/src/test/native/TorDeliveryProbe.java

## 2026-09-14 — T2.1: espera real em vez de `Thread.sleep(30s)`

### Como era antes

```java
require(tor.status().equals("READY"), "Bootstrap did not complete");
log("Bootstrap complete; allowing initial descriptor publication");
Thread.sleep(30_000);
long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
```

A sonda afirmava READY logo após o `start` (o que o nativo devolvia, mas era falso) e depois dormia 30 segundos na esperança de que a publicação tivesse terminado. Se não tivesse, os quatro minutos seguintes de tentativas de `send` mediam a publicação, não a entrega.

### Como ficou

```java
require(tor.status().equals("PUBLISHING"), "Launch must not claim readiness");
log("Bootstrap complete; waiting for the onion descriptor to be published");
long publishing = System.nanoTime();
String reached = tor.awaitReady(TorNative.MAX_READY_TIMEOUT_MILLIS);
require(reached.equals("READY"), "Onion descriptor was not published within 300s");
require(tor.status().equals("READY"), "Readiness wait returned an inconsistent status");
log(String.format("Descriptor published after %.1fs", ...));
long firstFrame = System.nanoTime();
```

e, depois do laço de conexão, `log("Time to first frame after publication: %.1fs")`.

### Vantagens

- Elimina um `sleep` arbitrário: a sonda espera o evento real do `tor-hsservice` e registra quanto tempo ele levou.
- Os dois tempos exigidos por T2.2 (publicação e primeiro frame) saem no próprio log da sonda.
- O `require` de `PUBLISHING` protege contra regressão do contrato de estados na ponte JNI.

### Por que a mudança foi feita

T2.1: as sondas ao vivo só podem tentar `send` depois de o serviço reportar-se alcançável.
