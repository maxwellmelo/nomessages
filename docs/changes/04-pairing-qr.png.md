# Mudanças em `docs/assets/screenshots/04-pairing-qr.png`

## 2026-09-23 — Correção da revisão: QR de pareamento real borrado

### Como era antes

O PNG mostrava o QR de pareamento totalmente legível — ele codifica a identidade e o endereço onion
de um cofre de teste real usado para capturar as screenshots do README (ver
`docs/changes/README.md.md`, seção "Como as screenshots foram capturadas"), não um placeholder
gerado à parte.

### Como é agora

A região do QR (retângulo aproximado `x:70-470, y:245-648` dentro da imagem 540×1145) foi borrada
com `PIL.ImageFilter.GaussianBlur(radius=28)`, aplicado só naquele recorte e colado de volta sobre a
imagem original — o resto da tela (barra de título, texto explicativo, contador "New QR in 01:57",
botões "Scan QR"/"Cancel") permanece nítido e sem alteração de pixel. O QR fica completamente
ilegível a olho nu (confirmado visualmente após o processamento).

### Vantagens

Ver `docs/changes/README.md.md`, entrada datada 2026-09-23 ("QR de pareamento real na screenshot"),
que também cobre o ajuste do alt-text e da legenda em `README.md`.

### Por que a mudança foi feita

Achado 2 da revisão desta tarefa.
