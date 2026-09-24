# docs/development/device-verification.md

## 2026-09-15 — Criação do arquivo: primeira execução do fluxo de dois aparelhos (T3.3), em dois emuladores

### Como era antes

O arquivo `docs/development/device-verification.md` não existia. O plano
(`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`, tarefa T3.2) já previa este arquivo
como destino de registro por cenário, mas nenhuma execução de dispositivo havia sido feita ainda
para a Fase 3 (pareamento, mensagens, anexos, fila offline — tarefa T3.3).

### Como ficou

Arquivo novo com uma tabela passo → PASSED/FAILED/BLOCKED → evidência → observações, cobrindo a
tentativa de executar os passos 1-5 do procedimento "Two-device messaging and membership
procedure" (`docs/release-checklist.md`) usando dois emuladores Android 15 x86_64 oficiais
(`nomessages35`/`emulator-5556` e o novo `nomessages35b`/`emulator-5560`) como par A/B.

Dois achados centrais ficaram registrados:

1. **Limitação de ambiente (não é bug do app):** a janela do próprio emulador, capturada via GDI
   (`System.Drawing.Graphics.CopyFromScreen`), mostra o mesmo quadro preto/congelado que
   `adb screencap` sempre que o NoMessages está em primeiro plano, porque `FLAG_SECURE`
   (`MainActivity.kt:33`) bloqueia o framebuffer entregue ao host, não só a API de screenshot do
   Android. Um teste de controle (capturar a tela inicial normal, fora do NoMessages) prova que o
   pipeline de captura em si funciona e reflete conteúdo ao vivo. Isso invalida, para este
   ambiente específico, a premissa do procedimento de que dava para relatar o QR de pareamento
   entre os dois emuladores via captura de tela da janela — sem alterar código do app não há
   nenhum canal alternativo (texto, log, clipboard) que exponha o payload do QR.
2. **Bug do app (bloqueador):** a criação do cofre pela tela real de Setup falha silenciosamente
   em **ambos** os emuladores, com senhas fracas e com senhas aleatórias de alta entropia (24
   caracteres, geradas com `secrets.choice`), sempre com a mensagem genérica "Could not complete
   — Use strong, distinct passwords with at least 16 characters" após ~11-17 s. A exceção real é
   engolida sem log em `NoMessagesController.kt:159-161`. Isso bloqueia toda execução downstream do
   procedimento (pareamento, mensagens, anexo, fila offline, e a leitura de prontidão do Tor, que
   só ativa após um `setup`/`unlock` bem-sucedido).

Como consequência, quase todos os passos do procedimento de dois aparelhos ficaram BLOCKED nesta
execução — não por falta de esforço de automação (o ambiente foi montado por completo: segundo
AVD clonado e configurado, script de câmera virtual adaptado para dois emuladores em paralelo,
pipeline de captura/posicionamento de janelas construído e validado por teste de controle), mas
por um defeito real do app encontrado no primeiro uso da tela de Setup e por uma limitação
genuína do ambiente de emulador frente a `FLAG_SECURE`.

### Vantagens

- Registra, com evidência reprodutível (PNGs, dumps de acessibilidade, logs cronológicos em
  `docs/development/build-logs/two-emulator-20260915/`), um bug de bloqueio de release que os
  testes automatizados existentes (T3.1, `AndroidVaultStorageTest`) não detectam, porque eles
  chamam a camada de storage diretamente e pulam `VaultManager.create()`/`PasswordPolicy`/
  `KdfCalibrator` — exatamente o caminho que a UI real usa.
- Documenta com precisão os limites do método "dois emuladores como par de aparelhos" para
  pareamento por QR, evitando que a mesma investigação (screencap preto, GDI também preto,
  root não ajuda) seja refeita do zero numa sessão futura.
- Segue o formato de tabela pedido pelo plano (T3.2/T3.3), pronto para ser estendido pelas
  próximas execuções de dispositivo (T3.4 em diante) sem precisar recriar a estrutura do arquivo.

### Por que a mudança foi feita

Tarefa T3.3 do roteiro de release: executar o fluxo de dois aparelhos e registrar evidências
PASSED/FAILED por etapa. Nenhum código do app foi alterado; esta é só a documentação da execução
e dos achados.

## 2026-09-15 — Segunda sessão (run2): dois dos três achados do run1 resolvidos, um novo bloqueador isolado

### Como era antes

Ao final da primeira sessão (seção acima), o arquivo registrava dois bloqueadores: (a) `FLAG_SECURE`
impedindo qualquer captura de tela do host (GDI ou `adb screencap`) enquanto o NoMessages estava em
primeiro plano, invalidando o relato do QR de pareamento entre os dois emuladores; (b) criação de
cofre falhando silenciosamente na UI real de Setup em ambos os emuladores. O procedimento de dois
aparelhos não avançou além do passo 1 em nenhum emulador.

### Como ficou

Uma nova seção `## T3.3 — run2` foi adicionada a `docs/development/device-verification.md`
registrando a segunda sessão, executada sobre o build HEAD (`cf1b1e2`), que já herdava os dois
bloqueadores anteriores resolvidos (switch debug-only `debug.nomessages.allow_capture` para a captura,
e o fix de política de senha para a criação de cofre — ambos de sessões intermediárias já
documentadas em outros arquivos de `docs/changes/`). Nesta sessão:

- **Confirmado resolvido:** com `debug.nomessages.allow_capture=1` e `hidden_api_policy=1` (mais
  reinício do app), `adb screencap` do QR de oferta de A capturou um quadro ao vivo e correto — não
  mais preto/congelado. O recorte (719×719, com margem branca) foi decodificado com sucesso
  offline via `zxing-cpp`/Pillow, confirmando o payload real `nomessages:1:...` (2708 caracteres).
- **Novo achado, bloqueador distinto:** a etapa seguinte do método com dois emuladores — injetar o
  PNG do QR capturado na câmera virtual do outro emulador (`adb emu virtualscene-image wall`) e
  deixar o próprio scanner do app decodificá-lo — nunca funcionou, em 7 tentativas independentes ao
  longo de ~10 min, incluindo um QR mínimo de diagnóstico (11 caracteres, bem enquadrado, quase sem
  distorção) e variantes espelhada/rotacionadas dele. O `Preview` da CameraX mostra a cena
  corretamente (confirmado visualmente); o logcat confirma bind bem-sucedido de `Preview` e
  `ImageAnalysis` (640×480) sem erros — isolando a falha para algo entre esse stream de análise e
  `ImageProxy.decodeQr()` em `PairingScreen.kt`, sem causa raiz confirmada (exigiria instrumentação
  de código, fora do escopo desta tarefa).
- Como consequência, os passos 2-5 do procedimento (pareamento bilateral, mensagens, anexo, fila
  offline) continuam BLOCKED nesta sessão — pelo motivo novo e mais restrito acima, não mais pelos
  dois motivos do run1. Gates 8 e 10 continuam NOT_RUN, como previsto (aparelhos físicos).
- Os dois emuladores foram deixados rodando, cofre desbloqueado, "Tor connected", zero contatos
  pareados, prontos para uma sessão futura retomar sem refazer o setup.

Evidência completa (PNGs brutos/recortados, dumps de UI, trechos de logcat, script Python de
recorte/decodificação usado para validação): `docs/development/build-logs/two-emulator-20260915/run2/`.

### Vantagens

- Prova, com evidência reprodutível, que o switch de captura debug-only (`debug.nomessages.allow_capture`)
  funciona exatamente como projetado — reduz o risco de regressão nesse mecanismo permanecer
  invalidado sem que ninguém perceba.
- Isola um bloqueador novo e mais estreito (relé de câmera virtual, não mais captura de tela nem
  criação de cofre), com testes de controle que já descartam várias hipóteses óbvias (densidade do
  QR, enquadramento, permissão de câmera, câmera travada, espelhamento/rotação simples) — poupando
  uma sessão futura de repetir esses mesmos testes de descarte.
- Registra uma recomendação concreta e de baixo risco (log de diagnóstico temporário e
  gated em `ImageProxy.decodeQr()`, ou teste em aparelhos físicos reais) para quem for investigar a
  causa raiz depois.

### Por que a mudança foi feita

Continuação da tarefa T3.3: registrar com evidência o estado real do procedimento de dois
aparelhos a cada tentativa, mesmo quando ela não consegue ser concluída, para que cada sessão
futura comece de onde esta parou em vez de redescobrir os mesmos bloqueadores. Nenhum código do
app foi alterado nesta sessão.

## 2026-09-18 — Nova seção "Aparelho físico — 2026-09-18": primeira leitura de QR pela câmera real

### Como era antes

O arquivo terminava em "T4.16 — run7" (2026-09-17), registrando a mecânica de pareamento validada
de ponta a ponta em dois emuladores, mas com um único item explicitamente pendente em todas as
sessões anteriores de T4.16 (topo do arquivo, run5, run6, run7): "a câmera real do Galaxy Note10+
nunca leu um QR de pareamento — nem do formato 1, nem do formato 2". Nenhuma seção do arquivo tinha
qualquer execução em aparelho físico; todas as validações de T4.16 até aqui foram feitas em dois
emuladores Android x86_64.

### Como ficou

Nova seção `## Aparelho físico — 2026-09-18` ao final do arquivo, registrando a primeira execução em
hardware real: Galaxy Note10+ SM-N975F, Android 12, arm64, serial `RX8MA0GD9ZY`, APK debug do commit
`706630a`, pareado com os dois emuladores já usados nas sessões anteriores (A = `emulator-5556`, B =
`emulator-5560`). Uma tabela Passo | Resultado | Evidência | Tempos cobre seis pontos:

1. A leitura do QR formato 1 pela câmera real, que **falhou** (achado histórico que originou T4.16) —
   transcrito, não reexecutado.
2. A leitura do QR formato 2 pela câmera real, que **passou na primeira tentativa** — evidência de
   logcat (`NoMessagesQrScan: decode attempt result=DECODED`, 10:12:34) e a captura `a-qr.png`.
3. Uma primeira cerimônia de pareamento celular↔emulador A que **expirou** por tempo esgotado (Tor do
   celular ainda "frio", ~1 min de vida), registrada explicitamente como falha de timing, não de
   protocolo — com SAS `705238` idêntico nos dois lados antes de expirar.
4. Uma segunda cerimônia, com o Tor já aquecido, que **completou** de ponta a ponta — SAS `432057`
   idêntico, contato salvo dos dois lados.
5. Envio de mensagem celular→emulador A (~10 s) e encaminhamento emulador A→emulador B (~15 s, etiqueta
   "Forwarded" só no destino) — captura `b-forwarded.png`.
6. Uma seção final "O que isto cobre e o que não cobre", deixando explícito que os fatos observados
   cobrem a leitura da **oferta** pela câmera real, não uma leitura da **resposta** por câmera (o QR de
   resposta e o de confirmação desta sessão foram entregues pelo relay, não fotografados), e que
   retentativa automática de busca, log de erro específico e os gates 8/10 continuam fora do escopo
   desta sessão.

Evidências visuais: `docs/development/build-logs/real-device-20260918/` (`README.md`, `a-qr.png`,
`b-forwarded.png`). Uma terceira captura (tela de bloqueio do celular do usuário) foi deliberadamente
omitida por conter dado pessoal.

### Vantagens

- Fecha, com evidência verificável, o único item que impedia marcar T4.16 como `[x]` em todas as
  sessões anteriores (run5, run6, run7) — a leitura de QR pela câmera real de um Galaxy Note10+.
- Distingue com precisão, na própria tabela e na prosa, o que os fatos observados demonstram (leitura
  da oferta pela câmera) do que não demonstram (leitura da resposta pela câmera), evitando que uma
  leitura futura infle o resultado além do que foi de fato medido.
- Registra a falha da primeira cerimônia como um achado de timing (Tor frio), não como um defeito de
  protocolo ou de leitura de QR, e abre a pendência correspondente (retentativa/log de erro) como uma
  tarefa nova rastreável (T4.18) em vez de deixá-la apenas mencionada em prosa.

### Por que a mudança foi feita

Sessão de verificação em aparelho físico real de 2026-09-18, continuação direta de T4.16
(`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`). Nenhum código do app foi alterado nesta
sessão — documentação apenas.

## 2026-09-18 — Amarração de mão dupla com o checklist de release

### Como era antes

A seção "T4.17 — campainha, validação ao vivo em dois emuladores (2026-09-18)" e seus dois adendos já
listavam o que foi coberto e o que continua `NOT_RUN`, mas terminavam sem apontar para o documento
onde esse status vira decisão de release. A referência existia só num sentido: o checklist não citava
esta seção, e esta seção não citava o checklist.

### Como ficou

Um parágrafo de cinco linhas no fim do último adendo, dizendo que os status foram transcritos para as
células dos gates **6** e **10** de `docs/release-checklist.md` em 2026-09-18 e resumindo, numa frase,
o que passa: gate 10(b) **(iv)**, a metade de batida **válida** do **(v)**, a metade de **mesmo
cofre** do **(vi)**, e as notificações **id=2** e **id=3** em inglês no gate 6 — com o resto em
`NOT_RUN` e nenhum dos dois gates passando como um todo.

Nenhuma outra linha do arquivo foi tocada, e nenhuma evidência foi repetida: o parágrafo não
reproduz as tabelas de arquivos que já estão logo acima.

### Por que a mudança foi feita

As listas de "cobre / não cobre" desta seção e as células dos gates agora dizem a mesma coisa em dois
arquivos. Sem um ponteiro explícito, a próxima sessão que editar um dos dois pode deixar o outro para
trás sem perceber — e a divergência que aparece é sempre no sentido otimista.

### Vantagens

- Quem lê a evidência bruta descobre imediatamente que ela já foi contabilizada, e onde.
- Quem lê o checklist volta à narrativa com horários e capturas por um link nomeado.
- O resumo de uma frase torna visível, dos dois lados, que só **metades** dos sub-itens (v) e (vi)
  passaram.

### Não verificado

Nada. A mudança é textual e não introduz nenhuma afirmação de medição nova.
