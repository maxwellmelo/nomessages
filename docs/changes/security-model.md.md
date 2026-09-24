# docs/security-model.md

## 2026-09-23 — Nova seção "Fixed media reservation and blind cover growth" (T4.1)

### Motivo

O plano (`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`, T4.1) apontava que
`AndroidVaultStorage.alignAllocations` só iguala `real.files`/`decoy.files` em tamanho no `create`/
`resetPanicPassword`; anexos reais escritos por `MessagingEngine.storeAttachment` entre um reset e o
próximo crescem só o lado que está sendo usado, e `beforeExport` só comparava os `.db`, nunca os
diretórios de mídia — um cofre em uso real (não logo após o setup) divergia em tamanho entre `real.
files`/`decoy.files`, um distinguidor forense que nenhum gate cobria diretamente.

### Como era

Nada no `security-model.md` documentava política de mídia nenhuma — a seção "Memory, Tor and storage
limits" cobre o banco (`databaseBytes` fixo) e caches de prévia, mas nunca os diretórios `.files/`.

### Como ficou

Seção nova, "Fixed media reservation and blind cover growth (2026-09-23, T4.1)", entre "Decoy
oracles fixed..." (T4.7) e "Input/keyboard hardening..." (T4.9), documentando as quatro partes da
correção: (1) uma reserva fixa de mídia por slot (`AndroidVaultStorage.mediaCapacityBytes`,
espelhando `databaseBytes`); (2) cobertura cega em toda escrita, não só no setup/reset
(`MessagingEngine.storeAttachment` grava um blob do mesmo tamanho, de bytes aleatórios com forma de
ciphertext, no slot irmão, sem `FileRecord` correspondente — inerte, nunca aberto como anexo); (3)
falha limpa ao esgotar a reserva (`MessagingError(MEDIA_CAPACITY_EXHAUSTED)`, mapeado para
`error_media_capacity`); (4) `beforeExport` passa a checar paridade de mídia também, fechando a
lacuna que só comparava os `.db`. Inclui o raciocínio de por que um blob cego (e não, por exemplo,
preencher o banco) é a ferramenta certa — só a mídia cresce com uso real, o banco já é uma alocação
fixa — e a referência aos dois testes instrumentados novos que sustentam a alegação.

### Vantagens

- Fecha a lacuna que o plano apontou na origem (o diretório de mídia, não só o `.db`), sem exigir
  mudar o formato do cofre existente nem a interface `VaultStorage` do `core`.
- `beforeExport` agora protege exatamente o cenário que faltava: um export tirado entre dois
  `alignAllocations`, isto é, qualquer export de um cofre que já foi usado.
- A reserva fixa dá uma falha limpa e localizada em vez de deixar o app tentar escrever
  indefinidamente ou falhar de um jeito genérico quando o espaço acaba.

Ver também `docs/changes/AndroidVaultStorage.kt.md`, `docs/changes/MessagingEngine.kt.md`,
`docs/changes/MessagingError.kt.md`, `docs/changes/NoMessagesController.kt.md` e
`docs/changes/AndroidVaultStorageTest.kt.md`.

## 2026-09-23 (revisão P1) — Correção do parágrafo T4.7(a): fechar o oráculo de I/O em disco que a versão anterior não fechava

### Motivo

Uma revisão de código sobre a seção "Decoy oracles fixed..." abaixo (achados P1, arquivo
`docs/security-model.md` linha ~405, e `VaultManager.kt` linha ~140) apontou que a frase "neither
branch can be distinguished from the other by outcome, message or (bounded by both doing two
same-cost KDF calls) wall-clock time" era falsa no caso geral: `resetPanicPassword` não gasta seu
tempo só em duas derivações Argon2id, gasta a maior parte dele copiando a árvore de texto cifrado
inteira (`copyCiphertextTree`), reescrevendo `decoy.db` do zero (`storage.initialize`) e depois
descartando a árvore antiga (`discardCiphertext(backup)`) — custo que escala com o tamanho real do
cofre (mídia incluída) e que `fakePanicPasswordChange`, como escrito originalmente, não pagava (zero
cópias, zero escrita de banco, zero exclusão). Ou seja: o próprio documento afirmava uma paridade de
tempo de parede que o código ao lado dele não entregava.

### Como era

> Both branches finish by setting `committed = true` and showing the identical
> `notice_panic_password_changed` toast; neither branch can be distinguished from the other by
> outcome, message or (bounded by both doing two same-cost KDF calls) wall-clock time.

### Como ficou

A frase de paridade de tempo de parede foi removida do parágrafo original (que agora só afirma o que
de fato prova: mesma validação, mesmo custo de KDF, nada comparado, nada escrito sob o diretório do
cofre). Um novo parágrafo "Follow-up (2026-09-23): the KDF-only match still left a disk-I/O oracle,
now closed" foi acrescentado logo depois, explicando: (1) exatamente que I/O o caminho real paga e
por que ela escala com o tamanho do cofre, não é uma constante; (2) que `fakePanicPasswordChange`
agora paga o mesmo I/O contra um diretório de estágio descartável (cópia da árvore inteira, reescrita
de `decoy.db`, descarte), lido do próprio sistema de arquivos que a sessão isca já acessa
legitimamente, e nunca escrevendo sob o diretório real do cofre; (3) a alegação final, agora honesta:
os dois ramos ficam limitados pelo mesmo custo de KDF **e** pelo mesmo custo de
cópia/reescrita/descarte de árvore, então tempo de parede e bytes tocados acompanham um ao outro
independente do tamanho do cofre; (4) o teste novo que sustenta essa alegação
(`VaultTest.fakePanicPasswordChangePaysComparableDiskIoToTheRealResetOnASeededVault`) e sua limitação
residual — a medição é feita contra `TestStorage` (JVM, banco pequeno), não contra
`AndroidVaultStorage` com `capacityMiB` real, então uma medição de tempo de parede num cofre Android
de verdade com mídia significativa continua pendente (registrada, não fingida como feita).

### Por quê

Documentar uma garantia que o código não cumpre é pior do que não documentar nada: um leitor deste
arquivo (inclusive uma futura revisão de segurança) confiaria numa paridade de tempo que não existia.
A correção certa aqui não era só ajustar o texto — o achado P1 pedia consertar o código quando
tratável (era: `fakePanicPasswordChange` ganhou o mesmo custo de I/O, ver
`docs/changes/VaultManager.kt.md`) e só então corrigir o texto para descrever com precisão o que
ficou igual, o que não foi medido, e por quê.

### Vantagens

- O documento volta a descrever exatamente o que o código faz, sem alegação de paridade que o código
  não entregava.
- A limitação residual (sem medição em `AndroidVaultStorage` real) fica registrada como tal, não
  escondida — é uma decisão explícita de escopo, não um esquecimento.
- Mantém o padrão do resto do arquivo: cada garantia de segurança vem com o teste que a sustenta e a
  fronteira exata do que esse teste prova.

Ver também `docs/changes/VaultManager.kt.md` (revisão P1) e `docs/changes/VaultTest.kt.md`
(revisão P1) para as mudanças de código e teste correspondentes.

## 2026-09-23 — Nova seção "Decoy oracles fixed, and the cipher key-derivation timing gap closed" (T4.7)

**Antes:** a revisão de 2026-09-15 tinha apontado dois problemas — o botão "trocar senha de pânico"
escondido na isca, e `cipher_memory_security` aplicado depois da chave em vez de antes — mas nenhum
dos dois estava documentado em `security-model.md`; só existiam como itens abertos no plano
(`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`, T4.7).

**Agora:** uma seção nova, entre "Timing parity of the migration" e "Input/keyboard hardening",
documenta os dois em detalhe: (a) por que `canChangePanicPassword` fixo em `true` fecha o oráculo
sem abrir um novo (o que `VaultManager.fakePanicPasswordChange` faz e por que pagar o mesmo custo de
KDF, e não simplesmente pular o trabalho, é a parte que importa), a lista dos outros pontos
grepados (`isDecoy`/`decoy`/`slot`/`panic` em `ui/` e `runtime/`) e por que nenhum deles é um oráculo
de UI; (b) como `SQLiteDatabaseHook.preKey` fecha a lacuna de tempo do `cipher_memory_security`, com
a evidência de que a API se comporta assim (decodificação direta do AAR do `sqlcipher-android`
4.19, já que não há `-sources.jar` publicado), e por que a garantia de ordenação em si não é
observável de um teste JVM/instrumentado — só o efeito (o pragma responde `1` logo após abrir).

**Por quê:** os dois eram itens `[B]` bloqueadores pendentes no roteiro de release (T4.7), achados
numa revisão adversarial anterior mas nunca corrigidos nem registrados no documento que existe
justamente para acumular esse tipo de decisão de modelo de ameaça. Ver
`docs/changes/VaultManager.kt.md`, `docs/changes/VaultHeader.kt.md`,
`docs/changes/NoMessagesController.kt.md`, `docs/changes/UiContract.kt.md`,
`docs/changes/AndroidVaultStorage.kt.md` e `docs/changes/AndroidVaultStorageTest.kt.md` para as
mudanças de código correspondentes.

## 2026-09-18 — T4.17 (campainha): a análise de ameaça do runtime, que a versão anterior explicitamente não reivindicava

**Antes:** a seção chamava-se "Reserved doorbell fields (T4.17) — no behaviour implemented" e terminava com uma renúncia explícita: *"The threat analysis of the doorbell's runtime — what stays alive while the app is locked — belongs to T4.17 and is **not** claimed here."* Só a exposição residual dos campos dentro do cofre cifrado estava analisada.

**Agora:** a seção é "Doorbell fields and runtime (T4.17)" e a renúncia foi substituída pela análise que ela prometia, em quatro partes: (1) **o que fica exposto em modo mínimo** — host Arti compartilhado, chave de identidade do onion da campainha e conjunto de tokens emitidos — com a lista explícita do que **não** fica (MK/DBK/FBK, banco aberto, ratchet Signal, estado MLS, identidade e seed de mensagens, plaintext, chaves de anexo), mais o fato de que o Kotlin nunca vê um token e de que um panic nativo derruba os **dois** serviços; (2) **modelo de ameaça do protocolo da batida** — forja/força bruta, replay, sinalização/flood, enumeração de contatos por um observador externo e ligação entre os dois onions, cada um com o mecanismo que o fecha e o número que o parametriza; (3) **limitações conhecidas** — o processo sobrevivente como sinal observável (e o que ele **não** diz: nem qual cofre, nem quais contatos, nem que haja mensagem), o estado Arti em claro por mais tempo, o caráter "melhor esforço" da promoção a foreground, e a exceção incondicional da senha de pânico; (4) **o que não foi medido em aparelho**, apontando para o gate 10c. As três colunas por contato e a limitação dos contatos antigos também entraram.

**Por quê:** era uma dívida nominal deixada por T4.16 — o documento apontava para esta tarefa pelo nome. Um modelo de ameaça que descreve os campos mas não o que fica ligado à rede com o aparelho apreendido e bloqueado não responde à pergunta que um leitor desse documento tem. O ponto central que a análise fixa é que a assimetria observável do protocolo é **um único byte**, entregue só a quem já tinha um token válido: toda invalidez fecha a conexão de forma idêntica.

## 2026-09-17 — T4.10: parágrafo novo sobre normalização NFKC e aceitação por força

**Antes:** a seção "Intended boundaries" descrevia o Argon2id e a portabilidade do cofre exportado sem dizer qual forma de normalização Unicode é aplicada à senha, e sem citar o critério de aceitação.

**Agora:** um parágrafo datado logo abaixo registra que os bytes entregues ao Argon2id são a forma **NFKC**, por que NFC não bastava (formas de compatibilidade dependentes de teclado/IME quebrariam a promessa de abrir em outro aparelho), que a mudança é neutra em ASCII puro, qual é a nova regra de aceitação (12–128 caracteres, ASCII imprimível ou letra Unicode, sem controle, sem espaço nas bordas, `estimateStrength` score ≥ 3), que o zxcvbn saiu, e que o par real/pânico agora recusa variação trivial.

**Por quê:** o documento é a referência de limites reais do produto. Uma mudança na forma de normalização altera a chave derivada e a portabilidade do export — é exatamente o tipo de fato que precisa estar escrito aqui, junto com a ressalva de que a estimativa de bits é engenharia, não prova de custo de adivinhação. Detalhes em [`PasswordPolicy.kt.md`](PasswordPolicy.kt.md) e [`PasswordStrength.kt.md`](PasswordStrength.kt.md).

## 2026-09-16 — Parágrafo novo sobre os caches de prévia de mídia inline (T4.8, agente final)

### Motivo

`MediaPreviewCache` e `AudioPlaybackCoordinator` (T4.8, ver `docs/changes/MediaPreviewCache.kt.md`
e `docs/changes/AudioPlaybackCoordinator.kt.md`) introduziram os dois primeiros caches em memória
do app fora do já documentado `_state.value.attachment?.bytes?.fill(0)` de
`NoMessagesController.lock()`. Nenhum dos três agentes de mídia inline tocou
`docs/security-model.md` por instrução explícita de escopo, então o modelo de segurança não
mencionava a existência desses caches, seus limites, o que exatamente eles guardam (e não guardam),
nem o comportamento de fallback do encoder AAC-em-memória — lacuna que o agente final deveria
fechar.

### Como era antes

A seção "Memory, Tor and storage limits" descrevia o armazenamento de sessão do Signal, o keystore
efêmero do Arti e a política de épocas do OpenMLS, mas nada sobre prévias de mídia em memória — a
funcionalidade ainda não existia quando essa seção foi escrita.

### Como ficou

Novo parágrafo curto "Inline media preview caches (2026-09-16)", inserido antes de "Maintenance,
audit and distribution", no mesmo estilo terso e denso em citação cruzada do resto do arquivo:
descreve o limite de `MediaPreviewCache` (~24 entradas / ~8 MiB), que ele guarda só resumos
derivados (baldes de forma de onda em `Float`, bitmaps de miniatura/poster reduzidos) e nunca bytes
brutos decifrados, que bitmaps cacheados são zerados e reciclados explicitamente ao serem
substituídos/despejados (não esperam o coletor de lixo), que `AudioPlaybackCoordinator` guarda só um
id de anexo e uma closure de pausa (nunca um `MediaPlayer` nem bytes), e que os dois são limpos de
forma síncrona em `NoMessagesController.lock()`, ao lado do `fill(0)` já documentado. Também registra o
uso de `memfd` pelo encoder AAC-em-memória e seu fallback documentado para WAV sem compressão,
deixando explícito que nenhum dos dois caminhos arrisca escrever texto plano parcial em disco.

### Vantagens

- O modelo de segurança volta a cobrir toda superfície de memória em cache do app, não só o que
  existia antes de T4.8 — mantém a promessa do arquivo de listar limites reais em vez de omitir
  funcionalidade nova.
- A distinção "dado derivado pequeno, cacheável" vs. "bytes brutos decifrados, nunca cacheados" fica
  registrada no mesmo lugar que já documenta o limite equivalente para o cofre-isca e para o
  `AttachmentViewer`, em vez de só nos comentários de código.
- Registra o comportamento de fallback do encoder de áudio como uma decisão de segurança
  deliberada (nunca risco de plaintext parcial em disco), não apenas como uma nota de
  confiabilidade em `docs/changes/MemoryAudioEncoder.kt.md`.

### Por que a mudança foi feita

Passo 4 da tarefa final de T4.8 pedia explicitamente este parágrafo em `docs/security-model.md`,
cobrindo os caches novos, seus limites, o que guardam, e o comportamento de fallback do encoder
AAC-em-memória.


---

## 2026-09-16 - Revisao adversarial de seguranca do commit 47afa3e

### Motivo

Duas afirmacoes do paragrafo "Inline media preview caches (2026-09-16)" nao correspondiam ao codigo
depois da revisao adversarial:

1. "cached bitmaps carry an explicit zero-and-recycle step on eviction or replacement so none is left
   for the garbage collector" - o `recycle()` era um defeito de runtime (crash ao desenhar um bitmap
   reciclado que ainda estava vinculado a uma bolha visivel) e o "zero" nunca executava, porque
   `BitmapFactory` devolve bitmaps imutaveis e o helper fazia `if (isMutable) eraseColor(...)`.
2. "AudioPlaybackCoordinator holds only the currently-playing attachment id and a pause callback" -
   descrevia um `reset()` que descartava o callback sem invoca-lo, ou seja, o lock **nao**
   interrompia a reproducao no auto-lock em segundo plano.

### Como era

> ... never raw decrypted attachment bytes; cached bitmaps carry an explicit zero-and-recycle step on
> eviction or replacement so none is left for the garbage collector to reclaim on its own schedule.
> `AudioPlaybackCoordinator` holds only the currently-playing attachment id and a pause callback
> (never a `MediaPlayer` or decrypted bytes) to enforce one playing bubble at a time app-wide.

### Como ficou

O paragrafo agora declara explicitamente a correcao: entradas despejadas/substituidas sao apenas
desvinculadas (sem `recycle()`), o apagamento de pixels acontece so no `clear()` do lock e passou a
ser real (miniaturas decodificadas com `inMutable = true`), bitmaps despejados por LRU dependem do GC
e nada disso toca o disco; e o coordenador agora guarda tambem um callback de teardown por player
vivo, executado de forma sincrona no lock. Ambas as correcoes apontam para os arquivos em
`docs/changes/`.

### Vantagem

O modelo de seguranca volta a descrever o codigo que existe, com a limitacao (dependencia do GC para
bitmaps despejados por LRU) declarada em vez de mascarada por uma promessa de zeragem que nao
acontecia.

---

## 2026-09-16 — Nova seção "Input/keyboard hardening and capture detection" (T4.9)

### Motivo

T4.9 endurece cinco superfícies além de `FLAG_SECURE` (IME privado, autofill, acessibilidade
sensível, aviso de teclado de terceiros, detecção de captura) e explicitamente deixa um gap
conhecido em aberto (teclado comprometido/malicioso — só um teclado próprio no app resolveria
isso, T7.1). Isso precisa estar documentado no modelo de ameaças, com o trade-off de
acessibilidade explicado (por que não foi usado `importantForAccessibility = NO`).

### Como era antes

`security-model.md` não mencionava teclado/IME, autofill ou acessibilidade — só `FLAG_SECURE` e a
chave de captura debug-only.

### Como ficou

Nova seção "Input/keyboard hardening and capture detection (2026-09-16, T4.9)", entre "Arti/Tor e
armazenamento" e "Maintenance, audit and distribution", cobrindo os cinco itens implementados e
fechando com uma referência explícita a T7.1 (`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`)
para o gap que nenhum deles fecha: um teclado do sistema comprometido/malicioso ainda pode ler tudo
que é digitado, porque o Android não oferece nenhuma forma de sandbox de IME por app.

### Vantagens

- O modelo de ameaças volta a descrever com precisão todas as camadas de proteção que existem hoje
  em volta da entrada de texto, não só a captura de tela.
- O trade-off de `ACCESSIBILITY_DATA_SENSITIVE_YES` fica registrado com o raciocínio completo
  (protege contra serviço de acessibilidade não-padrão, preserva o leitor de tela padrão do
  usuário) em vez de deixado implícito no comentário do código.
- A referência a T7.1 evita que o leitor confunda "aviso de teclado de terceiros" com "proteção
  contra teclado malicioso" — são coisas diferentes, e o texto agora diz isso explicitamente.

### Por que a mudança foi feita

T4.9, requisito de documentação explícito da tarefa.
