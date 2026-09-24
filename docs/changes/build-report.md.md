# docs/development/build-report.md

## 2026-09-17 — T4.10: zxcvbn sai do contrato de dependências do core

**Antes:** "Core: kotlinx-coroutines 1.10.2, zxcvbn 1.9.0, libsignal-client 0.102.2, JUnit Jupiter 5.13.4".

**Agora:** "Core: kotlinx-coroutines 1.10.2, libsignal-client 0.102.2, JUnit Jupiter 5.13.4".

**Por quê:** o `com.nulab-inc:zxcvbn` foi removido de `core/build.gradle.kts` e do catálogo de versões quando o estimador próprio `estimateStrength` substituiu o portão `score >= 4` na política de senhas. A lista de dependências do relatório de build precisa refletir o que o módulo realmente resolve. Detalhes em [`core-build.gradle.kts.md`](core-build.gradle.kts.md).

## 2026-09-14 — T4.5: contrato de dependências e reexecução do `:core:test`

### Como era antes

Na seção "Dependency contract":

```markdown
- Core: kotlinx-coroutines 1.10.2, protobuf-javalite/protoc 4.36.1,
  zxcvbn 1.9.0, libsignal-client 0.102.2, JUnit Jupiter 5.13.4.
```

E, na tabela "Validation ledger", a linha do gate de testes do núcleo terminava
em `build-logs/core-app-jdk21-sdk36.log`, sem nenhuma menção à remoção do plugin.

### Como ficou

```markdown
- Core: kotlinx-coroutines 1.10.2, zxcvbn 1.9.0, libsignal-client 0.102.2,
  JUnit Jupiter 5.13.4. The `com.google.protobuf` Gradle plugin and the
  `protobuf-javalite` runtime were removed (task T4.5): no generated class
  was ever consumed, and `Wire.kt` parses the bounded protobuf subset by
  hand. The schema now lives at `docs/development/pairing.proto` as
  reference material. `:core:test` was rerun after the removal on
  2026-09-14 (WSL Ubuntu, Temurin JDK 21.0.12.1, Gradle 9.3.1,
  `--offline` against the bootstrapped `gradle-home` cache): 64 cases,
  zero failures, two conditional skips. `:core:dependencies
  --configuration runtimeClasspath` resolves completely and contains no
  `com.google.protobuf` coordinate; libsignal-client 0.102.2 brings only
  kotlinx-coroutines, kotlinx-serialization and kotlin-stdlib.
```

E a linha "Core JVM tests" do ledger ganhou, ao final, a confirmação da
reexecução:

```markdown
Re-executed on 2026-09-14 after the protobuf plugin removal (T4.5) with
`./gradlew --offline :core:test` and `NOMESSAGES_NATIVE_DIR` pointing at the
bootstrapped host `libnomessages.so`: identical result (64 cases, zero
failures/errors, two skips) in `core/build/test-results/test/`.
```

O status do gate (`Passed: 62; skipped: 2`) **não** foi alterado, porque o
resultado é o mesmo — a reexecução confirma o número registrado, não o substitui.

### Por que a mudança foi feita

1. **O contrato ficou desatualizado no momento da remoção.** Continuar listando
   `protobuf-javalite/protoc 4.36.1` como dependência do `:core` descreveria um
   build que não existe mais. O contrato de dependências é consultado na revisão
   de release e no preenchimento de `THIRD_PARTY_NOTICES.md`.

2. **O ledger continha um resultado não reproduzido.** Os 64 casos registrados
   foram executados **com** o plugin protobuf no classpath. A remoção não deveria
   mudar nada (nenhum teste importa classe gerada), mas "não deveria" não é
   evidência, e a regra deste repositório é que o ledger só afirma o que foi de
   fato executado. A primeira versão desta seção registrava um aviso de pendência
   ("`:core:test` has NOT been rerun"); a reexecução do mesmo dia substituiu o
   aviso por um fato medido.

### Como a reexecução foi feita (evidência)

```sh
export JAVA_HOME=$HOME/nomessages-tools/jdk-21          # Temurin 21.0.12.1
export ANDROID_HOME=$HOME/nomessages-tools/android-sdk
export GRADLE_USER_HOME=$HOME/nomessages-tools/gradle-home
export NOMESSAGES_NATIVE_DIR=$HOME/nomessages-target/debug  # libnomessages.so do host
cd "/mnt/e/Vibe Coding/NoMessages"
./gradlew --offline --no-daemon :core:test            # BUILD SUCCESSFUL in 48s
```

Soma dos XMLs de `core/build/test-results/test/`:
`tests=64 failures=0 errors=0 skipped=2`.

Duas armadilhas que explicam por que tentativas anteriores falharam, e que ficam
registradas para quem repetir o gate:

- sem `GRADLE_USER_HOME=~/nomessages-tools/gradle-home`, o modo `--offline` não
  encontra AGP 9.1.1 e o build falha ainda na configuração — isso é ausência de
  cache, **não** ausência de toolchain;
- sem `NOMESSAGES_NATIVE_DIR`, o `java.library.path` aponta para
  `native/target/debug`, que está vazio porque o bootstrap usa
  `CARGO_TARGET_DIR=~/nomessages-target`; 36 casos falham com
  `UnsatisfiedLinkError: no nomessages in java.library.path`, sem relação alguma com
  o protobuf.

### Vantagens

- O relatório volta a descrever o build real de `:core`.
- O gate da T4.5 fecha com evidência medida, em vez de uma pendência aberta.
- Quem repetir o gate recebe o comando exato e as duas variáveis de ambiente que
  o tornam reproduzível.

### Não verificado

`:app` não resolve em modo `--offline` (20 artefatos AndroidX/CameraX/SQLCipher/
libsignal-android ausentes do cache), portanto a ausência de uma runtime
`com.google.protobuf` **dentro do APK empacotado** continua sem confirmação
direta; a checagem cabe à Fase 1/3 com rede. O log terminal desta reexecução não
foi arquivado em `docs/development/build-logs/` porque esse diretório não faz
parte dos arquivos desta tarefa.

---

## 2026-09-14 — T6.1/T4.4: seção "Continuous integration"

### Como era antes

O relatório descrevia o CI em um único parágrafo, correspondente ao workflow de
job único que existia:

```markdown
Each build preserves its terminal output under `docs/development/build-logs/`.
The checked GitHub Actions workflow runs the same pinned bootstrap/build path on
an adequately sized Ubuntu runner and uploads the debug-signed APK, test/lint
reports, manifest dump, ABI listing, and build logs without signing secrets.
```

Não havia nenhuma seção sobre CI, sobre caches, sobre paralelismo do Cargo nem
sobre a suíte Python.

### Como ficou

Duas edições, ambas restritas ao assunto CI:

1. O parágrafo acima ganhou a última frase, para não contradizer a nova seção:

```markdown
... It is split across
three jobs; see "Continuous integration" at the end of this report.
```

2. Uma seção nova `## Continuous integration` no fim do arquivo, com quatro
   subseções: a tabela dos três jobs (`host-core`, `native-android` em matriz,
   `app`) com o comando exato de cada um e o que produz; a tabela dos três
   caches (`~/.cargo`, `.tools/toolchains`, `native/target`) com as entradas de
   chave e a explicação dos três espaços de chave de toolchain; "Parallelism and
   the disk guard", registrando a mudança de `--jobs 1` para `nproc` e a nova
   `NOMESSAGES_GUARD_ARGS`; "Not yet verified", declarando que o workflow nunca
   executou; e "Python regression in the build (task T4.4)", descrevendo os
   quatro casos da suíte e a ordem exata em que o script os executa.

O **ledger de validação não foi tocado**. Nenhuma linha passou a Passed por
causa desta tarefa: nada foi executado em runner.

### Vantagens

- O relatório é o documento que o checklist de release consulta. Descrever o CI
  como um job serial de 180 min, quando ele passou a ser três jobs com cache,
  tornaria a evidência enganosa exatamente onde ela mais importa.
- A tabela de caches documenta a razão dos três espaços de chave de toolchain,
  que é a parte mais fácil de quebrar sem perceber: uma chave única faria o job
  sem NDK sobrescrever a árvore com NDK e o `native-android` voltaria a baixar
  1,5 GB a cada execução, sem erro visível.
- "Not yet verified" separa o que foi checado estaticamente (sintaxe, parse do
  YAML, SHAs das actions, opções do script contra stubs) do que continua sem
  medição (tempo de parede, acerto de cache, o critério das duas execuções
  verdes de T6.1). Sem isso, a seção pareceria uma tarefa concluída.
- Registrar o `chmod +x gradlew scripts/*.sh` como *contorno* — e não como
  correção — deixa rastro do defeito real: os modos `100644` no índice do Git.

### Por que a mudança foi feita

T6.1 pede o workflow executável; a evidência de build deste projeto vive neste
relatório, e a instrução da tarefa autoriza exatamente "uma seção nova 'CI'"
aqui. A frase acrescentada ao parágrafo antigo foi o mínimo necessário para que
o documento não afirmasse duas coisas incompatíveis sobre o mesmo workflow.

### Idioma

Esta seção foi escrita em inglês, ao contrário do restante da documentação de
mudanças. `docs/development/build-report.md` é integralmente em inglês, incluindo
a edição de T4.5 feita por outro agente nesta mesma rodada; inserir uma seção em
português no meio dele quebraria a consistência do documento. A documentação em
português desta mudança é este arquivo.

---

## 2026-09-14 — T0.3/T5.4: resumo do `apk-recovery-assembly.log` excluído do Git

Segunda modificação do mesmo relatório, feita em outra rodada e que até esta
revisão **não tinha registro em `docs/changes/`**. Fica documentada aqui, junto
com as três correções de auditoria aplicadas ao bloco.

### Como era antes

O parágrafo da segunda tentativa de build citava o caminho de um log que não
está mais versionado:

```markdown
A second attempt failed after files under `/tmp` disappeared during Gradle
configuration, without another host restart. The first causal errors were
`NoSuchFileException` for instrumentation caches and the configuration report,
not a Kotlin diagnostic (`build-logs/apk-recovery-assembly.log`). The root
filesystem then had 31 GiB available.
```

O arquivo `docs/development/build-logs/apk-recovery-assembly.log` tem 2.937.393
bytes e 25.297 linhas, e está excluído do Git por `.gitignore:21`. Ou seja: o
relatório apontava para uma evidência que ninguém que clonasse o repositório
receberia, e não havia nenhum resumo do conteúdo dentro do repositório.

### Como ficou

1. **A citação do caminho saiu do parágrafo**, substituída por um ponteiro
   interno para o resumo logo abaixo:

```markdown
`NoSuchFileException` for instrumentation caches and the configuration report,
not a Kotlin diagnostic; the excluded log is summarized below. The root
filesystem then had 31 GiB available.
```

2. **Um bloco novo resume o log**, com o comando executado, as duas falhas
   (`MergeInstrumentationAnalysisTransform` em 68 artefatos de dependência e
   `NoSuchFileException` no relatório de configuration cache durante o
   encerramento), a causa e o que foi recuperado — apontando para os três logs
   que **estão** versionados (`persistent-tools-recovery.log`,
   `persistent-gradle-recovery.log`, `native-host-recovery.log`).

### Números conferidos contra o arquivo

Todos recontados diretamente sobre o log nesta revisão:

| Afirmação | Comando | Resultado |
|---|---|---|
| 25.297 linhas | `wc -l` | 25297 |
| 2,9 MB | `wc -c` | 2.937.393 |
| 68 artefatos | `grep -o "Failed to transform [^ ]*" \| sort -u \| wc -l` | 68 |
| 71 ocorrências de `NoSuchFileException` | `grep -o NoSuchFileException \| wc -l` | 71 |
| 69 caminhos distintos | `grep -oE "NoSuchFileException: [^ ]+" \| sort -u \| wc -l` | 69 |
| todos sob `/tmp` | o mesmo, com `grep -v "^/tmp"` | nenhuma linha |
| fim do arquivo | `tail -3` | linha 25.296 `BUILD FAILED in 8m 45s`, linha 25.297 `Configuration cache entry stored.` |

### Correções aplicadas nesta revisão

1. **69 → 71 ocorrências.** O texto dizia "every path in the 69
   `NoSuchFileException` occurrences". São 71 ocorrências e 69 caminhos
   distintos: 68 caminhos
   `.../transformed/analysis/instrumentation-dependencies.bin` (um por
   artefato) mais três linhas do mesmo
   `.tmp/configuration-cache-report<n>.html`. Ficou "every one of the 71
   `NoSuchFileException` occurrences (69 distinct paths) lives under `/tmp`".

2. **Ordem do fim do log invertida.** O texto dizia que o build terminou em
   `BUILD FAILED` "after only 'Configuration cache entry stored'", mas no
   arquivo a ordem é a inversa: `BUILD FAILED in 8m 45s` na linha 25.296 e
   `Configuration cache entry stored.` como última linha. Ficou: "The run ended
   with `BUILD FAILED in 8m 45s` on line 25,296, followed only by
   `Configuration cache entry stored.` as the last line of the file."

3. **Parágrafo remendado.** A remoção do `(build-logs/apk-recovery-assembly.log)`
   deixou a linha "not a Kotlin diagnostic. The root" com 33 colunas no meio de
   um arquivo que preenche até ~80. O parágrafo (linhas 184-192) foi
   rejustificado no mesmo preenchimento e ganhou o ponteiro interno para o
   resumo, que antes só aparecia seis linhas abaixo, sem aviso.

### Vantagens

- Um log de 2,9 MB, quase todo repetição de duas stack traces Java, fica fora do
  Git sem que o diagnóstico se perca: quem lê o relatório sabe o que aconteceu,
  quantos artefatos falharam e por quê.
- Os números do resumo passam a bater com o arquivo. Um resumo de evidência que
  erra a contagem que ele mesmo apresenta perde a função de substituir a
  evidência.
- O parágrafo volta a apontar para dentro do documento em vez de apontar para um
  caminho que o clone não tem.

### Por que a mudança foi feita

T0.3 do roteiro (`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`)
pediu excluir do Git os logs grandes sem valor de auditoria, preservando o
diagnóstico em texto. As três correções vêm da revisão de auditoria desta
rodada.

### Não verificado

Nada foi reexecutado: o build que gerou o log é de 2026-09-14 no host anterior e
não é reproduzível aqui. O que foi verificado é o **conteúdo do arquivo**, que
continua presente no disco desta máquina (ignorado pelo Git, não apagado), com
os comandos da tabela acima.

## 2026-09-14 — Nova seção "Lint (2026-09-14)" (T1.4)

### Como era antes

`docs/development/build-report.md` terminava na seção
"Python regression in the build (task T4.4)". O ledger não tinha nenhum registro
do `:app:lintDebug`, porque a tarefa nunca havia concluído (cap de Metaspace de
384 MiB). Os 43 avisos e os 3 erros do primeiro run completo existiam só nos
relatórios gerados em `app/build/reports/`, que não são versionados.

### Como ficou

Foi **acrescentada ao final** uma seção `## Lint (2026-09-14)` com:

- o resultado do primeiro run completo (3 erros, 43 avisos) e o resultado depois
  das correções (0 erros, 42 avisos, 1 hint, `BUILD SUCCESSFUL in 7m 46s`);
- uma tabela com os 3 erros, o local exato e a correção aplicada, seguida do
  raciocínio de cada um (em especial, por que o provider do `androidx.startup`
  foi mantido com `tools:ignore` em vez de removido, e por que o `Path.of` era
  um crash real em Android 12/13, não um aviso de estilo);
- a triagem dos 43 avisos agrupados por id do lint, com contagem, caminho e
  classificação (a) corrigir agora / (b) aceitar com justificativa /
  (c) pendência futura;
- a nota de que nenhum baseline de lint foi criado e nenhum check foi
  desabilitado, mais a sugestão de uma tarefa única de limpeza de recursos.

Nenhuma linha preexistente foi alterada, removida ou reordenada: a edição é
estritamente aditiva, no fim do arquivo.

### Por que a mudança

O critério de conclusão de T1.4 no plano é literal: *"`lint-results-debug.html`
existe, zero erros; avisos triados e registrados no ledger"*. Sem a seção, os
avisos remanescentes seriam invisíveis no repositório e o próximo agente teria
de reexecutar um lint de ~8 minutos só para redescobrir o que já foi decidido.

### Vantagens

- Fecha o critério de T1.4 e destrava T6.1 (CI), que depende de T1.4.
- Registra a **decisão** por trás de cada aviso aceito, não só o número — quem
  reabrir o assunto vê por que `targetSdk` continua em 35 e por que as 26
  strings "não usadas" não devem ser apagadas.
- Edição aditiva, segura em relação aos outros agentes que editam o mesmo
  arquivo neste ciclo.

---

## 2026-09-14 — correções de auditoria da seção "Continuous integration" (área "ci")

Quatro correções na documentação do CI. O ledger de validação e os relatórios de
build anteriores não foram tocados.

### 1. Nova subseção "Ledger entries not covered by CI" (P2)

**Como era antes:** a seção "Continuous integration" listava o que cada job
executa, e o plano afirma em T6.1 que "todos os testes JVM/Rust/Python do ledger
passam a rodar aqui como regressão. Nenhum deles é tarefa manual em outra fase".
Em nenhum ponto o documento dizia **o que ficou de fora**, o que fazia a
afirmação do plano parecer satisfeita.

**Como ficou:** subseção nova com tabela de duas linhas:

| Entrada do ledger | Por que não está no CI | Onde está coberto |
|---|---|---|
| Conditional cleanup fault cases | Exige `vault_cleanup_faults.c` compilado e injetado com `LD_PRELOAD` sobre um classpath montado à mão; nenhuma tarefa Gradle nem script do repositório faz isso | `build-logs/vault-cleanup-fault-gate.log`, execução manual |
| Live Tor delivery | Exige rede real e serviço onion vivo; nenhum job chama `scripts/tor-delivery-probe.sh` nem `TorDeliveryProbe.java`, e o teste Rust `tor_live` é `#[ignore]` pelo mesmo motivo | Fase 2 do plano (T2.x), em aparelho/rede reais |

**Evidência:** `grep -rn vault_cleanup_faults --include=*.kts --include=*.sh` não
encontra nenhuma tarefa nem script que execute os dois casos — eles aparecem só
em `.md`. Dentro de `:core:test` continuam sendo os dois *conditional skips*
registrados no ledger. `scripts/tor-delivery-probe.sh` não é invocado por
nenhum dos três jobs.

**Vantagens:** a seção passa a dizer exatamente onde a regressão automática
termina. Quem lê o plano e o relatório juntos não conclui mais que o ledger
inteiro virou CI.

### 2. Afirmação sobre o orçamento de 10 GiB rebaixada (P3)

**Como era antes:**

```markdown
`CARGO_INCREMENTAL=0` and the three `CARGO_PROFILE_*_DEBUG=0` variables keep
those target directories inside the GitHub 10 GiB per-repository cache budget;
```

**Como ficou:**

```markdown
`CARGO_INCREMENTAL=0` and the three `CARGO_PROFILE_*_DEBUG=0` variables reduce
the size of those target directories; whether the resulting set of caches fits
the GitHub 10 GiB per-repository budget has not been measured (see "Not yet
verified").
```

E a subseção "Not yet verified" ganhou quatro itens explícitos: tamanho total de
cache não medido; os padrões de exclusão de glob não exercitados; `~/.rustup`
não cacheado, com a justificativa da troca (cerca de 1 GiB de cache para evitar
um download de cerca de 100 MB que leva segundos); e o excesso de instalação do
job `native-android`, com a observação de que largar `--bootstrap` não
resolveria porque a chamada `--with-ndk` é o mesmo script.

**Por quê:** era um fato declarado sem medição, num documento cuja disciplina é
separar o que foi executado do que não foi.

### 3. Tabela de jobs e de caches atualizada

- A linha do job `app` passou de `needs: host-core, native-android` para
  `needs: native-android`, com um parágrafo explicando que ele não consome nada
  de `host-core` e que a serialização era o principal risco ao critério de
  90 min de T6.1.
- A linha de `.tools/toolchains` passou de três espaços de chave
  (`toolchain-base-*`, `toolchain-ndk-*-<abi>`, `toolchain-ndk-app-*`) para dois
  (`toolchain-base-*` e `toolchain-ndk-*`), com o parágrafo que explica por que
  as três árvores eram idênticas.
- Parágrafo novo sobre `ANDROID_USER_HOME` no diretório temporário do runner,
  registrando que a exclusão da pasta `android-user` passou a ser defesa
  redundante e não a única proteção da chave de assinatura de debug.
- Parágrafo novo dizendo que as duas asserções `aapt2` são o *backstop* de
  `verify-apk.py`, e que o padrão anterior casava com o `0x0` de dentro do
  identificador do atributo e por isso não podia falhar.

### 4. Parágrafo dos bits de execução

**Como era antes:** "That is a workaround, not a fix; the index modes should be
corrected to `100755`."

**Como ficou:** o mesmo, mais o comando exato (`git update-index --chmod=+x ...`
para `gradlew` e os quatro scripts) e o registro de que
`scripts/build-android.sh` agora chama `bootstrap-tools.sh` e `./gradlew` por
`bash`, e de que o `README.md` documenta `bash scripts/build-android.sh ...`, de
modo que um clone limpo funciona sem `chmod` manual.

### Vantagens

- O relatório para de declarar como medido o que ninguém mediu, e para de
  sugerir cobertura de CI onde ela não existe. São os dois tipos de afirmação
  que este documento existe para conter.
- A subseção nova dá à próxima rodada a lista exata do que continua manual, com
  o motivo técnico de cada caso.

### O que **não** foi verificado

- Nada foi reexecutado: nenhum gate do ledger foi rodado nesta passagem. As duas
  linhas da tabela nova são afirmações sobre **ausência** de cobertura,
  verificadas por `grep` no repositório, não por execução.
- Os tamanhos reais de cache continuam sem medição — é exatamente o que a
  correção 2 passa a declarar.

---

## 2026-09-15 — Ledger: "Live Tor delivery" de Failed para Passed (T2.2)

### Escopo desta seção

Uma única linha da tabela "Validation ledger" foi editada. O arquivo tem várias
seções de outros autores e nenhuma delas foi tocada; nenhum código mudou.

### Como era antes

```
| Live Tor delivery | Failed | Bootstrap completed and the synthetic onion
service launched, but self-delivery timed out. Diagnostic run: 449 seconds, no
delivered frame. Cause is not established; representative device/network
validation is required. `build-logs/native-tor-diagnostics.log`. |
```

Status `Failed`, causa declarada como não estabelecida, um único log de
diagnóstico como evidência.

### Como ficou

Status `Passed: 4 runs`, com a data, o ambiente (rede residencial, WSL Ubuntu
24.04, **sem bridges**) e os quatro logs de 2026-09-15 citados com seus números:

- `build-logs/tor-live-20260915T133138Z.log` — descritor publicado após 102,5 s,
  primeiro frame 3,9 s depois, `1 passed` em 134,20 s;
- `build-logs/tor-delivery-probe-20260915T133138Z.log`,
  `...T133510Z.log` e `...T133553Z.log` — bootstrap 16,4 / 14,5 / 56,6 s;
  descritor 20,2 / 18,0 / 14,1 s depois; primeiro frame 5,6 / 7,1 / 5,4 s após a
  publicação; PASS aos 43,4 / 41,1 / 78,2 s.

A célula também registra a causa da falha anterior (declarar ONLINE antes da
publicação do descritor, corrigido em T2.1), mantém a citação do
`native-tor-diagnostics.log` como registro histórico, e diz o que continua
fora: contabilidade de sockets em aparelho e mensageria real seguem como gates
separados. Remete a `native-report.md` para a tabela completa.

### Por que a mudança foi feita

É a metade do "Feito quando" de T2.2 que vive neste arquivo: o plano exige
literalmente que o `build-report.md` mude "Live Tor delivery" de Failed para
Passed com o log. Manter `Failed` depois de quatro passagens medidas tornaria o
ledger — que é o resumo que a release-checklist consulta — falso na direção
pessimista, que é tão ruim quanto a otimista para decidir release.

### Vantagens

- O ledger volta a bater com `native-report.md`, sua fonte; antes as duas
  páginas diriam coisas opostas sobre o mesmo gate.
- A célula cita quatro logs em vez de um, e cada um com números, então a próxima
  leitura pode reconferir sem abrir o plano.
- O escopo do que *não* foi provado ficou dentro da própria célula, evitando que
  "Passed" seja lido como "entrega entre dois aparelhos verificada".

### O que **não** foi alterado

- A linha "Live Tor delivery" da subseção "Ledger entries not covered by CI"
  continua como estava, e continua correta: o gate segue sem cobertura de CI,
  porque exige rede real; passar uma vez à mão não o coloca em nenhum job.
- Nenhuma outra linha do ledger, nenhuma outra seção do arquivo.

## 2026-09-15 — T3.1: nova seção "Instrumented tests (2026-09-15)" e duas linhas do ledger

### Como era antes

O relatório não tinha nenhuma seção sobre execução instrumentada, e o ledger de
validação registrava o assunto como totalmente pendente:

```markdown
| App JVM tests | Passed: 17 | Eight messaging-policy, three sensitive-buffer and six UI/resource-ownership cases; zero failures or skips. ... |
...
| Android runtime/device gates | Pending external device/emulator | Platform-tools 37.0.1 `adb devices -l` completed with no attached device; no system image or emulator is installed. |
```

### Como ficou

Uma seção nova no fim do arquivo, "Instrumented tests (2026-09-15)", com o alvo
(AVD Android 15 API 35 `x86_64` com WHPX, `emulator-5556`), o método (APKs
recompilados no WSL, instalados com `adb install -r`, suíte por `am instrument
-w`), o resultado final `OK (12 tests)` em 151,3 s, uma tabela com as quatro
execuções e seus logs, e a explicação técnica das três causas raiz (PRAGMA com
resultado em `execSQL` do SQLCipher 4; ausência de SHA3-256 no Android;
sobrecarga de `rawQuery` resolvida para `Object...` sem spread).

No ledger, uma linha nova e uma linha corrigida:

```markdown
| Android instrumented tests | Passed: 12 | Emulador Android 15 (API 35) `x86_64` com WHPX, `emulator-5556`. `OK (12 tests)` em `build-logs/android-test-emulator-5556-20260915T143135Z.log`. ... |
| Android runtime/device gates | Parcial: emulador executado, aparelho pendente | ... Os gates que exigem hardware real (3, 4, 5, 8, 10) continuam pendentes de dois aparelhos arm64 descartáveis. |
```

A linha "App JVM tests" passou de `Passed: 17` para `Passed: 26`, com a
composição atual (oito messaging-policy, três sensitive-buffer, sete
transport-supervisor, seis UI/resource-ownership e os dois casos novos de
`Sha3_256Test`), preservando a contagem original de 17 como referência histórica.

### Por que a mudança

O relatório é a evidência de auditoria do projeto: um gate que passou e não está
registrado equivale a um gate que não foi executado. A seção nova é também o
único lugar onde ficam explicadas as três causas raiz encontradas, que valem
para qualquer código futuro que fale com o SQLCipher ou com `MessageDigest` no
Android. As duas linhas do ledger eram afirmações que deixaram de ser
verdadeiras no momento em que o emulador rodou: "no attached device" e a
contagem de testes JVM anterior às duas novas checagens do SHA3-256.

### Vantagens

- O estado real dos gates volta a bater com o ledger, inclusive na parte que
  continua pendente (hardware arm64), sem exagerar o que foi coberto.
- As quatro execuções ficam rastreáveis, com log e causa, e não só a que passou —
  o histórico de falha é o que justifica as mudanças de código do mesmo dia.
- A explicação das causas raiz fica em prosa técnica, no documento que os
  próximos agentes leem antes de mexer em armazenamento.

## 2026-09-15 — Investigação BlueStacks: nova seção "BlueStacks (2026-09-15)"

### Como era antes

O relatório não tinha nenhuma seção sobre BlueStacks. O sintoma reportado era
que, na instância "Tiramisu64" (Android 13 `x86_64`), a VM inteira do
BlueStacks (processo `HD-Player.exe`) morria assim que `am instrument`
começava, com os dois APKs instalando "Success" mas a saída da instrumentação
ficando vazia — já tinha acontecido duas vezes, sem causa registrada.

### Como ficou

Uma seção nova no fim do arquivo, "BlueStacks (2026-09-15)", com a causa raiz
isolada por quatro reproduções idênticas (duas anteriores a esta sessão, duas
nesta): a VM não morre em `am instrument`, morre ~1 s depois que o
`app-debug-androidTest.apk` termina de instalar, num bug do próprio host do
BlueStacks — `STATUS_STACK_BUFFER_OVERRUN` (`0xC0000409`) sempre no mesmo
endereço, disparado pela rotina de criação de atalho/ícone ao processar um
pacote com `activity`/`label`/`iconFileName` vazios (a forma de qualquer APK
`androidTest`, que não tem activity `MAIN`/`LAUNCHER`). A seção também
documenta os testes de confirmação (app sozinho instala e roda bem, inclusive
a biblioteca nativa; o crash só aparece ao instalar o APK de teste) e descarta,
com evidência, as hipóteses de conflito de hipervisor, alinhamento de página de
16 KiB, falta de instruções AVX e memória insuficiente. Conclusão:
BlueStacks não é utilizável para a suíte instrumentada (o bug impede
qualquer `am instrument`), mas é utilizável para uso manual/exploratório do
app.

### Por que a mudança

A causa do travamento não estava registrada em lugar nenhum e bloqueava
qualquer tentativa futura de usar BlueStacks como segundo dispositivo
`x86_64` para os gates de hardware pendentes do ledger. Registrar a evidência
completa (logs do host, endereço de exceção repetido, sequência exata de
eventos) evita que um agente futuro repita a mesma investigação do zero ou,
pior, gaste tempo investigando hipóteses já descartadas (hipervisor, página de
16 KiB, AVX, memória).

### Vantagens

- A causa fica isolada num nível de detalhe reproduzível (mesmo endereço de
  exceção, mesmo padrão de log, quatro em quatro), não uma suposição.
- Fica registrado que o app em si — incluindo a stack nativa Rust
  (libsodium/OpenMLS/Arti) — roda normalmente sob BlueStacks; o problema é
  isolado à instalação do APK `androidTest`, então não é motivo para suspeitar
  da biblioteca nativa do NoMessages.
- A recomendação final é acionável (atualizar o BlueStacks, ou procurar a
  opção de desativar criação de atalho automático) sem prometer que resolve,
  já que não foi testada nesta sessão.

### O que **não** foi alterado

- Nenhum código do app.
- Nenhum commit foi criado.
- A instância Pie64 e o `emulator-5556` não foram tocados.
