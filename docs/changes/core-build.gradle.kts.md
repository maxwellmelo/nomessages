# core/build.gradle.kts

> Registro exclusivo de `core/build.gradle.kts`. As mudanças no
> `build.gradle.kts` da **raiz** do projeto estão em
> [`build.gradle.kts.md`](build.gradle.kts.md), e as do catálogo de versões em
> [`libs.versions.toml.md`](libs.versions.toml.md).

## 2026-09-17 — T4.10: remoção do `com.nulab-inc:zxcvbn`

### Como era antes

```kotlin
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.zxcvbn)
    implementation(libs.libsignal.client)
    ...
}
```

### Como ficou

```kotlin
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.libsignal.client)
    ...
}
```

### Por quê

`PasswordPolicy.kt` era o **único** consumidor de zxcvbn no repositório inteiro. A nova política de
senhas (T4.10) substituiu o portão `Zxcvbn().measure(...).score >= 4` pelo estimador próprio
`estimateStrength` em `core/src/main/kotlin/dev/mx3/nomessages/core/vault/PasswordStrength.kt`, e a
biblioteca ficou sem chamador. Removida também de `gradle/libs.versions.toml` (versão e coordenada) e
de `THIRD_PARTY_NOTICES.md`.

**Vantagens:** um JAR a menos no APK — o zxcvbn4j embute listas de frequência de senhas e nomes, na
casa das centenas de KB —, uma dependência a menos na superfície de auditoria e de atualização, e um
estimador que devolve chaves de feedback traduzíveis e listas de termos em português, em vez de um
inteiro opaco calculado sobre um corpus anglófono. Racional completo em
[`PasswordPolicy.kt.md`](PasswordPolicy.kt.md) e [`PasswordStrength.kt.md`](PasswordStrength.kt.md).

## 2026-09-14 — T4.5: remoção do plugin protobuf sem uso

### Como era antes

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.protobuf.javalite)
    implementation(libs.zxcvbn)
    implementation(libs.libsignal.client)
    ...
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.1"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}
```

### Como ficou

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.zxcvbn)
    implementation(libs.libsignal.client)
    ...
}
```

O bloco `protobuf { ... }` inteiro foi removido. Nada mais no arquivo mudou:
`kotlin { }`, `java { }`, `tasks.test { }` e as tarefas `nativeBridgeProbe` /
`groupCapacityProbe` permanecem idênticas.

### Por que a mudança foi feita

O plugin gerava classes Java-lite a partir de `core/src/main/proto/pairing.proto`
(hoje movido para `docs/development/pairing.proto`), cujo `package` é
`dev.mx3.nomessages.core.protocol.wire`. **Nenhuma classe gerada é consumida em
lugar algum.** Prova obtida por busca em toda a árvore (excluindo `.git`):

- `grep -rn "protobuf|protoc|javalite|GeneratedMessage|com.google.protobuf" --include=*.kt --include=*.java --include=*.kts --include=*.toml --include=*.gradle --include=*.pro --include=*.yml --include=*.sh --include=*.py .`
  → fora de `docs/`, apenas `build.gradle.kts` (raiz), `core/build.gradle.kts` e
  `gradle/libs.versions.toml`. Nenhum arquivo `.kt`/`.java` importa
  `com.google.protobuf`.
- `grep -rn "PairingOffer|PairingConfirmation|protocol\.wire|PairingOuterClass" --include=*.kt --include=*.java .`
  → **zero ocorrências**. Nem as mensagens (`PairingOffer`, `PairingConfirmation`),
  nem o pacote gerado, nem a classe externa que o protoc criaria.
- `app/build.gradle.kts` não menciona protobuf em nenhuma linha.

A serialização real do QR de pareamento é feita à mão por
`core/src/main/kotlin/dev/mx3/nomessages/core/protocol/Wire.kt`, no `internal object
Proto`, que implementa um subconjunto estrito do wire format protobuf com
validações que a biblioteca oficial não faria: rejeita varint não canônico,
campos duplicados, campos desconhecidos, ordem fora da ascendente e payload
acima de 6144 bytes. Essa rigidez é deliberada e de segurança — trocá-la pela
runtime javalite seria um retrocesso, não uma simplificação.

### Vantagens

1. **Menos superfície de ataque e menos código de terceiros no APK**: some a
   runtime `protobuf-javalite` do classpath de `:core` e, por transitividade, do
   app.
2. **Build mais rápido e mais reprodutível**: o plugin baixava o binário
   `protoc:4.36.1` (artefato nativo, específico de plataforma) e executava a
   tarefa `generateProto` a cada build limpo. Em uma toolchain que se quer
   totalmente fixada e auditável, um binário nativo baixado sem uso é custo puro.
3. **Menos uma dependência para versionar, auditar e licenciar** (a linha do
   Protobuf saiu de `THIRD_PARTY_NOTICES.md`).
4. **Elimina ambiguidade de manutenção**: não existem mais duas implementações do
   mesmo formato (a gerada, morta, e a manual, viva) convidando alguém a usar a
   errada.

### Verificação (2026-09-14)

O gate `Feito quando` da T4.5 foi executado no WSL Ubuntu, com o JDK 21 e o cache
Gradle produzidos pelo bootstrap da Fase 1 (`~/nomessages-tools/{jdk-21,gradle-home}`):

```sh
export JAVA_HOME=$HOME/nomessages-tools/jdk-21
export ANDROID_HOME=$HOME/nomessages-tools/android-sdk
export GRADLE_USER_HOME=$HOME/nomessages-tools/gradle-home
export NOMESSAGES_NATIVE_DIR=$HOME/nomessages-target/debug   # host libnomessages.so
./gradlew --offline --no-daemon :core:test             # BUILD SUCCESSFUL in 48s
```

Resultado lido de `core/build/test-results/test/*.xml`:
`tests=64 failures=0 errors=0 skipped=2` — exatamente o mesmo resultado
registrado no ledger de `docs/development/build-report.md` antes da remoção.
A configuração do build também passou a ocorrer **sem** o plugin, o que prova
que nenhum ponto do `:core` dependia dele.

Complemento: `./gradlew --offline :core:dependencies --configuration
runtimeClasspath` resolve o grafo por completo e **não** contém nenhuma
coordenada `com.google.protobuf`; `libsignal-client:0.102.2` traz apenas
kotlinx-coroutines, kotlinx-serialization e kotlin-stdlib.

### Não verificado

`:app` ainda não pôde ser resolvido em modo `--offline` (20 artefatos AndroidX/
CameraX/SQLCipher/libsignal-android ausentes do cache local), então a ausência de
uma runtime `com.google.protobuf` **dentro do APK empacotado** continua sem
confirmação direta. A checagem fecha quando a Fase 1/3 rodar com rede.
