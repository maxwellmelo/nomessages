# QrScannerHooks.kt

## 2026-09-15 — T3.3 run4: ponte debug-only para injetar/ler o QR de pareamento

Arquivo **novo**: `app/src/main/kotlin/dev/mx3/nomessages/ui/QrScannerHooks.kt`.

### Como era antes

Não existia. O único caminho para entregar um QR ao app era a câmera real (`CameraPreview` ->
`ImageProxy.decodeQr()` -> `decodeQrLuminance` -> `onScanned(result.text)`), e o único caminho para
ler o QR exibido era fotografar a tela.

Isso é um beco sem saída nos dois emuladores usados para T3.3: a câmera virtual **não decodifica o
QR real de pareamento**, conforme provado na seção "Residual finding" de
`docs/development/device-verification.md` (T3.3 run3) — 668 tentativas de decode, 0 sucessos, com o
QR nítido, centralizado e independentemente decodificável pelo `zxing-cpp` a partir do mesmo
buffer. A causa é a distorção projetiva fixa da virtual scene, que o detector clássico do ZXing
Java não corrige. Sem alternativa, o fluxo A -> B -> A não podia ser exercitado sem um operador
humano com dois telefones reais, o que inviabiliza CI e desenvolvimento local.

### Como ficou

Um objeto minúsculo com dois campos `@Volatile`, sem nenhuma lógica:

```kotlin
object QrScannerHooks {
    @Volatile var scanSink: ((ByteArray) -> Unit)? = null
    @Volatile var shownPayload: (() -> ByteArray?)? = null
}
```

- `scanSink` — registrado por `QrScanner` enquanto a tela de leitura está composta. Quem o invoca
  entrega bytes **no mesmo ponto** em que o decodificador entregaria: depois do decode, antes de
  `actions.readPairing(code)`.
- `shownPayload` — registrado por `PairingQr` enquanto um QR está na tela. Devolve os bytes do
  payload exibido.

### Por que o objeto vive em `src/main` e não em `src/debug`

`PairingScreen.kt` é código de `src/main` e é compilado em **todos** os build types. Se o objeto só
existisse em `src/debug`, o build de release não compilaria. A alternativa (duplicar
`PairingScreen.kt` no source set de debug) seria muito pior: duas cópias divergentes da tela mais
sensível do app, e o caminho testado deixaria de ser o caminho enviado.

### Por que isso continua seguro em release

O contrato é deliberadamente assimétrico, e está escrito no KDoc do objeto para que ninguém
"conserte" isso por engano no futuro:

| Papel | Quem faz | Onde vive | Existe em release? |
|---|---|---|---|
| **Registrar** um callback (`scanSink = ...`, `shownPayload = ...`) | `PairingScreen.kt` | `src/main` | Sim — mas é inofensivo |
| **Invocar/ler** o callback (`scanSink?.invoke(...)`, `shownPayload?.invoke()`) | `DebugQrReceiver` | `src/debug` | **Não** |

Publicar um callback não expõe nada: sem chamador, o callback nunca roda. E o **único** chamador do
projeto inteiro é `DebugQrReceiver`, que só existe em `app/src/debug/kotlin` e cujo `<receiver>` só
existe em `app/src/debug/AndroidManifest.xml`. Num APK de release não há classe nem componente
Android capaz de acionar a injeção: os dois campos ficam sendo apenas dois ponteiros de função que
ninguém nunca lê.

A prova é reproduzível e está registrada em `docs/development/device-verification.md` (T3.3 run4):
`:app:processReleaseManifest` seguido de `grep -r "DebugQrReceiver\|INJECT_QR"` no manifesto
mesclado de release retorna **vazio**, enquanto o mesmo grep no manifesto mesclado de debug
encontra o receiver.

### Por que `ByteArray` e não `String`

O tipo de transporte é `ByteArray` para que o que trafega entre os dois emuladores seja exatamente
o conteúdo binário do QR, sem nenhuma normalização de charset no caminho. A conversão para o
`String` que o app de fato consome acontece num único lugar (`QrScanner`), com ISO-8859-1 — o mesmo
charset que o ZXing usa por padrão tanto no `QRCodeWriter().encode` quanto no `Result.text`. Para o
payload ASCII `nomessages:1:...` o round-trip `payload -> bytes -> payload` é exato, o que o relay
verifica na prática comparando o `sha256` calculado no aparelho com o `sha256` do conteúdo
decodificado no host (ver `docs/changes/emulator-pair.sh.md`).

### Vantagens

1. **Desbloqueia T3.3 sem afrouxar nada em produção.** O caminho exercitado a partir de
   `onScanned(...)` é bit a bit o de produção: parsing, verificação de assinatura Ed25519,
   derivação do SAS e confirmação passam todos pelo código real.
2. **Superfície mínima.** Dois campos, zero lógica, zero dependências. Não há o que dar errado
   dentro do próprio objeto, e a auditoria se resume a `grep` por `scanSink`/`shownPayload`.
3. **Auditável por construção.** A regra "só `src/debug` invoca" é verificável mecanicamente com um
   `grep` e está afirmada no KDoc do próprio arquivo, junto do comando que prova a ausência em
   release.
