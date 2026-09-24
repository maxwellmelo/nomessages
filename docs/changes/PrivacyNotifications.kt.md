# app/src/main/kotlin/dev/mx3/nomessages/runtime/PrivacyNotifications.kt

## 2026-09-18 — T4.17 fase 3: aviso de batida aceita e notificação do processo em segundo plano

### Motivo

A campainha precisa de duas notificações com propósitos **opostos**, e confundi-las arruinaria as
duas: uma que o usuário precisa ver e ouvir ("alguém tocou, há mensagens esperando") e uma que ele
não deve nem notar (a permanente que mantém o processo `:tor` vivo em modo mínimo).

### Como era antes

Um canal só e uma notificação só:

```kotlin
private val manager = context.getSystemService(NotificationManager::class.java)
init {
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "NoMessages", NotificationManager.IMPORTANCE_DEFAULT).apply {
        lockscreenVisibility = Notification.VISIBILITY_SECRET
        setShowBadge(false)
    })
}
fun show() { ... manager.notify(1, notification) }
fun clear() { manager.cancelAll() }
companion object { private const val CHANNEL = "private_messages" }
```

### Como ficou

**`showDoorbellPending()` reusa o canal existente `private_messages`.** É exatamente o que a tarefa
pede — "importância padrão do sistema" — e o canal já é `IMPORTANCE_DEFAULT`, já tem som/vibração
padrão e já é `VISIBILITY_SECRET` na tela de bloqueio. Criar um canal novo aqui só produziria uma
segunda linha nas configurações do sistema dizendo a mesma coisa.

- **Id 2**, distinto do `1` fixo de `show()`, para as duas coexistirem em vez de uma substituir a
  outra.
- Texto fixo, de recurso: `doorbell_pending_title` (o nome do app) e `doorbell_pending_body`
  ("Você tem mensagens aguardando. Abra o NoMessages." / "You have messages waiting. Open
  NoMessages."). **Sem contagem, sem remetente, sem prévia** — quem olhar a tela do aparelho aprende
  só que o app deve ser aberto.
- `VISIBILITY_SECRET`, `setLocalOnly(true)`, `setAutoCancel(true)`, `setOnlyAlertOnce(true)`, iguais
  a `show()`.
- `cancelDoorbellPending()` é idempotente e o controller a chama em **todo** `activate()`.

**Canal novo, só para a notificação permanente do `:tor`:**

```kotlin
const val BACKGROUND_CHANNEL = "background_service"
const val BACKGROUND_NOTIFICATION_ID = 3
fun backgroundNotification(context: Context): Notification
```

| Decisão | Valor | Por quê |
|---|---|---|
| Id do canal | `background_service` | separado de `private_messages`, que é barulhento por projeto |
| Nome visível | "Segundo plano" / "Background" | aparece na lista de canais nas configurações do sistema; "Campainha" ou "Tor" descreveria o comportamento do app para quem estiver com o aparelho na mão |
| Importância | `IMPORTANCE_LOW` | sem som, sem vibração, sem peek |
| Badge | desligado | nada no ícone do launcher |
| Lockscreen | `VISIBILITY_SECRET` | igual ao resto do app |
| Título | `app_name` ("NoMessages") | o sistema obriga essa notificação a ser visível, então ela não pode dizer mais do que o ícone já diz |
| Corpo | **nenhum** | mesma convenção de corpo-zero das outras notificações |
| `ongoing` | `true` | não dispensável enquanto o modo mínimo durar |

É uma função de companion porque quem a constrói é o processo `:tor`, não o principal; criar um canal
que já existe é no-op, então não há setup entre processos.

`show()` passou a usar `R.string.app_name` no lugar do literal `"NoMessages"` e as duas notificações
compartilham um `build(title, body)` privado — mesmo resultado, uma cópia a menos.

### Vantagens

- Os dois canais não se contaminam: silenciar a permanente (o usuário pode, nas configurações do
  sistema) não silencia o aviso de batida, e vice-versa.
- Ids fixos e documentados (1 mensagens, 2 campainha, 3 foreground) tornam impossível uma colisão
  silenciosa.
- Nenhum texto novo revela a natureza do app além do que o ícone e o nome já revelam.

### Por que a mudança foi feita

T4.17 fase 3, itens 2 e 4: a notificação persistente que segura o processo em modo mínimo e o aviso
de que alguém tocou a campainha.
