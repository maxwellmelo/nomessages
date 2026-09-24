# docs/development/vault-report.md

## 2026-09-17 — T4.10: política de senhas e ressalva de zeroização

**Antes:** o relatório afirmava "Password policy uses zxcvbn4j 1.9.0 score 4, 16–128 alphanumeric characters, UTF-8 NFC" e listava "zxcvbn internals" entre os motivos pelos quais a zeroização completa de memória não é demonstrável.

**Agora:** a primeira afirmação descreve a política vigente (`estimateStrength` score 3, 12–128 caracteres de ASCII imprimível ou letra Unicode, sem controle, sem espaço nas bordas, UTF-8 **NFKC**, par real/pânico nem igual nem variação trivial) e registra a remoção do `com.nulab-inc:zxcvbn`. A ressalva de zeroização trocou "zxcvbn internals" pelos buffers de rascunho do estimador e pela `String` imutável que o `java.text.Normalizer` necessariamente produz.

**Por quê:** as duas frases descreviam o código antigo e passariam a ser factualmente falsas depois da T4.10. Um relatório de verificação que mente sobre o que é verificado é pior do que nenhum relatório. Detalhes em [`PasswordPolicy.kt.md`](PasswordPolicy.kt.md).

## 2026-09-14 — T5.3: caminhos `/tmp` obsoletos

### Como era antes

```markdown
The vault/files suite currently contains 25 normal test methods plus two opt-in
cleanup fault tests. The host JNI library is `/tmp/nomessages-system-target/debug/libnomessages.so`;
the build owner has also staged a symlink under the central process's previously
configured native path.
```

```markdown
... a process with `LD_PRELOAD=/tmp/nomessages-vault-cleanup-faults.so
NOMESSAGES_TEST_DENY_CLEANUP=1` was verified to receive EACCES ...
```

Dois caminhos de `/tmp` apresentados como se fossem o estado atual do projeto.
Ambos eram artefatos de um host anterior: `/tmp/nomessages-system-target` era um
`CARGO_TARGET_DIR` sobrescrito naquela máquina, e `/tmp` é volátil por definição.

### Como ficou

```markdown
... The host JNI library is `native/target/debug/libnomessages.so`, which is where
`cargo build` places it and where `:core:test` looks by default; the
`/tmp/nomessages-system-target/debug` path recorded earlier was a `CARGO_TARGET_DIR`
override on the previous host and no longer exists. On that host the build owner
had also staged a symlink under the central process's previously configured
native path.
```

```markdown
... a process with `LD_PRELOAD=<fixture>.so NOMESSAGES_TEST_DENY_CLEANUP=1` (staged
under `/tmp` on the previous host; that path is historical and must be rebuilt
locally) was verified to receive EACCES ...
```

Nenhum outro trecho foi alterado. A linha 24, que fala de uma compilação
Kotlin 2.0.21/JDK17 interrompida, já estava redigida no passado e já identificava
a build autoritativa como Kotlin 2.3.21 — não precisava de correção.

### Por que a mudança foi feita

T5.3 no plano: `vault-report.md:20` (`/tmp/...` → `native/target/debug`).

O problema não é estético. Quem lê "a biblioteca JNI do host é
`/tmp/nomessages-system-target/debug/libnomessages.so`" e tenta reproduzir a evidência
encontra um diretório inexistente e conclui que a build está quebrada, quando o
caminho correto (`native/target/debug`) é justamente o *default* do Cargo e o que
a tarefa `:core:test` procura. Um caminho em `/tmp` também some no primeiro
reboot, então gravá-lo como estado do projeto é registrar uma evidência que não
pode ser reexecutada.

O texto preserva o registro histórico — diz que o caminho existiu e por quê (um
`CARGO_TARGET_DIR` sobrescrito) — em vez de apagá-lo, para que quem comparar com
logs antigos entenda a correspondência.

### Vantagens

- O relatório passa a descrever um caminho reproduzível na máquina de quem lê.
- Elimina um falso sinal de build quebrada.
- Mantém a rastreabilidade do registro anterior sem afirmar que ele ainda vale.
- O fixture `vault_cleanup_faults.c` fica explicitamente marcado como algo que
  precisa ser recompilado localmente, e não como um `.so` pronto em algum lugar.

### Não verificado

- Nenhum build foi executado nesta tarefa. Não foi confirmado empiricamente que
  `cargo build` produz `native/target/debug/libnomessages.so` nesta máquina — a
  afirmação vem do comportamento padrão do Cargo e do que
  `crypto-report.md` já registrava.
- A contagem "25 normal test methods" não foi reconferida; veio intacta da versão
  anterior do documento.

## 2026-09-14 — achado de revisão P2 ainda ABERTO em `vault-report.md:20`

**Esta seção não documenta uma mudança: documenta uma correção necessária que a área
`docs-catalog` não pôde aplicar**, porque `docs/development/vault-report.md` não está na
lista de arquivos desta tarefa. Registrada aqui para não se perder e para que a próxima
rodada a aplique. Nada em `vault-report.md` foi alterado por esta revisão.

### O problema

A edição de T5.3 descrita acima trocou

```markdown
The host JNI library is `/tmp/nomessages-system-target/debug/libnomessages.so`;
```

por

```markdown
The host JNI library is `native/target/debug/libnomessages.so`, which is where `cargo build`
places it and where `:core:test` looks by default; the `/tmp/nomessages-system-target/debug`
path recorded earlier was a `CARGO_TARGET_DIR` override on the previous host and no
longer exists.
```

`/tmp/nomessages-system-target/debug` não era só um `CARGO_TARGET_DIR` sobrescrito: é
**exatamente** o artefato ligado à libsodium 1.0.18 **do sistema** que o
`crypto-report.md:31` classifica como *integration-only* e proíbe para release.
`build-logs/native-crypto-host.log` registra
`ldd /tmp/nomessages-system-target/debug/libnomessages.so` → `libsodium.so.23 =>
/lib/x86_64-linux-gnu/libsodium.so.23`, com a nota "This accelerated host artifact used
the installed libsodium 1.0.18 shared library and the now-superseded sodiumoxide
implementation. It is integration-only and must not be released."

Ao apagar o caminho, a edição apagou junto o marcador que dizia **qual** biblioteca
produziu os 25 testes de vault/archive/files — e o texto passa a dar a entender que essa
evidência rodou contra a build empacotada (libsodium 1.0.22), sem nenhuma medição que
sustente isso. É o mesmo defeito de proveniência corrigido em `crypto-report.md:58-68`
nesta rodada, com um agravante: aqui a qualificação "não serve para release" foi perdida.

### Correção proposta (texto pronto para aplicar em `vault-report.md:20`)

> The host JNI library used for that run was `/tmp/nomessages-system-target/debug/libnomessages.so`
> — the integration-only artifact linked against the host's installed libsodium 1.0.18
> (see `crypto-report.md:31` and `build-logs/native-crypto-host.log`), which must not be
> released; that host no longer exists. To reproduce locally, build
> `native/target/debug/libnomessages.so` with
> `env -u SODIUM_LIB_DIR -u SODIUM_SHARED -u SODIUM_USE_PKG_CONFIG cargo build --locked`
> — the path `cargo build` uses by default and where `:core:test` looks — and re-run the
> suite. The results recorded here have **not** yet been reconfirmed against the bundled
> 1.0.22 build.

### Por que a correção importa

- Restaura a rastreabilidade: o leitor volta a saber contra qual biblioteca os 25 testes
  passaram, e que essa biblioteca é proibida para release.
- Evita uma falsa confirmação: sem o aviso, alguém pode marcar o gate de vault como
  satisfeito sobre a build empacotada, que nunca rodou essa suíte.
- Mantém o ganho legítimo da edição de T5.3 — dizer onde reproduzir hoje — em vez de
  desfazê-la: o caminho reproduzível continua no texto, agora separado do caminho
  histórico.
