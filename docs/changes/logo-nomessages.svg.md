# Mudanças em `docs/design/logo-nomessages.svg`

## 2026-09-23 — Correção da revisão: cores desatualizadas (verde do WhatsApp, fundo verde-teal)

### Como era antes

```svg
<rect x="0" y="0" width="108" height="108" fill="#073C31" />
<path d="M38,34 L70,34 A8,8 0 0 1 78,42 L78,66 A8,8 0 0 1 70,74 L38,74 L30,66 L30,42 A8,8 0 0 1 38,34 Z"
      fill="none" stroke="#F3F6F5" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" />
<path d="M33,33 L75,75" fill="none" stroke="#25D366" stroke-width="6" stroke-linecap="butt" />
```

O fundo (`#073C31`, verde-teal escuro) e, principalmente, o traço diagonal (`#25D366`, o verde da
marca do WhatsApp) ficaram do concept original de 2026-09-16. A paleta do ícone real do app mudou
para "Grafite e Âmbar" em 2026-09-17 (ver `app/src/main/res/values/colors.xml`), mas este SVG de
referência em `docs/design/` nunca foi atualizado — o comentário no topo do arquivo afirma que ele é
"1:1 faithful to the Android vector drawables", o que já não era verdade.

### Como é agora

```svg
<rect x="0" y="0" width="108" height="108" fill="#1B1F22" />
<path d="M38,34 L70,34 A8,8 0 0 1 78,42 L78,66 A8,8 0 0 1 70,74 L38,74 L30,66 L30,42 A8,8 0 0 1 38,34 Z"
      fill="none" stroke="#F3F6F5" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" />
<path d="M33,33 L75,75" fill="none" stroke="#E8A33B" stroke-width="6" stroke-linecap="butt" />
```

Fundo trocado para `#1B1F22` (`ic_launcher_bg`) e o traço diagonal para `#E8A33B`
(`ic_launcher_accent`) — os mesmos valores hex de `app/src/main/res/values/colors.xml` e usados por
`ic_launcher_background.xml`/`ic_launcher_foreground.xml`. O contorno da bolha (`#F3F6F5`,
`ic_launcher_glyph`) já batia com o ícone real e não mudou. `README.md` referencia este SVG
diretamente (sem PNG exportado intermediário), então nenhum outro arquivo precisou mudar.

### Vantagens

- O SVG volta a ser fiel ao ícone real do app instalado, em vez de mostrar uma versão antiga com o
  verde característico do WhatsApp — relevante porque `README.md` afirma explicitamente "NoMessages
  is not affiliated with WhatsApp or Meta and includes none of their logos, trademarks or official
  assets", e um verde `#25D366` reconhecível na logo do próprio projeto contradizia essa frase.
- Um único par de arquivos (`colors.xml` + este SVG) passa a ser a fonte de verdade da paleta do
  ícone; não há mais duas versões divergentes da mesma marca circulando no repositório.

### Por que a mudança foi feita

Achado 3 da revisão desta tarefa.
