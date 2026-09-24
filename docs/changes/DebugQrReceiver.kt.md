# DebugQrReceiver.kt

## 2026-09-15 — T3.3 run4: hook debug-only para injetar/ler o QR de pareamento

Arquivo **novo**: `app/src/debug/kotlin/dev/mx3/nomessages/debug/DebugQrReceiver.kt`.
Source set **novo**: `app/src/debug/kotlin/` (antes o source set `debug` só tinha o
`AndroidManifest.xml`).

### Como era antes

Não existia. Não havia nenhum caminho programático para entregar um QR ao app: só a câmera. E a
câmera virtual dos emuladores **não decodifica o QR real de pareamento** — provado na seção
"Residual finding" de `docs/development/device-verification.md` (T3.3 run3): 668 tentativas de
decode, 0 sucessos, com o QR nítido e independentemente decodificável pelo `zxing-cpp` a partir do
mesmo buffer. Resultado: o fluxo A -> B -> A era impossível de exercitar sem dois telefones reais e
um operador humano.

### Como ficou

Um `BroadcastReceiver` com duas ações, registrado **apenas** no manifesto de debug:

| Ação | Efeito |
|---|---|
| `dev.mx3.nomessages.debug.INJECT_QR` (extra `payload_b64`) | decodifica o base64 e entrega os bytes a `QrScannerHooks.scanSink`, ou seja, no mesmo ponto em que o decodificador entregaria o resultado da câmera |
| `dev.mx3.nomessages.debug.DUMP_QR` | escreve em `cacheDir/qr-shown.bin` os bytes de `QrScannerHooks.shownPayload?.invoke()` |

Detalhes de implementação que importam:

- **`DUMP_QR` escreve via arquivo temporário + `rename`**, e **remove** o arquivo quando não há QR
  na tela. Sem isso, o relay poderia ler um dump pela metade, ou pior, confundir o dump da rodada
  anterior com o da rodada atual — um falso positivo silencioso e caríssimo de depurar.
- **`INJECT_QR` lê o sink dentro do `Handler(mainLooper).post`**, não antes: se a tela de leitura
  foi fechada entre o broadcast e a execução, o resultado é um no-op em vez de uma chamada a um
  callback de uma composição já descartada. O `post` também garante que a escrita de estado do
  Compose ocorra na main thread.
- **O base64 recebido passa por `filterNot(Char::isWhitespace)`** antes de decodificar, porque o
  transporte é um argumento de linha de comando (`am broadcast --es`) que pode carregar quebras de
  linha do `base64` do aparelho e CR do Git Bash no Windows.

### A guarda de acesso: o que foi pedido, o que foi medido, e o que ficou

O pedido original era: processar o broadcast só se `Binder.getCallingUid()` fosse
`Process.SHELL_UID` (2000) ou `Process.ROOT_UID` (0). **Isso foi implementado, medido no aparelho, e
não funciona** — nem para aceitar, nem para recusar. Sonda temporária instalada num build de debug,
emulador Android 15 (API 35), broadcast disparado por `adb shell am broadcast`:

```
action=dev.mx3.nomessages.debug.DUMP_QR binder=10212 sent=-1 myUid=10212 shown=false sink=false
```

- `Binder.getCallingUid()` devolveu **10212**, o UID do próprio app — não 2000. É o comportamento
  documentado: `onReceive` roda num handler, sem transação binder ativa, e nesse caso a API devolve
  o UID do processo atual.
- `BroadcastReceiver.getSentFromUid()` (API 34+) devolveu **-1** (`INVALID_UID`), porque o
  remetente precisa optar por compartilhar a identidade via
  `BroadcastOptions.setShareIdentityEnabled(true)` — coisa que `am broadcast` não faz.

Uma guarda baseada só em UID, portanto, recusaria 100% dos broadcasts (hook inútil) e — o risco
real — convidaria alguém no futuro a "consertar" comparando com `Process.myUid()`, o que aceitaria
qualquer remetente. A intenção ("só shell/root") foi então implementada com mecanismos que de fato
entregam essa garantia, em três camadas:

1. **Existência só em debug.** Classe em `src/debug/kotlin`, `<receiver>` em
   `src/debug/AndroidManifest.xml`. Em release não existe nem a classe nem o componente.
2. **`android:permission="android.permission.WRITE_SECURE_SETTINGS"` no `<receiver>`** — imposta
   pelo **sistema operacional**, não pelo app. O `ActivityManager` recusa entregar o broadcast se o
   *remetente* não tiver a permissão. É `signature|privileged`: `com.android.shell` (uid 2000, quem
   executa `am broadcast` a partir do adb) e root a possuem; nenhum app de terceiros instalável
   consegue obtê-la. Esta camada é **mais forte** do que a checagem de UID pretendida, porque um app
   hostil nem chega a executar uma linha do receiver.
3. **Opt-in explícito por propriedade de debug**, `debug.nomessages.allow_qr_inject == "1"` — mesmo
   padrão já documentado de `debug.nomessages.allow_capture`. Escrever propriedades `debug.*` é
   restrito pelo SELinux aos domínios `shell`/`su`, então isso é na prática o mesmo teste de
   capacidade que a guarda de UID pretendia fazer, e ainda deixa o hook **desligado por padrão**.

A checagem de UID foi **mantida**, mas apenas com o poder que ela realmente tem: `reportedSenderUid()`
devolve `null` quando a plataforma não informa remetente, e `isTrustedDebugUid` é usada só para
**recusar** um remetente conhecido que não seja shell/root.

Quando qualquer camada recusa, o broadcast é descartado em silêncio: sem log, sem toast, sem
exceção, sem código de resultado. Um app hostil não consegue distinguir "existe e recusou" de
"não existe".

### Controle positivo e negativo (executados no aparelho)

```
$ adb -s emulator-5556 shell setprop debug.nomessages.allow_qr_inject 0
$ adb -s emulator-5556 shell run-as dev.mx3.nomessages.debug rm -f cache/qr-shown.bin
$ adb -s emulator-5556 shell am broadcast -a dev.mx3.nomessages.debug.DUMP_QR -n ...DebugQrReceiver
Broadcast completed: result=0
$ adb -s emulator-5556 shell run-as dev.mx3.nomessages.debug ls cache/qr-shown.bin
ls: cache/qr-shown.bin: No such file or directory          <-- recusado, e em silencio

$ adb -s emulator-5556 shell setprop debug.nomessages.allow_qr_inject 1
$ adb -s emulator-5556 shell am broadcast -a dev.mx3.nomessages.debug.DUMP_QR -n ...DebugQrReceiver
Broadcast completed: result=0
$ adb -s emulator-5556 shell run-as dev.mx3.nomessages.debug ls -l cache/qr-shown.bin
-rw------- 1 u0_a212 u0_a212_cache 2708 ... cache/qr-shown.bin   <-- aceito
```

Repare que a saída de `am broadcast` é **idêntica** nos dois casos (`result=0`): a recusa não vaza
nem pelo código de resultado.

### `build.gradle.kts` não precisou de mudança (verificado)

O AGP + Kotlin Android já incluem `src/<buildType>/kotlin` no source set por padrão — é o mesmo
mecanismo pelo qual `src/main/kotlin` já funciona neste projeto, que também não declara nenhum bloco
`sourceSets`. Confirmado na prática: `:app:assembleDebug` compilou `DebugQrReceiver.kt` sem
qualquer alteração no `build.gradle.kts`, e o `<receiver>` apareceu no manifesto mesclado de debug.
Nenhuma linha de build foi adicionada — o melhor tipo de mudança de build.

### Constantes de UID replicadas localmente (e por quê)

`Process.ROOT_UID` e `Process.SHELL_UID` são `@hide` no SDK público. Alcançá-las por reflexão
reintroduziria exatamente a fragilidade de hidden-API já documentada em
`docs/changes/MainActivity.kt.md` (onde a política de hidden-API do emulador chegou a bloquear o
`SystemProperties` até `settings put global hidden_api_policy 1`). Os valores fazem parte da ABI do
Android (`AID_ROOT` = 0, `AID_SHELL` = 2000) e foram replicados como constantes locais com esse
comentário.

### Vantagens

1. **Desbloqueia T3.3.** O pareamento completo A <-> B, que estava impossível desde run1, foi
   executado de ponta a ponta em 57 segundos com este hook (ver
   `docs/development/device-verification.md`, T3.3 run4).
2. **Zero impacto em release**, provado mecanicamente e não apenas afirmado (passo de prova no
   mesmo documento).
3. **Guarda mais forte que a pedida**, e — mais importante — **honesta**: o código diz no KDoc
   exatamente o que cada camada garante e o que a checagem de UID **não** garante, com a medição
   que sustenta a afirmação. Uma guarda que parece funcionar e não funciona é pior que nenhuma.
