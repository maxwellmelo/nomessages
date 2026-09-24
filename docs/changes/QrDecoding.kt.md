# QrDecoding.kt

## 2026-09-15 — Novo arquivo: núcleo puro de decodificação de QR, extraído de PairingScreen.kt

Contexto completo da investigação (evidência, hipóteses descartadas, achado real): ver
`docs/development/device-verification.md` (seção "T3.3 — run3") e
`docs/changes/PairingScreen.kt.md` (mesma data). Este documento cobre especificamente o arquivo novo.

### Como era antes

Não existia. A lógica de decodificação (montar `PlanarYUVLuminanceSource`, tentar rotação, chamar o
`MultiFormatReader`) vivia inteiramente dentro da extensão privada `ImageProxy.decodeQr()` em
`PairingScreen.kt`, acoplada à Android (`ImageProxy`) e impossível de testar num teste JVM puro
sem um `Robolectric`/instrumentado.

### Como ficou

`decodeQrLuminance(data: ByteArray, dataWidth: Int, width: Int, height: Int): Result?` — uma função
pura, sem qualquer dependência de Android, que recebe apenas o plano de luminância (Y) já extraído e
devolve o `Result` do ZXing ou `null`. `PairingScreen.kt` passou a apenas extrair os bytes do
`ImageProxy` (código inalterado nessa parte) e delegar a decodificação de fato para esta função.

```kotlin
internal fun decodeQrLuminance(data: ByteArray, dataWidth: Int, width: Int, height: Int): Result? {
    val reader = MultiFormatReader()
    reader.setHints(/* POSSIBLE_FORMATS=QR_CODE, TRY_HARDER=true */)
    try {
        var currentData = if (dataWidth == width) data else compact(data, dataWidth, width, height)
        var currentWidth = width
        var currentHeight = height
        for (rotation in 0..3) {
            if (rotation > 0) {
                currentData = rotateClockwise90(currentData, currentWidth, currentHeight)
                val swapped = currentWidth; currentWidth = currentHeight; currentHeight = swapped
            }
            val source = PlanarYUVLuminanceSource(currentData, currentWidth, currentHeight, 0, 0, currentWidth, currentHeight, false)
            for (candidate in listOf(source, source.invert())) {
                val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))) }.getOrNull()
                reader.reset()
                if (result != null) return result
            }
        }
        return null
    } finally {
        reader.reset()
    }
}
```

Tenta as 4 rotações (0/90/180/270°) × 2 polaridades (normal/invertida) = até 8 combinações, cada uma
com `TRY_HARDER` ligado e `MultiFormatReader.reset()` entre tentativas (ambos exigidos pela tarefa).

### O bug real que motivou a extração (e não apenas "adicionar mais rotações")

A implementação original em `PairingScreen.kt` já *tentava* uma rotação:

```kotlin
val source = PlanarYUVLuminanceSource(data, dataWidth, height, 0, 0, width, height, false)
runCatching { activeReader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
    ?: if (source.isRotateSupported) runCatching { ... source.rotateCounterClockwise() ... }.getOrNull() else null
```

Isso parecia razoável — e é exatamente o padrão sugerido por vários tutoriais de ZXing+CameraX
disponíveis publicamente. **O problema:** `PlanarYUVLuminanceSource`, na versão do zxing-core que
este projeto usa (`com.google.zxing:core:3.5.4`, `gradle/libs.versions.toml`), **não sobrescreve**
`isRotateSupported()` nem `rotateCounterClockwise()`. Isso foi confirmado desmontando a classe do
jar de verdade (não por suposição):

```
$ jar xf core-3.5.4.jar com/google/zxing/PlanarYUVLuminanceSource.class
$ javap -p com/google/zxing/PlanarYUVLuminanceSource.class
public final class com.google.zxing.PlanarYUVLuminanceSource extends com.google.zxing.LuminanceSource {
  ...
  public byte[] getRow(int, byte[]);
  public byte[] getMatrix();
  public boolean isCropSupported();
  public com.google.zxing.LuminanceSource crop(int, int, int, int);
  public int[] renderThumbnail();
  public int getThumbnailWidth();
  public int getThumbnailHeight();
  private void reverseHorizontal(int, int);
}
```

Sem overrides de rotação, a classe herda o comportamento padrão de `LuminanceSource`:
`isRotateSupported()` retorna `false` e `rotateCounterClockwise()` lançaria exceção se fosse
chamado. Ou seja: `if (source.isRotateSupported) ...` **nunca era verdadeiro**, e o "fallback de
rotação" da implementação original era código morto — nunca rodava, em nenhuma execução, desde
sempre. Isso foi provado ao vivo: um dump de depuração do frame real de análise (1280×720,
`rotationDegrees=90`) capturado no emulador durante um scan mostrou um QR completo, nítido, de alto
contraste (verificado como decodificável por uma biblioteca independente, `zxing-cpp`, via Python) —
e mesmo assim `decodeQrLuminance`/a função antiga devolviam `null`, porque a rotação de fato
necessária (o frame chega girado, ~-89° segundo a mesma verificação independente) nunca era tentada.

A correção: a rotação agora é feita manualmente, byte a byte, sobre o buffer de luminância bruto
(`rotateClockwise90`, função privada neste arquivo) — sem depender de nenhum método de rotação do
`LuminanceSource`/`PlanarYUVLuminanceSource`. Também foi adicionada a tentativa de imagem invertida
(`LuminanceSource.invert()`, que É genérico e funciona independente do tipo concreto da fonte) em
cada uma das 4 rotações, atendendo ao pedido da tarefa de também cobrir a possibilidade de uma fonte
de câmera entregar luminância invertida.

### Vantagens

- Corrige um bug real e antigo (rotação nunca tentada), não apenas uma limitação teórica de
  "poderia tentar mais rotações" — a evidência (dump do frame + verificação cruzada com
  `zxing-cpp`) prova que o frame do emulador chega genuinely rotacionado e que só isso já bastava
  para nunca decodificar, independente de resolução.
- Função pura, sem `android.*`, agora testável em um teste JVM comum (`QrDecodingTest.kt`) sem
  Robolectric/instrumentação — muito mais rápido de rodar e de depurar.
- Reaproveitável: qualquer outra tela que precise decodificar QR a partir de um plano de luminância
  (não só `PairingScreen`) pode chamar a mesma função sem duplicar a lógica de rotação/inversão.
- Mantém tudo que a tarefa pediu para preservar: `TRY_HARDER` ligado, `MultiFormatReader.reset()`
  entre tentativas, e o buffer de origem é zerado pelo chamador (`ImageProxy.decodeQr()` em
  `PairingScreen.kt`) depois da chamada, como antes.

### Limitação residual encontrada (não corrigida por este arquivo — ver PairingScreen.kt.md)

Mesmo depois desta correção, o frame específico capturado no emulador (câmera virtual, poster
"wall", macro `Walk_to_image_room`) ainda não decodifica pelo ZXing Java clássico em nenhuma das 8
combinações de rotação/inversão — apesar de o mesmíssimo buffer de bytes decodificar de imediato
com `zxing-cpp` (Python). A geometria dos 4 cantos do QR detectados pelo `zxing-cpp` mostra uma
distorção projetiva residual pequena, mas real (~1.65° de não-paralelismo entre lados opostos),
compatível com o ângulo fixo da câmera virtual da cena. Isso é consistente com uma limitação
conhecida do detector clássico do ZXing Java (mais sensível a distorção de perspectiva que
detectores mais modernos como o `zxing-cpp`/ML Kit) e não é algo que rotação/inversão/resolução
consigam corrigir — corrigir isso exigiria estimar e desfazer uma homografia, fora do escopo desta
tarefa. Ver o status detalhado em `docs/development/device-verification.md`.

### Por que a mudança foi feita

T3.3 (continuação): diagnosticar e corrigir, com evidência concreta, por que o scanner de QR nunca
decodifica nos emuladores (real e trivial), conforme pedido pela tarefa desta sessão.
