# app/src/main/kotlin/dev/mx3/nomessages/ui/ContactsScreens.kt

## 2026-09-16 — Campo de apelido com teclado privado (T4.9)

### Motivo

O campo de apelido em `ContactInfoScreen` é o único campo de texto editável em
`ContactsScreens.kt`. T4.9 pede a mesma proteção de `PrivateInput.kt` em todo campo editável do
app.

### Como era antes

```kotlin
OutlinedTextField(
    value = alias,
    onValueChange = { alias = it },
    label = { Text(stringResource(R.string.alias)) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
)
```

### Como ficou

```kotlin
PrivateImeScope {
    OutlinedTextField(
        value = alias,
        onValueChange = { alias = it },
        label = { Text(stringResource(R.string.alias)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = privateKeyboardOptions(),
    )
}
```

### Vantagens

- O apelido de um contato deixa de alimentar o dicionário de aprendizado do teclado do sistema.

### Por que a mudança foi feita

T4.9, item 1.
