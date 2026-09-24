# emulator-pair.sh

## 2026-09-15 — T3.3 run4: relay do QR de pareamento entre dois aparelhos, sem câmera

Arquivo **novo**: `scripts/emulator-pair.sh` (POSIX `sh`, usado via Git Bash no Windows).

### Como era antes

Não existia. As tentativas anteriores de relay (run2/run3) passavam pela **câmera virtual**:
`adb exec-out screencap` na tela de origem, recorte do QR, e `adb emu virtualscene-image wall` para
projetar a imagem na cena do emulador de destino. Isso falhou de forma definitiva — 668 tentativas
de decode, 0 sucessos — porque o detector clássico do ZXing Java não lida com a distorção projetiva
fixa da virtual scene (análise completa em `docs/development/device-verification.md`, T3.3 run3).

### Como ficou

Um script com quatro subcomandos, apoiado no hook debug-only `DebugQrReceiver`:

| Subcomando | O que faz |
|---|---|
| `relay <de> <para>` | dump no aparelho de origem + verificação de integridade + injeção no destino |
| `dump <serial>` | imprime o QR exibido em base64, uma linha |
| `payload <serial>` | imprime o payload do QR exibido como texto |
| `inject <serial> <b64>` | injeta um base64 já em mãos |
| `devices` | `adb devices -l` |

Uso típico (documentado em comentário no topo do próprio script):

```sh
# A mostra o QR de oferta, B está na tela de leitura
bash scripts/emulator-pair.sh relay emulator-5556 emulator-5560
# B passa a mostrar o QR de resposta; A vai para a tela de leitura
bash scripts/emulator-pair.sh relay emulator-5560 emulator-5556
# os dois exibem o mesmo SAS; confirme em ambos e repita o relay para os QRs de confirmação
```

### Armadilhas reais encontradas ao escrever o script (e como cada uma foi resolvida)

1. **Binário em pipe no Git Bash / Windows — resolvido evitando o problema, não contornando.**
   A abordagem óbvia, `adb exec-out run-as PKG cat cache/qr-shown.bin | base64`, faz bytes crus
   atravessarem o adb e o pipe do Git Bash, expondo-se a tradução CRLF e a bytes de controle. O
   script executa o `base64` **no aparelho** (toybox), de modo que só ASCII atravessa:
   `adb exec-out run-as PKG base64 cache/qr-shown.bin`. Isso elimina a classe inteira de problemas
   em vez de tentar detectá-los.

2. **Integridade verificada byte a byte, não presumida.** `relay` compara o `sha256sum` calculado
   **no aparelho** sobre o arquivo original com o `sha256sum` calculado **no host** sobre o
   resultado da decodificação, e aborta se divergirem. Qualquer truncamento, CR sobrando ou wrap de
   base64 mal removido pararia o relay ali, em vez de virar um "pareamento falhou" misterioso
   trinta passos adiante. Nas execuções reais os hashes bateram em 100% dos relays (payloads de
   2708 B, 2750 B e 314 B).

3. **O `adb` NÃO re-cita argumentos.** `adb shell a b c` concatena os argumentos com espaços numa
   única linha entregue ao shell do aparelho. Consequência prática: `run-as PKG sh -c "base64 X"`
   chega como `run-as PKG sh -c base64 X`, e o `sh -c` acaba executando `base64` com `X` como `$0`,
   lendo do stdin (trava/saída vazia). O script **nunca** monta shell aninhado: chama o binário
   direto via `run-as <pkg> <cmd> <args>`. Pelo mesmo motivo, o base64 do `--es` é passado entre
   aspas simples dentro do argumento (`"'$b64'"`), já que ele contém `+`, `/` e `=`.

4. **Redirecionamento e `run-as` não se misturam.** `run-as PKG wc -c <cache/qr-shown.bin` falha
   porque o redirecionamento é feito pelo shell do aparelho, que roda como `shell` e não consegue
   abrir um arquivo do diretório privado do app — o `run-as` só troca de UID depois. O script usa
   `run-as PKG wc -c cache/qr-shown.bin` (arquivo como argumento) e lê o primeiro campo.

5. **Dump velho confundido com dump novo.** `cmd_dump` remove `cache/qr-shown.bin` **antes** do
   broadcast e só então espera o arquivo aparecer (com retry configurável). Sem isso, disparar o
   dump numa tela que não mostra QR devolveria silenciosamente o payload da rodada anterior — um
   falso positivo caríssimo. O lado do app coopera: `DUMP_QR` **apaga** o arquivo quando não há QR
   na tela, em vez de deixá-lo intacto.

6. **A propriedade de opt-in não sobrevive a reboot.** `ensure_enabled` reaplica
   `setprop debug.nomessages.allow_qr_inject 1` em todo aparelho que o script toca. É idempotente e
   barato, e evita a falha mais provável numa sessão futura ("o script parou de funcionar" depois
   de reiniciar o emulador). O próprio `setprop` faz parte da guarda: escrever `debug.*` é restrito
   pelo SELinux aos domínios `shell`/`su`.

### Vantagens

1. **Torna T3.3 executável sem operador humano.** Pareamento completo A <-> B em 57 s, incluindo
   a troca dos QRs de confirmação, contra "impossível" antes.
2. **Determinístico.** Sem câmera, sem iluminação, sem enquadramento, sem tempo de exposição: os
   mesmos bytes, verificados por hash, sempre.
3. **Não mexe no caminho de produção.** O QR continua sendo gerado e consumido exatamente pelo
   mesmo código; o relay só substitui o meio físico (câmera) por um canal debug-only.
4. **Falha alto e cedo.** Todo passo tem verificação explícita (aparelho responde, arquivo existe e
   não está vazio, hash bate, broadcast foi entregue) com mensagem de erro específica, em vez de
   silenciosamente injetar lixo.

### Limitações conhecidas

- Exige APK **debug** instalado (`run-as` só funciona em builds `debuggable`) e
  `debug.nomessages.allow_qr_inject=1`.
- O QR de pareamento expira em **2 minutos**. O relay em si leva ~1,4 s, mas a automação de UI em
  volta dele precisa ser enxuta: uma primeira tentativa desta sessão gastou 78 s em navegação e o
  QR de confirmação expirou antes do fim (`Could not complete`). Ver
  `docs/development/device-verification.md` (T3.3 run4) para a sequência que fecha em 57 s.
- **Ordem obrigatória:** os dois lados precisam confirmar o SAS **antes** de qualquer troca de QR
  de confirmação. Entregar o QR de confirmação a um lado que ainda não confirmou o próprio SAS faz
  o app recusar o payload.

## 2026-09-17 — T4.16: revisado para o QR formato 2, **nenhuma mudança necessária**

### O que mudou no que ele transporta

O prefixo do QR de oferta passou de `nomessages:1:` para `nomessages:2:`, o de confirmação de
`nomessages-confirm:1:` para `nomessages-confirm:2:`, e o payload encolheu de ~2708 caracteres para
**405 bytes na oferta, 448 na resposta e 317 na confirmação** (o bundle PQXDH saiu do QR e passou a
ser buscado pela rede Tor — ver `docs/changes/Pairing.kt.md` e `SPEC.md` §6).

### Por que o script não precisou de nenhuma linha alterada

`emulator-pair.sh` nunca conheceu o prefixo. Ele conversa com o app por dois broadcasts de debug —
`DUMP_QR`, que devolve em base64 os **bytes** do QR que a tela está exibindo, e `INJECT_QR`, que
entrega bytes ao mesmo callback que a câmera real usaria. Os bytes são opacos para o script: ele
não os interpreta, não valida prefixo, não checa tamanho e não tem nenhuma constante do protocolo.
Uma busca por `nomessages:` no arquivo não encontra nada.

Isso foi verificado, não presumido: o hook continua válido e o relay continua servindo para a
validação ao vivo entre dois emuladores.

### O que muda na *operação* do relay (para a próxima execução)

Nada no script, mas três coisas no roteiro de quem o usa:

- **O QR ficou muito menor.** Versão 40 (177 módulos por lado) virou no máximo versão 14 (73
  módulos). Se a próxima execução voltar a tentar o caminho de câmera virtual — que o run2 nunca
  conseguiu decodificar — vale reavaliar: o motivo suspeitado na época era densidade/distorção, e
  esse fator diminuiu bastante. O relay por broadcast continua sendo o caminho confiável.
- **O prazo útil aumentou.** O QR continua legível por 120 s, mas a troca já montada agora tem
  **300 s** para concluir busca do bundle + SAS + as duas confirmações. A sequência de 57 s medida
  no run4 continua folgada.
- **Passo novo, invisível para o script:** entre ler o QR e confirmar o SAS, cada aparelho busca o
  bundle de chaves do outro pela rede Tor. Os dois precisam estar em "Tor conectado" antes disso,
  e a tela mostra `Aguardando a rede Tor…` / `Buscando o pacote de chaves…`. Um SAS que não aparece
  para confirmar não é travamento: é a busca em andamento. O botão de confirmar só habilita depois
  que o pacote chega e confere com o hash assinado.

### Regressão

Nenhuma: arquivo não modificado. Revisão feita por leitura, junto com a mudança de formato do QR.
A execução ao vivo do script continua **pendente** (ver `docs/development/device-verification.md`,
linha T4.16).

## 2026-09-23 — Correção da revisão: `$ADB` default apontava para um caminho que não existe nesta máquina

### Como era antes

```sh
ADB="${ADB:-$USERPROFILE/.nomessages-tools/platform-tools/adb.exe}"
```

Uma limpeza anterior trocou o default de `$ADB` para uma pasta de ferramentas com o nome atual do
projeto, mas o `adb.exe` real desta máquina fica numa pasta de ferramentas com o nome antigo do
projeto (nome de pasta em disco, não versionado; reinstalar o SDK não é o objetivo desta tarefa). O
script, portanto, só funcionava se o operador exportasse `ADB` manualmente — e fixar qualquer um dos
dois caminhos (o do nome antigo ou o do nome novo, ambos específicos desta máquina) no script
deixaria o mesmo problema se repetir na próxima máquina diferente.

### Como é agora

```sh
ADB="${NOMESSAGES_ADB:-${ADB:-adb}}"
```

`NOMESSAGES_ADB` é a variável nova e documentada; `ADB` continua aceita por compatibilidade; sem
nenhuma das duas, o script cai para `adb` resolvido pelo `PATH` do shell — nenhum caminho de uma
máquina específica fica escrito no script. O comentário de "Variáveis de ambiente" no cabeçalho foi
atualizado para descrever essa ordem de resolução.

### Vantagens

- O script funciona em qualquer máquina que tenha `adb` no `PATH` (o caso comum de quem já rodou o
  `bootstrap-tools.sh`/instalou o Android SDK do jeito padrão) sem precisar exportar nada.
- Quem tem um `adb.exe` fora do `PATH` (como nesta máquina) só precisa setar `NOMESSAGES_ADB` uma
  vez; a variável não embute suposição nenhuma sobre onde as ferramentas foram instaladas.
- Nenhum caminho de uma máquina específica (nem o da pasta de ferramentas com o nome antigo, nem um
  caminho hipotético com o nome novo) fica hardcoded no script versionado.

### Por que a mudança foi feita

Achado 4 da revisão desta tarefa.
