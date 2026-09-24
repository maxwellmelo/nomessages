# app/src/test/kotlin/dev/mx3/nomessages/runtime/DoorbellKnockPolicyTest.kt

## 2026-09-18 — Campainha, fase 4: teste JVM da agenda de toques (arquivo novo, T4.17)

### Como era antes

Não havia teste nenhum da campainha em nenhum módulo. As fases 1–3 entregaram o opcode nativo, as
colunas e a ponte IPC, todas verificadas só por compilação (e, no caso do nativo, pelos testes do
próprio crate). A suíte JVM do módulo `app` tinha 61 casos e nenhum deles mencionava campainha.

Antes desta fase também não existia a regra a testar: "quando tocar" não estava escrito em lugar
nenhum.

### Como ficou

13 casos JUnit4, mesmo estilo do `TransportSupervisorTest` (relógio injetado como parâmetro,
nenhum `Thread.sleep`, nenhum mock, nenhum Robolectric):

| Caso | O que prova |
| --- | --- |
| `aContactThatWasNeverKnockedIsEligible` | Ausência de registro ⇒ pode tocar |
| `neverKnocksBeforeTenMinutesHavePassed` | Nada toca em `t`, `t+1` nem `t+piso-1` |
| `knocksExactlyAtTheTenMinuteBoundary` | Toca **exatamente** no limite (`>=`, não `>`) e `nextEligible` confere |
| `backoffDoublesWithConsecutiveFailuresUpToATwoHourCeiling` | A curva é 10/20/40/80/120 min e satura no teto |
| `eachFailureStretchesTheWaitAndTheCeilingStopsIt` | A espera cresce a cada falha consecutiva ao longo de uma sequência real |
| `aConfirmedKnockResetsTheGrowthButNotTheFloor` | Sucesso zera as falhas **e mantém** o piso de 10 min |
| `theFailureCounterSaturatesInsteadOfOverflowing` | `Int.MAX_VALUE` não vira negativo |
| `aRecordStampedInTheFutureDoesNotSilenceTheDoorbellForever` | Relógio para trás ⇒ elegível, e custa um toque só |
| `anAttemptOnlyMovesTheStampAndNeverTheCounter` | `attempted` não mexe nas falhas |
| `aNegativeFailureCountIsRejected` | Contador negativo não é representável nem agendável |
| `theStoredRecordIsTwelveBigEndianBytesAndRoundTrips` | O formato persistido é byte a byte o esperado |
| `anUnreadableRecordCountsAsNeverKnockedInsteadOfBlockingTheDoorbell` | `null`/tamanho errado ⇒ "nunca tocado" |
| `aCorruptFailureCountIsReadBackAsZeroRatherThanRejected` | Contador corrompido satura em zero, não explode |

O caso do formato binário fixa o resultado literal (`0x0000018BCFE56800` + `0x00000003`) em vez de
só fazer ida-e-volta: um round-trip sozinho passaria mesmo se alguém trocasse a ordem dos bytes,
o que quebraria silenciosamente todo registro já gravado em cofres existentes.

### Vantagens

- A parte da feature que é pura decisão fica coberta sem nenhuma das dependências caras da
  campainha (Tor ao vivo, dois aparelhos, o processo `:tor`).
- Os limites são testados nas bordas exatas (`piso-1`, `piso`, teto, saturação), que é onde erros de
  `>` vs `>=` e de overflow costumam morar.
- O formato de `opaque_blobs` passa a ter um teste de regressão: mudar `encode` sem mudar `decode`
  (ou vice-versa) quebra a suíte em vez de corromper agendas em campo.

### Validação

`:app:testDebugUnitTest` (suíte inteira do módulo `app`, WSL, `--offline`): `BUILD SUCCESSFUL`,
74 testes, 0 falhas, 0 erros, 1 pulado — o pulado é pré-existente, em `QrDecodingTest`.
