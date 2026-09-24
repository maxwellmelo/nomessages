# PairingLifecycleTest.kt

## 2026-09-17 — Novo arquivo: cobertura JVM pura das regras de expiração do pareamento (T4.16)

Arquivo novo: `app/src/test/kotlin/dev/mx3/nomessages/ui/PairingLifecycleTest.kt`. Cobre
`app/src/main/kotlin/dev/mx3/nomessages/ui/PairingLifecycle.kt` — ver
`docs/changes/PairingLifecycle.kt.md` para a máquina de estados em si. Este documento cobre
especificamente o que cada um dos dez casos fixa.

### Como era antes

Não existia. As regras de expiração da tela de pareamento não tinham nenhum teste dedicado — só
podiam ser exercitadas indiretamente, através da própria composable, num teste instrumentado.

### Como ficou

```kotlin
class PairingLifecycleTest {
    private val now = 1_700_000_000_000L
    private fun offerOnScreen(expiresAt: Long) = PairingUi(offer = "nomessages:2:AAA", expiresAt = expiresAt)
    private fun exchange(expiresAt: Long, status: PairingBundleStatus = PairingBundleStatus.FETCHING) = PairingUi(
        offer = "nomessages:2:BBB", sas = "123456", peerFingerprint = "ab".repeat(32),
        expiresAt = expiresAt, bundleStatus = status,
    )
    ...
}
```

Dois fixtures cobrem os dois estados possíveis de `PairingUi` que `nextAction` distingue:
`offerOnScreen` (`sas == null`, só a oferta local existe) e `exchange` (`sas` preenchido, troca já
estagiada, `bundleStatus` parametrizável). `now` é uma constante fixa em vez de
`System.currentTimeMillis()`, então cada asserção é determinística e livre de *flakiness* por
tempo de execução — o próprio motivo de `PairingLifecycle` aceitar `nowMillis` como parâmetro em
vez de ler o relógio internamente.

### Os dez casos, e o que cada um fixa

1. **`a live offer is left alone`** — uma oferta com tempo restante (1 ms e 119 s) não gera
   nenhuma ação. Fixa que `nextAction` não é gatilho de regeneração/cancelamento antecipado.

2. **`an offer nobody scanned is regenerated the moment it expires`** — no instante exato de
   expirar (`now`) e já expirada (`now - 5s`), uma oferta sem SAS produz `REGENERATE`. Fixa o
   comportamento central do T4.16: a tela se atualiza sozinha em vez de deixar um QR morto.

3. **`an expired staged exchange is cancelled, never regenerated`** — uma troca estagiada
   (`sas` preenchido) no instante de expirar produz `CANCEL`; um milissegundo antes (`now + 1`)
   ainda produz `NONE`. Fixa a regra mais importante do arquivo: expiração de uma troca em
   andamento **nunca** regenera, porque o par já tem um transcript sobre este par de ofertas.

4. **`leaving the screen cancels whatever is in progress`** — `screenOpen = false` produz
   `CANCEL` tanto para uma oferta com tempo de sobra quanto para uma troca estagiada com tempo de
   sobra. Fixa que sair da tela cancela **independentemente** de quanto tempo resta e
   independentemente de qual dos dois estados está ativo.

5. **`a completed pairing is never touched`** — um pareamento `completed = true`, já expirado,
   produz `NONE` tanto com a tela aberta quanto fechada; `pairing == null` também produz `NONE`
   nos dois casos. Fixa que o QR de confirmação de um pareamento concluído nunca é descartado, e
   que a ausência de pareamento é um não-evento seguro.

6. **`the two deadlines are the ones the protocol enforces`** — `OFFER_TTL_MILLIS == 120_000L` e
   `PENDING_TTL_MILLIS == 300_000L`. Fixa as duas constantes contra regressão numérica silenciosa
   (por exemplo alguém "arredondando" 300 s para 120 s por engano, colapsando as duas deadlines).

7. **`remaining time clamps at zero and promotes epoch seconds`** — `now + 5s` → `5_000`;
   `now - 5s` → `0` (grampeado); `(now / 1000) + 5` (um valor em **segundos**) → `5_000`; `0` →
   `0`. Fixa as três garantias de `remainingMillis`: nunca negativo, e a promoção
   segundos→milissegundos funciona tanto para um valor válido quanto para o caso extremo `0`.

8. **`the countdown label is mm colon ss and goes away once it runs out`** —
   `120_000 → "02:00"`, `299_999 → "04:59"` (arredondamento para baixo dentro do segundo, não para
   cima), `9_400 → "00:09"`, e `0`/`-1` → `null`. Fixa o formato exato exibido na tela e que o
   rótulo desaparece (em vez de mostrar `"00:00"`) assim que expira, para o chamador trocar pelo
   texto localizado de "expirado".

9. **`retry is offered only for a failed fetch that still has time left`** — verdadeiro só para
   `FAILED` com tempo restante; falso para `FAILED` no instante exato de expirar, para `FETCHING`
   e `READY` (mesmo com tempo restante), para `pairing == null`, e para um pareamento `completed`
   mesmo com status `FAILED`. Fixa que o botão "tentar de novo" nunca aparece fora da janela em
   que uma nova tentativa teria efeito.

10. **`the SAS cannot be confirmed before the peer key bundle has arrived`** — verdadeiro só para
    `READY`; falso para `NONE`, `WAITING_FOR_TOR`, `FETCHING` e `FAILED`; falso para uma oferta sem
    SAS (`offerOnScreen`, nenhuma troca estagiada); falso quando `waitingForPeer = true` mesmo com
    `bundleStatus == READY`; falso para `pairing == null`. Fixa a gate completa do botão de
    confirmação: os cinco valores de `PairingBundleStatus` são testados individualmente (não só o
    caminho feliz), e o caso `waitingForPeer` fixa que confirmar a própria SAS não basta enquanto
    o outro lado ainda não confirmou a dele.

### Vantagens

- Cobre as cinco combinações de `PairingBundleStatus` explicitamente (caso 10), não só o caminho
  `READY`, então uma futura reordenação acidental do enum ou um novo valor esquecido no `when` de
  `canConfirmSas` quebraria o teste em vez de passar por acaso.
- `now`/`now ± Δ` fixo em vez de tempo de parede real: os dez casos rodam em milissegundos e são
  100% determinísticos, sem qualquer necessidade de `Thread.sleep`/relógio controlado.
- Nenhuma dependência de Android, Robolectric ou `ComposeTestRule` — roda como teste JVM puro em
  `:app:testDebugUnitTest`, junto do resto da suíte de unidade.

### Validação (2026-09-17)

`gradle-wsl.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
→ `BUILD SUCCESSFUL in 8m 35s`. `:app:testDebugUnitTest` — **60 testes, 0 falhas, 0 erros, 1
pulado** (contagem do módulo inteiro; os dez casos acima estão entre eles). Nenhuma execução em
emulador/aparelho físico foi feita para este arquivo.

---

## 2026-09-17 — Passo de polimento de UX: 11º caso, para `isExpiringSoon`

Segue a mesma sessão de revisão de UX descrita em `docs/changes/PairingLifecycle.kt.md` (seção com
o mesmo título). Um caso novo cobre a única função nova adicionada a `PairingLifecycle` nesta
rodada:

```kotlin
@Test
fun `the countdown warns before it errors, not the instant it hits zero`() {
    assertFalse(PairingLifecycle.isExpiringSoon(15_001))
    assertTrue(PairingLifecycle.isExpiringSoon(15_000))
    assertTrue(PairingLifecycle.isExpiringSoon(1))
    // Zero and below are "expired", a different color (error) than "expiring soon" (warning).
    assertFalse(PairingLifecycle.isExpiringSoon(0))
    assertFalse(PairingLifecycle.isExpiringSoon(-1))
}
```

Os dois limites (`15_000`/`15_001`) fixam a fronteira exata do aviso, e os dois valores fora do
intervalo (`0`/`-1`) fixam que "expirado" (tratado por `countdownLabel`/`ExpiryLabel` com a cor de
erro) nunca é também tratado como "expirando em breve" (cor âmbar) — os dois estados visuais são
mutuamente exclusivos por construção, não por convenção entre os chamadores.

### Validação

`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`.
`PairingLifecycleTest` — **11 testes, 0 falhas, 0 erros** (os dez anteriores mais este). Módulo
`app` inteiro: 61 testes, 1 pulado (pré-existente, não relacionado), 0 falhas, 0 erros.
