# `app/src/main/res/drawable/ic_nomessages_notification.xml`

## 2026-09-18 — troca do monograma antigo pelo glifo atual do NoMessages

### Como foi descoberto

Durante a validação ao vivo da campainha (T4.17, fase 8), ao capturar a notificação de "mensagens
pendentes" na gaveta do emulador B, o usuário reparou que o **ícone pequeno** ainda era o monograma
do produto anterior. Não era impressão: os dois `pathData` do arquivo antigo desenhavam literalmente
as duas letras do monograma antigo dentro do balão.

### Antes

```xml
<path
    android:pathData="M4,3.5h16a2,2 0,0 1,2 2v10a2,2 0,0 1,-2 2H10l-5.5,3v-3H4a2,2 0,0 1,-2 -2v-10a2,2 0,0 1,2 -2z"
    android:strokeColor="#FFFFFFFF" android:strokeWidth="1.8" />
<path
    android:pathData="M5.5,7 L7,14 L9.5,9.5 L12,14 L13.5,7 M15,14 L15,8.5 Q15,6.5 18,6.5 M14,9.5 L17.5,9.5"
    android:strokeColor="#FFFFFFFF" android:strokeWidth="1.5" />
```

O primeiro path é um balão de fala com rabinho; o segundo é o monograma: `M5.5,7 L7,14 L9.5,9.5
L12,14 L13.5,7` traça um **W** em zigue-zague e `M15,14 L15,8.5 Q15,6.5 18,6.5 M14,9.5 L17.5,9.5`
traça um **f** (haste com a curva no topo mais a barra transversal) — a marca **NoMessages**.

### Agora

Balão de fala **silenciado**, o mesmo glifo de `app/src/main/res/drawable/ic_launcher_monochrome.xml`
(conceito adotado em 2026-09-16): contorno arredondado com entalhe de rabinho no canto inferior
esquerdo, cortado por um traço diagonal.

```xml
<path android:pathData="M6.08,4.6 L17.92,4.6 A2.96,2.96 0 0 1 20.88,7.56 L20.88,16.44
                        A2.96,2.96 0 0 1 17.92,19.4 L6.08,19.4 L3.12,16.44 L3.12,7.56
                        A2.96,2.96 0 0 1 6.08,4.6 Z"
      android:strokeColor="#FFFFFFFF" android:strokeLineCap="round"
      android:strokeLineJoin="round" android:strokeWidth="2.2" />
<path android:pathData="M4.23,4.23 L19.77,19.77"
      android:strokeColor="#FFFFFFFF" android:strokeLineCap="butt" android:strokeWidth="2.2" />
```

### Como as coordenadas foram obtidas

Não foram redesenhadas no olho. O glifo do launcher vive num viewport de 108×108 e este arquivo num
de 24×24, então apliquei uma **transformação afim única** a todos os pontos:

```
novo = (antigo - 54) * 0.370 + 12
```

54 é o centro do viewport de 108, 12 o centro do de 24, e `0.370` é a escala escolhida (ver abaixo).
O **mesmo** fator foi aplicado ao raio dos cantos (`8 → 2.96`) e à espessura do traço (`6 → 2.2`),
que é o que garante que as proporções da marca sejam preservadas exatamente, e não aproximadas.

Conferência de alguns pontos:

| Ponto (108) | Cálculo | Ponto (24) |
|---|---|---|
| `38,34` | `(38-54)*0.370+12` / `(34-54)*0.370+12` | `6.08,4.6` |
| `78,42` | `(78-54)*0.370+12` / `(42-54)*0.370+12` | `20.88,7.56` |
| `30,66` | `(30-54)*0.370+12` / `(66-54)*0.370+12` | `3.12,16.44` |
| `33,33` (traço) | `(33-54)*0.370+12` | `4.23,4.23` |
| `75,75` (traço) | `(75-54)*0.370+12` | `19.77,19.77` |

### Por que a escala é 0.370 e não 24/108

Escalar uniformemente de 108 para 24 daria `0.2222`, e o glifo sairia com ~12dp de largura num
canvas de 24dp — pequeno demais. O motivo é que o arquivo de 108 é um **adaptive icon**, cuja zona
segura ocupa só a parte central do canvas; um small icon de notificação não tem essa moldura.

`0.370` foi escolhido a partir da extensão pintada do glifo original (x de 27 a 81 contando a metade
do traço, ou seja meia-extensão de 27) para que a meia-extensão final seja 10: `10 / 27 = 0.370`.

### Área segura e legibilidade em 24px

- Extensão pintada resultante: ~**20 × 17 dp**, centrada em (12, 12) → sobra ~**2 dp** de margem
  lateral, que é a keyline esperada por uma barra de status. O glifo **não** é esticado até as bordas.
- Interior livre do balão: `17.92 − 6.08 = 11.84`, menos o traço de 2.2 → **~9,6 px** de vão limpo,
  cruzado por um traço de 2,2 px. Nem o vão nem o traço colapsam visualmente a 24 px reais.
- `strokeLineCap="butt"` no traço diagonal é deliberado e copia o launcher: pontas arredondadas
  suavizariam a marca justamente no tamanho em que ela precisa ser mais definida.

### Monocromia

Continua sendo apenas `#FFFFFFFF` sobre `@android:color/transparent`, e agora isso está explicado num
comentário no topo do arquivo: o Android desenha um small icon **só pelo canal alfa** e aplica o
próprio tint, de modo que qualquer cor ali seria descartada e uma forma preenchida viraria um borrão
sólido. Por isso tudo é contorno, nunca preenchimento.

### Alcance da mudança: as três notificações de uma vez

Existe **um único** drawable de ícone de notificação no app, e ele é referenciado nos **dois**
construtores de `PrivacyNotifications.kt`:

| Id | O que é | Onde | Canal |
|---|---|---|---|
| 1 | Mensagem recebida | `build()`, `PrivacyNotifications.kt:58` | `private_messages` |
| 2 | Aviso da campainha (T4.17) | `build()`, mesma linha | `private_messages` |
| 3 | Foreground do `:tor` em modo mínimo | `backgroundNotification()`, `PrivacyNotifications.kt:107` | `background_service` |

`TorService.kt` não constrói notificação própria — ele consome
`PrivacyNotifications.backgroundNotification(...)`. Portanto **nenhum ponto do app ficou apontando
para um resource diferente**: trocar este arquivo unificou as três de uma vez, sem mudar uma linha de
Kotlin.

### Por que foi feito

Consistência de marca. Uma notificação é, muitas vezes, a única superfície do app que o usuário vê
com o aparelho bloqueado — e era exatamente ali que continuava aparecendo o logotipo do **produto
anterior**. Além do erro óbvio de marca, num app cujo propósito é discrição o ícone é o identificador
mais público que ele possui: mostrar o monograma antigo na barra de status associa o aparelho a um nome que o
produto não usa mais.

### Vantagens

- As três notificações passam a exibir o glifo atual, e ficam coerentes com o ícone do launcher e com
  o ícone temático do Android 13+, que já usavam o balão silenciado desde 2026-09-16.
- O último resquício da marca anterior na superfície visível do produto foi removido.
- Risco zero de regressão funcional: nenhuma linha de Kotlin mudou, nenhum id de recurso mudou,
  nenhum canal, importância, visibilidade ou texto de notificação foi tocado — os gates 6 e 10 de
  `docs/release-checklist.md` continuam valendo sobre exatamente os mesmos campos.
- A derivação por transformação afim documentada acima torna a mudança auditável e reproduzível: se o
  glifo do launcher mudar, basta reaplicar a mesma fórmula em vez de redesenhar à mão.

### Evidência ao vivo

`docs/development/build-logs/doorbell-20260918/21-B-notification-shade-new-icon.png` — gaveta de
notificações do emulador B com o APK reconstruído, mostrando o glifo novo no lugar do monograma antigo.
