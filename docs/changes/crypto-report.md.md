# docs/development/crypto-report.md

## 2026-09-14 — T4.3: seção "Known-answer vectors"

### Como era antes

A única declaração sobre vetores era um parágrafo na seção "Verification":

```markdown
The focused native suite covers a FIPS SHA-256 vector, an independently
generated keyed BLAKE2b-256 vector, an independently generated Argon2id vector
with exact `m=65536 KiB`, `t=2`, `p=1`, RFC 8032 Ed25519 vector 1, XChaCha round
trips, AAD and ciphertext tampering, key/signature/frame length rejection, KDF
bounds, random/key lengths, and allocation caps.
```

Ou seja: quatro vetores publicados, nenhuma origem rastreável para os casos de
borda, nenhum digest e nenhuma lista de casos pulados. Isso é exatamente o que o
gate 13 do checklist de release recusa como evidência.

Observação de honestidade: o arquivo já chegou a esta rodada com uma edição não
commitada de outra tarefa (T5.3), que reescreveu os três trechos sobre caminhos
`/tmp/nomessages-*-target`. Essa edição foi conferida, está correta e foi
**mantida**; nada nela foi duplicado. O antes/depois dela está registrado na
seção "T5.3" abaixo.

### Como ficou

Nova seção final `## Known-answer vectors (2026-09-14)` com:

1. **Corpus** — tabela com os quatro arquivos Wycheproof, a URL exata de origem
   (`raw.githubusercontent.com/C2SP/wycheproof/master/testvectors_v1/...`), o
   SHA-256 de cada JSON e a contagem de casos (325 + 315 + 518 + 151 = 1.309).
2. **Mapeamento para os símbolos exportados** — tabela que liga cada vetor à
   função realmente exercitada (`crypto::open`, `crypto::verify`,
   `chacha20poly1305_ietf`, `curve25519::scalarmult`), com a justificativa
   quando o vetor não passa por um símbolo de `crypto.rs`.
3. **Tratamento de `result` e flags** — `valid` aceito, `invalid` recusado,
   `acceptable` apenas contado; nenhuma flag usada para isentar caso.
4. **Contagens executadas** por arquivo, separando quantos casos foram recusados
   por contrato de tamanho.
5. **Pulados ou não aplicáveis, com motivo** — RFC 9106 §5.3 (inalcançável pelo
   libsodium), `eddsa_test.json` (404 em `testvectors_v1`; virou
   `ed25519_test.json`), direção de assinatura do Ed25519 no Wycheproof
   (arquivo é só de verificação), X25519 (não exportado pela API), os 9 + 9
   casos de nonce malformado, e o lembrete de que libsignal, RFC 9420/OpenMLS e
   PQXDH continuam fora deste corpus.
6. **Comando e resultado** — a linha de comando exata e o placar da execução.

### Por que a mudança foi feita

O critério "Feito quando" da T4.3 exige que o relatório liste **cada vetor
executado e cada caso pulado com motivo**. Sem origem e digest, um auditor não
consegue repetir a verificação; sem a lista de pulados, a suíte parece completa
quando não é.

### Vantagens

- Um auditor externo baixa os quatro arquivos pelas URLs registradas, confere o
  SHA-256 e reproduz o mesmo placar — a evidência deixa de depender de confiança.
- Fica explícito onde o produto **não** tem primitiva própria (X25519,
  ChaCha20-Poly1305 IETF) e contra o que os vetores rodaram nesses casos, em vez
  de sugerir uma cobertura que não existe.
- A incompatibilidade com o RFC 9106 §5.3 fica documentada como decisão de
  projeto (libsodium fixa `p=1`), não como esquecimento.

### Não verificado

- O texto da seção está em inglês para acompanhar o restante do arquivo, que é
  todo em inglês, assim como `release-checklist.md`. A documentação em Português
  fica nesta pasta `docs/changes/`.
- O corpus não tem tag upstream fixada; a estabilidade vem do SHA-256 checado em
  tempo de teste.

## 2026-09-14 — revisão de correções: proveniência dos artefatos e ordem do aviso histórico

Dois achados de revisão sobre a edição de T5.3 neste arquivo. A "Observação de
honestidade" da seção acima diz que a troca de `/tmp/nomessages-*-target` por
`native/target/debug/` "está correta e foi mantida"; **isso vale para as linhas 31 e 45,
mas não valia para as linhas 58-68**, e esta seção registra a correção.

### 1. O SHA-256 do log estava sendo vinculado ao binário errado (P2)

**Como era antes** (`crypto-report.md:58-68`):

```markdown
That crypto-only host JNI library is produced at `native/target/debug/libnomessages.so`;
the `/tmp/nomessages-libsodium122-target/debug` path recorded above was a `CARGO_TARGET_DIR`
override on the previous host and no longer exists.
...
It has since been superseded by the full Tor/MLS/crypto host library, likewise built at
`native/target/debug/libnomessages.so` (preserved on the previous host under a `/tmp`
staging directory that is gone); its SHA-256 is recorded in
`build-logs/native-host-sha256.log`.
```

O arquivo de evidência diz outra coisa. `build-logs/native-host-sha256.log` tem uma
única linha:

```text
1004f7e46343291a9fb47005bfd64c971944405890b43a1983cac5201b2f8c56  /tmp/nomessages-host-runtime/libnomessages.so
```

E os logs de build mostram que nenhuma das duas bibliotecas foi produzida em
`native/target/debug`: `build-logs/native-crypto-bundled.log:74` roda o binário de teste
a partir de `/tmp/nomessages-libsodium122-target/debug/deps/...`, e
`build-logs/native-full-host-j2.log:389` mostra a build completa no mesmo diretório
sobrescrito. O texto passava de "foi" para "é construído em", e com isso amarrava um
hash conferido a um binário que ninguém hasheou.

**Como ficou:** um bloco "Provenance of those two artifacts" que separa duas coisas que
estavam misturadas — **onde o artefato foi produzido** (fato do log, com o log citado
por linha) e **onde uma build padrão o produz** (`native/target/debug/libnomessages.so`) —
seguido de um aviso em negrito de que o hash `1004f7e4…` pertence ao binário staged em
`/tmp/nomessages-host-runtime/`, que não existe mais, **não se aplica** a nenhuma build
local, e precisa ser regerado e re-hasheado antes de virar evidência.

**Por que a mudança foi feita:** o gate T5.5 vai consumir exatamente esses três campos
("SHA-256 do artefato, commit, caminho da evidência"). Um relatório que aponta um hash
verificado para um caminho que o próprio arquivo de evidência contradiz corrompe a
cadeia de proveniência na única etapa em que ela é conferida. Pior: o erro é silencioso
— quem recompilar localmente vai obter um `.so` com outro digest e não tem como saber se
a divergência é uma regressão ou só a troca de host.

**Vantagens:** o relatório volta a concordar com os logs que cita; fica explícito que há
uma medição pendente (rebuild + re-hash) em vez de uma evidência aparentemente pronta; e
o registro histórico dos `/tmp` é preservado como registro, não como instrução.

### 2. Bloco de comandos histórico apresentado como instrução ativa (P3)

**Como era antes:** a linha `From the repository root:` introduzia o bloco `sh` com
`CARGO_TARGET_DIR=/tmp/nomessages-libsodium122-target …` e
`NOMESSAGES_NATIVE_DIR=/tmp/nomessages-libsodium122-target/debug ./gradlew :core:test`, e só
**depois** do bloco vinha a ressalva de que aqueles diretórios não existem mais.

**Como ficou:** a ressalva virou um bloco de citação `> **Historical toolchain note
(2026-09-14).**` inserido **antes** do bloco de comandos, e a linha introdutória passou a
ser `From the repository root, as executed on that host:`. Os comandos ficaram intactos.

**Por que a mudança foi feita:** é o mesmo padrão já aplicado com sucesso em
`message-report.md:9-16`, onde a nota histórica foi posta acima da evidência justamente
para que ninguém copie o comando obsoleto. Aqui a ordem estava invertida, e quem copiasse
o bloco apontaria `NOMESSAGES_NATIVE_DIR` para um diretório que o próprio documento declara
inexistente — o Gradle não acharia a `.so` e o erro pareceria um problema de build.

**Não verificado:** nenhuma build foi executada nesta revisão. A afirmação "uma build
padrão produz `native/target/debug/libnomessages.so`" vem do comportamento padrão do Cargo,
não de uma compilação observada nesta máquina; e o binário staged em
`/tmp/nomessages-host-runtime/` não pôde ser inspecionado porque o host de origem não existe
mais — só a linha de digest sobreviveu.

---

## 2026-09-14 — T5.3: caminhos `/tmp` obsoletos deixam de ser instrução

Esta seção documenta a edição que a T5.3 deixou no mesmo arquivo e que, até a
revisão, não tinha registro próprio em `docs/changes/`. Ela **não** é repetida
na seção T4.3 acima, que só a menciona de passagem.

### Como era antes

Três trechos apresentavam caminhos de uma máquina que não existe mais como se
fossem o procedimento corrente:

```markdown
`cargo build --no-default-features` completed with exit status 0 and produced
`/tmp/nomessages-system-target/debug/libnomessages.so`.

From the repository root:

```sh
CARGO_TARGET_DIR=/tmp/nomessages-libsodium122-target CARGO_BUILD_JOBS=1 \
...
```

That crypto-only host JNI library was `/tmp/nomessages-libsodium122-target/debug/libnomessages.so`.
... It has since been superseded by the full Tor/MLS/crypto host library preserved at
`/tmp/nomessages-host-runtime/libnomessages.so`; its SHA-256 is recorded in
`build-logs/native-host-sha256.log`.
```

O verbo "preserved at" e o "From the repository root:" sem ressalva convidavam o
leitor a reexecutar o bloco e a reaproveitar o SHA-256 como evidência de gate.

### Como ficou

1. A primeira menção passa a nomear o artefato (`libnomessages.so`) e a explicar
   entre parênteses que `/tmp/nomessages-system-target` era um `CARGO_TARGET_DIR`
   redirecionado **da máquina anterior, que não existe mais**, sendo
   `native/target/debug/libnomessages.so` a saída padrão.
2. O bloco de comandos ganhou um aviso em citação acima dele — *"Historical
   toolchain note (2026-09-14) … **do not re-run it**"* — com a invocação
   corrente ao lado (`cargo test --manifest-path native/Cargo.toml --locked
   --no-default-features crypto::tests` + `./gradlew :core:test`), e o cabeçalho
   virou "From the repository root, **as executed on that host**".
3. A terceira menção virou uma lista de proveniência que separa *onde o log diz
   que o artefato foi escrito* de *onde um build padrão o colocaria*, e ganhou o
   parágrafo em negrito: o SHA-256 `1004f7e4…` pertence ao binário encenado em
   `/tmp`, que não existe mais, **não se aplica** a nenhum
   `native/target/debug/libnomessages.so` local e **não pode ser reaproveitado como
   evidência de gate** — para a T5.5 a biblioteca tem de ser reconstruída e
   re-hasheada aqui.

### Por que a mudança foi feita

Documentação de release é lida como procedimento. Um caminho `/tmp` de outra
máquina, apresentado sem ressalva, produz dois erros caros: um build que grava
num diretório que o Gradle não procura, e — pior — a reutilização de um digest
que não corresponde a nenhum binário auditável.

### Vantagens

- O registro histórico é preservado integralmente (o log continua sendo a fonte),
  mas deixa de ser confundido com instrução.
- O digest fica explicitamente marcado como não reutilizável, fechando o caminho
  mais provável para uma evidência de gate falsa.
- Quem for executar a verificação hoje tem o comando correto na mesma tela.

### Não verificado

- O conteúdo de `build-logs/native-host-sha256.log` e das linhas citadas
  (`native-crypto-bundled.log:74`, `native-full-host-j2.log:389`) foi tomado como
  está nos logs; os binários originais não existem mais e não foram reconstruídos
  nesta rodada.

---

## 2026-09-14 — Revisão: correções de exatidão na seção "Known-answer vectors"

A revisão encontrou cinco afirmações do relatório que não correspondiam aos
fatos ou ao código. Todas foram corrigidas.

### 1. Cobertura do X25519 afirmada para caminhos que não a usam (P2)

**Antes:** *"The vectors cover the same libsodium build that backs the MLS
ciphersuite key exchange and the Tor handshake."*

**Verificação:** `grep -rn 'scalarmult\|curve25519' native/src/` não retorna
nenhuma ocorrência. O MLS usa `openmls_rust_crypto` (x25519-dalek) e o Arti usa
`tor-llcrypto` (curve25519-dalek).

**Depois:** a linha da tabela passa a dizer que os vetores validam **apenas** a
build de libsodium embutida e remete a uma seção nova, *"Scope limit of the
X25519 corpus (read before citing it)"*, que nomeia os dois provedores reais com
arquivo e linha e declara que a cobertura equivalente para eles **falta**. A
lista de "Skipped or not applicable" ganhou um item dizendo que essa é uma lacuna
real do gate 13, não um "não aplicável".

**Vantagem:** os 518 casos eram citados no checklist como evidência parcial do
gate para caminhos que não os executam. Agora o leitor sabe o que está e o que
não está coberto.

### 2. Licença do Wycheproof não constava (P2)

**Antes:** a tabela "Corpus" trazia origem, SHA-256 e contagem, sem nenhuma
menção a licença.

**Depois:** parágrafo antes da tabela declarando que os quatro arquivos são
redistribuídos verbatim do C2SP/wycheproof sob **Apache-2.0**, que são dados de
terceiros vendorizados num repositório AGPL-3.0, e que a linha correspondente em
`THIRD_PARTY_NOTICES.md` **ainda falta em 2026-09-14** e precisa entrar antes do
release. Esse arquivo não pertence a esta área; a pendência foi reportada.

**Vantagem:** 854.066 bytes de terceiros deixam de circular sem aviso de licença.

### 3. Contagem "1.309 + 2" contava duas vezes (P3)

**Antes:** *"Total: 1,309 Wycheproof cases plus the 2 specification vectors
below."*

**Verificação:** o `tcId 1` do `chacha20_poly1305_test.json` tem
`comment: "RFC 7539"` e a mesma chave/iv/aad do vetor codificado em `kat.rs`; o
`tcId 1` do arquivo XChaCha tem `comment: "draft-arciszewski-xchacha-02"`, idem.
São os mesmos casos.

**Depois:** o total passa a ser **1.309**, com a explicação de que os dois vetores
de especificação são aqueles `tcId 1`, reexecutados explicitamente na direção de
cifragem e pelo quadro exportado `crypto::open` — coisas que o laço do Wycheproof
não faz por eles.

### 4. "No third Argon2id implementation was available" era mais forte que os fatos (P3)

**Verificação no WSL:** `openssl version` = 3.0.13 e sem KDF Argon2 (correto);
`command -v argon2` e `python3 -c "import argon2"` falham (correto). Mas
`apt-cache policy argon2` retorna `Candidate: 0~20190702+dfsg-4build1` — a CLI de
referência PHC está empacotada na distribuição.

**Depois:** o item virou um bullet próprio, "Third-party verification of the
Argon2id `p=1` vector: still missing", que diz "nada estava instalado **e nenhum
pacote foi adicionado para esta tarefa**", registra a disponibilidade da CLI de
referência, admite que o digest `3fde0023…` foi gerado com a mesma libsodium que
ele valida (auto-consistência, não confirmação independente) e anota o obstáculo
prático: o sal atual são os bytes `0x00..0x0f`, que a CLI não aceita na linha de
comando, então um vetor cruzado precisa de sal ASCII de 16 bytes.

Na seção "Verification", a expressão "an independently generated Argon2id vector"
virou "a self-generated Argon2id vector (not third-party verified; see the caveat
under *Known-answer vectors*)", para o arquivo não se contradizer.

### 5. Referências desatualizadas pelo remanejo do teste e pelos contadores novos

- A menção a `crypto::tests::argon2id_rejects_the_rfc9106_section_5_3_profile`
  virou a descrição do que de fato existe: a primeira asserção de
  `argon2id_rejects_parameters_outside_the_contract`, com
  `assert!(MIN_MEMORY_KIB > 32)`; e o texto passa a separar o que é testável (a
  memória) do que é apenas fato de API documentado (`p=4`, segredo, AD).
- "Result and flag handling" ganhou o parágrafo sobre `invalid` exigir recusa
  **outright** e sobre o contador `mismatched`.
- As contagens por arquivo ganham "0 mismatched" e uma frase dizendo que todo
  número da seção é asseverado pelo teste, não apenas impresso.
- A contagem do Ed25519 passa a explicar que `crypto::verify` tem três causas de
  `InvalidArgument` e que o teste decide a causa esperada pelo próprio vetor.
- "Command and result" passa de 18 para 17 testes unitários, com a explicação do
  porquê, e ganha o placar impresso da execução real.

### Não verificado

- A linha de `THIRD_PARTY_NOTICES.md` não foi escrita: o arquivo não pertence a
  esta área. O relatório declara a pendência em vez de fingir que ela foi
  resolvida.
- Nenhum vetor Argon2id de terceiros foi gerado; a CLI de referência não foi
  instalada (a rodada proíbe alterar o ambiente por conta própria).
- Nenhum vetor X25519 foi rodado contra `openmls_rust_crypto` ou `tor-llcrypto`;
  isso é trabalho de outra tarefa e está registrado como falta.
