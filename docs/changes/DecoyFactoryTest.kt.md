# `app/src/test/kotlin/dev/mx3/nomessages/storage/DecoyFactoryTest.kt`

## 2026-09-15 — Arquivo novo: teste de regressão para o defeito da chave pública sintética

### Como era antes

O arquivo não existia. `DecoyFactory` não tinha nenhum teste JVM (só é exercitada indiretamente
pela suíte instrumentada, que precisa de aparelho/emulador), então o defeito descrito em
`docs/changes/DecoyFactory.kt.md` (seção "Revisão adversarial do commit 8c7f0f7") — o endereço
`.onion` sintético embutindo bytes aleatórios crus em vez de uma chave pública Ed25519 real — não
tinha como ser pego em segundos, só por inspeção manual ou pela suíte instrumentada completa.

### Como ficou

Um teste único, com um `Crypto` falso mínimo (`FakeCrypto`) que só implementa o que
`syntheticOnion()` realmente chama (`random`, `signingKeyPair`) e falha alto em qualquer outro
método:

```kotlin
@Test fun syntheticOnionEmbedsTheSigningKeyPairsPublicKeyNotRawRandomBytes() {
    val signingPublicKey = ByteArray(32) { 0xAB.toByte() }
    val distinguishableRandomBytes = ByteArray(32) { 0xCD.toByte() }
    val crypto = FakeCrypto(signingPublicKey, distinguishableRandomBytes)

    val onion = DecoyFactory(crypto).syntheticOnion()
    val address = decodeBase32(onion.removeSuffix(".onion"))

    assertArrayEquals(signingPublicKey, address.copyOfRange(0, 32))
    assertFalse(address.copyOfRange(0, 32).contentEquals(distinguishableRandomBytes))
    // + checksum SHA3-256(".onion checksum" || pubkey || 3)[0..2] e byte de versão == 3
}
```

`DecoyFactory.syntheticOnion()` passou de `private` para `internal` (ver
`docs/changes/DecoyFactory.kt.md`) só para o teste, no mesmo módulo, poder chamá-lo diretamente.

### Por que a mudança

Testar só o formato do endereço (comprimento, alfabeto, checksum) não pegaria o defeito: bytes
aleatórios crus produzem um endereço com formato perfeito na grande maioria das vezes — o problema
só aparece ao tentar decodificar a chave embutida como ponto Ed25519, algo que este teste não
reimplementa (exigiria aritmética de curva em Kotlin só para o teste). Em vez disso, o teste prende
a **origem** dos bytes: usa um `random()` e um `signingKeyPair()` que devolvem valores diferentes e
reconhecíveis, e verifica que o endereço final embute o do `signingKeyPair()` — que é exatamente
por onde a implementação real garante um ponto sempre válido — e não o do `random()`.

### Vantagens

- Fixa a causa raiz (fonte da chave), não só o sintoma (formato do endereço), então uma regressão
  futura para `crypto.random(32)` quebra este teste de forma direta e óbvia.
- Roda em `:app:testDebugUnitTest`, sem aparelho/emulador — mesmo caminho de CI usado por
  `Sha3_256Test`.
- `FakeCrypto` falha alto (`UnsupportedOperationException`) em qualquer método que `syntheticOnion()`
  não deveria chamar, então uma implementação futura que volte a depender de outra operação de
  `Crypto` (ex.: `hash()`) é forçada a atualizar o teste em vez de passar silenciosamente com um
  caminho não coberto.

### Validação

`:app:testDebugUnitTest --rerun --tests "dev.mx3.nomessages.storage.*"`: `DecoyFactoryTest` 1/1,
`Sha3_256Test` 2/2, 0 falhas. Suíte completa do módulo (`:app:testDebugUnitTest --rerun`, sem
filtro) também verde.
