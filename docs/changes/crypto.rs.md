# native/src/crypto.rs

## 2026-09-14 — T4.3: desvio do RFC 9106 §5.3 vira teste

### Como era antes

O módulo de testes documentava os limites do Argon2id apenas pelos valores de
fronteira do contrato próprio:

```rust
#[test]
fn argon2id_rejects_parameters_outside_the_contract() {
    let salt = [7; 16];
    assert_eq!(derive(b"password", &salt, 65_535, 2), Err(CryptoError::InvalidArgument));
    ...
}
```

Nada no código registrava por que o vetor oficial do RFC 9106 §5.3 — o único
vetor Argon2id publicado pelo RFC — não era executado. Quem lesse a suíte
poderia concluir que ele fora esquecido.

### Como ficou

Um teste novo, imediatamente antes daquele, que torna o desvio executável:

```rust
#[test]
fn argon2id_rejects_the_rfc9106_section_5_3_profile() {
    // RFC 9106 section 5.3 pins m=32 KiB, t=3, p=4, plus a secret key and
    // associated data. Libsodium fixes parallelism at 1 and exposes neither
    // secret nor associated data, and `derive` floors memory at 64 MiB, so
    // that official vector is unreachable by construction. The executable
    // substitute is the p=1 vector in the test above; the rejection below
    // keeps the deviation from silently disappearing.
    let salt = [2; 16];
    assert_eq!(derive(&[1; 32], &salt, 32, 3), Err(CryptoError::InvalidArgument));
}
```

Nenhuma linha de produção foi tocada: a mudança está inteiramente dentro de
`#[cfg(test)] mod tests`.

### Por que a mudança foi feita

O vetor do RFC 9106 §5.3 usa `p=4`, `m=32 KiB`, mais uma chave secreta e dados
associados. O libsodium fixa paralelismo em 1 e não expõe nem o segredo nem os
dados associados; além disso `derive` exige memória entre 64 MiB e 256 MiB. Logo
o digest oficial é **inalcançável por qualquer entrada que esta API aceite** —
não é um teste pulado por conveniência, é uma incompatibilidade estrutural.

O gate 13 exige registrar cada caso pulado com motivo. Registrar só no relatório
deixa a nota à mercê do próximo refactor; um teste que falha se `derive` um dia
passar a aceitar `m=32 KiB, t=3` mantém o registro vivo.

### Vantagens

- O motivo da ausência do vetor oficial fica ao lado do código, não apenas em
  `docs/development/crypto-report.md`.
- Se o piso de memória de 64 MiB for afrouxado por engano, o teste falha e força
  uma decisão consciente — o piso existe para custo de força bruta na frase
  secreta do vault.
- Reforça, de quebra, a validação de parâmetros: `m=32` (KiB) é recusado antes
  de qualquer trabalho de derivação.

### Não verificado

`cargo fmt`/`cargo clippy` não estão instalados no toolchain do WSL. O teste foi
executado: `cargo test --all-features --locked` terminou com 18 testes unitários
verdes, incluindo este.

---

## 2026-09-14 — Revisão: o teste do RFC 9106 §5.3 foi dobrado no teste de contrato

A revisão da entrega acima concluiu que o teste novo **não testava o que o nome
prometia** e duplicava cobertura já existente.

### Como era antes (o que a seção anterior deste arquivo entregou)

```rust
#[test]
fn argon2id_rejects_the_rfc9106_section_5_3_profile() {
    // ... comentário sobre p=4, chave secreta e dados associados ...
    let salt = [2; 16];
    assert_eq!(derive(&[1; 32], &salt, 32, 3), Err(CryptoError::InvalidArgument));
}
```

Dois problemas confirmados:

1. **O nome promete mais do que a asserção observa.** `derive` recusa no primeiro
   predicado que falha — `!(MIN_MEMORY_KIB..=MAX_MEMORY_KIB).contains(&32)` — e
   retorna antes de `iterations` ou de qualquer outra coisa importar. Os três
   elementos que de fato caracterizam o §5.3 (`p=4`, a chave secreta de 8 bytes e
   os 12 bytes de dados associados) **não são expressáveis** na assinatura de
   `derive`, logo a asserção não pode observá-los. Se o libsodium um dia expusesse
   `p`/segredo/AD, o teste continuaria passando inalterado enquanto o nome dele
   alegaria guardar o desvio.
2. **Duplicação.** O teste imediatamente seguinte,
   `argon2id_rejects_parameters_outside_the_contract`, já asseverava
   `derive(b"password", &salt, 65_535, 2) == Err(InvalidArgument)` — exatamente o
   mesmo ramo `MIN_MEMORY_KIB`. O `CLAUDE.md` do projeto pede explicitamente que
   não se crie cobertura duplicada.

### Como ficou

O teste separado foi removido e o caso virou a **primeira** asserção do teste de
contrato, com o comentário explicativo preservado e uma asserção nova sobre a
constante:

```rust
#[test]
fn argon2id_rejects_parameters_outside_the_contract() {
    let salt = [7; 16];
    // The first case is also the RFC 9106 section 5.3 memory parameter.
    // ... of those only memory and iterations are expressible through `derive`,
    // because libsodium fixes parallelism at 1 and exposes neither the secret
    // nor the associated data. Asserting the floor keeps the deviation
    // observable: nothing here proves the other three parameters are
    // unreachable (that stays a documented API fact in `crypto-report.md`), but
    // widening the contract down to 32 KiB would break this test ...
    assert!(MIN_MEMORY_KIB > 32, "RFC 9106 section 5.3 uses m=32 KiB");
    assert_eq!(derive(&[1; 32], &salt, 32, 3), Err(CryptoError::InvalidArgument));
    assert_eq!(derive(b"password", &salt, 65_535, 2), Err(CryptoError::InvalidArgument));
    ...
}
```

Nenhuma linha de produção foi tocada: a mudança continua inteiramente dentro de
`#[cfg(test)] mod tests`.

### Por que a mudança foi feita

Um teste cujo nome alega uma garantia que ele não verifica é pior do que nenhum
teste: ele desliga a atenção do revisor. O valor real da entrega anterior era o
**comentário** (o registro do motivo), não a asserção — e o comentário foi
mantido integralmente, agora acompanhado de uma delimitação honesta do que é e
do que não é verificável em código.

`assert!(MIN_MEMORY_KIB > 32)` é a única parte do §5.3 que dá para transformar em
guarda executável: se alguém baixar o piso de memória até o valor do RFC, o teste
quebra e força uma decisão consciente.

### Vantagens

- Deixa de existir um par de testes asseverando o mesmo ramo com nomes
  diferentes; a contagem de testes unitários caiu de 18 para 17 sem perder
  nenhuma asserção.
- O nome do teste passa a descrever exatamente o que ele faz.
- A parte inverificável do desvio (`p=4`, segredo, AD) fica declarada como fato
  de API documentado em `crypto-report.md`, não fingida como coberta por teste.

### Não verificado

- `cargo fmt`/`cargo clippy` continuam indisponíveis no WSL.
- Execução conferida: `cargo test --all-features --locked` terminou com 17 testes
  unitários, 7 KAT, 3 MLS, 0 falhas e 1 ignorado (`tor_live`).
