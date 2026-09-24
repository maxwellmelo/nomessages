# app/src/main/kotlin/dev/mx3/nomessages/ui/HomeScreen.kt

## 2026-09-14 — T4.2: selo de não lidas passa a usar a regra pura

Tarefa: T4.2 de `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.
Escopo: apenas a coluna direita de `ChatRow`. O restante da tela não foi tocado.

### Como era antes

```kotlin
Text(chat.time, style = MaterialTheme.typography.labelSmall, color = if (chat.unread > 0) WfGreen else MaterialTheme.colorScheme.onSurfaceVariant)
if (chat.unread > 0) {
    Badge(containerColor = WfAccent, contentColor = Color(0xFF073D29)) {
        Text(chat.unread.coerceAtMost(999).toString())
    }
} else {
    Spacer(Modifier.size(16.dp))
}
```

Três problemas: `chat.unread` nunca era diferente de `0` (o controlador jamais
preenchia o campo), a condição `> 0` estava duplicada e o teto `999` ficava
embutido no layout, sem teste e mentindo — uma conversa com 1.200 mensagens
novas exibia exatamente `999`.

### Como ficou

```kotlin
val badge = unreadBadge(chat.unread)
Text(chat.time, style = MaterialTheme.typography.labelSmall, color = if (badge.isNotEmpty()) WfGreen else MaterialTheme.colorScheme.onSurfaceVariant)
if (badge.isNotEmpty()) {
    Badge(containerColor = WfAccent, contentColor = Color(0xFF073D29)) {
        Text(badge)
    }
} else {
    Spacer(Modifier.size(16.dp))
}
```

O rótulo é calculado uma vez e a mesma resposta decide as duas coisas que
dependiam dele: a cor do horário e a existência do selo. A regra em si mora em
`UiLogic.kt` (`unreadBadge`), coberta por `UiLogicTest.kt`.

O ramo `else` com o `Spacer(Modifier.size(16.dp))` foi mantido de propósito:
é ele que impede a linha da conversa de mudar de altura conforme o selo aparece
e some.

### Vantagens

- O selo deixa de ser código morto: com `ChatUi.unread` preenchido pelo
  controlador, a lista passa a mostrar quantas mensagens novas há em cada conversa.
- Uma única fonte da verdade para "tem selo?" — antes eram duas comparações
  independentes que podiam divergir numa edição futura.
- O teto de 999 sai do Composable e vira regra testada, agora com sufixo `+`.

### Por que a mudança foi feita

T4.2 registrava o selo de `HomeScreen.kt:153-156` como código morto e exigia
implementá-lo ou removê-lo. A decisão foi implementar, com contador puramente
local — nenhum recibo de leitura trafega na rede.

---

## 2026-09-17 — Home simplificada para uma única lista de conversas

### Como era antes

`HomeScreen` montava um `Scaffold` com `topBar`, uma barra de abas acima do `NetworkBanner` e um
corpo condicional que escolhia entre um estado vazio e a lista de conversas conforme a aba
selecionada.

### Como ficou

Como só resta um destino no corpo da tela — a lista de conversas —, uma barra de abas com uma aba
só deixou de fazer sentido e foi removida; o FAB sempre abre o pareamento de um novo contato, e o
corpo do `Scaffold` decide apenas entre `EmptyState` (sem conversas) e `LazyColumn` (com
conversas):

```kotlin
internal fun HomeScreen(
    state: UiState,
    actions: UiActions,
    onShowChats: () -> Unit,
    onContacts: () -> Unit,
    onPairing: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(/* ... */)
                NetworkBanner(state.network, compact = true)
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onPairing, /* ... */) { /* ... */ }
        },
    ) { padding ->
        if (state.chats.isEmpty()) {
            EmptyState(/* ... */)
        } else {
            LazyColumn(/* ... */) { /* ... */ }
        }
    }
}
```

O import `androidx.compose.foundation.layout.Box` foi removido, por ficar sem uso; `Row`/
`Modifier.fillMaxWidth` continuam importados porque `ChatRow` ainda os usa.

### Vantagens

- A estrutura removida não tinha comportamento além de um texto fixo estático. Mantê-la custava
  manutenção (parâmetros, branch, composable, strings) sem nenhum benefício para quem usa o app.
- `HomeScreen` fica mais simples de ler: um destino, uma árvore de decisão (`chats.isEmpty()`),
  sem estado de aba selecionada para rastrear.
- O FAB de novo chat deixa de ter uma condição — sempre existe uma conversa possível de iniciar
  na única tela que `HomeScreen` agora renderiza.

### Por que a mudança foi feita

Simplificar a Home para o único destino que a tela de fato usa, removendo completamente a
estrutura morta em vez de manter UI/strings/branches sem uso.

### Correção pós-revisão (mesmo dia)

A revisão flagrou um resíduo: `onShowChats: () -> Unit` continuava na assinatura de
`HomeScreen()` e sendo passado por `NoMessagesApp.kt`, mas nada dentro de `HomeScreen`
o chamava mais — a linha de abas era o único chamador, e ela já tinha sido removida. Parâmetro
morto apagado dos dois arquivos (`HomeScreen.kt`, `NoMessagesApp.kt`); nenhum outro caller existia.

---

## 2026-09-17 — Nova paleta "Grafite e Âmbar": cinco pontos de chamada trocam cor hardcoded por `MaterialTheme.colorScheme` (T4.14)

Cada um dos cinco pontos de chamada que liam uma cor hardcoded diretamente neste arquivo foi
revisitado.

### 1. Barra superior (`TopAppBar`)

```kotlin
// antes
colors = TopAppBarDefaults.topAppBarColors(
    containerColor = WfDeepGreen,
    titleContentColor = Color.White,
    actionIconContentColor = Color.White,
),

// depois
colors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primary,
    titleContentColor = MaterialTheme.colorScheme.onPrimary,
    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
),
```

### 2. FAB de nova conversa

```kotlin
// antes
FloatingActionButton(
    onClick = onPairing,
    containerColor = WfAccent,
    contentColor = Color(0xFF073D29),
) { ... }

// depois
FloatingActionButton(
    onClick = onPairing,
    containerColor = MaterialTheme.colorScheme.secondary,
    contentColor = MaterialTheme.colorScheme.onSecondary,
) { ... }
```

### 3. Selo de entrega na linha de conversa (`ChatRow`)

```kotlin
// antes
color = if (chat.lastStatus.equals("read", ignoreCase = true)) WfRead else MaterialTheme.colorScheme.onSurfaceVariant,

// depois
color = if (chat.lastStatus.equals("read", ignoreCase = true)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
```

### 4. Horário em destaque quando há mensagens não lidas

```kotlin
// antes
Text(chat.time, style = MaterialTheme.typography.labelSmall, color = if (badge.isNotEmpty()) WfGreen else MaterialTheme.colorScheme.onSurfaceVariant)

// depois
Text(chat.time, style = MaterialTheme.typography.labelSmall, color = if (badge.isNotEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
```

### 5. Selo (`Badge`) de contagem de não lidas

```kotlin
// antes
Badge(containerColor = WfAccent, contentColor = Color(0xFF073D29)) {
    Text(badge)
}

// depois
Badge(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary) {
    Text(badge)
}
```

O import `androidx.compose.ui.graphics.Color` ficou sem uso direto restante neste arquivo depois da
migração dos cinco pontos de chamada acima, e foi removido junto.

### Vantagens

- A barra superior, o FAB, o selo de "lido" e o selo de não lidas passam a reagir a tema
  claro/escuro automaticamente — antes eram sempre o mesmo verde/branco hardcoded, mesmo em tema
  escuro do sistema.
- `WfRead` (o azul de "lido" do WhatsApp, `#34B7F1`) vira `MaterialTheme.colorScheme.tertiary`, que
  agora tem um valor explícito próprio em `NoMessagesTheme.kt` (ver
  `docs/changes/NoMessagesTheme.kt.md`) em vez de uma constante solta sem relação com o tema.

### Por que a mudança foi feita

T4.14 (ver `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`): nova paleta "Grafite e
Âmbar" em vez da identidade verde-WhatsApp.
