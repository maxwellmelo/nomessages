# docs/development/native-api.md

## 2026-09-18 — T4.17 (campainha): seção `Doorbell` e opcodes 10-16

**Antes:** `native-api.md` descrevia apenas os opcodes Tor 0-9, terminando em *"Opcode 9 was appended after `stop` so every previously published opcode keeps its number"*, e uma única convenção de erro (`"Tor operation failed"`/`"Tor output unavailable"`).

**Agora:** entrou a subseção `### Doorbell (T4.17)` dentro de `TorNative`, no mesmo estilo de prosa do resto do arquivo: compartilhamento do host Arti com ciclos de vida independentes, porta virtual 4243 contra 4242, e as sete chamadas com suas pré-condições reais (idempotência de `doorbellStart` por seed — seed diferente lança em vez de rebind silencioso —, conjunto de tokens substituído por inteiro sem resetar cache de replay nem limite de taxa, `doorbellPoll` devolvendo **só** uma contagem, `doorbellMinimal` recusando rodar sem campainha servindo, a ordem obrigatória `start` → `doorbellStop` no unlock, e o orçamento de 240 s por batida). Seguem duas subseções novas: **o frame da batida** (57 bytes, o que é assinado, o byte de confirmação, e a lista completa de motivos de invalidez que fecham a conexão de forma indistinguível) e as **convenções de erro próprias** (strings fixas por faixa de opcode; panic derruba os dois serviços). A seção "Internal binary encoding" ganhou a tabela dos opcodes **10-16** com argumentos e retorno de cada um, e a nota de que o `:tor` os espelha como `what` 20-26.

**Por quê:** os opcodes são o contrato entre o Rust e o Kotlin; sem tabela, quem for tocar num dos lados tem de reler o `match` do `tor_jni.rs`. As duas coisas que mais importam para quem lê este arquivo — que 0-9 não foram renumerados e que o erro da campainha tem string própria, para nunca revelar por opcode qual serviço falhou — ficam ditas explicitamente em vez de inferidas.

## 2026-09-14 — T2.1: contrato de prontidão do transporte

### Como era antes

O documento descrevia três estados e afirmava explicitamente que a propagação do descritor era problema de quem chamasse:

> `onion(): String`; `status(): String` returns BOOTSTRAPPING/READY/STOPPED; ...

> Tor status READY means bootstrapped with onion service launched; descriptor propagation is asynchronous.

A tabela de opcodes ia de 0 a 8.

### Como ficou

- `status()` documentado como BOOTSTRAPPING/PUBLISHING/READY/STOPPED e `awaitReady(timeoutMillis)` incluído na lista de métodos.
- Seção nova **Readiness states**, que descreve a máquina de estados real (BOOTSTRAPPING → PUBLISHING → READY), cita a fonte da verdade no `tor-hsservice` 0.46 (`OnionService::status_events()`, `OnionServiceStatus::state().is_fully_reachable()`, `State::Running`/`DegradedReachable`), registra que a transição é reversível e que STOPPED é terminal para a geração.
- A mesma seção documenta o orçamento de 300 s separado dos 180 s de bootstrap, o retorno `"PUBLISHING"` como prazo esgotado e não como falha, o fatiamento recomendado (5 s no `:tor`) e a razão de `send` ser permitido em PUBLISHING enquanto os gates ao vivo só enviam depois de READY.
- A frase sobre READY foi corrigida: READY agora significa descritor publicado e serviço considerado plenamente alcançável.
- A tabela de opcodes do Tor ganhou `9 awaitReady(u32 timeout)`, com a nota de que ele foi acrescentado depois de `stop` para preservar a numeração existente, e `awaitReady` entrou na lista de respostas em ASCII cru.

### Vantagens

- O contrato de integração deixa de conter uma afirmação falsa (READY ≠ alcançável), que foi a causa de a sonda ao vivo enviar cedo demais.
- Quem for implementar T2.2/T2.3 encontra no mesmo lugar o orçamento, a semântica do retorno e a orientação de fatiar a espera.
- A citação nominal da API do `tor-hsservice` permite auditar a implementação contra a versão fixada sem ler o código Rust.

### Por que a mudança foi feita

T2.1 lista `native-api.md` como entrega: o novo opcode, o novo estado e a semântica de espera precisam estar no contrato antes de qualquer consumidor depender deles.
