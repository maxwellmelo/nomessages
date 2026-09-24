# colors.xml — cores adicionadas para o novo ícone

## O que mudou

Duas cores novas foram **acrescentadas** ao final de
`app/src/main/res/values/colors.xml`, dedicadas exclusivamente ao ícone do launcher
(`ic_launcher_*`). Nenhuma cor existente foi alterada, renomeada ou removida.

```diff
     <color name="wf_surface">#EDEDED</color>
+
+    <!-- Dedicated to the app launcher icon (chat bubble + lock concept). Do not reuse for UI chrome. -->
+    <color name="ic_launcher_bg">#073C31</color>
+    <color name="ic_launcher_glyph">#F3F6F5</color>
 </resources>
```

## Cores adicionadas

| Nome                  | Valor     | Uso                                                              |
|------------------------|-----------|-------------------------------------------------------------------|
| `ic_launcher_bg`       | `#073C31` | Fundo do ícone (camada `background` do adaptive icon e fundo dos vectors legado em `mipmap/`). Verde-teal profundo, sóbrio, dentro da família `#06342C`–`#0A3D33` pedida. |
| `ic_launcher_glyph`    | `#F3F6F5` | Contorno do balão e corpo do cadeado (camada `foreground`). Quase-branco levemente frio. |

Prefixo `ic_launcher_` escolhido deliberadamente (em vez de `wf_`) para não colidir com o
rename de prefixo `wf_` → outro em andamento, em paralelo, pelo agente que está rebatizando o
pacote/strings do app.

## Por que não reaproveitar cores existentes

- `wf_deep_green` e `wf_accent` são usados em `values/themes.xml` (status bar / nav bar /
  `colorAccent`) — não foram tocados, e `wf_accent` continua referenciado dentro dos vectors do
  ícone (buraco da fechadura), conforme pedido.
- `wf_bubble` e `wf_surface` já estão mortos (`UnusedResources`, ver
  `docs/development/build-report.md`) e são de responsabilidade de outra limpeza — não foram
  usados nem removidos aqui, para não gerar divergência com aquele relatório.
- `wf_green` continua referenciado (agora só pelo ícone, como já era o caso antes) — usado como
  tom secundário na fenda do buraco da fechadura, então não vira lixo novo no lint.

## Contraste (WCAG)

Verificado programaticamente (fórmula de luminância relativa sRGB):

- `ic_launcher_glyph` (`#F3F6F5`) sobre `ic_launcher_bg` (`#073C31`): razão ≈ **11.4:1**
  (bem acima do mínimo AA de 4.5:1, folga que chega a atender AAA).
- `wf_accent` (`#25D366`) sobre `ic_launcher_bg` (`#073C31`): razão ≈ **6.2:1** (acima de AA).

## Confirmação

Nenhuma linha existente em `colors.xml` foi editada ou removida — apenas duas linhas novas
foram acrescentadas ao final do arquivo, antes do fechamento de `</resources>`.

---

## 2026-09-17 — Nova paleta "Grafite e Âmbar": os cinco `wf_*` saem, três cores de tema entram (T4.14)

### Como era antes

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="wf_deep_green">#075E54</color>
    <color name="wf_green">#128C7E</color>
    <color name="wf_accent">#25D366</color>
    <color name="wf_bubble">#DCF8C6</color>
    <color name="wf_surface">#EDEDED</color>

    <!-- Dedicated to the app launcher icon (silenced chat bubble concept, 2026-09-16). Do not reuse for UI chrome. -->
    <color name="ic_launcher_bg">#073C31</color>
    <color name="ic_launcher_glyph">#F3F6F5</color>
</resources>
```

### Como ficou

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Dedicated to the app launcher icon (silenced chat bubble concept, 2026-09-16). Do not reuse for UI chrome. -->
    <color name="ic_launcher_bg">#1B1F22</color>
    <color name="ic_launcher_glyph">#F3F6F5</color>
    <!-- Dedicated to the launcher icon's diagonal slash (Grafite e Âmbar palette, 2026-09-17). Do not reuse for UI chrome. -->
    <color name="ic_launcher_accent">#E8A33B</color>

    <!-- Legacy android:* theme attributes only (themes.xml); Compose screens use MaterialTheme.colorScheme. -->
    <color name="theme_accent">#D98E2B</color>
    <color name="theme_chrome">#23282C</color>
</resources>
```

### O que mudou, item a item

- **Removidas:** `wf_deep_green`, `wf_green`, `wf_accent`, `wf_bubble`, `wf_surface` — as cinco
  cores hardcoded que aproximavam a paleta clássica do WhatsApp. Nenhuma tela do Compose as lê mais
  (substituídas por `MaterialTheme.colorScheme.*`, ver `docs/changes/NoMessagesTheme.kt.md` e o
  `docs/changes/*.kt.md` de cada tela); `themes.xml` também parou de referenciá-las diretamente
  (ver `docs/changes/themes.xml.md`).
- **`ic_launcher_bg` mudou de valor** (não foi removida): `#073C31` (verde-teal escuro) →
  `#1B1F22` (grafite escuro, o mesmo `onBackground`/`primary`-escuro da paleta nova). O launcher
  continua com fundo escuro sóbrio — só a família de cor mudou de verde para grafite.
- **`ic_launcher_glyph` não mudou** — continua `#F3F6F5`, o contorno quase-branco do balão.
- **`ic_launcher_accent` é nova** (`#E8A33B`): antes o traço diagonal do ícone reaproveitava
  `wf_accent`; com `wf_accent` removida, o traço ganha uma cor própria, dentro da família âmbar da
  paleta nova mas ajustada separadamente do `secondary` do Compose (`#D98E2B`/`#F2A83D`) para ficar
  legível sobre o fundo escuro do launcher em qualquer densidade de tela — ver
  `docs/changes/ic_launcher.md`.
- **`theme_accent`/`theme_chrome` são novas**: dedicadas exclusivamente aos atributos `android:*`
  legados de `themes.xml` (`colorAccent`, `navigationBarColor`, `statusBarColor`) — ver
  `docs/changes/themes.xml.md` para o porquê de não apontarem direto para o `ColorScheme` do
  Compose.

### Por que não reaproveitar `MaterialTheme.colorScheme.secondary`/`primary` direto nas cores novas

`ic_launcher_accent`, `theme_accent` e `theme_chrome` vivem fora da árvore do Compose (vector
drawables do launcher, resolvidos pelo `PackageManager`/launcher do sistema; atributos `android:*`
de tema, resolvidos antes de qualquer `Composable` existir), então não há como referenciar
`MaterialTheme.colorScheme` ali. Os três valores foram copiados manualmente da mesma paleta
("Grafite e Âmbar") para manter a mesma identidade visual nesses dois cantos fora do Compose.

### Confirmação

`grep -rn "wf_" app/src/main/res/` não retorna nenhuma ocorrência depois desta mudança — nenhum
arquivo de recurso (XML de tema, drawable ou mipmap) continua referenciando as cores removidas.
