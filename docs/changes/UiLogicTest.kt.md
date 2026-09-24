# app/src/test/kotlin/dev/mx3/nomessages/ui/UiLogicTest.kt

## 2026-09-14 — T4.2: caso novo para o selo de não lidas

Tarefa: T4.2 de `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.
Escopo: um `@Test` novo. Os cinco casos existentes não foram alterados.

### Como era antes

A classe cobria `attachmentKind`, `deliveryMark`, `validGroupSelection`,
`completedGroupCheck` e o ciclo de vida de `OwnedResource`. Nada cobria as não
lidas, porque a formatação do selo estava dentro do Composable `ChatRow` e não
era alcançável por teste de JVM.

### Como ficou

Caso novo, inserido entre `message status maps only known delivery states to ticks`
e `group selection accounts for local member in three through one hundred total`:

```kotlin
@Test
fun `unread badge is local only and caps instead of widening the row`() {
    assertEquals("", unreadBadge(0))
    assertEquals("", unreadBadge(-1))
    assertEquals("1", unreadBadge(1))
    assertEquals("999", unreadBadge(MAX_UNREAD_BADGE))
    assertEquals("999+", unreadBadge(MAX_UNREAD_BADGE + 1))
}
```

Cada asserção fixa uma decisão:

- `0` → sem selo, que é o estado de uma conversa aberta agora e o estado de todo
  o histórico da isca (semeado em `READ`);
- `-1` → sem selo: contagem negativa é impossível pela consulta, mas a função
  degrada para "nada novo" em vez de desenhar `-1` num selo;
- `1` → o número aparece literalmente no caso mínimo;
- `MAX_UNREAD_BADGE` → o último valor exibido sem sufixo, fixando o limite;
- `MAX_UNREAD_BADGE + 1` → `"999+"`, fixando que o teto é anunciado como teto e
  não como número exato.

### Vantagens

- O teto do selo passa a ser regressão automática: alterar `MAX_UNREAD_BADGE`
  sem intenção quebra o teste.
- O comportamento em contagem zero — o mesmo que a isca exibe — fica preso por
  teste, e não por inspeção visual.
- Roda em JVM pura, junto do resto do arquivo, sem instrumentação de Compose.

### Por que a mudança foi feita

T4.2 pedia explicitamente "1 caso novo" em `UiLogicTest.kt` como a cobertura
mínima da funcionalidade de não lidas.

## 2026-09-15 — Teste dos 4 casos de `shouldApplySecureFlag`

Novo teste `secure flag is dropped only for a debug build with the exact opt-in value`, cobrindo os
4 casos exigidos pela tarefa 4 da investigação de 2026-09-15:

```kotlin
assertEquals(false, shouldApplySecureFlag(isDebug = true, propertyValue = "1"))
assertEquals(true, shouldApplySecureFlag(isDebug = true, propertyValue = "0"))
assertEquals(true, shouldApplySecureFlag(isDebug = true, propertyValue = null))
assertEquals(true, shouldApplySecureFlag(isDebug = false, propertyValue = "1"))
assertEquals(true, shouldApplySecureFlag(isDebug = false, propertyValue = null))
```

(O quinto `assertEquals` é o caso release-sem-propriedade, incluído por completude ao lado do
release-com-propriedade — os dois devem dar `true`.)

### Vantagens

- Fixa em teste JVM puro exatamente a matriz de decisão descrita em
  `docs/changes/UiLogic.kt.md` e em `docs/security-model.md`, sem precisar de instrumentação
  Android nem de um dispositivo/emulador.

### Por que a mudança foi feita

Tarefa 4 pedia explicitamente teste unitário (JVM, não instrumentado) cobrindo os 4 casos de
`shouldApplySecureFlag`.

## 2026-09-15 — Testes das novas decisões puras de rolagem do chat

Contexto completo do bug corrigido em `docs/changes/ChatScreen.kt.md`; as funções testadas aqui
estão documentadas em `docs/changes/UiLogic.kt.md`. Dois `@Test` novos, inseridos entre `secure flag
is dropped only for a debug build with the exact opt-in value` e `resource created after disposal is
released exactly once`. Nenhum caso existente foi alterado.

### Como ficou

```kotlin
@Test
fun `newest message sits at index 0 when reversed, at the last index otherwise`() {
    assertEquals(0, scrollIndexForNewestMessage(itemCount = 12, reverseLayout = true))
    assertEquals(11, scrollIndexForNewestMessage(itemCount = 12, reverseLayout = false))
    assertEquals(0, scrollIndexForNewestMessage(itemCount = 1, reverseLayout = false))
}

@Test
fun `auto-scroll to a new message only follows a reader who was already at the bottom`() {
    assertEquals(true, isAtBottom(firstVisibleItemIndex = 0))
    assertEquals(false, isAtBottom(firstVisibleItemIndex = 3))
    assertEquals(true, shouldAutoScrollToNewMessage(wasAtBottom = true))
    assertEquals(false, shouldAutoScrollToNewMessage(wasAtBottom = false))
}
```

O primeiro teste fixa os dois lados de `scrollIndexForNewestMessage`: com `reverseLayout = true`
(o modo usado hoje em `ChatScreen.kt`) a mensagem mais nova é sempre o índice `0`; com
`reverseLayout = false` seria o último índice — incluindo o caso de lista com um único item, onde os
dois modos coincidem em `0`. O segundo teste cobre `isAtBottom` nos dois lados (`0` e um índice
qualquer maior) e `shouldAutoScrollToNewMessage` nos dois lados (`true`/`false`), fixando que a
função é a identidade da entrada — ou seja, que toda a regra "só rola se já estava no fundo" está
nesse único ponto de decisão.

### Vantagens

- Roda em JVM pura, junto do resto do arquivo, sem instrumentação de Compose nem um
  `LazyListState` real.
- Fixa em teste a relação entre `reverseLayout` e o índice da mensagem mais nova, que é o ponto
  exato que estava invertido no bug original (`listState.scrollToItem(state.messages.lastIndex)`
  rolava até a mensagem mais antiga, não a mais nova).

### Por que a mudança foi feita

Correção do bug de ordem das mensagens do chat. A tarefa pedia 1-2 testes JVM cobrindo a nova lógica
pura de decisão de rolagem extraída para `UiLogic.kt`.

## 2026-09-16 — 7 casos novos: forma de onda, duração, velocidade e o cache de prévias (T4.8, mídia inline — parte A: áudio)

### Casos adicionados

- `computeWaveformBuckets`: normalização pelo balde mais alto (dois baldes, um baixo/um alto — o
  baixo fica proporcional, o alto vira exatamente `1.0`) e contagem exata de baldes mesmo com um
  buffer curto demais para preencher todos.
- `pcmDurationMs`: duração segue contagem de amostras/taxa de amostragem, não o layout de bytes.
- `formatDurationMs`: `mm:ss` com segundos zero-padded, incluindo duração negativa (`coerceAtLeast(0)`).
- `formatSpeedLabel`: `1f` -> `"1x"` (sem `.0`), `1.5f` -> `"1.5x"`, `2f` -> `"2x"`.
- `MediaPreviewCache`: eviction por LRU real (insere 30 entradas contra um teto de 24, toca a
  sobrevivente mais antiga via `get()` para movê-la para o fim, confirma que a próxima inserção
  remove a entrada seguinte — não a que acabou de ser tocada) e `clear()` síncrono.

### Por que a mudança foi feita

A2 pede explicitamente que a lógica de forma de onda seja "pure function, unit-testable". O cache
(`MediaPreviewCache`) é pura lógica Kotlin (sem dependência de Android), então seu comportamento de
eviction — a parte mais fácil de acertar errado — também ganhou cobertura JVM aqui, sem precisar de
emulador.

### Validação

`:app:testDebugUnitTest`: 17 casos, 0 falhas (10 pré-existentes + 7 novos).

## 2026-09-16 — 2 casos novos: `chooseInSampleSize` e `imageGalleryIds` (T4.8, mídia inline — parte B: fotos)

### Casos adicionados

- `` `in sample size halves until either dimension would drop below the target` ``: imagem já no
  tamanho alvo ou menor (`1`), fronteiras exatas de potência de dois (`1024→2`, `2048→4`), fonte que
  não é potência de dois (a dimensão menor governa o resultado), fonte muito grande (`8192→16`), e
  entrada malformada (largura/altura `<= 0`) degradando para `1` em vez de laço infinito/divisão por
  zero.
- `` `image gallery ids keep only image attachments, in message order` ``: uma lista de
  `MessageUi` mista (imagem, texto sem anexo, áudio, imagem, PDF) resulta só nos dois ids de imagem,
  na ordem em que apareciam; lista vazia devolve lista vazia.

### Por que a mudança foi feita

`chooseInSampleSize` é a receita "quantas vezes dividir por dois" da tarefa B1 — o tipo de lógica
com fronteiras fáceis de errar (quando exatamente parar de dobrar o `inSampleSize`), então precisa
de casos de teste fixando cada fronteira, não só o caminho feliz. `imageGalleryIds` sustenta
`UiState.imageGallery` (B3, navegação por swipe entre imagens) — um teste evita que um refactor
futuro comece a incluir anexos que não são imagem na lista de navegação.

### Validação

`:app:testDebugUnitTest`: 19 casos, 0 falhas (17 pré-existentes + 2 novos).


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Teste novo: limite de pixels do `chooseInSampleSize`

**Como era:** o unico teste de `chooseInSampleSize` cobria apenas proporcoes proximas de 1:1
(`512x512`, `1024x1024`, `1500x1200`, `8192x8192`), que e exatamente o conjunto em que a receita
classica por dimensao *parece* um limite de memoria.

**Como ficou:** acrescentado
`in sample size also bounds total pixels so an extreme aspect ratio cannot be a decompression bomb`,
que fixa o caso adversarial (40000x500 e 500x40000, alem de 65535x65535) e afirma
`(w/s) * (h/s) <= PREVIEW_MAX_PIXELS`, e reafirma que proporcoes de foto comum (`1500x1200`,
`4032x3024`) nao mudaram.

**Por que era necessario.** Sem esse caso, a regressao do bound de memoria descrita em
`UiLogic.kt.md` passa despercebida: todos os casos existentes continuam verdes com o codigo
vulneravel.

**Garantia restaurada.** Cobertura de teste do limite anti-decompression-bomb.

### Validacao

`:app:testDebugUnitTest :app:lintDebug` -> `BUILD SUCCESSFUL`, 44 testes JVM, 0 falhas.
`:app:assembleDebug :app:assembleDebugAndroidTest` -> `BUILD SUCCESSFUL`.
Suite instrumentada em `emulator-5556` -> `OK (15 tests)`.

---

## 2026-09-16 — Caso novo: `isSystemIme` (T4.9)

### Motivo

Cobrir os três cenários pedidos pela tarefa para a função pura nova `isSystemIme`
(`docs/changes/UiLogic.kt.md`): pacote de sistema (sem aviso), pacote de terceiros (com aviso), e o
caso `FLAG_UPDATED_SYSTEM_APP` (um teclado de sistema atualizado via Play Store, que deve continuar
contando como confiável).

### Como ficou

```kotlin
@Test
fun `third-party keyboard detection only trusts packages the caller marked as system`() {
    val systemPackages = setOf("com.google.android.inputmethod.latin", "com.android.inputmethod.pinyin")

    assertEquals(true, isSystemIme("com.google.android.inputmethod.latin/.LatinIME", systemPackages))
    assertEquals(false, isSystemIme("com.thirdparty.keyboard/.KeyboardService", systemPackages))

    val updatedSystemPackages = setOf("com.google.android.inputmethod.latin")
    assertEquals(true, isSystemIme("com.google.android.inputmethod.latin/.LatinIME", updatedSystemPackages))

    assertEquals(false, isSystemIme("com.thirdparty.keyboard", systemPackages))
    assertEquals(true, isSystemIme("com.google.android.inputmethod.latin", systemPackages))
}
```

O caso `FLAG_UPDATED_SYSTEM_APP` é simulado do lado puro pela própria composição do conjunto
`systemPackages` que o teste passa — a função pura não sabe (nem precisa saber) qual flag do
Android colocou o pacote ali; o teste demonstra que, esteja o pacote no conjunto por
`FLAG_SYSTEM` ou por `FLAG_UPDATED_SYSTEM_APP`, o resultado é o mesmo (`true`, sem aviso).

### Vantagens

- Os três cenários pedidos pela tarefa T4.9 ficam expressos como asserções diretas, sem nenhum
  mock de Android.
- Cobre também o caso de id malformado (sem `"/"`), que a implementação trata como nome de pacote
  em vez de lançar.

### Por que a mudança foi feita

T4.9, item 4.
