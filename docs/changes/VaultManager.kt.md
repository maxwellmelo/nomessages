# core/src/main/kotlin/dev/mx3/nomessages/core/vault/VaultManager.kt

## 2026-09-23 — `fakePanicPasswordChange`: a contrapartida da isca para `resetPanicPassword` (T4.7a)

### Motivo

A revisão de 2026-09-15 encontrou um oráculo de isca: a tela de configurações escondia o botão
"trocar senha de pânico" sempre que o cofre aberto era a isca (`opened.slot == VaultSlot.REAL` em
`NoMessagesController.kt`), o que por si só já denunciava qual cofre estava aberto sem precisar
tentar a senha real. A correção óbvia — sempre mostrar o botão — só é segura se o que acontece ao
tocar nele, na isca, for indistinguível do fluxo real: mesma validação, mesmo custo de KDF (senão o
tempo de resposta vira o novo oráculo) e a mesma mensagem de sucesso, sem nunca revelar ou alterar a
senha real. `VaultManager` não tinha nenhuma operação "de isca"; só existia `resetPanicPassword`, que
exige uma `VaultSession` real (`require(realSession.slot == VaultSlot.REAL)`) porque só o cofre real
tem uma senha de pânico para substituir.

### Como era antes

`VaultManager` só tinha `resetPanicPassword`, utilizável apenas com uma sessão real:

```kotlin
fun resetPanicPassword(directory: Path, realSession: VaultSession, password: CharArray) {
    ...
    require(!realSession.isClosed && realSession.slot == VaultSlot.REAL) { "Real vault session required" }
    PasswordPolicy().validate(password)
    normalized = normalizePassword(password)
    ...
    // duas derivações Argon2id: samePasswordKey (linha ~104) e newMaster (linha ~109) em VaultHeader.resetPanic
}
```

Não havia nenhuma função que a isca pudesse chamar; o único jeito de "aceitar" o fluxo na isca teria
sido pular a etapa inteira (o que reintroduziria o oráculo, agora como diferença de tempo) ou chamar
`resetPanicPassword` de verdade (o que exigiria uma sessão real que a isca não tem, e alteraria o
cofre-isca de um jeito observável entre reinícios).

### Como ficou

```kotlin
/**
 * Decoy-slot counterpart to [resetPanicPassword] ... pays exactly the KDF cost [resetPanic] pays
 * against the vault's own calibrated [KdfParams] ... and discards every result. Nothing on disk
 * changes ...
 */
fun fakePanicPasswordChange(directory: Path, password: CharArray) {
    var normalized: ByteArray? = null
    try {
        PasswordPolicy().validate(password)
        normalized = normalizePassword(password)
        password.fill('\u0000')
        val secret = normalized
        val params = VaultHeader.kdfParams(readHeader(directory))
        repeat(2) {
            val salt = crypto.random(16)
            try {
                val discarded = crypto.derive(secret, salt, params.memoryKiB, params.iterations)
                crypto.wipe(discarded)
            } finally { crypto.wipe(salt) }
        }
    } finally {
        password.fill('\u0000'); normalized?.let(crypto::wipe)
    }
}
```

Chamada por `NoMessagesController.changePanicPassword` quando a sessão aberta é a isca (ver
`docs/changes/NoMessagesController.kt.md`), depois da mesma sequência de teardown que o caminho real
executa. Os parâmetros de KDF vêm de `VaultHeader.kdfParams` (novo, ver
`docs/changes/VaultHeader.kt.md`), lidos direto do prefixo em claro do `header.bin` — o mesmo que
`unlock`/`resetPanic` já liam, sem precisar de senha nem de sessão.

### Por que é seguro

- **Mesma validação:** `PasswordPolicy().validate(password)` é chamada primeiro, exatamente como em
  `resetPanicPassword` — a mesma senha é aceita ou rejeitada nos dois fluxos.
- **Mesmo custo:** duas derivações Argon2id contra os parâmetros calibrados reais do cofre (os
  mesmos que `VaultHeader.resetPanic` paga: uma para o que seria a checagem "deve ser diferente da
  senha atual", uma para o que seria a chave mestra de substituição), com um salt aleatório novo a
  cada chamada — nenhuma reutiliza o salt real, então nada aqui pode ser usado para atacar o cofre
  real por canal lateral de tempo/cache.
- **Nada é comparado, nada é lido de volta:** os dois resultados são descartados com `crypto.wipe`
  imediatamente; a senha da isca nunca é comparada com a senha real nem com a senha de pânico
  verdadeira.
- **Nada no disco muda:** não há escrita de `header.bin`, não há banco tocado, não há
  `VaultSession` envolvida — a função nem abre uma.
- **Descoberto pelos testes** (`VaultTest.kt`, ver `docs/changes/VaultTest.kt.md`):
  `fakePanicPasswordChangePaysTheSameKdfCostAsResetAndTouchesNothing` conta as derivações (2, com um
  `Crypto` de teste que incrementa um contador), confirma que `header.bin` não mudou byte a byte e
  que a senha real e a de pânico continuam resolvendo exatamente como antes;
  `fakePanicPasswordChangeRejectsAWeakPasswordLikeTheRealResetDoes` confirma que uma senha fraca é
  rejeitada **antes** de qualquer derivação (0 chamadas ao KDF), como no caminho real.

### Vantagens

- Fecha o oráculo de tempo que a correção ingênua ("sempre mostrar o botão, mas não fazer nada na
  isca") teria reintroduzido.
- Zero acoplamento com `VaultSession`: não precisa nem pode abrir uma, então não há risco de a isca
  tocar em estado da sessão real por engano.
- Reaproveita `VaultHeader.kdfParams`/`PasswordPolicy`/`Crypto.derive` já existentes — nenhum
  primitivo criptográfico novo foi introduzido só para isto.

Ver também `docs/security-model.md` ("Decoy oracles fixed...", T4.7) e
`docs/changes/security-model.md.md`.

## 2026-09-23 (revisão P1) — `fakePanicPasswordChange` também paga o custo de I/O em disco do reset real

### Motivo

Uma revisão de código sobre a seção "Decoy oracles fixed" acima (achados P1, `docs/security-model.md`
linha ~405 e este arquivo linha ~140) apontou que igualar só a contagem de chamadas ao KDF não fecha
o oráculo: `resetPanicPassword` não gasta seu tempo só nas duas derivações de `VaultHeader.resetPanic`
— ele também roda `storage.beforeExport`, copia a árvore de texto cifrado inteira
(`copyCiphertextTree(absolute, stage)`: `real.db`, `decoy.db` e tudo sob `real.files/`/`decoy.files/`),
apaga e recria `decoy.files`, chama `storage.initialize` (reescreve `decoy.db` do zero até a
capacidade cheia e grava as fixtures de mídia da isca), `storage.alignAllocations`, dois `Files.move`
atômicos e por fim `discardCiphertext(backup)`, que apaga a árvore antiga inteira que acabou de
copiar. Nada disso é limitado por uma constante: `AndroidVaultStorage.alignAllocations` só garante que
`real.files`/`decoy.files` fiquem do mesmo tamanho *entre si*, não que sejam pequenos — então em
qualquer cofre com conteúdo de mídia real esse custo escala com o tamanho real do cofre, enquanto a
`fakePanicPasswordChange` original fazia zero cópias de arquivo, zero escrita de banco e zero
exclusão. Resultado: "trocar senha de pânico" terminava perceptivelmente mais devagar no cofre real do
que na isca em qualquer cofre com uso real — exatamente a classe de oráculo que T4.7(a) deveria ter
fechado, só que um nível abaixo do botão em si. O próprio teste do implementador
(`fakePanicPasswordChangePaysTheSameKdfCostAsResetAndTouchesNothing`) só verificava a contagem de KDF
(2) e que nenhum byte do cofre mudava; nada media ou limitava o tempo/I-O do ramo real contra o da
isca, então a alegação de "paridade de tempo de parede" no `security-model.md` não era testada e,
dado o código, era falsa no caso geral.

### Como era (só a parte de I/O, o resto do corpo é o da seção acima)

```kotlin
fun fakePanicPasswordChange(directory: Path, password: CharArray) {
    var normalized: ByteArray? = null
    try {
        PasswordPolicy().validate(password)
        normalized = normalizePassword(password)
        password.fill('\u0000')
        val secret = normalized
        val params = VaultHeader.kdfParams(readHeader(directory))
        repeat(2) { /* ...duas derivações Argon2id, descartadas... */ }
    } finally {
        password.fill('\u0000'); normalized?.let(crypto::wipe)
    }
    // nenhuma leitura/escrita/exclusão de arquivo em lugar nenhum
}
```

### Como ficou

```kotlin
fun fakePanicPasswordChange(directory: Path, password: CharArray) {
    var normalized: ByteArray? = null
    var stage: Path? = null
    try {
        PasswordPolicy().validate(password)
        normalized = normalizePassword(password)
        password.fill('\u0000')
        val secret = normalized
        val absolute = directory.toAbsolutePath()
        val params = VaultHeader.kdfParams(readHeader(absolute))
        repeat(2) { /* ...as mesmas duas derivações Argon2id, descartadas... */ }
        // Paga o mesmo I/O de disco que `resetPanicPassword` paga (cópia da árvore inteira, depois
        // reescrita de um arquivo do tamanho do banco, depois descarte completo) contra um diretório
        // de estágio descartável — nada sob `absolute` é escrito.
        stage = Files.createTempDirectory(absolute.parent, ".vault-fake-reset-")
        copyCiphertextTree(absolute, stage)
        Files.copy(stage.resolve("real.db"), stage.resolve("decoy.db"), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        password.fill('\u0000'); normalized?.let(crypto::wipe)
        stage?.let(::discardCiphertext)
    }
}
```

`copyCiphertextTree` e `discardCiphertext` já existiam neste arquivo (usadas por `resetPanicPassword`
e `create`), então nenhum primitivo de I/O novo foi introduzido — a isca agora só chama as mesmas duas
funções que o caminho real já chamava, contra um diretório próprio que ninguém mais referencia.

### Por que é seguro e por que fecha o achado

- **Escala com o tamanho real do cofre, não com uma constante:** `copyCiphertextTree` lê o diretório
  do cofre pelo sistema de arquivos (acesso que a própria sessão isca já tem legitimamente, por ser o
  mesmo diretório físico) e copia exatamente os mesmos arquivos que o caminho real copia —
  `real.db`, `decoy.db`, `real.files/*`, `decoy.files/*` — então o número de bytes lidos/escritos
  cresce e encolhe junto com o cofre, igual ao caminho real.
- **A reescrita do banco imita `storage.initialize`:** o caminho real reescreve `decoy.db` do zero até
  a capacidade cheia dentro de `storage.initialize`; aqui, `Files.copy(..., REPLACE_EXISTING)` do
  `real.db` copiado por cima do `decoy.db` copiado paga uma escrita completa do mesmo tamanho, sem
  precisar reimplementar `fillReserveToCapacity`.
- **O descarte imita `discardCiphertext(backup)`:** o `finally` sempre descarta `stage` inteiro,
  pagando o mesmo custo de exclusão que o caminho real paga ao descartar a árvore antiga.
- **O diretório do cofre em si nunca é escrito:** só o diretório de estágio é tocado, e ele é
  descartado antes da função retornar — a garantia "nada no disco do cofre muda" da seção acima
  continua valendo byte a byte (coberta pelo mesmo teste de antes mais o novo, abaixo).
- **Testado por `VaultTest.fakePanicPasswordChangePaysComparableDiskIoToTheRealResetOnASeededVault`**
  (ver `docs/changes/VaultTest.kt.md`): semeia mídia nos dois slots, roda os dois ramos e confirma (a)
  o diretório do cofre continua byte a byte idêntico depois da isca e (b) os dois tempos de parede
  ficam na mesma ordem de grandeza um do outro sobre o mesmo conteúdo semeado — um limite frouxo, para
  não piscar (flake) em hardware de CI compartilhado, não uma alegação de paridade exata.

### Limitação residual, registrada e não esta corrigida aqui

Este teste roda contra `TestStorage` (JVM, banco de alguns KB) — não contra
`AndroidVaultStorage` com `capacityMiB` real (256 MiB por padrão). A propriedade estrutural (mesma
cópia/reescrita/descarte, escalando com o tamanho real do diretório) foi corrigida e é testada; uma
medição de tempo de parede em um cofre Android real com mídia na faixa de dezenas/centenas de MiB não
foi executada nesta sessão (exigiria o gate de dispositivo T3.5/T3.6, fora do escopo desta correção
pontual). `docs/security-model.md` registra essa ressalva explicitamente na seção T4.7 atualizada.

### Vantagens

- Fecha o oráculo de I/O em disco (tempo de parede e bytes tocados) que a correção original de T4.7(a)
  deixou aberto, sem precisar redesenhar `resetPanicPassword` (a alternativa mais invasiva sugerida
  pela revisão).
- Reaproveita `copyCiphertextTree`/`discardCiphertext` já existentes — nenhuma superfície de código
  nova para auditar além de um `Files.copy` a mais.
- A correção de documentação (`docs/security-model.md`) para de alegar paridade que o código não
  entregava, e passa a explicar precisamente o que ficou igual e por quê.

Ver também `docs/security-model.md` ("Follow-up (2026-09-23)...", T4.7) e
`docs/changes/VaultTest.kt.md`.

## 2026-09-23 (revisão P1 do T4.1) — `resetPanicPassword` volta a passar em cofres com mídia real

### Motivo

Uma revisão de código sobre o fechamento do T4.1 (achado P1, `core/.../VaultManager.kt:108`)
encontrou uma quebra real: `resetPanicPassword` reconstrói `decoy.files` do zero
(`storage.initialize(stage, VaultSlot.DECOY, replacementKeys)`, que só grava o pequeno conjunto fixo
de fixtures de setup), mas `real.files` é só **copiado**, nunca reconstruído — então mantém qualquer
tamanho que tenha crescido durante o uso normal (`MessagingEngine.storeAttachment`'s crescimento por
cobertura cega, T4.1, mantém `real.files`/`decoy.files` iguais só enquanto os dois estão *vivos e em
uso*, não através de uma reconstrução do zero). A linha seguinte,
`storage.alignAllocations(stage, realKeys, replacementKeys)`, desde o T4.1 também exige que
`real.files`/`decoy.files` tenham o mesmo tamanho total e lança `IllegalStateException("Vault media
allocations differ")` sempre que divergem. Como `NoMessagesController.changePanicPassword` engole
essa exceção num `catch (_: Exception) {}` genérico, o resultado observável era um erro genérico e
`committed = false` — **a troca de senha de pânico simplesmente não acontecia**, em qualquer cofre
que já tivesse enviado ou recebido um anexo. Nenhum teste cobria isto: os dois casos novos do T4.1 em
`AndroidVaultStorageTest.kt` só chamavam `storage.beforeExport` depois do crescimento, nunca
`resetPanicPassword`/`changePanicPassword`; os testes de paridade real/isca de
`fakePanicPasswordChange`/`resetPanicPassword` em `VaultTest.kt` usam `TestStorage`, cujo
`alignAllocations` é `Unit` (não faz nada), então nunca poderiam pegar este bug.

### Como era

```kotlin
storage.initialize(stage, VaultSlot.DECOY, replacementKeys)
storage.alignAllocations(stage, realKeys, replacementKeys)
```

### Como ficou

```kotlin
storage.initialize(stage, VaultSlot.DECOY, replacementKeys)
// The rebuild above starts the decoy media directory back at the small fixed fixture
// size; `real.files` may since have grown arbitrarily larger through ordinary use. Top
// the decoy back up to match before the parity check below, or a panic-password reset of
// any vault that has ever sent/received an attachment would fail every time (T4.1
// follow-up, docs/security-model.md).
storage.padMediaToMatch(stage, VaultSlot.DECOY, VaultSlot.REAL)
storage.alignAllocations(stage, realKeys, replacementKeys)
```

`padMediaToMatch` é um método novo em `VaultStorage` (ver `docs/changes/VaultSession.kt.md`),
implementado em `AndroidVaultStorage` (ver `docs/changes/AndroidVaultStorage.kt.md`): completa a
mídia do slot indicado com blobs cegos, no mesmo estilo do `MessagingEngine.coverSiblingSlot`, até
igualar o tamanho do slot de referência. Um no-op quando o slot já é do mesmo tamanho ou maior — o
que cobre `create()`, onde os dois começam vazios e iguais, e onde chamar `alignAllocations`
diretamente continua correto sem nenhuma mudança.

### Por que é seguro

- O `decoy.files` reconstruído nunca fica **menor** do que o esperado — só recebe bytes extras de
  preenchimento, nunca perde nada do que `initialize` acabou de gravar.
- O padding usa `crypto.random` para o conteúdo e um nome de arquivo independentemente aleatório para
  cada blob (mesma forma que `MessagingEngine.coverSiblingSlot` já usa) — nenhuma relação entre o
  nome do arquivo e qualquer identificador real, então não introduz nenhuma correlação nova entre os
  dois slots.
- `real.files` (`directory`, o parâmetro `matchSlot`) nunca é escrito por `padMediaToMatch` — só lido
  para saber quantos bytes faltam; a garantia "o diretório do cofre original só é lido, nunca
  escrito, até o `Files.move` final" que `resetPanicPassword` já documentava continua valendo.
- Já que `real.files` nunca excede `mediaCapacityBytes` (garantido pela própria checagem de
  capacidade de `MessagingEngine.storeAttachment`), completar `decoy.files` até o mesmo tamanho
  também nunca excede a capacidade da isca — nenhuma checagem de capacidade extra foi necessária
  aqui.
- **Testado por** `AndroidVaultStorageTest.resetPanicPasswordSucceedsAfterMediaHasGrownPastTheSetupFixtureSize`
  (novo, ver `docs/changes/AndroidVaultStorageTest.kt.md`): envia um anexo real primeiro (crescendo os
  dois slots além do tamanho das fixtures), troca a senha de pânico de verdade contra o
  `AndroidVaultStorage` real e confirma que (a) não lança, (b) `real.files` fica intocado, (c)
  `decoy.files` reconstruído fica igual em bytes, e (d) a nova senha de pânico realmente desbloqueia a
  isca reconstruída.

### Vantagens

- Fecha um bloqueador funcional real (a troca de senha de pânico deixava de funcionar) introduzido
  pelo próprio T4.1, sem enfraquecer a checagem de paridade que o T4.1 adicionou — `alignAllocations`
  continua exigindo igualdade estrita, agora depois de o chamador ter restaurado essa igualdade de
  propósito.
- Interface pequena e reaproveitável: `padMediaToMatch` não é específico de `resetPanicPassword`,
  então qualquer reconstrução futura de um slot a partir do zero pode reaproveitá-lo sem duplicar a
  lógica de preenchimento.
- Não altera o comportamento de `create()` nem de nenhum outro chamador de `alignAllocations` — só
  `resetPanicPassword` passou a chamar o método novo.

Ver também `docs/security-model.md` ("Fixed media reservation and blind cover growth — follow-up
2026-09-23", T4.1), `docs/changes/VaultSession.kt.md` e `docs/changes/AndroidVaultStorage.kt.md`.
