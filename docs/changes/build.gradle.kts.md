# build.gradle.kts (raiz do projeto)

> Registro exclusivo do `build.gradle.kts` da **raiz**. As mudanças em
> `core/build.gradle.kts` estão em
> [`core-build.gradle.kts.md`](core-build.gradle.kts.md) e as do catálogo em
> [`libs.versions.toml.md`](libs.versions.toml.md). Os dois arquivos têm o mesmo
> nome de base, por isso o registro do módulo recebeu o prefixo `core-`.

## 2026-09-14 — T4.5: remoção do alias `protobuf` do bloco `plugins`

### Como era antes

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
}
```

### Como ficou

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
```

Apenas a linha do protobuf saiu; o restante do arquivo não foi tocado.

### Por que a mudança foi feita

`apply false` na raiz apenas **declara e resolve** a versão do plugin para os
subprojetos. Com `core/build.gradle.kts` deixando de aplicar
`alias(libs.plugins.protobuf)` (mesma tarefa T4.5), nenhum módulo do repositório
aplica mais o plugin — a declaração da raiz virou uma resolução de artefato sem
consumidor. Enquanto ela existisse, o catálogo `gradle/libs.versions.toml`
também não poderia ser limpo: remover `[plugins] protobuf` ali faria a
configuração da raiz falhar com erro de resolução do catálogo. Por isso as duas
edições foram feitas juntas, nesta ordem: raiz primeiro, catálogo depois.

Confirmação de que não sobrou nenhuma referência:
`grep -rn -i protobuf --include=*.kts --include=*.toml .` → **zero ocorrências**.

### Vantagens

1. **Configuração do Gradle mais rápida**: a raiz deixa de resolver o marker
   artifact `com.google.protobuf:com.google.protobuf.gradle.plugin:0.10.0` no
   Gradle Plugin Portal em toda configuração fria.
2. **Catálogo de versões pôde ser esvaziado por completo** (`protobuf`,
   `protobuf-plugin`, `protobuf-javalite` e `[plugins] protobuf`), eliminando
   entradas mortas que enganam quem audita dependências.
3. **Uma dependência de build a menos** para acompanhar em avisos de CVE e em
   atualizações de toolchain.

### Verificação (2026-09-14)

`./gradlew --offline --no-daemon :core:test` no WSL Ubuntu (Temurin JDK 21.0.12.1,
Gradle 9.3.1, `GRADLE_USER_HOME=~/nomessages-tools/gradle-home`) termina com
**BUILD SUCCESSFUL**; `core/build/test-results/test/*.xml` soma
`tests=64 failures=0 errors=0 skipped=2`. Como a raiz é sempre configurada antes
de qualquer subprojeto, esse resultado também prova que o bloco `plugins` da raiz
continua resolvendo sem o alias removido.

### Não verificado

A configuração de `:app` não foi exercitada em modo `--offline` (faltam 20
artefatos Android no cache local); ela depende da mesma raiz, mas não foi
executada aqui.
