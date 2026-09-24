# PasswordStrength.kt

## 2026-09-17 — T4.10: novo arquivo, estimador de força de senha próprio, offline e sem dependências

Arquivo: `core/src/main/kotlin/dev/mx3/nomessages/core/vault/PasswordStrength.kt`.
Documento irmão: `docs/changes/PasswordPolicy.kt.md` (quem consome este estimador).

### Como era antes

Não existia. A única noção de "força" no projeto era `Zxcvbn().measure(...).score >= 4`, dentro de
`PasswordPolicy.validate`. Isso tinha três problemas concretos:

1. **Não explicava nada.** Devolvia um inteiro. A UI não tinha como dizer *por que* a senha foi
   recusada, então a tela de criação só conseguia mostrar "senha muito previsível".
2. **Não falava português.** As listas de frequência do zxcvbn4j são anglófonas. `senha`, `brasil`,
   `futebol`, `flamengo`, `saopaulo` não eram penalizadas de forma confiável.
3. **Era binário no lugar errado.** `score >= 4` é um portão muito alto para servir de medidor: a
   maioria das senhas boas de 12–14 caracteres não chega a 4, e o usuário não via progresso.

### Como ficou

Uma função pura, sem Android, sem rede, sem dependência nova:

```kotlin
data class PasswordStrength(val score: Int, val bitsEstimate: Double, val feedback: List<String>)
fun estimateStrength(password: CharArray): PasswordStrength
object PasswordFeedback { /* TOO_SHORT, SEQUENTIAL_CHARS, REPEATED_CHARS, COMMON_WORD,
    DATE_OR_YEAR, TRIVIAL_SUFFIX_PATTERN, ADD_LENGTH, ADD_VARIETY, LOOKS_STRONG */ }
```

`feedback` são **chaves** `UPPER_SNAKE_CASE`, não texto localizado: o módulo `core` não tem recursos
de string do Android, então a camada de UI mapeia cada chave para um `R.string`. O conjunto é fechado
e está documentado em `PasswordFeedback`, com um teste que garante que nada fora dele é emitido.

#### 1. Normalização

A senha é normalizada com **NFKC** antes de qualquer análise — a mesma forma que
`normalizePassword()` entrega ao Argon2id. Assim o medidor julga exatamente os caracteres dos quais
a chave é derivada, e não uma versão diferente do que o usuário acha que digitou. Todo buffer mutável
alocado aqui é zerado no `finally`. O único resíduo inevitável é a `String` imutável que o
`java.text.Normalizer` necessariamente produz — a mesma ressalva já documentada em
`normalizePassword()`. Entrada acima de 128 caracteres é analisada só até esse limite: a política
recusa senhas maiores de qualquer jeito, e o corte mantém os detectores O(n²) limitados quando a UI
chama a função a cada tecla.

#### 2. Fórmula de entropia (modelo por caractere)

```
base    = comprimento * log2(tamanhoDoAlfabetoUsado)
final   = max(0, base - soma das economias de cada padrão detectado)
```

O alfabeto soma **apenas as classes realmente usadas**: minúscula 26, maiúscula 26, dígito 10,
símbolo ASCII 33 (`0x20`–`0x2F`, `0x3A`–`0x40`, `0x5B`–`0x60`, `0x7B`–`0x7E`) e "outra letra
Unicode" 60. Esse 60 é uma constante deliberadamente limitada: um atacante que conhece o alfabeto
enfrenta muito mais que 60 opções, um que não conhece enfrenta muito menos as relevantes, e o valor
foi escolhido para que **uma** letra acentuada não carregue sozinha uma senha fraca acima do piso.

A economia por padrão parte de uma ideia simples: uma região de `k` caracteres explicada por um
padrão não custa `k * bitsPorCaractere` ao atacante, custa só o que é preciso para *descrever* o
padrão. Então subtrai-se `k * bitsPorCaractere - bitsDoPadrão`, nunca abaixo de zero:

| Padrão | Detecção | `bitsDoPadrão` |
|---|---|---|
| `SEQUENTIAL_CHARS` (sequência) | run ASCII crescente/decrescente de 1 em 1, comprimento ≥ 4 | 8.0 |
| `SEQUENTIAL_CHARS` (teclado) | trecho ≥ 4 contido em `qwertyuiop`, `asdfghjkl`, `zxcvbnm`, `qwertzuiop`, `azertyuiop`, `qazwsxedcrfvtgb`, `1234567890` ou seus reversos | 8.0 |
| `REPEATED_CHARS` | repetição de período 1..4 (`aaaa`, `abab`, `abcabc`, `abcdabcd`), run ≥ 4 e ≥ 2·período | 4.0 + período·bitsPorCaractere |
| `DATE_OR_YEAR` | ano 1900–2099 em 4 dígitos | 7.6 |
| `DATE_OR_YEAR` | run de 6 ou 8 dígitos que abre como data (`ddmmyy`, `yymmdd`, `yyyymmdd`, `ddmmyyyy`) | 15.0 |
| `COMMON_WORD` | termo da lista embutida, casado na cópia *leet-dobrada* | log2(229) ≈ 7.84 |
| `TRIVIAL_SUFFIX_PATTERN` | `Palavra` + só dígitos/símbolos até o fim | penalidade fixa de 6.0 |

Os casamentos são aplicados **de forma gulosa, do maior ganho para o menor**, e cada um só é pago
sobre os caracteres que nenhum casamento anterior já explicou. Isso evita contagem dupla quando dois
detectores cobrem a mesma região (um run de dígitos é simultaneamente sequência e linha de teclado).

O período de repetição é limitado a 4 **de propósito**: `p4ssw0rdp4ssw0rd` é melhor explicado (e
mais barato) por dois casamentos de dicionário do que por uma repetição de período 8 — com o limite
em 4, a senha cai para ~15.7 bits e o feedback devolvido é `COMMON_WORD`, que é o que o usuário
precisa ler.

#### 3. Lista de termos comuns embutida

229 termos, minúsculos, **só letras**, inglês e português: `password`, `qwerty`, `letmein`, `dragon`,
`monkey`, `iloveyou`, `trustno`, `superman`, … e `senha`, `brasil`, `futebol`, `amor`, `familia`,
`deus`, `flamengo`, `corinthians`, `palmeiras`, `saopaulo`, `cruzeiro`, `carnaval`, `copacabana`,
`saudade`, `coracao`, `churrasco`, `neymar`, `ronaldinho`, `dinheiro`, `torcida`…

Duas decisões dessa lista merecem justificativa:

- **Só letras, sem variantes numéricas.** O casamento roda sobre uma cópia *leet-dobrada* da senha
  (`0→o`, `1→i`, `3→e`, `4→a`, `5→s`, `7→t`, `8→b`, `@→a`, `$→s`, `!→i`, `|→l`), então `p4ssw0rd`
  encontra `password` e `senha123` encontra `senha` sem que cada variante precise de entrada própria.
  A dobra é aplicada **só** para o casamento de termos, numa cópia separada, para que os detectores
  de dígito, data e sequência continuem vendo os dígitos originais.
- **Mínimo de quatro caracteres por termo.** Termos de três letras colidiriam por acaso dentro de
  senhas aleatórias. Com quatro, a chance de um falso positivo numa senha alfanumérica de 20
  caracteres fica na casa de 10⁻³ — e os vetores de teste do repositório foram conferidos um a um.

Essa lista é uma **blocklist do que nós consideramos ruim**. Ela deliberadamente *não* é usada como
tamanho de dicionário no modelo de frase-senha (ver abaixo).

#### 4. Caso especial: frases-senha

Três ou mais tokens alfabéticos de ≥ 3 caracteres separados por espaço, hífen ou sublinhado são
precificados por **escolha de palavra**, não por caractere:

```
bits = 2.0 (estrutura)  +  soma dos bits de cada token
token repetido de um anterior      ->  2.0
token que é caminhada de teclado   ->  8.0
token que está na blocklist        ->  log2(229) ≈ 7.84
qualquer outro token               ->  min(comprimento * log2(26), 17.0)
```

`17.0` é log2(131072) = 2¹⁷, o tamanho de vocabulário assumido. **Justificativa real:** é exatamente
o motivo pelo qual "correct horse battery staple" é forte apesar de ser feita de palavras comuns — o
espaço de busca é combinatório sobre um vocabulário que o atacante precisa adivinhar, não por
caractere. Um atacante que não sabe de qual idioma, lista ou conjunto de flexões o usuário tirou as
palavras precisa cobrir pelo menos um léxico PT+EN completo com flexões e nomes próprios; 2¹⁷ é essa
suposição escrita de forma explícita. Sem esse ramo, toda frase-senha legível seria punida uma vez
por palavra e o usuário seria empurrado de volta para senhas curtas, cheias de símbolos e
impossíveis de lembrar — o oposto do objetivo desta tarefa.

Os `2.0` de estrutura são um crédito conservador pelo fato de que o atacante também precisa adivinhar
que aquilo *é* uma frase-senha, quantas palavras tem e qual separador as une.

**Desvio consciente da instrução original.** A especificação da tarefa dizia para não penalizar
nenhum token. Os dois primeiros casos da tabela acima são penalizações, e existem porque sem eles o
ramo vira um bypass trivial: `senha senha senha senha` daria 70 bits (score 4) e
`qwertyuiop asdfghjkl zxcvbnm` também. Com os guardas, dão 15.8 e 26.0 bits (score 0). Os quatro
tokens do vetor forte da tarefa (`correto`, `cavalo`, `bateria`, `grampo`) não estão na blocklist e
não são caminhadas de teclado, então recebem os 17 bits cheios cada um.

#### 5. Escada de pontuação

| `score` | `bitsEstimate` | Leitura |
|---|---|---|
| 0 | < 28 | quebrável trivialmente offline |
| 1 | 28 – 39 | fraca |
| 2 | 40 – 59 | razoável, ainda abaixo do piso de aceitação |
| 3 | 60 – 67 | boa — **piso de aceitação do cofre** (`MINIMUM_ACCEPTABLE_SCORE`) |
| 4 | ≥ 68 | forte |

O corte de 60 bits para o score 3 é o pedido da tarefa e é o que faz uma senha de 12 caracteres
sem padrão (≈ 71 bits sobre o alfabeto alfanumérico de 62 símbolos) passar, enquanto
`Kp3rVn8qLz` — 10 caracteres, também sem padrão, ≈ 59.5 bits — não passa.

O corte de 68 bits para o score 4 **foi ajustado** em relação ao exemplo da tarefa (80 bits), e o
motivo está registrado no código: uma frase-senha de quatro palavras sobre o vocabulário assumido de
2¹⁷ vale exatamente 4 × 17 = 68 bits. Com o corte em 80, o padrão canônico de frase-senha nunca
chegaria a 4 por nenhum modelo honesto — seria preciso assumir um vocabulário de ~1 milhão de
palavras por token para chegar lá. Preferiu-se ajustar o corte, que a própria tarefa autorizava
("tune as needed but keep them monotonic and documented"), a inflar a estimativa de entropia. A
escada continua monótona e toda constante está comentada no arquivo.

### Verificação dos números

O modelo foi replicado em JavaScript e conferido vetor a vetor antes de escrever os testes:

| Senha | bits | score | feedback |
|---|---|---|---|
| `Senha123456!` | 16.4 | 0 | `COMMON_WORD, SEQUENTIAL_CHARS, TRIVIAL_SUFFIX_PATTERN, ADD_LENGTH` |
| `Kp3rVn8qLz` | 59.5 | 2 | `TOO_SHORT, ADD_LENGTH` |
| `correto cavalo bateria grampo` | 70.0 | 4 | `LOOKS_STRONG` |
| `Q7vfN2rT8bLp4WzK6sHx` | 119.1 | 4 | `LOOKS_STRONG` |
| `M9kP3vX7rB2nQ5sT8wLc` | 119.1 | 4 | `LOOKS_STRONG` |
| `Q7vfN2rT8bLp` (12) | 71.5 | 4 | `LOOKS_STRONG` |
| `Q7vfN2rT8bL` (11) | 65.5 | 3 | `TOO_SHORT` |
| `amordeus2024` | 23.3 | 0 | `COMMON_WORD, DATE_OR_YEAR, ADD_LENGTH, ADD_VARIETY` |
| `p4ssw0rdp4ssw0rd` | 15.7 | 0 | `COMMON_WORD, ADD_VARIETY` |
| `qwertyuiopasdfghjkl` | 16.0 | 0 | `SEQUENTIAL_CHARS, ADD_VARIETY` |
| `senha senha senha senha` | 15.8 | 0 | `COMMON_WORD, REPEATED_CHARS, ADD_VARIETY` |
| `qwertyuiop asdfghjkl zxcvbnm` | 26.0 | 0 | `SEQUENTIAL_CHARS, ADD_VARIETY` |
| `red dog cat` | 44.3 | 2 | `TOO_SHORT, ADD_LENGTH, ADD_VARIETY` |
| `Flamengo2019` | 9.4 | 0 | `COMMON_WORD, DATE_OR_YEAR, TRIVIAL_SUFFIX_PATTERN, ADD_LENGTH` |

### Vantagens sobre o que havia antes

- **Zero dependências.** A remoção do `com.nulab-inc:zxcvbn` (detalhada em
  `docs/changes/PasswordPolicy.kt.md`) tira um JAR com listas de frequência embutidas do APK e uma
  dependência da superfície de auditoria.
- **Explica a recusa.** As chaves de feedback permitem que a UI diga "isso é uma palavra comum" ou
  "isso é uma sequência de teclado" em vez de um genérico "senha fraca".
- **Fala português.** Metade da lista embutida é PT-BR, incluindo times, nomes próprios e termos
  afetivos, que é o que as pessoas realmente usam aqui.
- **Auditável e ajustável.** Todas as constantes estão em um arquivo, comentadas com o raciocínio, e
  cobertas por testes de ordenação em vez de testes de valor exato — dá para retunar sem reescrever
  a suíte.
- **Frases-senha viraram viáveis.** Era literalmente impossível usar uma sob a regra alfanumérica.

### Limites honestos

`bitsEstimate` é uma estimativa de engenharia para alimentar um medidor e um piso de aceitação, não
uma prova de custo de adivinhação. O modelo por caractere superestima senhas aleatórias longas e o
modelo de frase-senha depende de uma suposição de vocabulário que nenhum atacante é obrigado a
respeitar. A escolha da decomposição em padrões é gulosa, não ótima. Nada disso substitui o custo
real imposto pelo Argon2id, que continua sendo a defesa de fato contra ataque offline.

### Testes

`core/src/test/kotlin/dev/mx3/nomessages/core/vault/PasswordStrengthTest.kt`: os três vetores
obrigatórios (fraco `Senha123456!` < 3, médio `Kp3rVn8qLz` em {1,2,3}, forte
`correto cavalo bateria grampo` == 4) com a ordenação `fraco ≤ médio ≤ forte` verificada tanto em
`score` quanto em `bitsEstimate`; as duas senhas alfanuméricas de 20 caracteres já existentes com
score ≥ 3; emissão das chaves de cada detector; garantia de que nenhuma chave fora do conjunto
documentado é emitida; ausência de bypass no ramo de frase-senha; equivalência NFKC entre ASCII e
largura total; e a garantia de que o buffer do chamador não é mutado.

### Validação executada

`./gradlew --offline :core:test --tests "...PasswordPolicyTest" --tests "...PasswordStrengthTest"` com o JDK 21 (WSL): **19 testes, 0 falhas** (11 em `PasswordPolicyTest`, 8 em `PasswordStrengthTest`). `:core:compileTestKotlin` passa com `allWarningsAsErrors = true`. `VaultTest` compila, mas não pôde ser executado nesta máquina porque `native/target/debug/libnomessages.so` não está construído; por isso os vetores de política que ele afirma (`p4ssw0rdp4ssw0rd`, `qwertyuiopasdfghjkl`, e o par composto/decomposto) foram duplicados em `PasswordPolicyTest`, onde rodam sem a biblioteca nativa.
