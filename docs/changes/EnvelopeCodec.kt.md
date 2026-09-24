# core/src/main/kotlin/dev/mx3/nomessages/core/messaging/EnvelopeCodec.kt

## 2026-09-17 — Wire versão 2: flag `forwarded`, retrocompatível com a versão 1 (Parte B: Encaminhar)

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). O campo `forwarded`
adicionado em `Envelope.Text`/`Envelope.Attachment` (ver `docs/changes/Envelope.kt.md`) precisa
viajar no envelope, e o codec precisava passar a ser **extensível** em vez de rejeitar qualquer
versão diferente da única que conhecia.

### Layout do wire

Antes e depois, o cabeçalho é o mesmo:

```
magic "NMFM" (4 bytes ASCII)
version       (u16, big-endian)
tag           (u8)
id            (u16 de tamanho + bytes UTF-8 do UUID canônico de 36 caracteres)
timestamp     (i64, big-endian)
<corpo específico do tipo>
```

O que mudou é só o corpo de dois tipos:

| tag | tipo | corpo v1 | corpo v2 |
| --- | --- | --- | --- |
| 1 | `TEXT` | `body` (blob i32 + UTF-8) | `body` **+ `forwarded` (1 byte: 0 ou 1)** |
| 2 | `ATTACHMENT` | `name`, `mime`, `fileId[16]`, `fileEpoch`, `fileKey[32]`, `ciphertext` (blob) | os mesmos **+ `forwarded` (1 byte: 0 ou 1)** |
| 3,4,5,6,7,8,9,11 | demais | — | **idêntico à v1, nenhum byte novo** |

Ou seja: o byte de `forwarded` é **o último byte do envelope** de um TEXT/ATTACHMENT, colocado
*depois* de todos os campos que já existiam. Um corpo v1 é prefixo exato do corpo v2 correspondente.

### Como era antes

```kotlin
private const val version = 1
...
// encode
when (envelope) {
    is Envelope.Text -> writer.writeUtf8(envelope.body)
    is Envelope.Attachment -> {
        ...
        writer.writeBlob(envelope.ciphertext)
    }
    ...
}
...
// decode
require(reader.readUnsignedShort() == version) { "Unsupported envelope version" }
...
EnvelopeType.TEXT -> Envelope.Text(id, timestamp, reader.readUtf8(EnvelopeLimits.MAX_TEXT_BYTES))
```

Qualquer versão diferente de 1 era rejeitada — não havia caminho de evolução.

### Como é agora

```kotlin
private const val version = 2       // o que encode SEMPRE escreve
private const val legacyVersion = 1 // aceito por decode, nunca escrito
...
// encode
is Envelope.Text -> {
    writer.writeUtf8(envelope.body)
    writer.writeByte(if (envelope.forwarded) 1 else 0)
}
is Envelope.Attachment -> {
    ...
    writer.writeBlob(envelope.ciphertext)
    writer.writeByte(if (envelope.forwarded) 1 else 0)
}
...
// decode
val wireVersion = reader.readUnsignedShort()
require(wireVersion == legacyVersion || wireVersion == version) { "Unsupported envelope version" }
...
EnvelopeType.TEXT -> Envelope.Text(
    id,
    timestamp,
    reader.readUtf8(EnvelopeLimits.MAX_TEXT_BYTES),
    reader.readForwarded(wireVersion),
)
EnvelopeType.ATTACHMENT -> Envelope.Attachment(
    id, timestamp,
    reader.readUtf8(EnvelopeLimits.MAX_NAME_BYTES),
    reader.readUtf8(EnvelopeLimits.MAX_MIME_BYTES),
    reader.readBytes(fileIdBytes),
    reader.readLong(),
    reader.readBytes(fileKeyBytes),
    reader.readBlob(EnvelopeLimits.MAX_ATTACHMENT_CIPHERTEXT_BYTES),
    reader.readForwarded(wireVersion),
)
```

E, dentro do `Reader` privado:

```kotlin
fun readForwarded(wireVersion: Int): Boolean {
    if (wireVersion < 2) return false   // campo ausente na v1: nada é consumido
    val flag = readUnsignedByte()
    require(flag == 0 || flag == 1) { "Invalid forwarded flag" }
    return flag == 1
}
```

### Por que assim

- **`readForwarded` como leitura condicional, não como "byte opcional".** Na v1 o campo simplesmente
  não existe — não é um byte zero. Por isso a função não consome nada quando `wireVersion < 2`, o que
  é exatamente o que mantém o `require(reader.remaining == 0) { "Trailing envelope bytes" }` do final
  do `decode` funcionando *igualzinho* nas duas versões: nenhum byte sobra, nenhum falta.
- **Último argumento do construtor.** O Kotlin avalia argumentos de construtor da esquerda para a
  direita, então colocar `reader.readForwarded(...)` como último argumento consome o wire exatamente
  na ordem em que foi escrito. É o mesmo truque que o `decode` já usava para os outros campos.
- **`require(flag == 0 || flag == 1)`.** Um byte 0x42 nesse lugar é um envelope malformado, não um
  "verdadeiro". Rejeitar em vez de tratar como `!= 0` evita um caminho em que dois peers discordam
  sobre o que o mesmo byte significa.
- **Nenhuma validação nova em `validate()`.** `forwarded` é um `Boolean`: não há estado inválido a
  checar. O único invariante de wire (o byte ser 0 ou 1) é imposto na leitura, antes do
  `Boolean` existir.
- **Sem versão 0/negociação.** Como só a v1 e a v2 existem e a v1 nunca é escrita a partir de agora,
  não há necessidade de negociar: um peer antigo entende o cabeçalho, e um peer novo entende os dois
  formatos.

### Vantagens

- Histórico já persistido no cofre (envelopes v1 em `messages.body`) continua decodificando, com o
  default seguro `forwarded = false` — nenhuma migração de dados de wire foi necessária.
- O codec deixa de ser "uma versão só" e ganha o padrão que as próximas extensões vão seguir:
  acrescentar campos ao **fim** do corpo do tipo afetado e ler condicionalmente pela versão.
- Corpos de envelopes de controle continuam byte a byte idênticos, então nada mudou para MLS, ACK,
  evidências de pareamento ou convites de grupo.

### Impacto nos testes existentes

`EnvelopeCodecTest` tinha duas asserções presas à versão 1, ajustadas junto (ver
`docs/changes/EnvelopeCodecTest.kt.md`):

- `assertArrayEquals(byteArrayOf(0, 1), bytes.copyOfRange(4, 6))` → `byteArrayOf(0, 2)`;
- `assertRejected(valid.copyOf().also { it[5] = 2.toByte() })` → `it[5] = 3.toByte()`, já que a
  versão 2 passou a ser válida e a primeira versão realmente não suportada é a 3.

## 2026-09-17 — Wire versão 3: tags 12/13 do bundle de pareamento (T4.16)

### Motivo

O QR de pareamento formato 1 media ~2950 bytes (QR versão 40, 177 módulos), ilegível pela câmera do
aparelho de teste. A correção (`docs/changes/Envelope.kt.md`, `docs/changes/Pairing.kt.md`) tira o
bundle PQXDH de dentro do QR e passa a buscá-lo pela onion do par, usando dois envelopes novos,
`Envelope.BundleRequest`/`BundleResponse` (tags 12/13). O codec precisava de uma terceira versão de
wire para os carregar, sem tocar em nenhum dos oito tipos já existentes.

### Como era antes

```kotlin
private const val version = 2
private const val legacyVersion = 1
```

```kotlin
// decode
val wireVersion = reader.readUnsignedShort()
require(wireVersion == legacyVersion || wireVersion == version) { "Unsupported envelope version" }
```

`decode` só conhecia dois valores possíveis e checava cada um explicitamente. Não havia tag 12 nem
13 em `tagForType`/`typeForTag`, e `EnvelopeLimits` não tinha constantes para nonce nem para bundle.

### Como ficou

```kotlin
private const val version = 3
/** The first wire version that has the two pairing-bundle tags. */
private const val bundleVersion = 3
/** The first wire version that has the trailing TEXT/ATTACHMENT `forwarded` byte. */
private const val forwardedVersion = 2
```

```kotlin
// decode
val wireVersion = reader.readUnsignedShort()
require(wireVersion in 1..version) { "Unsupported envelope version" }
```

```kotlin
// encode
// Fixed-width nonce, no length prefix: `validate` already pinned it to exactly
// PAIRING_NONCE_BYTES, so a length field would only be a second place to disagree.
is Envelope.BundleRequest -> writer.write(envelope.nonce)
is Envelope.BundleResponse -> {
    writer.write(envelope.nonce)
    writer.writeBlob(envelope.bundle)
}
```

```kotlin
// decode
EnvelopeType.BUNDLE_REQUEST -> {
    requireBundleVersion(wireVersion)
    Envelope.BundleRequest(id, timestamp, reader.readBytes(EnvelopeLimits.PAIRING_NONCE_BYTES))
}
EnvelopeType.BUNDLE_RESPONSE -> {
    requireBundleVersion(wireVersion)
    Envelope.BundleResponse(
        id,
        timestamp,
        reader.readBytes(EnvelopeLimits.PAIRING_NONCE_BYTES),
        reader.readBlob(EnvelopeLimits.MAX_KEY_BUNDLE_BYTES),
    )
}
```

```kotlin
/**
 * The two pairing-bundle tags did not exist before wire version 3. Refusing them outright is
 * what keeps the version number honest: a version 2 envelope claiming tag 12 is a forgery or a
 * corruption, never an older body that happens to parse.
 */
private fun requireBundleVersion(wireVersion: Int) {
    require(wireVersion >= bundleVersion) { "Pairing bundle envelopes require wire version $bundleVersion" }
}
```

Mais `tagForType`/`typeForTag` ganhando `BUNDLE_REQUEST -> 12` / `BUNDLE_RESPONSE -> 13`, e
`validate()` dois ramos novos: `requirePairingNonce` (o nonce tem que medir exatamente
`PAIRING_NONCE_BYTES`) e, para `BundleResponse`, `bundle.size in 1..MAX_KEY_BUNDLE_BYTES`.

### Por que `decode` virou `wireVersion in 1..version` em vez de uma checagem de dois valores

Com a wire versão 2 (T-anterior, "Parte B: Encaminhar"), o codec já tinha dois valores válidos e
ainda assim `decode` comparava cada um por igualdade (`== legacyVersion || == version`). Isso
funcionava com dois valores, mas não escala: a cada versão nova a checagem cresceria uma cláusula
`||`. `wireVersion in 1..version` expressa diretamente a garantia real do formato — **toda** versão
já publicada continua aceita, porque nenhuma versão jamais removeu ou reformatou um corpo existente,
só acrescentou. A checagem passa a ser correta por construção à medida que `version` sobe, sem
precisar ser reescrita a cada extensão.

### Por que o nonce é largura fixa, sem prefixo de tamanho

Todo outro campo de bytes variável no codec (`body`, `ciphertext`, `bundle`) usa `writeBlob`/
`readBlob`, que prefixam um tamanho porque o tamanho realmente varia. O nonce de pareamento não
varia: `PairingEngine` sempre gera 16 bytes, e `validate()` já rejeita qualquer outro tamanho antes
do envelope conseguir ser codificado. Um prefixo de tamanho aqui não protegeria nada — só criaria um
segundo lugar (o prefixo) que poderia divergir do tamanho real dos bytes, ou de
`EnvelopeLimits.PAIRING_NONCE_BYTES`. `writer.write(envelope.nonce)` sem prefixo é o único jeito de
não ter essa segunda fonte de verdade.

### Por que uma tag nova sob wire versão antiga é recusada, não "adivinhada"

`requireBundleVersion` rejeita as tags 12/13 sob `wireVersion` 1 ou 2 em vez de tentar decodificá-las
mesmo assim. A alternativa — aceitar a tag e decodificar com o layout de versão 3 de qualquer forma —
apagaria a garantia que o número de versão existe para dar: um remetente honesto rodando código
anterior a T4.16 **fisicamente não pode** produzir a tag 12 ou 13, porque esses tipos não existiam
no `Envelope` dele. Então um envelope que chega afirmando "versão 2, tag 12" só pode ser uma
forjação deliberada ou bytes corrompidos — nunca um corpo antigo por coincidência compatível. Tratar
isso como erro, e não como "versão antiga que também sabe fazer bundle", é o que faz o número de
versão continuar sendo uma afirmação confiável sobre o que o remetente realmente enviou.

### `legacyVersion` virou dois nomes: `forwardedVersion` e `bundleVersion`

Antes só existia uma "versão legada" porque só havia uma extensão. Agora há duas, cada uma com seu
próprio primeiro-suportado-a-partir-de: o byte `forwarded` de TEXT/ATTACHMENT existe a partir da
versão 2, as tags de bundle a partir da versão 3. Nomear cada constante pelo que ela marca
(`forwardedVersion`, `bundleVersion`) em vez de reciclar `legacyVersion` para as duas evita que a
próxima extensão precise decidir qual "legado" um novo `legacyVersion` deveria significar.

### O ponto central de compatibilidade: a versão 3 não mudou nenhum corpo pré-existente

Esse é o mesmo padrão que a versão 2 já tinha estabelecido, e continua valendo: a versão 3
**acrescentou** duas tags novas e não tocou em nenhum byte da codificação dos oito tipos que já
existiam (`TEXT`, `ATTACHMENT`, `ACK`, `EVIDENCE`, `KEY_PACKAGE`, `KEY_PACKAGE_REQUEST`,
`GROUP_LEAVE`, `CONTROL_REJECTED`). Por isso, para essas oito tags, **a versão 2 e a versão 3 são
byte a byte idênticas** — a única diferença entre reencodificar o mesmo envelope como v2 ou como v3
é o próprio campo de versão no cabeçalho. É esse fato que o teste novo
`version two envelopes of every pre-existing type still decode unchanged` prova diretamente (ver
`docs/changes/EnvelopeCodecTest.kt.md`), e é o que garante que nenhum histórico já persistido no
cofre (envelopes v1 ou v2 salvos em `messages.body`) deixa de decodificar depois desta mudança.

### Vantagens

- O bundle PQXDH (~1832 bytes) sai do QR e passa a viajar por um par de envelopes dedicado, com
  limites próprios (`PAIRING_NONCE_BYTES`, `MAX_KEY_BUNDLE_BYTES`) em vez de reaproveitar
  `MAX_ENVELOPE_BYTES` genérico.
- `decode` ganha uma regra que escala (`wireVersion in 1..version`) em vez de uma lista de
  igualdades que cresceria a cada versão futura.
- As tags novas são estruturalmente impossíveis de confundir com um corpo antigo: `requireBundleVersion`
  faz a rejeição acontecer no ponto de decisão certo, antes de qualquer campo de bundle ser lido.
- Nenhum dos oito tipos pré-existentes muda de layout — retrocompatibilidade total com o histórico
  já persistido, comprovada por teste (não só por argumento).

### Impacto nos testes existentes

`EnvelopeCodecTest` tinha as duas asserções de versão presas à v2, ajustadas junto (ver
`docs/changes/EnvelopeCodecTest.kt.md`): o cabeçalho de versão esperado vai de `byteArrayOf(0, 2)`
para `byteArrayOf(0, 3)`, e a "primeira versão realmente não suportada" no teste de rejeição vai de
3 para 4.
