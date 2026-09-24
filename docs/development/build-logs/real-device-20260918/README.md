# Verificação em aparelho físico real — pareamento e mensagens (T4.16)

**Data:** 2026-09-18. **Aparelho físico:** Galaxy Note10+ SM-N975F, Android 12, arm64, serial
`RX8MA0GD9ZY`, com o APK debug do commit `706630a` instalado.
**Emuladores pareados:** emulador A = AVD `nomessages35` / `emulator-5556` (Android 15 x86_64);
emulador B = AVD `nomessages35b` / `emulator-5560` (Android 15 x86_64).

Esta sessão é a continuação do histórico já registrado em
`docs/development/device-verification.md` (tarefa T4.16 do roteiro de release,
`docs/superpowers/plans/2026-09-14-roadmap-to-release.md`) e nos logs de
`docs/development/build-logs/pairing-v2-20260917*/`. O registro completo dos passos e resultados
desta execução está na nova seção "Aparelho físico — 2026-09-18" de `device-verification.md`; este
diretório guarda apenas as evidências visuais.

## Capturas

1. `a-qr.png` — QR de pareamento **formato 2** exibido na tela do emulador A. É o QR que a câmera
   real do Note10+ leu com sucesso na primeira tentativa (logcat do celular:
   `NoMessagesQrScan: decode attempt result=DECODED`, frame 1920x1080, `rotationDegrees=90`, às
   10:12:34 hora do celular). Contraste direto com o achado histórico registrado em T4.16: o QR do
   formato 1 (versão 40, ~2.950 bytes) nunca foi lido pela mesma câmera, nem com a janela do
   emulador maximizada no monitor.
2. `b-forwarded.png` — conversa no emulador B (contato TesteB) mostrando a mensagem "ola"
   encaminhada do emulador A, com a etiqueta "↪ Forwarded | ola | 1:27 PM". Corresponde ao passo
   final do teste de encaminhamento entre três aparelhos (celular → emulador A → emulador B): a
   mensagem original "ola" foi enviada do celular para o emulador A, e de lá encaminhada por toque
   longo → "Forward to" → TesteB → Send para o emulador B, que estava com o Tor bloqueado nesse
   momento; após desbloqueado, a mensagem chegou em ~15 s já com a etiqueta "Forwarded" — etiqueta
   que aparece apenas no destino do encaminhamento, não na mensagem original.

## Nota sobre uma terceira captura omitida

Uma terceira captura de tela existia no scratchpad da sessão (`phone-now.png`, a tela de bloqueio do
celular do usuário) e foi **deliberadamente omitida** deste diretório por conter dado pessoal
(tela de bloqueio do aparelho físico do usuário).
