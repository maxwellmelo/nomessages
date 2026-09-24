# app/src/main/kotlin/dev/mx3/nomessages/MainActivity.kt

## 2026-09-15 — Chave de captura de tela só em build debug

Contexto: a investigação de 2026-09-15 sobre o bloqueador de criação de cofre (ver
`docs/development/device-verification.md`) precisou, à parte da causa raiz em si, poder tirar
`screencap`/`screenrecord` do emulador para confirmar visualmente telas do app durante o
diagnóstico — hoje impossível mesmo em build debug porque `FLAG_SECURE` cega qualquer captura,
sempre. `docs/security-model.md` já documentava esse comportamento como intencional para produção;
esta mudança acrescenta uma válvula de escape **só para debug**, opt-in, com o padrão inalterado.

### Como era antes

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    super.onCreate(savedInstanceState)
    ...
```

`FLAG_SECURE` era aplicada incondicionalmente, em todo build (debug e release). Não havia como
capturar a tela do app nem em desenvolvimento, mesmo num emulador sem dados reais.

### Como ficou

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    if (shouldApplySecureFlag(BuildConfig.DEBUG, debugCaptureProperty())) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    super.onCreate(savedInstanceState)
    ...
```

Duas peças novas:

- `shouldApplySecureFlag(isDebug, propertyValue)` — função pura, em
  `app/src/main/kotlin/dev/mx3/nomessages/ui/UiLogic.kt` (local convencional do projeto para lógica de
  UI sem efeitos colaterais; ver `docs/changes/UiLogic.kt.md`), coberta por teste JVM
  (`UiLogicTest.kt`) para as 4 combinações de `(isDebug, propertyValue)`.
- `debugCaptureProperty()` — lê a system property `debug.nomessages.allow_capture` via
  `android.os.SystemProperties.get(String, String)` por reflexão (a classe não é API pública, não
  há import direto). Qualquer falha de reflexão (classe ausente, assinatura mudou,
  `SecurityException`, etc.) cai num `catch (_: Throwable)` amplo que devolve `"0"` — o mesmo valor
  de "propriedade ausente" — então uma falha de reflexão só pode tornar o app mais seguro
  (`FLAG_SECURE` permanece aplicada), nunca menos.

`FLAG_SECURE` só deixa de ser aplicada quando **as duas** condições são verdadeiras ao mesmo tempo:
`BuildConfig.DEBUG == true` **e** a propriedade lida é exatamente `"1"`. Qualquer build release
ignora a propriedade por completo (a condição `isDebug` já é `false` em release, decidida em tempo
de compilação por variante). `adb shell setprop debug.nomessages.allow_capture 1` não exige root, pois
é uma propriedade do namespace `debug.*`.

### Vantagens

- Desenvolvimento e diagnóstico (como o desta própria investigação, e o relay de QR entre dois
  emuladores mencionado em `docs/development/device-verification.md`) deixam de exigir um build
  paralelo sem `FLAG_SECURE` mantido à mão — um `adb shell setprop` já habilita a captura.
- O comportamento padrão (propriedade ausente, ou qualquer build release) é **idêntico** ao de
  antes: `FLAG_SECURE` sempre aplicada. Nenhuma regressão de segurança para o usuário final.
- A decisão em si é uma função pura testável (`shouldApplySecureFlag`), separada do código Android
  específico (`Window`, reflexão de `SystemProperties`), então a regra de negócio ("só debug, só
  com a propriedade exata") tem cobertura de teste JVM direta, sem precisar de instrumentação.
- Falha de reflexão nunca derruba o app (catch amplo) e nunca relaxa a segurança por acidente (o
  fallback é sempre o valor mais seguro).

### Motivo da mudança

Tarefa 4 da investigação de 2026-09-15 (bloqueador de criação de cofre pela UI real nos dois
emuladores oficiais Android 15 x86_64): permitir captura de tela só em builds debug, mediante opt-in
explícito, sem alterar o comportamento padrão documentado em `docs/security-model.md`.

### Limitação conhecida, encontrada na verificação (2026-09-15)

Nos dois emuladores oficiais Android 15 x86_64 usados nesta investigação (`emulator-5556`/
`emulator-5560`, imagens `userdebug`), `android.os.SystemProperties.get(String, String)` está
bloqueada para reflexão por padrão (política de hidden API do Android para apps com `targetSdk 35`),
mesmo o app sendo `debuggable`. Com `adb shell setprop debug.nomessages.allow_capture 1` sozinho, a
reflexão falhava silenciosamente (o `catch` amplo por design) e `FLAG_SECURE` permanecia aplicada —
comportamento seguro, mas também a chave não tinha efeito algum nesses dois emuladores. Só depois de
`adb shell settings put global hidden_api_policy 1` (modo de compatibilidade, disponível em builds
`userdebug`/`eng`, reversível com o mesmo comando usando `0` ou removendo a chave) a reflexão passou
a funcionar e uma captura com a propriedade em `"1"` deixou de ser preta (confirmado via
`screencap` + verificação de pixels não-pretos). Isso não é uma falha do código: o fallback seguro
funcionou exatamente como projetado (nunca destrava a captura por acidente). É, porém, um pré-requisito
adicional, específico deste tipo de imagem de emulador, que vale documentar para quem for repetir o
teste manualmente — em um dispositivo físico ou emulador com política de hidden API diferente, o
comportamento pode variar.

---

## 2026-09-15 — T3.3 run4: `debugCaptureProperty()` generalizada para `readSystemProperty(name, fallback)`

Motivo: o hook debug-only de injeção de QR (`DebugQrReceiver`, ver
`docs/changes/DebugQrReceiver.kt.md`) precisa ler uma segunda propriedade de sistema,
`debug.nomessages.allow_qr_inject`, exatamente com a mesma mecânica e a mesma política de falha
segura já usada aqui para `debug.nomessages.allow_capture`. Copiar o bloco de reflexão seria duplicar
justamente o trecho mais delicado do arquivo — aquele cujo `catch (_: Throwable)` deliberadamente
amplo é o que garante que uma falha de hidden-API nunca vire um opt-in acidental.

### Como era antes

```kotlin
private fun debugCaptureProperty(): String = try {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
        .invoke(null, "debug.nomessages.allow_capture", "0") as? String
        ?: "0"
} catch (_: Throwable) {
    "0"
}
```

Nome da propriedade e valor de fallback ficavam embutidos na função.

### Como ficou

```kotlin
/** Reads `debug.nomessages.allow_capture`; see [readSystemProperty] for the mechanism and failure mode. */
private fun debugCaptureProperty(): String = readSystemProperty("debug.nomessages.allow_capture", "0")

internal fun readSystemProperty(name: String, fallback: String): String = try {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
        .invoke(null, name, fallback) as? String
        ?: fallback
} catch (_: Throwable) {
    fallback
}
```

### O que mudou e o que **não** mudou

- **Comportamento em runtime: idêntico.** `debugCaptureProperty()` continua lendo a mesma
  propriedade com o mesmo fallback `"0"`. `shouldApplySecureFlag` e seus testes unitários não foram
  tocados. Nenhuma chamada existente mudou de semântica.
- **Visibilidade:** `readSystemProperty` é `internal`, então fica visível para o source set
  `debug` do mesmo módulo (que é compilado junto com `main`) sem virar API pública do app.
- **Contrato do fallback documentado explicitamente:** o KDoc agora obriga o chamador a escolher
  como `fallback` o valor **seguro**, porque é ele que será usado se a reflexão falhar. Para
  `allow_capture` o seguro é `"0"` (mantém `FLAG_SECURE`); para `allow_qr_inject` o seguro também é
  `"0"` (mantém o hook desligado). Antes essa regra era verdadeira mas implícita em um único
  chamador; com dois chamadores ela precisava virar contrato escrito.
- **Nota adicionada ao KDoc:** escrever propriedades `debug.*` é restrito pelo SELinux aos domínios
  `shell`/`su`, enquanto ler é livre. Esse detalhe é o que torna a propriedade utilizável como
  *teste de capacidade* ("quem pede tem adb shell ou root?") pelo `DebugQrReceiver`, e não apenas
  como uma chave de conveniência — vale registrar ao lado do mecanismo.

### Vantagens

1. **Elimina duplicação do trecho mais sensível do arquivo.** Uma única implementação da reflexão,
   um único `catch` amplo, uma única política de falha fechada — auditável em um lugar só.
2. **Nenhuma mudança de comportamento**, portanto nenhum risco para a proteção de `FLAG_SECURE`
   que este arquivo já garantia (os testes de `shouldApplySecureFlag` seguem cobrindo as quatro
   combinações `(isDebug, propertyValue)`).
3. A ressalva de hidden-API documentada na seção anterior deste arquivo (`hidden_api_policy 1` em
   algumas imagens de emulador Android 15) passa a valer, escrita uma vez, para **as duas**
   propriedades.

---

## 2026-09-16 — Autofill, acessibilidade sensível, aviso de IME de terceiros e detecção de captura (T4.9)

O arquivo é `app/src/main/kotlin/dev/mx3/nomessages/MainActivity.kt`. Esta seção usa o
caminho/pacote atual.

### Motivo

T4.9 pede quatro proteções além de `FLAG_SECURE` (item 1, teclado privado, é tratado em
`PrivateInput.kt` e nos arquivos de tela — ver `docs/changes/PrivateInput.kt.md`): desligar
autofill, marcar a janela como sensível para acessibilidade não-padrão (API 34+), detectar qual
IME está ativo para alimentar o aviso de teclado de terceiros, e detectar tentativas de captura de
tela (API 34+) com um aviso não bloqueante.

### Como era antes

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    if (shouldApplySecureFlag(BuildConfig.DEBUG, debugCaptureProperty())) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    super.onCreate(savedInstanceState)
    window.decorView.filterTouchesWhenObscured = true
    resultOwner = controller.attachPlatform(this, ...)
    setContent {
        val state by controller.state.collectAsStateWithLifecycle()
        ...
        if (state.unlocked && captureId != null) key(captureId) { ... }
        else NoMessagesApp(state, controller)
    }
}
override fun onStart() { super.onStart(); controller.onForeground() }
override fun onStop() {
    if (isFinishing) controller.lock() else controller.onBackground()
    super.onStop()
}
```

Nenhum sinal de autofill, acessibilidade, IME ativo ou captura de tela existia.

### Como ficou

`onCreate` ganhou, logo após a linha existente `window.decorView.filterTouchesWhenObscured = true`
(não tocada):

```kotlin
window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
if (Build.VERSION.SDK_INT >= 34) {
    window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
}
```

`setContent` ganhou um `SnackbarHost` (o primeiro do app — não havia nenhum antes) num `Box` que
envolve o conteúdo existente, mais dois novos sinais observáveis por Compose:

```kotlin
private val thirdPartyImeActive = mutableStateOf(false)
private val captureAlertToken = mutableIntStateOf(0)
private var screenCaptureCallback: Activity.ScreenCaptureCallback? = null
```

lidos dentro de `setContent` (`val imeThirdParty by thirdPartyImeActive`, passado a
`NoMessagesApp(state, controller, thirdPartyImeActive = imeThirdParty)`) e um `LaunchedEffect`
que mostra o `Snackbar` de captura quando `captureAlertToken` incrementa.

`onStart` ganhou:

```kotlin
thirdPartyImeActive.value = computeThirdPartyImeActive(this)
if (Build.VERSION.SDK_INT >= 34 && screenCaptureCallback == null) {
    val callback = Activity.ScreenCaptureCallback { captureAlertToken.intValue += 1 }
    screenCaptureCallback = callback
    registerScreenCaptureCallback(mainExecutor, callback)
}
```

`onStop` ganhou o desregistro simétrico (API 34+) antes da lógica de lock/background já existente,
que não mudou.

Duas funções de nível de arquivo novas: `computeThirdPartyImeActive(context)` (lê
`Settings.Secure.DEFAULT_INPUT_METHOD` + `InputMethodManager.getEnabledInputMethodList()`, delega a
decisão pura a `isSystemIme` de `UiLogic.kt`) e `isSystemPackage(packageManager, packageName)`
(checa `ApplicationInfo.FLAG_SYSTEM`/`FLAG_UPDATED_SYSTEM_APP`, `false` em
`NameNotFoundException`).

### Vantagens

- Autofill e acessibilidade-sensível são configurados uma única vez no `decorView`, cobrindo toda a
  árvore Compose por herança — nenhuma tela individual precisou de um lever extra.
- O sinal de IME de terceiros e o token de captura vivem em `MainActivity` como estado local de
  Compose (`mutableStateOf`/`mutableIntStateOf`), não em `UiState`/`NoMessagesController` — são
  sinais de plataforma, não estado de app/cofre, então nada no controlador de negócio precisou
  mudar.
- `screenCaptureCallback`/registro são guardados por `Build.VERSION.SDK_INT >= 34` nos dois lados
  (registro e desregistro), preservando o comportamento existente em API 31-33.
- `onStart`/`onStop` continuam chamando `controller.onForeground()`/`onBackground()`/`lock()`
  exatamente como antes — nenhuma regressão de lifecycle.

### Por que a mudança foi feita

T4.9, itens 2, 3, 4 e 5.

### Verificação

`:app:compileDebugKotlin` → `BUILD SUCCESSFUL` (confirma que `InterceptPlatformTextInput`,
`Activity.ScreenCaptureCallback`, `setAccessibilityDataSensitive` e
`View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` resolvem exatamente como usados aqui contra o
SDK/compose-bom deste projeto). Ver `docs/changes/MainActivity.kt.md`'s seção "Validação" mais
recente (ou o relatório final da tarefa) para o resultado completo de
`testDebugUnitTest`/`lintDebug`/`assembleDebug` e a verificação manual em `emulator-5556`.

---

## 2026-09-16 — Correção pós-verificação em dispositivo: `getApplicationInfo` filtrado por visibilidade de pacote classificava Gboard como "terceiros"

### Motivo

A verificação manual em `emulator-5556` (T4.9) mostrou o banner "Third-party keyboard active..."
na tela de Setup mesmo com o teclado padrão sendo o Gboard pré-instalado
(`com.google.android.inputmethod.latin`, confirmado via `adb shell dumpsys package` com
`flags=[ SYSTEM ... ]`). Isso indicava um falso positivo na implementação original de
`computeThirdPartyImeActive`.

### Como era antes

```kotlin
private fun computeThirdPartyImeActive(context: Context): Boolean {
    val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?: return false
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val packageManager = context.packageManager
    val systemPackages = inputMethodManager?.enabledInputMethodList
        ?.map { it.packageName }
        ?.filterTo(HashSet()) { packageName -> isSystemPackage(packageManager, packageName) }
        ?: emptySet()
    return !isSystemIme(imeId, systemPackages)
}

private fun isSystemPackage(packageManager: PackageManager, packageName: String): Boolean = try {
    val flags = packageManager.getApplicationInfo(packageName, 0).flags
    (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
} catch (_: PackageManager.NameNotFoundException) {
    false
}
```

**Causa raiz:** `packageManager.getApplicationInfo(packageName, 0)` é uma consulta de
`PackageManager` sujeita ao filtro de visibilidade de pacotes do Android (API 30+, reforçado pelo
`targetSdk 35` deste app): sem um elemento `<queries>` declarando interesse em pacotes de IME, essa
chamada lança `NameNotFoundException` mesmo para o Gboard pré-instalado — o `catch` amplo então
devolvia `false` (não-sistema) para **todo** IME, inclusive os de sistema, fazendo o banner aparecer
sempre. Confirmado com `adb shell dumpsys package com.google.android.inputmethod.latin`, que mostra
`flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP RESTORE_ANY_VERSION ]` — o pacote é,
de fato, um app de sistema; o app só não conseguia mais consultá-lo.

### Como ficou

```kotlin
private fun computeThirdPartyImeActive(context: Context): Boolean {
    val imeId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?: return false
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val systemPackages = inputMethodManager?.enabledInputMethodList
        ?.filter { isSystemApplicationInfo(it.serviceInfo?.applicationInfo) }
        ?.mapTo(HashSet()) { it.packageName }
        ?: emptySet()
    return !isSystemIme(imeId, systemPackages)
}

private fun isSystemApplicationInfo(info: ApplicationInfo?): Boolean {
    val flags = info?.flags ?: return false
    return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
```

Em vez de uma segunda consulta ao `PackageManager` (sujeita ao filtro de visibilidade),
`InputMethodInfo.serviceInfo.applicationInfo` já vem preenchido pelo próprio sistema como parte do
retorno de `getEnabledInputMethodList()` — sem nenhuma filtragem adicional, porque não é uma nova
consulta de `PackageManager` feita pelo app, e sim um campo já anexado ao objeto que o
`InputMethodManager` devolveu. A dependência de `android.content.pm.PackageManager` foi removida do
arquivo (import não usado em mais nenhum outro lugar).

### Vantagens

- Corrige um falso positivo que apareceria em **qualquer** dispositivo/emulador real (não um
  artefato do emulador) sempre que o app não declarasse `<queries>` para IMEs — ou seja, o banner
  estaria sempre ligado, mesmo com o teclado de fábrica, tornando o aviso inútil por excesso de
  ruído (o oposto do "aviso discreto" pedido pela tarefa).
- Elimina uma consulta de `PackageManager` inteira por IME habilitado, então é também mais barato.
- Não exige adicionar um elemento `<queries>` ao manifesto (que ampliaria a superfície de
  visibilidade de pacotes do app só para este propósito) — o dado necessário já estava disponível
  sem essa consulta extra.

### Verificação

Confirmado em `emulator-5556`: antes da correção, `adb shell uiautomator dump` na tela de Setup
mostrava `text="Third-party keyboard active: it can see what you type"` com o Gboard como IME
padrão. Reinstalado o APK reconstruído com a correção e reverificado (ver relatório final da
tarefa para o resultado).

### Por que a mudança foi feita

T4.9 — achado da própria verificação manual em dispositivo pedida pela tarefa, corrigido antes de
reportar o item como concluído.
