# Mudanças em `CONTRIBUTING.md`

## 2026-09-23 — Correção da revisão: âncora `README.md#windows-wsl2` não existe mais

### Como era antes

"Development setup" mandava o leitor para uma âncora que não existe no `README.md` atual:

```markdown
NoMessages does not build natively on Windows. All commands below run inside **WSL2 with Ubuntu
24.04** (or a native Linux host). See the [Windows (WSL2)](README.md#windows-wsl2) section of the
README for the full setup, including the WSL system packages and the pinned Rust installer
commands. Summary of what you need:
```

O README foi reescrito como um README de apresentação (achado 2026-09-23 do mesmo lote de tarefas)
e não tem mais nenhuma seção "Windows (WSL2)" — a seção "Build from source" só linka de volta para
este arquivo. O link, portanto, levava a um clique morto: o navegador rola até o topo do README sem
achar a âncora.

### Como é agora

O conteúdo completo da antiga seção "Windows (WSL2)" do README (pré-requisitos do sistema via
`apt`, instalação do Rust 1.91 com verificação de digest, onde clonar, variáveis
`NOMESSAGES_TOOLS_DIR`/`CARGO_TARGET_DIR` e paralelismo) foi trazido para dentro deste arquivo,
traduzido para inglês, como uma nova subseção `### Windows (WSL2)` logo depois dos comandos de
build em "Development setup". O parágrafo de abertura agora linka para essa âncora local:
`[Windows (WSL2)](#windows-wsl2)`.

### Vantagens

- O link funciona: aponta para uma seção que existe, no mesmo arquivo, sem depender da estrutura
  de outro documento que pode mudar de novo no futuro.
- Nada de conteúdo técnico se perdeu na reescrita do README para um tom de apresentação — ele só
  migrou para o arquivo cujo público (quem vai efetivamente compilar o projeto) precisa dele.

### Por que a mudança foi feita

Achado 1 da revisão desta tarefa: "CONTRIBUTING.md aponta para README.md#windows-wsl2 — confirme se
a âncora existe no README novo; se não, aponte para a seção correta ou inclua os passos no
CONTRIBUTING." A âncora não existia, então os passos foram incluídos aqui.
