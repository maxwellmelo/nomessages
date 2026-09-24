# native/tests/kat.rs

## 2026-09-14 — T4.3: suíte de vetores conhecidos (KAT)

### Como era antes

O arquivo não existia. Todos os vetores conhecidos do crate nativo estavam
dentro do módulo `#[cfg(test)] mod tests` de `native/src/crypto.rs`, e cobriam
apenas quatro casos publicados:

```rust
// native/src/crypto.rs (antes e ainda hoje)
fn sha256_matches_fips_vector()                     // FIPS 180-4 "abc"
fn keyed_blake2b_256_matches_independent_vector()   // vetor independente
fn argon2id_uses_exact_memory_iterations_and_parallelism() // vetor independente
fn ed25519_matches_rfc8032_test_vector_one()        // RFC 8032 vetor 1
```

Não havia nenhum vetor do RFC 8439, nenhum do draft XChaCha e nenhum caso
Wycheproof. O diretório `native/tests/` só tinha `mls_roundtrip.rs` e
`tor_live.rs`, ou seja, testes de ida e volta — que, como o próprio checklist de
release diz, "não satisfazem por si só os vetores conhecidos".

### Como ficou

Novo teste de integração `native/tests/kat.rs` com sete testes:

| Teste | Cobertura |
|---|---|
| `rfc8439_chacha20_poly1305_aead_vector` | RFC 8439 §2.8.2, cifragem e decifragem |
| `xchacha_draft_a_3_1_aead_vector` | draft-irtf-cfrg-xchacha §A.3.1, via `crypto::open` no quadro `nonce24 + ct + tag16` |
| `wycheproof_chacha20_poly1305` | 325 casos |
| `wycheproof_xchacha20_poly1305` | 315 casos (306 pelo `crypto::open`) |
| `wycheproof_x25519` | 518 casos |
| `wycheproof_ed25519_verify` | 151 casos, pelo `crypto::verify` |
| `embedded_vector_corpus_is_intact` | soma de 1.309 casos e integridade dos quatro JSON |

Dois detalhes de projeto importantes:

```rust
// Sem dependência nova: o JSON do Wycheproof é embutido e lido por um
// parser mínimo dentro do próprio teste.
const X25519_VECTORS: &str = include_str!("vectors/x25519_test.json");
mod json { pub enum Value { Null, Bool(bool), Number(f64), Text(String), Array(..), Object(..) } }

// Cada arquivo é fixado por conteúdo: o digest é recalculado com a própria
// primitiva do produto (`crypto::hash`) e comparado com a constante do arquivo.
fn assert_digest(name: &str, contents: &str, expected: &str) { ... }
```

A semântica do Wycheproof é aplicada literalmente por `judge()`: `valid` precisa
ser aceito com o texto claro exato, `invalid` precisa ser recusado e
`acceptable` é apenas contado. A única regra derivada de comportamento é em
X25519: segredo compartilhado todo zero deve ser recusado pelo libsodium (ponto
de ordem baixa), qualquer outro deve ser reproduzido byte a byte.

### Por que a mudança foi feita

O gate 13 do `docs/release-checklist.md` exige "relatórios exatos para RFC
8439/7748/8032/9106 e casos Wycheproof relevantes" e diz explicitamente que
testes de ida e volta não bastam. A tarefa T4.3 do plano é fechar essa lacuna.

### Vantagens

- **Cobertura real de bordas.** Os 1.309 casos incluem pontos de torção,
  chaves públicas não canônicas, pontos de ordem baixa, `Poly1305` em casos
  extremos, maleabilidade de assinatura e overflow do Tink — cenários que um
  teste de ida e volta nunca gera.
- **Zero dependência nova.** O parser JSON próprio evita mexer em `Cargo.lock`
  (a suíte roda com `--locked`) e mantém o teste compilando sob qualquer
  combinação de features, inclusive `--no-default-features`.
- **Corpus à prova de edição silenciosa.** Se alguém alterar um JSON de vetores,
  o digest embutido falha o teste em vez de aceitar um vetor adulterado.
- **Caminho real exercitado.** Os vetores XChaCha e Ed25519 passam pelas funções
  exportadas `crypto::open` e `crypto::verify`, não por uma cópia paralela —
  incluindo o formato de quadro que `crypto::seal` emite.

### Não verificado

- `cargo fmt` e `cargo clippy` não rodaram: nenhum dos dois componentes está
  instalado no toolchain 1.91 do WSL. A formatação segue o estilo do restante do
  crate, mas não foi conferida por ferramenta.
- O corpus foi baixado em 2026-09-14 do branch `master` do C2SP/wycheproof. Não
  há tag fixada upstream; a fixação aqui é pelo SHA-256 registrado no teste e em
  `docs/development/crypto-report.md`.

---

## 2026-09-14 — Revisão: correções no oráculo e nos contadores da suíte

Revisão da entrega T4.3 apontou seis defeitos em `native/tests/kat.rs`. Todos
foram corrigidos e a suíte foi reexecutada (`cargo test --all-features --locked`
no WSL Ubuntu, Rust 1.91): 17 testes unitários, 7 KAT, 3 MLS, 0 falhas.

### 1. `judge` aceitava uma falsificação como recusa (P2)

**Como era antes**

```rust
"invalid" => assert_ne!(accepted, Accepted::Yes, "{case} should be rejected"),
```

`Accepted::Mismatch` é produzido quando `chacha::decrypt` / `crypto::open`
**tem sucesso** mas devolve um texto claro diferente do vetor. Para um caso
`invalid`, isso significa que o Poly1305 autenticou um ciphertext que o vetor
manda recusar — ou seja, uma falsificação. Como `Mismatch != Yes`, o
`assert_ne!` passava e a suíte ficava verde.

**Como ficou**

```rust
"invalid" => assert_eq!(accepted, Accepted::No, "{case} should be rejected"),
```

**Por quê / vantagem:** o corpus do gate 13 existe justamente para detectar
falsificação autenticada. O oráculo anterior era incapaz de reprovar o único
cenário que mais importa. A troca é segura: verificado que hoje todos os 138
casos `invalid` dos dois arquivos AEAD retornam `Err`/`Ok(None)`, então a suíte
continua verde.

### 2. `Accepted::Mismatch` era documentado como "reportado à parte", mas nunca contado (P3)

**Como era antes:** o comentário da variante dizia "is reported separately
because it would be a bug", e não existia contador, `println!` nem asserção em
nenhuma das quatro suítes.

**Como ficou:** `let mut mismatched = 0usize;` nas duas suítes AEAD, incrementado
no braço `Ok(_) => Accepted::Mismatch`, impresso na linha de placar e fechado com
`assert_eq!(mismatched, 0, "...")`. O comentário da variante foi reescrito para
descrever o que o código realmente faz.

**Por quê / vantagem:** o `assert_eq!` do item 1 já cobre os casos `invalid`; o
contador cobre também os `acceptable`, onde nenhuma asserção de resultado é
aplicada. O comentário deixa de prometer algo que o código não fazia.

### 3. Contagens publicadas no relatório não eram asseveradas (P3)

**Como era antes:** `wycheproof_chacha20_poly1305` fechava com
`assert_eq!(executed, 325)` e `assert_eq!(skipped, 0)`;
`wycheproof_ed25519_verify` fechava só com `assert_eq!(executed, 151)`. Os
valores `rejected_by_length` (9 e 12) eram apenas impressos — embora
`crypto-report.md` os publique como evidência do gate 13. A suíte XChaCha já
fixava o seu (`assert_eq!(rejected_by_length, 9)`), o que tornava a omissão
inconsistente dentro do mesmo arquivo.

**Como ficou:** `assert_eq!(rejected_by_length, 9);` na suíte ChaCha e
`assert_eq!(rejected_by_length, 12);` na suíte Ed25519, cada um com um comentário
dizendo que o número é citado no relatório.

**Por quê / vantagem:** um número publicado como evidência de release tem de
quebrar o build quando deixar de ser verdade. Ambos foram reconferidos por parse
do JSON antes de serem fixados.

### 4. `rejected_by_length` do Ed25519 contava o erro errado (P3)

**Como era antes**

```rust
Err(_) => {
    // A signature whose length is not 64 bytes never reaches libsodium; ...
    rejected_by_length += 1;
    Accepted::No
}
```

`crypto::verify` (`native/src/crypto.rs:165-181`) devolve `InvalidArgument` por
**três** motivos distintos: mensagem acima de 64 MiB, chave pública que não tem
32 bytes e assinatura que não tem 64 bytes. Hoje o número coincide porque os 78
grupos do corpus têm chave de 32 bytes e exatamente 12 testes têm assinatura fora
de 64 bytes (reconferido por parse), mas um corpus regenerado com um grupo de
chave malformada inflaria o mesmo contador e manteria o teste verde enquanto a
frase do relatório se tornaria falsa.

**Como ficou:** a causa esperada é decidida a partir do próprio vetor
(`let bad_signature_length = signature.len() != 64;`); só esse caso incrementa o
contador, e qualquer outro `Err` vira
`panic!("{case} errored for a non-length reason: {error:?}")`. Uma asserção
adicional garante que uma assinatura fora do tamanho jamais seja aceita.

**Por quê / vantagem:** o contador passa a medir o que o nome e o relatório
dizem, e um erro de outra natureza deixa de se esconder atrás dele.

### 5. `utf8_width` mapeava byte de continuação para largura 4 (P3)

**Como era antes**

```rust
match byte {
    0x00..=0x7f => 1,
    0xc0..=0xdf => 2,
    0xe0..=0xef => 3,
    _ => 4,
}
```

O braço `_` engolia `0x80..=0xbf` (continuação), `0xc0..=0xc1` (lead overlong) e
`0xf5..=0xff`. Como `string()` usa o resultado para avançar o cursor e depois faz
`from_utf8(&bytes[start..*cursor]).unwrap()`, um byte solto de continuação
fatiaria no meio de um caractere e estouraria com um `Utf8Error` opaco — ou
passaria do fim da fatia perto do EOF.

**Como ficou**

```rust
0x00..=0x7f => 1,
0xc2..=0xdf => 2,
0xe0..=0xef => 3,
0xf0..=0xf4 => 4,
other => panic!("invalid UTF-8 lead byte {other:#04x}"),
```

**Por quê / vantagem:** endurecimento defensivo. Inalcançável com os quatro
arquivos fixados por digest, mas o parser é genérico e uma falha de diagnóstico
legível vale mais que um `unwrap` no meio da fatia.

### 6. Comentário do X25519 afirmava cobertura que não existe (P2)

**Como era antes**

```rust
/// `nomessages::crypto` exposes no X25519 entry point; the vectors run against the
/// `crypto_scalarmult_curve25519` implementation of the same bundled libsodium
/// build, which is what the MLS ciphersuite and Tor handshakes rely on.
```

Isso é falso. `grep -rn 'scalarmult\|curve25519' native/src/` não retorna
**nenhuma** ocorrência: nenhum código de produção chama o X25519 do libsodium. O
MLS usa `openmls_rust_crypto::OpenMlsRustCrypto` (`native/src/mls.rs:6`, suíte
`MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` em `mls.rs:13`), cujo DH vem do
`x25519-dalek`; o Arti usa `tor-llcrypto` (`native/Cargo.toml:26`), que é
`curve25519-dalek`.

**Como ficou:** o comentário passa a dizer exatamente isso, com o resultado do
`grep`, os dois provedores reais e a observação de que vetores equivalentes para
eles continuam faltando — com remissão a `docs/release-checklist.md`.

**Por quê / vantagem:** os 518 casos eram citados no checklist como evidência
parcial do gate 13 para caminhos que não os executam. Um defeito de ponto de
ordem baixa ou de canonicidade nas implementações realmente usadas permaneceria
invisível. A correção não remove o valor do corpus — ele continua fixando o
comportamento do libsodium —, apenas impede que ele seja lido como mais do que é.

### Não verificado

- `cargo fmt` e `cargo clippy` continuam indisponíveis no toolchain do WSL; a
  formatação das linhas novas seguiu o estilo do arquivo, sem conferência por
  ferramenta.
- A asserção `assert_eq!(mismatched, 0)` nunca foi vista falhando: não foi
  construída uma implementação defeituosa para provocá-la deliberadamente.
