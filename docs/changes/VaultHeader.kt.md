# core/src/main/kotlin/dev/mx3/nomessages/core/vault/VaultHeader.kt

## 2026-09-23 — `kdfParams`: os parâmetros de custo do KDF, legíveis sem senha (T4.7a)

### Motivo

`VaultManager.fakePanicPasswordChange` (ver `docs/changes/VaultManager.kt.md`) precisa pagar
exatamente o mesmo custo de Argon2id que `resetPanic` paga, contra os parâmetros **reais** e
calibrados deste cofre — não um valor fixo/estimado — para que o tempo de resposta do botão "trocar
senha de pânico" não vire, ele mesmo, um oráculo de isca (uma isca "rápida demais" ou "devagar
demais" em relação ao cofre real seria tão reveladora quanto o botão que só aparecia no real). Esses
parâmetros (`memoryKiB`, `iterations`) já ficam gravados em claro no prefixo do `header.bin` —
`unlock` e `resetPanic` já os liam nos mesmos dois `int`s, na mesma posição, antes de qualquer
derivação — mas não existia nenhuma função pública para lê-los sem também autenticar uma senha.

### Como era antes

Sem função dedicada; cada chamador que precisava dos parâmetros os lia manualmente do prefixo, por
exemplo em `resetPanic`:

```kotlin
val prefix = encoded.copyOfRange(0, PREFIX_SIZE)
val buffer = ByteBuffer.wrap(prefix)
buffer.position(6)
val params = KdfParams(buffer.int, buffer.int)
```

Não havia como um chamador fora de `VaultHeader` obter esses parâmetros sem duplicar esse código ou
sem ter uma senha para chamar `unlock`.

### Como ficou

```kotlin
/**
 * The Argon2id cost parameters recorded in the header prefix, readable without any password ...
 * Exists so [VaultManager.fakePanicPasswordChange] can pay the *real* calibrated KDF cost for a
 * decoy-slot "change panic password" without ever opening a session ...
 */
fun kdfParams(encoded: ByteArray): KdfParams {
    requireStructure(encoded)
    val buffer = ByteBuffer.wrap(encoded)
    buffer.position(6)
    return KdfParams(buffer.int, buffer.int)
}
```

`requireStructure` (já existente) valida o formato do cabeçalho primeiro, então uma chamada com um
`header.bin` corrompido ou de formato desconhecido falha com o mesmo `SecurityException("Invalid
vault")` que qualquer outra leitura do cabeçalho já usava — `kdfParams` não introduz um novo modo de
falha.

### Por que é seguro expor isto sem senha

Os dois inteiros lidos aqui (custo de memória e iterações do Argon2id) **já** eram públicos em
texto claro no prefixo do `header.bin` antes desta mudança — `requireStructure` já os decodifica e
descarta só para validar o formato, e `unlock`/`resetPanic` já os liam sem nenhuma senha, porque a
derivação em si é o que precisa deles, não o inverso. `kdfParams` não expõe nenhum segredo novo; só
dá um nome público a uma leitura que já não dependia de segredo nenhum.

### Vantagens

- Elimina a duplicação da leitura manual do prefixo que `resetPanic` já fazia, dando um nome e uma
  descrição a essa leitura.
- Permite que `VaultManager.fakePanicPasswordChange` pague o custo de KDF certo sem abrir sessão
  nem conhecer senha nenhuma.
- Reaproveita `requireStructure` para a validação de formato, então um cabeçalho malformado falha do
  mesmo jeito em todo caminho que o lê.

Ver também `docs/security-model.md` ("Decoy oracles fixed...", T4.7) e
`docs/changes/security-model.md.md`.
