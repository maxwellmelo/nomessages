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
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "dev.mx3.nomessages"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
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
            versionNameSuffix = "-debug"
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

    androidResources {
        // Only these locales ship in the APK. English (values/) is the default and also the
        // fallback for every unlisted device locale; Portuguese (values-pt/) is the only other
        // supported translation. Keep in sync with res/xml/locales_config.xml.
        localeFilters += listOf("en", "pt")
    }

    buildFeatures {
        compose = true
        // Generates dev.mx3.nomessages.BuildConfig so debug-only code paths (setup/unlock failure
        // logging, the debug screen-capture escape hatch) can gate on BuildConfig.DEBUG instead of
        // a hand-rolled flag. BuildConfig.DEBUG is compiled to a literal per build type, so a
        // release build still gets the exact same dead-code elimination as before this was enabled.
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "libsignal_jni*.so",
            "libsignal_jni*.dylib",
            "signal_jni*.dll",
        )
        jniLibs.excludes += setOf(
            "**/armeabi-v7a/**",
            "**/x86/**",
            "**/libsignal_jni_testing.so",
        )
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            val nativeDirectory = providers.environmentVariable("NOMESSAGES_NATIVE_DIR")
                .orElse(rootProject.layout.projectDirectory.dir("native/target/debug").asFile.absolutePath)
            it.inputs.files(nativeDirectory.map { directory ->
                fileTree(directory).matching { include("libnomessages.so") }
            }).withPropertyName("nomessagesNativeLibraries")
            it.systemProperty("java.library.path", nativeDirectory.get())
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.sqlite)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.sqlcipher.android)

    // Android requires both matching Signal artifacts. The client artifact also
    // supplies desktop JNI used by :core host tests.
    implementation(libs.libsignal.client)
    runtimeOnly(libs.libsignal.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

val requiredNativeLibraries = listOf("arm64-v8a", "x86_64").associateWith { abi ->
    layout.projectDirectory.file("src/main/jniLibs/$abi/libnomessages.so").asFile
}
val verifyNoMessagesNativeLibraries by tasks.registering {
    group = "verification"
    description = "Rejects APK assembly unless libnomessages.so exists for every supported ABI."
    val libraries = requiredNativeLibraries
    inputs.files(libraries.values)
    doLast {
        libraries.forEach { (abi, library) ->
            check(library.isFile && library.length() > 0L) {
                "Missing native runtime for $abi: ${library.absolutePath}. Run scripts/build-android.sh (full mode) or use Gradle compile tasks for source-only diagnostics."
            }
        }
    }
}

tasks.matching { it.name == "mergeDebugNativeLibs" || it.name == "mergeReleaseNativeLibs" }.configureEach {
    dependsOn(verifyNoMessagesNativeLibraries)
}
