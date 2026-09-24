# native/tests/vectors/ed25519_test.json

## 2026-09-14 — T4.3: corpus Wycheproof Ed25519 (verificação)

### Como era antes

O arquivo não existia. O Ed25519 era coberto pelo vetor 1 do RFC 8032 e por um
teste de adulteração de assinatura em `crypto.rs` — nada de maleabilidade,
codificação inválida ou overflow.

### Como ficou

Cópia **byte a byte** do arquivo publicado, sem qualquer edição:

- Origem:
  `https://raw.githubusercontent.com/C2SP/wycheproof/master/testvectors_v1/ed25519_test.json`
- Baixado em: 2026-09-14
- SHA-256: `752d2ea7d7c6cf4736381b6cbacb61f8182b126ab7cd9b058f00c50084975536`
- Tamanho: 126.699 bytes; `numberOfTests`: 151 (88 `valid`, 63 `invalid`),
  78 grupos, cada um com sua chave pública de 32 bytes
- Schema: `eddsa_verify_schema_v1.json` — **só verificação**, sem chaves privadas
- Flags presentes: `InvalidEncoding` (22), `InvalidSignature` (20),
  `SignatureMalleability` (8), `TinkOverflow` (32), `CompressedSignature` (4),
  `SignatureWithGarbage` (5), `TruncatedSignature` (3), `Ktv` (38)

Consumido por `native/tests/kat.rs::wycheproof_ed25519_verify`, pela função
exportada `crypto::verify`. Doze casos `invalid` têm assinatura com tamanho
diferente de 64 bytes e são recusados pelo contrato de tamanho antes mesmo de
chegar ao libsodium; os demais são recusados pela verificação propriamente dita.

Nota de nomenclatura: a URL `.../testvectors_v1/eddsa_test.json` responde HTTP
404. Nessa geração do Wycheproof os casos de verificação EdDSA são publicados
como `ed25519_test.json`, que é o arquivo aqui embutido.

### Por que a mudança foi feita

Assinaturas Ed25519 autenticam o pareamento e as identidades. Aceitar uma
assinatura maleável ou com codificação não canônica permitiria duas assinaturas
distintas válidas para a mesma mensagem — exatamente o que os 8 casos
`SignatureMalleability` e os 22 `InvalidEncoding` detectam.

### Vantagens

- Cobre a direção de verificação com 63 recusas obrigatórias, incluindo os casos
  de overflow do Tink, que nenhum teste escrito à mão normalmente contempla.
- Passa pela API exportada, então valida também o contrato de erro do bridge
  (`Err(InvalidArgument)` para tamanhos fora do contrato).

### Não verificado

- O arquivo não traz chaves privadas: a direção de assinatura continua coberta
  apenas pelo vetor 1 do RFC 8032, em `crypto.rs`.
- `master` não é tag imutável; a fixação é pelo SHA-256 checado em tempo de
  teste.

---

## 2026-09-14 — Revisão: lista de flags completada

O arquivo JSON **não foi alterado** (o SHA-256 é o mesmo e continua fixado no
teste). Esta seção corrige a documentação.

**Como era antes:** a lista "Flags presentes" omitia duas flags — `Valid` (18) e
`InvalidKtv` (1) — embora fosse apresentada como enumeração completa.

**Como ficou** (contagem obtida por parse do arquivo, agora completa):
`Ktv` (38), `TinkOverflow` (32), `InvalidEncoding` (22), `InvalidSignature` (20),
`Valid` (18), `SignatureMalleability` (8), `SignatureWithGarbage` (5),
`CompressedSignature` (4), `TruncatedSignature` (3), `InvalidKtv` (1).

Os demais números da seção conferem: 151 casos (88 `valid`, 63 `invalid`), 78
grupos, **todos** com chave pública de 32 bytes, e exatamente 12 testes com
assinatura de tamanho diferente de 64 bytes.

**Por quê / vantagem:** além da exatidão, o fato de os 78 grupos terem chave de 32
bytes passou a importar: `native/tests/kat.rs` agora distingue a causa do
`InvalidArgument` antes de incrementar `rejected_by_length`, porque
`crypto::verify` também erra por chave pública fora de 32 bytes e por mensagem
acima de 64 MiB. Essa distinção só pôde ser feita com segurança porque o corpus
foi medido.
