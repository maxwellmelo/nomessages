# Mudanças em `README.md`

## 2026-09-14 — T5.4: seção Windows (WSL2) e estado real

### Como era antes

O parágrafo de estado era uma linha genérica, sem número de commits, sem
situação da toolchain, sem o bloqueio do Tor e sem dizer que os gates não foram
executados:

```markdown
**Estado:** implementação em validação. A existência de código e testes não significa que os gates de segurança foram aprovados. Os resultados executados ficam em [docs/development](docs/development); a homologação é acompanhada no [checklist de release](docs/release-checklist.md).
```

A seção "Compilar e testar" começava assim, citando apenas "Linux" como
requisito, sem nenhuma instrução para quem trabalha no Windows:

```markdown
Requisitos: Linux, JDK 21, Android SDK, Rust 1.91+ e toolchain Android NDK para as bibliotecas nativas. ...
```

Não havia nenhuma subseção sobre WSL. Um desenvolvedor em Windows descobria
sozinho, depois de falhar, que `scripts/bootstrap-tools.sh` só baixa binários
Linux x64 e que o build em `/mnt/<letra>` é lento.

### Como ficou

1. **Parágrafo "Estado"** virou `**Estado (2026-09-14):**` com cinco marcadores
   verificáveis: código em `develop` em cinco commits temáticos sobre o commit
   inicial de contratos; toolchain validada em host Linux anterior e em
   reprodução no WSL Ubuntu 24.04; testes JVM/Rust e APK debug pendentes
   de reprodução nesta máquina; **Tor ao vivo em correção (T2.x)**, com a causa
   já identificada (`start` declarava `READY` antes da publicação do descritor);
   **13 gates NOT_RUN**. Acrescentado link para o roteiro
   `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.

2. **Requisitos** ganharam uma frase final apontando para a nova subseção:
   "Em Windows, nada compila nativamente: use o WSL2 conforme a subseção
   Windows (WSL2) abaixo."

3. **Nova subseção `### Windows (WSL2)`**, ao final de "Compilar e testar",
   com os passos reais de T1.1 do roteiro:
   - por que o Windows não serve (binários Linux x64 no bootstrap; dependência
     de `bash`, `/proc`, `du`, `flock`, `setsid`);
   - `sudo apt install -y build-essential clang cmake pkg-config python3 unzip zip curl git`,
     com a justificativa de cada peça crítica (`clang` para o C de libsodium,
     `tomllib` para `scripts/tor-delivery-probe.sh`);
   - `rustup` com toolchain `1.91` e
     `rustup target add aarch64-linux-android x86_64-linux-android`;
   - verificação rápida (`rustc --version`, `clang --version`,
     `python3 -c 'import tomllib'`);
   - recomendação de clonar em `~/nomessages`, dentro do sistema de arquivos do
     WSL, por causa da I/O de arquivos pequenos em `/mnt/<letra>` (drvfs/9p);
   - alternativa para quem mantém as fontes no disco do Windows: exportar
     `NOMESSAGES_TOOLS_DIR` e `CARGO_TARGET_DIR` para caminhos no disco do WSL,
     com a explicação do efeito em cascata e o aviso de não apontá-las para
     `/mnt/...`.

O bloco de comandos existente de "Compilar e testar" (linhas
`scripts/build-android.sh --bootstrap` etc.) **não foi tocado**: pertence a
outro agente.

### Nomes de variáveis conferidos no código

Antes de citar qualquer variável, os scripts foram lidos. Todas existem:

| Variável | Onde é lida | Efeito |
|---|---|---|
| `NOMESSAGES_TOOLS_DIR` | `scripts/bootstrap-tools.sh`, `scripts/build-android.sh` (`TOOLS_DIR="${NOMESSAGES_TOOLS_DIR:-$PROJECT_DIR/.tools/toolchains}"`), `scripts/verify-apk.py`, `scripts/tor-delivery-probe.sh`, `scripts/guard-build.py` | Substitui `.tools/toolchains`; dele derivam, nas linhas imediatamente seguintes de `build-android.sh`, `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, `ANDROID_USER_HOME` e `TMPDIR` |
| `CARGO_TARGET_DIR` | `scripts/build-android.sh` (`export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$PROJECT_DIR/native/target}"`), `scripts/tor-delivery-probe.sh`, `scripts/guard-build.py` | Substitui `native/target` |
| `NOMESSAGES_NATIVE_DIR` | `scripts/build-android.sh` (`export NOMESSAGES_NATIVE_DIR="${NOMESSAGES_NATIVE_DIR:-$CARGO_TARGET_DIR/debug}"`), `scripts/tor-delivery-probe.sh` | Derivada de `CARGO_TARGET_DIR/debug`; não precisou ser citada no README |

Nenhuma variável foi inventada e nenhuma citada deixou de existir.

A tabela citava números de linha (`build-android.sh:8`, `:9-14`, `:15`, `:16`)
até a revisão de 2026-09-14. Eles foram trocados pelo texto da própria
atribuição porque `scripts/build-android.sh` ganhou, na mesma entrega, o
invólucro de guarda (`NOMESSAGES_BUILD_GUARDED`/`NOMESSAGES_GUARD_ARGS`) antes dessas
linhas e deslocou tudo em oito posições. Um ponteiro que envelhece a cada
inserção anula o propósito da tabela, que é permitir a conferência.

### Vantagens

- Quem clona o projeto em Windows tem o caminho completo e testável até o
  ponto em que o build é possível, em vez de descobrir por tentativa e erro.
- O aviso sobre `/mnt/<letra>` e sobre `TMPDIR` derivado de
  `NOMESSAGES_TOOLS_DIR` previne exatamente a falha já registrada no
  `build-report.md` (caches do Gradle apagados debaixo do build).
- O "Estado" deixa de ser uma frase que envelhece sem aviso e passa a dizer o
  que falta: Tor ao vivo e 13 gates. Isso reduz o risco de alguém ler
  "implementação em validação" como "pronto para dados sensíveis".

### Por que a mudança foi feita

T5.4 do roteiro `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`:
"README: seção Windows/WSL e estado real". O ambiente de desenvolvimento
mudou para Windows + WSL2 e o README ainda descrevia apenas o host Linux
anterior.

---

## 2026-09-14 — T6.1/T4.4: bloco de comandos de "Compilar e testar"

Edição cirúrgica: **apenas** o bloco ` ```bash ` da seção "Compilar e testar"
(as demais partes do README pertencem a outra tarefa e não foram tocadas).

### Como era antes

```bash
# Prepara JDK/SDK/Gradle em diretório próprio e inicia a compilação.
scripts/build-android.sh --bootstrap

# Núcleo Kotlin/JVM. Requer a biblioteca JNI host no diretório configurado.
scripts/build-android.sh --core-only --with-native-host

# Suíte nativa isolada, quando precisar validar alterações nativas.
cargo test --manifest-path native/Cargo.toml --locked --jobs 1
```

### Como ficou

Acrescentados quatro exemplos e uma descrição mais completa do modo padrão:

```bash
# Prepara JDK/SDK/Gradle em diretório próprio e executa tudo de ponta a ponta:
# nativo host, nativo Android (arm64-v8a e x86_64), :core, :app, a suíte Python
# de regressão da fila de saída, os dois APKs, o lint e a inspeção do APK.
scripts/build-android.sh --bootstrap
...
# Etapas isoladas, exatamente como o CI as executa (.github/workflows/android.yml).
scripts/build-android.sh --bootstrap --skip-gradle --no-native-host \
    --with-native-android --abi arm64-v8a
scripts/build-android.sh --bootstrap --app-only --skip-native --with-ndk

# Paralelismo do Cargo: o padrão passou a ser o número de núcleos (nproc).
# Use --jobs, ou a variável CARGO_BUILD_JOBS, para limitar máquinas pequenas.
scripts/build-android.sh --core-only --with-native-host --jobs 1

# NOMESSAGES_GUARD_ARGS repassa opções a scripts/guard-build.py sem editar scripts
# (orçamento de cache e piso de espaço livre; ver o parágrafo abaixo).
NOMESSAGES_GUARD_ARGS="--max-gib 24 --min-free-gib 6" scripts/build-android.sh --bootstrap

# Lista completa de opções.
scripts/build-android.sh --help
```

### Vantagens

- O README deixa de descrever uma interface que não existe mais. O script
  passou a aceitar `--app-only`, `--skip-gradle`, `--no-native-host`,
  `--no-native-android`, `--with-ndk`, `--abi` e `--jobs`, e o padrão do
  paralelismo do Cargo mudou de 1 para `nproc`: quem seguisse o texto antigo
  não teria como reproduzir localmente o que o CI faz.
- Os dois comandos "como o CI as executa" são literalmente as linhas usadas em
  `.github/workflows/android.yml`. Reproduzir uma falha de CI na máquina local
  vira copiar e colar, não leitura de YAML.
- `--jobs 1` aparece documentado como a saída para máquinas pequenas, já que o
  padrão deixou de ser conservador.
- `--help` passa a ser mencionado, e agora existe de verdade: `usage()` lista
  todas as opções e as variáveis de ambiente.

### Por que a mudança foi feita

A instrução da tarefa autoriza mexer no README "APENAS o bloco de comandos de
'Compilar e testar' se a interface do script mudar". A interface mudou em duas
frentes (opções novas em T6.1 e mudança de padrão do paralelismo), então o
bloco foi atualizado e nada mais.

### Não verificado

Nenhum dos comandos do bloco foi executado de verdade nesta máquina: não há
JDK/SDK instalados. O que foi exercitado foi o roteamento de cada linha de
comando dentro do script, contra stubs (ver `docs/changes/build-android.sh.md`).

---

## 2026-09-14 — Revisão: correções de fato e de supply chain no README

Segunda passagem sobre o mesmo arquivo, conferindo cada afirmação contra a
árvore de trabalho em vez de contra a memória da primeira rodada. Seis
correções, todas fora do bloco de comandos (que pertence à seção anterior,
T6.1/T4.4, e não foi tocado aqui).

### 1. Estado do Tor: `ONLINE` não existe, `PUBLISHING` já existe

**Antes** (`README.md`, marcador "Tor ao vivo"):

```markdown
- **Tor ao vivo em correção (T2.x do roteiro):** a entrega onion de ponta a ponta falhou porque `start` declara ONLINE antes da publicação do descritor do serviço. Reconexão com retry e o estado intermediário `PUBLISHING` ainda não existem.
```

Duas afirmações erradas contra a própria árvore entregue:

- **Nunca houve um estado `ONLINE` no transporte nativo.** `state_name` em
  `native/src/tor.rs` retorna `BOOTSTRAPPING`, `READY`, `PUBLISHING` e
  `STOPPED`; `grep -n ONLINE native/src/tor.rs` não retorna nada. Em
  `git show HEAD:native/src/tor.rs` o defeito era `state.store(2, ...)` — isto
  é, `READY` — antes de qualquer confirmação de publicação do descritor. Quem
  procurasse `ONLINE` no código nativo não encontraria o símbolo. (`ONLINE`
  existe, sim, mas no enum de UI `NetworkStatus`, que é outra camada.)
- **`PUBLISHING` já existe.** `native/src/tor.rs` tem
  `const STATE_PUBLISHING: u8 = 3;`, a transição
  `transport.state.store(STATE_PUBLISHING, Ordering::Release)`, o consumo de
  `service.status_events()` com
  `advance_state(&status_state, if reachable { STATE_READY } else { STATE_PUBLISHING })`
  e testes próprios. O estado já está exposto em `native/src/tor_jni.rs`, em
  `TorNative.kt`, no enum `NetworkStatus` (`UiContract.kt`) e no rótulo de
  `Components.kt`.

**Depois:**

```markdown
- **Tor ao vivo em correção (T2.x do roteiro):** a entrega onion de ponta a ponta falhava porque `start` declarava `READY` antes da publicação do descritor do serviço — o transporte nativo nunca teve um estado chamado `ONLINE`; seus nomes são `BOOTSTRAPPING`, `PUBLISHING`, `READY` e `STOPPED`. O estado intermediário `PUBLISHING` já existe na árvore de trabalho, em `native/src/tor.rs`, `TorNative` e `NetworkStatus`, **ainda sem validação em rede real**. A reconexão com retry continua ausente: `NetworkStatus.RETRYING` é só um rótulo de UI, sem nenhum produtor no runtime (T2.3).
```

A única metade da frase original que continua verdadeira é a reconexão com
retry: a busca por `RETRYING`, `backoff` e `reconnect` em `.kt` e `.rs` só
encontra a constante do enum e o `when` que a renderiza. Nada no runtime produz
esse estado, então o README passa a dizer exatamente isso, em vez de "não
existe".

### 2. Testes Python: não são do núcleo e nunca rodaram

**Antes:** "Testes JVM, Rust e Python do núcleo passaram no host anterior".

O ledger de `docs/development/build-report.md` tem linhas para *Core JVM
tests*, *App JVM tests* e *Native host crypto, Tor and MLS* — **nenhuma linha
Python**. O único teste Python do repositório é
`app/src/test/python/test_messaging_queue.py`, que fica em `app/`, não em
`core/`.

**Depois:** a frase perdeu o "Python do núcleo" e ganhou o estado real: o teste
pertence ao `app`, **já foi ligado** a `scripts/build-android.sh` na árvore de
trabalho (`python3 -m unittest discover` logo após `:app:testDebugUnitTest`, com
`set -o pipefail` abortando o build se falhar), mas **ainda não foi executado
nenhuma vez** e não consta do ledger.

Registro honesto: o achado que motivou esta correção dizia que o teste "sequer
está ligado ao build" e que T4.4 continuava aberta. Isso era verdade em `HEAD`;
na árvore atual o agente de `scripts/build-android.sh` já implementou a chamada.
O texto reflete a árvore, não o achado.

### 3. `sudo apt update` antes do `apt install`

**Antes:** `sudo apt install -y build-essential clang cmake pkg-config python3 unzip zip curl git`

Uma imagem Ubuntu recém-instalada no WSL chega com `/var/lib/apt/lists` vazio;
o `apt install` falha em "Unable to locate package" antes de baixar qualquer
coisa. A instrução falhava justamente para o público a que se destina — quem
acabou de instalar o WSL.

**Depois:** `sudo apt update && sudo apt install -y ...`, com uma frase
explicando por que o `update` não é opcional.

### 4. `libsodium-sys` → `libsodium-rs`/`libsodium-sys-stable`

**Antes:** "`clang` e `build-essential` são obrigatórios: `libsodium-sys`
compila C."

Não existe crate `libsodium-sys` no grafo. `native/Cargo.toml` declara
`libsodium-rs = { version = "=0.2.4", features = ["minimal"] }` e o
`native/Cargo.lock` registra `libsodium-rs` e `libsodium-sys-stable` — este
último é quem compila o C. Quem fosse conferir a exigência no lock não
encontraria o nome citado.

**Depois:** "`libsodium-rs` puxa `libsodium-sys-stable`, que compila libsodium
em C a partir do fonte vendorizado."

### 5. Rust instalado com verificação de digest, não `curl | sh`

**Antes:**

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup toolchain install 1.91
rustup default 1.91
rustup target add aarch64-linux-android x86_64-linux-android
```

Era o **único** componente de toolchain do repositório entrando na máquina sem
conferência de integridade — e justamente o compilador que produz a cripto
(libsodium/OpenMLS) e o transporte (Arti) auditados no gate T6.2. Em contraste,
`scripts/bootstrap-tools.sh` fixa digest de cada download (`JDK_SHA256`,
`CMDLINE_TOOLS_SHA1`, `PLATFORM_TOOLS_SHA1`, `NDK_SHA1`), exige `sha1sum` e
`sha256sum` entre os comandos obrigatórios e roda `sha256sum --check --status`.

**Depois:**

```bash
base=https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu
curl --proto '=https' --tlsv1.2 -fsSLO "$base/rustup-init"
curl --proto '=https' --tlsv1.2 -fsSL "$base/rustup-init.sha256" | sha256sum --check --status -
chmod +x rustup-init
./rustup-init -y --default-toolchain 1.91 --profile minimal
. "$HOME/.cargo/env"
rustup target add aarch64-linux-android x86_64-linux-android
```

Os comandos foram **executados de fato** no WSL Ubuntu antes de entrarem no
README: o download retornou 0, o `sha256sum --check --status -` retornou 0 e o
arquivo baixado tem 21.113.232 bytes, digest
`dda7234360b7f578ca8b0ddcb80145646fa61a67c1720a5abc7051b35c9fcb71`. O formato
publicado é `<digest> *./rustup-init`, aceito diretamente pelo `sha256sum -c`.

O README diz explicitamente o que essa verificação **não** garante: o `.sha256`
vem do mesmo servidor do binário, logo cobre download corrompido ou truncado,
não um servidor comprometido. A garantia forte exige fixar o digest no
repositório, como o bootstrap faz com o JDK — registrado como recomendação.

`rustup default` deixou de ser necessário porque `--default-toolchain 1.91` já
define a toolchain padrão; `--profile minimal` evita baixar documentação e
componentes que o build não usa.

### 6. Paralelismo documentado onde é acionável

A subseção Windows (WSL2) ganhou um parágrafo **Paralelismo**: `nproc` é o
padrão do `scripts/build-android.sh` (`--jobs auto`), `CARGO_BUILD_JOBS` e
`--jobs <n>` reduzem, e `NOMESSAGES_GRADLE_WORKERS` (padrão `1`, aplicado como
`--max-workers`) controla o Gradle separadamente. A máquina do WSL costuma ter
mais núcleos que o host Linux anterior, que rodou a etapa nativa com `--jobs 1`
em 39 min. Fica o aviso de subir um de cada vez, porque o guarda de disco conta
consumo agregado.

A seção anterior deste mesmo documento (T6.1/T4.4) já havia documentado `--abi`,
`--with-ndk`, `--jobs` e `NOMESSAGES_GUARD_ARGS` no bloco de comandos. O que
faltava era `NOMESSAGES_GRADLE_WORKERS` e o contexto de "por que subir isto no
WSL", que é justamente o assunto da subseção WSL2. Nada foi duplicado: o bloco
de comandos não foi reeditado.

### Vantagens

- O parágrafo de estado — o primeiro que um avaliador de segurança lê — deixa de
  afirmar o oposto do código entregue. Um leitor que procurar `ONLINE` ou
  `PUBLISHING` no `native/` agora encontra o que o texto promete.
- As instruções de WSL passam a funcionar em uma máquina realmente nova
  (`apt update`) e apontam para nomes de crate que existem no `Cargo.lock`.
- A toolchain Rust deixa de ser o furo de supply chain no meio de um repositório
  que fixa digest de todo o resto.
- O README para de creditar um teste Python que nunca rodou, o que é
  exatamente o tipo de afirmação que o ledger existe para impedir.

### Por que a mudança foi feita

Revisão dos achados de auditoria sobre a entrega de T5.4. Três achados P2 (o
estado do Tor, contado duas vezes, e o teste Python) descreviam o README como
factualmente divergente da árvore; os P3 cobriam `apt update`, o nome do crate,
o `curl | sh` e o paralelismo.

### Não verificado

- Nada foi compilado nesta passagem: `cargo test` não foi executado porque
  nenhum arquivo Rust foi tocado.
- A afirmação "ainda sem validação em rede real" para `PUBLISHING` vem do
  ledger, que continua com "Live Tor delivery | Failed". Não houve execução de
  rede aqui, além do download do `rustup-init` descrito acima.
- `./rustup-init -y --default-toolchain 1.91 --profile minimal` **não** foi
  executado: o WSL desta máquina já tem Rust 1.91 instalado e reinstalar a
  toolchain no meio de builds concorrentes seria destrutivo. Foram executados
  apenas o download e a conferência de digest.

---

## 2026-09-14 — correções de auditoria do README (área "ci")

Três correções. A seção Windows (WSL2) entregue em T5.4 não foi tocada.

### 1. Contradição sobre a suíte Python (P3)

**Como era antes** (linha 9):

```markdown
... ele já é chamado por `scripts/build-android.sh` (T4.4) na árvore de
trabalho, mas ainda não foi executado nenhuma vez e não consta do
[ledger de validação](docs/development/build-report.md).
```

Mas `docs/development/build-report.md` descreve a execução em detalhe:
"Verified locally on Python 3.12.3: four cases, zero failures; an intentionally
failing case was injected and the script exited 1 without reaching assembly."
Dois documentos tocados na mesma rodada, com a mesma data, afirmando o oposto um
do outro.

**Como ficou:**

```markdown
... ele já é chamado por `scripts/build-android.sh` (T4.4) na árvore de trabalho
e foi executado localmente em 2026-09-14 (Python 3.12.3, quatro casos, zero
falhas, com um caso quebrado de propósito confirmando que o build aborta antes
de empacotar). Não consta do [ledger de validação](docs/development/build-report.md)
porque não é um dos 13 gates; os detalhes estão na subseção "Python regression in
the build (task T4.4)" do [build-report.md](docs/development/build-report.md).
```

**Por quê:** num projeto cuja disciplina é não afirmar o que não foi executado, a
afirmação inversa também precisa ser verdadeira. A execução foi reconfirmada
nesta rodada: `python3 -m unittest discover -s app/src/test/python -p 'test_*.py'`
no WSL Ubuntu (Python 3.12.3) termina com quatro casos e `OK`.

### 2. Referência cruzada pendurada de `NOMESSAGES_GUARD_ARGS` (P3)

**Como era antes:** o bloco de comandos mandava "ver o parágrafo abaixo" para
`NOMESSAGES_GUARD_ARGS`, e o parágrafo indicado falava só do orçamento de disco,
sem citar a variável e sem dizer que 8/10 GiB são apenas padrões:

```markdown
A compilação monitora o uso de disco com orçamento de 8 GiB para
caches/artefatos e reserva mínima de 10 GiB livres, interrompendo com margem
antes desses limites.
```

**Como ficou:** o mesmo parágrafo, com uma frase a mais:

```markdown
Esses dois números são apenas os **padrões** de `scripts/guard-build.py`
(`--max-gib 8 --min-free-gib 10 --headroom-gib 1`); `NOMESSAGES_GUARD_ARGS` repassa
outros valores sem editar nenhum script, e o CI usa
`--max-gib 24 --min-free-gib 6 --headroom-gib 2` porque um runner carrega JDK,
SDK, NDK, cache Gradle e `native/target` ao mesmo tempo, em um disco menor.
```

Os padrões foram conferidos em `scripts/guard-build.py:105-107`
(`default=8`, `default=10`, `default=1`) e os valores do CI em
`.github/workflows/android.yml` (`NOMESSAGES_GUARD_ARGS`).

### 3. Comandos documentados que não funcionam num clone limpo (P2)

**Como era antes:** o bloco de "Compilar e testar" e a seção "Verificação em
aparelhos" documentavam `scripts/build-android.sh --bootstrap`,
`scripts/build-android.sh --help`, `scripts/threat-gates.sh --help` etc. como
invocação direta. `git ls-files -s gradlew scripts/*.sh` devolve `100644` para
todos: num clone limpo isso é "Permission denied".

**Como ficou:** todas as linhas passaram a usar o prefixo `bash`
(`bash scripts/build-android.sh --bootstrap`, ...), precedidas de um parágrafo
que explica o motivo e diz que a correção definitiva é o modo `100755` no
índice. `scripts/build-android.sh` também passou a chamar
`scripts/bootstrap-tools.sh` e `./gradlew` por `bash`, de modo que o caminho
documentado funciona de ponta a ponta sem nenhum `chmod`
(ver `docs/changes/build-android.sh.md`).

Aproveitando o mesmo bloco, ficou registrada a ressalva de que `--app-only`
**não** desliga as etapas nativas: sozinho ele ainda compila a host e as duas
ABIs, e quem reusa `jniLibs` pré-compilados é `--skip-native`.

### Vantagens

- O README deixa de contradizer o relatório de build sobre um teste executado.
- O leitor que quiser ajustar o guarda de disco encontra a variável, os padrões
  reais e os valores do CI no mesmo lugar.
- Os comandos do README passam a funcionar num clone limpo, que é a situação de
  quem lê o README.

### O que **não** foi verificado

- Nenhum build completo foi executado a partir do README: a verificação do item
  3 foi feita em sandbox, com `gradlew` e `bootstrap-tools.sh` em modo 644 e
  stubs para `cargo`/`gradlew`.
- Os modos do índice **continuam** `100644`: corrigi-los altera arquivos fora
  desta área e está registrado como pendência no retorno.

---

## 2026-09-17 — Lista de funcionalidades do README ajustada ao que o app entrega

### Como era antes

A lista de funcionalidades do README trazia uma linha que já não batia com o que o código
implementava.

### Como ficou

```
- Anexos de até 8 MiB, captura de foto/áudio em memória e visualizador interno para formatos suportados.
```

### Vantagens

- O README deixa de descrever algo que não existe mais no código como se fosse uma funcionalidade
  presente, ainda que desativada.

### Por que a mudança foi feita

Manter o README alinhado ao estado real do app: a linha antiga descrevia como desativada uma
funcionalidade que, de fato, foi removida.

## 2026-09-17 — README: monograma antigo trocado por "NM" na lista de funcionalidades (mesmo dia — achado na conferência do orquestrador)

A revisão do trabalho de T4.14 encontrou uma linha da lista de funcionalidades que ainda descrevia
o monograma e a paleta antigos:

```
- Kotlin e Jetpack Compose, PT-BR e inglês, ícone próprio e visual baseado na paleta clássica especificada.
```

Corrigida para refletir o monograma novo (`"NM"`) e a paleta própria em vez da "paleta clássica"
(verde-WhatsApp) que essa frase ainda descrevia:

```
- Kotlin e Jetpack Compose, PT-BR e inglês, ícone próprio NM e identidade visual própria (paleta "Grafite e Âmbar").
```

---

## 2026-09-23 — Nova seção "Instalação (release assinado)"

### Como era antes

Não havia nenhuma instrução de instalação no README: a seção "O aplicativo" ia direto para "Como
usar" (fluxo de criação de cofre), presumindo que o leitor já tinha o APK instalado — normalmente
por ter compilado a partir do código-fonte.

### Como ficou

Nova seção `## Instalação (release assinado)`, inserida entre "O aplicativo" e "Como usar":

~~~markdown
## Instalação (release assinado)

Para instalar o APK de release (fora do fluxo de build local):

1. Baixe o APK da release **v1.0.0** na página de [releases do GitHub](https://github.com/maxwellmelo/nomessages/releases).
2. Antes de instalar, confira o **SHA-256** do arquivo baixado contra o valor publicado na release
   (também registrado em [`docs/release-checklist.md`](docs/release-checklist.md)):
   ```
   dfadbcb7c500e25cd9e46a5d5c916e5b3534a4cb5edd278687e6a4751343d729
   ```
3. Após instalar, opcionalmente confira também o fingerprint do certificado de assinatura
   (`adb shell pm list packages -f` + `apksigner verify --print-certs`, ou qualquer inspetor de APK):
   ```
   CN=NoMessages, O=NoMessages
   SHA-256: 28:9D:C1:88:5B:BE:A0:96:97:94:74:09:3A:64:F8:53:50:42:27:CC:62:41:A1:B3:84:AC:15:E0:10:39:39:99
   ```
   Um SHA-256 de arquivo ou um fingerprint de certificado diferentes destes indicam um APK adulterado
   ou de outra origem — não instale.

## Como usar
~~~

### Vantagens

- Alguém que baixa o APK pronto (em vez de compilar) agora tem instruções explícitas, em vez de ter
  que deduzir o processo a partir da seção de build.
- A verificação de SHA-256 do arquivo e do fingerprint do certificado dá ao usuário uma forma
  concreta de detectar um APK adulterado ou de origem diferente antes de instalar — relevante para
  um app cujo modelo de ameaça já presume adversários capazes de interceptar distribuição.
- Os dois valores citados (hash do APK e fingerprint do certificado) são os mesmos registrados em
  `docs/release-checklist.md`, então não há duplicação de fonte de verdade — o README apenas
  referencia o checklist.

### Motivo da mudança

Pedido explícito do usuário em 2026-09-23, junto com a geração do primeiro build de release 1.0.0
assinado (ver `docs/changes/app-build.gradle.kts.md`, seção "2026-09-23", e
`docs/release-checklist.md`, seção "Build de release 1.0.0 (2026-09-23)"). Antes desta build não
fazia sentido documentar instalação a partir de um artefato assinado publicamente distribuível,
porque não existia um.

## 2026-09-23 — Reescrita completa como apresentação pública do repositório (release grátis só pelo GitHub)

### Como era antes

O README já estava inteiramente em inglês (mudança anterior) e já cobria a maior parte do conteúdo
técnico, mas foi escrito como documentação de desenvolvimento, sem preocupação de ser a "vitrine" de
um repositório público: sem selos (badges), sem galeria de screenshots consistente (as três imagens
citadas vinham de diretórios de evidência de teste — `docs/development/build-logs/doorbell-20260918/`
— com tema/resolução inconsistentes entre si e sem link para uma pasta de assets dedicada), sem
diagrama do fluxo pareamento → Tor → entrega, e com a seção "Screenshots" logo no topo mas fora da
ordem de seções pedida (não havia um agrupamento explícito "Privacy and security" nem "Project
status" com esses títulos exatos — o conteúdo equivalente existia espalhado em outras seções com
nomes diferentes, ex. "Privacy & security model, in short").

### Como ficou

Reescrita completa, mantendo todo o conteúdo tecnicamente correto do README anterior (relido e
conferido contra `SPEC.md`, `docs/security-model.md`, `docs/development/doorbell-design.md` e
`docs/release-checklist.md` antes de escrever) e reorganizando em torno da lista mínima pedida:

1. **Cabeçalho com ícone.** `<img src="docs/design/logo-nomessages.svg" width="88" height="88">`
   centralizado, título `NoMessages`, tagline de uma linha, e três selos: licença (AGPL-3.0-or-later,
   linkado para `LICENSE`), "Android 12+", e o selo de status do workflow de CI
   (`.github/workflows/android.yml`), usando o caminho do repositório real
   `maxwellmelo/nomessages` (não um placeholder genérico).
2. **Galeria de screenshots nova**, em tabela HTML de 6 colunas, apontando para
   `docs/assets/screenshots/0{1..6}-*.png` (pasta nova — não existia antes). Ver detalhe de captura
   abaixo.
3. **Seções com os títulos exatos pedidos**, na ordem pedida: "What it is", "Features", "How it
   works" (com diagrama Mermaid `sequenceDiagram` do fluxo pareamento → Tor → entrega, incluindo o
   ramo de fila no remetente e o toque na campainha quando o destinatário está bloqueado), "Privacy
   and security model, in short" (o que protege / o que **não** protege, sem telemetria, sem
   servidores, link para `docs/security-model.md`), "Project status" (1.0.0, gates pendentes em
   hardware físico, auditoria externa pendente, link para `docs/release-checklist.md`), "Install"
   (com o SHA-256 e o fingerprint do certificado já publicados, e um "Quick start" de pareamento),
   "Build from source" (resumo curto + link para `CONTRIBUTING.md`, sem duplicar o conteúdo já
   documentado lá), "Contributing" (link `CONTRIBUTING.md`/`CODE_OF_CONDUCT.md`, menção ao
   `CODEOWNERS`), "Security" (link `SECURITY.md`), "License" (AGPL-3.0-or-later,
   `THIRD_PARTY_NOTICES.md`). Sem nenhum emoji como marcador de seção ou de lista — verificado por
   varredura de caracteres Unicode categoria "So" (símbolo, outro) sobre o arquivo final: zero
   ocorrências.
4. **Nada do nome antigo do produto.** Conferido com uma busca case-insensitive pelo nome anterior
   sobre `README.md`: zero ocorrências (o README anterior já não tinha nenhuma, então isso confirma
   que a reescrita não reintroduziu o nome).

### Como as screenshots foram capturadas

Nenhum código foi alterado para isto. Passos, todos em `emulator-5556`:

1. `app/src/main/kotlin/.../NoMessagesController.kt` estava mais novo que o `app-debug.apk`
   instalado (mudanças de idioma de outra tarefa paralela), então o debug foi recompilado:
   `gradle-wsl.sh :app:assembleDebug` (WSL/Ubuntu, ver ambiente da tarefa) e reinstalado com
   `adb install -r`.
2. Idioma do app de debug forçado para inglês sem depender do idioma do sistema:
   `adb shell cmd locale set-app-locales dev.mx3.nomessages.debug --locales en-US`.
3. Escape hatch de captura **só existe em build debug** (`docs/security-model.md`, "Debug-only
   capture escape hatch"): `adb shell setprop debug.nomessages.allow_capture 1` +
   `adb shell settings put global hidden_api_policy 1` (necessário neste emulador Android 15 para a
   leitura por reflexão de `SystemProperties` não ser bloqueada pela política de API oculta) +
   force-stop + reabertura do app, para que o processo leia a propriedade já no `onCreate`.
4. Com o cofre real já existente neste emulador desbloqueado (senha já fornecida pelo operador da
   tarefa, nunca registrada aqui), 6 telas foram capturadas com `adb shell screencap`: desbloqueio,
   lista de conversas (com o banner "Tor connected"), uma conversa aberta com bolhas e confirmação de
   entrega, a tela de pareamento por QR (QR de uso único, expira em ~2 min — **correção 2026-09-23:**
   "inofensivo publicar" estava errado, o QR carrega a identidade/endereço onion do cofre e foi
   borrado depois de capturado, ver a entrada datada 2026-09-23 no fim deste arquivo),
   Configurações com o toggle "Pending message notice" ligado, e o diálogo "Reset panic password"
   com o medidor de força mostrando "Strong" (senha de exemplo digitada e o diálogo **cancelado**, não
   salvo — a senha de pânico real do cofre não foi alterada).
5. Conteúdo das telas: contatos/mensagens de teste pré-existentes no cofre de desenvolvimento
   (`TesteB`, com mensagens como "campainha-pos-correcao" — nomes de tarefas internas, não dados
   pessoais nem texto constrangedor). Nenhuma mensagem nova foi enviada para compor a galeria.
6. Pós-processamento com Pillow: barra de status do Android recortada (100 px do topo, antes do
   redimensionamento) e cada imagem redimensionada para 540 px de largura, mantendo a proporção;
   salvas em `docs/assets/screenshots/`.

### Vantagens

- Um visitante do GitHub agora tem, na própria página do repositório, tudo que precisa para decidir
  se instala: o que o app faz, para quem serve, prints de tela reais e consistentes (mesmo tema,
  mesma resolução, mesmo build), o que a criptografia cobre e o que não cobre, e o estado honesto do
  release (nada de alegar gates aprovados que continuam `NOT_RUN`).
- Os selos de licença/Android/CI dão um resumo de uma linha no topo, sem precisar abrir mais nenhum
  arquivo — padrão comum em repositórios públicos que o pedido do usuário pediu para seguir.
- O diagrama Mermaid é renderizado nativamente pelo GitHub (sem imagem externa para manter
  atualizada) e mostra o ponto mais frequentemente mal-entendido do design — não existe mailbox de
  terceiro, a fila fica no remetente, e o aviso de campainha não vaza conteúdo — no mesmo lugar onde
  alguém vai procurar "como funciona".
- Screenshots vivem em `docs/assets/screenshots/`, uma pasta dedicada e nova, em vez de apontar para
  dentro de `docs/development/build-logs/...`, que é diretório de evidência de teste e pode ser
  reorganizado/limpo sem aviso — a galeria do README não fica mais refém disso.

### Motivo da mudança

Pedido explícito do usuário: liberar o repositório gratuitamente só pelo GitHub, com o README como
"apresentação completa do aplicativo" para quem chega de fora — com prints, funcionalidades, para que
serve e privacidade — tudo em inglês, e sem nada do nome antigo. Outras tarefas paralelas da mesma
leva cuidaram de tornar o inglês o idioma padrão do app, limpar o histórico/repositório do nome
antigo e criar `CONTRIBUTING.md`/`CODE_OF_CONDUCT.md`/`SECURITY.md`; esta tarefa cobriu especificamente
o README e as screenshots que ele referencia.

## 2026-09-23 — Correções da revisão: QR de pareamento real na screenshot e menção ao nome antigo num comando

### Como era antes

- `docs/assets/screenshots/04-pairing-qr.png` mostrava o QR de pareamento **legível**, que codifica a
  identidade e o endereço onion de um cofre de teste real (não um placeholder). O alt-text também
  afirmava "showing a one-time QR code and its countdown", como se o conteúdo do QR estivesse
  exposto de propósito.
- `docs/changes/README.md.md` (este arquivo), na verificação "Nada do nome antigo", citava
  literalmente o comando `grep` usado para conferir, escrevendo por extenso o próprio nome antigo do
  produto como argumento — ou seja, o texto que deveria provar a ausência do nome antigo o
  reintroduzia.

### Como é agora

- `04-pairing-qr.png`: a região do QR foi borrada (PIL `GaussianBlur`, raio 28, aplicado só no
  retângulo do QR) até ficar ilegível; o resto da tela (título, contador, botões) continua nítido. O
  alt-text em `README.md` passou a dizer "with its one-time QR code blurred out and the countdown
  visible", e a legenda abaixo da tabela de screenshots ganhou uma frase explicando por quê.
- Este arquivo: a linha 599 agora descreve a verificação sem citar o nome antigo ("uma busca
  case-insensitive pelo nome anterior").

### Vantagens

- A screenshot publicada não expõe mais um identificador real (ainda que de um cofre de teste
  descartável): um QR de pareamento carrega a identidade e o endereço onion do cofre que o gerou, e
  a nota anterior no passo 4 desta mesma seção — "QR de uso único, expira em ~2 min — inofensivo
  publicar" — estava errada quanto a isso; a validade curta não torna o conteúdo inofensivo.
- Uma busca `git grep` por qualquer variação do nome antigo agora dá zero ocorrências em todo o
  repositório, inclusive dentro dos próprios arquivos de documentação que auditam essa ausência —
  documentar a verificação sem escrever o termo buscado por extenso evita o paradoxo de reintroduzir
  o que se está provando que não existe mais.

### Por que a mudança foi feita

Achados 2 e 8 (parte 2) da revisão desta tarefa.

## 2026-09-23 — Screenshots 02 e 03 refeitas em inglês, sem strings de teste em PT-BR

`docs/assets/screenshots/02-chat-list.png` e `03-conversation.png` mostravam
strings de teste em português ("campainha", "aceite-pos-diagnostico",
"campainha-pos-correcao") e o contato de desenvolvimento `TesteB` — inadequado
para a vitrine em inglês do README. Via UI (tela "Contact info", que já tinha
campo de alias e botão "Rename"), o contato foi renomeado para `Alice` no
emulador A e `Sam` no emulador B (pareamento existente reaproveitado, sem
recriar cofres); uma conversa curta e natural em inglês (7 mensagens
alternadas, ex. "Hey, are you around this weekend?" / "Yes! Saturday works.
Coffee at the usual place?") foi trocada de verdade entre os dois emuladores
sobre Tor com "Tor connected" confirmado antes da captura. A screenshot da
lista de conversas (`02`) saiu de uma captura direta, sem edição de conteúdo.
A da conversa aberta (`03`) precisou de um recorte adicional com Pillow: a
thread antiga em português continuava acima das mensagens novas na mesma
tela (o app não tem opção de apagar mensagem nem limpar histórico pela UI), e
enviar mensagens de preenchimento suficientes para empurrá-la para fora da
viewport por scroll natural exigiria o dobro do volume de mensagens; em vez
disso, o bloco de 3 bolhas antigas foi recortado da imagem bruta (mantendo o
cabeçalho, o banner "Tor connected" e todas as mensagens novas intactos) antes
do redimensionamento padrão para 540 px de largura. `01-unlock.png`,
`05-settings.png` e `06-password-strength.png` foram conferidas e não têm
texto em português nem dado de teste constrangedor — não foram tocadas. A
legenda abaixo da galeria em `README.md` foi ajustada: "harmless developer
test fixtures" virou "staged sample data" (a frase antiga não descrevia mais o
conteúdo novo) e a nota "every other screenshot is unedited" ganhou a ressalva
sobre o recorte da thread antiga em `03`, para continuar precisa; os textos
alternativos das duas imagens não precisaram mudar, porque já descreviam o
conteúdo em termos genéricos (banner "Tor connected" e bolhas com confirmação
de entrega) que continuam verdadeiros para as novas capturas.
