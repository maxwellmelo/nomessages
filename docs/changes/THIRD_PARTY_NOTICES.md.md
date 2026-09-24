# THIRD_PARTY_NOTICES.md

## 2026-09-17 — T4.10: remoção do aviso do zxcvbn4j

**Antes:** a tabela de componentes trazia a linha ` | zxcvbn4j | [zxcvbn4j](https://github.com/nulab/zxcvbn4j), MIT | `.

**Agora:** a linha foi removida.

**Por quê:** a dependência `com.nulab-inc:zxcvbn` deixou de ser distribuída com o app (T4.10). Manter o aviso de um componente que não é mais embarcado engana quem audita o APK; o arquivo só deve listar o que de fato acompanha o binário. Contexto em [`PasswordPolicy.kt.md`](PasswordPolicy.kt.md).

## 2026-09-14 — T4.5: remoção do aviso do Protobuf

### Como era antes

```markdown
| Kotlin / coroutines | [JetBrains](https://github.com/Kotlin/kotlinx.coroutines), Apache-2.0 |
| Protobuf | [Protocol Buffers](https://github.com/protocolbuffers/protobuf), BSD-3-Clause |
| ZXing | [ZXing](https://github.com/zxing/zxing), Apache-2.0 |
```

### Como ficou

```markdown
| Kotlin / coroutines | [JetBrains](https://github.com/Kotlin/kotlinx.coroutines), Apache-2.0 |
| ZXing | [ZXing](https://github.com/zxing/zxing), Apache-2.0 |
```

Apenas a linha do Protobuf saiu. Todas as outras linhas da tabela e os parágrafos
sobre OpenMLS vendorizado, ícones AndroidX e distribuição do APK permanecem
inalterados.

### Por que a mudança foi feita

O aviso existia por causa da dependência direta `protobuf-javalite`, declarada em
`core/build.gradle.kts` e agora removida (T4.5). O repositório não distribui mais
esse artefato por vontade própria: o formato de fio é implementado à mão em
`Wire.kt` (`object Proto`), e uma implementação própria de um formato público não
gera obrigação de aviso de licença de terceiros.

Manter o aviso de um componente que não é mais distribuído é tão ruim quanto
omitir o de um que é: o arquivo perde credibilidade como inventário e o auditor
deixa de saber o que realmente está no APK.

### Vantagens

- O inventário volta a refletir o grafo de dependências real.
- Menos uma licença (BSD-3-Clause) a conferir na revisão de release.
- Reforça a regra do próprio arquivo — "as versões exatas estão em
  `gradle/libs.versions.toml`" — que só se sustenta se as duas listas andarem
  juntas; a linha do Protobuf agora não tem par no catálogo.

### Verificação parcial (2026-09-14)

Com o JDK 21 e o cache Gradle do bootstrap da Fase 1 disponíveis no WSL
(`~/nomessages-tools/{jdk-21,gradle-home}`), o grafo do módulo `:core` foi resolvido:

```sh
./gradlew --offline :core:dependencies --configuration runtimeClasspath
```

O grafo resolve **por completo** e não contém nenhuma coordenada
`com.google.protobuf`. Em particular, `org.signal:libsignal-client:0.102.2`
traz apenas `kotlinx-coroutines-core`, `kotlinx-serialization-json` e
`kotlin-stdlib` — ou seja, a hipótese mais provável de reintrodução transitiva
está descartada para o núcleo.

### Risco conhecido / não verificado

O grafo de `:app` (`releaseRuntimeClasspath`) **não** pôde ser resolvido em modo
`--offline`: 20 artefatos (AndroidX, CameraX, ZXing, SQLCipher Android e
`libsignal-android`) não estão no cache local e aparecem como `FAILED`. Logo,
**continua não verificado** se alguma dessas dependências reintroduz uma runtime
`com.google.protobuf` no APK. Se a Fase 1/3, com rede, mostrar
`com.google.protobuf` no grafo resolvido de `:app` ou classes
`com/google/protobuf/` dentro do APK empacotado, **esta linha deve voltar**, com
a nota de que a origem é transitiva.
