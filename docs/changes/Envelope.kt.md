# core/src/main/kotlin/dev/mx3/nomessages/core/messaging/Envelope.kt

## 2026-09-17 — Campo `forwarded` em `Envelope.Text` e `Envelope.Attachment` (Parte B: Encaminhar)

### Motivo

Parte B do pedido do usuário: "Encaminhar mensagem" no estilo WhatsApp. O destinatário precisa saber
que a mensagem foi encaminhada (para a UI desenhar o rótulo "Encaminhada" na bolha), e essa
informação tem que viajar no envelope autenticado — não pode ser inferida localmente, senão o
destinatário nunca a veria.

### Como era antes

```kotlin
data class Text(
    override val id: String,
    override val timestamp: Long,
    val body: String,
) : Envelope { ... }

data class Attachment(
    override val id: String,
    override val timestamp: Long,
    val name: String,
    val mime: String,
    val fileId: ByteArray,
    val fileEpoch: Long,
    val fileKey: ByteArray,
    val ciphertext: ByteArray,
) : Envelope { ... }
```

### Como é agora

```kotlin
data class Text(
    override val id: String,
    override val timestamp: Long,
    val body: String,
    val forwarded: Boolean = false,
) : Envelope { ... }

data class Attachment(
    override val id: String,
    override val timestamp: Long,
    val name: String,
    val mime: String,
    val fileId: ByteArray,
    val fileEpoch: Long,
    val fileKey: ByteArray,
    val ciphertext: ByteArray,
    val forwarded: Boolean = false,
) : Envelope { ... }
```

Mais o KDoc explicando a semântica do campo.

### Decisões de projeto

- **Só `Text` e `Attachment`.** `Ack`, `Evidence`, `KeyPackage`, `GroupInvite`, `GroupCommit`,
  `KeyPackageRequest`, `GroupLeave` e `ControlRejected` ficaram exatamente como estavam: são
  envelopes de controle, não mensagens de conversa, e "encaminhar" não significa nada para eles. Isso
  também mantém o layout de wire deles byte a byte idêntico entre as versões 1 e 2 (ver
  `docs/changes/EnvelopeCodec.kt.md`).
- **Um bit, sem referência à origem.** Deliberadamente não existe `originalChatId`,
  `originalAuthor` nem contador "encaminhada N vezes". Qualquer um desses vazaria para o destinatário
  informação sobre as *outras* conversas do remetente — exatamente o tipo de metadado que o modelo de
  ameaça do app existe para eliminar. Encaminhar uma mensagem já encaminhada mantém o bit em `true`;
  ele nunca acumula.
- **Default `false` e último parâmetro.** Todo `Envelope.Text(...)`/`Envelope.Attachment(...)`
  posicional que já existia no repositório (incluindo o `decode` do codec e os testes) continua
  compilando sem alteração.

### Vantagens

- O menor acréscimo possível ao modelo de dados autenticado: um `Boolean`, um byte no wire.
- Nenhum tipo de envelope de controle foi tocado, então nenhuma superfície de parsing de controle
  mudou.
- `copy()` das data classes (usado em `MessagingEngine.history`, que zera `ciphertext`/`fileKey`
  antes de persistir) preserva o campo automaticamente — nada a sincronizar manualmente ali.

## 2026-09-17 — `Envelope.BundleRequest`/`BundleResponse` e os limites da busca de bundle (T4.16)

### Motivo

QR de pareamento formato 1 media ~2950 bytes — QR versão 40 a EC nível L, 177 módulos por lado —
que a câmera do aparelho de teste (Galaxy Note10+) não conseguia ler nem preenchendo a tela toda de
um monitor. 1569 desses bytes eram só a chave pública Kyber-1024 do bundle PQXDH obrigatório do
libsignal (`docs/development/protocol-report.md`, item 2).

Decisão: **tirar o bundle de dentro do QR** e buscá-lo pela onion do próprio par, autenticado por um
hash SHA-256 que já viaja assinado dentro do QR (formato 2). Isso exige um par de envelopes novo
para pedir e responder esse bundle — e esse par tem uma propriedade que nenhum outro envelope deste
arquivo tem.

### Como era antes

`Envelope` não tinha nenhum tipo relacionado a pareamento: o bundle inteiro (~1832 bytes, incluindo
a chave Kyber-1024) viajava dentro do próprio QR, como campo assinado.

### Como ficou

```kotlin
/**
 * Asks a device being paired with for the PQXDH key bundle behind one of its own pairing offers.
 *
 * ...
 * This is the **only** envelope pair that is exchanged before a Signal session exists, so - unlike
 * every other type here - it is not carried inside Signal ciphertext. It is carried by
 * `PacketKind.BUNDLE`, in the clear inside the Tor stream, which is exactly why it must never
 * carry anything secret: [nonce] is a value the requester read off a QR, and the answer is a
 * public key bundle whose integrity comes from the signed hash, not from the transport.
 * ...
 */
data class BundleRequest(
    override val id: String,
    override val timestamp: Long,
    val nonce: ByteArray,
) : Envelope {
    override val type: EnvelopeType = EnvelopeType.BUNDLE_REQUEST
}

/** The answer to a [BundleRequest]: see its documentation for why this is not secret. */
data class BundleResponse(
    override val id: String,
    override val timestamp: Long,
    val nonce: ByteArray,
    val bundle: ByteArray,
) : Envelope {
    override val type: EnvelopeType = EnvelopeType.BUNDLE_RESPONSE
}
```

Mais dois novos valores em `EnvelopeType` (`BUNDLE_REQUEST`, `BUNDLE_RESPONSE`) e, em
`EnvelopeLimits`:

```kotlin
/** Pairing offer nonce, matching `PairingEngine`'s 16-byte nonce. */
const val PAIRING_NONCE_BYTES: Int = 16

/**
 * Upper bound for a transported PQXDH bundle. The real encoding is ~1832 bytes (1569 of them
 * the Kyber-1024 public key); the headroom leaves the decoder a hard ceiling that does not have
 * to be revised for a key-size change, while staying four orders of magnitude below
 * [MAX_ENVELOPE_BYTES] so an unauthenticated pairing request can never allocate a large buffer.
 */
const val MAX_KEY_BUNDLE_BYTES: Int = 4 * 1024
```

### Por que este par é diferente de todo o resto do arquivo

Todo outro tipo em `Envelope` — `Text`, `Attachment`, `Ack`, `Evidence`, `KeyPackage`,
`GroupInvite`, `GroupCommit`, `KeyPackageRequest`, `GroupLeave`, `ControlRejected` — só existe depois
que uma sessão Signal já foi estabelecida entre as duas pontas: o envelope codificado é o
*conteúdo* que a sessão Signal cifra antes de ir para a rede (`PacketKind.SIGNAL`), e é essa
cifragem que dá sigilo e autenticação a cada byte deles.

`BundleRequest`/`BundleResponse` são o par que existe exatamente para tornar essa sessão Signal
possível — pedir e responder o bundle PQXDH que a sessão precisa para nascer. Por definição, nenhuma
sessão existe ainda quando eles trafegam: são carregados por `PacketKind.BUNDLE`
(`docs/changes/WirePacket.kt.md`), em texto claro dentro do fluxo Tor, sem cifragem Signal nenhuma
por cima. Essa é a razão do KDoc insistir que eles **nunca podem carregar nada secreto**: não é uma
recomendação de estilo, é a única garantia de sigilo que este par de tipos tem.

Na prática isso já é verdade para os dois campos:

- `nonce` é um valor que o requisitante **já leu de um QR escaneado** — não é segredo compartilhado,
  é só um seletor para "qual das minhas ofertas você está respondendo" (`PairingEngine.bundleFor`).
- `bundle` é a chave pública PQXDH do respondente. Chave pública, por definição, não precisa de
  sigilo — sua integridade vem inteiramente da comparação `SHA-256(bundle) == bundleHash` contra o
  hash que já estava dentro da parte assinada por Ed25519 do QR (`acceptPeerBundle`), não do
  transporte que o carregou.

### Por que os dois novos limites são o que são

- **`PAIRING_NONCE_BYTES = 16`.** Não é um novo número: é o mesmo tamanho de nonce que
  `PairingEngine` já usa e assina dentro do QR (campo 5 de `Offer`). O envelope só precisa repetir
  esse valor para o respondente localizar a oferta certa — daí ser uma constante compartilhada, não
  um limite inventado para o codec.
- **`MAX_KEY_BUNDLE_BYTES = 4096`.** O bundle real mede ~1832 bytes, dos quais 1569 são só a chave
  Kyber-1024. O limite fica bem acima disso — margem que absorve uma mudança futura de tamanho de
  chave sem precisar tocar o codec — mas ainda assim quatro ordens de grandeza abaixo de
  `MAX_ENVELOPE_BYTES`. Isso importa porque `BundleRequest` é o único envelope que
  `MessagingEngine.accept` processa **antes** de qualquer autenticação de sessão — um estranho não
  pareado pode mandar um (ver `docs/changes/WirePacket.kt.md` e
  `docs/development/pairing-bundle-over-tor.md`) — então o teto precisa impedir que esse estranho
  force o decodificador a alocar um buffer grande, sem depender de nenhuma outra camada para isso.

### Vantagens

- O QR de pareamento deixa de carregar o bundle inteiro: só o hash de 32 bytes. É essa mudança,
  combinada com o formato 2 de `Pairing.kt`, que tira o QR da versão 40 (177 módulos, ilegível) para
  as versões 13–14 medidas em `ProtocolTest` — ver `docs/changes/Envelope.kt.md`'s vizinhos em
  `docs/development/protocol-report.md`.
- O vínculo identidade ↔ onion ↔ bundle continua íntegro: o hash está dentro da mesma assinatura
  Ed25519 que já cobria `ed` e `onion`, então uma substituição do bundle em trânsito produz uma
  incompatibilidade de hash e o pareamento é abortado, nunca aceito silenciosamente.
- O par fica isolado dos oito tipos pré-existentes: nenhum corpo antigo mudou, e os dois tags novos
  (12/13) só existem a partir da wire versão 3 (`docs/changes/EnvelopeCodec.kt.md`).
