# pairing.proto

## 2026-09-14 — T4.5: schema movido para documentação

### Como era antes

Caminho: `core/src/main/proto/pairing.proto` — diretório de fontes do plugin
`com.google.protobuf`, isto é, uma **entrada de compilação**. A cada build o
`protoc` gerava classes Java-lite no pacote `dev.mx3.nomessages.core.protocol.wire`
que ninguém importava.

Conteúdo original (início do arquivo):

```proto
syntax = "proto3";
package dev.mx3.nomessages.core.protocol.wire;
// All fields REQUIRED by application validation. Canonical ascending fields;
// duplicate/unknown fields rejected. Ed25519 signs deterministic CBOR, not protobuf.
message PairingOffer {
  uint64 proto_ver = 1;
  ...
}
```

### Como ficou

Caminho: `docs/development/pairing.proto`, movido com `git mv` (o histórico do
arquivo é preservado; o Git registra `R core/src/main/proto/pairing.proto ->
docs/development/pairing.proto`). O diretório `core/src/main/proto/`, que ficou
vazio, foi removido.

As definições das mensagens `PairingOffer` e `PairingConfirmation` estão
**intactas**, byte a byte. A única adição é um cabeçalho de comentário que
impede o próximo leitor de supor que o arquivo é compilado:

```proto
// Reference schema only. Not compiled: the protobuf Gradle plugin and the
// protobuf-javalite dependency were removed in T4.5 because no generated
// class was ever consumed. The wire bytes described here are produced and
// parsed by the hand-written strict subset in
// core/src/main/kotlin/dev/mx3/nomessages/core/protocol/Wire.kt (object Proto),
// which rejects duplicate, unknown and noncanonical fields. Keep this file
// and Wire.kt in sync by hand.
syntax = "proto3";
package dev.mx3.nomessages.core.protocol.wire;
...
```

### Por que a mudança foi feita

O schema continua sendo a melhor descrição legível do que viaja no QR
`nomessages:1:` — numeração dos campos, tipos, tamanhos esperados e o alerta de que
a assinatura Ed25519 cobre CBOR determinístico, não o protobuf. Jogar o arquivo
fora perderia essa documentação. Mantê-lo em `src/main/proto/`, porém, significa
mantê-lo como entrada de build — e foi exatamente isso que produziu código morto
por meses.

Mover para `docs/development/` resolve os dois lados: o valor documental fica,
o efeito colateral de compilação some.

### Vantagens

- O schema vira o que de fato é: contrato de formato, não fonte compilada.
- Fica ao lado de `protocol-api.md`, que agora o referencia por link relativo —
  quem lê a API do protocolo encontra o layout dos campos em um clique.
- O cabeçalho aponta explicitamente para `Wire.kt` (`object Proto`), a única
  implementação viva, e avisa que a sincronia entre os dois é manual. Sem esse
  aviso, alguém poderia editar o `.proto` achando que o código mudaria junto.
- `git mv` preserva o histórico e o `git log --follow` do arquivo.

### Verificação (2026-09-14)

Nada a compilar aqui (o arquivo deixou de ser entrada de build). A confirmação de
que a remoção do diretório `core/src/main/proto/` não afeta o build foi obtida no
WSL, com o JDK 21 e o cache Gradle do bootstrap da Fase 1:
`./gradlew --offline --no-daemon :core:test` executa `:core:compileKotlin` e
`:core:compileTestKotlin` com sucesso e termina em **BUILD SUCCESSFUL**
(`tests=64 failures=0 errors=0 skipped=2`). Nenhuma fonte referencia as classes
que o `protoc` gerava.
