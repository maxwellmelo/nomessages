# app/src/main/res/values/strings.xml e app/src/main/res/values-en/strings.xml

## 2026-09-14 — T2.1: string `network_publishing` (PT/EN)

### Como era antes

Havia cinco strings de rede, uma por valor de `NetworkStatus`:

```xml
<string name="network_starting">Conectando ao Tor…</string>
<string name="network_online">Tor conectado</string>
```

```xml
<string name="network_starting">Connecting to Tor…</string>
<string name="network_online">Tor connected</string>
```

### Como ficou

Uma string nova em cada arquivo, logo depois de `network_starting`, mantendo a mesma ordem nos dois idiomas:

```xml
<string name="network_publishing">Publicando o endereço onion — ainda não é possível receber.</string>
```

```xml
<string name="network_publishing">Publishing the onion address — cannot receive yet.</string>
```

### Vantagens

- Paridade PT/EN preservada: mesmo nome, mesma posição, mesma quantidade de strings nos dois arquivos, que é o que o gate de paridade verifica.
- O texto informa a consequência prática ("ainda não é possível receber"), no mesmo estilo das demais mensagens de rede, em vez de expor jargão de descritor/HSDir.

### Por que a mudança foi feita

T2.1: `NetworkStatus.PUBLISHING` precisa de texto próprio nos dois idiomas.

---

## 2026-09-16 — Duas strings novas: aviso de teclado de terceiros e detecção de captura (T4.9)

### Como era antes

Nenhuma string cobria um aviso de teclado de terceiros ativo nem uma notificação de tentativa de
captura de tela detectada.

### Como ficou

Duas strings novas em cada arquivo, logo após `error_contact_limit` (última entrada existente),
mantendo a mesma posição/ordem nos dois idiomas:

```xml
<string name="third_party_keyboard_warning">Teclado de terceiros ativo: ele pode ver o que você digita</string>
<string name="screen_capture_detected">Tentativa de captura de tela detectada</string>
```

```xml
<string name="third_party_keyboard_warning">Third-party keyboard active: it can see what you type</string>
<string name="screen_capture_detected">Screen capture attempt detected</string>
```

### Vantagens

- Paridade PT/EN preservada: mesmas chaves, mesma posição, mesma contagem nos dois arquivos.
- `third_party_keyboard_warning` é usada no composer (`ChatScreen.kt`) e nas telas de setup/lock
  (`SetupLockScreens.kt`); `screen_capture_detected` no `Snackbar` de `MainActivity.kt`.

### Por que a mudança foi feita

T4.9, itens 4 e 5.

## 2026-09-17 — Três strings novas: seletor de encaminhamento e rótulo "Encaminhada"

### Como era antes

Nenhuma chave com prefixo `forward` existia em nenhum dos dois arquivos (`grep -c forward` = 0 nos
dois, verificado imediatamente antes da edição).

### Como ficou

`app/src/main/res/values/strings.xml` (Português — locale **default** deste app; não existe
`values-pt`), inseridas logo antes de `</resources>`:

```xml
<string name="forward_to">Encaminhar para</string>
<string name="forward_no_targets">Nenhum contato ou grupo disponível para encaminhar</string>
<string name="forwarded_label">Encaminhada</string>
```

`app/src/main/res/values-en/strings.xml` (Inglês), mesmas chaves, mesma posição:

```xml
<string name="forward_to">Forward to</string>
<string name="forward_no_targets">No contacts or groups available to forward to</string>
<string name="forwarded_label">Forwarded</string>
```

### Onde cada uma é usada (`ChatScreen.kt`)

- `forward_to` — título do `AlertDialog` do seletor de destinatários.
- `forward_no_targets` — corpo do mesmo diálogo quando `state.forwardTargets` está vazio (nenhum
  contato pareado utilizável e nenhum grupo `READY`); nesse caso o botão de envio fica desabilitado.
- `forwarded_label` — rótulo discreto, em itálico, precedido do glifo `↪`, na primeira linha da
  bolha quando `message.forwarded` é verdadeiro.

O botão de envio e o de cancelar reutilizam `send`/`cancel`, que já existiam — nenhuma string
duplicada foi criada.

### Chave deliberadamente NÃO criada: `forward`

O toque longo abre o seletor **diretamente**, sem menu de contexto de um item só (ver
`docs/changes/ChatScreen.kt.md`), então não há rótulo de item de menu para traduzir. A chave entra
quando/se um segundo comando de bolha justificar o menu.

### Vantagens

- Paridade PT/EN preservada: `diff` das chaves dos dois arquivos continua vazio depois da edição.
- Inserção no fim do arquivo, uma edição por arquivo, para minimizar conflito com o agente que
  editava estes mesmos arquivos em paralelo.
- Nenhum texto fixo em código na tela de conversa: os três textos novos são localizáveis.

### Por que a mudança foi feita

Pedido do usuário: encaminhar mensagem no estilo WhatsApp (parte C — camada de UI).

---

## 2026-09-17 — Quinze strings novas + `password_requirement` atualizado (T4.10)

Documentos irmãos: `docs/changes/Components.kt.md`, `docs/changes/SetupLockScreens.kt.md`,
`docs/changes/SettingsScreen.kt.md` (quem consome estas strings), `docs/changes/PasswordPolicy.kt.md`
(a política que `password_requirement` descreve).

### Como era antes

```xml
<string name="password_requirement">Use pelo menos 16 caracteres alfanuméricos. A criação pode levar alguns segundos.</string>
```

```xml
<string name="password_requirement">Use at least 16 alphanumeric characters. Creation may take a few seconds.</string>
```

Nenhuma chave de força de senha, confirmação em tempo real ou similaridade real/pânico existia.

### Como ficou

`password_requirement` foi reescrito para refletir a política nova do `:core` (piso de 12, símbolos
liberados — ver `docs/changes/PasswordPolicy.kt.md`):

```xml
<string name="password_requirement">Use pelo menos 12 caracteres. Símbolos e acentos são permitidos. A criação pode levar alguns segundos.</string>
```

```xml
<string name="password_requirement">Use at least 12 characters. Symbols and accented letters are allowed. Creation can take a few seconds.</string>
```

Quinze chaves novas, inseridas logo antes de `</resources>` em cada arquivo, na mesma ordem/posição
nos dois (`app/src/main/res/values/strings.xml` — PT, locale default deste app — e
`app/src/main/res/values-en/strings.xml`):

| Chave | PT | EN |
|---|---|---|
| `password_strength_weak` | Fraca | Weak |
| `password_strength_fair` | Razoável | Fair |
| `password_strength_good` | Boa | Good |
| `password_strength_strong` | Forte | Strong |
| `password_hint_too_short` | Use mais caracteres | Use more characters |
| `password_hint_sequential` | Evite sequências como "abcd" ou "1234" | Avoid sequences like "abcd" or "1234" |
| `password_hint_repeated` | Evite repetições | Avoid repeated characters |
| `password_hint_common_word` | Evite palavras comuns | Avoid common words |
| `password_hint_date_or_year` | Evite datas ou anos | Avoid dates or years |
| `password_hint_trivial_suffix` | Evite o padrão "Palavra1!" | Avoid the "Word1!" pattern |
| `password_hint_add_variety` | Combine letras, números e símbolos | Combine letters, numbers and symbols |
| `password_match` | As senhas coincidem | Passwords match |
| `password_mismatch` | As senhas não coincidem | Passwords do not match |
| `panic_password_too_similar` | A senha de pânico é parecida demais com a senha real. | The panic password is too similar to the real password. |
| `panic_password_must_differ` | A senha de pânico não pode ser igual à senha real. | The panic password cannot be the same as the real password. |

As aspas literais dentro de `password_hint_sequential`/`password_hint_trivial_suffix` são escapadas
com `\"` (nenhuma outra string do arquivo continha aspas literais antes desta mudança, então não
havia um precedente de escaping a seguir; `\"` é a forma padrão do formato de recursos do Android
para uma string não delimitada por aspas externas).

### Onde cada grupo é usado

- `password_strength_*` — rótulo de `PasswordStrengthMeter` (`Components.kt`), um por faixa de
  `PasswordStrength.score` (0/1→fraca, 2→razoável, 3→boa, 4→forte).
- `password_hint_*` — `PasswordStrengthTips` (`Components.kt`), mapeadas de `PasswordFeedback.*`
  (`:core`); `password_hint_too_short` é reaproveitada tanto para `TOO_SHORT` quanto para
  `ADD_LENGTH` (mesma dica prática, "use mais caracteres"), evitando uma 16ª chave redundante.
  `PasswordFeedback.LOOKS_STRONG` deliberadamente não tem string correspondente aqui — não é
  mostrada como dica (ver `docs/changes/Components.kt.md`).
- `password_match`/`password_mismatch` — `PasswordMatchHint` (`Components.kt`), usada em `SetupScreen`
  (duas vezes) e `PanicPasswordDialog` (uma vez).
- `panic_password_too_similar`/`panic_password_must_differ` — aviso inline em `SetupScreen`, mapeado
  das duas mensagens exatas que `PasswordPolicy.validatePair` lança (ver
  `docs/changes/SetupLockScreens.kt.md`). Não usadas em `PanicPasswordDialog`, que não tem a senha
  real disponível para comparar (ver `docs/changes/SettingsScreen.kt.md`).

### Vantagens

- Paridade PT/EN preservada: mesmas 15 chaves, mesma posição relativa, nos dois arquivos — conferido
  lendo ambos os arquivos imediatamente antes de cada inserção, protocolo já usado nas edições
  anteriores deste mesmo arquivo (ver as seções acima), necessário porque outro agente também edita
  estes dois arquivos em paralelo.
- `password_hint_too_short` reaproveitada para duas chaves de feedback do `:core` evita uma string
  redundante sem perder tradução por chave semântica (`TOO_SHORT` e `ADD_LENGTH` são, na prática, o
  mesmo conselho).
- O texto de `panic_password_too_similar`/`panic_password_must_differ` usa exatamente a redação em
  português pedida pelo usuário, palavra por palavra.

### Por que a mudança foi feita

T4.10 (ver `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`).

---

## 2026-09-17 — Três strings sem caller removidas dos dois idiomas

### Como era antes

Três chaves, na mesma posição nos dois arquivos, logo depois de `chats`, só eram lidas por
`HomeScreen.kt`.

### Como ficou

As três chaves foram removidas dos dois arquivos, nos dois idiomas, sem deixar buraco na
numeração/ordem das chaves vizinhas:

```xml
<string name="chats">Conversas</string>
<string name="contacts">Contatos</string>
```

```xml
<string name="chats">Chats</string>
<string name="contacts">Contacts</string>
```

### Vantagens

- Paridade PT/EN preservada: as três chaves saíram dos dois arquivos, na mesma posição, então a
  contagem/ordem continua idêntica nos dois.
- Acompanha a limpeza de código em `HomeScreen.kt` (ver `docs/changes/HomeScreen.kt.md`) — remover
  o código antes e a string depois evita um `lint` de recurso não usado ficando pra trás.

### Por que a mudança foi feita

Remoção completa das strings sem caller em vez de mantê-las sem uso.

---

## 2026-09-17 — `brand_mark`: monograma antigo → "NM" (nova paleta "Grafite e Âmbar", T4.14)

Nos dois arquivos (`values/strings.xml` e `values-en/strings.xml`, mesma chave, mesmo valor nos
dois idiomas — não é texto traduzível, são as iniciais da marca):

```xml
<!-- antes -->
<string name="brand_mark">XX</string>

<!-- depois -->
<string name="brand_mark">NM</string>
```

O valor antigo eram as iniciais do codinome usado antes do nome atual do app; `"NM"` são as de "NoMessages". A chave
não é lida diretamente por nenhum `Composable` hoje (`Components.kt`'s `BrandMark()` usa um texto
fixo `"NM"` próprio, atualizado junto — ver `docs/changes/Components.kt.md`), mas foi mantida em
sincronia porque é o mesmo conteúdo semântico e um recurso não referenciado hoje pode voltar a ser
lido amanhã sem que alguém lembre de revisar seu valor.

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" — parte do rebrand de marca, não só de cor.

---

## 2026-09-17 — Sete strings novas: busca do pacote de chaves pela rede Tor e regeneração do QR (T4.16)

Contexto completo: ver `docs/changes/PairingScreen.kt.md`, `docs/changes/PairingLifecycle.kt.md` e
`docs/changes/UiContract.kt.md`. O QR formato 2 tirou o pacote de chaves PQXDH de dentro do QR e
passou a buscá-lo pela rede Tor depois da leitura — a tela de pareamento precisa comunicar o
progresso dessa busca, sua falha, e a regeneração automática de uma oferta expirada.

### Como era antes

Nenhuma das sete chaves existia em nenhum dos dois arquivos (`grep -c pairing_bundle` e
`grep -c qr_refreshed` = 0 nos dois, e `error_pairing_bundle_missing` também ausente).

### Como ficou

`app/src/main/res/values/strings.xml` (Português — locale **default** deste app), inseridas logo
após `qr_expires`:

```xml
<!-- Busca do pacote de chaves pela rede Tor (QR formato 2, T4.16). -->
<string name="pairing_bundle_waiting_tor">Aguardando a rede Tor ficar pronta nos dois aparelhos…</string>
<string name="pairing_bundle_fetching">Buscando o pacote de chaves do outro aparelho pela rede Tor…</string>
<string name="pairing_bundle_ready">Pacote de chaves recebido e conferido.</string>
<string name="pairing_bundle_failed">Não foi possível falar com o outro aparelho pela rede Tor. Confira se ele está com o app aberto e conectado.</string>
<string name="pairing_bundle_retry">Tentar de novo</string>
<string name="qr_refreshed">O QR expirou e foi gerado outro. Peça para escanear o novo.</string>
```

e, mais abaixo, junto das demais mensagens de erro genéricas:

```xml
<string name="error_pairing_bundle_missing">O pacote de chaves do outro aparelho ainda não chegou. Aguarde a busca pela rede Tor terminar.</string>
```

`app/src/main/res/values-en/strings.xml` (Inglês), mesmas sete chaves, mesma posição relativa nos
dois blocos:

```xml
<!-- Key-bundle fetch over Tor (QR format 2, T4.16). -->
<string name="pairing_bundle_waiting_tor">Waiting for Tor to be ready on both devices…</string>
<string name="pairing_bundle_fetching">Fetching the other device’s key bundle over Tor…</string>
<string name="pairing_bundle_ready">Key bundle received and verified.</string>
<string name="pairing_bundle_failed">Could not reach the other device over Tor. Check that it has the app open and connected.</string>
<string name="pairing_bundle_retry">Try again</string>
<string name="qr_refreshed">The QR expired and a new one was generated. Ask for the new one to be scanned.</string>
```

```xml
<string name="error_pairing_bundle_missing">The other device’s key bundle has not arrived yet. Wait for the Tor fetch to finish.</string>
```

### O que cada uma diz ao usuário, e quando

- **`pairing_bundle_waiting_tor`** — "Aguardando a rede Tor ficar pronta nos dois aparelhos…".
  Mostrada quando `PairingUi.bundleStatus == WAITING_FOR_TOR`: os dois lados ainda não têm circuito
  Tor pronto para a troca. Diz explicitamente **"nos dois aparelhos"** porque a diretriz de
  direcionalidade do protocolo (ver `docs/changes/Pairing.kt.md`) exige os dois lados prontos antes
  de a busca sequer começar — não é só a conexão local do usuário que pode estar faltando.
- **`pairing_bundle_fetching`** — "Buscando o pacote de chaves do outro aparelho pela rede Tor…".
  Mostrada em `FETCHING`: os dois lados já estão prontos e a requisição
  `BundleRequest`/`BundleResponse` está de fato em trânsito. Nomeia explicitamente "a rede Tor" para
  que uma demora (5–40 s é comum para um circuito novo) não pareça um travamento do app.
- **`pairing_bundle_ready`** — "Pacote de chaves recebido e conferido.". Mostrada em `READY`: o
  pacote chegou **e** seu hash bateu com o `bundleHash` assinado dentro da oferta. A palavra
  "conferido" é deliberada — comunica que houve verificação, não só recebimento, o que é a garantia
  de segurança real da etapa (ver a seção "Argumento de segurança" em
  `docs/changes/Pairing.kt.md`).
- **`pairing_bundle_failed`** — "Não foi possível falar com o outro aparelho pela rede Tor. Confira
  se ele está com o app aberto e conectado.". Mostrada em `FAILED`, com uma ação concreta sugerida
  (conferir se o outro aparelho está com o app aberto), porque a causa mais provável de falha nesta
  etapa é exatamente essa — não um defeito do próprio Tor.
- **`pairing_bundle_retry`** — "Tentar de novo". Rótulo do botão que só aparece enquanto
  `PairingLifecycle.canRetryBundleFetch` devolve `true` (falha real e deadline ainda não estourada).
- **`qr_refreshed`** — "O QR expirou e foi gerado outro. Peça para escanear o novo.". Mostrada
  quando a tela regenera automaticamente uma oferta que ninguém escaneou a tempo
  (`PairingQrAction.REGENERATE`, ver `docs/changes/PairingLifecycle.kt.md`), para que o usuário não
  ache que o QR na tela é o mesmo que já mostrou/tentou mostrar antes.
- **`error_pairing_bundle_missing`** — "O pacote de chaves do outro aparelho ainda não chegou.
  Aguarde a busca pela rede Tor terminar.". Mensagem de erro genérica reservada para o caso em que
  alguma ação dependente do pacote de chaves (ex.: confirmar o SAS) for tentada antes de
  `bundleStatus == READY` por um caminho que não passa pelo `enabled` do botão — mesmo papel que as
  demais chaves `error_*` já cumprem no arquivo, uma rede de segurança textual para um estado que a
  UI já devia ter impedido pela gate (`PairingLifecycle.canConfirmSas`).

### Vantagens

- Paridade PT/EN preservada: as sete chaves entraram nos dois arquivos, mesma posição relativa nos
  dois blocos (cinco logo após `qr_expires`, uma junto de `error_*`), mesma contagem — conferido
  lendo os dois arquivos imediatamente antes da edição, mesmo protocolo das seções anteriores deste
  documento.
- `pairing_bundle_waiting_tor`/`pairing_bundle_fetching` dão ao usuário um diagnóstico específico em
  cada etapa, em vez de um "carregando…" genérico que esconderia se o problema é a própria conexão
  do usuário ou o outro aparelho.
- Nenhum texto fixo em código na tela de pareamento: todas as mensagens novas são localizáveis.

### Por que a mudança foi feita

T4.16: QR de pareamento formato 2 (busca do pacote de chaves PQXDH pela rede Tor em vez de dentro
do QR).

---

## 2026-09-17 — Passo de polimento de UX: quatro chaves novas e um ajuste de texto em inglês

Continuação, mesmo dia, da seção T4.16 logo acima — revisão de UX/UI do fluxo de pareamento, ver
`docs/changes/PairingScreen.kt.md` (seção com o mesmo título) para onde cada chave é usada.

### Chaves novas

`app/src/main/res/values/strings.xml`:

```xml
<string name="pairing_bundle_fetching_hint">Isso pode levar até 40 segundos. A busca continua sozinha, não é preciso fazer nada.</string>
<string name="pairing_bundle_failed_no_retry">O tempo deste pareamento está terminando. Cancele e comece de novo em instantes.</string>
<string name="pairing_timed_out">O tempo para concluir o pareamento acabou. Comece de novo quando quiser.</string>
```

`app/src/main/res/values-en/strings.xml`:

```xml
<string name="pairing_bundle_fetching_hint">This can take up to 40 seconds. It keeps trying automatically — no action needed.</string>
<string name="pairing_bundle_failed_no_retry">This pairing attempt is almost out of time. Cancel and start again in a moment.</string>
<string name="pairing_timed_out">Time ran out to finish pairing. Start again whenever you’re ready.</string>
```

- **`pairing_bundle_fetching_hint`** — mostrada só durante `FETCHING` (não durante
  `WAITING_FOR_TOR`, que é um problema diferente e tipicamente breve), abaixo da mensagem principal
  de busca. Existe para que uma busca de 30-40 s (comum para um circuito Tor novo, ver
  `startBundleFetch` em `NoMessagesController.kt`) não pareça travada: diz um número concreto
  ("até 40 segundos") em vez de um "aguarde" vago, e deixa claro que **o usuário não precisa fazer
  nada** — o motor já está tentando de novo sozinho por trás.
- **`pairing_bundle_failed_no_retry`** — mostrada quando a busca falhou e o botão "Tentar de novo"
  já não pode mais aparecer (`PairingLifecycle.canRetryBundleFetch == false`, ou seja, o prazo de
  300 s está acabando). Sem essa mensagem, o cartão de falha ficava sem nenhuma ação nem explicação
  nos últimos instantes — um beco sem saída silencioso bem no momento em que a troca está prestes a
  ser cancelada automaticamente.
- **`pairing_timed_out`** — mostrada depois que uma troca estagiada é cancelada sozinha por
  timeout (ver a nova flag `timedOut` em `PairingScreen.kt`, seção correspondente em
  `docs/changes/PairingScreen.kt.md`). Sem ela, a tela simplesmente voltava para "comece o
  pareamento" sem explicação alguma, como se algo tivesse quebrado silenciosamente.

Tom deliberadamente calmo nas três: nenhuma menciona termos técnicos do protocolo (nenhuma diz
"nonce", "onion", "circuito" ou "Kyber") — só o que o usuário precisa saber ("está demorando, é
normal" / "o tempo está acabando" / "acabou o tempo, tente de novo"), consistente com o resto do
app (compare com `camera_denied`, `pairing_bundle_failed`: frase curta, uma ação clara, sem jargão).

### Ajuste de texto em inglês: `qr_refreshed`

**Como era:** "The QR expired and a new one was generated. Ask for the new one to be scanned."

**Como ficou:** "The QR expired and a new one was generated. Ask the other person to scan it."

A frase original usava voz passiva ("ask for X to be scanned") de um jeito que soa artificial em
inglês corrente; a nova frase nomeia quem faz o quê ("ask the other person to scan it"), mais perto
de como o restante do arquivo em inglês já fala com o usuário (frases diretas, sujeito explícito).
O texto em português (`"Peça para escanear o novo."`) já estava natural e não foi alterado.

### Vantagens

- As três mensagens novas fecham exatamente os três pontos que a revisão de UX pediu: a busca não
  parece travada, a falha não termina num beco sem saída, e o timeout não lê como uma quebra
  silenciosa.
- Paridade PT/EN mantida (as quatro chaves entraram nos dois arquivos, mesma posição relativa).
- Nenhuma chave existente teve seu nome ou uso alterado — só uma correção de fraseado em
  `qr_refreshed` (inglês) e quatro adições.

### Validação

`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL` (ver
`docs/changes/PairingScreen.kt.md` para a contagem completa). Lint não sinalizou nenhuma string
nova (sem `MissingTranslation`/`ExtraTranslation` — as quatro chaves têm par nos dois arquivos).

## 2026-09-17 (2) — "Novo QR em", legenda fixa, e um aviso removido por estar errado (T4.16)

### Removida

| chave | texto anterior (PT / EN) |
|---|---|
| `qr_refreshed` | "O QR expirou e foi gerado outro. **Peça para escanear o novo.**" / "The QR expired and a new one was generated. **Ask the other person to scan it.**" |

Removida, não reescrita. Depois que exibição e validade foram separadas (ver
`docs/changes/Pairing.kt.md`, seção "2026-09-17 (3)"), até 3 ofertas ficam válidas ao mesmo tempo e
**rescanear é exatamente o que não é preciso** — quem já escaneou o QR anterior conclui o pareamento
normalmente. Manter a string seria embarcar conscientemente um conselho errado. Saiu dos dois locales
junto com o estado `refreshed` que a exibia, então não sobrou recurso órfão para o `UnusedResources`.

### Acrescentadas

| chave | PT | EN |
|---|---|---|
| `qr_regenerates_in` | "Novo QR em %1$s" | "New QR in %1$s" |
| `qr_previous_still_valid` | "Quem já escaneou o QR anterior ainda consegue concluir o pareamento." | "Anyone who already scanned the previous QR can still finish pairing." |

`qr_regenerates_in` é o **mesmo** cronômetro de `qr_expires`, com outra leitura: enquanto só o QR
próprio está na tela, chegar a zero gira o QR e não custa nada a ninguém, então o texto diz isso e o
rótulo não fica vermelho. Depois que a troca é montada, volta a ser `qr_expires` — aí é prazo de
verdade. Nenhum segundo timer foi criado.

`qr_previous_still_valid` é legenda **fixa**, exibida enquanto houver oferta na tela (`sas == null`),
em linguagem simples e sem jargão: ela explica a rotação **antes** de ela acontecer, em vez de
reagir depois.


## 2026-09-18 — T4.17 fase 3: strings da campainha (PT/EN)

### Como era antes

Não havia string alguma de campainha; a notificação persistente do processo `:tor` não existia e a
notificação existente usava o literal `"NoMessages"` em código.

### Como ficou

Três entradas novas em cada arquivo, mesma convenção `snake_case`, prefixo `doorbell_` para o aviso:

```xml
<!-- values/strings.xml -->
<string name="doorbell_pending_title">NoMessages</string>
<string name="doorbell_pending_body">Você tem mensagens aguardando. Abra o NoMessages.</string>
<string name="background_channel_name">Segundo plano</string>
```

```xml
<!-- values-en/strings.xml -->
<string name="doorbell_pending_title">NoMessages</string>
<string name="doorbell_pending_body">You have messages waiting. Open NoMessages.</string>
<string name="background_channel_name">Background</string>
```

### Vantagens e decisões

- O corpo é **exatamente** o texto especificado, sem contagem, sem remetente e sem prévia: a
  notificação diz o que fazer, não o que chegou nem de quem.
- O título é o nome do app nos dois idiomas. Um título descritivo ("Nova batida", "Alguém tocou")
  diria para quem estiver olhando a tela de bloqueio mais do que o ícone já diz, sem ajudar o dono do
  aparelho em nada.
- `background_channel_name` é deliberadamente genérico: é o nome do canal na lista de configurações
  do sistema, e "Campainha" ou "Tor" ali seria uma descrição do comportamento do app para qualquer
  um que abrisse aquela tela.
- Paridade PT/EN mantida: as duas cópias têm exatamente as mesmas chaves, na mesma ordem.

### Por que a mudança foi feita

T4.17 fase 3, item 4 (e o canal do item 2).

## 2026-09-18 — T4.17 fase 6: strings do rótulo da configuração da campainha (PT/EN)

### Como era antes

O prefixo `doorbell_` já existia nos dois arquivos, mas só com as strings da **notificação** de
batida aceita (`doorbell_pending_title`, `doorbell_pending_body`, acrescentadas na fase 3). Não havia
nenhuma string para o **rótulo da configuração**: a opção não existia na tela.

### Como ficou

Quatro entradas novas em cada arquivo, ao lado do bloco da fase 3, sem reordenar nem duplicar nada:

```xml
<!-- values/strings.xml -->
<!-- Campainha (T4.17): rótulo da configuração por cofre. Texto normativo de
     docs/development/doorbell-design.md; os dois modos são legítimos e seguros. -->
<string name="doorbell_setting_title">Aviso de mensagens pendentes</string>
<string name="doorbell_setting_on">Com o app bloqueado, um canal separado avisa que há mensagens aguardando. Nenhuma mensagem, contato ou chave fica acessível nesse estado.</string>
<string name="doorbell_setting_off">Com o app bloqueado, nada fica ligado à rede. Você recebe as mensagens ao abrir o app.</string>
<string name="doorbell_setting_context">Nos dois modos, o conteúdo só existe dentro do cofre cifrado.</string>
```

```xml
<!-- values-en/strings.xml -->
<string name="doorbell_setting_title">Pending message notice</string>
<string name="doorbell_setting_on">With the app locked, a separate channel lets you know messages are waiting. No message, contact, or key is accessible in that state.</string>
<string name="doorbell_setting_off">With the app locked, nothing stays connected to the network. You receive messages when you open the app.</string>
<string name="doorbell_setting_context">In both modes, content only exists inside the encrypted vault.</string>
```

### Vantagens e decisões

- **Sufixo `_setting_`, não `_pending_`.** As duas famílias são campainha, mas coisas diferentes:
  `doorbell_pending_*` é o que a notificação diz quando alguém tocou; `doorbell_setting_*` é o rótulo
  do interruptor que decide se a campainha toca. O sufixo evita que uma futura edição confunda as duas.
- **O PT é cópia literal** da seção "Texto da configuração" de `docs/development/doorbell-design.md`,
  incluindo pontuação. Esse texto é normativo, não indicativo: ele é o resultado de uma decisão sobre
  *o que se promete ao usuário* em cada modo, e reescrevê-lo mudaria a promessa.
- **O EN foi criado agora** (o desenho só traz PT) mantendo a mesma estrutura frase a frase — três
  textos, na mesma ordem, com a mesma divisão de orações, para que os dois idiomas caibam igual no
  mesmo layout e possam ser comparados linha a linha numa revisão futura.
- **Nenhum dos dois modos é qualificado.** Ligado e desligado aparecem como descrições factuais; não
  há "recomendado", "mais seguro", "atenção" nem qualquer termo que sugira fragilidade — diretriz
  explícita do desenho, e o motivo de a linha de contexto ("o conteúdo só existe dentro do cofre
  cifrado") ser mostrada nos dois estados: é o que não muda, e é o que importa.
- **Paridade PT/EN mantida**: as duas cópias têm exatamente as mesmas chaves, na mesma ordem, no
  mesmo lugar do arquivo.
- Nenhuma das quatro chaves aparece em `UnusedResources` no relatório de `:app:lintDebug`, o que
  confirma que todas estão de fato referenciadas por `SettingsScreen.kt`.

### Por que a mudança foi feita

T4.17 fase 6 (UI de Configurações), item 3.

## 2026-09-23 — Limpeza do lint `UnusedResources`/`PluralsCandidate` (T4.6)

### Motivo

`:app:lintDebug` (achado registrado em `docs/development/build-report.md`, seção "Lint
(2026-09-14)") apontava 28 strings em `UnusedResources` (20 `error_*` mais `brand_mark`, `chats`,
`scan_again`, `group_info`, `open_attachment`, `media_unsupported`, `offline_message_note`, `retry`)
e 8 em `PluralsCandidate` (`selected_count`, `missing_pairs_title`, `timeout_seconds`,
`timeout_minutes`, cada um em PT e EN). O plano (T4.6) pedia: para cada `error_*`, conectar ao
caminho que descreve ou remover se o caminho não existe; converter as quatro strings de contagem em
`<plurals>`; apagar os recursos mortos.

### Como era

28 `<string>` sem nenhum chamador (a lista completa está no relatório de lint citado acima) e 4
strings com `%d` seguido de palavra, sinalizadas como candidatas a `<plurals>` nas duas línguas.

### Como ficou

**13 strings `error_*` ganharam chamador** (ver `docs/changes/MessagingError.kt.md`,
`docs/changes/MessagingEngine.kt.md`, `docs/changes/MemoryAudioRecorder.kt.md` e
`docs/changes/NoMessagesController.kt.md` para o código): `error_type_message`,
`error_file_unavailable`, `error_file_invalid`, `error_no_other_members`, `error_group_sync_failed`,
`error_group_waiting_online`, `error_left_group`, `error_contact_unavailable`,
`error_contact_unpaired`, `error_group_unavailable`, `error_duplicate_file`,
`error_existing_file_mismatch`, `error_no_audio_recorded`.

**15 strings foram removidas** (PT e EN, mesma chave nos dois arquivos), em dois grupos:

| Grupo | Chaves | Por quê |
|---|---|---|
| Sem literal correspondente em código nenhum | `error_invalid_membership_change` | Nunca existiu um `require`/`error` com esse texto — string órfã desde sempre. |
| Só alcançável pelo laço de recebimento, que engole toda exceção por desenho | `error_unauthenticated_message`, `error_invalid_message`, `error_unknown_group`, `error_invalid_pairing_proof` | `receiveLoop` descarta qualquer `Exception` ao processar quadro de rede não confiável (para um quadro malformado nunca virar oráculo) — nenhum código chegaria a ler esses. Ver `docs/changes/MessagingError.kt.md`. |
| Duplicata de uma string já correta no mesmo ponto de chamada | `error_file_over_limit`, `error_file_eight_mib` | Ambas descreviam o mesmo limite de 8 MiB que `error_attachment_limit` já cobre nos mesmos call sites, com texto pior (sem o número). |
| Sem chamador e sem literal equivalente em lugar nenhum (nem localizado, nem hardcoded) | `brand_mark`, `chats`, `scan_again`, `group_info`, `open_attachment`, `media_unsupported`, `offline_message_note`, `retry` | Nenhuma tela/botão/rótulo do app as usa hoje — confirmado por `grep` no texto PT de cada uma em `app/src/main/kotlin`, sem nenhum resultado. Inventar UI nova só para consumi-las estaria fora do escopo de uma limpeza de lint. |

**4 strings viraram `<plurals>`** (categorias `one`/`other`, PT e EN — ver
`docs/changes/GroupWizardScreen.kt.md` e `docs/changes/SettingsScreen.kt.md` para os call sites):

```xml
<!-- antes -->
<string name="selected_count">%1$d selecionados</string>
<string name="missing_pairs_title">Ainda faltam %1$d pareamentos</string>
<string name="timeout_seconds">%1$d segundos</string>
<string name="timeout_minutes">%1$d minutos</string>

<!-- depois -->
<plurals name="selected_count">
    <item quantity="one">%1$d selecionado</item>
    <item quantity="other">%1$d selecionados</item>
</plurals>
<plurals name="missing_pairs_title">
    <item quantity="one">Ainda falta %1$d pareamento</item>
    <item quantity="other">Ainda faltam %1$d pareamentos</item>
</plurals>
<plurals name="timeout_seconds">
    <item quantity="one">%1$d segundo</item>
    <item quantity="other">%1$d segundos</item>
</plurals>
<plurals name="timeout_minutes">
    <item quantity="one">%1$d minuto</item>
    <item quantity="other">%1$d minutos</item>
</plurals>
```

(EN espelha a mesma estrutura, com "1 pairing is still missing" etc. na categoria `one`.)
`timeout_minute` (o singular fixo "1 minuto"/"1 minute", já uma `<string>` própria antes desta
tarefa) não foi tocado — não estava na lista de `PluralsCandidate`.

### Vantagens

- `:app:lintDebug` sai de 28 `UnusedResources` + 8 `PluralsCandidate` para 0 de cada (verificado
  abaixo), sem baseline nem supressão — os achados foram corrigidos, não escondidos.
- Cada `error_*` que sobrou agora é lido por código de verdade; nenhuma string finge estar conectada.
- Nenhum texto hoje visível mudou (todos os valores reais de contagem em uso — 5/15/30 s, 2–99
  seleções — caem na categoria "other" nas duas línguas); a categoria "one" fica correta para quando
  a faixa de valores crescer ou para leitores de tela.
- `values/strings.xml` e `values-en/strings.xml` continuam com a mesma contagem de recursos entre si
  (225 cada, confirmado por `grep -c "<string"` depois da limpeza) — nenhuma divergência PT/EN.

### Verificação

`:app:testDebugUnitTest`: `BUILD SUCCESSFUL`. `:app:lintDebug`: `BUILD SUCCESSFUL`, relatório sem
`UnusedResources` nem `PluralsCandidate` (restam apenas `UseKtx` ×3, `Typos` ×1, `PrivateApi` ×1,
`OldTargetApi` ×1, `InlinedApi` ×1 — nenhum desses no escopo de T4.6).

## 2026-09-23 — `error_media_capacity` (T4.1)

Uma string nova, em PT e EN, para a reserva fixa de mídia de T4.1 (ver
`docs/security-model.md`, "Fixed media reservation and blind cover growth"):

```xml
<!-- values/strings.xml -->
<string name="error_media_capacity">Não foi possível anexar. O espaço reservado para mídia deste cofre está cheio.</string>
<!-- values-en/strings.xml -->
<string name="error_media_capacity">The file could not be attached. This vault\'s reserved media space is full.</string>
```

Mapeada por `NoMessagesController.errorMessage` a partir de `MessagingErrorCode.MEDIA_CAPACITY_EXHAUSTED`
— ver `docs/changes/MessagingError.kt.md` e `docs/changes/NoMessagesController.kt.md`. O apóstrofo em
`vault's` precisou de escape (`\'`) para o compilador de recursos aceitar o arquivo — nenhuma outra
string em `values-en/strings.xml` usava apóstrofo antes desta.

### Verificação

`:app:testDebugUnitTest`/`:app:lintDebug`/`:app:assembleDebug`: `BUILD SUCCESSFUL`. Referenciada e
exercitada pela suíte instrumentada indiretamente (o caminho de código que a mostraria é testado por
`AndroidVaultStorageTest.sendAttachmentFailsCleanlyWhenTheMediaReservationIsFull`, que verifica o
`MessagingErrorCode`, não o texto — o texto em si é lido por `Context.getString`, fora do alcance de
um teste JVM/instrumentado que não monta a UI).

## 2026-09-23 (2) — Inglês vira o locale default; `values-en/` some, `values-pt/` nasce

### Como era antes

`app/src/main/res/values/strings.xml` era o **português** (locale default do app — sem qualificador,
lido sempre que o dispositivo não tem `values-en/` aplicável) e `app/src/main/res/values-en/strings.xml`
era o inglês. Ou seja: um aparelho em qualquer idioma que não fosse inglês (inclusive espanhol,
francês, alemão etc., não só português) caía no PT por ser o default. Não havia `res/xml/locales_config.xml`,
nenhum `android:localeConfig` no manifesto, e nenhum `androidResources { localeFilters }` no
`app/build.gradle.kts` — o APK empacotava qualquer locale que a árvore de resources contivesse.

### Como ficou

Os dois arquivos **trocaram de papel**, movidos com `git mv` (preserva histórico, sem editar
conteúdo linha a linha):

```
app/src/main/res/values/strings.xml     ← conteúdo que estava em values-en/ (Inglês, agora default)
app/src/main/res/values-pt/strings.xml  ← conteúdo que estava em values/    (Português)
app/src/main/res/values-en/             ← removido (diretório vazio apagado)
```

`values/colors.xml` e `values/themes.xml` não são traduzíveis e não tinham cópia em `values-en/`;
permaneceram em `values/` sem alteração — conferido antes da mudança (`ls` nos dois diretórios) que
`strings.xml` era o único recurso duplicado entre eles. `values-pt` (sem qualificador de região) cobre
tanto pt-BR quanto pt-PT, que é o que o pedido do usuário exige ("celular em português → português").

As 222 chaves (`<string>`) e as 4 famílias `<plurals>` (`selected_count`, `missing_pairs_title`,
`timeout_seconds`, `timeout_minutes`) mantiveram paridade exata entre `values/` e `values-pt/` — a
mudança foi só de diretório, nenhum texto foi reescrito.

`app/src/main/res/xml/locales_config.xml` (novo arquivo), referenciado no manifesto via
`android:localeConfig="@xml/locales_config"`:

```xml
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="pt" />
</locale-config>
```

`app/build.gradle.kts`, bloco novo dentro de `android { }` (AGP 9.1.1 usa `androidResources.localeFilters`,
não o `resourceConfigurations` de versões antigas do AGP):

```kotlin
androidResources {
    localeFilters += listOf("en", "pt")
}
```

Teste instrumentado novo, `app/src/androidTest/kotlin/dev/mx3/nomessages/LocaleResourcesTest.kt`, que
usa `Configuration.setLocale` + `Context.createConfigurationContext` (não `Locale.setDefault`, que só
afeta o processo, não a resolução de resources) para confirmar, sem heurística: `pt-BR` e `pt-PT`
resolvem `unlock` como "Desbloquear"; `en` resolve "Unlock"; e dois locales **não suportados**
(`es-ES` e `ja`, nenhum dos dois listado em `locales_config.xml`) também resolvem "Unlock" — a prova
de que o fallback de terceiro idioma cai no inglês, não mais no português.

### Levantamento de literais em português fora de `resources` (pedido explícito da tarefa)

`grep` por caracteres acentuados em `app/src/main/kotlin` e `core/src/main/kotlin` encontrou ocorrências
só em dois grupos, nenhum dos dois é texto de UI visível ao usuário comum:

1. **Comentários KDoc/inline** (`DoorbellKnockPolicy.kt`, `MessagingEngine.kt`, `TorConnection.kt`,
   `PairingScreen.kt`, `QrScannerHooks.kt`, `VaultHeader.kt`, `VaultManager.kt`, etc.) e mensagens de
   `require`/`error` internas (ex.: `"Transporte indisponível"`, `"Mensagem não autenticada"`) que nunca
   chegam à tela — todo texto que a UI realmente mostra já passa por `stringResource`/`context.getString`
   (confirmado por uma busca por `Text(`, `contentDescription`, `Toast.makeText`, `setContentText`,
   `setContentTitle` com literal direto: nenhum resultado fora de resources).
2. **Conteúdo de exemplo do cofre-isca** (`AndroidVaultStorage.kt`, `DecoyFactory`: nomes como "Ana",
   "Marina" e mensagens de conversa fictícia tipo "Você chega às sete?"). Deliberadamente fora do
   escopo desta tarefa: é conteúdo de amostra gerado para o perfil de pânico, não texto de interface do
   app, e mexer nele put em risco a paridade real/isca que T4.1/T4.6/T4.7 acabaram de fechar (commit
   `6da50da`) — trocar o idioma dessas mensagens é uma decisão de segurança/design própria, não um
   efeito colateral do locale default.

`PrivacyNotifications.kt:19` usa o literal `"NoMessages"` como nome de canal de notificação — mantido
como está: é o nome próprio do app, não texto traduzível (mesmo exemplo citado no pedido da tarefa).

### Vantagens

- Fallback de terceiro idioma agora é o que o usuário pediu: português no dispositivo → português;
  inglês → inglês; qualquer outro idioma → inglês (antes caía em português, por ser o default antigo).
- `locales_config.xml` + `localeFilters` fazem o Android 13+ oferecer só en/pt no seletor de idioma por
  app do sistema, e impedem que o APK empacote acidentalmente um resource set de locale que ninguém
  testou.
- `git mv` preservou o histórico de cada string (em vez de um "arquivo novo" / "arquivo apagado" sem
  relação no `git log`).

### Por que a mudança foi feita

Pedido do usuário: o idioma padrão do app passa a ser inglês; português continua disponível quando o
aparelho está em português (pt-BR/pt-PT); qualquer outro idioma cai em inglês.

### Verificação

`wsl … gradle-wsl.sh :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest --console=plain` foi iniciado e chegou a `:app:dexBuilderDebug` sem
nenhuma falha (`:core:test`, `:app:testDebugUnitTest` e `:app:lintDebug` já haviam passado nesse ponto
do log) antes deste registro ter sido escrito; a suíte instrumentada (`t31-devices.sh emulator-5556`)
ainda não havia rodado. **Este parágrafo deve ser atualizado com o resultado final assim que o build em
andamento terminar** — ver a ressalva no relatório desta rodada.

## 2026-09-23 — Correção da revisão: `values-pt/strings.xml` sem a quantidade `many` (lint `MissingQuantity`)

### Como era antes

Os 4 `<plurals>` de `values-pt/strings.xml` (`selected_count`, `missing_pairs_title`,
`timeout_seconds`, `timeout_minutes`) só tinham `quantity="one"` e `quantity="other"`:

```xml
<plurals name="timeout_seconds">
    <item quantity="one">%1$d segundo</item>
    <item quantity="other">%1$d segundos</item>
</plurals>
```

O lint do Android Gradle Plugin (`MissingQuantity`) sinaliza `pt` como um locale cujas regras CLDR
esperam também a categoria `many`, então essas 4 declarações de plural disparavam o aviso.

### Como é agora

Cada um dos 4 `<plurals>` ganhou um item `quantity="many"` com o mesmo texto de `quantity="other"`
(português não distingue "many" de "other" na fala normal — não existe uma forma plural própria
para essa categoria neste idioma, mas o lint exige a entrada mesmo assim):

```xml
<plurals name="timeout_seconds">
    <item quantity="one">%1$d segundo</item>
    <item quantity="many">%1$d segundos</item>
    <item quantity="other">%1$d segundos</item>
</plurals>
```

Aplicado também a `selected_count`, `missing_pairs_title` e `timeout_minutes`.

### Vantagens

- `:app:lintDebug` para de sinalizar `MissingQuantity` para `values-pt/strings.xml`, sem alterar
  nenhum texto visível ao usuário (o texto de `many` é idêntico ao de `other`, então o app se
  comporta exatamente como antes em qualquer contagem).

### Por que a mudança foi feita

Achado 6 da revisão desta tarefa.
