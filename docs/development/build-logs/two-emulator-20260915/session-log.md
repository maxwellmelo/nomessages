# Sessão de execução — fluxo de dois aparelhos (T3.3), 2026-09-15

Log cronológico (UTC) da execução em dois emuladores Android 15 x86_64 (par A/B). Ver
`docs/development/device-verification.md` para a tabela PASSED/FAILED/BLOCKED e
`docs/changes/emulator-with-qr.ps1.md` (seção 2026-09-15) para o racional das mudanças de
ferramenta. Nenhum código do app foi alterado nesta sessão.

## Ambiente

- APK testado: `artifacts/nomessages-1.0.0-dev-debug.apk`, SHA-256
  `c42b35c48202fdf731eebcddec8d044f1fffcc948632789a20220ff99169a537` (igual ao registrado em
  `artifacts/artifacts.json`, build `1.0.0-dev-debug`, commit `1e4f4dc`).
- Emulador A: AVD `nomessages35` (Pixel 6, Android 15 API 35 google_apis x86_64), serial
  `emulator-5556`, porta 5556.
- Emulador B: AVD `nomessages35b` (clone de `nomessages35`: hw.ramSize=3072, hw.keyboard=yes,
  hw.gpu.mode=swiftshader_indirect, disk.dataPartition.size=4096M, criado com `avdmanager create
  avd -n nomessages35b -k "system-images;android-35;google_apis;x86_64" -d pixel_6 --force`), serial
  `emulator-5560`, porta 5560.
- Ambos reiniciados com `scripts/emulator-with-qr.ps1` (adaptado nesta sessão: parâmetros
  `-AvdName`/`-Port`, COM janela / sem `-no-window`).
- Janelas posicionadas lado a lado via P/Invoke `SetWindowPos` (script auxiliar
  `capture-window.ps1`, não commitado — vive no scratchpad da sessão) para permitir captura GDI.

## Cronologia

- **14:22:55–14:23:32** — Emulador A (`nomessages35`/5556) reiniciado com o script adaptado, câmera
  virtual `wall` apontando para um PNG branco de placeholder. Boot completo em **26,2 s** (AVD já
  havia sido usado antes nesta máquina, sem primeiro-boot). Macro `Walk_to_image_room` reproduzida
  em 8,0 s adicionais.
- **14:23:01–14:23:50** — Emulador B (`nomessages35b`/5560) iniciado pela primeira vez. Boot completo
  em **40,9 s** (primeiro boot do AVD novo). Macro da câmera em 8,0 s adicionais.
- **14:2x** — Janelas de A e B posicionadas sem sobreposição (0,0)-(425,949) e (520,0)-(945,949).
  Captura de controle confirma que a captura GDI reflete o conteúdo real e ao vivo da janela do
  emulador (wallpaper padrão, depois tela inicial com relógio mudando) quando o NoMessages **não**
  está em primeiro plano.
- **~14:26** — APK reinstalado em A (havia um conflito de assinatura com uma instalação anterior
  de outro agente; resolvido com `adb uninstall` + `adb install -r`) e instalado pela primeira vez
  em B. Permissões de câmera, microfone e notificações concedidas via `pm grant` para eliminar
  diálogos de permissão do fluxo de automação.
- **~14:26** — **Achado 1 (limitação de ambiente, não bug do app):** com o NoMessages em primeiro
  plano (`MainActivity`, que define `FLAG_SECURE`), tanto `adb exec-out screencap -p` (inclusive
  após `adb root`) quanto a captura GDI da própria janela do emulador (`System.Drawing.Graphics.
  CopyFromScreen` sobre o retângulo da janela, obtido via `GetWindowRect`) devolvem o **mesmo
  quadro congelado** de antes do app abrir (ou preto, no caso do `screencap` com root). Um teste
  de controle prova que o pipeline de captura funciona normalmente fora do NoMessages: pressionar
  HOME e capturar de novo mostra a tela inicial real, com o relógio atualizado. Ou seja, o
  framebuffer que o próprio emulador entrega para a janela do host Windows já é tratado como uma
  saída "não seguro" pelo compositor do Android guest, igual à API de screenshot — ao contrário da
  premissa do procedimento original (que a janela do emulador escaparia do `FLAG_SECURE` por não
  passar pela API de screenshot). Evidência: `01-emuA-live-before-app-launch.png` (cena viva antes
  do app), `02-emuA-flag-secure-frozen-capture.png` (mesmo quadro, travado, com o NoMessages em
  primeiro plano e focado — confirmado por `dumpsys window`/`dumpsys activity`), `03-emuA-live-
  after-leaving-app-control-test.png` (volta a refletir conteúdo ao vivo após `KEYCODE_HOME`),
  `04-emuA-root-screencap-black.png` (screencap preto mesmo com `adb root`, confirmando que nem
  root contorna a marca `eSecure` no SurfaceFlinger). **Consequência prática:** não existe, sem
  alterar código do app, nenhum canal de pixels para extrair o QR de pareamento exibido por um
  emulador e injetá-lo no outro; `uiautomator dump` funciona normalmente (a árvore de
  acessibilidade não é afetada por `FLAG_SECURE`), mas o `contentDescription` da imagem do QR é
  genérico ("Seu QR temporário"/"Your pairing QR") e não expõe o payload. Não há canal de
  clipboard/log para o payload de pareamento (só o endereço onion tem botão "Copiar", em
  `SettingsScreen.kt`). Isso bloqueia a etapa 2 (pareamento bilateral) e, por consequência, todas
  as etapas que dependem de um contato pareado (3, 4, 5) usando o método de dois emuladores
  descrito no procedimento.
- **17:25–17:34** — Cofre de A limpo (`pm clear`) e recriado do zero pela UI (nome "Aparelho A",
  4 campos de senha preenchidos via `adb shell input text` + navegação por toque, coordenadas
  obtidas de `uiautomator dump` a cada campo). Primeira tentativa com senhas de 20-21 caracteres
  alfanuméricos mas previsíveis (contendo "Test", "Main", "Panic", dígitos sequenciais) falhou com
  `error_create_vault` — comportamento correto: o `PasswordPolicy` (zxcvbn, score mínimo 4) as
  rejeita como previsíveis, embora a mensagem de UI só cite o requisito de 16 caracteres e não
  mencione força/entropia (ver observação abaixo).
- **17:33:29–17:33:46** — **Achado 2 (bug do app, bloqueador):** segunda tentativa em A com senhas
  aleatórias fortes (24 caracteres alfanuméricos, geradas com `secrets.choice` do Python, mistura
  de maiúsculas/minúsculas/dígitos — deveriam pontuar no máximo do zxcvbn) ainda falha com o mesmo
  diálogo genérico "Could not complete / The vault could not be created. Use strong, distinct
  passwords with at least 16 characters." após ficar ~11-17 s em "Working…". Repetido em B
  (`nomessages35b`, instalação limpa e independente) às 17:49:48–17:50:05 com outro par de senhas
  aleatórias de 24 caracteres: mesma falha, mesmo tempo, mesma mensagem. Nenhuma pasta de estágio
  (`.vault-create-*`) chegou a aparecer em `/data/data/dev.mx3.nomessages.debug/files` durante o
  polling a cada ~1,5 s (confirmado via `run-as ... ls -laR`), nenhum `Tombstone`/`Fatal signal`/
  `SIGABRT` no logcat, nenhuma linha de log do app em nenhum nível (V a F) durante a janela do
  erro. Causa: `NoMessagesController.setup()` (`app/src/main/kotlin/dev/mx3/nomessages/runtime/
  NoMessagesController.kt:159-161`) engole a exceção real com `catch (_: Exception) { ...;
  error(context.getString(R.string.error_create_vault), token) }`, sem logar nada — não há como
  diferenciar, a partir da UI ou do logcat, entre "senha fraca", "disco cheio", "cripto nativa
  falhou" ou qualquer outra causa. Investigação por eliminação: espaço em disco confirmado
  abundante (4,8 GiB livres de 6 GiB, 22% usado); `PasswordPolicy`/zxcvbn é exercitado com sucesso
  pelos testes JVM de `core` (`VaultTest.kt`) no host, então a biblioteca em si funciona; a suíte
  instrumentada `AndroidVaultStorageTest` (T3.1, 11/11 aprovados) chama `AndroidVaultStorage.
  initialize()` diretamente, **sem** passar por `VaultManager.create()`, `PasswordPolicy` ou
  `KdfCalibrator` — ou seja, o caminho de código exercitado pela suíte automatizada não é o mesmo
  que o fluxo real de "Criar cofre" na UI, o que explica por que T3.1 não pegou este defeito.
  Tentativa de anexar `jdb` via `adb forward tcp:7777 jdwp:<pid>` para obter o stack trace real não
  teve sucesso nesta sessão (o `jdb.exe` nativo do Windows não interopera com um FIFO POSIX criado
  via `mkfifo` do Git Bash para injeção de comandos interativos; um debugger real como Android
  Studio resolveria isso rapidamente). Evidência: `06-*.xml`, `07-*.xml` (A), `08-*.xml` (B).
  **Consequência prática: nenhum cofre pôde ser criado nesta sessão em nenhum dos dois emuladores
  pela UI real, o que bloqueia toda a Fase 3 a partir daqui — não há como desbloquear a rede Tor
  (só ativa após `setup`/`unlock`), pareamento, mensagens, anexos ou fila offline.**
- **17:50** — Sessão de captura de evidências encerrada; ambos os emuladores deixados rodando (ver
  seriais abaixo) para inspeção posterior ou para um desenvolvedor anexar um debugger real.

## Observações adicionais (não bloqueadoras)

- A UI do app renderizou em **inglês** (`values-en`), não em português (`values`, PT-BR), apesar
  de o procedimento do plano assumir PT-BR como idioma padrão do dispositivo de teste — o locale
  do AVD (en-US) é o fator determinante, não uma falha do app; a paridade PT-BR/EN citada no
  roteiro está de fato presente (strings equivalentes localizadas em ambos os idiomas).
- A mensagem `error_create_vault` ("The vault could not be created. Use strong, distinct passwords
  with at least 16 characters.") é genérica demais: ela é mostrada tanto para o caso real
  (senha previsível, rejeitada por zxcvbn) quanto para qualquer outra falha interna (como a
  observada com senhas de alta entropia), tornando impossível ao usuário final entender o que
  fazer. Recomenda-se, no mínimo, logar a exceção real (nível debug/verbose, sem incluir a senha)
  antes de descartá-la em `NoMessagesController.kt:159-161`.
- Automação de UI: `adb shell input keyevent KEYCODE_BACK` demonstrou comportamento inconsistente
  entre os dois emuladores/tentativas — às vezes só fecha o teclado (comportamento esperado
  enquanto um campo de texto está focado), às vezes fecha a tela de Setup inteira (o app não tem
  pilha de retrocesso na tela inicial, então isso finaliza a Activity). Isso é mais provável ser um
  artefato de timing do evento de tecla sintético (`adb shell input keyevent`) competindo com a
  extração do foco do IME/callback de back preditivo do próprio Android 15 do que um bug do app;
  registrado aqui para quem for repetir este procedimento manualmente.
- O botão "Create vault" pode ficar coberto pelo teclado virtual (Gboard) quando o teclado está
  aberto e o último campo de senha acaba de ser preenchido — o `imePadding()` da tela de Setup nem
  sempre reserva espaço suficiente antes do teclado terminar de animar. Trocar de campo focado
  sem antes fechar o teclado (ex.: `KEYCODE_BACK`) pode fazer um toque na região do botão cair,
  na verdade, sobre o campo de texto (confirmado observando o conteúdo do campo "Confirmar senha
  de pânico" mudar após toques repetidos na posição nominal do botão). Contornado nesta sessão
  fechando o teclado antes de cada toque no botão.

## Emuladores deixados rodando ao final

| Papel | AVD | Serial | Porta | Estado final |
|---|---|---|---|---|
| A | nomessages35 | emulator-5556 | 5556 | NoMessages em primeiro plano, diálogo "Could not complete" (falha de criação de cofre) |
| B | nomessages35b | emulator-5560 | 5560 | NoMessages em primeiro plano, diálogo "Could not complete" (falha de criação de cofre) |
