# Messaging codec validation report

Date: 2026-09-13

Scope: `core/src/main/kotlin/dev/mx3/nomessages/core/messaging`, its JVM tests, and the messaging API document.

Implemented checks cover round trips for all ten application envelope types, fixed magic/version/type tags, strict UTF-8, canonical UUIDs, distinct acknowledgement and control-rejection IDs, closed rejection-reason tags with no remote text field, attachment key and size limits, control characters, proof count and size limits, group membership bounds and identity canonicalization, malicious declared lengths, truncation, trailing bytes, and identity-free Signal/MLS packet framing.

> **Historical toolchain note (2026-09-14).** Everything from here to the end of this report
> describes a one-off manual compilation performed on 2026-09-13 with the Kotlin 2.0.21
> compiler bundled in the then-installed Gradle 8.13 and a Java 17 target, driven from
> `/tmp/nomessages-tools`. That is **not** the project toolchain. The build pins Kotlin 2.3.21
> (`gradle/libs.versions.toml:3`) and JDK/JVM 21 (`core/build.gradle.kts:11,13`;
> `app/build.gradle.kts:48,81,83`), and `/tmp/nomessages-tools` no longer exists. The commands
> below are kept verbatim as the record of how this evidence was obtained; do not re-run
> them. The authoritative regression is `:core:test` under the pinned toolchain.

Production and test source compilation was executed with the Kotlin 2.0.21 compiler bundled in the installed Gradle distribution and a Java 17 target:

```text
/tmp/nomessages-tools/jdk-17/bin/java \
  -cp '/tmp/nomessages-tools/gradle-8.13/lib/*' \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -no-stdlib -no-reflect \
  -classpath /tmp/nomessages-tools/gradle-8.13/lib/kotlin-stdlib-2.0.21.jar \
  -d <temporary-directory> \
  core/src/main/kotlin/dev/mx3/nomessages/core/messaging/Envelope.kt \
  core/src/main/kotlin/dev/mx3/nomessages/core/messaging/EnvelopeCodec.kt \
  core/src/main/kotlin/dev/mx3/nomessages/core/messaging/WirePacket.kt
```

Observed production result: exit 0 with 22 class files produced. The two owned test sources were then compiled into the same temporary output using the JUnit Platform Console Standalone 1.13.4 classpath; observed result: exit 0.

The tests were executed directly with the JUnit Platform 1.13.4 runner:

```text
java -jar /tmp/junit-platform-console-standalone-1.13.4.jar execute \
  --class-path <compiled-classes>:<kotlin-stdlib-2.0.21.jar> \
  --select-package dev.mx3.nomessages.core.messaging \
  --details tree --fail-if-no-tests
```

Observed result: exit 0; 15 tests found, 15 started, 15 successful, 0 skipped, 0 failed. The run included 12 `EnvelopeCodecTest` cases and 3 `WirePacketTest` cases.

The first Gradle-targeted attempt before the wrapper was published was:

```text
./gradlew :core:test --tests 'dev.mx3.nomessages.core.messaging.*'
```

Observed result: exit 127, `./gradlew: No such file or directory`. A later first-run Gradle process was interrupted with exit 130 during cache generation and produced no compiler or test result. The build owner then started the full `:core:test` gate with the configuration cache disabled. That project-wide run uses the build's pinned Kotlin 2.3.21 plugin and remains separate from the successful owned-suite evidence above.
