# .github/workflows/android.yml

## 2026-09-14 — T6.1: workflow executável e rápido

### Como era antes

Um único job, `build`, em `ubuntu-24.04`, com `timeout-minutes: 180`, sem
nenhum cache e com o paralelismo do Rust fixado em uma tarefa:

```yaml
jobs:
  build:
    runs-on: ubuntu-24.04
    timeout-minutes: 180
    env:
      NOMESSAGES_TOOLS_DIR: ${{ runner.temp }}/nomessages-tools
      CARGO_TARGET_DIR: ${{ runner.temp }}/nomessages-native-target
      CARGO_BUILD_JOBS: "1"
      CARGO_INCREMENTAL: "0"
      CARGO_PROFILE_DEV_DEBUG: "0"
      CARGO_PROFILE_TEST_DEBUG: "0"
    steps:
      - name: Check out source
        uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2

      - name: Build native libraries, run tests, lint, and assemble the APK
        run: scripts/build-android.sh --bootstrap --with-native-host --with-native-android

      - name: Verify packaged ABIs and manifest policy
        run: |
          apk=app/build/outputs/apk/debug/app-debug.apk
          unzip -l "$apk" | tee docs/development/build-logs/apk-contents-ci.log
          test "$(unzip -Z1 "$apk" | grep -E '^lib/[^/]+/libnomessages\.so$' | sort | tr '\n' ' ')" = "lib/arm64-v8a/libnomessages.so lib/x86_64/libnomessages.so "
          "$NOMESSAGES_TOOLS_DIR/android-sdk/build-tools/36.0.0/aapt2" dump xmltree --file AndroidManifest.xml "$apk" | tee docs/development/build-logs/manifest-ci.log
          grep -q 'android:allowBackup.*0x0' docs/development/build-logs/manifest-ci.log
          grep -q 'android:usesCleartextTraffic.*0x0' docs/development/build-logs/manifest-ci.log

      - name: Upload APK and verification evidence
        ...
```

Três problemas, todos apontados por T6.1:

1. **Não cabia no orçamento.** Um job serial com `CARGO_BUILD_JOBS: "1"`
   reproduz os ~150 min de Rust medidos no host anterior, dentro de um limite
   de 180 min, e ainda tem que assar dois APKs e rodar lint depois disso.
2. **Sem cache.** Cada execução baixava de novo o JDK Temurin, as command line
   tools, os platform tools, o NDK r27d, o índice do crates.io, todas as
   dependências Gradle, e recompilava `native/target` do zero.
3. **Nunca executou.** Não há remote (T0.4), então nada disso tinha sido
   exercitado nem sequer sintaticamente.

Havia ainda um defeito silencioso: `run: scripts/build-android.sh ...` invoca o
script diretamente, mas `gradlew` e `scripts/*.sh` estão registrados no Git com
modo `100644`. Num checkout limpo do runner isso é "Permission denied".

### Como ficou

Três jobs, o mesmo `permissions: contents: read`, `timeout-minutes: 180` em
cada um (o teto permitido), toda action fixada por SHA de 40 caracteres.

```yaml
env:
  NOMESSAGES_TOOLS_DIR: ${{ github.workspace }}/.tools/toolchains
  NOMESSAGES_GUARD_ARGS: --max-gib 24 --min-free-gib 6 --headroom-gib 2
  CARGO_INCREMENTAL: "0"
  CARGO_PROFILE_DEV_DEBUG: "0"
  CARGO_PROFILE_TEST_DEBUG: "0"
  CARGO_PROFILE_RELEASE_DEBUG: "0"
  CARGO_TERM_COLOR: never
```

| Job | `needs` | Comando |
|---|---|---|
| `host-core` | — | `bash scripts/build-android.sh --bootstrap --core-only --with-native-host` |
| `native-android` (matriz `arm64-v8a`, `x86_64`) | — | `bash scripts/build-android.sh --bootstrap --skip-gradle --no-native-host --with-native-android --abi <abi>` |
| `app` | `host-core`, `native-android` | `bash scripts/build-android.sh --bootstrap --app-only --skip-native --with-ndk` |

- `host-core` produz `native/target/debug/libnomessages.so`, roda
  `cargo test --all-features --locked`, `:core:test`, `:core:nativeBridgeProbe`
  e `:core:groupCapacityProbe`.
- Cada job da matriz publica a sua biblioteca como artefato
  `nomessages-jni-<abi>`.
- `app` baixa os dois artefatos, instala-os em
  `app/src/main/jniLibs/<abi>/libnomessages.so` e então executa
  `:app:testDebugUnitTest`, a suíte Python (T4.4), `assembleDebug`,
  `assembleDebugAndroidTest`, `lintDebug`, `verify-apk.py` e, por fim, as
  checagens `unzip -Z1`/`aapt2` que já existiam, agora com `set -euo pipefail`
  e com o SHA-256 do APK registrado em log.

Caches (`actions/cache@0400d5f…`, v4.2.4), todos com chave por conteúdo:

```yaml
      - name: Cache Cargo registry and git checkouts
        with:
          path: |
            ~/.cargo/registry/index
            ~/.cargo/registry/cache
            ~/.cargo/git/db
          key: cargo-deps-${{ runner.os }}-${{ hashFiles('native/Cargo.lock') }}
```

```yaml
      - name: Cache JDK, Android SDK and Gradle home
        with:
          path: |
            .tools/toolchains
            !.tools/toolchains/tmp
            !.tools/toolchains/downloads
            !.tools/toolchains/android-user
          key: toolchain-base-${{ runner.os }}-${{ hashFiles('scripts/bootstrap-tools.sh', 'gradle/libs.versions.toml', ...) }}
```

```yaml
      - name: Cache the host Cargo target directory
        with:
          path: native/target
          key: native-target-host-${{ runner.os }}-${{ hashFiles('native/Cargo.lock') }}-${{ hashFiles('native/Cargo.toml', 'native/src/**', 'native/tests/**') }}
```

O cache de ferramentas usa **três espaços de chave** —
`toolchain-base-*` (sem NDK, do `host-core`),
`toolchain-ndk-*-<abi>` (da matriz) e `toolchain-ndk-app-*` (do `app`) — porque
as três árvores têm conteúdos diferentes e uma chave única faria o job sem NDK
sobrescrever a árvore com NDK. A escada de `restore-keys` permite que cada job
aproveite a árvore de outro como ponto de partida quente.

Dois passos novos aparecem nos três jobs:

```yaml
      - name: Reclaim runner disk
        run: |
          set -euo pipefail
          df -h /
          sudo rm -rf /usr/share/dotnet /usr/local/lib/android /usr/local/.ghcup /opt/ghc || true
          df -h /

      - name: Restore executable bits
        run: chmod +x gradlew scripts/*.sh
```

E `NOMESSAGES_TOOLS_DIR` saiu de `${{ runner.temp }}` para dentro do workspace,
para que o caminho que o script usa e o caminho que o `actions/cache` guarda
sejam o mesmo. `CARGO_TARGET_DIR` deixou de ser definido: o padrão do script
(`native/target`) é exatamente o caminho que T6.1 manda cachear.

`CARGO_BUILD_JOBS` foi **removido** do ambiente de propósito, com comentário no
arquivo: sem a variável, `scripts/build-android.sh` resolve o paralelismo para
`nproc`. Definir um número ali volta a ser a forma de limitar um job que se
mostre limitado por memória.

### Vantagens

- **Caminho crítico muito menor.** Rust host e as duas ABIs Android deixam de
  ser serializados num único job e passam a correr em três runners em paralelo,
  cada um com `nproc` tarefas de Cargo em vez de uma.
- **A ABI só é compilada uma vez.** O job `app` consome artefatos em vez de
  recompilar; `verifyNoMessagesNativeLibraries` continua recusando o APK se
  faltar qualquer uma das duas bibliotecas, então um artefato perdido vira
  falha explícita e não um APK incompleto.
- **Cache quente.** Índice do crates.io, `native/target`, JDK, SDK, NDK e cache
  Gradle deixam de ser rebaixados a cada execução.
- **Diagnóstico mais rápido.** `fail-fast: false` na matriz mostra as duas ABIs
  numa só execução; a suíte Python aborta antes de empacotar; cada job publica
  seus próprios logs mesmo em falha (`if: always()`).
- **Superfície de permissão mínima.** `contents: read` no nível do workflow;
  nenhum job pede escrita, e não há `pull-requests`, `packages` nem `id-token`.
- **Cadeia de suprimentos.** Toda action continua fixada por SHA; os dois SHAs
  novos (`actions/cache` v4.2.4 e `actions/download-artifact` v4.3.0) foram
  resolvidos pela API do GitHub, não copiados de memória.

### Por que a mudança foi feita

Item T6.1 do roteiro: "Tornar o workflow executável e rápido", com as ações
explicitamente listadas — `actions/cache` para `.tools/toolchains`,
`~/.cargo/registry` e `native/target`; `CARGO_BUILD_JOBS` igual ao número de
CPUs do runner; e divisão em três jobs (host+core, native-android por ABI em
matriz, app+lint+verify com `needs`), mantendo `verify-apk.py` e a verificação
`unzip -Z1`/`aapt2`.

### Verificação feita

- Parse do YAML com PyYAML 6.0.1: chaves de topo, três jobs, `needs` do job
  `app`, `permissions: {contents: read}`.
- Asserção automática de que **toda** referência `uses:` termina em um SHA de
  40 dígitos hexadecimais e de que **todo** `timeout-minutes` é no máximo 180.
- Os quatro SHAs de action foram resolvidos contra
  `repos/<owner>/<repo>/git/ref/tags/<tag>` da API do GitHub:
  `actions/checkout` v4.2.2 = `11bd7190…`, `actions/upload-artifact` v4.6.2 =
  `ea165f8d…`, `actions/cache` v4.2.4 = `0400d5f6…`,
  `actions/download-artifact` v4.3.0 = `d3f86a10…`.
- Conferência de que **toda** opção de `build-android.sh` usada no YAML existe
  no script: `--bootstrap`, `--core-only`, `--with-native-host`,
  `--skip-gradle`, `--no-native-host`, `--with-native-android`, `--abi`,
  `--app-only`, `--skip-native`, `--with-ndk`. As quatro linhas de comando
  foram executadas contra stubs e produziram a sequência esperada.
- Ausência de tabulação e de CRLF no arquivo.

### O que **não** foi verificado

- **O workflow nunca executou.** Não há remote (T0.4). Portanto o critério
  "Feito quando" de T6.1 — duas execuções verdes consecutivas em `develop`,
  cada uma abaixo de 90 min com cache quente — **não** foi satisfeito, e a
  tarefa continua desmarcada no plano.
- Tempo de parede, taxa de acerto de cache e tamanho real dos caches são
  estimativas. `native/target` pode passar do orçamento de 10 GiB de cache por
  repositório do GitHub; nesse caso a consequência é despejo LRU (mais lento),
  nunca resultado errado.
- `sudo rm -rf /usr/share/dotnet …` e os valores de `NOMESSAGES_GUARD_ARGS` foram
  escolhidos a partir do tamanho conhecido das imagens `ubuntu-24.04`, sem
  medição neste repositório.
- `bash scripts/build-android.sh` mais `chmod +x gradlew scripts/*.sh` contorna
  os modos `100644` do índice, mas não os corrige. Ver "Necessidades fora de
  escopo" no retorno desta tarefa.

---

## 2026-09-14 — correções de auditoria do workflow (área "ci")

Segunda passagem sobre o mesmo arquivo, corrigindo os achados da revisão da
entrega de T6.1. Nenhuma estrutura foi refeita: os três jobs, os caches e a
ordem das etapas permanecem como descrito acima.

### 1. Gate de política do manifesto — vazio (P2)

**Como era antes** (etapa "Verify packaged ABIs and manifest policy"):

```yaml
          grep -q 'android:allowBackup.*0x0' docs/development/build-logs/manifest-ci.log
          grep -q 'android:usesCleartextTraffic.*0x0' docs/development/build-logs/manifest-ci.log
```

O padrão casava com o `0x0` **de dentro do identificador de recurso do
atributo** — `android:allowBackup(0x0101000d)=true` casa igual. O `grep` só
provava que o atributo existia.

**Como ficou:**

```yaml
          grep -Eq 'android:allowBackup\([^)]*\)=(false|\(type 0x12\)0x0)[[:space:]]*$' docs/development/build-logs/manifest-ci.log
          grep -Eq 'android:usesCleartextTraffic\([^)]*\)=(false|\(type 0x12\)0x0)[[:space:]]*$' docs/development/build-logs/manifest-ci.log
```

**Evidência** (executada no WSL Ubuntu contra as formas de saída que o
`aapt2 dump xmltree` produz para um atributo booleano):

| linha | padrão antigo | padrão novo |
|---|---|---|
| `...android:allowBackup(0x0101000d)=false` | casa | casa |
| `...android:allowBackup(0x0101000d)=(type 0x12)0x0` | casa | casa |
| `...android:allowBackup(0x0101000d)=true` | **casa** | não casa |
| `...android:allowBackup(0x0101000d)=(type 0x12)0xffffffff` | **casa** | não casa |
| `...android:allowBackup(0x0101000d)=@0x7f100002` | **casa** | não casa |

O padrão novo também tolera `\r` no fim da linha (`[[:space:]]*$`). A ideia é a
mesma da regex já validada de `scripts/verify-apk.py`: ancorar no **valor** e
não no identificador do atributo.

**Vantagens:** o gate volta a poder falhar. `verify-apk.py` continua sendo o
dono da política (roda antes, no mesmo job, e cobre também `fullBackupContent`,
`minSdk`, pacote e atividades auxiliares); estas duas linhas passam a ser o
*backstop* que o `build-report.md` diz que elas são, em vez de uma garantia
falsa.

### 2. `persist-credentials: false` nos três checkouts (P2)

**Como era antes:**

```yaml
      - name: Check out source
        uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
```

**Como ficou** (nos três jobs):

```yaml
      - name: Check out source
        uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
        with:
          persist-credentials: false
```

**Por quê:** o gatilho é `pull_request`, sem filtro de branch, e o job executa
código vindo do head do PR com rede irrestrita — `build.rs` de toda dependência
Cargo (inclusive `libsodium-sys-stable`, que compila C do fonte), os
`*.gradle.kts` e `scripts/bootstrap-tools.sh`. O padrão do `actions/checkout`
grava `http.https://github.com/.extraheader = AUTHORIZATION: basic <token>` no
`.git/config` da mesma árvore. `permissions: contents: read` limita o dano, não
o elimina. Nenhuma etapa deste workflow faz operação Git autenticada depois do
checkout, então não há regressão funcional.

### 3. `ANDROID_USER_HOME` fora de todo cache (P3)

**Como era antes:** nenhum job definia a variável; `scripts/build-android.sh`
forçava `ANDROID_USER_HOME="$TOOLS_DIR/android-user"`, dentro de
`.tools/toolchains`, que é justamente a árvore cacheada nos três jobs. A única
proteção da chave privada de assinatura de debug (gerada pelo AGP ali dentro)
era o padrão de exclusão `!.tools/toolchains/android-user`, que o próprio
implementador registrou como não testado.

**Como ficou** — `env` de cada um dos três jobs:

```yaml
    env:
      ANDROID_USER_HOME: ${{ runner.temp }}/android-user
```

com `scripts/build-android.sh` passando a respeitar o valor herdado (ver
`docs/changes/build-android.sh.md`). O diretório temporário do runner não
aparece em nenhum `path:` de cache.

**Vantagens:** a chave privada deixa de depender da semântica de um glob de
exclusão para não entrar num cache do Actions restaurável por builds de PR. A
exclusão continua no arquivo como defesa redundante.

### 4. `needs` do job `app` (P3)

**Como era antes:** `needs: [host-core, native-android]`.
**Como ficou:** `needs: [native-android]`, com comentário explicando a escolha.

O job `app` consome apenas os artefatos `nomessages-jni-*`; nada de `host-core` é
baixado (o artefato daquele job só tem relatórios, e
`native/target/debug/libnomessages.so` sequer é publicado). Esperar por `host-core`
colocava o job mais longo do workflow em série antes do empacotamento, que é o
principal risco ao critério "abaixo de 90 min" de T6.1. Uma falha de `host-core`
continua reprovando a execução inteira do workflow.

### 5. Espaços de chave do cache da toolchain (P3)

**Como era antes:** três espaços — `toolchain-ndk-...-${{ matrix.abi }}` (um por
ABI) e `toolchain-ndk-app-...`.
**Como ficou:** um só, `toolchain-ndk-...`, usado pela matriz e pelo job `app`.

**Por quê:** `scripts/build-android.sh` chama `bootstrap-tools.sh --with-ndk`
**sem repassar ABI nenhum**, e `scripts/bootstrap-tools.sh` extrai sempre o
mesmo subconjunto do NDK e executa sempre
`rustup target add aarch64-linux-android x86_64-linux-android`. As três árvores
eram idênticas: a chave por ABI triplicava o consumo do limite de 10 GiB de
cache por repositório sem distinguir conteúdo nenhum. A chave já inclui o hash
de `bootstrap-tools.sh`, que é o que de fato determina o conteúdo.

**Não feito, com justificativa:** a revisão também sugeriu acrescentar
`~/.rustup/toolchains` ao cache. Não foi feito. `rustup target add` para os dois
alvos Android baixa ~100 MB e leva segundos; cachear o diretório da toolchain
custaria da ordem de 1 GiB do mesmo orçamento de 10 GiB que o achado pede para
proteger. A troca é ruim. O custo evitado está registrado em `build-report.md`,
seção "Not yet verified".

### 6. `set -euo pipefail` na etapa do digest host (P3)

**Como era antes:**

```yaml
      - name: Record the host JNI library digest
        run: sha256sum native/target/debug/libnomessages.so | tee docs/development/build-logs/host-jni-ci.log
```

**Como ficou:**

```yaml
      - name: Record the host JNI library digest
        run: |
          set -euo pipefail
          test -s native/target/debug/libnomessages.so
          sha256sum native/target/debug/libnomessages.so | tee docs/development/build-logs/host-jni-ci.log
```

**Evidência:** o shell padrão do GitHub para `run:` em Linux é `bash -e {0}`,
sem `pipefail`. Reproduzido no WSL com `bash -e` sobre um arquivo inexistente: a
forma antiga terminou com **exit 0 e log de 0 bytes**; a forma nova termina com
exit 1. Era a única etapa `run` multilinha do arquivo sem o prefixo.

### 7. Lista de ABIs deixa de ser copiada à mão (P3)

**Como era antes:** a matriz definia `[arm64-v8a, x86_64]`, e a mesma lista
aparecia escrita de novo no laço de instalação e uma terceira vez na string
literal esperada pelo `unzip -Z1`.

**Como ficou:**

```yaml
          for artifact in "$RUNNER_TEMP"/jni/nomessages-jni-*; do
            abi="${artifact##*/nomessages-jni-}"
            ...
          done
          test "$installed" -gt 0
```

```yaml
          expected="$(cd app/src/main/jniLibs && ls -1d */ | sed 's|/$||;s|^|lib/|;s|$|/libnomessages.so|' | sort | tr '\n' ' ')"
          packaged="$(unzip -Z1 "$apk" | grep -E '^lib/[^/]+/libnomessages\.so$' | sort | tr '\n' ' ')"
          printf 'expected: %s\npackaged: %s\n' "$expected" "$packaged"
          test "$packaged" = "$expected"
```

A matriz volta a ser a única fonte da verdade do conjunto de ABIs. Um artefato
faltante continua sendo falha dura: `verifyNoMessagesNativeLibraries`
(`app/build.gradle.kts:134-148`) recusa a montagem se qualquer uma das ABIs
exigidas não tiver biblioteca não vazia, e `test "$installed" -gt 0` recusa o
caso em que **nenhum** artefato foi baixado.

**Evidência:** o laço e as duas derivações foram executados verbatim em sandbox:
com os dois artefatos presentes, `expected` = `packaged`; com uma ABI extra no
APK, a comparação falha como esperado.

### Verificação feita nesta passagem

- Parse do YAML com PyYAML; asserções automáticas de que todo `uses:` termina em
  SHA de 40 hex, todo `timeout-minutes` é no máximo 180, os três
  `actions/checkout` têm `persist-credentials: false`, os três jobs definem
  `ANDROID_USER_HOME` no diretório temporário do runner e `app` tem
  `needs: [native-android]`. Sem tabulações e sem CRLF.
- `bash -n` em **cada uma das 13 etapas `run:`** do arquivo, com `${{ ... }}`
  substituído por um literal.
- Os cinco casos de `grep` do item 1 e a reprodução do defeito de `pipefail` do
  item 6, ambos no WSL Ubuntu.
- As quatro linhas de comando de `build-android.sh` usadas pelo YAML foram
  reexecutadas em sandbox com stubs (inclusive com `gradlew` e
  `bootstrap-tools.sh` em modo 644): todas com exit 0 e na ordem esperada.

### O que **não** foi verificado

- O workflow continua **sem nunca ter executado** (sem remote, T0.4). Tempo de
  parede, acerto de cache e o critério das duas execuções verdes de T6.1 seguem
  sem medição, e T6.1 continua desmarcada no plano.
- Que `persist-credentials: false` não quebra nada foi conferido por leitura
  (nenhuma etapa faz operação Git autenticada), não por execução.
- O comportamento real do `actions/cache` com chave compartilhada entre jobs
  concorrentes (espera-se o aviso "cache already exists" no segundo a salvar)
  não foi observado neste repositório.
- A forma exata da saída do `aapt2` deste SDK não foi capturada aqui: os cinco
  casos de teste do item 1 cobrem as duas formas documentadas, mas nenhum APK
  real foi inspecionado nesta máquina.
- O job `native-android` continua instalando JDK, platform, build-tools e
  platform-tools de que não precisa. A correção exige um modo "só NDK" em
  `scripts/bootstrap-tools.sh`, que está fora dos arquivos desta área; ver o
  registro em `build-report.md` e as necessidades fora de escopo no retorno.

## 2026-09-15 — arquivo de workflow inválido no GitHub

**Antes:** os três jobs definiam `ANDROID_USER_HOME: ${{ runner.temp }}/android-user` em `jobs.<id>.env`. O GitHub rejeitou o arquivo na primeira execução real (run 34975478586, "workflow file issue", 0 jobs): o contexto `runner` não está disponível em `env` de job, só em steps.

**Depois:** `ANDROID_USER_HOME: ${{ github.workspace }}/.ci-android-user`. O contexto `github` é permitido em `env` de job, e o diretório fica fora de `.tools/toolchains`, portanto fora de todas as entradas de `actions/cache`, preservando a intenção de nunca cachear o keystore de debug.

**Motivo:** primeira execução do CI após a criação do remote (T0.4); a validação local por parser YAML não detecta regras semânticas do Actions.
