# native/tests/tor_live.rs

## 2026-09-14 — T2.1: só enviar depois do Running

### Como era antes

Logo depois de `tor::start`, o teste já entrava no laço de `send`, com 180 s de tolerância:

```rust
eprintln!("Tor bootstrap completed; waiting for the synthetic onion service");
assert_eq!(onion, tor::address(&[91; 32]).unwrap());
let deadline = Instant::now() + Duration::from_secs(180);
let connection = loop {
    match tor::send(&onion, b"opaque authenticated ciphertext") { /* ... */ }
};
```

Como o descritor ainda não estava publicado, as primeiras tentativas falhavam por construção e o prazo de 180 s era consumido pela publicação, não pelo transporte. Foi exatamente esse o modo de falha registrado em `native-tor-diagnostics.log`.

### Como ficou

```rust
assert_eq!(tor::status(), "PUBLISHING", "Launch must not claim readiness");
let publishing = Instant::now();
let reached = tor::await_ready(300_000).unwrap();
assert_eq!(reached, "READY", "Onion descriptor was not published within 300s");
assert_eq!(tor::status(), "READY");
eprintln!("Descriptor published after {:.1}s; starting the synthetic self-connect", publishing.elapsed().as_secs_f64());
let first_frame = Instant::now();
```

Depois do laço de `send` o teste imprime o tempo até o primeiro frame. Ao final, além de `status() == "STOPPED"` e do `send` recusado, passou a afirmar que `await_ready(0)` também falha com o transporte parado.

### Vantagens

- O teste passa a separar as duas grandezas que T2.2 precisa medir: tempo de publicação do descritor e tempo até o primeiro frame. Ambos são impressos e podem ir direto para o `native-report.md`.
- Uma falha agora acusa o transporte, e não a lentidão da publicação.
- A asserção `PUBLISHING` logo após o `start` é uma regressão contra alguém voltar a marcar READY no `launch`.

### Por que a mudança foi feita

Critério "Feito quando" de T2.1: `tor_live.rs` reescrito para só tentar `send` depois do `Running`.

## 2026-09-15 — Revisão T2.1: asserções de estado transitório afrouxadas (P3)

### Como era antes

```rust
assert_eq!(tor::status(), "PUBLISHING", "Launch must not claim readiness");
...
let reached = tor::await_ready(300_000).unwrap();
assert_eq!(reached, "READY", "Onion descriptor was not published within 300s");
assert_eq!(tor::status(), "READY");
```

As duas asserções de `status()` afirmam igualdade sobre um estado que o próprio desenho declara
transitório e **reversível**: o observador de status roda em tarefa própria, então um diretório
rápido pode levar o estado a `READY` antes da primeira asserção, e `tor-hsservice` pode voltar a
`PUBLISHING` a qualquer momento depois da segunda. Ou seja: o gate T2.2 podia falhar numa rede
**boa** — a pior direção possível para um gate ao vivo falhar.

### Como ficou

```rust
assert!(
    matches!(tor::status(), "PUBLISHING" | "READY"),
    "Launch must reach publication, and must never report BOOTSTRAPPING or STOPPED"
);
...
let reached = tor::await_ready(300_000).unwrap();
assert_eq!(reached, "READY", "Onion descriptor was not published within 300s");
assert!(matches!(tor::status(), "PUBLISHING" | "READY"));
```

### Vantagens

- O gate real fica onde a informação é determinística: `await_ready` devolve o estado que
  **observou**, e `"PUBLISHING"` ali significa, sem ambiguidade, que o orçamento de 300 s estourou
  — que é a falha que T2.2 precisa pegar.
- As leituras de `status()` continuam sendo gates de verdade: um `BOOTSTRAPPING` ou `STOPPED` nesses
  dois pontos continua reprovando, que é o defeito que elas foram escritas para encontrar
  ("`start` não pode alegar prontidão que não tem").
- `assert_eq!(tor::status(), "STOPPED")` depois de `stop()` ficou como estava: esse estado é
  terminal, não transitório.

### Por que a mudança foi feita

Achado P3 da revisão (`native/tests/tor_live.rs:37`).
