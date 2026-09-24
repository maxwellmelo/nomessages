# Campainha (T4.17) — build completo e validação ao vivo em dois emuladores (2026-09-18)

Fase 8 de T4.17: **build e medição**, não implementação. A narrativa completa desta execução está na
seção "T4.17 — campainha, validação ao vivo em dois emuladores (2026-09-18)" de
`docs/development/device-verification.md`; este diretório guarda as evidências brutas.

**Emuladores.** A = AVD `nomessages35` / `emulator-5556` (Android 15, x86_64), cofre `TesteA`.
B = AVD `nomessages35b` / `emulator-5560` (Android 15, x86_64), cofre `TesteB`.
O aparelho físico `RX8MA0GD9ZY` estava conectado ao adb durante toda a sessão e **não foi tocado**.

**Por que os cofres foram recriados do zero.** Os contatos que existiam nos dois emuladores vinham de
pareamentos anteriores ao esquema v4 e portanto tinham `doorbell_token_issued` vazio — sem correção
retroativa possível (ver `docs/development/runtime-catalog.md` §8.3). Sem esse campo o lado que
recebe a batida não tem o que reconhecer, então nenhum toque poderia ser aceito. `pm clear` nos dois
aparelhos, cofres recriados com as senhas fixadas para esta validação e **pareamento novo** A↔B.

## Linha do tempo (UTC)

| Hora | Evento |
|---|---|
| 18:12–18:15 | Cofres recriados nos dois emuladores; Tor sobe nos dois ("Tor connected") |
| ~18:16 | Relay da oferta de A para B — QR formato 2, **405 bytes**, sha256 `6ab114a2…` |
| ~18:16 | B busca e verifica o pacote de chaves de A pela rede Tor ("Key bundle received and verified") |
| ~18:17 | Relay da resposta de B para A — **448 bytes**, sha256 `fba6b2a1…`; **SAS `806080` idêntico nos dois lados** |
| ~18:18 | Confirmação mútua; relay dos dois QRs de confirmação (**317 bytes** cada); "Contact verified and saved" nos dois |
| 18:20:42 | B, destrancado: PIDs `16971` (app) e `18555` (`:tor`) |
| ~18:20 | Confirmado em B que **"Pending message notice" está LIGADO** por padrão (switch `checked=true` + texto do modo ligado) |
| 18:21:15 | **B bloqueado.** `:tor` **sobrevive com o mesmo PID `18555`** — modo mínimo |
| 18:21:26 | B tem apenas a notificação **id=3**, canal `background_service`, `importance=2`, `vis=SECRET`, `ONGOING`; **não há id=2** |
| 18:22:20 | A envia a mensagem `campainha` para o contato TesteB |
| **18:22:46** | **Notificação da campainha em B — 26 s depois do envio.** id=2, canal `private_messages`, `importance=3`, `vis=SECRET`, `LOCAL_ONLY`, título `NoMessages`, corpo `You have messages waiting. Open NoMessages.` |
| 18:23:29 | B destrancado com a senha do cofre |
| 18:23:44 | **id=2 cancelada** no desbloqueio (nenhum registro de notificação do app). `:tor` com **PID novo `19421`** — ver o achado abaixo |
| 18:24:21 | **Mensagem `campainha` entregue em B**, ~2 min após o envio e ~50 s após o desbloqueio |
| 18:25–18:26 | Sonda isolada do handoff (lock/unlock do **mesmo** cofre, sem nenhuma batida) — reproduz o achado |

## Arquivos

| Arquivo | O que prova |
|---|---|
| `01-A-tor-connected-empty-vault.png`, `02-B-...` | Ponto de partida: cofres novos, vazios, Tor conectado nos dois |
| `03-A-sas-806080.png`, `04-B-sas-806080.png` | O mesmo SAS de seis dígitos nos dois aparelhos |
| `05-A-contact-verified-and-saved.png`, `06-B-...` | Pareamento concluído dos dois lados |
| `07-A-contact-TesteB.png`, `08-B-contact-TesteA.png` | Contatos gravados com impressão digital |
| `09-B-settings-pending-message-notice-on.png` | O aviso está **ligado por padrão** num cofre recém-criado, com o texto do modo ligado |
| `10-B-pids-before-lock.txt` / `11-B-pids-after-lock.txt` | `:tor` **sobrevive ao bloqueio com o mesmo PID** (gate 10(b), modo mínimo) |
| `12-B-notifications-after-lock.txt` | Só a notificação de foreground (id=3) existe enquanto ninguém tocou |
| `13-A-send-timestamp.txt` | Carimbo exato do envio em A |
| `14-B-doorbell-notification.txt` | **Dump literal da notificação id=2** — canal, importância, visibilidade, título e corpo |
| `15-B-notification-shade-doorbell.png` (removido; mostrava o ícone de uma versão anterior do app) | A mesma notificação vista na gaveta, com o aparelho bloqueado |
| `16-B-pids-and-notifications-after-unlock.txt` | id=2 sumiu no desbloqueio; PID do `:tor` mudou |
| `17-B-chatlist-message-after-unlock.png`, `18-B-chat-campainha-delivered.png` | A mensagem chega depois do desbloqueio |
| `19-B-handoff-probe.txt` | Sonda isolada do reaproveitamento do filho — **o achado** |
| `20-B-logcat-around-unlock.txt` | `ActivityManager: Process …:tor (pid 19421) has died` no desbloqueio |

## Achado: o filho `:tor` NÃO é reaproveitado ao destravar o mesmo cofre

Reproduzido duas vezes, uma delas numa sonda isolada sem nenhuma batida envolvida
(`19-B-handoff-probe.txt`): o `:tor` sobrevive ao bloqueio com o mesmo PID — o modo mínimo funciona —
mas ao **destravar o mesmo cofre, no mesmo processo de app**, o filho é derrubado e um novo sobe
(`18555 → 19421`, depois `19421 → 19636`), com a UI mostrando "Reconnecting to Tor — sends are
waiting", ou seja um bootstrap completo e não a adoção de um host já pronto.

Isso contraria o item (3) de T4.17 no roteiro e o `prepareDoorbellHandoff`
(`NoMessagesController.kt:599-609`), e é exatamente o sub-item **(vi) do gate 10(b)** de
`docs/release-checklist.md` ("unlocking the **same** vault reuses the live child rather than
rebuilding it").

Não é crash: o buffer `crash` do logcat está vazio e não há tombstone, `SIGABRT` nem `Fatal signal`.
O que aparece é `ActivityManager: Process dev.mx3.nomessages.debug:tor (pid 19421) has died: vis
BTOP` seguido de `Scheduling restart of crashed service … TorService … for connection`, que é o que
se espera quando `shutdownTransport` → `TorConnection.shutdown()` mata o filho enquanto um cliente
ainda está ligado a ele. Ou seja: **o ramo de destruição de `prepareDoorbellHandoff` foi tomado, não
o de adoção.**

Como `VaultSlot` é um enum de dois valores (`core/.../VaultSession.kt:5`), `kept.slot == slot` não
pode falhar para o mesmo cofre. Restam duas causas possíveis, que **não** foram discriminadas nesta
sessão porque isso exigiria acrescentar instrumentação ao código — fora do escopo desta fase:

1. `minimal` já estava `null` quando `prepareDoorbellHandoff` rodou (`kept == null`); ou
2. `kept.transport.alive` era `false` (`TorConnection.kt:107`, `!stopped && !dead.isCompleted`) —
   isto é, aquela `TorConnection` foi marcada como parada em algum ponto entre o `lock()` que a
   preservou e o `unlock()` que deveria adotá-la, mesmo com o processo filho ainda vivo.

**Impacto.** Nenhum para o critério de aceite da funcionalidade: bloquear, tocar, notificar,
destravar e entregar funcionam de ponta a ponta. O custo é de desempenho e de bateria — todo
desbloqueio paga de novo o bootstrap do Tor (~40–60 s aqui, "Reconnecting to Tor"), que é justamente
o que o reaproveitamento existe para evitar. Não foi corrigido nesta fase, por decisão explícita de
escopo.

## Adendo — troca do ícone pequeno de notificação (2026-09-18, pedido do usuário)

Ao ver a campainha funcionando ao vivo, o usuário reparou que o **small icon** das notificações ainda
era um monograma de duas letras desatualizado. Confirmado no código: os dois `pathData` de
`app/src/main/res/drawable/ic_nomessages_notification.xml` desenhavam literalmente essas letras.

Substituído pelo balão silenciado de `ic_launcher_monochrome.xml`, mapeado por transformação afim de
108→24 (`novo = (antigo - 54) * 0.370 + 12`), monocromático (`#FFFFFFFF` sobre transparente),
extensão pintada de ~20×17 dp centrada em 24×24. Detalhes e justificativa em
`docs/changes/ic_nomessages_notification.xml.md`.

Existe **um único** drawable de ícone de notificação, referenciado nos dois construtores de
`PrivacyNotifications.kt` (`build()` → ids 1 e 2; `backgroundNotification()` → id 3), e `TorService`
consome o segundo em vez de construir o seu. Trocar o arquivo cobriu as três, sem mudar Kotlin.

- APK reconstruído: sha256 `5051b96f…` (era `e0295f8f…`); lint inalterado, **0 erros, 43 avisos, 6 dicas**.
- Reinstalado nos dois emuladores, cofres destrancados, B bloqueado de novo.
- `21-B-notification-shade-new-icon.png` — gaveta de B mostrando a notificação **id=3** (`NoMessages`,
  seção "Silent") com o **glifo novo**, em contraste com o monograma antigo da rodada anterior
  (imagem removida deste diretório).

Nesta segunda rodada o emulador B ficou ~20 min em "Publishing the onion address", então a
notificação **id=2** não foi reencenada; a evidência do ícone usa a **id=3**, que compartilha
exatamente o mesmo drawable. A id=2 com todos os seus campos já está provada em
`14-B-doorbell-notification.txt`.

## Adendo (mais tarde no mesmo dia) — as duas causas possíveis acima foram discriminadas

As duas hipóteses listadas na seção "Achado" (`minimal == null`, ou `kept.transport.alive == false`)
**estão as duas erradas**. Com instrumentação guardada por `BuildConfig.DEBUG`, cinco rodadas da
sonda mostram a adoção sendo decidida corretamente e o transporte chegando vivo ao desbloqueio; o que
falha é o segundo `START` dentro do filho, recusado pelo keystore do Arti
(`Key already exists`). Ver `22-handoff-root-cause.txt`, `23-B-handoff-probe-instrumented.txt` e
`24-B-logcat-native-error.txt`. A correção é no nativo e ficou registrada como **T4.19**.

## Fase final (2026-09-18, fim do dia) — T4.19 corrigida: o `:tor` sobrevive ao ciclo inteiro

`tor::shutdown_transport` e `doorbell::shutdown` passaram a remover do keystore do `TorClient`
compartilhado a chave HsId do serviço que acabaram de derrubar (`tor::forget_onion_key`), em vez de
deixá-la para trás e fazer o `start`/`launch` seguinte do mesmo nickname morrer com
`Key already exists`. A chave é derivada da seed do cofre, então removê-la e reinseri-la devolve o
mesmo endereço onion. Registro em `docs/changes/tor.rs.md` e `docs/changes/doorbell.rs.md`
(seções de 2026-09-18).

A campainha entrou junto porque `NoMessagesController.kt:369` chama `doorbellStop()` em todo
desbloqueio: com o `:tor` passando a sobreviver, o segundo bloqueio do processo tropeçaria na chave
que o primeiro deixou.

`native/Cargo.toml` ganhou a feature `onion-service-cli-extra` do `arti-client` — é ela que expõe
`TorClient::keymgr()`. Pura feature: nenhum crate novo, `Cargo.lock` intocado,
`cargo test --all-features --locked` aceita.

- `cargo test --all-features --locked`: **46 passaram, 0 falharam, 1 ignorado** (eram 45; o novo é
  `relaunching_a_nickname_needs_its_key_forgotten_first`, que reproduz o defeito **offline**).
- `.so` recompiladas e APK reinstalada nos dois emuladores.
- Sonda de PID, cinco rodadas: PID do `:tor` **idêntico** nas cinco (antes mudava 5/5), zero
  `transport attempt: failed`, desbloqueio em **16 s** em vez de 40–60 s —
  `27-B-pid-stable-after-fix.txt`.
- Cenário de aceite completo re-executado no **mesmo** processo (sexto ciclo): batida, aviso id=2 em
  25 s com o texto fixo, desbloqueio cancelando o aviso, mensagem entregue —
  `28-B-acceptance-after-fix.txt`, `29-B-acceptance-delivered-after-fix.png`.
- Regressão Kotlin: `:app:testDebugUnitTest` 83/0/1 pulado, `:core:test` 102/0/2 pulados.

O sub-item **(vi)** do gate 10(b) sai de FALHANDO para **PASSANDO**. O aparelho físico
`RX8MA0GD9ZY` continuou sem ser tocado.
