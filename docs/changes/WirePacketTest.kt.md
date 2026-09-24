# core/src/test/kotlin/dev/mx3/nomessages/core/messaging/WirePacketTest.kt

## 2026-09-17 — Round-trip de `BUNDLE` e o caso "kind desconhecido" migrado de tag 3 para tag 4 (T4.16)

### Motivo

`PacketKind.BUNDLE` (tag 3) e `WirePacket.encodeBundle` foram introduzidos para carregar o bundle de
pareamento fora do QR (`docs/changes/WirePacket.kt.md`). Precisava de cobertura própria de
round-trip, e o teste existente que usava a tag 3 para exercitar "kind desconhecido" passou a
apontar para um kind real — precisava ser corrigido, não só deixado passando.

### Como era antes

```kotlin
@Test
fun `round trips signal and mls payloads`() {
    val ciphertext = byteArrayOf(9, 8, 7)
    val signal = WirePacket.decode(WirePacket.encodeSignal(ciphertext))
    assertEquals(PacketKind.SIGNAL, signal.kind)
    assertArrayEquals(ciphertext, signal.payload)

    val mls = WirePacket.decode(WirePacket.encodeMls(ciphertext))
    assertEquals(PacketKind.MLS, mls.kind)
    assertArrayEquals(ciphertext, mls.payload)
}
```

```kotlin
@Test
fun `rejects malformed packets`() {
    ...
    assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(byteArrayOf(3, 0, 0, 0, 0)) }
    ...
}
```

Aqui a tag 3 fazia o papel de "kind que o `decode` não reconhece" — o `when` de `decode` só tinha
ramos para 1 (`SIGNAL`) e 2 (`MLS`), então qualquer outro valor caía no `else -> throw
IllegalArgumentException("Unknown packet kind")`.

### Como ficou

```kotlin
@Test
fun `round trips signal and mls payloads`() {
    val ciphertext = byteArrayOf(9, 8, 7)
    val signal = WirePacket.decode(WirePacket.encodeSignal(ciphertext))
    assertEquals(PacketKind.SIGNAL, signal.kind)
    assertArrayEquals(ciphertext, signal.payload)

    val mls = WirePacket.decode(WirePacket.encodeMls(ciphertext))
    assertEquals(PacketKind.MLS, mls.kind)
    assertArrayEquals(ciphertext, mls.payload)

    // BUNDLE (T4.16) carries a plaintext pairing envelope, but the framing is identical.
    val bundle = WirePacket.decode(WirePacket.encodeBundle(ciphertext))
    assertEquals(PacketKind.BUNDLE, bundle.kind)
    assertArrayEquals(ciphertext, bundle.payload)
}
```

```kotlin
@Test
fun `rejects malformed packets`() {
    ...
    // Kind 4 is the first unassigned tag; kind 3 became BUNDLE in T4.16.
    assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(byteArrayOf(4, 0, 0, 0, 1, 9)) }
    assertThrows(IllegalArgumentException::class.java) { WirePacket.decode(byteArrayOf(3, 0, 0, 0, 0)) }
    ...
}
```

### Por que o caso "kind desconhecido" precisava mudar de tag, e não podia só ser deletado

A tag 3 virou `PacketKind.BUNDLE`, então `byteArrayOf(3, 0, 0, 0, 0)` continuaria lançando
`IllegalArgumentException` — mas por um motivo diferente do que o teste alegava estar provando: o
payload de tamanho zero fere a checagem `bytes.size in HEADER_BYTES..MAX_PACKET_BYTES` no topo de
`decode`, não o `else` do `when` de kind. O teste continuaria "verde" com zero cobertura real do
ramo `"Unknown packet kind"` — o tipo de regressão silenciosa mais perigoso, porque nenhum teste
quebra para avisar que a proteção some. A correção não é remover a linha, é apontá-la para uma tag
que ainda é desconhecida de verdade: como 3 passou a estar ocupada, **4** é agora a primeira tag
livre, e é ela quem precisa exercitar esse `else`. O caso antigo (`byteArrayOf(3, 0, 0, 0, 0)`)
continua no teste, mas agora está corretamente exercitando a checagem de tamanho de payload — não é
redundante, é outro invariante.

### Vantagens

- `BUNDLE` ganha exatamente o mesmo nível de cobertura de round-trip que `SIGNAL` e `MLS` já tinham.
- O caso "kind desconhecido" volta a testar o que seu nome promete: nenhuma tag numérica hoje
  ocupada consegue mais passar por ele silenciosamente.
- Nenhum outro caso de `rejects malformed packets` mudou: os limites de tamanho de payload por kind
  continuam cobertos como antes.
