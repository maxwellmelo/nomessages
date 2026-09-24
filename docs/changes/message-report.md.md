# docs/development/message-report.md

## 2026-09-14 — T5.3: marcar Kotlin 2.0.21 / JDK 17 como histórico

### Como era antes

Depois do parágrafo de escopo, o relatório emendava direto na evidência:

```markdown
Implemented checks cover round trips for all ten application envelope types, ...

Production and test source compilation was executed with the Kotlin 2.0.21
compiler bundled in the installed Gradle distribution and a Java 17 target:

```text
/tmp/nomessages-tools/jdk-17/bin/java \
  -cp '/tmp/nomessages-tools/gradle-8.13/lib/*' \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -no-stdlib -no-reflect \
  -classpath /tmp/nomessages-tools/gradle-8.13/lib/kotlin-stdlib-2.0.21.jar \
  ...
```
```

Não havia nada avisando que aquilo era uma compilação manual e pontual de
2026-09-13, feita fora do toolchain do projeto e a partir de um diretório `/tmp`
que já não existe. Só no último parágrafo, 30 linhas abaixo, aparecia uma menção
solta a "the build's pinned Kotlin 2.3.21 plugin".

### Como ficou

Um bloco de citação inserido **antes** de toda a seção de evidência:

```markdown
> **Historical toolchain note (2026-09-14).** Everything from here to the end of
> this report describes a one-off manual compilation performed on 2026-09-13 with
> the Kotlin 2.0.21 compiler bundled in the then-installed Gradle 8.13 and a
> Java 17 target, driven from `/tmp/nomessages-tools`. That is **not** the project
> toolchain. The build pins Kotlin 2.3.21 (`gradle/libs.versions.toml:3`) and
> JDK/JVM 21 (`core/build.gradle.kts:11,13`; `app/build.gradle.kts:48,81,83`),
> and `/tmp/nomessages-tools` no longer exists. The commands below are kept verbatim
> as the record of how this evidence was obtained; do not re-run them. The
> authoritative regression is `:core:test` under the pinned toolchain.
```

Os blocos de comando abaixo dele ficaram **intactos**, incluindo os caminhos
`/tmp/nomessages-tools/jdk-17` e `/tmp/junit-platform-console-standalone-1.13.4.jar`.

Todas as referências `arquivo:linha` do bloco foram conferidas contra a árvore
atual:

| Referência | Conteúdo |
|---|---|
| `gradle/libs.versions.toml:3` | `kotlin = "2.3.21"` |
| `core/build.gradle.kts:11` | `jvmToolchain(21)` |
| `core/build.gradle.kts:13` | `jvmTarget.set(JvmTarget.JVM_21)` |
| `app/build.gradle.kts:48` | `sourceCompatibility = JavaVersion.VERSION_21` |
| `app/build.gradle.kts:81` | `jvmToolchain(21)` |
| `app/build.gradle.kts:83` | `jvmTarget.set(JvmTarget.JVM_21)` |

### Por que a mudança foi feita

T5.3 no plano: `message-report.md` (nota de que Kotlin 2.0.21/JDK 17 é histórico).

Um relatório de validação tem duas funções que aqui estavam em conflito: registrar
o que foi observado e dizer o que ainda vale. Sem a nota, o documento lia como se
o projeto compilasse com Kotlin 2.0.21 e alvo 17 — três versões menores e quatro
níveis de JVM atrás do que o build pinna. O risco concreto: alguém tenta reproduzir
a evidência, encontra `/tmp/nomessages-tools` vazio, ou pior, instala Kotlin 2.0.21
para "bater com o relatório" e introduz uma divergência de toolchain.

A escolha foi **anotar, não reescrever**. Reescrever os comandos para a
invocação atual apagaria a única descrição de como aqueles "15 tests found, 15
successful" foram produzidos, e o relatório deixaria de ser evidência. A nota
separa claramente as duas leituras: o que aconteceu (histórico, congelado) e o
que vale (Kotlin 2.3.21 / JDK 21, regressão por `:core:test`).

### Vantagens

- Impossível ler os comandos sem passar pelo aviso: o bloco está acima deles.
- A versão vigente é afirmada com `arquivo:linha` verificável, não como prosa.
- A evidência original é preservada por inteiro, mantendo o valor do relatório
  como registro.
- Instrui explicitamente qual é a regressão autoritativa hoje (`:core:test`),
  fechando a lacuna que o último parágrafo só insinuava.

### Não verificado

- Nenhuma build foi executada. Não foi confirmado que `:core:test` passa hoje —
  essa confirmação pertence aos gates da Fase 3 e a T5.5.
- Os 15 testes relatados em 2026-09-13 não foram reexecutados sob o toolchain
  pinado; a nota afirma qual é a regressão autoritativa, não que ela já rodou.
