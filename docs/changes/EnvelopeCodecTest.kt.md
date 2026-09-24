# core/src/test/kotlin/dev/mx3/nomessages/core/messaging/EnvelopeCodecTest.kt

## 2026-09-17 — Cobertura do flag `forwarded` e vetor de compatibilidade com a wire v1

### Motivo

Parte B do pedido do usuário (Encaminhar mensagens no estilo WhatsApp). `EnvelopeCodec` passou a
emitir wire versão 2 e a aceitar as versões 1 e 2 (ver `docs/changes/EnvelopeCodec.kt.md`). Duas
coisas precisavam de prova em teste: que o flag sobrevive ao round-trip, e que um envelope antigo
(sem o byte) continua decodificando com `forwarded = false`.

### Testes adicionados

**1. `round trips the forwarded flag for text and attachments`**

Para `forwarded = false` e `forwarded = true`, codifica e decodifica um `Envelope.Text` e um
`Envelope.Attachment` e confere que o valor volta igual.

**2. `forwarded flag is the last byte of the body and must be zero or one`**

Prova o layout documentado, não só o comportamento:

```kotlin
val plain = EnvelopeCodec.encode(Envelope.Text(id, 1, "x"))
val forwarded = EnvelopeCodec.encode(Envelope.Text(id, 1, "x", forwarded = true))

assertEquals(plain.size, forwarded.size)
assertEquals(0, plain[plain.lastIndex].toInt())
assertEquals(1, forwarded[forwarded.lastIndex].toInt())
assertArrayEquals(plain.copyOfRange(6, plain.lastIndex), forwarded.copyOfRange(6, forwarded.lastIndex))
assertRejected(plain.copyOf().also { it[it.lastIndex] = 2.toByte() })
```

Ou seja: os dois envelopes diferem **exclusivamente** no último byte, e um valor que não seja 0 nem 1
naquela posição é rejeitado (não silenciosamente tratado como verdadeiro).

**3. `decodes a version one text envelope with forwarded defaulting to false`** — o vetor de
compatibilidade pedido.

O array de bytes é montado à mão no próprio teste com `ByteArrayOutputStream`/`DataOutputStream`
(mesma técnica que o `EnvelopeCodec` usa internamente), sem chamar nenhum método privado do codec:

```kotlin
writer.write("NMFM".toByteArray(Charsets.US_ASCII)) // magic, 4 bytes
writer.writeShort(1)                                // versão 1, u16 big-endian
writer.writeByte(1)                                 // tag 1 = TEXT
writer.writeShort(idBytes.size); writer.write(idBytes) // id: u16 + 36 bytes UTF-8
writer.writeLong(timestamp)                         // i64 big-endian
writer.writeInt(bodyBytes.size); writer.write(bodyBytes) // body: blob i32 + UTF-8
// nada depois: o byte de forwarded não existia na v1
```

**Offsets conferidos contra o `EnvelopeCodec` atual antes de fechar o teste:**

| campo | escrita (`DataOutputStream`) | leitura (`Reader`) | bytes |
| --- | --- | --- | --- |
| magic | `write(magic)` | `readBytes(4)` + `contentEquals` | 4 |
| version | `writeShort` (big-endian) | `readUnsignedShort()` = `(b0 shl 8) or b1` (big-endian) | 2 |
| tag | `writeByte` | `readUnsignedByte()` | 1 |
| id | `writeShortUtf8` = `writeShort(len)` + bytes | `readShortUtf8(36)` | 2 + 36 |
| timestamp | `writeLong` (big-endian) | `readLong()` via `ByteBuffer` (big-endian por padrão) | 8 |
| body | `writeUtf8` → `writeBlob` = `writeInt(len)` + bytes | `readUtf8` → `readBlob` → `readInt()` via `ByteBuffer` | 4 + len |

O próprio teste afirma o total (`4 + 2 + 1 + 2 + 36 + 8 + 4 + body.size`), então se algum desses
offsets mudar no futuro o teste quebra em vez de passar por acaso.

Depois de decodificar, o teste confere `id`, `timestamp`, `body` e `forwarded == false`, e ainda que
reencodificar o resultado produz um envelope **versão 2**, exatamente um byte maior, com todo o
miolo (do offset 6 em diante) idêntico ao vetor v1.

### Ajustes em testes já existentes

Dois testes estavam presos à versão 1 e passaram a falhar com o codec novo:

| teste | como era | como ficou | porquê |
| --- | --- | --- | --- |
| `encoded envelope starts with fixed magic version and type` | `assertArrayEquals(byteArrayOf(0, 1), bytes.copyOfRange(4, 6))` | `byteArrayOf(0, 2)` | `encode` agora sempre emite a versão corrente, que é 2 |
| `rejects illegal magic version type and trailing bytes` | `assertRejected(valid.copyOf().also { it[5] = 2.toByte() })` | `it[5] = 3.toByte()` | a versão 2 passou a ser válida; a primeira versão realmente não suportada é a 3 |

`assertEnvelopeEquals` (helper do próprio arquivo) também passou a comparar `forwarded` nos ramos
`Envelope.Text` e `Envelope.Attachment`, então **todo** teste que já usava o helper — inclusive
`round trips every envelope type` — passou a cobrir o campo de graça.

Novos imports: `java.io.ByteArrayOutputStream`, `java.io.DataOutputStream`,
`org.junit.jupiter.api.Assertions.assertFalse` e `assertTrue`.

### Vantagens

- O contrato de wire vira executável: a posição do byte, o valor permitido e a compatibilidade com a
  v1 são todos verificados, não apenas descritos em comentário.
- O vetor v1 é construído independentemente do codec, então ele detecta uma regressão mesmo que
  `encode` e `decode` mudem juntos de forma consistente (o erro clássico de round-trip).

## 2026-09-17 — Cobertura da wire versão 3: bundle de pareamento e prova de retrocompatibilidade (T4.16)

### Motivo

`EnvelopeCodec` passou a emitir wire versão 3 e a aceitar as tags 12/13 (`BundleRequest`/
`BundleResponse`) só a partir dela (ver `docs/changes/EnvelopeCodec.kt.md`). Três coisas precisavam
de prova em teste: que o par novo sobrevive ao round-trip e respeita seus próprios limites; que as
duas tags são recusadas sob wire versão antiga em vez de decodificadas com um layout adivinhado; e
que — ponto central desta mudança — nenhum dos oito tipos que já existiam mudou de comportamento.

### Ajustes nas asserções de versão já existentes

Duas asserções presas à versão 2 tiveram que mover para a versão 3, pela mesma razão que já as
tinha movido de 1 para 2 na mudança anterior:

```kotlin
// encoded envelope starts with fixed magic version and type
- assertArrayEquals(byteArrayOf(0, 2), bytes.copyOfRange(4, 6))
+ assertArrayEquals(byteArrayOf(0, 3), bytes.copyOfRange(4, 6))
```

```kotlin
// rejects illegal magic version type and trailing bytes
- assertRejected(valid.copyOf().also { it[5] = 3.toByte() })
+ assertRejected(valid.copyOf().also { it[5] = 4.toByte() })
```

`encode` agora sempre emite a versão corrente (3); a primeira versão realmente não suportada deixou
de ser 3 e passou a ser 4, porque 3 virou válida. O mesmo padrão se repete no teste de
retrocompatibilidade v1 (`decodes a version one text envelope...`): o comentário sobre o
reencodificado agora nota explicitamente que a versão 3 **não acrescentou nenhum campo a TEXT**, então
a única diferença de tamanho entre o vetor v1 e o reencodificado continua sendo o único byte de
`forwarded` que a v2 já tinha introduzido — não um segundo byte novo da v3.

`round trips every envelope type` ganhou `Envelope.BundleRequest` e `Envelope.BundleResponse` na
lista de envelopes cobertos, e `assertEnvelopeEquals` ganhou os dois ramos correspondentes
(`assertArrayEquals` em `nonce`, e em `nonce` + `bundle` para a resposta) — então qualquer teste que
já usava o helper passou a cobrir os dois tipos novos de graça, do mesmo jeito que já tinha
acontecido com `forwarded` na mudança anterior.

### Testes novos

**1. `pairing bundle envelopes round trip and bound their fields`**

Codifica e decodifica um `BundleRequest` e um `BundleResponse` com um bundle de ~1832 bytes (o
tamanho real medido do PQXDH) e confere que `nonce`/`bundle` voltam iguais. Depois prova os três
limites de `validate()` diretamente:

```kotlin
assertThrows(IllegalArgumentException::class.java) {
    EnvelopeCodec.encode(Envelope.BundleRequest(id, 9, ByteArray(15)))
}
assertThrows(IllegalArgumentException::class.java) {
    EnvelopeCodec.encode(Envelope.BundleResponse(id, 9, nonce, ByteArray(0)))
}
assertThrows(IllegalArgumentException::class.java) {
    EnvelopeCodec.encode(Envelope.BundleResponse(id, 9, nonce, ByteArray(EnvelopeLimits.MAX_KEY_BUNDLE_BYTES + 1)))
}
```

Ou seja: um nonce de 15 bytes (não 16) é recusado no `encode`, um `bundle` vazio é recusado, e um
`bundle` um byte acima de `MAX_KEY_BUNDLE_BYTES` também — os três nos limites exatos, não com
valores folgados que só provariam "existe algum limite em algum lugar".

**2. `pairing bundle envelopes are refused under wire versions one and two`**

Prova `requireBundleVersion` diretamente: codifica um `BundleRequest` válido (que sai com wire
versão 3 e tag 12, conferido por `assertEquals(12, encoded[6].toInt())`), depois força o byte de
versão do envelope já codificado para 2 e para 1 e confere que os dois são rejeitados:

```kotlin
val encoded = EnvelopeCodec.encode(Envelope.BundleRequest(id, 9, ByteArray(16) { it.toByte() }))
assertEquals(12, encoded[6].toInt())
assertRejected(encoded.copyOf().also { it[5] = 2.toByte() })
assertRejected(encoded.copyOf().also { it[5] = 1.toByte() })
```

Isto é a prova de que uma tag 12 sob versão 2 é tratada como forjação/corrupção — recusada — e nunca
como "um corpo antigo que por acaso também sabe decodificar bundle", exatamente o comportamento que
`requireBundleVersion` documenta.

**3. `version two envelopes of every pre-existing type still decode unchanged`**

O teste mais importante desta rodada para o argumento de retrocompatibilidade. Para os sete outros
tipos representativos (`Text`, `Ack`, `Evidence`, `KeyPackage`, `KeyPackageRequest`, `GroupLeave`,
`ControlRejected`), codifica cada um (o que produz naturalmente um envelope versão 3), força o byte
de versão de volta para 2, decodifica, e confere que o resultado é **idêntico** ao original:

```kotlin
envelopes.forEach { expected ->
    val asVersionTwo = EnvelopeCodec.encode(expected).also { it[5] = 2.toByte() }
    assertEnvelopeEquals(expected, EnvelopeCodec.decode(asVersionTwo))
}
```

Isto é o que "retrocompatível" tem que significar concretamente para as oito tags anteriores a
T4.16: não "o codec ainda compila", mas **o mesmo array de bytes, rotulado como versão 2 em vez de
versão 3, produz exatamente o mesmo envelope**. Se a versão 3 tivesse mudado um único byte de
qualquer um desses corpos, este teste quebraria — é ele, e não um comentário de código, quem garante
que nenhum histórico já persistido no cofre (envelopes v1/v2 salvos em `messages.body`) para de
decodificar depois desta mudança.

### Vantagens

- Os dois tipos novos (`BundleRequest`/`BundleResponse`) têm a mesma cobertura de round-trip, limite
  e helper de igualdade que todo tipo pré-existente já tinha — nenhum tratamento especial "porque é
  novo".
- A regra "tag nova, versão antiga → recusado" deixa de ser só um comentário em
  `requireBundleVersion`: há um teste que efetivamente força essa combinação e confere a exceção.
- A garantia central da mudança — nenhum corpo pré-existente mudou entre v2 e v3 — passa a ser
  verificada por teste, byte a byte, em vez de apoiada só no argumento do KDoc.
