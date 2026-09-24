# app/src/main/kotlin/dev/mx3/nomessages/ui/NoMessagesApp.kt

## 2026-09-16 — Passa `imageGallery`/`onNavigate` para `AttachmentViewer` (T4.8, mídia inline — parte B: fotos, item B3)

### Motivo

`AttachmentViewer` ganhou suporte a deslizar entre as imagens de uma conversa (ver
`docs/changes/AttachmentViewer.kt.md`), o que exige dois dados novos que só `UiState`/`UiActions`
têm: a lista ordenada de ids (`state.imageGallery`) e o callback que decifra o próximo/anterior
(`actions.openAttachment`, reaproveitado — não uma ação nova). Este arquivo tem o único call site de
`AttachmentViewer` no app, então é o único lugar que precisava mudar.

### Como era antes

```kotlin
state.attachment?.let { attachment ->
    AttachmentViewer(attachment = attachment, onClose = actions::closeAttachment)
}
```

### Como é agora

```kotlin
state.attachment?.let { attachment ->
    AttachmentViewer(
        attachment = attachment,
        gallery = state.imageGallery,
        onNavigate = actions::openAttachment,
        onClose = actions::closeAttachment,
    )
}
```

### Vantagens

- Diff mínimo: só o call site muda, nada mais neste arquivo (nem `UnlockedApp`, nem a navegação por
  `Destination`) foi tocado.
- `onNavigate` reaproveita `actions::openAttachment` — a mesma função que já decifra/zera o anexo
  anterior antes de abrir o novo — em vez de precisar de uma ação nova em `UiActions` só para
  navegação de galeria.

### Validação

`:app:testDebugUnitTest :app:lintDebug`: `BUILD SUCCESSFUL`.

---

## 2026-09-16 — Encaminha `thirdPartyImeActive` até `ChatScreen`/`SetupScreen`/`LockScreen` (T4.9)

### Motivo

`MainActivity.kt` calcula, a cada `onStart`, se o IME padrão do sistema é de terceiros
(`computeThirdPartyImeActive`, delegando a decisão pura a `isSystemIme` em `UiLogic.kt`). Esse
sinal precisa chegar às telas que mostram o aviso (`ChatScreen.kt`, `SetupLockScreens.kt`) sem
passar por `UiState`/`NoMessagesController` — é um sinal de plataforma (qual teclado está ativo
agora), não estado de app/cofre, e mantê-lo fora do controlador evita tocar qualquer lógica de
negócio para uma funcionalidade puramente de UI/disclosure.

### Como era antes

```kotlin
@Composable
fun NoMessagesApp(state: UiState, actions: UiActions) {
    ...
    !state.configured -> SetupScreen(state, actions)
    !state.unlocked -> LockScreen(state, actions)
    else -> UnlockedApp(state, actions)
    ...
}

@Composable
private fun UnlockedApp(state: UiState, actions: UiActions) {
    ...
    ChatScreen(state = state, actions = actions, isGroup = selected?.group == true)
    ...
}
```

### Como ficou

```kotlin
@Composable
fun NoMessagesApp(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean = false) {
    ...
    !state.configured -> SetupScreen(state, actions, thirdPartyImeActive)
    !state.unlocked -> LockScreen(state, actions, thirdPartyImeActive)
    else -> UnlockedApp(state, actions, thirdPartyImeActive)
    ...
}

@Composable
private fun UnlockedApp(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean) {
    ...
    ChatScreen(
        state = state,
        actions = actions,
        isGroup = selected?.group == true,
        thirdPartyImeActive = thirdPartyImeActive,
    )
    ...
}
```

### Vantagens

- `thirdPartyImeActive: Boolean = false` no topo (`NoMessagesApp`) tem valor padrão, então nenhum
  outro chamador existente precisou mudar além de `MainActivity.kt`.
- O sinal atravessa só as telas que realmente precisam dele (setup, lock, chat) sem tocar
  `UiState`/`UiActions`/`NoMessagesController` — nenhum risco de conflito com trabalho concorrente
  no controlador.

### Por que a mudança foi feita

T4.9, item 4 (plumbing do aviso de teclado de terceiros até as telas).

---

## 2026-09-17 — Home simplificada: `Destination` perde um destino sem tela própria

### Como era antes

`private enum class Destination` tinha um valor a mais do que telas reais existiam, e o `when` de
`UnlockedApp` tinha um ramo próprio para ele antes do ramo `Destination.CHATS -> HomeScreen(...)`.

### Como ficou

O enum ficou só com os destinos que de fato têm tela própria (o `LaunchedEffect` de
`state.selectedChat` já só usava `Destination.CHATS`/`GROUP`), e o `when` correspondente perdeu o
ramo morto (ver `docs/changes/HomeScreen.kt.md`):

```kotlin
private enum class Destination { CHATS, CONTACTS, CONTACT_INFO, PAIRING, GROUP, SETTINGS }

// ...
when (destination) {
    Destination.CHATS -> HomeScreen(
        state = state,
        actions = actions,
        onShowChats = { destination = Destination.CHATS },
        onContacts = { destination = Destination.CONTACTS },
        onPairing = { destination = Destination.PAIRING },
        onSettings = { destination = Destination.SETTINGS },
    )
    // ...
}
```

### Vantagens

- O ramo removido não tinha nenhum comportamento real além de trocar qual composable aparecia.
- O `when` de `UnlockedApp` fica com um ramo por tela de verdade, sem um `Destination` sem tela
  própria para rastrear.

### Por que a mudança foi feita

Remover a estrutura de navegação morta por completo, incluindo o destino que só existia
para alternar para uma tela sem comportamento próprio.

### Correção pós-revisão (mesmo dia)

`onShowChats = { destination = Destination.CHATS }` continuou sendo passado para `HomeScreen`
mesmo depois de a linha de abas (o único lugar que chamava `onShowChats`) ter sido removida —
parâmetro morto de ponta a ponta. Removido junto com a limpeza em `HomeScreen.kt` (ver
`docs/changes/HomeScreen.kt.md`, seção "Correção pós-revisão").

## 2026-09-23 — Correção da revisão: changelog duplicado com o nome antigo do produto removido

Uma renomeação anterior (`git mv` do arquivo de UI, do nome antigo do produto para
`NoMessagesApp.kt`) deveria ter levado junto seu arquivo de changelog, mas a versão com o nome
antigo no próprio nome de arquivo (`docs/changes/`, prefixo do nome antigo + `App.kt.md`) ficou para
trás como um arquivo separado, com o conteúdo já duplicado neste arquivo (a seção "2026-09-16 —
Passa `imageGallery`/`onNavigate` para `AttachmentViewer`" é idêntica nos dois). Esse arquivo foi
removido com `git rm` — nenhum conteúdo foi perdido, e nenhum arquivo no repositório carrega mais o
nome antigo do produto no próprio nome.
