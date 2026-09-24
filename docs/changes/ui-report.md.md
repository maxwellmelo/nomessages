# docs/development/ui-report.md

## 2026-09-16 — Seção "Inline media" consolidada e nota de verificação (T4.8, agente final)

### Motivo

Os três agentes de mídia inline (A: áudio + infraestrutura compartilhada; B: fotos; C: vídeo)
implementaram a funcionalidade completa mas, por instrução explícita de escopo, nenhum deles tocou
`docs/development/ui-report.md` — essa consolidação ficou para o agente final, que também rodou a
suíte instrumentada e validou visualmente em dois emuladores pareados. Sem essa seção, o relatório
de UI continuava descrevendo apenas a política de anexos "genéricos" (PDF/imagem em tela cheia/
texto) da seção "Attachment policy", sem nenhuma menção às três bolhas novas (áudio/foto/vídeo) nem
ao cache de prévia limitado que elas introduziram.

### Como era antes

O arquivo ia direto de "Attachment policy" (anexos em tela cheia, sem nenhuma bolha inline) para
"Resources and accessibility", e a frase final de "Entry point and state ownership" afirmava sem
ressalva: "Message, contact, pairing, and attachment content is never cached by the UI."

### Como ficou

Nova seção "Inline media (2026-09-16, T4.8)" entre "Attachment policy" e "Resources and
accessibility", cobrindo bolha de áudio (play/pause, seek, duração, forma de onda arrastável, ciclo
de velocidade, um player por vez via `AudioPlaybackCoordinator`), bolha de foto (miniatura limitada
por `inSampleSize`, zoom/swipe em `AttachmentViewer`), bolha de vídeo (poster frame via
`MediaMetadataRetriever`, selo de duração, seek arrastável reaproveitado por áudio em tela cheia
também), e uma explicação honesta do limite de `MediaPreviewCache` (~24 entradas / ~8 MiB) e de sua
limpeza síncrona no `lock()`/`closeSession()`. A seção "Verification" ganhou um item novo citando o
resultado da suíte instrumentada (15/15) e a validação visual em dois emuladores, com o caminho da
evidência.

A frase "Message, contact, pairing, and attachment content is never cached by the UI" foi
reformulada dentro da nova seção (não removida do texto original, mas qualificada logo depois):
agora o relatório deixa explícito que ela deixou de ser literalmente exata desde que o cache
limitado de prévias existe — dados derivados pequenos (forma de onda, miniatura/poster) **são**
cacheados deliberadamente durante a sessão desbloqueada, nunca os bytes brutos do anexo, e a garantia
real é "nada sobrevive a um lock", não "nada é cacheado".

### Vantagens

- O relatório de UI volta a descrever com precisão tudo que `ChatScreen.kt`/`AttachmentViewer.kt`
  renderizam hoje, em vez de parar na política de anexo genérico anterior a T4.8.
- A ressalva sobre a frase de cache-zero evita uma alegação de segurança mais forte do que o código
  realmente garante — documentado com o mesmo cuidado que o resto do arquivo já usa (ex.: a seção
  de `debug.nomessages.allow_capture`).
- A nova entrada de "Verification" torna rastreável, por caminho de arquivo, a evidência de que a
  suíte instrumentada e a validação visual realmente aconteceram nesta tarefa, sem exigir que o
  leitor busque em outro lugar.

### Por que a mudança foi feita

Passo 4 da tarefa final de T4.8 pedia explicitamente esta seção nova em
`docs/development/ui-report.md`, reaproveitando o tom/estrutura de "Attachment policy" e
qualificando com cuidado a frase de cache-zero existente.

---

## 2026-09-16 — Nova seção "Input/keyboard hardening, autofill, and capture-detection banner" (T4.9)

### Motivo

`docs/development/ui-report.md` descrevia toda a UI implementada até T4.8, mas nada sobre as
proteções de entrada/captura de T4.9 (aviso de teclado de terceiros no composer e no
setup/lock, `Snackbar` de detecção de captura). Sem essa seção, o relatório de UI ficaria
desatualizado em relação à tela real.

### Como era antes

O arquivo ia de "Inline media (2026-09-16, T4.8)" direto para "Resources and accessibility", sem
nenhuma menção a IME privado, autofill, banner de teclado de terceiros ou `Snackbar` de captura.

### Como ficou

Nova seção "Input/keyboard hardening, autofill, and capture-detection banner (2026-09-16, T4.9)"
entre "Inline media" e "Resources and accessibility", cobrindo os cinco pontos visíveis na UI:
todo campo de texto do app agora usa `PrivateImeScope`/`privateKeyboardOptions`; autofill excluído
uma vez no `decorView`; o banner discreto de teclado de terceiros no composer e nas telas de
setup/lock, threadado como `Boolean` simples (não via `UiState`); e o primeiro `SnackbarHost` do
app, adicionado na raiz de `MainActivity` para o aviso de captura detectada.

### Vantagens

- O relatório de UI volta a descrever com precisão toda tela que renderiza hoje, inclusive os dois
  elementos novos de UI (banner de teclado, `Snackbar` de captura).
- Documenta explicitamente a decisão de manter `thirdPartyImeActive` fora de `UiState`/
  `NoMessagesController` — um leitor futuro entende que foi deliberado, não um descuido.

### Por que a mudança foi feita

T4.9, requisito explícito de nota em `docs/development/ui-report.md`.

---

## 2026-09-17 — Bullet obsoleto removido do bloco de "Implemented flows"

### Como era antes

Um bullet isolado, no meio de um bloco sobre backup/restore, logo antes da seção "Attachment
policy", descrevia uma tela que o app não tem mais (ver `docs/changes/HomeScreen.kt.md`,
`docs/changes/NoMessagesApp.kt.md`).

### Como ficou

O bullet foi apagado por inteiro (não sobrou item de lista vazio); o bullet anterior passa a
encerrar o bloco, seguido direto pela seção "Attachment policy".

### Vantagens

- O relatório de UI deixa de descrever uma tela que o app não tem mais — antes o texto ainda seria
  tecnicamente verdadeiro, mas passaria a descrever código morto que sequer existe.

### Por que a mudança foi feita

Manter `docs/development/ui-report.md` batendo com as telas que o app de fato renderiza.

---

## 2026-09-17 — Nova seção "Visual identity" (T4.14)

### Motivo

`docs/development/ui-report.md` não tinha nenhuma seção descrevendo a paleta de cores do app —
"classic NoMessages palette" era mencionada de passagem em "Implemented flows", sem tabela de
tokens nem justificativa. Com a troca completa de paleta (cor hardcoded → `MaterialTheme.colorScheme`,
"Grafite e Âmbar" — ver `docs/changes/NoMessagesTheme.kt.md`), o relatório de UI precisava de uma
seção própria descrevendo a paleta nova, para servir de referência única em vez de espalhar a
tabela de tokens por múltiplos `docs/changes/*.kt.md`.

### Como era antes

O arquivo ia direto do título/data ("Updated: 2026-09-14") para "Entry point and state ownership",
sem nenhuma seção de identidade visual/paleta.

### Como ficou

Nova seção "Visual identity", logo no topo do arquivo, antes de "Entry point and state ownership":
nome da paleta ("Grafite e Âmbar"), a garantia de que nenhuma constante de cor hardcoded sobrevive em
`app/src/main/kotlin/dev/mx3/nomessages/ui/` (verificável por `grep`), a nota de que todo par
texto/fundo relevante foi checado contra WCAG AA nos dois temas, a explicação de como bolha
enviada/recebida se distingue sem depender só de matiz, a tabela completa de tokens (papel → claro
→ escuro → uso) e um ponteiro para `docs/design/palette-preview.html`, que também mostra as duas
paletas candidatas rejeitadas.

### Vantagens

- Uma única tabela de tokens serve de referência central, em vez de reconstruí-la lendo os seis ou
  sete `docs/changes/*.kt.md` que tocaram cor nesta tarefa.
- `docs/design/palette-preview.html` (produzido por um agente paralelo, não pelo mesmo agente desta
  seção) fica referenciado a partir do relatório de UI "oficial" do projeto, não só dos documentos
  de mudança individuais.

### Por que a mudança foi feita

T4.14 (ver `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`): nova paleta "Grafite e
Âmbar" em vez da identidade verde-WhatsApp.

### Correção pós-revisão (2026-09-17, mesmo dia — achado na conferência do orquestrador)

A seção "Visual identity" nova ficou correta, mas um parágrafo mais antigo em "Resources and
accessibility" (mais abaixo no mesmo arquivo) não tinha sido tocado por esta tarefa e ainda dizia:

```
The launcher uses an original vector mark and no Meta asset. Controls have
localized content descriptions, large touch targets, system typography, and
dark/light Compose color schemes based on the required classic palette:
`#075E54`, `#128C7E`, `#25D366`, `#DCF8C6`, and `#EDEDED`.
```

Ou seja, o mesmo arquivo passou a se contradizer: uma seção nova descrevendo a paleta "Grafite e
Âmbar" e uma seção antiga ainda afirmando o monograma antigo e os cinco hexadecimais verde-WhatsApp
como "the required classic palette". Corrigido para apontar para a seção nova em vez de duplicar
(e desatualizar) a lista de cores:

```
The launcher uses an original "NM" vector mark and no Meta asset. Controls have
localized content descriptions, large touch targets, system typography, and
dark/light Compose color schemes based on the app's own "Grafite e Âmbar"
palette (see "Visual identity" above for the full token table) — no longer the
classic WhatsApp green palette this section used to describe.
```
