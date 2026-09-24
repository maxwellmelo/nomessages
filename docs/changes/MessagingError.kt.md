# app/src/main/kotlin/dev/mx3/nomessages/runtime/MessagingError.kt

## 2026-09-23 — Arquivo novo: `MessagingErrorCode`/`MessagingError` (T4.6)

### Motivo

`:app:lintDebug` (achado registrado em `docs/development/build-report.md`, seção "Lint
(2026-09-14)") apontava ~20 strings `R.string.error_*` sem nenhum chamador (`UnusedResources`). A
causa raiz: `MessagingEngine.kt` lançava `IllegalArgumentException`/`IllegalStateException` com texto
em português cru (`require(...) { "Digite uma mensagem" }`, `error("Arquivo indisponível")` etc.), e
todo catch em `NoMessagesController.kt` ao redor de uma chamada ao motor descartava
`Throwable.message` por completo, mostrando sempre o mesmo texto genérico
(`error_operation_failed`/`error_attachment_limit`/`error_attachment_send`/`error_audio_finish`,
dependendo do call site) — nunca o texto específico, que por sua vez nunca existia como recurso
localizado, só como literal PT dentro do `require`/`error`.

### Como ficou

Arquivo novo, tipo puro (sem `Context`, sem `R.string`, sem dependência de Android) para manter
`runtime/` livre de acoplamento com recursos: `MessagingEngine`/`MemoryAudioRecorder` continuam sem
saber que existe uma tela; só sabem que existe uma **razão** com nome.

```kotlin
enum class MessagingErrorCode {
    EMPTY_MESSAGE, FILE_TOO_LARGE, FILE_UNAVAILABLE, FILE_INVALID, NO_OTHER_MEMBERS,
    GROUP_SYNC_FAILED, GROUP_WAITING_ONLINE, LEFT_GROUP, CONTACT_UNAVAILABLE, CONTACT_UNPAIRED,
    GROUP_UNAVAILABLE, DUPLICATE_FILE, EXISTING_FILE_MISMATCH, NO_AUDIO_RECORDED,
}

class MessagingError(val code: MessagingErrorCode, message: String) : IllegalStateException(message)
```

`NoMessagesController` (o único lugar com `Context`) é o único código que lê `code` e escolhe o
`R.string.error_*` certo, bem antes de mostrar — ver `docs/changes/NoMessagesController.kt.md`.

### Por que só estes 14 códigos, e não um para cada `error_*` do lint

O achado listava ~20 strings. Catorze descrevem uma falha alcançável a partir de uma ação do usuário
(`sendText`, `sendAttachment`, `decryptAttachment`, `removeMember`, `leaveGroup`, gravação de áudio) —
essas ganharam código e string conectados. As demais se dividem em dois grupos, nenhum dos quais
ganhou código aqui:

- **Sem caminho até a UI de jeito nenhum:** `accept`/`handle`/`acceptKeyPackage`/`acceptInvite`/
  `acceptCommit`, chamados só pelo laço de recebimento (`receiveLoop`), processam quadro de rede não
  confiável e têm suas exceções **sempre** engolidas ali mesmo (`catch (_: Exception) { ... }`), por
  desenho — para um quadro malformado de um peer nunca virar oráculo. Dar um código a esses só criaria
  a aparência de que algo os lê; nada lê. `error_unauthenticated_message`, `error_invalid_message`,
  `error_unknown_group` e `error_invalid_pairing_proof` foram removidos de `strings.xml` por isso (ver
  `docs/changes/strings.xml.md`); `error_invalid_membership_change` nunca teve sequer um literal
  correspondente no código — removido também.
- **Duplicata de um texto já correto no mesmo call site:** `error_file_over_limit`/
  `error_file_eight_mib` descrevem o mesmo limite de 8 MiB que `error_attachment_limit` já cobre nos
  mesmos pontos de chamada (`SensitiveBuffer`/`sendAttachment`), com um texto já melhor ("Não foi
  possível anexar. O limite é de 8 MiB."). `MessagingErrorCode.FILE_TOO_LARGE` mapeia para
  `error_attachment_limit` (ver `docs/changes/NoMessagesController.kt.md`) em vez de introduzir uma
  string nova que diria a mesma coisa; as duas strings redundantes foram removidas.

### Vantagens

- Fecha o achado do lint sem acoplar `MessagingEngine`/`MemoryAudioRecorder` (JVM/lógica) a
  `android.content.Context`/`R.string` (Android/apresentação) — a tradução de código para texto
  localizado fica inteiramente em `NoMessagesController`, que já é o único dono de `Context` nessa
  cadeia.
- Uma falha real ganha um texto específico e útil ("Não há outros membros" em vez de "Não foi
  possível concluir a operação") sem exigir comparação de string frágil em nenhum catch.
- O que **não** tinha caminho até a UI foi removido em vez de fingido conectado — a alternativa
  (inventar uma UI só para consumir uma string órfã) estava fora do escopo de um achado de lint.

Ver também `docs/changes/MessagingEngine.kt.md`, `docs/changes/MemoryAudioRecorder.kt.md`,
`docs/changes/NoMessagesController.kt.md` e `docs/changes/strings.xml.md`.

## 2026-09-23 — `MEDIA_CAPACITY_EXHAUSTED` (T4.1)

Um código novo, adicionado ao `enum class MessagingErrorCode` desta mesma tarefa de limpeza de lint
(T4.6) para a reserva fixa de mídia de T4.1:

```kotlin
/** [MessagingEngine.sendAttachment]/[MessagingEngine.storeAttachment]: this slot's fixed media
 * reservation (`AndroidVaultStorage.mediaCapacityBytes`, T4.1) is full. */
MEDIA_CAPACITY_EXHAUSTED,
```

Lançado por `MessagingEngine.storeAttachment` quando a escrita levaria o slot que está crescendo
acima de `mediaCapacityBytes`, e mapeado por `NoMessagesController.errorMessage` para
`R.string.error_media_capacity`. Ver `docs/changes/MessagingEngine.kt.md`,
`docs/changes/NoMessagesController.kt.md` e `docs/security-model.md` ("Fixed media reservation and
blind cover growth", T4.1).
