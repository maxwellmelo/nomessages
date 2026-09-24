# QrDecodingTest.kt

## 2026-09-15 — Novo arquivo: teste JVM de `decodeQrLuminance()` com um QR versão-40-L real

Contexto completo: `docs/development/device-verification.md` ("T3.3 — run3") e
`docs/changes/QrDecoding.kt.md`. Este documento cobre especificamente o arquivo de teste novo.

### Como era antes

Não existia nenhum teste automatizado para a decodificação de QR — a única cobertura possível era
manual, via emulador (lento, ~1 min por ciclo, e como esta sessão provou, nem sempre representativo
da causa real de uma falha).

### Como ficou

`app/src/test/kotlin/dev/mx3/nomessages/ui/QrDecodingTest.kt`, um teste JUnit 5 comum (roda em
`:app:testDebugUnitTest`, sem emulador/Robolectric) com 4 casos:

1. **Sanity check do fixture:** gera um payload de 2900 caracteres do alfabeto Base64 (força modo
   "byte" do ZXing, já que o alfabeto tem minúsculas — o modo "Alphanumeric" do QR não tem — assim
   como o payload real `nomessages:1:...`, que é texto derivado de binário/base64, não alfanumérico
   restrito) e confirma que ele realmente produz uma matriz de 177+2×margem módulos por lado
   (versão 40, forçada explicitamente via `EncodeHintType.QR_VERSION` porque o otimizador de modo do
   próprio ZXing por vezes reduz um payload deste tamanho para a versão 39).
2. **1920×1080 decodifica:** renderiza a matriz (via nearest-neighbor) centralizada em ~1000px
   dentro de um frame de luminância 1920×1080 (a resolução que `PairingScreen.kt` agora pede via
   `ResolutionSelector`) e confirma que `decodeQrLuminance()` recupera exatamente o payload
   original.
3. **640×480 documenta o requisito de resolução, sem travar o build:** em vez de simplesmente
   `assertNull`/`assertNotNull` a decodificação em 640×480 (o que seria uma suposição não
   verificada, ou uma trava frágil caso o comportamento do ZXing mude), o teste calcula os
   pixels-por-módulo reais possíveis a 640×480 (mesmo preenchendo quase toda a altura do frame) e
   usa `Assumptions.assumeTrue(pxPerModule >= 3.0, "<mensagem explicando o porquê>")`. Hoje isso
   sempre aborta o teste (pulo documentado, não falha, não passo silencioso) com uma mensagem que
   explica exatamente por que 640×480 é insuficiente para um QR versão 40 — o teste "torna legível"
   o requisito de resolução, como pedido pela tarefa, em vez de apenas afirmar um resultado.
4. **Rotação de 90°:** rotaciona manualmente (função própria do teste, sem depender de
   `LuminanceSource.rotateCounterClockwise()` — ver `QrDecoding.kt.md` para o porquê disso importar)
   o frame 1920×1080 já renderizado e confirma que `decodeQrLuminance()` ainda recupera o payload
   correto a partir do frame girado, provando que o fallback de rotação de fato funciona (o oposto
   do bug real encontrado na implementação antiga).

```kotlin
@Test
fun `640x480 frame is below the recommended pixels-per-module for a version-40-L QR`() {
    val qrPixelSize = 460
    val pxPerModule = qrPixelSize.toDouble() / matrix.width
    assumeTrue(pxPerModule >= minRecommendedPxPerModule, "640x480 gives only %.2f px/module ...")
    // Inalcançável hoje; roda de verdade se a versão do QR encolher ou a resolução mínima crescer.
    val frame = rasterize(matrix, 640, 480, qrPixelSize)
    assertNotNull(decodeQrLuminance(frame, dataWidth = 640, width = 640, height = 480))
}
```

### Vantagens

- Cobre exatamente a classe de tamanho do payload real de pareamento (versão 40-L, ~2.9 KB), não
  um QR trivial — a diferença que motivou esta investigação inteira.
- Roda em segundos, sem emulador, como parte normal de `:app:testDebugUnitTest` — qualquer regressão
  futura em `decodeQrLuminance()` (resolução, rotação, ou o próprio ZXing) é pega imediatamente,
  sem precisar repetir o ciclo de diagnóstico manual desta sessão.
- O caso de 640×480 documenta o requisito de resolução de forma legível e viva (a mensagem de
  `assumeTrue` é o argumento por extenso de por que `ResolutionSelector` é necessário), em vez de
  um comentário estático que pode ficar desatualizado.
- O caso de rotação prova, de forma automatizada, que a correção do bug real (fallback de rotação
  morto — ver `QrDecoding.kt.md`) realmente funciona, não apenas que "parece razoável".

### Por que a mudança foi feita

T3.3 (continuação): a tarefa desta sessão pediu explicitamente um teste JVM cobrindo um QR
versão-40-L real, incluindo um caso de resolução insuficiente (documentado, não silenciosamente
ignorado) e um caso de rotação.
