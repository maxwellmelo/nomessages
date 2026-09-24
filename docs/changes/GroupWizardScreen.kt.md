# app/src/main/kotlin/dev/mx3/nomessages/ui/GroupWizardScreen.kt

## 2026-09-16 — Campo de nome do grupo com teclado privado (T4.9)

### Motivo

O campo de nome do grupo (`GroupName`, terceiro passo do assistente) é o único campo de texto
editável em `GroupWizardScreen.kt`. T4.9 pede a mesma proteção de `PrivateInput.kt` em todo campo
editável do app.

### Como era antes

```kotlin
OutlinedTextField(
    value = name,
    onValueChange = onName,
    label = { Text(stringResource(R.string.group_name)) },
    singleLine = true,
    enabled = enabled,
    modifier = Modifier.fillMaxWidth(),
)
```

### Como ficou

```kotlin
PrivateImeScope {
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.group_name)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = privateKeyboardOptions(),
    )
}
```

### Vantagens

- O nome de um grupo deixa de alimentar o dicionário de aprendizado do teclado do sistema.

### Por que a mudança foi feita

T4.9, item 1.

---

## 2026-09-17 — Nova paleta "Grafite e Âmbar": três pontos de chamada trocam cor hardcoded por `MaterialTheme.colorScheme` (T4.14)

### 1. Destaque do passo ativo do assistente (`WizardSteps`)

```kotlin
// antes
color = if (step == index + 1) WfGreen else MaterialTheme.colorScheme.surfaceVariant,

// depois
color = if (step == index + 1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
```

### 2 e 3. Faixa "grupo pronto" — fundo e texto

```kotlin
// antes
Surface(color = WfAccent.copy(alpha = 0.18f), shape = MaterialTheme.shapes.medium) {
    Text(stringResource(R.string.group_ready), modifier = Modifier.fillMaxWidth().padding(16.dp), color = WfDeepGreen, fontWeight = FontWeight.SemiBold)
}

// depois
Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f), shape = MaterialTheme.shapes.medium) {
    Text(stringResource(R.string.group_ready), modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
}
```

A constante real usada pelo texto era `WfDeepGreen`, não `WfGreen` — mapeamento genérico entre as
duas telas anteriores (`WfDeepGreen → primary`, `WfGreen/WfAccent → secondary`) não se aplica aqui
sem ajuste: um `Text` `primary` (grafite escuro) sobre um `Surface` tingido de `secondary` (âmbar)
a 18% de opacidade teria contraste pior do que o texto reaproveitar o mesmo `secondary` do fundo em
tom cheio — por isso `WfDeepGreen` foi mapeada para `secondary` aqui, não para `primary`, para
continuar legível/consistente com a moldura em tom âmbar que o envolve.

### Vantagens

- O destaque de passo ativo e a faixa de "grupo pronto" passam a reagir a tema claro/escuro.
- A cor do texto da faixa "grupo pronto" continua legível sobre o novo fundo tingido de âmbar —
  verificado por leitura (mesmo token `secondary` no texto e no fundo, então contraste depende só da
  opacidade do fundo, não de uma combinação de dois papéis diferentes).

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" em vez da identidade verde-WhatsApp.

## 2026-09-23 — `selected_count`/`missing_pairs_title` viram `<plurals>` (T4.6)

### Motivo

`:app:lintDebug` apontava as duas strings como `PluralsCandidate`. Ver `docs/changes/strings.xml.md`
para a conversão dos recursos.

### Como era

```kotlin
Text(stringResource(R.string.selected_count, selected.size), ...)
Text(stringResource(R.string.selected_count, selectedCount), ...)          // × 2 (verificação e nome)
Text(stringResource(R.string.missing_pairs_title, missingPairs.size), ...)
```

### Como ficou

```kotlin
Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size), ...)
Text(pluralStringResource(R.plurals.selected_count, selectedCount, selectedCount), ...)  // × 2
Text(pluralStringResource(R.plurals.missing_pairs_title, missingPairs.size, missingPairs.size), ...)
```

Os quatro call sites deste arquivo (seleção de membros, verificação de grafo × 2, nome do grupo)
mudaram. A faixa válida de seleção é 2 a 99 contatos (`validGroupSelection`), então a categoria "one"
do plural nunca é exercitada na prática por este fluxo — mas fica correta para os textos de leitor de
tela e para qualquer mudança futura no mínimo de seleção.

### Vantagens

- Fecha `PluralsCandidate` para as duas strings sem mudar nenhum texto mostrado hoje.
- `%1$d selecionado`/`Ainda falta %1$d pareamento` (categoria "one", com concordância verbal
  correta em PT) ficam prontos, não só a forma plural.

### Verificação

`:app:testDebugUnitTest`/`:app:lintDebug`: `BUILD SUCCESSFUL`, zero `PluralsCandidate` remanescente.
