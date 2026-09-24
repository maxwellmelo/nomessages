# VaultTest.kt

## 2026-09-17 — T4.10: correção de `passwordPolicyRejectsWeakShortNonAlphanumericAndEqualPasswords`

Arquivo: `core/src/test/kotlin/dev/mx3/nomessages/core/vault/VaultTest.kt`.
Contexto completo da mudança de política: `docs/changes/PasswordPolicy.kt.md`.

### Como era antes

```kotlin
@Test fun passwordPolicyRejectsWeakShortNonAlphanumericAndEqualPasswords() {
    val policy = PasswordPolicy()
    for (password in listOf("p4ssw0rdp4ssw0rd", "qwertyuiopasdfghjkl", "Q7vfN2rT", "Q7vfN2rT8bLp4WzK!")) {
        assertThrows(IllegalArgumentException::class.java) { policy.validate(password.toCharArray()) }
    }
    policy.validate(real())
    ...
}
```

### Como ficou

```kotlin
@Test fun passwordPolicyRejectsWeakShortAndEqualPasswordsWhileAcceptingSymbols() {
    val policy = PasswordPolicy()
    // "Q7vfN2rT8bLp4WzK!" used to be in this list only because it carried a symbol. Symbols are
    // accepted since T4.10 and that string has no weakness pattern, so it moved to the positive
    // case below; "Senha123456!" replaces it as a genuinely weak symbol password (common word,
    // ascending digit run, capitalized-word-plus-trailing-symbol shape).
    for (password in listOf("p4ssw0rdp4ssw0rd", "qwertyuiopasdfghjkl", "Q7vfN2rT", "Senha123456!")) {
        assertThrows(IllegalArgumentException::class.java) { policy.validate(password.toCharArray()) }
    }
    policy.validate(real())
    policy.validate("Q7vfN2rT8bLp4WzK!".toCharArray())
    ...
}
```

O resto do método (par igual rejeitado, `KdfParams` inválidos, e o caso composto/decomposto com
`é` contra `e` + U+0301) ficou intacto.

### O que mudou e por quê

| Vetor | Antes | Agora | Motivo |
|---|---|---|---|
| `p4ssw0rdp4ssw0rd` | falhava | falha | palavra de dicionário repetida; continua ~15.7 bits |
| `qwertyuiopasdfghjkl` | falhava | falha | duas linhas inteiras de teclado; ~16.0 bits |
| `Q7vfN2rT8bLp4WzK` + `!` (8 chars) → `Q7vfN2rT` | falhava | falha | 8 caracteres, abaixo do novo piso de 12 |
| `Q7vfN2rT8bLp4WzK!` | falhava | **passa** | falhava **só** pelo `!`; símbolos são aceitos agora |
| `Senha123456!` | — | falha | vetor novo: `senha` + `123456` + `Palavra…!`, ~16.4 bits |

O ponto central: `Q7vfN2rT8bLp4WzK!` tem 17 caracteres pseudoaleatórios, mistura maiúsculas,
minúsculas, dígito e símbolo, e **não** contém palavra de dicionário nem sequência. Sob a regra
antiga ela era recusada apenas pela cláusula `all { it.isLetterOrDigit() }`. Sob a regra nova ela
vale ~111.7 bits e é legitimamente forte — mantê-la na lista de rejeições tornaria o teste
**falso**. Ela foi promovida a caso positivo explícito no mesmo teste, o que é mais valioso do que
apenas removê-la: agora o teste prova que símbolos passaram a ser aceitos.

`Senha123456!` entrou no lugar porque é uma senha **com símbolo** e ainda assim fraca, preservando a
intenção original do vetor (provar que um símbolo não compra força) com a razão certa: palavra comum
em português, run de dígitos ascendente e o formato "palavra capitalizada + sufixo".

O nome do método foi ajustado para continuar descrevendo o que ele afirma: `NonAlphanumeric` saiu
(não é mais motivo de rejeição) e `WhileAcceptingSymbols` entrou (é a nova asserção positiva).

### Verificação de retrocompatibilidade

Os auxiliares `real() = "Q7vfN2rT8bLp4WzK6sHx"` e `panic() = "M9kP3vX7rB2nQ5sT8wLc"`, usados por
todos os outros testes deste arquivo, valem 119.1 bits cada (score 4) e continuam válidos sem
alteração — assim como `"A6mR9xH2kV5zL8pQ3nTc"`, usado em
`resetPanicPreservesRealKeysReplacesDecoyAndRevokesPreviousPanic`. O par `real()`/`panic()` também
passa nas novas regras de distinção: nenhum é subcadeia do outro e a distância de edição entre eles
é muito maior que 2.

O caso composto/decomposto continua a falhar em `validatePair` pelo mesmo motivo de antes — as duas
formas colapsam no mesmo texto normalizado —, apenas sob NFKC em vez de NFC. Como as duas senhas são
idênticas após a normalização, a mensagem lançada continua sendo `"Passwords must be distinct"`, e
não a nova mensagem de variação trivial.

### Arquivos de teste novos criados junto

- `core/src/test/kotlin/dev/mx3/nomessages/core/vault/PasswordPolicyTest.kt`
- `core/src/test/kotlin/dev/mx3/nomessages/core/vault/PasswordStrengthTest.kt`

## 2026-09-23 — Cobertura JVM de `VaultManager.fakePanicPasswordChange` (T4.7a)

### Motivo

`VaultManager.fakePanicPasswordChange` (novo, ver `docs/changes/VaultManager.kt.md`) é lógica pura
que roda inteiramente em JVM — não toca em `AndroidVaultStorage` nem em SQLCipher — então a
propriedade que mais importa provar (paga o mesmo custo de KDF que `resetPanic`, não altera nada no
disco, valida como o caminho real) cabe aqui, na mesma suíte que já cobre `resetPanicPassword`.

### Como ficou

```kotlin
@Test fun fakePanicPasswordChangePaysTheSameKdfCostAsResetAndTouchesNothing() {
    val root = Files.createTempDirectory("vault-fake-panic").resolve("vault")
    val manager = VaultManager(crypto, TestStorage(crypto))
    manager.create(root, real(), panic(), KdfParams(65536, 1))
    val headerBefore = Files.readAllBytes(root.resolve("header.bin"))
    var derivations = 0
    val counted = object : Crypto by crypto {
        override fun derive(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
            derivations++; return crypto.derive(password, salt, memoryKiB, iterations)
        }
    }
    val decoyLookingPassword = "A6mR9xH2kV5zL8pQ3nTc".toCharArray()
    VaultManager(counted, TestStorage(crypto)).fakePanicPasswordChange(root, decoyLookingPassword)
    assertEquals(2, derivations)
    assertTrue(decoyLookingPassword.all { it == '\u0000' })
    assertArrayEquals(headerBefore, Files.readAllBytes(root.resolve("header.bin")))
    manager.unlock(root, real()).use { assertEquals(VaultSlot.REAL, it.slot) }
    manager.unlock(root, panic()).use { assertEquals(VaultSlot.DECOY, it.slot) }
}

@Test fun fakePanicPasswordChangeRejectsAWeakPasswordLikeTheRealResetDoes() {
    ...
    assertThrows(IllegalArgumentException::class.java) {
        VaultManager(counted, TestStorage(crypto)).fakePanicPasswordChange(root, "weak".toCharArray())
    }
    assertEquals(0, derivations)
}
```

O primeiro caso usa o mesmo padrão de `Crypto` contador que
`distinctPasswordsOpenOnlyTheirOwnStorageAndCloseErasesBorrowedKeys` já usa para provar "exatamente
duas derivações" no `unlock` — reaproveitado aqui para provar "exatamente duas derivações" na função
de isca, o mesmo número que `resetPanic` paga internamente (checagem "deve ser diferente" +
substituição). O segundo prova que a rejeição de senha fraca acontece **antes** de qualquer chamada
ao KDF (`0` derivações), assim como no caminho real.

### Vantagens

- Prova em JVM, sem emulador, a propriedade que mais importa para o oráculo de tempo: o custo é
  igual (2 derivações), não um custo qualquer só "parecido".
- `assertArrayEquals(headerBefore, ...)` prova bit a bit que nada no `header.bin` mudou — mais forte
  que só verificar que `unlock` ainda funciona depois.
- Reaproveita `TestStorage`/`Crypto by crypto` já existentes neste arquivo; nenhuma infraestrutura de
  teste nova.

## 2026-09-23 (revisão P1) — Cobertura de I/O de disco para `fakePanicPasswordChange`

### Motivo

A revisão de código sobre a seção acima apontou o que ela mesma já registrava como limite: contar
derivações e comparar `header.bin` byte a byte prova o custo de KDF e que o **cofre** não muda, mas
não prova nada sobre o **I/O de disco** que `resetPanicPassword` paga (cópia da árvore inteira,
reescrita de `decoy.db`, descarte da árvore antiga) e que `fakePanicPasswordChange` — antes desta
correção — simplesmente não pagava. Como `VaultManager.fakePanicPasswordChange` agora também paga
esse I/O contra um diretório de estágio descartável (ver `docs/changes/VaultManager.kt.md`), este
arquivo ganhou um teste que semeia mídia real e mede as duas coisas que faltavam: que o diretório do
cofre continua intocado, e que os dois ramos ficam na mesma ordem de grandeza de tempo de parede.

### Como ficou

```kotlin
@Test fun fakePanicPasswordChangePaysComparableDiskIoToTheRealResetOnASeededVault() {
    val root = Files.createTempDirectory("vault-fake-panic-io").resolve("vault")
    val manager = VaultManager(crypto, TestStorage(crypto))
    manager.create(root, real(), panic(), KdfParams(65536, 1))
    val payload = ByteArray(2 * 1024 * 1024) { it.toByte() }
    Files.write(root.resolve("real.files").resolve("seed.bin"), payload)
    Files.write(root.resolve("decoy.files").resolve("seed.bin"), payload)
    val snapshotBefore = snapshotTree(root)

    val fakeStartNanos = System.nanoTime()
    VaultManager(crypto, TestStorage(crypto)).fakePanicPasswordChange(root, "B7pK4wX1qM6yN9zR2sVd".toCharArray())
    val fakeMillis = (System.nanoTime() - fakeStartNanos) / 1_000_000

    assertEquals(snapshotBefore, snapshotTree(root))

    val session = manager.unlock(root, real())
    val resetStartNanos = System.nanoTime()
    manager.resetPanicPassword(root, session, "T3fG8jL5oQ0uZ4xC7wEy".toCharArray())
    val resetMillis = (System.nanoTime() - resetStartNanos) / 1_000_000

    assertTrue("fakeMillis=$fakeMillis resetMillis=$resetMillis", fakeMillis <= resetMillis * 5 + 50)
    assertTrue("fakeMillis=$fakeMillis resetMillis=$resetMillis", resetMillis <= fakeMillis * 5 + 50)
}
```

`snapshotTree` (novo, internal top-level, reaproveitável por outros testes deste arquivo) lê todo
arquivo regular sob um diretório para um `Map<Path, List<Byte>>` comparável por `assertEquals` — mais
forte que só comparar `header.bin`, porque agora também cobre `real.db`, `decoy.db` e os dois
`*.files/`.

### Por que o limite de tempo é frouxo (`* 5 + 50`) e o que ele prova de fato

Este teste roda contra `TestStorage` (bancos de poucos KB, não os `capacityMiB` reais de
`AndroidVaultStorage`), então o custo dominante nos dois ramos é a cópia/exclusão da árvore de
2 MiB semeada, não a reescrita do banco. Um limite exato (`fakeMillis == resetMillis`) seria uma
alegação falsa de paridade e uma fonte garantida de flake em CI compartilhado; o limite de "mesma
ordem de grandeza" (5x, mais uma folga fixa de 50 ms para chamadas quase instantâneas) é o que
efetivamente falha se alguém reintroduzir a versão antiga de `fakePanicPasswordChange` (custo ~0 vs.
um `resetPanicPassword` que precisa copiar/reescrever/apagar 2+ MiB) sem falhar por ruído de
agendamento do processo de teste.

### Limitação registrada, não corrigida aqui

Uma medição de tempo de parede contra `AndroidVaultStorage` real (banco de `capacityMiB`, ex.:
256 MiB) exigiria o gate de dispositivo T3.5/T3.6 e fica fora do escopo desta correção pontual;
`docs/security-model.md` e `docs/changes/VaultManager.kt.md` registram essa ressalva explicitamente.

### Vantagens

- É o primeiro teste deste arquivo que de fato mede e compara o custo dos dois ramos, em vez de só
  contar chamadas de KDF — fecha a lacuna que a própria revisão apontou no teste anterior.
- `snapshotTree` é reaproveitável por qualquer teste futuro que precise da mesma garantia "nada no
  cofre mudou" para mais do que só `header.bin`.
- O limite frouxo mantém o teste determinístico em CI compartilhado sem esvaziar a asserção: ainda
  falha se o custo de I/O voltar a ser assimétrico por uma ordem de grandeza.

## 2026-09-23 (revisão P1 do T4.1) — `TestStorage.padMediaToMatch`: no-op, mesmo tratamento de `alignAllocations`

### Motivo

`VaultStorage` ganhou um método novo, `padMediaToMatch` (ver `docs/changes/VaultSession.kt.md` e
`docs/changes/VaultManager.kt.md`), exigido pelo achado P1 do T4.1 sobre
`VaultManager.resetPanicPassword`. `TestStorage`, o fake usado pelos testes JVM deste arquivo, precisa
implementar a interface para continuar compilando.

### Como ficou

```kotlin
override fun alignAllocations(directory: Path, real: VaultKeys, decoy: VaultKeys) = Unit
override fun beforeExport(directory: Path) = Unit
// This fake never tracks media allocation (alignAllocations above is already a no-op for the
// same reason), so there is nothing to top up. AndroidVaultStorageTest exercises the real
// implementation against real media directories.
override fun padMediaToMatch(directory: Path, slot: VaultSlot, matchSlot: VaultSlot) = Unit
```

### Por que é seguro

`TestStorage` nunca modelou tamanho de diretório de mídia — `initialize` só escreve um `.db` de
4096 bytes por slot, sem `real.files`/`decoy.files` nenhum — e `alignAllocations` já era `Unit` pelo
mesmo motivo. Um `padMediaToMatch` que de fato preenchesse alguma coisa não teria o que preencher
aqui; a cobertura de comportamento real (`AndroidVaultStorage.padMediaToMatch` contra diretórios de
mídia de verdade) é do `AndroidVaultStorageTest`
(`resetPanicPasswordSucceedsAfterMediaHasGrownPastTheSetupFixtureSize`, ver
`docs/changes/AndroidVaultStorageTest.kt.md`), não deste arquivo.

### Vantagens

- Mantém os testes JVM deste arquivo (que rodam sem emulador, no `:core:test`) compilando e passando
  sem precisar simular sistema de arquivos.
- Consistente com o tratamento que `alignAllocations` já recebia neste mesmo fake, pelo mesmo motivo.
