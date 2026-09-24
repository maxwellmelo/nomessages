# `app/src/main/kotlin/dev/mx3/nomessages/storage/DecoyFactory.kt`

## 2026-09-15 — T3.1: o Android não tem provedor de SHA3-256

Correção do defeito que derrubou 11 dos 12 casos da segunda execução da suíte
instrumentada (`docs/development/build-logs/android-test-emulator-5556-20260915T141204Z.log`):

> `java.security.NoSuchAlgorithmException: SHA3-256 MessageDigest not available`
> em `DecoyFactory.syntheticOnion` -> `create` -> `AndroidVaultStorage.seedDecoy`

### Como era antes

```kotlin
import java.security.MessageDigest
...
private fun syntheticOnion(): String {
    val publicKey = crypto.random(32)
    val checksumInput = ONION_CHECKSUM_PREFIX + publicKey + ONION_VERSION
    val checksum = MessageDigest.getInstance("SHA3-256").digest(checksumInput)
```

### Como ficou

```kotlin
private fun syntheticOnion(): String {
    val publicKey = crypto.random(32)
    val checksumInput = ONION_CHECKSUM_PREFIX + publicKey + ONION_VERSION
    // Android exposes no SHA3-256 provider, so the v3 onion checksum uses the bundled digest.
    val checksum = Sha3_256.digest(checksumInput)
```

O `import java.security.MessageDigest` foi removido: era o único uso no arquivo.

### Por que a mudança

O endereço `.onion` v3 termina em dois bytes de checksum definidos como
`SHA3-256(".onion checksum" || pubkey || version)`. O código pedia esse digest ao
`MessageDigest` da plataforma. Isso resolve em JVM de desktop (o provedor SUN tem
SHA-3 desde o JDK 9), mas **não existe no Android**: o Conscrypt não implementa a
família SHA-3 e o BouncyCastle reempacotado da plataforma a removeu. O resultado
era uma exceção na criação do cofre isca — ou seja, o app não conseguia concluir
o setup em nenhum aparelho, embora todos os testes JVM passassem.

A alternativa de adicionar o BouncyCastle completo como dependência foi
descartada: traria megabytes e uma superfície criptográfica inteira para obter
um único digest de 48 bytes de entrada. O digest passou a ser implementado no
próprio app, em `Sha3_256.kt`, com teste contra os vetores do FIPS 202.

### Vantagens

- O cofre isca volta a ser criado no Android; a suíte instrumentada avançou de
  1/12 para 10/12 nessa etapa.
- O checksum continua byte a byte igual ao que a rede Tor valida, então um
  endereço sintético da isca permanece indistinguível de um endereço real — que
  é exatamente o objetivo da fixture (a classe promete pares "criptograficamente
  válidos").
- O comportamento deixa de depender de nível de API ou de provedor instalado:
  mesmo resultado em JVM e no aparelho.

## 2026-09-15 — Revisão adversarial do commit 8c7f0f7: chave pública sintética não era um ponto Ed25519 válido

Revisão de segurança pedida sobre o commit que introduziu `Sha3_256.kt` e trocou o `MessageDigest`
por ele (seção acima). A implementação do SHA3-256 em si foi auditada byte a byte contra a FIPS 202
(constantes de rodada, offsets de rotação, theta/rho/pi/chi/iota, absorção/espremedura
little-endian, padding `0x06`/`0x80`) e validada contra os vetores oficiais do NIST e contra
`java.security.MessageDigest` em entradas de 0, 135, 136, 137, 271, 272, 1000 e 4096 bytes — sem
defeito. O defeito real estava uma camada acima, em `syntheticOnion()`.

### Como era antes

```kotlin
private fun syntheticOnion(): String {
    val publicKey = crypto.random(32)
    val checksumInput = ONION_CHECKSUM_PREFIX + publicKey + ONION_VERSION
    val checksum = Sha3_256.digest(checksumInput)
    val address = publicKey + checksum.copyOf(2) + ONION_VERSION
    ...
}
```

### Como ficou

```kotlin
internal fun syntheticOnion(): String {
    // A real v3 onion address embeds a genuine Ed25519 public key: a point that a key
    // generator derived (via SHA-512 expansion and clamping of a secret seed), which is
    // always a valid Edwards-curve point in the prime-order subgroup. 32 raw random bytes are
    // NOT that: they decompress to a valid curve point only ~50% of the time and pass the
    // subgroup check even less often, so a decoy contact's onion would be detectable as
    // synthetic just by decoding it - no cryptanalysis required, only base32 + a curve check.
    // signingKeyPair() runs the same native Ed25519 keygen used for real identities elsewhere
    // in this class (see PairingIdentity.create above), so the embedded key is indistinguishable
    // from a real node's public key. The private half is immediately discarded.
    val publicKey = crypto.signingKeyPair().use { it.publicKey }
    val checksumInput = ONION_CHECKSUM_PREFIX + publicKey + ONION_VERSION
    val checksum = Sha3_256.digest(checksumInput)
    val address = publicKey + checksum.copyOf(2) + ONION_VERSION
    ...
}
```

`syntheticOnion()` também passou de `private` para `internal` só para ficar testável a partir de
`app/src/test` (mesmo módulo) — nenhuma mudança de comportamento.

### Por que a mudança

Um endereço `.onion` v3 real (`native/src/tor.rs:88-99`, `HsIdKeypair::from(ed25519::ExpandedKeypair::from(&kp))`
sobre `ed25519::Keypair::from_bytes(seed)`) nunca embute bytes aleatórios crus como chave pública: a
derivação Ed25519 padrão expande o seed via SHA-512, faz o *clamping* do escalar e calcula
`ponto = escalar * B`. Como `B` gera o subgrupo de ordem prima da curva, **todo** ponto assim
derivado é, por construção, um ponto Edwards válido no subgrupo correto — sempre, sem exceção.

`crypto.random(32)` gerava 32 bytes uniformemente aleatórios e os usava diretamente como se fossem
essa chave pública. Medido empiricamente (20.000 amostras): só **~50,4%** dos vetores aleatórios de
32 bytes descomprimem para um ponto Edwards válido, e a fração cai para **~6,3%** ao aplicar também
a checagem de subgrupo de ordem prima que toda chave Ed25519 real satisfaz. Como `create()` gera um
`syntheticOnion()` por contato (`aliases.size in 3..5`), a chance de **pelo menos um** endereço do
cofre-isca ser matematicamente impossível chegava a 87–97%. Um adversário que force a abertura do
app e decodifique os endereços dos contatos (base32 + checagem de ponto Ed25519 — poucas linhas de
código, nenhuma quebra criptográfica) identificaria o cofre-isca com confiança altíssima, o que
anula o propósito central da classe: pares "criptograficamente válidos" que não se distingam de
pares reais.

`crypto.signingKeyPair()` já é usado neste mesmo arquivo (`PairingIdentity.create`, linhas 13 e 35)
e chama, via JNI, `crypto_sign::KeyPair::generate()` do libsodium (`native/src/crypto.rs:137`) — o
mesmo caminho nativo, puramente local e offline, que gera as identidades reais de pareamento. A
chave privada devolvida é descartada imediatamente (`.use { it.publicKey }`); só a pública, sempre
válida, chega ao endereço.

### Vantagens

- O endereço `.onion` sintético passa a ser indistinguível de um real também sob inspeção
  matemática, não só sob inspeção de formato (tamanho, alfabeto, checksum já estavam corretos).
- Reaproveita um caminho nativo já exercitado no mesmo arquivo — sem nova superfície de código, sem
  nova dependência.
- Teste de regressão novo (`DecoyFactoryTest.kt`, ver `docs/changes/DecoyFactoryTest.kt.md`) prende
  a fonte da chave embutida ao `signingKeyPair()`, então uma futura regressão para `random(32)`
  quebra em segundos em `:app:testDebugUnitTest`, sem precisar de aparelho.

### Validação

`:app:testDebugUnitTest` (forçado com `--rerun`): `Sha3_256Test` 2/2 e `DecoyFactoryTest` 1/1,
0 falhas, 0 erros. Suíte completa do módulo `app` também verde após a mudança.

## 2026-09-17 — Pareamento decoy segue o formato 2 (bundle via `bundleFor`) e ganha colunas de campainha (T4.16)

### Motivo

O T4.16 trocou o formato de QR de pareamento (v1 → v2, ver `docs/changes/Pairing.kt.md`): o bundle de
chaves saiu do QR e passa a ser buscado à parte, e a oferta assinada ganhou os campos reservados de
campainha (`doorbellKey`/`doorbellToken`, ver `docs/changes/StorageModels.kt.md`). `DecoyFactory.create`
pareia dois `PairingEngine` em processo para gerar cada contato do cofre-isca; ele precisa continuar
percorrendo exatamente o mesmo caminho de aceitação que um pareamento real percorre, ou o par
resultante deixa de ser indistinguível de um par real — o mesmo princípio que já motivou a correção
de chave sintética inválida (seção de 2026-09-15 acima).

### 1. `syntheticOnion` dividido em `syntheticOnionKey()` + `OnionAddress.address(...)`

**Como era antes**

```kotlin
internal fun syntheticOnion(): String {
    // ... comentário sobre por que usar signingKeyPair() em vez de random(32) ...
    val publicKey = crypto.signingKeyPair().use { it.publicKey }
    val checksumInput = ONION_CHECKSUM_PREFIX + publicKey + ONION_VERSION
    val checksum = Sha3_256.digest(checksumInput)
    val address = publicKey + checksum.copyOf(2) + ONION_VERSION
    return try { base32(address) + ".onion" }
    finally { crypto.wipe(publicKey); crypto.wipe(checksumInput); crypto.wipe(checksum); crypto.wipe(address) }
}

private fun base32(bytes: ByteArray): String { /* ... */ }

companion object {
    private val ONION_CHECKSUM_PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)
    private val ONION_VERSION = byteArrayOf(3)
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
}
```

**Como ficou**

```kotlin
internal fun syntheticOnion(): String {
    val key = syntheticOnionKey()
    return try { OnionAddress.address(key) } finally { crypto.wipe(key) }
}

/** A fresh, genuine Ed25519 public key with its private half discarded. See [syntheticOnion]. */
internal fun syntheticOnionKey(): ByteArray {
    // A real v3 onion address embeds a genuine Ed25519 public key ... (comentário original mantido
    // na íntegra — ver seção "Revisão adversarial" acima)
    // The base32 + SHA3-256 checksum encoding moved to OnionAddress on 2026-09-17 (T4.16), which
    // needed the same conversion for the doorbell identity key. Android exposes no SHA3-256
    // provider, so it still uses the bundled digest; the address is byte-identical to what this
    // function produced before.
    return crypto.signingKeyPair().use { it.publicKey }
}
```

O `base32`/`companion object` de checksum saíram inteiros deste arquivo — foram para `OnionAddress`
(arquivo novo, ver `docs/changes/OnionAddress.kt.md`).

O raciocínio original — por que uma chave Ed25519 real e não 32 bytes aleatórios — não mudou uma
vírgula; só migrou de `syntheticOnion` para `syntheticOnionKey`, que é agora a função que efetivamente
gera a chave. `syntheticOnion()` continua existindo, com a mesma assinatura e o mesmo endereço
byte-a-byte, só que compondo `syntheticOnionKey()` + `OnionAddress.address(...)` em vez de repetir o
algoritmo.

### 2. Pareamento decoy passa a trocar o bundle pelo caminho verificado

**Como era antes**

```kotlin
val local = PairingEngine(crypto, localIdentity, localOnion)
val remote = PairingEngine(crypto, syntheticPeer, syntheticOnion())
val remoteProgress = remote.respond(local.createOffer())
val localProgress = local.processResponse(checkNotNull(remoteProgress.responseQr))
check(localProgress.sas == remoteProgress.sas)
val localConfirmation = local.confirm(localProgress.handle, true)
val remoteConfirmation = remote.confirm(remoteProgress.handle, true)
```

Sob o formato 1, o bundle de chaves viajava dentro do próprio QR — não havia passo separado de busca.

**Como ficou**

```kotlin
// Decoy doorbell identities are synthesised the same way the decoy onions are - a real
// Ed25519 public key with the private half discarded - so a decoy contact's doorbell column
// is indistinguishable from a real one's by inspection. Reserved for T4.17; nothing listens.
val localDoorbell = syntheticOnionKey()
val remoteDoorbell = syntheticOnionKey()
val local: PairingEngine
val remote: PairingEngine
try {
    local = PairingEngine(crypto, localIdentity, localOnion, localDoorbell)
    remote = PairingEngine(crypto, syntheticPeer, syntheticOnion(), remoteDoorbell)
} finally { crypto.wipe(localDoorbell); crypto.wipe(remoteDoorbell) }
val remoteProgress = remote.respond(local.createOffer())
val localProgress = local.processResponse(checkNotNull(remoteProgress.responseQr))
check(localProgress.sas == remoteProgress.sas)
// QR format 2 keeps the key bundle out of the QR, so each side is handed the peer's bundle
// directly. On a real pairing this is a Tor round trip; here both engines are in-process,
// and the decoy must still traverse exactly the same verified acceptance path so the
// resulting session state is byte-shaped like a genuine one.
local.acceptPeerBundle(localProgress.handle, checkNotNull(remote.bundleFor(localProgress.peerBundleNonce)))
remote.acceptPeerBundle(remoteProgress.handle, checkNotNull(local.bundleFor(remoteProgress.peerBundleNonce)))
val localConfirmation = local.confirm(localProgress.handle, true)
val remoteConfirmation = remote.confirm(remoteProgress.handle, true)
```

Sob o formato 2, buscar o bundle do peer é um passo explícito do protocolo (normalmente uma conexão
Tor — ver `docs/changes/MessagingEngine.kt.md`, `fetchPeerBundle`). Como os dois `PairingEngine` do
decoy correm no mesmo processo, não há rede real envolvida; mas a chamada a `acceptPeerBundle` não
foi contornada por isso. `remote.bundleFor(nonce)` é a mesma função que, num pareamento real,
responde a um `BundleRequest` recebido pela rede — aqui ela é chamada diretamente, sem o transporte
Tor no meio, mas com a mesma verificação de nonce e a mesma resposta. `acceptPeerBundle` é a mesma
função que, num pareamento real, confere `SHA-256(bundle) == peerOffer.bundleHash` e cancela a troca
em caso de divergência (ver fact sheet do T4.16, item 6). Pular esse passo teria deixado o `Contact`
resultante do decoy num estado que um pareamento real jamais alcançaria — sessão Signal estabelecida
sem nunca ter passado pela verificação de hash do bundle — o tipo exato de atalho que uma inspeção do
estado interno do cofre poderia usar para separar isca de real.

### 3. Contato decoy passa a popular `doorbellOnion`/`doorbellToken`

**Como era antes**

```kotlin
ContactRecord(
    id = ..., alias = alias, onion = ...,
    identityPublic = ..., signalPeer = ...,
    pairedAt = pairedAtMillis, displayOnly = false,
).also(database::putContact)
```

**Como ficou**

```kotlin
ContactRecord(
    id = ..., alias = alias, onion = ...,
    identityPublic = ..., signalPeer = ...,
    pairedAt = pairedAtMillis, displayOnly = false,
    // Populated, not left empty: an all-blank doorbell column on every decoy contact while
    // real contacts carry one would make the two distinguishable by inspection alone.
    doorbellOnion = OnionAddress.address(contact.doorbellKey),
    doorbellToken = contact.doorbellToken,
).also(database::putContact)
```

`contact.doorbellKey`/`contact.doorbellToken` vêm do `Contact` que `local.finish(...)` devolve — os
mesmos campos que um pareamento real extrai da oferta assinada do peer (ver
`docs/changes/NoMessagesController.kt.md`). `OnionAddress.address(...)` reconstrói o texto de 62
caracteres a partir da chave de 32 bytes, do mesmo jeito que o controller faz para um contato real —
ver `docs/changes/OnionAddress.kt.md`.

### Por que o decoy não pode deixar essas colunas em branco

O cofre-isca existe para não ser distinguível do cofre real sob inspeção — de tela, de arquivo, ou de
esquema. Se todo contato real passa a carregar `doorbell_onion`/`doorbell_token` não vazios (porque
foi pareado pelo formato 2) e todo contato decoy os deixasse em branco, a própria coluna vazia seria
um distinguidor trivial: bastaria abrir qualquer contato e olhar duas colunas para saber que o cofre
é falso, sem precisar de nenhuma criptoanálise — exatamente a categoria de defeito que a revisão
adversarial de 2026-09-15 (seção acima) já havia corrigido para o endereço `.onion` principal.
Popular as colunas com material sintético, porém estruturalmente válido (chave Ed25519 real, endereço
v3 corretamente formado), fecha essa lacuna do mesmo jeito que `syntheticOnionKey()` já fecha para
`onion`.

### Vantagens

- Uma única implementação do algoritmo de endereço `.onion` (`OnionAddress`), compartilhada entre
  decoy e produção real — ver `docs/changes/OnionAddress.kt.md`.
- O pareamento decoy volta a espelhar exatamente o protocolo real (formato 2 completo, incluindo a
  busca de bundle), então qualquer verificação futura sobre o estado de um `Contact` pareado vale
  igualmente para real e decoy.
- Nenhuma coluna nova do schema v3 é um distinguidor: `doorbell_onion`/`doorbell_token` são não-vazios
  em ambos os cofres, populados por material sintético mas estruturalmente indistinguível do real.
- `syntheticOnion()` continua produzindo o endereço byte-a-byte idêntico a antes da extração — nenhum
  teste ou fixture existente que dependa do valor exato precisou mudar.

---

## 2026-09-17 (run5, validação em emulador) — correção de bloqueador: a chave de doorbell era zerada antes de ser usada

### Como era

```kotlin
val localDoorbell = syntheticOnionKey()
val remoteDoorbell = syntheticOnionKey()
val local: PairingEngine
val remote: PairingEngine
try {
    local = PairingEngine(crypto, localIdentity, localOnion, localDoorbell)
    remote = PairingEngine(crypto, syntheticPeer, syntheticOnion(), remoteDoorbell)
} finally { crypto.wipe(localDoorbell); crypto.wipe(remoteDoorbell) }
```

### Como é

```kotlin
// (comentário completo no arquivo)
val local = PairingEngine(crypto, localIdentity, localOnion, syntheticOnionKey())
val remote = PairingEngine(crypto, syntheticPeer, syntheticOnion(), syntheticOnionKey())
```

### Por que a mudança foi feita

`PairingEngine` guarda o parâmetro `doorbellKey:ByteArray` **por referência**
(`private val doorbellKey:ByteArray`, `Pairing.kt:102`) e só o lê mais tarde, dentro de
`createOffer()` → `newOffer()`, onde faz `doorbellKey.copyOf()`. O `crypto.wipe` no `finally`
executava **imediatamente após a construção** e zerava o array antes dessa leitura.

Consequência: a oferta sintética publicava 32 bytes nulos como identidade de doorbell, e
`Offer.decode` — que corretamente recusa uma identidade toda zero
(`require(doorbellKey.size==32 && doorbellKey.any { it.toInt()!=0 })`, `Pairing.kt:356`) — lançava
`IllegalArgumentException: Invalid doorbell identity key` já em `PairingEngine.respond()`.

O alcance disso é muito maior do que parece à primeira vista. A cadeia é:

```
VaultManager.create()
  -> AndroidVaultStorage.initialize()      (AndroidVaultStorage.kt:57)
    -> seedDecoy()                         (AndroidVaultStorage.kt:380)
      -> DecoyFactory.create() -> pair()   (DecoyFactory.kt:15 / :47)
```

ou seja, **toda criação de cofre falhava, em qualquer aparelho, no app inteiro** — não apenas em
teste. Medido em `emulator-5556` em 2026-09-17: **15 de 19 casos instrumentados falhavam**, todos com
esse mesmo stack
(`docs/development/build-logs/android-test-emulator-5556-20260917T211600Z.log`). Nenhum gate de host
podia pegar isso: `DecoyFactory` é do módulo `app` e só é exercitada pela suíte instrumentada, e
`:core:test` (95/0) e `:app:testDebugUnitTest` (61/0) passavam normalmente.

### Vantagens

- **Destrava a criação de cofre**, que é literalmente a primeira coisa que o app faz. Confirmado em
  aparelho: cofre novo criado pela UI real de Setup em `emulator-5560` às 21:31 UTC
  (`docs/development/build-logs/pairing-v2-20260917/03-B-home-fresh-vault.png`), e suíte
  instrumentada em `emulator-5556` passando `OK (19 tests)`
  (`android-test-emulator-5556-20260917T212519Z.log`).
- **Não se perde nenhuma propriedade de segurança.** O que era zerado é uma chave **pública**
  Ed25519 cuja metade privada `syntheticOnionKey()` já descarta no próprio `use { it.publicKey }`.
  Além disso ela é gravada em claro na linha do contato poucas linhas abaixo, como
  `doorbellOnion = OnionAddress.address(contact.doorbellKey)` — apagar a cópia local enquanto a
  mesma informação vai para o banco não protegia nada.
- **Menos código e menos estado mutável:** sumiram duas `val` não inicializadas, um `try/finally` e
  duas chamadas de `wipe`; as chaves passam a ser expressões descartáveis no ponto de uso.
- A prova em campo de que a correção funciona está na oferta real decodificada de `emulator-5556`
  às 21:42:36 UTC: o campo `f7` (doorbellKey) veio `b5030b4ba9fedb249eba20d91c6e53c98994...`, não
  zerado (`docs/development/build-logs/pairing-v2-20260917/session-log.md`).

### Recomendação deixada em aberto (não aplicada nesta sessão)

`PairingEngine` deveria guardar `doorbellKey.copyOf()` em vez do array do chamador. Hoje existe um
contrato implícito e não documentado — "quem constrói um `PairingEngine` não pode zerar o array que
passou" — que nada verifica e que este defeito violou silenciosamente. A cópia defensiva custa 32
bytes e elimina a classe inteira. Não foi feita aqui para manter a correção contida ao arquivo que
de fato tinha o defeito, já que `core/.../Pairing.kt` acabara de ser reescrito por outra tarefa.

## 2026-09-18 — `doorbellTokenIssued` no contato decoy: paridade com a coluna nova da v4 (T4.17)

### Motivo

Continuação, no cofre-isca, da coluna `contacts.doorbell_token_issued` (schema v4, ver
`docs/changes/ChatDatabase.kt.md`, 2026-09-18). `PairedContact.doorbellTokenIssued` e
`ContactRecord.doorbellTokenIssued` agora existem (ver `docs/changes/Pairing.kt.md`,
`docs/changes/StorageModels.kt.md`); este arquivo é quem constrói o `ContactRecord` de cada contato
sintético do cofre-isca e já preenchia `doorbellOnion`/`doorbellToken` desde o T4.16 pelo mesmo motivo
de paridade (ver seção de 2026-09-17 acima, "3. Contato decoy passa a popular
`doorbellOnion`/`doorbellToken`").

### Como era antes

```kotlin
ContactRecord(
    id = ..., alias = alias, onion = ...,
    identityPublic = ..., signalPeer = ...,
    pairedAt = pairedAtMillis, displayOnly = false,
    // Populated, not left empty: an all-blank doorbell column on every decoy contact while
    // real contacts carry one would make the two distinguishable by inspection alone.
    doorbellOnion = OnionAddress.address(contact.doorbellKey),
    doorbellToken = contact.doorbellToken,
).also(database::putContact)
```

### Como ficou

```kotlin
ContactRecord(
    id = ..., alias = alias, onion = ...,
    identityPublic = ..., signalPeer = ...,
    pairedAt = pairedAtMillis, displayOnly = false,
    doorbellOnion = OnionAddress.address(contact.doorbellKey),
    doorbellToken = contact.doorbellToken,
    // Schema v4 (T4.17). Nothing extra is synthesised here: the decoy pairing above is a
    // genuine two-engine exchange, so `local` really did mint a token for this synthetic
    // peer and `finish` hands it back exactly as it does on a real pairing. The column is
    // therefore filled by the same code path and with the same kind of value as in the real
    // vault - 32 bytes of `crypto.random`, statistically identical, unrelated to any other
    // contact's - which is the whole point: a column that were populated only in the real
    // vault, or only in the decoy, would be a tell on its own, and the parity has to hold
    // for the new column from the moment it exists rather than be retrofitted later.
    doorbellTokenIssued = contact.doorbellTokenIssued,
).also(database::putContact)
```

Uma linha só. `contact` aqui é o mesmo `Contact` devolvido por `local.finish(...)` de onde
`doorbellOnion`/`doorbellToken` já vinham sendo lidos — nada mudou no pareamento decoy em si (a troca
de bundle, `acceptPeerBundle`, os dois `PairingEngine` em processo, tudo isso do T4.16 continua igual).

### Por que não foi preciso sintetizar nada

A pergunta óbvia é se `doorbellTokenIssued` precisaria de algum gerador dedicado, como
`syntheticOnionKey()` existe para a chave pública do onion. A resposta é não, e a razão é estrutural:
o pareamento do cofre-isca (`DecoyFactory.pair`) não é um pareamento simulado ou mockado — é uma troca
genuína entre duas instâncias reais de `PairingEngine` (`local` e `remote`) rodando no mesmo processo.
`local.finish(...)` executa exatamente o mesmo código de `PairingEngine` que um pareamento real
executa, então ele já cunha um token de verdade (`crypto.random(32)`) para esse par sintético e o
devolve dentro do `Contact` — o mesmo caminho de código, a mesma fonte aleatória, um valor
estatisticamente idêntico ao de um token real e sem relação com nenhum outro contato do cofre. Não há
lacuna a preencher com material sintético porque o material que sai de `finish()` já É genuíno.

### Por que a paridade importa aqui como já importava para `doorbellOnion`/`doorbellToken`

O cofre-isca existe para não ser distinguível do cofre real sob inspeção. Uma coluna preenchida só no
cofre real (ou só na isca) seria, sozinha, um distinguidor trivial sob inspeção coagida — exatamente o
que o requisito de negação plausível proíbe: bastaria abrir um contato decoy, ver
`doorbell_token_issued` vazio enquanto todo contato real pareado sob a v4 o tem preenchido, e o cofre
falso estaria identificado sem nenhuma criptoanálise. Esse é o mesmo raciocínio, com o mesmo peso, já
documentado neste arquivo em 2026-09-17 para as colunas de campainha da v3
(`doorbellOnion`/`doorbellToken`) — e antes disso, em 2026-09-15, para o próprio endereço `.onion`
principal. A paridade precisa valer desde o instante em que a coluna existe, não ser remendada depois:
se este commit tivesse deixado `doorbellTokenIssued` de fora, a janela entre "coluna existe no schema"
e "isca também a preenche" seria, por si só, uma janela de detecção.

### Vantagens

- Zero código novo de geração: o valor sai do mesmo `finish()` que já alimentava
  `doorbellOnion`/`doorbellToken`, então não há segunda fonte de aleatoriedade a auditar.
- A isca continua indistinguível do cofre real coluna a coluna, sem nenhum ramo de código específico
  de isca — mesmo princípio já estabelecido para as demais colunas de campainha.
- A paridade nasce junto com a coluna, em vez de ser corrigida depois como aconteceu com a chave
  sintética do onion principal em 2026-09-15.
