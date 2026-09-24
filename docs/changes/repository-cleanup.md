# Limpeza do repositório — remoção do codinome anterior do produto

**Data:** 2026-09-23.

## O que foi feito

Todo vestígio textual e visual do codinome de produto usado antes do nome atual (o
protocolo de 7 letras "What...sfy", suas variantes de maiúsculas/minúsculas, o monograma
de duas letras derivado dele, os magics de protocolo de 4 bytes que carregavam esse
codinome, e nomes de diretório de ferramentas/AVD que o citavam) foi removido do estado
atual dos arquivos do repositório — código-fonte, documentação, scripts, CI e capturas de
tela de desenvolvimento.

O trabalho cobriu:

- **Código-fonte (Kotlin):** comentários que citavam o codinome antigo junto a magics de
  protocolo e domain separators foram reescritos em inglês neutro, descrevendo apenas o
  valor atual, sem narrativa de renomeação.
- **`SPEC.md` e `docs/security-model.md`:** notas de "codinome anterior" e um caminho local
  de ferramentas com o nome antigo foram removidos/atualizados.
- **`docs/development/runtime-catalog.md` e `docs/development/vault-api.md`:** a seção que
  documentava dois "passes" de renomeação de identificadores de protocolo foi reescrita
  para descrever apenas o estado final atual, sem tabela de antes/depois do nome antigo.
- **`docs/changes/`:** o documento que resumia a renomeação de produto e a nota de índice
  que explicava entradas antigas com o codinome foram removidos/reescritos; cerca de 60
  entradas históricas de mudança de arquivo (registros legítimos de "como era antes / como
  ficou" de código real) tiveram os identificadores do codinome antigo trocados
  mecanicamente pelos equivalentes atuais, incluindo o merge de duas entradas cujo nome de
  arquivo ainda citava o codinome antigo para dentro dos documentos atuais correspondentes;
  passagens que descreviam um monograma/token de cor de duas letras específico do codinome
  antigo foram reescritas para descrição genérica.
- **`scripts/`:** caminhos default de ferramentas em `emulator-pair.sh` e
  `emulator-with-qr.ps1` (incluindo o nome de AVD default) foram atualizados, sem alterar o
  comportamento dos scripts (variáveis de ambiente continuam permitindo override).
- **`docs/development/build-logs/`:** logs de texto e dumps de UI que citavam o codinome
  antigo em caminhos, nomes de AVD, nome de pacote ou nome de tarefa Gradle tiveram esses
  identificadores substituídos pelos equivalentes atuais, preservando o log como evidência
  útil (em vez de apagá-lo, já que a maioria é referenciada por outros relatórios).
- **Imagens:** 23 capturas de tela de uma pasta de investigação anterior à renomeação de
  produto foram apagadas (mostravam o nome/ícone antigos), com as referências correspondentes
  em `docs/development/device-verification.md` ajustadas. Além disso, toda captura de tela
  de pasta datada de 2026-09-16/17 cujo conteúdo mostrava a tela de desbloqueio, a barra de
  título do app, ou um estado vazio de contatos/conversas com o monograma ou nome antigo foi
  revisada visualmente e apagada quando confirmado (mais 13 imagens, entre elas duas capturas
  de notificação com o ícone pequeno antigo), com as respectivas referências em documentos de
  sessão ajustadas para não apontar para arquivos inexistentes.

## O que foi verificado ao final

- Busca pelo nome antigo e pelos identificadores de protocolo antigos no estado atual dos arquivos: nenhuma ocorrência.
  ocorrência fora de `README.md` (fora do escopo desta tarefa, tratado por outra sessão).
- `git grep -nE "\bWf\b"` (monograma de duas letras): nenhuma ocorrência textual restante
  em todo o repositório.

## O que ficou fora de escopo (por instrução explícita)

`README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`,
`app/src/main/res/values*/strings.xml`, `.github/ISSUE_TEMPLATE`,
`.github/PULL_REQUEST_TEMPLATE.md` e `CODEOWNERS` foram deixados intocados por esta tarefa
— cobertos por outras sessões em paralelo. A reescrita do histórico do git (para remover o
codinome de commits antigos) também é responsabilidade de uma etapa separada, posterior a
esta.
