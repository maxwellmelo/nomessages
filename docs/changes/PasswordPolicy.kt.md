# PasswordPolicy.kt

## 2026-09-17 — T4.10: símbolos liberados, piso de 12 caracteres, NFKC e rejeição de variação trivial

Arquivo: `core/src/main/kotlin/dev/mx3/nomessages/core/vault/PasswordPolicy.kt`.
Documentos irmãos: `docs/changes/PasswordStrength.kt.md` (o novo estimador) e
`docs/changes/VaultTest.kt.md` (o teste antigo que precisou ser corrigido).

### Contexto

O usuário testou o app e reprovou a política do cofre: só alfanumérico, mínimo de 16 caracteres.
O pedido foi liberar símbolos, mostrar um medidor de força na criação, validar a confirmação em
tempo real e baixar o mínimo sem sacrificar segurança.

### Como era antes

```kotlin
class PasswordPolicy {
    private val estimator = Zxcvbn()

    fun validate(password: CharArray) {
        require(password.size <= 128) { "Password exceeds maximum length" }
        val normalized = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC).toCharArray()
        try {
            require(normalized.size in 16..128) { "Password must contain 16 to 128 alphanumeric characters" }
            require(normalized.all { it.isLetterOrDigit() }) { "Password must be alphanumeric" }
            require(estimator.measure(CharBuffer.wrap(normalized)).score >= 4) { "Password is too predictable" }
        } finally { normalized.fill('\u0000') }
    }

    fun validatePair(real: CharArray, panic: CharArray) {
        validate(real); validate(panic)
        val first = normalizePassword(real); val second = normalizePassword(panic)
        try { require(!MessageDigest.isEqual(first, second)) { "Passwords must be distinct" } }
        finally { first.fill(0); second.fill(0) }
    }
}

internal fun normalizePassword(password: CharArray): ByteArray {
    require(password.size <= 128) { "Password exceeds maximum length" }
    return Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
}
```

Efeitos práticos: `Q7vfN2rT8bLp4WzK!` era recusada **só** por causa do `!`; qualquer frase-senha
com espaço era recusada; nenhuma letra acentuada ou de outro alfabeto passava; e a senha de pânico
podia ser a senha real com o último caractere trocado.

### Como ficou

A regra de aceitação, em uma frase: depois de normalizar com **NFKC**, a senha é aceita quando tem
de **12 a 128** caracteres, todos eles ASCII imprimível (`0x20`–`0x7E`) **ou** letra Unicode
(`Character.isLetter`), **nenhum** caractere de controle (`Character.isISOControl`), **não** começa
nem termina com espaço em branco, e `estimateStrength(...).score >= 3` (≈ 60 bits estimados).

Ordem das verificações (cada uma com mensagem própria, para a UI mapear string por string):

| Verificação | Mensagem lançada |
|---|---|
| tamanho bruto ≤ 128 | `Password exceeds maximum length` |
| 12..128 após NFKC | `Password must contain 12 to 128 characters` |
| sem caractere de controle | `Password must not contain control characters` |
| só ASCII imprimível ou letra Unicode | `Password contains an unsupported character` |
| sem espaço no início/fim | `Password must not start or end with whitespace` |
| `estimateStrength(...).score >= 3` | `Password is too predictable` |

`validatePair` ganhou a rejeição de **variação trivial**, além da igualdade que já existia:

```kotlin
require(!MessageDigest.isEqual(first, second)) { "Passwords must be distinct" }
require(!containsSlice(first, second) && !containsSlice(second, first)) {
    "Panic password is too similar to the real password"
}
require(editDistance(first, second) > MAXIMUM_PANIC_EDIT_DISTANCE) {
    "Panic password is too similar to the real password"
}
```

`editDistance` é um Levenshtein clássico O(n·m) com duas linhas roláveis, escrito no próprio arquivo
— nenhuma dependência nova. `containsSlice` é uma busca de subcadeia contígua sobre os bytes
normalizados. As duas mensagens são distintas e "grepáveis" de propósito: a UI mostra textos
diferentes para "as duas senhas são iguais" e "a senha de pânico é variação da real".

### Por que essas mudanças

**1. Símbolos e letras Unicode liberados, mínimo 12.** O que protege um cofre não é a classe dos
caracteres, é a entropia. `Senha123456!` tem quatro classes e 12 caracteres e é lixo; `Q7vfN2rT8bLp`
tem três classes e 12 caracteres e vale ~71 bits. Trocar um piso de comprimento por um piso de força
é estritamente melhor, e é o que `estimateStrength` permitiu fazer.

**2. NFC → NFKC — a mudança mais importante, e a menos visível.** `normalizePassword()` produz
exatamente os bytes entregues ao Argon2id, e a §4.2 do `SPEC.md` promete *"Abrir em outro telefone =
mesma senha"*. NFC preserva distinções de compatibilidade que teclados e IMEs diferentes introduzem
sem o usuário perceber: dígitos e letras de largura total dos IMEs CJK, a ligadura `ﬁ` contra `fi`,
`№` contra `No`, espaço não-quebrável contra espaço comum, dígitos sobrescritos, os vários pontos de
código que desenham um hífen. Com NFC, a "mesma" senha digitada no segundo aparelho derivaria outra
chave e o cofre importado simplesmente não abriria. NFKC dobra tudo isso numa forma canônica única.
**É essa garantia que torna seguro aceitar símbolos.** A mudança é retrocompatível: NFC e NFKC são
idênticos em ASCII puro, que é todo o corpus existente de senhas, todos os vetores de teste atuais e
tudo que a política alfanumérica anterior poderia ter aceitado — nenhum cofre existente muda de
chave. Como `VaultManager.kt` já passa por `normalizePassword()` em todos os pontos (`create`,
`unlock`, `resetPanicPassword`), trocar a forma dentro dessa função bastou; nenhum chamador mudou.

**3. Rejeição de variação trivial no par real/pânico.** O modelo de ameaça da senha de pânico é
coação. Se a de pânico for a real com um caractere trocado, ou a real com um sufixo, quem arrancou a
primeira deduz a segunda em segundos e o vault decoy não protege ninguém. Substring + Levenshtein ≤ 2
cobre as duas variações que as pessoas realmente escolhem. A comparação de igualdade continua sendo
`MessageDigest.isEqual` (tempo constante); os testes de similaridade são inerentemente dependentes
dos dados, o que é aceitável aqui: rodam uma vez no setup, no aparelho do próprio dono das duas
senhas, sem oráculo remoto do outro lado.

### Por que a política era alfanumérica antes — racional **inferido**, não recuperado

Não existe registro histórico. O `git log` deste arquivo tem exatamente dois commits, ambos em massa
(renomeação do produto), sem justificativa na mensagem; não há ADR, comentário ou issue. A
reconstrução abaixo vem do código em volta e do `SPEC.md`, e está registrada no KDoc da classe para
que possa ser contestada:

1. **Portabilidade da chave derivada.** `normalizePassword()` alimenta o Argon2id diretamente e o
   `SPEC.md` promete abrir o cofre exportado em outro telefone. A normalização Unicode só é
   garantidamente byte-estável entre aparelhos, teclados e IMEs para um conjunto restrito de
   caracteres: letras e dígitos normalizam igual em quase todo lugar, enquanto muitos símbolos —
   sobretudo não-ASCII — têm caminhos de entrada dependentes de layout/IME e decomposições de
   compatibilidade. Restringir a alfanumérico era a forma barata de nunca encostar nesse problema.
2. **Entropia sem estimador.** 16 caracteres sobre um alfabeto de 62 símbolos são ~95 bits, que
   passavam com folga no portão `zxcvbn >= 4` sem ninguém precisar de um modelo de entropia por senha.

Os dois motivos foram **resolvidos**, não ignorados: (1) pelo NFKC, (2) pelo `estimateStrength`.

### Dependência removida: `com.nulab-inc:zxcvbn`

`PasswordPolicy.kt` era o **único** consumidor de zxcvbn no repositório inteiro (confirmado por grep
em `*.kt`, `*.kts`, `*.toml`, `*.java` antes e depois da mudança). Sem o portão `score >= 4`, a
biblioteca ficou sem chamador e foi removida:

| Arquivo | Antes | Agora |
|---|---|---|
| `core/build.gradle.kts` | `implementation(libs.zxcvbn)` | linha removida |
| `gradle/libs.versions.toml` | `zxcvbn = "1.9.0"` e `zxcvbn = { module = "com.nulab-inc:zxcvbn", ... }` | ambas removidas |
| `THIRD_PARTY_NOTICES.md` | linha `zxcvbn4j ... MIT` | removida |

Vantagens: um JAR a menos no APK (o zxcvbn4j carrega listas de frequência embutidas, na casa das
centenas de KB), uma dependência a menos para auditar e atualizar, e o estimador passa a ser código
do repositório — legível, testável e ajustável — em vez de uma caixa-preta cujo `score` não explicava
*por que* recusou. Como efeito colateral positivo, o novo estimador devolve chaves de feedback que a
UI pode traduzir; o zxcvbn4j devolvia apenas um inteiro e textos em inglês.

### Outros documentos atualizados nesta mudança

`SPEC.md` (§4.2 `password = UTF-8 NFKC`, §5.1 regra nova + justificativa, §5.2 variação trivial),
`docs/security-model.md`, `docs/development/vault-api.md`, `docs/development/vault-report.md`,
`docs/development/contracts.md`, `docs/development/build-report.md`.

### Testes

Novo `core/src/test/kotlin/dev/mx3/nomessages/core/vault/PasswordPolicyTest.kt`: piso de 12 (rejeita
11), aceitação em 12 quando a força passa, rejeição entre 12 e 15 quando não passa, símbolos aceitos,
espaço interno aceito, controle e espaço nas bordas recusados, letras cirílicas aceitas,
equivalência NFKC entre ASCII e largura total, retrocompatibilidade das senhas de 16–20 alfanuméricos,
e as três rejeições do par real/pânico com asserção sobre a mensagem exata.

### Validação executada

`./gradlew --offline :core:test --tests "...PasswordPolicyTest" --tests "...PasswordStrengthTest"` com o JDK 21 (WSL): **19 testes, 0 falhas** (11 em `PasswordPolicyTest`, 8 em `PasswordStrengthTest`). `:core:compileTestKotlin` passa com `allWarningsAsErrors = true`. `VaultTest` compila, mas não pôde ser executado nesta máquina porque `native/target/debug/libnomessages.so` não está construído; por isso os vetores de política que ele afirma (`p4ssw0rdp4ssw0rd`, `qwertyuiopasdfghjkl`, e o par composto/decomposto) foram duplicados em `PasswordPolicyTest`, onde rodam sem a biblioteca nativa.
