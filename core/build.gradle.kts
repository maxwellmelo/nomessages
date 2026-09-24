import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.mx3.nomessages"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.libsignal.client)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    // Test-only: ProtocolTest measures the real QR symbol version the pairing payload produces,
    // rather than comparing it against a capacity table. ZXing is not a :core runtime dependency -
    // only `app` renders QR codes.
    testImplementation(libs.zxing.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    val nativeDirectory = providers.environmentVariable("NOMESSAGES_NATIVE_DIR")
        .orElse(layout.projectDirectory.dir("../native/target/debug").asFile.absolutePath)
    inputs.files(nativeDirectory.map { directory ->
        fileTree(directory).matching { include("libnomessages.so") }
    }).withPropertyName("nomessagesNativeLibraries")
    systemProperty("java.library.path", nativeDirectory.get())
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

mapOf(
    "nativeBridgeProbe" to "NativeBridgeProbe.java",
    "groupCapacityProbe" to "GroupCapacityProbe.java",
).forEach { (taskName, source) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Checks Kotlin/JNI integration without opening sockets ($source)."
        dependsOn(tasks.classes)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(layout.projectDirectory.file("src/test/native/$source").asFile.absolutePath)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        maxHeapSize = "256m"
        val nativeDirectory = providers.environmentVariable("NOMESSAGES_NATIVE_DIR")
            .orElse(layout.projectDirectory.dir("../native/target/debug").asFile.absolutePath)
        systemProperty("java.library.path", nativeDirectory.get())
    }
}
