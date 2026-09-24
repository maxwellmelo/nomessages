# scripts/build-android.sh

## 2026-09-14 — T4.4 (suíte Python no build) e T6.1 (etapas isoladas para o CI)

Esta seção cobre as mudanças de **T4.4** e **T6.1**. Uma rodada anterior de
agentes já havia deixado parte do trabalho no arquivo (as opções `--app-only`,
`--skip-gradle`, `--no-native-host`, `--no-native-android`, `--with-ndk`,
`--abi`, `--jobs`, a variável `NOMESSAGES_GUARD_ARGS` e a primeira versão da
chamada Python). O que segue descreve o arquivo original versionado em
`HEAD` como "antes" e o estado final como "depois", separando o que foi
aproveitado do que foi acrescentado ou corrigido agora.

### Como era antes (arquivo em `HEAD`)

Interface: cinco opções, sem ajuda estruturada.

```bash
-h|--help)
    printf 'Usage: %s [--bootstrap] [--core-only] [--skip-native] [--with-native-host] [--with-native-android]\n' "$0"
```

Paralelismo do Cargo fixo em uma tarefa, nas três invocações:

```bash
cargo build --manifest-path "$PROJECT_DIR/native/Cargo.toml" --all-features --locked --jobs 1
cargo test  --manifest-path "$PROJECT_DIR/native/Cargo.toml" --all-features --locked --jobs 1
cargo build ... --release --target "$rust_target" --jobs 1
```

Gradle sempre em uma única invocação, com a lista inteira de tarefas:

```bash
gradle_tasks=(":core:test" ":core:nativeBridgeProbe" ":core:groupCapacityProbe")
if [[ "$core_only" == false ]]; then
    gradle_tasks+=(":app:testDebugUnitTest" ":app:assembleDebug" ":app:assembleDebugAndroidTest" ":app:lintDebug")
fi

cd "$PROJECT_DIR"
./gradlew --no-daemon --max-workers=1 --stacktrace \
    "-Dorg.gradle.jvmargs=..." "${gradle_tasks[@]}" 2>&1 | tee "$log_file"

if [[ "$core_only" == false ]]; then
    python3 "$PROJECT_DIR/scripts/verify-apk.py" ...
fi
```

Nenhuma chamada à suíte Python. O arquivo
`app/src/test/python/test_messaging_queue.py` existia desde o commit inicial e
**nunca era executado por nenhum build**: era um teste órfão.

O guarda de disco era invocado sem qualquer possibilidade de reconfiguração:

```bash
exec python3 "$PROJECT_DIR/scripts/guard-build.py" -- bash "$0" "$@"
```

### Como ficou

**1. Paralelismo do Cargo configurável, com padrão `nproc`.**

```bash
# Cargo parallelism. The default is every core the machine reports: the former
# hard-coded "--jobs 1" made the Rust stage take ~150 min, which does not fit a
# 180 min CI budget. CARGO_BUILD_JOBS (or --jobs) overrides it for constrained
# hosts; "auto" resolves to nproc here so Cargo never sees a non-numeric value.
cargo_jobs="${CARGO_BUILD_JOBS:-auto}"
...
if [[ "$cargo_jobs" == auto || "$cargo_jobs" == nproc ]]; then
    cargo_jobs="$(nproc)"
fi
if [[ ! "$cargo_jobs" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Invalid Cargo job count: %s\n' "$cargo_jobs" >&2
    exit 2
fi
export CARGO_BUILD_JOBS="$cargo_jobs"
```

O agente anterior tinha deixado o padrão em `1`; a tarefa T6.1 pede
explicitamente `nproc` como padrão, e foi isso que ficou. `--jobs 1` continua
disponível para máquinas pequenas.

**2. Gradle em duas ondas, com a suíte Python no meio (T4.4).**

```bash
# Gradle work is split in two waves so the Python regression can sit exactly
# where it belongs: after :app:testDebugUnitTest and before packaging. A broken
# outbox query then stops the run without paying for assembly and lint.
jvm_test_tasks=()
package_tasks=()
if [[ "$skip_gradle" == false ]]; then
    if [[ "$app_only" == false ]]; then
        jvm_test_tasks+=(":core:test" ":core:nativeBridgeProbe" ":core:groupCapacityProbe")
    fi
    if [[ "$core_only" == false ]]; then
        jvm_test_tasks+=(":app:testDebugUnitTest")
        package_tasks+=(":app:assembleDebug" ":app:assembleDebugAndroidTest" ":app:lintDebug")
    fi
fi
```

```bash
run_gradle() {
    (($# > 0)) || return 0
    ./gradlew --no-daemon "--max-workers=$gradle_workers" --stacktrace \
        "-Dorg.gradle.jvmargs=..." "$@" 2>&1 | tee -a "$log_file"
}

run_gradle ${jvm_test_tasks[@]+"${jvm_test_tasks[@]}"}

if [[ "$run_app_checks" == true ]]; then
    python3 -m unittest discover \
        -s "$PROJECT_DIR/app/src/test/python" \
        -t "$PROJECT_DIR/app/src/test/python" \
        -p 'test_*.py' --verbose 2>&1 \
        | tee "$PROJECT_DIR/docs/development/build-logs/python-tests-$build_stamp.log"
fi

run_gradle ${package_tasks[@]+"${package_tasks[@]}"}

if [[ "$run_app_checks" == true ]]; then
    python3 "$PROJECT_DIR/scripts/verify-apk.py" ...
fi
```

A versão parcial anterior colocava a chamada Python **depois de todas** as
tarefas Gradle, inclusive `lintDebug`. Isso tinha dois defeitos: o critério da
tarefa é "após `:app:testDebugUnitTest`", e uma falha de lint (que já ocorreu
neste projeto por limite de metaspace) impediria a suíte Python de rodar,
escondendo o resultado. A divisão em duas ondas corrige os dois pontos.

**3. Opções novas usadas pelo CI.** Aproveitadas da rodada anterior e
verificadas uma a uma: `--app-only`, `--skip-gradle`, `--no-native-host`,
`--no-native-android`, `--with-ndk`, `--abi <arm64-v8a|x86_64>` (repetível) e
`--jobs <n|auto>`, com `usage()` descrevendo todas. `--core-only` junto de
`--app-only` é rejeitado com status 2, assim como uma ABI desconhecida ou um
número de tarefas inválido.

**4. `NOMESSAGES_GUARD_ARGS`.** Aproveitada da rodada anterior; permite repassar
`--max-gib`, `--min-free-gib` e `--headroom-gib` a `scripts/guard-build.py` sem
editar script nenhum. O CI usa `--max-gib 24 --min-free-gib 6 --headroom-gib 2`,
porque os padrões (8 GiB / 10 GiB) foram calibrados para um laptop e não cabem
num runner que carrega JDK, SDK, NDK, cache Gradle e `native/target` juntos.

**5. Correção de detalhe.** A linha final imprimia sempre o caminho do log do
Gradle, mesmo com `--skip-gradle`, quando esse arquivo nunca é criado:

```bash
if [[ "$skip_gradle" == false ]]; then
    printf 'Build evidence: %s\n' "$log_file"
else
    printf 'Build evidence: %s\n' "$PROJECT_DIR/docs/development/build-logs"
fi
```

### Vantagens

- **A suíte Python deixa de ser código morto.** Quatro casos de regressão do
  `readyQuery` da caixa de saída (cota de pedidos versus convites, ordenação de
  commits de membresia entre épocas, par já tentado versus par novo, e um
  destinatário offline não bloqueando outro do mesmo grupo) passam a falhar o
  build. Eles cobrem exatamente a consulta SQL de produção, lida por caminho de
  `MessagingPolicy.kt`, sem Android, sem JNI e sem Gradle.
- **Falha cedo e barato.** Uma consulta quebrada interrompe o build antes de
  `assembleDebug`, `assembleDebugAndroidTest` e `lintDebug` — as três etapas
  mais caras do pipeline.
- **O tempo de Rust deixa de estourar o orçamento do CI.** `--jobs 1` custava
  cerca de 150 min de um limite de 180. Com `nproc` o mesmo trabalho ainda pode
  ser dividido entre três jobs paralelos.
- **Cada etapa do YAML é uma invocação do próprio script.** O CI não reimplementa
  o build: ele chama o mesmo script que o desenvolvedor chama, com recortes
  diferentes. Isso elimina a classe de bug "funciona local, quebra no CI por
  divergência de comandos".
- **O caminho local completo continua intacto.** `scripts/build-android.sh`
  sem argumentos e `--bootstrap` fazem exatamente o que faziam, na mesma ordem,
  mais a suíte Python.

### Por que a mudança foi feita

T4.4 do roteiro (`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`)
pede ligar o teste Python ao build, e T6.1 pede que o workflow seja executável
e rápido, o que exige que o script aceite recortes por etapa (só núcleo, só
uma ABI nativa, só aplicativo) e paralelismo configurável. As duas tarefas
tocam o mesmo arquivo e foram feitas juntas para não editá-lo duas vezes.

### Verificação feita

- `bash -n scripts/build-android.sh` no WSL Ubuntu: sem erro de sintaxe.
- `python3 -m unittest discover -s app/src/test/python -p 'test_*.py' -v`
  (Python 3.12.3): 4 casos, `OK`.
- Banco de provas em sandbox (`bootstrap-tools.sh`, `cargo`, `rustup`,
  `gradlew` e `verify-apk.py` substituídos por stubs que apenas registram a
  chamada): as quatro linhas de comando usadas pelo YAML produzem exatamente a
  sequência esperada, e `--bootstrap` sem recorte produz o caminho completo.
- Propagação de falha: com um caso Python propositalmente quebrado, o script
  terminou com status 1 e **nenhuma** tarefa de empacotamento foi executada.
- `NOMESSAGES_GUARD_ARGS` foi exercitada de ponta a ponta com o
  `scripts/guard-build.py` real (não stub), com e sem a variável definida.

### O que **não** foi verificado

Nenhum build real ocorreu: esta máquina não tem JDK nem SDK no Windows, e o
WSL não tem a árvore `.tools/toolchains` instalada. Tempo de parede, consumo de
disco real com `--jobs nproc` e o comportamento do `verify-apk.py` sobre um APK
de verdade continuam sem medição nesta rodada.

---

## 2026-09-14 — correções de auditoria do script (área "ci")

Três correções pontuais sobre a entrega descrita acima. Nenhuma etapa do build
mudou de ordem e nenhuma opção nova foi criada.

### 1. `ANDROID_USER_HOME` passa a respeitar valor herdado

**Como era antes** (linha 21):

```bash
export ANDROID_USER_HOME="$TOOLS_DIR/android-user"
```

Sobrescrita incondicional, diferente das duas linhas seguintes
(`CARGO_TARGET_DIR` e `NOMESSAGES_NATIVE_DIR`), que usam `${VAR:-default}`.

**Como ficou:**

```bash
# AGP generates and uses the debug signing keystore under ANDROID_USER_HOME.
# The default keeps it beside the toolchain, but an inherited value wins so a CI
# job can place that private key outside every cached directory. Same pattern as
# CARGO_TARGET_DIR and NOMESSAGES_NATIVE_DIR below.
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$TOOLS_DIR/android-user}"
```

**Por quê:** `app/build.gradle.kts` não declara `signingConfig` para `debug`, de
modo que o AGP gera e usa o keystore de debug padrão sob `ANDROID_USER_HOME`.
Com o valor forçado, essa chave privada ficava dentro de `.tools/toolchains`, a
árvore que o CI guarda em `actions/cache` nos três jobs; a única proteção era um
padrão de exclusão `!` de glob, registrado pelo próprio implementador como não
testado. Agora `.github/workflows/android.yml` define
`ANDROID_USER_HOME: ${{ runner.temp }}/android-user`, fora de qualquer `path:`
de cache, e a exclusão vira defesa redundante.

**Evidência:** executado no WSL Ubuntu.

| execução | resultado |
|---|---|
| `ANDROID_USER_HOME=<tmp>/inherited-user ... build-android.sh --help` | criou `<tmp>/inherited-user`; **não** criou `$NOMESSAGES_TOOLS_DIR/android-user` |
| sem a variável no ambiente | criou `$NOMESSAGES_TOOLS_DIR/android-user`, como antes |

**Vantagens:** a chave privada de assinatura de debug deixa de depender da
semântica de um glob para não entrar num cache restaurável por builds de PR, e o
comportamento padrão para quem compila localmente fica idêntico.

### 2. Texto de `--app-only` no `usage()` deixa de prometer o que não faz

**Como era antes** (linhas 66-67):

```
  --app-only               Run only the :app tasks, the Python suite and
                           verify-apk.py, reusing prebuilt jniLibs.
```

**Como ficou:**

```
  --app-only               Run only the :app tasks, the Python suite and
                           verify-apk.py. It does not imply any native skip:
                           add --skip-native to reuse prebuilt jniLibs.
```

**Por quê:** a opção só afeta `jvm_test_tasks`; ela não mexe em
`with_native_host` nem em `with_native_android`. Sozinha, `--app-only` ainda
compila e testa a biblioteca host **e** faz o cross-build das duas ABIs — quem
reusa `jniLibs` pré-compilados é `--skip-native`, que o CI passa junto. O texto
antigo descrevia um comportamento que o script não tem.

**Por que corrigir o texto em vez de implementar a implicação:** o
comportamento atual está certo para quem roda `--app-only` sozinho querendo um
APK completo a partir do fonte; tornar a implicação automática removeria essa
possibilidade sem pedido de ninguém e mudaria o significado de uma opção já
usada pelo CI. O defeito era de documentação. O README ganhou a mesma
ressalva.

### 3. `bootstrap-tools.sh` e `gradlew` chamados por `bash`

**Como era antes** (linhas 196, 200 e 258):

```bash
    "$PROJECT_DIR/scripts/bootstrap-tools.sh"
...
    "$PROJECT_DIR/scripts/bootstrap-tools.sh" --with-ndk
...
    ./gradlew \
```

**Como ficou:**

```bash
    # Called through "bash" on purpose: gradlew and scripts/*.sh are recorded in
    # Git with mode 100644, so a fresh clone cannot execute them directly. This
    # keeps the documented invocation working without a manual chmod; the index
    # modes still need to be corrected to 100755.
    bash "$PROJECT_DIR/scripts/bootstrap-tools.sh"
...
    bash "$PROJECT_DIR/scripts/bootstrap-tools.sh" --with-ndk
...
    bash ./gradlew \
```

**Por quê:** `git ls-files -s gradlew scripts/*.sh` devolve `100644` para todos.
O CI contorna isso com `chmod +x gradlew scripts/*.sh` em cada job, mas fora do
CI ninguém roda esse `chmod`: num clone limpo, `bash scripts/build-android.sh
--bootstrap` morria em "Permission denied" na chamada ao `bootstrap-tools.sh` e,
mais adiante, no `./gradlew`. Com o prefixo `bash`, o caminho documentado no
README funciona sem nenhum `chmod`.

**O que isto não é:** não é a correção do defeito. A correção é
`git update-index --chmod=+x gradlew scripts/build-android.sh
scripts/bootstrap-tools.sh scripts/tor-delivery-probe.sh scripts/threat-gates.sh`,
que altera quatro arquivos fora desta área e por isso **não** foi executada aqui
— está registrada nas necessidades fora de escopo do retorno e em
continuam corretas (só deixam de ser necessárias) e as etapas
"Restore executable bits" do workflow podem ser removidas.

**Evidência:** sandbox no WSL com um projeto de teste cujos `gradlew` e
`scripts/bootstrap-tools.sh` estão em modo 644, com `cargo`, `gradlew`,
`bootstrap-tools.sh` e `verify-apk.py` substituídos por stubs que registram a
chamada. As quatro linhas de comando terminaram com exit 0 e na ordem esperada:

```
--- host-core job (exit 0)
bootstrap-tools.sh
cargo build
cargo test
gradlew :core:test :core:nativeBridgeProbe :core:groupCapacityProbe

--- native-android job (exit 0)
bootstrap-tools.sh
bootstrap-tools.sh --with-ndk
cargo build

--- app job (exit 0)
bootstrap-tools.sh
bootstrap-tools.sh --with-ndk
gradlew :app:testDebugUnitTest
gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

--- no cut (README) (exit 0)
bootstrap-tools.sh
bootstrap-tools.sh --with-ndk
cargo build
cargo test
cargo build
cargo build
gradlew :core:test :core:nativeBridgeProbe :core:groupCapacityProbe :app:testDebugUnitTest
gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

O último traço confirma, de passagem, o item 2: sem recorte, `--app-only` não
aparece e os quatro `cargo build`/`cargo test` do caminho completo acontecem.

### O que **não** foi corrigido, e por quê

A revisão apontou que o job `native-android` baixa JDK, `platforms;android-37.0`,
`build-tools;36.0.0` e platform-tools sem precisar de nada disso, e sugeriu
condicionar a primeira chamada de bootstrap a `needs_jvm_toolchain`. **A
sugestão não resolve:** a segunda chamada, `bootstrap-tools.sh --with-ndk` (que
esse job precisa), é o mesmo script, e os blocos de JDK/SDK dele rodam
independentemente de `--with-ndk` — o `--with-ndk` só acrescenta o bloco do NDK
ao final. Condicionar a primeira chamada apenas removeria uma invocação
idempotente, sem economizar um byte de download. A correção real é um modo
"só NDK" em `scripts/bootstrap-tools.sh`, arquivo fora desta área; está
registrada no retorno e em `build-report.md`.

### O que **não** foi verificado

Nenhum build real: esta máquina não tem JDK/SDK no Windows e o WSL não tem a
árvore `.tools/toolchains` instalada. Toda a verificação acima é `bash -n`,
execução com stubs e inspeção de variáveis de ambiente. Tempo de parede, consumo
de disco e comportamento do `gradlew` real sob `bash ./gradlew` continuam sem
medição nesta rodada.
