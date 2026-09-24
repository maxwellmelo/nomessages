# PairingScreen.kt

## 2026-09-15 — T3.3 run3: `ImageAnalysis` em 640x480 + fallback de rotação morto no scanner de QR

Contexto completo (evidência bruta, comandos, PNGs) em
`docs/development/device-verification.md` ("T3.3 — run3") e
`docs/development/build-logs/two-emulator-20260915/run2/session-log.md` (achado original que
motivou esta sessão). Este arquivo documenta a mudança em `PairingScreen.kt` especificamente; a
função de decodificação em si foi extraída para `QrDecoding.kt` (ver `docs/changes/QrDecoding.kt.md`
para o detalhe do bug real encontrado).

### Como era antes

```kotlin
val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
analysis.setAnalyzer(analyzerExecutor) { image ->
    val result = try { image.decodeQr() } finally { image.close() }
    if (result != null && delivered.compareAndSet(false, true)) { ... }
}

private fun ImageProxy.decodeQr(): Result? {
    ...
    val source = PlanarYUVLuminanceSource(data, dataWidth, height, 0, 0, width, height, false)
    runCatching { activeReader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
        ?: if (source.isRotateSupported) runCatching { ... source.rotateCounterClockwise() ... }.getOrNull() else null
}
```

Sem `ResolutionSelector`, o CameraX escolhia o `StreamSpec` padrão para `ImageAnalysis`
(`640x480`, confirmado em logcat nas sessões anteriores). A lógica de decodificação vivia inteira
dentro do arquivo, acoplada a `ImageProxy`, sem nenhum teste automatizado, e tentava apenas uma
rotação (que — ver abaixo — nunca de fato executava) e nenhuma inversão de polaridade.

### Como ficou

1. **Resolução:** `ImageAnalysis.Builder()` agora recebe um `ResolutionSelector` pedindo
   1920×1080 (`ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`, com preferência de
   proporção 16:9). `STRATEGY_KEEP_ONLY_LATEST` continua em uso, então o backpressure na resolução
   maior continua limitado (descarta frames que o analisador não processa a tempo, em vez de
   enfileirar).
2. **Decodificação:** `ImageProxy.decodeQr()` agora só extrai o plano Y para um buffer compacto e
   delega toda a decodificação (incluindo o fallback de rotação/inversão) para
   `decodeQrLuminance()`, uma função pura nova em `QrDecoding.kt` — ver esse documento para o bug
   real corrigido ali (o fallback de rotação da versão antiga nunca executava).
3. **Log de diagnóstico (debug-only, permanente):** `logAnalysisFrame()` registra, uma linha por
   frame, largura/altura/rowStride/pixelStride/rotationDegrees/format — gated por
   `BuildConfig.DEBUG`, então custo zero em build de release. Isso existia como uma dump em PGM
   temporária durante a investigação desta sessão; a dump foi removida (ver "O que foi removido"
   abaixo) mas o log de uma linha por frame foi mantido por ser barato e diretamente útil para
   qualquer investigação futura sem precisar reintroduzir nada.

```kotlin
val resolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
    .build()
val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setResolutionSelector(resolutionSelector)
    .build()
analysis.setAnalyzer(analyzerExecutor) { image ->
    if (BuildConfig.DEBUG) logAnalysisFrame(image)
    val result = try { image.decodeQr() } finally { image.close() }
    if (BuildConfig.DEBUG) Log.d(QR_SCAN_TAG, "decode attempt result=${if (result != null) "DECODED" else "none"}")
    if (result != null && delivered.compareAndSet(false, true)) { ... }
}
```

### O que foi removido (dump de depuração temporária)

Durante a investigação, uma função `dumpFirstFrameForDebug()` (debug-only, um único frame,
gated por `BuildConfig.DEBUG` + `AtomicBoolean`) escrevia o plano Y bruto do primeiro frame
analisado como um arquivo PGM em `cacheDir`, para inspeção manual via
`adb exec-out run-as dev.mx3.nomessages.debug cat cache/qr-debug-frame.pgm > frame.pgm`. Essa dump foi
essencial para provar, com evidência visual e um decode cruzado independente (`zxing-cpp`/Python),
que o frame de análise realmente continha um QR completo e de alto contraste — refutando as
hipóteses de oclusão/câmera mal-posicionada levantadas durante a sessão (ver
`docs/development/device-verification.md`). Ela foi removida depois de confirmado o diagnóstico,
conforme pedido pela tarefa; o log de uma linha por frame (`logAnalysisFrame`) permanece.

### Evidência coletada nesta sessão (resumo — detalhe completo no device-verification.md)

- Frame de análise real, com o fix de resolução já aplicado: **1280×720**, `rotationDegrees=90`,
  `rowStride=1280`, `pixelStride=1` — não 1920×1080 pedido, porque a câmera virtual do emulador não
  oferece esse modo; o `ResolutionSelector` caiu para o mais próximo disponível, exatamente o
  comportamento de fallback pretendido.
- A dump do frame mostrou um QR completo, nítido (transições de borda de 2-3 px), alto contraste
  (mín ~16, máx ~233) — decodificado com sucesso por uma biblioteca independente (`zxing-cpp`/
  Python) a partir do mesmíssimo buffer de bytes.
- Isso isolou definitivamente a causa: não era oclusão, não era o macro de posicionamento da
  câmera, não era falta de contraste/nitidez — era o fallback de rotação nunca executar (ver
  `docs/changes/QrDecoding.kt.md` para o porquê) combinado com uma limitação residual do detector
  clássico do ZXing Java diante da leve distorção de perspectiva da cena virtual (ver o mesmo
  documento, seção "Limitação residual").

### Vantagens

- Corrige dois problemas reais e independentes: resolução insuficiente para um QR versão 40 (a
  carga real de pareamento) e um fallback de rotação que nunca executava (afetava qualquer QR,
  mesmo o trivial de 11 caracteres, em qualquer orientação que não fosse exatamente a entregue pelo
  sensor).
- A lógica de decodificação agora é testável por um teste JVM comum (`QrDecodingTest.kt`), sem
  precisar de emulador/instrumentação para validar mudanças futuras nela.
- O log por frame (debug-only, permanente) torna qualquer investigação futura de scanner
  imediata via `adb logcat`, sem precisar reintroduzir instrumentação.

### Por que a mudança foi feita

T3.3 (continuação desta sessão): diagnosticar com evidência concreta, e corrigir, por que o
scanner de QR do app nunca decodifica — nem o QR real de pareamento, nem um QR trivial de teste —
nos dois emuladores usados para validar o procedimento de dois aparelhos.

### Limitação residual, não corrigida por esta mudança (ver device-verification.md para o status completo)

Mesmo depois dos dois fixes acima, o decode ao vivo do QR real de pareamento pela tela de scan do
app **não** teve sucesso no emulador nesta sessão: 668 tentativas de decode em ~25 s, nenhum
sucesso, sem travar (o app permaneceu responsivo, continuou tentando normalmente, sem erro/crash).
A causa apurada é uma limitação de detecção do ZXing Java clássico (usado pelo app, exigido pelo
escopo desta tarefa) diante da distorção de perspectiva residual da câmera virtual do emulador —
não corrigível por rotação/inversão/resolução, e provavelmente não representativa de uma câmera
real de telefone (onde o usuário enquadra o QR de frente, sem essa distorção fixa). Ver
`docs/development/device-verification.md` para a análise completa, incluindo a verificação cruzada
com `zxing-cpp` que descarta oclusão/framing/contraste como causas.

---

## 2026-09-15 — T3.3 run4: pontos de instrumentação debug-only para o relay de QR entre emuladores

Contexto: a limitação residual documentada acima (a câmera virtual do emulador não decodifica o QR
real) bloqueava T3.3 por completo. Em vez de continuar tentando consertar o detector, esta rodada
adicionou um caminho alternativo que **não passa pela câmera** e mantém todo o resto do fluxo real.
Ver `docs/changes/QrScannerHooks.kt.md`, `docs/changes/DebugQrReceiver.kt.md` e
`docs/changes/emulator-pair.sh.md`.

### Como era antes

Nenhuma das duas telas expunha o payload que estava exibindo nem aceitava um payload que não viesse
do decodificador da câmera:

```kotlin
@Composable
private fun PairingQr(payload: String) {
    var bitmap by remember(payload) { mutableStateOf<Bitmap?>(null) }
    ...
}

@Composable
private fun QrScanner(modifier: Modifier = Modifier, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { ... }
    ...
}
```

### Como ficou

Dois `DisposableEffect`, um em cada composable, registrando callbacks em `QrScannerHooks`:

```kotlin
// PairingQr(payload)
DisposableEffect(payload) {
    val provider: () -> ByteArray? = { payload.toByteArray(Charsets.ISO_8859_1) }
    QrScannerHooks.shownPayload = provider
    onDispose { if (QrScannerHooks.shownPayload === provider) QrScannerHooks.shownPayload = null }
}

// QrScanner(modifier, onScanned)
val currentOnScanned by rememberUpdatedState(onScanned)
DisposableEffect(Unit) {
    val sink: (ByteArray) -> Unit = { bytes -> currentOnScanned(String(bytes, Charsets.ISO_8859_1)) }
    QrScannerHooks.scanSink = sink
    onDispose { if (QrScannerHooks.scanSink === sink) QrScannerHooks.scanSink = null }
}
```

### Decisões de projeto (e as armadilhas de Compose que elas evitam)

1. **Um único ponto cobre as três etapas.** `PairingQr` é o único composable que renderiza QR no
   app e é reusado em **todos** os pontos do fluxo: QR de oferta, QR de resposta e QR de
   confirmação (o ramo `pairing.completed` chama o mesmo `PairingQr(pairing.offer)`). Um registro
   só, portanto, cobre "mostrar QR", "resposta" e "confirmação" — confirmado na prática, já que o
   relay leu com sucesso payloads de 2708 B (oferta), 2750 B (resposta) e 314 B (confirmação).

2. **`rememberUpdatedState` + `DisposableEffect(Unit)` no scanner, e não `DisposableEffect(onScanned)`.**
   `onScanned` é uma lambda recriada a cada recomposição do chamador; usá-la como chave
   re-registraria o sink sem necessidade a cada recomposição. Com `rememberUpdatedState`, o sink é
   registrado uma única vez por instância da tela e mesmo assim sempre chama a lambda **mais
   recente**.

3. **Limpeza com checagem de identidade (`===`) no `onDispose`.** Quando a tela troca de um QR para
   outro, a ordem do Compose (dispatch de `leaving` antes de `entering`) já faz a coisa certa. A
   checagem existe para que, se essa ordem mudar em alguma versão futura, o `onDispose` de uma tela
   antiga **não apague o registro de uma tela nova** — um bug que se manifestaria como "o relay
   funciona às vezes", o pior tipo de defeito para depurar.

4. **O registro do sink fica ANTES da checagem de permissão de câmera**, de propósito. O relay entre
   emuladores não depende da câmera; exigir a permissão concedida só para poder injetar adicionaria
   um passo de automação sem nenhum ganho de segurança (o hook já é debug-only e guardado por UID
   do remetente + propriedade de opt-in — ver `docs/changes/DebugQrReceiver.kt.md`).

5. **ISO-8859-1 nos dois sentidos.** É o charset que o ZXing usa por padrão tanto em
   `QRCodeWriter().encode` (usado por `makeQr`, sem `EncodeHintType.CHARACTER_SET`) quanto no
   `Result.text` que o decodificador produz. Para o payload ASCII `nomessages:1:...` o round-trip
   `payload -> bytes -> payload` é exato — e isso não ficou na base da fé: o script de relay compara
   o `sha256` calculado no aparelho de origem com o `sha256` do conteúdo decodificado no host antes
   de injetar.

### Em release, isto é inerte

Nada em `src/main` **lê ou invoca** esses dois campos; só os registra. O único invocador do projeto
é `DebugQrReceiver`, que não existe em release. Prova por `grep` no código-fonte e no manifesto
mesclado de release em
`docs/development/build-logs/two-emulator-20260915/run3/60-release-manifest-proof.txt`.

### Vantagem medida

Com estes dois pontos de instrumentação, o pareamento completo A <-> B — oferta, resposta, SAS
idêntico nos dois lados, confirmação dupla e troca dos QRs de confirmação — foi executado de ponta
a ponta em **57 segundos**, sem operador humano e sem usar a câmera. Antes desta mudança, o mesmo
fluxo era impossível de completar nos emuladores.

---

## 2026-09-16 — Campo de apelido local com teclado privado (T4.9)

### Motivo

O campo de apelido local, preenchido durante a confirmação SAS do pareamento, é o único campo de
texto editável em `PairingScreen.kt`. T4.9 pede a mesma proteção de `PrivateInput.kt` em todo campo
editável do app.

### Como era antes

```kotlin
OutlinedTextField(
    value = alias,
    onValueChange = { alias = it },
    label = { Text(stringResource(R.string.local_alias)) },
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
        label = { Text(stringResource(R.string.local_alias)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = privateKeyboardOptions(),
    )
}
```

### Vantagens

- O apelido de um contato recém-pareado deixa de alimentar o dicionário de aprendizado do teclado
  do sistema, consistente com o mesmo tratamento dado ao apelido em `ContactsScreens.kt`.

### Por que a mudança foi feita

T4.9, item 1.

---

## 2026-09-17 — Nova paleta "Grafite e Âmbar": três pontos de chamada trocam cor hardcoded por `MaterialTheme.colorScheme` (T4.14)

### 1. Texto "aguardando confirmação"

```kotlin
// antes
Text(stringResource(R.string.waiting_confirmation_qr), color = WfDeepGreen, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

// depois
Text(stringResource(R.string.waiting_confirmation_qr), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
```

A constante real era `WfDeepGreen`, mapeada para `secondary` (não `primary`) — é um texto de
ênfase dentro do fluxo de QR, que já usa tom âmbar em outros dois pontos deste mesmo arquivo (itens
2 e 3 abaixo); manter os três no mesmo papel de tema mantém a moldura de cor do fluxo de pareamento
consistente.

### 2. Ícone de permissão de câmera

```kotlin
// antes
Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(56.dp), tint = WfGreen)

// depois
Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
```

### 3. Borda do quadro de escaneamento de QR

```kotlin
// antes
Modifier.align(Alignment.Center).fillMaxWidth(0.72f).aspectRatio(1f).border(3.dp, WfAccent, RoundedCornerShape(18.dp)),

// depois
Modifier.align(Alignment.Center).fillMaxWidth(0.72f).aspectRatio(1f).border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(18.dp)),
```

### Vantagens

- Os três elementos de ênfase do fluxo de pareamento (texto de espera, ícone de permissão, moldura
  de escaneamento) passam a compartilhar o mesmo papel de tema (`secondary`), reagindo a
  claro/escuro juntos em vez de depender de duas constantes (`WfDeepGreen`/`WfAccent`) que nunca
  tiveram relação formal entre si.

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" em vez da identidade verde-WhatsApp.

---

## 2026-09-17 — QR formato 2: busca do pacote de chaves pela rede Tor, expiração e regeneração automáticas (T4.16)

Contexto completo (motivo do formato 2, deadlines, fluxo de busca): ver
`docs/changes/PairingLifecycle.kt.md` e `docs/changes/UiContract.kt.md`. Toda regra de deadline foi
extraída para o objeto puro `PairingLifecycle` — esta seção documenta só o lado da tela: o que ela
tica, o que ela desenha e para quem ela delega cada decisão.

### 1. O `LaunchedEffect` que tica e despacha — nenhuma regra de tempo própria

**Como era antes.** A tela não tinha nenhum efeito de auto-atualização: uma oferta expirada
simplesmente ficava parada, sem QR válido, até o usuário sair e voltar manualmente.

**Como ficou**

```kotlin
var refreshed by remember { mutableStateOf(false) }
LaunchedEffect(state.pairing?.expiresAt, state.pairing?.sas, state.pairing?.completed) {
    while (true) {
        when (PairingLifecycle.nextAction(state.pairing, screenOpen = true, nowMillis = System.currentTimeMillis())) {
            PairingQrAction.REGENERATE -> { refreshed = true; actions.showPairing(); return@LaunchedEffect }
            PairingQrAction.CANCEL -> { actions.cancelPairing(); return@LaunchedEffect }
            PairingQrAction.NONE -> delay(1_000)
        }
    }
}
```

O efeito tica uma vez por segundo e, a cada tick, pergunta a `PairingLifecycle.nextAction` o que
fazer — ele mesmo não decide **quando** uma oferta expira nem **se** uma troca estagiada deve ser
cancelada ou regenerada; ele só chama `actions.showPairing()` (nova oferta) ou
`actions.cancelPairing()` (descarta) quando mandado, e para de ticar (`return@LaunchedEffect`)
assim que um dos dois acontece — a próxima chave do efeito (`expiresAt`/`sas`/`completed` mudando)
o reinicia para o novo estado. `screenOpen = true` é passado explicitamente porque, enquanto este
efeito está rodando, a tela de pareamento está, por definição, aberta; o caso `!screenOpen` de
`nextAction` (que produz `CANCEL`) é para quando o `LaunchedEffect` é **descartado** (o usuário sai
da tela) — a chave `Unit` implícita de escopo do composable garante isso via `actions.cancelPairing()`
no ponto de saída existente da tela, não por este efeito.

### 2. O aviso `qr_refreshed`

```kotlin
if (refreshed && pairing.sas == null) {
    Text(
        stringResource(R.string.qr_refreshed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = TextAlign.Center,
    )
}
```

`refreshed` é local à tela (não vem de `UiState`): fica `true` assim que o `LaunchedEffect` acima
regenera uma oferta pelo menos uma vez nesta sessão da tela, e a condição `pairing.sas == null`
garante que o aviso só aparece enquanto a oferta atual (a que acabou de ser gerada) ainda não foi
escaneada — assim que alguém escaneia e uma troca é estagiada (`sas` deixa de ser nulo), o aviso
some sozinho sem precisar de um `LaunchedEffect` dedicado a escondê-lo. Ver
`docs/changes/strings.xml.md` para o texto exato.

### 3. `BundleFetchStatus`: cartão novo de progresso da busca pelo pacote de chaves

```kotlin
@Composable
private fun BundleFetchStatus(pairing: PairingUi, actions: UiActions) {
    if (pairing.sas == null || pairing.completed) return
    val message = when (pairing.bundleStatus) {
        PairingBundleStatus.NONE -> return
        PairingBundleStatus.WAITING_FOR_TOR -> R.string.pairing_bundle_waiting_tor
        PairingBundleStatus.FETCHING -> R.string.pairing_bundle_fetching
        PairingBundleStatus.READY -> R.string.pairing_bundle_ready
        PairingBundleStatus.FAILED -> R.string.pairing_bundle_failed
    }
    val color = when (pairing.bundleStatus) {
        PairingBundleStatus.FAILED -> MaterialTheme.colorScheme.error
        PairingBundleStatus.READY -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Column(...) {
            val active = pairing.bundleStatus == PairingBundleStatus.WAITING_FOR_TOR ||
                pairing.bundleStatus == PairingBundleStatus.FETCHING
            if (active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(message), color = color, textAlign = TextAlign.Center)
            val retryable by produceState(initialValue = false, pairing.bundleStatus, pairing.expiresAt) {
                while (true) {
                    value = PairingLifecycle.canRetryBundleFetch(pairing, System.currentTimeMillis())
                    delay(1_000)
                }
            }
            if (retryable) {
                OutlinedButton(onClick = actions::retryPairingBundle, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pairing_bundle_retry))
                }
            }
        }
    }
}
```

Decisões de UI, uma por uma:

- **Nada é desenhado antes de uma troca estar estagiada** (`pairing.sas == null` retorna cedo):
  enquanto só o QR de oferta local está na tela, não existe par nenhum para buscar um pacote de
  chaves — o cartão apareceria vazio, e mostrar um cartão "buscando..." nesse ponto confundiria o
  usuário sobre o que a tela está esperando.
- **`NONE` também não desenha nada** (`return` no `when`), pelo mesmo motivo — é o valor padrão de
  `PairingUi.bundleStatus` antes do controlador publicar o primeiro estado real da busca.
- **Cor por status**, não uma cor fixa: `error` para `FAILED` (a única cor de alerta real da tela),
  `secondary` para `READY` (mesmo papel de tema usado nos outros pontos de ênfase do fluxo de
  pareamento, ver a seção T4.14 acima), e `onSurfaceVariant` (neutro) para os dois estados de
  progresso — um usuário que olha rapidamente para a tela já sabe, pela cor, se precisa agir.
- **Indicador de progresso só nos dois estados "ainda rodando"** (`WAITING_FOR_TOR`/`FETCHING`):
  `READY` e `FAILED` são estados terminais de uma tentativa e não têm mais nada "em andamento" para
  mostrar.
- **O botão de tentar de novo é recalculado a cada segundo via `produceState`**, chamando
  `PairingLifecycle.canRetryBundleFetch` — não uma checagem única no momento da composição — porque
  a resposta muda sozinha com o tempo: uma tentativa `FAILED` com tempo de sobra vira "sem botão"
  no exato instante em que a troca expira, mesmo sem nenhum outro estado mudar. `actions::retryPairingBundle`
  é a nova ação declarada em `UiContract.kt` (ver `docs/changes/UiContract.kt.md`).

### 4. O botão de confirmar o SAS também gated em `PairingLifecycle.canConfirmSas`

**Como era antes**

```kotlin
enabled = alias.isNotBlank(),
```

**Como ficou**

```kotlin
// Also gated on the peer's key bundle having arrived and matched the signed hash: confirming
// before that would only fail later, at `finish`, with both QRs already exchanged.
enabled = alias.isNotBlank() && PairingLifecycle.canConfirmSas(pairing),
```

Sem essa segunda condição, o usuário podia digitar um apelido e confirmar o SAS antes do pacote de
chaves do outro aparelho ter chegado — o pareamento pareceria concluído (os dois QRs de confirmação
trocados) e só falharia depois, dentro de `finish()`, ao tentar `identity.sessions.establish` sem o
pacote. Com a gate, o botão simplesmente não fica clicável enquanto `bundleStatus != READY`, e o
cartão `BundleFetchStatus` acima mostra por quê.

### 5. `ExpiryLabel`/`remainingMillis` passam a delegar para a máquina de estados

**Como era antes**

```kotlin
val label = if (remaining <= 0) stringResource(R.string.expired) else "%02d:%02d".format(remaining / 60_000, (remaining / 1_000) % 60)
...
private fun remainingMillis(expiresAt: Long): Long {
    val expiryMillis = if (expiresAt < 10_000_000_000L) expiresAt * 1_000 else expiresAt
    return (expiryMillis - System.currentTimeMillis()).coerceAtLeast(0)
}
```

**Como ficou**

```kotlin
val label = PairingLifecycle.countdownLabel(remaining) ?: stringResource(R.string.expired)
...
// Delegates to the pure state machine so the countdown on screen and the refresh decision can
// never disagree about when a QR is expired, including about the seconds-vs-milliseconds promotion.
private fun remainingMillis(expiresAt: Long): Long =
    PairingLifecycle.remainingMillis(expiresAt, System.currentTimeMillis())
```

A formatação `mm:ss` e a promoção segundos→milissegundos eram, antes, uma **segunda cópia** da
mesma lógica que `nextAction` também precisava (implicitamente, dentro do antigo efeito de tick que
não existia ainda quando isso foi escrito pela primeira vez) — duas implementações do mesmo cálculo
são exatamente o tipo de duplicação que diverge silenciosamente quando só uma das duas é corrigida.
Agora as duas telas de informação (a contagem regressiva visível e a decisão de regenerar/cancelar)
leem o mesmo `PairingLifecycle.remainingMillis`, então não há como o número na tela dizer "00:03"
enquanto a lógica de fundo já decidiu que o QR expirou, nem o inverso.

### Vantagens

- A tela concentra zero regra de deadline própria: tudo o que envolve "quando" vem de
  `PairingLifecycle`, testado por dez casos JVM (`docs/changes/PairingLifecycleTest.kt.md`) sem
  precisar de emulador.
- Uma oferta que ninguém escaneia deixa de virar um QR morto — a tela se regenera sozinha e avisa
  (`qr_refreshed`) em vez de exigir que o usuário perceba e saia/volte manualmente.
- O usuário aprende que o outro aparelho está inalcançável **antes** de terminar de confirmar o
  código de verificação, com o botão de tentar de novo ainda visível — não depois, com os dois QRs
  de confirmação já trocados.

### Validação (2026-09-17)

`gradle-wsl.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
→ `BUILD SUCCESSFUL in 8m 35s`. `:core:test` — 95 testes, 0 falhas, 2 pulados; `:app:testDebugUnitTest`
— 60 testes, 0 falhas, 1 pulado; `:app:lintDebug` — 0 erros (42 avisos, 6 hints, mesmo baseline).
**Não executado**: qualquer validação em emulador/aparelho físico — não faz parte desta tarefa.

---

## 2026-09-17 — Passo de polimento de UX/UI sobre o T4.16 acima (mesmo dia, agente diferente)

Contexto: revisão focada de UX pedida especificamente sobre o fluxo de QR formato 2 que a seção
anterior implementou — sem redesenhar nada, sem novas funcionalidades. Cinco pontos foram avaliados:
legibilidade da contagem regressiva, se a regeneração automática lê como esperada, o estado de
busca do pacote de chaves, o estado de erro/retry (comparado ao padrão visual já usado em
`NetworkBanner`/`GroupStatusBanner` de `Components.kt`/`ChatScreen.kt`), e a qualidade do texto
PT/EN. Ver `docs/changes/PairingLifecycle.kt.md` (mesma data) para a única mudança que saiu deste
arquivo, e `docs/changes/strings.xml.md` para o texto novo.

### 1. A contagem regressiva ganha um estado de aviso âmbar antes do vermelho

**Como era antes**

```kotlin
val label = PairingLifecycle.countdownLabel(remaining) ?: stringResource(R.string.expired)
Text(stringResource(R.string.qr_expires, label), style = MaterialTheme.typography.labelLarge, color = if (remaining <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
```

Só duas cores: neutra até o último milissegundo, depois um pulo direto para vermelho de erro no
instante exato em que `nextAction` está prestes a regenerar o QR (se `sas == null`) ou cancelar a
troca (se já estiver estagiada). Um pulo repentino de cor bem no momento em que algo automático e
esperado está para acontecer lê como alarme ("quebrou algo"), não como o fim natural de uma janela
de tempo.

**Como ficou**

```kotlin
val color = when {
    remaining <= 0 -> MaterialTheme.colorScheme.error
    PairingLifecycle.isExpiringSoon(remaining) -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
Text(stringResource(R.string.qr_expires, label), style = MaterialTheme.typography.labelLarge, color = color)
```

Nos últimos 15 s (`PairingLifecycle.isExpiringSoon`, nova função — ver
`docs/changes/PairingLifecycle.kt.md`), a contagem passa para `secondary`, o mesmo âmbar já usado
como cor de ênfase em outros três pontos desta mesma tela (texto "aguardando confirmação", ícone de
permissão de câmera, moldura do scanner — ver a seção "Grafite e Âmbar" acima). Só vira vermelho de
fato quando `remaining <= 0`. O mesmo componente é reusado tanto para a janela de 120 s (oferta não
escaneada) quanto para a de 300 s (troca estagiada), então os dois ganham o aviso automaticamente.

### 2. `PairingQr` ganha um `Crossfade` real entre o QR antigo e o novo

**Como era antes**

`bitmap`/`finished` eram `remember(payload)` direto no corpo de `PairingQr`, e o `if (!finished) ...
else if (bitmap == null) ... else Image(...)` desenhava direto — quando o `payload` mudava (troca de
oferta, seja por regeneração automática ou por avançar de etapa), a imagem antiga desaparecia e o
spinner aparecia no mesmo frame, sem nenhuma transição.

**Como ficou**

```kotlin
Crossfade(targetState = payload, label = "pairing_qr") { framePayload ->
    var bitmap by remember(framePayload) { mutableStateOf<Bitmap?>(null) }
    var finished by remember(framePayload) { mutableStateOf(false) }
    // ... o mesmo LaunchedEffect de geração de antes, agora keyed em framePayload ...
    if (!finished) CircularProgressIndicator()
    else if (bitmap == null) Text(stringResource(R.string.qr_render_error), ...)
    else Image(...)
}
```

O detalhe que importa aqui: `bitmap`/`finished` (e o `LaunchedEffect` que gera o bitmap) foram
movidos para **dentro** do lambda de conteúdo do `Crossfade`, remembered no parâmetro que o
`Crossfade` está de fato animando (`framePayload`), e não no parâmetro externo `payload` da função.
Uma primeira tentativa manteve esse estado fora do `Crossfade` — o resultado seria os dois ramos
(saindo/entrando) da animação lendo o mesmo `MutableState` (já apontando para o novo `payload`),
então não haveria nada de fato diferente para cruzar-fade entre si. Com o estado remembered por
`framePayload`, o ramo que está saindo continua mostrando seu próprio QR já pronto enquanto o novo
começa do zero (spinner), e os dois se cruzam de fato.

O `DisposableEffect(payload)` do hook de instrumentação debug-only (`QrScannerHooks.shownPayload`)
continua keyed no `payload` externo, sem mudança — ele só publica bytes para o relay entre
emuladores, nada visual.

### 3. `qr_refreshed` sem mudança de código, mas ver a nota de string

Esse aviso já existia e já tinha o comportamento certo (aparece quando `refreshed && pairing.sas ==
null`, some sozinho ao ser escaneado). Só o texto em inglês foi ajustado — ver
`docs/changes/strings.xml.md`.

### 4. `BundleFetchStatus`: cartão de falha agora usa `errorContainer`, mais uma dica de "não travou", mais um texto de saída quando não há mais retry

**Como era antes**

```kotlin
val color = when (pairing.bundleStatus) {
    PairingBundleStatus.FAILED -> MaterialTheme.colorScheme.error
    PairingBundleStatus.READY -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
Card(Modifier.fillMaxWidth()) {
    Column(...) {
        if (active) LinearProgressIndicator(...)
        Text(stringResource(message), color = color, textAlign = TextAlign.Center)
        val retryable by produceState(...) { ... }
        if (retryable) OutlinedButton(onClick = actions::retryPairingBundle, ...) { ... }
    }
}
```

Falha só tingia o **texto** de vermelho num `Card` de fundo neutro — inconsistente com o resto do
app, onde todo estado de erro/atenção (`NetworkBanner`, `GroupStatusBanner`, o banner de gravação em
`ChatScreen.kt`) usa uma superfície tingida (`colorScheme.errorContainer`/`onErrorContainer`), não
só texto colorido num fundo padrão. Além disso, quando `FAILED` e o prazo já não permitia mais uma
tentativa (`canRetryBundleFetch` retornando `false`), o cartão ficava sem nenhuma ação nem
explicação — um beco sem saída silencioso.

**Como ficou**

```kotlin
val failed = pairing.bundleStatus == PairingBundleStatus.FAILED
val containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
val color = when (pairing.bundleStatus) {
    PairingBundleStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    PairingBundleStatus.READY -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
    Column(...) {
        if (active) LinearProgressIndicator(...)
        Text(stringResource(message), color = color, textAlign = TextAlign.Center)
        if (pairing.bundleStatus == PairingBundleStatus.FETCHING) {
            Text(stringResource(R.string.pairing_bundle_fetching_hint), style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center)
        }
        val retryable by produceState(...) { ... }
        if (retryable) {
            OutlinedButton(onClick = actions::retryPairingBundle, ...) { ... }
        } else if (failed) {
            Text(stringResource(R.string.pairing_bundle_failed_no_retry), style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center)
        }
    }
}
```

Três mudanças distintas aqui:

1. **Cor da superfície, não só do texto, para `FAILED`** — agora usa exatamente o par
   `errorContainer`/`onErrorContainer` que `NetworkBanner`/`GroupStatusBanner` já usam, em vez de
   inventar um segundo jeito de mostrar erro dentro do mesmo app. `WAITING_FOR_TOR`/`FETCHING`/`READY`
   continuam num `Card` neutro — não são estados de erro, não deveriam parecer um.
2. **Dica de duração só durante `FETCHING`** (`pairing_bundle_fetching_hint`, nova string) — a busca
   real pode legitimamente levar até ~40 s (o motor tenta de novo sozinho até a troca expirar, ver
   `startBundleFetch` em `NoMessagesController.kt`); um spinner indeterminado sem nenhuma expectativa
   de tempo lê como travado bem antes disso. Não aparece em `WAITING_FOR_TOR` de propósito: esse é um
   problema diferente ("minha própria rede Tor ainda não subiu"), já comunicado pela mensagem
   principal, e normalmente breve.
3. **Texto de saída quando não há mais retry** (`pairing_bundle_failed_no_retry`, nova string) — só
   aparece quando `failed && !retryable`, ou seja, nos últimos instantes antes do prazo de 300 s
   acabar. Não é um botão (não há mais nada a fazer localmente), só explica que o tempo está
   acabando; o `LaunchedEffect` no topo da tela cancela a troca segundos depois e mostra o banner de
   timeout (ver item 5).

### 5. Novo: banner de timeout quando uma troca estagiada expira sozinha (`timedOut`)

**O problema encontrado.** `PairingLifecycle.nextAction` já distinguia `CANCEL` por timeout de
`CANCEL` por saída de tela (via `screenOpen`), mas o `LaunchedEffect` que consome essa decisão
simplesmente chamava `actions.cancelPairing()` e retornava — sem deixar rastro nenhum. Como esse
efeito só roda com `screenOpen = true` (a tela está, por definição, aberta enquanto ele roda), todo
`CANCEL` que ele recebe é necessariamente por **timeout**, nunca por navegação — mas nada na tela
comunicava isso ao usuário: `pairing` virava `null` e a tela simplesmente voltava para o cartão
"comece o pareamento", como se nada tivesse acontecido. Alguém no meio de uma troca (QRs já
escaneados, código SAS já comparado) veria tudo sumir sem explicação.

**Como ficou**

```kotlin
var timedOut by remember { mutableStateOf(false) }
LaunchedEffect(state.pairing?.expiresAt, state.pairing?.sas, state.pairing?.completed) {
    while (true) {
        when (PairingLifecycle.nextAction(state.pairing, screenOpen = true, nowMillis = System.currentTimeMillis())) {
            PairingQrAction.REGENERATE -> { refreshed = true; actions.showPairing(); return@LaunchedEffect }
            PairingQrAction.CANCEL -> { timedOut = true; actions.cancelPairing(); return@LaunchedEffect }
            PairingQrAction.NONE -> delay(1_000)
        }
    }
}
...
if (pairing == null) {
    if (timedOut) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(stringResource(R.string.pairing_timed_out), modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, textAlign = TextAlign.Center)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(...) {
            Button(onClick = { refreshed = false; timedOut = false; actions.showPairing() }, ...) { ... }
            ...
        }
    }
}
```

`timedOut` é local à tela, do mesmo jeito que `refreshed` já era — nenhum campo novo em `PairingUi`
foi necessário (ver a próxima seção sobre por que isso não é um "jeitinho de UI" escondendo uma
lacuna real). Some assim que o usuário aperta "Mostrar meu QR" de novo (reset explícito no
`onClick`, junto com `refreshed`), então não sobrevive a uma nova tentativa manual.

### Este arquivo NÃO precisou de nenhum campo novo em `UiState`/`PairingUi`/`UiActions`

A tarefa pedia para adicionar o mínimo de estado na máquina de estados (`PairingLifecycle`) só se a
UI realmente precisasse — e avisar claramente se isso acontecesse. Verificação explícita, dois
casos considerados:

- **Um estado "regenerando" em `PairingBundleStatus`/`PairingQrAction`**: não foi necessário. Do
  ponto de vista da UI, a regeneração é síncrona — `actions.showPairing()` substitui a oferta no
  mesmo ciclo de estado, sem uma fase intermediária observável pelo controlador. A transição visual
  (item 2 acima) e o aviso textual (`qr_refreshed`, já existente) resolvem inteiramente do lado da
  tela, com um `Crossfade` e uma flag local.
- **Um sinal de "cancelado por timeout" separado de "cancelado pelo usuário"**: também não foi
  necessário mudar `PairingLifecycle`. `nextAction` já expõe essa distinção implicitamente — quem
  chama sabendo que `screenOpen = true` sabe, só por receber `CANCEL`, que só pode ser por deadline.
  A tela já tinha essa informação disponível no ponto de chamada; só faltava guardá-la (`timedOut`)
  e mostrá-la.

Ou seja: `PairingLifecycle.kt` só ganhou uma função nova e puramente cosmética
(`isExpiringSoon`/`EXPIRY_WARNING_MILLIS`, ver `docs/changes/PairingLifecycle.kt.md`) — nada em
`UiContract.kt` mudou nesta rodada (ver `docs/changes/UiContract.kt.md`).

### O que foi deixado como estava, e por quê

- **O botão "Cancelar" (`onBack`) e o fluxo de saída manual da tela** — já navegam para
  `Destination.CONTACTS` e chamam `cancelPairing()` explicitamente a partir de
  `NoMessagesApp.kt`; o usuário sabe que cancelou porque ele mesmo apertou o botão. Nenhuma mudança.
- **O card do SAS (`sas_title`, `sas_instruction`, campo de apelido)** — fora do escopo pedido
  (contagem, busca do pacote, erro/retry, contraste, texto), e já segue o padrão de teclado privado
  de T4.9. Não tocado.
- **`WAITING_FOR_TOR` sem dica de duração própria** — decisão deliberada (ver item 4.2): é
  tipicamente breve e já tem sua própria mensagem específica; adicionar uma segunda linha de texto
  ali só duplicaria informação sem ajudar.
- **`NetworkBanner`/`GroupStatusBanner` em si (`Components.kt`/`ChatScreen.kt`)** — usados como
  referência para o padrão visual de erro reaproveitado aqui, mas não alterados: já estavam
  corretos e fora do escopo desta tela.

### Vantagens

- A contagem regressiva e o cartão de falha agora falam a mesma língua visual do resto do app
  (âmbar de ênfase, `errorContainer` de erro) em vez de inventar uma paleta paralela só para esta
  tela.
- Uma troca que expira sozinha deixa de parecer um bug silencioso: o usuário vê exatamente por que
  a tela voltou ao início.
- Nenhuma mudança de rede, criptografia ou contrato de estado — só como a informação que já existia
  (`bundleStatus`, `expiresAt`, o resultado de `nextAction`) é mostrada.

### Validação (2026-09-17, rodada de UX)

`gradle-wsl.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` →
`BUILD SUCCESSFUL`. `:app:testDebugUnitTest` — **61 testes, 0 falhas, 0 erros, 1 pulado**
(pré-existente, não relacionado a esta mudança); `PairingLifecycleTest` isoladamente — 11 testes, 0
falhas (10 anteriores + `isExpiringSoon`, novo). `:app:lintDebug` — `BUILD SUCCESSFUL`, 42 avisos
(mesmo baseline, nenhum novo introduzido por este arquivo). `:app:assembleDebug` — sucesso.
**Não executado**: qualquer validação em emulador/aparelho físico.

---

## 2026-09-17 (run5, validação em emulador) — a contagem regressiva travava em "expirado" depois da primeira expiração

### Como era

```kotlin
@Composable
private fun ExpiryLabel(expiresAt: Long) {
    val remaining by produceState(initialValue = remainingMillis(expiresAt), expiresAt) {
        while (value > 0) {
            delay(1_000)
            value = remainingMillis(expiresAt)
        }
    }
    val label = PairingLifecycle.countdownLabel(remaining) ?: stringResource(R.string.expired)
```

### Como é

```kotlin
    val remaining by produceState(initialValue = remainingMillis(expiresAt), expiresAt) {
        // (comentário completo no arquivo, com a medição que isolou o defeito)
        value = remainingMillis(expiresAt)
        while (value > 0) {
            delay(1_000)
            value = remainingMillis(expiresAt)
        }
    }
```

### Por que a mudança foi feita

`produceState` avalia `initialValue` **uma única vez**, quando o `ExpiryLabel` entra em composição.
Quando a chave (`expiresAt`) muda, ele **relança o bloco produtor mas preserva o `value` atual** —
não volta ao `initialValue`. Então:

1. o QR chega ao fim da janela de 120 s e `value` cai para 0;
2. `PairingLifecycle.nextAction` devolve `REGENERATE`, `actions.showPairing()` publica uma oferta
   nova com um `expiresAt` novo, e a chave do `produceState` muda;
3. o bloco é relançado, encontra `value == 0`, e `while (value > 0)` **nem entra**;
4. `countdownLabel(0)` devolve `null` e o rótulo vira `"Expires in expired"` — para sempre.

A oferta em si estava perfeitamente viva. Prova em aparelho (`emulator-5556`, 21:42:36 UTC): o QR
exibido foi capturado pelo hook `DUMP_QR` e decodificado no host, e o campo `created` dava
`2026-09-17 21:42:50Z`, **15 s de idade**, sob um rótulo que dizia "expirado"
(`docs/development/build-logs/pairing-v2-20260917/session-log.md`, seção 21:42:36).

Nada disso é alcançável por teste de host: `PairingLifecycle` é uma máquina de estados pura, está
coberto por `PairingLifecycleTest` (10 casos) e **passa** — o defeito mora inteiramente no
acoplamento com o ciclo de vida do `produceState` do Compose.

### Vantagens

- **A regeneração automática volta a comunicar o que faz.** Antes da correção o usuário via
  "expirado" logo abaixo de um QR recém-gerado e perfeitamente escaneável, ao lado da própria
  mensagem "The QR expired and a new one was generated. Ask the other person to scan it." — duas
  afirmações contraditórias na mesma tela. É o oposto exato do propósito da funcionalidade
  introduzida por T4.16.
- **Cosmético no protocolo, grave na usabilidade:** o motor nunca esteve errado, mas a única
  informação que o usuário tem para decidir se vale a pena pedir para a outra pessoa escanear estava
  errada, e de forma pessimista — a pessoa desistiria de um QR válido.
- **A correção torna o produtor idempotente** em relação a quantas vezes já rodou, em vez de depender
  do estado deixado pela execução anterior. É a forma correta de usar `produceState` com uma chave
  que muda; `initialValue` cobre apenas a primeira composição, e qualquer bloco que leia `value`
  antes de escrevê-lo tem a mesma fragilidade.
- Verificado **ao vivo**, não apenas por raciocínio, com o build corrigido instalado por
  `install -r -t` em `emulator-5556`:

```
21:59:57Z  Expires in 00:04
22:00:00Z  Expires in 00:02
22:00:03Z  Expires in 01:59   + "The QR expired and a new one was generated. Ask the other person to scan it."
22:00:16Z  Expires in 01:45
22:00:47Z  Expires in 01:15
```

  Evidência: `docs/development/build-logs/pairing-v2-20260917/15-A-countdown-after-regeneration-fixed.png`
  (depois) e `05-A-offer-expired-label.png` (antes).

### Nota de cobertura

Não foi acrescentado teste automatizado. O comportamento em falta é a semântica de retenção de
estado do `produceState` entre trocas de chave, que só um teste de UI Compose (`createComposeRule` +
relógio virtual) alcançaria; hoje o módulo `app` não tem nenhum teste de composição, e introduzir
essa infraestrutura estava fora do alcance de uma sessão de verificação em aparelho. Fica registrado
aqui como lacuna conhecida.

## 2026-09-17 (3) — um cronômetro, duas leituras, e uma legenda fixa (T4.16)

Acompanha a separação entre exibição e validade feita em `PairingEngine` (ver
`docs/changes/Pairing.kt.md`, seção "2026-09-17 (3)"): até 3 ofertas ficam simultaneamente válidas, e
a rotação do QR na tela deixou de ser um prazo que alguém possa perder.

### Como era antes

```kotlin
ExpiryLabel(pairing.expiresAt)
if (refreshed && pairing.sas == null) {
    Text(stringResource(R.string.qr_refreshed), ...)   // "Peça para escanear o novo."
}
```

Duas coisas erradas depois da mudança de motor: o rótulo dizia sempre "Expira em", inclusive quando
zerar apenas gira o QR; e o aviso transitório mandava **rescanear**, que é justamente o que não é mais
necessário.

### Como ficou

```kotlin
ExpiryLabel(pairing.expiresAt, regenerating = pairing.sas == null)
if (pairing.sas == null) {
    Text(stringResource(R.string.qr_previous_still_valid), ...)
}
```

- **Um cronômetro só**, com duas leituras: `regenerating` troca o texto para "Novo QR em MM:SS" e
  impede o vermelho ao zerar, porque ali chegar a zero não custa nada a ninguém. Montada a troca,
  volta a "Expira em MM:SS" com a progressão neutro → âmbar → vermelho introduzida na passagem
  anterior, **intacta** — só acrescentei as guardas `!regenerating`. Nenhum segundo timer foi criado.
- **Legenda fixa** logo abaixo enquanto houver oferta na tela.
- O estado `refreshed` e a string `qr_refreshed` foram removidos (ver `docs/changes/strings.xml.md`).

As duas leituras se decidem por `pairing.sas == null`, estado que este composable **já** usava para se
organizar: nenhum estado condicional novo foi inventado, e `PairingLifecycle` não foi tocado.

### Regressão

`:core:test` 100 testes / 0 falhas; `:app:testDebugUnitTest` 61 testes / 0 falhas; `:app:lintDebug`
**0 erros** (42 avisos, 6 hints — mesma linha de base, sem `UnusedResources` novo apesar da string
removida, porque ela saiu dos dois locales); os dois APKs compilam.
