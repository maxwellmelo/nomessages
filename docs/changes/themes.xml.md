# app/src/main/res/values/themes.xml

## 2026-09-17 — `android:colorAccent`/`navigationBarColor`/`statusBarColor` seguem a paleta "Grafite e Âmbar" (T4.14)

### Contexto

`themes.xml` é o único lugar do app que ainda fala em `android:*` (atributos de tema legado do
sistema, fora do Compose) — `colorAccent` e as cores de barra de navegação/status. Os três
apontavam para `wf_accent`/`wf_deep_green`, cores que o rebrand de paleta remove de
`colors.xml` (ver `docs/changes/colors.xml.md`). Como `themes.xml` não é lido por nenhum
Composable (é resolvido pelo sistema Android antes/independente da árvore do Compose), a
mudança de paleta em `NoMessagesTheme.kt` (ver `docs/changes/NoMessagesTheme.kt.md`) não o
alcança sozinha — precisa de sua própria atualização, com suas próprias cores.

### Como era antes

```xml
<style name="Theme.NoMessages" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:fontFamily">sans</item>
    <item name="android:windowActionModeOverlay">true</item>
    <item name="android:colorAccent">@color/wf_accent</item>
    <item name="android:navigationBarColor">@color/wf_deep_green</item>
    <item name="android:statusBarColor">@color/wf_deep_green</item>
    <item name="android:windowLightStatusBar">false</item>
</style>
```

### Como ficou

```xml
<style name="Theme.NoMessages" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:fontFamily">sans</item>
    <item name="android:windowActionModeOverlay">true</item>
    <item name="android:colorAccent">@color/theme_accent</item>
    <item name="android:navigationBarColor">@color/theme_chrome</item>
    <item name="android:statusBarColor">@color/theme_chrome</item>
    <item name="android:windowLightStatusBar">false</item>
</style>
```

`theme_accent` (`#D98E2B`) e `theme_chrome` (`#23282C`) são cores novas, dedicadas exclusivamente
a estes três atributos `android:*` legados — não reaproveitam `MaterialTheme.colorScheme.secondary`/
`primary` diretamente porque XML de tema não tem acesso ao `ColorScheme` do Compose; os valores
foram copiados manualmente da mesma paleta (`secondary`/`primary` do tema claro), então navegam,
status bar e o `colorAccent` do sistema continuam visualmente idênticos ao chrome que o Compose
desenha. Ver `docs/changes/colors.xml.md` para as duas cores novas.

### Por que `android:windowLightStatusBar` continua `false`

`theme_chrome` (`#23282C`) é um grafite escuro — a mesma lógica que já valia para `wf_deep_green`
(`#075E54`, também escuro) continua valendo: uma barra de status/navegação escura precisa de ícones
**claros** para ter contraste, e `windowLightStatusBar = false` é o que pede ícones claros ao
sistema (`true` pediria ícones escuros, invisíveis sobre um fundo escuro). Nenhuma mudança aqui —
listado por completude, para que o próximo leitor não precise verificar de novo se o valor ficou
desalinhado com a nova cor de fundo.

Este arquivo continua sendo um único `values/themes.xml` estático, sem variante `values-night` —
nenhuma mudança de estrutura, só de valores de cor.

### Vantagens

- O chrome do sistema (barra de status, barra de navegação, `colorAccent` herdado por diálogos e
  componentes `android:*` fora do Compose) deixa de mostrar o verde-WhatsApp antigo mesmo fora da
  árvore do Compose, fechando o último canto do app que ainda referenciava a paleta antiga.
- Cores dedicadas (`theme_accent`/`theme_chrome`) em vez de tentar importar `ColorScheme` para XML
  — mantém a separação clara entre "cores do Compose" (`NoMessagesTheme.kt`) e "cores do sistema
  Android legado" (`themes.xml`), já documentada em `docs/changes/colors.xml.md`.

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" — nenhuma cor de UI deveria continuar referenciando os valores
`wf_*` removidos de `colors.xml`.
