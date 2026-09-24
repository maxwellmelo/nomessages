# NoMessagesControllerErrorMappingTest.kt

## 2026-09-23 — arquivo novo (T4.6)

**Antes:** não existia. Os erros de mensageria chegavam à UI como texto literal ou mensagem genérica, e as strings `error_*` traduzidas não tinham caminho de código.

**Depois:** teste JVM que cobre a função pura de mapeamento `MessagingErrorCode` → recurso de string, garantindo que cada código de erro resolve para uma string localizada e que nenhuma mensagem de exceção crua chega ao usuário. O contexto completo está no adendo de 2026-09-23 em `docs/changes/NoMessagesController.kt.md`.

**Vantagens e motivo:** trava a regressão que T4.6 corrigiu (lint `UnusedResources` zerado porque os textos voltaram a ser usados) e documenta o contrato do mapeamento.
