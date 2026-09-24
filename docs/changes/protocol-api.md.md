# docs/development/protocol-api.md

## 2026-09-14 — T4.5: apontar para o novo caminho do schema

### Como era antes

```markdown
- `createOffer(): String` creates `nomessages:1:` protobuf QR (120s TTL). Bundle
  excludes duplicated outer identity/prekey fields to fit QR40-L; UI MUST use
  error correction L for pairing QR (~2880 ASCII bytes).
```

O documento descrevia o QR como "protobuf" sem jamais dizer onde está o schema.
O arquivo existia em `core/src/main/proto/pairing.proto`, mas nenhuma página de
API o referenciava — e, pior, sua localização sugeria que as classes de
serialização eram geradas.

### Como ficou

```markdown
- `createOffer(): String` creates `nomessages:1:` protobuf QR (120s TTL). The field
  layout is documented in [`pairing.proto`](pairing.proto); that schema is
  reference material only and is not compiled — `Wire.kt` (`object Proto`)
  encodes and decodes the strict bounded subset by hand. Bundle excludes
  duplicated outer identity/prekey fields to fit QR40-L; UI MUST use error
  correction L for pairing QR (~2880 ASCII bytes).
```

O link é relativo (`pairing.proto`), resolvido dentro de `docs/development/`,
que é onde o schema passou a morar. Nenhum outro trecho do documento foi tocado.

### Por que a mudança foi feita

A tarefa T4.5 moveu o schema para `docs/development/pairing.proto`. Um caminho
novo sem nenhum ponteiro é um arquivo perdido: quem implementa ou audita o
pareamento entra por `protocol-api.md`, não varrendo o repositório atrás de
`*.proto`.

Além do caminho, a frase acrescenta a informação que faltava e que agora é
crítica: o schema **não é compilado**. Sem esse aviso, um leitor que encontrasse
o `.proto` poderia gerar classes com `protoc` e assumir compatibilidade com o
que o app aceita — o que seria falso, porque `Wire.kt` impõe restrições que o
protobuf padrão não impõe (varint canônico, sem campos duplicados, sem campos
desconhecidos, ordem ascendente, limite de 6144 bytes).

### Vantagens

- A documentação do protocolo passa a ser navegável: descrição da API → layout
  dos campos, em um link.
- Elimina a interpretação errada de que existe geração de código no caminho de
  pareamento.
- Nomeia a implementação real (`Wire.kt`, `object Proto`), que é onde qualquer
  mudança de formato precisa acontecer.

### Não verificado

Renderização do link relativo em visualizadores Markdown fora do GitHub não foi
testada; o caminho está correto no sistema de arquivos (`docs/development/`
contém tanto `protocol-api.md` quanto `pairing.proto`).
