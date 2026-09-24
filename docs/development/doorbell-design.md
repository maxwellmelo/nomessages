# Campainha: aviso de mensagens pendentes com o app bloqueado

Data: 2026-09-17. Estado: aprovado pelo usuário; implementação prevista como T4.17, após o QR de pareamento v2 (T4.16), que já reserva os campos necessários.

## Problema

O bloqueio do NoMessages é criptográfico: fecha o banco, apaga as chaves de sessão e a chave do banco, e encerra o processo `:tor`. Consequência: enquanto o destinatário está bloqueado, o remetente mantém a fila e nada chega. O destinatário não tem como saber que há mensagens esperando, a não ser abrindo o app. O usuário decidiu manter esse modelo de bloqueio (nada de "sempre conectado") e pediu um aviso que não exponha conteúdo nem privacidade.

## Desenho

1. **Segundo endereço onion, só para campainha.** Cada cofre possui uma seed própria (`doorbell_seed`, distinta da seed do onion de mensagens) que deriva um onion v3 estável. O endereço vai no QR de pareamento v2 (`doorbellOnion`).
2. **Token por contato.** Cada Offer de pareamento carrega 32 bytes aleatórios (`doorbellToken`). Cada lado guarda o token emitido pelo outro (para tocar) e o conjunto dos tokens que emitiu (para verificar). Ambos entram no canônico assinado e no transcript do SAS.
3. **Ao bloquear:** o serviço de mensagens morre como hoje. Permanece apenas um ouvinte mínimo no onion de campainha, com dois dados em memória: a chave desse onion e o conjunto de tokens emitidos. Nenhuma chave de sessão, nenhuma chave de banco.
4. **Ao tocar:** quando há fila para um contato e o onion de mensagens dele está inalcançável, o remetente conecta ao onion de campainha e envia `HMAC(token, carimbo de tempo)`; nada mais. Uma batida por contato a cada N minutos, com backoff.
5. **Ao receber a batida:** verificar o HMAC contra o conjunto de tokens, aceitar apenas carimbo dentro de uma janela curta e nonce não repetido, descartar em silêncio qualquer batida inválida. Mostrar uma notificação sem remetente, sem conteúdo e sem contagem: "Você tem mensagens aguardando. Abra o NoMessages."
6. **Senha de pânico:** derruba tudo e sobe a campainha do cofre-isca, que possui os mesmos mecanismos, para não existir diferença observável.
7. **Configuração:** opção por cofre, presente igualmente no real e na isca.

## O que fica exposto com o aparelho apreendido bloqueado

- Chave do onion de campainha: não lê nem envia mensagens; permite apenas responder a batidas.
- Quantidade de tokens (número de contatos), sem identidades.
- Nada do conteúdo, das sessões ou do banco.

## Texto da configuração (PT-BR, com paridade em EN)

Título: **Aviso de mensagens pendentes**

- Ligado: "Com o app bloqueado, um canal separado avisa que há mensagens aguardando. Nenhuma mensagem, contato ou chave fica acessível nesse estado."
- Desligado: "Com o app bloqueado, nada fica ligado à rede. Você recebe as mensagens ao abrir o app."
- Linha de contexto: "Nos dois modos, o conteúdo só existe dentro do cofre cifrado."

Diretriz de redação (pedido do usuário): os dois modos são legítimos e seguros; nunca descrever um deles como "menos seguro", "bloqueio total" ou termos que sugiram fragilidade do app.

## Impacto no checklist de release

Gate 10 passa a ser: "o bloqueio encerra o serviço de mensagens e apaga as chaves de sessão e do banco; com o aviso ligado, apenas o onion de campainha permanece, com chave e tokens próprios". A evidência do gate deve mostrar os dois modos.

## Custos conhecidos

- Tor ativo em segundo plano com serviço em primeiro plano e notificação discreta; consumo moderado de bateria.
- Mudanças em: pareamento (campos já reservados na v2), storage (`doorbell_seed`, `doorbell_token` por contato), TorService (segundo serviço onion e modo mínimo), MessagingEngine (toque com backoff), notificações, configurações, SPEC, security-model, release-checklist.
