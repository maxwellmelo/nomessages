# docs/development/vault-api.md

## 2026-09-17 — T4.10: superfície pública nova no pacote `vault`

**Antes:** a listagem de assinaturas trazia apenas `class PasswordPolicy { fun validate(...); fun validatePair(...) }`.

**Agora:** as assinaturas de `PasswordPolicy` continuam idênticas (nenhuma quebra para chamadores), e foram acrescentadas as três declarações novas que a UI precisa conhecer: `data class PasswordStrength(score, bitsEstimate, feedback)`, `fun estimateStrength(password: CharArray): PasswordStrength` e o `object PasswordFeedback` com o conjunto fechado de chaves emitidas.

**Por quê:** a tela de criação de senha vai desenhar um medidor de força e traduzir cada chave de feedback para um `R.string`. Sem essas assinaturas listadas aqui, a camada de UI não teria contrato publicado e descobriria a API lendo o código. Detalhes em [`PasswordStrength.kt.md`](PasswordStrength.kt.md).

## 2026-09-14 — T5.3: versão de JDK obsoleta

### Como era antes

```markdown
All code is synchronous, Java 17 / Kotlin, package `dev.mx3.nomessages.core.vault`
or `.files`. Call from one serialized IO context. Never call concurrently with
close/export.
```

### Como ficou

```markdown
All code is synchronous, JDK 21 / Kotlin, package `dev.mx3.nomessages.core.vault`
or `.files`. Call from one serialized IO context. Never call concurrently with
close/export.
```

Uma única palavra-versão. Nenhum outro trecho do documento foi alterado — a
varredura por `Java`, `JDK`, `Kotlin` e `/tmp` no arquivo devolve apenas essa
linha.

### Por que a mudança foi feita

T5.3 no plano: `vault-api.md:3` ("Java 17" → JDK 21).

"Java 17" era simplesmente falso em relação ao build. Verificado no código:

- `core/build.gradle.kts:11` — `jvmToolchain(21)`
- `core/build.gradle.kts:13` — `jvmTarget.set(JvmTarget.JVM_21)`
- `app/build.gradle.kts:48` — `sourceCompatibility = JavaVersion.VERSION_21`
- `app/build.gradle.kts:81,83` — `jvmToolchain(21)` e `JvmTarget.JVM_21`

A linha 3 é o cabeçalho de contrato do documento: é ali que quem vai chamar a API
descobre com que runtime está lidando. Declarar Java 17 num módulo compilado com
target 21 é um convite a escrever código-cliente restrito a 17 sem necessidade, e
a errar na hora de investigar uma incompatibilidade de bytecode.

Escolhido "JDK 21" e não "Java 21" porque o projeto pinna um *toolchain*
(`jvmToolchain(21)`), não apenas um nível de linguagem.

### Vantagens

- O contrato declarado passa a bater com o que o Gradle realmente compila.
- Remove a última menção a Java 17 num documento de API vigente (as demais
  menções a 17 no repositório estão em relatórios históricos, agora rotulados
  como tal).
- Mudança de uma palavra: risco zero de conflito com outros agentes editando o
  mesmo arquivo.

### Não verificado

- Nenhuma build Gradle foi executada (o Windows desta sessão não tem JDK/SDK). A
  afirmação "JDK 21" vem da leitura dos arquivos de build, não de uma compilação
  observada.
