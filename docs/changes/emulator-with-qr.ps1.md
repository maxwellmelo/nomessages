# scripts/emulator-with-qr.ps1

## 2026-09-15 — Prova de conceito: emulador Android como "segundo aparelho" no pareamento por QR

### Como era antes

Não existia nenhuma ferramenta no repositório para testar a tela de leitura de QR do pareamento
(`PairingScreen.kt` → `QrScanner`) sem um segundo aparelho físico. Validar manualmente exigia dois
celulares reais, o que não é viável em CI nem numa sessão de desenvolvimento comum.

### Como ficou

Um script PowerShell reutilizável, fora do código do app, que transforma o emulador oficial do
Android (AVD `nomessages35`) num "segundo aparelho": ele reinicia o emulador com a câmera traseira em
modo `virtualscene` apontando para um poster PNG (o QR de teste) e espera o boot completo.

```powershell
# Primeira vez (ou quando o emulador não está rodando) — caminho lento, ~35-45s:
.\scripts\emulator-with-qr.ps1 -PngPath "C:\caminho\qr.png" [-Mode wall|table] [-TimeoutSec 180]

# Trocar o QR exibido com o MESMO emulador já rodando — caminho rápido, ~0.1-1s, sem reboot:
.\scripts\emulator-with-qr.ps1 -PngPath "C:\caminho\novo-qr.png" -SwapOnly
```

Duas descobertas de investigação ficaram embutidas no script, porque sem elas o comando
`-virtualscene-poster` sozinho não é suficiente:

1. **A orientação inicial da câmera virtual não aponta para o poster.** A cena padrão do emulador
   (`Toren1BD`) começa olhando para uma estante/TV; os posters `wall` e `table` ficam fora de
   quadro. Sem janela, não dá para usar WASD/mouse para girar a câmera. A correção foi reproduzir,
   via `adb emu automation play`, a macro pronta `emulator/resources/macros/Walk_to_image_room`
   (a mesma que o próprio Android Studio grava para essa finalidade) — ela reposiciona a câmera
   virtual 100% headless, sem precisar de janela.
2. **Trocar o poster não exige reiniciar o emulador.** O console do emulador aceita
   `adb emu virtualscene-image <wall|table> <arquivo>` em tempo real, sem reboot. Como o QR de
   pareamento expira em 120 s, isso é essencial: só o primeiro QR paga o custo do boot (~35-45s);
   cada QR seguinte troca em frações de segundo, com a câmera já enquadrada.

### Vantagens

- Permite provar e depurar a leitura de QR do NoMessages (`PairingScreen.kt`) sem um segundo celular
  físico, reaproveitável em qualquer sessão de desenvolvimento.
- Documenta, no próprio script, uma limitação não óbvia do emulador (câmera virtual não nasce
  olhando para o poster) e a solução headless (macro de automação) — economiza a mesma
  investigação para quem for usar o script depois.
- O modo `-SwapOnly` torna viável o ciclo real de teste ("screencap do outro aparelho → poster →
  scan") dentro da janela de 120 s do QR de pareamento, já que só o primeiro boot é lento.
- Nunca toca em `127.0.0.1:5555` (BlueStacks) nem mata `node.exe`; opera exclusivamente em
  `emulator-5556` / AVD `nomessages35`.

### Por que a mudança foi feita

Tarefa de QA: provar que o emulador Android oficial pode servir de "segundo aparelho" no
pareamento por QR do NoMessages, sem alterar código do app. Nenhuma mudança foi feita no app; este é
um script de ferramentas de desenvolvimento/QA, fora do código de produção.

### Limitações conhecidas (ver relatório da sessão para o detalhamento completo)

- `adb exec-out screencap` retorna preto sempre que o NoMessages está em primeiro plano, porque
  `MainActivity.kt:33` define `WindowManager.LayoutParams.FLAG_SECURE` (proteção anti-forense
  esperada do app). A evidência de que o app decodificou o QR precisa vir da árvore de
  acessibilidade (`adb shell uiautomator dump`), não de screenshots de pixel do NoMessages.
- `screencap` também não captura o preview ao vivo da câmera (mesmo em apps sem `FLAG_SECURE`,
  como o `com.android.camera2` de fábrica) — por isso a verificação visual do poster foi feita
  tirando uma foto real com o app de câmera de fábrica e puxando o arquivo `.jpg` salvo, em vez de
  tentar capturar a tela.

## 2026-09-15 — Adaptado para dois emuladores em paralelo (T3.3); GDI também bloqueado por FLAG_SECURE

### Como era antes

O script só sabia operar um único AVD fixo (`nomessages35`/`emulator-5556`), sempre com `-no-window`
(headless). Isso bastava para provar a leitura de QR num único emulador, mas a tarefa T3.3 do
roteiro de release (`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`) exige um **par** de
aparelhos para o pareamento bilateral (A mostra QR, B escaneia e mostra resposta, A escaneia de
volta) — não dá para isso com um único emulador fixo, e sem janela não há como capturar pixels da
tela para relatar o QR de um emulador para o outro.

### Como ficou

O script agora aceita `-AvdName` e `-Port` (com uma trava explícita contra as portas 5554/5555 do
BlueStacks), calculando o serial (`emulator-<port>`) e os arquivos de log a partir desses
parâmetros, para que duas instâncias possam rodar em paralelo (`nomessages35`/5556 e um segundo AVD
clonado, `nomessages35b`/5560, criado nesta sessão). O `-no-window` foi removido: o emulador agora
sempre sobe com janela, para permitir captura da tela via GDI (`System.Drawing.Graphics.
CopyFromScreen` sobre o retângulo da janela, obtido via P/Invoke `GetWindowRect`/`SetWindowPos`,
num script auxiliar de sessão que posiciona as duas janelas lado a lado sem sobreposição).

```powershell
# Emulador A:
.\scripts\emulator-with-qr.ps1 -AvdName nomessages35  -Port 5556 -PngPath qr.png
# Emulador B (segundo AVD, porta diferente):
.\scripts\emulator-with-qr.ps1 -AvdName nomessages35b -Port 5560 -PngPath qr.png
```

### Achado importante desta sessão: a janela do emulador TAMBÉM é bloqueada por `FLAG_SECURE`

A premissa que motivou rodar com janela — que o framebuffer entregue à janela do host escaparia da
restrição de `FLAG_SECURE` por não passar pela API de screenshot do SurfaceFlinger — **não se
confirmou**. Um teste de controle mostrou que:

- Com o NoMessages em primeiro plano (confirmado por `dumpsys window`/`dumpsys activity`), a captura
  GDI da janela do emulador devolve o **mesmo quadro congelado** de antes do app abrir (não preto,
  mas travado — o compositor parece simplesmente parar de atualizar aquela região para qualquer
  consumidor externo ao guest, exatamente como faria para uma tela de cast/gravação não segura).
- `adb exec-out screencap -p`, inclusive **após `adb root`**, devolve preto — confirmando que nem
  root contorna a marcação de camada segura no SurfaceFlinger.
- Saindo do NoMessages (`KEYCODE_HOME`) e capturando de novo, a mesma janela volta a mostrar conteúdo
  ao vivo e correto (relógio da tela inicial atualizado) — prova de que o pipeline de captura em
  si funciona e está correto; o bloqueio é especificamente sobre o conteúdo com `FLAG_SECURE`.

Ou seja: o emulador Android moderno trata a própria janela do host como uma saída "não segura"
para fins de composição (provavelmente porque ele transmite o framebuffer via um mecanismo
equivalente a um display virtual, sujeito à mesma política que bloqueia telas de cast/gravação).
Evidência completa (PNGs, dumps) em
`docs/development/build-logs/two-emulator-20260915/session-log.md`.

### Vantagens

- Permite rodar dois AVDs NoMessages em paralelo, pré-requisito de qualquer teste bilateral de
  pareamento/mensagens neste projeto.
- Descobre e documenta, com evidência reprodutível, que a captura de tela via janela do host **não
  é** um caminho viável para ler o QR de pareamento em um emulador — economiza a mesma investigação
  (e a falsa esperança de que "rodar com janela" resolveria) para quem tentar de novo.

### Por que a mudança foi feita

Tarefa T3.3 do roteiro de release: executar o fluxo de dois aparelhos usando dois emuladores como
par A/B. Nenhuma alteração foi feita no código do app; este script continua sendo uma ferramenta de
QA fora do produto.

## 2026-09-23 — Correção da revisão: `$SdkRoot`/`$AvdHome`/`$JavaHome` e `-AvdName` fixados numa máquina que não existe

### Como era antes

```powershell
param(
  [string]$AvdName = 'nomessages35',
  ...
)
...
$SdkRoot   = 'C:\Users\maxwe\.nomessages-tools\android-sdk'
$AvdHome   = 'C:\Users\maxwe\.nomessages-tools\avd'
$JavaHome  = 'C:\Users\maxwe\.nomessages-tools\jdk-21'
$Emulator  = Join-Path $SdkRoot 'emulator\emulator.exe'
$Adb       = Join-Path $SdkRoot 'platform-tools\adb.exe'
...
$MacroPath = Join-Path $SdkRoot 'emulator\resources\macros\Walk_to_image_room'
```

Uma limpeza anterior trocou os caminhos e o nome de AVD default de valores com o nome antigo do
projeto para valores com o nome atual (`nomessages35`, uma pasta de ferramentas com o nome atual),
mas nenhum dos dois existe nesta máquina: o SDK real fica numa pasta de ferramentas com o nome
antigo do projeto (nome de pasta em disco, não versionado). O script falhava ao tentar rodar
`emulator.exe`/`adb.exe` a partir de um caminho inexistente, sem nenhuma mensagem que apontasse a
causa real.

### Como é agora

`-AvdName` não tem mais um valor fixo: o default vem de `$env:NOMESSAGES_AVD` quando setada, e o
script recusa rodar sem nenhum dos dois (`-AvdName` explícito ou a variável), com uma mensagem de uso
clara em vez de seguir com um nome de AVD que provavelmente não existe. Um novo parâmetro
`-SdkRoot` (default `$env:NOMESSAGES_SDK_ROOT`) resolve `$Emulator`/`$Adb`/`$AvdHome` a partir dele
quando setado (com `Test-Path` conferindo os dois executáveis e uma mensagem de erro nomeando a
variável a setar); sem `-SdkRoot`/`NOMESSAGES_SDK_ROOT`, o script cai para `emulator`/`adb`
resolvidos via `Get-Command` no `PATH`. `$JavaHome` só é sobrescrita quando existe uma pasta
`jdk-21` dentro do `SdkRoot` resolvido; do contrário o `JAVA_HOME` já presente no ambiente (ou
nenhum) é preservado. `$MacroPath` (a macro `Walk_to_image_room`) passou a ser derivado do diretório
do próprio `$Emulator` já resolvido, em vez de `$SdkRoot` diretamente — necessário porque `$SdkRoot`
fica vazio quando o fallback do `PATH` foi usado. O cabeçalho `.NOTES` documenta essa ordem de
resolução, e os exemplos em `.USAGE` trocaram o nome de AVD fixo `nomessages35` por um placeholder
`<seu-avd>`.

### Vantagens

- O script roda em qualquer máquina com `emulator`/`adb` no `PATH` (o caso comum de uma instalação
  padrão do Android Studio/SDK) sem precisar de nenhuma configuração.
- Numa máquina com o SDK em um caminho não padrão (como esta, numa pasta de ferramentas com o nome
  antigo do projeto), basta setar `NOMESSAGES_SDK_ROOT` e `NOMESSAGES_AVD` uma vez — nenhum caminho
  de máquina específica, com o nome antigo ou o novo, fica escrito no script versionado.
- Falhas de configuração agora produzem uma mensagem que nomeia a variável a setar, em vez de um
  erro genérico do `Start-Process`/`adb` tentando abrir um caminho inexistente.

### Por que a mudança foi feita

Achado 4 da revisão desta tarefa.
