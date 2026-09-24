# ic_launcher — Novo ícone do app (rebrand NoMessages)

## Contexto

O ícone anterior era um monograma estilizado de duas letras sobre um
verde vivo (`#128C7E`), claramente herdado do visual do WhatsApp — inclusive a paleta e a
sigla remetiam ao codinome usado antes do nome atual do app. Com o rebrand para **NoMessages** (foco extremo em
privacidade/deniability: Tor, vault duplo com senha de pânico, zero rastro), o ícone precisava
comunicar visualmente "mensagem privada e cifrada", não mais "clone de WhatsApp".

## Conceito escolhido: balão de conversa + cadeado sólido

Um balão de conversa em contorno (retângulo arredondado, sem a cauda triangular saliente do
WhatsApp — no lugar dela, um pequeno entalhe chanfrado no canto inferior esquerdo, mais sóbrio
e geométrico) com um cadeado sólido centralizado dentro dele.

**Por que este e não as alternativas** (as duas alternativas foram produzidas só como SVG de
preview em `docs/design/logo-alt-1.svg` e `docs/design/logo-alt-2.svg`, não entraram no app):

- **Alternativa 1 (escudo + balão recortado)**: comunica "proteção" de forma mais genérica —
  poderia ser o ícone de qualquer app de segurança/antivírus. Perde a referência direta a
  "mensagens", que é o produto em si.
- **Alternativa 2 (balão silenciado com traço diagonal)**: comunica "ausência de notificação/
  rastro", mas o traço diagonal é visualmente muito próximo do padrão universal de "recurso
  desativado/bloqueado" (ex.: câmera desligada, silenciar). Corre o risco de ser lido como
  "mensagens desativadas" em vez de "mensagens privadas".
- **Balão + cadeado** é a metáfora mais direta e inequívoca de "mensagem privada/cifrada",
  reconhecível a 48px mesmo sem o contexto do nome do app, e combina diretamente com o pitch
  (Tor, vault cifrado, zero rastro).

## Paleta

Mantido `wf_accent` (`#25D366`) como destaque, conforme pedido. Fundo trocado do verde vivo
`wf_green` (`#128C7E`) para um verde-teal bem mais escuro e discreto, `ic_launcher_bg`
(`#073C31`) — dentro da família sugerida (`#06342C`–`#0A3D33`). Glifo (contorno do balão e
corpo do cadeado) em quase-branco frio, `ic_launcher_glyph` (`#F3F6F5`).

Contraste (WCAG, razão de luminância) verificado:

| Par                                   | Razão   | AA (4.5:1) |
|----------------------------------------|---------|------------|
| `ic_launcher_glyph` sobre `ic_launcher_bg` | ≈ 11.4:1 | folga ampla (acima até de AAA) |
| `wf_accent` sobre `ic_launcher_bg`     | ≈ 6.2:1  | folga ampla |

O buraco da fechadura usa `wf_accent` (círculo) e `wf_green` (fenda) como tons secundários —
isso também mantém `wf_green` referenciado (antes só era usado pelo próprio ícone; se o ícone
parasse de referenciá-lo, ele viraria lixo novo no lint `UnusedResources`).

## Antes / depois

### `app/src/main/res/drawable/ic_launcher_background.xml`

Antes (shape XML, sem referência real usada pelo adaptive icon — o `mipmap-anydpi-v26`
apontava direto para `@color/wf_green`, deixando este arquivo morto/`UnusedResources`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/wf_green" />
</shape>
```

Depois (VectorDrawable de verdade, agora referenciado pelo adaptive icon como camada
`background`, com a nova cor sóbria):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="@color/ic_launcher_bg"
        android:pathData="M0,0h108v108h-108z" />
</vector>
```

### `app/src/main/res/drawable/ic_launcher_foreground.xml`

Antes (dois traços brancos formando "W" e "f"):

```xml
<path android:fillColor="@android:color/transparent"
    android:pathData="M22,31 L33,75 L50,49 L64,75 L75,31"
    android:strokeColor="#FFFFFFFF" android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeWidth="8" />
<path android:fillColor="@android:color/transparent"
    android:pathData="M72,73 L72,41 Q72,25 89,25 M66,43 L86,43"
    android:strokeColor="#FFFFFFFF" android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeWidth="7" />
```

Depois (balão de conversa com entalhe + cadeado sólido, cores via `@color`):

```xml
<path android:fillColor="@android:color/transparent"
    android:strokeColor="@color/ic_launcher_glyph" android:strokeWidth="6"
    android:strokeLineCap="round" android:strokeLineJoin="round"
    android:pathData="M38,34 L70,34 A8,8 0 0 1 78,42 L78,66 A8,8 0 0 1 70,74 L38,74 L30,66 L30,42 A8,8 0 0 1 38,34 Z" />
<path android:fillColor="@android:color/transparent"
    android:strokeColor="@color/ic_launcher_glyph" android:strokeWidth="5"
    android:strokeLineCap="round" android:strokeLineJoin="round"
    android:pathData="M47,54 V46 A7,7 0 0 1 61,46 V54" />
<path android:fillColor="@color/ic_launcher_glyph"
    android:pathData="M48,54 L60,54 A3,3 0 0 1 63,57 L63,67 A3,3 0 0 1 60,70 L48,70 A3,3 0 0 1 45,67 L45,57 A3,3 0 0 1 48,54 Z" />
<path android:fillColor="@color/wf_accent"
    android:pathData="M54,60.5 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0" />
<path android:fillColor="@color/wf_green"
    android:pathData="M53,61 L55,61 L55,65 L53,65 Z" />
```

### `app/src/main/res/drawable/ic_launcher_monochrome.xml` (novo)

Não existia. Criado para a camada `<monochrome>` dos ícones temáticos do Android 13+: mesma
silhueta (balão + cadeado), em um único caminho por forma, cor irrelevante (o sistema aplica o
próprio tint). O buraco da fechadura vira um recorte real via `android:fillType="evenOdd"` (em
vez do detalhe colorido usado na versão principal, já que o monochrome não carrega cor de
destaque).

### `app/src/main/res/mipmap-anydpi-v26/` → `app/src/main/res/mipmap-anydpi/`

`minSdk = 31` (`app/build.gradle.kts`) já é maior que 26, então o qualificador `-v26` era
redundante (achado `ObsoleteSdkInt` do lint). A pasta foi recriada sem o qualificador e a
antiga (`mipmap-anydpi-v26`) foi apagada.

Antes (`mipmap-anydpi-v26/ic_launcher.xml`, idêntico em `ic_launcher_round.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/wf_green" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

Depois (`mipmap-anydpi/ic_launcher.xml`, idêntico em `ic_launcher_round.xml`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

Os nomes de recurso (`@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`) continuam os mesmos
referenciados pelo `AndroidManifest.xml` — nenhuma edição de manifesto foi necessária.

### `app/src/main/res/mipmap/ic_launcher.xml` e `ic_launcher_round.xml` (legado, 48dp)

Antes (glifo do monograma antigo, cor de fundo hardcoded `#FF128C7E`):

```xml
<path android:fillColor="#FF128C7E" android:pathData="M8,8h92v92h-92z" />
<path android:fillColor="@android:color/transparent" android:pathData="M22,31 L33,75 L50,49 L64,75 L75,31"
    android:strokeColor="#FFFFFFFF" android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeWidth="8" />
<path android:fillColor="@android:color/transparent" android:pathData="M72,73 L72,41 Q72,25 89,25 M66,43 L86,43"
    android:strokeColor="#FFFFFFFF" android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeWidth="7" />
```

Depois (mesmo glifo balão + cadeado do adaptive icon, agora via `@color` em vez de hex
hardcoded; fundo quadrado com o mesmo recorte de 8dp de margem do original, fundo redondo com
o mesmo círculo raio 50 do original):

```xml
<path android:fillColor="@color/ic_launcher_bg" android:pathData="M8,8h92v92h-92z" />
<!-- (ic_launcher_round.xml usa "M54,4 A50,50 0,1 0,54 104 A50,50 0,1 0,54 4" no lugar da linha acima) -->
<path android:fillColor="@android:color/transparent" android:strokeColor="@color/ic_launcher_glyph"
    android:strokeWidth="6" android:strokeLineCap="round" android:strokeLineJoin="round"
    android:pathData="M38,34 L70,34 A8,8 0 0 1 78,42 L78,66 A8,8 0 0 1 70,74 L38,74 L30,66 L30,42 A8,8 0 0 1 38,34 Z" />
<path android:fillColor="@android:color/transparent" android:strokeColor="@color/ic_launcher_glyph"
    android:strokeWidth="5" android:strokeLineCap="round" android:strokeLineJoin="round"
    android:pathData="M47,54 V46 A7,7 0 0 1 61,46 V54" />
<path android:fillColor="@color/ic_launcher_glyph"
    android:pathData="M48,54 L60,54 A3,3 0 0 1 63,57 L63,67 A3,3 0 0 1 60,70 L48,70 A3,3 0 0 1 45,67 L45,57 A3,3 0 0 1 48,54 Z" />
<path android:fillColor="@color/wf_accent" android:pathData="M54,60.5 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0" />
<path android:fillColor="@color/wf_green" android:pathData="M53,61 L55,61 L55,65 L53,65 Z" />
```

## Área segura

O balão (30–78 × 34–74) e o cadeado (corpo 45–63 × 54–70, argola até y≈39) ficam inteiramente
dentro do círculo de segurança de 66dp de diâmetro centrado em (54,54) — os pontos mais
distantes do centro (cantos chanfrados do balão) ficam a ≈26–31dp do centro, contra um raio de
33dp disponível. Isso garante que o glifo completo permaneça visível em qualquer máscara do
sistema (círculo, squircle, quadrado arredondado).

## Como isso resolve os dois achados de lint

- **`UnusedResources` em `ic_launcher_background.xml`**: o drawable deixou de ser um `<shape>`
  não referenciado e passou a ser um `VectorDrawable` referenciado por
  `mipmap-anydpi/ic_launcher.xml` (e `ic_launcher_round.xml`) como camada `background` —
  agora está em uso.
- **`ObsoleteSdkInt` em `mipmap-anydpi-v26`**: a pasta foi renomeada para `mipmap-anydpi` (sem
  qualificador `-v26`), já que `minSdk = 31` torna esse qualificador redundante. A pasta antiga
  foi apagada.

## Arquivos tocados

- Modificados: `drawable/ic_launcher_background.xml`, `drawable/ic_launcher_foreground.xml`,
  `mipmap/ic_launcher.xml`, `mipmap/ic_launcher_round.xml`, `values/colors.xml` (só acréscimo).
- Criados: `drawable/ic_launcher_monochrome.xml`, `mipmap-anydpi/ic_launcher.xml`,
  `mipmap-anydpi/ic_launcher_round.xml`, `docs/design/*`, `docs/changes/ic_launcher.md`,
  `docs/changes/colors.xml.md`.
- Removidos: `mipmap-anydpi-v26/ic_launcher.xml`, `mipmap-anydpi-v26/ic_launcher_round.xml`
  (pasta inteira apagada após a migração de conteúdo).

## Validação

Ver resultado de build/lint e instalação no emulador no relatório final desta tarefa (não
repetido aqui para evitar divergência caso o comando seja re-executado depois).

---

## 2026-09-16 — Troca de conceito: "balão + cadeado" → "balão silenciado"

### Decisão

O usuário avaliou as três direções (balão+cadeado em produção, e as duas alternativas em SVG)
e escolheu a **Alternativa 2 — "balão silenciado"** (`docs/design/logo-alt-2.svg` na versão
anterior deste documento) como o novo conceito definitivo: o mesmo contorno de balão sóbrio
(com o entalhe chanfrado), agora cruzado por um traço diagonal na cor de destaque do app
(`wf_accent`, `#25D366`), sem o cadeado. A leitura passa a ser "nenhuma mensagem fica
armazenada/interceptável" em vez de "mensagem cifrada" — mais alinhado ao pitch de
"zero rastro" do NoMessages do que à ideia de "cofre".

Como consequência, os três arquivos SVG de documentação foram reorganizados:

| Arquivo | Antes (2026-09-14) | Depois (2026-09-16) |
|---|---|---|
| `docs/design/logo-nomessages.svg` (master) | Balão + cadeado | **Balão silenciado** (novo conceito de produção) |
| `docs/design/logo-alt-1.svg` | Escudo + balão recortado | Escudo + balão recortado (inalterado, nunca foi escolhido) |
| `docs/design/logo-alt-2.svg` | Balão silenciado | **Balão + cadeado** (conceito anterior, agora arquivado como alternativa) |

`docs/design/logo-preview.html` foi atualizado para exibir o novo master (tamanhos/máscaras)
e as legendas dos dois cards de alternativa foram reescritas para refletir essa reorganização.

### XML — antes / depois

`app/src/main/res/drawable/ic_launcher_foreground.xml` — antes (balão + cadeado sólido, ver
seção acima para o XML completo) → depois (balão + traço diagonal):

```xml
<path
    android:fillColor="@android:color/transparent"
    android:strokeColor="@color/ic_launcher_glyph"
    android:strokeWidth="6"
    android:strokeLineCap="round"
    android:strokeLineJoin="round"
    android:pathData="M38,34 L70,34 A8,8 0 0 1 78,42 L78,66 A8,8 0 0 1 70,74 L38,74 L30,66 L30,42 A8,8 0 0 1 38,34 Z" />

<path
    android:fillColor="@android:color/transparent"
    android:strokeColor="@color/wf_accent"
    android:strokeWidth="6"
    android:strokeLineCap="butt"
    android:pathData="M33,33 L75,75" />
```

O cadeado (argola + corpo + buraco da fechadura) foi removido do `foreground` e do
`monochrome`. `ic_launcher_background.xml` **não foi tocado** — o fundo `ic_launcher_bg`
(`#073C31`) continua o mesmo. Os vectors legado `mipmap/ic_launcher.xml` e
`ic_launcher_round.xml` (48dp, pré-adaptive-icon) também foram atualizados para o mesmo
glifo, por consistência com o que passa a estar em produção — evita que dispositivos que
caem no fallback legado ainda mostrem o cadeado retirado.

`ic_launcher_monochrome.xml`: o traço diagonal virou um `<path>` com `strokeWidth="6"`
próprio (não uma cor sobre um path já existente) — ou seja, sua legibilidade vem da própria
espessura/forma do traçado, não da cor `wf_accent`, que o monochrome ignora.

### Ajuste de área segura (66dp / raio 33 a partir de 54,54)

O palpite inicial de coordenadas do traço, `(30,30)→(78,78)`, fica a `dx=24,dy=24` do centro,
distância `≈33.94` — fora do raio de 33 mesmo antes de considerar a espessura do traço.

Dois ajustes foram feitos, não só um:

1. **`strokeLineCap` trocado de `round` para `butt`.** Com `round`, a ponta do traço "estoura"
   para além da coordenada do próprio path em meia espessura (3, já que `strokeWidth=6`) na
   direção da diagonal — ou seja, o ponto realmente pintado na tela fica ainda mais longe do
   centro do que a coordenada sugere (para `(31,31)`, o desenho real chegaria a
   `≈(28.9,28.9)`, distância `≈35.5` do centro: pior, não melhor). Com `butt`, o traço termina
   exatamente na coordenada informada, sem essa extrapolação — importante para qualquer
   elemento com ponta aberta (não fechado) que precise respeitar uma área de segurança.
2. **Coordenadas reduzidas para `(33,33)→(75,75)`.** `dx=dy=21`, distância `≈29.70` do centro
   — folga de `≈3.3` em relação ao raio de 33 (em vez de ficar exatamente no limite como a
   sugestão de `(31,31)→(77,77)`, distância `≈32.53`, que decidi não usar por deixar margem
   quase nula uma vez somada qualquer imprecisão de renderização/antialiasing).

O contorno do balão (inalterado desde a versão anterior) já havia sido conferido contra essa
mesma área de segurança: seus pontos mais distantes do centro são os cantos chanfrados
inferior-esquerdos, a `≈26–31` do centro — folga mantida.

### Decisão sobre o "gap" no cruzamento traço × balão

Avaliei duas opções para o ponto onde o traço diagonal cruza o contorno do balão:

- **(A) Sobreposição direta** — o traço é desenhado por cima do contorno do balão, sem
  interromper nenhum dos dois paths.
- **(B) "Quebra limpa"** — abrir um pequeno gap no contorno do balão (ou no traço) exatamente
  nos dois pontos de cruzamento, simulando o traço "passando por baixo" do contorno em um dos
  lados, como alguns ícones de "mudo" fazem.

**Escolhi a opção (A).** Motivos:

1. **Robustez a 48px.** Um gap correto exige um recorte (`fillType="evenOdd"` ou path
   separado) preciso no ângulo exato de 45° onde as duas larguras de traço (6 e 6) se cruzam.
   Em 48dp esse recorte vira poucos pixels — qualquer arredondamento de sub-pixel/antialiasing
   tende a deixar um "dente" ou uma mancha em vez de uma quebra limpa, ou seja, o risco de
   piorar a legibilidade é maior que o ganho estético.
2. **Consistência com o padrão do Material Design** para ícones "off" (`mic_off`,
   `videocam_off`, `notifications_off`): a barra diagonal é desenhada diretamente por cima do
   glifo original, sem cortar seu contorno. Isso já é um padrão reconhecível pelos usuários.
3. **Simplicidade no monochrome.** O layer monocromático já precisa ficar legível só por
   forma; introduzir um recorte de interseção dobra a complexidade geométrica desse arquivo
   (dois paths com um recorte mútuo) para um ganho visual marginal a 48px.

Testado mentalmente lado a lado (balão + traço contínuo vs. balão + traço com quebra nos dois
pontos de cruzamento): a versão contínua (A) ficou mais limpa e mais robusta em todos os
tamanhos avaliados (48/72/108/192px) — a quebra (B) só ficaria perceptivelmente "mais
polida" a partir de ~108px, tamanho em que o ícone já não é o caso de uso crítico de
legibilidade.

### Arquivos tocados nesta revisão

- Modificados: `drawable/ic_launcher_foreground.xml`, `drawable/ic_launcher_monochrome.xml`,
  `mipmap/ic_launcher.xml`, `mipmap/ic_launcher_round.xml`, `values/colors.xml` (só o texto do
  comentário, nenhuma cor alterada), `docs/design/logo-nomessages.svg`,
  `docs/design/logo-alt-1.svg` (só a legenda), `docs/design/logo-alt-2.svg`,
  `docs/design/logo-preview.html`, `docs/design/launcher-preview.png` (recapturado).
- Não tocados nesta revisão: `drawable/ic_launcher_background.xml`,
  `mipmap-anydpi/ic_launcher.xml`, `mipmap-anydpi/ic_launcher_round.xml` (nenhuma mudança de
  estrutura de camadas foi necessária, só o conteúdo do `foreground`/`monochrome` que elas já
  referenciavam).

---

## 2026-09-17 — Traço diagonal troca de `wf_accent` para `ic_launcher_accent` (nova paleta "Grafite e Âmbar", T4.14)

### Contexto

O rebrand de paleta (`docs/changes/NoMessagesTheme.kt.md`) remove `wf_accent` de `colors.xml` —
a cor que o traço diagonal do ícone ("balão silenciado", ver a seção de 2026-09-16 acima) usava
como `strokeColor`. O ícone precisa de uma cor própria para continuar existindo depois da remoção.

### `app/src/main/res/drawable/ic_launcher_foreground.xml`

Antes:

```xml
<!-- Diagonal "silenced" slash, drawn on top of the bubble contour (no gap, see docs/changes/ic_launcher.md). -->
<path
    android:fillColor="@android:color/transparent"
    android:strokeColor="@color/wf_accent"
    android:strokeWidth="6"
    android:strokeLineCap="butt"
    android:pathData="M33,33 L75,75" />
```

Depois:

```xml
<!-- Diagonal "silenced" slash, drawn on top of the bubble contour (no gap, see docs/changes/ic_launcher.md). -->
<path
    android:fillColor="@android:color/transparent"
    android:strokeColor="@color/ic_launcher_accent"
    android:strokeWidth="6"
    android:strokeLineCap="butt"
    android:pathData="M33,33 L75,75" />
```

`ic_launcher_accent` (`#E8A33B`) é uma cor nova, dedicada só ao launcher (ver
`docs/changes/colors.xml.md`), dentro da família âmbar da paleta "Grafite e Âmbar" mas ajustada
separadamente do `secondary` que o Compose usa (`#D98E2B` claro / `#F2A83D` escuro) — o ícone não
segue tema claro/escuro do sistema (é um asset estático), então recebeu o tom que melhor contrasta
com `ic_launcher_bg` (`#1B1F22`, também trocado nesta mesma leva — ver `docs/changes/colors.xml.md`)
em vez de herdar um dos dois tons do Compose.

### `app/src/main/res/mipmap/ic_launcher.xml` e `ic_launcher_round.xml` — achado durante o grep de autoverificação

Estes dois arquivos legados (ícone de 48dp, pré-adaptive-icon) **não estavam no inventário original**
desta tarefa — a lista de arquivos a revisar citava só o `drawable/ic_launcher_foreground.xml`. O
autoverificação `grep -rn "wf_" app/src/main/res/` (exigido no final da tarefa para confirmar zero
referências) encontrou `@color/wf_accent` também nestes dois mipmaps, herdado da mesma revisão de
2026-09-16 que trocou o cadeado pelo traço diagonal (ver a seção acima) — eles replicam o mesmo
glifo do `foreground` para o fallback de dispositivos sem adaptive icon, e a troca de cor daquela
revisão não tinha sido propagada a eles nesta leva. Corrigidos da mesma forma:

```xml
<!-- antes, nos dois arquivos -->
<path
    android:fillColor="@android:color/transparent"
    android:strokeColor="@color/wf_accent"
    android:strokeWidth="6"
    android:strokeLineCap="butt"
    android:pathData="M33,33 L75,75" />

<!-- depois, nos dois arquivos -->
<path
    android:fillColor="@android:color/transparent"
    android:strokeColor="@color/ic_launcher_accent"
    android:strokeWidth="6"
    android:strokeLineCap="butt"
    android:pathData="M33,33 L75,75" />
```

Nenhuma outra linha desses dois arquivos foi tocada — os dois `path` que desenham o contorno do
balão e o fundo (`@color/ic_launcher_bg`) já usavam nomes de cor, não hex hardcoded, então não
precisaram de mudança de referência (só o valor de `ic_launcher_bg` mudou, em `colors.xml`).

### Vantagens

- Fecha o único uso de `wf_accent` que restava fora das telas do Compose — sem ele, remover a cor
  de `colors.xml` deixaria três `VectorDrawable` com uma referência quebrada.
- O achado nos mipmaps legados mostra o valor prático do grep de autoverificação pedido no final da
  tarefa: um inventário de arquivos escrito de antemão não previu esses dois, e a verificação
  automatizada os pegou antes da entrega.

### Por que a mudança foi feita

T4.14: nova paleta "Grafite e Âmbar" — nenhum asset do app deveria continuar referenciando
`wf_accent` depois que a cor sai de `colors.xml`.
