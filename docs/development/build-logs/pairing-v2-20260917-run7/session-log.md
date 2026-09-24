# T4.16 — run7: validação ao vivo do orçamento de confirmação separado (2026-09-17)

Sessão que exercitou em aparelho a correção do achado estrutural de run6: a busca do bundle por Tor
e toda a fase humana passaram a ter um orçamento próprio, `CONFIRMATION_TTL_SECONDS = 240`, contado
a partir do **staging**, em vez de dividirem os 300 s ancorados na criação da oferta mais antiga.

Horários em **UTC**. Nenhum comando sem `-s <serial>` explícito. O aparelho físico `RX8MA0GD9ZY`
(Galaxy Note10+) **não recebeu nenhum comando**. Nenhum `pm clear` em nenhum dos dois emuladores.

---

## Resumo executivo

**O pareamento de duas rotações completou de ponta a ponta**, coisa que nunca tinha acontecido: em
run6 as duas tentativas de 250 s foram aceitas e morreram sem orçamento. A correção entrega o que
promete — A recebeu **240 s inteiros** a partir do seu próprio staging (`Expires in 03:59` na tela,
contra os 4 s e 35 s de run6a/6c).

**Mas a primeira tentativa desta sessão, que era "repetir 6a exatamente", falhou** — e falhou por um
motivo novo que o desenho de dois relógios cria e que vale registrar com clareza:

> O relógio de confirmação de **B** começa quando **B** faz staging, e no roteiro de 250 s o staging
> de B acontece no **começo** da espera, não no fim. Com B escaneando aos ~26 s da oferta, os 240 s
> de B terminaram **antes** de a resposta chegar a A.

Não é um defeito: é a consequência direta e documentada de cada aparelho contar do próprio staging.
Mas significa que **"repetir 6a exatamente" não é mais um cenário completável**, e que o parâmetro
que decide se o encontro de duas rotações dá certo não é o intervalo entre os relays — é **em que
ponto da janela de 120 s o outro aparelho escaneou**.

Três tentativas, e o que cada uma ensina:

| | Quando B escaneou | Intervalo entre relays | Rotações | A aceitou | Concluiu |
|---|---|---|---|---|---|
| **7a** | T0+26 s (cedo) | 250 s | 2 | — (B já tinha expirado) | ❌ **B expirou durante a espera** |
| **7b** | T0+102 s (tarde) | 168 s | 2 | ✅ SAS `065700`, **`Expires in 03:59`** | ❌ erro meu de automação (ver abaixo) |
| **7c** | T0+103 s (tarde) | **246 s** | **2** | ✅ SAS `030958`, **`Expires in 03:52`** | ✅ **COMPLETO** |

---

## Passos 1 e 2 — build e instalação

```
:core:test              102 testes, 0 falhas, 2 pulados   (eram 100 em run6)
:app:testDebugUnitTest   61 testes, 0 falhas, 1 pulado
:app:lintDebug           0 erros (42 avisos, 6 hints)
BUILD SUCCESSFUL
```

`23:32:45Z` — `install -r -t` nos dois, **`Success`**. SHA-256 do APK instalado:
`cba4779f85d9b472248ea4d5315f1a792b00cc2aec8f4eda5aa99ebc7a3e85da`.
`23:34:02Z` — os dois cofres destrancados, "Tor connected", contato de run6 intacto nos dois.

## O que o código faz agora (lido antes de interpretar qualquer resultado)

`PairingEngine.stage` passou de `expiresAt = minOf(first.created, second.created) + PENDING_TTL` para
`expiresAt = now() + CONFIRMATION_TTL_SECONDS` (240 s). `PENDING_TTL_SECONDS = 300` continua sendo o
orçamento de **aquisição** (quanto tempo uma oferta emitida segue respondível e respondendo a
`BundleRequest`), contado do `created` de cada oferta — inalterado desde run6, que é justamente a
parte que run6 já provou funcionar.

---

## 7a — "repetir 6a exatamente": **B expira durante a espera** (23:34:22Z → 23:39:01Z)

| Hora | Evento | Dado |
|---|---|---|
| 23:34:22 | **T0** — oferta #1 | `sha256=e3600ca4…` `nonce=9ad77751…` |
| 23:34:48.0 | relay A→B (T0+26 s), 405 B | |
| 23:34:52.5 | B: **`Fetching the other device's key bundle over Tor…`** | estado `FETCHING` capturado em dump |
| **23:34:55.6** | B: "Key bundle received and verified.", SAS **`173469`** | **busca por Tor em B ≤ 7,6 s** |
| 23:34:59 | B mostra `Expires in 03:51` | **B fez staging ~23:34:50 → prazo de B ≈ 23:38:49** |
| **23:36:30** | **ROTAÇÃO 1** (`00:04` → `01:59`) | |
| **23:38:29** | **ROTAÇÃO 2** (`00:00` → `01:57`) | oferta #3: `sha256=f6769b88…` `created=23:38:23Z` |
| 23:38:33 | B mostra `Expires in 00:14` | 14 s restantes |
| 23:38:53.7 | A posto no scanner e **verificado** (`Point the camera` = 1) | |
| 23:38:53.7 | relay B→A → **`erro: nenhum QR exibido em emulator-5560`** | B já tinha expirado ~4 s antes |
| 23:39:01 | B: **"Time ran out to finish pairing. Start again whenever you're ready."** | ❌ |

Evidência: `02-B-expired-during-wait.png`.

O acompanhamento minuto a minuto dos dois relógios está no log desta sessão: enquanto A contava
`New QR in …` e rotacionava, B contava `Expires in 03:08 → 02:25 → 01:31 → 00:53 → 00:14` sem
interrupção. **O relógio de B nunca parou de correr durante a espera**, porque ele começou no staging
de B.

**Passo 3 do roteiro cumprido:** desta vez A foi explicitamente verificada na tela de leitura antes
de qualquer injeção (`Point the camera` = 1), exatamente para não repetir o erro de 6a. A falha aqui
não foi essa — foi o prazo de B.

## 7b — B escaneia tarde: A recebe 240 s inteiros, mas erro meu de automação (23:40:08Z → 23:45:55Z)

Ajuste deliberado: se o relógio de B começa quando B escaneia, então B deve escanear o mais **tarde**
possível dentro da janela de 120 s da oferta #1, e não logo no começo.

| Hora | Evento | Dado |
|---|---|---|
| 23:40:08 | **T0** — oferta #1 | `sha256=f00b0107…` `nonce=8d4e58f0…` |
| **23:41:50.1** | relay A→B **a T0+102 s** (dentro dos 120 s de leitura, com 18 s de folga) | 405 B |
| **23:41:55.2** | B: bundle verificado, SAS **`065700`** | **busca por Tor em B ≤ 5,1 s** |
| 23:41:58 | B: `Expires in 03:54` | prazo de B ≈ 23:45:52 |
| 23:42:30 | **ROTAÇÃO 1** | oferta #2: `sha256=eb9f952a…` `created=23:42:09Z` |
| 23:44:13 | **ROTAÇÃO 2** | oferta #3: `sha256=95ef3758…` `created=23:44:09Z` |
| 23:44:38.6 | A verificada no scanner, relay B→A (**T0+270 s**) | |
| **23:44:40.3** | **A ACEITOU** — SAS **`065700`**, **`Expires in 03:58`** | ✅ **240 s frescos** |
| 23:45:04 | tentativa de confirmar em A | **não registrou** |
| 23:45:52 | B expira | ❌ |

Capturas: `03-A-7b-sas-fresh-240s-after-two-rotations.png` (o momento da aceitação, com o contador
fresco e o estado `FETCHING` ainda na tela) e `04-A-7b-budget-still-running-after-B-expired.png`.

**Erro meu, não do app.** Li as coordenadas do campo de apelido às 23:44:40, quando A ainda estava
buscando o bundle. Quando a busca terminou, o painel *"Fetching…"* encolheu e **todo o formulário
subiu ~210 px** (`EditText` de y=1875 para y=1665). Meu toque foi para a posição antiga, o
`input text` não foi para campo nenhum, o apelido ficou vazio e o botão "The codes match" é inerte
sem apelido (`confirmPairing` exige `alias.trim().length in 1..80`). Confirmado depois: o dump às
23:45:55 mostrava o campo **vazio** e o botão ainda presente, com A ainda viva em `Expires in 02:49`.

É a terceira vez nesta série que coordenadas lidas em T ficam obsoletas em T+Δ por um painel de
status mudando de altura. A regra que faltava: **esperar o painel assentar e reler as coordenadas
imediatamente antes de cada toque, e verificar o conteúdo do campo depois de digitar.**

## 7c — **PAREAMENTO COMPLETO com duas rotações** (23:47:11Z → 23:54:30Z)

Mesmo desenho de 7b, com a disciplina de verificação aplicada em cada passo.

| Hora | Evento | Dado |
|---|---|---|
| 23:47:11 | **T0** — oferta #1 | `sha256=9aa94a0a…` `nonce=923fe359…` |
| **23:48:54.1** | relay A→B **a T0+103 s** | 405 B, integridade conferida |
| 23:48:59.3 | B: **`Fetching … over Tor…`** | |
| **23:49:02.3** | B: bundle verificado, SAS **`030958`** | **busca por Tor em B ≤ 8,2 s** |
| 23:49:05 | B: `Expires in 03:51` | **prazo de B ≈ 23:52:52** |
| 23:49:20 | B pré-preparada: rolagem, apelido `TesteA` **digitado e verificado por dump** | |
| **23:49:39** | **ROTAÇÃO 1** | oferta #2: `sha256=a0f9eacd…` `created=23:49:11Z` `nonce=372aa33b…` |
| **23:51:16** | **ROTAÇÃO 2** | oferta #3: `sha256=61488a36…` `created=23:51:11Z` `nonce=c07f670f…` |
| 23:51:17 | B: `Expires in 01:35` | 95 s restantes |
| 23:51:37 | A no scanner, **verificada** (`Point the camera` = 1) | passo 3 do roteiro |
| **23:51:39.9** | **relay B→A** | **245,8 s após o relay A→B; T0+268,9 s** |
| 23:51:41.5 | **B confirma** (apelido já preenchido) → "Confirmation recorded. Now scan each other's confirmation QR." | |
| **23:51:50.6** | **A ACEITOU** — SAS **`030958`**, **`Expires in 03:52`** | ✅ **240 s frescos** |
| 23:52:04.1 | apelido `TesteB` digitado em A e **verificado por dump** antes de confirmar | |
| 23:52:10.2 | **A confirma** → A passa a exibir seu QR de confirmação | |
| 23:52:31.6 | relay B→A da **confirmação (317 B / 424 chars)** → **A: "Contact verified and saved."** | |
| **23:52:49.5** | injeção da confirmação de A em B → **B: "Contact verified and saved."** | ✅ **COMPLETO** |
| 23:53:50.7 | A → B `a2b-run7-01` | **✓✓**, recebida e exibida em B |
| 23:54:18.6 | B → A `b2a-run7-01` | **✓✓**, recebida e exibida em A |

Contatos depois: **um em cada aparelho** (upsert, não duplicata) —
A vê `TesteB` fp `7f8b c646 … 6bc2 a4cd`; B vê `TesteA` fp `e3fc 4e3d … 67f4 5c2f`.

### Margem de confirmação usada e restante — o número que o roteiro pediu

| | Início do relógio | Prazo | Concluiu | **Margem restante** |
|---|---|---|---|---|
| **A** | staging 23:51:40 | 23:55:40 (240 s) | 23:52:38 | **~182 s (76 %)** |
| **B** | staging 23:48:55 | **23:52:52** (240 s) | 23:52:50 | **~2 s (1 %)** |

**A fase de confirmação em si levou 70 s** (do staging de A às 23:51:40 até B salvar às 23:52:50):
duas confirmações de SAS, dois apelidos digitados e verificados, e duas trocas de QR de confirmação,
com um dump de verificação antes de cada toque. Bem dentro dos 240 s — **pelo lado de A**.

Pelo lado de B, sobraram **cerca de 2 segundos**. Não foi a fase de confirmação que consumiu o
orçamento de B: foram os 165 s em que B ficou parada esperando a resposta chegar a A, entre o seu
staging (23:48:55) e o relay de volta (23:51:40). O orçamento de B é gasto pela **espera**, não pela
cerimônia.

---

## Leitura honesta do resultado

1. **A correção faz o que promete.** Quem faz staging por último — A, neste roteiro — recebe os 240 s
   inteiros, medidos na tela (`Expires in 03:59` e `03:52`) contra os 4 s e 35 s de run6. Com isso a
   cerimônia deixou de ser uma corrida: 70 s de fase de confirmação, com verificação a cada passo, e
   ainda sobraram 182 s em A.
2. **O gargalo mudou de lado, não desapareceu.** Agora quem limita é o aparelho que faz staging
   **primeiro**, porque seu relógio corre durante toda a espera. Em 7a isso fez B expirar antes de a
   resposta sequer chegar a A; em 7c sobraram 2 s.
3. **O que decide o sucesso não é o intervalo entre os relays, é quando o outro aparelho escaneou.**
   O orçamento compartilhado é `240 − (instante do relay de volta − instante do staging de B)`.
   Escaneando cedo (7a, T0+26) sobram −10 s: impossível. Escaneando tarde (7c, T0+103) sobram ~75 s:
   apertado mas suficiente. É a mesma aritmética de run6, deslocada de uma ponta do fluxo para a
   outra.
4. **No uso real isto é invisível**, como a nota do desenho antecipa: duas pessoas juntas escaneiam e
   respondem com segundos de diferença, então os dois relógios ficam praticamente alinhados e cada
   lado tem os 240 s quase inteiros. O cenário de duas rotações com 250 s de espera é deliberadamente
   patológico — existe para forçar a sobreposição tripla de ofertas, não porque descreva um encontro
   plausível.
5. **Ainda assim vale registrar**, porque é o tipo de coisa que volta como relatório de campo: quem
   escaneia primeiro e depois espera muito (a outra pessoa se distrai, o telefone cai, alguém tem de
   digitar uma senha) queima o próprio orçamento em silêncio, e o aparelho que expira é o que **não**
   fez nada de errado.

## Medidas de Tor

| Medida | Valor |
|---|---|
| B ← A (7a) | ≤ 7,6 s |
| B ← A (7b) | ≤ 5,1 s |
| B ← A (7c) | ≤ 8,2 s |
| A ← B (7b) | ≤ ~2 s |
| A ← B (7c) | ≤ ~11 s (`FETCHING` visível na captura, verificado no dump seguinte) |
| Tentativas necessárias | **1**, em todas |

Estado `FETCHING` observado e registrado **três vezes** nesta sessão (7a e 7c em dump, 7b em
captura de tela) — em run5 ele constava como nunca observado.

## Arquivos de evidência

Todos em `E:\Vibe Coding\NoMessages\docs\development\build-logs\pairing-v2-20260917-run7\`:

| Arquivo | O que prova |
|---|---|
| `01-A-7a-scanner-relay-failed.png` | 7a: A na tela de leitura, verificada, quando o relay falhou por B já ter expirado |
| `02-B-expired-during-wait.png` | **7a: B expirada durante a espera** — o achado desta sessão |
| `03-A-7b-sas-fresh-240s-after-two-rotations.png` | **A aceita a oferta de duas rotações atrás e recebe `Expires in 03:59`** — a correção funcionando, com o estado `FETCHING` na mesma tela |
| `04-A-7b-budget-still-running-after-B-expired.png` | 7b: A ainda com orçamento enquanto B já tinha expirado |
| `05-A-contact-verified-and-saved.png`, `06-B-contact-verified-and-saved.png` | **7c: "Contact verified and saved." nos dois aparelhos** |
| `07-A-chat-run7-final.png`, `08-B-chat-run7-final.png` | **7c: `a2b-run7-01` e `b2a-run7-01` nos dois sentidos** |
| `09-A-logcat-run7.txt`, `10-B-logcat-run7.txt` | logcat filtrado da janela de pareamento |
| `11-A-logcat-fatals.txt`, `12-B-logcat-fatals.txt` | **vazios quanto ao app** — nenhum crash |
| `session-log.md` | este arquivo |

## Estado em que os emuladores foram deixados

| Papel | Serial | Estado |
|---|---|---|
| A | `emulator-5556` | app em primeiro plano, **conversa com `TesteB` aberta**, cofre **destrancado** com a senha real original (**nunca** `pm clear`), "Tor connected", **1 contato pareado (`TesteB`)**, `allow_capture=1`, `allow_qr_inject=1`, IMEs desabilitados, `svc power stayon true`, build run7 instalado |
| B | `emulator-5560` | app em primeiro plano, **conversa com `TesteA` aberta**, cofre **destrancado** (o mesmo de run5, `EncaminharTesteGamma03`), "Tor connected", **1 contato pareado (`TesteA`)**, `allow_capture=1`, `allow_qr_inject=1`, IMEs desabilitados, `svc power stayon true`, build run7 instalado |

`RX8MA0GD9ZY` **não recebeu nenhum comando desta sessão**.

## Armadilha de automação nova

**Espere o painel de status assentar antes de ler coordenadas.** O painel "Fetching the other
device's key bundle over Tor…" encolhe quando a busca termina e **empurra todo o formulário abaixo
dele ~210 px para cima**. Coordenadas lidas durante a busca ficam erradas segundos depois. Releia
imediatamente antes de cada toque e **verifique o conteúdo do campo depois de digitar** — um apelido
vazio torna "The codes match" inerte, sem nenhuma mensagem de erro na tela.
