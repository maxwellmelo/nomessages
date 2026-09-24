#!/bin/sh
# emulator-pair.sh - relay do QR de pareamento entre dois emuladores/aparelhos, sem câmera.
#
# ============================================================================================
# POR QUE ESTE SCRIPT EXISTE
# ============================================================================================
# A câmera virtual do emulador não decodifica o QR real de pareamento (ver a seção
# "Residual finding" em docs/development/device-verification.md, T3.3 run3: o detector clássico
# do ZXing Java não lida com a distorção projetiva fixa da virtual scene). Sem um caminho
# alternativo, o fluxo A -> B -> A não pode ser exercitado sem um operador humano com dois
# telefones. Este script usa o hook debug-only dev.mx3.nomessages.debug.DebugQrReceiver para:
#
#   1. ler os bytes do QR que o aparelho de origem está EXIBINDO (ação DUMP_QR); e
#   2. entregá-los ao aparelho de destino no mesmo ponto em que o decodificador entregaria
#      (ação INJECT_QR).
#
# Todo o resto do fluxo - parsing, verificação de assinatura, derivação do SAS, confirmação -
# roda exatamente como em produção. O hook só existe em builds debug e só aceita broadcasts
# vindos de shell/root; ver docs/security-model.md.
#
# ============================================================================================
# PASSO A PASSO DE USO
# ============================================================================================
# Pré-requisitos: APK debug instalado nos dois aparelhos, ambos com o cofre DESBLOQUEADO e o app
# em primeiro plano. O próprio script liga o opt-in `debug.nomessages.allow_qr_inject=1` em cada
# aparelho que toca (ver ensure_enabled abaixo); nada mais precisa ser preparado à mão.
#
#   # 0) conferir que os dois aparelhos estão visíveis
#   bash scripts/emulator-pair.sh devices
#
#   # 1) em A (emulator-5556): abrir "Novo chat" -> "Mostrar meu QR"  (QR de oferta na tela)
#   #    em B (emulator-5560): abrir "Novo chat" -> "Ler QR"          (tela de leitura ativa)
#   bash scripts/emulator-pair.sh relay emulator-5556 emulator-5560
#
#   # 2) B processa a oferta e passa a exibir o QR de resposta; deixe A na tela de leitura
#   bash scripts/emulator-pair.sh relay emulator-5560 emulator-5556
#
#   # 3) os dois devem exibir o mesmo SAS de 6 dígitos; confirme em ambos pela UI e, se o app
#   #    pedir mais um QR de confirmação, repita o relay na direção que ele pedir.
#
# Subcomandos auxiliares (úteis para depurar o relay em si):
#   dump <serial>       imprime em base64 (uma linha) o QR exibido no aparelho
#   payload <serial>    imprime o payload do QR exibido como texto (ISO-8859-1)
#   inject <serial> <b64>   injeta um base64 já em mãos no aparelho
#   relay <de> <para>   dump em <de> + inject em <para>, com verificação de integridade
#
# Variáveis de ambiente: NOMESSAGES_ADB (caminho do adb.exe; ADB é aceito como alias por
# compatibilidade) — sem nenhuma delas, cai para `adb` resolvido via PATH, então nenhum caminho de
# máquina específica fica fixado no script. PKG (applicationId debug), DUMP_RETRIES / DUMP_SLEEP
# (espera pelo arquivo de dump).
#
# ============================================================================================
# NOTA SOBRE BINÁRIO NO GIT BASH / WINDOWS
# ============================================================================================
# Os bytes NUNCA trafegam crus pelo pipe. O `base64` é executado NO APARELHO (toybox), de modo
# que só ASCII atravessa o adb e o pipe do Git Bash - isso elimina de uma vez a classe inteira de
# problemas de tradução CRLF e de bytes 0x1a/0x00 em pipes do Windows. Como cinto e suspensório,
# `relay` compara o sha256 calculado no aparelho com o sha256 do conteúdo decodificado no host
# antes de injetar, ou seja, a integridade é verificada byte a byte e não presumida.
set -eu

# Sem NOMESSAGES_ADB (ou ADB, aceito por compatibilidade), cai para `adb` resolvido via PATH —
# nenhum caminho de uma máquina específica fica fixado aqui (ver item 4 da revisão em
# docs/changes/emulator-pair.sh.md).
ADB="${NOMESSAGES_ADB:-${ADB:-adb}}"
PKG="${PKG:-dev.mx3.nomessages.debug}"
RECEIVER="$PKG/dev.mx3.nomessages.debug.DebugQrReceiver"
DUMP_PATH="cache/qr-shown.bin"
ALLOW_PROPERTY="debug.nomessages.allow_qr_inject"
DUMP_RETRIES="${DUMP_RETRIES:-10}"
DUMP_SLEEP="${DUMP_SLEEP:-0.5}"

die() { echo "erro: $*" >&2; exit 1; }
log() { echo "[emulator-pair] $*" >&2; }

# `adb shell` entrega CR porque aloca um pty; `exec-out` não, mas normalizamos os dois caminhos.
#
# PEGADINHA IMPORTANTE: o adb NÃO re-cita os argumentos - ele os concatena com espaços numa única
# linha de comando entregue ao shell do aparelho. Portanto qualquer aspa necessária no aparelho
# precisa estar DENTRO do argumento que passamos aqui. Por isso todos os comandos abaixo invocam o
# binário diretamente via `run-as <pkg> <cmd> <args>` (que executa o programa sem passar por um
# `sh -c`), em vez de montar uma string de shell aninhada.
adb_sh() { serial="$1"; shift; "$ADB" -s "$serial" shell "$@" | tr -d '\r'; }
adb_out() { serial="$1"; shift; "$ADB" -s "$serial" exec-out "$@"; }

require_device() {
    "$ADB" -s "$1" shell true >/dev/null 2>&1 || die "aparelho '$1' não responde ao adb"
    ensure_enabled "$1"
}

# O hook é opt-in: fica inerte até `debug.nomessages.allow_qr_inject` valer exatamente "1". Escrever
# propriedades `debug.*` é restrito pelo SELinux aos domínios shell/su, então este setprop é, ele
# próprio, parte da guarda (mesmo padrão de `debug.nomessages.allow_capture`). É idempotente e barato,
# então é reaplicado a cada comando — a propriedade não sobrevive a um reboot do emulador, e essa é
# justamente uma das armadilhas que mais custou tempo em sessões anteriores.
ensure_enabled() {
    current="$("$ADB" -s "$1" shell getprop "$ALLOW_PROPERTY" 2>/dev/null | tr -d '\r')"
    [ "$current" = "1" ] && return 0
    "$ADB" -s "$1" shell setprop "$ALLOW_PROPERTY" 1 >/dev/null 2>&1 \
        || die "não foi possível ativar $ALLOW_PROPERTY em $1 (precisa de adb shell)"
    log "$1: $ALLOW_PROPERTY=1 (hook de QR habilitado)"
}

# Dispara DUMP_QR e imprime o base64 (uma única linha) do QR exibido no aparelho.
# O arquivo antigo é removido ANTES do broadcast: assim um dump velho jamais pode ser confundido
# com um dump novo se a tela de origem não estiver mostrando QR nenhum.
cmd_dump() {
    serial="${1:?uso: dump <serial>}"
    require_device "$serial"
    adb_sh "$serial" run-as "$PKG" rm -f "$DUMP_PATH" >/dev/null 2>&1 || true
    adb_sh "$serial" am broadcast -a dev.mx3.nomessages.debug.DUMP_QR -n "$RECEIVER" >/dev/null \
        || die "broadcast DUMP_QR falhou em $serial"

    i=0
    size=""
    while [ "$i" -lt "$DUMP_RETRIES" ]; do
        size="$(adb_sh "$serial" run-as "$PKG" wc -c "$DUMP_PATH" 2>/dev/null | awk '{print $1}' || true)"
        case "$size" in
            ''|0|*[!0-9]*) : ;;
            *) break ;;
        esac
        i=$((i + 1))
        sleep "$DUMP_SLEEP"
    done
    case "$size" in
        ''|0|*[!0-9]*) die "nenhum QR exibido em $serial ($DUMP_PATH vazio ou ausente após $DUMP_RETRIES tentativas)" ;;
    esac

    log "$serial: QR exibido tem $size bytes"
    adb_out "$serial" run-as "$PKG" base64 "$DUMP_PATH" | tr -d '\r\n'
    echo
}

# sha256 do arquivo de dump, calculado NO APARELHO.
device_sha() {
    adb_sh "$1" run-as "$PKG" sha256sum "$DUMP_PATH" 2>/dev/null | awk '{print $1}'
}

cmd_payload() {
    serial="${1:?uso: payload <serial>}"
    cmd_dump "$serial" | base64 -d
    echo
}

cmd_inject() {
    serial="${1:?uso: inject <serial> <base64>}"
    b64="${2:?uso: inject <serial> <base64>}"
    require_device "$serial"
    # O base64 vai como um único argumento entre aspas: ele contém '+', '/' e '=', todos seguros
    # dentro de aspas simples/duplas, mas fatais se deixados sem aspas em qualquer um dos dois
    # shells envolvidos (o do host e o do aparelho).
    result="$(adb_sh "$serial" am broadcast \
        -a dev.mx3.nomessages.debug.INJECT_QR -n "$RECEIVER" --es payload_b64 "'$b64'")" \
        || die "broadcast INJECT_QR falhou em $serial"
    echo "$result" | grep -q "Broadcast completed" \
        || die "INJECT_QR não foi entregue em $serial: $result"
    log "$serial: INJECT_QR entregue (${#b64} chars de base64)"
}

cmd_relay() {
    from="${1:?uso: relay <de> <para>}"
    to="${2:?uso: relay <de> <para>}"
    [ "$from" != "$to" ] || die "origem e destino são o mesmo aparelho"

    b64="$(cmd_dump "$from")"
    [ -n "$b64" ] || die "dump vazio em $from"

    # Verificação byte a byte: o hash é calculado no aparelho sobre o arquivo original e no host
    # sobre o resultado da decodificação. Se o transporte tivesse corrompido qualquer byte
    # (CRLF, truncamento, wrap de base64 mal removido), os hashes divergiriam aqui.
    expected="$(device_sha "$from")"
    actual="$(printf '%s' "$b64" | base64 -d | sha256sum | awk '{print $1}')"
    [ -n "$expected" ] || die "não foi possível calcular o sha256 no aparelho $from"
    [ "$expected" = "$actual" ] \
        || die "payload corrompido no transporte: aparelho=$expected host=$actual"
    log "integridade confirmada (sha256 $expected)"

    cmd_inject "$to" "$b64"
    log "relay $from -> $to concluído"
}

cmd_devices() { "$ADB" devices -l; }

usage() {
    sed -n '2,50p' "$0" >&2
    exit 2
}

case "${1:-}" in
    relay)   shift; cmd_relay "$@" ;;
    dump)    shift; cmd_dump "$@" ;;
    payload) shift; cmd_payload "$@" ;;
    inject)  shift; cmd_inject "$@" ;;
    devices) shift; cmd_devices "$@" ;;
    *)       usage ;;
esac
