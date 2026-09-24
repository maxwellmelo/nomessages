# Pairing.kt — mudanças

## 2026-09-17 — QR formato 2: o bundle PQXDH sai do QR, vira busca por Tor autenticada por hash (T4.16)

### Motivo

Medido no aparelho real do usuário (Galaxy Note10+, câmera real): o QR de pareamento formato 1 tinha
~2950 bytes → **QR versão 40 no nível de correção L, 177 módulos por lado** — a câmera do celular não
conseguia ler nem preenchendo um monitor inteiro. 1569 desses bytes eram só a chave pública Kyber-1024
do bundle PQXDH obrigatório do libsignal (`docs/development/protocol-report.md`, item 2).

Decisão: **tirar o bundle de chaves do QR por completo** e buscá-lo por Tor, autenticado por um hash
SHA-256 comprometido dentro da parte assinada em Ed25519 do QR.

---

### 1. Layout de campos assinados: 10 campos, formato 1 → formato 2

`Offer` é uma `data class` privada; `kinds` do `Proto.decode`/`Proto.encode` passou de `"ibbbbibbbb"`
para `PairingEngine.OFFER_FIELD_KINDS = "ibbibbbbbb"`.

**Como era antes**

```kotlin
private data class Offer(val ed:ByteArray,val signal:ByteArray,val ephemeral:ByteArray,val onion:String,val created:Long,val nonce:ByteArray,val bundle:SignalBundle,val reply:ByteArray,val signature:ByteArray) {
    private fun fields()=listOf(1L,ed,signal,ephemeral,onion.toByteArray(Charsets.US_ASCII),created,nonce,bundle.encode(),reply)
    // "nomessages-offer-v1": CBOR domain separator
    fun canonical()=cbor("nomessages-offer-v1",*fields().toTypedArray())
```

| # | campo | bytes | formato 1 |
|---|-------|-------|-----------|
| 1 | version | 1 | sempre `1` |
| 2 | ed | 32 | chave pública de identidade Ed25519 |
| 3 | signal | 33 | chave de identidade X25519 do bundle |
| 4 | ephemeral | 33 | pre-key assinada do bundle |
| 5 | onion | 62 | onion de mensagens |
| 6 | created | 5 | epoch seconds |
| 7 | nonce | 16 | |
| 8 | bundle | ~1832 | `SignalBundle.encode()` inteiro, Kyber-1024 incluído |
| 9 | reply | 0/32 | só na resposta |
| 10 | signature | 64 | |

**Como ficou**

```kotlin
private data class Offer(val ed:ByteArray,val onion:String,val created:Long,val nonce:ByteArray,val bundleHash:ByteArray,val doorbellKey:ByteArray,val doorbellToken:ByteArray,val reply:ByteArray,val signature:ByteArray) {
    private fun fields()=listOf(2L,ed,onion.toByteArray(Charsets.US_ASCII),created,nonce,bundleHash,doorbellKey,doorbellToken,reply)
    // "nomessages-offer-v2": CBOR domain separator, bumped from "nomessages-offer-v1" on 2026-09-17
    // because the signed field list itself changed.
    fun canonical()=cbor("nomessages-offer-v2",*fields().toTypedArray())
```

| # | campo | bytes | formato 2 | nota |
|---|-------|-------|-----------|------|
| 1 | version | 1 | sempre `2` | |
| 2 | ed | 32 | igual | chave pública de identidade Ed25519 |
| 3 | onion | 62 | igual | onion de mensagens, `<56 chars>.onion`, US-ASCII |
| 4 | created | 5 | igual | epoch seconds, varint |
| 5 | nonce | 16 | igual | agora também é o seletor de `BundleRequest` |
| 6 | **bundleHash** | 32 | **novo** | `SHA-256(SignalBundle.encode())` |
| 7 | **doorbellKey** | 32 | **novo** | chave de identidade da onion de campainha — reservado p/ T4.17 |
| 8 | **doorbellToken** | 32 | **novo** | aleatório, único por oferta — reservado p/ T4.17 |
| 9 | reply | 0/32 | igual | digest da oferta respondida (só na resposta) |
| 10 | signature | 64 | igual | Ed25519 sobre `canonical()` dos campos 1–9 |

**O que saiu:** `signal` (33 bytes), `ephemeral` (33 bytes) e o blob `bundle` inteiro (~1832 bytes) —
juntos, a fonte quase total dos ~2950 bytes do formato 1. **O que entrou:** `bundleHash` (32 bytes,
fixo, independentemente do tamanho do bundle), mais os dois campos de campainha (64 bytes juntos,
reservados — ver seção 6). Resultado líquido: uma oferta de ~1832+ bytes de payload assinado vira uma
de 294 bytes (oferta) / 326 bytes (resposta).

Decodificação, `Offer.decode`: rejeita duas vezes um QR formato 1 — o prefixo não bate
(`nomessages:1:` vs `nomessages:2:`), e mesmo que alguém force o prefixo certo com o corpo antigo,
`require(f[0]==2L)` rejeita pelo campo de versão.

---

### 2. `bundleHash` — o que substitui a repetição de `signal`/`ephemeral`

No formato 1, a defesa contra um bundle trocado era repetir `signal`/`ephemeral` como campos externos
assinados e comparar com o que vinha dentro do `bundle` (`require(signal.contentEquals(bundle.identity)
&& eph.contentEquals(bundle.pre))`). Sem o bundle no QR, essa comparação não existe mais — no lugar,
`bundleHash = SHA-256(SignalBundle.encode())` cobre o bundle **inteiro**, incluindo a chave de
identidade e a one-time prekey, e é verificado uma única vez em `acceptPeerBundle` (seção 4) quando o
bundle chega por Tor. É uma defesa mais forte, não um substituto mais fraco: format 1 só amarrava dois
dos sete campos do bundle; o hash amarra todos.

---

### 3. Bumps de domain separator CBOR

`nomessages-offer-v1` → `nomessages-offer-v2` (a lista de campos assinados mudou de forma) e
`nomessages-transcript-v1` → `nomessages-transcript-v2` (as ofertas codificadas que alimentam o
transcript mudaram de forma). Reaproveitar um separador entre duas codificações incompatíveis é
exatamente a ambiguidade que um domain separator existe para prevenir. Pré-lançamento, sem migração —
nenhum QR ou transcript formato 1 precisa continuar válido.

```kotlin
val transcript=crypto.hash(cbor("nomessages-transcript-v2",first.encode(),second.encode()))
```

O transcript continua cobrindo as duas ofertas completas, e cada oferta agora compromete seu próprio
`bundleHash` — então o SAS de seis dígitos que os dois humanos comparam em voz alta passa a autenticar
transitivamente os dois bundles PQXDH, mesmo sem nenhum dos dois aparelhos tê-los visto no momento do
QR.

---

### 4. `PairingProgress` ganha `peerBundleNonce`/`peerBundleReady`; novo `acceptPeerBundle`

**Como era antes**

```kotlin
data class PairingProgress(val handle:String,val responseQr:String?,val sas:String,val peerId:String,val peerOnion:String,val expiresAt:Long)
```

**Como ficou**

```kotlin
data class PairingProgress(
    val handle:String,
    val responseQr:String?,
    val sas:String,
    val peerId:String,
    val peerOnion:String,
    val expiresAt:Long,
    val peerBundleNonce:ByteArray,
    val peerBundleReady:Boolean,
)
```

`peerBundleNonce` é o nonce da oferta **do peer** — é o que um `BundleRequest` enviado a `peerOnion`
precisa carregar, porque o QR não transporta mais o bundle de 1,8 KB, só o hash dele.
`peerBundleReady` é `false` até `acceptPeerBundle` aceitar um bundle cujo hash bate com o que o peer
assinou; `finish` recusa terminar enquanto for `false` (seção 5).

```kotlin
/**
 * Accepts the peer's PQXDH bundle, fetched over Tor, for the pending exchange [handle].
 *
 * The only thing that makes it trustworthy is the hash comparison below: `bundleHash` is inside
 * the Ed25519-signed part of the offer this device already verified, so a transport that swaps
 * the bytes produces a mismatch here. Any failure burns the exchange rather than leaving a
 * half-verified one behind.
 */
fun acceptPeerBundle(handle:String,encoded:ByteArray):PairingProgress {
    val p=get(handle)
    try {
        require(p.peerBundle==null) { "Key bundle already received for this pairing" }
        require(java.security.MessageDigest.isEqual(crypto.hash(encoded),p.peer.bundleHash)) {
            "Key bundle does not match the hash signed in the QR code"
        }
        val bundle=SignalBundle.decode(encoded)
        require(bundle.identity.size==33 && bundle.pre.size==33) { "Invalid key bundle" }
        p.peerBundle=bundle
        return progress(p,null,sas(p))
    } catch(e:Exception) { cancel(); throw e }
}
```

`MessageDigest.isEqual` (comparação em tempo constante) em vez de `ByteArray.contentEquals` — mesma
prudência que já se espera de qualquer comparação de material de segurança. Qualquer falha (hash
errado, bundle malformado) chama `cancel()`: a troca inteira é queimada, não fica um estado
meio-verificado.

**Fluxo ponta a ponta:**

1. A abre "mostrar meu QR" → `createOffer()` → QR de oferta com nonce `Na` e `hash(bundleA)`.
2. B escaneia → `respond()` → SAS + QR de resposta com nonce `Nb` e `hash(bundleB)`.
3. **B conecta na onion de A** (`MessagingEngine.fetchPeerBundle`), manda `BundleRequest(Na)`; A
   responde `BundleResponse(Na, bundleA)`.
4. A escaneia o QR de resposta de B → `processResponse()` → mesmo SAS.
5. **A conecta na onion de B**, manda `BundleRequest(Nb)`; B responde `BundleResponse(Nb, bundleB)`.
6. Cada lado chama `acceptPeerBundle(handle, bytes)`.
7. Os dois lados comparam o SAS de seis dígitos em voz alta, confirmam, trocam os QRs
   `nomessages-confirm:2:`.
8. `finish()` exige o bundle do peer presente e chama `identity.sessions.establish(peer, bundle)`.

Regra de direção: **quem acabou de ler um QR busca de quem o mostrou.** Os dois lados precisam do Tor
pronto; se ainda não estiver, a UI espera (`WAITING_FOR_TOR`) até o prazo da troca, em vez de falhar
na hora.

---

### 5. `finish` agora exige o bundle já buscado

**Como era antes**

```kotlin
fun finish(handle:String,peerConfirmationQr:String):PairedContact {
    val p=get(handle)
    ...
    identity.sessions.establish(p.peer.ed.hex(),p.peer.bundle)
```

**Como ficou**

```kotlin
fun finish(handle:String,peerConfirmationQr:String):PairedContact {
    val p=get(handle)
    val own=p.localSignature ?: error("Confirm the SAS locally first")
    try {
        val bundle=p.peerBundle ?: error("The other device's key bundle has not arrived yet")
        ...
        identity.sessions.establish(p.peer.ed.hex(),bundle)
```

No formato 1 `p.peer.bundle` sempre existia — chegava dentro do próprio QR. No formato 2 `Offer` não
carrega mais bundle nenhum (só `bundleHash`); o bundle de verdade só existe em `Pending.peerBundle`,
preenchido por `acceptPeerBundle`, e `finish` falha explicitamente (`"key bundle has not arrived
yet"`) se a UI tentar terminar antes da busca por Tor completar — em vez de um `NullPointerException`
ou de estabelecer sessão com material vazio.

---

### 6. `bundleFor(nonce)` — a "resposta" do próprio dispositivo ao `BundleRequest` do peer

```kotlin
/**
 * The encoded bundle behind one of **this device's own** offers, or null.
 *
 * The rate limit is structural rather than numeric: an answer requires a nonce that this device
 * itself minted and published in a QR, inside that offer's own [PENDING_TTL_SECONDS] window, and
 * each nonce is answered exactly once. An unknown, stale, or already-consumed nonce yields null,
 * so nothing is revealed about offers that are not live.
 */
fun bundleFor(nonce:ByteArray):ByteArray? {
    if(nonce.size!=16) return null
    val match=listOfNotNull(offer,pending?.local,superseded)
        .firstOrNull { java.security.MessageDigest.isEqual(it.offer.nonce,nonce) } ?: return null
    if(match.answered) return null
    val age=now()-match.offer.created
    if(age<0 || age>=PENDING_TTL_SECONDS) return null
    match.answered=true
    return match.bundle.encode()
}
```

Regra de "exatamente uma vez, só para ofertas próprias e vivas": um nonce só é respondido quando (a)
tem 16 bytes; (b) bate com uma oferta que **este próprio dispositivo** cunhou — a que está na tela
agora (`offer`), a que está dentro da troca pendente (`pending.local`), ou a única oferta
`superseded` que o auto-refresh acabou de substituir; (c) essa oferta ainda está dentro da própria
janela `PENDING_TTL_SECONDS`; e (d) **ainda não foi respondida** (`match.answered`). Qualquer outra
coisa devolve `null` — e o chamador (`MessagingEngine`, fora deste arquivo) responde com **silêncio,
não erro**, para não confirmar a uma sondagem de nonce arbitrária que esta onion está rodando uma tela
de pareamento.

---

### 7. Retenção do offer `superseded` — por que guardar exatamente um

```kotlin
/**
 * The offer the automatic refresh on the "show my QR" screen has just replaced, kept answerable
 * until its own [PENDING_TTL_SECONDS] window ends. Without it, a peer that scanned the QR in its
 * last seconds would have its bundle fetch fail against an offer this device threw away while
 * the Tor circuit was still being built. At most one is retained; see [createOffer].
 */
private var superseded:LocalOffer?=null
```

```kotlin
fun createOffer():String {
    val previous=offer
    val stale=listOfNotNull(pending?.local,superseded).filter { it!==previous }
    pending?.localSignature?.fill(0)
    pending=null; offer=null; superseded=null
    stale.distinct().forEach(::burn)
    superseded=previous
    val local=newOffer(ByteArray(0)); offer=local
    return local.offer.qr()
}
```

A tela de "mostrar meu QR" atualiza a oferta sozinha quando ela expira (ver
`app/.../ui/PairingLifecycle.kt`, `T4.16`). Sem reter a oferta anterior, um peer que escaneou o QR
nos últimos segundos antes do refresh teria a própria busca do bundle falhar: o nonce que ele guardou
já não corresponderia a nenhuma oferta viva neste dispositivo. `superseded` mantém exatamente **uma**
oferta anterior respondível pelo resto da janela de 300 s dela, sem estender indefinidamente a
superfície de resposta. Um reinício explícito — abrir uma nova troca pendente — queima tudo
(`stale.forEach(::burn)`), porque nesse caso não há mais ambiguidade sobre qual oferta o usuário quer
mostrar.

---

### 8. Prazos: `OFFER_TTL_SECONDS = 120` (inalterado) vs. novo `PENDING_TTL_SECONDS = 300`

```kotlin
/** How long a QR may still be scanned. Unchanged from format 1. */
const val OFFER_TTL_SECONDS=120L
/**
 * How long a staged exchange has to finish the Tor bundle fetch, the SAS comparison and the
 * confirmation QRs. Longer than [OFFER_TTL_SECONDS] on purpose: building a circuit to a
 * fresh onion service takes 5-40 s, which used to be time the format-1 flow never needed.
 */
const val PENDING_TTL_SECONDS=300L
```

`OFFER_TTL_SECONDS` continua governando quanto tempo um QR pode ainda ser **escaneado** — não mudou.
`PENDING_TTL_SECONDS` é novo e governa quanto tempo uma **troca já em andamento** (depois do scan) tem
para terminar a busca do bundle por Tor + comparação do SAS + troca dos dois QRs de confirmação. Segue
ancorado na oferta **mais antiga** das duas (`expiresAt=minOf(first.created,second.created)+
PENDING_TTL_SECONDS`), então não é reiniciado a cada etapa. Um circuito Tor até uma onion recém
publicada custa rotineiramente 5–40 s — tempo que o fluxo formato 1 nunca precisava gastar, porque o
bundle já vinha dentro do próprio QR.

Teste (`ProtocolTest.responseKeepsOriginalDeadlineAndDoesNotRedisplayTheConsumedOffer`):

```kotlin
assertEquals(1000L + PairingEngine.PENDING_TTL_SECONDS, response.expiresAt) // era 1120L (1000+120)
```

---

### 9. `PairedContact` ganha `doorbellKey`/`doorbellToken`

**Como era antes**

```kotlin
data class PairedContact(val peerId:String,val publicKey:ByteArray,val onion:String,val pairedAt:Long,val evidence:ByteArray)
```

**Como ficou**

```kotlin
data class PairedContact(
    val peerId:String,
    val publicKey:ByteArray,
    val onion:String,
    val pairedAt:Long,
    val evidence:ByteArray,
    val doorbellKey:ByteArray,
    val doorbellToken:ByteArray,
)
```

```kotlin
return PairedContact(p.peer.ed.hex(),p.peer.ed.copyOf(),p.peer.onion,p.statement.pairedAt,evidence,
    p.peer.doorbellKey.copyOf(),p.peer.doorbellToken.copyOf())
```

---

### 10. `doorbellKey`/`doorbellToken` — RESERVADO para T4.17, sem comportamento

Escopo veio de um ajuste no meio da tarefa, para evitar uma segunda migração de formato de QR mais
tarde. Nota de design: `docs/development/doorbell-design.md`.

- **`doorbellKey`** (32 bytes): a chave de identidade Ed25519 de uma **segunda onion v3 dedicada**,
  derivada da própria seed do vault — chave `meta` **`doorbell_seed`**, 32 bytes aleatórios, gerada no
  primeiro uso. Deliberadamente uma seed **diferente** de `onion_seed`, para que o endereço de
  campainha e o endereço de mensagens sejam desvinculáveis: uma seed só permitiria que quem tivesse
  qualquer um dos dois endereços confirmasse que pertencem ao mesmo aparelho.
- **`doorbellToken`** (32 bytes): fresco a cada oferta, igual ao nonce — comentário no código:

  ```kotlin
  // A fresh doorbell token per offer, exactly like the nonce: the peer of THIS exchange is the
  // only party that ever learns it, so two contacts can never present each other's token.
  val unsigned=Offer(identity.publicKey,onion,now(),crypto.random(16),crypto.hash(bundle.encode()),
      doorbellKey.copyOf(),crypto.random(32),reply,ByteArray(0))
  ```

- Os dois estão dentro dos bytes assinados canônicos e, portanto, dentro do transcript do SAS — não
  existe caminho de hash ou assinatura separado para eles.
- Validação, mesmo sem uso ainda: uma chave de campainha toda-zero é recusada
  (`require(doorbellKey.size==32 && doorbellKey.any { it.toInt()!=0 })`), tanto no construtor de
  `PairingEngine` quanto em `Offer.decode` — aceitar uma deixaria um endereço inutilizável dentro de
  um registro de contato assinado.
- **Nenhum comportamento**: nada escuta na onion de campainha ainda; nada em `MessagingEngine` ou na UI
  reage a `doorbellKey`/`doorbellToken`. Só é publicado e assinado agora para que o formato de QR não
  precise mudar uma segunda vez quando T4.17 chegar.

---

### 11. DESVIO explícito: chave em vez de texto de endereço

O ajuste pediu um campo `doorbellOnion` carregando o **texto do endereço de 62 caracteres**. Ele é
carregado como a **chave de identidade de 32 bytes** em vez disso.

**Motivo, com número medido.** Um endereço onion v3 é
`base32(pubkey32 || checksum2 || version1) + ".onion"` — 62 caracteres US-ASCII codificando 35 bytes,
dos quais só os 32 bytes da chave não são deriváveis (o checksum e o byte de versão são calculados a
partir dela). Com o endereço em texto, **a resposta media QR versão 15** — acima do orçamento
versão 14 que o próprio ajuste definiu; com a chave, mede versão 14. Nada se perde:
`OnionAddress.address()` (novo, `app/.../storage/OnionAddress.kt`) reconstrói o texto exato do
endereço a partir da chave, e o controller grava esse endereço reconstruído em
`contacts.doorbell_onion`.

**Segundo desvio, menor.** `doorbell_seed` é uma chave **`meta`**, não um namespace `opaque_blobs`
como o ajuste tinha redigido. Motivo: `onion_seed` — o registro do qual ela é irmã — vive em `meta`, e
`SPEC.md` §10 enumera a qual tabela cada registro de runtime pertence; uma irmã na outra tabela
contradiria essa lista sem nenhum ganho.

---

### 12. Números medidos do QR (2026-09-17, `ZXing Encoder.encode(payload, ErrorCorrectionLevel.L).version`)

| payload | base64url + prefixo | versão do QR em L |
|---------|----------------------|--------------------|
| oferta | 405 bytes | **13** |
| resposta | 448 bytes | **14** |
| confirmação | 317 bytes | **11** |

Orçamento afirmado em `ProtocolTest`: ≤ 500 bytes e ≤ **versão 14**. A versão 14 comporta 458 bytes no
nível L, então a resposta tem **10 bytes de folga** — qualquer coisa acrescentada à oferta daqui para
frente precisa re-derivar o orçamento, não apenas relaxar a asserção. Antes de os campos de campainha
entrarem, a mesma medição dava oferta 315 B / v11, resposta 357 B / v12, confirmação 317 B / v11 — ou
seja, os dois campos de campainha (64 bytes) sozinhos empurraram a resposta de v12 para v14.

Para referência, o formato 1 produzia ~2950 bytes → versão 40 (177 módulos por lado).

### Vantagens

- QR de pareamento escaneável por câmera de celular de verdade, resolvendo uma falha de
  disponibilidade medida em aparelho real (não hipotética).
- A vinculação identidade ↔ onion ↔ bundle continua intacta: `ed`, `onion` e `bundleHash` estão dentro
  de uma única assinatura Ed25519, e o SAS de seis dígitos cobre o transcript das duas ofertas
  completas — a comparação falada dos humanos continua cobrindo transitivamente os dois bundles, sem
  que nenhum dos aparelhos os tenha visto no momento do QR.
- A busca por Tor não introduz MITM: o transporte só entrega bytes; a verificação é inteiramente a
  comparação de hash em `acceptPeerBundle`. Um transporte que troca, repete ou fabrica o bundle produz
  incompatibilidade e a troca é queimada.
- Superfície nova exposta é mínima e medida: um estranho não pareado que conhece a onion pode mandar
  um pacote BUNDLE; ele chega a um decoder, um token bucket e uma busca de nonce — nunca à transação
  do vault, nunca ao ratchet — e não aprende nada a menos que já tenha um nonce de um QR ao vivo.
- Campo de campainha entra pronto para T4.17 sem exigir uma segunda migração de formato de QR.

### Validação (2026-09-17)

`gradle-wsl.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` → `BUILD SUCCESSFUL in 8m 35s`. `:core:test` — 95 testes, 0 falhas, 0
erros, 2 pulados. `:app:testDebugUnitTest` — 60 testes, 0 falhas, 0 erros, 1 pulado. `:app:lintDebug` —
0 erros (42 avisos, 6 hints, mesmo baseline de T4.15). Os dois APKs foram gerados.

**NÃO executado, e não deve ser alegado:** nenhuma validação em dispositivo/emulador. Nenhum emulador
foi usado, nenhuma captura de tela, `scripts/emulator-pair.sh` não rodou ao vivo. O novo teste
instrumentado `doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration`
(`AndroidVaultStorageTest.kt`) foi escrito mas **nunca executado**. Validação ao vivo com dois
emuladores fica para uma fase seguinte.

`scripts/emulator-pair.sh` **não precisou de nenhuma mudança**: ele repassa os bytes do QR pelo gancho
de broadcast `DUMP_QR`/`INJECT_QR` e nunca fixa o prefixo `nomessages:1:`/`nomessages:2:` no código.


## 2026-09-17 (2) — uma só noção de "oferta ainda válida": `processResponse` alinhado a `bundleFor` (T4.16, achado do run5)

Correção posterior à seção acima, feita depois da validação ao vivo em dois emuladores. O defeito é
de **consistência de projeto** na lógica que eu mesmo escrevi algumas horas antes, não um descuido
de digitação: o mesmo fluxo passou a ter **duas noções diferentes de "esta oferta ainda vale"**.

### O que aconteceu na validação ao vivo

Pareamento de velocidade normal, sem corrida forçada. A oferta de A expirou em `21:36:49`, a
**regeneração automática funcionou** como projetada, e mesmo assim A morreu com um
`Could not complete` genérico ao ler o QR de resposta de B, sem nada útil no logcat. Evidência
completa: `docs/development/build-logs/pairing-v2-20260917/session-log.md` e a seção run5 de
`docs/development/device-verification.md`.

### Como era antes

```kotlin
fun processResponse(responseQr:String):PairingProgress {
    val local=offer ?: error("No active offer")
    try {
        fresh(local.offer)                    // exige idade < OFFER_TTL_SECONDS (120)
        val remote=readOffer(responseQr)      // readOffer TAMBÉM aplica fresh() na resposta
        require(remote.reply.contentEquals(crypto.hash(local.offer.encode()))) { "Response does not match offer" }
        ...
```

Duas causas **independentes**, e é isso que torna o defeito traiçoeiro — corrigir uma só o faz
reaparecer com outra cara:

1. **`processResponse` nunca olhava `superseded`.** `bundleFor` olhava (a divergência nº 5 da seção
   anterior existe exatamente para isso: a regeneração de 120 s pode trocar qual oferta é a "atual"
   entre o instante em que o par escaneia e o instante em que a resposta dele volta). A resposta de B
   responde ao *digest da oferta antiga*, então o `require` falhava. A tolerância documentada cobria
   a busca do bundle e **não** o QR de resposta que vem logo depois no mesmo fluxo.
2. **`readOffer` aplicava o teto de 120 s à resposta do par.** Uma resposta não é um convite
   permanente: ela pertence a uma troca já montada, a tela de B a mantém no ar pelos 300 s inteiros
   e **mostra esse relógio** ("Expires in 04:02" na captura do run5). O motor exigia 120 s enquanto a
   UI prometia 300 s. As duas coisas discordavam, e a discordância aparecia como erro genérico.

### Como ficou

Uma definição só, usada pelos dois caminhos que precisam reconhecer uma oferta depois do fato:

```kotlin
private fun within(value:Offer,seconds:Long):Boolean { val age=now()-value.created; return age>=0 && age<seconds }
private fun live(candidate:LocalOffer)=within(candidate.offer,PENDING_TTL_SECONDS)

/** A oferta na tela e a única `superseded` retida, cada uma dentro da janela de 300 s dela. */
private fun standingOffers():List<LocalOffer> = listOfNotNull(offer,superseded).distinct().filter(::live)
```

- `processResponse` casa o `reply` contra **todas** as `standingOffers()` e lê a resposta com
  `readOffer(responseQr, PENDING_TTL_SECONDS)`.
- `bundleFor` passou a usar `standingOffers()` mais `pending?.local` (o par pode ainda estar buscando
  o bundle depois do SAS já ter aparecido nas duas telas) — mesmo conjunto, mesma janela.
- `respond` continua com `readOffer(offerQr, OFFER_TTL_SECONDS)`: um **convite permanente** é
  regenerado a cada 120 s, então lê-lo mais velho que isso é ler algo que a outra tela já substituiu.
  Esse é o único lugar onde o teto de 120 s continua sendo a regra certa.
- Depois de montar a troca, a oferta que **não** foi respondida é queimada ali mesmo, em vez de ficar
  respondível: a partir do staging só `cancel()`/`finish()` tocam material de chave.

### Por que alargar a janela da resposta não afrouxa nada

Uma resposta só é consumível pelo aparelho que cunhou a oferta que ela nomeia (`reply` é o digest
daquela oferta), só **uma vez** (`consumed`), e a troca que ela monta continua expirando
`PENDING_TTL_SECONDS` depois da **mais antiga** das duas ofertas. O que muda é que o motor passa a
honrar o mesmo prazo que a tela já mostrava.

### `confirm`/`acceptPeerBundle`/`finish` já estavam corretos

Verificado, não presumido. Os três passam por `get(handle)`, cujo **único** prazo sempre foi
`PENDING_TTL_SECONDS` via `Pending.expires` — não há nenhum `fresh()` de 120 s nesse caminho. E a
inconsistência "atual vs. substituída" não se aplica a eles por construção: uma **oferta** é
substituída, uma **troca** nunca é. Existe no máximo um `pending`, e o QR de confirmação carrega o
`PairStatement` completo, que o amarra àquela troca e a nenhuma outra. O KDoc de `get()` passou a
dizer isso explicitamente, para que a próxima pessoa não precise refazer a análise.

### Cópia defensiva de `doorbellKey`

Aproveitando a mesma passagem pelo arquivo, e por recomendação do agente de validação: o construtor
guardava o `ByteArray` do chamador **por referência** e o lia depois, dentro de `createOffer()`. Isso
é um contrato não escrito de "não zere este array" pendurado num construtor público, e o
`DecoyFactory` o violou — quebrando a criação de cofre no app inteiro (defeito (a) do run5, corrigido
no ponto de chamada pelo agente de validação). Agora o construtor faz `doorbellKey.copyOf()`. São 32
bytes que eliminam a classe inteira de uso-após-limpeza, e uma chave pública não é material secreto
cuja vida alguém precise encurtar.

### Vantagens

- Um pareamento de velocidade normal deixa de falhar quando a oferta se regenera entre a leitura e a
  resposta — que foi o caso real observado, não uma corrida artificial.
- Motor e UI passam a concordar sobre o prazo: o relógio de 300 s na tela agora é o prazo de verdade.
- Uma definição só de "oferta ainda viva" em vez de duas, então o próximo caminho que precisar
  reconhecer uma oferta herda a regra certa em vez de inventar a terceira.
- `PairingEngine` deixa de ter contrato implícito sobre a vida do array do chamador.

### Regressão

`:core:test` **97 testes, 2 pulados, 0 falhas, 0 erros**; `:app:testDebugUnitTest` **61 testes,
1 pulado, 0 falhas, 0 erros**; `:app:lintDebug` **0 erros** (42 avisos, 6 hints — mesma linha de
base). O teste novo foi conferido contra as duas causas separadamente: revertendo **só** a consulta a
`superseded`, ou **só** o teto de 120 s, ou as duas, ele falha nos três casos — ou seja, cobre as
duas metades de verdade, e não por acidente.


## 2026-09-17 (3) — exibição e validade separadas: até 3 ofertas simultaneamente válidas (T4.16)

Terceira passagem, por decisão de projeto do coordenador. A correção anterior ("2026-09-17 (2)")
unificou a **regra** de validade, mas manteve o **conjunto** pequeno demais.

### Como era antes

```kotlin
private var offer:LocalOffer?=null
private var superseded:LocalOffer?=null
private fun standingOffers():List<LocalOffer> = listOfNotNull(offer,superseded).distinct().filter(::live)
```

Exatamente **duas** ofertas. Conferi a aritmética contra o próprio código em vez de aceitar a
afirmação: uma oferta vive `PENDING_TTL_SECONDS` (300 s) e a tela gira a cada `OFFER_TTL_SECONDS`
(120 s), então `ceil(300 / 120) = 3` se sobrepõem no pico. Com ofertas em t = 0, 120 e 240, no
instante t = 240 as três estão dentro da janela própria — mas `createOffer()` queimava a de t = 0 ao
criar a de t = 240. Ela morria em t = 240 em vez de t = 300: **60 s de validade perdidos**, e um par
segurando aquela oferta falhava exatamente como no defeito original.

### Como ficou

```kotlin
/** Ofertas emitidas e ainda não queimadas, mais nova primeiro. */
private val emitted=mutableListOf<LocalOffer>()

private fun prune() {
    val expired=emitted.filterNot(::live)
    emitted.removeAll { !live(it) }
    while(emitted.size>MAX_LIVE_OFFERS) emitted.removeAt(emitted.lastIndex).let(::burn)
    expired.forEach(::burn)
}

private fun standingOffers():List<LocalOffer> { prune(); return emitted.toList() }
```

**Exibição e validade passaram a ser coisas separadas.** A primeira entrada é a que está na tela; a
rotação de 120 s é uma decisão de *exibição* e não invalida nada. Toda oferta emitida continua apta a
receber resposta e a responder `BundleRequest` pelos 300 s **dela**, contados do `created` dela.

- `MAX_LIVE_OFFERS = 3`, derivado e não escolhido — é `ceil(PENDING_TTL / OFFER_TTL)`. O teto existe
  para o caso patológico (relógio maluco, chamador em laço); em regime normal ele nunca corta nada.
- Uma entrada envelhece **no próprio cronograma**, nunca por ser empurrada por uma mais nova; a única
  exceção é o teto, e a poda faz expiração primeiro justamente para o teto quase nunca morder.
- `standingOffers()`, `bundleFor` e `processResponse` varrem o conjunto inteiro.
- Ao montar a troca, as demais são queimadas via `discardBundle`. Isso é no **staging**, não no
  `finish()`: este aparelho só comporta uma troca, então as outras não levam mais a lugar nenhum, e
  responder `BundleRequest` por uma oferta que nunca poderá concluir é precisamente o que a regra de
  limite de taxa proíbe. Queimar antes também apaga o material de chave mais cedo e não perde nada —
  uma troca cancelada não as ressuscita, ela gera um QR novo.
- `prune()` roda na leitura, então material de chave vencido é apagado quando alguém pergunta, em vez
  de esperar o próximo `createOffer()`.

`doorbellKey.copyOf()` no construtor (da passagem anterior) continua no lugar — confirmado, não
presumido.

### UI

Um cronômetro só, como pedido, com duas leituras. Enquanto só o QR próprio está na tela ele lê
**"Novo QR em MM:SS"** e não fica vermelho ao zerar: chegar a zero gira o QR e não custa nada a
ninguém. Depois que a troca é montada, volta a ser **"Expira em MM:SS"** com a progressão
neutro → âmbar → vermelho que o especialista de UI introduziu, intacta. As duas leituras se decidem
por `pairing.sas == null`, estado que o composable já usava para se organizar — nenhum estado
condicional novo foi inventado.

Abaixo do cronômetro, legenda fixa enquanto houver oferta na tela: **"Quem já escaneou o QR anterior
ainda consegue concluir o pareamento."**

E o aviso transitório `qr_refreshed` foi **removido**, não reescrito: ele dizia *"Peça para escanear o
novo"*, que com várias ofertas válidas ao mesmo tempo é conselho **errado** — rescanear é exatamente o
que não é preciso. Deixá-lo seria embarcar conscientemente um defeito de texto. As duas strings
saíram dos dois locales junto com o estado `refreshed`, então não sobrou recurso órfão para o lint.

### Vantagens

- Fecha a janela de 60 s que a correção anterior ainda deixava aberta, no cenário exato que a
  validação ao vivo vai exercitar (esperar ~150 s entre a leitura e a resposta).
- O conjunto é limitado por construção: no máximo 3 ofertas, com material de chave apagado na poda.
- A UI deixa de dar conselho errado e passa a explicar a rotação antes de ela acontecer, em vez de
  reagir depois.

### Regressão

`:core:test` **100 testes, 2 pulados, 0 falhas, 0 erros**; `:app:testDebugUnitTest` **61 testes,
1 pulado, 0 falhas, 0 erros**; `:app:lintDebug` **0 erros** (42 avisos, 6 hints — mesma linha de
base, inclusive sem `UnusedResources` novo); `:app:assembleDebug` e `:app:assembleDebugAndroidTest`
geram os dois APKs. Os testes novos foram conferidos por mutação: baixando `MAX_LIVE_OFFERS` para 2,
os dois falham — o primeiro no comportamento observável ("offer 0 must still be answerable"), não na
constante.


## 2026-09-17 (4) — aquisição e confirmação em relógios separados (T4.16, achado do run6)

Quarta passagem. O run6 provou a correção anterior (três ofertas respondíveis, rotações verificadas
por hash, os cenários de 1 e 2 rotações **aceitos** pelo protocolo) e, ao fazer isso, expôs um
problema estrutural que só aparece rodando: as duas tentativas de 2 rotações foram aceitas e mesmo
assim **acabaram o tempo** antes de concluir.

### Como era antes

```kotlin
val expiresAt = minOf(first.created, second.created) + PENDING_TTL_SECONDS
```

Um relógio só, ancorado na criação da oferta, para duas fases muito diferentes. Com duas rotações
gastas na aquisição (~240 s), sobravam **4 a 35 s** para tudo o que vem depois de o SAS existir: a
busca do bundle pela rede Tor, ler seis dígitos em voz alta, confirmar nas duas telas e trocar dois
QRs de confirmação. O run6b, com uma rotação só, concluiu com 165 s de folga — ou seja, a mecânica
estava certa; a **aritmética do orçamento** é que não fechava.

### Como ficou

```kotlin
val expiresAt = now() + CONFIRMATION_TTL_SECONDS
```

Duas fases, dois relógios:

| fase | constante | contado a partir de | o que cobre |
|---|---|---|---|
| aquisição | `PENDING_TTL_SECONDS` = **300 s** | `created` de **cada** oferta | quanto tempo cada uma das até 3 ofertas emitidas continua respondível: casar uma resposta e responder `BundleRequest` |
| confirmação | `CONFIRMATION_TTL_SECONDS` = **240 s** | o instante do **staging** | busca do bundle pela Tor, SAS falado, alias, os dois QRs de confirmação |

**Mudança consciente de semântica, e ela precisa ser lida como tal:** o "300 s cobre busca + SAS +
confirmação" do spec original **deixou de valer**. O tempo total de relógio de parede, da primeira
oferta até um pareamento concluído, agora pode passar de 300 s — até ~300 s de aquisição mais 240 s
de confirmação. Isso é intencional, e é consequência direta da interação entre a sobreposição de
ofertas e o staging que o run6 mediu.

### Por que 240 s e não os 180 s propostos

Número derivado, não escolhido — e um fato que muda a conta: a busca do bundle pela rede Tor é
disparada pelo runtime **depois** do staging (`NoMessagesController.readPairing` chama
`pairingEngine.receive(code)` e **só então** `startBundleFetch(next)`), logo ela está dentro do
orçamento de confirmação, não no de aquisição.

| passo | custo |
|---|---|
| busca do bundle admitindo **uma** tentativa falha (`BUNDLE_ATTEMPT_MILLIS` 60 s + 3 s de espera + ~40 s) | ~103 s |
| duas pessoas lendo e comparando seis dígitos em voz alta | ~30 s |
| digitar o alias local | ~20 s |
| mostrar e escanear dois QRs de confirmação | ~60 s |
| **total** | **~215 s** |

240 s dá cerca de 10 % de folga sobre isso. Os 165 s do run6b são um **piso**, não um alvo: aquele
fluxo passou por relé com script e busca de primeira tentativa, o que remove justamente os dois
passos mais lentos da tabela.

A assimetria de custo também empurra para o lado generoso: curto demais custa uma cerimônia
presencial falhada — exatamente a falha de disponibilidade que esta tarefa existe para eliminar —
enquanto longo demais custa um bundle de prekey one-time parado no store por alguns minutos a mais,
num aparelho que o usuário está segurando, e que `cancel()` queima ao bloquear, ao sair da tela ou
no próprio prazo.

### Consequência: os dois lados têm prazos diferentes

Cada aparelho conta a partir do **próprio** staging, então os dois prazos diferem exatamente pelo
atraso do relé entre eles. Isso é correto, não apenas tolerável: o orçamento limita há quanto tempo o
material de chave **deste** aparelho está pendente, e o usuário deste aparelho pôde agir desde que o
SAS dele apareceu. Presencialmente os dois stagings ficam a segundos de distância, então a janela
compartilhada é praticamente o orçamento inteiro. Num relé artificialmente lento — os 250 s que o
run6 forçou de propósito para exercitar a sobreposição — a janela compartilhada encolhe por esse
atraso, o que é esperado e aceitável: não é o fluxo de duas pessoas lado a lado.

### Mensagem de erro

Inalterada de propósito. `get()` continua lançando `"Pairing expired"`, que a UI já mapeia para
`pairing_timed_out` ("O tempo para concluir o pareamento acabou. Comece de novo quando quiser."). Só
o prazo em que ela dispara mudou — nenhuma regressão para um "Could not complete" genérico.

### Regressão

`:core:test` **102 testes, 2 pulados, 0 falhas, 0 erros**; `:app:testDebugUnitTest` **61 testes,
1 pulado, 0 falhas, 0 erros**; `:app:lintDebug` **0 erros** (42 avisos, 6 hints — mesma linha de
base); os dois APKs compilam. Testes novos conferidos por mutação nas duas direções (voltar ao prazo
ancorado na oferta, e afrouxar o prazo para 100x): ambos são pegos, e o teste do cenário 6a/6c falha
com `java.lang.IllegalStateException: Pairing expired` — o sintoma real —, não com uma diferença de
aritmética.


## 2026-09-18 — o lado que faltava: `PairedContact` ganha `doorbellTokenIssued` (T4.17 fase 2)

Primeira mudança de comportamento da campainha desde que os campos ficaram reservados no T4.16. Fase
2 da tarefa: camada de armazenamento + pareamento.

### Motivo

Assimetria encontrada lendo o próprio código, não suposta. Desde o T4.16 o cofre guarda, por contato,
`doorbell_onion` e `doorbell_token` — a metade do **par** que diz onde bater e o que apresentar quando
**este** aparelho é que toca a campainha do outro. Já o token que este aparelho **cunhou** para aquele
contato — o que uma batida **vinda dele** vai apresentar, ou seja, o único valor que um ouvinte local
teria como verificar — era gerado em `PairingEngine.newOffer()` (`crypto.random(32)`), embutido dentro
da própria oferta assinada deste aparelho, e simplesmente perdido no fim da troca: vivia apenas dentro
do objeto `LocalOffer`/`Offer` transitório, que `finish()` não devolvia e que era descartado junto com
o resto do estado de pareamento.

Resultado prático: o cofre sabia tocar a campainha do par e não tinha absolutamente nada com que
reconhecer uma batida na própria. E não é um defeito recuperável depois do fato — cada lado cunha um
`crypto.random(32)` independente por troca, não derivável de nada (nem do token do par, nem da
identidade do cofre, nem de qualquer segredo compartilhado), e não existe um segundo canal autenticado
no qual combinar um token mais tarde. O pareamento presencial com SAS é a única janela em que os dois
lados aprendem algo um do outro; perder o valor ali é perdê-lo para sempre.

### 1. `PairedContact` ganha o terceiro campo, sem default

**Como era antes**

```kotlin
data class PairedContact(
    val peerId:String,
    val publicKey:ByteArray,
    val onion:String,
    val pairedAt:Long,
    val evidence:ByteArray,
    val doorbellKey:ByteArray,
    val doorbellToken:ByteArray,
)
```

**Como ficou**

```kotlin
data class PairedContact(
    val peerId:String,
    val publicKey:ByteArray,
    val onion:String,
    val pairedAt:Long,
    val evidence:ByteArray,
    val doorbellKey:ByteArray,
    val doorbellToken:ByteArray,
    val doorbellTokenIssued:ByteArray,
)
```

Sem valor default. `PairedContact` só é construído em `finish()` — o único ponto de construção no
arquivo inteiro —, então o compilador passa a **exigir** que o campo seja sempre preenchido ali. É uma
invariante verificada em tempo de compilação em vez de um default silencioso que deixaria passar um
`PairedContact` com o token do próprio cofre vazio sem que nada acusasse o erro.

O KDoc da classe também mudou de forma, não só de tamanho: em vez de descrever os dois campos de
campainha juntos, agora separa os três por **quem cada um descreve**:

```
 * - [doorbellKey] - the peer's 32-byte Ed25519 onion-service identity key for a second, dedicated
 *   onion. Where to knock.
 * - [doorbellToken] - the 32-byte secret the peer minted for this exchange and expects to be
 *   presented when *its* doorbell is rung. What to say when knocking.
 * - [doorbellTokenIssued] - the 32-byte secret **this** device minted for this exchange, inside its
 *   own signed offer (see [PairingEngine.newOffer]). It is what the peer will present when it rings
 *   *this* device's doorbell, so it is the value a local listener has to recognise.
```

`doorbellKey` e `doorbellToken` continuam descrevendo o **par** — onde bater, o que apresentar ao
bater; `doorbellTokenIssued` descreve **este aparelho** — o que aceitar de quem bate aqui. Os três
continuam cobertos pelo mesmo SAS de seis dígitos, só que por direções opostas: os dois primeiros
porque estavam dentro da oferta assinada do par; o terceiro porque estava dentro da oferta assinada
deste próprio aparelho.

### 2. `finish()` devolve o token cunhado — o último instante em que ele existe

**Como era antes**

```kotlin
identity.sessions.establish(p.peer.ed.hex(),bundle)
val signatures=if(identity.id==p.statement.first) listOf(own,sig) else listOf(sig,own)
val evidence=PairEvidence(p.statement,signatures[0],signatures[1]).encode()
pending=null
return PairedContact(p.peer.ed.hex(),p.peer.ed.copyOf(),p.peer.onion,p.statement.pairedAt,evidence,
    p.peer.doorbellKey.copyOf(),p.peer.doorbellToken.copyOf())
```

**Como ficou**

```kotlin
identity.sessions.establish(p.peer.ed.hex(),bundle)
val signatures=if(identity.id==p.statement.first) listOf(own,sig) else listOf(sig,own)
val evidence=PairEvidence(p.statement,signatures[0],signatures[1]).encode()
// The token THIS device minted in its own offer for THIS exchange - see [newOffer]. It
// is handed out here because this is the last instant it exists anywhere: the exchange
// ends, `pending` is dropped, and the offer that carried it is unreachable afterwards.
// Before T4.17 it was simply lost, which left the vault able to say "what to present at
// the peer's doorbell" but not "what to accept at mine" - an asymmetry with no way back,
// since there is no second authenticated channel on which to agree a token later.
val issued=p.local.offer.doorbellToken.copyOf()
// Zeroed only after the copy above. Safe here and nowhere earlier: an offer can age out
// of the standing set while an exchange staged against it is still live, so `burn` -
// which runs on exactly that path - deliberately does NOT touch this array.
p.local.offer.doorbellToken.fill(0)
pending=null
return PairedContact(p.peer.ed.hex(),p.peer.ed.copyOf(),p.peer.onion,p.statement.pairedAt,evidence,
    p.peer.doorbellKey.copyOf(),p.peer.doorbellToken.copyOf(),issued)
```

Note o que muda de fonte: `p.peer.doorbellKey`/`p.peer.doorbellToken` continuam lidos da **oferta do
par** (`p.peer`), exatamente como antes — são a metade do par. `issued` é lido de `p.local.offer` — a
oferta que **este aparelho** cunhou para esta troca (`p.local`, o `LocalOffer` guardado em `Pending`
desde o staging). `finish()` é o único código deste arquivo que enxerga as duas ofertas ao mesmo
tempo — a do par, já verificada, e a própria, ainda com o token intacto —, e é por isso que é o único
lugar onde a cópia pode acontecer.

### 3. Zeroização: por que em `finish`/`cancel`, e deliberadamente não em `burn`

É o ponto mais fino da mudança. `finish()` zera `p.local.offer.doorbellToken` **depois** da cópia, e
`cancel()` ganhou uma linha simétrica:

```kotlin
fun cancel() {
    val p=pending
    pending=null
    p?.localSignature?.fill(0)
    val all=(listOfNotNull(p?.local)+emitted).distinct()
    emitted.clear()
    all.forEach(::burn)
    // The doorbell tokens these offers minted die with them, exactly like `localSignature` above.
    // Zeroing is safe *here* and not inside `burn` because cancel drops every reference the
    // engine holds - the pending exchange and the whole emitted list - so no surviving offer can
    // still need its token, whereas `burn` also runs on offers that merely aged out of the
    // standing set while an exchange staged against one of them is still heading for `finish`.
    all.forEach { it.offer.doorbellToken.fill(0) }
}
```

`cancel()` zera o token de **toda** oferta que descarta, ao lado do `localSignature?.fill(0)` que já
existia — mesma disciplina de limpeza, campo novo.

A pergunta que importa é por que essa mesma linha **não** foi colocada dentro de `burn()`:

```kotlin
private fun burn(target:LocalOffer) { identity.sessions.discardBundle(target.bundle.keyId) }
```

`burn()` não só roda a partir de `cancel()`. Ele também roda a partir de `prune()` — chamado por
`standingOffers()` a cada leitura — sobre ofertas que apenas **saíram da janela `PENDING_TTL_SECONDS`
delas**, sem que a troca em andamento tenha terminado:

```kotlin
private fun prune() {
    val expired=emitted.filterNot(::live)
    emitted.removeAll { !live(it) }
    while(emitted.size>MAX_LIVE_OFFERS) emitted.removeAt(emitted.lastIndex).let(::burn)
    expired.forEach(::burn)
}
```

E uma troca encenada (`pending`) contra uma dessas ofertas ainda pode estar a caminho de `finish()`:
desde a seção "2026-09-17 (4)" deste arquivo, `CONFIRMATION_TTL_SECONDS` (o prazo da troca) é contado
a partir do **staging**, não do `created` da oferta que a originou — e os dois relógios são
independentes por construção. Ou seja: o relógio da **oferta** pode expirar e chamar `burn()` sobre
ela antes de o relógio da **troca** expirar, com `finish()` ainda pendente do lado da UI (SAS confirmado
dos dois lados, faltando só o QR de confirmação do par). Se a zeroização estivesse dentro de `burn()`,
esse `finish()` tardio leria `p.local.offer.doorbellToken` já zerado e persistiria um
`doorbellTokenIssued` todo-zeros no contato — um defeito silencioso, sem exceção nenhuma para
denunciá-lo.

`cancel()` é seguro para zerar porque larga **todas** as referências de uma vez — o `pending` e a
lista `emitted` inteira — antes de zerar; depois de `cancel()` não existe nenhum caminho de código que
ainda possa precisar daquele token. `burn()`, por ser chamado também fora de `cancel()`, não tem essa
garantia: uma oferta que ele queima pode não ser a mesma oferta que uma troca em andamento ainda vai
usar em `finish()`. A zeroização, portanto, mora exatamente nos dois pontos onde o token de verdade
deixa de poder ser necessário — a cópia acabou de sair (`finish`) ou toda referência acabou de cair
(`cancel`) — e em nenhum outro.

### 4. Compatibilidade

Nenhum campo existente mudou de sentido: `doorbellKey`/`doorbellToken` continuam sendo a metade do
par, lidos de `p.peer` exatamente como antes. `PairedContact` só é construído em `finish()` e só é
consumido em dois lugares fora deste arquivo — `NoMessagesController.readPairing` e `DecoyFactory`
(camada de armazenamento) —, então acrescentar um campo obrigatório não quebrou nenhuma outra chamada;
quebrou, de propósito, qualquer construção de `PairedContact` que não passasse pelo `finish()` já
ajustado.

### 5. Escolha do nome: `doorbellTokenIssued`

Coluna correspondente no vault: `doorbell_token_issued`. Mantém o prefixo `doorbellToken*`, de modo
que as duas metades — o que aceitar e o que apresentar — ficam lado a lado tanto no registro Kotlin
quanto no esquema SQL; o sufixo "Issued" diz quem cunhou o valor.

`localDoorbellToken` foi cogitado e descartado. Neste arquivo, "local" já tem um sentido fixado —
designa a oferta **emitida por este aparelho** (`LocalOffer`, `localSignature`, `p.local`) —, não a
posse de um segredo. Usar "local" para as duas coisas ao mesmo tempo criaria ambiguidade exatamente no
único campo em que confundir "o token que eu cunhei" com "o token que eu recebi" produz um contato com
os dois lados da campainha trocados — o erro mais fácil de cometer nesta mudança, e o mesmo que a
seção seguinte, em `ProtocolTest.kt.md`, existe para pegar.

### Vantagens

- A troca continua sendo a única janela em que um token de campainha pode ser aprendido, e agora ela é
  aproveitada **inteira**: os dois lados que ela sempre carregou passam a ser persistidos, não só um.
- O valor sai de `finish()` já copiado, e o original (`p.local.offer.doorbellToken`) é zerado logo em
  seguida — o segredo não sobrevive em nenhum objeto transitório depois que a troca termina, com ou
  sem sucesso (`cancel()` zera o equivalente no caminho de erro).
- A assimetria "sei tocar a campainha do par, não sei reconhecer quem toca a minha" deixou de existir
  sem mudar um único byte no fio: o campo já viajava assinado dentro da própria oferta desde o T4.16
  (seção "2026-09-17" deste arquivo); só não era guardado deste lado. Nenhuma migração de formato de
  QR foi necessária.
- A zeroização foi colocada nos dois únicos pontos onde é seguro fazê-la (`finish`, `cancel`) e
  deliberadamente fora de `burn()`, que roda sobre ofertas que meramente saíram de janela enquanto uma
  troca ainda pode estar viva contra elas — evitando um defeito silencioso (token zerado persistido)
  que nenhum teste de fluxo feliz pegaria.

## 2026-09-18 — correção de comentário desatualizado

Apenas texto de KDoc/comentário: **nenhuma linha de código, assinatura ou lógica mudou**.

### Antes

1. KDoc de `PairedContact` (linha ~61):

   > The three doorbell fields are **reserved for the doorbell feature (T4.17)** and carry no behaviour
   > today.

2. Tabela do formato de fio, KDoc de `Offer` (linhas ~496-497):

   | 7  | doorbellKey   | 32    | doorbell onion identity key - reserved for T4.17  |
   | 8  | doorbellToken | 32    | fresh per offer - reserved for T4.17              |

### Agora

1. KDoc de `PairedContact`:

   > The three doorbell fields **drive the doorbell feature (T4.17)**: they are what lets a device whose
   > messaging service is stopped still be woken by a peer whose delivery attempt failed.

2. Tabela do formato de fio:

   | 7  | doorbellKey   | 32    | doorbell onion identity key - where to knock      |
   | 8  | doorbellToken | 32    | minted fresh per offer - what to say when knocking |

### Por que

As duas frases descreviam o estado do código em 2026-09-17, quando os campos já viajavam assinados
mas nada os consumia. Depois das fases 1-7 do T4.17 isso deixou de ser verdade: `doorbellKey` é o
endereço do onion da campainha para onde `MessagingEngine` toca quando a entrega direta falha,
`doorbellToken` é o segredo apresentado nesse toque, e `doorbellTokenIssued` é o que o ouvinte local
reconhece. Um comentário que diz "carry no behaviour today" sobre três campos que hoje carregam todo
o comportamento da campainha é pior do que comentário nenhum: induz quem lê a tratá-los como campos
mortos, removíveis ou livres para reuso.

As descrições "where to knock" / "what to say when knocking" na tabela reaproveitam de propósito a
mesma metáfora já usada logo abaixo, no KDoc de `PairedContact`, de modo que a tabela do formato de
fio e a descrição do contato persistido passem a usar o mesmo vocabulário.

### Vantagens

- A documentação do arquivo volta a descrever o comportamento real, e não a intenção de uma fase já
  concluída.
- Elimina a referência "reserved for T4.17" em três pontos, que era o último lugar do código onde a
  campainha ainda aparecia como trabalho futuro.
- Risco zero de regressão: a mudança não toca em `fields()`, `canonical()`, `encode()` nem em
  qualquer valor assinado — o layout de 294/326 bytes e o separador de domínio `nomessages-offer-v2`
  continuam idênticos.
