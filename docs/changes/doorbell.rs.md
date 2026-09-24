# native/src/doorbell.rs

## 2026-09-18 — T4.17 fase 1 (nativo): a campainha, um segundo onion service que sobrevive ao bloqueio

Arquivo **novo**. Não há "como era antes": até aqui a camada nativa só conhecia um onion service,
o de mensagens (`native/src/tor.rs`), e o bloqueio do cofre matava o processo `:tor` inteiro
(`TorService.kt`, `Process.killProcess`). Um contato que tentasse falar com um aparelho bloqueado
simplesmente não encontrava nada — nem sabia que havia alguém do outro lado.

### Por que o módulo existe

Com o cofre bloqueado, o onion de mensagens é destruído de propósito: é ele que carrega as chaves de
sessão, os circuitos por contato e o estado do MLS. A campainha resolve o problema **sem tocar em
nada disso**: um segundo onion service, mínimo, que só sabe responder "alguém bateu". Ela não
decifra, não guarda mensagem, não abre sessão e não sabe *quem* bateu — só que uma batida
autenticada chegou.

### Por que um módulo separado, e não mais código dentro de `tor.rs`

1. **Superfície de ataque.** `tor.rs` tem estado de sessão, fila de frames de aplicação, mapa de
   conexões e clientes isolados por par. A campainha roda com o cofre bloqueado, que é exatamente o
   momento em que o aparelho está mais exposto. Mantê-la num módulo próprio garante, por construção,
   que o caminho que roda bloqueado não alcança nenhuma dessas estruturas.
2. **Ciclo de vida independente.** A campainha precisa continuar de pé quando o transporte de
   mensagens cai, e vice-versa. Dois módulos com dois estados estáticos (`CURRENT` em `tor.rs`,
   `DOORBELL` aqui) tornam isso explícito em vez de depender de flags dentro de um `Transport`.
3. **Testabilidade.** O verificador é lógica pura com relógio injetado; ele compila e roda sem Tor,
   sem rede e sem runtime. Se ele vivesse dentro de `Transport`, testar a janela de tempo exigiria
   um onion service de verdade.

### O frame da batida

```
versão(1) || timestamp u64 big-endian(8) || nonce(16) || HMAC-SHA256(token, versão||timestamp||nonce)(32)
```

= **57 bytes, tamanho fixo**, um único frame por conexão. Qualquer outro tamanho é inválido.

```rust
pub const KNOCK_LEN: usize = 1 + 8 + NONCE_LEN + MAC_LEN;   // 57
const SIGNED_LEN: usize = KNOCK_LEN - MAC_LEN;              // 25 bytes sob o HMAC
```

- **Tamanho fixo, sem campo de comprimento.** Um campo de comprimento seria mais um estado de parser
  para errar, e um frame de tamanho variável vazaria informação pelo próprio tamanho. 57 bytes é 57
  bytes: qualquer coisa diferente morre antes de qualquer verificação criptográfica.
- **A versão está dentro do HMAC.** Subir a versão invalida automaticamente todo frame antigo; não
  existe downgrade silencioso.
- **O timestamp é big-endian**, igual ao resto do wire do projeto (`native/src/wire.rs`).

### As quatro defesas, e por que cada uma existe

```rust
pub fn verify(&mut self, frame: &[u8], now: u64) -> bool
```

| defesa | valor | por quê |
|---|---|---|
| janela de tempo | ±300 s (inclusive) | Um frame capturado deixa de valer em 5 minutos. Sem ela, um HMAC gravado hoje tocaria a campainha para sempre. |
| cache de nonces | TTL 600 s | Impede replay *dentro* da janela. O TTL é maior que a janela de propósito: o frame expira por timestamp antes de o nonce ser esquecido, então não existe intervalo em que ele volte a ser aceito. |
| limite de taxa | 1 aceita por token / 600 s | Sem isso, um contato (ou quem roubou o token dele) faria o aparelho bloqueado tocar em laço — bateria, notificação e um canal lateral de tráfego. |
| HMAC em tempo constante | — | Ver abaixo. |

O relógio é **injetado** (`now: u64`), nunca lido de `SystemTime::now()` dentro do verificador. É a
mesma ideia do clock injetável de `TransportSupervisor.kt` do lado Kotlin, e é o que permite testar
os 10 minutos do limite de taxa sem esperar 10 minutos.

### Comparação em tempo constante e varredura completa dos tokens

```rust
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() { return false; }
    let mut difference = 0u8;
    for (x, y) in a.iter().zip(b.iter()) { difference |= x ^ y; }
    std::hint::black_box(difference) == 0
}
```

`==` de slices tem short-circuit no primeiro byte diferente, o que transforma a comparação da tag
num oráculo byte a byte. O acumulador XOR compara sempre os 32 bytes; o `black_box` impede o
otimizador de reintroduzir a saída antecipada.

O laço que procura o token **não tem `break`**:

```rust
for (index, token) in self.tokens.iter().enumerate() {
    if let Some(expected) = tag(token, signed) && constant_time_eq(&expected, mac) {
        matched = Some(index);
    }
}
```

Todos os tokens são testados sempre. Assim o tempo gasto não revela a posição do batedor na lista —
nem se algum token bateu. O custo é trivial (no máximo 256 HMAC-SHA256 por batida).

### Por que HMAC-SHA256 via `hmac` + `sha2`, e não libsodium

O projeto já usa `libsodium-rs` com a feature `minimal`, que **não** expõe HMAC-SHA256. As crates
`hmac 0.12.1` (via `tor-proto`) e `sha2 0.10.9` (via `tor-llcrypto`) já eram compiladas pela feature
`tor`, que é default-on — elas apenas foram promovidas a dependências diretas no `Cargo.toml`,
fixadas exatamente nas versões que o `Cargo.lock` já resolvia. O diff do lockfile confirma: nenhum
pacote novo, nenhuma versão mudou, só a lista de dependências diretas de `nomessages` ganhou duas
linhas. Alternativa rejeitada: trocar a feature do libsodium — isso sim mudaria o grafo e o binário
Android.

### Comportamento na conexão: recusa muda, aceitação de um byte

```rust
async fn serve_knock<S>(mut stream: S, verifier: Arc<Mutex<Verifier>>, events: Sender<()>)
```

Todo caminho de recusa fecha a conexão **sem escrever nenhum byte**: tamanho errado, versão errada,
token desconhecido, timestamp fora da janela, replay e limite de taxa são indistinguíveis de fora.
Só uma batida totalmente válida recebe `0x01`. É o que impede o batedor de descobrir *qual*
verificação falhou — em particular, de descobrir se um token é conhecido pelo aparelho.

O frame "grande demais" é tratado com uma sondagem de 50 ms depois dos 57 bytes: bytes que chegaram
*junto* com o frame reprovam a batida, sem obrigar uma batida honesta a esperar um fim de fluxo que
nunca vem (o batedor legítimo mantém o fluxo aberto esperando o ACK).

O lock do verificador é tomado e solto de forma síncrona, nunca atravessa um `await`.

### O evento entregue ao Kotlin é opaco

A fila é `mpsc::Sender<()>`: unidades, não estruturas. Não há token, não há índice de contato, não há
timestamp. O Kotlin só consegue saber *quantas* batidas foram aceitas desde o último dreno — que é
tudo que ele precisa para mostrar uma notificação. `try_send`, não `send`: uma fila cheia já carrega
a mesma notificação que este evento carregaria, e bloquear ali deixaria um inundador prender um slot
de conexão.

### Estado que sobrevive de propósito

```rust
static VERIFIER: OnceLock<Arc<Mutex<Verifier>>> = OnceLock::new();
```

O verificador vive fora de qualquer geração da campainha. Parar e reiniciar o serviço **não** zera o
cache de nonces nem o livro do limite de taxa — senão "stop; start" seria um bypass grátis das duas
defesas. Pelo mesmo motivo, `set_tokens` troca a lista de tokens mas preserva os dois mapas: parear
um contato novo é rotina e não pode virar botão de reset. O limite de taxa é indexado por
`SHA-256(token)`, não pela posição na lista, exatamente para sobreviver a uma recarga que reordene os
tokens.

Os dois mapas só crescem em **aceitação**, nunca em recusa. Como a aceitação é limitada a 1 por token
a cada 10 minutos e há no máximo 256 tokens, o cache é trivialmente limitado — um inundador não
consegue fazer a memória crescer. A limpeza é preguiçosa (`expire`, chamado em toda verificação): não
há thread de varredura.

### Porta virtual própria

`DOORBELL_PORT = 4243`, distinta da porta de mensagens (4242). Mesmo que os dois serviços rodem no
mesmo cliente Arti, uma sondagem de um nunca pode ser respondida pelo outro.

### `knock`: o lado que bate

Além de receber, o módulo também **envia** batidas (`pub fn knock(onion, token) -> Result<bool>`). O
token é consumido no nativo: o Kotlin entrega o blob, o frame é montado aqui, o nonce vem de
`crate::crypto::random` (libsodium), e o resultado que volta é um booleano. Cada batida usa um
`isolated_client()` novo, para que o circuito da campainha não fique ligado aos circuitos de mensagem
do mesmo contato.

### Vantagens

- O aparelho bloqueado deixa de ser um buraco negro: o contato sabe que a batida chegou, e o dono
  sabe que alguém bateu — sem que uma única chave de sessão volte à memória.
- A lógica sensível (janela, replay, taxa, tempo constante) é testável offline e está coberta por 18
  testes que rodam em 0,4 s, sem rede.
- Nenhuma nova dependência entrou no grafo de build.
- Nenhum motivo de recusa é observável de fora.

### Testes (`#[cfg(test)] mod tests`, mesmo padrão de `tor.rs`)

Aceitação com token conhecido; token desconhecido e MAC adulterado; conjunto vazio; janela nos dois
sentidos; bordas da janela (exatamente ±300 s, ±299 s, ±301 s); replay do mesmo nonce; limite de taxa
com nonce e timestamp novos; liberação do limite após 10 minutos de relógio injetado; recarga de
tokens não reseta o limite; frames curtos/longos/versão errada sem pânico; limites do conjunto de
tokens; a comparação em tempo constante contra `==`; e dois testes `#[tokio::test]` sobre
`serve_knock` com `tokio::io::duplex`, cobrindo o ACK de uma batida válida, o silêncio total de uma
inválida e a recusa de um frame com byte sobrando.

## 2026-09-18 — `shutdown` esquecia de esquecer a chave da campainha (T4.19)

Correção simétrica à de `tor.rs` (o registro completo da causa raiz está em
`docs/changes/tor.rs.md`, seção de 2026-09-18). Aqui fica só o que é específico da campainha.

### Por que a campainha também precisava, e não é simetria decorativa

`launch_onion_service_with_hsid` **insere** o par de chaves HsId no keystore do `TorClient`
compartilhado, sob o nickname do serviço, e recusa sobrescrever uma entrada existente. `shutdown`
soltava o `RunningOnionService` e deixava a entrada "nomessages-doorbell" para trás.

Enquanto o filho `:tor` morria a cada desbloqueio, isso nunca aparecia: o processo novo começava com
um keystore vazio. A correção de `tor.rs` é exatamente o que tira essa rede de proteção — o `:tor`
passa a atravessar os ciclos vivo. E `NoMessagesController.kt:369` chama `doorbellStop()` em **todo**
desbloqueio, assim que o serviço de mensagens adota o host. Logo, com só metade da correção aplicada,
o **segundo** bloqueio do processo chamaria `doorbell::start` sobre a chave deixada pelo primeiro e a
campainha simplesmente não abriria — trocaríamos um defeito no ciclo 1 por outro no ciclo 2.

### Antes

```rust
fn shutdown(doorbell: &Arc<Doorbell>) {
    doorbell.cancel.send_replace(true);
    if let Ok(mut service) = doorbell.service.lock() {
        service.take();
    }
}
```

### Depois

```rust
const NICKNAME: &str = "nomessages-doorbell";

fn shutdown(doorbell: &Arc<Doorbell>) {
    doorbell.cancel.send_replace(true);
    if let Ok(mut service) = doorbell.service.lock() {
        service.take();
    }
    let _ = tor::forget_onion_key(&doorbell.host, NICKNAME);
}
```

`launch` passou a ler o nickname da mesma const, em vez do literal inline, para que lançamento e
remoção não possam divergir.

### Best effort, de propósito

`shutdown` não devolve `Result`, e não deveria: `stop` tem de derrubar a campainha diga o keystore o
que disser — meia campainha (onion vivo, sem observador) é pior que campainha nenhuma, e o comentário
que já existia em `start` diz isso. Como a chave é derivável da seed do cofre, uma falha aqui custa,
no pior caso, um relançamento futuro da campainha; nunca um dado.

### Segurança e identidade

A entrada é derivada deterministicamente da seed da campainha, a mesma de onde sai o endereço
publicado nas ofertas de pareamento. Removê-la e reinseri-la devolve o mesmo endereço — o que
importa aqui mais até que no transporte, porque `start` recusa explicitamente um endereço diferente
("Doorbell already started" com `ensure!(running.onion == onion)`), para não rebindar sob os pares.
Só o nickname passado é tocado: a campainha nunca mexe na identidade do serviço de mensagens.

### Validação

Coberto pelo mesmo teste offline de `tor.rs`
(`relaunching_a_nickname_needs_its_key_forgotten_first`), que verifica justamente que esquecer um
nickname não mexe no outro. Ao vivo: `docs/development/build-logs/doorbell-20260918/`,
arquivos `27-B-pid-stable-after-fix.txt` (cinco ciclos de lock/unlock com a campainha abrindo e
fechando em cada um, no mesmo processo) e `28-B-acceptance-after-fix.txt` (sexto ciclo, com batida
real, aviso id=2 e entrega).

### Vantagens

- A campainha pode abrir e fechar quantas vezes forem necessárias dentro de um processo, que é o que
  o ciclo de bloqueio/desbloqueio agora exige dela.
- Keystore e serviços onion andam em passo dos dois lados: quem derruba o serviço derruba a chave.
- O nickname deixou de ser literal solto e virou const.
