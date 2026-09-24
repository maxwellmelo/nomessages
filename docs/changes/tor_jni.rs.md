# native/src/tor_jni.rs

## 2026-09-14 — T2.1: opcode de espera pela publicação

### Como era antes

A ponte JNI ia do opcode 0 ao 8 e terminava em `stop`:

```rust
8 => {
    r.finish()?;
    tor::stop()?;
    Vec::new()
}
_ => anyhow::bail!("Unknown Tor operation"),
```

O opcode 3 (`status`) só podia devolver BOOTSTRAPPING/READY/STOPPED, e não existia operação capaz de bloquear até o descritor onion ser publicado.

### Como ficou

Um único opcode novo, **acrescentado depois** do 8 para não renumerar nada:

```rust
// Readiness wait. Kept after `stop` so every pre-existing opcode keeps its number.
9 => {
    let timeout = r.u32()? as i32;
    r.finish()?;
    tor::await_ready(timeout)?.as_bytes().to_vec()
}
```

O opcode 3 continua idêntico na forma (ASCII cru), mas agora pode devolver também `PUBLISHING`, vindo de `tor::status()`.

### Vantagens

- Compatibilidade total dos opcodes existentes: nenhum chamador antigo muda de número nem de formato de resposta.
- A validação do prazo fica no Rust (`0..=300000`), e um valor grande demais vira negativo no `as i32` e é rejeitado pela mesma verificação, sem caminho especial.
- A mensagem de erro segue o padrão do arquivo: falhas nativas usam textos fixos sem dado secreto.

### Por que a mudança foi feita

T2.1 exige que o estado `PUBLISHING` e a espera pela publicação sejam acessíveis pela ponte JNI, para que o `:tor` do Android e as sondas em JVM possam distinguir "bootstrapado" de "alcançável".

## 2026-09-18 — T4.17 fase 1: opcodes 10–16 da campainha

### Motivo

A camada nativa passou a ter um segundo onion service (`native/src/doorbell.rs`). Ele precisa de uma
superfície JNI para o Kotlin ligar, alimentar com tokens, drenar eventos, entrar em modo mínimo e
parar. A convenção obrigatória do arquivo é **nunca renumerar opcode existente**: os novos entram no
fim, a partir de 10.

### Como era antes

Um único `match operation` com 0–9 e o `_ => bail!("Unknown Tor operation")`. Um só par de mensagens
fixas de erro:

```rust
Ok(Err(_)) => {
    let _ = env.throw_new("java/lang/IllegalStateException", "Tor operation failed");
    std::ptr::null_mut()
}
Err(_) => {
    let _ = std::panic::catch_unwind(|| { let _ = tor::stop(); });
    let _ = env.throw_new("java/lang/IllegalStateException", "Tor operation failed");
    std::ptr::null_mut()
}
```

### Como ficou

Sete braços novos, cada um com o mesmo padrão dos antigos: ler os campos com `Reader`, `r.finish()?`
(que recusa bytes sobrando), chamar a função e devolver bytes.

| # | nome sugerido no Kotlin | argumentos (wire) | retorno |
|---|---|---|---|
| 10 | `doorbellStart` | `seed[32]`, `stateDir`, `cacheDir`, lista de pontes | endereço onion (UTF-8, 62 bytes) |
| 11 | `doorbellOnion` | — | endereço onion (UTF-8, 62 bytes) |
| 12 | `doorbellTokens` | lista de ≤256 blobs de 16–64 bytes | vazio |
| 13 | `doorbellPoll` | `timeoutMs` u32 (0..=30000) | vazio se nada tocou, senão u32 big-endian com a contagem |
| 14 | `doorbellMinimal` | — | vazio |
| 15 | `doorbellStop` | — | vazio |
| 16 | `doorbellKnock` | `onion[62]`, `token` (≤64) | 1 byte: `1` reconhecido, `0` não |

Detalhes que valem registro:

- **10 espelha o opcode 1** (mesma forma de argumentos) porque a campainha pode precisar construir o
  host Arti sozinha, quando é iniciada sem o transporte de mensagens (processo reaproveitado com o
  cofre já bloqueado). Quando o transporte existe, os diretórios são ignorados e o host dele é
  reusado — ver `docs/changes/tor.rs.md`.
- **11 espelha o opcode 2** e é também a forma mais barata de o Kotlin perguntar "a campainha está
  de pé?": ele falha quando ela não está.
- **13 espelha o opcode 5** na convenção "vazio = nada", mas devolve uma contagem em vez de um frame.
  O evento é opaco de propósito: sem token, sem contato, sem timestamp. Um dreno colapsa uma rajada
  numa notificação só.
- **16** consome o token dentro do nativo (`Zeroizing`), monta o frame de 57 bytes e devolve só um
  booleano. O token nunca volta para o Kotlin.

Mensagens de erro continuam **fixas e sem interpolar nada**, agora escolhidas pela faixa do opcode:

```rust
// Fixed strings only, chosen by opcode range. No error, token, knock or address byte ever
// reaches a Java exception message.
let (failure, unavailable) = if (10..=16).contains(&operation) {
    ("Doorbell operation failed", "Doorbell output unavailable")
} else {
    ("Tor operation failed", "Tor output unavailable")
};
```

E o braço de pânico passou a derrubar os **dois** serviços:

```rust
Err(_) => {
    // A panic leaves unknown state behind, so both services go down, not just the one
    // whose opcode panicked.
    let _ = std::panic::catch_unwind(|| {
        let _ = doorbell::stop();
        let _ = tor::stop();
    });
    // ...
}
```

### Por que o pânico derruba também a campainha

Um pânico significa estado desconhecido no processo `:tor`. Manter a campainha de pé depois dele
seria manter um serviço de rede aberto num processo que acabou de provar estar inconsistente. O
Kotlin já trata `IllegalStateException` como "reiniciar do zero"; derrubar tudo mantém esse contrato.

### Vantagens

- Nenhum opcode antigo mudou de número nem de comportamento — `TorNative.kt` continua válido sem
  tocar em nada.
- A faixa 10–16 é autodocumentada no próprio `match`, no mesmo estilo do comentário que já existia no
  opcode 9.
- Nenhum dado do knock, do token, do endereço ou do erro pode vazar por uma mensagem de exceção.

### Pendência para a fase de documentação

`docs/development/native-api.md` ainda não descreve estes sete opcodes; a tabela acima é o insumo
pronto para essa fase.
