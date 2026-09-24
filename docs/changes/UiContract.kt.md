# app/src/main/kotlin/dev/mx3/nomessages/ui/UiContract.kt

## 2026-09-14 — T2.1: `NetworkStatus.PUBLISHING`

### Como era antes

```kotlin
enum class NetworkStatus { OFF, STARTING, ONLINE, RETRYING, ERROR }
```

Não havia como representar o intervalo entre "Tor bootstrapado" e "endereço onion alcançável".

### Como ficou

```kotlin
/** PUBLISHING sits between bootstrap and reachability: Tor is up, the onion descriptor is not yet published. */
enum class NetworkStatus { OFF, STARTING, PUBLISHING, ONLINE, RETRYING, ERROR }
```

O valor foi inserido entre `STARTING` e `ONLINE`, seguindo a ordem cronológica dos estados.

### Vantagens

- A ordem do enum passa a descrever a sequência real do transporte, o que torna óbvio para o leitor onde cada estado se encaixa.
- Como o `when` de `NetworkBanner` é exaustivo, o compilador obriga qualquer nova tela a tratar o estado.

### Por que a mudança foi feita

T2.1: `PUBLISHING` precisa ser distinto de `ONLINE` na interface.

## 2026-09-16 — `AudioPreviewUi`/`AudioBytesUi` e dois novos métodos suspensos em `UiActions` (T4.8, mídia inline — parte A: áudio)

### Motivo

A bolha de áudio inline (`ChatScreen.kt`) precisa de dois dados que nenhuma ação existente
oferecia: (1) a forma de onda/duração de um anexo, cacheável e barata de repetir; (2) os bytes
decifrados de um anexo, só quando o usuário realmente aperta play, nunca cacheados. Toda ação
existente em `UiActions` é "dispara e esquece" (retorna `Unit`, o resultado chega via `UiState`);
essas duas precisam devolver um valor diretamente para a Composable que pediu, então viraram `suspend
fun` com retorno nulável em vez de mais um campo em `UiState`.

### Como é agora

```kotlin
data class AudioPreviewUi(val waveform: FloatArray, val durationMs: Int)
data class AudioBytesUi(val mimeType: String, val bytes: ByteArray)

interface UiActions {
    ...
    fun openAttachment(id: String)
    fun closeAttachment()
    suspend fun loadAudioPreview(attachmentId: String): AudioPreviewUi?
    suspend fun loadAudioBytes(attachmentId: String): AudioBytesUi?
    ...
}
```

`loadAudioPreview` serve do `MediaPreviewCache` (ver `docs/changes/MediaPreviewCache.kt.md`) quando
já está quente, senão decifra+decodifica fora da main thread e cacheia. `loadAudioBytes` decifra sem
nunca cachear — o chamador (a bolha de áudio) é responsável por zerar os bytes quando termina.
Ambos retornam `null` (cofre bloqueado, anexo ausente, decodificação falhou) em vez de lançar, para
que a bolha degrade graciosamente em vez de quebrar a tela de conversa.

### Vantagens

- Não infla `UiState`/`MessageUi` com um campo por anexo carregado — o dado pedido chega direto para
  quem pediu, com o ciclo de vida certo (`LaunchedEffect` cancela a corrotina se a bolha sair de tela).
- Implementado por `NoMessagesController` reaproveitando o mesmo `mutex`/`generation`/`shuttingDown` que
  `openAttachment` já usa, então segue exatamente a mesma disciplina de concorrência do resto do
  controlador (ver `docs/changes/NoMessagesController.kt.md`).
- Extensível: um agente futuro de foto/vídeo pode adicionar `loadImagePreview`/`loadVideoBytes` na
  mesma forma, sem reabrir esta interface de um jeito incompatível.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

## 2026-09-16 — `AttachmentUi.id`, `UiState.imageGallery`, `ImagePreviewUi`/`loadImagePreview` (T4.8, mídia inline — parte B: fotos)

### Motivo

Três coisas a bolha/visualizador de imagem precisavam que a interface ainda não oferecia:

1. `AttachmentUi` não carregava o próprio id do anexo, só `name`/`mimeType`/`bytes` — o visualizador
   em tela cheia não tinha como saber *qual* anexo é o que está aberto, o que é indispensável para
   localizar a posição dele dentro da lista de imagens irmãs (B3, navegação por swipe).
2. Nada em `UiState` guardava a lista ordenada de imagens da conversa aberta — sem ela o
   visualizador não sabe para qual id ir ao deslizar.
3. Não existia um equivalente de `loadAudioPreview` para imagem: a bolha inline de foto precisa de
   uma miniatura pequena e cacheável, do mesmo jeito que a bolha de áudio já pede uma forma de onda.

### Como era antes

```kotlin
data class UiState(..., val attachment: AttachmentUi? = null)
data class AttachmentUi(val name: String, val mimeType: String, val bytes: ByteArray)

interface UiActions {
    ...
    suspend fun loadAudioBytes(attachmentId: String): AudioBytesUi?
    fun setLockTimeout(seconds: Int)
    ...
}
```

### Como é agora

```kotlin
data class UiState(
    ...,
    val attachment: AttachmentUi? = null,
    /** Ordered ids of every image attachment in the currently open chat ... */
    val imageGallery: List<String> = emptyList()
)
data class AttachmentUi(val id: String, val name: String, val mimeType: String, val bytes: ByteArray)
data class ImagePreviewUi(val bitmap: Bitmap)

interface UiActions {
    ...
    suspend fun loadAudioBytes(attachmentId: String): AudioBytesUi?
    suspend fun loadImagePreview(attachmentId: String): ImagePreviewUi?
    fun setLockTimeout(seconds: Int)
    ...
}
```

`imageGallery` só é populada por `NoMessagesController.openAttachment` quando o anexo aberto é uma
imagem, e só quando há mais de uma imagem na conversa (nada para deslizar caso contrário); é
esvaziada em `closeAttachment()` junto de `attachment = null`, e volta ao valor padrão (`emptyList`)
em todo caminho que substitui `_state.value` por um `UiState()` novo (`lock()`,
`cleanupFailedActivation`, etc.) sem precisar de nenhum gancho extra — ver
`docs/changes/NoMessagesController.kt.md`. `loadImagePreview` segue exatamente o mesmo contrato de
`loadAudioPreview` (serve do `MediaPreviewCache` como `MediaPreviewPayload.Thumbnail`, senão decifra
+ decodifica fora da main thread e cacheia; `null` em vez de lançar).

### Vantagens

- `AttachmentUi.id` é o único campo novo que qualquer código existente precisava passar a fornecer
  (um `grep` por `AttachmentUi(` confirmou um único call site, em `NoMessagesController.openAttachment`)
  — nenhuma outra tela quebrou.
- Segue o mesmo formato pergunta/resposta (`suspend fun ... : X?`) já estabelecido por
  `loadAudioPreview`/`loadAudioBytes`, então um agente de vídeo pode adicionar
  `loadVideoPreview`/`loadVideoBytes` do mesmo jeito, sem inventar um formato novo.
- `imageGallery` é uma lista de ids, não de bitmaps/bytes: o visualizador nunca pré-carrega uma
  imagem vizinha, só sabe para qual id pedir ao `openAttachment` de novo quando o usuário deslizar.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

## 2026-09-16 — `VideoPreviewUi`/`loadVideoPreview` (T4.8, mídia inline — parte C: vídeo, agente 3 de 3)

### Motivo

A parte B já deixava o formato previsto ("um agente de vídeo pode adicionar
`loadVideoPreview`/`loadVideoBytes` do mesmo jeito, sem inventar um formato novo"). A bolha de vídeo
inline nova em `ChatScreen.kt` precisa de um poster frame + duração, cacheável, do mesmo jeito que a
bolha de imagem já pede uma miniatura. Diferente de imagem, o vídeo não ganhou um `loadVideoBytes`
separado: a reprodução acontece só em tela cheia (`AttachmentViewer.kt`), que já recebe os bytes
decifrados inteiros via `state.attachment`/`openAttachment` — não há um "tocar inline" que precise de
bytes brutos sob demanda como a bolha de áudio.

### Como é agora

```kotlin
/**
 * A cached-or-freshly-decoded bounded poster-frame bitmap plus clip duration for an inline video
 * bubble. The bitmap lives in [MediaPreviewCache] as a [MediaPreviewPayload.VideoPoster] - this is
 * just a handle to it, never a second owner: it must not be recycled by the caller.
 */
data class VideoPreviewUi(val poster: Bitmap, val durationMs: Int)

interface UiActions {
    ...
    suspend fun loadImagePreview(attachmentId: String): ImagePreviewUi?
    suspend fun loadVideoPreview(attachmentId: String): VideoPreviewUi?
    fun setLockTimeout(seconds: Int)
    ...
}
```

`loadVideoPreview` segue exatamente o mesmo contrato de `loadImagePreview`/`loadAudioPreview`: serve
do `MediaPreviewCache` (`MediaPreviewPayload.VideoPoster`) quando já quente, senão decifra e decodifica
fora da main thread (via `MediaMetadataRetriever`, ver `docs/changes/NoMessagesController.kt.md`) e
cacheia; `null` em vez de lançar em qualquer falha (cofre bloqueado, anexo ausente, sem faixa de
vídeo decodificável).

### Vantagens

- Zero surpresa para quem já leu `loadAudioPreview`/`loadImagePreview`: mesma assinatura, mesmo
  contrato "cache primeiro, decifra-e-descarta depois, nunca lança".
- Não duplica `AttachmentUi`/`UiState`: o poster e a duração chegam direto para quem pediu
  (`VideoBubble`), sem inflar nenhum modelo compartilhado.
- Deliberadamente **sem** `loadVideoBytes`: o único player de vídeo do app já vive em
  `AttachmentViewer.kt` (tela cheia), que reaproveita `state.attachment.bytes` — criar um segundo
  caminho de decifração só para um player que não existe seria complexidade sem uso.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

## 2026-09-17 — `MessageUi.forwarded`, `ForwardTargetUi`, `UiState.forwardTargets` e `UiActions.forwardMessage` (Parte B)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). Este arquivo é o contrato
entre o controlador e a UI. A Parte C (menu de toque longo, diálogo de seleção de destinatários,
rótulo "Encaminhada" na bolha) é feita por outro agente depois, **em cima** do que foi declarado
aqui — então a superfície precisava ficar pronta e documentada antes.

### Como era antes

```kotlin
data class MessageUi(val id: String, val text: String, val time: String, val outgoing: Boolean, val status: String, val attachmentId: String? = null, val attachmentName: String? = null, val mimeType: String? = null)

data class UiState(
    ...
    val imageGallery: List<String> = emptyList()
)

interface UiActions {
    ...
    fun openAttachment(id: String)
    fun closeAttachment()
    ...
}
```

### Como é agora

```kotlin
/** [forwarded] drives the "Encaminhada" label on the bubble; it is provenance only, with no link back to the origin chat. */
data class MessageUi(..., val mimeType: String? = null, val forwarded: Boolean = false)

data class UiState(
    ...
    val imageGallery: List<String> = emptyList(),
    /** Every chat a message can be forwarded into: paired, non-display-only contacts plus groups whose state is READY. [...] */
    val forwardTargets: List<ForwardTargetUi> = emptyList()
)
/** One selectable destination in the forward picker. [id] is a chat id: a contact id, or a group id when [isGroup]. */
data class ForwardTargetUi(val id: String, val title: String, val isGroup: Boolean)

interface UiActions {
    ...
    fun openAttachment(id: String)
    fun closeAttachment()
    /**
     * Re-sends message [messageId] into each chat in [targetChatIds], marked as forwarded. [...]
     */
    fun forwardMessage(messageId: String, targetChatIds: List<String>)
    ...
}
```

### Decisões de contrato

- **`forwarded` é o último parâmetro de `MessageUi`, com default.** Nenhum chamador que constrói
  `MessageUi` posicionalmente (previews, testes de UI) precisa mudar.
- **`forwardMessage(messageId, targetChatIds: List<String>)`, uma chamada só.** A tela de seleção é
  de múltipla escolha; passar a lista inteira de uma vez significa uma única entrada em `action { }`
  no controlador — ou seja, um `mutex.withLock` e um `refresh()`, em vez de N. Ids duplicados são
  colapsados pelo controlador.
- **`ForwardTargetUi` em vez de reusar `ChatUi`.** `ChatUi` carrega prévia da última mensagem,
  horário, contador de não lidas e status de entrega — nada disso faz sentido numa lista de destinos,
  e vazá-los para um diálogo de encaminhamento mostraria conteúdo de outras conversas na tela em que
  o usuário está prestes a escolher para quem mandar. `ForwardTargetUi` carrega só o mínimo:
  identificador, título e se é grupo (para o ícone).
- **`isGroup` como `Boolean`, e `id` sendo um "chat id".** É o mesmo identificador que
  `UiActions.openChat` e `MessagingEngine.sendText` já aceitam (id de contato ou id de grupo), então
  a UI não precisa de nenhuma tradução.
- **`forwardTargets` fica em `UiState`, recalculado a cada `refresh()`.** Um grupo que entra em
  pendência, falha ou do qual o usuário saiu desaparece da lista automaticamente, sem bookkeeping
  extra — a UI só renderiza o que recebe.
- **Encaminhar para a própria conversa de origem é permitido**, como no WhatsApp; não há filtro do
  chat atual na lista.

### Vantagens

- A Parte C não precisa de nenhum acesso a banco, a envelope ou ao motor: `state.forwardTargets` e
  `actions.forwardMessage(...)` bastam.
- Todos os campos novos têm default, então o arquivo continua compatível com qualquer construção
  existente de `UiState`/`MessageUi`.

## 2026-09-17 — `PairingUi.bundleStatus`, `PairingBundleStatus` e `UiActions.retryPairingBundle()` (T4.16)

Contexto completo do QR formato 2 (por que o pacote de chaves saiu do QR, o fluxo de busca pela
rede Tor): ver `docs/changes/Pairing.kt.md` e `docs/changes/SignalSessions.kt.md`. Este documento
cobre só o contrato entre o controlador e `PairingScreen.kt` (ver `docs/changes/PairingScreen.kt.md`
e `docs/changes/PairingLifecycle.kt.md` para quem consome cada campo novo).

### Como era antes

```kotlin
data class PairingUi(val offer: String?, val sas: String? = null, val peerFingerprint: String? = null, val expiresAt: Long, val waitingForPeer: Boolean = false, val completed: Boolean = false)
```

Nenhum campo reportava o progresso da busca do pacote de chaves, porque não existia busca nenhuma:
no QR formato 1 o pacote inteiro (1832 bytes, incluindo a chave pública Kyber-1024) vinha dentro do
próprio QR, lido junto com o resto da oferta.

### Como ficou

```kotlin
/**
 * [expiresAt] is epoch milliseconds and carries whichever deadline is in force: `now + 120 s` while
 * only a local offer is on screen, and the engine's 300 s exchange deadline once [sas] is non-null.
 * See [PairingLifecycle] for why the two are different and how the screen tells them apart.
 *
 * [bundleStatus] reports the Tor fetch of the peer's PQXDH key bundle, which QR format 2 moved out
 * of the QR and onto the network (2026-09-17, T4.16).
 */
data class PairingUi(
    val offer: String?,
    val sas: String? = null,
    val peerFingerprint: String? = null,
    val expiresAt: Long,
    val waitingForPeer: Boolean = false,
    val completed: Boolean = false,
    val bundleStatus: PairingBundleStatus = PairingBundleStatus.NONE,
)

/**
 * Progress of the pairing key-bundle fetch over Tor.
 *
 * [WAITING_FOR_TOR] is kept apart from [FETCHING] for the same reason `NetworkStatus.PUBLISHING` is
 * kept apart from `STARTING`: "your own onion is not reachable yet" and "the other phone is not
 * answering" are different problems with different remedies, and one banner for both would tell the
 * user nothing actionable.
 */
enum class PairingBundleStatus { NONE, WAITING_FOR_TOR, FETCHING, READY, FAILED }
```

```kotlin
interface UiActions {
    ...
    fun cancelPairing()

    /** Re-runs a pairing key-bundle fetch that failed, while the exchange deadline still allows it. */
    fun retryPairingBundle()
    ...
}
```

### `bundleStatus`: por que cinco valores e não três

O caminho ingênuo seria um booleano (`fetchingBundle: Boolean`) ou, no máximo, um estado de três
valores (parado/buscando/pronto). Cinco existem porque há duas causas de espera distintas e uma
causa de falha:

- **`NONE`** — valor padrão, antes de qualquer troca ser estagiada (nenhum par ainda escaneou/foi
  escaneado) ou antes do controlador publicar o primeiro estado real da busca.
- **`WAITING_FOR_TOR`** separado de **`FETCHING`** pela **mesma razão** que
  `NetworkStatus.PUBLISHING` já era separado de `STARTING` (T2.1, ver a seção correspondente acima
  neste mesmo arquivo): "meu próprio onion ainda não está alcançável" e "o outro aparelho não está
  respondendo" são **problemas diferentes**, com **remédios diferentes**. Se os dois caíssem no
  mesmo estado "buscando", o usuário veria a mesma mensagem tanto para "espere, sua própria conexão
  Tor ainda está subindo" (nada a fazer além de esperar) quanto para "o outro aparelho pode ter
  fechado o app" (vale conferir com a outra pessoa) — um banner genérico não diria nada acionável
  para nenhum dos dois casos.
- **`FETCHING`** — os dois lados já têm Tor pronto e a requisição `BundleRequest`/`BundleResponse`
  está de fato em trânsito.
- **`READY`** — o pacote de chaves chegou e seu hash bateu com `bundleHash` assinado na oferta
  (`PairingEngine.acceptPeerBundle`). É o único estado em que `PairingLifecycle.canConfirmSas`
  permite apertar o botão de confirmação (ver `docs/changes/PairingLifecycle.kt.md`).
- **`FAILED`** — a tentativa atual não completou dentro do seu próprio período (60 s,
  `NoMessagesController.BUNDLE_ATTEMPT_MILLIS`) ou foi recusada. É o único estado em que
  `PairingLifecycle.canRetryBundleFetch` pode devolver `true`, e só enquanto a deadline de 300 s
  da troca ainda não passou.

### `expiresAt`: significado agora documentado explicitamente

O campo já existia, mas seu significado — que ele carrega **deadlines diferentes** em momentos
diferentes da mesma troca — não estava escrito em nenhum lugar. O KDoc novo fixa isso: `now + 120 s`
(epoch milissegundos) enquanto só a oferta local está na tela, e o `PairingProgress.expiresAt` do
motor (epoch **segundos**, 300 s) assim que `sas` deixa de ser nulo. A diferença de unidade entre os
dois é a razão de `PairingLifecycle.remainingMillis` existir (ver
`docs/changes/PairingLifecycle.kt.md`) — este arquivo não faz a conversão, só documenta o contrato
que o consumidor precisa respeitar.

### `retryPairingBundle()`

Sem parâmetros e sem retorno, como as demais ações de pareamento já existentes
(`cancelPairing`, `confirmPairing`) — o controlador sabe qual troca está em andamento pelo seu
próprio estado interno, então a tela não precisa passar nenhum identificador. A gate de quando o
botão correspondente aparece (`bundleStatus == FAILED` **e** deadline ainda não estourada) vive em
`PairingLifecycle.canRetryBundleFetch`, não aqui — este arquivo só declara a ação, nunca decide
quando ela deve ser oferecida.

### Vantagens

- `WAITING_FOR_TOR`/`FETCHING` dão ao usuário um diagnóstico acionável em vez de um "carregando..."
  genérico, replicando um padrão de design já validado (`NetworkStatus.PUBLISHING`) em vez de
  inventar um novo.
- `bundleStatus` tem default (`NONE`), então nenhum construtor existente de `PairingUi` quebra.
- O KDoc de `expiresAt` fecha uma lacuna de documentação que já existia antes desta tarefa (o campo
  sempre carregou unidades diferentes em momentos diferentes; só não estava escrito).

### Validação (2026-09-17)

`gradle-wsl.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
→ `BUILD SUCCESSFUL in 8m 35s`. Nenhuma validação em emulador/aparelho físico foi feita nesta
tarefa.

---

## 2026-09-17 — Passo de polimento de UX sobre a seção acima: revisado, sem mudança de contrato

Continuação, mesmo dia, da seção T4.16 logo acima — uma revisão de UX/UI do fluxo de pareamento
(contagem regressiva, estado de busca do pacote, erro/retry, contraste, texto — ver
`docs/changes/PairingScreen.kt.md` para o detalhe completo). Registrado aqui **sem** um bloco
"como era/como ficou" porque, deliberadamente, `UiContract.kt` não mudou nesta rodada — o resultado
de uma decisão explícita, não um esquecimento.

A tarefa pedia para adicionar o mínimo de estado novo à máquina de estados só se a UI realmente
precisasse dele, e documentar claramente se isso acontecesse. Dois pontos foram considerados e
descartados:

1. **Um estado "regenerando" em `PairingBundleStatus`** (ou em algum enum novo) para a transição
   visual do QR que se atualiza sozinho. Não foi necessário: do ponto de vista deste contrato, a
   regeneração de uma oferta é **síncrona** — uma chamada a `showPairing()` substitui `PairingUi`
   inteiro no mesmo ciclo de estado, sem nenhuma fase intermediária que o controlador precise
   publicar. A transição visual pedida (um `Crossfade` entre o QR antigo e o novo) foi resolvida
   inteiramente dentro de `PairingScreen.kt`, com estado local ao composable.
2. **Um sinal explícito de "a troca foi cancelada por timeout"**, distinto de "o usuário cancelou".
   Também não foi necessário mudar este arquivo: `PairingLifecycle.nextAction` (que já faz parte do
   contrato de fato, ainda que não estritamente de `UiContract.kt`) já devolve essa distinção via o
   parâmetro `screenOpen` que o próprio chamador controla — quem chama sabendo que passou
   `screenOpen = true` já sabe, só pelo fato de receber `CANCEL`, que só pode ser por deadline. A
   tela guardou essa informação numa flag local (`timedOut`) em vez de pedir um campo novo aqui.

A única mudança de "contrato" desta rodada de UX foi em `PairingLifecycle.kt`
(`isExpiringSoon`/`EXPIRY_WARNING_MILLIS`, puramente cosmético — decide uma cor, não um valor de
`PairingUi`), documentada em `docs/changes/PairingLifecycle.kt.md`, não aqui.

### Validação

`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL` (ver
`docs/changes/PairingScreen.kt.md` para a contagem completa de testes).

## 2026-09-18 — T4.17 fase 6: `UiState.doorbellEnabled` e `UiActions.setDoorbellEnabled`

### Como era antes

`UiState` já carregava as duas preferências por cofre que a tela de Configurações edita
(`lockTimeoutSeconds`, `bridges`) e `UiActions` já tinha os dois gravadores correspondentes
(`setLockTimeout`, `setBridges`). A preferência da campainha existia apenas no controlador
(`isDoorbellEnabled`/`setDoorbellEnabled`), fora deste contrato — ou seja, invisível para a interface.

### Como ficou

```kotlin
data class UiState(
    ...
    val lockTimeoutSeconds: Int = 30,
    val bridges: String = "",
    val doorbellEnabled: Boolean = true,   // + KDoc
    ...
)

interface UiActions {
    ...
    fun setLockTimeout(seconds: Int)
    fun setBridges(bridges: String)
    fun setDoorbellEnabled(enabled: Boolean)   // + KDoc
}
```

### Por que o padrão aqui é `true`

O padrão do campo espelha o padrão do controlador para a chave ausente (`DOORBELL_DEFAULT_ENABLED`).
Se fosse `false`, qualquer `UiState` publicado antes de a ativação terminar — a tela de espera, um
`UiState(configured = true, busy = true)` — renderizaria o interruptor desligado por um instante e
depois o veria pular para ligado. Visualmente isso se lê como "o app desligou meu aviso sozinho", que
é exatamente a impressão que a funcionalidade não pode dar.

### Paridade cofre real / cofre-isca

Nem o campo nem a ação têm qualquer noção de `VaultSlot`. O único campo dependente de slot continua
sendo `canChangePanicPassword`. A assinatura de `setDoorbellEnabled` é a mesma nos dois cofres e
grava no cofre que estiver aberto.

### Vantagens

- A preferência passa a existir no mesmo contrato que a tela já consome, sem canal lateral nem
  leitura direta do controlador dentro de um composable.
- `UiActions` continua sendo a lista completa do que a interface pode pedir: nada novo escapa dela.

### Validação

`:app:compileDebugKotlin`, `:app:lintDebug` e `:app:testDebugUnitTest` (83 testes, 0 falhas, 1
pulado) — detalhes em `docs/changes/SettingsScreen.kt.md`.

## 2026-09-23 — `canChangePanicPassword`: o último campo dependente de slot deixa de ser (T4.7a)

### Motivo

A entrada anterior deste arquivo (2026-09-14) já registrava: "O único campo dependente de slot
continua sendo `canChangePanicPassword`." A revisão de 2026-09-15 confirmou que esse único campo era
um oráculo de isca — a ausência do botão "trocar senha de pânico" na isca denunciava o slot por
leitura de tela ou de código, sem precisar da senha real.

### Como era antes

```kotlin
val doorbellEnabled: Boolean = true,
val canChangePanicPassword: Boolean = false,
```

Sem comentário explicando o valor `false` como default nem por que, ao contrário de
`doorbellEnabled` (documentado logo acima como "não ramifica por slot"), este campo ramificava.

### Como ficou

```kotlin
val doorbellEnabled: Boolean = true,
/**
 * Whether the "trocar senha de pânico" button shows on the settings screen.
 *
 * Fixed at `true` once unlocked (was `opened.slot == VaultSlot.REAL` until T4.7, 2026-09-23) ...
 * `NoMessagesController.changePanicPassword` still branches on the session's actual slot - real
 * replaces the panic password, decoy pays the same KDF cost and discards it - but that branch
 * never reaches this field.
 */
val canChangePanicPassword: Boolean = false,
```

O controlador (`NoMessagesController.kt`, ver `docs/changes/NoMessagesController.kt.md`) agora
publica sempre `canChangePanicPassword = true` quando desbloqueado, para os dois slots.

### Vantagens

- Fecha a última exceção que a documentação deste arquivo já apontava explicitamente desde
  2026-09-14: agora **nenhum** campo de `UiState` ramifica por `VaultSlot`.
- O comentário no campo explica tanto o valor atual quanto o porquê da mudança, para quem só lê
  `UiContract.kt` sem abrir o histórico deste documento.

Ver também `docs/security-model.md` ("Decoy oracles fixed...", T4.7) e
`docs/changes/security-model.md.md`.
