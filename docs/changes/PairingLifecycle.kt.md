# PairingLifecycle.kt

## 2026-09-17 — Novo arquivo: máquina de estados pura por trás do pareamento (T4.16)

Contexto completo do QR formato 2 (motivo, tamanhos medidos, fluxo de busca do pacote de chaves
pela rede Tor): ver `docs/changes/Pairing.kt.md` e `docs/changes/SignalSessions.kt.md`. Este
documento cobre especificamente o arquivo novo
`app/src/main/kotlin/dev/mx3/nomessages/ui/PairingLifecycle.kt`, coberto por
`app/src/test/kotlin/dev/mx3/nomessages/ui/PairingLifecycleTest.kt` (ver
`docs/changes/PairingLifecycleTest.kt.md`).

### Como era antes

Não existia. Antes do QR formato 2, a tela de pareamento tinha uma única contagem regressiva de
120 s (a janela em que uma oferta pode ser lida) e nenhuma regenerção automática: a regra de
expiração vivia inteira dentro de `PairingScreen.kt`, numa função privada de módulo:

```kotlin
private fun remainingMillis(expiresAt: Long): Long {
    val expiryMillis = if (expiresAt < 10_000_000_000L) expiresAt * 1_000 else expiresAt
    return (expiryMillis - System.currentTimeMillis()).coerceAtLeast(0)
}
```

e o rótulo `mm:ss` era formatado inline, dentro do `@Composable ExpiryLabel`. Não havia segunda
deadline, não havia estado de busca de pacote de chaves e não havia nenhuma decisão de "regenerar
vs. cancelar" a testar — QR formato 2 introduziu as três coisas de uma vez.

### Por que este arquivo não tem nenhuma dependência de Android

`PairingScreen.kt` é um `@Composable` dirigido por um ticker de um segundo
(`LaunchedEffect(...) { while (true) { ...; delay(1_000) } }`, ver
`docs/changes/PairingScreen.kt.md`). "Quando exatamente um QR é descartado e substituído" é
precisamente o tipo de regra que é **cara de testar através de um composable** (exige
`ComposeTestRule`, um relógio controlado, recomposição) e **barata de testar aqui** (funções puras,
`Long` de entrada, `enum`/valor de saída, sem nenhum framework). `PairingLifecycle` não importa
nada de `android.*` nem de `androidx.compose.*`; a tela só tica e despacha o que este arquivo
decide (ver `docs/changes/PairingScreen.kt.md`).

### As duas deadlines — e por que não podem ser colapsadas em uma só

```kotlin
internal object PairingLifecycle {
    const val OFFER_TTL_MILLIS: Long = 120_000L
    const val PENDING_TTL_MILLIS: Long = 300_000L
    ...
}
```

São coisas diferentes e a confusão entre as duas já foi o defeito original do formato 1 (uma única
janela de 120 s para tudo):

- **`OFFER_TTL_MILLIS` (120 s)** — quanto tempo um QR ainda pode ser **lido**. Espelha
  `PairingEngine.OFFER_TTL_SECONDS` (`core/.../protocol/Pairing.kt`); vale enquanto só a oferta
  deste aparelho está na tela (`pairing.sas == null`).
- **`PENDING_TTL_MILLIS` (300 s)** — quanto tempo uma **troca já em andamento** tem para terminar:
  a busca do pacote de chaves do par pela rede Tor, a comparação falada do SAS e a troca dos dois
  QRs de confirmação. Espelha `PairingEngine.PENDING_TTL_SECONDS`, ancorada na **mais antiga** das
  duas ofertas (não reiniciada a cada etapa). Existe porque o formato 2 acrescentou uma ida e volta
  de rede a um onion recém-publicado — algo que custa rotineiramente 5–40 s e, às vezes, mais — que
  o fluxo do formato 1 nunca precisava pagar, já que o pacote inteiro vinha dentro do próprio QR.

Uma única constante de 120 s para os dois casos deixaria uma troca já em andamento (com o circuito
Tor ainda subindo) ser abortada no meio do caminho por uma janela pensada só para "alguém ainda não
apontou a câmera para este QR" — dois problemas de natureza diferente com o mesmo relógio.

### Como `nextAction` decide qual das duas deadlines está em vigor

`UiState.pairing.expiresAt` carrega **qual das duas** deadlines está valendo a cada momento: o
controlador publica `now + 120 s` enquanto só existe uma oferta local, e o `PairingProgress.expiresAt`
do motor (300 s) assim que uma troca é estagiada. `nextAction` decide qual delas está olhando a
partir de **`PairingUi.sas` ser não-nulo** — um SAS presente significa "a troca já foi estagiada" —
em vez de um segundo campo booleano que poderia divergir do primeiro:

```kotlin
fun nextAction(pairing: PairingUi?, screenOpen: Boolean, nowMillis: Long): PairingQrAction {
    if (pairing == null) return PairingQrAction.NONE
    if (pairing.completed) return PairingQrAction.NONE
    if (!screenOpen) return PairingQrAction.CANCEL
    if (remainingMillis(pairing.expiresAt, nowMillis) > 0) return PairingQrAction.NONE
    return if (pairing.sas == null) PairingQrAction.REGENERATE else PairingQrAction.CANCEL
}
```

Um campo separado (por exemplo `PairingUi.isStaged: Boolean`) exigiria manter dois valores em
sincronia em cada ponto que constrói `PairingUi`; derivar de `sas` torna a discordância
estruturalmente impossível — não há como `sas` estar preenchido e a troca não estar estagiada.

### Cada regra de `nextAction`

| Situação | Ação | Por quê |
|---|---|---|
| `pairing == null` | `NONE` | nada na tela para expirar |
| `pairing.completed` | `NONE` | ver abaixo |
| `!screenOpen` | `CANCEL` | ver abaixo |
| `remainingMillis(...) > 0` | `NONE` | ainda dentro do prazo, seja ele qual for |
| expirado, `sas == null` | `REGENERATE` | oferta sem ninguém ter escaneado |
| expirado, `sas != null` | `CANCEL` | troca estagiada que estourou o prazo |

- **`REGENERATE` — oferta que ninguém escaneou.** Pede uma oferta nova (novo nonce, novo pacote de
  chaves) e a tela permanece aberta, em vez de deixar um QR morto na tela para a outra pessoa
  continuar falhando em ler. `PairingEngine.createOffer()` mantém até uma oferta superada
  respondível pelo resto da sua própria janela de 300 s, então um par que escaneou o QR nos
  últimos segundos ainda consegue terminar a busca depois da tela ter se auto-atualizado (ver
  `docs/changes/Pairing.kt.md`).
- **`CANCEL` de uma troca estagiada expirada — nunca regenerada.** Uma troca em andamento **não
  pode ser reiniciada unilateralmente**: o par já guarda um transcript sobre exatamente este par de
  ofertas (é o transcript que produz o SAS de seis dígitos comparado em voz alta). Uma nova oferta
  local mudaria o transcript deste lado sem que o outro soubesse, deixando os dois aparelhos com
  SAS diferentes — um convite a confirmar um código que já não corresponde ao mesmo par de ofertas.
  A única jogada segura é descartar a troca inteira e recomeçar do zero, dos dois lados.
- **`CANCEL` ao sair da tela.** `!screenOpen` cobre tanto uma oferta parada quanto uma troca
  estagiada: em qualquer um dos dois casos, material de pareamento pendente é queimado em vez de
  deixado meio-estabelecido enquanto o usuário está em outra tela.
- **`NONE` para um pareamento concluído.** Seu QR de confirmação precisa **permanecer na tela**
  para o outro aparelho ainda poder escaneá-lo; `nextAction` verifica `pairing.completed` antes de
  qualquer outra coisa e nunca toca num pareamento já concluído, mesmo que `expiresAt` já tenha
  passado.

### `remainingMillis`: a promoção segundos→milissegundos e o bug real que ela evita

```kotlin
fun remainingMillis(expiresAt: Long, nowMillis: Long): Long {
    val millis = if (expiresAt in 1 until SECONDS_CEILING) expiresAt * 1_000 else expiresAt
    return (millis - nowMillis).coerceAtLeast(0)
}

/** Any epoch value below this is seconds, not milliseconds (it is the year 2286 in seconds). */
private const val SECONDS_CEILING = 10_000_000_000L
```

`expiresAt` chega em **duas unidades diferentes** dependendo de quem o produziu: o controlador
publica `now + 120 s` já em milissegundos para uma oferta solitária, mas
`PairingProgress.expiresAt`, do motor (`core/.../protocol/Pairing.kt`), é epoch **segundos**. Sem a
promoção, um `expiresAt` em segundos (por exemplo `1_758..._000`, dez dígitos) seria subtraído de
`System.currentTimeMillis()` (treze dígitos) e o resultado seria um número **negativo enorme**,
grampeado em zero pelo `coerceAtLeast(0)` — ou seja, a contagem regressiva mostraria "expirado"
imediatamente ao entrar na tela de troca, e `nextAction` cancelaria a troca no primeiro tick,
mesmo com os 300 s inteiros ainda por usar. `SECONDS_CEILING` (ano 2286 em segundos) separa os dois
casos sem precisar de um segundo parâmetro dizendo qual unidade o chamador está passando: qualquer
valor abaixo dele só pode ser segundos, porque um milissegundo desse tamanho seria uma data no
passado distante, não um prazo futuro.

### `countdownLabel`

```kotlin
fun countdownLabel(remainingMillis: Long): String? {
    if (remainingMillis <= 0) return null
    val totalSeconds = remainingMillis / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
```

`mm:ss` para uma contagem viva, ou `null` assim que ela zera — o chamador (`ExpiryLabel`, em
`PairingScreen.kt`) mostra a string localizada de "expirado" nesse caso, então o texto continua
traduzível sem que a lógica de formatação precise conhecer `stringResource`.

### `canRetryBundleFetch`

```kotlin
fun canRetryBundleFetch(pairing: PairingUi?, nowMillis: Long): Boolean {
    if (pairing == null || pairing.completed) return false
    if (pairing.bundleStatus != PairingBundleStatus.FAILED) return false
    return remainingMillis(pairing.expiresAt, nowMillis) > 0
}
```

Só oferece "tentar de novo" para uma troca estagiada cuja busca **de fato falhou** e que **ainda
tem tempo**: tentar de novo depois do prazo gastaria um circuito Tor numa troca que
`PairingEngine` já vai recusar de qualquer forma (ver a regra de `bundleFor(nonce)` em
`docs/changes/Pairing.kt.md`).

### `canConfirmSas`

```kotlin
fun canConfirmSas(pairing: PairingUi?): Boolean =
    pairing != null && !pairing.completed && pairing.sas != null &&
        !pairing.waitingForPeer && pairing.bundleStatus == PairingBundleStatus.READY
```

O botão de confirmação do SAS exige o pacote de chaves do par **antes** de confirmar, em vez de
deixar a falha aparecer só em `finish()`. Assim o usuário descobre que o outro aparelho está
inalcançável **enquanto o botão de tentar de novo ainda está na tela**, e não depois de já ter
trocado os dois QRs de confirmação — o que deixaria os dois lados achando que o pareamento
terminou quando na verdade um deles não tem a sessão Signal estabelecida.

### Vantagens

- Toda regra de expiração/regeneração/cancelamento do pareamento passa a viver num único lugar,
  puro e testável por JVM comum, em vez de espalhada dentro de um `@Composable`.
- `nextAction` deriva "a troca está estagiada?" de um campo que já existia (`sas`), então não há
  como um segundo campo de estado divergir dele.
- `remainingMillis` é a única função em todo o app que converte `expiresAt` para milissegundos —
  `ExpiryLabel` e o `LaunchedEffect` de refresh em `PairingScreen.kt` delegam a ela, então a
  contagem regressiva na tela e a decisão de regenerar/cancelar **nunca podem discordar** sobre
  quando um QR está expirado.
- Coberto por dez casos de teste JVM puro (`PairingLifecycleTest`, ver
  `docs/changes/PairingLifecycleTest.kt.md`), sem precisar de emulador nem de `ComposeTestRule`.

### Validação

`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` →
`BUILD SUCCESSFUL in 8m 35s` (ver `docs/changes/PairingLifecycleTest.kt.md` para a contagem de
testes). Nenhuma validação em aparelho/emulador foi feita nesta tarefa.

---

## 2026-09-17 — Passo de polimento de UX sobre o T4.16: `isExpiringSoon`/`EXPIRY_WARNING_MILLIS`

Contexto: esta é uma rodada de revisão de UX/UI feita **em cima** da seção acima (mesmo dia, mesma
tarefa T4.16, agente diferente). O pedido era revisar a legibilidade da contagem regressiva, o
estado de busca do pacote de chaves e o estado de erro/retry em `PairingScreen.kt` — a maior parte
das mudanças ficou inteiramente na tela (ver `docs/changes/PairingScreen.kt.md`), mas uma delas
tocou este arquivo.

### Motivo

A contagem em `ExpiryLabel` (ver `PairingScreen.kt`) só tinha duas cores: neutra
(`onSurfaceVariant`) enquanto havia tempo, e vermelha (`error`) assim que `remaining <= 0`. Isso
produz uma transição abrupta bem no instante em que `nextAction` está prestes a regenerar o QR ou
cancelar a troca — o pulo repentino de cor lia como um alarme (algo quebrou) mais do que como o
fim esperado e automático de uma janela de tempo.

### Como ficou

```kotlin
/** Below this many milliseconds left, the countdown switches to its "running out" warning color
 *  instead of waiting for the hard cut to zero - a sudden jump straight from neutral to error reads
 *  as more alarming than a brief early warning. */
const val EXPIRY_WARNING_MILLIS: Long = 15_000L

/** Whether the countdown should render in its warning color: some time is left, but not much. */
fun isExpiringSoon(remainingMillis: Long): Boolean = remainingMillis in 1..EXPIRY_WARNING_MILLIS
```

`ExpiryLabel` agora consulta esta função para decidir entre três cores (neutra → âmbar
`secondary` → vermelha `error`) em vez de duas — ver `docs/changes/PairingScreen.kt.md` para o
`when` completo. A constante e a regra ficam aqui, e não direto na tela, pelo mesmo motivo que todo
o resto deste arquivo existe: é uma decisão de "quando", testável sem depender de um
`@Composable`, e mantém a promessa do KDoc do topo do arquivo ("toda regra de deadline vive
aqui").

### Por que não foi preciso mexer em mais nada neste arquivo

A revisão considerou explicitamente se o pedido do usuário ("um estado 'regenerando' distinto")
exigia um novo valor em `PairingQrAction` ou em `PairingBundleStatus`. Não exigiu: a regeneração já
é síncrona do ponto de vista da UI (uma nova oferta chega no mesmo ciclo de estado que substitui a
anterior — `actions.showPairing()` não tem uma fase intermediária "gerando" observável pelo
controlador). A transição visual pedida foi resolvida inteiramente do lado da tela com um
`Crossfade` local e uma flag de tela já existente (`refreshed`) — ver
`docs/changes/PairingScreen.kt.md`. Nenhum novo campo de estado do motor de pareamento era
necessário para isso.

Da mesma forma, o silêncio de `cancelPairing()` ao cancelar por timeout (a tela simplesmente volta
para "comece o pareamento" sem nenhuma explicação) foi resolvido com uma flag **local à tela**
(`timedOut`), não com um novo caso em `PairingQrAction`: o chamador (`PairingScreen`'s
`LaunchedEffect`) já sabe, pelo próprio fato de estar chamando `nextAction` com `screenOpen = true`,
que qualquer `CANCEL` que ele recebe só pode ser por timeout — a distinção que a tela precisava já
estava disponível no ponto de chamada, sem precisar de um sinal novo vindo de `PairingLifecycle`.

### Vantagens

- Mantém a regra "toda decisão de tempo mora em `PairingLifecycle`" mesmo para uma mudança que é,
  na prática, só cosmética (cor do texto) — um agente futuro que precisar mudar o limiar de aviso
  muda uma constante aqui, testada, em vez de um número mágico dentro de um `@Composable`.
- Zero mudança de comportamento fora da cor: `nextAction`, `remainingMillis`,
  `canRetryBundleFetch` e `canConfirmSas` são bit-a-bit os mesmos de antes desta rodada.

### Validação

`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL` (61 testes no
módulo `app`, 1 skipped, 0 falhas, 0 erros; `PairingLifecycleTest` com 11 casos, incluindo o novo
`isExpiringSoon`). Ver `docs/changes/PairingLifecycleTest.kt.md` para o teste adicionado.

## 2026-09-17 (2) — `CONFIRMATION_TTL_MILLIS`: o espelho do segundo relógio (T4.16, achado do run6)

`PairingEngine` passou a ter dois orçamentos em vez de um (ver `docs/changes/Pairing.kt.md`, seção
"2026-09-17 (4)"), e este objeto documenta os prazos que a tela mostra — então precisava acompanhar.

### Como ficou

```kotlin
const val OFFER_TTL_MILLIS: Long = 120_000L        // leitura do convite
const val PENDING_TTL_MILLIS: Long = 300_000L      // aquisição, por oferta emitida
const val CONFIRMATION_TTL_MILLIS: Long = 240_000L // confirmação, a partir do staging
```

`PENDING_TTL_MILLIS` deixou de significar "a troca montada tem 300 s" e passou a significar
"**cada oferta emitida** continua respondível por 300 s". O que vem depois do staging — busca do
bundle pela Tor, SAS, alias, dois QRs de confirmação — é `CONFIRMATION_TTL_MILLIS`.

### O que **não** mudou

`nextAction`, `remainingMillis`, `countdownLabel`, `canRetryBundleFetch`, `canConfirmSas` e
`isExpiringSoon` estão intactos. Nenhum deles lê essas constantes: todos trabalham sobre
`PairingUi.expiresAt`, que o motor já preenche com o prazo certo de cada fase. As constantes aqui são
documentação executável — existem para que o teste JVM prenda os números e para que quem lê a tela
saiba de onde eles vêm. Trocar o relógio do motor, portanto, não exigiu mudar uma linha de lógica de
UI, o que é exatamente o que se espera de ter posto as decisões de prazo num só lugar.

O teste `the two deadlines are the ones the protocol enforces` virou
`the three deadlines are the ones the protocol enforces` e prende também o valor novo.

### Regressão

`:app:testDebugUnitTest` 61 testes / 0 falhas; `:app:lintDebug` 0 erros.
