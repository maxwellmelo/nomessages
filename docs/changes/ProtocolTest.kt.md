# ProtocolTest.kt — mudanças

## 2026-09-17 — Testes adaptados e adicionados para o QR formato 2 / busca de bundle por Tor (T4.16)

Contexto completo da mudança de produto em `docs/changes/Pairing.kt.md` e
`docs/changes/SignalSessions.kt.md`. Este arquivo documenta só as mudanças em
`core/src/test/.../protocol/ProtocolTest.kt` e a dependência de teste que elas exigiram em
`core/build.gradle.kts`.

---

### 1. Toda chamada a `PairingEngine(...)` ganhou um parâmetro `doorbell`

O construtor de `PairingEngine` passou a exigir uma chave de identidade de campainha (32 bytes,
reservada para T4.17 — ver `Pairing.kt.md` seção 10). Todo teste que construía o engine diretamente
precisou de uma fixture:

```kotlin
private val doorbellA = ByteArray(32) { (it + 1).toByte() }
private val doorbellB = ByteArray(32) { (it + 65).toByte() }
private val doorbellC = ByteArray(32) { (it + 129).toByte() }
```

e, em `ProtocolAdversarialTest`, um gerador por semente (porque essa classe cria vários engines com
identidades diferentes dentro do mesmo teste):

```kotlin
private fun doorbellKey(seed:Int)=ByteArray(32) { ((it + seed) or 1).toByte() }
```

(`or 1` garante que o byte nunca seja 0 em toda posição — a checagem `doorbellKey.any { it.toInt()!=0
}` só exige que **algum** byte não seja zero, mas a fixture usa `or 1` por simplicidade, não por
necessidade.) Nenhuma outra propriedade dos testes depende do conteúdo dessas chaves — o engine só
assina e publica o valor, nunca o interpreta.

---

### 2. Deadline: `assertEquals(1120L, ...)` → `1000L + PairingEngine.PENDING_TTL_SECONDS`

```kotlin
// 300 s, not 120 s: the exchange now has to fit a Tor round trip for the key
// bundle. Still anchored to the OLDEST offer, so it is not restarted per step.
assertEquals(1000L + PairingEngine.PENDING_TTL_SECONDS, response.expiresAt)
```

O valor literal `1120L` (`1000 + 120`) foi trocado pela expressão `1000L +
PairingEngine.PENDING_TTL_SECONDS`, que passa a valer `1300L`. Não é só o número que mudou: o
**significado** do prazo mudou, de "quanto tempo o QR pode ser escaneado" para "quanto tempo a troca
inteira, incluindo a busca por Tor, tem para terminar" (`Pairing.kt.md` seção 8) — escrever a
expressão em vez do literal deixa essa dependência explícita e à prova de o valor de
`PENDING_TTL_SECONDS` mudar de novo no futuro.

---

### 3. Helper novo: `exchangeBundles`

```kotlin
/** Relays each side's real key bundle, standing in for `MessagingEngine`'s Tor fetch. */
private fun exchangeBundles(ea: PairingEngine, ar: PairingProgress, eb: PairingEngine, br: PairingProgress) {
    ea.acceptPeerBundle(ar.handle, requireNotNull(eb.bundleFor(ar.peerBundleNonce)))
    eb.acceptPeerBundle(br.handle, requireNotNull(ea.bundleFor(br.peerBundleNonce)))
}
```

`ProtocolTest` roda em JVM puro, sem Tor. Este helper substitui o transporte por uma chamada direta:
cada lado pede ao **outro engine** (não a si mesmo) o bundle atrás do nonce que o peer publicou
(`bundleFor`), e entrega esses bytes a `acceptPeerBundle`. Todo teste que chega até `confirm`/`finish`
precisa chamar isto primeiro agora, porque `finish` recusa terminar sem `peerBundleReady` (ver
`Pairing.kt.md` seção 5) — daí ele aparecer em `bilateralPairingAndSignalRestoreRoundTrip`,
`formatTwoQrPayloadsStayWithinTheVersionFourteenBudgetAtLevelL`,
`doorbellFieldsAreSignedCarriedIntoTheContactAndFreshPerOffer` e, em `ProtocolAdversarialTest`, em
`pair(...)`, `pinnedIdentityMismatchIsRejectedByPeer` e `confirmationCannotBeReplayedIntoOtherExchange`
(as duas últimas chamando as duas linhas do helper inline, já que não compartilham a classe de teste
com `ProtocolTest`).

---

### 4. `assertTrue(response.toByteArray().size <= 2953, "Must fit one QR40-L")` — removido

As duas ocorrências desse assert (em `responseKeepsOriginalDeadlineAndDoesNotRedisplayTheConsumedOffer`
e `bilateralPairingAndSignalRestoreRoundTrip`) mediam o teto do formato 1 (QR versão 40 no nível L,
2953 bytes — o teto que o próprio formato 1 estourava em aparelho real, ver `Pairing.kt.md` seção 1).
Com o bundle fora do QR, medir contra esse teto deixou de fazer sentido; a medição real de tamanho/QR
version passou a ser responsabilidade de um teste dedicado (seção 5), que mede o orçamento novo
(versão 14) em vez do antigo (versão 40).

---

### 5. Teste novo: `formatTwoQrPayloadsStayWithinTheVersionFourteenBudgetAtLevelL`

A medição principal de T4.16. Mede oferta, resposta e confirmação com o encoder de verdade do ZXing —
o mesmo que `PairingScreen.makeQr` usa em produção — em vez de comparar contra uma tabela de
capacidade:

```kotlin
val version = Encoder.encode(payload, ErrorCorrectionLevel.L).version.versionNumber
assertTrue(version <= 14, "$name renders as QR version $version at level L, over the version-14 budget")
```

**Por que medir com o encoder, não com uma tabela.** O otimizador de modo de segmento do ZXing pode
dividir um payload entre segmentos byte/alfanumérico/numérico; uma tabela de capacidade só limita a
versão **por cima** (o pior caso), enquanto `Encoder.encode(...).version` é a versão que o app de fato
vai renderizar. Medir o valor real, não um limite superior teórico, é o que torna o assert um
orçamento de verdade e não uma aproximação otimista.

Números medidos em 2026-09-17, já com os dois campos de campainha inclusos: oferta 405 bytes /
versão 13, resposta 448 bytes / versão 14, confirmação 317 bytes / versão 11 (tabela completa e
comparação com o formato 1 em `docs/changes/Pairing.kt.md` seção 12). O comentário do teste registra
explicitamente que a resposta fica **no** orçamento, não abaixo dele — dez bytes de folga na versão
14 — e que é exatamente essa folga apertada que motivou o desvio de carregar `doorbellKey` como chave
de 32 bytes em vez do endereço de texto de 62 caracteres (`Pairing.kt.md` seção 11).

---

### 6. Teste novo: `sasChangesWhenEitherSideUsesADifferentBundle`

Roda o pareamento três vezes com identidades e bundles de uso único frescos a cada rodada e reúne os
SAS resultantes num `Set`:

```kotlin
assertEquals(3, seen.size)
```

Três identidades diferentes têm de produzir três SAS diferentes; um `Set` menor do que 3 significaria
que o transcript deixou de depender do material por-oferta — ou seja, que o SAS parou de amarrar o
bundle/identidade de fato usados naquela troca específica, o que anularia a garantia central da seção
6 de `Pairing.kt.md` (a comparação falada dos humanos cobre transitivamente os bundles).

---

### 7. Teste novo: `tamperedOrSubstitutedBundleIsRejectedAndCancelsThePairing`

Dois ataques distintos, ambos barrados pela comparação de hash em `acceptPeerBundle`:

1. **Bundle adulterado** — um byte do bundle real é invertido antes de `acceptPeerBundle`; é
   rejeitado, e a troca fica queimada (uma segunda tentativa com o bundle correto também falha,
   porque o handle não existe mais):

   ```kotlin
   val flipped = real.copyOf()
   flipped[flipped.lastIndex] = (flipped.last().toInt() xor 1).toByte()
   assertThrows(Exception::class.java) { ea.acceptPeerBundle(ar.handle, flipped) }
   // Burned: the handle is gone, so a later correct bundle cannot revive it.
   assertThrows(Exception::class.java) { ea.acceptPeerBundle(ar.handle, real) }
   ```

2. **Bundle substituído** — um bundle bem formado e válido, mas pertencente a um terceiro
   (`stranger`/`doorbellC`) completamente alheio à troca, é oferecido no lugar do bundle esperado; é
   rejeitado pela mesma razão — o hash não bate — não por qualquer defeito na validade interna do
   bundle do estranho. O nonce do estranho é lido do QR dele exatamente como um scanner real leria:

   ```kotlin
   val strangerNonce = Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX, strangerQr), PairingEngine.OFFER_FIELD_KINDS)[4] as ByteArray
   val foreign = requireNotNull(es.bundleFor(strangerNonce))
   assertThrows(Exception::class.java) { ea2.acceptPeerBundle(ar2.handle, foreign) }
   ```

---

### 8. Teste novo: `bundleForAnswersOnceAndOnlyForItsOwnLiveOffers`

Cobre, num único teste, as quatro cláusulas da regra de `bundleFor` documentada em `Pairing.kt.md`
seção 6:

- **Não responde pelo nonce de outro dispositivo**: `eb.bundleFor(nonce da oferta de ea)` → `null`.
- **Nonce desconhecido ou de tamanho errado** → `null` (`ByteArray(16)` aleatório, `ByteArray(8)`).
- **Exatamente uma vez**: a primeira chamada com o nonce certo devolve o bundle; a segunda, `null`.
- **Oferta `superseded` continua respondível** pelo resto da própria janela — cria uma oferta, guarda
  o nonce, cria outra (o que empurra a primeira para `superseded`), e confirma que o nonce da primeira
  ainda responde.
- **Oferta expirada nunca respondida vira `null`** mesmo sem nunca ter sido consumida — avança o
  relógio de teste em `PairingEngine.PENDING_TTL_SECONDS` e confirma.

---

### 9. Teste novo: `formatOneQrCodesAreRejected`

Confirma as duas camadas de rejeição descritas em `Pairing.kt.md` seção 1: um QR com o prefixo
`nomessages:1:` é rejeitado antes de qualquer decodificação de campo, e um QR com o prefixo **correto**
(`nomessages:2:`) mas o campo de versão interno forçado de volta para `1` é rejeitado por
`Offer.decode`, não pelo prefixo:

```kotlin
assertThrows(Exception::class.java) { eb.receive("nomessages:1:" + valid.substring(PairingEngine.OFFER_PREFIX.length)) }
...
val fields = Proto.decode(body, PairingEngine.OFFER_FIELD_KINDS).toMutableList()
fields[0] = 1L
assertThrows(Exception::class.java) { eb.receive(Proto.qr(PairingEngine.OFFER_PREFIX, Proto.encode(fields))) }
```

---

### 10. Teste novo: `doorbellFieldsAreSignedCarriedIntoTheContactAndFreshPerOffer`

Cobre os dois campos de campainha reservados como campos de wire de verdade, mesmo sem comportamento
funcional ainda (`Pairing.kt.md` seção 10):

- A chave publicada no QR bate com a que foi passada ao construtor; o token tem 32 bytes.
- Editar `doorbellKey` **ou** `doorbellToken` isoladamente invalida a assinatura Ed25519 — os dois
  índices (`6`, `7`) são testados num loop.
- Uma `doorbellKey` toda-zero é recusada mesmo com o resto do payload válido (checagem de identidade
  onion utilizável, não só de assinatura).
- Depois de um pareamento completo, cada lado termina com a chave e o token **do outro** dentro de
  `PairedContact` — e os dois tokens são diferentes entre si.
- Uma segunda oferta do mesmo dispositivo repete a mesma `doorbellKey` (estável, é a identidade da
  onion de campainha) mas nunca o mesmo `doorbellToken` (fresco por oferta).

---

### 11. `rejectsSignedOfferBundleSubstitution` reescrito para `rejectsBundleHashSubstitutionInASignedOffer`

**Por que o teste antigo não tinha mais nada para atacar.** No formato 1, o ataque testado era trocar
o campo `signal` (a chave de identidade duplicada do bundle, 33 bytes) por lixo e tentar reassinar —
barrado porque o bundle original (`bundle`) continuava presente e batendo com o `signal` de antes, não
com o novo. No formato 2, o campo `signal` **não existe mais** — foi removido do QR inteiro (seção 1 de
`Pairing.kt.md`). Não há mais um "bundle duplicado" para comparar contra um "campo externo trocado"; o
único candidato equivalente é `bundleHash`, o hash de 32 bytes que substituiu os dois campos antigos.

O teste novo ataca exatamente esse campo, com dois cenários:

```kotlin
val values=Proto.decode(Proto.fromQr(PairingEngine.OFFER_PREFIX,offer),PairingEngine.OFFER_FIELD_KINDS).toMutableList()
values[5]=ByteArray(32) // a hash of the attacker's own choosing
assertThrows(Exception::class.java) { engine(b).respond(Proto.qr(PairingEngine.OFFER_PREFIX,Proto.encode(values))) }
// Re-signing with a DIFFERENT key does not help either: the offer then belongs to that key,
// and `ed` no longer matches the identity the victim believes they scanned.
val impostor=PairingIdentity.create(crypto)
val signed=cbor("nomessages-offer-v2",*values.take(7).toTypedArray())
values[7]=crypto.sign(impostor.signing.secretKey,signed)
assertThrows(Exception::class.java) { engine(b).respond(Proto.qr(PairingEngine.OFFER_PREFIX,Proto.encode(values))) }
```

1. `bundleHash` editado sem reassinar — rejeitado pela verificação de assinatura, como qualquer campo
   assinado adulterado.
2. `bundleHash` editado **e** reassinado, mas com a chave de um **impostor**, não a original — ainda
   rejeitado, e por um motivo mais forte, não mais fraco: a oferta passa a pertencer honestamente à
   identidade do impostor, então `ed` já não bate com a identidade que a vítima acredita ter escaneado.
   Reassinar com a chave *original* simplesmente não é possível para o atacante — é isso que a
   assinatura garante — então esse terceiro cenário não tem como ser expresso como um teste que
   "deveria falhar", ele é a premissa de que a chave privada está protegida.

O comentário do teste deixa essa lógica explícita: "the equivalent attack is swapping the *hash*...
What is asserted here is the part a machine can check". Cobertura foi **adaptada** para o que ainda é
atacável no formato novo, não removida.

---

### 12. Corpus de QR malformado (`boundedMalformedQrCorpusRejectsWithoutChangingReceiverState`)

**Por que o literal `"nomessages:2:"` não podia continuar como estava.** No formato 1, esse literal no
corpus era deliberadamente um prefixo de versão errada (`OFFER_PREFIX` era `"nomessages:1:"`) — um caso
válido de "rejeitar por versão errada". Com o bump do prefixo para `nomessages:2:` (formato 2), esse
mesmo literal **se tornaria o próprio prefixo válido**, e o caso deixaria silenciosamente de testar
rejeição alguma — passaria a testar um QR bem formado.

**Como ficou:**

```kotlin
// Wrong version prefixes: "nomessages:3:" does not exist yet and "nomessages:1:" is
// the format T4.16 replaced. Both must be refused before any parsing happens.
// `substring(OFFER_PREFIX.length)` instead of a hard-coded 13 so a future rename
// cannot silently turn one of these cases into the valid payload - which is exactly
// what happened to the literal "nomessages:2:" that used to sit here.
val body = valid.substring(PairingEngine.OFFER_PREFIX.length)
corpus += listOf(valid+"=", valid+"\u0000", "nomessages:3:"+body, "nomessages:1:"+body,
    PairingEngine.OFFER_PREFIX+"A".repeat(8193), PairingEngine.OFFER_PREFIX+"/", "")
```

Duas mudanças juntas, não uma:

- O literal fixo `13` (comprimento de `"nomessages:1:"`) virou `PairingEngine.OFFER_PREFIX.length` —
  não hard-coded, então um rename futuro do prefixo não pode repetir silenciosamente o mesmo erro que
  aconteceu aqui.
- Os dois casos de prefixo errado passaram a ser `"nomessages:3:"` (uma versão futura que ainda não
  existe) e `"nomessages:1:"` (a versão que T4.16 substituiu) — cobrindo tanto "versão adiante demais"
  quanto "versão formato antigo", em vez de um único caso que, sem querer, teria parado de testar
  rejeição nenhuma.

O restante do corpus (payload oversized, base64 malformado, protobuf truncado/com campos gigantes)
não mudou de forma — só ganhou os fixtures de campainha nos construtores de `PairingEngine`.

---

### 13. Dependência de teste nova: `zxing-core` em `core/build.gradle.kts`

```kotlin
// Test-only: ProtocolTest measures the real QR symbol version the pairing payload produces,
// rather than comparing it against a capacity table. ZXing is not a :core runtime dependency -
// only `app` renders QR codes.
testImplementation(libs.zxing.core)
```

`:core` é um módulo Kotlin/JVM puro que não desenha QR nenhum em produção — só `app` faz isso, com
CameraX + ZXing. A dependência foi adicionada como `testImplementation`, não `implementation`: existe
só para que `formatTwoQrPayloadsStayWithinTheVersionFourteenBudgetAtLevelL` possa chamar o encoder real
do ZXing e medir a versão do símbolo, sem que `zxing-core` vaze para o classpath de runtime de `:core`
nem, por extensão, para o `app-debug.apk`/`app-release.apk` fora do que já chegava via `app`.

**Por que medir com o encoder em vez de uma tabela de capacidade** (mesmo raciocínio da seção 5,
repetido aqui porque é a motivação direta da dependência): o otimizador de modo de segmento do ZXing
pode dividir um payload entre segmentos byte/alfanumérico/numérico, então uma tabela de capacidade só
delimita a versão **por cima**; `Encoder.encode(...).version` é a versão que o app efetivamente vai
renderizar, e é essa a garantia que o teste precisa dar.

### Validação (2026-09-17)

`gradle-wsl.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` → `BUILD SUCCESSFUL in 8m 35s`. `:core:test` — 95 testes, 0 falhas, 0
erros, 2 pulados (inclui os seis testes novos deste arquivo e o teste reescrito da seção 11).


## 2026-09-17 (2) — regressão do defeito (c) do run5: resposta aceita após regeneração da oferta (T4.16)

Dois testes novos, acrescentados depois da validação ao vivo em dois emuladores (ver
`docs/changes/Pairing.kt.md`, seção "2026-09-17 (2)").

### `responseIsAcceptedAfterTheOfferRegeneratedAndPastTheQrReadingTtl`

Reproduz o cenário real que falhou no run5, com as **duas** causas independentes no mesmo percurso,
porque foi assim que elas ocorreram:

```kotlin
val answered = ea.createOffer()      // t = 1000
time += 110
val br = eb.respond(answered)        // t = 1110 — B responde à oferta antiga
time += 15
ea.createOffer()                     // t = 1125 — causa 1: a tela de A regenera; a antiga vira `superseded`
time += 130
val ar = ea.processResponse(response) // t = 1255 — causa 2: a resposta tem 145 s, acima do teto de 120 s
```

Verifica que o SAS bate nos dois lados, que `expiresAt` continua ancorado na **oferta mais antiga**
(`1000 + PENDING_TTL_SECONDS`, ou seja não reiniciado pela regeneração e não tirado da oferta que por
acaso estava na tela), que a busca do bundle resolve contra a oferta substituída — que agora é a
oferta local da troca montada — e que o pareamento **conclui**, com `finish` dos dois lados.

**Conferido que o teste pega o defeito**, em vez de apenas passar: revertendo no motor **só** a
consulta a `superseded`, **só** o teto de 120 s na resposta, e as duas juntas, ele falha nos três
casos. Cada metade da correção é necessária.

### `aResponseIsStillRefusedOnceTheExchangeWindowItselfHasClosed`

O contrapeso. Alargar a janela da resposta é trocar de prazo, não abrir mão dele:

- uma resposta entregue depois de `PENDING_TTL_SECONDS` continua recusada;
- uma oferta que envelheceu além da própria janela deixa de ser candidata, e `bundleFor` devolve
  `null` para o mesmo nonce — a mesma regra nos dois caminhos, que é justamente o ponto da correção.

O convite permanente segue com o teto de 120 s, coberto por
`rejectsReplayExpiredTamperedAndUnconfirmedOffers` (inalterado), então as duas regras continuam
testadas separadamente e não podem se fundir por descuido.


## 2026-09-17 (3) — três ofertas vivas ao mesmo tempo (T4.16)

Três testes novos, cobrindo a separação entre exibição e validade.

### `everyOfferInsideItsOwnWindowStaysAnswerableNotJustTheCurrentAndOnePredecessor`

O pico do regime permanente, derivado e não escolhido: ofertas em t = 0, 120 e 240, todas dentro da
janela de 300 s delas em t = 240, ainda que só a mais nova esteja na tela. Verifica que as três
respondem `bundleFor` e que cada uma responde **exatamente uma vez** — é isso que prova serem três
ofertas vivas distintas, e não uma respondida três vezes. Depois avança para t = 1300 e confirma que
a primeira saiu **no cronograma dela**, enquanto as outras seguem vivas.

A asserção sobre a constante `MAX_LIVE_OFFERS` fica **depois** do comportamento, de propósito: assim,
encolher o teto faz o teste falhar naquilo que um par de fato observaria, não na constante.

### `aResponseToTheOldestOfThreeLiveOffersStillPairs`

O caso ponta a ponta que a validação ao vivo vai repetir: B escaneia a primeira oferta, a tela de A
gira **duas** vezes, e a resposta de B chega quando a oferta respondida é a **mais antiga das três
vivas** — exatamente a entrada que a versão "atual + uma antecessora" já tinha queimado. Verifica SAS
igual, `expiresAt` ancorado na oferta mais antiga, busca do bundle e `finish` dos dois lados.

### `stagingAnExchangeBurnsTheOtherLiveOffers`

O contrapeso: montada a troca, a oferta que ninguém respondeu deixa de responder `bundleFor`
(este aparelho só comporta uma troca, então ela não leva a lugar nenhum e responder por ela violaria
a regra de limite de taxa), enquanto a oferta respondida continua respondendo — o par ainda pode
estar buscando o bundle dela.

### Conferência por mutação

Com `MAX_LIVE_OFFERS = 2`, os dois primeiros falham; o primeiro falha em
`offer 0 must still be answerable`, ou seja no comportamento. Os testes pegam o defeito, não passam
por acaso.


## 2026-09-17 (4) — orçamento de confirmação próprio (T4.16, cenário 6a/6c)

### `aSlowAcquisitionNoLongerEatsTheConfirmationBudget`

Reproduz exatamente o que falhou ao vivo: duas rotações gastas antes do staging (aquisição consome
245 s), e então uma fase humana de 80 s — que o prazo antigo, fixado em 1300, recusava. Verifica que
`confirm` e `finish` concluem nos dois lados e só **depois** confere o valor de `expiresAt`.

A ordem é deliberada. Na primeira versão a asserção de `expiresAt` vinha antes, e a mutação falhava
com `expected: <1485> but was: <1300>` — uma diferença de aritmética. Com o comportamento primeiro,
a mesma mutação falha com `java.lang.IllegalStateException: Pairing expired`, que é o sintoma que o
usuário de fato observou no run6. Mesma disciplina aplicada ao teste das três ofertas na passagem
anterior.

### `confirmationPastItsOwnBudgetStillFailsWithTheSpecificExpiryError`

O contrapeso: a fase de confirmação ganhou um prazo **novo**, não a ausência de prazo. Um segundo
antes do limite o SAS ainda é confirmável; um segundo depois a troca acabou — e a exceção continua
sendo a específica `"Pairing expired"`, a que a UI mapeia para `pairing_timed_out`, nunca um erro
genérico.

### Asserções existentes ajustadas

Três testes afirmavam `1000 + PENDING_TTL_SECONDS`. Agora medem a partir do staging, e um deles
passou a afirmar **prazos diferentes nos dois lados** (B em 1110, A em 1255), documentando a
assimetria em vez de escondê-la.

### Conferência por mutação

Duas direções: voltando ao prazo ancorado na oferta e afrouxando o prazo em 100x, os testes novos
falham nos dois casos.


## 2026-09-18 — mesmo teste, agora espelhado: `doorbellTokenIssued` (T4.17 fase 2)

Contexto completo da mudança de produto em `docs/changes/Pairing.kt.md`, seção "2026-09-18 — o lado
que faltava". Esta entrada documenta só a extensão do teste JVM já existente em `ProtocolTest.kt`.

### `doorbellFieldsAreSignedCarriedIntoTheContactAndFreshPerOffer`

O teste já existia desde o T4.16 e já fazia o trabalho pesado: uma troca local completa entre duas
`PairingEngine` (A e B), com relé do bundle PQXDH via `exchangeBundles`, terminando nos dois `finish()`
— `contactForA` e `contactForB`. Já capturava `token`, o campo 7 (`doorbellToken`) da própria oferta de
A, lido de volta do QR antes de qualquer `finish()` rodar. Isso já bastava para provar que
`doorbellKey`/`doorbellToken` chegam intactos e frescos por oferta — a metade do **par**. O que faltava
era a metade que a mudança de 2026-09-18 passou a persistir: o token que cada lado **cunhou**.

O KDoc do teste ganhou um parágrafo novo, registrando por que as asserções abaixo não são decoração:

```kotlin
 * Since T4.17 phase 2 the exchange also has to hand back the token this device *minted*
 * (`PairedContact.doorbellTokenIssued`), which is not a wire field of the incoming offer but of
 * the outgoing one. It used to be discarded with the offer, leaving the vault able to ring a
 * peer's doorbell and unable to verify a ring at its own, so the mirror-image assertions below
 * are the point of the addition, not decoration.
```

**Asserções acrescentadas**, logo depois das que já existiam para `doorbellKey`/`doorbellToken`:

```kotlin
// ...and, since T4.17, also its OWN token: the one it minted in its own offer, which
// is what it will have to recognise when this peer rings its doorbell. `token` was
// read out of A's offer QR before any of this ran, so this pins the exact value
// rather than merely "some 32 bytes".
assertArrayEquals(token, contactForA.doorbellTokenIssued)
// The two sides are mirror images: what A issued is what B must present, and the
// other way round. Nothing here may be confused with the peer's half.
assertArrayEquals(contactForA.doorbellTokenIssued, contactForB.doorbellToken)
assertArrayEquals(contactForB.doorbellTokenIssued, contactForA.doorbellToken)
assertFalse(contactForA.doorbellTokenIssued.contentEquals(contactForA.doorbellToken))
assertEquals(32, contactForB.doorbellTokenIssued.size)
```

### Por que cada asserção está aí, e não só "32 bytes quaisquer"

O erro mais fácil de cometer nesta mudança — nomeado explicitamente na seção "5. Escolha do nome" de
`docs/changes/Pairing.kt.md` — é persistir o token do **par** duas vezes: por exemplo, copiar
`p.peer.doorbellToken` para `doorbellTokenIssued` em vez de `p.local.offer.doorbellToken`. Um teste que
só checasse `contactForA.doorbellTokenIssued.size == 32` passaria de qualquer jeito, porque os dois
tokens envolvidos têm sempre 32 bytes — o defeito não muda tamanho nenhum, só troca de lado. Por isso
cada asserção mira exatamente o valor errado que essa troca produziria:

- `assertArrayEquals(token, contactForA.doorbellTokenIssued)` fixa o **valor exato**: `token` foi lido
  do QR de A antes de qualquer `finish()` rodar, então esta linha prova que o que sai de `finish()` do
  lado de A é literalmente o mesmo token que a oferta de A carregava assinado — não um token de
  tamanho certo vindo de outro lugar.
- `assertArrayEquals(contactForA.doorbellTokenIssued, contactForB.doorbellToken)` e sua simétrica
  (`contactForB.doorbellTokenIssued` == `contactForA.doorbellToken`) são o espelho propriamente dito:
  o que A cunhou é exatamente o que B guarda como "o que o par vai apresentar", e vice-versa. Trocar os
  lados na implementação faria uma das duas falhar sem afetar a outra — as duas juntas cobrem as duas
  direções da cópia errada possível.
- `assertFalse(contactForA.doorbellTokenIssued.contentEquals(contactForA.doorbellToken))` é o
  contrapeso dentro do **mesmo** contato: prova que os dois campos de A não colapsaram no mesmo valor
  por engano (o que aconteceria, por exemplo, se `finish()` copiasse `p.local.offer.doorbellToken` para
  os dois campos, ou se a zeroização em `Pairing.kt` acontecesse antes da cópia e ambos os campos
  acabassem lendo o mesmo array já zerado por coincidência de referência).
- `assertEquals(32, contactForB.doorbellTokenIssued.size)` fecha o par de verificações de tamanho já
  existente para `doorbellToken`, agora também para `doorbellTokenIssued` — sem ela o teste checaria
  tamanho de um lado só.

Nenhuma dessas cinco é redundante com as outras quatro: cada uma isola uma forma diferente de o campo
novo estar errado (valor trocado por outro fresco, lado A/B invertido nas duas direções, os dois campos
de um mesmo contato colapsados, tamanho errado), o que é o padrão que o resto deste arquivo já segue —
provar o comportamento observável específico, não uma propriedade genérica que um bug ainda deixaria
passar.

### O que este teste não cobre

É um teste de `core`, na JVM, sobre `PairingEngine`/`PairedContact` puros — não toca a camada de
armazenamento. Ele prova que o valor certo **sai** de `finish()`; não prova que ele chega intacto ao
esquema SQL (`doorbell_token_issued`, migração v4) ou à leitura de volta em `NoMessagesController`. Essa
cobertura é responsabilidade dos testes instrumentados/Android do lado de armazenamento, fora deste
arquivo.

### Regressão

Não executado nesta passagem — mudança só de documentação retroativa; ver `docs/changes/Pairing.kt.md`
(2026-09-18) para o estado de validação declarado da mudança de código em si.
