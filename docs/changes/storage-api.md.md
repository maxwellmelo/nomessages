# docs/development/storage-api.md

## 2026-09-14 — T4.2: contrato do contador local de não lidas

Tarefa: T4.2 de `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`.

### Como era antes

O bloco de contrato de `ChatDatabase` listava as operações de mensagem assim:

```kotlin
fun listChats(): List<ChatRecord>
fun getMessage(id: String): MessageRecord?
fun listMessages(peerOrGroup: String, beforeTimestamp: Long? = null, limit: Int = 100): List<MessageRecord>
fun insertMessage(message: MessageRecord)
fun updateMessageStatus(id: String, status: MessageStatus): Boolean
```

`MessageStatus.READ` aparecia no enum documentado, mas o documento não dizia
quem escrevia esse estado, nem que ele é local. Um leitor podia razoavelmente
supor que existia recibo de leitura no protocolo.

### Como ficou

Duas assinaturas acrescentadas ao bloco de contrato, nas posições em que estão
no código:

```kotlin
fun listChats(): List<ChatRecord>
fun unreadCounts(): Map<String, Int>
...
fun updateMessageStatus(id: String, status: MessageStatus): Boolean
fun markChatRead(peerOrGroup: String): Int
```

E uma seção nova, "Contador de não lidas (apenas local)", inserida antes do
parágrafo sobre `FileRecord.id`, registrando:

- que `READ` é estado exclusivamente local, que nenhum recibo entra ou sai pela
  rede e que nenhum tipo de envelope foi criado para isso — com a decisão
  atribuída a T4.2;
- que `unreadCounts()` é uma varredura agrupada única, atendida pelo índice
  `messages_chat_time`, com o mesmo enunciado SQL no cofre real e no cofre isca;
- que `markChatRead` só toca a direção `INCOMING`, preservando o significado dos
  tiques de entrega do remetente;
- que a estimativa de escrita é zero, portanto não consome reserva e não falha
  por capacidade esgotada;
- que a isca é semeada em `READ` por `AndroidVaultStorage.seedDecoy` e que a
  diferença de tempo observável é entre conversa nova e conversa lida — que
  existe igualmente nos dois cofres — e não entre real e isca.

### Vantagens

- O documento deixa de permitir a leitura errada de que há recibo de leitura no
  protocolo, que é exatamente a propriedade de privacidade em jogo.
- A análise de canal lateral real/isca fica registrada junto do contrato, e não
  apenas no comentário do código.
- Quem for auditar a superfície de armazenamento vê as duas operações novas na
  mesma lista das demais, sem precisar ler o Kotlin.

### Por que a mudança foi feita

Regra do roteiro: toda mudança de superfície de API é refletida no documento de
API correspondente. T4.2 acrescentou duas funções públicas a `ChatDatabase` e,
mais importante, uma garantia de privacidade que precisa estar escrita.

### Ressalva de idioma

A seção nova foi escrita em Português, conforme a regra "documentação em
Português" desta rodada, embora o restante de `storage-api.md` esteja em Inglês,
como os demais documentos de `docs/development/`. Uniformizar o arquivo inteiro
está fora do escopo de T4.2 e foi deixado como decisão do orquestrador.
