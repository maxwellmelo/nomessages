# `app/src/test/kotlin/dev/mx3/nomessages/storage/Sha3_256Test.kt`

## 2026-09-15 — T3.1: arquivo novo, teste do SHA3-256 embutido

### Como era antes

O arquivo não existia, porque o digest também não: o SHA3-256 vinha do provedor
da plataforma e não havia nada do app para testar.

### Como ficou

Dois casos JUnit 5, no mesmo estilo dos demais testes de unidade do módulo:

```kotlin
@Test fun matchesTheFips202VectorsAroundThePaddingBoundaries() {
    // 0 bytes: o separador de domínio e o terminador dividem o mesmo bloco vazio
    assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", hex(Sha3_256.digest(ByteArray(0))))
    assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", hex(Sha3_256.digest("abc".toByteArray(US_ASCII))))
    // 135 bytes: o padding cabe no único byte restante do bloco
    // 136 bytes: um bloco cheio obriga um bloco extra só de padding
}

@Test fun matchesTheReferenceImplementationOnMultiBlockInputs() {
    // 1, 64, 137, 271, 272, 1000 e 4096 bytes pseudoaleatórios, semente fixa
}
```

A referência é `MessageDigest.getInstance("SHA3-256")` do JDK, disponível nos
testes de unidade (que rodam em JVM) justamente porque **não** está disponível no
Android — que é o motivo de o digest existir dentro do app.

### Por que a mudança

Criptografia escrita à mão só é aceitável com verificação objetiva. Os casos
cobrem exatamente onde uma implementação de Keccak costuma errar: mensagem
vazia, bloco de taxa exatamente cheio (136 bytes, que força um bloco inteiro de
padding), um byte a menos que a taxa (135, em que `0x06` e `0x80` se combinam no
mesmo byte) e entradas de múltiplos blocos.

### Vantagens

- O digest fica preso ao padrão por dois lados: vetores fixos do FIPS 202 e
  comparação com a implementação do JDK.
- O teste roda em `:app:testDebugUnitTest`, ou seja, também no CI, sem precisar
  de emulador ou aparelho.
- Uma regressão no endereço sintético da isca (que quebraria a negação plausível
  sem quebrar teste nenhum) passa a ser detectada em segundos.
