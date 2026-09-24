# app/build.gradle.kts

> Registro do `build.gradle.kts` do módulo `:app`. O `build.gradle.kts` da raiz está em
> [`build.gradle.kts.md`](build.gradle.kts.md); o do módulo `:core` está em
> [`core-build.gradle.kts.md`](core-build.gradle.kts.md). Este arquivo recebeu o prefixo `app-`
> pela mesma razão que o de `:core` recebeu `core-`: o nome de base é o mesmo em todos os módulos.

## 2026-09-15 — Habilita geração de `BuildConfig` (`buildFeatures.buildConfig = true`)

### Como era antes

```kotlin
buildFeatures {
    compose = true
    buildConfig = false
}
```

O módulo `:app` não gerava a classe `dev.mx3.nomessages.BuildConfig`. Não havia como um código-fonte do
app distinguir build debug de release em tempo de execução sem introduzir uma flag própria.

### Como ficou

```kotlin
buildFeatures {
    compose = true
    // Generates dev.mx3.nomessages.BuildConfig so debug-only code paths (setup/unlock failure
    // logging, the debug screen-capture escape hatch) can gate on BuildConfig.DEBUG instead of
    // a hand-rolled flag. BuildConfig.DEBUG is compiled to a literal per build type, so a
    // release build still gets the exact same dead-code elimination as before this was enabled.
    buildConfig = true
}
```

### Vantagens

- Permite usar `BuildConfig.DEBUG`, a forma padrão e já otimizada pelo Android Gradle Plugin, em vez
  de reinventar uma constante equivalente em Kotlin puro.
- `BuildConfig.DEBUG` é compilado como um literal booleano por variante de build; um `if
  (BuildConfig.DEBUG) { ... }` que dá `false` é eliminado como código morto pelo R8 no build de
  release — o binário release não fica maior nem ganha nenhum código extra por causa disto.
- Reaproveitado por duas correções do mesmo dia: o logging condicional de diagnóstico em
  `NoMessagesController.kt` (`docs/changes/NoMessagesController.kt.md`) e a chave de captura de tela
  apenas em debug de `MainActivity.kt` (`docs/changes/MainActivity.kt.md`).

### Motivo da mudança

Pré-requisito das tarefas 1 e 4 da investigação de 2026-09-15 (bloqueador de criação de cofre pela
UI real nos dois emuladores oficiais Android 15 x86_64): ambas dependem de `BuildConfig.DEBUG`
existir no módulo `:app`, e o módulo tinha essa geração explicitamente desligada.

## 2026-09-23 — Assinatura condicional de release por `NOMESSAGES_SIGNING_PROPERTIES` e bump de `versionName` para `1.0.0`

### Como era antes

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.mx3.nomessages"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mx3.nomessages"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ...
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // ...
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    // ...
}
```

Não havia nenhum `signingConfigs` declarado. Um `assembleRelease` local sempre produzia um APK **sem
assinatura de release** (só a assinatura de debug automática do AGP, quando aplicável), e o
`versionName` ainda trazia o sufixo `-dev`, sinalizando uma build de desenvolvimento mesmo quando o
binário já estava pronto para ser candidato a release.

### Como ficou

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing material lives OUTSIDE this repo (a checked-in keystore or password would be a
 * secret leak on every clone/fork). Its location is resolved at configuration time from, in order:
 * the `NOMESSAGES_SIGNING_PROPERTIES` environment variable, then the `nomessages.signing` Gradle
 * project property (`-Pnomessages.signing=...`) - both are indirections to a path, never a
 * hardcoded machine-specific path in version control, so the same build script works unmodified on
 * every developer's machine and in CI.
 *
 * CI never sets either one, and a fresh checkout has no keystore at all, so this must never fail
 * Gradle configuration: a missing env var/property, a missing file, or a file missing any of the
 * four required keys all fall through to `null`, and the `release` build type simply stays
 * unsigned (the pre-existing default AGP behavior) rather than breaking `assembleRelease`,
 * `lintRelease`, debug builds, or CI.
 */
val releaseSigningProps: Properties? = run {
    val path = providers.environmentVariable("NOMESSAGES_SIGNING_PROPERTIES")
        .orElse(providers.gradleProperty("nomessages.signing"))
        .orNull
    val file = path?.let { file(it) }
    if (file == null || !file.isFile) return@run null
    val props = Properties()
    FileInputStream(file).use { props.load(it) }
    val requiredKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    if (requiredKeys.any { props.getProperty(it).isNullOrBlank() }) null else props
}

android {
    namespace = "dev.mx3.nomessages"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mx3.nomessages"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ...
        }
    }

    signingConfigs {
        if (releaseSigningProps != null) {
            create("release") {
                storeFile = file(releaseSigningProps.getProperty("storeFile"))
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // ...
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    // ...
}
```

### Vantagens

- **Nenhum segredo entra no repositório.** O keystore `.p12` e as senhas ficam inteiramente fora do
  Git (`C:\Users\maxwe\.nomessages-release\` nesta máquina); o script só recebe um caminho por
  variável de ambiente ou propriedade Gradle, nunca um caminho fixo commitado nem o conteúdo do
  segredo.
- **Degrada com segurança, nunca quebra o build.** Se a variável não estiver definida, o arquivo não
  existir ou faltar qualquer uma das quatro chaves obrigatórias (`storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`), `releaseSigningProps` vira `null` e o build type `release` simplesmente
  fica sem `signingConfig` — o comportamento padrão do AGP antes desta mudança. Isso significa que
  `assembleRelease`, `lintRelease`, os builds de debug e o CI (que nunca define essa variável)
  continuam funcionando exatamente como antes, sem exigir nenhum segredo presente.
- **O mesmo script funciona sem alteração em qualquer máquina/CI.** Como o caminho é resolvido em
  tempo de configuração via variável de ambiente/propriedade, não há necessidade de editar o
  `build.gradle.kts` por desenvolvedor nem manter um caminho hardcoded específico de uma máquina.
- **`versionName = "1.0.0"` (sem o sufixo `-dev`)** identifica corretamente o primeiro candidato a
  release pronto para distribuição, distinguindo-o de builds de desenvolvimento anteriores.

### Motivo da mudança

Pedido explícito do usuário em 2026-09-23 para gerar o primeiro build de release 1.0.0 assinado do
NoMessages, documentado em `docs/release-checklist.md`, seção "Build de release 1.0.0
(2026-09-23)". A verificação estática completa (`apksigner`, `aapt2 dump badging`, `aapt2 dump
xmltree`, `scripts/verify-apk.py`) e o teste funcional em `emulator-5556` (criação de cofre,
conexão Tor, e confirmação de que `FLAG_SECURE` permanece efetivo mesmo com o gancho de debug de
captura de tela ativo) estão documentados em
`docs/development/build-logs/release-1.0.0/`. Este build **não** substitui os gates de hardware
físico (T4.1/T4.6/T4.7) nem a auditoria externa de segurança, que seguem pendentes.
