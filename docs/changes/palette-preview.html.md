# docs/design/palette-preview.html

## 2026-09-17 — Comparativo de 3 paletas candidatas ao rebrand visual (arquivo novo)

Tarefa: preview de design, sem tocar em código do app (nenhum `.kt`/`.xml` de
`app/src/main` foi lido para edição, apenas consultado como referência de geometria do
ícone atual). Entregável isolado para revisão da identidade visual.

### Arquivo novo — sem "como era antes"

`docs/design/palette-preview.html` não existia. Não há "antes" a documentar; a seção
abaixo descreve o que foi criado.

### O que o arquivo contém

Um `.html` único, autocontido (CSS e JS inline, zero requisição externa, abre direto no
navegador sem servidor nem build), comparando **três paletas candidatas** para substituir
o esquema verde herdado do WhatsApp (`#075E54`/`#128C7E`/`#25D366`/`#DCF8C6`/`#EDEDED`):

- **Paleta 1 — "Grafite e Âmbar"** (grafite no chrome, âmbar como accent) — marcada no
  documento como **recomendada e já em implementação** como novo padrão do app.
- **Paleta 2 — "Ardósia e Cobre"** (ardósia azulada + cobre) — alternativa registrada,
  não escolhida.
- **Paleta 3 — "Meia-noite e Menta"** (índigo quase-preto + menta) — alternativa
  registrada, não escolhida.

Cada seção mostra, para claro e escuro: a faixa de swatches com os hex de
`background`/`surface`/`surfaceVariant`/`primary`/`primaryContainer`/`secondary`/`tertiary`;
o ícone do launcher recolorido, redesenhado como SVG inline reproduzindo a geometria real
de `ic_launcher_background.xml`/`ic_launcher_foreground.xml` (o mesmo `pathData` do balão
com entalhe chanfrado e do traço diagonal, só trocando as cores); e mockups estáticos de
telefone para as três telas mais representativas — lista de conversas, conversa e
configuração inicial — cada uma em claro e escuro (8 mockups por paleta, 24 no total).

Os hex de todas as três paletas foram copiados literalmente do pedido e depois conferidos
por script (extração de todo `#RRGGBB` do objeto `PALETTES` e comparação contra o conjunto
esperado): zero divergência, nenhum valor faltando ou sobrando. A razão de contraste de cada
par crítico (`primary`/`onPrimary`, `primaryContainer`/`onPrimaryContainer`,
`secondary`/`onSecondary`, claro e escuro, nas três paletas) é **calculada ao vivo no
próprio HTML**, pela fórmula padrão WCAG de luminância relativa sRGB — não são números
digitados à mão, então não podem divergir da implementação da fórmula. As mesmas dezoito
razões foram recalculadas de forma independente em Python durante a revisão e batem com os
exemplos do pedido (ex.: Paleta 1 clara, `secondary`/`onSecondary` ≈ 6.21:1; `primary`/
`onPrimary` claro ≈ 14.88:1, escuro ≈ 10.36:1). Todos os pares ficam acima de 4.5:1 (AA).

A tela de conversa também é renderizada uma segunda vez com `filter: grayscale(1)`, lado a
lado com a versão colorida, para demonstrar que bolhas enviadas e recebidas continuam
distinguíveis sem depender de matiz — alinhamento (direita/esquerda) e formato do canto
arredondado (canto reto embaixo à direita na enviada, embaixo à esquerda na recebida) que
seguram a leitura mesmo em escala de cinza, para quem não distingue as cores.

### Vantagens

- Decisão de identidade visual documentada com números conferíveis (hex exatos, razões de
  contraste calculadas por fórmula, não estimadas) em vez de uma escolha de paleta feita só
  "no olho".
- Reúne num único artefato tudo que um revisor precisa para aprovar ou pedir ajuste: cor,
  ícone recolorido e telas reais lado a lado, claro e escuro — sem precisar montar o app.
- A prova de distinguibilidade em escala de cinza fica registrada como parte do processo de
  decisão, não como afirmação não verificada.
- Por ser gerado a partir de um único objeto de dados em JavaScript (`PALETTES`), o mesmo
  hex alimenta o swatch, o mockup e o cálculo de contraste — elimina a classe de erro em
  que o texto da doc diverge da cor realmente usada no mockup.

### Por que a mudança foi feita

Pedido do usuário: o NoMessages sempre foi um clone visual do WhatsApp (inclusive na
paleta) e está em processo de ganhar identidade própria. Antes de qualquer paleta virar
padrão do app, era preciso um comparativo formal com prova de acessibilidade (contraste AA)
e de robustez para daltonismo (distinguibilidade em escala de cinza), permitindo decidir
com base em critérios verificáveis. A Paleta 1 ("Grafite e Âmbar") foi a selecionada; sua
implementação em `NoMessagesTheme.kt`/`colors.xml`/ícone do launcher é conduzida por um
agente separado, em paralelo a este documento.

### Não verificado

- O arquivo não foi aberto num navegador real dentro desta sessão (sem ferramenta de
  browser disponível); a validação foi estática: HTML bem formado (via `html.parser` do
  Python), sintaxe JS válida (`node --check`), e execução do script de geração num stub de
  DOM em Node, confirmando a contagem esperada de elementos por paleta (8 telefones, 14
  swatches, 16 bolhas, 10 linhas de conversa, 3 SVGs, `div`s balanceados) sem exceção em
  tempo de execução.
