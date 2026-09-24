# native/tests/vectors/xchacha20_poly1305_test.json

## 2026-09-14 — T4.3: corpus Wycheproof XChaCha20-Poly1305

### Como era antes

O arquivo não existia. O XChaCha20-Poly1305 — a primitiva de `crypto::seal` e
`crypto::open`, usada para todo o conteúdo selado do produto — era coberto
apenas por testes de ida e volta e por adulteração de tag/AAD em `crypto.rs`.

### Como ficou

Cópia **byte a byte** do arquivo publicado, sem qualquer edição:

- Origem:
  `https://raw.githubusercontent.com/C2SP/wycheproof/master/testvectors_v1/xchacha20_poly1305_test.json`
- Baixado em: 2026-09-14
- SHA-256: `a79de072571b90eb40c3a63ce0c7f75dcb4b62323c8870228e1f61dcc61d63a9`
- Tamanho: 232.350 bytes; `numberOfTests`: 315 (246 `valid`, 69 `invalid`)
- Schema: `aead_test_schema_v1.json`; grupo principal com nonce de 192 bits
  (306 casos) e 9 grupos de nonce inválido

Consumido por `native/tests/kat.rs::wycheproof_xchacha20_poly1305`. Os 306 casos
de nonce correto são montados no formato de quadro do produto
(`nonce24 + ciphertext + tag16`) e passam pela função exportada `crypto::open`;
os 9 de nonce malformado não são representáveis nesse quadro e contam como
recusa por construção. O `tcId 1` é o vetor A.3.1 do draft XChaCha, também
executado explicitamente em `xchacha_draft_a_3_1_aead_vector`.

### Por que a mudança foi feita

É o único corpus público de casos de borda para a primitiva AEAD que protege o
vault e as mensagens. Rodá-lo pelo `crypto::open` valida, de uma vez, a
primitiva, o layout de quadro e o contrato de erro (`Ok(None)` para falha de
autenticação, `Err` para argumento inválido).

### Vantagens

- Exercita o caminho real de decifragem do produto com 246 textos cifrados
  legítimos e 60 tags adulteradas, em vez de um punhado de casos escritos à mão.
- Confirma que `open` recusa — e nunca devolve texto claro parcial — em todos os
  casos `invalid`.

### Não verificado

`master` não é tag imutável; a fixação é pelo SHA-256 checado em tempo de teste.
