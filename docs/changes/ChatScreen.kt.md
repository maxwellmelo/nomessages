# app/src/main/kotlin/dev/mx3/nomessages/ui/ChatScreen.kt

## 2026-09-15 — Corrige a ordem das mensagens: a mais recente agora fica perto da caixa de texto

Bug reportado pelo usuário (com captura de tela da tela de conversa): a mensagem mais recente
aparecia no TOPO da lista, logo abaixo do cabeçalho, e a mais antiga ficava embaixo, perto da caixa
de texto — o inverso do WhatsApp real e do que a seção de tela de chat do `SPEC.md` descreve.

Escopo desta mudança: apenas `ChatScreen.kt` (o `LazyColumn` que renderiza `state.messages` e os
efeitos de rolagem) e, em arquivo separado, a extração de duas decisões puras para `UiLogic.kt`
(ver `docs/changes/UiLogic.kt.md`). Nenhuma query SQL, nenhuma função de `ChatDatabase.kt` nem o
formato de `NoMessagesController.refresh()` foi tocado.

### Causa raiz

`NoMessagesController.refresh()` monta `state.messages` a partir de
`ChatDatabase.listMessages(selected)`, cuja consulta é:

```kotlin
"SELECT id,peer_or_group,direction,ts,body,status FROM messages WHERE peer_or_group = ? ORDER BY ts DESC,id DESC LIMIT ?"
```

(apoiada pelo índice `messages_chat_time ON messages(peer_or_group,ts DESC,id DESC)`, em
`AndroidVaultStorage.kt`). Ou seja, `state.messages[0]` é sempre a mensagem **mais recente**, e o
último índice é a mais antiga — de propósito, para servir tanto a lista de conversas (que só
precisa da última mensagem) quanto uma futura paginação por `beforeTimestamp`.

O bug estava só na camada de UI: o `LazyColumn` de `ChatScreen.kt` iterava essa lista na ordem em
que ela vinha (`items(state.messages.size) { index -> MessageBubble(state.messages[index]) }`) sem
`reverseLayout`, então o índice 0 — a mensagem mais nova — era desenhado no topo da tela, e o
`LaunchedEffect` de auto-scroll chamava `listState.scrollToItem(state.messages.lastIndex)`, ou
seja, rolava até a mensagem mais **antiga** (o último índice de uma lista DESC), reforçando o
comportamento invertido.

Verificado antes de mudar a consulta: `grep` por `state.messages` no módulo `:app` só encontra uso
em `ChatScreen.kt`. `HomeScreen.kt` usa `state.chats` (montada por
`ChatDatabase.listChats()`, uma consulta separada de "última mensagem por conversa") — não
depende da ordem de `state.messages` de forma alguma. Por isso a correção pôde ficar inteiramente
na UI, sem qualquer risco à pré-visualização/contador de não lidas da tela inicial.

### Abordagem escolhida (opção A do enunciado): `reverseLayout = true`, sem reordenar a lista

Duas abordagens eram possíveis: (a) manter a lista como vem do controlador (DESC, mais nova no
índice 0) e usar `reverseLayout = true`; ou (b) inverter a lista em memória antes de passar ao
`LazyColumn`, mantendo `reverseLayout = false`. Optei pela (a):

- menor diff — nenhuma cópia/inversão de `state.messages` é criada a cada recomposição;
- a ordem que já existe (`ts DESC`) e o índice 0 = mais nova batem exatamente com o que
  `reverseLayout = true` espera (índice 0 ancorado embaixo, perto da caixa de texto);
- zero risco a outros consumidores, porque a lista em si nunca muda de forma/ordem — só a
  orientação do layout muda.

### Como era antes

```kotlin
val listState = rememberLazyListState()
...
LaunchedEffect(state.messages.size) {
    if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
}
...
LazyColumn(
    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp),
    state = listState,
    verticalArrangement = Arrangement.spacedBy(5.dp),
) {
    items(state.messages.size, key = { state.messages[it].id }) { index ->
        MessageBubble(state.messages[index], actions::openAttachment)
    }
}
```

### Como ficou

```kotlin
val listState = rememberLazyListState()
var lastScrolledChat by remember { mutableStateOf<String?>(null) }
// Read during composition, before this pass's layout adjusts the scroll offset for the item
// that was just added, so this reflects whether the reader was at the bottom BEFORE the new
// message - not after - which is what makes "don't yank the reader down" work below.
val wasAtBottom = remember(state.messages.size) { isAtBottom(listState.firstVisibleItemIndex) }
...
// The list is rendered newest-first with reverseLayout = true (see the LazyColumn below), so
// the newest message is always index 0. Opening a chat (or switching to a different one) snaps
// straight to it with no animation; a message arriving/sent afterwards only animates into view
// when the reader was already at the bottom, so reading older history is never interrupted.
LaunchedEffect(state.selectedChat, state.messages.size) {
    val chatChanged = lastScrolledChat != state.selectedChat
    lastScrolledChat = state.selectedChat
    if (state.messages.isEmpty()) return@LaunchedEffect
    val newestIndex = scrollIndexForNewestMessage(state.messages.size, reverseLayout = true)
    if (chatChanged) listState.scrollToItem(newestIndex)
    else if (shouldAutoScrollToNewMessage(wasAtBottom)) listState.animateScrollToItem(newestIndex)
}
...
LazyColumn(
    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp),
    state = listState,
    // state.messages is already newest-first (it mirrors the messages_chat_time query,
    // ORDER BY ts DESC, id DESC), so reverseLayout anchors index 0 - the newest message
    // - at the bottom of the screen, next to the composer, exactly like WhatsApp. This
    // keeps the query, the controller, and every other consumer of state.messages
    // (there are none besides this screen) untouched.
    reverseLayout = true,
    verticalArrangement = Arrangement.spacedBy(5.dp),
) {
    items(state.messages.size, key = { state.messages[it].id }) { index ->
        MessageBubble(state.messages[index], actions::openAttachment)
    }
}
```

O corpo de `items { ... }` não mudou: a lista continua percorrida na mesma ordem (índice 0 =
mais nova); só o layout que a desenha foi invertido.

### Detalhe: por que `wasAtBottom` é lido fora do `LaunchedEffect`

`remember(state.messages.size)` roda durante a composição, **antes** da passagem de layout que o
`LazyColumn` faz para a nova lista. O Compose preserva a posição visual de rolagem por `key`
quando itens são inseridos no início da lista (o `items(..., key = { state.messages[it].id })` já
usava chave estável) — ou seja, se o item que estava visível no fundo da tela antes da nova
mensagem chegar for empurrado do índice 0 para o índice 1, o `firstVisibleItemIndex` também
migraria para 1 automaticamente, escondendo o fato de que o leitor estava no fundo. Lendo
`listState.firstVisibleItemIndex` durante a composição — antes dessa realocação de layout — captura
corretamente "o leitor estava no fundo antes desta mensagem chegar", que é exatamente a condição
que a regra de negócio (`shouldAutoScrollToNewMessage`) precisa.

O caso de trocar de conversa (`lastScrolledChat != state.selectedChat`) é tratado à parte, sempre
com `scrollToItem` (sem animação) e sem depender de `wasAtBottom` — reabrir uma conversa não deve
herdar a posição de rolagem da conversa anterior nem animar.

### Vantagens

- Corrige o bug com o menor diff possível: nenhuma query, nenhum formato de `UiState`/`MessageUi`,
  nenhum outro Composable foi alterado — `HomeScreen.kt` e o restante do app continuam intocados.
- A decisão de "para onde rolar" (`scrollIndexForNewestMessage`) e "deve rolar automaticamente?"
  (`shouldAutoScrollToNewMessage`, `isAtBottom`) viraram funções puras em `UiLogic.kt`, testáveis em
  JVM sem instrumentação de Compose — ver `docs/changes/UiLogic.kt.md` e
  `docs/changes/UiLogicTest.kt.md`.
- Ao abrir uma conversa, a lista já nasce ancorada no fundo (índice 0, perto da composer) porque é
  assim que `reverseLayout = true` posiciona o item de índice 0 por padrão — sem precisar de um
  `scrollToItem` extra só para o primeiro quadro.
- Uma mensagem nova só puxa a rolagem se o leitor já estava no fundo, atendendo ao requisito do
  `SPEC.md` de não "puxar" quem está lendo histórico antigo.

### Verificação

`:app:testDebugUnitTest :app:lintDebug` (via WSL/Gradle) — `BUILD SUCCESSFUL`, `UiLogicTest`
com 10 casos (8 já existentes + 2 novos), 0 falhas; `lintDebug` com 0 erros e nenhum apontamento em
`ChatScreen.kt` ou `UiLogic.kt` (os 43 warnings/1 hint pré-existentes do relatório pertencem a
outros arquivos, não tocados aqui).

## 2026-09-16 — Bolha de áudio inline (T4.8, mídia inline — parte A: áudio, item A3)

### Motivo

`MessageBubble` renderizava todo anexo (imagem, áudio, vídeo, PDF) de forma idêntica: um ícone de
arquivo genérico + nome do arquivo, clicável para abrir o visualizador em tela cheia. A tarefa pede
uma bolha de áudio estilo WhatsApp embutida diretamente na conversa: play/pause, forma de onda
arrastável/buscável, rótulos de tempo atual/total, e um controle de velocidade 1x/1.5x/2x — sem
precisar abrir nada em tela cheia.

### Como era antes

```kotlin
private fun MessageBubble(message: MessageUi, onAttachment: (String) -> Unit) {
    ...
    message.attachmentId?.let { id ->
        Row(modifier = Modifier.fillMaxWidth().clickable { onAttachment(id) }...) {
            Icon(Icons.Default.Description, ...)
            Text(message.attachmentName ?: stringResource(R.string.attachment), ...)
        }
    }
    ...
}
// chamada: MessageBubble(state.messages[index], actions::openAttachment)
```

### Como é agora

```kotlin
private fun MessageBubble(message: MessageUi, actions: UiActions) {
    ...
    message.attachmentId?.let { id ->
        if (message.mimeType?.let(::attachmentKind) == AttachmentKind.AUDIO) {
            AudioBubble(attachmentId = id, actions = actions)
        } else {
            Row(modifier = Modifier.fillMaxWidth().clickable { actions.openAttachment(id) }...) { /* inalterado */ }
        }
    }
    ...
}
// chamada: MessageBubble(state.messages[index], actions)
```

`AudioBubble` (novo, privado neste arquivo) é a peça principal:

- **Prévia (forma de onda/duração)**: `LaunchedEffect(attachmentId) { actions.loadAudioPreview(id) }`
  — serve do `MediaPreviewCache` se já estiver quente, senão decifra e decodifica fora da main
  thread (ver `docs/changes/NoMessagesController.kt.md`).
- **Reprodução sob demanda**: só ao tocar em play é que `actions.loadAudioBytes(id)` decifra os
  bytes de verdade, usados para montar um `MediaPlayer` via `MemoryMediaDataSource` (o mesmo padrão
  de `AttachmentViewer.kt`, ver `docs/changes/AttachmentViewer.kt.md`) — nunca cacheados, zerados
  quando a bolha sai de composição ou o player é liberado.
- **Forma de onda desenhada com `Canvas`**: 48 barras (`WAVEFORM_BUCKET_COUNT`), coloridas
  diferentemente até a posição de reprodução atual; `pointerInput` com `detectTapGestures`/
  `detectDragGestures` traduz a posição X em fração de busca (`seekTo`), chamando
  `MediaPlayer.seekTo`.
- **Velocidade**: botão que cicla 1x → 1.5x → 2x → 1x, aplicando via
  `mediaPlayer.playbackParams.setSpeed(...)` dentro de `runCatching` (nem todo aparelho suporta toda
  velocidade).
- **Só um player por vez**: `AudioPlaybackCoordinator.requestPlay(id) { pausa este player }` antes de
  iniciar a reprodução; `snapshotFlow { AudioPlaybackCoordinator.activeId }` pausa esta bolha se
  outra assumir o coordenador.

### Vantagens

- Reaproveita 100% do padrão de decifração/zeragem já estabelecido no app (nunca grava bytes
  decifrados em disco, nunca os mantém além do necessário).
- A prévia (forma de onda) e a reprodução (bytes brutos) são caminhos separados e com ciclos de vida
  diferentes — a prévia fica cacheada (pequena, limitada), os bytes de reprodução nunca ficam.
- Outros tipos de anexo (imagem, vídeo, PDF) continuam exatamente como estavam — só o ramo de áudio
  mudou, deixando o ponto de extensão óbvio para os próximos agentes (foto/vídeo).

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL` (17 casos em `UiLogicTest`, 0 falhas).

## 2026-09-16 — Bolha de imagem inline (T4.8, mídia inline — parte B: fotos, item B1)

### Motivo

Depois da parte A (áudio), `MessageBubble` ainda desenhava toda imagem como o mesmo ícone de
arquivo genérico + nome, exigindo abrir o visualizador em tela cheia só para ver uma miniatura —
diferente do WhatsApp, que mostra a foto direto na bolha. B1 pede uma bolha de imagem com miniatura
limitada (~512px), placeholder enquanto carrega, degradação para a linha genérica em caso de falha
de decodificação, e toque para abrir o visualizador em tela cheia.

### Como era antes

```kotlin
message.attachmentId?.let { id ->
    if (message.mimeType?.let(::attachmentKind) == AttachmentKind.AUDIO) {
        AudioBubble(attachmentId = id, actions = actions)
    } else {
        Row(modifier = Modifier.fillMaxWidth().clickable { actions.openAttachment(id) }...) {
            Icon(Icons.Default.Description, ...)
            Text(message.attachmentName ?: stringResource(R.string.attachment), ...)
        }
    }
}
```

### Como é agora

```kotlin
message.attachmentId?.let { id ->
    when (message.mimeType?.let(::attachmentKind)) {
        AttachmentKind.AUDIO -> AudioBubble(attachmentId = id, actions = actions)
        AttachmentKind.IMAGE -> ImageBubble(attachmentId = id, attachmentName = message.attachmentName, actions = actions)
        // PDF/unsupported fall back to the generic row below; so does VIDEO until a
        // later agent adds a dedicated inline video bubble here.
        else -> GenericAttachmentRow(attachmentId = id, attachmentName = message.attachmentName, actions = actions)
    }
}
```

A antiga `Row` genérica virou a função `GenericAttachmentRow` (mesmo corpo, sem mudança de
comportamento), reaproveitada tanto pelo `else` do `when` quanto pelo fallback de falha de
`ImageBubble`. `ImageBubble` (novo, privado neste arquivo):

- **Prévia**: `LaunchedEffect(attachmentId) { actions.loadImagePreview(id) }` — serve do
  `MediaPreviewCache` se já quente, senão decifra+decodifica fora da main thread (ver
  `docs/changes/NoMessagesController.kt.md`). Igual ao padrão de `AudioBubble` já estabelecido na
  parte A.
- **Estados**: placeholder (`CircularProgressIndicator` sobre uma caixa cinza) enquanto carrega;
  `Image` com a miniatura (`ContentScale.FillWidth`, altura limitada a 220dp, cantos arredondados
  combinando com a bolha) quando pronta; `GenericAttachmentRow` se `loadImagePreview` devolver
  `null` (decodificação falhou, cofre bloqueou no meio do carregamento, etc.) — nunca um espaço em
  branco.
- **Toque**: `actions.openAttachment(attachmentId)` — o mesmo call site que a linha genérica já
  usava, abrindo o visualizador em tela cheia (ver `docs/changes/AttachmentViewer.kt.md`).

### Vantagens

- Reaproveita 100% o padrão prévia-cacheada/decifra-sob-demanda que a parte A já estabeleceu para
  áudio — nenhum bitmap decodificado guardado fora de `MediaPreviewCache`/`OwnedResource`.
- `GenericAttachmentRow` extraído elimina a duplicação que existiria entre o fallback de PDF/vídeo e
  o fallback de falha de decodificação de imagem.
- PDF, texto e (por enquanto) vídeo continuam exatamente como estavam — só o ramo de imagem mudou,
  com um comentário `TODO`-style apontando onde o próximo agente (vídeo) deve adicionar seu próprio
  ramo no `when`.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL` (19 casos em `UiLogicTest`, 0 falhas;
`lintDebug` sem nenhum novo apontamento — o único ponto de `AutoboxingStateCreation` restante em
`AttachmentViewer.kt`/`ChatScreen.kt` é pré-existente da parte A, não deste arquivo).

## 2026-09-16 — Bolha de vídeo inline (T4.8, mídia inline — parte C: vídeo, agente 3 de 3)

### Motivo

`MessageBubble` tratava `AttachmentKind.VIDEO` pelo ramo genérico (`else -> GenericAttachmentRow`),
com um comentário deixado pela parte B apontando exatamente onde adicionar o ramo dedicado. A tarefa
pede uma bolha de vídeo estilo WhatsApp: poster frame limitado, ícone de play sobreposto, duração num
selo no canto, toque abrindo o player em tela cheia.

### Como era antes

```kotlin
message.attachmentId?.let { id ->
    when (message.mimeType?.let(::attachmentKind)) {
        AttachmentKind.AUDIO -> AudioBubble(attachmentId = id, actions = actions)
        AttachmentKind.IMAGE -> ImageBubble(attachmentId = id, attachmentName = message.attachmentName, actions = actions)
        // PDF/unsupported fall back to the generic row below; so does VIDEO until a
        // later agent adds a dedicated inline video bubble here.
        else -> GenericAttachmentRow(attachmentId = id, attachmentName = message.attachmentName, actions = actions)
    }
}
```

### Como é agora

```kotlin
message.attachmentId?.let { id ->
    when (message.mimeType?.let(::attachmentKind)) {
        AttachmentKind.AUDIO -> AudioBubble(attachmentId = id, actions = actions)
        AttachmentKind.IMAGE -> ImageBubble(attachmentId = id, attachmentName = message.attachmentName, actions = actions)
        AttachmentKind.VIDEO -> VideoBubble(attachmentId = id, attachmentName = message.attachmentName, actions = actions)
        // PDF/unsupported fall back to the generic row below.
        else -> GenericAttachmentRow(attachmentId = id, attachmentName = message.attachmentName, actions = actions)
    }
}
```

`VideoBubble` (novo, privado neste arquivo) segue a mesma estrutura de `ImageBubble`:

- **Prévia**: `LaunchedEffect(attachmentId) { actions.loadVideoPreview(id) }` — serve do
  `MediaPreviewCache` se já quente, senão decifra+decodifica fora da main thread (ver
  `docs/changes/NoMessagesController.kt.md`). Igual ao padrão já estabelecido para áudio/imagem.
- **Estados**: placeholder (`CircularProgressIndicator` sobre uma caixa cinza, reaproveitando
  `IMAGE_BUBBLE_MAX_HEIGHT`) enquanto carrega; poster + overlay quando pronto; `GenericAttachmentRow`
  se `loadVideoPreview` devolver `null` — nunca um espaço em branco.
- **Overlay**: um `Box` circular semi-transparente centralizado com `Icons.Default.PlayArrow` (o
  mesmo círculo-com-play do WhatsApp) e um `Surface` semi-transparente no canto inferior direito com
  a duração formatada por `formatDurationMs` (já usado pela bolha de áudio).
- **Toque**: `actions.openAttachment(attachmentId)` — abre o player em tela cheia (ver
  `docs/changes/AttachmentViewer.kt.md`), que agora tem sua própria barra de progresso arrastável.

### Vantagens

- Reaproveita 100% o padrão prévia-cacheada da parte A/B — nenhum bitmap decodificado guardado fora
  de `MediaPreviewCache`.
- `GenericAttachmentRow`/`IMAGE_BUBBLE_MAX_HEIGHT`/`formatDurationMs` reaproveitados em vez de
  duplicados — a bolha de vídeo não introduz nenhuma constante ou string nova.
- Fecha o `when` de `MessageBubble`: todo `AttachmentKind` agora tem uma bolha inline dedicada
  (áudio, imagem, vídeo) ou cai deliberadamente no fallback genérico (PDF/texto/não suportado).

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`, sem nenhum apontamento novo.


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Correcao: a bolha de audio registra teardown completo e nao vaza o data source

**Como era (47afa3e):**

```kotlin
fun releasePlayer() {
    runCatching { player?.stop() }
    player?.release()          // sem guarda: se lancar, o close() abaixo nunca roda
    dataSource?.close()
    player = null; dataSource = null; playing = false
}
...
dataSource = source
player = mediaPlayer
playerDurationMs = mediaPlayer.duration.coerceAtLeast(0)
AudioPlaybackCoordinator.requestPlay(attachmentId) { runCatching { mediaPlayer.pause() }; playing = false }
```

**Como ficou:**

```kotlin
val playerToken = remember(attachmentId) { Any() }

fun releasePlayer() {
    AudioPlaybackCoordinator.unregisterPlayer(playerToken)
    runCatching { player?.stop() }
    runCatching { player?.release() }
    runCatching { dataSource?.close() }    // e o que zera os bytes decifrados deste clipe
    player = null; dataSource = null; playing = false
}
...
dataSource = source
player = mediaPlayer
AudioPlaybackCoordinator.registerPlayer(playerToken) { releasePlayer() }
playerDurationMs = ...
```

**Por que era necessario.** Dois problemas: (1) `player?.release()` sem `runCatching` podia pular o
`dataSource?.close()`, que e exatamente o passo que zera o PCM/AAC decifrado do clipe; (2) sem o
registro no `AudioPlaybackCoordinator`, o auto-lock em segundo plano nao tinha como parar este player
nem zerar seus bytes - ver `docs/changes/AudioPlaybackCoordinator.kt.md`.

**Garantia restaurada.** Um player de audio inline e sempre parado, liberado e tem seus bytes
decifrados zerados no lock do cofre, inclusive quando o app esta em segundo plano e nenhuma
recomposicao acontece.

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.

---

## 2026-09-16 — Composer com teclado privado + aviso de teclado de terceiros (T4.9)

### Motivo

O campo de mensagem é o campo de texto mais usado do app e o mais óbvio candidato a vazamento via
IME (aprendizado/sugestões do teclado do sistema). T4.9 pede a mesma proteção de `PrivateInput.kt`
aqui, mais um aviso discreto quando o IME padrão não é um app de sistema.

### Como era antes

```kotlin
OutlinedTextField(
    value = draft,
    onValueChange = { draft = it },
    modifier = Modifier.weight(1f),
    enabled = composerEnabled && !state.recordingAudio,
    placeholder = { Text(stringResource(R.string.message_hint)) },
    shape = RoundedCornerShape(24.dp),
    maxLines = 5,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
    keyboardActions = KeyboardActions(onSend = { send() }),
)
```

`ChatScreen` não recebia nenhum sinal sobre o teclado ativo.

### Como ficou

```kotlin
PrivateImeScope {
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.weight(1f),
        enabled = composerEnabled && !state.recordingAudio,
        placeholder = { Text(stringResource(R.string.message_hint)) },
        shape = RoundedCornerShape(24.dp),
        maxLines = 5,
        keyboardOptions = privateKeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
    )
}
```

`ChatScreen(state, actions, isGroup, thirdPartyImeActive: Boolean = false)` ganhou o novo
parâmetro; quando `true`, uma linha `Text` discreta (cor de erro) com
`R.string.third_party_keyboard_warning` aparece no `bottomBar`, acima do aviso de gravação e da
barra do composer. O import `androidx.compose.foundation.text.KeyboardOptions`, sem uso direto
restante, foi removido.

### Vantagens

- O campo mais usado do app (uma mensagem por envio) ganha as mesmas duas flags de privacidade de
  IME que os campos de senha.
- O aviso de teclado de terceiros aparece exatamente onde o usuário está prestes a digitar algo
  sensível — a conversa — sem bloquear o envio nem exigir nenhuma ação.
- `thirdPartyImeActive` tem valor padrão `false`, então nenhum outro chamador de `ChatScreen`
  (testes, previews) precisa mudar.

### Por que a mudança foi feita

T4.9, itens 1 e 4.

## 2026-09-17 — Auditoria de privacidade de anexos recebidos: texto recebido não selecionável

Pedido do usuário após teste no aparelho: **mídia e anexos RECEBIDOS não podem ser salvos no
celular nem compartilhados com outros aplicativos**. Esta seção registra a auditoria completa dos
vetores de exfiltração na camada de conversa e a única mudança de código que ela exigiu.

### Auditoria — o que foi procurado e o que foi encontrado

Varredura em todo o repositório (`*.kt`, `*.java`, `*.xml`, excluindo `build/` e `.git/`) pelos
padrões `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_VIEW`, `Intent.createChooser`,
`FileProvider`, `getUriForFile`, `grantUriPermission`, `FLAG_GRANT_READ_URI_PERMISSION`,
`MediaStore`, `Environment.`, `dragAndDropSource`, `DragAndDropTarget`, `SelectionContainer`,
`ContextMenuArea`, `TextToolbar`, `combinedClickable`/`onLongClick` e `<provider`.

| Vetor | Resultado |
| --- | --- |
| `Intent` de compartilhar/abrir anexo (`ACTION_SEND`/`ACTION_SEND_MULTIPLE`/`ACTION_VIEW`/`createChooser`) | **Auditado, nenhum problema encontrado.** Nenhuma ocorrência no app. Os únicos `Intent` existentes são `PendingIntent` para a própria `MainActivity` (`PrivacyNotifications.kt`) e `bind/stopService` do `TorService` (`TorConnection.kt`). |
| `FileProvider` / `<provider>` exportando bytes de anexo | **Auditado, nenhum problema encontrado.** O único `<provider>` do `AndroidManifest.xml` é o `androidx.startup.InitializationProvider`, com `android:exported="false"`, e existe apenas para *remover* o `EmojiCompatInitializer`. Não há `res/xml/file_paths.xml` nem qualquer declaração `files-path`/`cache-path`/`external-path`. |
| Gravação em `MediaStore`, `DIRECTORY_DOWNLOADS`, `DIRECTORY_PICTURES`/galeria ou fora do cofre | **Auditado, nenhum problema encontrado.** Anexo recebido só é persistido como **ciphertext** dentro de `filesDir/vault` (`MessagingEngine.receiveAttachment`); a leitura para exibição (`NoMessagesController.openAttachment` → `decryptAttachment`) devolve os bytes **em memória**, que são zerados em `closeAttachment()`/`lock()`. Nenhuma `FileOutputStream` de plaintext, nenhum uso de `Environment` ou `MediaStore`. |
| Item de UI/menu de "salvar", "compartilhar", "abrir com", "baixar" para anexo recebido | **Auditado, nenhum problema encontrado.** Os únicos `DropdownMenu` de `ChatScreen.kt` são o de **envio** (anexar arquivo / tirar foto) e o de **administração de grupo** (remover membro / sair do grupo). Não há menu de contexto, `combinedClickable` nem `onLongClick` em nenhuma bolha. `R.string.save` ("Salvar") é usada apenas por botões de formulário do `SettingsScreen.kt`, e `export_vault` refere-se ao backup **cifrado** do cofre inteiro (intencional, exige a senha) — nada disso exporta anexo em claro. |
| Drag-and-drop para fora do app (`Modifier.dragAndDropSource`, `DragAndDropTarget`) | **Auditado, nenhum problema encontrado.** Nenhuma ocorrência. Os `pointerInput` existentes são gestos internos: pinch-zoom/pan e swipe entre imagens no visualizador, e tap/drag de *seek* na forma de onda do áudio. |
| Menu de contexto do sistema sobre mensagem recebida (`ContextMenuArea`/`TextToolbar`) | **Corrigido de forma preventiva** — ver abaixo. |
| Seleção de texto recebido | **Corrigido de forma preventiva** — ver abaixo. |

### Como era antes

```kotlin
if (message.text.isNotEmpty()) Text(message.text, style = MaterialTheme.typography.bodyLarge)
```

Uma única linha, igual para mensagem enviada e recebida. Na prática o texto **já não era
selecionável**: no Compose, `Text` só entra em seleção se algum ancestral for um
`SelectionContainer`, e não existe nenhum `SelectionContainer` no app (confirmado por varredura).
Ou seja, não havia vazamento ativo — mas também não havia nada que *impedisse* o vazamento de
voltar: bastaria alguém, no futuro, embrulhar essa lista (ou uma tela que a reaproveite) em um
`SelectionContainer` para que toda mensagem recebida virasse texto selecionável, com alças de
seleção, `TextToolbar` de copiar e arrasto do trecho selecionado para fora do app.

### Como é agora

```kotlin
// Received message bodies render with selection explicitly disabled. A plain
// `Text` is already non-selectable unless some ancestor `SelectionContainer` opts
// it in, so this is not fixing a live leak - it makes the guarantee structural:
// ...
if (message.text.isNotEmpty()) {
    if (message.outgoing) {
        Text(message.text, style = MaterialTheme.typography.bodyLarge)
    } else {
        DisableSelection {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

Novo import: `androidx.compose.foundation.text.selection.DisableSelection`.

`DisableSelection` publica um `LocalSelectionRegistrar` nulo para a subárvore, então o `Text` de
dentro não se registra em nenhuma seleção — mesmo que exista um `SelectionContainer` acima. Logo,
para a bolha **recebida** ficam impossíveis, por construção: seleção, alças de seleção, barra de
contexto de copiar do sistema e arrasto do texto selecionado para outro app.

### O que deliberadamente NÃO foi mexido

- **Composer (`OutlinedTextField` do `bottomBar`)**: continua com copiar/colar normal do sistema.
  O que o usuário está digitando é o texto dele, ainda não enviado; restringir isso só atrapalharia
  sem ganho de privacidade.
- **Bolha enviada (`message.outgoing == true`)**: mantém o `Text` simples. O conteúdo é do próprio
  usuário e o escopo do pedido é anexo/mensagem **recebida**.
- **`copySensitive` do `SettingsScreen.kt`** (endereço onion): intencional e operacionalmente
  necessário; `SettingsScreen.kt` não foi tocado.
- **`R.string.export_vault`**: exporta o cofre **cifrado** inteiro, protegido pela senha do usuário,
  via `CreateDocument` com `Intent.EXTRA_LOCAL_ONLY` (`MainActivity.LocalVaultExport`). Não é um
  caminho de exfiltração de anexo em claro.

### Vantagens

- O requisito ("anexo recebido não sai do app") deixa de depender de um *default* do Compose e passa
  a ser aplicado explicitamente no ponto de renderização, sobrevivendo a refatorações futuras —
  inclusive à tela de "Encaminhar" que outro agente vai adicionar a este mesmo arquivo.
- Mudança mínima e localizada (um import + um `if` em `MessageBubble`), fácil de revisar e de fazer
  merge com trabalho paralelo no `ChatScreen.kt`.
- Nenhuma string nova, nenhum recurso novo, nenhuma mudança de API (`UiActions`/`UiState` intactos).

### Por que a mudança foi feita

Privacidade — impedir exfiltração de conteúdo recebido. O usuário exige que mídia/anexos recebidos
não possam ser salvos no aparelho nem compartilhados com outros aplicativos; o texto recebido segue
a mesma regra.

## 2026-09-17 — Encaminhar mensagem (parte C, camada de UI): toque longo, seletor de destinatários e rótulo "Encaminhada"

Terceira e última parte da funcionalidade "Encaminhar mensagem" pedida pelo usuário (estilo
WhatsApp). As partes anteriores já estavam prontas quando esta mudança começou e **não** foram
tocadas: `UiContract.kt` (`MessageUi.forwarded`, `ForwardTargetUi`, `UiState.forwardTargets`,
`UiActions.forwardMessage`) e `NoMessagesController.kt` (o `forwardMessage` que decodifica o
envelope original e reenvia texto/anexo por destino). Esta entrada cobre só o que a tela faz.

### Como era antes

- `MessageBubble(message, actions)` não tinha nenhum gesto de toque longo — não havia como escolher
  uma mensagem para nada.
- `ImageBubble`/`VideoBubble`/`GenericAttachmentRow` abriam o anexo com `Modifier.clickable { ... }`.
- A bolha não exibia nenhuma marca de procedência, mesmo para uma mensagem reenviada.
- `ChatScreen` tinha dois diálogos (`leaveConfirmation`, `removeConfirmation`) e nenhum seletor.

### Como é agora

**1. Toque longo — abordagem escolhida: opção B (detector dedicado), não `combinedClickable`.**

No `Row` externo do `MessageBubble`:

```kotlin
.pointerInput(message.id) { detectTapGestures(onLongPress = { onLongPress() }) }
```

Por que a opção B e não `Modifier.combinedClickable`:

- `combinedClickable` continua sendo `@ExperimentalFoundationApi` na maioria das versões do Compose
  Foundation, e **este módulo compila com `allWarningsAsErrors.set(true)`** (`app/build.gradle.kts`).
  Isso torna a escolha assimétrica e arriscada: se a API ainda for experimental e faltar o `@OptIn`,
  é erro; se já tiver sido estabilizada e o `@OptIn` estiver presente, o aviso "Unnecessary @OptIn"
  também vira erro. Não há como acertar os dois casos sem compilar, e o host desta sessão não tem
  toolchain Android para verificar. `detectTapGestures` é estável e já estava importado no arquivo —
  zero acoplamento a esse detalhe de versão.
- O detector declara **apenas** `onLongPress`, sem `onTap`: ele nunca consome um toque curto, então
  não interfere no tap-to-seek/drag-to-seek do `AudioBubble`, nos botões de play/velocidade, nem no
  tap-to-open das bolhas de mídia.

**2. Filhos com gesto próprio recebem o mesmo `onLongPress` (correção de um bug real, não estética).**

`Modifier.clickable` **não tem timeout de toque longo**: ele dispara `onClick` na soltura do dedo,
por mais tempo que a pressão tenha durado. Portanto, manter `.clickable` nos filhos e só adicionar o
detector no pai produziria, num toque longo sobre uma foto/vídeo/anexo: o seletor de destinatários
abrindo aos ~400 ms **e**, ao soltar o dedo, o visualizador em tela cheia abrindo por cima dele.

A correção é um único detector por superfície, extraído para um modifier privado:

```kotlin
private fun Modifier.attachmentTapGestures(key: Any, onOpen: () -> Unit, onLongPress: () -> Unit): Modifier =
    this
        .pointerInput(key) { detectTapGestures(onLongPress = { onLongPress() }, onTap = { onOpen() }) }
        .semantics {
            role = Role.Button
            onClick { onOpen(); true }
        }
```

Com `onLongPress` declarado, `detectTapGestures` deixa de reportar a soltura como `onTap` — toque
curto e toque longo ficam mutuamente exclusivos, que é exatamente o que o `combinedClickable` faz
internamente, sem o opt-in experimental. O bloco `semantics` devolve o que a remoção do `.clickable`
custaria: a ação de clique e o `Role.Button` que o TalkBack usa para ativar o anexo. O único item
realmente abdicado é o ripple, que nunca aparecia sob uma miniatura que ocupa a bolha inteira.

O mesmo tratamento foi aplicado ao `Canvas` da onda do `AudioBubble` (que já usava
`detectTapGestures` para o seek): passou a declarar `onLongPress`, para que um toque longo sobre a
onda encaminhe em vez de reposicionar o áudio ao soltar. O arraste continua fazendo seek, porque um
arraste não é um toque longo.

Quando o detector do pai e o de um filho disparam para a mesma pressão, os dois chamam
`onForward(message)` com a mesma mensagem — idempotente, sem recomposição extra (um `mutableStateOf`
não notifica ao receber um valor igual ao atual).

**3. Estado local e limpeza.**

```kotlin
var forwardingMessage by remember { mutableStateOf<MessageUi?>(null) }
var forwardSelection by remember(forwardingMessage) { mutableStateOf(emptySet<String>()) }
```

`remember(forwardingMessage)` é o que garante seleção vazia a cada abertura: fechar o diálogo e
segurar outra mensagem nunca herda os destinatários marcados antes. O
`DisposableEffect(state.selectedChat)` existente ganhou `forwardingMessage = null` junto dos resets
de `draft`/`attachmentMenu`/`groupMenu`, porque a mensagem escolhida pertence à conversa que está
sendo deixada.

**4. Diálogo de destinatários — direto, sem menu de contexto intermediário.**

Das duas leituras possíveis do pedido ("menu de toque longo → Encaminhar → seletor"), foi adotada a
direta: o toque longo abre o seletor. Hoje a bolha oferece uma única ação, então um menu de um item
só seria um toque a mais sem escolha nenhuma — e mais um passo de código para manter. Por isso
**não** foi criada a string `forward` (rótulo de item de menu); se um segundo comando de bolha
(responder, apagar) aparecer no futuro, o menu passa a valer e essa string entra junto com ele.

O corpo do diálogo usa `LazyColumn(Modifier.heightIn(max = 320.dp))`, não `Column`: o corpo de um
`AlertDialog` é limitado em altura e um cofre pode ter até 1.024 contatos (`error_contact_limit`),
então a lista precisa rolar e reciclar em vez de compor todos os destinos de uma vez. Cada linha é
`Checkbox` + (ícone `Group`, quando o destino é grupo) + título; a linha inteira alterna a marcação
e o checkbox é a leitura visual desse estado. O botão de envio usa `enabled = canConfirmForward(...)`
e faz **uma** chamada `actions.forwardMessage(message.id, forwardSelection.toList())` com a seleção
inteira — o controlador é quem gera um envelope novo e independente por destino. Com
`state.forwardTargets` vazio, o corpo vira a frase `forward_no_targets` e o botão fica desabilitado.

A lógica de marcação/habilitação ficou em `UiLogic.kt` (`toggleForwardTarget`, `canConfirmForward`),
testada em JVM — ver `docs/changes/UiLogic.kt.md`.

**5. Rótulo "Encaminhada".**

Primeira linha do `Column` da bolha, antes do anexo e do texto, só quando `message.forwarded`:

```kotlin
Text(
    "↪ " + stringResource(R.string.forwarded_label),
    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 2.dp),
)
```

Glifo Unicode `↪` em vez de um ícone Material novo: não havia como validar o path SVG de um ícone
vendorizado sem compilar/renderizar, e um ícone quebrado seria pior que um glifo. `onSurfaceVariant`
em vez do `Color(0xFF687873)` usado no horário, porque é um token de tema e funciona nos temas claro
e escuro.

O rótulo diz apenas "esta mensagem foi encaminhada" — deliberadamente sem conversa de origem, sem
remetente original e sem horário original: a cópia não carrega nada disso e a UI não pode sugerir
que carregue.

### Novos imports

`androidx.compose.material3.Checkbox`, `androidx.compose.ui.text.font.FontStyle` e
`androidx.compose.ui.semantics.{Role, onClick, role, semantics}`. Nenhum import foi removido
(`clickable` continua em uso na linha do seletor).

### O que deliberadamente NÃO foi mexido

- `DisableSelection` do texto recebido (auditoria de privacidade do mesmo dia) — preservado
  exatamente como estava.
- `UiContract.kt`, `NoMessagesController.kt`, `MessagingEngine.kt`, `AttachmentViewer.kt`: a tela só
  consome `state.forwardTargets` e chama `actions.forwardMessage`.
- Comportamento de toque curto de qualquer bolha: abrir anexo, play/pause, seek e troca de
  velocidade continuam idênticos.

### Vantagens

- Nenhuma dependência de API experimental num módulo que trata aviso como erro.
- O bug "abre o seletor e o visualizador ao mesmo tempo" fica impossível por construção, em vez de
  depender de o `AlertDialog` cancelar o gesto da janela de baixo (o que não é garantido).
- Acessibilidade preservada nas superfícies de anexo (ação de clique + `Role.Button` explícitos).
- A seleção nunca vaza entre mensagens nem entre conversas (chaves de `remember` + reset no
  `DisposableEffect`).
- Regra de seleção/habilitação isolada em funções puras, testável sem instrumentação.

### Por que a mudança foi feita

Pedido do usuário: encaminhar mensagem no estilo WhatsApp (parte C — camada de UI).

### Validação

Gradle não foi executado nesta sessão (instrução explícita: o usuário roda build e testes ao final).
A lógica pura extraída está coberta por testes JUnit 5 em
`app/src/test/kotlin/dev/mx3/nomessages/ui/UiLogicTest.kt`.

---

## 2026-09-17 — Nova paleta "Grafite e Âmbar": dez pontos de chamada trocam cor hardcoded por `MaterialTheme.colorScheme` (T4.14)

Dez pontos de chamada de cor neste arquivo (o de maior contagem entre as telas do rebrand — bolha
de mensagem, três tipos de bolha de mídia e a barra superior estão todos aqui). Nenhum outro
comportamento (encaminhar, seleção de texto, bolhas de mídia, composer) foi tocado.

### 1. Barra superior (`TopAppBar`)

```kotlin
// antes
colors = TopAppBarDefaults.topAppBarColors(containerColor = WfDeepGreen, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),

// depois
colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary),
```

### 2. Ícone de enviar/gravar áudio

```kotlin
// antes
tint = WfGreen,

// depois
tint = MaterialTheme.colorScheme.secondary,
```

### 3. Ícone de grupo no seletor de encaminhamento

```kotlin
// antes
if (target.isGroup) {
    Icon(Icons.Default.Group, contentDescription = null, tint = WfGreen)
    ...
}

// depois
if (target.isGroup) {
    Icon(Icons.Default.Group, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
    ...
}
```

### 4. Fundo e texto da bolha de mensagem enviada

```kotlin
// antes
color = if (message.outgoing) WfBubble else MaterialTheme.colorScheme.surface,
contentColor = if (message.outgoing) Color(0xFF17211E) else MaterialTheme.colorScheme.onSurface,

// depois
color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
contentColor = if (message.outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
```

### 5. Texto do horário na bolha

```kotlin
// antes
Text(message.time, style = MaterialTheme.typography.labelSmall, color = Color(0xFF687873))

// depois
Text(message.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
```

### 6. Selo de entrega da mensagem enviada

```kotlin
// antes
Text(deliveryMark(message.status), color = if (message.status.equals("read", ignoreCase = true)) WfRead else Color(0xFF687873), style = MaterialTheme.typography.labelMedium)

// depois
Text(deliveryMark(message.status), color = if (message.status.equals("read", ignoreCase = true)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
```

### 7. Ícone de anexo genérico (`GenericAttachmentRow`)

```kotlin
// antes
Icon(Icons.Default.Description, contentDescription = null, tint = WfGreen)

// depois
Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
```

### 8. Spinner de carregamento da prévia de imagem (`ImageBubble`)

```kotlin
// antes
CircularProgressIndicator(color = WfGreen)

// depois
CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
```

### 9. Spinner de carregamento da prévia de vídeo (`VideoBubble`)

```kotlin
// antes
CircularProgressIndicator(color = WfGreen)

// depois
CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
```

### 10. Bolha de áudio (`AudioBubble`): ícone de play/pause, barra "tocada" da forma de onda e rótulo de duração

```kotlin
// antes
Icon(
    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
    tint = WfGreen,
)
...
drawLine(
    color = if (played) WfGreen else Color(0xFF9AA7A2),
    ...
)
...
color = Color(0xFF687873),

// depois
Icon(
    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
    tint = MaterialTheme.colorScheme.secondary,
)
...
drawLine(
    color = if (played) MaterialTheme.colorScheme.secondary else Color(0xFF9AA7A2),
    ...
)
...
color = MaterialTheme.colorScheme.onSurfaceVariant,
```

O cinza `Color(0xFF9AA7A2)` da barra "não tocada" da forma de onda **não** é uma cor hardcoded antiga e foi
deixado como está — é um cinza neutro independente da paleta de marca, usado só para o trecho ainda
não reproduzido da forma de onda. O rótulo de duração (`Color(0xFF687873)` → `onSurfaceVariant`) foi
mapeado para o mesmo token usado no horário da bolha (item 5) e no selo de entrega de fallback (item
6) — os três usavam exatamente o mesmo hexadecimal hardcoded neste arquivo, então convergem para o
mesmo papel de tema por consistência.

### Vantagens

- Toda superfície de cor deste arquivo — chrome da conversa, ícones de ação, as três bolhas de mídia
  e a bolha de texto — passa a reagir a tema claro/escuro, em vez de um verde fixo.
- Os três usos independentes do mesmo hex `#687873` (horário, selo de entrega, duração de áudio)
  convergem para o mesmo `onSurfaceVariant`, então uma futura mudança de tom não corre o risco de
  atualizar dois lugares e esquecer o terceiro.
- Bolha enviada/recebida continua distinguível sem depender só de matiz: além do tom
  (`primaryContainer` âmbar vs. `surface` neutro), a posição (`Arrangement.End`/`Start`, já existente
  antes desta mudança) marca a diferença — ver `docs/changes/NoMessagesTheme.kt.md`.

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" em vez da identidade verde-WhatsApp.

### Correção pós-revisão (2026-09-17, mesmo dia — build quebrado, achado pela validação Gradle)

`:app:compileDebugKotlin` falhou depois da migração de T4.14: dentro do `Canvas { ... }` do
waveform de `AudioBubble` (uma lambda `DrawScope`, não `@Composable`), o `drawLine` de cada barra
tocada chamava `MaterialTheme.colorScheme.secondary` diretamente:

```kotlin
drawLine(
    color = if (played) MaterialTheme.colorScheme.secondary else Color(0xFF9AA7A2),
    ...
)
```

Erro do compilador: `@Composable invocations can only happen from the context of a @Composable
function` — `Canvas`'s `onDraw` roda fora do slot composable, então ler `MaterialTheme.colorScheme`
ali dentro não compila (diferente de `tint = MaterialTheme.colorScheme.secondary` nos `Icon(...)`
do mesmo arquivo, que ficam em contexto composable normal e compilam sem problema).

**Correção:** ler a cor uma vez no corpo composable de `AudioBubble`, antes do `Canvas`, e capturar
o valor local dentro da lambda de desenho:

```kotlin
val playedBarColor = MaterialTheme.colorScheme.secondary
...
Canvas(...) {
    ...
    drawLine(color = if (played) playedBarColor else Color(0xFF9AA7A2), ...)
}
```

Único `Canvas` de toda a árvore de UI (`grep -n "Canvas(" app/src/main/kotlin/dev/mx3/nomessages/ui/*.kt`
retorna só esta ocorrência), então não havia outro ponto igual a corrigir.
