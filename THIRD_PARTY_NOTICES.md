# Componentes de terceiros

As versões exatas estão em `gradle/libs.versions.toml` e `native/Cargo.lock`. Este arquivo não substitui os avisos presentes em cada dependência nem uma conferência completa dos artefatos distribuídos.

| Componente | Origem e licença publicada |
|---|---|
| libsignal | [Signal](https://github.com/signalapp/libsignal), AGPL-3.0 |
| libsodium | [libsodium](https://github.com/jedisct1/libsodium), ISC |
| libsodium-rs | [libsodium-rs](https://github.com/jedisct1/libsodium-rs), MIT |
| libsodium-sys-stable | [libsodium-sys-stable](https://github.com/jedisct1/libsodium-sys-stable), MIT ou Apache-2.0 |
| SQLCipher Community | [Zetetic](https://github.com/sqlcipher/sqlcipher), BSD-style; Android bindings preservam seus próprios avisos |
| Arti | [Tor Project](https://gitlab.torproject.org/tpo/core/arti), MIT ou Apache-2.0 |
| OpenMLS | [OpenMLS](https://github.com/openmls/openmls), MIT |
| AndroidX / Compose / CameraX | [Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/), Apache-2.0 |
| Kotlin / coroutines | [JetBrains](https://github.com/Kotlin/kotlinx.coroutines), Apache-2.0 |
| ZXing | [ZXing](https://github.com/zxing/zxing), Apache-2.0 |

O armazenamento em memória OpenMLS incluído em `native/vendor`, quando presente, mantém o aviso upstream e documenta a alteração limitada à limpeza de buffers. As primitives e o protocolo MLS continuam fornecidos pelas bibliotecas upstream.

Os 19 vetores em `app/src/main/kotlin/dev/mx3/nomessages/ui/icons` foram selecionados
do código-fonte oficial AndroidX `material-icons-extended-android:1.7.8`,
Apache-2.0. Apenas os pacotes foram realocados; os desenhos e avisos de copyright
foram preservados. A seleção evita incluir a coleção inteira no APK. Origem:
[sources JAR oficial](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-icons-extended-android/1.7.8/material-icons-extended-android-1.7.8-sources.jar),
SHA-256 `77b7e782a519a3e074fc91e09d59334ac1b815edd87d2e89c0c9ca7c95678e93`.
A [licença completa](docs/licenses/Apache-2.0.txt) acompanha o código.

Ao distribuir um APK, disponibilize o código correspondente, os scripts de build e os avisos aplicáveis aos binários incluídos. O repositório não contém assets oficiais da Meta.
