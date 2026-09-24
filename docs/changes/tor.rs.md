# native/src/tor.rs

## 2026-09-14 — T2.1: esperar a publicação do descritor onion

### Como era antes

O estado do transporte era um `AtomicU8` com três valores mágicos e `start` declarava o serviço pronto assim que `launch_onion_service_with_hsid` retornava:

```rust
pub fn status() -> &'static str {
    match current().map(|s| s.state.load(Ordering::Acquire)) {
        Ok(1) => "BOOTSTRAPPING",
        Ok(2) => "READY",
        _ => "STOPPED",
    }
}
// ...
ensure!(!*transport.cancel.borrow(), "Transport stopped");
transport.state.store(2, Ordering::Release);
```

`send` exigia `state == 2`. Não havia nenhuma forma de esperar pela publicação do descritor: o `launch` apenas inicia a montagem dos pontos de introdução e o upload do descritor, de modo que "READY" era uma afirmação falsa durante vários minutos.

### Como ficou

- Os códigos viraram constantes nomeadas (`STATE_STOPPED`/`BOOTSTRAPPING`/`READY`/`PUBLISHING`) com `state_name` para a conversão, e `STATE_STOPPED` é terminal.
- `Transport` ganhou `state: Arc<AtomicU8>` (compartilhado com o observador) e `ready: watch::Sender<bool>`, que espelha READY para que quem espera estacione em vez de fazer polling no átomo.
- Depois do `launch`, `start` publica `STATE_PUBLISHING` e cria um observador da API real do `tor-hsservice` 0.46:

```rust
transport.state.store(STATE_PUBLISHING, Ordering::Release);
transport.handle.spawn(async move {
    tokio::pin!(status_events);
    loop {
        let status = tokio::select! { /* cancel | status_events.next() */ };
        let reachable = status.state().is_fully_reachable();
        advance_state(&status_state, if reachable { STATE_READY } else { STATE_PUBLISHING });
        if status_state.load(Ordering::Acquire) != STATE_STOPPED {
            status_ready.send_replace(reachable);
        }
    }
    status_ready.send_replace(false);
});
```

- `advance_state` usa `compare_exchange` em laço para nunca ressuscitar um transporte já parado: um evento de status atrasado, chegando depois de `stop`, não pode reabrir a geração destruída.
- Nova função pública `await_ready(timeout_ms: i32) -> Result<&'static str>`, com orçamento próprio de 300 s (`MAX_READY_TIMEOUT_MS`), independente dos 180 s de bootstrap. Retorna o estado alcançado: `"READY"` no sucesso e `"PUBLISHING"` se o prazo esgotou (não é falha — a publicação continua). Falha com `Transport stopped` quando o `cancel` dispara, que é como o lock aborta a espera.
- `send` passou a aceitar `READY | PUBLISHING`, com comentário explicando o motivo: um rendezvous de saída depende do cliente bootstrapado, não do nosso próprio descritor; só a alcançabilidade de entrada exige READY.
- `shutdown_transport` zera o `watch` de prontidão além do estado.
- Dois testes unitários novos: nomes/monotonicidade dos estados (incluindo o caso STOPPED terminal) e validação dos limites de `await_ready`.

### Vantagens

- O estado exposto passa a ser verdadeiro: `READY` só aparece quando o `tor-hsservice` afirma `is_fully_reachable()` (`State::Running` ou `State::DegradedReachable`), isto é, descritor atualizado e pontos de introdução satisfeitos.
- O gate ao vivo deixa de medir a paciência do teste e passa a medir o transporte: a espera pela publicação é explícita e cronometrada.
- A reversão READY → PUBLISHING é suportada, então uma degradação posterior da alcançabilidade é visível em vez de ficar congelada em "pronto".
- O orçamento separado evita que um bootstrap lento consuma o prazo da publicação (e vice-versa).

### Por que a mudança foi feita

T2.1 do roteiro `docs/superpowers/plans/2026-09-14-roadmap-to-release.md`: a hipótese (a) da falha do gate Tor ao vivo é justamente a ausência de espera pelo descritor. Sem um estado intermediário real, a UI e as sondas anunciavam prontidão antes de o aparelho poder ser encontrado.

## 2026-09-18 — T4.17 fase 1: runtime e cliente Arti compartilhados (`TorHost`), para a campainha sobreviver ao bloqueio

### Motivo

A fase 1 da campainha (`native/src/doorbell.rs`) exige que o onion service de mensagens e o de
campainha tenham **ciclos de vida independentes dentro do mesmo processo**: `lock()` derruba
mensagens e mantém a campainha ("modo mínimo"), e parar a campainha não pode derrubar mensagens.

O reconhecimento inicial supôs que bastaria a campainha guardar um `Arc::clone` do `TorClient` do
`Transport`. **Isso não funciona**, e o motivo está no código antigo:

```rust
fn shutdown_transport(s: Arc<Transport>) -> Result<()> {
    // ...
    if let Some(runtime) = s.runtime.lock()...take() {
        runtime.shutdown_timeout(Duration::from_secs(2));
    }
    Ok(())
}
```

O `TorClient` do Arti é construído dentro de `runtime.enter()` e fica preso ao runtime tokio que o
`Transport` era dono. Derrubar o `Transport` derrubava o runtime, e um `Arc<TorClient>` clonado
sobreviveria como um objeto morto: sem executor, sem circuitos, sem nada. Um segundo `TorClient`
próprio também não serve — o Arti trava o diretório de estado, e dois clientes sobre os mesmos
diretórios falham na criação.

A correção é mover a posse do runtime e do cliente para um host contado por referência.

### Como era antes

```rust
struct Transport {
    runtime: Mutex<Option<Runtime>>,
    handle: Handle,
    client: Arc<Client>,
    onion: String,
    // ...
}
```

`start` construía config, runtime e cliente inline, e `shutdown_transport` fazia
`runtime.shutdown_timeout(...)` na mão.

### Como ficou

```rust
/// O runtime tokio mais o cliente Arti sobre os quais todo onion service deste processo roda.
pub(crate) struct TorHost {
    runtime: Mutex<Option<Runtime>>,
    pub(crate) handle: Handle,
    pub(crate) client: Arc<Client>,
}
impl Drop for TorHost {
    fn drop(&mut self) {
        if let Some(runtime) = self.runtime.get_mut().ok().and_then(Option::take) {
            if Handle::try_current().is_ok() {
                std::thread::spawn(move || runtime.shutdown_timeout(Duration::from_secs(2)));
            } else {
                runtime.shutdown_timeout(Duration::from_secs(2));
            }
        }
    }
}

static HOST: OnceLock<Mutex<Weak<TorHost>>> = OnceLock::new();

struct Transport {
    host: Arc<TorHost>,
    onion: String,
    // ...
}
```

- `pub(crate) fn host(state_dir, cache_dir, bridges) -> Result<Arc<TorHost>>` devolve o host
  existente ou constrói um novo. Todo o bloco de configuração (keystore efêmero, pontes OR simples,
  instalação do provider Rustls, builder do runtime, `create_unbootstrapped`) **saiu de `start` e foi
  para lá, sem uma linha de lógica alterada**.
- `pub(crate) fn active_host() -> Option<Arc<TorHost>>` entrega o host vivo, se houver — é como
  `doorbell::knock` encontra um cliente para bater sem exigir um `Transport`.
- `pub(crate) fn keypair(seed) -> Result<HsIdKeypair>`, um wrapper de `key`, porque a campainha
  precisa do par de chaves (não só do endereço) para lançar o próprio onion service.
- `start` encolheu para:

```rust
let hsid = key(seed)?;
let onion = HsIdKey::from(&hsid).id().display_unredacted().to_string();
let host = host(state_dir, cache_dir, bridges)?;
```

- `shutdown_transport` não derruba mais o runtime:

```rust
// O runtime tokio não é mais destruído aqui. Ele pertence ao `TorHost` compartilhado, que o
// derruba no `Drop` assim que o último dono — este transporte e/ou a campainha — some.
drop(s);
```

- As referências `transport.handle` / `transport.client` / `s.handle` / `s.client` viraram
  `…host.handle` / `…host.client`. Nenhuma assinatura pública mudou; os opcodes 0–9 e seus
  comportamentos estão intactos.

### Por que `Weak`, e não `Arc`, no `static HOST`

Se o registro global segurasse um `Arc`, o host nunca morreria e o `stop()` sem campainha deixaria o
Tor de pé para sempre — uma regressão de segurança direta. Com `Weak`, o host morre assim que o
último dono real (o `Transport` e/ou a campainha) solta. Sem campainha rodando, o `drop(s)` no fim de
`shutdown_transport` é esse último drop, e `stop` mantém exatamente o comportamento antigo: o stack
Tor inteiro morre junto com o transporte.

### Por que o `Drop` desvia para uma thread quando está dentro do runtime

Derrubar um `Runtime` de dentro de uma de suas próprias worker threads causa pânico. Nenhuma task
recebe um `Arc<TorHost>` (as tasks recebem `Handle`, `Connections`, `Sender`, `Semaphore`), então o
caso não deveria acontecer — o desvio é defesa em profundidade, para que um refactor futuro erre de
forma barulhenta em vez de matar o processo `:tor`.

### Reuso do host e seus limites

Quando um host já existe, os diretórios e as pontes da chamada nova são **ignorados**: um `TorClient`
vivo não se reconfigura para outro diretório de estado, e quem o criou detém o lock desse diretório.
Os argumentos continuam sendo validados sempre (caminhos absolutos e distintos, ≤16 pontes de ≤1024
bytes, só pontes OR simples), para que uma entrada inválida seja recusada igual, venha de
`tor::start` ou de `doorbell::start`.

### Consequência de segurança, deliberada e a ser registrada em `security-model.md`

No modo mínimo, `tor::stop()` continua destruindo o onion service de mensagens, o par de chaves dele,
o mapa de conexões e os clientes isolados por par. O que agora **sobrevive** é o cliente Arti
compartilhado (guardas, circuitos de diretório, cache de descritores) — porque a campainha precisa de
Tor para existir. Isso é uma troca consciente: o alternativa seria a campainha bootstrapar um segundo
cliente, o que o lock do diretório de estado do Arti impede. Nenhuma chave de sessão da aplicação
(Signal/MLS) vive nessa camada; elas são do lado Kotlin e continuam sendo destruídas no bloqueio.

Efeito colateral positivo: destravar depois de um modo mínimo reaproveita um cliente já bootstrapado,
então o `start` seguinte não paga os até 180 s de bootstrap de novo.

### Vantagens

- Os dois serviços passam a ter ciclos de vida de verdade independentes, sem duplicar o stack Tor e
  sem esbarrar no lock do diretório de estado do Arti.
- `stop()` sem campainha é byte a byte o comportamento anterior.
- A construção do cliente ficou num só lugar (`host`), em vez de inline dentro de `start`.
- `start` ficou 45 linhas mais curto e legível.

### Validação

`cargo check --all-features` e `cargo test --all-features` limpos (35 testes unitários, 0 avisos);
`cargo check --no-default-features` também compila. O diff do `Cargo.lock` contém apenas as duas
dependências diretas novas (`hmac 0.12.1`, `sha2 0.10.9`), já presentes como transitivas — nenhum
pacote novo, nenhuma versão alterada.

## 2026-09-18 — `start` dentro do mesmo processo colidia com a chave que `stop` deixava no keystore (T4.19)

Última etapa da campainha (T4.17). Correção de **um** defeito no nativo, com causa raiz já fechada
por instrumentação (5/5 reproduções determinísticas) em
`docs/development/build-logs/doorbell-20260918/22-handoff-root-cause.txt` e
`24-B-logcat-native-error.txt`.

### O sintoma

Bloquear o cofre e destrancá-lo de novo, no mesmo processo, matava o filho `:tor` da campainha e
pagava um bootstrap completo do Tor (~40–60 s) — exatamente o que o reaproveitamento do `TorHost`
existia para evitar. O lado Kotlin estava correto o tempo todo: decidia a adoção
(`doorbell handoff: reuse=true sameSlot=true alive=true`) e chamava `start` de novo no filho vivo.

### A causa raiz, confirmada

```
tor: bad API usage (bug): Error while trying to access a key store:
Error while trying to access a key store: Key already exists
```

`arti_client::TorClient::launch_onion_service_with_hsid` não é um "lance este serviço com esta
chave": ele **insere** o par de chaves HsId no keystore do cliente, sob o nickname do serviço, e por
projeto do Arti recusa sobrescrever uma entrada existente. O código-fonte da versão que usamos
(`arti-client 0.46.0`, `src/client.rs:2019`) fecha a questão — o `overwrite` é literal:

```rust
.insert::<HsIdKeypair>(id_keypair, &hsid_spec, selector, false)?;
```

Esse keystore pertence ao `TorHost` **compartilhado**, que desde a fase 1 desta feature sobrevive
deliberadamente a qualquer serviço onion isolado: é a campainha mantendo-o vivo durante o bloqueio
que permite ao desbloqueio seguinte pular o bootstrap. Só que `shutdown_transport` largava o
`RunningOnionService` e **não** a entrada do keystore. Na segunda chamada de `start` dentro do mesmo
processo, o Arti encontrava a chave "nomessages" ainda lá e recusava antes de qualquer coisa
acontecer — em ~3 ms. A tentativa adotiva virava `FAILED`, o supervisor esperava 5 s, a tentativa
seguinte já não achava handoff e `recycleTransport()` matava o filho da campainha.

### Antes

```rust
fn shutdown_transport(s: Arc<Transport>) -> Result<()> {
    s.state.store(STATE_STOPPED, Ordering::Release);
    s.ready.send_replace(false);
    s.cancel.send_replace(true);
    s.connections.lock()…?.clear();
    s.peers.lock()…?.clear();
    s.service.lock()…?.take();          // <- solta o serviço, mas não a chave dele
    drop(s);
    Ok(())
}
```

### Depois

```rust
pub(crate) fn forget_onion_key(host: &TorHost, nickname: &str) -> Result<()> {
    let nickname: tor_hsservice::HsNickname = nickname
        .to_owned()
        .try_into()
        .map_err(|_| anyhow!("Invalid onion service nickname"))?;
    let removed: Option<HsIdKeypair> = host
        .client
        .keymgr()
        .map_err(|_| anyhow!("Keystore unavailable"))?
        .remove(
            &tor_hsservice::HsIdKeypairSpecifier::new(nickname),
            tor_keymgr::KeystoreSelector::Primary,
        )
        .map_err(|_| anyhow!("Keystore removal failed"))?;
    drop(removed);   // zera a metade secreta; `None` só significa que o serviço nunca subiu
    Ok(())
}

fn shutdown_transport(s: Arc<Transport>) -> Result<()> {
    …
    s.service.lock()…?.take();
    // O objeto do serviço e a entrada dele no keystore andam juntos. Por último, depois de todos
    // os outros passos, para que uma falha de keystore nunca deixe um transporte meio parado.
    let keystore = forget_onion_key(&s.host, NICKNAME);
    drop(s);
    keystore
}
```

O nickname literal `"nomessages"`, que estava inline em `start`, virou a const `NICKNAME`, para que
lançamento e remoção não possam divergir.

### Por que a remoção do keystore, e não as alternativas

| Alternativa | Por que não |
|---|---|
| Sobrescrever na própria chamada de lançamento | Não existe nesta versão: o `overwrite` é `false` fixo dentro do `arti-client`, sem parâmetro exposto. |
| Cair para `launch_onion_service` (sem hsid) quando a inserção falhar | `launch_onion_service` usa `get_or_generate`: se a chave por algum motivo **não** estivesse lá, ele geraria uma identidade **aleatória** e o serviço subiria num endereço onion que ninguém conhece — um modo de falha silencioso e muito pior que o erro atual. |
| Nickname variável por "geração" | Faria o keystore crescer sem limite com chaves órfãs a cada ciclo de lock/unlock e embaralharia a relação nickname↔identidade. Descartado. |
| Derrubar o `TorClient` inteiro no bloqueio | Anula a otimização: seria voltar a pagar o bootstrap, que é o problema que se queria resolver. |

### Por que é seguro e por que o endereço onion não muda

A entrada removida é **derivada deterministicamente** da seed de mensagens do cofre, pela mesma
função `key(seed)` que `address(seed)` usa para imprimir o endereço. Ela não guarda estado nenhum:
o `start` seguinte a reconstrói bit a bit a partir da mesma seed e republica **o mesmo** endereço.
Remover é esquecer uma cópia em memória de algo que se sabe recalcular.

Só o nickname passado é tocado, então o serviço de mensagens e a campainha nunca mexem na identidade
um do outro. E os erros são achatados em strings fixas ("Keystore unavailable", "Keystore removal
failed"): nada vindo do keystore pode vazar para uma mensagem que suba até o Kotlin.

Confirmação ao vivo, e não só por argumento: depois de **seis** ciclos de bloqueio/desbloqueio dentro
do mesmo processo `:tor` — seis remoções e seis reinserções da chave — o aparelho A entregou uma
mensagem a B pelo caminho direto, sobre o onion que A guardou no pareamento, muito antes desta
correção existir (`28-B-acceptance-after-fix.txt`). Endereço diferente seria entrega impossível.

### Raio de impacto

Nenhum outro cenário muda de comportamento. `start` normal sem campainha: a primeira inserção sempre
foi num keystore vazio. `stop` completo e pânico: o `TorHost` morre junto e leva o keystore inteiro,
então a remoção é apenas redundante. Caminho de erro do próprio `start` (bootstrap falhou antes do
lançamento): `forget_onion_key` devolve `Ok(None)` — remover o que não existe não é erro.

A chamada fica **depois** de todo o resto do teardown de propósito: quando ela roda, o estado já é
`STOPPED`, o canal de cancelamento já disparou e conexões e pares já foram limpos. Uma falha de
keystore não consegue deixar um transporte meio parado para trás.

### Nota: `doorbell.rs` precisou da mesma correção (e por quê)

Não é simetria decorativa. `NoMessagesController.kt:369` chama `doorbellStop()` **em todo
desbloqueio**, assim que o serviço de mensagens adota o host. Com o `:tor` passando a sobreviver aos
ciclos — que é justamente o efeito desta correção —, o **segundo** bloqueio do processo chamaria
`doorbell::start` sobre a chave "nomessages-doorbell" deixada pelo primeiro, e a campainha falharia
ao abrir. Corrigir só o transporte trocaria um defeito no ciclo 1 por outro no ciclo 2. `shutdown`
da campainha ganhou a mesma chamada, em best effort (`let _ = …`): `stop` tem de derrubar a
campainha diga o keystore o que disser, e a chave é derivável da seed, então uma falha ali custa um
relançamento futuro, nunca dado.

### `Cargo.toml`: uma feature, nenhuma dependência

`TorClient::keymgr()` — o único acesso público ao `KeyMgr` do cliente — está atrás de
`onion-service-cli-extra`. A feature foi acrescentada ao `arti-client`. Ela expande para
`tor-keymgr/onion-service-cli-extra`, que por sua vez é `keymgr` (já ligada, via
`onion-service-service`) mais um marcador vazio. **Nenhum crate entra no grafo e o `Cargo.lock` não
mudou** — `cargo test --all-features --locked` aceita a árvore sem tocar no lock.

### Validação

- `cargo test --all-features --locked` (WSL): **46 passaram, 0 falharam, 1 ignorado** (o `tor_live`,
  que exige Tor ao vivo). Eram 45; o teste novo é
  `tor::tests::relaunching_a_nickname_needs_its_key_forgotten_first`, que reproduz o defeito
  **offline**: a colisão é no keystore, não na rede, e o keystore existe num `TorClient`
  não-bootstrapado — então o ciclo inteiro vira inserir (lançar) → esquecer (derrubar) → inserir
  (relançar). O teste checa que a segunda inserção sem esquecer **falha**, que esquecer um nickname
  não mexe no outro, que depois de esquecer a reinserção passa, que a chave reinstalada devolve
  exatamente `address(seed)`, e que esquecer um nickname que nunca subiu é no-op.
- Sonda de PID ao vivo, 5 rodadas: `27-B-pid-stable-after-fix.txt` — PID do `:tor` idêntico nas
  cinco, zero `transport attempt: failed`, "Tor connected" em 16 s em vez de 40–60 s.
- Cenário de aceite completo: `28-B-acceptance-after-fix.txt` e
  `29-B-acceptance-delivered-after-fix.png`.
- Regressão Kotlin: `:app:testDebugUnitTest` 83/0/1 pulado e `:core:test` 102/0/2 pulados — as
  mesmas contagens de antes.

### Vantagens

- O reaproveitamento do `TorHost` finalmente entrega o que prometia: desbloqueio sem bootstrap,
  filho da campainha vivo, 5 s de backoff a menos e uma bateria a menos gasta por ciclo.
- Keystore e serviços onion passam a andar em passo: quem derruba o serviço derruba a chave dele.
- O nickname deixou de ser literal solto e virou const, de onde lançamento e remoção o leem.
- O defeito passou a ter um teste de regressão que roda offline, em vez de depender de dois
  emuladores e de um bootstrap de Tor para reaparecer.
