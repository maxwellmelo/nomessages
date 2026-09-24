# AndroidManifest.xml — mudanças

## 2026-09-14 — Fechamento do erro de lint `MissingClass` (T1.4)

### Como era antes

```xml
<!-- Use system emoji; never initialize a downloadable font provider. -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.emoji2.text.EmojiCompatInitializer"
        tools:node="remove" />
</provider>
```

O `:app:lintDebug` — que rodou até o fim pela primeira vez neste ciclo — falhava
com erro:

> `MissingClass`: Class referenced in the manifest,
> `androidx.startup.InitializationProvider`, was not found in the project or the
> libraries — `AndroidManifest.xml:45`

### Como ficou

O bloco continua igual em comportamento. Só foi acrescentado
`tools:ignore="MissingClass"` no elemento `<provider>` e um comentário longo
explicando os três pontos que sustentam a decisão (classe realmente existe em
runtime, por que o `meta-data` de remoção é obrigatório, e por que remover o
provider inteiro seria errado).

### Por que essa correção e não outra

Foram avaliadas três opções:

1. **`tools:node="remove"` no provider inteiro.** **Rejeitada.** O manifesto
   mesclado (`app/build/intermediates/merged_manifest/debug/.../AndroidManifest.xml`)
   mostra que o mesmo provider também carrega
   `androidx.lifecycle.ProcessLifecycleInitializer` e
   `androidx.profileinstaller.ProfileInstallerInitializer`. Remover o provider
   desligaria os dois silenciosamente.
2. **Declarar `androidx.startup:startup-runtime` como dependência direta.**
   **Rejeitada.** Resolveria o lint, mas só para satisfazer um verificador:
   a classe já está no APK. Além disso exigiria mexer em
   `gradle/libs.versions.toml`, editado por outro agente neste mesmo ciclo.
3. **`tools:ignore="MissingClass"` com justificativa.** **Escolhida.**

### Diagnóstico do falso positivo

`androidx.startup:startup-runtime:1.1.1` chega ao app como dependência
transitiva de **escopo runtime** (`lifecycle-process`, `profileinstaller`) —
confirmado em
`app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel/debug-artifact-libraries.xml`.
O lint resolve nomes de classe do manifesto contra o **compile classpath**, onde
dependências transitivas de escopo runtime não aparecem. Logo: a classe existe
no APK e não existe para o lint. É um erro de escopo, não um erro do manifesto.

### Por que o `meta-data` de remoção foi preservado

`androidx.emoji2:emoji2:1.4.0` **está** no grafo de dependências. Sem o
`tools:node="remove"`, o manifesto mesclado registraria o
`EmojiCompatInitializer`, que inicializa um provedor de fontes baixáveis — o
oposto da intenção de segurança registrada em `docs/development/ui-report.md`.
Essa garantia não é só de revisão: `scripts/verify-apk.py:140` reprova o APK se
`androidx.emoji2.text.EmojiCompatInitializer` aparecer no manifesto empacotado.

### Vantagens

- `:app:lintDebug` passa a terminar com **zero erros** (critério de T1.4).
- A garantia de segurança "nunca inicializar provedor de fontes baixáveis"
  permanece intacta e continua verificada pelo `verify-apk.py`.
- A supressão é do escopo mais estreito possível (um elemento, um id de check) e
  vem com a justificativa no próprio arquivo — nenhum baseline de lint, nenhum
  check global desabilitado.
- Os inicializadores de lifecycle e profileinstaller continuam funcionando.

---

## 2026-09-15 — T3.3 run4: `<receiver>` debug-only no manifesto de debug (`app/src/debug/AndroidManifest.xml`)

> Atenção: esta seção documenta **`app/src/debug/AndroidManifest.xml`**, não o manifesto principal.
> O manifesto de `app/src/main/` **não foi tocado** nesta mudança — e isso é parte do ponto.

### Como era antes

O manifesto de debug existia, mas só para remover uma `activity`:

```xml
<manifest ...>
    <application>
        <!-- Keep the secure app activity as the only entry point in debug too. -->
        <activity
            android:name="androidx.compose.ui.tooling.PreviewActivity"
            tools:node="remove" />
    </application>
</manifest>
```

### Como ficou

O mesmo bloco, mais um `<receiver>` (com um comentário longo explicando as três camadas de guarda):

```xml
<receiver
    android:name="dev.mx3.nomessages.debug.DebugQrReceiver"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.WRITE_SECURE_SETTINGS"
    tools:ignore="ExportedReceiver">
    <intent-filter>
        <action android:name="dev.mx3.nomessages.debug.INJECT_QR" />
        <action android:name="dev.mx3.nomessages.debug.DUMP_QR" />
    </intent-filter>
</receiver>
```

### Por que `android:permission` e não só uma checagem em código

A guarda originalmente planejada (comparar `Binder.getCallingUid()` com 2000/0) foi implementada,
**medida no aparelho e reprovada**: em API 35, dentro de `onReceive`, `Binder.getCallingUid()`
devolve o UID do próprio app e `getSentFromUid()` devolve `-1`. A medição completa está em
`docs/changes/DebugQrReceiver.kt.md`.

`android:permission="android.permission.WRITE_SECURE_SETTINGS"` entrega a mesma intenção com uma
garantia **mais forte**, porque é imposta pelo `ActivityManager` antes de o app rodar qualquer
linha: o broadcast só é entregue se o **remetente** tiver a permissão. Ela é `signature|privileged`
— `com.android.shell` (uid 2000, quem executa `am broadcast` a partir do adb) e root a têm; nenhum
app de terceiros instalável consegue obtê-la. Verificado no aparelho:
`dumpsys package com.android.shell` lista `WRITE_SECURE_SETTINGS`, e o `am broadcast` do adb
continuou funcionando normalmente depois de a permissão ser declarada.

`tools:ignore="ExportedReceiver"` fica, mas agora por um motivo diferente e mais honesto: o lint
avisa sobre receiver exportado sem permissão, e a permissão **está** presente — o aviso é que não
enxerga o conjunto (componente debug-only + permissão privilegiada + opt-in por propriedade).

### Prova de que nada disso chega ao release (reproduzível)

```
$ wsl -d Ubuntu -- bash .../gradle-wsl.sh :app:processReleaseManifest --console=plain
BUILD SUCCESSFUL

$ grep -r "DebugQrReceiver\|INJECT_QR" app/build/intermediates/merged_manifest/release/
(vazio, exit=1)

$ grep -rc "WRITE_SECURE_SETTINGS" app/build/intermediates/merged_manifest/release/
app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml:0

$ grep -rc "DebugQrReceiver\|INJECT_QR" app/build/intermediates/merged_manifest/debug/
app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml:5
```

Saída completa salva em
`docs/development/build-logs/two-emulator-20260915/run3/60-release-manifest-proof.txt`.

### Vantagens

1. **Isolamento estrutural, não por convenção.** O componente não é "desligado" em release: ele
   simplesmente não existe no manifesto mesclado, o que é verificável por um `grep` e não depende
   de ninguém lembrar de uma flag.
2. **Permissão imposta pelo SO** em vez de uma checagem de UID em código que, medida, não funciona.
3. O manifesto principal segue intocado, então nenhuma das asserções que `scripts/verify-apk.py`
   faz sobre o manifesto empacotado mudou.

---

## 2026-09-16 — Permissão normal `DETECT_SCREEN_CAPTURE` (T4.9)

### Motivo

`Activity.registerScreenCaptureCallback` (API 34+) exige a permissão normal
`android.permission.DETECT_SCREEN_CAPTURE` declarada no manifesto para notificar o app quando o
sistema detecta uma tentativa de captura de tela — a peça de detecção do item 5 de T4.9, que
complementa (nunca substitui) o bloqueio já feito por `FLAG_SECURE`.

### Como era antes

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Como ficou

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Normal permission (API 34+): lets registerScreenCaptureCallback notify this app when the
     system detects a screenshot/recording attempt, for the non-blocking notice in MainActivity.
     FLAG_SECURE (see MainActivity.shouldApplySecureFlag) already blocks the capture itself; this
     is detection-only, for whenever a screenshot/recording is still attempted. -->
<uses-permission android:name="android.permission.DETECT_SCREEN_CAPTURE" />
```

Adicionada só ao manifesto principal (`app/src/main/AndroidManifest.xml`), nunca ao manifesto
debug.

### Vantagens

- Permissão `normal` (não perigosa): nenhum diálogo de runtime, concedida automaticamente na
  instalação — não adiciona fricção nenhuma ao usuário.
- É estritamente detecção: o registro/uso está guardado por `Build.VERSION.SDK_INT >= 34` em
  `MainActivity.kt`, então em API < 34 a permissão fica declarada mas inerte.

### Por que a mudança foi feita

T4.9, item 5.


## 2026-09-18 — T4.17 fase 3: foreground service do processo `:tor`

### Motivo

Em modo mínimo (cofre bloqueado, campainha servindo) o `:tor` é um processo em background comum e o
sistema o recicla quando quiser — a campainha simplesmente pararia de responder. A única forma
suportada de impedir isso é um foreground service, e a partir do targetSdk 34 ele exige tipo
declarado no manifesto e permissão correspondente.

### Como era antes

```xml
<service
    android:name=".runtime.TorService"
    android:directBootAware="false"
    android:exported="false"
    android:process=":tor"
    android:stopWithTask="true" />
```

Sem `FOREGROUND_SERVICE*` entre as permissões.

### Como ficou

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
...
<service
    android:name=".runtime.TorService"
    android:directBootAware="false"
    android:exported="false"
    android:foregroundServiceType="specialUse"
    android:process=":tor"
    android:stopWithTask="true">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="background_transport" />
</service>
```

### Decisões

- **`specialUse` e não `dataSync`/`remoteMessaging`.** O serviço segura um onion em escuta, que não é
  sincronização de dados nem sessão de mídia; e `dataSync` é justamente o tipo que o Android 15
  colocou em relógio (teto de 6 h a cada 24 h), o que mataria a campainha de um aparelho que ficasse
  bloqueado a noite inteira. `remoteMessaging` seria defensável, mas declara no manifesto — legível
  por qualquer um que abra o APK — que este app troca mensagens; `specialUse` com subtipo neutro
  diz menos.
- **Subtipo `background_transport`**, pelo mesmo motivo do nome do canal de notificação: neutro.
- **As duas permissões são `normal`**, concedidas na instalação; nada de novo é pedido ao usuário em
  tempo de execução.
- **`stopWithTask` continua `true`.** Deslizar o app para fora dos recentes deve continuar levando o
  transporte junto, com campainha ou sem ela: é uma propriedade de privacidade existente e o
  usuário que faz isso está pedindo exatamente que nada fique rodando.
- **O serviço não é foreground o tempo todo.** A promoção acontece só em `DOORBELL_MINIMAL` e é
  desfeita no `START` seguinte, no `DOORBELL_STOP` e no `terminate()` (ver
  `docs/changes/TorService.kt.md`). Durante o uso normal o filho já herda prioridade do vínculo com o
  processo em primeiro plano, e uma notificação permanente ali seria pura exposição.

### Vantagens

- A campainha sobrevive ao bloqueio sem que o app precise ser foreground enquanto está em uso.
- O tipo declarado é o único que não está sujeito aos limites de tempo do Android 15.
- Nenhuma permissão perigosa nova; nenhuma mudança no comportamento de `stopWithTask`.

### Por que a mudança foi feita

T4.17 fase 3, item 2.
