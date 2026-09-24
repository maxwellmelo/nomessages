# app/src/main/kotlin/dev/mx3/nomessages/ui/NoMessagesTheme.kt

## 2026-09-17 — Nova paleta "Grafite e Âmbar": as seis cores hardcoded somem, `ColorScheme` vira a única fonte de cor (T4.14)

### Contexto

O tema do app era, na prática, um clone de cores do WhatsApp: seis constantes de topo de arquivo
(cores hardcoded para o tom profundo, verde, destaque, balão, superfície e lido) reproduziam quase
exatamente a paleta oficial (`#075E54`/`#128C7E`/`#25D366`/`#DCF8C6`/`#EDEDED`), e todo o resto do
app (`HomeScreen.kt`, `ChatScreen.kt`, `Components.kt`, `GroupWizardScreen.kt`, `PairingScreen.kt`,
`SettingsScreen.kt`) lia essas constantes diretamente em vez de passar pelos papéis de
`MaterialTheme.colorScheme`. `LightColors`/`DarkColors` só preenchiam uma fração dos papéis do
Material 3 (sem `tertiary`, sem `outline`/`outlineVariant`, sem `onSurface`/`onSurfaceVariant`
explícitos), deixando o restante no default do Compose.

Pedido do usuário: substituir a identidade verde-WhatsApp por uma paleta própria, "Grafite e Âmbar",
usando os papéis do Material 3 de ponta a ponta — nenhuma cor hardcoded fora deste arquivo.

### Como era antes

```kotlin
internal val WfDeepGreen = Color(0xFF075E54)
internal val WfGreen = Color(0xFF128C7E)
internal val WfAccent = Color(0xFF25D366)
internal val WfBubble = Color(0xFFDCF8C6)
internal val WfSurface = Color(0xFFEDEDED)
internal val WfRead = Color(0xFF34B7F1)

private val LightColors = lightColorScheme(
    primary = WfGreen,
    onPrimary = Color.White,
    primaryContainer = WfBubble,
    onPrimaryContainer = Color(0xFF123B35),
    secondary = WfAccent,
    onSecondary = Color(0xFF063C29),
    surface = Color.White,
    surfaceVariant = WfSurface,
    background = Color(0xFFF7F5F2),
    onBackground = Color(0xFF1D2422),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD4C0),
    onPrimary = Color(0xFF00382F),
    primaryContainer = WfDeepGreen,
    onPrimaryContainer = Color.White,
    secondary = WfAccent,
    onSecondary = Color(0xFF003919),
    surface = Color(0xFF111B21),
    surfaceVariant = Color(0xFF202C33),
    background = Color(0xFF0B141A),
    onBackground = Color(0xFFE9EDEF),
    error = Color(0xFFB3261E),
)
```

Note que essas constantes cobriam só a metade dos usos reais: a de "lido" (o azul, `#34B7F1`) e as demais
nunca entravam em `LightColors`/`DarkColors` — eram lidas soltas, direto pelas telas, sem
nenhum vínculo com o tema claro/escuro do Material.

### Como ficou

As seis constantes de cor hardcoded foram apagadas por inteiro. `LightColors`/`DarkColors` passam a atribuir um
valor explícito a todo papel usado em algum lugar do app — inclusive `tertiary`/`onTertiary` (novo,
para o selo de "lido"), `outline`/`outlineVariant` (não consumidos hoje por nenhuma tela, mas
definidos para consistência do `ColorScheme` e para uso futuro) e `onSurface`/`onSurfaceVariant`
explícitos (antes deixados no default do Compose):

```kotlin
private val LightColors = lightColorScheme(
    background = Color(0xFFFAFBFB),
    onBackground = Color(0xFF1B1F22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1F22),
    surfaceVariant = Color(0xFFF4F6F7),
    onSurfaceVariant = Color(0xFF3A4147),
    outline = Color(0xFFC7CDD1),
    outlineVariant = Color(0xFFDDE2E5),
    primary = Color(0xFF23282C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DFB8),
    onPrimaryContainer = Color(0xFF1B1F22),
    secondary = Color(0xFFD98E2B),
    onSecondary = Color(0xFF1B1F22),
    tertiary = Color(0xFF2E7BB6),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF14171A),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF1B1F22),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF2E353A),
    onSurfaceVariant = Color(0xFFB7C0C6),
    outline = Color(0xFF4A5157),
    outlineVariant = Color(0xFF3A4147),
    primary = Color(0xFF3A4147),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A3419),
    onPrimaryContainer = Color(0xFFF3EDE3),
    secondary = Color(0xFFF2A83D),
    onSecondary = Color(0xFF14171A),
    tertiary = Color(0xFF6FB8E8),
    onTertiary = Color(0xFF05283C),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)
```

### Tabela de tokens (papel → claro → escuro → uso na UI)

| Papel `ColorScheme` | Claro | Escuro | Onde aparece |
|---|---|---|---|
| `background` / `onBackground` | `#FAFBFB` / `#1B1F22` | `#14171A` / `#ECEFF1` | fundo raiz das telas |
| `surface` / `onSurface` | `#FFFFFF` / `#1B1F22` | `#1B1F22` / `#ECEFF1` | cartões, bolha recebida, texto padrão |
| `surfaceVariant` / `onSurfaceVariant` | `#F4F6F7` / `#3A4147` | `#2E353A` / `#B7C0C6` | bolha recebida (fundo), horário/legendas |
| `outline` / `outlineVariant` | `#C7CDD1` / `#DDE2E5` | `#4A5157` / `#3A4147` | bordas (reservado; não consumido hoje) |
| `primary` / `onPrimary` | `#23282C` / `#FFFFFF` | `#3A4147` / `#FFFFFF` | barra superior (chrome), avatar de grupo, `BrandMark` |
| `primaryContainer` / `onPrimaryContainer` | `#F6DFB8` / `#1B1F22` | `#4A3419` / `#F3EDE3` | bolha de mensagem enviada |
| `secondary` / `onSecondary` | `#D98E2B` / `#1B1F22` | `#F2A83D` / `#14171A` | destaque (FAB, selo de não lidas, ícones de ação, avatar 1:1) |
| `tertiary` / `onTertiary` | `#2E7BB6` / `#FFFFFF` | `#6FB8E8` / `#05283C` | selo de mensagem "lida" |
| `error` / `onError` | `#B3261E` / `#FFFFFF` | `#F2B8B5` / `#601410` | estados de erro |

Todo par texto/fundo da tabela acima que carrega texto foi verificado programaticamente (fórmula de
luminância relativa sRGB) contra o mínimo AA de contraste (≥ 4,5:1), nos dois temas.

### Distinção enviada/recebida sem depender só de matiz (daltonismo)

A bolha enviada (`primaryContainer`, âmbar claro) e a recebida (`surface`/`surfaceVariant`, neutro)
já se distinguem por **posição** (`Arrangement.End` vs. `Arrangement.Start`, ver
`docs/changes/ChatScreen.kt.md`) além do tom. Isso evita que a leitura da conversa dependa
exclusivamente de diferenciar matizes — relevante porque o par verde/cinza antigo, e o par
âmbar/neutro novo, ficam ambos dentro da faixa que uma pessoa com deuteranopia/protanopia percebe
com menos contraste do que uma pessoa com visão de cor típica.

### Comparativo de paletas

Um agente paralelo construiu `docs/design/palette-preview.html`, comparando esta paleta ("Grafite e
Âmbar") lado a lado com duas candidatas rejeitadas. Este documento não descreve esse comparativo em
detalhe — ver a página publicada.

### Vantagens

- Nenhuma cor de UI hardcoded fora deste arquivo: toda tela lê `MaterialTheme.colorScheme.*`, então
  trocar de tema (ou ajustar um tom) volta a exigir editar um único lugar.
- `ColorScheme` agora preenche todo papel relevante (`tertiary`, `outline`/`outlineVariant`,
  `onSurface`/`onSurfaceVariant`) em vez de deixar parte no default do Compose — o tema passa a ser
  autoexplicativo por si só, sem depender de constantes externas para completar o quadro.
- O app deixa de reproduzir a paleta reconhecível do WhatsApp (`#075E54`/`#128C7E`/`#25D366`), a
  parte mais visível do risco de trade dress citado no `SPEC.md` seção 1.

### Por que a mudança foi feita

T4.14 (ver `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`): identidade visual própria em
vez de clone de marca. Uma varredura por constantes de cor hardcoded em
`app/src/main/kotlin/dev/mx3/nomessages/ui/` e `app/src/main/res/` confirmou zero ocorrências depois da mudança.
