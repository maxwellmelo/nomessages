package dev.mx3.nomessages.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import dev.mx3.nomessages.readSystemProperty
import dev.mx3.nomessages.ui.QrScannerHooks
import java.io.File

/**
 * Receiver **debug-only** que permite relayar o QR de pareamento entre dois emuladores sem passar
 * pela câmera virtual.
 *
 * ## Por que existe
 *
 * Os emuladores usados para testar o pareamento não conseguem decodificar o QR real pela câmera
 * virtual (ver "Residual finding" em `docs/development/device-verification.md`, T3.3 run3: o
 * detector clássico do ZXing Java não lida com a distorção projetiva fixa da virtual scene).
 * Sem um caminho alternativo, o fluxo A -> B -> A não pode ser exercitado nem no CI nem
 * localmente. Este receiver injeta os bytes do QR exatamente no ponto onde o decodificador
 * entregaria o resultado, deixando **todo** o resto do fluxo (parsing, validação de assinatura,
 * derivação de SAS, confirmação) idêntico ao caminho de produção.
 *
 * ## Como o acesso é controlado (três camadas independentes)
 *
 * 1. **Só existe em debug.** Esta classe está em `app/src/debug/kotlin` e o `<receiver>` está
 *    declarado apenas em `app/src/debug/AndroidManifest.xml`. Nenhum dos dois é compilado ou
 *    mesclado num APK de release — provado por `:app:processReleaseManifest` + `grep`, ver
 *    `docs/security-model.md`.
 * 2. **Permissão no manifesto, imposta pelo SO.** O `<receiver>` exige
 *    `android.permission.WRITE_SECURE_SETTINGS` do **remetente**. É uma permissão
 *    `signature|privileged`: `com.android.shell` (uid 2000, que é quem executa `am broadcast` a
 *    partir do adb) e root a possuem; nenhum app de terceiros instalável consegue obtê-la. Esta é
 *    a camada forte: o `ActivityManager` simplesmente não entrega o broadcast a este receiver se o
 *    remetente não tiver a permissão, então um app hostil nunca chega a executar uma linha daqui.
 * 3. **Opt-in explícito por propriedade de debug.** Mesmo vindo do shell, o hook fica inerte a não
 *    ser que `debug.nomessages.allow_qr_inject` seja exatamente `"1"`. Escrever propriedades `debug.*`
 *    é restrito pelo SELinux aos domínios `shell`/`su`, então isso é, na prática, o mesmo teste de
 *    capacidade que a guarda de UID pretendia fazer — e segue o padrão já documentado de
 *    `debug.nomessages.allow_capture` (ver `docs/security-model.md`).
 *
 * Quando alguma camada recusa, o broadcast é descartado **silenciosamente**: sem log, sem toast,
 * sem exceção, sem código de resultado. Um app hostil não consegue nem distinguir "existe e
 * recusou" de "não existe".
 *
 * ## Por que a guarda de UID sozinha não funciona (medido, não suposto)
 *
 * A ideia original era recusar tudo que não viesse de `Process.SHELL_UID`/`ROOT_UID` lido de
 * [Binder.getCallingUid]. Medido neste projeto, em emulador Android 15 (API 35), com o broadcast
 * disparado por `adb shell am broadcast`:
 *
 * ```
 * action=dev.mx3.nomessages.debug.DUMP_QR binder=10212 sent=-1 myUid=10212
 * ```
 *
 * Ou seja: `Binder.getCallingUid()` devolveu o UID do **próprio app** (10212), não 2000, porque o
 * `onReceive` roda num handler sem transação binder ativa — e nesse caso a API, por contrato,
 * devolve o UID do processo atual. E [BroadcastReceiver.getSentFromUid] (API 34+) devolveu `-1`,
 * porque o remetente precisa optar por compartilhar a identidade
 * (`BroadcastOptions.setShareIdentityEnabled(true)`), coisa que `am broadcast` não faz.
 *
 * Portanto uma guarda baseada só em UID recusaria **todos** os broadcasts (hook inútil) e, pior,
 * daria uma falsa sensação de proteção se alguém "consertasse" comparando com `Process.myUid()`.
 * A checagem de UID foi mantida em [isTrustedDebugUid], mas apenas com o poder que ela realmente
 * tem: **recusar** um remetente conhecido que não seja shell/root. Quando a plataforma não informa
 * remetente nenhum, quem decide são as camadas 2 e 3.
 */
class DebugQrReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Falha fechada: qualquer dúvida sobre a identidade do remetente descarta o broadcast.
        if (!isTrustedSender()) return

        when (intent.action) {
            ACTION_INJECT_QR -> injectScannedPayload(intent)
            ACTION_DUMP_QR -> dumpShownPayload(context)
            else -> Unit
        }
    }

    /**
     * Entrega os bytes recebidos ao `scanSink` registrado pela tela de leitura.
     *
     * O sink é lido dentro do `post` (e não agora) para que a tela de leitura ter sido fechada
     * entre o broadcast e a execução resulte num no-op, e não numa chamada a um callback de uma
     * composição já descartada. O `post` no main looper também garante que a escrita de estado do
     * Compose aconteça na thread correta, independentemente de qual thread entregou o broadcast.
     */
    private fun injectScannedPayload(intent: Intent) {
        val encoded = intent.getStringExtra(EXTRA_PAYLOAD_B64) ?: return
        // O transporte passa por `am broadcast --es`, um argumento de linha de comando: quebras de
        // linha do `base64` do aparelho e CR do Git Bash no Windows podem sobrar. Removê-las aqui
        // evita depender do comportamento do decoder quanto a espaços em branco.
        val compact = encoded.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return
        val payload = runCatching { Base64.decode(compact, Base64.DEFAULT) }.getOrNull() ?: return
        if (payload.isEmpty()) return

        Handler(Looper.getMainLooper()).post {
            QrScannerHooks.scanSink?.invoke(payload)
        }
    }

    /**
     * Escreve em `cacheDir/qr-shown.bin` os bytes do QR atualmente exibido.
     *
     * Escreve primeiro num arquivo temporário e renomeia: o script de relay lê este arquivo logo
     * após o broadcast, e um `rename` dentro do mesmo diretório é atômico o suficiente para que
     * ele nunca leia um arquivo pela metade. Quando não há QR na tela o arquivo é **removido** em
     * vez de mantido, para que o relay não confunda um dump antigo com um dump novo.
     */
    private fun dumpShownPayload(context: Context) {
        val target = File(context.cacheDir, DUMP_FILE_NAME)
        val payload = QrScannerHooks.shownPayload?.invoke()
        runCatching {
            if (payload == null || payload.isEmpty()) {
                target.delete()
            } else {
                val staging = File(context.cacheDir, "$DUMP_FILE_NAME.tmp")
                staging.writeBytes(payload)
                if (!staging.renameTo(target)) {
                    target.writeBytes(payload)
                    staging.delete()
                }
            }
        }
    }

    /**
     * Camadas 3 e 2b da guarda descrita no KDoc da classe (a camada 2 principal é imposta pelo SO
     * antes de chegarmos aqui).
     */
    private fun isTrustedSender(): Boolean {
        if (readSystemProperty(ALLOW_PROPERTY, DISABLED) != ENABLED) return false
        val reported = reportedSenderUid()
        return reported == null || isTrustedDebugUid(reported)
    }

    /**
     * UID do remetente **quando a plataforma realmente o informa**, ou `null` quando ela não tem
     * essa informação — caso em que o valor não pode ser usado nem para aceitar nem para recusar.
     *
     * Ver o KDoc da classe para a medição que mostra que, para `am broadcast`, as duas fontes ficam
     * indisponíveis.
     */
    private fun reportedSenderUid(): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val shared = runCatching { sentFromUid }.getOrDefault(UNKNOWN_UID)
            if (shared != UNKNOWN_UID) return shared
        }
        val binderUid = Binder.getCallingUid()
        // Fora de uma transação binder este valor degrada para o UID do próprio processo; nesse
        // caso ele não diz nada sobre o remetente.
        return if (binderUid != Process.myUid()) binderUid else null
    }

    private companion object {
        const val ACTION_INJECT_QR = "dev.mx3.nomessages.debug.INJECT_QR"
        const val ACTION_DUMP_QR = "dev.mx3.nomessages.debug.DUMP_QR"
        const val EXTRA_PAYLOAD_B64 = "payload_b64"
        const val DUMP_FILE_NAME = "qr-shown.bin"

        /** Mesmo padrão de `debug.nomessages.allow_capture`: opt-in manual, local, só via adb/root. */
        const val ALLOW_PROPERTY = "debug.nomessages.allow_qr_inject"
        const val ENABLED = "1"
        const val DISABLED = "0"

        /**
         * Valores de `android.os.Process.ROOT_UID` / `Process.SHELL_UID`, replicados como
         * constantes locais: essas duas constantes são `@hide` no SDK público, e alcançá-las por
         * reflexão introduziria exatamente o tipo de fragilidade de hidden-API já documentada em
         * `docs/changes/MainActivity.kt.md`. Os valores fazem parte da ABI do Android
         * (`AID_ROOT` = 0, `AID_SHELL` = 2000).
         */
        const val ROOT_UID = 0
        const val SHELL_UID = 2000

        /** Sentinela da plataforma para "remetente não informado" (`Process.INVALID_UID`). */
        const val UNKNOWN_UID = -1

        fun isTrustedDebugUid(uid: Int): Boolean = uid == ROOT_UID || uid == SHELL_UID
    }
}
