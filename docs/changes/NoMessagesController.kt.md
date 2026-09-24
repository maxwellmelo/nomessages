# app/src/main/kotlin/dev/mx3/nomessages/runtime/NoMessagesController.kt

## 2026-09-14 — T2.1: mapeamento de status em `activate`

Escopo desta mudança: apenas o trecho de mapeamento de status dentro de `activate` (antigas linhas ~235-249). Nenhuma outra parte do arquivo foi tocada; o loop de reconexão com backoff continua sendo trabalho de T2.3.

### Como era antes

```kotlin
tor.start(seed, bridges.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
seed.fill(0)
publish(token) { it.copy(network = NetworkStatus.ONLINE) }
```

`ONLINE` era publicado no instante em que `start` retornava — isto é, com o cliente Arti bootstrapado, mas com o descritor onion ainda em publicação. A interface anunciava "Tor conectado" enquanto o aparelho ainda não podia ser encontrado por ninguém.

### Como ficou

```kotlin
// Bootstrap only proves the client reached the network. Until the onion descriptor
// is published nobody can reach us, so ONLINE is held back instead of being claimed
// the moment `start` returns.
publish(token) { it.copy(network = NetworkStatus.PUBLISHING) }
if (!tor.awaitReady()) throw IllegalStateException("Transporte indisponível")
publish(token) { it.copy(network = NetworkStatus.ONLINE) }
```

O `catch` existente continua mapeando qualquer falha para `NetworkStatus.ERROR` com `notice_tor_unavailable`, e o `finally`/`invokeOnCompletion` continua zerando a seed.

### Vantagens

- O usuário passa a ver um estado honesto durante a publicação, em vez de um "conectado" que não permite receber mensagens.
- Estourar os 300 s vira um erro explícito, insumo direto para o `RETRYING` de T2.3, em vez de ficar preso em ONLINE falso.
- A espera acontece dentro da mesma coroutine cancelada pelo lock, então nenhuma trava nova é introduzida no caminho de bloqueio.

### Por que a mudança foi feita

T2.1: expor `PUBLISHING` como estado distinto de ONLINE, conforme o roteiro `2026-09-14-roadmap-to-release.md`.

## 2026-09-14 — T2.3: supervisão, backoff e reconexão do transporte

Escopo desta mudança: o laço de ativação e seus auxiliares. A seção anterior (T2.1) continua válida
quanto ao mapeamento de PUBLISHING; o que muda aqui é que ele agora é emitido pelo supervisor.

### Como era antes

Uma única tentativa, com falha terminal:

```kotlin
val tor = TorConnection(context)
val localEngine = MessagingEngine(crypto, database, localIdentity, opened, directory, tor, scope, mutex, ...)
...
transport = tor
...
scope.launch {
    try {
        tor.start(seed, bridges.lineSequence()...toList())
        seed.fill(0)
        publish(token) { it.copy(network = NetworkStatus.PUBLISHING) }
        if (!tor.awaitReady()) throw IllegalStateException("Transporte indisponível")
        publish(token) { it.copy(network = NetworkStatus.ONLINE) }
        while (isActive) {
            mutex.withLock { if (generation.get() == token && !shuttingDown) persistTorState() }
            delay(60_000)
        }
    } catch (_: Exception) {
        publish(token) { it.copy(network = NetworkStatus.ERROR, notice = ...notice_tor_unavailable) }
    } finally { seed.fill(0) }
}.invokeOnCompletion { seed.fill(0) }
```

Problemas: qualquer falha (bootstrap estourando 180 s, descritor não publicado em 300 s, processo
`:tor` morto depois) virava ERROR permanente até bloquear e reabrir; a semente ficava em claro na
`ByteArray` capturada pela coroutine durante toda a ativação; e a morte do filho depois de ONLINE
não era sequer detectada.

### Como ficou

A ativação instala o motor sem transporte e entrega a supervisão a `TransportSupervisor`:

```kotlin
val onion = TorNative.address(seed)
// Every activation attempt re-reads the seed from the vault and wipes it again, so no copy
// in the clear survives between retries.
seed.fill(0)
...
scope.launch { superviseTransport(token, localEngine, bridges) }
```

`superviseTransport` liga o estado do supervisor à UI (`onStatus` → `publish`) e o `sleep` a
`delay`, e mantém `NetworkStatus.ERROR` apenas para a morte da própria supervisão — o único caso em
que o texto de `notice_tor_unavailable` ("bloqueie e abra novamente para reconectar") ainda é
verdadeiro, já que a reconexão passou a ser automática.

Os quatro auxiliares novos:

- `attemptTransport` — uma tentativa: recicla a anterior, cria uma `TorConnection`, registra-a sob
  `lifecycleGuard` **antes** de iniciá-la (o que o lock capturar é o que ele mata), lê a semente do
  vault, chama `start`, publica PUBLISHING e espera `awaitReady`. Só devolve ONLINE depois de
  `localEngine.attachTransport(tor)`. Classifica `TimeoutCancellationException` **antes** de
  `CancellationException`: o prazo de bootstrap estourado é justamente a falha que o laço existe
  para tratar, e não um lock.
- `recycleTransport` — desacopla o motor e confirma a morte do filho anterior (até três tentativas)
  antes de permitir uma nova, porque `TorService` recusa um segundo START e um filho sobrevivente
  seria reaproveitado pelo `bindService` seguinte. O desregistro usa identidade **e** geração, para
  nunca apagar o transporte de uma sessão mais nova.
- `torSeed` — lê `onion_seed` do SQLCipher sob o mutex, com verificação de geração; o chamador zera a
  cópia logo após `start`.
- `holdTransport` — mantém a persistência de guards a cada 60 s e devolve o controle assim que o
  transporte se perde. Um filho morto faz a requisição falhar; um filho que parou de responder
  estoura o prazo interno. Ambos são perda. Um transporte que apenas voltou a PUBLISHING mantém a
  chamada pendente e **não** provoca reinício.

O kill confirmado do processo `:tor` em `lock()` não foi tocado.

### Vantagens

- Falha de rede deixa de exigir intervenção do usuário: 5 s, 10 s, 20 s, 60 s, 120 s e teto de
  5 min, sempre cancelável pelo lock porque o laço vive no `sessionScope`.
- A semente da onion deixa de ficar em claro entre tentativas: `activate` zera a sua cópia assim que
  deriva o endereço, e cada tentativa lê a sua do vault e a zera em seguida.
- Queda depois de ONLINE passa a ser detectada e reparada, cenário que antes ficava indefinidamente
  com a faixa "Tor conectado" mentindo para o usuário.
- `RETRYING`, que existia no contrato de UI desde o início e nunca era emitido, passa a ser o estado
  real durante as esperas, com a outbox pausada — o texto "envios aguardam" passa a ser verdade.

### Por que a mudança foi feita

T2.3 do roteiro `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.

## 2026-09-14 — T4.2: contador local de não lidas

Tarefa: T4.2 de `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.
Escopo: apenas `openChat` e o trecho de montagem de `chats` dentro de `refresh()`.
Nada do supervisor de transporte (T2.3), acima neste mesmo documento, foi tocado.

### Como era antes

```kotlin
override fun openChat(id: String) = action { _state.update { previous -> if (shuttingDown) previous else previous.copy(selectedChat = id) }; refresh() }
```

```kotlin
val chats = database.listChats().map { chat ->
    ChatUi(chat.id, chat.title, chat.lastMessage?.let { preview(it) }.orEmpty(), time(chat.lastTimestamp), group = chat.isGroup,
        lastOutgoing = chat.lastOutgoing, lastStatus = chat.lastStatus?.name.orEmpty())
}
```

`ChatUi.unread` nunca era preenchido, então caía no valor padrão `0` do contrato
e o selo de `HomeScreen.kt` jamais aparecia. Nenhuma mensagem recebida era
marcada como lida em momento algum: `MessageStatus.READ` só existia na isca.
O `refresh()` explícito no fim de `openChat` também era redundante — `action`
já chama `refresh()` depois do bloco, então a interface era remontada duas vezes
a cada abertura de conversa.

### Como ficou

```kotlin
// `action` refreshes after the block, and `refresh` is what clears the local unread counter of
// the selected chat, so opening a conversation marks it read without a second code path.
override fun openChat(id: String) = action { _state.update { previous -> if (shuttingDown) previous else previous.copy(selectedChat = id) } }
```

```kotlin
// Reading is decided locally and is never announced to the peer: the open conversation is
// marked READ before the counters are read back, so it also covers a message that arrives
// while the user is already looking at it. The decoy history is seeded READ, so its rows
// are already in this state and no vault is singled out by doing extra work here.
if (selected != null) database.markChatRead(selected)
val unread = database.unreadCounts()
val chats = database.listChats().map { chat ->
    ChatUi(chat.id, chat.title, chat.lastMessage?.let { preview(it) }.orEmpty(), time(chat.lastTimestamp), group = chat.isGroup,
        unread = unread[chat.id] ?: 0,
        lastOutgoing = chat.lastOutgoing, lastStatus = chat.lastStatus?.name.orEmpty())
}
```

A marcação ficou dentro de `refresh()`, e não dentro de `openChat`, de propósito:

- `openChat` é `action { ... }` e `action` chama `refresh()` ao terminar, então o
  caso "abri a conversa" continua coberto por um único caminho;
- o `refresh()` disparado pelo `onChanged` do `MessagingEngine` cobre também o
  caso "a mensagem chegou enquanto a conversa já estava aberta na tela". Sem
  isso o selo passaria a mentir no instante em que o usuário voltasse à lista.

A ordem importa: marca-se primeiro, lê-se o contador depois, de modo que a
conversa aberta já sai de `refresh()` com `unread = 0` na mesma emissão de
estado — a interface nunca pisca um selo que será apagado no quadro seguinte.

**Compromisso assumido, registrado por honestidade.** Como a condição é "há
conversa selecionada", uma mensagem que chega com o aplicativo em segundo plano
e a conversa ainda selecionada é marcada como lida sem que o usuário a tenha
visto naquele instante — a notificação continua sendo disparada logo depois, na
mesma linha `onChanged`, e a conversa já está na tela quando ele volta. A
alternativa (marcar só com `foreground == true`) exigiria um `refresh()` dentro
de `onForeground()`, que roda na thread principal e faria IO de SQLCipher ali;
foi descartada por isso. Se o contador em segundo plano vier a incomodar, o
conserto certo é agendar um `refresh()` em IO a partir de `onForeground()`, e
não mexer nesta condição.

A escrita é segura neste ponto porque `markChatRead` usa `mutate(0)`: estimativa
zero não consome reserva, então `refresh()` não passa a poder falhar com
`StorageCapacityException` num banco cheio.

### Vantagens

- O selo de não lidas de `HomeScreen.kt` deixa de ser código morto, sem nenhum
  metadado novo trafegando: a leitura é registrada só no aparelho.
- O contador fica correto também quando a mensagem chega com a conversa aberta,
  e não apenas no instante da abertura.
- Uma remontagem de interface a menos por abertura de conversa, pela remoção do
  `refresh()` duplicado.
- Um único `unreadCounts()` agrupado por `refresh()`, em vez de uma consulta por
  conversa — o custo não cresce com o número de chats abertos na lista.

### Por que a mudança foi feita

T4.2 exigia decidir entre implementar ou remover as não lidas. A decisão tomada
foi implementar **somente o contador local**, sem recibo de leitura na rede, que
adicionaria metadado e um tipo de envelope. Este arquivo é o lado do runtime
dessa decisão; o lado do armazenamento está em `docs/changes/ChatDatabase.kt.md`.

## 2026-09-15 — Revisão T2.1/T2.3: checkpoint de guards, prontidão tri-estado, morte esperada e alcançabilidade reversível

### Como era antes

```kotlin
// attemptTransport
try { tor.start(seed, bridges) } finally { seed.fill(0) }
supervisor.publishing()
if (tor.awaitReady()) { localEngine.attachTransport(tor); TransportAttempt.ONLINE }
else TransportAttempt.FAILED

// recycleTransport
dying.shutdown()
synchronized(lifecycleGuard) { if (generation.get() == token && transport === dying) transport = null }
return true

// holdTransport
while (true) {
    mutex.withLock { if (generation.get() == token && !shuttingDown) persistTorState() }
    delay(60_000)
    val tor = ... ?: return
    val alive = try { withTimeoutOrNull(30_000) { tor.awaitReady() } } catch ... 
    if (alive == false) return
}
```

Quatro defeitos:

1. `recycleTransport` confirmava a morte do `:tor` e **nunca** fazia checkpoint do estado de
   guards, violando `docs/security-model.md` linha 29 ("checkpointed inside SQLCipher after
   bootstrap, periodically while unlocked, and after a confirmed shutdown"). Cada reconexão
   descartava o conjunto de guards.
2. Prazo de publicação esgotado era tratado como transporte morto: o processo era recriado e mais
   um bootstrap de 180 s era pago para chegar exatamente ao mesmo ponto.
3. A morte do transporte só era notada no heartbeat seguinte (até 60 s depois), e nessa janela o
   `outboxLoop` seguia gastando o cooldown de 30 s por item contra um transporte morto.
4. Depois de ONLINE, uma queda de alcançabilidade (READY → PUBLISHING) nunca voltava para a UI:
   o banner continuava dizendo "Tor conectado" — a mentira que T2.1 existe para remover.

### Como ficou

```kotlin
// attemptTransport
try { tor.start(seed, bridges) } finally { seed.fill(0) }
checkpointTorState(token)
supervisor.publishing()
repeat(PUBLICATION_ROUNDS) {
    when (tor.awaitReady()) {
        TorReadiness.READY -> { localEngine.attachTransport(tor); return TransportAttempt.ONLINE }
        TorReadiness.PUBLISHING -> checkpointTorState(token)
        TorReadiness.LOST -> return TransportAttempt.FAILED
    }
}
TransportAttempt.FAILED

// recycleTransport
dying.shutdown()
checkpointTorState(token)
synchronized(lifecycleGuard) { ... }

// novo helper
private suspend fun checkpointTorState(token: Long) {
    mutex.withLock { if (generation.get() == token && !shuttingDown) persistTorState() }
}

// holdTransport(token, supervisor)
while (true) {
    checkpointTorState(token)
    val tor = ... ?: return
    if (withTimeoutOrNull(HEARTBEAT_MILLIS) { tor.awaitDeath() } != null) return
    when (tor.awaitReady(REACHABILITY_PROBE_MILLIS)) {
        TorReadiness.READY -> supervisor.reachable()
        TorReadiness.PUBLISHING -> supervisor.publishing()
        TorReadiness.LOST -> return
    }
}
```

Constantes novas no `companion object`: `PUBLICATION_ROUNDS = 3`, `HEARTBEAT_MILLIS = 60_000`,
`REACHABILITY_PROBE_MILLIS = 30_000`.

### Vantagens

- **Guards duráveis em todas as transições**: depois do bootstrap, a cada orçamento de publicação
  esgotado e depois de uma morte confirmada. Todos passam pelo mesmo `checkpointTorState`, que
  continua respeitando `generation`/`token` e `shuttingDown` — uma sessão nova nunca escreve por
  uma antiga. Reusar guards é o que impede que cada reconexão pareça, para a rede, um cliente novo
  escolhendo guards novos.
- **Um bootstrap poupado por publicação lenta**: até três orçamentos de 300 s no MESMO cliente Arti.
  O limite continua existindo — um serviço onion travado (não lento) só é consertado por processo
  novo, e aí o backoff do supervisor assume.
- **Morte detectada na hora**: `awaitDeath()` corre contra o heartbeat em vez de ser amostrada.
  A janela de até 60 s com transporte morto anexado ao motor desapareceu.
- **Banner honesto nos dois sentidos**: `reachable()` é o contraponto de `publishing()`. O supervisor
  deduplica o status, então nada é repintado enquanto nada muda, e **não há reinício** por queda de
  alcançabilidade: o mesmo cliente continua sendo o certo.

### Por que a mudança foi feita

Achados P2 da revisão em `NoMessagesController.kt:294`, `:309`, `:333` e `:343`.

## 2026-09-15 — Diagnosticabilidade: logging condicional (debug) nos `catch` de `setup()`/`unlock()`

Contexto: T3.3 (dois emuladores) achou que a criação do cofre pela UI real falha em
`emulator-5556`/`emulator-5560` com o diálogo genérico `error_create_vault`, sem nenhuma pista no
logcat porque a exceção real era descartada. Ver `docs/development/device-verification.md`, seção
"T3.3 — Two-device messaging and membership procedure" e "Bug found: vault creation fails silently
from the real Setup UI". Esta seção documenta apenas a mudança de diagnosticabilidade — a correção
da causa raiz em si (se aplicável) está documentada no arquivo correspondente ao arquivo-fonte
efetivamente corrigido.

### Como era antes

```kotlin
} catch (_: Exception) {
    cleanupFailedActivation(token)
    error(context.getString(R.string.error_create_vault), token)
}
```

```kotlin
} catch (_: Exception) {
    cleanupFailedActivation(token)
    error(context.getString(R.string.error_open_vault), token)
}
```

A exceção real de `setup()` e de `unlock()` era completamente descartada (`_: Exception`) — nem tipo
nem mensagem chegavam ao logcat, em nenhum tipo de build. Qualquer defeito no caminho de
`VaultManager.create()`/`VaultManager.unlock()` (KDF, storage, decoy) só podia ser investigado
anexando um debugger JDWP, como o relatório de T3.3 registrou ter sido necessário.

### Como ficou

```kotlin
} catch (e: Exception) {
    // Never log password or key material here - only the exception's own type/message,
    // and only in debug builds. Release keeps today's fully silent behavior.
    if (BuildConfig.DEBUG) {
        Log.e(TAG, "setup failed: ${e.javaClass.name}: ${e.message}", e)
    }
    cleanupFailedActivation(token)
    error(context.getString(R.string.error_create_vault), token)
}
```

E o equivalente em `unlock()`, trocando a mensagem para `"unlock failed: ..."` e
`error_open_vault`. Import novo: `android.util.Log` e `dev.mx3.nomessages.BuildConfig`. Constante nova
`private const val TAG = "NoMessagesController"` no `companion object`. `app/build.gradle.kts` passou a
gerar `BuildConfig` (`buildFeatures.buildConfig = true`; ver
`docs/changes/app-build.gradle.kts.md`) — o pacote é `dev.mx3.nomessages.BuildConfig`, o mesmo em
build debug e release (a classe é gerada por variante e cada uma recebe seu próprio `DEBUG`
constante).

Nenhum outro `catch` genérico no arquivo fica no caminho de `setup()`/`unlock()`: `activate()`, que
roda dentro do bloco `try` de ambos, não tem `catch` próprio — qualquer exceção sua já propagava
para estes dois blocos, então logá-los aqui cobre o fluxo inteiro de ativação.

### Vantagens

- O stack trace real (tipo e mensagem da exceção) passa a aparecer no `logcat` em build debug,
  permitindo diagnosticar o bloqueador de T3.3 sem anexar um debugger JDWP.
- Em build release o comportamento é **idêntico** ao de antes: `BuildConfig.DEBUG` é `false` e o
  `if` inteiro é eliminado como código morto pelo R8/compilador, então nada passa a ser logado.
- Nenhuma senha ou material de chave é logado — apenas `e.javaClass.name` e `e.message`, e os dois
  já eram descartados (não continham segredos: são exceções de `require`/`check`/IO/nativas).

### Motivo da mudança

Passo 1 da tarefa de investigação de 2026-09-15 (bloqueador de criação de cofre pela UI real nos
dois emuladores oficiais Android 15 x86_64): tornar o diagnóstico possível antes de tentar corrigir
a causa raiz às cegas.

## 2026-09-16 — Áudio comprimido no envio, prévias sob demanda e limpeza do cache no lock (T4.8, mídia inline — parte A: áudio)

### `stopAudio()`: AAC primeiro, WAV só como fallback

Antes enviava sempre `"audio.wav"`/`"audio/wav"`. Agora usa `recording.aacBytes` (de
`MemoryAudioRecorder.finish()`, ver `docs/changes/MemoryAudioRecorder.kt.md`) quando disponível —
`"audio.m4a"`/`"audio/mp4"` — e só cai para `"audio.wav"`/`"audio/wav"` quando a codificação AAC
falhou neste aparelho. Logo após o envio, cacheia a forma de onda já calculada:

```kotlin
val fileId = if (aac != null) activeEngine().sendAttachment(request.chat, "audio.m4a", "audio/mp4", aac)
    else activeEngine().sendAttachment(request.chat, "audio.wav", "audio/wav", recording.wavBytes)
MediaPreviewCache.put(fileId, MediaPreviewPayload.Waveform(recording.waveform, recording.durationMs))
```

### Dois novos métodos suspensos: `loadAudioPreview`/`loadAudioBytes`

Implementam `UiActions.loadAudioPreview`/`loadAudioBytes` (ver `docs/changes/UiContract.kt.md`),
logo após `closeAttachment()`. Os dois seguem exatamente a mesma disciplina de concorrência de
`openAttachment` (`mutex.withLock`, checagem de `token == generation.get()`/`shuttingDown`/`session
!= null`), mas devolvem o valor direto para quem pediu em vez de passar por `_state`:

- `loadAudioPreview`: confere `MediaPreviewCache` primeiro; se ausente, decifra o anexo, chama
  `decodeAudioWaveform` (novo, `AudioWaveformDecoder.kt`), cacheia o resultado e devolve. Os bytes
  decifrados usados só para o cálculo são zerados no `finally`, nunca cacheados.
  `loadAudioPreview`/`loadAudioBytes` chamam `withContext(Dispatchers.IO)` explicitamente — decifrar
  e decodificar sempre correm fora da main thread, o `mutex.withLock` de dentro é o mesmo já usado
  pelo resto do controlador para serializar acesso à sessão.
- `loadAudioBytes`: decifra e devolve os bytes direto (nunca cacheados) — o chamador (a bolha de
  áudio) é quem os zera depois de tocar.

### Limpeza do `MediaPreviewCache`/`AudioPlaybackCoordinator` no lock

```kotlin
// lock(), dentro do bloco synchronized(lifecycleGuard), logo após _state.value.attachment?.bytes?.fill(0):
MediaPreviewCache.clear()
AudioPlaybackCoordinator.reset()

// closeSession():
MediaPreviewCache.clear()
AudioPlaybackCoordinator.reset()
```

`closeSession()` é o ponto de convergência de **todo** caminho de teardown de sessão: `lock()`,
`cleanupFailedActivation()`, `acceptExport()` e `changePanicPassword()` passam por
`closeOwnedSession()` → `closeSession()` (o `grep` de `closeSession()`/`closeOwnedSession()` no
arquivo confirma isso), então limpar o cache ali cobre todos eles com uma única mudança; a limpeza
adicional dentro de `lock()` garante que aconteça de forma síncrona, no mesmo instante em que o
resto do estado sensível já é zerado, sem esperar o teardown assíncrono em `Dispatchers.IO`.

### Vantagens

- Anexo de voz menor no caminho feliz (AAC), sem tocar no teto de 8 MiB nem no formato de
  `Envelope`.
- Nenhuma prévia de áudio nem player ativo sobrevive a um lock — cumpre o invariante 2 da tarefa
  (cache pequeno, limitado, esvaziado de forma síncrona no lock) usando exatamente os pontos de
  gancho que a tarefa apontou (`lock()` ~linha 405, `closeSession()` ~linha 431, antes de qualquer
  edição).

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

## 2026-09-16 — `loadImagePreview`, galeria de imagens em `openAttachment` (T4.8, mídia inline — parte B: fotos)

### Motivo

Espelhar para fotos o par decrypt-preview/decrypt-bytes que `loadAudioPreview`/`loadAudioBytes` já
implementam para áudio (mesmo `mutex`/`generation`/`shuttingDown` guard, mesmo "cacheia a prévia
pequena, nunca os bytes decifrados"), e dar ao visualizador em tela cheia a lista ordenada de ids de
imagem da conversa aberta para poder deslizar entre elas (B3), sem introduzir uma segunda consulta
ao banco — a lista já vem de `_state.value.messages`, que `refresh()` já monta a cada mudança.

### Como era antes

```kotlin
override fun openAttachment(id: String) = action {
    val token = generation.get()
    _state.value.attachment?.bytes?.fill(0)
    val (record, bytes) = activeEngine().decryptAttachment(id)
    synchronized(lifecycleGuard) {
        if (token == generation.get() && !shuttingDown && _state.value.unlocked)
            _state.update { previous -> if (shuttingDown) previous else previous.copy(attachment = AttachmentUi(record.displayName, record.mimeType, bytes)) }
        else bytes.fill(0)
    }
}
override fun closeAttachment() {
    _state.value.attachment?.bytes?.fill(0)
    _state.update { previous -> if (shuttingDown) previous else previous.copy(attachment = null) }
}
// loadAudioPreview/loadAudioBytes existiam; nenhum equivalente para imagem.
```

### Como é agora

```kotlin
override fun openAttachment(id: String) = action {
    val token = generation.get()
    _state.value.attachment?.bytes?.fill(0)
    val (record, bytes) = activeEngine().decryptAttachment(id)
    val gallery = if (attachmentKind(record.mimeType) == AttachmentKind.IMAGE) {
        imageGalleryIds(_state.value.messages).takeIf { it.size > 1 } ?: emptyList()
    } else emptyList()
    synchronized(lifecycleGuard) {
        if (token == generation.get() && !shuttingDown && _state.value.unlocked)
            _state.update { previous -> if (shuttingDown) previous else previous.copy(attachment = AttachmentUi(id, record.displayName, record.mimeType, bytes), imageGallery = gallery) }
        else bytes.fill(0)
    }
}
override fun closeAttachment() {
    _state.value.attachment?.bytes?.fill(0)
    _state.update { previous -> if (shuttingDown) previous else previous.copy(attachment = null, imageGallery = emptyList()) }
}

override suspend fun loadImagePreview(attachmentId: String): ImagePreviewUi? {
    (MediaPreviewCache.get(attachmentId) as? MediaPreviewPayload.Thumbnail)?.let { return ImagePreviewUi(it.bitmap) }
    if (!_state.value.unlocked) return null
    val token = generation.get()
    return withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                if (token != generation.get() || shuttingDown || session == null) return@withLock null
                val (record, bytes) = activeEngine().decryptAttachment(attachmentId)
                try {
                    val thumbnail = decodeBoundedThumbnail(bytes) ?: return@withLock null
                    MediaPreviewCache.put(attachmentId, MediaPreviewPayload.Thumbnail(thumbnail))
                    ImagePreviewUi(thumbnail)
                } finally { bytes.fill(0) }
            }
        } catch (_: Exception) { null }
    }
}

private fun decodeBoundedThumbnail(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = chooseInSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_DIMENSION)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
```

`decodeBoundedThumbnail` nunca decodifica a imagem em resolução total: lê só as dimensões primeiro
(`inJustDecodeBounds = true`), calcula o `inSampleSize` com a função pura `chooseInSampleSize` (ver
`docs/changes/UiLogic.kt.md`, alvo de 512px do lado maior), e só então decodifica de verdade. Os
bytes decifrados (`bytes`) são zerados no `finally` assim que a miniatura sai deles — o mesmo padrão
de todo outro `decryptAttachment` neste arquivo.

### Vantagens

- Segue exatamente o padrão de `loadAudioPreview` (mesmo guard de concorrência, mesmo
  cache-primeiro-decifra-depois, `null` em vez de lançar) — quem já entende um entende o outro.
- `imageGalleryIds(_state.value.messages)` reaproveita a lista de mensagens já carregada; nenhuma
  consulta nova ao `ChatDatabase`.
- `imageGallery` só é populada quando há mais de uma imagem — o visualizador em tela cheia não
  precisa checar esse caso especial, uma galeria de tamanho `0` ou `1` já significa "nada para
  deslizar".
- `AttachmentUi` ganhou `id` (ver `docs/changes/UiContract.kt.md`) só para dar ao visualizador como
  localizar a posição do anexo aberto dentro de `imageGallery` — sem isso, B3 não seria possível.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

## 2026-09-16 — `loadVideoPreview`/`decodeVideoPoster` (T4.8, mídia inline — parte C: vídeo, agente 3 de 3)

### Motivo

A bolha de vídeo inline nova (`ChatScreen.kt`) precisa de um poster frame + duração, do mesmo jeito
que `loadImagePreview` já fornece uma miniatura para a bolha de imagem. Diferente de imagem
(`BitmapFactory`), extrair um frame de um contêiner de vídeo exige `MediaMetadataRetriever`, que só
sabe ler de um caminho de arquivo, um `FileDescriptor`, ou — o caso usado aqui, para nunca gravar
bytes decifrados em disco — um `MediaDataSource` em memória, exatamente o `MemoryMediaDataSource` que
`AttachmentViewer.kt` já expõe como `internal` para o resto do módulo.

### Como é agora

```kotlin
override suspend fun loadVideoPreview(attachmentId: String): VideoPreviewUi? {
    (MediaPreviewCache.get(attachmentId) as? MediaPreviewPayload.VideoPoster)?.let {
        return VideoPreviewUi(it.bitmap, it.durationMs)
    }
    if (!_state.value.unlocked) return null
    val token = generation.get()
    return withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                if (token != generation.get() || shuttingDown || session == null) return@withLock null
                val (record, bytes) = activeEngine().decryptAttachment(attachmentId)
                try {
                    val (poster, durationMs) = decodeVideoPoster(bytes) ?: return@withLock null
                    MediaPreviewCache.put(attachmentId, MediaPreviewPayload.VideoPoster(poster, durationMs))
                    VideoPreviewUi(poster, durationMs)
                } finally { bytes.fill(0) }
            }
        } catch (_: Exception) { null }
    }
}

private fun decodeVideoPoster(bytes: ByteArray): Pair<Bitmap, Int>? {
    if (bytes.isEmpty()) return null
    val dataSource = MemoryMediaDataSource(bytes)
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(dataSource)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val frame = retriever.getScaledFrameAtTime(
            0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_MAX_DIMENSION, THUMBNAIL_MAX_DIMENSION,
        ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        frame?.let { it to durationMs }
    } catch (_: Exception) { null }
    finally {
        retriever.release()
        dataSource.close()
    }
}
```

Três decisões concentradas em `decodeVideoPoster`:

- **Nunca copia `bytes`**: `MemoryMediaDataSource` é a mesma classe que já toma posse do array e o
  zera no `close()` (ver `docs/changes/AttachmentViewer.kt.md`), então o `finally { bytes.fill(0) }`
  do chamador vira um segundo zeramento inofensivo (idempotente) em vez de precisar de uma cópia só
  para o retriever ler.
- **Limite de tamanho reaproveitado**: em vez de um segundo bound de "~512px", `decodeVideoPoster`
  passa o mesmo `THUMBNAIL_MAX_DIMENSION` que `decodeBoundedThumbnail` já usa para imagem, ao
  `getScaledFrameAtTime(timeUs, option, dstWidth, dstHeight)` — disponível desde a API 27, bem abaixo
  do `minSdk` 31 do projeto — que decodifica já limitado, sem nunca materializar um bitmap em
  resolução total só para reduzi-lo depois (o mesmo espírito de `chooseInSampleSize`, mas resolvido
  pela própria API do `MediaMetadataRetriever` em vez de aritmética manual). Um fallback para
  `getFrameAtTime` sem bound cobre o caso raro de um dispositivo cuja implementação da sobrecarga
  limitada devolva `null` mesmo com um frame decodificável existindo.
- **Nunca lança**: qualquer falha (vídeo corrompido, sem faixa de vídeo, contêiner não suportado)
  cai no `catch` e devolve `null`, igual a todo outro `decode*` deste arquivo — o `finally` sempre
  libera o `retriever` e fecha a `dataSource`, sucesso ou falha.

### Vantagens

- Mesmo contrato de concorrência (`mutex`/`generation`/`shuttingDown`) e mesmo formato
  cache-primeiro-decifra-depois de `loadAudioPreview`/`loadImagePreview` — nenhuma disciplina nova
  para quem já leu os outros dois.
- Reaproveita duas peças já existentes em vez de duplicá-las: `MemoryMediaDataSource` (memória, nunca
  disco) e `THUMBNAIL_MAX_DIMENSION` (mesmo teto visual de 512px que a miniatura de foto já usa).
- Roda inteiramente dentro do `withContext(Dispatchers.IO)` que já envolve o bloco — `MediaMetadataRetriever`
  nunca toca a main thread, mesma disciplina de todo outro decode neste arquivo.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`. `:app:assembleDebugAndroidTest`:
`BUILD SUCCESSFUL` (compila o novo `VideoPreviewDecodeTest.kt`, que exercita
`MediaMetadataRetriever`/`MemoryMediaDataSource`/`getScaledFrameAtTime` contra um clipe MP4 sintético
gerado em runtime — ver `docs/changes/VideoPreviewDecodeTest.kt.md`; não executado nesta tarefa, sem
emulador disponível).


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Correcoes: `inMutable` na miniatura e contencao de `OutOfMemoryError`

**Como era (47afa3e):**

```kotlin
private fun decodeBoundedThumbnail(bytes: ByteArray): Bitmap? {
    ...
    val options = BitmapFactory.Options().apply {
        inSampleSize = chooseInSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_DIMENSION)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun decodeVideoPoster(bytes: ByteArray): Pair<Bitmap, Int>? {
    ... } catch (_: Exception) { null } finally { ... }
}
```

**Como ficou:**

```kotlin
private fun decodeBoundedThumbnail(bytes: ByteArray): Bitmap? {
    ...
    return try {
        ...
        val options = BitmapFactory.Options().apply {
            inSampleSize = chooseInSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (failure: OutOfMemoryError) { Log.w(TAG, ...); null }
}

private fun decodeVideoPoster(bytes: ByteArray): Pair<Bitmap, Int>? {
    ... } catch (_: Exception) { null }
      catch (failure: OutOfMemoryError) { Log.w(TAG, ...); null }
      finally { ... }
}
```

**Por que era necessario.**

1. `inMutable = true`: `MediaPreviewPayload.Thumbnail.wipe()` (antes `dispose()`) faz
   `if (isMutable) eraseColor(...)`. Um bitmap do `BitmapFactory` e **imutavel** por padrao, logo o
   "erase" prometido em `docs/security-model.md` ("cached bitmaps carry an explicit zero-and-recycle
   step") nunca era executado para miniaturas - so o `recycle()`. Com `inMutable = true` o apagamento
   de pixels no lock passa a ser real.
2. `catch (OutOfMemoryError)`: `loadImagePreview`/`loadVideoPreview` envolvem a decodificacao em
   `catch (_: Exception)`, que nao captura `Error`. Mesmo com o novo orcamento de pixels, o
   `getFrameAtTime` de fallback do poster de video e **nao limitado** e um decodificador pode estourar
   memoria em entrada hostil. Sem esse catch, o resultado e a morte do processo em vez da degradacao
   documentada para a linha generica de anexo.

**Garantia restaurada.** O apagamento de pixels no lock deixa de ser um no-op silencioso; uma imagem
ou video hostil degrada para a linha generica em vez de derrubar o app.

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.

---

## 2026-09-17 — `forwardMessage`, `forwardTargets` e `MessageUi.forwarded` (Parte B: Encaminhar)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). O controlador é a ponte entre
o banco/engine e a UI: ele precisava (a) propagar o flag `forwarded` para a bolha, (b) publicar a
lista de destinos possíveis que a tela de seleção de destinatários vai renderizar, e (c) implementar
a ação de encaminhar de fato. A UI em si (menu de toque longo, diálogo de seleção, rótulo na bolha) é
a Parte C, feita por outro agente **em cima destas APIs**.

---

### 1. `refresh()` — `MessageUi.forwarded`

**Como era antes**

```kotlin
val messages = if (selected == null) emptyList() else database.listMessages(selected).map { record ->
    val decoded = try { EnvelopeCodec.decode(record.body) } catch (_: Exception) { null }
    val attachment = decoded as? Envelope.Attachment
    MessageUi(record.id, (decoded as? Envelope.Text)?.body ?: attachment?.name ?: record.body.toString(Charsets.UTF_8).take(4000),
        time(record.timestamp), record.direction == MessageDirection.OUTGOING, record.status.name,
        attachmentId = ..., attachmentName = attachment?.name, mimeType = attachment?.mime)
}
```

**Como é agora**

```kotlin
        attachmentId = ..., attachmentName = attachment?.name, mimeType = attachment?.mime,
        // From the column, not from the decoded envelope: the row is authoritative and is
        // readable even when the body fails to decode (a truncated or foreign envelope).
        forwarded = record.forwarded)
```

O valor vem de `record.forwarded` (a coluna) e **não** de `(decoded as? Envelope.Text)?.forwarded`.
Os dois são sempre iguais por construção, mas a coluna continua legível quando o envelope não
decodifica — que é justamente o caso em que a bolha cai no fallback de texto cru e o rótulo ainda
deve aparecer.

---

### 2. `refresh()` — `forwardTargets`

**Como era antes** — não existia; `refresh()` só calculava o status do grupo **selecionado**:

```kotlin
val contacts = database.listContacts().map { it.toUi() }
...
fun hasGroupFlag(namespace: String): Boolean {
    val bytes = selected?.let { database.getBlob(namespace, it) } ?: return false
    bytes.fill(0)
    return true
}
val groupStatus = when {
    group == null -> GroupStatus.NONE
    hasGroupFlag("group_left") -> GroupStatus.LEFT
    hasGroupFlag("group_error") -> GroupStatus.FAILED
    hasGroupFlag("group_pending") -> GroupStatus.PENDING
    else -> GroupStatus.READY
}
```

**Como é agora**

```kotlin
// Os registros são mantidos ao lado da projeção de UI porque ContactUi nao expoe displayOnly.
val contactRecords = database.listContacts()
val contacts = contactRecords.map { it.toUi() }
...
fun hasGroupFlag(namespace: String, groupId: String? = selected): Boolean {
    val bytes = groupId?.let { database.getBlob(namespace, it) } ?: return false
    bytes.fill(0)
    return true
}
fun statusOf(groupId: String): GroupStatus = when {
    hasGroupFlag("group_left", groupId) -> GroupStatus.LEFT
    hasGroupFlag("group_error", groupId) -> GroupStatus.FAILED
    hasGroupFlag("group_pending", groupId) -> GroupStatus.PENDING
    else -> GroupStatus.READY
}
val groupStatus = if (group == null) GroupStatus.NONE else statusOf(group.id)
...
val forwardTargets = contactRecords.filter { !it.displayOnly }.map { ForwardTargetUi(it.id, it.alias, isGroup = false) } +
    database.listGroups().mapNotNull { candidate ->
        try {
            ForwardTargetUi(candidate.id, candidate.name, isGroup = true)
                .takeIf { statusOf(candidate.id) == GroupStatus.READY && candidate.state.isNotEmpty() }
        } finally { candidate.state.fill(0) }
    }
```

e `forwardTargets = forwardTargets` no `_state.update { current.copy(...) }`.

**Decisões:**

- **`hasGroupFlag` ganhou um parâmetro com default `= selected`.** A lógica de status não foi
  duplicada: ela foi *parametrizada*. Os três `hasGroupFlag("...")` do bloco de status viraram
  `statusOf(group.id)`, que produz exatamente o mesmo resultado que antes (o `group` é buscado por
  `selected`, logo `group.id == selected`), e a mesma função serve agora a cada grupo candidato.
- **`group == null -> GroupStatus.NONE` continua curto-circuitando** antes de qualquer consulta de
  blob, como no código original.
- **`contactRecords` separado de `contacts`.** `ContactUi` deliberadamente não expõe `displayOnly`;
  os contatos "só para exibição" do cofre decoy não têm endereço onion, então não há para onde
  enviar — eles ficam fora da lista de destinos.
- **Higiene de memória.** `database.listGroups()` materializa o estado MLS de **todos** os grupos.
  Cada `candidate.state` é zerado num `finally` imediatamente depois de ler `id`/`name`, seguindo o
  mesmo padrão do `group?.state?.fill(0)` que já existia logo abaixo. Nenhum byte de estado MLS
  chega ao `UiState`.
- **`candidate.state.isNotEmpty()`** é uma segunda barreira, redundante com `group_left` mas barata:
  é a mesma condição que `MessagingEngine.sendApplication` exige para aceitar um envio.

---

### 3. `forwardMessage(messageId, targetChatIds)` — novo

```kotlin
override fun forwardMessage(messageId: String, targetChatIds: List<String>) = action {
    val targets = targetChatIds.distinct().filter { it.isNotBlank() }
    if (targets.isEmpty()) return@action
    val record = db().getMessage(messageId) ?: return@action
    val decoded = try { EnvelopeCodec.decode(record.body) } finally { record.body.fill(0) }
    when (decoded) {
        is Envelope.Text -> targets.forEach { target -> activeEngine().sendText(target, decoded.body, forwarded = true) }
        is Envelope.Attachment -> {
            val attachmentId = decoded.fileId.joinToString("") { "%02x".format(it.toInt() and 255) }
            val (file, bytes) = activeEngine().decryptAttachment(attachmentId)
            try {
                targets.forEach { target ->
                    activeEngine().sendAttachment(target, file.displayName, file.mimeType, bytes, forwarded = true)
                }
            } finally { bytes.fill(0) }
        }
        else -> Unit
    }
}
```

**Decisões:**

- **`action { }`**, como `openAttachment`/`sendText`: o bloco já roda em `Dispatchers.IO`, dentro de
  `mutex.withLock`, com checagem de geração de sessão, e dispara `refresh()` ao final. Zero
  concorrência nova introduzida.
- **`distinct()`**: uma seleção múltipla que, por qualquer motivo, repita um chat gera um envio só.
- **Anexo é re-encriptado, não re-emitido.** O histórico persistido guarda `ciphertext` vazio e
  `fileKey` zerada (`MessagingEngine.history`), então os bytes têm que vir do armazenamento de
  arquivos cifrados via `decryptAttachment`. Como `sendAttachment` gera `fileId` e chave de arquivo
  **novos** para cada destino, nada liga as cópias entre si nem à conversa de origem. O hex do
  `fileId` é montado com o mesmo padrão já usado em `refresh()`/`openAttachment`.
- **`bytes.fill(0)` num `finally`**, e `record.body.fill(0)` também — mesmo padrão de zeragem de
  buffer sensível de `acceptAttachment`/`openAttachment`. O `Reader` do `EnvelopeCodec` copia cada
  campo (`copyOfRange`), então zerar `record.body` depois do `decode` é seguro.
- **`else -> Unit`**: defensivo. Só mensagens de conversa viram `MessageUi`, então nenhum outro tipo
  de envelope deveria chegar aqui; ignorar em silêncio evita transformar uma linha estranha do banco
  em um erro na tela.
- **Nenhuma lógica de pausa de outbox foi escrita.** `sendText`/`sendAttachment` →
  `sendApplication` → `queue()` → tabela `outbox`, drenada só por `outboxLoop()`, que já pausa
  sozinho (`val live = transport; if (live == null) { delay(500); continue }`) quando não há
  transporte Tor. Encaminhar reaproveita esse caminho inteiro, então com a rede fora do ar as cópias
  simplesmente ficam `PENDING` — comportamento idêntico ao de qualquer mensagem normal.
- **Sem limite extra de destinatários.** Os limites que já existem na fila de saída são os mesmos que
  valem para qualquer envio.

### Vantagens

- A Parte C (UI) não precisa de nenhuma consulta ao banco: `state.forwardTargets` já vem pronto e
  filtrado, e `actions.forwardMessage(id, selecionados)` é uma chamada única.
- Nenhum caminho novo de rede, de criptografia ou de concorrência: encaminhar é literalmente um envio
  normal com um bit ligado.
- A lógica de status de grupo deixou de existir só para o chat aberto sem ser duplicada — virou uma
  função nomeada (`statusOf`) usada nos dois lugares.

---

### Adendo (2026-09-17, mesmo dia) — `forwarded` passa a ser decidido por destino

**Motivo**

Regra de produto adicional do usuário: a etiqueta "Encaminhada" **não** deve aparecer quando o
destino escolhido é a **mesma conversa de origem** da mensagem. Reenviar dentro do próprio chat é
indistinguível de digitar o texto de novo, e rotular isso como encaminhamento afirma uma
proveniência que não existe.

**Como era antes**

```kotlin
is Envelope.Text -> targets.forEach { target -> activeEngine().sendText(target, decoded.body, forwarded = true) }
...
        activeEngine().sendAttachment(target, file.displayName, file.mimeType, bytes, forwarded = true)
```

`forwarded = true` incondicional, para todo destino.

**Como é agora**

```kotlin
is Envelope.Text -> targets.forEach { target ->
    activeEngine().sendText(target, decoded.body, forwarded = shouldMarkForwarded(record.peerOrGroup, target))
}
...
        activeEngine().sendAttachment(
            target, file.displayName, file.mimeType, bytes,
            forwarded = shouldMarkForwarded(record.peerOrGroup, target))
```

`record.peerOrGroup` é o chat ao qual a mensagem original pertence (o "chat de origem");
`shouldMarkForwarded` é a função pura nova de `ui/UiLogic.kt` (mesmo módulo `app`, já visível pelo
`import dev.mx3.nomessages.ui.*` que existia no topo do arquivo — nenhum import novo foi preciso).
O KDoc de `forwardMessage` ganhou o parágrafo que registra a regra.

**O que deliberadamente NÃO mudou**

- `targetChatIds` **não** é filtrado para excluir o chat de origem. O usuário pode continuar
  escolhendo a própria conversa como destino; a cópia é enviada normalmente, só sem a etiqueta.
- O campo `forwarded` do envelope, seu formato no fio (wire v2) e a coluna `forwarded` de `messages`
  são exatamente os mesmos. Mudou só a **decisão de quando** o valor é `true`.
- Nenhuma tela foi tocada: a regra é de dado/negócio, não de apresentação.

**Vantagens**

- A etiqueta volta a significar uma única coisa verificável: "esta cópia atravessou de uma conversa
  para outra". Antes ela também aparecia em casos onde essa afirmação era falsa.
- A regra é uma função pura coberta por teste JVM, e não um `if` embutido no laço de envio, então
  mudá-la depois (encaminhar para si mesmo, mensagens de grupo, etc.) não exige emulador.

---

## 2026-09-17 — Fetch do bundle de pareamento sobre Tor, campo doorbell reservado (QR formato 2, T4.16)

### Motivo

O QR de pareamento formato 2 (`docs/development/doorbell-design.md`, `core/.../protocol/Pairing.kt`)
deixou de carregar o bundle PQXDH e passou a carregar só o hash dele; o bundle em si precisa ser
buscado sobre Tor depois da leitura do QR. `NoMessagesController` é quem orquestra essa busca —
`MessagingEngine.fetchPeerBundle` faz a rede, `PairingEngine.acceptPeerBundle` verifica o hash, e o
controlador é quem decide quando tentar, quando repetir e quando desistir, além de publicar o status
que a tela de pareamento mostra. A mesma revisão de escopo que criou o formato 2 também reservou dois
campos de "doorbell" (endereço/token de um segundo onion, para T4.17) dentro do QR assinado, para não
exigir uma segunda migração de formato depois — o controlador precisa mintar e fornecer a chave de
identidade desse segundo onion, sem implementar nenhum comportamento sobre ela ainda.

### 1. `doorbellIdentityKey` — chave de identidade do onion-campainha, minted sob demanda

**Como era antes**

Não existia: `PairingEngine` não tinha esse parâmetro de construção.

**Como ficou**

```kotlin
/**
 * This device's doorbell onion identity key, minting and storing its dedicated seed on first use.
 *
 * Reserved for T4.17: no service listens on this address yet, and nothing outside the pairing
 * offer reads it. It exists now so the QR format does not have to change a second time.
 *
 * The seed is its own `meta` record rather than a reuse of `onion_seed`, so the doorbell address
 * and the messaging address are unlinkable: deriving both from one seed would let anyone holding
 * either address confirm they belong to the same device. `meta` and not an `opaque_blobs`
 * namespace because `onion_seed` - the record this is a sibling of - lives in `meta`, and
 * `SPEC.md` §10 enumerates which table each runtime record belongs to; a sibling in the other
 * table would contradict it for no gain. A vault created before T4.16 simply has no such key
 * and mints one here the first time it is unlocked, which is the same on-demand generation the
 * messaging seed already uses.
 *
 * Never returns the seed: the caller gets the public key, and the seed copy is wiped here.
 */
private fun doorbellIdentityKey(database: ChatDatabase): ByteArray {
    val stored = database.getMeta(DOORBELL_SEED_KEY)
    val seed = stored ?: crypto.random(32)
    return try {
        if (stored == null) database.putMeta(DOORBELL_SEED_KEY, seed)
        OnionAddress.publicKey(TorNative.address(seed))
    } finally { seed.fill(0) }
}
```

com `DOORBELL_SEED_KEY = "doorbell_seed"` no `companion object`.

**Por que uma seed separada de `onion_seed`.** O onion de mensagens (`onion_seed`) e o futuro onion
de campainha derivam ambos de uma seed de 32 bytes via `TorNative.address(seed)`. Se as duas
derivassem da **mesma** seed, os dois endereços onion resultantes seriam matematicamente ligáveis:
qualquer parte que descobrisse os dois endereços (por exemplo, um contato malicioso que também
recebesse o endereço de campainha no futuro, cruzando com o endereço de mensagens que já tem) poderia
confirmar que pertencem ao mesmo dispositivo apenas verificando se derivam da mesma raiz — quebrando
exatamente a propriedade de não-vinculação que o design do onion-campainha existe para garantir.
Uma seed independente, com seu próprio registro `meta`, torna os dois endereços criptograficamente
não relacionados: conhecer um não dá nenhuma vantagem computacional para derivar ou verificar o
outro.

**Por que a função nunca devolve a seed.** Só a chave pública de 32 bytes sai da função; a cópia
local da seed é zerada (`seed.fill(0)`) no `finally`, o mesmo padrão de higiene de memória usado em
todo o resto do arquivo para material sensível.

**A DEVIATION explícita: minted, não recuperado de forma diferente para cofre novo vs. antigo.** Um
cofre criado antes do T4.16 simplesmente não tem a chave `doorbell_seed` em `meta` ainda —
`database.getMeta(DOORBELL_SEED_KEY)` devolve `null`, uma seed nova de 32 bytes é gerada e persistida
na primeira vez que esse cofre é destravado depois da atualização. É a mesma estratégia de geração
sob demanda que a seed de mensagens já usa; não há caminho de migração separado nem "primeira
execução do app" especial — a primeira chamada a `doorbellIdentityKey` depois da atualização, seja
ela em que contexto for, já resolve e persiste a chave.

### 2. Construtor de `PairingEngine` ganha a chave de campainha

**Como era antes**

```kotlin
val localPairing = PairingEngine(crypto, localIdentity, onion, consumed = consumed)
```

**Como ficou**

```kotlin
val localPairing = PairingEngine(crypto, localIdentity, onion, doorbellIdentityKey(database), consumed = consumed)
```

A chave calculada por `doorbellIdentityKey` entra como quarto argumento posicional do construtor de
`PairingEngine` (`core/.../protocol/Pairing.kt`), que passa a exigir e validar 32 bytes não-nulos
(`require(doorbellKey.size==32 && doorbellKey.any { it.toInt()!=0 })`). `PairingEngine.createOffer`
inclui essa chave dentro do campo `doorbellKey` de todo `Offer` que este dispositivo assina — coberto
pela mesma assinatura Ed25519 e, portanto, pelo mesmo SAS que os dois humanos comparam em voz alta,
sem hashing ou caminho de assinatura separado.

### 3. `attachPairingBundles` — o motor passa a atender pedidos de bundle

```kotlin
identity = localIdentity
pairing = localPairing
// The engine answers incoming BundleRequests straight out of the pairing engine. It is
// called from `accept`, i.e. under this controller's mutex, which is the only lock
// `localPairing` is ever mutated under.
localEngine.attachPairingBundles { nonce -> localPairing.bundleFor(nonce) }
```

O lambda instalado fecha sobre `localPairing` (a instância deste `PairingEngine`, não uma referência
global) e delega inteiramente para `PairingEngine.bundleFor`, cuja regra de admissão está documentada
em `docs/changes/MessagingEngine.kt.md` e `docs/changes/MessagingPolicy.kt.md`. O comentário no
código explica a segurança de concorrência: `MessagingEngine.accept` roda dentro de
`mutex.withLock` no laço de recepção, e esse `mutex` é o mesmo lock (`this.mutex`, do controlador)
sob o qual `localPairing` é sempre mutado — então o estado de pareamento lido dentro do lambda não
pode mudar por baixo dele.

### 4. `startBundleFetch` / `publishBundleStatus` / `retryPairingBundle` — o ciclo de vida do job

**`bundleFetch: Job?`**, novo campo do controlador:

```kotlin
/**
 * The in-flight Tor fetch of the peer's PQXDH key bundle (QR format 2, T4.16). At most one runs
 * at a time: a new exchange, a retry, a cancellation and a lock all replace or cancel it, so a
 * fetch belonging to an abandoned exchange can never land on a newer one.
 */
private var bundleFetch: Job? = null
```

Cinco pontos do arquivo tocam este campo, cobrindo todo o ciclo de vida:

| Onde | O que faz | Por quê |
|---|---|---|
| `showPairing()` | `bundleFetch?.cancel(); bundleFetch = null` antes de criar a nova oferta | Uma nova oferta local zera qualquer busca pendente da oferta anterior — não há mais peer/nonce a que ela pertença. |
| `readPairing(code)` | `startBundleFetch(next)` logo após publicar `WAITING_FOR_TOR` | É aqui que a direção "quem acabou de ler o QR busca de quem o mostrou" dispara: ler a resposta do par é o gatilho da busca. |
| `finish(...)` (dentro de `confirmPairing`/fluxo de conclusão) | `bundleFetch?.cancel(); bundleFetch = null` após o pareamento completar | Depois de concluído não há mais nada a buscar; um job residual poderia, em teoria, sobrepor um resultado tardio. |
| `retryPairingBundle()` | reinicia via `startBundleFetch(current)` só se não houver um job ativo | É o botão "tentar de novo" da UI depois de `FAILED`. |
| `cancelPairing()` / `lock()` (bloqueio do cofre) | `bundleFetch?.cancel(); bundleFetch = null` | Cancelar o pareamento ou bloquear o cofre não pode deixar uma corrotina de rede viva presa a um `PairingEngine` que está prestes a ser fechado. |

`startBundleFetch` sempre começa cancelando qualquer job anterior (`bundleFetch?.cancel()`) antes de
atribuir o novo — garantindo que nunca há dois fetches concorrentes para o mesmo `progress`.

```kotlin
private fun startBundleFetch(target: PairingProgress) {
    val token = generation.get()
    val scope = sessionScope ?: return
    val handle = target.handle
    val onion = target.peerOnion
    val nonce = target.peerBundleNonce.copyOf()
    val deadline = target.expiresAt * 1000
    bundleFetch?.cancel()
    bundleFetch = scope.launch(Dispatchers.IO) {
        try {
            while (System.currentTimeMillis() < deadline) {
                val live = engine ?: break
                if (!live.transportAttached) {
                    publishBundleStatus(token, handle, PairingBundleStatus.WAITING_FOR_TOR)
                    delay(1_000)
                    continue
                }
                publishBundleStatus(token, handle, PairingBundleStatus.FETCHING)
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val bundle = try {
                    live.fetchPeerBundle(onion, nonce, remaining.coerceAtMost(BUNDLE_ATTEMPT_MILLIS))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    delay(BUNDLE_RETRY_DELAY_MILLIS)
                    continue
                }
                var accepted = false
                try {
                    mutex.withLock {
                        if (token != generation.get() || session == null || progress?.handle != handle) return@withLock
                        // A mismatch here means the bytes did not hash to what the peer signed:
                        // acceptPeerBundle burns the exchange, and this must surface as a
                        // pairing failure, never as another retry.
                        progress = checkNotNull(pairing).acceptPeerBundle(handle, bundle)
                        persistIdentity()
                        accepted = true
                        refresh()
                    }
                } finally { bundle.fill(0) }
                if (accepted) publishBundleStatus(token, handle, PairingBundleStatus.READY)
                return@launch
            }
            publishBundleStatus(token, handle, PairingBundleStatus.FAILED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publishBundleStatus(token, handle, PairingBundleStatus.FAILED)
        } finally { nonce.fill(0) }
    }
}

/** Only touches the banner while [handle] is still the exchange on screen. */
private fun publishBundleStatus(token: Long, handle: String, status: PairingBundleStatus) {
    publish(token) { previous ->
        val current = previous.pairing
        if (current == null || current.completed || progress?.handle != handle) previous
        else previous.copy(pairing = current.copy(bundleStatus = status))
    }
}

override fun retryPairingBundle() {
    val current = progress ?: return
    if (current.peerBundleReady) return
    if (bundleFetch?.isActive == true) return
    startBundleFetch(current)
}
```

**Por que o fetch roda no `sessionScope`, sem o mutex do controlador, e só toma o lock no
verify-and-persist.** O laço inteiro (`while (System.currentTimeMillis() < deadline) { ... }`) roda
fora de `mutex.withLock`; a única seção protegida é o bloco curto que chama `acceptPeerBundle`,
`persistIdentity` e `refresh()`. A razão é a mesma que faz `MessagingEngine.fetchPeerBundle` não
segurar o mutex do motor (ver `docs/changes/MessagingEngine.kt.md`), só que um nível acima: enquanto
este dispositivo está suspenso dentro de `live.fetchPeerBundle(...)` esperando uma circuito Tor de
5–40 s até o par, o par está simultaneamente rodando a mesma busca em espelho contra este
dispositivo — e atender esse pedido de entrada (via `MessagingEngine.answerBundleRequest`, disparado
de dentro de `accept`) precisa do **mesmo** `mutex` do controlador que protege `pairing`/`progress`.
Se `startBundleFetch` segurasse esse mutex durante toda a espera de rede, dois aparelhos que se
escanearam ao mesmo tempo (o caso comum, não o raro) travariam um esperando o outro liberar o lock
que o outro também está esperando. Tomar o lock só para o passo final — verificar o hash e persistir
— mantém essa seção curta e determinística, sem I/O de rede dentro dela.

Dentro do bloco protegido, três checagens (`token != generation.get()`, `session == null`,
`progress?.handle != handle`) garantem que um resultado tardio de um job cancelado/substituído nunca
sobrescreve um estado de pareamento mais novo — a mesma disciplina de `token`/`generation` já usada
em outros pontos assíncronos do controlador.

**Retry até a deadline, não erro imediato.** Uma falha de `fetchPeerBundle` (timeout, onion ainda não
publicado, circuito que não fecha) não aborta a busca — o laço volta, espera
`BUNDLE_RETRY_DELAY_MILLIS` e tenta de novo, até `deadline` (o `PairingProgress.expiresAt`, a janela
de 300 s da troca) se esgotar. Isso reflete a realidade observada de onion services recém-publicados,
que costumam precisar de algumas tentativas antes de ficarem alcançáveis.

**`WAITING_FOR_TOR` vs. `FETCHING`.** Enquanto `live.transportAttached` é falso, o status publicado é
`WAITING_FOR_TOR` ("meu próprio Tor ainda não está pronto"); só depois que o transporte está anexado
o status vira `FETCHING` ("estou tentando alcançar o outro aparelho"). São problemas diferentes com
remédios diferentes para o usuário, daí campos de enum separados em vez de um único "carregando".

### 5. `BUNDLE_ATTEMPT_MILLIS` e `BUNDLE_RETRY_DELAY_MILLIS`

```kotlin
/**
 * Budget for one attempt of the pairing key-bundle fetch (T4.16). A circuit to a fresh onion
 * service commonly costs 5-40 s; 60 s per attempt leaves room for a slow one while still
 * fitting several attempts inside the 300 s exchange deadline.
 */
private const val BUNDLE_ATTEMPT_MILLIS = 60_000L
/** Pause between fetch attempts, so a peer that is not up yet is not hammered. */
private const val BUNDLE_RETRY_DELAY_MILLIS = 3_000L
```

`BUNDLE_ATTEMPT_MILLIS = 60_000` é o teto por tentativa individual (repassado a
`fetchPeerBundle(..., timeoutMillis = remaining.coerceAtMost(BUNDLE_ATTEMPT_MILLIS))`) — grande o
bastante para cobrir os 5–40 s típicos de um circuito novo com folga, mas pequeno o bastante para
caber várias tentativas dentro dos 300 s de `PENDING_TTL_SECONDS`. `BUNDLE_RETRY_DELAY_MILLIS = 3_000`
é a pausa entre uma tentativa falha e a próxima, para não martelar um onion que ainda não subiu.

### 6. `confirmPairing` passa a exigir `peerBundleReady`

**Como era antes**

```kotlin
override fun confirmPairing(alias: String) = action {
    if (_state.value.pairing?.completed == true || confirmed) return@action
    require(alias.trim().length in 1..80)
    val current = checkNotNull(progress)
    val confirmation = checkNotNull(pairing).confirm(current.handle, true)
    ...
```

**Como ficou**

```kotlin
override fun confirmPairing(alias: String) = action {
    if (_state.value.pairing?.completed == true || confirmed) return@action
    require(alias.trim().length in 1..80)
    val current = checkNotNull(progress)
    // Checked here and not only at `finish`: the user should learn that the other phone is
    // unreachable while the retry button is still on screen, not after both confirmation QRs
    // have been exchanged and there is nothing left to do but start over.
    require(current.peerBundleReady) { context.getString(R.string.error_pairing_bundle_missing) }
    val confirmation = checkNotNull(pairing).confirm(current.handle, true)
    ...
```

**Por que checar aqui e não só em `finish`.** `PairingEngine.finish` já recusa completar sem o bundle
do par (`peerBundleReady` falso), então tecnicamente bastaria deixar essa checagem existir só lá. Mas
`confirmPairing` é o passo em que o usuário confirma o SAS em voz alta e a UI troca o QR de
confirmação — depois desse ponto, o fluxo natural para o usuário é "já terminei, é só o outro escanear
minha confirmação". Se o bundle do par ainda não tiver chegado nesse momento, exigir a checagem aqui
faz o usuário descobrir o problema **enquanto o botão de retry ainda está na tela** (o botão só é
oferecido enquanto a deadline permite, `PairingLifecycle.canRetryBundleFetch`), em vez de descobrir
só depois de já ter trocado os dois QRs de confirmação — momento em que não resta nada a fazer a não
ser recomeçar a troca inteira do zero.

### 7. `showPairing` usa `PairingLifecycle.OFFER_TTL_MILLIS`

**Como era antes**

```kotlin
override fun showPairing() = action {
    pairing?.cancel(); progress = null; confirmed = false; pairingAlias = ""
    val code = checkNotNull(pairing).createOffer()
    persistIdentity()
    _state.update { previous -> if (shuttingDown) previous else previous.copy(pairing = PairingUi(code, expiresAt = System.currentTimeMillis() + 120_000)) }
}
```

**Como ficou**

```kotlin
override fun showPairing() = action {
    bundleFetch?.cancel(); bundleFetch = null
    progress = null; confirmed = false; pairingAlias = ""
    val code = checkNotNull(pairing).createOffer()
    persistIdentity()
    _state.update { previous -> if (shuttingDown) previous else previous.copy(
        pairing = PairingUi(code, expiresAt = System.currentTimeMillis() + PairingLifecycle.OFFER_TTL_MILLIS)) }
}
```

A constante mágica `120_000` foi substituída pela constante nomeada `PairingLifecycle.OFFER_TTL_MILLIS`
(também `120_000L`, definida no novo `app/.../ui/PairingLifecycle.kt`) — o mesmo número, mas agora com
uma única fonte de verdade compartilhada com a máquina de estados pura que decide regeneração e
cancelamento automáticos do QR (`PairingLifecycle.nextAction`). Note também que a linha
`pairing?.cancel()` do topo virou `bundleFetch?.cancel(); bundleFetch = null` — cancelar o
`PairingEngine` inteiro a cada nova exibição de QR foi substituído por cancelar só a busca de bundle
em andamento, já que `pairing` (a instância de `PairingEngine`) é de longa duração e `createOffer()` já
cuida de substituir a oferta antiga preservando a oferta `superseded` respondível.

### 8. `finish` grava `doorbellOnion`/`doorbellToken` do par no contato

**Como era antes**

```kotlin
putContact(ContactRecord(contact.peerId, pairingAlias.ifBlank { context.getString(R.string.default_contact_alias, contact.peerId.take(6)) },
    contact.onion, contact.publicKey, checkNotNull(identity).sessions.peerIdentity(contact.peerId), contact.pairedAt * 1000))
```

**Como ficou**

```kotlin
// The peer's doorbell address and token come from its signed offer, so they are
// covered by the SAS the two humans just compared. They are stored now because
// there is no second authenticated channel to obtain them on later; nothing
// reads them until T4.17.
putContact(ContactRecord(contact.peerId, pairingAlias.ifBlank { context.getString(R.string.default_contact_alias, contact.peerId.take(6)) },
    contact.onion, contact.publicKey, checkNotNull(identity).sessions.peerIdentity(contact.peerId), contact.pairedAt * 1000,
    doorbellOnion = OnionAddress.address(contact.doorbellKey), doorbellToken = contact.doorbellToken))
bundleFetch?.cancel(); bundleFetch = null
```

`contact.doorbellKey` é a chave de identidade de 32 bytes de campainha do par, extraída do `Offer`
assinado que ele apresentou; `OnionAddress.address(...)` (novo helper compartilhado, ver
`docs/changes/OnionAddress.kt.md`/`DecoyFactory.kt.md`) reconstrói o endereço `.onion` textual completo
de 62 caracteres a partir dela, e é esse endereço reconstruído — não a chave crua — que fica gravado
em `contacts.doorbell_onion`. `contact.doorbellToken` (32 bytes, fresco por oferta) é gravado
diretamente em `contacts.doorbell_token`.

**Por que gravar agora, se nada lê ainda (reservado para T4.17).** Não existe, hoje, nenhum segundo
canal autenticado entre os dois dispositivos além do próprio pareamento — depois que a troca de
ofertas assinadas e a comparação de SAS terminam, este é o único momento em que este dispositivo tem
esses dois valores em mãos com a garantia de terem vindo do par certo (cobertos pela mesma assinatura
Ed25519 e pelo mesmo SAS que autenticou todo o resto da oferta). Esperar para gravá-los "quando o
T4.17 precisar" exigiria inventar um segundo canal autenticado só para obtê-los depois — trabalho
redundante e desnecessário quando o valor já está disponível, verificado, no momento exato em que o
contato é criado.

### Vantagens

- A busca do bundle nunca pode deadlockar dois aparelhos que se pareiam simultaneamente — nem no
  motor (`fetchPeerBundle`) nem no controlador (`startBundleFetch`), pelos mesmos motivos em cada
  camada.
- O usuário aprende que o outro aparelho está inalcançável no momento em que ainda pode agir (retry
  visível antes da confirmação), não depois de já ter trocado ambos os QRs de confirmação.
- A chave de identidade do onion-campainha nasce e persiste de forma correta desde já (seed própria,
  não vinculável ao onion de mensagens), sem exigir uma segunda migração de formato de QR quando o
  T4.17 chegar.
- Uma única constante nomeada (`PairingLifecycle.OFFER_TTL_MILLIS`) substitui um número mágico
  duplicado, compartilhada com a máquina de estados pura que decide regeneração/cancelamento
  automáticos.

### Validação

Ver `docs/development/T4.16` (fact sheet): `BUILD SUCCESSFUL` para
`:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
(2026-09-17). `:app:testDebugUnitTest` — 60 testes, 0 falhas, 0 erros, 1 pulado. Nenhuma validação em
aparelho/emulador foi executada para esta tarefa; o novo teste instrumentado de migração de schema
(`doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration`, ver
`docs/changes/AndroidVaultStorageTest.kt.md`) foi escrito mas nunca executado.

## 2026-09-18 — `finish` também grava `doorbellTokenIssued` no contato (T4.17)

### Motivo

Mesma fase do T4.17 documentada em `docs/changes/ChatDatabase.kt.md` e `docs/changes/DecoyFactory.kt.md`
(2026-09-18): a coluna nova `contacts.doorbell_token_issued` (schema v4) precisa ser preenchida no
único ponto do controlador que monta um `ContactRecord` a partir de um pareamento concluído — o mesmo
`finish` que, no T4.16, já passou a gravar `doorbellOnion`/`doorbellToken` (ver seção "8. `finish`
grava `doorbellOnion`/`doorbellToken` do par no contato" acima).

### Como era antes

```kotlin
putContact(ContactRecord(contact.peerId, pairingAlias.ifBlank { context.getString(R.string.default_contact_alias, contact.peerId.take(6)) },
    contact.onion, contact.publicKey, checkNotNull(identity).sessions.peerIdentity(contact.peerId), contact.pairedAt * 1000,
    doorbellOnion = OnionAddress.address(contact.doorbellKey), doorbellToken = contact.doorbellToken))
```

### Como ficou

```kotlin
// The peer's doorbell address and token come from its signed offer, so they are
// covered by the SAS the two humans just compared. They are stored now because
// there is no second authenticated channel to obtain them on later; nothing
// reads them until T4.17.
//
// `doorbellTokenIssued` is the other direction of the same exchange: the secret
// THIS device minted inside its own offer for this one peer, which is what an
// incoming ring from it will present. `finish` is the last moment it exists -
// it lived only inside the pairing offer, which is discarded here - so failing
// to persist it in this very statement loses it for good and leaves the contact
// able to be rung but unable to be answered. Schema v4 exists for this column.
putContact(ContactRecord(contact.peerId, pairingAlias.ifBlank { context.getString(R.string.default_contact_alias, contact.peerId.take(6)) },
    contact.onion, contact.publicKey, checkNotNull(identity).sessions.peerIdentity(contact.peerId), contact.pairedAt * 1000,
    doorbellOnion = OnionAddress.address(contact.doorbellKey), doorbellToken = contact.doorbellToken,
    doorbellTokenIssued = contact.doorbellTokenIssued))
```

Um argumento nomeado a mais, no mesmo construtor de `ContactRecord`, na mesma chamada a `putContact`
dentro de `finish`. Nada além disso mudou nesta chamada.

### Por que este é o único momento possível

`contact.doorbellTokenIssued` chega de `pairingEngine.finish(...)`, que devolve o `PairedContact` — e
é dentro desse mesmo `Contact`/oferta que o valor viveu desde que `PairingEngine.newOffer()` o cunhou
com `crypto.random(32)`. A oferta que carregava esse token é descartada logo depois de `finish`
retornar (o mesmo padrão de vida curta de todo material de pareamento já usado no resto do
controlador). Isso significa que `finish()` é literalmente o último instante em que o valor existe em
qualquer lugar do sistema: não há coluna, cache ou segundo canal onde ele reapareça depois. Não
gravá-lo nesta mesma transação — a mesma chamada a `putContact` que já grava `doorbellOnion`/
`doorbellToken` do par — significaria perdê-lo para sempre e deixar o contato num estado
permanentemente assimétrico: "tocável e não atendível", o mesmo problema estrutural que motivou a
coluna inteira (ver `docs/changes/ChatDatabase.kt.md`, 2026-09-18).

### O que deliberadamente NÃO mudou nesta fase

Fora desta única linha, nada mais em `NoMessagesController.kt` foi tocado. Ligar a campainha de fato —
a ponte Kotlin/`TorService` para o onion dedicado, o modo mínimo de atendimento, carregar no lado
nativo o conjunto de tokens emitidos para reconhecer uma batida recebida — é a fase seguinte do T4.17,
feita por outro agente. Este commit só garante que o dado necessário para essa fase já está no cofre
quando ela chegar, gravado no único momento em que podia ser capturado.

### Vantagens

- O dado é capturado na única janela em que existe, sem nenhuma mudança no formato de QR: o token já
  viajava assinado dentro da oferta desde o T4.16 (ver seção "1. `doorbellIdentityKey`" acima) — só
  não era guardado deste lado até agora.
- Nenhum novo caminho de código, mutex ou estado assíncrono: a gravação entra na mesma transação que
  já persistia o restante do contato dentro de `finish`.
- O escopo desta mudança fica claramente contido a "capturar o dado", deixando "usar o dado" (bridge
  Tor, modo mínimo, verificação nativa) como trabalho isolado e revisável separadamente pela próxima
  fase.


## 2026-09-18 — T4.17 fase 3: modo mínimo, handoff do `:tor` e preferência por cofre

### Motivo

Com o nativo (fase 1) e o armazenamento (fase 2) prontos, falta a decisão de runtime: **bloquear sem
matar o transporte** quando a campainha está ligada, avisar o usuário quando alguém tocar, e devolver
o processo vivo ao desbloqueio — sem jamais confundir o cofre real com o isca.

### 1. Preferência "campainha ligada", por cofre

#### Como ficou

```kotlin
fun isDoorbellEnabled(): Boolean
fun setDoorbellEnabled(enabled: Boolean) = action {
    db().putBlob(VAULT_SETTINGS_NAMESPACE, DOORBELL_ENABLED_KEY, byteArrayOf(if (enabled) 1 else 0))
}
private const val VAULT_SETTINGS_NAMESPACE = "vault_settings"
private const val DOORBELL_ENABLED_KEY = "doorbell_enabled"
private const val DOORBELL_DEFAULT_ENABLED = true
```

#### Decisão: `opaque_blobs`, namespace novo `vault_settings`

`meta` guarda **registros estruturais de runtime** — `onion_seed`, `doorbell_seed`, `identity`,
`lock_timeout`, `bridges`, histórico de guards, sequência do outbox. Isto aqui é preferência de
usuário, da mesma família do que a tela de Configurações já edita. Nenhum dos 14 namespaces
catalogados em `docs/development/runtime-catalog.md` (`group_pending`, `group_left`, `group_error`,
`group_request`, `group_packages`, `key_packages`, `outbox_control`, `outbox_lanes`, `attempts`,
`accepted_ids`, `rejected_ids`, `receipts`, `failed_controls`, `evidence_gossip`) é sobre
preferências: reaproveitar qualquer um deles faria `listBlobs` daquele namespace devolver uma linha
que nenhum leitor dele espera. Daí um namespace novo, e um nome genérico o bastante para a próxima
preferência não precisar de outro.

#### Decisão: o padrão é **LIGADO**

O aviso *é* a funcionalidade. Um cofre que bloqueia com a campainha desligada se comporta exatamente
como um que nunca teve a feature, e um usuário que nunca abre Configurações só descobriria que ela
existe **perdendo mensagem**. O custo do padrão ligado é uma notificação silenciosa enquanto
bloqueado, removível em um toque; o custo do padrão oposto é uma funcionalidade que só funciona para
quem foi procurar. Chave ausente = ligado, o que também resolve de graça todo cofre criado antes
desta fase — inclusive o isca, que não tem nenhum `opaque_blobs` ao ser semeado.

#### Sem bifurcação por slot

`isDoorbellEnabled`/`doorbellEnabled` leem o cofre que estiver aberto, seja ele qual for. Nenhuma
linha nova em nenhum dos caminhos desta fase testa `VaultSlot` para **decidir comportamento**: o
isca liga a campainha, semeia sua própria seed, publica seu próprio endereço e carrega seu próprio
conjunto de tokens (normalmente vazio) exatamente como o real. O único lugar onde `VaultSlot`
aparece é a comparação de identidade do handoff (item 3), que não muda o que é feito, só *para quem*.

### 2. `lock()`: modo mínimo antes de zerar chave nenhuma

#### Como era antes

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    var confirmedDeath = false
    try { oldTransport?.shutdown(); confirmedDeath = true } catch (_: Exception) { }
    finally { try { mutex.withLock {
        try { if (confirmedDeath) persistTorState() } finally { closeOwnedSession() }
    } } catch (_: Exception) { } }
    if (!confirmedDeath) shutdownTransport(oldTransport)
    clearTorStateUntilDone()
    ...
}
```

Ou seja: sempre matar o `:tor`, sempre limpar o diretório de estado do Tor.

#### Como ficou

Antes de tudo, ainda **com o cofre aberto** (é o único momento em que a seed e os tokens são
legíveis) e com o filho ainda vivo:

```kotlin
var kept = false
try { mutex.withLock {
    kept = startMinimalDoorbell(oldTransport)
    if (kept) closeOwnedSession()
} } catch (_: Exception) { }
if (kept) { /* publica estado bloqueado e retorna: sem shutdown, sem clearTorState */ return@launch }
// ... caminho existente, byte por byte igual ao de antes ...
```

`startMinimalDoorbell` executa a ordem exigida pelo nativo, que **não** é intercambiável:

1. `doorbellStart(seed, bridges)` — seed lida de `meta["doorbell_seed"]`, cunhada preguiçosamente
   (mesmo padrão de `onion_seed`), passada ao nativo e a cópia local zerada no `finally`.
2. `doorbellTokens(...)` — `db().listContacts()` filtrado por `doorbellTokenIssued.size == 32`
   (contatos pareados antes da fase 2 têm coluna vazia); cada token é zerado depois de enviado.
3. `doorbellMinimal()` — derruba só o serviço de mensagens e promove o `:tor` a foreground.
4. Só então `closeOwnedSession()` faz o que sempre fez: fecha o banco, zera MK/DBK/FBK/ratchet,
   descarta engine, identidade e sessão.

Se qualquer passo falhar, `doorbellStop()` é tentado e a função devolve `false` — o lock inteiro cai
no caminho antigo, matando o processo. Meia campainha (onion vivo sem ninguém drenando) seria pior
que nenhuma: deixaria uma notificação que nada limparia.

**Duas coisas deixaram de acontecer no caminho da campainha, de propósito:**

- `clearTorStateUntilDone()` — o cliente Arti vivo é dono daquele diretório. Apagá-lo por baixo dele
  é arrancar exatamente o histórico de guards que o snapshot existe para preservar.
- `persistTorState()` — capturar o diretório enquanto o cliente escreve nele produziria um snapshot
  possivelmente inconsistente; o último snapshot bom continua no cofre, e os checkpoints normais
  voltam assim que o transporte reaproveitado ficar online.

Um `lock()` repetido enquanto já se está em modo mínimo agora retorna imediatamente
(`if (minimal != null && session == null) return`), justamente para não rodar aquele
`clearTorStateUntilDone()` contra o diretório da campainha viva.

### 3. `activate()`: reaproveitar o `:tor` vivo — e como "mesmo cofre" é detectado

#### Estado em memória, nunca em disco

```kotlin
private class MinimalDoorbell(val slot: VaultSlot, val transport: TorConnection)
@Volatile private var minimal: MinimalDoorbell? = null
private var handoff: TorConnection? = null
```

"Mesmo cofre" é decidido comparando o **slot** (`REAL`/`DECOY`) gravado no momento do lock com
`opened.slot` do cofre que está sendo aberto agora. Não a seed, não o onion, não a identidade:
qualquer coisa derivada do cofre exigiria manter material do cofre **enquanto ele está bloqueado**,
que é precisamente o que o lock existe para impedir; e persistir esse dado deixaria em disco um
registro de qual cofre foi aberto por último. Como é memória do processo, se o processo morrer o
campo morre junto — e o filho morre com ele (o `TorService` está vinculado à morte do Binder do
cliente). O resultado de qualquer perda de estado é sempre o conservador: destruir, nunca reaproveitar
errado.

#### O handoff

`prepareDoorbellHandoff(slot)` roda em `unlock()` **antes** de `activate()`, dentro do mesmo
`mutex.withLock`:

- mesmo slot e filho vivo → `handoff = transport = kept.transport`;
- qualquer outro caso (slot diferente, `null`, filho morto) → `shutdownTransport(kept.transport)`,
  que é o teardown total de sempre (`Process.killProcess` dentro de `TorConnection.shutdown`).

`attemptTransport` consome o handoff **uma vez**, e a ordem dentro dele é a exigida pela fase 1:

```kotlin
val reused = synchronized(lifecycleGuard) { handoff.also { handoff = null } }?.takeIf { it.alive }
if (reused == null && !recycleTransport(token, localEngine)) return TransportAttempt.FAILED
val tor = reused ?: TorConnection(context)
...
try { if (reused != null) tor.restart(seed, bridges) else tor.start(seed, bridges) } finally { seed.fill(0) }
if (reused != null) try { tor.doorbellStop() } catch (_: Exception) { }
```

Primeiro `start` (mensagens adota o host Arti ainda vivo), **depois** `doorbellStop`. Invertido, a
campainha seria a única dona do host no instante em que fosse parada e levaria o runtime junto — o
desbloqueio pagaria o bootstrap inteiro. Uma segunda tentativa (se a primeira falhar) já não encontra
handoff e volta ao caminho normal de filho novo.

`activate()` também **pula** `TorStateSnapshot.clear`/`restore` quando há handoff, pelo mesmo motivo
que `lock()` pula a limpeza: o cliente vivo é dono daqueles arquivos.

#### Senha de pânico

Não existe função de pânico separada neste controller — foi verificado lendo o código: a senha de
pânico entra por `unlock()`, e `VaultManager.unlock` resolve o cabeçalho para o slot `DECOY`
(`changePanicPassword` só **redefine** a senha, não é o caminho de acionamento). Portanto o pânico
cai naturalmente no ramo "cofre diferente" de `prepareDoorbellHandoff` e recebe **teardown total e
incondicional**: o `:tor` é morto de verdade antes de o cofre-isca subir do zero. Isso não é uma
gentileza — os tokens carregados naquele filho pertencem ao cofre que o usuário acabou de abandonar
na frente de quem o obrigou a digitar a senha de pânico; deixá-lo respondendo batidas seria vazar
exatamente o que o cofre-isca existe para esconder.

Depois disso, o isca segue o fluxo unificado: ao ser bloqueado com a opção ligada (o padrão), ele
sobe **sua** campainha, com **sua** seed, pelos mesmos `lock()`/`activate()`. Nenhum código especial
de "campainha do pânico" existe, e nenhuma checagem de slot decide comportamento em ponto algum.

### 4. Consumo de batida aceita

```kotlin
private fun watchDoorbell(tor: TorConnection) {
    doorbellWatch?.cancel()
    doorbellWatch = viewModelScope.launch(Dispatchers.IO) {
        try { while (isActive) { if (tor.doorbellPoll(DOORBELL_POLL_MILLIS) > 0) notifications.showDoorbellPending() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
}
```

`DOORBELL_POLL_MILLIS = 25_000`: logo abaixo do teto nativo de 30 s, então o processo dorme quase
todo o intervalo (nada de timer acordando o rádio) e ainda assim o laço volta com frequência
suficiente para observar o cancelamento do desbloqueio. **Não** roda no `sessionScope` — esse escopo
é cancelado justamente pelo lock que inicia o laço; roda no `viewModelScope` e é cancelado à mão em
`prepareDoorbellHandoff` e em `onCleared`. Qualquer falha encerra o laço em silêncio: as únicas
alcançáveis são "filho morreu" e "campainha parada", e em ambas não há nada para o usuário fazer.

`activate()` chama `notifications.cancelDoorbellPending()` **incondicionalmente na primeira linha**,
mesmo que ninguém tenha tocado e qualquer que seja o cofre — a notificação nunca sobrevive a um
desbloqueio, e cancelar é idempotente.

### 5. Refatoração de apoio

`doorbellIdentityKey` foi dividido: a leitura/cunhagem da seed virou `doorbellSeed(database)`, que é
o **único** ponto que entrega a seed em si, sempre como cópia que o chamador zera. Os dois leitores
(`doorbellIdentityKey`, que só deriva a chave pública, e `startMinimalDoorbell`, que a passa ao
`:tor`) zeram imediatamente. Comportamento externo idêntico ao de antes.

### Vantagens

- O lock continua sendo o mesmo teardown criptográfico de sempre: as chaves são zeradas na mesma
  ordem, no mesmo lugar. A única diferença é **não matar** um processo que não guarda chave nenhuma.
- Reaproveitar o `:tor` transforma o desbloqueio de "até 180 s de bootstrap" em "online quase
  imediato" — o modo mínimo deixa de ser um custo e passa a ser uma vantagem de latência.
- Comportamento idêntico entre cofre real e isca, garantido por construção (nenhum `if (slot ==
  REAL)` novo), não por convenção.
- Estado de reaproveitamento em memória: nada novo em disco, nada novo no cofre, nada que uma perícia
  possa correlacionar.
- Falha em qualquer ponto da campainha degrada para o comportamento antigo (matar o `:tor`), nunca
  para um estado intermediário.

### Por que a mudança foi feita

T4.17 fase 3 (bridge Kotlin/serviço). A UI do interruptor em Configurações e a lógica de
`doorbellKnock` no `MessagingEngine` são fases seguintes e **não** foram implementadas aqui; só os
métodos que elas vão consumir.

---

## 2026-09-18 — Log de diagnóstico da busca do pacote de chaves e extração da política de retentativa (T4.18)

### Motivo

Corrida ao vivo de 2026-09-18, aparelho físico real: a 1ª cerimônia celular↔emulador A chegou a
exibir o mesmo SAS `705238` nos dois lados, mas o celular não conseguiu buscar o pacote de chaves do
emulador a tempo. Não havia **nada** em logcat sobre a causa — por projeto, para não vazar metadados
de pareamento — então não dava para distinguir em campo "onion ainda não publicada" de "circuito
estourou o prazo" de "o par respondeu lixo".

Dos três itens do plano, (a) retentativa dentro do prazo e (c) retentativa manual pelo botão da
`PairingScreen` **já existiam** desde T4.16 (`while` até o `deadline` e `retryPairingBundle()`). O
que faltava era (b).

---

### 1. `startBundleFetch()` — o `catch` mudo

**Como era antes**

```kotlin
val bundle = try {
    live.fetchPeerBundle(onion, nonce, remaining.coerceAtMost(BUNDLE_ATTEMPT_MILLIS))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    delay(BUNDLE_RETRY_DELAY_MILLIS)
    continue
}
```

A exceção era descartada no próprio `catch (_: Exception)`. Nenhum `Log` em lugar nenhum do fluxo de
pareamento — nem em debug.

**Como ficou**

```kotlin
} catch (e: Exception) {
    // T4.18: without this line a ceremony that expired to a cold Tor left no
    // trace at all, so field diagnosis could not tell "onion not published yet"
    // from "circuit timed out" from "peer answered garbage". Exception type and
    // message only, and only in debug builds: the onion, the nonce, the bundle
    // and every other piece of pairing metadata stay out of logcat, and release
    // keeps today's fully silent behavior.
    if (BuildConfig.DEBUG) {
        Log.e(TAG, "bundle fetch attempt failed: ${e.javaClass.name}: ${e.message}")
    }
    val pause = BundleFetchPolicy.retryDelay(deadline, System.currentTimeMillis()) ?: break
    delay(pause)
    continue
}
```

**Por que assim, e não de outro jeito**

- **Mesmo padrão já usado em `setup()` e `unlock()` neste arquivo**: `if (BuildConfig.DEBUG) Log.e(TAG,
  "<o quê> failed: ${e.javaClass.name}: ${e.message}")`. Uma disciplina só no arquivo inteiro, e o
  build de release continua **exatamente** tão silencioso quanto era — R8 remove o bloco inteiro.
- **Só tipo e mensagem da exceção.** Nada de onion do par, nada de nonce, nada do pacote, nada de
  `peerId`. O motivo original de não logar nada era não vazar metadados de quem parea com quem; logar
  `SocketTimeoutException: timeout` não vaza isso.
- **Sem o `Throwable` no terceiro argumento**, ao contrário de `unlock()`. Uma stack trace de rede
  atravessa frames do transporte que podem carregar o endereço na mensagem de frames internos; o par
  tipo+mensagem é o que serve ao diagnóstico e é o mínimo suficiente.
- É por tentativa, não por cerimônia: o valor diagnóstico está justamente em ver a sequência
  (`ConnectException`, `ConnectException`, `TimeoutException`) e concluir "Tor frio".

---

### 2. A aritmética do prazo saiu do laço — `BundleFetchPolicy`

**Como era antes** — três decisões implícitas, sem nome e não testáveis (exigiriam `AndroidViewModel`
+ SQLCipher + Tor vivos):

```kotlin
while (System.currentTimeMillis() < deadline) {
    ...
    val remaining = deadline - System.currentTimeMillis()
    if (remaining <= 0) break
    live.fetchPeerBundle(onion, nonce, remaining.coerceAtMost(BUNDLE_ATTEMPT_MILLIS))
```

**Como ficou**

```kotlin
while (BundleFetchPolicy.shouldAttempt(deadline, System.currentTimeMillis())) {
    ...
    val budget = BundleFetchPolicy.attemptBudget(deadline, System.currentTimeMillis())
    if (budget <= 0L) break
    live.fetchPeerBundle(onion, nonce, budget)
```

`BUNDLE_ATTEMPT_MILLIS` e `BUNDLE_RETRY_DELAY_MILLIS` saíram do `companion object` do controller para
`BundleFetchPolicy` (`ATTEMPT_BUDGET_MILLIS` / `RETRY_DELAY_MILLIS`), com os **mesmos valores**: 60 s
e 3 s. Ficou no `companion` apenas `TOR_POLL_MILLIS = 1_000L`, o intervalo de espera pelo transporte —
que não é tentativa nenhuma e por isso não pertence à política.

Arquivo novo documentado em `docs/changes/BundleFetchPolicy.kt.md`; teste em
`docs/changes/BundleFetchPolicyTest.kt.md`. Mesma disciplina de `TransportSupervisor.kt` e
`DoorbellKnockPolicy.kt`: função pura, relógio injetado, teste JVM sem mock.

**Única mudança de comportamento observável**: uma falha ocorrida nos últimos 3 s da janela agora
encerra o laço na hora (`retryDelay` devolve `null`) em vez de dormir 3 s e só então reprovar no
`while`. O banner `FAILED` aparece enquanto o usuário ainda está olhando a tela, não depois de a troca
já ter vencido.

---

### 3. O prazo é 240 s — e continua sendo

A discrepância encontrada: o texto de T4.18 falava em "300 s", mas `deadline` vem de
`target.expiresAt`, que vem de `Pending.expires = now() + PairingEngine.CONFIRMATION_TTL_SECONDS` =
**240 s**. Os 300 s são o `PENDING_TTL_SECONDS`, constante diferente e maior, que rege a fase
*anterior* (aquisição da oferta).

**Decisão: NÃO alterar `CONFIRMATION_TTL_SECONDS`.** Motivo — raio de impacto. Essa constante não
governa só este laço: ela é também a janela de confirmação humana (leitura do SAS em voz alta, digitar
o apelido, mostrar e escanear os dois QRs de confirmação) e é aplicada **independentemente** por
`PairingEngine.get`, o portão de `confirm`/`acceptPeerBundle`/`finish`, em `now() >= p.expires`.
Alargar só o `deadline` do controller para 300 s não teria efeito algum: a chamada seria recusada pelo
motor aos 240 s do mesmo jeito. E alargar a constante mexeria na janela do SAS, que tem
dimensionamento próprio documentado em `Pairing.kt` — outra tarefa, outro risco.

Consequência: a menção a "300 s" no roadmap foi tratada como erro de redação do próprio item e
reconciliada em `docs/superpowers/plans/2026-09-14-roadmap-to-release.md` (T4.18) para
"240 s, `PairingEngine.CONFIRMATION_TTL_SECONDS` — não 300 s, que é o `PENDING_TTL_SECONDS` da fase
anterior", no objetivo, no item (a) e no "Feito quando". O KDoc de `BUNDLE_ATTEMPT_MILLIS`, que também
dizia "300 s exchange deadline", foi corrigido junto na mudança de lugar. T4.18 marcada como `[x]`.

### Vantagens

- Uma falha de pareamento em campo passa a ser diagnosticável num build debug, sem attach de
  debugger e sem instrumentar nada — e o build de release não mudou em um byte de comportamento
  observável.
- A janela de retentativa virou código puro com 9 testes JVM, incluindo a borda exata do prazo.
- A documentação parou de contradizer o código sobre um número (240 × 300) que é justamente o que
  decide se uma cerimônia presencial dá certo ou não.

### Validação

`:app:compileDebugKotlin` BUILD SUCCESSFUL; `:app:testDebugUnitTest` **83 testes, 0 falhas, 0 erros,
1 pulado** (74 antes desta tarefa + 9 novos de `BundleFetchPolicyTest`).

## 2026-09-18 — T4.17 fase 6: a preferência da campainha chega a `UiState` e a `UiActions`

### Como era antes

A fase 3 criou os dois acessos da preferência, mas eles ficaram sem nenhum chamador na interface: o
KDoc de `setDoorbellEnabled` dizia literalmente "The settings screen (a later phase) calls this", e
`isDoorbellEnabled()` não era chamado por ninguém — só `startMinimalDoorbell` lia a preferência, por
dentro, via o privado `doorbellEnabled(database)`. `activate()` publicava `lockTimeoutSeconds` e
`bridges` no `UiState`, e mais nada de preferência.

```kotlin
val timeout = database.getMeta("lock_timeout")?...?: 30
val bridges = database.getMeta("bridges")?.toString(Charsets.UTF_8).orEmpty()
...
_state.value = UiState(configured = true, unlocked = true, onion = onion, network = NetworkStatus.STARTING,
    lockTimeoutSeconds = timeout, bridges = bridges, canChangePanicPassword = opened.slot == VaultSlot.REAL)
...
/** Persists the preference for the open vault. The settings screen (a later phase) calls this. */
fun setDoorbellEnabled(enabled: Boolean) = action {
    db().putBlob(VAULT_SETTINGS_NAMESPACE, DOORBELL_ENABLED_KEY, byteArrayOf(if (enabled) 1 else 0))
}
```

### Como ficou

Três mudanças, todas de ligação — nenhuma toca no namespace, na chave ou no padrão ligado:

```kotlin
val bridges = database.getMeta("bridges")?.toString(Charsets.UTF_8).orEmpty()
val doorbell = isDoorbellEnabled()
...
_state.value = UiState(configured = true, unlocked = true, onion = onion, network = NetworkStatus.STARTING,
    lockTimeoutSeconds = timeout, bridges = bridges, doorbellEnabled = doorbell,
    canChangePanicPassword = opened.slot == VaultSlot.REAL)
```

```kotlin
override fun setDoorbellEnabled(enabled: Boolean) = action {
    db().putBlob(VAULT_SETTINGS_NAMESPACE, DOORBELL_ENABLED_KEY, byteArrayOf(if (enabled) 1 else 0))
    _state.update { previous -> if (shuttingDown) previous else previous.copy(doorbellEnabled = enabled) }
}
```

### Decisões

- **`isDoorbellEnabled()` em `activate()`, não `doorbellEnabled(database)`.** Neste ponto
  `database` **é** `storage.active` (vem de `db()`, no topo de `activate`), então os dois são
  equivalentes — e usar o acessor público deixa **um único leitor** da preferência e um único lugar
  onde o padrão ligado mora. De quebra, `isDoorbellEnabled()` deixa de ser API pública sem chamador.
- **`_state.update` com a guarda `shuttingDown`,** copiada palavra por palavra de `setLockTimeout` e
  `setBridges`. Sem a atualização de estado, o interruptor voltaria sozinho ao valor anterior até a
  próxima ativação, porque quem o desenha é `UiState`. Com a guarda, um bloqueio ou um pânico em voo
  não tem o seu estado sobrescrito por uma gravação que ainda estava na fila.
- **Sem `notice`.** `setBridges` publica um aviso ("Pontes salvas. Bloqueie e abra novamente…")
  porque a mudança dele só vale depois de reconectar. Aqui o próprio interruptor e o texto que troca
  embaixo dele já são a confirmação; um Snackbar adicional a cada toque seria ruído.
- **`override`.** O método agora implementa `UiActions`; a assinatura não mudou.

### Paridade cofre real / cofre-isca — verificada, sem bifurcação

Confirmado por leitura, não por suposição: `isDoorbellEnabled()` resolve `storage.active` (o cofre
aberto, seja qual for), `doorbellEnabled(database)` devolve `DOORBELL_DEFAULT_ENABLED` para chave
ausente nos dois, e `setDoorbellEnabled` grava em `db()`, também o cofre aberto. **Nenhum teste de
`VaultSlot` foi acrescentado em nenhum dos três pontos novos**, e o único uso de `VaultSlot` na
construção do `UiState` continua sendo o pré-existente `canChangePanicPassword`. A tentação seria
diferenciar a isca (esconder a opção, fixá-la, ou gravá-la só no cofre real): **deliberadamente não
feito** — qualquer uma dessas três coisas seria uma diferença observável entre os cofres, exatamente
o que o item 7 do desenho ("opção por cofre, presente igualmente no real e na isca") proíbe.

### Vantagens

- A decisão de manter ou não a campainha passa a ser do usuário, e por cofre, como o desenho previu.
- A leitura fica onde as outras preferências por cofre já eram lidas, então uma fase futura que mexer
  em ativação encontra as três juntas em vez de uma escondida.
- Nenhum caminho novo entre os dois cofres: a isca continua respondendo exatamente como o cofre real.

### Validação

`:app:compileDebugKotlin` BUILD SUCCESSFUL; `:app:lintDebug` BUILD SUCCESSFUL sem aviso novo;
`:app:testDebugUnitTest` **83 testes, 0 falhas, 0 erros, 1 pulado** — mesma contagem de T4.18, esta
fase não acrescentou teste JVM (não há suíte JVM do controlador: ele é um `AndroidViewModel`).

---

## 2026-09-18 — T4.19: diagnóstico do handoff da campainha (por que o `:tor` vivo não é adotado)

Fase de **diagnóstico**, não de correção. O objetivo era descobrir por que o `:tor` mantido vivo pelo
modo mínimo não é reaproveitado ao destravar o mesmo cofre. A causa raiz foi confirmada ao vivo e
**não está neste arquivo** — está no Rust. O que mudou aqui é só instrumentação, guardada por
`BuildConfig.DEBUG`.

### A hipótese que estava na mesa — e que se provou FALSA

A investigação anterior (`docs/development/build-logs/doorbell-20260918/session-log.md`) tinha
deixado duas suspeitas, sem discriminá-las:

1. `minimal`/`kept` já estaria `null` quando `prepareDoorbellHandoff` roda; ou
2. `kept.transport.alive` seria `false` — isto é, o `lock()`, ao armar o modo mínimo, ainda assim
   derrubaria em algum ponto a mesma `TorConnection` que acabara de preservar.

**As duas estão erradas.** Com o log novo, cinco execuções da sonda isolada (lock → esperar →
unlock do mesmo cofre, sem batida nenhuma) dizem sempre exatamente isto:

```
lock: minimal doorbell kept=true (stopped=false dead=false cause=none)
doorbell handoff: reuse=true sameSlot=true hasSlot=true alive=true (stopped=false dead=false cause=none)
transport attempt: reusingDoorbellChild=true
```

Ou seja: `prepareDoorbellHandoff` toma o ramo de **adoção**, o slot bate, e a `TorConnection`
preservada chega ao desbloqueio **viva e nunca parada** (`cause=none` — nenhum `shutdown()` correu
sobre ela entre o lock e a adoção). O `lock()` já isolava corretamente os dois caminhos.

### A causa raiz confirmada

O que falha é o `START` seguinte, **dentro do filho** `:tor`:

```
TorService: START: native transport slot before start = STOPPED
TorService: messaging start/pump ended: java.lang.IllegalStateException: Tor operation failed
transport attempt: failed (reused=true): java.lang.IllegalStateException: Transporte indisponível
```

Três milissegundos entre o START e o erro. Um build local e temporário (já revertido) que devolvia o
erro real do Rust em vez da string fixa de `tor_jni.rs` mostrou o texto:

```
tor: bad API usage (bug): Error while trying to access a key store:
Error while trying to access a key store: Key already exists
```

`tor::start` (`native/src/tor.rs`) chama `launch_onion_service_with_hsid(config, hsid)` com o
nickname fixo `"nomessages"`, o que **insere** o par de chaves HsId no keystore efêmero do
`TorClient`. Esse `TorClient` vive dentro do `TorHost` compartilhado, que a campainha segura durante
todo o bloqueio — é justamente isso que torna o reaproveitamento atraente. Só que `tor::stop()`
(chamado por `doorbell::minimal_mode()`) larga o `RunningOnionService` mas **não remove a chave do
keystore**. No segundo `start` dentro do mesmo processo a chave ainda está lá, e o Arti recusa na
hora.

### Por que o PID do `:tor` muda mesmo assim

Encadeamento completo, medido:

1. a tentativa adotiva falha em ~3 ms e vira `TransportAttempt.FAILED`;
2. `TransportSupervisor` cumpre o primeiro degrau do backoff (5 s);
3. a tentativa seguinte já não encontra `handoff` (ele é consumido uma vez só, por desenho) e segue
   o caminho normal de filho novo;
4. `recycleTransport()` → `TorConnection.shutdown()` mata o filho da campainha — que até esse
   instante estava vivo.

Quem mata o filho é o **retry**, não o `lock()`. O `ActivityManager: Process …:tor has died` +
`Scheduling restart of crashed service … for connection` que a fase anterior tinha observado é a
assinatura desse `shutdown()` (o filho se mata de dentro, via `TorService.terminate()`, com o
vínculo ainda registrado), e não de uma morte por pressão de memória.

### Antes / depois do código

Nada de comportamento mudou. O que entrou foi diagnóstico, todo sob `BuildConfig.DEBUG` (o
compilador R8 remove o bloco inteiro em release, incluindo a interpolação de string).

**Antes** — `prepareDoorbellHandoff` decidia em silêncio:

```kotlin
if (kept == null) return
if (slot != null && kept.slot == slot && kept.transport.alive) {
    synchronized(lifecycleGuard) { handoff = kept.transport; transport = kept.transport }
    return
}
shutdownTransport(kept.transport)
```

**Depois** — a decisão passa a dizer qual das três condições a reprovou:

```kotlin
if (kept == null) {
    // Only the booleans of the decision, never the slot's meaning to a bystander: which of
    // the two vaults is being opened is exactly what must not reach a log.
    if (BuildConfig.DEBUG) Log.d(TAG, "doorbell handoff: no kept child (minimal=null)")
    return
}
val sameSlot = slot != null && kept.slot == slot
val alive = kept.transport.alive
if (BuildConfig.DEBUG) {
    Log.d(TAG, "doorbell handoff: reuse=${sameSlot && alive} sameSlot=$sameSlot " +
        "hasSlot=${slot != null} alive=$alive (${kept.transport.diagnostics})")
}
if (sameSlot && alive) {
    synchronized(lifecycleGuard) { handoff = kept.transport; transport = kept.transport }
    return
}
shutdownTransport(kept.transport)
```

Mais três pontos, pela mesma razão e com a mesma guarda:

- em `lock()`, logo depois de `startMinimalDoorbell`: `lock: minimal doorbell kept=$kept` com o
  `diagnostics` do transporte que está sendo preservado — é o outro extremo da mesma pergunta;
- em `attemptTransport`, na entrada: `transport attempt: reusingDoorbellChild=${reused != null}` —
  responde se o handoff foi de fato consumido;
- em `attemptTransport`, nos dois `catch` que antes descartavam a exceção sem olhar
  (`catch (_: TimeoutCancellationException)` e `catch (_: Exception)`): o tipo e a mensagem. Foi
  **este** log que virou o caso: sem ele, uma tentativa adotiva que falha é indistinguível de uma
  que nunca aconteceu.

### O que NÃO foi mexido, de propósito

`lock()`, `prepareDoorbellHandoff`, `closeSession()`/`closeOwnedSession()`, `startMinimalDoorbell` e
`recycleTransport` continuam com a mesma lógica. A correção pedida no roteiro ("o `lock()` não pode
derrubar a `TorConnection` preservada") **não se aplica**: ele já não derruba, e o log prova. A
correção real fica em `native/src/tor.rs` — remover a chave do keystore no `shutdown_transport`, ou
tornar o lançamento do serviço idempotente, ou usar um nickname por geração. As três mexem no
keystore do Arti e no ciclo de vida do serviço onion, com implicações de estado de guarda; por isso
ficaram registradas para decisão em vez de tentadas aqui.

### Vantagens da instrumentação (que vale manter)

- A decisão adotar-vs-recriar deixa de ser invisível: qualquer regressão futura no handoff aparece em
  uma linha de log em vez de exigir outra rodada de arqueologia com dois emuladores.
- Os dois `catch` mudos de `attemptTransport` eram um ponto cego real — uma tentativa que falha
  rápido parecia, de fora, uma tentativa que nunca ocorreu.
- Nada sensível vaza: só booleanos da decisão e rótulos de um conjunto fechado. O texto de erro do
  nativo continua sendo a string fixa de `tor_jni.rs` (o build que expunha o erro real foi local,
  temporário e revertido).

### Validação

- `:app:testDebugUnitTest` **83 testes, 0 falhas, 1 pulado**; `:core:test` **102 testes, 0 falhas,
  2 pulados** — mesmas contagens conhecidas.
- Sonda isolada do handoff: `docs/development/build-logs/doorbell-20260918/23-B-handoff-probe-instrumented.txt`.
- Causa raiz e logcat literal: `22-handoff-root-cause.txt` e `24-B-logcat-native-error.txt`.
- Cenário de aceite completo re-executado (bloquear, bater de A, notificar, destravar, entregar):
  `25-acceptance-after-diagnosis.txt` e `26-B-acceptance-delivered.png` — continua passando.

## 2026-09-23 — `canChangePanicPassword` deixa de depender do slot; `changePanicPassword` ganha um caminho de isca (T4.7a)

### Motivo

A revisão de 2026-09-15 encontrou um oráculo de isca em `activate()`: o campo publicado
`canChangePanicPassword` era `opened.slot == VaultSlot.REAL`, e `SettingsScreen.kt` só desenhava o
botão "trocar senha de pânico" quando esse campo era `true`. A simples ausência do botão já
denunciava, por leitura de tela (ou de código), que o cofre aberto era a isca — sem precisar
descobrir a senha real. Ver `docs/changes/UiContract.kt.md` para a documentação do campo em si.

### Como era antes

```kotlin
_state.value = UiState(configured = true, unlocked = true, onion = onion, network = NetworkStatus.STARTING,
    lockTimeoutSeconds = timeout, bridges = bridges, doorbellEnabled = doorbell,
    canChangePanicPassword = opened.slot == VaultSlot.REAL)
```

```kotlin
override fun changePanicPassword(password: CharArray, confirm: CharArray) {
    ...
    viewModelScope.launch(Dispatchers.IO) {
        var committed = false
        try {
            shutdownTransport(tor)
            mutex.withLock {
                discardAudio()
                engine?.close(); engine = null
                pairing?.cancel(); persistIdentity()
                persistTorState()
                manager.resetPanicPassword(directory, checkNotNull(session), ownedPassword)
                committed = true
            }
        } catch (_: Exception) { }
        ...
```

Chamar isto com uma sessão de isca teria lançado (`require(realSession.slot == VaultSlot.REAL)` em
`VaultManager.resetPanicPassword`), então a única forma de a isca "aceitar" o botão sem quebrar seria
escondê-lo — o próprio bug.

### Como ficou

```kotlin
_state.value = UiState(configured = true, unlocked = true, onion = onion, network = NetworkStatus.STARTING,
    lockTimeoutSeconds = timeout, bridges = bridges, doorbellEnabled = doorbell,
    canChangePanicPassword = true)
```

```kotlin
val isRealSession = session?.slot == VaultSlot.REAL
viewModelScope.launch(Dispatchers.IO) {
    var committed = false
    try {
        shutdownTransport(tor)
        mutex.withLock {
            discardAudio()
            engine?.close(); engine = null
            pairing?.cancel(); persistIdentity()
            persistTorState()
            if (isRealSession) {
                manager.resetPanicPassword(directory, checkNotNull(session), ownedPassword)
            } else {
                manager.fakePanicPasswordChange(directory, ownedPassword)
            }
            committed = true
        }
    } catch (_: Exception) { }
    ...
```

`isRealSession` é capturado **antes** da teardown assíncrona, então nada mais adiante no fluxo pode
ramificar pelo slot de novo. Os dois ramos passam pela mesma sequência de
`discardAudio()`/`engine?.close()`/`pairing?.cancel()`/`persistIdentity()`/`persistTorState()`, sob o
mesmo `mutex.withLock`, terminam com `committed = true` e mostram o mesmo aviso
`notice_panic_password_changed`. O ramo da isca chama `VaultManager.fakePanicPasswordChange` (ver
`docs/changes/VaultManager.kt.md`), que paga o mesmo custo de Argon2id que o ramo real paga e não
toca em nada no disco.

### Vantagens

- O botão passa a existir, com a mesma aparência e o mesmo fluxo de diálogo/medidor de senha, nos
  dois cofres — nenhuma diferença observável de UI entre real e isca.
- O tempo de resposta continua equivalente nos dois ramos (mesmo número de derivações Argon2id), então
  a correção não troca um oráculo visual por um oráculo de tempo.
- `session?.slot` continua sendo a única fonte de verdade sobre qual cofre está aberto — capturado
  uma vez, não espalhado por múltiplas checagens que poderiam divergir.

Ver também `docs/security-model.md` ("Decoy oracles fixed...", T4.7) e
`docs/changes/security-model.md.md`.

## 2026-09-23 — `errorMessage(failure, fallback)`: cada catch mostra o texto específico quando existe um (T4.6)

### Motivo

Ver `docs/changes/MessagingError.kt.md` para o achado de lint completo. Os quatro catches ao redor de
`MessagingEngine`/`MemoryAudioRecorder` (`action`, o seletor de arquivo anexado, `sendMediaBytes`,
`stopAudio`) descartavam `Throwable.message` inteiramente e sempre mostravam o mesmo texto genérico
daquele call site, não importa a causa real.

### Como era

```kotlin
private fun action(block: () -> Unit) {
    ...
    catch (_: Exception) { error(context.getString(R.string.error_operation_failed), token) }
}
// e, em três outros catches: error_attachment_limit / error_attachment_send / error_audio_finish
// sempre, qualquer que fosse a exceção.
```

### Como ficou

```kotlin
private fun errorMessage(failure: Throwable, fallback: Int): String = context.getString(when ((failure as? MessagingError)?.code) {
    MessagingErrorCode.EMPTY_MESSAGE -> R.string.error_type_message
    MessagingErrorCode.FILE_TOO_LARGE -> R.string.error_attachment_limit
    MessagingErrorCode.FILE_UNAVAILABLE -> R.string.error_file_unavailable
    MessagingErrorCode.FILE_INVALID -> R.string.error_file_invalid
    MessagingErrorCode.NO_OTHER_MEMBERS -> R.string.error_no_other_members
    MessagingErrorCode.GROUP_SYNC_FAILED -> R.string.error_group_sync_failed
    MessagingErrorCode.GROUP_WAITING_ONLINE -> R.string.error_group_waiting_online
    MessagingErrorCode.LEFT_GROUP -> R.string.error_left_group
    MessagingErrorCode.CONTACT_UNAVAILABLE -> R.string.error_contact_unavailable
    MessagingErrorCode.CONTACT_UNPAIRED -> R.string.error_contact_unpaired
    MessagingErrorCode.GROUP_UNAVAILABLE -> R.string.error_group_unavailable
    MessagingErrorCode.DUPLICATE_FILE -> R.string.error_duplicate_file
    MessagingErrorCode.EXISTING_FILE_MISMATCH -> R.string.error_existing_file_mismatch
    MessagingErrorCode.NO_AUDIO_RECORDED -> R.string.error_no_audio_recorded
    null -> fallback
})
```

Cada um dos quatro catches agora chama `errorMessage(failure, <seu fallback anterior>)`, vinculando a
exceção capturada em vez de descartá-la (`catch (failure: Exception)`), por exemplo:

```kotlin
catch (failure: Exception) { error(errorMessage(failure, R.string.error_operation_failed), token) }
catch (failure: Exception) { error(errorMessage(failure, R.string.error_attachment_limit), token) }
catch (failure: Exception) { error(errorMessage(failure, R.string.error_attachment_send), token) }
catch (failure: Exception) { error(errorMessage(failure, R.string.error_audio_finish), token) }
```

Nada muda para uma exceção **sem** `MessagingErrorCode` (falha de I/O, `CancellationException` já
tratada antes, etc.): `errorMessage` cai no mesmo `fallback` que cada catch sempre mostrou. Só uma
`MessagingError` reconhecida ganha texto mais específico do que o fallback do call site.

### Por que `FILE_TOO_LARGE` mapeia para `error_attachment_limit`, e não para uma string nova

`error_file_eight_mib` descrevia o mesmo limite de 8 MiB que `error_attachment_limit` já cobre nos
mesmos pontos de envio, com um texto pior (mais curto, sem o número). Reaproveitar
`error_attachment_limit` fecha o achado de lint sem duas strings dizendo a mesma coisa — ver
`docs/changes/MessagingError.kt.md`.

### Vantagens

- Uma falha específica agora mostra texto específico ("Não há outros membros" em vez de "Não foi
  possível concluir a operação") nos quatro pontos onde o usuário pode ver um erro de mensageria.
- Um só lugar (`errorMessage`) decide a tradução código→string; nenhum catch individual precisa saber
  a lista completa de códigos.
- Fecha `UnusedResources` para as 13 strings `error_*` que tinham um caminho de UI real, sem alterar
  o comportamento de nenhuma exceção que não carrega `MessagingErrorCode`.

### Verificação

`:app:testDebugUnitTest`/`:app:lintDebug`: `BUILD SUCCESSFUL`, zero `UnusedResources`/
`PluralsCandidate` (antes: 28). Ver `docs/changes/MessagingEngine.kt.md`,
`docs/changes/MemoryAudioRecorder.kt.md`, `docs/changes/MessagingError.kt.md` e
`docs/changes/strings.xml.md`.

### Adendo (2026-09-23, mesmo dia) — `errorResource` extraído como função pura, com teste JVM dedicado

**Motivo.** O "Feito quando" de T4.6 não exige um teste de mapeamento erro→recurso — cobre só
`:app:lintDebug` sem `UnusedResources`/`PluralsCandidate` e nenhum erro de UI em texto literal, critério
já satisfeito antes deste adendo. A extração abaixo é uma melhoria de cobertura adicional, decidida
nesta revisão por conta própria: `errorMessage` como estava misturava duas coisas — a decisão
`código → id de recurso` e a chamada `context.getString(id)` — numa função só, o que exigiria
Robolectric (ou um `Context` real) só para testar o `when`. A decisão em si não precisa de `Context`
nenhum, e isolá-la como função pura permite testá-la sem esse custo.

**Como era**

```kotlin
private fun errorMessage(failure: Throwable, fallback: Int): String = context.getString(when ((failure as? MessagingError)?.code) {
    MessagingErrorCode.EMPTY_MESSAGE -> R.string.error_type_message
    ...
    null -> fallback
})
```

**Como ficou**

```kotlin
private fun errorMessage(failure: Throwable, fallback: Int): String =
    context.getString(errorResource((failure as? MessagingError)?.code, fallback))

companion object {
    ...
    internal fun errorResource(code: MessagingErrorCode?, fallback: Int): Int = when (code) {
        MessagingErrorCode.EMPTY_MESSAGE -> R.string.error_type_message
        ...
        null -> fallback
    }
}
```

O `when` em si não mudou uma linha (mesmos 15 ramos, mesma ordem); só foi movido para uma função
`internal` no `companion object`, que recebe o `MessagingErrorCode?` já extraído em vez do `Throwable`,
e devolve um `Int` (o id do recurso) em vez do `String` já resolvido. `errorMessage` ficou reduzida a
uma casca de duas chamadas.

**Teste novo**, `app/src/test/kotlin/dev/mx3/nomessages/runtime/NoMessagesControllerErrorMappingTest.kt`
— roda no `:app:testDebugUnitTest`, sem Robolectric: três casos, (1) cada um dos 15
`MessagingErrorCode` mapeado para o seu `R.string.error_*` esperado, um por um (lista exaustiva escrita
à mão, não um loop sobre `entries`, para que um código novo force uma linha nova aqui em vez de passar
por acidente), (2) todos os 15 mapeamentos são distintos entre si e nenhum coincide com o `fallback`
passado — a garantia de que nenhuma reserva de dois códigos para a mesma string voltou a acontecer, e
(3) `code = null` sempre cai no `fallback` do chamador, testado com dois fallbacks diferentes para
confirmar que não é uma constante fixa.

**Por que `internal`, não `private`.** O teste vive em `app/src/test/...`, mesmo módulo Gradle
(`:app`) mas pacote de teste separado; `internal` é visível dali sem abrir a função para o resto do
app, que continua só enxergando `errorMessage` (`private`).

### Vantagens

- O "Feito quando" de T4.6 ("Teste JVM para o mapeamento de erro → recurso, função pura") passa a ter
  um teste que o satisfaz literalmente, em vez de depender da cobertura indireta dos catches em
  `MessagingEngineTest`/instrumentado.
- Um `MessagingErrorCode` novo que ninguém mapeia em `errorResource` já quebra a compilação (`when`
  exaustivo sem `else`); um `MessagingErrorCode` mapeado para o recurso **errado** agora quebra este
  teste, sem precisar reproduzir o catch específico que o dispara.
- Nenhuma mudança de comportamento em runtime: o texto mostrado ao usuário para cada código é
  byte-a-byte o mesmo de antes.

### Verificação (adendo)

`:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`:
`BUILD SUCCESSFUL`. Lint: 0 `UnusedResources`, 0 `PluralsCandidate`, 0 erros (mantido).

## 2026-09-23 — `MessagingEngine` ganha a capacidade de mídia; `MEDIA_CAPACITY_EXHAUSTED` mapeado (T4.1)

### Motivo

Ver `docs/security-model.md` ("Fixed media reservation and blind cover growth", T4.1) e
`docs/changes/MessagingEngine.kt.md`.

### Como era

```kotlin
val localEngine = MessagingEngine(crypto, database, localIdentity, opened, directory, scope, mutex,
    onChanged = { ... },
    onError = { ... })
```

### Como ficou

```kotlin
val localEngine = MessagingEngine(crypto, database, localIdentity, opened, directory, storage.mediaCapacityBytes, scope, mutex,
    onChanged = { ... },
    onError = { ... })
```

E, em `errorMessage` (ver a seção acima sobre T4.6):

```kotlin
MessagingErrorCode.NO_AUDIO_RECORDED -> R.string.error_no_audio_recorded
MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED -> R.string.error_media_capacity
null -> fallback
```

`storage` já era um `AndroidVaultStorage` concreto neste arquivo (`private val storage by lazy {
AndroidVaultStorage(context, crypto) }`), então `storage.mediaCapacityBytes` não exigiu mudança de
tipo nem de construção — só um argumento a mais na chamada de `MessagingEngine(...)`.

### Vantagens

- Um só ponto de construção (`activate()`) passa a capacidade real para o motor de mensagens; nenhum
  valor mágico duplicado em `runtime/`.
- Reaproveita o `errorMessage` que T4.6 já introduziu — a reserva de mídia esgotada mostra texto
  específico ("O espaço reservado para mídia deste cofre está cheio.") pelo mesmo mecanismo que
  qualquer outro `MessagingError`.

### Verificação

`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest`:
`BUILD SUCCESSFUL`. Suíte instrumentada em `emulator-5556`: `OK (23 tests)`. Ver
`docs/changes/AndroidVaultStorage.kt.md`, `docs/changes/MessagingEngine.kt.md` e
`docs/changes/MessagingError.kt.md`.

## 2026-09-23 — Correção da revisão: changelog duplicado com o nome antigo do produto removido

Mesma situação de `docs/changes/NoMessagesApp.kt.md` (ver a entrada equivalente lá): a renomeação do
arquivo do controller, do nome antigo do produto para `NoMessagesController.kt`, deixou o changelog
com o nome antigo no próprio nome de arquivo (`docs/changes/`, prefixo do nome antigo +
`Controller.kt.md`) para trás, com todo o conteúdo já presente neste arquivo (os mesmos 15 títulos
de seção, de "T2.1: mapeamento de status em `activate`" até "`MessagingEngine` ganha a capacidade de
mídia..."). Removido com `git rm`.
