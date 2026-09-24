# app/src/main/kotlin/dev/mx3/nomessages/storage/OnionAddress.kt

## 2026-09-17 — arquivo novo: conversão entre chave pública Ed25519 e endereço `.onion` v3 (T4.16)

### Como era antes

O arquivo não existia. A única implementação do encadeamento `chave pública → endereço .onion v3`
vivia dentro de `DecoyFactory.syntheticOnion` (base32 + checksum SHA3-256), usada só para sintetizar
onions de contatos do cofre-isca — sempre em uma direção, nunca a inversa (endereço → chave).

O T4.16 precisou da direção inversa pela primeira vez: o campo `doorbellOnion` (ver
`docs/changes/StorageModels.kt.md`) é armazenado como a **chave** de 32 bytes que veio da oferta de
pareamento assinada (não como o texto do endereço — ver a seção "Deviation" abaixo), e o texto de 62
caracteres só precisa existir quando a UI/o `NoMessagesController` reconstroem o endereço para
mostrar ou discar. Duas direções, um único código de baixo nível para as duas.

### Como ficou

```kotlin
internal object OnionAddress {
    const val KEY_BYTES = 32
    private const val ENCODED_LENGTH = 56
    private const val SUFFIX = ".onion"
    private val CHECKSUM_PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)
    private val VERSION = byteArrayOf(3)
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    fun address(publicKey: ByteArray): String {
        require(publicKey.size == KEY_BYTES) { "A v3 onion identity key is $KEY_BYTES bytes" }
        val checksumInput = CHECKSUM_PREFIX + publicKey + VERSION
        val checksum = Sha3_256.digest(checksumInput)
        val payload = publicKey + checksum.copyOf(2) + VERSION
        return try { base32(payload) + SUFFIX }
        finally { checksumInput.fill(0); checksum.fill(0); payload.fill(0) }
    }

    fun publicKey(address: String): ByteArray {
        require(address.length == ENCODED_LENGTH + SUFFIX.length && address.endsWith(SUFFIX)) {
            "Invalid v3 onion address"
        }
        val payload = unbase32(address.substring(0, ENCODED_LENGTH))
        require(payload[payload.lastIndex] == VERSION[0]) { "Unsupported onion address version" }
        val key = payload.copyOf(KEY_BYTES)
        val expected = address(key)
        require(expected == address) { "Onion address checksum mismatch" }
        return key
    }

    private fun base32(bytes: ByteArray): String { /* mesmo algoritmo de sempre, extraído */ }
    private fun unbase32(encoded: String): ByteArray { /* novo: inverso de base32 */ }
}
```

`DecoyFactory.syntheticOnion` foi reescrito para chamar `OnionAddress.address(...)` em vez de manter
sua própria cópia — ver `docs/changes/DecoyFactory.kt.md`.

### Formato v3 e o checksum

Um endereço v3 é `base32(chavePública[32] || checksum[2] || versão[1]) + ".onion"` — 56 caracteres
base32 codificando 35 bytes, onde o checksum é `SHA3-256(".onion checksum" || chavePública ||
versão)[0..1]` (rend-spec-v3 §6, a mesma especificação que o `HsIdKeypair` nativo do projeto segue —
ver `native/src/tor.rs`). Dos 35 bytes do payload, só os 32 da chave carregam informação: checksum e
versão são funções determinísticas da chave, por isso a oferta de pareamento transporta a chave de
32 bytes e não o texto de 62 caracteres (ver a seção "DEVIATION" da fact sheet do T4.16 e
`docs/changes/Pairing.kt.md`) — o QR economiza exatamente os 30 bytes que seriam redundantes.

`56` caracteres base32 carregam exatamente `56 × 5 = 280` bits, e o payload de 35 bytes usa
`35 × 8 = 280` bits — os dois números batem exatamente, então `unbase32` não sobra nenhum bit para
descartar ou verificar (diferente de um alfabeto base32 com padding, onde bits sobrando teriam que
ser conferidos como zero). `check(index == output.size && bits == 0)` em `unbase32` é essa
propriedade expressa em código, não uma margem de segurança arbitrária.

### Por que `publicKey` verifica o checksum em vez de só decodificar

`publicKey(address)` não se contenta em decodificar os 32 primeiros bytes e descartar o resto — ele
recalcula `address(key)` e compara com a entrada (`require(expected == address)`). Um endereço
digitado errado, colado pela metade, ou truncado por um copy-paste ruim decodifica sem lançar exceção
de "caractere inválido" na maioria dos casos (o alfabeto base32 é permissivo); sem a verificação de
checksum, esse endereço malformado seguiria adiante e acabaria **assinado** dentro de uma oferta de
pareamento — momento em que o erro já não é mais recuperável sem reiniciar o pareamento inteiro. A
verificação de checksum é o único ponto barato de pegar isso, e é exatamente o mesmo checksum que o
Tor real verifica ao publicar/resolver o serviço, então "endereço aceito por `OnionAddress`" e
"endereço aceito pela rede Tor" nunca divergem.

### Por que o código foi EXTRAÍDO de `DecoyFactory.syntheticOnion`, não duplicado

A regra do repositório é verificar se já existe implementação antes de criar uma nova
(`CLAUDE.md`). `DecoyFactory.syntheticOnion` já tinha a única cópia do algoritmo de base32 + checksum
v3 no projeto, auditada e testada byte a byte contra a rede real (ver a revisão adversarial de
2026-09-15 em `docs/changes/DecoyFactory.kt.md` — o defeito da chave sintética inválida foi corrigido
ali). Duplicar esse código para `OnionAddress` teria criado duas implementações do mesmo formato de
endereço que precisariam ser mantidas byte-a-byte idênticas para sempre; um ajuste futuro em uma e
esquecido na outra produziria endereços de isca detectáveis por divergirem do formato real, ou pior,
endereços de campainha reais calculados errado. A extração deixa exatamente **uma** implementação: a
de isca chama `OnionAddress.address`, e os endereços que ela produz continuam byte-idênticos aos de
antes da extração (ver `docs/changes/DecoyFactory.kt.md`, seção de verificação).

### Por que o `Sha3_256` embutido, e não `MessageDigest`

O Android não expõe SHA3-256 por `MessageDigest` (Conscrypt não implementa a família SHA-3; o
BouncyCastle reempacotado da plataforma a removeu) — o mesmo motivo documentado em
`docs/changes/Sha3_256.kt.md` quando esse digest foi escrito para `DecoyFactory`. `OnionAddress`
reaproveita esse mesmo objeto `internal object Sha3_256` em vez de pedir o algoritmo ao provedor da
plataforma.

### Vantagens

- Uma única implementação do formato de endereço v3 no projeto, usada pela isca (`DecoyFactory`) e
  pelo dado real (campo `doorbellOnion` do T4.16/T4.17) — impossível divergirem.
- `publicKey()` é a primeira função do projeto capaz de validar um endereço `.onion` v3 digitado ou
  colado, com verificação de checksum, antes de qualquer coisa que dependa dele (como assiná-lo numa
  oferta) rodar.
- `internal`, sem estado, sem dependência de Android — testável como JVM puro.
