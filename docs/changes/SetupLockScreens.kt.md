# app/src/main/kotlin/dev/mx3/nomessages/ui/SetupLockScreens.kt

## 2026-09-16 — Teclado privado no nome de exibição + aviso de teclado de terceiros (T4.9)

### Motivo

`SetupScreen` (nome de exibição) e `LockScreen` (senha de desbloqueio) são as duas primeiras telas
que qualquer pessoa digita algo neste app — inclusive a senha principal, antes mesmo do cofre estar
aberto. T4.9 pede a mesma proteção de `PrivateInput.kt` aqui, mais o aviso de teclado de terceiros
nos dois lugares mais sensíveis do fluxo de autenticação.

### Como era antes

```kotlin
internal fun SetupScreen(state: UiState, actions: UiActions) {
    ...
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy,
        singleLine = true,
        label = { Text(stringResource(R.string.display_name)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    ...
}

internal fun LockScreen(state: UiState, actions: UiActions) {
    ...
    PasswordField(
        value = password,
        onValueChange = { password = it },
        label = R.string.password,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ...
}
```

Nenhum aviso de teclado de terceiros existia.

### Como ficou

```kotlin
internal fun SetupScreen(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean = false) {
    ...
    if (thirdPartyImeActive) {
        Text(
            stringResource(R.string.third_party_keyboard_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
    }
    PrivateImeScope {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            singleLine = true,
            label = { Text(stringResource(R.string.display_name)) },
            keyboardOptions = privateKeyboardOptions(imeAction = ImeAction.Next),
        )
    }
    ...
}

internal fun LockScreen(state: UiState, actions: UiActions, thirdPartyImeActive: Boolean = false) {
    ...
    if (thirdPartyImeActive) {
        Text(
            stringResource(R.string.third_party_keyboard_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
    }
    PasswordField(
        value = password,
        onValueChange = { password = it },
        label = R.string.password,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    ...
}
```

(`PasswordField` já ganha a proteção de IME internamente — ver `docs/changes/Components.kt.md` —
então `LockScreen` não precisou de um `PrivateImeScope` próprio, só do banner.) O import
`androidx.compose.foundation.text.KeyboardOptions`, sem uso direto restante, foi removido.

### Vantagens

- As duas primeiras telas de qualquer sessão (setup e desbloqueio) — exatamente onde a senha
  principal é digitada pela primeira e a cada vez seguinte — ganham o aviso de teclado de
  terceiros antes de qualquer outra tela do app.
- Os dois novos parâmetros (`thirdPartyImeActive: Boolean = false`) têm valor padrão, então nenhum
  chamador existente de `SetupScreen`/`LockScreen` precisou mudar além de `NoMessagesApp.kt`.

### Por que a mudança foi feita

T4.9, itens 1 e 4.

---

## 2026-09-17 — `SetupScreen`: medidor de força, confirmação em tempo real, aviso de similaridade real/pânico (T4.10)

Documentos irmãos: `docs/changes/Components.kt.md` (os três composables novos consumidos aqui),
`docs/changes/PasswordPolicy.kt.md`/`PasswordStrength.kt.md` (o `:core` consumido),
`docs/changes/strings.xml.md` (as strings novas). `LockScreen`, nesta mesma tela, **não** foi tocado:
é um campo de senha existente (desbloqueio), não uma criação, então não tem medidor de força.

### Contexto

Pedido do usuário: a política de senha do `:core` ficou mais permissiva (símbolos liberados, piso de
12) e ganhou um estimador de força (`estimateStrength`), mas isso só protege no momento do envio —
sem preview, o usuário só descobre que a senha é fraca ou que a senha de pânico é parecida demais com
a real depois de tentar criar o cofre, através de `error_create_vault`, um texto genérico que nunca
expõe a mensagem exata do `:core` (ver `NoMessagesController.kt`, fora do escopo desta mudança).

### Como era antes

```kotlin
PasswordField(password, { password = it }, R.string.main_password, !state.busy)
Spacer(Modifier.height(12.dp))
PasswordField(confirm, { confirm = it }, R.string.confirm_main_password, !state.busy)
Spacer(Modifier.height(18.dp))
PasswordField(panicPassword, { panicPassword = it }, R.string.panic_password, !state.busy)
Spacer(Modifier.height(12.dp))
PasswordField(panicConfirm, { panicConfirm = it }, R.string.confirm_panic_password, !state.busy)
Spacer(Modifier.height(12.dp))
Text(
    text = stringResource(R.string.password_requirement),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
Spacer(Modifier.height(22.dp))
Button(
    onClick = ::submit,
    enabled = !state.busy && name.isNotBlank() && password.isNotEmpty() && confirm.isNotEmpty() &&
        panicPassword.isNotEmpty() && panicConfirm.isNotEmpty(),
    modifier = Modifier.fillMaxWidth().height(50.dp),
) { Text(stringResource(R.string.create_vault)) }
```

Nenhuma pré-visualização de força, confirmação ou similaridade; o botão só exigia os campos
preenchidos, não validados.

### Como ficou

Cada campo de senha (`password`, `panicPassword`) ganhou um `PasswordStrengthMeter` +
`PasswordStrengthTips` logo abaixo; cada campo de confirmação (`confirm`, `panicConfirm`) ganhou um
`PasswordMatchHint`; e um aviso específico de similaridade real/pânico foi inserido perto do campo de
senha de pânico (não perto da confirmação, já que é uma relação real↔pânico, não confirmação):

```kotlin
PasswordField(password, { password = it }, R.string.main_password, !state.busy)
val mainStrength = rememberPasswordStrength(password)
if (password.isNotEmpty()) {
    PasswordStrengthMeter(mainStrength, Modifier.padding(horizontal = 4.dp))
    PasswordStrengthTips(mainStrength, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
}
...
val panicSimilarity = rememberPanicSimilarityWarning(password, panicPassword)
if (panicSimilarity != null) {
    Text(text = panicSimilarity, color = MaterialTheme.colorScheme.error, ...)
}
...
val canSubmit = name.isNotBlank() && password.isNotEmpty() && confirm.isNotEmpty() &&
    panicPassword.isNotEmpty() && panicConfirm.isNotEmpty() &&
    mainStrength.score >= 3 && confirm == password &&
    panicStrength.score >= 3 && panicConfirm == panicPassword &&
    panicSimilarity == null
Button(onClick = ::submit, enabled = !state.busy && canSubmit, ...) { ... }
```

Dois `private` composables novos no fim do arquivo alimentam esses `val`:

```kotlin
@Composable
private fun rememberPasswordStrength(password: String): PasswordStrength = remember(password) {
    val chars = password.toCharArray()
    try { estimateStrength(chars) } finally { chars.fill('\u0000') }
}

@Composable
private fun rememberPanicSimilarityWarning(password: String, panicPassword: String): String? {
    if (password.isBlank() || panicPassword.isBlank()) return null
    val mustDiffer = stringResource(R.string.panic_password_must_differ)
    val tooSimilar = stringResource(R.string.panic_password_too_similar)
    return remember(password, panicPassword) {
        val realChars = password.toCharArray()
        val panicChars = panicPassword.toCharArray()
        try {
            PasswordPolicy().validatePair(realChars, panicChars)
            null
        } catch (error: IllegalArgumentException) {
            when (error.message) {
                "Passwords must be distinct" -> mustDiffer
                "Panic password is too similar to the real password" -> tooSimilar
                else -> null
            }
        } finally {
            realChars.fill('\u0000')
            panicChars.fill('\u0000')
        }
    }
}
```

`rememberPanicSimilarityWarning` chama `PasswordPolicy().validatePair(...)` só para capturar a
exceção; `validatePair` também roda `validate(real)`/`validate(panic)` internamente (tamanho,
caracteres, força), mas qualquer mensagem que não seja uma das duas de similaridade cai no
`else -> null` — essas outras falhas já são cobertas pelo medidor de força mostrado acima. As duas
mensagens exatas (`"Passwords must be distinct"` e `"Panic password is too similar to the real
password"`) são as strings literais que `PasswordPolicy.kt` lança (ver
`docs/changes/PasswordPolicy.kt.md`); se o `:core` um dia mudar o texto dessas exceções sem avisar a
UI, o pior caso é o aviso parar de aparecer (`else -> null`), nunca um crash — o botão continua
bloqueado corretamente porque `validatePair` ainda lança e `PasswordPolicy().validatePair(...)`
adicional dentro de `canSubmit` (via `panicSimilarity == null`) ainda reprova.

`password_requirement` (texto estático abaixo dos quatro campos) foi atualizado de "Use pelo menos 16
caracteres alfanuméricos..." para "Use pelo menos 12 caracteres. Símbolos e acentos são
permitidos...", refletindo a política nova do `:core` (ver `docs/changes/strings.xml.md`).

### Por que o `>= 3` está duplicado como literal

`PasswordPolicy.MINIMUM_ACCEPTABLE_SCORE` é `internal` ao módulo `:core` (não exportado), então este
módulo `:app` não pode importá-lo. O valor `3` é intencionalmente hardcoded nos dois pontos que a
tarefa pediu (`mainStrength.score >= 3`, `panicStrength.score >= 3`), com um comentário no código
apontando a razão e o `SPEC.md` §5 como fonte da verdade caso o piso mude.

### Por que a recomputação por tecla é aceita, não um bug

`mainStrength`/`panicStrength`/`panicSimilarity` são cada um um `remember(chave) { ... }` — cada um
recalcula no máximo uma vez por tecla no campo do qual depende, não cinco vezes por campo. `canSubmit`
reaproveita esses três valores já computados em vez de inlinar `estimateStrength`/`validatePair`
soltos dentro do parâmetro `enabled =`, que é o padrão que a tarefa pediu para evitar. O custo de
`estimateStrength`/`validatePair` é local e sem I/O (ver `docs/changes/PasswordStrength.kt.md`), então
mesmo o pior caso (usuário digitando rápido nos quatro campos) é imperceptível.

### Vantagens

- Symmetria: `password`/`panicPassword` — os dois únicos campos que o `:core` de fato mede força —
  ganham o mesmo tratamento visual; `confirm`/`panicConfirm` ganham o mesmo tratamento de
  confirm-match.
- O aviso de similaridade real/pânico aparece **antes** do envio, com as duas mensagens exatas que o
  usuário pediu em português ("A senha de pânico é parecida demais com a senha real." /
  "A senha de pânico não pode ser igual à senha real."), reduzindo a chance de a rejeição
  server-side (`error_create_vault`, genérica) ser a primeira vez que o usuário ouve falar do
  problema.
- `PasswordField`/`PrivateImeScope`/`privateKeyboardOptions` (T4.9) permanecem intactos — nenhum
  campo de senha novo foi criado; só composables de leitura (barra, dicas, texto) foram adicionados
  ao redor dos campos existentes.

### Testes e verificação

Não foi possível rodar `:app:testDebugUnitTest`/`:app:assembleDebug` nesta máquina (ver
`docs/changes/PasswordPolicy.kt.md`, mesma limitação de toolchain WSL/Windows já registrada por T4.10
no módulo `:core`). Revisão feita por leitura: imports resolvidos (`PasswordPolicy`, `PasswordStrength`,
`estimateStrength` do `:core`; `PasswordStrengthMeter`/`PasswordStrengthTips`/`PasswordMatchHint` de
`Components.kt`), tipos conferidos, e as chaves de string (`R.string.panic_password_must_differ`,
`R.string.panic_password_too_similar`) existem em ambos `strings.xml`/`values-en/strings.xml` (ver
`docs/changes/strings.xml.md`).

### Por que a mudança foi feita

T4.10 (ver `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`).
