# app/src/main/kotlin/dev/mx3/nomessages/ui/PrivateInput.kt

## 2026-09-16 — Arquivo novo: helpers de teclado/IME privado (T4.9)

### Motivo

T4.9 pede que todo campo de texto do app reduza o vazamento de dados de teclado além do que
`FLAG_SECURE` já cobre (screenshot/gravação/Recents): desligar autocorrect do lado Compose e, mais
importante, pedir ao IME do sistema para não aprender/sugerir a partir do que é digitado no app —
algo que `KeyboardOptions` sozinho não alcança, porque isso vive no `EditorInfo`/`InputType`
nativos que o Android entrega ao teclado, não em nenhum campo exposto pela API pública de
`KeyboardOptions`. Antes deste arquivo não existia nenhum ponto central para essa lógica; cada tela
montava seu próprio `KeyboardOptions(...)` de forma independente.

### Como era antes

Não existia. Cada campo (`Components.kt`, `ChatScreen.kt`, `SetupLockScreens.kt` etc.) chamava
`KeyboardOptions(...)` diretamente, sem nenhum desligamento de autocorrect e sem nenhuma
interceptação do `EditorInfo` nativo — o teclado do sistema ficava livre para aprender/sugerir a
partir de senhas, mensagens, apelidos etc.

### Como ficou

Duas funções públicas, ambas no pacote `dev.mx3.nomessages.ui` (mesmo pacote de todas as telas, sem
import extra necessário nos call sites):

```kotlin
fun privateKeyboardOptions(
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
): KeyboardOptions = KeyboardOptions(
    autoCorrectEnabled = false,
    keyboardType = keyboardType,
    imeAction = imeAction,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateImeScope(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            nextHandler.startInputMethod(
                PlatformTextInputMethodRequest { outAttrs ->
                    val connection = request.createInputConnection(outAttrs)
                    outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    outAttrs.inputType = outAttrs.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    connection
                },
            )
        },
        content = content,
    )
}
```

`privateKeyboardOptions` substitui `KeyboardOptions(...)` com o autocorrect já desligado
(`autoCorrectEnabled = false`), mantendo `keyboardType`/`imeAction` configuráveis pelo chamador.
`PrivateImeScope` usa `InterceptPlatformTextInput` (API confirmada por compilação contra o
compose-bom 2026.06.01 deste projeto, que resolve `androidx.compose.ui:ui:1.11.4`) para interceptar
a sessão de IME de tudo que estiver dentro do seu `content` e fazer um **OR** das duas flags de
privacidade no `EditorInfo` que o próprio campo já construiu — nunca um overwrite, então bits que o
campo já definiu (variação de senha, ação do IME etc.) não se perdem.

**Por que não é literalmente um `Modifier.privateIme()`** (como o pedido original nomeava): a única
API do Compose UI resolvido neste projeto capaz de interceptar o `EditorInfo` real é
`InterceptPlatformTextInput`, que é uma função `@Composable` que envolve um `content: @Composable ()
-> Unit` — ela fornece um `CompositionLocal` para a subárvore, e só um `content` lambda pode
escopar isso, não uma função de extensão de `Modifier` (que só contribui para a cadeia de
modificadores de um único nó, sem lambda de conteúdo para envolver o restante da composição). A
alternativa mais simples cogitada, `KeyboardOptions.platformImeOptions`/`PlatformImeOptions`, só
encaminha uma *string* (`privateImeOptions`, um extra que o IME pode ignorar) e não tem nenhuma
forma tipada de fazer OR em `imeOptions`/`inputType`, então não conseguiria as duas flags exigidas.
O raciocínio completo está no KDoc do próprio arquivo.

### Vantagens

- Um único ponto de manutenção para "campo de texto privado" — qualquer ajuste futuro nas flags de
  privacidade (ex.: uma terceira flag do Android) muda uma vez, não em oito arquivos.
- OR em vez de overwrite: nenhum campo perde comportamento existente (ex.: `KeyboardType.Password`
  no `PasswordField`, `ImeAction.Send` no composer) ao ganhar a proteção.
- Compilação (`:app:compileDebugKotlin`, depois `:app:assembleDebug`) confirmou que as assinaturas
  usadas (`InterceptPlatformTextInput`, `PlatformTextInputMethodRequest`, `KeyboardOptions.autoCorrectEnabled`)
  são exatamente as expostas pela versão resolvida do Compose UI neste projeto — não presumidas.

### Por que a mudança foi feita

T4.9 (`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`), item 1 do pedido de
endurecimento de entrada.
