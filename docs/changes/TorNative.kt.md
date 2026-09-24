# core/src/main/kotlin/dev/mx3/nomessages/core/nativebridge/TorNative.kt

## 2026-09-14 — T2.1: `awaitReady` e status com PUBLISHING

### Como era antes

```kotlin
private const val MAX_FRAME = 4 + 16 * 1024
// ...
fun onion(): String = call(2).toString(Charsets.US_ASCII)
fun status(): String = call(3).toString(Charsets.US_ASCII)
```

`status()` não tinha contrato documentado e não havia forma de esperar pela publicação do descritor.

### Como ficou

```kotlin
/** Mirrors the native readiness cap; see `native/src/tor.rs`. */
const val MAX_READY_TIMEOUT_MILLIS = 300_000
// ...
/** BOOTSTRAPPING, PUBLISHING, READY or STOPPED. Only READY means the onion descriptor is published. */
fun status(): String = call(3).toString(Charsets.US_ASCII)

fun awaitReady(timeoutMillis: Int = MAX_READY_TIMEOUT_MILLIS): String {
    require(timeoutMillis in 0..MAX_READY_TIMEOUT_MILLIS)
    return call(9) { int(timeoutMillis) }.toString(Charsets.US_ASCII)
}
```

O `call(9)` reaproveita o mesmo `Arguments` com limpeza garantida; nenhuma outra função mudou.

### Vantagens

- O limite de 300 s é validado antes da travessia JNI, com a mesma política de "wrapper Kotlin é dono da validação" já usada no arquivo.
- A constante pública é visível também do Java (`TorNative.MAX_READY_TIMEOUT_MILLIS`), o que a sonda `TorDeliveryProbe` usa sem duplicar o número.
- O KDoc de `status()` deixa explícito que só READY significa descritor publicado, evitando que chamadores voltem a tratar "não-STOPPED" como pronto.

### Por que a mudança foi feita

T2.1 exige que o estado PUBLISHING e a espera pela publicação sejam expostos por `TorNative`.


## 2026-09-18 — T4.17 fase 3: funções Kotlin dos opcodes 10-16 (campainha)

### Motivo

A fase 1 entregou os opcodes nativos 10-16 (`native/src/tor_jni.rs`), mas nada em Kotlin sabia
chamá-los. Esta é a ponte: sete funções que espelham exatamente aqueles opcodes, sem inventar
semântica nova e sem mexer em uma linha do que já existia.

### Como era antes

A superfície terminava no opcode 9 e o objeto só conhecia transporte de mensagens:

```kotlin
fun closeConnection(connectionId: Long) { require(connectionId > 0); call(7) { long(connectionId) } }
fun stop() { call(8) }

// Never grow a buffer containing the onion seed: BAOS growth would leave a retired copy.
private class Arguments : ByteArrayOutputStream(32 * 1024) {
```

### Como ficou

Sete funções acrescentadas **depois** de `stop()`, todas usando o mesmo `call(opcode) { ... }` com o
mesmo builder `Arguments` (buffer fixo, `wipe()` no `finally`, cópia dos argumentos zerada):

```kotlin
fun doorbellStart(seed32: ByteArray, stateDir: String, cacheDir: String, bridgeLines: Array<String> = emptyArray()): String
fun doorbellOnion(): String
fun doorbellTokens(tokens: List<ByteArray>)
fun doorbellPoll(timeoutMillis: Int): Int
fun doorbellMinimal()
fun doorbellStop()
fun doorbellKnock(onion: String, token: ByteArray): Boolean
```

Três constantes novas espelham os limites nativos (`native/src/doorbell.rs`), em vez de repetir
números soltos: `MIN_DOORBELL_TOKEN = 16`, `MAX_DOORBELL_TOKEN = 64`, `MAX_DOORBELL_TOKENS = 256`.

Detalhes que não são cosméticos:

- `doorbellStart` tem a **mesma forma de argumentos** de `start` (seed, stateDir, cacheDir, bridges),
  porque o nativo aceita construir o host Arti sozinho quando não há transporte de mensagens.
- `doorbellPoll` devolve `0` quando o retorno nativo vem vazio e, para o caso teórico de um `u32` que
  não cabe em `Int`, devolve `1` em vez de um número negativo: qualquer valor positivo significa
  "alguém tocou", que é tudo que o chamador precisa.
- `doorbellTokens` aceita lista vazia de propósito (ver `NoMessagesController.kt.md`).

### Vantagens

- Zero renumeração e zero mudança de formato nos opcodes 0-9: nenhum chamador existente muda.
- A disciplina de memória do arquivo continua valendo de graça para a seed da campainha e para os
  tokens, porque tudo passa pelo mesmo `call`/`Arguments` que já zera buffer e cópia.
- Os limites de token ficam em um único lugar, e o `TorService` os reusa para revalidar o que
  atravessa o Binder, em vez de repetir `16..64` em dois arquivos.
- Os KDocs registram a **ordem obrigatória** (`doorbellStart` antes de `doorbellMinimal`; `start`
  antes de `doorbellStop`) no lugar onde quem chamar vai ler, não só no plano.
