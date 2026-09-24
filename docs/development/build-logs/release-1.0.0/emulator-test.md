# Teste funcional do APK de release 1.0.0 no emulator-5556

Data: 2026-09-23
Dispositivo: `emulator-5556` (sdk_gphone64_x86_64, já em execução antes do teste)
APK testado: `app/build/outputs/apk/release/app-release.apk` (SHA-256 `dfadbcb7c500e25cd9e46a5d5c916e5b3534a4cb5edd278687e6a4751343d729`)

## Passos executados

1. **Instalação lado a lado.** `adb -s emulator-5556 install app-release.apk` — sucesso. O app de debug
   (`dev.mx3.nomessages.debug` e `dev.mx3.nomessages.debug.test`) permaneceu instalado sem conflito; o
   pacote `dev.mx3.nomessages` (release) passou a coexistir como um terceiro pacote.
   `pm list packages | grep nomessages` confirmou os três pacotes lado a lado.

2. **Abertura da activity.** `adb -s emulator-5556 shell am start -n dev.mx3.nomessages/dev.mx3.nomessages.MainActivity`
   — `topResumedActivity` confirmado via `dumpsys activity activities`.

3. **Observação de segurança inicial (não planejada, mas relevante):** um `screencap` tirado ainda na tela
   de criação do cofre (antes de qualquer login) já retornou uma imagem 100% preta. Isso indica que
   `FLAG_SECURE` é aplicado à janela do app inteira em builds de release, não apenas depois de logar —
   um comportamento de segurança mais amplo do que o mínimo pedido pela tarefa, e consistente com o
   objetivo de nunca deixar conteúdo sensível capturável nesse build.

4. **Criação do cofre pela UI.** Fluxo conduzido via `uiautomator dump` + `input tap`/`input text`
   (sem coordenadas fixas — recalculadas a cada dump, pois o `ScrollView` reposiciona os campos quando o
   teclado abre/fecha). Campos preenchidos:
   - Nome no dispositivo: `ReleaseTester`
   - Senha principal / confirmação: senha real fornecida (19 caracteres) — **não registrada em nenhum
     arquivo de evidência**.
   - Senha de pânico / confirmação: senha isca fornecida (17 caracteres) — **idem**.
   - Navegação entre campos via `KEYCODE_TAB` (tecla 61) para evitar depender de coordenadas de toque
     que mudam com o scroll.
   - O botão "Create vault" ficou "enabled=true" na árvore de acessibilidade somente após as duas
     confirmações de senha baterem ("Passwords match").

   **Observação de troubleshooting:** a primeira tentativa de tocar em "Create vault" falhou
   silenciosamente (0% de CPU do processo, nenhuma mudança de tela) porque o teclado virtual havia sido
   fechado com `KEYCODE_ESCAPE` (111) em vez de `KEYCODE_BACK` (4) — a janela `InputMethod` continuava
   `isVisible=true` (confirmado via `dumpsys window windows`) e interceptava o toque na região do botão.
   Trocar para `KEYCODE_BACK` resolveu o problema.

5. **Criação do cofre.** Após o toque correto em "Create vault", a tela mostrou "Working…" e, alguns
   segundos depois, o diálogo padrão do Android "Allow NoMessages to send you notifications?" — toque em
   "Allow". Em seguida a Home foi exibida ("No chats yet" / "Add a contact in person to get started.").

6. **Conexão Tor.** A Home mostrou inicialmente "Publishing the onion address — cannot receive yet.".
   Fazendo polling a cada 20s, o status mudou para **"Tor connected"** em aproximadamente **60 segundos**
   após a criação do cofre, e permaneceu estável nas leituras seguintes (até 120s).

7. **Teste do gancho de debug em build de release.**
   - `adb shell setprop debug.nomessages.allow_capture 1` (confirmado com `getprop`).
   - `am force-stop dev.mx3.nomessages` seguido de reabertura da MainActivity.
   - Tela de "Unlock NoMessages" apareceu; senha principal real digitada (19 caracteres, confirmado pelo
     comprimento do texto no campo, não pelo conteúdo) e teclado fechado com `KEYCODE_BACK`.
   - Toque em "Unlock" → tela "Working…" → Home novamente exibida com sucesso ("No chats yet"), provando
     que a senha real desbloqueou o cofre corretamente mesmo com o setprop de debug ativo.
   - `adb exec-out screencap -p > flag-secure-release-blackscreen.png` capturado nesse estado (cofre
     desbloqueado, Home visível no dispositivo).

## Resultado

- A captura de tela pós-desbloqueio (`flag-secure-release-blackscreen.png`) é **100% preta**: medição com
  PIL (threshold de intensidade de canal RGB = 10) sobre os 2.592.000 pixels da imagem (1080×2400)
  encontrou **0 pixels não-pretos (fração 0,00000000 / 0,0000%)**.
- Isso confirma que `FLAG_SECURE` continua efetivo no build de release mesmo com a propriedade de debug
  `debug.nomessages.allow_capture=1` setada — ou seja, o gancho de bypass de captura é **inerte** em
  builds de release (funciona apenas em builds de debug, como esperado pelo modelo de segurança).
- Nenhuma captura de tela com conteúdo real do cofre (nomes, mensagens, etc.) foi salva como evidência.
  A única captura de tela mostrando qualquer conteúdo (a tela de criação do cofre, ainda antes do
  desbloqueio) também saiu 100% preta por causa do `FLAG_SECURE` global — não havia, portanto, nenhum
  conteúdo sensível capturável em nenhum momento do teste.

## Observações adicionais

- Esquemas de assinatura do APK confirmados via `apksigner`: v2 habilitado; v1, v3, v3.1 e v4
  desabilitados (ver `apksigner-verify.txt`). Isso é aceitável dado `minSdkVersion=31` (Android 12+, que
  não depende do esquema v1/JAR signing), mas fica registrado aqui para referência futura caso se queira
  também habilitar v3 para maior robustez em rotação de chave.
