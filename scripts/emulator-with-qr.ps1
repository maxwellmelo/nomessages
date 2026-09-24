<#
.SYNOPSIS
  Inicia (ou troca o poster de) um AVD NoMessages com a camera traseira virtual apontando para um
  poster (PNG) contendo um QR code, e espera o boot completo. Roda COM janela (para permitir
  captura de tela da janela do processo via GDI) e aceita o nome do AVD / porta, para suportar
  dois emuladores em paralelo (par A/B do fluxo de dois aparelhos, T3.3).

.USAGE
  Primeira vez (ou quando o emulador nao esta rodando) - caminho lento (~35-45s, boot completo):
    .\emulator-with-qr.ps1 -AvdName <seu-avd> -Port 5556 -PngPath "C:\caminho\qr.png" [-Mode wall|table] [-TimeoutSec 180]

  Para trocar o QR exibido enquanto o MESMO emulador ja esta rodando - caminho rapido (~1-2s,
  sem reboot, usa o console "virtualscene-image"; a camera ja fica enquadrada porque a posicao
   foi ajustada na primeira chamada):
    .\emulator-with-qr.ps1 -AvdName <seu-avd> -Port 5556 -PngPath "C:\caminho\novo-qr.png" -SwapOnly

  <seu-avd> pode vir do parametro -AvdName ou da variavel de ambiente NOMESSAGES_AVD (ver NOTES
  abaixo para como o SDK/emulator/adb tambem sao resolvidos sem nenhum caminho fixo no script).

.NOTES
  - NAO mexe em 127.0.0.1:5555 nem 5554 (isso e o BlueStacks). So opera as portas explicitas
    passadas em -Port (ex.: 5556, 5560, 5562).
  - Mata o emulador anterior nessa MESMA porta (se estiver rodando) com `adb emu kill` (NAO mata
    node.exe) -- so no caminho de restart completo; -SwapOnly nunca mata nada, so troca a imagem
    do poster.
  - Roda COM janela (sem -no-window): o NoMessages usa FLAG_SECURE (MainActivity.kt:33), que faz
    `adb screencap`/`screenrecord` devolver preto com o app em primeiro piano. A unica forma de
    capturar o QR mostrado pelo app e capturar a regiao da janela do emulador na area de trabalho
    do Windows (GDI/System.Drawing), que reflete o framebuffer composto normalmente (FLAG_SECURE
    so afeta a API de screenshot do SurfaceFlinger, nao a saida de vídeo da janela do emulador).
  - A orientacao inicial da camera virtual (cena "Toren1BD") NAO aponta para o poster wall/table
    (olha para a estante/TV). Por isso, apos o boot, o script reproduz a macro de automacao
    "Walk_to_image_room" (gravada pelo proprio Android Studio/emulator) via
    `adb emu automation play`, que reposiciona a camera headless, sem precisar de WASD manual.
  - Nenhum caminho de ferramenta e AVD sao fixados no script (evita hardcode de uma maquina
    especifica). Resolucao, nesta ordem:
      1. -AvdName (parametro) ou a variavel de ambiente NOMESSAGES_AVD.
      2. -SdkRoot (parametro) ou a variavel de ambiente NOMESSAGES_SDK_ROOT: usada para montar
         emulator.exe/adb.exe (`<root>\emulator\emulator.exe`, `<root>\platform-tools\adb.exe`)
         e, se ANDROID_AVD_HOME nao estiver setada, `<root>\avd`.
      3. Sem SDK root: cai para `emulator`/`adb` resolvidos via PATH (Get-Command). Falha com
         mensagem clara se nenhum dos dois existir.
    JAVA_HOME so e sobrescrita quando NOMESSAGES_SDK_ROOT aponta para uma pasta `jdk-21` dentro
    dela; do contrario o JAVA_HOME ja setado no ambiente (ou nenhum) e preservado.
#>
param(
  [string]$AvdName = $(if ($env:NOMESSAGES_AVD) { $env:NOMESSAGES_AVD } else { '' }),
  [int]$Port = 5556,
  [string]$PngPath,
  [ValidateSet('wall', 'table')][string]$Mode = 'wall',
  [int]$TimeoutSec = 180,
  [switch]$SwapOnly,
  [int]$WindowX = 0,
  [int]$WindowY = 0,
  [string]$SdkRoot = $(if ($env:NOMESSAGES_SDK_ROOT) { $env:NOMESSAGES_SDK_ROOT } else { '' })
)

if (-not $AvdName) { throw "Uso: .\emulator-with-qr.ps1 -AvdName <avd> -Port <porta> -PngPath <arquivo.png> [-Mode wall|table] [-SwapOnly] (ou defina a variavel de ambiente NOMESSAGES_AVD)" }
if (-not $PngPath) { throw "Uso: .\emulator-with-qr.ps1 -AvdName <avd> -Port <porta> -PngPath <arquivo.png> [-Mode wall|table] [-SwapOnly]" }
if ($Port -eq 5554 -or $Port -eq 5555) { throw "Porta $Port e reservada ao BlueStacks; use outra porta (ex.: 5556, 5560, 5562)." }

$ErrorActionPreference = 'Stop'

if ($SdkRoot) {
  $AvdHome  = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $SdkRoot 'avd' }
  $Emulator = Join-Path $SdkRoot 'emulator\emulator.exe'
  $Adb      = Join-Path $SdkRoot 'platform-tools\adb.exe'
  if (-not (Test-Path $Emulator)) { throw "emulator.exe nao encontrado em $Emulator (confira NOMESSAGES_SDK_ROOT / -SdkRoot)." }
  if (-not (Test-Path $Adb)) { throw "adb.exe nao encontrado em $Adb (confira NOMESSAGES_SDK_ROOT / -SdkRoot)." }
  $env:ANDROID_SDK_ROOT = $SdkRoot
  $env:ANDROID_HOME     = $SdkRoot
  $env:ANDROID_AVD_HOME = $AvdHome
  $candidateJavaHome = Join-Path $SdkRoot 'jdk-21'
  if (Test-Path $candidateJavaHome) { $env:JAVA_HOME = $candidateJavaHome }
} else {
  $emulatorCmd = Get-Command emulator -ErrorAction SilentlyContinue
  $adbCmd      = Get-Command adb -ErrorAction SilentlyContinue
  if (-not $emulatorCmd -or -not $adbCmd) {
    throw "emulator/adb nao encontrados no PATH e NOMESSAGES_SDK_ROOT nao foi definida. Defina NOMESSAGES_SDK_ROOT (ou -SdkRoot) apontando para o Android SDK, ou coloque emulator.exe/adb.exe no PATH."
  }
  $Emulator = $emulatorCmd.Source
  $Adb      = $adbCmd.Source
}
$Serial    = "emulator-$Port"
$LogDir    = Split-Path -Parent $PSCommandPath
$StdOutLog = Join-Path $LogDir "emulator-$AvdName-stdout.log"
$StdErrLog = Join-Path $LogDir "emulator-$AvdName-stderr.log"

if (-not (Test-Path $PngPath)) { throw "PNG nao encontrado: $PngPath" }
$PngPath = (Resolve-Path $PngPath).Path

function Step($m) { Write-Output ("=== " + (Get-Date -Format 'HH:mm:ss.fff') + " [$AvdName/$Serial] " + $m) }

if ($SwapOnly) {
  $devices = & $Adb devices 2>$null
  if (-not ($devices -match [regex]::Escape($Serial))) {
    throw "$Serial nao esta rodando. Rode sem -SwapOnly primeiro para subir o emulador com boot completo."
  }
  $t0 = Get-Date
  Step "Trocando poster '$Mode' em tempo real (sem reboot): $PngPath"
  & $Adb -s $Serial emu virtualscene-image $Mode $PngPath | Out-Null
  $elapsed = ((Get-Date) - $t0).TotalSeconds
  Step "Poster trocado em ${elapsed}s. Nenhum boot/macro necessario (camera ja estava enquadrada)."
  exit 0
}

# 1) Encerra o emulador atual nesta porta, se existir. NUNCA mexe em 5554/5555 (BlueStacks).
$devices = & $Adb devices 2>$null
if ($devices -match [regex]::Escape($Serial)) {
  Step "Encerrando $Serial (adb emu kill)..."
  & $Adb -s $Serial emu kill 2>$null | Out-Null
  $waited = 0
  while ((& $Adb devices 2>$null) -match [regex]::Escape($Serial) -and $waited -lt 30) {
    Start-Sleep -Seconds 1
    $waited++
  }
  Step "Emulador anterior encerrado (esperou ${waited}s)."
} else {
  Step "$Serial nao estava rodando."
}

# 2) Sobe o emulador novo com camera virtualscene apontando pro PNG, COM janela (para captura GDI).
$posterArg = "$Mode=$PngPath"
$argList = @(
  '-avd', $AvdName,
  '-port', $Port,
  '-no-audio',
  '-no-boot-anim',
  '-gpu', 'swiftshader_indirect',
  '-camera-back', 'virtualscene',
  '-virtualscene-poster', $posterArg,
  '-no-snapshot-save'
)
Step "Iniciando emulador: $Emulator $($argList -join ' ')"
$proc = Start-Process -FilePath $Emulator -ArgumentList $argList `
  -RedirectStandardOutput $StdOutLog -RedirectStandardError $StdErrLog `
  -PassThru
Step "Processo iniciado (PID $($proc.Id)). Aguardando boot completo (timeout ${TimeoutSec}s)..."

$t0 = Get-Date
& $Adb -s $Serial wait-for-device *> $null

$booted = $false
while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
  try {
    $prop = (& $Adb -s $Serial shell getprop sys.boot_completed) -join ''
    $prop = $prop.Trim()
  } catch {
    $prop = ''
  }
  if ($prop -eq '1') { $booted = $true; break }
  Start-Sleep -Seconds 2
}
$elapsed = ((Get-Date) - $t0).TotalSeconds

if (-not $booted) {
  Step "TIMEOUT: boot nao completou em ${TimeoutSec}s (elapsed=${elapsed}s)."
  exit 1
}

Step "Boot completo em ${elapsed}s. Poster ativo: $posterArg"

# 3) Reposiciona a camera virtual via macro headless-safe (funciona com ou sem janela).
# Derivado do diretorio do proprio emulator.exe resolvido acima (nao de $SdkRoot, que fica vazio
# quando o fallback do PATH foi usado).
$EmulatorDir = Split-Path -Parent $Emulator
$MacroPath = Join-Path $EmulatorDir 'resources\macros\Walk_to_image_room'
if (Test-Path $MacroPath) {
  Step "Reproduzindo macro de automacao para enquadrar o poster: $MacroPath"
  & $Adb -s $Serial emu automation play $MacroPath | Out-Null
  Start-Sleep -Seconds 8
  Step "Macro concluida (camera deve estar olhando para o poster '$Mode')."
} else {
  Step "AVISO: macro Walk_to_image_room nao encontrada em $MacroPath; poster pode ficar fora do quadro inicial."
}

Step "PID do emulador: $($proc.Id) | logs: $StdOutLog / $StdErrLog"
