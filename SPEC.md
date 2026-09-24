# NoMessages — Especificação técnica completa

> Decisões do Maxwell (2026-09-13), travadas:
> 1. Ícone visível
> 2. Vault exportável só com a senha (sem amarrar ao aparelho)
> 3. Tor na v1
> 4. Grupos até 100
> 5. Senha de pânico na v1
> 6. Sem mailbox
> 7. **Só Android.** Sem iOS, sem Flutter, sem KMP de UI. Maxwell 2026-09-13.
>
> Nome visível: **NoMessages**. Aparência: clone da UI do WhatsApp.
> Não implementar até autorização de uma fase. Fonte de verdade deste arquivo.

**Package:** `dev.mx3.nomessages`  
**Plataforma:** Android 12+ (API 31), 64-bit only. Sem iPhone.  
**Idioma UI:** EN default (usado em qualquer idioma de sistema não suportado), PT-BR/PT-PT secundário  
**Licença:** **AGPL-3.0-or-later** (arquivo `LICENSE`). A sugestão original era GPLv3; a licença adotada acompanha a licença AGPL do `libsignal-client`, que é dependência direta do ratchet 1:1 e cuja AGPL é contagiosa para o código do app. Código auditável; segredo = senha. Componentes de terceiros mantêm suas licenças (`THIRD_PARTY_NOTICES.md`).

---

## 0. Tradução de “criptografia inquebrável como a da blockchain”

Blockchain **não cifra mensagens**. Bitcoin usa SHA-256 (prova de trabalho) e ECDSA secp256k1 (assinatura). O que as pessoas chamam de “inquebrável” é: **2^128–2^256 tentativas**, inviável com computadores atuais e projetados.

NoMessages usa o **mesmo piso computacional**, no modelo certo para mensagens:

| Camada | Primitive | Nível clássico | Analogia honesta |
|---|---|---|---|
| Disco (vault) | AES-256-GCM / XChaCha20-Poly1305 | 256-bit | Mais duro que SHA-256 truncado de endereço BTC |
| Senha → chave | Argon2id (memória-dura) | bruteforce por tentativa ~2–3 s | Não existe no Bitcoin; é *mais* hostil a GPU/ASIC |
| Identidade | Ed25519 + X25519 | ~128-bit ECC | Curva moderna; não secp256k1 |
| 1:1 | **PQXDH** (X25519 + Kyber-1024) + Double Ratchet | ~128-bit clássico + PQ | **Bitcoin não tem isto**: chave BTC vazou = passado comprometido. Ratchet vazou agora ≠ mensagens antigas |
| Grupo | MLS RFC 9420 | FS + PCS em árvore | Necessário em N=100 |
| Transcript | SHA-256 / BLAKE2b | 256-bit | Mesma família do BTC |
| PQ (já na v1) | X25519 + Kyber-1024 no handshake 1:1 | resistência a "colhe agora, decifra depois" | Bitcoin **não** tem |

**Promessa verdadeira:** sem a senha, o dump do telefone é indistinguível de ruído (AEAD). Quebrar AES-256 ou X25519 por força bruta está na mesma classe de “quebrar SHA-256 do Bitcoin” — não é “infinito”, é **computacionalmente inviável**.

**Promessa falsa, proibida no marketing e no README:**

- “ninguém nunca vai ler, mesmo com o app aberto”
- “invisível na forense” (o package existe)
- “igual blockchain” como se blockchain cifrasse chat
- “à prova de coação” — senha de pânico **mitiga**, não anula tortura

---

## 1. Produto

App Android de mensagens e arquivos **E2EE**, sem servidor de contas, sem número, sem username global.

- Contato só existe após **QR presencial** (ou o mesmo blob impresso + SAS).
- Grupo só existe se o grafo dos membros for um **clique**: todo par já pareado.
- Com o app **bloqueado**, quem tem o telefone **não lê** mensagens nem arquivos.
- Com o app **aberto**, a tela é o terminal. Isso não se resolve com crypto.

Nome da launcher: **NoMessages**. Ícone visível, identidade visual própria (grafite e âmbar), balões, lista de chats.

**Risco legal (uma linha):** trade dress / marca WhatsApp-Meta. Sideload/F-Droid primeiro; Play Store aumenta exposição. Não é bloqueio técnico.

---

## 2. Modelo de ameaça

### Defende

- Extração de `/data/data` com vault fechado
- Abertura do ícone sem a senha (tela de lock; zero preview)
- Força bruta offline do dump (Argon2id pesado)
- ISP / Wi‑Fi: Tor onion, sem IP do par no app
- Contato falso: QR + SAS falado
- Grupo com membro não pareado com todos: recusa
- Coação leve: senha de pânico abre vault decoy plausível e **não** denuncia o vault real

### Não defende

- App desbloqueado com a senha real
- Root/malware no OS, accessibility, teclado de nuvem
- Dump de RAM com vault aberto
- Coação se a senha real for entregue
- “App não existe no telefone” (PackageManager lista `NoMessages`)
- Os dois offline ao mesmo tempo (sem mailbox ⇒ mensagem fica no remetente até o onion do destino subir)

---

## 3. Stack

| Camada | Tecnologia | Por quê |
|---|---|---|
| Linguagem | Kotlin 2.x, coroutines | Android atual |
| UI | Jetpack Compose + desenho WhatsApp | clone fiel, sem XML legado |
| Min SDK | 31 | `FLAG_SECURE`, notification privacy, noBackup |
| Crypto nativa | libsodium (via JNI) + Google Tink | primitives auditadas; zero AES caseiro |
| Ratchet 1:1 | libsignal-client (Java/Android) | Double Ratchet de verdade, não clone |
| Grupo | OpenMLS (via JNI/uniffi) **ou** MLS Kotlin port | RFC 9420; N=100 |
| KDF | Argon2id (libsodium `crypto_pwhash`) | memória-dura |
| DB | SQLCipher 4 Community, AES-256 | DB inteira cifrada |
| Arquivos | chunks 64 KiB XChaCha20-Poly1305 | AAD = `file_id ‖ idx ‖ vault_epoch` |
| QR | CameraX + ZXing/ML Kit on-device | sem cloud vision |
| Tor | **Arti** (Rust, JNI) onion v3 | Tor moderno; sem mailbox |
| Transporte overlay | length-prefixed frames sobre Tor stream | só ciphertext |
| QR/payload | protobuf3 | schema versionado |
| Serialização extra | canonical CBOR para signatures | sem JSON canônico ambíguo |
| Testes crypto | JUnit + vetores RFC + wycheproof | |
| Testes ameaça | scripts `adb pull` / `strings` / SQLCipher wrong-key | gate de release |

**Proibido:** AES-ECB, cifrar com a senha crua, chave em SharedPreferences plaintext, Android Keystore como **único** segredo (abre com PIN do telefone), Firebase, Crashlytics com conteúdo, `allowBackup=true`, `MediaStore` para mídia do chat, WebView de conversa.

---

## 4. Vault (disco = massa cifrada)

Android não executa APK 100% cifrado. Desenho honesto:

```
/data/data/dev.mx3.nomessages/
  stub/                 # lock screen + Argon2 + opener (código visível)
  files/vault/
    header.bin          # magic NMSG, ver, kdf params, salts, wrapped keys
    real.db             # SQLCipher — vault A
    decoy.db            # SQLCipher — vault B (pânico)
    real.files/         # mídia A
    decoy.files/        # mídia B
  cache/                # zero conteúdo; wipe no lock
```

O módulo de UI de conversas pode viver no APK (ícone visível já admite que o app existe). **Dados** é que são o blob. Não há DEX cifrado obrigatório na v1 (complexidade vs ganho baixo com ícone visível).

### 4.1 Header

```
magic     = "NMSG" 
version   = u16
kdf       = Argon2id, m_kib, t, p, salt_real[16], salt_decoy[16]
wrap_real = nonce || ciphertext(DBK_r || FBK_r || IDK_r)
wrap_decoy= nonce || ciphertext(DBK_d || FBK_d || IDK_d)
mac       = keyed BLAKE2b sobre o header sem o mac
```

Dois wraps independentes. **Mesmo tamanho, mesmo tempo de Argon2.** Verificador não diz qual senha é qual: tenta unwrap real, tenta unwrap decoy; um abre.

### 4.2 KDF

```
MK = Argon2id(
  password = UTF-8 NFKC,
  salt     = salt_do_alvo,
  m        = max(65536, calibrado no 1º setup para ~2500 ms em 1 thread),
  t        = calibrado,
  p        = 1,
  out      = 32
)
```

Piso: m ≥ 64 MiB. Recalibrar só para cima, nunca para baixo, e só com vault aberto.

**Sem StrongBox obrigatório** (decisão 2). Exportar = copiar `vault/` + senha. Abrir em outro telefone = mesma senha.

Opcional não-bloqueante: cache de unlock biométrico **embrulhando MK já derivada**, TTL 30 s, default **off**. Biometria nunca substitui a senha.

### 4.3 Lock

Dispara: botão, `ON_STOP` > 30 s, `ACTION_SCREEN_OFF`, falha de Argon2, panic.

Ações atômicas:

1. `db.close()`
2. `zeroize(MK, DBK, FBK, ratchet_mem, tor_keys_em_ram)`
3. Unmap arquivos; wipe page cache na medida do possível
4. Derrubar **todos** os circuitos Tor / sockets
5. Voltar à lock activity; `FLAG_SECURE`

App bloqueado ⇒ **zero bytes** de plaintext em disco e **zero sockets**.

**Exceção documentada e deliberada (campainha, T4.17):** quando o cofre que está sendo bloqueado
tem o *Aviso de mensagens pendentes* ligado (opção por cofre, **padrão ligado**,
`opaque_blobs.vault_settings/doorbell_enabled`), o passo 4 acima deixa de derrubar **tudo**: o
serviço onion de **mensagens** é destruído exatamente como antes — e os passos 1 a 3 continuam
idênticos, com `zeroize` de MK/DBK/FBK e das chaves de sessão —, mas o socket do **segundo** onion
service, o da campainha, permanece no ar no processo `:tor`, agora promovido a foreground service.
Nesse estado o processo guarda apenas duas coisas: a chave de identidade daquele onion (derivada de
`meta.doorbell_seed`) e o conjunto de tokens que este aparelho emitiu aos seus contatos. Nenhum
banco aberto, nenhuma chave de sessão, nenhuma chave de banco, nenhum plaintext em disco. Com a
opção desligada o comportamento é o antigo, sem exceção alguma: o processo `:tor` termina. O
desenho está em `docs/development/doorbell-design.md` e a análise de ameaça completa dos dois modos
em `docs/security-model.md`; o gate 10 da seção 12 exige evidência das **duas** variantes.

### 4.4 Export / import

- Export: ZIP ou diretório `nomessages-vault-<epoch>.bin` = header + dbs + files, já cifrados. Não re-cifra (são o blob).
- Import: cola no app virgem, pede senha, abre. Sem conta, sem QR extra.
- Sem nuvem. Sem Google Drive. Sem backup automático (`allowBackup=false`, `dataExtractionRules` deny-all).

---

## 5. Senhas

### 5.1 Senha real

- Mínimo **12** caracteres, máximo 128, contados **após normalização NFKC**
- Qualquer ASCII imprimível (`0x20`–`0x7E`) **e** qualquer letra Unicode. Símbolos são aceitos;
  espaços internos também (frases-senha). Proibidos: caracteres de controle e espaço no início/fim
- `estimateStrength(...).score ≥ 3` (≈ 60 bits estimados). Recusa dicionário PT/EN embutido,
  teclado sequencial, runs repetidos, ano/data, `p4ssw0rd`, `Palavra1!`
- Mostrar custo Argon2 (~2–3 s) na criação
- Confirmada duas vezes, com medidor de força e validação da confirmação em tempo real

> **Por que era alfanumérico e por que relaxou (T4.10).** Racional *inferido* do código e deste
> documento — não existe registro histórico da decisão. A senha normalizada alimenta o Argon2id
> diretamente e a §4.2 promete "abrir em outro telefone = mesma senha"; a normalização só é
> byte-estável entre teclados/IMEs para um conjunto restrito de caracteres, e muitos símbolos
> (sobretudo não-ASCII) têm formas de compatibilidade dependentes do método de entrada. Com **NFC**,
> a "mesma" senha digitada em outro aparelho podia derivar outra chave. Trocando para **NFKC**, as
> formas de compatibilidade (dígitos de largura total, ligaduras, espaços não-quebráveis, variantes
> de hífen) são dobradas numa única forma canônica, então aceitar símbolos deixa de ameaçar a
> portabilidade. O mínimo de 16 alfanuméricos era o atalho para garantir entropia alta sem estimador
> por senha; `estimateStrength` agora precifica cada senha, o que permite baixar o piso para 12 sem
> aceitar `Senha123456!`. NFC e NFKC são idênticos em ASCII puro: nenhum cofre existente muda de chave.

### 5.2 Senha de pânico (v1, obrigatória)

Criada **no setup**, distinta da real. O app recusa igualdade (comparação em tempo constante após
NFKC) **e** variação trivial: uma senha contida na outra, ou distância de edição (Levenshtein) ≤ 2.
Quem arranca a senha real sob coação não pode deduzir a de pânico trocando um caractere.

Efeitos ao abrir com a de pânico:

| Item | Comportamento |
|---|---|
| Vault real | **Não monta**. Wraps reais nem são distinguíveis por timing |
| Vault decoy | Abre com chats fake gerados no setup (conversas banais, datas espalhadas) |
| Rede | Sobe Tor e o onion **do decoy** (identidade separada). Contatos reais não existem neste grafo |
| UI | Idêntica. NoMessages “normal” |
| Panic-wipe opcional | Flag no setup, **default off**: 3 erros da senha real **não** apagam. Wipe só por ação explícita no vault real. Coação + wipe automático é perigoso e fica desligado |

Regras de implementação (senão vaza):

- Argon2 das duas senhas **sempre** roda até o fim; depois tenta os dois unwraps
- Tamanho de `real.db` e `decoy.db` alinhado (padding) para dump não denunciar — **garantido no setup**; o crescimento de mídia em uso ainda não tem política, ver **9.2 (em definição)**
- Decoy precisa de **volume plausível** (N chats, M msgs, 1–2 mídias pequenas) gerado no setup
- Nunca logar “wrong vault” / “decoy”
- Notificações do decoy: mesmo padrão vazio
- Quem sabe as duas senhas pode abrir os dois; o app não oferece “trocar para o real” sem lock + senha real

Isto **não** é prova contra tortura prolongada. É deniability de disco + UI.

---

## 6. Identidade e pareamento QR

Sem conta. No primeiro unlock do vault **real** (e outro jogo no decoy):

```
id_ed25519   # assinatura
id_x25519    # ECDH estático
device_rnd   # 32 B, local
onion_v3     # derivado de chave Tor dedicada (não reusar id_*)
```

### QR formato 2 (uso único, TTL de leitura 120 s) — 2026-09-17, T4.16

```
nomessages:2:<base64url(PairingOffer)>

PairingOffer {                 # 294 bytes (oferta) / 326 bytes (resposta)
  proto_ver: 2                 #   1 B
  id_ed25519_pub               #  32 B
  onion_v3                     #  62 B  — onion de mensagens, "<56 chars>.onion", US-ASCII
  created_unix                 #   5 B  — varint
  nonce_16                     #  16 B  — também é o seletor do BundleRequest
  bundle_hash                  #  32 B  — SHA-256(SignalBundle.encode())
  doorbell_key                 #  32 B  — reservado para a campainha (T4.17)
  doorbell_token               #  32 B  — reservado para a campainha (T4.17)
  reply                        # 0/32 B — digest da oferta respondida (só na resposta)
  sig_ed25519                  #  64 B  — sobre os campos acima
}
```

**O bundle PQXDH saiu do QR.** O formato 1 carregava o `SignalBundle` inteiro (≈1832 bytes, dos
quais 1569 são a chave pública Kyber-1024 obrigatória) e o payload chegava a ~2950 bytes: **QR
versão 40 no nível L, 177 módulos por lado**. Medido no aparelho real do usuário (Galaxy Note10+):
a câmera **não conseguia ler**, nem com o QR ocupando um monitor inteiro. O formato 2 carrega
apenas `SHA-256(bundle)` e mede, com ZXing no nível L:

| payload | bytes (base64url + prefixo) | versão do QR no nível L |
|---|---|---|
| oferta | 405 | **13** |
| resposta | 448 | **14** |
| confirmação (`nomessages-confirm:2:`) | 317 | **11** |

O orçamento travado é **versão 14** (458 bytes no nível L): a resposta tem **10 bytes de folga**.
Qualquer campo novo na oferta exige refazer a conta, não afrouxar a asserção
(`ProtocolTest.formatTwoQrPayloadsStayWithinTheVersionFourteenBudgetAtLevelL`).

Separadores de domínio CBOR: `nomessages-offer-v1` → **`nomessages-offer-v2`** e
`nomessages-transcript-v1` → **`nomessages-transcript-v2`**. As ofertas codificadas que alimentam o
transcript mudaram de forma; reusar o separador entre duas codificações incompatíveis é exatamente a
ambiguidade que um separador existe para impedir. Pré-lançamento, sem migração. **QRs do formato 1
são recusados** duas vezes: o prefixo não casa, e o campo de versão do corpo é verificado.

#### Campos da campainha (T4.17) — comportamento implementado

`doorbell_key` e `doorbell_token` entraram no formato 2 antes de existir a campainha, para **não
haver uma segunda migração de formato de QR** quando ela fosse construída
(`docs/development/doorbell-design.md`). **Desde T4.17 os dois têm comportamento**: sobe um segundo
serviço onion, existe notificação e existe uma opção nas Configurações. O **formato do QR não
mudou** — os campos 7 e 8 continuam com 32 bytes cada, nas mesmas posições e dentro da mesma
assinatura —, então o orçamento de bytes medido em T4.16 continua valendo sem alteração: oferta
405 B / versão 13, resposta 448 B / versão 14, confirmação 317 B / versão 11, todos no nível L.
T4.17 só passou a **persistir e usar** o que o protocolo já carregava; nenhum byte novo entrou na QR.

- `doorbell_key` — a **chave de identidade Ed25519** de um segundo onion v3, derivada de uma seed
  própria (`meta.doorbell_seed`, 32 bytes aleatórios, criada na primeira utilização: na criação do
  cofre ou no primeiro unlock de um cofre anterior a T4.16). A seed é **distinta de `onion_seed`** de
  propósito: uma única seed para os dois endereços permitiria a quem conhece um deles confirmar que
  pertencem ao mesmo aparelho. O endereço textual é reconstruído por `OnionAddress.address()`.
  **Divergência registrada:** o campo carrega a chave de 32 bytes e não os 62 caracteres do endereço.
  Um endereço v3 é `base32(pubkey32 || checksum2 || versão1) + ".onion"` — 62 caracteres que
  codificam 35 bytes, dos quais só os 32 da chave não são deriváveis. Com o texto do endereço a
  resposta media **versão 15**, acima do orçamento; com a chave, mede 14. Nada se perde.
- `doorbell_token` — 32 bytes aleatórios **novos a cada oferta**, como o nonce. Só o par daquela
  troca aprende o token, então dois contatos nunca podem apresentar o token um do outro. É o segredo
  que o par apresenta ao bater na **minha** campainha, e o que eu apresento ao bater na dele.

Os dois estão dentro dos bytes canônicos assinados e, por consequência, dentro do transcript do SAS.
Não há caminho de hash ou assinatura separado para eles.

São **três** os campos guardados por contato, e eles não são intercambiáveis:

| Campo em `contacts` | De quem é | Para que serve |
|---|---|---|
| `doorbell_onion` | do par (derivado do `doorbell_key` dele, por `OnionAddress.address()`) | **onde** bater |
| `doorbell_token` | do par (o `doorbell_token` da oferta dele) | **o que apresentar** ao bater |
| `doorbell_token_issued` | **meu**, cunhado na minha própria oferta | o que **aceito** quando ele bate aqui |

`doorbell_token_issued` entrou no esquema **v4** (ver seção 10): até T4.16 o token local cunhado em
`PairingEngine.newOffer()` era destruído junto com a oferta e se perdia, de modo que este aparelho
sabia bater mas não sabia reconhecer quem batia. `PairingEngine.finish()` passou a devolvê-lo em
`PairedContact.doorbellTokenIssued`. **Limitação assumida:** contatos pareados **antes** de T4.17
ficam com `doorbell_token_issued` vazio e não conseguem tocar a campainha deste aparelho — não há
correção retroativa possível, porque o valor só existia dentro daquela oferta; só um novo pareamento
resolve. O cofre-isca preenche as três colunas com valores plausíveis (`DecoyFactory`), pelo mesmo
motivo de sempre: coluna vazia em todo contato sintético seria um distinguidor por simples inspeção.

### Fluxo (os dois no mesmo lugar)

1. A mostra QR; B scaneia
2. **B conecta no onion de A** e pede o bundle: `BundleRequest(nonce_de_A)` → `BundleResponse`
3. B mostra QR de resposta **ou** SAS
4. A scaneia a resposta e **conecta no onion de B**: `BundleRequest(nonce_de_B)` → `BundleResponse`
5. Cada lado confere `SHA-256(bundle) == bundle_hash` da oferta assinada do outro. Qualquer falha
   **cancela** o pareamento com erro específico; não existe meio-termo
6. SAS: 6 dígitos decimais = `SHA-256(transcript)[0:3]` modulo apresentação tipo RFC 8135; lido em
   voz alta; mismatch aborta e queima as efêmeras
7. **PQXDH** (identidades + efêmeras + prekey Kyber-1024) → root key → Double Ratchet (libsignal)
8. Cada um grava `Contact { pubkeys, onion, ratchet, alias_local, paired_at, doorbell_* }`
9. Alias é **local**, nunca trafega

Direcionalidade: **quem acabou de ler um QR busca de quem mostrou o QR.** O onion do outro já veio
no QR, então nenhuma descoberta extra é necessária. Os dois lados precisam do Tor em `READY`; se
ainda não estiver, a UI espera até o prazo com retorno visível em vez de falhar na hora.

### Dois prazos, não um

| prazo | valor | contado de | o que limita |
|---|---|---|---|
| `OFFER_TTL_SECONDS` | **120 s** | criação da oferta | por quanto tempo um **convite** (QR de oferta, `reply` vazio) ainda pode ser lido — é o QR que se regenera sozinho |
| `PENDING_TTL_SECONDS` | **300 s** | criação de **cada** oferta | **aquisição**: por quanto tempo cada uma das até 3 ofertas emitidas continua respondível — casar um **QR de resposta** e responder `BundleRequest` |
| `CONFIRMATION_TTL_SECONDS` | **240 s** | o **staging** da troca | **confirmação**: busca do bundle pela rede Tor, SAS falado, alias e os dois QRs de confirmação |

**Duas fases, dois relógios — e o "300 s cobre tudo" da versão anterior deste SPEC não vale mais.**
O tempo total de relógio de parede, da primeira oferta até um pareamento concluído, pode passar de
300 s: até ~300 s de aquisição **mais** 240 s de confirmação. A mudança é intencional e veio de
medição ao vivo (T4.16 run6): com duas rotações de QR gastas na aquisição, o prazo antigo, ancorado
na criação da oferta, deixava **4 a 35 s** para a fase humana inteira, e as duas tentativas foram
aceitas pelo protocolo e ainda assim estouraram o tempo. Os 240 s saem da soma do que de fato
acontece depois do staging — a busca do bundle é disparada **depois** dele, admitindo uma tentativa
falha (~103 s), mais SAS falado (~30 s), alias (~20 s) e dois QRs de confirmação (~60 s) ≈ 215 s,
com ~10 % de folga.

Cada aparelho conta a confirmação a partir do **próprio** staging, então os dois prazos diferem pelo
atraso do relé entre eles. Presencialmente isso são segundos e a janela compartilhada é praticamente
o orçamento inteiro.

O QR de resposta é governado pelo prazo de 300 s, **não** pelo de 120 s: ele não se regenera, a tela
de quem o mostra o mantém no ar pela janela inteira e exibe esse relógio. Aceitá-lo só por 120 s fazia
o motor contradizer a contagem que o usuário estava olhando, e foi um defeito real observado na
validação ao vivo (run5; ver `docs/changes/Pairing.kt.md`). Pelo mesmo motivo, uma resposta é casada
contra a oferta na tela **e** contra a única oferta substituída retida: a regeneração de 120 s pode
trocar qual delas é a "atual" entre a leitura do par e a volta da resposta dele.

O prazo da troca é ancorado na **mais antiga** das duas ofertas, então não reinicia a cada passo.
Ele é maior que o do QR porque o formato 2 acrescentou uma ida e volta pela rede Tor até um onion
recém-publicado, o que custa tipicamente 5–40 s — tempo que o formato 1 nunca precisou gastar.

Na tela "mostrar meu QR" há contagem regressiva visível. Quando o QR expira sem ninguém ter
escaneado, **outro é gerado automaticamente** (nonce novo, bundle novo) com a tela aberta, e o
anterior é descartado. Uma troca já montada que expira é **cancelada**, nunca regenerada: o outro
lado já tem um transcript sobre aquele par exato de ofertas, e uma oferta nova unilateral deixaria
os dois com SAS diferentes. Sair da tela cancela tudo. A regra completa é uma máquina de estados
pura em `ui/PairingLifecycle.kt`, coberta por teste JVM.

**Exibição e validade são coisas separadas.** O QR na tela gira a cada 120 s, mas **toda** oferta já
emitida continua apta a receber resposta e a responder `BundleRequest` pelos 300 s **dela**, contados
do `created` dela e independentemente de ainda estar ou não na tela. Como uma oferta vive 300 s e a
tela gira a cada 120 s, `ceil(300 / 120) = 3` ofertas ficam simultaneamente válidas no pico — é daí
que sai `MAX_LIVE_OFFERS = 3`, um teto derivado, que em regime normal nunca corta nada e existe só
para o caso patológico. Uma entrada sai **no cronograma dela**, nunca por ser empurrada por uma mais
nova. Ao montar uma troca, o material de chave de todas as outras é descartado na hora
(`discardBundle`): este aparelho só comporta uma troca, então as demais não levam a lugar nenhum e
responder por elas violaria a regra de limite de taxa acima.

Na tela, o contador lê **"Novo QR em MM:SS"** enquanto só o QR próprio está no ar — zerar gira o QR e
não custa nada a ninguém — e passa a **"Expira em MM:SS"** quando a troca é montada, aí sim um prazo
de verdade. Uma legenda fixa explica que quem já escaneou o QR anterior ainda conclui o pareamento.

### Busca do bundle pela rede Tor

Dois envelopes novos, versão 3 do `EnvelopeCodec`: `BundleRequest(nonce)` e
`BundleResponse(nonce, bundle)`. São o **único** par de envelopes trocado antes de existir sessão
Signal e, por isso, o único que **não** viaja dentro de ciphertext Signal: vão em `PacketKind.BUNDLE`,
em claro dentro do stream Tor. Isso é seguro porque nada ali é secreto — o bundle é material de
chave pública e sua integridade vem do hash assinado, não do transporte (ver `docs/security-model.md`).

Limite de taxa: um aparelho responde **apenas** a um nonce que ele mesmo cunhou, apenas enquanto
aquela oferta está viva (janela de 300 s dela), e **exatamente uma vez**. Nonce desconhecido, vencido
ou já consumido recebe **silêncio**, não erro — um erro confirmaria a quem só testa nonces que este
onion está com uma tela de pareamento aberta. Bundles de ofertas que não estão vivas nunca são
revelados.

### PQXDH no lugar do X3DH clássico (divergência registrada)

O SPEC original pedia X3DH e deixava o híbrido pós-quântico para a v1.1. O construído usa **PQXDH**, e isto não é opcional: o `libsignal-client` 0.102.2 — a biblioteca escolhida para o Double Ratchet — só aceita `PreKeyBundle` híbrido, com prekey KEM assinada pela identidade. Reimplementar um X3DH clássico próprio significaria abandonar o ratchet oficial, o que a decisão "Double Ratchet de verdade, não clone" proíbe.

Implementação real (`core/.../protocol/SignalSessions.kt`):

- KEM: `KEMKeyType.KYBER_1024` (não Kyber-768, como dizia a linha de v1.1 da seção 0)
- Cada QR de pareamento gera prekey one-time, signed prekey e prekey Kyber **novas**; a prekey Kyber é assinada pela identidade Ed25519 do bundle Signal
- Não há last-resort key nem cache de reuso: `markKyberPreKeyUsed` descarta a chave usada
- O bundle serializado tem **versão 3** (era 2) e agora é **autocontido**: carrega `identity` e
  `pre` além de `signed`, `signature`, `kyber`, `kyberSignature`. A versão 2 podia omitir os dois
  primeiros porque o QR os repetia como campos externos assinados; o QR do formato 2 carrega só o
  hash, então o blob precisa se sustentar sozinho. Ele não viaja mais no QR — viaja pela rede Tor

Efeito no modelo de ameaça: o handshake fica resistente a "colher agora, decifrar depois". O restante (Double Ratchet, transcript SHA-256, SAS falado) não muda. A autenticação continua sendo presencial; PQXDH não substitui o SAS.

Sem QR não há descoberta de pessoas. A única busca que existe é a do **bundle de chaves**, e ela só
acontece contra um onion que o QR já nomeou, com um nonce que o QR já entregou. Sem link permanente
(link permanente = ID global). Papel: imprimir o mesmo protobuf uma vez + SAS por canal já confiável — fase 1.1, não bloqueia v1 presencial.

Replay de QR expirado: rejeitar. QR de terceiro sem SAS: rejeitar.

---

## 7. Transporte — Tor v1, sem mailbox

```
[UI] → [Ratchet/MLS] → [frame: len|nonce|ad|ciphertext] → [Arti onion stream]
```

- Cada vault (real e decoy) tem **onion v3 próprio** — e, desde T4.17, um **segundo** onion v3 só
  para a campainha, em porta virtual própria (**4243**, contra 4242 das mensagens), derivado de uma
  seed distinta para que os dois endereços sejam desvinculáveis
- App bloqueado: Arti desliga, chaves Tor saem da RAM — **exceto** com a campainha ligada, quando o
  onion de mensagens morre e só o da campainha sobrevive, com a chave dele e os tokens emitidos
  (ver 4.3, seção 11 e `docs/security-model.md`)
- Conectar é função da senha (decisão original)
- Sem bridge obrigatória no v1; permitir bridges manuais (censura)
- Sem push FCM com conteúdo. Notificação local de mensagem: título “NoMessages”, **body vazio**. Duas exceções, ambas de texto **constante**, nunca derivado de mensagem, remetente ou contagem: o aviso da campainha (título “NoMessages”, corpo fixo “Você tem mensagens aguardando. Abra o NoMessages.”, id 2, canal `private_messages`) e a notificação persistente de foreground do processo `:tor` em modo mínimo (título “NoMessages”, **sem corpo**, id 3, canal `background_service`, `IMPORTANCE_LOW`, silenciosa). As três são `VISIBILITY_SECRET` e `setLocalOnly`
- Padding de frame: buckets 256 / 1024 / 4096 / 16384
- Se o onion do destino está down: mensagem fica **no outbox cifrado do remetente**. Sem mailbox de terceiro. Os dois precisam coincidir online, ou o remetente deixa o app desbloqueado até entregar

LAN/Wi‑Fi Direct: **não** no v1 (pedido foi Tor). Pode voltar como transport extra depois, nunca bypassando o ratchet.

### 7.1 Enquadramento construído

- `FrameCodec` (`core/.../protocol/Frames.kt`): buckets exatos de **256 / 1.024 / 4.096 / 16.384** bytes, com prefixo de comprimento de 4 bytes e preenchimento aleatório. O lado Rust espelha o limite: `MAX_FRAME = 4 + 16 * 1024` (16.388 bytes) em `native/src/wire.rs` — esse é o tamanho do **frame no fio** (bucket de 16.384 + prefixo de 4 bytes), não a capacidade de conteúdo.
- Dentro de cada bucket vão **16 bytes de cabeçalho de fragmento** (`HEADER = 16` em `Frames.kt:11`): comprimento total do ciphertext, índice, contagem e comprimento deste fragmento. A carga útil máxima por frame é portanto `CHUNK = 16.384 − 16 =` **16.368 bytes** (`Frames.kt:12`), e `count = ⌈ciphertext / CHUNK⌉` (`Frames.kt:16`).
- Ciphertext maior que um bucket é fragmentado em sequência estrita, com teto total de 16 MiB. O `FrameAssembler` pertence a **um** stream por par, nunca é global, e os metadados de fragmento são tratados como não confiáveis.
- Dentro do frame vai um `WirePacket` de 5 bytes de cabeçalho (`1 = SIGNAL`, `2 = MLS`) e o ciphertext. Não há campo de remetente: o circuito onion já identifica o par.
- Padding reduz a precisão do tamanho. Não esconde horário, disponibilidade nem toda a informação de tamanho — e isto continua declarado na seção 11.

### 7.2 Estados de rede expostos na UI

Máquina de estados decidida para a v1:

| Estado | Significado |
|---|---|
| `OFF` | Vault bloqueado ou rede não ativada. Zero sockets |
| `STARTING` | Bootstrap do Arti em andamento (`timeout` de 180 s em `native/src/tor.rs`) |
| `PUBLISHING` | Onion lançado, **aguardando o descritor ser publicado no HSDir**. Ainda não é alcançável |
| `ONLINE` | Descritor publicado; o par pode conectar |
| `RETRYING` | Falha transitória; backoff exponencial limitado, cancelável pelo lock. Envios aguardam |
| `ERROR` | Falha persistente. As mensagens continuam no outbox cifrado do aparelho |

**Estado da implementação em 2026-09-14 (parcial, e o SPEC não finge o contrário):**

- Camada nativa: **já feita**. `native/src/tor.rs` expõe `status()` e `await_ready(timeout_ms)`, que bloqueia até o descritor estar publicado e o serviço alcançável (`is_fully_reachable` do `tor-hsservice`: `Running` ou `DegradedReachable`). O teto de espera é `MAX_READY_TIMEOUT_MS = 300.000` ms, independente do orçamento de bootstrap de 180 s. Receber `"PUBLISHING"` de volta significa "o prazo acabou primeiro", **não** falha: a publicação continua e uma chamada posterior pode observar `READY`.
- Camada Kotlin: **`PUBLISHING` já aterrissou** (T2.1). `NetworkStatus` em `app/.../ui/UiContract.kt:8` declara `OFF, STARTING, PUBLISHING, ONLINE, RETRYING, ERROR`, e `NoMessagesController.activate` emite `STARTING` (`NoMessagesController.kt:235`) → `PUBLISHING` (`:246`) → e só chama `ONLINE` (`:248`) depois de `tor.awaitReady()` (`:247`) devolver verdadeiro; qualquer exceção vira `ERROR`. O banner traduz o estado em `Components.kt:105` e as strings `network_publishing` existem em EN e PT (`values/strings.xml` e `values-pt/strings.xml`, ambas na linha 130).
- Lacuna restante: **`RETRYING` não tem emissor.** O valor existe no enum (`UiContract.kt:8`) e tem string PT/EN mapeada em `Components.kt:107`, mas nenhum caminho de código o publica — não há laço de backoff. Essa é a tarefa **T2.3**.

Enquanto T2.3 não fechar, a linha `RETRYING` da tabela acima descreve o alvo especificado, não o comportamento observável no aparelho; as demais linhas já correspondem ao código.

### 7.3 Checkpoint de guards (`TorStateSnapshot`)

O keystore do Arti é configurado como `ArtiKeystoreKind::Ephemeral` (`native/src/tor.rs`): **nenhuma chave Tor toca o disco em claro**. A chave do onion é derivada da seed que vive dentro do vault e é zerada da RAM no lock.

Os metadados de guards, porém, são segredo de ligação: perdê-los a cada lock forçaria escolher guards novos toda vez, o que é um sinal observável. Solução construída em `core/.../nativebridge/TorStateSnapshot.kt`:

- Captura apenas `state/guards.json`, `state/vanguards.json` e `state/circuit_timeouts.json`; qualquer outro caminho é recusado na decodificação
- Formato próprio: magic `WFTOR001`, contagem de entradas, pares (nome, bytes) e SHA-256 final; teto de **4 MiB**
- Captura ao vivo exige **duas leituras idênticas** (até 3 tentativas) para não gravar um arquivo sendo substituído pelo Arti; `null` significa "sem checkpoint utilizável" e o valor anterior do vault **deve** ser preservado
- Validação sintática de JSON com limites (profundidade ≤ 32, ≤ 100.000 nós) e exigência de pelo menos um guard amostrado — o Arti ainda faz a validação de schema autoritativa
- Persiste cifrado em `meta` sob a chave `tor_guard_state`, dentro do SQLCipher do vault; o controller regrava a cada 60 s enquanto a sessão está viva
- No unlock: `clear` apaga o diretório de estado, `restore` recria os arquivos com permissões privadas e o diretório de destino precisa estar vazio e o Tor parado
- Todos os buffers intermediários são zerados em `finally`

Consequência para o gate 10: depois de um lock normal **e** depois de morte abrupta do processo `:tor`, o checkpoint precisa voltar do vault; nunca de arquivo fora dele. O gate 10 foi emendado (item **10b** da seção 12) para medir exatamente isso, porque o texto original só media sockets.

**O checkpoint é por vault — e isso é um vazamento de deniability em aberto.** `NoMessagesController` restaura o snapshot do banco **ativo** (`:223-226`: `TorStateSnapshot.clear(torStateDirectory)` seguido de `restore(..., database.getMeta(TorStateSnapshot.META_KEY))`) e regrava no mesmo banco ativo (`:361`). O vault decoy, porém, nunca é semeado com `tor_guard_state`: `DecoyFactory.create` grava apenas `StorageKeys.IDENTITY` e `StorageKeys.ONION_SEED`. Logo, na primeira abertura com a senha de pânico o diretório de estado do Arti fica vazio e o Arti **amostra guards novos**, enquanto o vault real preserva os seus.

O efeito observável é um distinguidor real/decoy na rede: o guard (ou quem observa o tráfego de entrada) vê a troca abrupta do conjunto de guards exatamente quando a senha de pânico é usada. Isto está registrado na tabela da seção 11 e **não tem solução decidida** — as saídas plausíveis (semear o decoy com um checkpoint sintético no setup; ou compartilhar um único conjunto de guards entre os dois vaults, o que reintroduz ligação entre eles) têm custos opostos de deniability. A decisão final vai para `docs/security-model.md`, mesmo tratamento dado à lacuna da 9.2.

**Limitação já admitida em `docs/security-model.md`, repetida aqui para não ficar só lá:** durante a sessão o Arti escreve metadados de guard e de introduction point **em claro** no diretório de estado, fora do SQLCipher. `clear` apaga esse diretório no lock e no start, mas apagar arquivo **não prova apagamento seguro** dos remanescentes em flash. O gate 1 procura UTF-8 de mensagem e não cobre esses metadados.

---

## 8. Criptografia de mensagem

### 8.1 1:1

- **PQXDH** + Double Ratchet (libsignal) — ver a justificativa da divergência na seção 6
- Header da mensagem Signal: ratchet keys, não conteúdo
- Sealed sender **local** (o onion já identifica o circuito; não adicionar From em claro no frame)
- Attachments: chave de arquivo aleatória 256-bit, envelopada na mensagem; arquivo em chunks AEAD; sem thumbnail na galeria; e **sem qualquer via de salvar ou compartilhar para fora do app** — ver **9.3**

### 8.2 Grupos até 100

Clique obrigatório:

```
criar_grupo(S):
  3 ≤ |S| ≤ 100
  ∀ a,b ∈ S, a≠b: existe pareamento 1:1
  senão recusar e listar arestas faltando
```

**Custo social de N=100:** C(100,2) = **4.950 pareamentos QR**. É a regra pedida, não um bug. UI deve mostrar o progresso do clique (“faltam 12 pares”) e recusar “adicionar quem ninguém conhece”.

**Criptografia do grupo (não pairwise fanout de cada texto):**

- **MLS RFC 9420** (OpenMLS)
- Epoch keys na árvore; FS + PCS
- Distribuição de Welcome/Commit **somente** pelos canais 1:1 já pareados (não existe DS servidor)
- Remoção/saída = novo epoch; o removido não recebe o Commit
- Sender da aplicação: `application_secret` do MLS envolve o AEAD da mensagem
- Arquivos: igual 1:1, chave envelopada no application message

Por que não só Double Ratchet N×N para o texto: em 100 membros, cada msg seria 99 ciphers. MLS é o protocolo certo neste N. O clique continua sendo a **política de membership**, não o cifrador.

Limite duro: 100. Acima recusa. `EnvelopeLimits` fixa `MIN_MEMBER_COUNT = 3`, `MAX_MEMBER_COUNT = 100`, e um grupo existente pode encolher até 1 membro (`MIN_COMMIT_MEMBER_COUNT = 1`). `MAX_PROOF_COUNT = 4.950` é exatamente C(100,2), o clique completo no tamanho máximo.

### 8.3 Retenção de épocas MLS (construído)

`native/src/mls.rs` configura `PastEpochDeletionPolicy::MaxEpochs(2)` nos dois pontos de carga do grupo (`:312` na criação e `:374` no `StagedWelcome`): o OpenMLS guarda **exatamente duas épocas anteriores**.

No mesmo ponto — e é a outra metade do mesmo tradeoff — vai `SenderRatchetConfiguration::new(32, 1024)` (`native/src/mls.rs:313` e `:375`): **32** gerações fora de ordem retidas por remetente e distância máxima de **1024** à frente. Os dois números têm efeito oposto ao `MaxEpochs(2)`: reter chaves de mensagem fora de ordem é o que permite decifrar o que chegou trocado, mas **adia o benefício de forward secrecy** dessas chaves, que ficam vivas no armazenamento do grupo até serem consumidas ou a época cair. Acima de 1024 de distância o pacote é recusado, não decifrado tarde.

Consequência honesta, que precisa estar no SPEC porque é perda de mensagem observável pelo usuário:

- Mensagem de aplicação de outro remetente atrasada por **mais de duas mudanças de membership** fica indecifrável e permanece assim
- Mensagem enviada por quem já foi removido, chegando depois, também fica indecifrável
- FIFO por destino dentro de um grupo não estabelece ordem global entre remetentes diferentes

Isto é o preço direto de FS/PCS sem servidor de entrega: sem mailbox, não há quem segure o Commit para o retardatário. Reduzir a perda exigiria reter mais épocas, o que enfraquece forward secrecy — a escolha de 2 é deliberada e fica como gate de verificação em aparelho.

### 8.4 Coordenação de grupo sem servidor

Não existe Delivery Service. O que o código faz no lugar:

- Criação fica **pendente** até todo par convidado devolver um KeyPackage MLS fresco e vinculado ao `requestId`. Grupo pendente não envia aplicação
- MLS proíbe o committer remover a si mesmo: a saída do coordenador envia `GroupLeave` autenticado a todos os sobreviventes e transfere a coordenação para a **identidade sobrevivente lexicograficamente menor**, que emite o Commit de remoção depois de aceitar a transferência
- Commit que chega antes da transferência é retentado pelo destinatário
- Remoção **não** envia o novo Commit ao removido; o aparelho removido simplesmente não recebe a nova época

---

## 9. UI — identidade visual própria, nome NoMessages

Identidade visual própria (paleta "Grafite e Âmbar"), não clone de protocolo nem de marca.

| Tela | Comportamento |
|---|---|
| Launcher | Ícone e nome **NoMessages** (balão silenciado, paleta grafite/âmbar) |
| Lock | Tela cheia; campo senha; sem “esqueci”; sem biometria default |
| Lista de chats | Igual WA: avatar, nome, preview (só com vault aberto), hora, ticks |
| Conversa | Balões âmbar (enviada) / neutro (recebida), input barra, clipe, câmera, mic (áudio = arquivo AEAD, sem codec nuvem). Toque longo em qualquer balão = **encaminhar** dentro do app (ver 9.3). Sem "salvar", "compartilhar" ou "abrir com" em anexo nenhum |
| Info do contato | Alias local + SAS / “verificar QR de novo” |
| Novo chat | **Só** “Escanear QR” / “Mostrar meu QR”. Sem busca de telefone |
| Novo grupo | Wizard: selecionar membros já pareados; se o subgrafo não é clique, bloquear com a lista de pares faltantes |
| Settings | Lock agora, timeout, export vault, onion copiável só com vault aberto, senha de pânico (redefinir só logado no real) |
| Recents / screenshot | `FLAG_SECURE` em **todas** as activities pós-lock e no lock |
| Notificações | Sem preview de texto/remetente |

Cores: `#1B1F22` / `#23282C` / `#D98E2B` / `#F6DFB8` / `#F4F6F7` (tema claro) — paleta própria "Grafite e Âmbar"; ver `docs/development/ui-report.md` para a tabela completa de tokens claro/escuro e `docs/design/palette-preview.html` para o comparativo das três paletas candidatas. Fonte: Sans Android. Não usar assets oficiais da Meta (logo WhatsApp, wordmark). Ícone próprio “NM”.

Teclado: IME de sistema; settings avisa teclado em nuvem. IME próprio = fase posterior (§14, T7.1).

**Endurecimento de entrada e captura (2026-09-16, T4.9).** Além de `FLAG_SECURE` (linha "Recents /
screenshot" acima), todo campo de texto pede ao IME do sistema para não aprender/sugerir a partir
do que é digitado (`autoCorrectEnabled = false` + `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` +
`InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS`, via `PrivateInput.kt`); autofill é desligado para toda a
árvore de Compose (`importantForAutofill = NO_EXCLUDE_DESCENDANTS`); em API 34+ o conteúdo da
janela é marcado `ACCESSIBILITY_DATA_SENSITIVE_YES` (retido apenas para o serviço de acessibilidade
padrão do usuário — leitores de tela continuam funcionando; um serviço não-padrão não recebe a
árvore); um aviso discreto aparece no composer e nas telas de setup/lock quando o teclado padrão
não é um app de sistema; e, em API 34+, uma tentativa de captura de tela ainda assim gera um aviso
não bloqueante (`Snackbar`), detectada por `registerScreenCaptureCallback`. Nenhum desses itens
impede um teclado comprometido/malicioso de ler a entrada — só o app nunca entregar a digitação ao
IME do sistema faria isso (T7.1, fora da v1). Detalhes e trade-offs em `docs/security-model.md`.

### 9.1 Não lidas e recibos de leitura (decisão v1)

Decisão: **contador de não lidas estritamente local. Nenhum recibo de leitura trafega na rede.**

- Um recibo de leitura exigiria um tipo novo de envelope e entregaria ao par um metadado que ele hoje não tem (quando você abriu a conversa). Isso contraria a seção 11: não criar metadado novo sem necessidade
- O tique duplo continua significando **entrega autenticada** (`DELIVERED` = ACK de todos os destinatários), nunca leitura
- `MessageStatus.READ` existe no modelo (`PENDING, SENT, DELIVERED, READ, FAILED`) e passa a ser marcado **localmente** ao abrir a conversa
- O badge de não lidas em `HomeScreen.kt` só permanece se o contador for de fato implementado; caso contrário é removido, não deixado como enfeite morto

**Estado da implementação em 2026-09-14:** `ChatUi.unread` é sempre 0 — `NoMessagesController` nunca o preenche e `MessageStatus.READ` só aparece no seed do vault decoy. O badge é código morto. A implementação é a tarefa **T4.2** do roteiro de release. Nada disso muda o modelo de ameaça: é contagem local dentro do SQLCipher.

### 9.2 Crescimento de mídia real/decoy — **em definição** (T4.1)

Esta é uma lacuna aberta, registrada aqui em vez de escondida.

**O problema.** A seção 5.2 promete que "`real.db` e `decoy.db` têm tamanho alinhado para o dump não denunciar". O código cumpre isso **apenas no setup**: `alignAllocations` iguala os dois bancos na criação e o core verifica que os dois arquivos têm o mesmo número de bytes; `beforeExport` compara de novo só os `.db`. Em uso normal, porém, anexos reais crescem livremente em `files/vault/real.files/` (via `MessagingEngine.storeAttachment`), enquanto `files/vault/decoy.files/` fica parado com a cobertura sintética criada no setup. `docs/development/storage-api.md` chega a citar uma "política de reserva de capacidade fixa" que **não existe** no código de runtime.

**Por que importa.** Depois de algumas mídias reais, o tamanho agregado dos dois contêineres diverge de forma monotônica e observável num `adb pull`. Isso não quebra confidencialidade (tudo continua AEAD), mas corrói exatamente a deniability de disco que a senha de pânico promete — e é o gate 2 do release.

**O que ainda não está decidido.** As duas saídas plausíveis são:

- (a) **Reserva fixa de mídia por vault**, alocada no setup e consumida por dentro, com cobertura cega gravada no decoy a cada N MiB, e falha limpa quando a reserva esgotar. Custa espaço fixo no aparelho e obriga a definir N e o teto;
- (b) **Aceitar a divergência** e documentá-la como limite explícito da deniability ("o tamanho da mídia denuncia volume de uso real"), sem prometer o que não se entrega.

A recomendação técnica é (a), porque este SPEC promete tamanhos iguais — mas a decisão depende da medição do gate 2 em aparelho (T3.5) e **não está tomada**. Até que esteja, nem o SPEC nem o README podem afirmar que os dois vaults têm o mesmo tamanho em uso; só no setup. A decisão final vai para `docs/security-model.md`.

### 9.3 Anexos ficam dentro do app; encaminhamento é interno (2026-09-17, T4.11/T4.12)

**Anexo recebido não sai do app.** Mídia e arquivos recebidos podem ser **vistos** no viewer interno (`AttachmentViewer`, bytes decifrados só em memória) e **nada mais**: não há como salvá-los no aparelho nem entregá-los a outro aplicativo. Isso não é uma opção que o usuário possa ligar — é ausência de mecanismo. A auditoria de 2026-09-17 percorreu os sete vetores pelos quais um cliente Android normalmente exporta mídia e confirmou que **nenhum** existe em `app/src/main`:

| Vetor | Estado |
|---|---|
| `Intent.ACTION_SEND` / `ACTION_SEND_MULTIPLE` | ausente |
| `Intent.ACTION_VIEW` para abrir em outro app | ausente |
| `Intent.createChooser` | ausente |
| `FileProvider` / `<provider>` exportando `files/vault/*.files/` | ausente (nenhum `<provider>` no manifesto) |
| Escrita em `MediaStore`, `Downloads` ou galeria | ausente (já proibido pela seção 3) |
| Botão/menu "salvar", "compartilhar", "exportar" ou "abrir com" | ausente em todas as telas |
| Seleção/cópia do corpo de mensagem **recebida** | desabilitada explicitamente (`DisableSelection`) |

O último item é o único que exigiu mudança de código, e é endurecimento preventivo, não correção de vazamento: um `Text` do Compose já não é selecionável sem um `SelectionContainer` acima dele; envolver o corpo das mensagens **recebidas** em `DisableSelection` (`ChatScreen.kt`) torna a garantia estrutural, de modo que nenhuma tela futura que introduza um `SelectionContainer` acima da lista reabra a via "selecionar → copiar → colar em outro app". Mensagens **enviadas** (as palavras do próprio usuário) e o campo de composição continuam com cópia normal.

O único caminho de saída de um anexo continua sendo o export do vault inteiro (4.4), que sai cifrado e só abre com a senha.

**Encaminhar é interno, e o destino vê que foi encaminhada.** Toque longo em qualquer balão — texto ou anexo de qualquer tipo, inclusive áudio — abre o seletor de destinatários (contatos pareados não-`displayOnly` + grupos `READY`) e reenvia a mensagem por **um envio normal pelo mesmo caminho de sempre**: novo envelope, novo `id`, novo timestamp e, no caso de anexo, **nova chave de arquivo e novo `fileId`**, porque o anexo é recifrado a partir do plaintext em vez de ser repassado como ciphertext armazenado. Os bytes decifrados existem só em memória e são zerados no `finally`; nada é escrito fora do vault. Como o encaminhamento entra na mesma `outbox`, ele respeita a pausa de transporte da 7.2 sem código adicional: com o Tor fora do ar as cópias ficam `PENDING`.

A mensagem chega ao destino com a etiqueta **"Encaminhada"** acima do conteúdo do balão. Essa etiqueta é **um bit de proveniência e nada mais**: não viaja chat de origem, nem autor original, nem contador de "encaminhada N vezes" — qualquer um dos três vazaria para o destinatário as outras conversas do remetente, o que a seção 11 proíbe. Encaminhar uma mensagem já encaminhada mantém o bit em `true`; ele não acumula. O transporte do bit é o campo `forwarded` do envelope (ver 10.1) e a coluna homônima de `messages` (seção 10).

**A etiqueta só existe quando a cópia troca de conversa.** O seletor de destinos não esconde a conversa de origem: o usuário **pode** reenviar a mensagem para o mesmo chat onde ela já está. Quando faz isso, a cópia é enviada como uma mensagem **normal**, com `forwarded = false` — sem etiqueta, indistinguível de ter digitado o texto de novo, que é exatamente o que ela é. O bit só vai a `true` quando o destino é uma conversa **diferente** da de origem. A decisão é por destino, não por encaminhamento: selecionar o próprio chat e mais dois contatos produz uma cópia sem etiqueta e duas com. A regra mora em `shouldMarkForwarded(sourceChatId, targetChatId)` (`ui/UiLogic.kt`), aplicada por `NoMessagesController.forwardMessage` contra o `peer_or_group` da linha original. Nada disso muda o formato do bit no fio nem a coluna do banco — muda só quando ele é ligado.

---

## 10. Dados no SQLCipher (vault aberto)

Tabelas construídas (nomes internos, não visíveis), conforme `AndroidVaultStorage`:

- `meta(k, v)` — pares chave/valor simples, lidos e gravados por `getMeta`/`putMeta` (`ChatDatabase.kt:18-20`). Guarda o epoch, a identidade e a seed de onion do vault, a **seed do onion de campainha** (`doorbell_seed`, 32 bytes — seed própria, distinta de `onion_seed`, para que os dois endereços sejam desvinculáveis; em uso desde T4.17), o checkpoint de guards do Tor (`tor_guard_state`) e mais cinco chaves de runtime: `display_name` (`NoMessagesController.kt:156,789`), `bridges` (`:222,592`), `lock_timeout` (`:221,586`), `pairing_consumed` (`:209,489`) e `outbox_sequence` (`MessagingEngine.kt:273,275`)
- `opaque_blobs(namespace, k, value)` — estado de runtime opaco, chaveado por namespace (ver 10.2). Além dos namespaces de mensageria, T4.17 acrescentou dois: `vault_settings` (chave `doorbell_enabled`, 1 byte, **ausência = ligado**) e `doorbell_knocks` (chave = `id_pub` do contato, 12 bytes: carimbo do último toque **tentado** + contador de falhas consecutivas). Catálogo em `docs/development/runtime-catalog.md`
- `contacts(id_pub, alias, onion, identity_public, signal_peer, paired_at, display_only, doorbell_onion, doorbell_token, doorbell_token_issued)` — as duas primeiras colunas de campainha entraram no esquema **v3** e `doorbell_token_issued` no **v4**, as duas por `ALTER TABLE` a partir de `open()` (ver seção 6 e `docs/development/runtime-catalog.md` §7)
- `messages(id, peer_or_group, direction, ts, body, status, forwarded)` + índice `(peer_or_group, ts DESC, id DESC)` — `forwarded` (`INTEGER NOT NULL DEFAULT 0 CHECK(forwarded IN (0,1))`) espelha o bit homônimo do envelope em `body` e existe como coluna própria para a lista de conversa não ter de decodificar cada envelope. Entrou na **versão 2** do esquema, por `ALTER TABLE` (ver `docs/development/runtime-catalog.md` §7)
- `chat_groups(id, name, mls_state, coordinator, created_at)` — nomeada assim para não colidir com palavra reservada
- `group_members(group_id, id_pub)` com `ON DELETE CASCADE`
- `wire_blobs(hash, frame)` — payload de saída armazenado **uma vez** por digest SHA-256
- `outbox(id, dest_onion, wire_hash, created_at)` + índice `(created_at, id)` — a linha referencia o digest; o payload só é liberado depois do último destinatário
- `files(id, path_rel, aead_params, size, epoch, display_name, mime_type)`
- `pair_graph(first_id, second_id, evidence, paired_at)` com `CHECK(first_id < second_id)` — aresta canônica, uma linha por par
- `storage_reserve(id, payload)` — páginas cifradas reservadas para alinhar o tamanho dos dois bancos

Tudo isso **já** está dentro do SQLCipher. Não há segunda camada “por mensagem” obrigatória; o ratchet já cifrou no fio, o vault cifra em disco. Defense in depth: body pode permanecer no ratchet seal + DB cipher.

### 10.1 Envelopes de aplicação

Plaintext autenticado transportado **dentro** de Signal ou MLS, nunca em claro no fio. Tags de wire fixas (`EnvelopeCodec`):

| Tag | Tipo | Para quê |
|---|---|---|
| 1 | `Text` | corpo de texto (+ bit `forwarded`) |
| 2 | `Attachment` | anexo: `fileId`, `fileEpoch`, `fileKey` e ciphertext (+ bit `forwarded`) |
| 3 | `Ack` | confirmação autenticada de um `id` recebido |
| 4 | `Evidence` | provas de pareamento para o grafo do clique |
| 5 | `KeyPackage` | KeyPackage MLS vinculado a um `requestId` |
| 6 | `GroupInvite` | Welcome MLS + membros + provas |
| 7 | `GroupCommit` | Commit MLS + membros + provas |
| 8 | `KeyPackageRequest` | pedido de KeyPackage com nonce |
| 9 | `GroupLeave` | saída/transferência de coordenação |
| 10 | — | **reservado**, não emitido |
| 11 | `ControlRejected` | recusa permanente de pré-requisito de controle |

`ControlRejected` (tipo **11**) existe porque, sem servidor, um controle que nunca poderá ser satisfeito ficaria retentando para sempre na lane. Ele carrega o `requestId` — que é **o ID do envelope original**, não um nonce de key package — e uma de quatro razões limitadas: `KEY_PACKAGE_QUOTA` (1), `REQUEST_EXPIRED` (2), `GROUP_CAPACITY` (3), `INVALID_PREREQUISITE` (4). O remetente só honra uma recusa para o controle rejeitável pendente **daquele par**, remove o controle sem alegar entrega, registra `failed_controls` e grava `group_error` como `peerId:REASON`. Envio de aplicação no grupo para enquanto houver erro; remover o membro que falhou limpa o erro. A própria recusa é confirmada com ACK.

**Versão de wire 2 — o bit `forwarded` (2026-09-17).** O cabeçalho comum de `EnvelopeCodec` traz `version` como `u16`; ele passou de **1 para 2**. A única diferença entre as duas versões é o **último byte do corpo** de `Text` e de `Attachment`: um flag `forwarded`, `0` ou `1`, escrito **depois** de todos os campos que já existiam. Os outros oito tipos (`Ack`, `Evidence`, `KeyPackage`, `GroupInvite`, `GroupCommit`, `KeyPackageRequest`, `GroupLeave`, `ControlRejected`) têm layout byte a byte idêntico nas duas versões. `encode` sempre emite a versão 2; `decode` aceita as duas e, na versão 1, **não consome byte nenhum** e devolve `forwarded = false` — na versão 1 o campo é ausente, não é um zero. Qualquer outro valor que não 0 ou 1 na versão 2 é envelope malformado, nunca um valor silenciosamente verdadeiro. A retrocompatibilidade é testada com um vetor de bytes versão 1 montado à mão em `EnvelopeCodecTest`. Semântica do bit: ver 9.3.

Limites de codec (`EnvelopeLimits`): envelope ≤ 12 MiB, texto ≤ 8 MiB, ciphertext de anexo ≤ 8 MiB + 64 KiB de folga de formato, ≤ 4.950 provas de 1.024 bytes, nome e MIME ≤ 255 bytes. Fora do envelope, `WirePacket.MAX_PACKET_BYTES` = 16 MiB.

### 10.2 Filas, lanes e cursores

- **Lanes:** cada envelope é classificado por `MessagingPolicy.lane` em `direct`, `group:<id>`, `request:<nonce>`, `evidence` ou `rejection`, persistidas no namespace `outbox_lanes`. A SQL de prontidão só libera o próximo envelope de um par (destino, lane) depois do ACK do anterior. Isso é o que impede que uma recusa de quota em um grupo bloqueie um chat direto — head-of-line blocking fica contido na lane.
- **Concorrência:** no máximo quatro destinos distintos em voo; prazo de ACK de 60 s; streams expirados, recusados, concluídos ou de engine fechando são explicitamente fechados. No máximo 16 remontagens compartilhando 32 MiB.
- **Cursores de trial:** stream Signal de origem desconhecida não traz identidade. A decodificação tenta candidatos com limite duro: **64** pares para pacotes ≤ 64 KiB, **8** acima disso (`MessagingPolicy.trialLimit`). O cursor retoma de onde parou na retransmissão; a cada retry, pares recém-pareados e grupos recém-entrados vão para a frente, os não varridos mantêm a rotação, e mudança só de ordenação por recência **não** reinicia a varredura. O cache não retém ciphertext, tem no máximo 16 entradas e teto conservador de 24 MiB (com o limite de 1.024 contatos fica abaixo de ~4 MiB), expirando em 120 s ocioso para mensagens pequenas e 15 min para transferências grandes.
- **Token bucket:** toda invocação de pacote Signal/MLS desconhecido passa por um balde global de 2/s com rajada 4. MLS usa as mesmas fatias 64/8, carregando um snapshot de segredo por vez.

### 10.3 Limites duros de runtime

| Limite | Valor | Onde |
|---|---|---|
| Contatos | **1.024** | `MessagingPolicy.maxContacts`, casado com o conjunto de candidatos de `SignalSessions.decryptCandidates` |
| Grupos ativos | **128** | criação, admissão e enumeração de recepção em `MessagingEngine`; registros inativos não consomem slot |
| Membros por grupo | 3..100 na criação, ≥ 1 depois | `EnvelopeLimits` |
| Arestas no grafo de clique | **16.384** | a importação recusa quando `armazenadas + novas ≥ 16.384` |
| Provas por envelope de sincronização | 256 | evidência só é buscada para os membros selecionados |
| KeyPackages MLS pendentes | **4 por par solicitante**, **128 no total**, TTL de **24 h** | `MessagingEngine.packageTtl` e `MessagingPolicy.packageRefusal`; expirados são podados antes de novos pedidos e recusados na admissão do Welcome |
| Snapshot de estado do Tor | 4 MiB | `TorStateSnapshot.MAX_SNAPSHOT_BYTES` |
| Épocas MLS anteriores retidas | **2** | `PastEpochDeletionPolicy::MaxEpochs(2)`, `native/src/mls.rs:312,374` (ver 8.3) |
| Gerações fora de ordem por remetente MLS | **32** | `SenderRatchetConfiguration::new(32, 1024)`, `native/src/mls.rs:313,375` |
| Distância máxima à frente no ratchet MLS | **1.024** | mesma configuração, `native/src/mls.rs:313,375` |

Consequência do TTL de 24 h: um Welcome atrasado além da reclamação dos pacotes **não** entra no grupo. Coordenação de grupo exige janela de disponibilidade sobreposta — outra face direta do "sem mailbox".

Os namespaces de `opaque_blobs` usados hoje são **catorze**: `group_pending`, `group_left`, `group_error`, `group_request`, `group_packages`, `key_packages`, `outbox_control`, `outbox_lanes`, `attempts`, `accepted_ids`, `rejected_ids`, `receipts`, `failed_controls` e `evidence_gossip` — todos via `putBlob`/`getBlob` (`SELECT value FROM opaque_blobs WHERE namespace = ? AND k = ?`, `ChatDatabase.kt:34-37`).

**Não confundir com a tabela `meta`:** `display_name`, `bridges`, `lock_timeout`, `pairing_consumed` e `outbox_sequence` — assim como `tor_guard_state` — são chaves de `meta`, não namespaces de `opaque_blobs` (ver a lista da seção 10 e `docs/development/runtime-catalog.md`). São tabelas distintas com APIs distintas; procurar esses registros em `opaque_blobs` não encontra nada. O catálogo formal de cada um (formato, dono, ciclo de vida) é a tarefa **T5.2** e vive em `docs/development/runtime-catalog.md`, não aqui — o SPEC fixa os limites, o catálogo descreve os registros.

---

## 11. Rede e metadados

| Metadado | Onde vaza | Mitigação |
|---|---|---|
| “Este telefone tem NoMessages” | package + ícone | Aceito (decisão 1) |
| IP real | ISP vê Tor, não o par | Arti |
| Quem fala com quem | Circuito onion 1:1 | Um circuito por contato; sem multiplex óbvio no v1 |
| Tamanho da msg | tráfego Tor | padding em buckets 256/1024/4096/16384 (ver 7.1). Reduz precisão, **não** elimina: conteúdo acima de **16.368 B** (bucket de 16.384 menos o cabeçalho de fragmento de 16 bytes) vira vários frames e a contagem de frames revela a ordem de grandeza |
| Horário | tráfego | sem solução perfeita; não fingir |
| Disponibilidade (“este onion está no ar”) | descritor publicado no HSDir | **aceito**. Sem mailbox, estar alcançável é pré-requisito de entrega; quem conhece o endereço onion pode sondar se ele está publicado (ver 7.2) |
| Volume de mídia acumulado | tamanho dos diretórios de anexo no dump | **em aberto**, ver 9.2 (T4.1). Hoje só o setup alinha real/decoy |
| Qual vault foi aberto (real vs. pânico) | conjunto de guards muda entre sessões: o checkpoint `tor_guard_state` é **por vault** e o decoy não é semeado com um | **em aberto**, ver 7.3. O decoy amostra guards novos na primeira abertura, enquanto o real preserva os seus — troca visível ao guard e a quem observa a entrada. Decisão vai para `docs/security-model.md` |
| Metadados de guard/introduction point em claro no disco | diretório de estado do Arti, fora do SQLCipher, durante a sessão | parcial: `TorStateSnapshot.clear` apaga no lock e no start, mas **não** há apagamento seguro de remanescentes em flash (ver 7.3 e gate 10b) |
| Membership de grupo | só nos devices do clique | sem servidor |
| Campainha: “este aparelho pode estar com o aviso ligado” | com a campainha ligada, o processo `:tor` **não** morre no lock e exibe uma notificação persistente de foreground service | **aceito**, é o preço da funcionalidade. Não diz **qual** cofre está aberto (real e isca sobem a campainha pelo mesmo caminho de código), nem quais contatos existem, nem que haja mensagem alguma. Sem a campainha o processo morre, e essa diferença é observável no próprio aparelho |
| Campainha: número de contatos | o conjunto de tokens carregado no verificador tem um token por contato com `doorbell_token_issued` preenchido | **parcial**: o conjunto **nunca sai do processo nativo** e não é sondável de fora — um observador de rede não tem como contá-lo. O que vaza é local: quem já tem o aparelho apreendido e vivo pode inspecionar a memória do `:tor` e ler a ordem de grandeza. Sem identidades: o verificador nunca aprende a qual contato cada token pertence |
| Campainha: padrão de conexões ao onion de aviso | descritor publicado no HSDir + conexões recebidas | **aceito e igualado**: quem conhece o endereço pode sondá-lo e abrir conexões, mas **não distingue uma batida válida de uma inválida** — toda batida inválida (tamanho errado, token desconhecido, fora da janela, replay, limite de taxa) fecha a conexão de forma idêntica, sem escrever um byte; só o autor de uma batida válida recebe o byte de confirmação. A batida em si tem tamanho fixo (57 bytes) e não carrega remetente nem conteúdo |

---

## 12. Testes de ameaça (gate de release)

1. `adb pull` com app locked → `strings` + SQLCipher senha vazia/PIN do Android/`password` → **zero** UTF-8 de mensagem
2. Mesmo dump: tamanhos real.db ≈ decoy.db. **Atenção:** o alinhamento só é garantido no setup; o crescimento de mídia em uso é lacuna aberta da seção **9.2** (T4.1). Este gate deve medir os diretórios de anexo também, não só os `.db`
3. Timing: 100 unlocks senha errada vs real vs pânico — diferenças < jitter documentado; nenhum log distinto
4. `FLAG_SECURE`: screencap da conversa preta/falha
5. Recents sem texto
6. `dumpsys notification` sem body
7. `adb backup` recusado
8. QR expirado / replay rejeitado
9. Grupo com aresta faltando recusa
10. Após lock, medido nas **duas** variantes da opção *Aviso de mensagens pendentes* (seção 4.3):
    - **10a. Campainha desligada** — comportamento de sempre, inalterado: `ss/tcpdump` no emulador →
      zero sockets NoMessages; processo `:tor` **morto** (PID antes/depois).
    - **10b.** Checkpoint de guards (seção **7.3**), medido em duas variantes — lock normal **e** morte abrupta do processo `:tor`: (i) depois do lock o diretório de estado do Tor **não existe**; (ii) depois do unlock os guards vêm do `meta.tor_guard_state` do vault aberto e **nenhum** arquivo de estado sobrevive fora dele; (iii) registrar se o conjunto de guards do vault decoy difere do real na primeira abertura — hoje difere, e é o vazamento em aberto da 7.3. O item (i) é medido no modo **10a**: em modo mínimo o processo não morreu, então o diretório de estado continua vivo por construção — e essa é a limitação registrada em `docs/security-model.md`, não uma reprovação.
    - **10c. Campainha ligada** — o processo `:tor` **pode** continuar vivo, como foreground service,
      e nesse estado é preciso demonstrar que ele só serve a campainha: (i) nenhuma conexão ao onion
      de **mensagens** é aceita (o descritor daquele onion saiu do ar e uma tentativa de entrega do
      par falha); (ii) só o onion de campainha responde, e só na porta virtual 4243; (iii) nenhum
      dado de sessão ou de banco é acessível — o dump privado continua passando no gate 1; (iv) a
      notificação de foreground está presente, é a esperada (canal `background_service`,
      `IMPORTANCE_LOW`, silenciosa, título "NoMessages", sem corpo) e some ao desbloquear; (v) uma
      batida válida de um contato pareado produz a notificação de aviso (título "NoMessages", corpo
      fixo, sem remetente, sem contagem) e uma batida inválida não produz nada; (vi) destravar o
      **mesmo** cofre reaproveita o filho vivo, e destravar outro cofre ou entrar com a senha de
      pânico o derruba por completo — PIDs registrados em cada caso.
11. Export + import em segundo aparelho com a senha real → conversas iguais
12. Senha de pânico no segundo aparelho → **só** decoy, zero contato real
13. Vetores: RFC 8439, RFC 7748, RFC 8032, RFC 9106 (Argon2), wycheproof AES-GCM, libsignal known-answer, MLS test vectors RFC 9420 — e **avaliação explícita do desvio PQXDH** (seção 6), já que o handshake construído não é o X3DH clássico

Dump **unlocked** deve falhar (RAM). Documentar, não esconder.

---

## 13. Fases de construção

| Fase | Entrega | Critério de pronto |
|---|---|---|
| **0** | Vault + duas senhas + lock + export/import | Testes 1–3, 6, 7, 11, 12 |
| **1** | UI lock + shell NoMessages (lista vazia) | FLAG_SECURE, ícone, paleta |
| **2** | Identidade + QR + SAS + 1:1 Tor | Dois devices, 10 msgs, dump locked cego |
| **3** | Arquivos AEAD + viewer interno | Sem MediaStore |
| **4** | MLS grupos ≤100 com checker de clique | Recusa / aceita determinística |
| **5** | Harden: padding, bridges, fuzz QR, auditoria externa | |

Sem Fase 0 passando `adb pull`, não há chat. Sem Tor estável, não há “conexão direta” no sentido pedido.

Ordem de magnitude: Fase 0 2–4 sem; 1+2 6–10 sem; 3 2–3; 4 5–8 (MLS + grafo); auditoria à parte.

---

## 14. Fora da v1

- Multi-device ao vivo (export é o “segundo device”)
- Mailbox / VPS de recado
- LAN bypass
- Play Store como canal primário
- IME próprio
- Stealth (esconder o ícone)
- Amarra StrongBox que impede export
- N>100
- “APK inteiro cifrado”
- iOS / Flutter / app único nos dois sistemas

---

## 15. Checklist de primitives (copiar para README técnico)

```
KDF          Argon2id (RFC 9106)  m≥64MiB  t~2.5s  p=1  out=32
KEK wrap     XChaCha20-Poly1305 (RFC 8439 + 192-bit nonce)
DB           SQLCipher 4 AES-256-CBC + HMAC  (ou SQLCipher AEAD se disponível)
Files        XChaCha20-Poly1305 chunks 64KiB
ID sig       Ed25519 (RFC 8032)
ID ECDH      X25519 (RFC 7748)
Handshake    PQXDH (X25519 + Kyber-1024, prekey KEM assinada)  -- libsignal-client 0.102.2 exige o bundle híbrido
1:1          Double Ratchet (libsignal)
Groups       MLS 1.0 RFC 9420 (OpenMLS), retenção de 2 épocas passadas
Transcript   SHA-256
Padding      buckets 256/1024/4096/16384, MAX_FRAME 16388 B, total <= 16 MiB
Tor          Onion v3, Arti, keystore efêmero + checkpoint de guards no vault
```

Parâmetros Argon2id efetivamente implementados (`KdfParams`): `memoryKiB` default 65.536 (64 MiB), faixa aceita 64 MiB..256 MiB; `iterations` default 3, faixa 1..20; `p = 1`; saída 32 bytes. A calibração de ~2,5 s sobe primeiro as iterações (até 20) e só então dobra a memória até o teto de 256 MiB — nunca para baixo, como manda a seção 4.2.

Nenhuma chave secreta vive fora do vault cifrado. Nenhuma chave do vault real é derivável da senha de pânico.

---

## 16. Decisões travadas (não reabrir sem Maxwell)

1. Ícone visível — **NoMessages**
2. Exportável com senha — **sem StrongBox obrigatório**
3. Transporte v1 — **Tor onion**, sem mailbox
4. Grupos — **até 100**, clique completo
5. Senha de pânico — **v1**
6. Mailbox — **não**

Default visual: identidade visual própria ("Grafite e Âmbar", ver seção 9); não é mais clone visual do WhatsApp.

---

## 17. Changelog do SPEC

### 2026-09-18 — campainha: aviso de mensagens pendentes com o app bloqueado (T4.17)

| # | Mudança | Seções | Motivo |
|---|---------|--------|--------|
| 1 | **Exceção documentada à invariante do lock**: com o aviso ligado, o socket do onion de campainha — e só ele — sobrevive ao bloqueio | 4.3, 7, 11 | sem mailbox de terceiro, um destinatário bloqueado não tem como saber que há fila para ele; o aviso é a razão de ser da funcionalidade. O que sobrevive é enumerado e limitado: a chave daquele onion e o conjunto de tokens emitidos |
| 2 | **Segundo serviço onion**, em porta virtual **4243** (mensagens: 4242), com ciclo de vida independente sobre o mesmo host Arti compartilhado | 7 | um sondador de um serviço nunca pode ser respondido pelo outro; e "derrubar mensagens" tem de ser uma operação distinta de "derrubar a campainha" |
| 3 | **Protocolo de batida**: `versão(1) ‖ timestamp u64 BE(8) ‖ nonce(16) ‖ HMAC-SHA256(token, versão‖timestamp‖nonce)(32)` = **57 bytes fixos**, uma batida por conexão; janela ±300 s, cache de nonce de 600 s, no máximo **uma batida aceita por token a cada 600 s** | 7, 11 | tamanho fixo não vaza nada por padding; a janela mais o nonce fecham replay; o limite de taxa fecha a batida como canal de sinalização. Toda invalidez fecha a conexão do mesmo jeito, sem escrever um byte |
| 4 | **Esquema do cofre v3 → v4**: `contacts.doorbell_token_issued BLOB NOT NULL DEFAULT x''`, pelo mesmo `ALTER TABLE` a partir de `open()` de T4.15 | 6, 10 | o token que **este** aparelho cunhou em cada oferta se perdia; sem ele o aparelho sabia bater mas não sabia reconhecer quem batia |
| 5 | **Opção por cofre, padrão LIGADO** (`opaque_blobs.vault_settings/doorbell_enabled`; ausência de chave = ligado), com paridade real/isca sem nenhuma bifurcação de slot | 4.3, 9, 10 | o aviso é a funcionalidade; e a senha de pânico tem de subir a campainha do cofre-isca pelo mesmo caminho de código, ou o modo se torna um distinguidor |
| 6 | **Gate 10 desdobrado em 10a/10c**: evidência exigida nos dois modos | 12 | com dois comportamentos legítimos de lock, um gate que só mede um deles não mede o produto |

Senha de pânico: teardown total e incondicional do processo `:tor`, sem exceção de modo mínimo —
ela entra pelo fluxo normal de unlock resolvendo para o cofre-isca, e a campainha do isca sobe
depois, pelo mesmo `lock()`/`activate()` do cofre real. **Validação em aparelho/emulador: PENDENTE**
(gate 10c e gate 6 acima; ver `docs/release-checklist.md` e `docs/development/device-verification.md`).

### 2026-09-17 — QR de pareamento compacto, bundle pela rede Tor (T4.16)

| # | Mudança | Seções | Motivo |
|---|---------|--------|--------|
| 1 | QR de pareamento passa ao **formato 2** (`nomessages:2:` / `nomessages-confirm:2:`), com `bundle_hash` no lugar do bundle | 6 | o formato 1 media ~2950 bytes, QR versão 40 (177 módulos/lado), e a câmera do Galaxy Note10+ do usuário **não conseguia ler** nem com o QR ocupando um monitor inteiro — 1569 desses bytes eram a chave Kyber-1024 obrigatória do PQXDH |
| 2 | Bundle PQXDH buscado pela rede Tor, autenticado pelo hash assinado | 6 | o único jeito de tirar 1832 bytes do QR sem afrouxar a autenticação: o hash está dentro da assinatura Ed25519 que o scanner já confere, e por consequência dentro do transcript do SAS |
| 3 | Dois envelopes novos (`BundleRequest`/`BundleResponse`), `EnvelopeCodec` versão 3, `PacketKind.BUNDLE` | 6, 10.1 | precisam trafegar **antes** de existir sessão Signal, então não cabem no caminho cifrado existente |
| 4 | Prazos separados por fase: 300 s de **aquisição** por oferta e 240 s de **confirmação** a partir do staging | 6 | uma ida e volta até um onion recém-publicado custa 5–40 s; e medição ao vivo (run6) mostrou que ancorar as duas fases na criação da oferta deixava 4–35 s para a fase humana quando a aquisição gastava duas rotações. O total de relógio de parede passa a poder exceder 300 s |
| 5 | Regeneração automática do QR na tela, como máquina de estados pura testável | 6, 9 | um QR morto na tela é pior que nenhum: o outro lado fica tentando escanear |
| 6 | `SignalBundle` passa à versão 3, autocontido (`identity` e `pre` dentro do blob) | 6 | com o QR carregando só o hash, o blob não tem mais de onde emprestar esses dois campos |
| 7 | `doorbell_key` e `doorbell_token` na oferta; `meta.doorbell_seed`; `contacts.doorbell_onion`/`doorbell_token` (esquema v3). Entraram aqui **sem comportamento**, para não haver uma segunda migração de formato de QR; **ganharam comportamento em T4.17** (entrada abaixo), sem mudar um byte do formato | 6, 10 | decisão de escopo para **não haver uma segunda migração de formato de QR** quando a campainha for construída |
| 8 | `doorbell_key` carrega a **chave de 32 bytes**, não os 62 caracteres do endereço | 6 | com o endereço textual a resposta media QR versão 15, acima do orçamento de 14; a chave é a única parte não derivável de um endereço v3 |

Validado em 2026-09-17: `BUILD SUCCESSFUL` para `:core:test :app:testDebugUnitTest :app:lintDebug
:app:assembleDebug :app:assembleDebugAndroidTest`; `:core:test` 95 testes / 0 falhas / 2 pulados;
`:app:testDebugUnitTest` 60 testes / 0 falhas / 1 pulado; lint **0 erros** (42 avisos, 6 hints —
mesma linha de base de T4.15). **Validação ao vivo em dois emuladores continua PENDENTE**; o teste
instrumentado novo (`doorbellColumnsRoundTripAfterTheSchemaVersionThreeMigration`) foi escrito e
**não foi executado**.

### 2026-09-17 — nova paleta de cores: "Grafite e Âmbar" (T4.14)

| # | O que mudou | Onde | Por quê |
|---|---|---|---|
| 1 | Retitulada a seção 9 e substituída a tagline "Clone visual, não clone de protocolo" | seção 9 | o app deixa de reivindicar identidade visual "idêntica ao WhatsApp" — a nova paleta própria ("Grafite e Âmbar") é o oposto deliberado de um clone de marca, não só de protocolo |
| 2 | Tabela de telas: linha `Launcher` perde o parêntese "(verde WhatsApp-like)"; linha `Conversa` passa a descrever balões âmbar (enviada) / neutro (recebida) em vez de "verde/cinza" | seção 9 | as cores reais deixaram de ser verde-WhatsApp; a tabela precisa continuar batendo com o que o app renderiza |
| 3 | Linha de cores trocada de `#075E54`/`#128C7E`/`#25D366`/`#DCF8C6`/`#EDEDED` ("paleta clássica WA") para os tokens do tema claro da paleta nova, com ponteiro para a tabela completa em `docs/development/ui-report.md` e para o comparativo de paletas candidatas em `docs/design/palette-preview.html` | seção 9 | seis constantes de cor hardcoded foram removidas do código-fonte e substituídas por `MaterialTheme.colorScheme` (claro **e** escuro); o SPEC precisa apontar para a fonte de verdade em vez de embutir só os cinco hexadecimais do tema claro |
| 4 | "Nome da launcher" (linha ~60) e "Default visual" (seção 16, decisões travadas) reescritos para não afirmarem mais um clone visual do WhatsApp | seções 1 e 16 | as duas frases repetiam a mesma reivindicação de "estilo WhatsApp"/"clone WhatsApp, paleta clássica" fora da seção 9; deixadas desatualizadas, um leitor que só lesse a seção 1 ou 16 continuaria com a informação antiga |

Departure completo da identidade verde-WhatsApp: `NoMessagesTheme.kt` teve os seis valores de cor
hardcoded (tons profundo, verde, destaque, balão, superfície e lido) apagados e
`LightColors`/`DarkColors` reconstruídos inteiramente com `lightColorScheme(...)`/
`darkColorScheme(...)`, atribuindo um papel de `ColorScheme` explícito a cada tom (`primary`,
`secondary`, `tertiary`, `surfaceVariant`, `outline`, etc.) em vez de reaproveitar constantes soltas
em cada tela. Todos os 27 pontos de chamada que liam essas constantes diretamente (`HomeScreen.kt`,
`ChatScreen.kt`, `Components.kt`, `GroupWizardScreen.kt`, `PairingScreen.kt`, `SettingsScreen.kt`)
passaram a ler `MaterialTheme.colorScheme.*` — ver `docs/changes/NoMessagesTheme.kt.md` e o
`docs/changes/*.kt.md` de cada arquivo para a lista completa por ponto de chamada.

Todo par texto/fundo da paleta nova foi verificado contra WCAG AA (razão ≥ 4,5:1 para pares que
carregam texto), nos dois temas (claro e escuro). A distinção visual entre bolha enviada e recebida
deixa de depender só de matiz (verde vs. cinza) — passa a combinar forma/alinhamento (enviada à
direita, recebida à esquerda) **e** tom (`primaryContainer` âmbar claro vs. `surface`/`surfaceVariant`
neutro), o que também torna o app utilizável por quem tem daltonismo de vermelho-verde, o par de
matizes mais próximo do antigo verde-WhatsApp de bolha/selo de "lido".

**Vantagem:** o app deixa de correr o risco de trade dress citado na seção 1 ("Risco legal: trade
dress/marca WhatsApp-Meta") pela via mais visível — a paleta de cores — sem abrir mão de nenhuma
convenção de UX que o usuário já testou (mesma disposição de bolhas, mesmos ícones, mesmo fluxo).

### 2026-09-17 — anexos não saem do app + encaminhar mensagem (T4.11/T4.12)

| # | O que mudou | Onde | Por quê |
|---|---|---|---|
| 1 | Nova **9.3**: anexo recebido não pode ser salvo nem compartilhado, com a tabela dos sete vetores auditados | seções 8.1, 9 (tabela de telas) e 9.2→10 | o SPEC só proibia `MediaStore` (seção 3) e prometia "viewer interno" (fase 3). Não dizia em lugar nenhum que **nenhuma** via de export de mídia existe — que é a propriedade de fato entregue e a que a auditoria verificou. Sem isso, um leitor podia supor que "sem galeria" ainda admitia um `ACTION_SEND` |
| 2 | 9.3 registra o encaminhamento interno: toque longo, seletor de destinos, etiqueta "Encaminhada" no destino, sem chat de origem nem contador | seção 9 | comportamento de produto inteiro que o SPEC não previa; e a decisão de **não** carregar origem/autor/contador é uma escolha de metadado que pertence ao SPEC, não só ao código |
| 3 | Envelope: wire version **1 → 2**, com o bit `forwarded` como último byte do corpo de `Text`/`Attachment` e regra de retrocompatibilidade (ausente = `false`) | 10.1 | a versão do envelope estava documentada como fixa; um auditor que lesse `version = u16 1` e encontrasse `2` no fio concluiria que o documento está errado. Os outros oito tipos não mudaram de layout, e isso precisa estar dito explicitamente |
| 4 | `messages` ganha a coluna `forwarded`; esquema do vault passa a **v2** por `ALTER TABLE` | seção 10 | a tabela do SPEC listava seis colunas e o banco passou a ter sete; a coluna existe para a lista de conversa não decodificar cada envelope |
| 5 | 9.3 passa a dizer que a etiqueta "Encaminhada" **só** aparece quando o destino é uma conversa diferente da de origem; reenviar para o mesmo chat não marca `forwarded` | seção 9.3 | a redação anterior dava a entender que toda cópia sai etiquetada. Reenviar dentro do próprio chat é indistinguível de digitar de novo, e a etiqueta ali afirmaria uma proveniência falsa. É regra de produto observável pelo destinatário, logo pertence ao SPEC — o formato do bit no fio e a coluna do banco não mudaram |

**Vantagem:** as duas propriedades que um usuário sob risco mais precisa entender — "o que eu recebo não vaza para outro app" e "o que eu encaminho não denuncia de onde veio" — passam a estar escritas onde a auditoria externa (T6.2) as procura, com o alcance exato de cada uma e sem prometer mais do que o código faz.

**Não resolvido aqui:** a validação de ponta a ponta em dois emuladores (envio, recebimento e etiqueta no destino) **ainda está em andamento** e não é reivindicada por esta edição; os checkboxes de T4.11/T4.12 no roteiro de release só mudam quando essa medição fechar. A limitação de migração do cofre (`open()` exige versão exata e não migra um cofre já aberto; só `initialize()` roda `migrate`) está registrada em `docs/security-model.md` e em `docs/development/runtime-catalog.md` §7.

### 2026-09-14 — alinhamento com o que foi construído (tarefa T5.1 do roteiro de release)

Este arquivo era o desenho aprovado de 2026-09-13 e não tinha sido reconciliado com o código. As mudanças abaixo trocam promessa por fato verificado no fonte. Nenhuma decisão travada da seção 16 foi reaberta.

| # | O que mudou | Onde | Por quê |
|---|---|---|---|
| 1 | Licença: GPLv3 → **AGPL-3.0-or-later** | cabeçalho | `libsignal-client` é AGPL e é dependência direta do ratchet; o `LICENSE` do repositório já é AGPL-3.0 |
| 2 | Handshake 1:1: X3DH → **PQXDH (X25519 + Kyber-1024)**, com nova subseção de justificativa | seções 0, 6, 8.1, 12 (item 13), 15 | `libsignal-client` 0.102.2 só aceita `PreKeyBundle` híbrido com prekey KEM assinada. A alternativa seria reimplementar X3DH e abandonar o ratchet oficial, o que a seção 3 proíbe |
| 3 | A linha "opcional v1.1: X25519+Kyber768" virou "PQ já na v1, Kyber-**1024**" | seção 0 | o código usa `KEMKeyType.KYBER_1024`, não 768, e já na v1 |
| 4 | Nova **7.1**: buckets reais, `MAX_FRAME` = 16.388 B, `WirePacket` de 5 bytes, teto de 16 MiB | seção 7 | o SPEC só dizia "buckets 256..16384"; o enquadramento construído tem mais estrutura e precisa ser auditável |
| 5 | Nova **7.2**: máquina de estados `OFF → STARTING → PUBLISHING → ONLINE`, com `RETRYING` em backoff e `ERROR` | seção 7 | `PUBLISHING` separa "onion lançado" de "descritor publicado no HSDir"; sem ele a UI diz ONLINE antes de ser alcançável. Registrado também o estado real: nativo (`await_ready`/`status()`, teto de 300 s) **e** Kotlin (enum, controller, banner e strings PT/EN) já entregues por T2.1; a única lacuna é `RETRYING`, que existe no enum mas não tem emissor (T2.3) |
| 6 | Nova **7.3**: `TorStateSnapshot` — keystore Arti efêmero + checkpoint cifrado de guards em `meta.tor_guard_state` | seção 7 | o SPEC dizia apenas "chaves Tor saem da RAM"; não explicava como os guards sobrevivem ao lock sem vazar para fora do vault. É pré-requisito do gate 10 |
| 7 | Nova **8.3**: retenção de **2 épocas** MLS e a perda de mensagem que ela implica | seção 8 | `PastEpochDeletionPolicy::MaxEpochs(2)` é escolha deliberada de FS sobre entrega; o usuário pode perder mensagem atrasada e isso não pode ficar só no código |
| 8 | Nova **8.4**: grupo pendente até os KeyPackages, transferência de coordenação na saída do coordenador | seção 8 | comportamento inteiro que o SPEC não previa, decorrente de não haver Delivery Service |
| 9 | Nova **9.1**: contador de não lidas **local**, sem recibo de leitura na rede | seção 9 | recibo de leitura criaria metadado novo (quando você abriu a conversa) em troca de conveniência; contraria a seção 11 |
| 10 | Nova **9.2**: política de crescimento de mídia real/decoy marcada como **em definição** | seções 5.2 e 9 | `alignAllocations` só iguala os vaults no setup; anexos reais crescem sozinhos. A decisão depende da medição do gate 2 em aparelho e **não foi tomada** — o problema está descrito, a solução não foi inventada |
| 11 | Seção 10 reescrita com o schema real (incluindo `opaque_blobs`, `wire_blobs`, `chat_groups`, `storage_reserve`) | seção 10 | as tabelas do SPEC eram um esboço; `outbox` referencia digest, não frame |
| 12 | Nova **10.1**: tabela de tipos de envelope com tags de wire, incluindo `ControlRejected` (tag **11**, tag 10 reservada) e as quatro razões de recusa | seção 10 | sem servidor, um controle impossível retentaria para sempre; a recusa autenticada é parte do protocolo e precisa estar especificada |
| 13 | Nova **10.2**: lanes de outbox, concorrência de 4 destinos, ACK de 60 s e cursores de trial 64/8 | seção 10 | lane é o que impede que uma recusa de quota num grupo bloqueie chats não relacionados |
| 14 | Nova **10.3**: limites duros — 1.024 contatos, 128 grupos ativos, 16.384 arestas, 4 KeyPackages por par / 128 globais / TTL 24 h, snapshot Tor de 4 MiB | seção 10 | todos esses números existem no código e nenhum estava no SPEC; são o que um auditor precisa para avaliar DoS e esgotamento de recurso |
| 15 | Parâmetros Argon2id reais (64 MiB..256 MiB, t 1..20, p=1) acrescentados ao checklist | seção 15 | o SPEC dava piso e alvo; faltava o teto e a ordem de calibração |
| 16 | Tabela de metadados ampliada: precisão real do padding, **disponibilidade do onion** e **volume de mídia acumulado** | seção 11 | 7.1 e 9.2 introduzem vazamentos que a tabela de metadados não listava; deixá-los só no corpo do texto esconderia do leitor que vai direto à seção 11 |
| 17 | Gate 2 dos testes de ameaça agora aponta para 9.2 e exige medir também os diretórios de anexo | seção 12 | comparar só `real.db` × `decoy.db` daria verde num aparelho cuja mídia real já denuncia o uso; o gate precisa medir o que a promessa afirma |
| 18 | **Correção factual:** a nota "Camada Kotlin: ainda não" de 7.2 foi reescrita — o enum, o controller, o banner e as strings PT/EN de `PUBLISHING` já existem | 7.2, changelog item 5, "Continua em aberto" | T2.1 aterrissou enquanto esta seção era escrita e a nota nasceu obsoleta: afirmava como fato (`UiContract.kt` sem `PUBLISHING`, ONLINE assim que `start` retorna) o oposto do fonte. Um SPEC que descreve errado o estado parcial induz retrabalho |
| 19 | **Correção factual:** `pairing_consumed`, `lock_timeout`, `bridges`, `display_name` e `outbox_sequence` saíram da lista de namespaces de `opaque_blobs` e foram para a tabela `meta` | seções 10 e 10.3 | são gravados por `putMeta`/`getMeta`, não `putBlob`; a própria seção 10 apresenta as duas tabelas como distintas, então a lista se contradizia e mandava o auditor procurar registros onde eles não estão |
| 20 | **Correção factual:** o limiar de fragmentação virou **16.368 B** e 7.1 ganhou o cabeçalho de fragmento de 16 bytes | 7.1 e tabela da seção 11 | 16.388 B é o frame no fio (bucket 16.384 + prefixo de 4), não a capacidade de conteúdo. `Frames.kt:11-12` reserva `HEADER = 16`, então `CHUNK = 16.368`: 16.369 B de ciphertext já viram dois frames |
| 21 | Nova linha de metadado "qual vault foi aberto" + parágrafo em 7.3 sobre o checkpoint ser **por vault**, e novo gate **10b** | 7.3, seção 11, seção 12 | `DecoyFactory` não semeia `tor_guard_state`, então a senha de pânico produz guards novos e diferentes do vault real — distinguidor real/decoy observável na rede que nem 7.3 nem a seção 11 registravam. O gate 10 só media sockets e não cobria o requisito de restauração que a 7.3 exige |
| 22 | 8.3 e 10.3 ganharam `SenderRatchetConfiguration::new(32, 1024)` | 8.3 e 10.3 | citar só `MaxEpochs(2)` mostrava metade do tradeoff de forward secrecy configurado na mesma linha do fonte: 32 chaves fora de ordem retidas por remetente adiam o benefício de FS |

**Vantagem geral:** o SPEC volta a ser fonte de verdade utilizável pela auditoria externa (T6.2). Antes, quem lesse o SPEC e o código encontraria divergências de protocolo (X3DH), de licença e de limites, e concluiria — com razão — que o documento não descrevia o produto. Agora cada divergência ou está corrigida, ou está marcada com o estado real da implementação e a tarefa que a fecha.

**Continua em aberto e deliberadamente não resolvido aqui:**

- **9.2 — política de crescimento de mídia real/decoy (T4.1).** As duas saídas estão descritas e a recomendação técnica está registrada, mas a decisão depende da medição do gate 2 em aparelho e **não foi tomada**. Nenhuma solução foi inventada para preencher o espaço.
- **7.2 — `RETRYING` (T2.3).** `PUBLISHING` foi fechado por T2.1 em todas as camadas (nativo, enum, controller, banner, strings PT/EN). O que resta é o laço de backoff: `RETRYING` está no enum e tem string traduzida, mas **nenhum emissor**.
- **7.3 — divergência de guards entre vault real e decoy.** O checkpoint `tor_guard_state` é por vault e o decoy não é semeado; a senha de pânico produz um conjunto de guards novo, observável na rede. Registrado na tabela da seção 11 e **sem decisão tomada**; nenhuma solução foi inventada aqui.
- **README.md:35 contradiz a 9.2.** O README ainda afirma que os dois cofres mantêm tamanhos iguais em uso ("reserva 256 MiB (512 MiB para os dois) para manter tamanhos iguais") e aponta para uma "reserva de mídia" descrita em `docs/security-model.md` que **não existe** naquele documento nem em `docs/development/storage-api.md`. A 9.2 proíbe essa afirmação fora do setup. Corrigir junto de T4.1/T5.4 — `README.md` não é escopo desta edição.
- **T5.2 — catálogo formal de `opaque_blobs`.** A seção 10.3 lista os catorze namespaces em uso e separa as chaves de `meta`; o formato, dono e ciclo de vida de cada um ficam em `docs/development/runtime-catalog.md`.

O SPEC descreve o alvo, cita o número que está no fonte e marca o que falta com a tarefa que fecha. Em nenhum ponto afirma que algo está pronto quando não está.
