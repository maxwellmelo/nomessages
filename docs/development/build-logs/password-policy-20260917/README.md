# Verificação manual — política de senha com símbolos (T4.10)

**Data:** 2026-09-17. **Aparelho:** `emulator-5556` (AVD `nomessages35`, API 35 x86_64).
**Build:** `app-debug.apk` gerado por `:app:assembleDebug` após `:core:test`, `:app:testDebugUnitTest`
e `:app:lintDebug` passarem (ver `docs/changes/PasswordPolicy.kt.md`, `PasswordStrength.kt.md`,
`SetupLockScreens.kt.md`, `SettingsScreen.kt.md`, `Components.kt.md`, `strings.xml.md`).
**Captura:** `debug.nomessages.allow_capture=1` e `settings put global hidden_api_policy 1`
aplicados antes de reabrir o app (pacote de debug instalado: `dev.mx3.nomessages.debug`).

Fluxo executado na tela **Create your vault** (locale do aparelho: inglês — `values-en/strings.xml`;
paridade PT confirmada por leitura direta de `values/strings.xml`, ver docs citados acima), digitando
com `adb shell input text`/`input keyevent` (sem gravar em log a senha real em texto puro fora deste
documento, conforme pedido).

## Capturas

Capturas 1-4 (tela vazia com o novo texto de requisito, medidor de força com dicas, e a validação
ao vivo de confirmação de senha divergindo/batendo) foram removidas deste diretório por mostrarem o
monograma/ícone de uma versão anterior do app; o conteúdo textual observado está resumido abaixo:

1. Tela vazia, mostrando o novo texto de requisito: "Use at least 12 characters. Symbols and
   accented letters are allowed." (era "Use at least 16 alphanumeric characters.").
2. Senha real digitada no campo "Main password": medidor de 4 segmentos preenchendo 3/4, rótulo
   **Good**, duas dicas ("Avoid common words", "Avoid dates or years") — a senha contém a palavra
   comum "cofre" (lista embutida PT/EN) e o ano "2026", exatamente como o estimador de
   `core/.../PasswordStrength.kt` prevê.
3. Campo "Confirm main password" com um valor diferente: "Passwords do not match" em vermelho,
   atualizado a cada tecla, sem esperar o envio do formulário.
4. Mesmo campo corrigido: "Passwords match" em verde.
5. `05_panic_too_similar_warning_button_disabled.png` — campo "Panic password" preenchido com uma
   variação trivial da senha real (mesmo prefixo, um caractere final diferente — distância de edição
   1): aviso "The panic password is too similar to the real password." em vermelho, e o botão
   "Create vault" permanece desabilitado (cinza) mesmo com os dois campos de senha preenchidos e
   confirmados.
6. `06_panic_strength_strong_after_fix.png` — senha de pânico corrigida para um valor genuinamente
   distinto: medidor cheio (4/4), rótulo **Strong**, aviso de similaridade desaparece.
7. `07_all_valid_create_vault_enabled.png` — os quatro campos de senha preenchidos e válidos
   (força ≥ "Good" nos dois, confirmações batendo, sem aviso de similaridade): botão "Create vault"
   passa a sólido/habilitado.
8. `08_vault_created_app_opened.png` — resultado do toque em "Create vault": Argon2id roda (overlay
   "Working…" nas capturas intermediárias, não incluídas aqui) e o app abre normalmente na tela
   principal do NoMessages (diálogo padrão do Android pedindo permissão de notificação por cima),
   confirmando que o cofre foi criado com sucesso com as duas senhas abaixo.

## Senhas usadas nesta verificação (registradas só aqui, por pedido do usuário)

- Senha real: `Cofre#Real-2026!x`
- Senha de pânico: `Panico*Isca-9!qz`

## Observação sobre a automação (não é um bug do app)

Ao dirigir a UI via `adb shell input tap`, coordenadas calculadas a partir de um dump do
`uiautomator` tirado **enquanto o teclado estava aberto e a rolagem ainda estava se ajustando**
(por exemplo, logo após o medidor de força ou a dica de confirmação mudarem de altura) toques em
`(x, y)` esperados landavam no campo ainda focado, não no campo-alvo, porque a árvore de
acessibilidade não refletia a posição pós-rolagem no momento do toque. A correção usada foi sempre
fechar o teclado (`KEYCODE_BACK`) antes de calcular coordenadas de um novo campo, tirar o dump/
screenshot com o teclado fechado (layout estável, sem `imePadding`), e só então tocar — o toque
reabre o teclado e o foco fica correto. Isso é uma característica da automação via `uiautomator`
sobre Compose com rolagem + `imePadding`, não um problema de acessibilidade do app em si.
