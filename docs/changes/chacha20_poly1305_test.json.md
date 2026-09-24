# native/tests/vectors/chacha20_poly1305_test.json

## 2026-09-14 — T4.3: corpus Wycheproof ChaCha20-Poly1305

### Como era antes

O arquivo não existia; o diretório `native/tests/vectors/` também não. Não havia
nenhum caso conhecido de ChaCha20-Poly1305 no repositório.

### Como ficou

Cópia **byte a byte** do arquivo publicado, sem qualquer edição:

- Origem:
  `https://raw.githubusercontent.com/C2SP/wycheproof/master/testvectors_v1/chacha20_poly1305_test.json`
- Baixado em: 2026-09-14
- SHA-256: `fe61d25f90e1bde4461d00eafe61049e5f29bd999f36b766df9cda90906ad53d`
- Tamanho: 241.127 bytes; `numberOfTests`: 325 (256 `valid`, 69 `invalid`)
- Schema: `aead_test_schema_v1.json`; 10 grupos, chave de 256 bits e tag de 128
  bits em todos; 9 grupos existem só para nonces de tamanho inválido

Consumido por `native/tests/kat.rs::wycheproof_chacha20_poly1305`, que verifica
o SHA-256 do arquivo antes de usá-lo. O caso `tcId 1` é o próprio vetor do
RFC 8439 §2.8.2, reexecutado também de forma explícita em
`rfc8439_chacha20_poly1305_aead_vector`.

### Por que a mudança foi feita

`crypto.rs` não exporta ChaCha20-Poly1305 IETF, mas o XChaCha20-Poly1305 que ele
exporta **é** essa mesma construção precedida de HChaCha20, na mesma build de
libsodium embutida. Validar a variante IETF com o corpus oficial cobre o núcleo
ChaCha20 + Poly1305 com casos de borda que nenhum teste de ida e volta gera
(tags modificadas, chaves de Poly1305 degeneradas, textos cifrados extremos).

### Vantagens

- Corpus mantido intacto: o SHA-256 registrado pode ser comparado diretamente
  com o arquivo upstream por qualquer auditor.
- Fica versionado junto do código, então a suíte roda offline e determinística,
  sem baixar nada em tempo de teste.

### Não verificado

O branch `master` do Wycheproof não é uma tag imutável. A garantia de que este
arquivo é o mesmo de 2026-09-14 vem do SHA-256 fixado no teste, não do upstream.
