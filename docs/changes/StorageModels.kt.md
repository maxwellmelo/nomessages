# app/src/main/kotlin/dev/mx3/nomessages/storage/StorageModels.kt

## 2026-09-17 — `MessageRecord.forwarded` (Parte B: Encaminhar)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). O flag `forwarded` precisa
ser persistido no cofre, e não apenas viver dentro do envelope serializado em `body`, para que a
lista de mensagens possa ser renderizada sem decodificar envelope por envelope — e para que o rótulo
"Encaminhada" continue aparecendo mesmo se um envelope específico falhar ao decodificar.

### Como era antes

```kotlin
data class MessageRecord(
    val id: String,
    val peerOrGroup: String,
    val direction: MessageDirection,
    val timestamp: Long,
    val body: ByteArray,
    val status: MessageStatus,
)
```

### Como é agora

```kotlin
data class MessageRecord(
    val id: String,
    val peerOrGroup: String,
    val direction: MessageDirection,
    val timestamp: Long,
    val body: ByteArray,
    val status: MessageStatus,
    val forwarded: Boolean = false,
)
```

Mais um KDoc explicando que `forwarded` espelha o flag do envelope em `body` e que os dois são
sempre escritos juntos (por `MessagingEngine.envelopeForwarded`).

### Por que duplicar o dado que já está no envelope

É uma desnormalização deliberada, do mesmo tipo que `direction`/`ts`/`status` já eram:

- `NoMessagesController.refresh()` monta `MessageUi` para a conversa inteira; ler uma coluna
  `INTEGER` é muito mais barato do que depender do resultado de `EnvelopeCodec.decode` para um campo
  que a bolha precisa em toda renderização.
- A linha do banco é autoritativa e legível mesmo quando o `body` não decodifica (envelope truncado
  ou de origem estranha) — nesse caso o `try/catch` de `refresh()` devolve `null` e todo o resto do
  `MessageUi` degrada, mas `forwarded` continua correto.
- O risco da duplicação (os dois discordarem) é eliminado na origem: **um único ponto** calcula o
  valor para as duas escritas (`MessagingEngine.envelopeForwarded`, usado tanto no envio quanto na
  recepção), e o seed do cofre decoy faz o mesmo.

### Vantagens

- Último parâmetro com default `false`: todo `MessageRecord(...)` posicional que já existia (testes
  instrumentados, `MessagingEngine`, `AndroidVaultStorage.seedDecoy`) continua compilando.
- Registros de eras anteriores (schema v1) leem `forwarded = false` naturalmente, porque a coluna
  nasce com `NOT NULL DEFAULT 0` — ver `docs/changes/AndroidVaultStorage.kt.md`.

## 2026-09-17 — `ContactRecord.doorbellOnion`/`doorbellToken`, reservados para o T4.17 (T4.16)

### Motivo

O QR de pareamento formato 2 (T4.16, ver `docs/changes/Pairing.kt.md`) passou a incluir, dentro da
oferta assinada, o endereço de um segundo onion dedicado do par ("campainha", ainda não implementada
— T4.17) e um segredo de 32 bytes específico daquela troca. `ContactRecord` é o modelo que
`ChatDatabase.putContact`/`getContact` persistem por contato; os dois campos novos precisam de um
lugar para viajar do resultado do pareamento até a linha do banco (ver
`docs/changes/AndroidVaultStorage.kt.md`, schema v3, e `docs/changes/ChatDatabase.kt.md`).

### Como era antes

```kotlin
data class ContactRecord(
    val id: String,
    val alias: String,
    val onion: String,
    val identityPublic: ByteArray,
    val signalPeer: ByteArray,
    val pairedAt: Long,
    val displayOnly: Boolean = false,
)
```

### Como ficou

```kotlin
/**
 * One paired contact.
 *
 * [doorbellOnion] and [doorbellToken] are **reserved for the doorbell feature (T4.17)** and are read
 * by nothing today. They come from the peer's signed pairing offer (QR format 2, T4.16), so the SAS
 * the two humans compared already covers them, and they are stored at pairing time because there is
 * no second authenticated channel on which to obtain them later.
 *
 * [doorbellOnion] is the peer's second, dedicated onion address, derived from a different vault seed
 * than [onion] so the two are unlinkable. [doorbellToken] is the 32-byte secret that peer minted for
 * this exchange and will expect to be presented when its doorbell is rung.
 *
 * Both default to empty: contacts written before schema v3 have no such columns, and a display-only
 * contact has no network identity at all.
 */
data class ContactRecord(
    val id: String,
    val alias: String,
    val onion: String,
    val identityPublic: ByteArray,
    val signalPeer: ByteArray,
    val pairedAt: Long,
    val displayOnly: Boolean = false,
    val doorbellOnion: String = "",
    val doorbellToken: ByteArray = ByteArray(0),
)
```

### De onde vêm os valores

Não de uma consulta posterior nem de um segundo QR: vêm da **oferta de pareamento assinada** do
próprio par — os campos `doorbellKey` (32 bytes, chave de identidade Ed25519 do segundo onion) e
`doorbellToken` (32 bytes) do formato 2 (ver fact sheet do T4.16, tabela de campos assinados). Os
dois estão dentro do mesmo `canonical()` que o Ed25519 do par assina e que os dois humanos comparam
como SAS de seis dígitos — não há um caminho de hashing ou assinatura separado para eles. É por isso
que `doorbellOnion` pode ser armazenado com a mesma confiança que já se deposita em `onion`: o
mesmo ato de verificação (assinatura válida + SAS conferido em voz alta) cobre os dois.

### Por que são gravados no momento do pareamento, e não buscados depois

Não existe, hoje, um segundo canal autenticado entre os dois aparelhos além da própria sessão Signal
que o pareamento acabou de estabelecer — e usar essa sessão para perguntar "qual é seu onion de
campainha?" depois exigiria confiar a mesma pergunta a uma troca sem o SAS presencial que o
pareamento tem. Gravar os dois campos no instante em que a oferta assinada chega, junto com `onion` e
`identityPublic`, evita inventar um segundo protocolo de distribuição de chave para um dado que já
estava disponível, assinado, no primeiro.

### O que o vazio significa

`doorbellOnion = ""` / `doorbellToken = ByteArray(0)` não é um erro nem um "ainda não migrado" —
é o valor correto para **todo contato pareado antes do formato de QR 2**, cuja oferta nunca teve
esses campos, e para um contato `displayOnly` (que não tem identidade de rede nenhuma). A dupla
convenção "os dois vazios juntos, ou os dois presentes juntos" é imposta por
`ChatDatabase.validateContact` (ver `docs/changes/ChatDatabase.kt.md`), não por este arquivo.

### Vantagens

- Dois parâmetros novos com default: todo `ContactRecord(...)` posicional existente — testes
  instrumentados, `NoMessagesController`, `DecoyFactory` — continua compilando sem alteração.
- Contatos de eras anteriores (schema v2 e anteriores) leem `doorbellOnion = ""` /
  `doorbellToken = ByteArray(0)` naturalmente, porque as colunas nascem com
  `NOT NULL DEFAULT '' / x''` — ver `docs/changes/AndroidVaultStorage.kt.md`.
- O KDoc deixa registrado, no próprio modelo, que o campo é reservado e por quem será lido — quem ler
  `StorageModels.kt` sem contexto de T4.17 não confunde "não implementado" com "esquecido".

## 2026-09-18 — `ContactRecord.doorbellTokenIssued`, a outra metade do par (T4.17)

### Motivo

Fase 2 da feature "campainha" (T4.17). `doorbellOnion`/`doorbellToken` (schema v3, T4.16) descrevem
só a metade do par que pertence **ao outro lado**: onde bater na campainha do contato e o que
apresentar lá. Faltava o token que **este** aparelho cunhou para aquele contato — o que uma batida
vinda dele vai apresentar, e portanto o único valor contra o qual um ouvinte local poderia verificar.
Esse valor era gerado em `PairingEngine.newOffer()`, publicado dentro da nossa própria oferta
assinada e descartado junto com ela; não existia campo nenhum para retê-lo. Schema v3 → v4 (ver
`docs/changes/AndroidVaultStorage.kt.md`).

### Como era antes

```kotlin
/**
 * One paired contact.
 *
 * [doorbellOnion] and [doorbellToken] are **reserved for the doorbell feature (T4.17)** and are read
 * by nothing today. They come from the peer's signed pairing offer (QR format 2, T4.16), so the SAS
 * the two humans compared already covers them, and they are stored at pairing time because there is
 * no second authenticated channel on which to obtain them later.
 *
 * [doorbellOnion] is the peer's second, dedicated onion address, derived from a different vault seed
 * than [onion] so the two are unlinkable. [doorbellToken] is the 32-byte secret that peer minted for
 * this exchange and will expect to be presented when its doorbell is rung.
 *
 * Both default to empty: contacts written before schema v3 have no such columns, and a display-only
 * contact has no network identity at all.
 */
data class ContactRecord(
    val id: String,
    val alias: String,
    val onion: String,
    val identityPublic: ByteArray,
    val signalPeer: ByteArray,
    val pairedAt: Long,
    val displayOnly: Boolean = false,
    val doorbellOnion: String = "",
    val doorbellToken: ByteArray = ByteArray(0),
)
```

### Como ficou

```kotlin
/**
 * One paired contact.
 *
 * The three doorbell fields are **reserved for the doorbell feature (T4.17)** and are read by
 * nothing today. All three are settled during the pairing exchange (QR format 2, T4.16) and stored
 * at pairing time because there is no second authenticated channel on which to obtain or agree them
 * later. Two describe the peer, one describes this device, and they are not interchangeable:
 *
 * - [doorbellOnion] - the peer's second, dedicated onion address, derived from a different vault
 *   seed than [onion] so the two are unlinkable. **Where to knock.**
 * - [doorbellToken] - the 32-byte secret that peer minted for this exchange and will expect to be
 *   presented when its doorbell is rung. **What to present when knocking.**
 * - [doorbellTokenIssued] - the 32-byte secret **this device** minted for this contact, inside its
 *   own signed offer. It is what this contact will present when it rings *our* doorbell, so it is
 *   the value the local listener has to recognise. **What to accept from this contact.**
 *
 * The first two come from the peer's signed offer and the third from ours, so the SAS the two humans
 * compared covers all three - each in one direction.
 *
 * Why the third one is a separate column rather than derivable: it is not derivable from anything.
 * Each side mints an independent random secret per exchange, so `doorbellTokenIssued` for contact X
 * has no relation to X's `doorbellToken`, to any other contact's columns, or to the vault identity.
 * Until schema v4 it was generated, published inside our offer, and then dropped on the floor - the
 * vault could ring a peer's doorbell but had nothing to check an incoming ring against.
 *
 * All three default to empty: contacts written before schema v3/v4 have no such columns, and a
 * display-only contact has no network identity at all.
 */
data class ContactRecord(
    val id: String,
    val alias: String,
    val onion: String,
    val identityPublic: ByteArray,
    val signalPeer: ByteArray,
    val pairedAt: Long,
    val displayOnly: Boolean = false,
    val doorbellOnion: String = "",
    val doorbellToken: ByteArray = ByteArray(0),
    val doorbellTokenIssued: ByteArray = ByteArray(0),
)
```

### Os três campos, separados por papel

O KDoc deixa de tratar os dois campos antigos como um par indiferenciado e passa a nomear o papel de
cada um dos três, porque a partir de agora existe uma direção que importa:

- `doorbellOnion` — onde bater.
- `doorbellToken` — o que apresentar ao bater.
- `doorbellTokenIssued` — o que aceitar de quem bate aqui.

Os dois primeiros continuam vindo da **oferta assinada do par**; o terceiro vem da **nossa própria**
oferta assinada, a mesma que `LocalOffer`/`localSignature` já representam em `Pairing.kt`. Os dois
lados do canonical assinado (o do par e o nosso) cobrem, juntos, os três campos — cada um na sua
direção — e o SAS de seis dígitos que os dois humanos conferem em voz alta segue sendo a única
verificação que os autentica.

### Por que não é derivável, e por que não pode ser obtido depois

`doorbellTokenIssued` de um contato X não tem nenhuma relação matemática com o `doorbellToken` de X,
com as colunas de nenhum outro contato, nem com a identidade do cofre — cada lado cunha um segredo
aleatório independente por troca (`crypto.random(32)`, o mesmo mecanismo dos dois campos já
existentes). Sem coluna própria, o valor existia por alguns segundos dentro de `PairingEngine.newOffer()`
e desaparecia junto com a oferta assinada. E não há um segundo canal autenticado entre os dois
aparelhos para combinar um token depois — o pareamento presencial com SAS é a única janela em que os
dois lados se veem, exatamente a mesma razão, já documentada aqui, pela qual `doorbellOnion`/
`doorbellToken` também são gravados no instante do pareamento e não buscados depois.

### Por que o nome é `doorbellTokenIssued`, não `localDoorbellToken`

O prefixo `doorbell_token*`/`doorbellToken*` é deliberado: as duas metades do par ficam lado a lado
no esquema (`doorbell_token`, `doorbell_token_issued`) e no registro (`doorbellToken`,
`doorbellTokenIssued`), e o sufixo diz **quem cunhou** o segredo — "emitido [por nós]" — em vez de
repetir "doorbell" com um qualificador solto. `localDoorbellToken` foi cogitado e descartado: em
`Pairing.kt`, "local" já tem um significado fixado — designa a **oferta** emitida por este aparelho
(`LocalOffer`, `localSignature`), não a posse de um segredo dentro dela. Reaproveitar "local" aqui
com um sentido diferente criaria confusão entre os dois arquivos que mais precisam concordar sobre o
vocabulário do pareamento.

### Vantagens

- Terceiro parâmetro com default `ByteArray(0)`: todo `ContactRecord(...)` posicional existente —
  testes instrumentados, `NoMessagesController`, `DecoyFactory` — continua compilando sem alteração.
- Contatos de eras anteriores (schema v3 e anteriores) leem `doorbellTokenIssued = ByteArray(0)`
  naturalmente, porque a coluna nasce com `NOT NULL DEFAULT x''` — ver
  `docs/changes/AndroidVaultStorage.kt.md`.
- O KDoc passa a nomear o papel de cada um dos três campos individualmente, em vez de descrever "os
  dois campos de campainha" como um bloco — a assimetria "sei tocar, não sei atender" que motivou a
  mudança fica impossível de não notar para quem ler o modelo depois.
- Nenhuma mudança de formato de QR: os três campos continuam vindo da mesma oferta assinada de
  sempre, só a coluna que faltava foi acrescentada.
