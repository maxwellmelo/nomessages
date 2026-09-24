# SignalSessions.kt — mudanças

## 2026-09-17 — `SignalBundle` versão de wire 2 → 3: autocontido, para poder viajar sozinho por Tor (T4.16)

### Motivo

O QR de pareamento formato 2 (`docs/changes/Pairing.kt.md`) parou de carregar o bundle PQXDH — carrega
só `bundleHash = SHA-256(SignalBundle.encode())`. O bundle de verdade passa a ser buscado por Tor
depois do scan (`MessagingEngine.fetchPeerBundle`, `Envelope.BundleRequest`/`BundleResponse`). Isso só
funciona se o blob que trafega por Tor conseguir ser verificado e decodificado **sozinho**, sem nenhum
dado auxiliar vindo de fora dele — porque, nesse momento, o único material que o dispositivo que pediu
já tem é o hash assinado, não os campos `identity`/`pre` que antes vinham repetidos no próprio QR.

### Como era antes

```kotlin
internal data class SignalBundle(val registration:Int,val keyId:Int,val identity:ByteArray,val pre:ByteArray,val signed:ByteArray,val signature:ByteArray,val kyber:ByteArray,val kyberSignature:ByteArray) {
    fun encode()=pack { writeInt(2); writeInt(registration); writeInt(keyId); listOf(signed,signature,kyber,kyberSignature).forEach { blob(it) } }
    fun native()=PreKeyBundle(registration,1,keyId,ECPublicKey(pre),keyId,ECPublicKey(signed),signature,IdentityKey(identity),keyId,KEMPublicKey(kyber),kyberSignature)
    companion object {
        fun decode(bytes:ByteArray,identity:ByteArray,pre:ByteArray)=unpack(bytes,4096) {
            require(readInt()==2); val registration=readInt(); val id=readInt()
            require(registration in 1..16380 && id>0)
            val values=listOf(identity,pre)+(0..3).map { blob(2048) }
            require(values[0].size==33 && values[1].size==33 && values[2].size==33 && values[3].size==64 && values[4].size==1569 && values[5].size==64)
            val public=ECPublicKey(identity)
            require(public.verifySignature(values[2],values[3]) && public.verifySignature(values[4],values[5])) { "Invalid signed Signal bundle" }
            SignalBundle(registration,id,values[0],values[1],values[2],values[3],values[4],values[5]).also { it.native() }
        }
    }
}
```

`identity` e `pre` **não** eram codificados dentro do bundle: `encode()` só empacota `signed`,
`signature`, `kyber` e `kyberSignature`. `decode` recebia `identity`/`pre` como **parâmetros
separados**, porque quem chamava (`Pairing.kt`, formato 1) já os tinha em mãos — eram campos externos
do `Offer`, repetidos ali de propósito para servir de defesa contra substituição
(`require(signal.contentEquals(bundle.identity) && eph.contentEquals(bundle.pre))`).

### Como ficou

```kotlin
/**
 * A complete PQXDH prekey bundle.
 *
 * Wire version 3 (2026-09-17, T4.16) is **self-contained**: it carries `identity` and `pre` itself
 * instead of borrowing them from the enclosing pairing offer. Version 2 could omit them because the
 * offer QR repeated both as signed outer fields; the QR now carries only `SHA-256(encode())`, so the
 * blob has to stand on its own. What binds it to the scanned identity is that hash, which is inside
 * the Ed25519-signed part of the offer - see `Pairing.kt` and `docs/security-model.md`.
 *
 * The encoded size is ~1832 bytes, dominated by the 1569-byte Kyber-1024 public key. That is exactly
 * why it no longer travels inside the QR.
 */
internal data class SignalBundle(val registration:Int,val keyId:Int,val identity:ByteArray,val pre:ByteArray,val signed:ByteArray,val signature:ByteArray,val kyber:ByteArray,val kyberSignature:ByteArray) {
    fun encode()=pack { writeInt(VERSION); writeInt(registration); writeInt(keyId); listOf(identity,pre,signed,signature,kyber,kyberSignature).forEach { blob(it) } }
    fun native()=PreKeyBundle(registration,1,keyId,ECPublicKey(pre),keyId,ECPublicKey(signed),signature,IdentityKey(identity),keyId,KEMPublicKey(kyber),kyberSignature)
    companion object {
        const val VERSION=3
        /** Upper bound for a transported bundle; the real encoding is ~1832 bytes. */
        const val MAX_ENCODED_BYTES=4096
        fun decode(bytes:ByteArray)=unpack(bytes,MAX_ENCODED_BYTES) {
            require(readInt()==VERSION) { "Unsupported Signal bundle version" }
            val registration=readInt(); val id=readInt()
            require(registration in 1..16380 && id>0)
            val values=(0..5).map { blob(2048) }
            require(values[0].size==33 && values[1].size==33 && values[2].size==33 && values[3].size==64 && values[4].size==1569 && values[5].size==64)
            val public=ECPublicKey(values[0])
            require(public.verifySignature(values[2],values[3]) && public.verifySignature(values[4],values[5])) { "Invalid signed Signal bundle" }
            SignalBundle(registration,id,values[0],values[1],values[2],values[3],values[4],values[5]).also { it.native() }
        }
    }
}
```

Três mudanças, todas na mesma direção — tornar o blob autossuficiente:

1. **`encode()` agora empacota `identity` e `pre` também** — seis blobs em vez de quatro
   (`identity, pre, signed, signature, kyber, kyberSignature`).
2. **`decode(bytes)` perde os parâmetros `identity`/`pre`.** A assinatura antiga era
   `decode(bytes: ByteArray, identity: ByteArray, pre: ByteArray)`; a nova é só `decode(bytes:
   ByteArray)`. Quem chama do outro lado de Tor não tem — e não deveria precisar ter — nenhum dado
   auxiliar: recebe só os bytes do `BundleResponse` e decodifica o bundle inteiro a partir deles.
3. **`VERSION` sobe de 2 para 3** e passa a ser uma constante nomeada (`SignalBundle.VERSION`) em vez
   de um literal `2`/`3` solto em dois lugares (`encode`/`decode`) — mesmo padrão que
   `PairingEngine.OFFER_PREFIX`/`OFFER_FIELD_KINDS` adotou no mesmo commit. `decode` rejeita qualquer
   outra versão com uma mensagem nomeada (`"Unsupported Signal bundle version"`) em vez do
   `require(readInt()==2)` sem texto de antes.

`MAX_ENCODED_BYTES = 4096` também vira constante nomeada (`SignalBundle.MAX_ENCODED_BYTES`), no lugar
do literal `4096` que só existia como argumento posicional de `unpack`. É reaproveitada por
`EnvelopeLimits.MAX_KEY_BUNDLE_BYTES` (`core/.../messaging/Envelope.kt`) para o teto do payload de
`Envelope.BundleResponse` — as duas verificações compartilham o mesmo número com um único nome, em vez
de dois `4096` que poderiam divergir silenciosamente numa mudança futura.

### Por que exatamente esses dois campos tinham que sair do QR

O tamanho codificado é **~1832 bytes**, e a distribuição não é uniforme: o campo `kyber` sozinho —
a chave pública Kyber-1024 do PQXDH, 1569 bytes fixos — responde por mais de 85% desse total. É
justamente esse peso que fazia o QR formato 1 virar versão 40 (177 módulos por lado, ilegível em
câmera real). `identity` e `pre` são só 33 bytes cada; tirá-los do formato 1 pouco ajudaria sozinhos.
A decisão de T4.16 não foi "encolher o bundle" — o bundle continua do mesmo tamanho, PQXDH exige o
Kyber inteiro — foi **tirar o bundle inteiro do QR** e deixar só o hash de 32 bytes fixos, e para isso
o bundle precisou aprender a se validar e se reconstruir sozinho a partir dos bytes que chegam por
Tor, sem depender de campos que só existiam porque o QR os repetia.

### Vantagens

- `SignalBundle` deixa de depender de dados externos ao seu próprio encoding — pré-requisito direto
  para poder ser transportado por um canal (Tor) diferente do QR que carrega a oferta assinada.
- `decode` ganha uma assinatura mais simples (um parâmetro em vez de três) e uma mensagem de erro
  nomeada para versão incompatível.
- `VERSION`/`MAX_ENCODED_BYTES` nomeados eliminam dois literais mágicos duplicados
  (`Pairing.kt`/`Envelope.kt` compartilham `MAX_ENCODED_BYTES` via `EnvelopeLimits`).
- Nenhuma mudança na verificação criptográfica em si: as mesmas duas assinaturas (`signed`/`signature`
  e `kyber`/`kyberSignature`) continuam verificadas contra a mesma chave de identidade, agora lida do
  próprio blob em vez de receber de fora.

### Regressão

`:core:test` — 95 testes, 0 falhas, 0 erros, 2 pulados (`gradle-wsl.sh`, 2026-09-17). Ver também
`docs/changes/ProtocolTest.kt.md` para os testes de `core/.../protocol` que exercitam este arquivo
indiretamente através de `PairingEngine`.
