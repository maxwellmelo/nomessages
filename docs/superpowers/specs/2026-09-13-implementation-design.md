# NoMessages: desenho de implementação

Fonte de verdade: [SPEC.md](../../../SPEC.md). O pedido de 2026-09-13 autoriza o desenvolvimento de todas as fases. As decisões de produto permanecem as da especificação.

## Estrutura

- `core`: Kotlin/JVM, lógica de vault, arquivos, pareamento, ratchet, grafo, enquadramento e regras de sessão, testáveis fora do Android.
- `app`: Android 12+, Compose, SQLCipher, CameraX, SAF para importar/exportar blobs cifrados, ciclo de vida e integração dos módulos.
- `native`: biblioteca Rust/JNI, primitives libsodium, Arti e OpenMLS. Não há substituto silencioso para uma biblioteca criptográfica indisponível.
- `scripts`: build reproduzível e coleta explícita de evidências dos gates em aparelhos/emuladores.

O código da UI depende de um contrato de estado e ações. O controller mantém uma única sessão, serializa mutações de ratchet e banco, encerra a rede antes de fechar o banco e descarta referências ao estado visível ao bloquear. Senhas nunca são persistidas. Exportações contêm somente dados já cifrados e o import valida limites, duplicatas e traversal antes da substituição atômica.

## Compatibilidade e decisões técnicas

1. O `SPEC.md` serve de desenho aprovado; não reabrir decisões travadas nem solicitar autorizações de fases já abrangidas pelo pedido.
2. Usar APIs efetivamente disponíveis em versões fixas das bibliotecas oficiais. Registrar qualquer divergência necessária antes de alegar conformidade.
3. As garantias de indistinguibilidade absoluta e apagamento de RAM/page cache não são demonstráveis em Android gerenciado. Aplicar mitigação, testar o observável e registrar limites. O próprio layout com dois bancos revela dois contêineres; padding não prova deniability.
4. `Signal` possui sua própria identidade de curva e formato de bundles. Uma chave Ed25519 assina o transcript de pareamento e vincula a identidade Signal; nunca reinterpretar chaves Ed25519 como X25519.
5. O grafo precisa de evidências autenticadas de pareamento entre terceiros. Arestas exigem assinaturas dos dois endpoints; não aceitar declarações unilaterais.
6. Chaves Arti/OpenMLS são serializadas para dentro do vault; arquivos de cache Tor podem conter somente dados públicos. A interrupção do processo de rede no lock é uma fronteira explícita de ciclo de vida.
7. Critério de pronto inclui compilação, testes host, execução Android, dois dispositivos Tor e auditoria externa. Ausência de dispositivo ou de auditor independente é relatada como gate pendente, nunca como teste aprovado.
8. Desvio de compatibilidade identificado nas APIs oficiais: libsignal-client 0.102.2 exige o bundle híbrido PQXDH com prekey Kyber. A integração usa PQXDH + Double Ratchet oficial e registra antecipação do componente híbrido da v1.1, preservando a autenticação presencial. Não reimplementar um X3DH clássico próprio nem afirmar que o handshake é apenas X3DH.

## Validação

Testes de comportamento para cabeçalho adulterado, senha errada/pânico, isolamento de vaults, import malformado, chunks truncados/reordenados, replay/TTL/SAS, ratchet persistido, prova de clique, limites de grupo/frame e encerramento de sessão. Vetores oficiais validam interfaces nativas. Testes instrumentados verificam SQLCipher, flags de janela, lock e export/import. Scripts produzem evidências auditáveis dos gates do SPEC.

## Ambiente inicial

Diretório apenas com SPEC; sem Git, JDK completo ou SDK Android. Disco principal com ~1,8 GiB livres; `/tmp` é tmpfs com ~4 GiB livres. Instalações e builds devem usar diretórios próprios, limitar paralelismo e não limpar dados de outros projetos.
