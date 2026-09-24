# Mudanças em `docs/development/native-report.md`

## 2026-09-15 — Seção nova "Live Tor delivery — 2026-09-15" (T2.2)

### Escopo desta seção

Só documentação. Nenhuma linha de código foi tocada nesta rodada. O que mudou
foi o registro do resultado do gate Tor ao vivo, que passou hoje quatro vezes
seguidas.

### Como era antes

O arquivo terminava com dois blocos que descreviam **falhas** do gate ao vivo e
nada mais:

- O parágrafo do Rustls: "The focused regression passed bootstrap but timed out
  connecting to its synthetic onion service after 350 seconds... This is a
  failed live gate, not evidence of successful delivery or a proven application
  defect... Live delivery is not yet verified."
- O último parágrafo do arquivo, sobre a sonda Kotlin/JNI isolada: "It failed
  inside `TorNative.start` after 184.1 seconds... This is a failed live gate;
  the cause is still unresolved. No identical retry was launched."

Ou seja: quem lesse o relatório concluía, corretamente para a data, que a
entrega ao vivo nunca tinha acontecido e que a causa era desconhecida.

### Como ficou

Os dois parágrafos históricos **permanecem intactos** — são o registro do que se
sabia então. Abaixo deles entrou uma seção nova, `## Live Tor delivery —
2026-09-15`, com:

1. O contexto do ambiente: rede residencial, WSL Ubuntu 24.04, identidade onion
   nova e diretórios de estado/cache novos a cada passagem, **nenhuma bridge**
   configurada em nenhuma das quatro.
2. Uma tabela com as quatro passagens — log, harness, bootstrap, tempo até a
   publicação do descritor, tempo até o primeiro frame depois da publicação e
   resultado:

   | Log | Bootstrap | Descritor | Primeiro frame | Resultado |
   |---|---|---|---|---|
   | `tor-live-20260915T133138Z.log` | frio, não cronometrado separadamente no log | 102,5 s após o bootstrap | 3,9 s | `1 passed`, 134,20 s no total |
   | `tor-delivery-probe-20260915T133138Z.log` | 16,4 s | +20,2 s, aos 36,6 s | 5,6 s, aos 42,2 s | PASS aos 43,4 s |
   | `tor-delivery-probe-20260915T133510Z.log` | 14,5 s | +18,0 s, aos 32,4 s | 7,1 s, aos 39,5 s | PASS aos 41,1 s |
   | `tor-delivery-probe-20260915T133553Z.log` | 56,6 s | +14,1 s, aos 70,7 s | 5,4 s, aos 76,1 s | PASS aos 78,2 s |

3. O envelope medido nas três sondas JNI (bootstrap 14,5–56,6 s; publicação
   14,1–20,2 s depois; primeiro frame 5,4–7,1 s após a publicação; PASS
   41,1–78,2 s) e a explicação do ponto fora da curva: o teste Rust publicou em
   102,5 s porque arrancou frio, sem checkpoint de guards persistido e com
   retentativas de diretório visíveis no próprio log — mas foi justamente ele
   que teve o menor tempo até o primeiro frame, 3,9 s.
4. A causa raiz das falhas anteriores: `start` devolvia e marcava ONLINE logo
   depois de `launch_onion_service_with_hsid`, sem consumir `status_events()`;
   o self-connect começava antes de o descritor subir aos HSDirs e antes de
   existirem intro points, então o cliente não tinha a que se conectar e a
   tentativa consumia o prazo inteiro. Corrigido em T2.1.
5. A distinção explícita entre "não reproduzido" e "resolvido" para as falhas de
   guard do host anterior: elas não aconteceram aqui; a hipótese de rede
   restrita naquele host **não foi provada**, apenas não se reproduziu.
6. O que continua fora deste gate, citando a própria linha de PASS da sonda
   ("device socket accounting and messaging remain separate gates"):
   contabilidade de sockets por PID em aparelho real e mensageria real sobre o
   transporte, ambas na Fase 3. E o lembrete de que estas passagens são de host
   (WSL) e auto-endereçadas: um processo alcançando o próprio serviço onion.

### Por que a mudança foi feita

O `native-report.md` é o documento que a release-checklist e o `build-report.md`
citam quando alguém pergunta "a entrega ao vivo funciona?". Enquanto ele
terminasse em dois parágrafos de falha, a resposta documentada era "não", e o
ledger do `build-report.md` não podia mover a linha para Passed sem contradizer
sua própria fonte. A seção nova fecha o "Feito quando" de T2.2, que exige o
tempo-até-primeiro-frame registrado exatamente aqui.

### Idioma

O arquivo é predominantemente em inglês; a seção nova foi escrita em inglês para
não quebrar a leitura. Este registro de mudança é em português, como manda a
regra de `docs/changes`.

### Vantagens

- O número que importa para dimensionar timeouts de produto — tempo até o
  primeiro frame **depois** da publicação — está medido quatro vezes e fica em
  segundos de um dígito, não em minutos. Quem for ajustar prazos não precisa
  mais chutar.
- A separação entre bootstrap, publicação e conexão deixa claro qual das três
  fases domina o tempo total (é a publicação, e ela varia com o estado dos
  guards), o que é a informação acionável para a próxima otimização.
- O relatório passa a distinguir três coisas que antes se confundiam: causa
  corrigida, hipótese não reproduzida e gate ainda não executado.

### O que **não** foi verificado

- Entrega entre dois aparelhos, comportamento em rede móvel e o ciclo de vida do
  processo `:tor` no Android continuam sem execução.
- Contabilidade de sockets por PID não foi medida; segue como gate de Fase 3.
- A hipótese de que a rede do host anterior bloqueava guards permanece hipótese.
