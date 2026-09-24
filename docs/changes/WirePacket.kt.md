# core/src/main/kotlin/dev/mx3/nomessages/core/messaging/WirePacket.kt

## 2026-09-17 — `PacketKind.BUNDLE`: o único payload que não é ciphertext (T4.16)

### Motivo

A busca do bundle PQXDH pela onion do par (`docs/changes/Envelope.kt.md`,
`docs/changes/EnvelopeCodec.kt.md`) precisa de um envelope `BundleRequest`/`BundleResponse`
trafegando pelo transporte Tor. Todo transporte deste arquivo até aqui só carregava dois tipos de
coisa — um pacote Signal ou um pacote MLS — e ambos são sempre ciphertext, cifrados antes de chegar
ao `WirePacket`. O bundle de pareamento não pode ser: ele é pedido e respondido **antes** de existir
qualquer sessão Signal entre as duas pontas, e é justamente a sessão Signal que faltava que ele
serve para estabelecer.

### Como era antes

```kotlin
enum class PacketKind {
    SIGNAL,
    MLS,
}
```

```kotlin
fun encodeSignal(ciphertext: ByteArray): ByteArray = encode(PacketKind.SIGNAL, ciphertext)

fun encodeMls(ciphertext: ByteArray): ByteArray = encode(PacketKind.MLS, ciphertext)
```

```kotlin
val kind = when (reader.get().toInt() and 0xff) {
    1 -> PacketKind.SIGNAL
    2 -> PacketKind.MLS
    else -> throw IllegalArgumentException("Unknown packet kind")
}
```

```kotlin
val tag = when (kind) {
    PacketKind.SIGNAL -> 1
    PacketKind.MLS -> 2
}
```

Duas tags ocupadas (1, 2); 3 era a primeira tag desconhecida, e era exatamente essa tag 3 que
`WirePacketTest` usava para cobrir o caminho "kind desconhecido" do `decode`.

### Como ficou

```kotlin
enum class PacketKind {
    SIGNAL,
    MLS,

    /**
     * Pairing key-bundle fetch (2026-09-17, T4.16).
     *
     * The only kind whose payload is **not** ciphertext: it carries a plaintext [Envelope]
     * (BUNDLE_REQUEST or BUNDLE_RESPONSE) because it is exchanged before any Signal session exists.
     * Confidentiality here is whatever the Tor stream provides; authenticity comes entirely from the
     * SHA-256 hash the peer signed inside the pairing QR, which the receiver checks before the bytes
     * are allowed to become a session. Nothing secret may ever be put in this kind - see
     * `docs/security-model.md`.
     */
    BUNDLE,
}
```

```kotlin
/** See [PacketKind.BUNDLE]: [plaintext] is an encoded pairing envelope, not ciphertext. */
fun encodeBundle(plaintext: ByteArray): ByteArray = encode(PacketKind.BUNDLE, plaintext)
```

```kotlin
val kind = when (reader.get().toInt() and 0xff) {
    1 -> PacketKind.SIGNAL
    2 -> PacketKind.MLS
    3 -> PacketKind.BUNDLE
    else -> throw IllegalArgumentException("Unknown packet kind")
}
```

```kotlin
val tag = when (kind) {
    PacketKind.SIGNAL -> 1
    PacketKind.MLS -> 2
    PacketKind.BUNDLE -> 3
}
```

Tag 3 passa a ser `BUNDLE`; **4 é a primeira tag não atribuída agora.**

### Por que `BUNDLE` é o único kind cujo payload não é ciphertext

`SIGNAL` e `MLS` carregam bytes que já saíram cifrados de uma camada de sessão antes de chegar ao
`WirePacket` — o pacote em si não precisa saber disso, ele só move bytes opacos com um tag na
frente. `BUNDLE` carrega um `Envelope` (`BundleRequest`/`BundleResponse`) codificado em texto claro
pelo `EnvelopeCodec`, porque a razão de ele existir é justamente permitir que a sessão Signal nasça
— não há sessão ainda para cifrar nada com ela. O parâmetro do novo método reflete isso no próprio
nome: `encodeSignal`/`encodeMls` recebem `ciphertext`, `encodeBundle` recebe `plaintext`.

Isso não é uma lacuna de segurança porque nada secreto é colocado dentro dele: como documentado em
`Envelope.BundleRequest`/`BundleResponse` (`docs/changes/Envelope.kt.md`), o nonce já foi lido de um
QR escaneado e o bundle é chave pública cuja integridade vem do hash assinado no QR, não do
transporte. O papel de `PacketKind.BUNDLE` é só declarar essa exceção no framing do jeito mais
explícito possível — no próprio KDoc do enum, no ponto onde qualquer leitura futura do arquivo
passa — em vez de deixá-la implícita.

### Impacto na cobertura de teste existente

A tag 3 tinha um segundo papel em `WirePacketTest`: era o valor usado para exercitar "kind
desconhecido" no `decode` (`byteArrayOf(3, 0, 0, 0, 0)`). Com a tag 3 virando `BUNDLE`, esse caso
teria continuado passando — `decode` ainda lançaria `IllegalArgumentException`, só que agora porque
o payload de tamanho zero fere `HEADER_BYTES..MAX_PACKET_BYTES`, não porque o kind é desconhecido.
Um teste "verde" pelo motivo errado é uma perda silenciosa de cobertura: o caminho `else -> throw
IllegalArgumentException("Unknown packet kind")` ficaria sem nenhum caso cobrindo-o. Ver
`docs/changes/WirePacketTest.kt.md` para o ajuste (o caso "kind desconhecido" moveu de tag 3 para
tag 4).

### Vantagens

- O bundle de pareamento ganha seu próprio kind de framing em vez de ser espremido dentro de
  `SIGNAL` ou `MLS` com uma convenção implícita para distingui-lo.
- A exceção "este payload não é ciphertext" fica documentada no próprio tipo que a introduz, no
  lugar que qualquer leitor futuro do enum vai ver primeiro.
- `encode`/`decode` e o cabeçalho de framing (tag + tamanho + payload) não mudam: só um `when` novo
  em cada direção, então nenhum outro kind é afetado.
