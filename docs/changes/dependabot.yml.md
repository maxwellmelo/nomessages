
## 2026-09-24 — agrupamento e exclusões

**Antes:** um PR por dependência, sem limite; na criação do repositório público o Dependabot abriu 14 PRs de uma vez, quatro deles quebrando o build.

**Depois:** atualizações minor e patch agrupadas em um PR semanal por ecossistema (Gradle, Cargo, GitHub Actions), às segundas-feiras, com limite de PRs abertos. Bibliotecas de segurança (libsignal, SQLCipher, OpenMLS, libsodium, Arti) ficam fora do grupo e continuam chegando em PRs individuais para revisão do changelog. Subidas major continuam separadas. Foram ignoradas por ora as versões que exigem migração planejada: Android Gradle Plugin ≥ 9.4, `hmac` ≥ 0.13 e `sha2` ≥ 0.11 (precisam subir juntas), `jni` ≥ 0.22.

**Vantagens e motivo:** menos ruído e menos uso de CI, revisão cuidadosa só onde importa, e nenhum PR sabidamente quebrado reaberto automaticamente. As exclusões devem ser removidas quando as migrações forem feitas.
