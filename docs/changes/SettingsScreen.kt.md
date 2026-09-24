# SettingsScreen.kt — mudanças

## 2026-09-14 — Texto de recurso resolvido em composição (`LocalContextGetResourceValueCall`, T1.4)

### Como era antes

Dentro do `trailingIcon` do campo do endereço `.onion`, o texto do Toast era
resolvido pelo `Context` de dentro da lambda de clique:

```kotlin
IconButton(onClick = {
    copySensitive(context, state.onion)
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}) { ... }
```

O `:app:lintDebug` reprovava com erro:

> `LocalContextGetResourceValueCall`: Querying resource values using
> `LocalContext.current` — `SettingsScreen.kt:137`

### Como ficou

O texto passa a ser resolvido no corpo do composable, junto com o `context`:

```kotlin
val context = LocalContext.current
// Resolved in composition so a configuration change invalidates it; reading it
// from LocalContext inside the click lambda would serve a stale locale.
val copiedNotice = stringResource(R.string.copied)
```

e a lambda apenas consome a variável:

```kotlin
Toast.makeText(context, copiedNotice, Toast.LENGTH_SHORT).show()
```

### Por que a mudança

`LocalContext.current` **não** é invalidado quando o `Configuration` muda. O
`Context` capturado pela lambda continua sendo o de quando a lambda foi criada,
então `context.getString(...)` pode devolver o texto do **idioma anterior**
depois de uma troca de idioma, de layout direction (RTL) ou de qualquer mudança
de configuração que o Compose absorva sem recriar a Activity.

`stringResource` lê de `LocalConfiguration`/`LocalResources`, que são
`CompositionLocal`s observáveis: uma mudança de configuração recompõe o
`SettingsScreen` e `copiedNotice` é recalculado. É o contrato correto do Compose
para recursos.

Concretamente, o app tem PT-BR como padrão e um conjunto `values-en` completo
com paridade (ver `docs/development/ui-report.md`). Justamente por ser bilíngue,
o risco de servir a string errada não é teórico.

### Nota de acessibilidade/UX

A mudança é neutra visualmente e mantém o `contentDescription` do ícone
(`R.string.copy`) intacto. O Toast continua sendo a única confirmação da cópia
do endereço `.onion` — copiado por `copySensitive`, que marca o clip como
sensível.

### Vantagens

- Elimina um erro de lint que bloqueava T1.4.
- Corrige um bug real de localização: o aviso "Copiado" agora sempre acompanha o
  idioma corrente.
- A string é resolvida uma vez por composição em vez de a cada clique.
- Alinha o arquivo ao padrão já usado em todo o resto dele, que só usa
  `stringResource`.

### Regressão

`:app:testDebugUnitTest` — 22 testes, 0 falhas, 0 erros, 0 pulados.
`:app:lintDebug` — 0 erros.

---

## 2026-09-16 — Campo de pontes Tor com teclado privado (T4.9)

### Motivo

O campo de pontes Tor (`bridges`) é o único campo de texto editável em `SettingsScreen.kt` (o campo
de endereço onion é `readOnly = true` e nunca abre o IME, então foi deixado como está). T4.9 pede a
mesma proteção de `PrivateInput.kt` em todo campo editável do app.

### Como era antes

```kotlin
OutlinedTextField(
    value = bridges,
    onValueChange = { bridges = it },
    label = { Text(stringResource(R.string.bridges_hint)) },
    minLines = 3,
    maxLines = 8,
    modifier = Modifier.fillMaxWidth().padding(16.dp),
)
```

### Como ficou

```kotlin
PrivateImeScope {
    OutlinedTextField(
        value = bridges,
        onValueChange = { bridges = it },
        label = { Text(stringResource(R.string.bridges_hint)) },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        keyboardOptions = privateKeyboardOptions(),
    )
}
```

### Vantagens

- O campo passa a pedir ao IME que não aprenda/sugira a partir de linhas de ponte Tor — dado de
  configuração de rede, não conteúdo de conversa, mas ainda assim algo que não deveria alimentar o
  dicionário pessoal do teclado.
- O campo de endereço onion (só leitura) foi conscientemente deixado fora do escopo desta mudança —
  não abre IME, então não há nada para proteger ali.

### Por que a mudança foi feita

T4.9, item 1.

---

## 2026-09-17 — `PanicPasswordDialog`: medidor de força e confirmação em tempo real (T4.10)

Documentos irmãos: `docs/changes/Components.kt.md` (os composables consumidos aqui),
`docs/changes/SetupLockScreens.kt.md` (o mesmo padrão aplicado na criação do cofre),
`docs/changes/strings.xml.md`.

### Contexto

Mesmo pedido de T4.10 que motivou a mudança em `SetupScreen` (ver `docs/changes/SetupLockScreens.kt.md`),
aplicado aqui ao único outro lugar do app onde uma senha é criada: o diálogo de redefinição da senha
de pânico em Configurações.

### Diferença deliberada em relação a `SetupScreen`: sem verificação de similaridade real/pânico

Este diálogo recebe apenas a **nova** senha de pânico e sua confirmação — não a senha real. Isso é
por design: `VaultManager.resetPanicPassword` compara a nova senha de pânico contra o material de
chave da senha real **no servidor** (dentro do vault já aberto), nunca em texto puro na camada de UI
(ver `docs/development/vault-api.md`). Sem a senha real em texto puro aqui, não existe como chamar
`PasswordPolicy().validatePair(...)` do lado do cliente. Esse caso de rejeição continua existindo e
continua sendo reportado hoje via `R.string.error_panic_password_change` (genérico, server-side) —
inalterado por esta mudança, e fora do escopo dela (ver a seção "Por que `NoMessagesController.kt`
está fora do escopo" no pedido original desta tarefa).

### Como era antes

```kotlin
@Composable
private fun PanicPasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray, CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    ...
    AlertDialog(
        ...
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.panic_reset_warning))
                PasswordField(password, { password = it }, R.string.new_panic_password, true)
                PasswordField(confirm, { confirm = it }, R.string.confirm_new_panic_password, true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && confirm.isNotEmpty(),
                onClick = { ... },
            ) { Text(stringResource(R.string.save)) }
        },
        ...
    )
}
```

### Como ficou

```kotlin
@Composable
private fun rememberPasswordStrength(password: String): PasswordStrength = remember(password) {
    val chars = password.toCharArray()
    try { estimateStrength(chars) } finally { chars.fill('\u0000') }
}

@Composable
private fun PanicPasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray, CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    ...
    val strength = rememberPasswordStrength(password)
    AlertDialog(
        ...
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.panic_reset_warning))
                PasswordField(password, { password = it }, R.string.new_panic_password, true)
                if (password.isNotEmpty()) {
                    PasswordStrengthMeter(strength, Modifier.padding(horizontal = 4.dp))
                    PasswordStrengthTips(strength, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
                PasswordField(confirm, { confirm = it }, R.string.confirm_new_panic_password, true)
                PasswordMatchHint(
                    matches = if (confirm.isEmpty()) null else confirm == password,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && confirm.isNotEmpty() &&
                    strength.score >= 3 && confirm == password,
                onClick = { ... },
            ) { Text(stringResource(R.string.save)) }
        },
        ...
    )
}
```

Mesmo hardcode de `>= 3` (e mesmo motivo — `PasswordPolicy.MINIMUM_ACCEPTABLE_SCORE` é `internal` ao
`:core`) documentado em `docs/changes/SetupLockScreens.kt.md`.

### Vantagens

- Mesma pré-visualização de força e confirmação que `SetupScreen` ganhou, no único outro fluxo de
  criação de senha do app, com o mesmo par de composables — nenhuma lógica visual duplicada.
- O botão "Salvar" passa a ficar desabilitado para uma senha de pânico fraca ou uma confirmação
  divergente, reduzindo a chance de a rejeição genérica `error_panic_password_change` ser a primeira
  notícia que o usuário tem do problema.
- `PasswordField`/`PrivateImeScope` (T4.9) permanecem intactos.

### Testes e verificação

Mesma ressalva de `docs/changes/SetupLockScreens.kt.md`: não foi possível rodar
`:app:testDebugUnitTest` nesta máquina; revisão feita por leitura (imports, tipos, chaves de string).

### Por que a mudança foi feita

T4.10.

---

## 2026-09-17 — Nova paleta "Grafite e Âmbar": título de seção troca `WfGreen` por `MaterialTheme.colorScheme.secondary` (T4.14)

### Como era antes

```kotlin
private fun SettingsSectionTitle(resource: Int) {
    Text(
        text = stringResource(resource),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        style = MaterialTheme.typography.titleMedium,
        color = WfGreen,
        fontWeight = FontWeight.Bold,
    )
}
```

### Como ficou

```kotlin
private fun SettingsSectionTitle(resource: Int) {
    Text(
        text = stringResource(resource),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.Bold,
    )
}
```

Único ponto de chamada de cor hardcoded neste arquivo.

### Vantagens

- Todo título de seção de Configurações (Segurança, Rede, Sobre etc.) passa a herdar tema
  claro/escuro automaticamente.

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" em vez da identidade verde-WhatsApp.

## 2026-09-18 — T4.17 fase 6: chave de "Aviso de mensagens pendentes" na tela de Configurações

### Como era antes

A campainha existia inteira por baixo — seed própria, onion mínimo no bloqueio, tokens por contato,
preferência gravada em `opaque_blobs`/`vault_settings`/`doorbell_enabled` — mas **não havia nenhum
controle na interface**. O único valor possível era o padrão (ligado), e o usuário não tinha como
saber que a funcionalidade existia nem como desligá-la. A seção "Segurança" ia direto do bloqueio
automático (chips de 5/15/30 s) e do aviso de teclado para o botão de senha de pânico.

Além disso, a tela inteira não tinha um único `Switch` nem `Checkbox`: todo controle era botão,
`FilterChip` ou campo de texto.

### Como ficou

Um composable privado novo, inserido na seção "Segurança" logo depois do aviso de teclado e antes do
botão de senha de pânico:

```kotlin
Text(stringResource(R.string.keyboard_warning), ...)
DoorbellSetting(enabled = state.doorbellEnabled, onChange = actions::setDoorbellEnabled)
if (state.canChangePanicPassword) { ... }
```

```kotlin
@Composable
private fun DoorbellSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.doorbell_setting_title), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Switch(checked = enabled, onCheckedChange = onChange)
    }
    Text(stringResource(if (enabled) R.string.doorbell_setting_on else R.string.doorbell_setting_off), ...)
    Text(stringResource(R.string.doorbell_setting_context), ...)
}
```

Imports acrescentados: `androidx.compose.material3.Switch` e `androidx.compose.ui.Alignment`. Nenhuma
dependência nova.

### Decisões de design

- **`Switch`, não `Checkbox`.** Os dois `Checkbox` que já existiam no app (`GroupWizardScreen` para
  escolher membros, `ChatScreen` para escolher destinos de encaminhamento) marcam *itens de uma
  lista* antes de um botão de confirmação. Aqui é uma preferência binária única que vale no instante
  em que é tocada — o papel do `Switch` no Material 3. Nenhuma cor foi passada: o controle herda o
  `colorScheme` e, portanto, a paleta Grafite e Âmbar, como todo o resto da tela.
- **Posição: dentro de "Segurança", colado ao bloqueio automático.** A opção descreve o que o app faz
  *enquanto bloqueado*; o vizinho natural é o controle que decide *quando* o bloqueio acontece. Criar
  uma seção só para ela sugeriria que é um assunto à parte do bloqueio, que é exatamente o que ela
  não é.
- **Só um dos dois textos por vez.** O texto acompanha `enabled`: a configuração só se explica
  sozinha se disser o que a escolha *atual* faz. Mostrar os dois juntos obrigaria o leitor a decidir
  qual dos parágrafos é o dele.
- **A linha de contexto fica sempre visível.** É o que não muda nos dois modos; deixá-la fixa evita
  que alguém precise acionar o interruptor para descobrir o que ele não altera.
- **Título em `SemiBold` no mesmo `padding(start = 16.dp, top = 20.dp)` de `lock_timeout`**, e textos
  em `bodySmall`/`onSurfaceVariant` como `bridges_detail` e `keyboard_warning`: nenhum estilo,
  espaçamento ou cor novo foi inventado.

### Texto: reprodução literal, e o que foi deliberadamente omitido

Os três textos vêm **palavra por palavra** de `docs/development/doorbell-design.md` (seção "Texto da
configuração"), sem paráfrase e sem pontuação acrescentada. A diretriz de redação do desenho foi
seguida à risca: os dois modos aparecem como fatos, nenhum é chamado de "menos seguro" ou de
"bloqueio total", e **não foi acrescentado nada** — nenhum "recomendado", nenhum "atenção", nenhum
ícone de alerta, nenhuma cor de erro. Um aviso ali diria ao usuário que uma das duas escolhas
legítimas é um erro.

### Paridade cofre real / cofre-isca

Nada neste arquivo consulta `VaultSlot`. A tela lê `state.doorbellEnabled` e chama
`actions.setDoorbellEnabled`, ambos indiferentes ao cofre aberto; o único campo de `UiState` que
depende do slot continua sendo `canChangePanicPassword`, que já existia. Havia a tentação de esconder
ou fixar o interruptor na isca — **deliberadamente não feito**: um interruptor ausente, travado ou com
outro valor é justamente a diferença observável que a isca existe para não ter.

### Vantagens

- A funcionalidade deixa de ser invisível: quem abre Configurações descobre que o aviso existe e o
  que ele faz, em uma tela que já é o lugar das preferências por cofre.
- Quem prefere não deixar nada ligado à rede com o app bloqueado tem um toque para isso, com o texto
  correspondente confirmando a escolha na hora.
- O padrão continua ligado; a tela agora *mostra* esse padrão em vez de deixá-lo implícito.

### Validação

`:app:compileDebugKotlin` BUILD SUCCESSFUL; `:app:lintDebug` BUILD SUCCESSFUL, **nenhum aviso novo**
(as 51 ocorrências do relatório são todas anteriores; nenhuma das quatro strings novas aparece em
`UnusedResources`, o que confirma que todas estão referenciadas); `:app:testDebugUnitTest` **83
testes, 0 falhas, 0 erros, 1 pulado** — igual à contagem anterior.

### Lacuna conhecida

Não existe nenhum teste Compose nesta tela, nem em qualquer outra do app: `compose.ui.test.junit4` está
declarado em `app/build.gradle.kts` mas `createComposeRule` não aparece em nenhum arquivo do projeto, e
o conjunto `androidTest` só tem testes de mídia e de storage. Criar a primeira suíte de UI do app só
para este interruptor ficaria fora do escopo desta fase — e não poderia ser executada aqui, já que
teste instrumentado exige aparelho/emulador. Fica registrado: a troca de texto conforme o estado e a
presença do interruptor foram verificadas por leitura e compilação, não por teste automatizado.

## 2026-09-23 — `timeoutLabel` usa `pluralStringResource` para segundos/minutos (T4.6)

### Motivo

`:app:lintDebug` apontava `timeout_seconds`/`timeout_minutes` como `PluralsCandidate` ("Formatting
%d followed by words... should probably be a plural"). Ver `docs/changes/strings.xml.md` para a
conversão dos recursos em `<plurals>`.

### Como era

```kotlin
private fun timeoutLabel(seconds: Int): String = when (seconds) {
    60 -> stringResource(R.string.timeout_minute)
    in 120..Int.MAX_VALUE -> stringResource(R.string.timeout_minutes, seconds / 60)
    else -> stringResource(R.string.timeout_seconds, seconds)
}
```

### Como ficou

```kotlin
private fun timeoutLabel(seconds: Int): String = when (seconds) {
    60 -> stringResource(R.string.timeout_minute)
    in 120..Int.MAX_VALUE -> pluralStringResource(R.plurals.timeout_minutes, seconds / 60, seconds / 60)
    else -> pluralStringResource(R.plurals.timeout_seconds, seconds, seconds)
}
```

`timeout_minute` (o caso especial "1 minuto", nunca atingido pelos três valores em uso — 5/15/30 s —
mas mantido para o resto da faixa) continua uma `<string>` comum, fora do escopo deste achado de
lint. Os três chips reais (`listOf(5, 15, 30)` em `SettingsScreen`) sempre caem no ramo `else`
(`timeout_seconds`, categoria `other` do plural), então o comportamento visível hoje não muda — a
correção cobre a faixa de minutos para quando/se ela vier a ser usada.

### Vantagens

- Fecha `PluralsCandidate` para as duas strings sem mudar nenhum texto mostrado hoje (todos os
  valores em uso caem em "other" nas duas línguas).
- Prepara corretamente a categoria "one" (`%1$d segundo`/`%1$d minuto`) para o dia em que a faixa de
  opções crescer, em vez de deixar a lacuna para outra tarefa notar de novo.

### Verificação

`:app:testDebugUnitTest`/`:app:lintDebug`: `BUILD SUCCESSFUL`, zero `PluralsCandidate` remanescente.
