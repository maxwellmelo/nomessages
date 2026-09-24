# app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingEngine.kt

## 2026-09-14 — T2.3: outbox pausada enquanto o transporte não está ONLINE

### Como era antes

O transporte era uma dependência fixa de construção e sempre presente:

```kotlin
class MessagingEngine(
    ...
    private val directory: Path,
    private val transport: TorConnection,
    private val scope: CoroutineScope,
    ...
)
```

Os três laços de rede assumiam que ele existia e estava pronto:

```kotlin
connection = transport.send(item.destinationOnion, frames.first())
...
val result = withTimeoutOrNull(1_000) { transport.incoming.receiveCatching() } ?: continue
val incoming = result.getOrNull() ?: break
```

Consequências: enquanto o Tor não estava alcançável, a outbox continuava consultando o conjunto
pronto e tentando enviar. Cada tentativa gravava `attempts` e queimava o intervalo de 30 s por item
contra uma rede inexistente. E o `break` da linha do canal fechado matava o laço de recepção junto
com o primeiro processo `:tor` que morresse — sem reconexão possível, o que combinava com o
comportamento antigo de ERROR permanente.

### Como ficou

O transporte passou a ser propriedade do controlador, entregue e retirado em tempo de execução:

```kotlin
/**
 * The published transport, or null while the controller is still connecting or retrying. Every
 * network loop parks on null, so the outbox stays queued instead of burning its per-item
 * cooldown against a transport nobody can reach.
 */
@Volatile private var transport: TorConnection? = null

fun attachTransport(connection: TorConnection?) { transport = connection }
```

- `outboxLoop` lê o campo uma vez por volta e estaciona em 500 ms enquanto for nulo, antes de
  qualquer consulta ao banco; o envio usa a referência local `live`, então uma troca de transporte no
  meio de um tique não mistura duas conexões.
- `receiveLoop` estaciona em 200 ms com transporte nulo e, ao ver o canal fechado, passa a
  `continue` em vez de `break`: o processo filho morreu, mas o supervisor do controlador vai
  entregar um substituto, e o laço precisa continuar vivo para atendê-lo.
- Fechamento de fluxo (`closeStream`, manutenção de deadlines e `close()`) virou chamada segura a
  nulo; `close()` captura a referência viva e zera o campo antes de despachar os fechamentos
  pendentes.

### Vantagens

- Cumpre a entrega "outbox pausada até ONLINE" de T2.3: a mensagem composta offline continua
  `PENDING`, com a mesma durabilidade de antes, mas sem gastar sua janela de retentativa contra uma
  rede indisponível — quando o transporte chega, ela é a primeira a sair.
- O motor deixa de morrer junto com um processo `:tor`. Como `TorService` recusa um segundo START,
  cada tentativa de reconexão precisa de uma `TorConnection` nova; sem este ponto de troca, a
  reconexão de T2.3 seria impossível sem recriar a sessão inteira.
- Menos escrita em SQLCipher durante uma queda de rede: nenhuma linha `attempts` é reescrita
  enquanto a pausa dura.

### Por que a mudança foi feita

T2.3 do roteiro `docs/superpowers/plans/2026-09-14-roadmap-to-release.md` pede explicitamente
"`MessagingEngine.kt` (pausar outbox enquanto não `Running`)". A troca do parâmetro de construção
pelo par `attachTransport`/campo nulo é o mínimo que implementa a pausa **e** permite que o
supervisor substitua o processo filho, que é o que a reconexão exige.

## 2026-09-15 — Revisão T2.3: fechamento de stream deixa de derrubar os laços (P1)

### Como era antes

```kotlin
private suspend fun closeStream(connection: Long) {
    rememberClosed(connection)
    streamDeadlines.remove(connection)
    expectedPeers.remove(connection)
    transport?.close(connection)   // sem proteção
}
```

E, no varredor de streams expirados dentro de `start()`:

```kotlin
rememberClosed(entry.key)
transport?.close(entry.key)        // dentro de um launch/coroutineScope
```

Com o filho `:tor` morto, `TorConnection.close` chegava a um Binder morto e lançava
`DeadObjectException`. Como `closeStream` é chamado de dentro do `receiveLoop` (expiração de
assemblies, caminho de erro, ACK) e do `outboxLoop` (falha de envio), a exceção subia e matava o
laço **permanentemente**. O controller até criava um transporte novo e o anexava, mas não havia
mais nenhum laço lendo o campo — a reconexão de T2.3 ficava sem efeito visível.

### Como ficou

```kotlin
private suspend fun closeStream(connection: Long) {
    rememberClosed(connection)
    streamDeadlines.remove(connection)
    expectedPeers.remove(connection)
    closeQuietly(connection)
}

private suspend fun closeQuietly(connection: Long) {
    try { transport?.close(connection) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { }
}
```

O varredor de `start()` passou a usar o mesmo `closeQuietly`.

### Vantagens

- Um ponto único de teardown de stream, o que elimina a chance de um quarto chamador esquecer a
  proteção. `close()` já usava `runCatching` e continua como estava (roda em `NonCancellable`).
- Cancelamento real (o lock) continua propagando: usei `try/catch` explícito em vez de
  `runCatching` justamente porque `runCatching` engoliria `CancellationException` e faria o lock
  demorar mais para desmontar a sessão.
- Junto com `TorConnection.onDeath()` marcando `stopped`, são duas camadas: a chamada nem chega ao
  Binder morto, e se chegar (corrida entre a morte e a marcação) a exceção morre aqui.

### Por que a mudança foi feita

Achado P1 da revisão (`MessagingEngine.kt:796`): sem isso, a promessa central de T2.3 — "um
transporte que morre é substituído" — não se cumpre, porque o motor deixa de usar o substituto.

## 2026-09-16 — `sendAttachment` passa a retornar o id do arquivo (T4.8, mídia inline — parte A: áudio)

### Motivo

O remetente de uma mensagem de voz já calcula a forma de onda a partir do PCM bruto, antes de
enviar (ver `docs/changes/MemoryAudioRecorder.kt.md`). Para cachear essa forma de onda em
`MediaPreviewCache` contra o id correto (o mesmo id hex que `MessageUi.attachmentId` vai expor depois
de `refresh()`), o chamador precisa saber esse id assim que o envio termina — mas `sendAttachment`
gera o `fileId` internamente (`crypto.random(16)`) e não o devolvia.

### Como era antes

```kotlin
fun sendAttachment(chatId: String, name: String, mime: String, bytes: ByteArray) {
    ...
    val fileId = crypto.random(16)
    ...
    atomic { storeAttachment(envelope); sendApplication(chatId, envelope) }
    ...
}
```

### Como é agora

```kotlin
fun sendAttachment(chatId: String, name: String, mime: String, bytes: ByteArray): String {
    ...
    atomic { storeAttachment(envelope); sendApplication(chatId, envelope) }
    ...
    return fileId.toHexId()
}
private fun ByteArray.toHexId(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
```

A codificação hex é a mesma usada em `NoMessagesController.refresh()` para montar
`MessageUi.attachmentId` a partir de `Envelope.Attachment.fileId`, então a chave usada para cachear a
prévia casa exatamente com a chave que a bolha de áudio vai consultar depois.

### Vantagens

- Elimina uma redecifração desnecessária da própria mensagem só enviada: o remetente já tinha a
  forma de onda calculada, só faltava saber onde guardá-la.
- Mudança aditiva e compatível: os outros três chamadores existentes (`pickAttachment`,
  `capturePhoto`/`sendMediaBytes`) continuam ignorando o valor de retorno sem nenhuma alteração —
  Kotlin não exige que o resultado de uma chamada seja consumido.
- Não altera nada do formato de fio (`Envelope`/`EnvelopeCodec`, T4.8 proíbe explicitamente tocar
  neles): é só a assinatura Kotlin de uma função de runtime que passou a devolver um valor que já
  existia internamente.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

## 2026-09-17 — `forwarded` em `sendText`/`sendAttachment` e na persistência (Parte B: Encaminhar)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). O motor é quem monta o
envelope e quem grava a linha de `messages`; ele precisava aceitar o bit de encaminhamento no envio e
propagá-lo para os dois lados (wire e banco), tanto no envio quanto na recepção.

### Como era antes

```kotlin
fun sendText(chatId: String, text: String) {
    require(text.isNotBlank()) { "Digite uma mensagem" }
    atomic { sendApplication(chatId, Envelope.Text(id(), now(), text)) }
}

fun sendAttachment(chatId: String, name: String, mime: String, bytes: ByteArray): String {
    ...
    val envelope = Envelope.Attachment(id(), now(), name, mime, fileId, session.epoch, key, ciphertext.toByteArray())
    ...
}

// sendApplication, no final:
db.insertMessage(MessageRecord(envelope.id, chatId, MessageDirection.OUTGOING, envelope.timestamp,
    history(envelope), if (...) MessageStatus.DELIVERED else MessageStatus.PENDING))

// handle(sender, group, envelope):
is Envelope.Text, is Envelope.Attachment -> {
    if (envelope is Envelope.Attachment) storeAttachment(envelope)
    db.insertMessage(MessageRecord(envelope.id, group?.id ?: sender, MessageDirection.INCOMING,
        envelope.timestamp, history(envelope), MessageStatus.DELIVERED))
}
```

### Como é agora

```kotlin
fun sendText(chatId: String, text: String, forwarded: Boolean = false) {
    require(text.isNotBlank()) { "Digite uma mensagem" }
    atomic { sendApplication(chatId, Envelope.Text(id(), now(), text, forwarded = forwarded)) }
}

fun sendAttachment(chatId: String, name: String, mime: String, bytes: ByteArray, forwarded: Boolean = false): String {
    ...
    val envelope = Envelope.Attachment(id(), now(), name, mime, fileId, session.epoch, key, ciphertext.toByteArray(), forwarded = forwarded)
    ...
}

/**
 * The `forwarded` bit of a chat envelope, or false for every control envelope [...]
 */
private fun envelopeForwarded(envelope: Envelope): Boolean = when (envelope) {
    is Envelope.Text -> envelope.forwarded
    is Envelope.Attachment -> envelope.forwarded
    else -> false
}

// sendApplication, no final (sétimo argumento):
db.insertMessage(MessageRecord(..., MessageStatus.PENDING, envelopeForwarded(envelope)))

// handle(...), ramo de mensagem de conversa:
db.insertMessage(MessageRecord(..., MessageStatus.DELIVERED, envelopeForwarded(envelope)))
```

### Decisões

- **Parâmetro com default `false`, no fim da assinatura.** Todos os chamadores existentes
  (`NoMessagesController.sendText`, `acceptAttachment`, `acceptPhoto`, o fluxo de áudio) continuam
  compilando sem alteração — só o novo `forwardMessage` passa `forwarded = true`.
- **`envelopeForwarded` como fonte única.** O banco e o envelope persistido em `body` são
  preenchidos a partir da **mesma** expressão. Não existe caminho em que a coluna e o envelope
  discordem — o que é importante porque `NoMessagesController.refresh()` lê a coluna e a bolha da
  Parte C vai desenhar o rótulo a partir dela.
- **`else -> false`, não `error(...)`.** Os envelopes de controle (Ack, Evidence, a família
  MLS/key-package) não têm o conceito e nunca chegam a `insertMessage`; um `when` exaustivo com
  ramos individuais para cada um só adicionaria ruído.
- **`history(envelope)` não precisou mudar.** Ele faz
  `envelope.copy(ciphertext = byteArrayOf(), fileKey = ByteArray(32))`, e `copy()` de data class
  preserva `forwarded` automaticamente. O que é persistido em `body` já sai com o bit certo.
- **Encaminhar sempre marca `true`, sem encadeamento.** Quem encaminha passa `forwarded = true`
  independentemente de a mensagem original já ser encaminhada ou não. Não há contador "encaminhada N
  vezes" nesta versão — e não haverá sem uma decisão explícita, porque contar exigiria carregar
  histórico de proveniência no envelope.

### Pausa da outbox: nada foi implementado, e é de propósito

`sendText`/`sendAttachment` → `sendApplication` → `queue()` → tabela `outbox`. Quem drena essa fila é
`outboxLoop()`, que já pausa sozinho quando não há transporte:

```kotlin
val live = transport
if (live == null) { delay(500); continue }
```

Encaminhar reaproveita esse caminho inteiro, sem nenhum atalho. Logo, com o Tor fora do ar, uma
mensagem encaminhada simplesmente fica `PENDING` na fila e sai quando o transporte voltar —
exatamente como qualquer outra mensagem. Nenhum código novo de pausa foi (nem deveria ser) escrito.

### Vantagens

- Encaminhar não é um caminho de rede novo: é o caminho de envio existente com um bit ligado, então
  herda de graça a fila, as tentativas, os ACKs, as faixas (lanes) e o comportamento offline.
- Um anexo encaminhado é re-encriptado com `fileId` e chave de arquivo novos (consequência de
  `sendAttachment` sempre gerar os dois), então nada liga a cópia à conversa de origem.


## 2026-09-17 — Fluxo de fetch de bundle sobre Tor no motor de mensagens (QR formato 2, T4.16)

### Motivo

O formato 1 do QR de pareamento carregava o bundle PQXDH completo (chave Kyber-1024 obrigatória do
libsignal) dentro do próprio QR: ~2950 bytes, QR versão 40 nível L, 177 módulos por lado — ilegível
pela câmera de um Galaxy Note10+ real mesmo preenchendo um monitor inteiro
(`docs/development/protocol-report.md`, item 2). A decisão (`docs/development/doorbell-design.md`
e `core/.../protocol/Pairing.kt`) foi tirar o bundle do QR e buscá-lo sobre Tor, autenticado por um
hash SHA-256 comprometido dentro da parte assinada em Ed25519 do QR. `MessagingEngine` é quem abre e
atende essa busca, porque é o único componente com acesso ao `TorConnection` publicado.

### Como era antes

`MessagingEngine` não tinha nenhum conceito de pareamento: toda a troca de chaves acontecia dentro
do próprio QR, sem tráfego de rede associado. Não havia `bundleLimiter`, `bundleWaiters`,
`pairingBundles`, `fetchPeerBundle`, `answerBundleRequest`, nem `PacketKind.BUNDLE`.

### Como ficou

Quatro peças novas, na ordem em que a troca as usa.

**1. `attachPairingBundles` — a fonte de respostas**

```kotlin
/**
 * Installs (or clears) the source of answers to an incoming pairing [Envelope.BundleRequest].
 *
 * The lambda is invoked from [accept], i.e. already under the engine mutex the controller owns,
 * which is the same lock its `PairingEngine` is mutated under - so the pairing state it reads
 * cannot change underneath it.
 */
fun attachPairingBundles(source: ((ByteArray) -> ByteArray?)?) {
    pairingBundles = source
}
```

`NoMessagesController` instala aqui um lambda que delega a `PairingEngine.bundleFor(nonce)`. É
`@Volatile` porque pode ser lido pelo laço de recepção fora do mutex do controller (ver seção sobre
o mutex mais abaixo) — mas a leitura em si é sempre feita dentro de `accept`, que já roda sob o
mutex do motor.

**2. `transportAttached` — pré-condição para tentar buscar**

```kotlin
/** True once a reachable transport has been published, i.e. a Tor fetch can be attempted. */
val transportAttached: Boolean get() = transport != null
```

O controlador consulta esta propriedade antes de cada tentativa de `fetchPeerBundle`, para
diferenciar "meu próprio Tor ainda não está pronto" (`WAITING_FOR_TOR`) de "o outro aparelho não
respondeu" (`FETCHING` que expira) — ver `docs/changes/NoMessagesController.kt.md`.

**3. `fetchPeerBundle` — o lado que pergunta, e por que roda sem o mutex**

```kotlin
suspend fun fetchPeerBundle(onion: String, nonce: ByteArray, timeoutMillis: Long): ByteArray {
    check(!closed) { "Cofre bloqueado" }
    require(nonce.size == EnvelopeLimits.PAIRING_NONCE_BYTES)
    require(timeoutMillis > 0)
    val live = transport ?: error("Rede Tor indisponível")
    val request = EnvelopeCodec.encode(Envelope.BundleRequest(id(), now(), nonce))
    val frames = try { FrameCodec.encode(WirePacket.encodeBundle(request)) } finally { request.fill(0) }
    val waiter = CompletableDeferred<ByteArray>()
    var connection: Long? = null
    try {
        connection = live.send(onion, frames.first())
        bundleWaiters[connection] = waiter
        for (index in 1 until frames.size) live.reply(connection, frames[index])
        val wire = withTimeoutOrNull(timeoutMillis) { waiter.await() }
            ?: error("O outro aparelho não respondeu pela rede Tor")
        ...
        return envelope.bundle
    } finally {
        connection?.let { bundleWaiters.remove(it); closeStream(it) }
        frames.forEach { it.fill(0) }
    }
}
```

`fetchPeerBundle` é deliberadamente **`suspend` solto**, sem `atomic { }` e sem tomar o mutex do
motor em nenhum ponto. A razão não é estilo: é a única forma de evitar um deadlock estrutural entre
dois aparelhos.

Considere os dois lados do pareamento: A leu o QR de B e chama `fetchPeerBundle` contra o onion de
B; ao mesmo tempo, B leu o QR de A e chama `fetchPeerBundle` contra o onion de A. Cada chamada
suspende por um circuito Tor que tipicamente leva 5–40 s (às vezes mais). Enquanto A está suspenso
esperando a resposta de B, o pacote `BUNDLE` que B enviou para A precisa ser **atendido** por A —
e atendê-lo (via `answerBundleRequest`, chamado de dentro de `accept`) roda sob o **mesmo mutex**
que o controlador usa para orquestrar `PairingEngine`. Se `fetchPeerBundle` tivesse tomado esse
mutex antes de suspender, o pedido de B chegando em A ficaria bloqueado esperando A liberar o
mutex — e A só libera o mutex depois que a própria chamada retornar, que por sua vez está esperando
B responder, que por sua vez está bloqueado esperando A. Dois aparelhos que se escanearam ao mesmo
tempo (o caso normal, não o excepcional) travariam sempre. `fetchPeerBundle` só toca o mutex
**depois** de já ter os bytes em mãos, e mesmo assim é o chamador (`NoMessagesController`) quem
decide isso — o motor em si nunca pede o lock aqui.

O retorno é o bundle bruto, sem qualquer verificação de confiança: quem verifica o hash contra o
que o par assinou é `PairingEngine.acceptPeerBundle`, chamado pelo controlador depois do retorno.
`fetchPeerBundle` não concede confiança nenhuma — só transporta bytes e os devolve para quem sabe
verificá-los.

**4. `bundleWaiters` — por que a resposta não passa por `accept`**

```kotlin
private val bundleWaiters = ConcurrentHashMap<Long, CompletableDeferred<ByteArray>>()
```

No `receiveLoop`, antes de qualquer wire completo ir para `accept`:

```kotlin
val waiter = bundleWaiters.remove(incoming.connection)
if (waiter != null) {
    waiter.complete(wire.copyOf())
    closeStream(incoming.connection)
    continue
}
val ack = mutex.withLock { if (closed) null else accept(wire, expectedPeers[incoming.connection]) }
```

Um `BundleResponse` não pertence a nenhuma sessão Signal — não existe ratchet, não existe contato
registrado, não existe nada contra o qual `accept` possa autenticá-lo. `accept` é o caminho de
mensagens **autenticadas** (Signal/MLS) mais o caminho especial e deliberadamente aberto de
`BUNDLE` de entrada (pedidos, não respostas). Uma resposta que o próprio `fetchPeerBundle` está
esperando é correlacionada por número de conexão (`connection`, a stream que este dispositivo abriu
com `live.send`) e entregue direto ao `CompletableDeferred` que está bloqueado em `waiter.await()`
— sem decodificar de novo, sem tocar em `accept`, sem tocar no banco.

### O risco residual assumido conscientemente

`bundleWaiters[connection] = waiter` só é registrado **depois** que `live.send(...)` retorna. Entre
o retorno de `send` e essa linha, existe uma janela — por mais estreita que seja — em que uma
resposta já chegada cairia em `accept` (porque `connection` ainda não está no mapa) e seria
descartada ali. Na prática essa janela é impossível de ser vencida sobre Tor: a resposta do par só
existe depois de uma ida-e-volta completa de rede sobre um circuito recém-construído — ordens de
grandeza mais lenta que o tempo entre `send` retornar e a linha seguinte executar em processo local.
A consequência de perder essa corrida, se algum dia acontecer, não é um resultado errado: é um
timeout (`withTimeoutOrNull` expira) seguido do retry visível na tela que
`NoMessagesController.startBundleFetch` já implementa. Registrado aqui, e não corrigido, porque
fechar essa janela exigiria reordenar `send`/registro de forma que mudaria a API de `TorConnection`
para um ganho que nenhum cenário real materializa.

**5. `answerBundleRequest` — o lado que responde, e por que silêncio**

```kotlin
private fun answerBundleRequest(payload: ByteArray): ByteArray? {
    if (payload.size > maxBundlePayload) return null
    val source = pairingBundles ?: return null
    if (!bundleLimiter.admit()) return null
    val envelope = try { EnvelopeCodec.decode(payload) } catch (_: IllegalArgumentException) { return null }
    if (envelope !is Envelope.BundleRequest) return null
    val bundle = source(envelope.nonce) ?: return null
    val response = EnvelopeCodec.encode(Envelope.BundleResponse(id(), now(), envelope.nonce, bundle))
    return try { WirePacket.encodeBundle(response) } finally { response.fill(0); bundle.fill(0) }
}
```

Qualquer motivo de recusa — payload grande demais, nenhuma tela de pareamento aberta
(`pairingBundles == null`), token bucket esgotado, envelope que não decodifica, nonce desconhecido
(`source(nonce) == null`) — devolve `null`, e `null` vira **silêncio**: nenhum pacote de resposta é
enviado. Isto é proposital. Um `BUNDLE` de entrada é o único pacote que um estranho não pareado pode
legitimamente mandar para este onion, então esse onion está exposto a sondagens de nonce aleatório
de quem quer que descubra o endereço. Se a resposta a um nonce desconhecido fosse um envelope de
erro, esse erro em si seria a confirmação de que "este onion está com uma tela de pareamento aberta
agora" — um oráculo binário que um nonce-probe puro e simples poderia usar para varrer onions
suspeitos. Silêncio é indistinguível de "esse endereço não existe" ou "está fora do ar".

### `accept` — BUNDLE tratado antes de qualquer coisa que toque o cofre

```kotlin
private fun accept(wire: ByteArray, expectedPeer: String?): ByteArray? {
    val packet = WirePacket.decode(wire)
    // Answered before the receipt cache and before `atomic`: a pairing bundle request touches no
    // database row, no ratchet and no identity export, so it must not open a vault transaction -
    // it is the one packet an unpaired stranger can legitimately send.
    if (packet.kind == PacketKind.BUNDLE) return answerBundleRequest(packet.payload)
    val hash = hex(crypto.hash(wire))
    db.getBlob("receipts", hash)?.let { return it.takeIf { bytes -> bytes.isNotEmpty() } }
    val packet = WirePacket.decode(wire)
    require(trialLimiter.admit(packet.kind, expectedPeer != null)) { "Transporte ocupado" }
    ...
```

Antes desta mudança, `WirePacket.decode` só acontecia depois da consulta ao cache de recibos
(`db.getBlob("receipts", hash)`). Agora `decode` acontece primeiro justamente para poder desviar um
`BUNDLE` **antes** de tocar o banco. O motivo é o mesmo da seção anterior, visto do lado do
atendimento: um `BUNDLE` de um estranho não pareado não deve abrir nenhuma transação de cofre
(`atomic { }`), não deve consultar nem gravar o cache de recibos, não deve avançar nenhum ratchet e
não deve exportar nenhum material de identidade. É o único tipo de pacote com essa propriedade —
todos os outros (`SIGNAL`, `MLS`) pressupõem uma sessão já estabelecida e passam pelo caminho normal
de recibo + limiter + `atomic`.

Um efeito colateral notado, não corrigido porque é irrelevante: `WirePacket.decode(wire)` passou a
rodar duas vezes no caminho não-BUNDLE (uma vez no topo, outra na linha original mais abaixo,
mantida). É uma decodificação extra de um cabeçalho de poucos bytes, não uma segunda passada pelo
payload inteiro — custo desprezível frente à clareza de "o desvio de BUNDLE acontece o mais cedo
possível".

### O `when` de `PacketKind` — o ramo `BUNDLE` inalcançável, escrito por extenso

```kotlin
// Unreachable: `accept` returns on a BUNDLE packet before opening this transaction.
// Spelled out rather than folded into an `else` so that adding a third real packet
// kind is a compile error here instead of a silent fall-through into "unauthenticated".
PacketKind.BUNDLE -> error("Pacote de pareamento fora do caminho de pareamento")
```

Este ramo, dentro do `when (packet.kind)` que trata pacotes já autenticados, nunca executa — `accept`
já retornou antes de chegar aqui para qualquer `BUNDLE`. Ele poderia ter sido omitido (Kotlin não
exige exaustividade contra um `else` presente) ou dobrado dentro de um `else -> ...` genérico. Foi
escrito por extenso, com `error(...)`, de propósito: `PacketKind` é um enum fechado dentro deste
módulo, e se um dia um terceiro tipo de pacote real for adicionado (tag 4 em diante), um `when`
exaustivo por `PacketKind` explícito obriga o compilador a apontar exatamente este arquivo como um
lugar que precisa de uma decisão — em vez de um `else` silencioso que trataria o pacote novo como
"não autenticado, cai no branch genérico", que seria o comportamento errado por padrão para
qualquer tipo de pacote que não seja `BUNDLE`.

### `handle()` — rejeição se um par empacotar `BundleRequest`/`BundleResponse` no ratchet

```kotlin
// Pairing bundle traffic is answered in `accept` before any session lookup and is
// never queued into a Signal lane, so reaching `handle` means a paired peer wrapped one
// in its ratchet - which no honest build does.
is Envelope.BundleRequest, is Envelope.BundleResponse ->
    throw IllegalArgumentException("Envelope de pareamento fora do fluxo de pareamento")
```

`handle` processa envelopes já decifrados por uma sessão Signal estabelecida — ou seja, um contato
já pareado. Tráfego de pareamento nunca chega aqui pelo caminho normal: ele é interceptado em
`accept`, antes de qualquer sessão existir, e nunca é enfileirado numa lane Signal. Se este ramo
alguma vez executar, significa que um par já pareado escolheu embrulhar um `BundleRequest` ou
`BundleResponse` dentro do próprio ratchet estabelecido — algo que nenhuma build honesta deste app
faz, mas que um cliente adversarial poderia tentar para ver como o motor reage. A resposta é rejeitar
explicitamente, e não silenciosamente ignorar, porque aqui a mensagem já está autenticada (veio de
dentro de uma sessão Signal válida) — o silêncio que se aplica a um `BUNDLE` de estranho não se
aplica a um abuso de protocolo vindo de alguém já pareado.

### `maxBundlePayload`

```kotlin
/**
 * Ceiling for a `PacketKind.BUNDLE` payload in either direction. A BundleResponse is
 * ~1.9 KB (a 1832-byte bundle plus envelope header); 8 KiB leaves room without letting an
 * unauthenticated stream push megabytes at the envelope decoder.
 */
private val maxBundlePayload = 8 * 1024
```

O bundle PQXDH codificado tem ~1832 bytes (`SignalBundle`, `MAX_ENCODED_BYTES = 4096` em
`SignalSessions.kt`); com o cabeçalho de envelope, uma `BundleResponse` real fica perto de 1,9 KB.
O teto de 8 KiB dá folga confortável sem abrir a porta para um estranho não autenticado empurrar
megabytes contra o decodificador de envelope — lembrando que este é justamente o único tipo de
pacote que chega **antes** de qualquer verificação de identidade.

### `close()` — limpeza de waiters e da fonte de bundle

```kotlin
override fun close() {
    closed = true
    pairingBundles = null
    bundleWaiters.values.forEach { it.cancel() }
    bundleWaiters.clear()
    jobs.forEach { it.cancel() }
    jobs.clear()
    ...
```

Ao fechar o motor, `pairingBundles` é zerado (nenhuma resposta a `BUNDLE` de entrada depois disso —
`answerBundleRequest` já checaria `closed` de qualquer forma pela ausência de `transport`, mas
zerar a fonte é uma segunda barreira explícita) e todo `CompletableDeferred` ainda pendente em
`bundleWaiters` é cancelado antes de o mapa ser limpo, para que nenhuma corrotina fique presa para
sempre em `waiter.await()` de uma busca cuja sessão foi encerrada por baixo dela.

### Vantagens

- Fecha a lacuna que motivou o formato 2 inteiro: o QR volta a ser legível por câmera real (ver
  `docs/changes/Pairing.kt.md`), sem abrir mão da vinculação identidade↔onion↔bundle — o hash
  continua dentro da parte assinada.
- O deadlock estrutural entre dois aparelhos que se escaneiam ao mesmo tempo é evitado por
  construção (`fetchPeerBundle` nunca segura o mutex do motor enquanto suspende), não por sorte de
  timing.
- Um estranho não pareado tem exatamente um pacote que pode enviar e uma única superfície de
  resposta (silêncio ou uma `BundleResponse` legítima) — nunca alcança `atomic { }`, o ratchet ou
  qualquer exportação de identidade.
- O `when` exaustivo por `PacketKind` transforma "esqueci de decidir o que fazer com um tipo de
  pacote novo" em erro de compilação, não em comportamento silenciosamente incorreto.

### Validação

Ver `docs/development/T4.16` (fact sheet da tarefa): `BUILD SUCCESSFUL` para
`:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
(2026-09-17). `:app:testDebugUnitTest` — 60 testes, 0 falhas, 0 erros, 1 pulado. Nenhuma validação
em aparelho/emulador foi executada para esta tarefa.

---

## 2026-09-18 — Campainha, fase 4: o motor toca a campainha de quem não recebe (T4.17)

### Como era antes

`outboxLoop` tratava **toda** falha de envio da mesma forma, sem registrar nada sobre o destino:

```kotlin
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { connection?.let { closeStream(it) } }
finally { item.frame.fill(0); frames.forEach { it.fill(0) } }
```

Consequências:

- Não existia **nenhum** sinal no motor dizendo "o onion deste par está inalcançável". A única
  reação a uma falha era fechar o stream e deixar o item na fila; o próximo tique tentava de novo
  depois do intervalo fixo de 30 s (`lastAttempt`), indefinidamente.
- Os campos `ContactRecord.doorbellOnion` e `ContactRecord.doorbellToken` (fase 2) e o método
  `TorConnection.doorbellKnock` (fase 3) não tinham nenhum chamador: a campainha existia inteira,
  do Rust ao IPC, e nunca era tocada.
- Um par desligado nunca era avisado de que havia fila para ele. A mensagem ficava parada até que
  ele, por conta própria, abrisse o app.

### Como ficou

O `catch` passou a ser o gatilho do toque, e o caminho de sucesso passou a ser o sinal de vida:

```kotlin
mutex.withLock {
    if (!closed) {
        val messageId = item.id.substringBefore(':')
        if (db.getMessage(messageId)?.status == MessageStatus.PENDING) db.updateMessageStatus(messageId, MessageStatus.SENT)
        // The frame reached this peer's messaging onion, so it is awake:
        // the doorbell schedule drops back to its floor (T4.17).
        clearKnockFailures(item.id.substringAfter(':'))
        onChanged()
    }
}
// The incoming worker closes the stream after the authenticated ACK.
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) {
    connection?.let { closeStream(it) }
    // Every delivery failure is a doorbell candidate. Nothing on this path
    // distinguishes "the peer's onion is unreachable" from any other
    // transient error - `live.send` reports one opaque failure - so the
    // faithful reading of what the outbox knows is "this destination did
    // not take the frame". Over-ringing is bounded by the schedule in
    // `DoorbellKnockPolicy`, while under-ringing would defeat the feature.
    ringDoorbell(item.id.substringAfter(':'))
}
```

E o `Ack` autenticado, em `handle`, também zera o contador de falhas:

```kotlin
is Envelope.Ack -> {
    // An authenticated acknowledgement is the strongest possible sign of life from this
    // peer, so its doorbell schedule returns to the floor (T4.17).
    clearKnockFailures(sender)
    db.acknowledgePendingFrame("...:$sender")
```

O ciclo completo de um toque ficou em cinco membros privados novos, mais o registro de jobs:

| Membro | Papel |
| --- | --- |
| `knocks: ConcurrentHashMap<String, Job>` | Um toque em voo por contato; é o próprio guarda de concorrência |
| `ringDoorbell(peer)` | Dispara (ou recusa) um toque, sem bloquear a outbox |
| `knock(peer)` | A tentativa inteira: decidir, registrar, tocar, registrar o resultado |
| `prepareKnock(peer, now)` | Sob o mutex: valida o contato, aplica a política e **grava a tentativa antes de ela acontecer** |
| `recordKnock(peer, ok)` | Sob o mutex: só mexe no contador de falhas, preservando o carimbo |
| `clearKnockFailures(peer)` | Zera o contador quando o par dá sinal de vida por qualquer via |

A política pura de agendamento ficou em arquivo próprio — ver
`docs/changes/DoorbellKnockPolicy.kt.md`.

### Por que "qualquer falha de envio = candidato a toque"

O motor **não sabe** por que um envio falhou. `TorConnection.send` devolve uma única falha opaca
(`IllegalStateException("Transporte indisponível")`, `DeadObjectException`, estouro do
`withTimeout(45_000)`, erro do lado nativo…) e o `catch` da outbox sempre foi genérico. Criar aqui
uma distinção "onion inalcançável" × "erro transitório" exigiria expor um motivo em
`TorConnection`/`TorService` — fora do escopo desta fase, que não pode tocar nesses arquivos.

Diante disso, a leitura mais fiel do que a outbox realmente sabe é: **este destino não aceitou o
frame**. As duas assimetrias justificam a escolha:

- Tocar demais é **limitado por construção**: no máximo um toque por contato a cada 10 min, e
  menos que isso durante uma ausência longa (backoff). Um erro transitório que gere um toque extra
  custa, no pior caso, uma tentativa a cada dez minutos.
- Tocar de menos **anula a feature**: se o critério fosse mais estrito, o caso principal (par com o
  aparelho desligado) poderia nunca ser reconhecido, já que é justamente o caso em que o erro chega
  como um timeout genérico.

A decisão está registrada como comentário no próprio `catch`, para que ninguém a leia como descuido.

### Curva de backoff escolhida

10 min → 20 min → 40 min → 80 min → **teto de 120 min**, em `DoorbellKnockPolicy.BACKOFF_MILLIS`.

- O primeiro degrau **é** o piso exigido ("no máximo 1 toque por contato a cada 10 min") e também o
  valor para o qual um toque **confirmado** volta: um par que respondeu pode ser acordado barato.
- A duplicação existe para o caso que motiva a campainha — um aparelho desligado por horas. Cada
  toque custa até 240 s de construção de circuito (`TorConnection.doorbellKnock`) e revela a quem
  observa o onion de campainha que alguém tem tráfego para o dono dele; repetir isso a cada 10 min
  durante uma noite inteira seria caro e barulhento.
- O teto de 2 h é o compromisso: um remetente que ficou tocando a madrugada inteira ainda acorda o
  par dentro de um teto depois que ele volta, em vez de convergir para um atraso maior que a própria
  ausência (é a mesma forma do `TransportSupervisor`: degraus crescentes com teto explícito).

### Namespace e formato exato em `opaque_blobs`

| Campo | Valor |
| --- | --- |
| Namespace | `doorbell_knocks` (novo; nenhum dos 14 catalogados em `runtime-catalog.md` nem o `vault_settings` da fase 3 colidem) |
| Chave | `ContactRecord.id` — o identificador estável do contato (hex da identidade), **nunca** `alias`, que o usuário pode renomear |
| Valor | 12 bytes fixos, big-endian: `long` (8 B) com o epoch em milissegundos do **último toque tentado** + `int` (4 B) com o número de falhas consecutivas |
| Escreve | `ME.prepareKnock` (tentativa), `ME.recordKnock` (resultado), `ME.clearKnockFailures` (sinal de vida) |
| Lê | `ME.knockState` |
| Apaga | nunca — o registro é sobrescrito, nunca removido |
| No decoy? | Não nasce com o cofre; é criado sob demanda, igual nos dois cofres |

Três decisões de formato merecem registro:

1. **O carimbo é da tentativa, não do sucesso.** É gravado em `prepareKnock`, antes de
   `doorbellKnock` rodar. Se fosse gravado só no sucesso, o caso principal (par desligado ⇒
   campainha dele também inalcançável) não teria limite nenhum: cada tique da outbox abriria um
   toque novo de 240 s.
2. **Um blob só, não dois.** Carimbo e contador andam juntos em toda decisão, e uma escrita única
   não pode ficar meio gravada.
3. **Um registro ilegível vale como "nunca tocado".** `decode` devolve `null` para tamanho
   inesperado e satura contador negativo em zero: um blob corrompido não pode silenciar a campainha
   de um contato para sempre.

### Como a concorrência é evitada

Três camadas, nesta ordem:

1. **Um job por contato, registrado antes de existir.** `ringDoorbell` cria o job com
   `CoroutineStart.LAZY`, faz `knocks.putIfAbsent(peer, job)` e só então `job.start()`. Duas falhas
   simultâneas para o mesmo par não podem passar as duas pelo teste "já estou tocando?": o perdedor
   cancela um job que nunca executou uma linha. (`computeIfAbsent` foi evitado de propósito: o job
   remove a própria entrada ao terminar, o que reentraria no mapa de dentro da função de mapeamento.)
2. **A decisão acontece sob o mutex do motor.** `prepareKnock` lê o contato, lê o agendamento e
   grava a tentativa dentro do mesmo `mutex.withLock`, então dois toques não podem ler o mesmo
   "último toque" e ambos concluir que estão liberados.
3. **O toque não bloqueia a outbox.** O job roda no `scope` do motor, não dentro do worker de envio.
   A outbox admite quatro workers e no máximo um por par; esperar 240 s lá dentro travaria a fila
   inteira daquele contato — inclusive a retentativa que o toque existe para fazer dar certo.

`close()` cancela todo toque em voo (`knocks.values.forEach { it.cancel() }`) antes de cancelar os
laços, e todas as escritas finais são condicionadas a `if (!closed)`, para que um cofre bloqueado no
meio de um toque não escreva em um banco já fechado.

### Uma falha nossa não conta como falha do par

`TorConnection.alive` (já existente, só lido aqui) separa os dois casos depois do toque:

```kotlin
// A knock that failed while this device's own transport was dying says nothing about
// the peer, so it does not stretch that peer's schedule. The attempt stamp written by
// `prepareKnock` stays, so a local outage still cannot produce a knock storm.
if (!acknowledged && !live.alive) return
```

Se o processo `:tor` **deste** aparelho morreu no meio do toque, o resultado não diz nada sobre o
par: o contador de falhas dele não avança e a curva não estica à toa. O carimbo da tentativa, esse,
permanece — uma queda local nossa também não pode virar uma rajada de toques.

Ainda assim, uma queda local *que não mate o processo filho* (rede do aparelho fora do ar com o
transporte ainda vivo) conta como falha do par e estica a curva dele indevidamente. É recuperável
sozinho: a primeira entrega bem-sucedida, ou o primeiro `Ack`, zera o contador. Está registrado
como pendência conhecida desta fase.

### Higiene de segredo

`prepareKnock` devolve uma **cópia** de `doorbellToken` e zera, no `finally`, as duas cópias vindas
do `ContactRecord` (`doorbellToken` e `doorbellTokenIssued`, que o motor não usa mas recebe junto).
A cópia entregue ao knock é zerada no `finally` do próprio `doorbellKnock`, e todo blob codificado é
zerado após a gravação — o mesmo padrão já aplicado a chaves de arquivo e estado MLS neste arquivo.

Nota de leitura, porque a confusão seria silenciosa e grave: o que vai no toque é
`ContactRecord.doorbellToken` (o token que **o contato emitiu para este aparelho**), nunca
`doorbellTokenIssued` (o que **este aparelho emitiu** e serve para reconhecer batidas recebidas).

### Vantagens

- Fecha o ciclo da feature: o que as fases 1–3 construíram (opcode nativo, colunas, ponte IPC)
  passa a ter um chamador real, guiado por um sinal que o motor já produzia e jogava fora.
- O limite de taxa vale **inclusive quando o toque falha**, que é o caso comum — sem isso, um par
  desligado geraria um toque de 240 s a cada 30 s de fila.
- A decisão de agendar é uma função pura, testável sem Android, sem Tor, sem banco e sem relógio de
  parede (13 casos JUnit novos, ver `docs/changes/DoorbellKnockPolicyTest.kt.md`).
- A contenção de concorrência é estrutural (registro antes de iniciar + decisão sob mutex), não uma
  questão de sorte de timing.
- Contatos pareados antes da feature são pulados em silêncio, sem erro e sem log: a ausência das
  colunas é "não combinamos campainha", não uma falha.

### Validação

WSL, `--offline`, `JAVA_HOME=~/nomessages-tools/jdk-21`, `GRADLE_USER_HOME=~/nomessages-tools/gradle-home`:

- `:app:compileDebugKotlin` — `BUILD SUCCESSFUL in 1m 31s`.
- `:app:testDebugUnitTest` (suíte inteira do módulo `app`) — `BUILD SUCCESSFUL in 1m 33s`;
  **74 testes, 0 falhas, 0 erros, 1 pulado**. O pulado é pré-existente e sem relação com esta
  mudança (`QrDecodingTest`, "640x480 frame is below the recommended pixels-per-module for a
  version-40-L QR"). Os 13 casos novos são de `DoorbellKnockPolicyTest`.

Nenhuma validação em aparelho/emulador foi executada nesta fase: o toque de verdade depende de dois
aparelhos e da campainha ao vivo, e fica para a fase de validação E2E.

## 2026-09-23 — Falhas de usuário passam a lançar `MessagingError` com código, não texto PT cru (T4.6)

### Motivo

Ver `docs/changes/MessagingError.kt.md` para o achado de lint completo e o critério de quais
`require`/`error` viraram código. Resumo: uma dúzia de literais em português (`"Digite uma
mensagem"`, `"Arquivo indisponível"` etc.) eram lançados como mensagem de exceção crua e descartados
por todo catch em `NoMessagesController`, deixando `strings.xml` com ~20 recursos `error_*` sem
chamador (`UnusedResources`).

### Como era antes

```kotlin
fun sendText(chatId: String, text: String, forwarded: Boolean = false) {
    require(text.isNotBlank()) { "Digite uma mensagem" }
    ...
}
fun decryptAttachment(fileId: String): Pair<FileRecord, ByteArray> {
    val record = db.getFile(fileId) ?: error("Arquivo indisponível")
    val key = crypto.open(...) ?: error("Arquivo inválido")
    ...
}
private fun contact(peer: String): ContactRecord = db.getContact(peer)?.also {
    require(!it.displayOnly) { "Contato indisponível para envio" }
} ?: error("Contato não pareado")
```

### Como ficou

Dois auxiliares novos (`fail`/`requireCode`) substituem o literal por um `MessagingErrorCode`:

```kotlin
private fun fail(code: MessagingErrorCode): Nothing = throw MessagingError(code, code.name)
private fun requireCode(condition: Boolean, code: MessagingErrorCode) { if (!condition) fail(code) }

fun sendText(chatId: String, text: String, forwarded: Boolean = false) {
    requireCode(text.isNotBlank(), MessagingErrorCode.EMPTY_MESSAGE)
    ...
}
fun decryptAttachment(fileId: String): Pair<FileRecord, ByteArray> {
    val record = db.getFile(fileId) ?: fail(MessagingErrorCode.FILE_UNAVAILABLE)
    val key = crypto.open(...) ?: fail(MessagingErrorCode.FILE_INVALID)
    ...
}
private fun contact(peer: String): ContactRecord = db.getContact(peer)?.also {
    requireCode(!it.displayOnly, MessagingErrorCode.CONTACT_UNAVAILABLE)
} ?: fail(MessagingErrorCode.CONTACT_UNPAIRED)
```

Doze call sites mudaram no total: `sendText` (`EMPTY_MESSAGE`), `sendAttachment`
(`FILE_TOO_LARGE`), `decryptAttachment` (`FILE_UNAVAILABLE`/`FILE_INVALID`), `removeMemberInternal`
(`NO_OTHER_MEMBERS`), `sendApplication` (`GROUP_SYNC_FAILED`/`GROUP_WAITING_ONLINE`/`LEFT_GROUP`),
`cancelPendingGroup`/`activeGroup` (`GROUP_UNAVAILABLE`, mais os dois de cima reaproveitados),
`contact` (`CONTACT_UNAVAILABLE`/`CONTACT_UNPAIRED`) e `storeAttachment`
(`DUPLICATE_FILE`/`EXISTING_FILE_MISMATCH`). Nenhum deles muda de comportamento: a mesma condição
falha na mesma hora, só a informação carregada pela exceção muda de "uma frase em português" para
"um código mais uma frase em português só para debug/log".

`storeAttachment` é chamado tanto por `sendAttachment` (envio do usuário, id de arquivo sempre novo
via `crypto.random(16)`) quanto por `handle` (recebimento, no laço de rede) — nos dois casos o tipo
lançado agora é `MessagingError`, mas só o primeiro caminho tem alguém observando o código: o
`receiveLoop` continua engolindo qualquer `Exception`, `MessagingError` incluída, então o
comportamento do caminho de recebimento não muda em nada.

### O que **não** mudou

`accept`/`handle`/`acceptKeyPackage`/`acceptInvite`/`acceptCommit` continuam lançando
`IllegalArgumentException`/`IllegalStateException`/`RejectedControl` com texto cru, sem
`MessagingErrorCode` — essas exceções nunca escapam de `receiveLoop`, então não haveria quem lesse um
código ali. Ver `docs/changes/MessagingError.kt.md` para o raciocínio completo.

### Vantagens

- Zero mudança de comportamento observável (mesma validação, mesma ordem, mesma condição) — só a
  informação que a exceção carrega muda.
- `runtime/` continua sem importar nada de `android.content`/`R.string`: a tradução para texto vive
  só em `NoMessagesController`.
- Fecha o achado de lint sem inventar UI nova só para consumir uma string órfã.

### Verificação

`:core:test` (inalterado, este arquivo é só `app`), `:app:testDebugUnitTest` e `:app:lintDebug`:
`BUILD SUCCESSFUL`, zero `UnusedResources`/`PluralsCandidate` no relatório (antes: 28). Ver
`docs/changes/MessagingError.kt.md`, `docs/changes/MemoryAudioRecorder.kt.md` e
`docs/changes/NoMessagesController.kt.md`.

## 2026-09-23 — `storeAttachment` ganha reserva fixa de mídia e cobertura cega no slot irmão (T4.1)

### Motivo

Ver `docs/security-model.md` ("Fixed media reservation and blind cover growth", T4.1) para o
raciocínio completo. Resumo: `storeAttachment` era o único lugar que escrevia em `real.files`/
`decoy.files`, e escrevia só no slot da sessão aberta — nada mantinha o outro slot crescendo junto,
então um cofre em uso real divergia em tamanho de mídia entre os dois slots.

### Como era

```kotlin
private fun storeAttachment(envelope: Envelope.Attachment) {
    val fileId = hex(envelope.fileId)
    requireCode(db.getFile(fileId) == null, MessagingErrorCode.DUPLICATE_FILE)
    val info = files.decrypt(...)
    require(info.plaintextLength <= 8 * 1024 * 1024)
    Files.createDirectories(mediaRoot)
    val relative = "$fileId.bin"
    val target = safeMediaPath(relative)
    val existing = Files.exists(target)
    if (existing) { /* ...checagem de conteúdo idêntico... */ }
    val temporary = mediaRoot.resolve(".$fileId.part")
    try {
        if (!existing) {
            FileOutputStream(temporary.toFile()).use { it.write(envelope.ciphertext); it.fd.sync() }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            createdFiles.add(target)
        }
        FileChannel.open(mediaRoot, StandardOpenOption.READ).use { it.force(true) }
        val wrapped = crypto.seal(session.keys.fileKey, envelope.fileKey, fileId.toByteArray())
        db.putFile(FileRecord(fileId, relative, wrapped, info.plaintextLength, envelope.fileEpoch, envelope.name, envelope.mime))
    } finally { Files.deleteIfExists(temporary) }
}
```

Sem verificação de capacidade nenhuma (um anexo cabia sempre que a checagem de 8 MiB por arquivo
passasse) e sem nada tocando `real.files` quando a escrita era em `decoy.files`, ou vice-versa.

### Como ficou

O construtor ganhou `mediaCapacityBytes: Long` (de `AndroidVaultStorage.mediaCapacityBytes`, passado
por `NoMessagesController`). `storeAttachment` passou a checar a capacidade do slot que está
crescendo antes de escrever, e a cobrir o slot irmão depois:

```kotlin
private fun storeAttachment(envelope: Envelope.Attachment) {
    ...
    val existing = Files.exists(target)
    if (existing) { /* ...igual a antes... */ } else {
        requireCode(mediaDirectoryBytes(mediaRoot) + envelope.ciphertext.size <= mediaCapacityBytes, MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED)
    }
    Files.createDirectories(mediaRoot)
    /* ...mesma escrita atômica de antes... */
    if (!existing) coverSiblingSlot(envelope.ciphertext.size)
}

private fun siblingMediaRoot(): Path =
    directory.resolve(if (session.slot == VaultSlot.REAL) "decoy.files" else "real.files")

private fun mediaDirectoryBytes(root: Path): Long { /* soma os tamanhos dos arquivos em root, 0 se root não existe */ }

private fun coverSiblingSlot(size: Int) {
    val sibling = siblingMediaRoot()
    Files.createDirectories(sibling)
    val name = crypto.random(16).toHexId()
    val target = sibling.resolve("$name.bin").normalize()
    check(target.startsWith(sibling) && target.parent == sibling && !Files.isSymbolicLink(target))
    val temporary = sibling.resolve(".$name.part")
    val filler = crypto.random(size)
    try {
        FileOutputStream(temporary.toFile()).use { it.write(filler); it.fd.sync() }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(sibling, StandardOpenOption.READ).use { it.force(true) }
    } finally { Files.deleteIfExists(temporary); filler.fill(0) }
}
```

A checagem de capacidade roda só contra o slot que está de fato crescendo, antes de qualquer byte ser
escrito; a cobertura roda só depois de uma escrita real bem-sucedida (nunca no caso `existing`, uma
retentativa idempotente que também não escreveu nada de novo). `coverSiblingSlot` usa `crypto.random`
— nunca a chave de arquivo do slot irmão, que esta sessão não tem e não precisa derivar — e o nome do
blob (`<hex aleatório>.bin`) tem a mesma forma de um anexo real, mas não existe nenhum `FileRecord`
para ele no banco daquele outro slot (que esta sessão também não abre), então ele é inerte: nunca
será aberto como anexo por `decryptAttachment`.

### Por que a checagem de capacidade fica só no lado que cresce

Como os dois slots começam iguais (`AndroidVaultStorage.alignAllocations`, no setup/reset) e toda
escrita real é espelhada por uma cobertura do mesmo tamanho no outro slot, por indução os dois ficam
sempre com o mesmo total de bytes — checar só o lado que está de fato recebendo a escrita real já
garante os dois dentro do orçamento, sem duplicar a checagem.

### Vantagens

- `real.files`/`decoy.files` ficam iguais em tamanho continuamente, não só logo após um reset — fecha
  o distinguidor forense que um cofre em uso real deixava aberto.
- Falha limpa e específica (`MEDIA_CAPACITY_EXHAUSTED`) em vez de deixar o anexo crescer
  indefinidamente ou falhar de um jeito genérico.
- Reaproveita o mesmo padrão de escrita atômica (`temporary` + `ATOMIC_MOVE` + `fsync`) que a escrita
  real já usava — nenhuma técnica de I/O nova, só aplicada duas vezes.

### Verificação

`:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL`. Suíte instrumentada em `emulator-5556`: `OK (23
tests)`. Ver `docs/changes/AndroidVaultStorage.kt.md`, `docs/changes/MessagingError.kt.md`,
`docs/changes/NoMessagesController.kt.md` e `docs/changes/AndroidVaultStorageTest.kt.md`.

## 2026-09-23 (revisão P1 do T4.1) — cobertura cega deixa de depender de `existing`, vira idempotente por `fileId`

### Motivo

Uma revisão de código sobre o fechamento do T4.1 (achado P1, `MessagingEngine.kt:962`) encontrou uma
falha de consistência a crash: `storeAttachment` sempre roda dentro de `atomic{}`, que envolve o
corpo inteiro em `db.transaction{}` (`atomic`, linha ~440) — mas a escrita primária do anexo é uma
operação de sistema de arquivos crua (`FileOutputStream(...).use { it.write(...); it.fd.sync() }`
seguido de `Files.move(temporary, target, ATOMIC_MOVE)`), **durável assim que `fsync`+rename
terminam, independente de a transação SQL ao redor chegar a commitar**. A chamada a
`coverSiblingSlot` só acontecia dentro de `if (!existing)`, onde `existing` vem de
`Files.exists(target)` calculado no início da função. Se o processo morresse depois do `fsync` da
escrita primária mas antes da transação envolvente commitar (`db.putFile` roda dentro da mesma
transação, mais adiante na mesma chamada síncrona), o arquivo primário ficava durável no disco mas
nada era gravado em `db`. Uma retentativa (reenvio do remetente, ou uma mensagem redelivered)
reentrava em `storeAttachment`, encontrava `existing == true` (o arquivo sobreviveu ao crash) e, sob
o gate antigo, **pulava a cobertura para sempre** — desequilibrando `real.files`/`decoy.files`
permanentemente depois de um evento comum de SO móvel (OOM kill, force-stop, bateria), não um caso
exótico. Nenhum teste cobria isso: os dois novos casos do T4.1 nunca simulavam um crash a meio
caminho.

### Como era

```kotlin
// ...
if (!existing) coverSiblingSlot(envelope.ciphertext.size)
```

```kotlin
private fun coverSiblingSlot(size: Int) {
    val sibling = siblingMediaRoot()
    Files.createDirectories(sibling)
    val name = crypto.random(16).toHexId()
    val target = sibling.resolve("$name.bin").normalize()
    // ...escreve `filler` de `size` bytes sob esse nome, sem checar se já existe algo equivalente...
}
```

### Como ficou

```kotlin
// storeAttachment agora chama incondicionalmente:
coverSiblingSlot(fileId, envelope.ciphertext.size)
```

```kotlin
private fun coverSiblingSlot(fileId: String, size: Int) {
    val sibling = siblingMediaRoot()
    Files.createDirectories(sibling)
    val name = crypto.mac(session.keys.fileKey, ("cover:$fileId").toByteArray()).copyOf(16).toHexId()
    val target = sibling.resolve("$name.bin").normalize()
    check(target.startsWith(sibling) && target.parent == sibling && !Files.isSymbolicLink(target))
    if (Files.exists(target)) {
        check(Files.isRegularFile(target) && Files.size(target) == size.toLong()) {
            "Vault blind cover size mismatch for an existing fileId"
        }
        return
    }
    val temporary = sibling.resolve(".$name.part")
    val filler = crypto.random(size)
    try {
        FileOutputStream(temporary.toFile()).use { it.write(filler); it.fd.sync() }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        FileChannel.open(sibling, StandardOpenOption.READ).use { it.force(true) }
    } finally { Files.deleteIfExists(temporary); filler.fill(0) }
}
```

### Por que o nome do arquivo não é derivado direto do `fileId` (e por que isso importa)

A correção sugerida pela revisão original propunha um nome determinístico a partir do `fileId`
(ex.: `sibling.resolve("$fileId.cover")`). Isso foi **rejeitado**: os arquivos de anexo reais já se
chamam `"$fileId.bin"` (`fileId` é público — vem do envelope na rede, e é o nome de arquivo visível
em texto claro no slot real). Se a cobertura no slot irmão reusasse esse mesmo `fileId` (com ou sem
uma extensão `.cover` diferente), qualquer um que tivesse decifrado **um** dos dois slots poderia
procurar esse mesmo `fileId` no outro slot e encontrar a cobertura correspondente **sem nenhuma
chave do slot que a contém** — uma correlação trivial entre "cofre real" e "cofre isca" pelo nome do
arquivo sozinho, o oposto do que uma cobertura cega deveria garantir. Em vez disso, o nome usa
`mac(session.keys.fileKey, "cover:" + fileId)`: uma retentativa desta *mesma* sessão (mesma chave,
mesmo `fileId`) recalcula o mesmo nome e encontra seu próprio arquivo; um inspetor sem essa chave —
mesmo que tenha comprometido totalmente o **outro** slot e conheça seus `fileId`s reais em texto
claro — não consegue calcular nem reconhecer o nome correspondente no slot irmão.

### Por que é seguro

- **Idempotente por construção:** o nome do arquivo de cobertura é uma função pura de
  (`session.keys.fileKey`, `fileId`) — a mesma entrada sempre produz o mesmo nome, então uma
  retentativa encontra exatamente o arquivo que uma tentativa anterior (crashada ou não) já teria
  escrito, em vez de criar um segundo.
- **Nunca duplica:** se o arquivo já existe, a função só verifica o tamanho e retorna — nunca
  reescreve nem soma um blob extra. Isso também cobre o caso oposto (crash **depois** da cobertura
  já ter sido gravada e fsync'd, mas aines de a transação commitar): a retentativa reconhece a
  cobertura existente e não duplica.
- **`db.getFile(fileId) == null` continua sendo o guardião de entrada** (topo de `storeAttachment`,
  inalterado): se a transação de uma tentativa anterior **chegou** a commitar, uma redelivery do
  mesmo `fileId` já é rejeitada ali com `DUPLICATE_FILE`, antes mesmo de chegar perto da cobertura —
  então `coverSiblingSlot` só é chamada de novo quando a tentativa anterior genuinamente não
  commitou, que é exatamente o caso em que ela precisa rodar.
- **A checagem de capacidade continua válida por indução:** como a cobertura agora acontece no
  máximo uma vez por `fileId` (idempotência, acima), o argumento de indução do comentário original
  (os dois slots começam iguais e toda escrita real é espelhada exatamente uma vez) volta a ser
  verdadeiro sem exceção, em vez de só "na ausência de crash".
- **Testado por**
  `AndroidVaultStorageTest.storeAttachmentWritesTheCoverOnARetryThatFindsThePrimaryFileAlreadyDurable`
  (novo, ver `docs/changes/AndroidVaultStorageTest.kt.md`): simula a janela de crash diretamente —
  coloca manualmente o arquivo primário no disco (o que uma escrita primária durável de uma
  tentativa anterior teria deixado) sem passar por `storeAttachment`, chama o método privado uma vez
  via reflexão (exatamente a retentativa), e confirma que a cobertura é escrita apesar de
  `existing == true`, e que `db.getFile` passa a resolver.

### Vantagens

- Fecha uma falha de consistência a crash real, não um caso de laboratório: qualquer morte de
  processo (comum em Android) na janela entre a escrita primária e o commit da transação deixava o
  desequilíbrio permanente, sem nenhum caminho de autocorreção em lugar nenhum do código.
- Preserva (e reforça, com uma explicação explícita) a propriedade de não-correlação entre slots que
  o desenho original de `coverSiblingSlot` já tinha, em vez de trocá-la por uma correção mais simples
  mas insegura.
- Nenhuma mudança de assinatura pública — só a função privada `coverSiblingSlot` ganhou um parâmetro
  novo (`fileId`), e a chamada em `storeAttachment` perdeu o `if (!existing)`.

### Verificação

`:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`,
`:app:assembleDebugAndroidTest`: `BUILD SUCCESSFUL`. Suíte instrumentada em `emulator-5556` refeita
com o caso novo. Ver `docs/changes/AndroidVaultStorageTest.kt.md`, `docs/changes/VaultManager.kt.md`
e `docs/security-model.md` ("Fixed media reservation and blind cover growth — follow-up 2026-09-23",
T4.1).

## 2026-09-23 — Correção da revisão: literal em português no fallback de `ControlRejected`

### Como era antes

```kotlin
deferredErrors += "Não foi possível concluir a operação do grupo."
```

Único literal em português do arquivo que não é um comentário nem uma mensagem de `require`/`error`
interna pura — `deferredErrors` alimenta o parâmetro `onError: (String) -> Unit` do construtor,
então à primeira vista parecia um texto que podia chegar à UI.

### Como é agora

```kotlin
deferredErrors += "Could not complete the group operation."
```

Investigação: o único call site de `onError` (`NoMessagesController.kt:271`) é
`onError = { error(context.getString(R.string.error_group_operation), token) }` — a lambda não lê o
parâmetro `String` recebido (nem o nomeia); ela sempre mostra `R.string.error_group_operation` via
`context.getString`, que já existe em `values/strings.xml` (inglês) e `values-pt/strings.xml`
(português) com o texto correto localizado. O literal de `MessagingEngine.kt` nunca é lido — serve
só para popular a lista e disparar a chamada de `onError`, sem que o texto em si importe. Por isso
o item foi resolvido como "log interno" (mais precisamente, um valor descartado) e só traduzido
para inglês, sem virar um novo recurso de string.

### Vantagens

- Código do runtime consistente em inglês, sem introduzir uma string de recurso nova e não usada
  (o texto real que o usuário vê já está corretamente localizado em `error_group_operation`).

### Por que a mudança foi feita

Achado 7 da revisão desta tarefa.
