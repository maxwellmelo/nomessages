# app/src/main/kotlin/dev/mx3/nomessages/ui/Components.kt

## 2026-09-14 — T2.1: faixa de rede para PUBLISHING

### Como era antes

```kotlin
NetworkStatus.STARTING -> R.string.network_starting to false
NetworkStatus.ONLINE -> R.string.network_online to true
```

### Como ficou

```kotlin
NetworkStatus.STARTING -> R.string.network_starting to false
NetworkStatus.PUBLISHING -> R.string.network_publishing to false
NetworkStatus.ONLINE -> R.string.network_online to true
```

O segundo elemento do par (`online`) permanece `false`, então a faixa continua usando o esquema de cor de aviso (`errorContainer`) e não o de conectado.

### Vantagens

- O usuário recebe um aviso específico ("Publicando o endereço onion — ainda não é possível receber") em vez do genérico de conexão, o que explica por que ainda não chegam mensagens.
- Manter `online = false` impede que a faixa desapareça visualmente como se tudo estivesse pronto.

### Por que a mudança foi feita

T2.1: o novo estado do transporte tem de ser visível na interface, com paridade PT/EN.

---

## 2026-09-16 — `PasswordField` ganha `PrivateImeScope` + `privateKeyboardOptions` (T4.9)

### Motivo

`PasswordField` é o componente compartilhado usado por toda senha do app (setup, desbloqueio,
diálogo de importação, redefinição de senha de pânico) — o candidato mais sensível a receber a
proteção de teclado privado de `PrivateInput.kt` (ver `docs/changes/PrivateInput.kt.md`), e o ponto
com maior alavancagem: uma única mudança aqui cobre todo lugar que já chama `PasswordField`.

### Como era antes

```kotlin
OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    singleLine = true,
    label = { Text(text = androidx.compose.ui.res.stringResource(label)) },
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
)
```

### Como ficou

```kotlin
PrivateImeScope {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(text = androidx.compose.ui.res.stringResource(label)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = privateKeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
```

O import `androidx.compose.foundation.text.KeyboardOptions`, agora sem nenhum uso direto no
arquivo, foi removido.

### Vantagens

- Toda senha do app (`PasswordField` é reaproveitado por `SetupScreen`, `LockScreen`,
  `PasswordActionDialog` e `PanicPasswordDialog`) ganha as duas flags de privacidade de IME numa
  única mudança, sem tocar cada tela individualmente.
- `KeyboardType.Password` e `PasswordVisualTransformation()` já estavam corretos aqui — nenhuma
  correção adicional foi necessária, só o envolvimento em `PrivateImeScope`.

### Por que a mudança foi feita

T4.9, item 1 (aplicar `privateKeyboardOptions`/`PrivateImeScope` a todo campo de texto do app).

---

## 2026-09-17 — Três composables novos: medidor de força, dicas e confirmação em tempo real (T4.10)

Documentos irmãos: `docs/changes/PasswordPolicy.kt.md` e `docs/changes/PasswordStrength.kt.md` (o
`:core` que esta UI passa a consumir), `docs/changes/SetupLockScreens.kt.md` e
`docs/changes/SettingsScreen.kt.md` (quem usa estes composables), `docs/changes/strings.xml.md`
(as strings novas referenciadas aqui).

### Contexto

O usuário reprovou a política antiga de senha (só alfanumérico, mínimo 16) e pediu, além de liberar
símbolos no `:core` (já feito por outro agente, ver os dois documentos irmãos citados acima), um
medidor de força visível e validação de confirmação em tempo real na tela de criação de cofre e no
diálogo de redefinição de senha de pânico.

### Como era antes

Não existia nenhum composable de força de senha. `PasswordField` era o único componente de senha
compartilhado (ver a seção acima), e cada tela media "força" apenas no sentido de que o backend
(`VaultManager.create`/`resetPanicPassword`) podia rejeitar a senha em `try/catch` genérico, sem
qualquer pré-visualização — o usuário só descobria que a senha era fraca ao tentar enviar.

### Como ficou

Três composables novos, todos `internal`, logo depois de `PasswordField`:

```kotlin
@Composable
internal fun PasswordStrengthMeter(strength: PasswordStrength, modifier: Modifier = Modifier)

@Composable
internal fun PasswordStrengthTips(strength: PasswordStrength, modifier: Modifier = Modifier)

@Composable
internal fun PasswordMatchHint(matches: Boolean?, modifier: Modifier = Modifier)
```

`PasswordStrengthMeter` é uma barra de 4 segmentos finos (`RoundedCornerShape(2.dp)`, 4.dp de
altura, no mesmo estilo visual dos demais elementos deste arquivo) mais um rótulo de texto abaixo.
Segmentos preenchidos = `strength.score` (0..4), sempre limitado a 4 (`coerceIn(0, 4)`) — um score 4
não desenha um quinto segmento, só preenche os quatro. Cor e rótulo seguem o mesmo agrupamento
{0,1}→fraca (vermelho, `colorScheme.error`), {2}→razoável (âmbar, `Color(0xFFB8860B)` — o
`ColorScheme` do Material3 não tem papel "aviso/razoável" para reaproveitar), {3}→boa
(`colorScheme.primary`), {4}→forte (`WfGreen`, a mesma cor de marca já usada em `SettingsSectionTitle`
e em `PasswordMatchHint` para "coincide").

`PasswordStrengthTips` mapeia até duas chaves de `strength.feedback` (`PasswordFeedback.*`, definidas
no `:core`) para strings curtas via um `when` com `else -> null`, para que uma chave futura ainda não
conhecida por este arquivo seja ignorada em vez de derrubar o app. `LOOKS_STRONG` deliberadamente não
aparece aqui — a barra verde e o rótulo "Forte"/"Strong" já comunicam isso.

`PasswordMatchHint` recebe um `Boolean?`: `null` (campo de confirmação ainda vazio) não desenha nada;
`false`/`true` desenham uma linha de texto colorida (`error`/`WfGreen`), seguindo a convenção já
usada neste arquivo de `Text` simples em vez de um novo ícone — não havia nenhum ícone de "check" já
importado no projeto (`dev.mx3.nomessages.ui.icons.filled.*` foi conferido), e a tarefa pedia
explicitamente para não adicionar uma dependência de ícone só para isso.

### Vantagens

- Um único lugar define a barra/dicas/confirmação — `SetupScreen` (dois campos de senha) e
  `PanicPasswordDialog` (um campo) reaproveitam os três composables sem duplicar lógica visual.
- `feedbackHint`'s `else -> null` torna o mapeamento futuro-compatível: se `:core` ganhar uma nova
  chave em `PasswordFeedback`, o pior caso é a dica não aparecer, nunca um crash.
- Nenhuma dependência nova (ícone ou biblioteca): tudo reaproveita `MaterialTheme.colorScheme` e
  `Text`/`Box`/`Row` já usados no resto do arquivo.
- `estimateStrength` é pura e local (ver `docs/changes/PasswordStrength.kt.md`), então recomputar a
  cada tecla não tem custo perceptível — nenhum debounce foi necessário.

### Por que a mudança foi feita

T4.10, item "medidor de força" e "confirmação em tempo real" (ver
`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`).

### Correção pós-build (2026-09-17, mesmo dia): import indevido de `weight`

A validação `:app:compileDebugKotlin` (via `gradle-wsl.sh`) falhou com:

```
e: .../Components.kt:17:43 Cannot access 'val RowColumnParentData?.weight: Float': it is internal in file.
```

O arquivo tinha ganhado `import androidx.compose.foundation.layout.weight`, adicionado junto com o
`PasswordStrengthMeter`. `weight` **não** é uma função de topo do pacote — é uma função-membro das
interfaces `RowScope`/`ColumnScope` do Compose Foundation, disponível automaticamente dentro do
`content` de um `Row`/`Column` (como o `Row { ... Box(Modifier.weight(1f)) ... }` do medidor de
força), sem necessidade de import. O import explícito resolveu, em vez disso, para um detalhe de
implementação interno do mesmo pacote (`RowColumnParentData?.weight`), que não é acessível fora do
módulo `compose-foundation` — daí o erro. Nenhum outro arquivo do app importa `weight` (conferido em
`AttachmentViewer.kt` e `ChatScreen.kt`, que já usam `.weight(1f)` sem esse import); esta é a
convenção correta do projeto.

**Correção:** removida a linha `import androidx.compose.foundation.layout.weight`. Nenhuma outra
mudança — `Modifier.weight(1f)` dentro do `Row` do `PasswordStrengthMeter` volta a resolver pela
função-membro de `RowScope`, exatamente como em `AttachmentViewer.kt`/`ChatScreen.kt`.

---

## 2026-09-17 — Nova paleta "Grafite e Âmbar": cinco pontos de chamada trocam cor hardcoded por `MaterialTheme.colorScheme` (T4.14)

### 1. `BrandMark()` — fundo e monograma

```kotlin
// antes
modifier = modifier
    .size(if (compact) 42.dp else 76.dp)
    .clip(CircleShape)
    .background(LegacyGreen),
...
Text(
    text = "XX",
    color = Color.White,
    ...
)

// depois
modifier = modifier
    .size(if (compact) 42.dp else 76.dp)
    .clip(CircleShape)
    .background(MaterialTheme.colorScheme.primary),
...
Text(
    text = "NM",
    color = Color.White,
    ...
)
```

O monograma também mudou de texto — o texto fixo antigo de duas letras vira
`"NM"` (NoMessages). Decisão deliberada de rebrand tomada junto com a mudança de cor, não escopo
adicional por conta própria: o brand mark é o mesmo texto exibido por `R.string.brand_mark`, que já
tinha sido atualizado para `"NM"` (ver `docs/changes/strings.xml.md`) — deixar o texto
fixo deste `Text()` divergente do recurso localizado equivalente seria uma inconsistência visível
nas telas de setup/estado vazio, que reaproveitam `BrandMark()`.

### 2. `strengthColor()` — ramo "forte"

```kotlin
// antes
else -> WfGreen

// depois
else -> MaterialTheme.colorScheme.secondary
```

### 3. `PasswordMatchHint()` — cor de "senhas coincidem"

```kotlin
// antes
color = if (matches) WfGreen else MaterialTheme.colorScheme.error,

// depois
color = if (matches) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
```

### 4. `Avatar()` — fundo de grupo vs. 1:1

```kotlin
// antes
.background(if (group) WfDeepGreen else WfGreen.copy(alpha = 0.88f)),

// depois
.background(if (group) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f)),
```

Efeito colateral positivo: avatares de grupo agora ficam com tom grafite (`primary`) e avatares
individuais com tom âmbar (`secondary`), reforçando visualmente a distinção grupo/1:1 na lista de
contatos, sem que isso tenha sido pedido explicitamente — consequência natural do mapeamento
`WfDeepGreen → primary` / `WfGreen → secondary` já usado nos demais arquivos.

### 5. `NetworkBanner()` — tinta de fundo quando online

```kotlin
// antes
color = if (online) WfAccent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.errorContainer,

// depois
color = if (online) MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.errorContainer,
```

### Vantagens

- Os cinco composables compartilhados (`BrandMark`, o medidor/dica de força de senha, o avatar e a
  faixa de rede) passam a herdar tema claro/escuro automaticamente, em vez de um verde fixo — como
  são reaproveitados por várias telas, cada um cobre múltiplos pontos de UI de uma vez.
- O monograma `"NM"` fecha o rebrand de marca: nenhum texto fixo no app continua com o monograma antigo.

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" em vez da identidade verde-WhatsApp.
