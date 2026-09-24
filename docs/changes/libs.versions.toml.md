# gradle/libs.versions.toml

## 2026-09-17 — T4.10: remoção das entradas do zxcvbn

**Antes:** `zxcvbn = "1.9.0"` em `[versions]` e `zxcvbn = { module = "com.nulab-inc:zxcvbn", version.ref = "zxcvbn" }` em `[libraries]`.

**Agora:** as duas linhas foram removidas.

**Por quê:** o último consumidor (`PasswordPolicy.kt`) deixou de usar a biblioteca quando o portão `score >= 4` do zxcvbn foi substituído pelo estimador próprio `estimateStrength` (`PasswordStrength.kt`). Vantagem: catálogo sem coordenada órfã, um artefato a menos para resolver, baixar, auditar e atualizar. Detalhes em [`PasswordPolicy.kt.md`](PasswordPolicy.kt.md) e [`core-build.gradle.kts.md`](core-build.gradle.kts.md).

## 2026-09-14 — T4.5: remoção das entradas protobuf órfãs

### Como era antes

```toml
[versions]
agp = "9.1.1"
kotlin = "2.3.21"
protobuf-plugin = "0.10.0"
protobuf = "4.36.1"
coroutines = "1.10.2"
...

[libraries]
...
protobuf-javalite = { module = "com.google.protobuf:protobuf-javalite", version.ref = "protobuf" }
...

[plugins]
...
protobuf = { id = "com.google.protobuf", version.ref = "protobuf-plugin" }
```

### Como ficou

```toml
[versions]
agp = "9.1.1"
kotlin = "2.3.21"
coroutines = "1.10.2"
...

[libraries]
# (a linha protobuf-javalite foi removida)

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

**Removidas as quatro entradas:** as versões `protobuf = "4.36.1"` e
`protobuf-plugin = "0.10.0"`, a biblioteca `protobuf-javalite` e o alias
`[plugins] protobuf`. A limpeza só foi possível porque, na mesma tarefa, o
`build.gradle.kts` da raiz deixou de declarar `alias(libs.plugins.protobuf) apply
false` — enquanto essa linha existisse, apagar `[plugins] protobuf` faria a
configuração da raiz falhar na resolução do catálogo. Verificação:
`grep -rn -i protobuf --include=*.kts --include=*.toml .` → zero ocorrências.

### Por que a mudança foi feita

Depois que `core/build.gradle.kts` deixou de declarar
`implementation(libs.protobuf.javalite)` e de aplicar o plugin, as quatro
entradas ficaram sem nenhum consumidor. A versão `protobuf = "4.36.1"` era
referenciada **exclusivamente** pela biblioteca `protobuf-javalite`, e
`protobuf-plugin = "0.10.0"` **exclusivamente** pelo alias `[plugins] protobuf`;
com os consumidores removidos, as duas viraram versões órfãs. Catálogo de
versões com entradas mortas engana quem audita a lista de dependências e tende a
ser "reaproveitado" por engano.

### Vantagens

- Contrato de dependências (`docs/development/build-report.md`) volta a bater com
  o catálogo: nada de `protobuf-javalite 4.36.1` listado sem estar em uso.
- Duas versões a menos para acompanhar em avisos de CVE e em atualizações.
- Evita que um módulo futuro adicione `libs.protobuf.javalite` "porque já estava
  no catálogo", reintroduzindo a runtime que acabou de ser retirada.
- A raiz deixa de resolver o marker artifact do plugin em toda configuração fria.

### Verificação (2026-09-14)

A resolução do catálogo foi exercitada no WSL Ubuntu (Temurin JDK 21.0.12.1,
Gradle 9.3.1, `GRADLE_USER_HOME=~/nomessages-tools/gradle-home`):
`./gradlew --offline --no-daemon :core:test` termina com **BUILD SUCCESSFUL**
(`tests=64 failures=0 errors=0 skipped=2`), o que só acontece se raiz e `:core`
resolverem o catálogo sem as entradas removidas. Além disso,
`./gradlew --offline :core:dependencies --configuration runtimeClasspath`
resolve o grafo completo e não lista nenhuma coordenada `com.google.protobuf`.

### Não verificado

A configuração de `:app` não foi exercitada (`--offline` não resolve 20
artefatos Android que faltam no cache local), então o uso do catálogo pelo módulo
Android permanece sem confirmação direta nesta rodada.
