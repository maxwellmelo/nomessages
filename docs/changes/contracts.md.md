# docs/development/contracts.md

## 2026-09-17 — T4.10: contrato da tarefa de cofre não cita mais o zxcvbn

**Antes:** "passwords validated at setup with zxcvbn".

**Agora:** "passwords validated at setup with the in-repo `estimateStrength` (`PasswordStrength.kt`), no third-party estimator".

**Por quê:** o contrato aponta para a biblioteca que foi removida do projeto na T4.10. Apontar para o arquivo do repositório mantém o contrato verificável por quem ler o código. Detalhes em [`PasswordPolicy.kt.md`](PasswordPolicy.kt.md).

## 2026-09-15 — caminho do schema do QR

**Antes:** o contrato da tarefa de protocolo apontava `core/src/main/proto` como diretório existente.

**Depois:** aponta `docs/development/pairing.proto`, com a nota de que o schema foi movido na T4.5.

**Vantagem e motivo:** o diretório antigo foi removido junto com o plugin protobuf sem uso; a referência quebrada confundiria quem usa os contratos como mapa do repositório.
# docs/development/contracts.md

## 2026-09-15 — caminho do schema do QR

**Antes:** o contrato da tarefa de protocolo apontava `core/src/main/proto` como diretório existente.

**Depois:** aponta `docs/development/pairing.proto`, com a nota de que o schema foi movido na T4.5.

**Vantagem e motivo:** o diretório antigo foi removido junto com o plugin protobuf sem uso; a referência quebrada confundiria quem usa os contratos como mapa do repositório.
