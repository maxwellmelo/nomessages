package dev.mx3.nomessages.ui

/**
 * Ponte de instrumentação **debug-only** entre o source set `src/debug` e a tela de pareamento.
 *
 * ## Por que este objeto vive em `src/main` e não em `src/debug`
 *
 * `PairingScreen.kt` (que é código de `src/main`, compilado em TODOS os build types) precisa
 * registrar/limpar os dois campos abaixo. Se o objeto existisse apenas em `src/debug`, o build de
 * release não compilaria. Portanto o objeto existe em `main` — mas **nada em `main` jamais
 * ESCREVE um valor não-nulo que venha de fora da própria UI**, e nada em `main` jamais invoca
 * [scanSink] nem [shownPayload].
 *
 * ## Garantia de segurança em release
 *
 * O contrato é assimétrico de propósito:
 *
 * - **Quem registra** (`PairingScreen.kt`, em `main`): apenas *publica* um callback enquanto o
 *   composable correspondente está vivo, e o limpa em `onDispose`. Publicar um callback não
 *   expõe nada: sem um chamador, o callback nunca roda.
 * - **Quem chama/lê** (`DebugQrReceiver`, exclusivamente em `src/debug`): é o **único** ponto do
 *   projeto inteiro que faz `scanSink?.invoke(...)` ou `shownPayload?.invoke()`.
 *
 * Em um APK de release a classe `DebugQrReceiver` não existe (o source set `src/debug` não é
 * compilado, e o `<receiver>` só está declarado em `app/src/debug/AndroidManifest.xml`), então não
 * há nenhum caminho de código — nem componente Android exportado — capaz de acionar a injeção ou
 * a leitura do payload. Os campos continuam sendo escritos pelos composables, mas permanecem
 * inertes: são apenas dois ponteiros de função que ninguém nunca lê.
 *
 * A prova disso é reproduzível: `:app:processReleaseManifest` seguido de um `grep` por
 * `DebugQrReceiver`/`INJECT_QR` no manifesto mesclado de release retorna vazio (ver
 * `docs/security-model.md`, nota "Debug-only QR injection hook (2026-09-15)", e
 * `docs/development/device-verification.md`, seção T3.3 run4).
 *
 * ## Contrato dos bytes
 *
 * O tipo é `ByteArray` (não `String`) para que o transporte entre emuladores seja exatamente o
 * conteúdo binário do QR, sem nenhuma normalização de charset no meio do caminho. A conversão
 * para o `String` que o app realmente consome acontece num único ponto, em `QrScanner`, usando
 * ISO-8859-1 — o mesmo charset que o ZXing usa por padrão tanto no `QRCodeWriter().encode` quanto
 * no `Result.text` produzido pelo decoder, o que torna o round-trip
 * `payload -> bytes -> payload` exato para o payload ASCII `nomessages:1:...`.
 */
object QrScannerHooks {
    /**
     * Recebe os bytes de um QR "lido" e os entrega ao mesmo callback `onScanned` que a câmera real
     * usaria — ou seja, depois do decode e antes de `actions.readPairing(...)`.
     *
     * Registrado por `QrScanner` enquanto a tela de leitura está composta; `null` em qualquer
     * outro momento (e sempre `null` na prática em release, onde ninguém o invoca).
     */
    @Volatile
    var scanSink: ((ByteArray) -> Unit)? = null

    /**
     * Devolve os bytes do payload do QR que está sendo exibido no momento (oferta, resposta ou
     * confirmação — todos passam pelo mesmo composable `PairingQr`), ou `null` se nenhum QR está
     * na tela.
     *
     * Registrado por `PairingQr` enquanto o QR está composto.
     */
    @Volatile
    var shownPayload: (() -> ByteArray?)? = null
}
