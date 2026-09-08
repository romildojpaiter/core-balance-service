# Feature Specification: Core Banking — Saldo Corrente por Eventos

**Feature Branch**: `002-core-banking-balance`

**Created**: 2026-09-06

**Status**: Draft

**Input**: User description: "Specification funcional do core-banking-service: consumir eventos financeiros via Kafka, validar, determinar elegibilidade, atualizar o saldo corrente da conta, proteger o estado contra eventos duplicados e fora de ordem, e disponibilizar consulta de saldo via REST."

**Constitution**: `.specify/memory/constitution.md` v1.0.0 — esta specification é subordinada aos 15 princípios ali definidos.

**Relação com `specs/001-balance-update-api`**: esta specification **substitui** a 001. A 001 foi escrita antes da
constitution e não contempla elegibilidade (`transaction.status` / `account.status`), DLQ, semântica de offset nem
comportamento de erro. Onde as duas divergirem, prevalece esta. As divergências deliberadas estão listadas em
[Divergências em relação à 001](#divergências-em-relação-à-001).

## Clarifications

### Session 2026-09-07

- Q: O corpo da resposta deve conter exatamente as quatro chaves da amostra (`id`, `owner`, `balance`, `updated_at`), eliminando `lastTransactionId`? → A: **Sim.** O corpo passa a ser exatamente as quatro chaves; `accountId` é renomeado para `id` e `lastTransactionId` sai da resposta. Ele **continua persistido** — é o que detecta reentrega (INV-005) — e continua nos logs estruturados via MDC, de modo que a rastreabilidade muda de lugar, não desaparece
- Q: Quando a conta não tiver titular conhecido, o campo `owner` deve ser omitido da resposta ou aparecer como `null`? → A: **A situação não deve existir.** Toda conta está relacionada a um titular, portanto `account.owner` passa a ser **estruturalmente obrigatório** (FR-003): um evento sem ele é **mensagem inválida**, vai para a DLQ sem retry (linha 6 da matriz) e é contabilizado como tal. Na resposta REST, `owner` é sempre presente e nunca nulo
- Q: O saldo na resposta REST deve sair como número JSON (`183.12`) ou como texto entre aspas (`"183.12"`)? → A: **Número JSON**, serializado a partir de decimal exato com escala 2 preservada. **Revisa a FR-044 e a decisão de 2026-09-06 sobre este campo.** A Constitution X permanece intacta: ela proíbe *tipos* de ponto flutuante binário e exige representação decimal exata — a saída do serviço continua exata (`150.00` é emitido como `150.00`, não `150`), e a eventual perda ocorre no parser do cliente, fora da fronteira do sistema
- Q: O saldo deve continuar saindo como número JSON, ou voltar a sair como texto, depois de uma resposta em execução ter sido observada como `"amount": 150`? → A: **Manter número JSON** — a decisão acima é **reafirmada**, não revista. O `150` observado foi produzido pelo cliente que exibiu a resposta, não pelo serviço: reproduzido byte a byte, o corpo emitido era `150.00`. Clientes JavaScript (Postman, Insomnia, REST Client, DevTools) executam `JSON.parse`, que lê o literal num `double` de 64 bits — onde `150.00` e `150` são o mesmo valor — e reimprimem a forma mais curta. A amostra do desafio usa `183.12`, um valor **sem zero à direita**, então o efeito nunca aparece nela; só valores terminados em zero são afetados
- Q: Como a SC-009 deve medir "nenhum valor monetário exibido difere do valor recebido", agora que o valor emitido e o valor exibido pelo cliente podem divergir? → A: A SC-009 passa a medir o valor **emitido pelo serviço** e o **persistido**, e passa a **nomear o método de aferição**: corpo bruto da resposta, nunca valor já desserializado. A renderização no cliente vai para *Out of Scope*. O motivo de fixar o método é concreto: tanto a validação por esquema quanto as asserções sobre o corpo já convertido aprovam `150` e `150.00` indistintamente, porque ambas leem o valor depois de convertido a um tipo numérico — um critério que não diga *onde olhar* passaria com o sistema quebrado. As ferramentas concretas afetadas estão nomeadas em plan.md §6 e no contrato

### Session 2026-09-06

- Q: Evento sem `transaction.status`/`account.status`, ou com valor desconhecido, é mensagem inválida (DLQ) ou evento inelegível (ignorado)? → A: Inelegível — ignorado de forma observável, offset confirmado, sem DLQ; ausente e desconhecido têm o mesmo tratamento
- Q: A resposta do `GET /balances/{accountId}` segue a amostra do desafio (`id`/`owner`/`updated_at`, saldo como número JSON) ou o contrato da spec (`accountId`/`lastTransactionId`/`lastEventAt`, saldo como texto)? → A: ~~O contrato da spec (FR-043 a FR-046); a amostra é ilustrativa e não normativa~~ — **superada em 2026-09-07**: a amostra do desafio é normativa para o corpo da resposta
- Q: Em empate de `transaction.timestamp` entre transações diferentes da mesma conta, qual evento prevalece? → A: Comparação estritamente maior (`>`) — vence o primeiro aplicado, o segundo é rejeitado como `TIMESTAMP_TIE_REJECTED`; sem desempate determinístico
- Q: Mensagem no formato `{"account": {...}}`, sem o bloco `transaction`, vai para a DLQ ou é descartada? → A: Descartada de forma observável (`UNSUPPORTED_MESSAGE_TYPE`), sem DLQ; bloco `transaction` presente porém incompleto continua indo para a DLQ
- Q: `transaction.timestamp` está em microssegundos ou milissegundos desde a época Unix? → A: Microssegundos; o valor é persistido como recebido e convertido para data-hora apenas na resposta da consulta (FR-046)
- Q: Qual o nome e o formato do campo de instante na resposta REST? → A: **Revisa a decisão anterior sobre este campo.** O campo chama-se `updated_at` e é renderizado como ISO-8601 com offset local e milissegundos (`2025-07-05T18:04:13.433-03:00`), conforme a amostra do desafio. Os demais campos permanecem no contrato da spec: `accountId`, `balance.amount` como **texto**, `balance.currency`, `lastTransactionId` — **superado em 2026-09-07**, ver a sessão acima

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consultar o saldo atual de uma conta (Priority: P1)

Como cliente do banco, quero consultar o saldo corrente da minha conta para saber quanto tenho disponível e tomar decisões financeiras informadas.

**Why this priority**: É a entrega de valor visível do sistema. Sem a consulta, todo o processamento de eventos
é invisível para o usuário final.

**Independent Test**: Pode ser testada isoladamente populando o estado de uma conta e verificando as respostas do endpoint de consulta para conta existente, conta inexistente e identificador inválido.

**Acceptance Scenarios**:

1. **Given** a conta `acc-12345` possui estado corrente persistido (saldo `150.00 BRL`, originado do evento
   `tx-002` com timestamp `1725552000000200`), **When** o cliente consulta `GET /balances/acc-12345`,
   **Then** o sistema responde `200 OK` com o identificador da conta, o saldo `150.00`, a moeda `BRL`,
   o identificador da última transação aplicada e o instante do snapshot aplicado.

2. **Given** a conta `acc-99999` nunca teve nenhum evento elegível aplicado, **When** o cliente consulta
   `GET /balances/acc-99999`, **Then** o sistema responde `404 Not Found` com um corpo de erro que identifica
   a conta como desconhecida — o sistema **não** inventa um saldo zero.

3. **Given** um identificador de conta que viola o formato aceito (vazio, com espaços ou com caracteres fora
   de `[A-Za-z0-9-]`), **When** o cliente consulta o endpoint, **Then** o sistema responde `400 Bad Request`
   sem consultar o estado persistido.

4. **Given** o repositório de estado está indisponível, **When** o cliente consulta um saldo,
   **Then** o sistema responde `500 Internal Server Error` sem expor detalhes internos e registra o erro.

5. **Given** um evento elegível acabou de ser aplicado para a conta `acc-12345`, **When** o cliente consulta
   o saldo imediatamente depois, **Then** a resposta reflete o snapshot recém-aplicado — nunca um estado anterior.

---

### User Story 2 - Aplicar o snapshot de saldo de um evento elegível (Priority: P1)

Como sistema de core banking, preciso consumir eventos financeiros e persistir o saldo que eles carregam,
para que o estado corrente de cada conta reflita a informação mais recente da origem autoritativa.

**Why this priority**: É a ingestão que alimenta todo o restante. Sem ela não existe saldo para consultar.

**Independent Test**: Pode ser testada publicando eventos elegíveis no tópico e verificando o estado corrente
resultante de cada conta.

**Acceptance Scenarios**:

1. **Given** a conta `acc-12345` não possui nenhum estado persistido, **When** é consumido um evento com
   `transaction.status = APPROVED`, `account.status = ENABLED`, `account.balance.amount = 100.00`,
   `account.balance.currency = BRL` e `transaction.timestamp = 1725552000000100`, **Then** o estado corrente
   da conta passa a ser `100.00 BRL` com marcador de frescor `1725552000000100`, e o evento é confirmado.

2. **Given** a conta `acc-12345` possui estado corrente `100.00 BRL` com marcador `1725552000000100`,
   **When** é consumido um evento elegível com `balance.amount = 150.00` e timestamp `1725552000000200`,
   **Then** o estado corrente passa a ser `150.00 BRL` com marcador `1725552000000200`.

3. **Given** um evento elegível de uma transação do tipo `DEBIT` com `transaction.amount = 30.00` e
   `account.balance.amount = 70.00`, **When** o evento é processado, **Then** o saldo persistido é `70.00` —
   o valor carregado em `account.balance`, e **não** o resultado de qualquer aritmética sobre o saldo anterior.

4. **Given** um evento elegível cujo `account.balance.amount` é negativo, **When** o evento é processado,
   **Then** o saldo negativo é persistido normalmente (não há validação de limite nesta versão).

---

### User Story 3 - Ignorar eventos inelegíveis sem corromper o estado (Priority: P1)

Como sistema de core banking, preciso ignorar eventos que não representam uma mudança autoritativa de saldo,
para que uma transação recusada ou uma conta desabilitada nunca sobrescrevam um saldo válido.

**Why this priority**: É uma regra de integridade financeira direta (Constitution I e IX). Sem ela, um evento
`DECLINED` sobrescreveria um saldo correto.

**Independent Test**: Pode ser testada publicando eventos `DECLINED` e eventos de contas `DISABLED` e verificando
que nem o saldo nem o marcador de frescor mudam, e que a decisão é observável.

**Acceptance Scenarios**:

1. **Given** a conta `acc-12345` possui estado corrente `150.00 BRL` com marcador `1725552000000200`,
   **When** é consumido um evento com `transaction.status = DECLINED`, timestamp `1725552000000300` e
   `account.balance.amount = 999.00`, **Then** o saldo permanece `150.00`, o marcador permanece
   `1725552000000200`, o descarte é registrado com o motivo `TRANSACTION_NOT_APPROVED`, e o evento é confirmado.

2. **Given** a conta `acc-12345` possui estado corrente `150.00 BRL` com marcador `1725552000000200`,
   **When** é consumido um evento com `account.status = DISABLED`, timestamp `1725552000000300` e
   `account.balance.amount = 999.00`, **Then** o saldo permanece `150.00`, o marcador permanece
   `1725552000000200`, o descarte é registrado com o motivo `ACCOUNT_NOT_ENABLED`, e o evento é confirmado.

3. **Given** um evento com `transaction.status = DECLINED` **e** `account.status = DISABLED`,
   **When** o evento é processado, **Then** ele é descartado e a observabilidade registra ambos os motivos.

4. **Given** um evento inelegível foi descartado para a conta `acc-12345`, **When** chega em seguida um evento
   elegível com timestamp **maior que o do evento descartado, porém ainda maior que o marcador persistido**,
   **Then** o evento elegível é aplicado normalmente — o descarte anterior não bloqueou nem antecipou o marcador.

---

### User Story 4 - Proteger o estado contra eventos fora de ordem e duplicados (Priority: P1)

Como sistema de core banking, preciso garantir que um snapshot antigo ou repetido nunca substitua um snapshot
mais recente, para que o saldo não regrida.

**Why this priority**: É o coração do desafio e a garantia de corretude central (Constitution V).

**Independent Test**: Pode ser testada publicando eventos em ordem embaralhada e repetida para a mesma conta e
verificando que o saldo final corresponde sempre ao evento de maior timestamp elegível.

**Acceptance Scenarios**:

1. **Given** a conta `acc-12345` possui estado corrente `150.00 BRL` com marcador `1725552000000200`,
   **When** é consumido um evento elegível com timestamp `1725552000000150` e `balance.amount = 120.00`,
   **Then** o estado permanece `150.00` com marcador `1725552000000200`, a rejeição é registrada como
   `STALE_EVENT`, e o evento é confirmado.

2. **Given** o evento `tx-002` (timestamp `1725552000000200`, `balance.amount = 150.00`) já foi aplicado à conta
   `acc-12345`, **When** exatamente o mesmo evento é consumido novamente (qualquer número de vezes),
   **Then** o estado permanece `150.00` com marcador `1725552000000200`, o fato é registrado como `DUPLICATE_EVENT`
   (identificado por `transaction.id` igual ao da última transação aplicada), e o evento é confirmado.

3. **Given** a conta `acc-12345` possui estado corrente aplicado a partir de `tx-002`
   (timestamp `1725552000000200`), **When** é consumido um evento **diferente** (`tx-777`) com timestamp
   **exatamente igual** `1725552000000200` e `balance.amount = 180.00`, **Then** o evento **não** é aplicado —
   o estado permanece `150.00` — e o fato é registrado como `TIMESTAMP_TIE_REJECTED`
   (ver [Assumptions](#assumptions), A-03).

4. **Given** os eventos elegíveis T1 (ts `...100`, `100.00`), T2 (ts `...200`, `150.00`) e T3 (ts `...150`,
   `120.00`) para a mesma conta, **When** eles são consumidos em qualquer ordem de chegada,
   **Then** o saldo final é sempre `150.00` — o snapshot de maior timestamp.

---

### User Story 5 - Manter consistência sob processamento concorrente (Priority: P2)

Como operador do sistema, preciso poder escalar consumers e instâncias horizontalmente sem que o saldo seja
corrompido por atualizações concorrentes da mesma conta.

**Why this priority**: Depende das regras de ordenação já definidas, mas é o que permite o sistema operar
sob carga real sem perda de atualização.

**Independent Test**: Pode ser testada disparando atualizações simultâneas para a mesma conta a partir de
múltiplos processos e verificando o estado final.

**Acceptance Scenarios**:

1. **Given** a conta `acc-12345` sem estado persistido, **When** dois eventos elegíveis são processados
   simultaneamente por consumers distintos — evento A (timestamp `...200`, `150.00`) e evento B
   (timestamp `...250`, `180.00`) — **Then** o estado final é `180.00` com marcador `...250`,
   independentemente da ordem em que as escritas foram tentadas.

2. **Given** duas instâncias da aplicação processando eventos da mesma conta ao mesmo tempo,
   **When** a instância que processa o evento mais antigo tenta escrever depois da que processa o mais recente,
   **Then** a escrita mais antiga é rejeitada pela condição de frescor e registrada como `STALE_EVENT` —
   nenhuma atualização é perdida e o estado não regride.

3. **Given** N eventos elegíveis para a mesma conta processados concorrentemente,
   **When** todos completarem, **Then** o estado final é determinístico: o snapshot de maior timestamp,
   qualquer que tenha sido o entrelaçamento das execuções.

---

### User Story 6 - Tratar falhas sem perder nem corromper eventos (Priority: P2)

Como operador do sistema, preciso que falhas de infraestrutura e mensagens inválidas sejam tratadas de forma
previsível, para que nenhuma mensagem seja silenciosamente perdida nem bloqueie a partição indefinidamente.

**Why this priority**: Sem uma política explícita de retry/DLQ, uma única mensagem ruim trava o processamento
de todas as contas atrás dela na partição.

**Independent Test**: Pode ser testada injetando falhas transitórias e permanentes e verificando retry,
roteamento para DLQ, confirmação de offset e telemetria.

**Acceptance Scenarios**:

1. **Given** uma mensagem que não pode ser interpretada como um evento financeiro válido (JSON malformado,
   campo obrigatório ausente, timestamp não numérico ou valor monetário inválido), **When** ela é consumida,
   **Then** nenhum saldo é alterado, a mensagem é roteada para a DLQ com o motivo da falha, o evento é
   confirmado, e **nenhum retry** é executado.

2. **Given** o repositório de estado retorna uma falha transitória (timeout, throttling, indisponibilidade
   temporária), **When** um evento elegível é processado, **Then** o sistema tenta novamente com política
   limitada e backoff, o offset **não** é confirmado enquanto o desfecho não for terminal, e cada tentativa
   é observável.

3. **Given** um evento cujo processamento continua falhando após esgotar a política de retry,
   **When** o limite é atingido, **Then** a mensagem é roteada para a DLQ com o histórico da falha,
   o offset é confirmado somente após o aceite da DLQ, e o sistema segue processando as mensagens seguintes.

4. **Given** a aplicação é encerrada abruptamente após aplicar o snapshot mas antes de confirmar o offset,
   **When** ela reinicia, **Then** o mesmo evento é reprocessado e tratado como duplicado — o saldo não muda.

5. **Given** a DLQ está indisponível, **When** uma mensagem precisa ser roteada para ela,
   **Then** o offset **não** é confirmado e o roteamento é tentado novamente — a mensagem nunca é descartada.

---

### Edge Cases

- **Evento sem `account.id`, sem `transaction.id` ou sem `transaction.timestamp`** → mensagem inválida:
  DLQ, sem retry, sem alteração de estado.
- **Evento sem `account.owner`** → mensagem inválida: DLQ, sem retry (FR-003a, decidido em 2026-09-07). Toda
  conta pertence a um titular, então um evento sem ele descreve uma conta que não pode existir. O saldo
  daquela conta **não é atualizado**, mesmo que o snapshot em si seja válido — consequência aceita
  explicitamente sob Constitution I.
- **Evento sem `account.balance` ou sem `account.balance.amount`** → mensagem inválida: DLQ. Um evento elegível
  sem snapshot não tem o que aplicar.
- **Evento sem `account.balance.currency`** → mensagem inválida: DLQ. A moeda não é inferida nem assumida.
- **`transaction.status` ou `account.status` ausente ou com valor desconhecido** → o evento é **inelegível**
  (falha o critério "é exatamente `APPROVED`" / "é exatamente `ENABLED`"), não inválido: descartado de forma
  observável, sem DLQ. Ver FR-008a.
- **`account.balance.amount` com mais de 2 casas decimais** → mensagem inválida: DLQ, pois o arredondamento
  implícito de um valor monetário é proibido (Constitution X).
- **Moeda diferente da moeda já persistida para a conta** → o snapshot é aplicado (o evento é autoritativo),
  porém a troca de moeda é registrada como anomalia observável. Ver A-07.
- **Timestamp no futuro** → aceito. O sistema não valida relógio de origem; o timestamp é apenas o critério
  de ordenação. Ver A-08.
- **Conta que só recebeu eventos inelegíveis** → permanece sem estado corrente; a consulta retorna `404`.
- **Mensagem sem chave de particionamento** → processada normalmente. A corretude não depende da chave
  (Constitution VII); a ausência de chave apenas aumenta a contenção e é registrada como anomalia.
- **Evento cujo `account.id` difere da chave da mensagem** → o `account.id` do payload é a autoridade;
  a divergência é registrada como anomalia observável.
- **Mensagem de tipo `{"account": {...}}` sem bloco `transaction`** (formato produzido por
  `make kafka-produce-accounts-events`) → **descartada de forma observável** como tipo de mensagem não
  suportado, sem DLQ e sem retry (FR-004a). Não é defeito de dados: é uma mensagem de outro fluxo. Ver
  [Out of Scope](#out-of-scope).

## Requirements *(mandatory)*

### Functional Requirements

#### Ingestão de eventos

- **FR-001**: Sistema DEVE consumir eventos financeiros de um tópico de mensagens cujo nome é fornecido por
  configuração. Nenhum nome de tópico é fixado por esta specification.
- **FR-002**: Sistema DEVE consumir esse tópico usando um único consumer group dedicado, cujo identificador é
  fornecido por configuração.
- **FR-003**: Sistema DEVE interpretar cada mensagem como um evento contendo os seguintes campos
  **estruturalmente obrigatórios**: `transaction.id`, `transaction.timestamp`, `account.id`,
  **`account.owner`**, `account.balance.amount` e `account.balance.currency`. O evento também transporta
  `transaction.status` e `account.status`, que **não** são estruturalmente obrigatórios: sua ausência ou
  seu preenchimento com valor desconhecido é tratada pela regra de elegibilidade (FR-008a), não pela
  regra de validade.
- **FR-003a**: `account.owner` é **estruturalmente obrigatório** (decidido em 2026-09-07): no domínio
  do problema toda conta pertence a um titular, então um evento sem ele descreve uma conta que não pode
  existir e é um defeito do produtor, não um caso de negócio. Consequência aceita explicitamente: um
  evento que traga um saldo perfeitamente válido mas omita o titular **não atualiza o saldo** — vai para
  a DLQ. Isso é deliberado sob Constitution I, que resolve a ambiguidade em favor de **não** mutar
  estado; o custo é que um campo sem influência sobre o saldo passa a poder interromper a atualização
  daquela conta, e a DLQ é o lugar onde isso fica visível em vez de silencioso.
- **FR-004**: Sistema DEVE tratar como **inválida** toda mensagem que não puder ser interpretada ou que não
  contenha todos os campos estruturalmente obrigatórios de FR-003 com valores utilizáveis. A ausência ou
  o valor desconhecido de `transaction.status` ou `account.status` NÃO torna a mensagem inválida.
- **FR-004a**: Sistema DEVE distinguir **mensagem de outro fluxo** de **mensagem inválida**. Uma mensagem
  cujo bloco `transaction` esteja **integralmente ausente** (por exemplo o formato `{"account": {...}}`)
  DEVE ser descartada de forma observável, com o motivo `UNSUPPORTED_MESSAGE_TYPE`, sem alterar estado,
  sem retry e **sem DLQ**, sendo o descarte um desfecho terminal de sucesso que autoriza a confirmação do
  evento. Uma mensagem com bloco `transaction` **presente porém incompleto** permanece **inválida**
  (FR-004) e vai para a DLQ. Rationale: a DLQ existe para o que exige intervenção humana; poluí-la com
  mensagens de outro fluxo mascara os defeitos reais.
- **FR-005**: Sistema DEVE processar mensagem inválida sem alterar o estado de nenhuma conta.
- **FR-006**: Onde o sistema produzir eventos deste tipo, ele DEVE usar `account.id` como chave da mensagem,
  de modo que eventos da mesma conta caiam na mesma partição.
- **FR-007**: Sistema NÃO DEVE depender de ordenação global do Kafka nem de ordenação entre partições para
  garantir corretude.

#### Elegibilidade

- **FR-008**: Sistema DEVE considerar um evento **elegível** para atualizar o saldo somente quando
  `transaction.status` for exatamente `APPROVED` **e** `account.status` for exatamente `ENABLED`.
- **FR-008a**: Sistema DEVE tratar `transaction.status` ou `account.status` **ausente** exatamente como
  trata um valor **desconhecido**: ambos falham o critério de igualdade positiva de FR-008 e tornam o
  evento **inelegível**, nunca inválido. O motivo registrado é o mesmo de um status explicitamente
  negativo (`TRANSACTION_NOT_APPROVED` / `ACCOUNT_NOT_ENABLED`).
- **FR-009**: Sistema NÃO DEVE alterar o saldo a partir de um evento inelegível.
- **FR-010**: Sistema NÃO DEVE avançar o marcador de frescor da conta a partir de um evento inelegível.
- **FR-011**: Sistema DEVE registrar todo descarte por inelegibilidade com o identificador da conta,
  o identificador da transação e o motivo (`TRANSACTION_NOT_APPROVED`, `ACCOUNT_NOT_ENABLED` ou ambos).
- **FR-012**: Sistema DEVE tratar o descarte por inelegibilidade como **processamento bem-sucedido**,
  autorizando a confirmação do evento. Um evento inelegível não é erro e não vai para a DLQ.

#### Snapshot autoritativo de saldo

- **FR-013**: Sistema DEVE persistir, para um evento elegível e aceito, o valor presente em
  `account.balance.amount` como o saldo corrente da conta.
- **FR-014**: Sistema NÃO DEVE calcular o saldo somando ou subtraindo `transaction.amount` de um saldo anterior,
  nem derivar o saldo por acumulação de transações.
- **FR-015**: Sistema DEVE persistir a moeda presente em `account.balance.currency` junto ao saldo,
  sem inferir, assumir ou converter moeda.
- **FR-016**: Sistema DEVE usar `transaction.timestamp` como marcador de frescor do estado persistido.
- **FR-017**: Sistema DEVE registrar o `transaction.id` que originou o estado corrente de cada conta.
- **FR-018**: Sistema DEVE preservar valores monetários com exatidão decimal de 2 casas, sem
  representação em ponto flutuante em nenhuma etapa (ingestão, domínio, persistência ou exposição).

#### Ordenação e duplicidade

- **FR-019**: Sistema DEVE aplicar um snapshot elegível somente quando
  `incoming.timestamp > persisted.timestamp`, ou quando a conta ainda não possuir estado corrente.
- **FR-020**: Sistema NÃO DEVE aplicar um snapshot quando `incoming.timestamp <= persisted.timestamp`.
- **FR-021**: Sistema DEVE avaliar a condição de FR-019 **atomicamente junto com a escrita**, no armazenamento.
  Uma comparação apenas em memória NÃO é suficiente e NÃO conta como proteção.
- **FR-022**: Sistema DEVE tratar a rejeição por evento antigo, duplicado ou empatado como
  **processamento bem-sucedido**, autorizando a confirmação do evento.
- **FR-023**: Sistema DEVE distinguir, na observabilidade, `DUPLICATE_EVENT` (o `transaction.id` recebido é igual
  ao `transaction.id` que originou o estado corrente) de `STALE_EVENT` (timestamp menor, transação diferente) e
  de `TIMESTAMP_TIE_REJECTED` (timestamp igual, transação diferente).
- **FR-024**: Sistema DEVE produzir o mesmo estado final ao processar o mesmo evento uma ou N vezes.

#### Concorrência

- **FR-025**: Sistema DEVE operar corretamente com múltiplos consumers, múltiplas threads e múltiplas
  instâncias da aplicação processando simultaneamente.
- **FR-026**: Sistema DEVE garantir que, para um conjunto de eventos elegíveis da mesma conta processados
  concorrentemente, o estado final seja determinístico e corresponda ao evento de maior `transaction.timestamp`.
- **FR-027**: Sistema NÃO DEVE usar locks de JVM, `synchronized`, locks distribuídos improvisados, nem
  pressupor consumer único ou instância única para garantir consistência de saldo.
- **FR-028**: Sistema NÃO DEVE usar sequências ler-depois-escrever que assumam ausência de alteração no intervalo.

#### Armazenamento do estado (requisitos funcionais)

- **FR-029**: Cada conta DEVE possuir no máximo um estado corrente.
- **FR-030**: O estado corrente DEVE ser recuperável diretamente pelo identificador da conta.
- **FR-031**: O estado corrente DEVE conter: identificador da conta, valor do saldo, moeda, marcador de frescor
  e identificador da última transação aplicada.
- **FR-032**: A atualização do estado corrente DEVE ser uma operação atômica e condicional — aplicada
  integralmente ou não aplicada.
- **FR-033**: Uma leitura de saldo NÃO DEVE retornar um estado anterior a uma escrita já concluída para
  aquela conta.

#### Semântica de processamento e falhas

- **FR-034**: Sistema NÃO DEVE confirmar o offset de uma mensagem antes de o processamento atingir um
  desfecho terminal.
- **FR-035**: Sistema DEVE reconhecer como desfechos terminais: snapshot aplicado; evento descartado por
  inelegibilidade; **mensagem de outro fluxo descartada (FR-004a)**; evento rejeitado por duplicidade,
  antiguidade ou empate; mensagem aceita pela DLQ.
- **FR-036**: Sistema DEVE reprocessar, com retry limitado e backoff, falhas transitórias do armazenamento
  ou da infraestrutura.
- **FR-037**: Sistema NÃO DEVE executar retry de erro permanente (mensagem inválida, violação de schema,
  conteúdo não processável).
- **FR-038**: Sistema DEVE rotear para uma DLQ, cujo nome é fornecido por configuração, toda mensagem inválida
  e toda mensagem cujo retry tenha se esgotado.
- **FR-039**: A mensagem roteada para a DLQ DEVE preservar o payload original e carregar metadados suficientes
  para diagnóstico e reprocessamento: motivo da falha, origem (tópico, partição, offset) e instante da falha.
- **FR-040**: Sistema NÃO DEVE confirmar o offset quando o roteamento para a DLQ falhar.
- **FR-041**: Sistema DEVE operar sob semântica **at-least-once**; entrega exatamente-uma-vez NÃO DEVE ser
  pressuposta.

#### Consulta de saldo

- **FR-042**: Sistema DEVE expor `GET /balances/{accountId}` para consulta do saldo corrente de uma conta.
- **FR-043**: Para conta com estado corrente, o sistema DEVE responder `200 OK` com **exatamente** estas
  quatro chaves de topo, e nenhuma outra: `id` (identificador da conta), `owner` (identificador do
  titular), `balance` (objeto com `amount` e `currency`) e `updated_at` (instante do snapshot aplicado).
  `owner` é **sempre presente e nunca nulo**, garantido por FR-003a.
- **FR-043a**: `lastTransactionId` **NÃO** é exposto na resposta (decidido em 2026-09-07), mas **DEVE
  continuar persistido**: é o operando que distingue reentrega de conflito na escrita condicional
  (INV-005, FR-023). A rastreabilidade de "qual evento produziu este saldo" muda de lugar — segue
  disponível na tabela e nos logs estruturados via MDC — em vez de desaparecer.
- **FR-044**: Sistema DEVE representar o valor do saldo na resposta como **número JSON** com exatamente 2 casas
  decimais (ex.: `183.12`, `150.00`, `-50.25`), serializado a partir de uma representação decimal exata.
  Especificamente:
  - a escala 2 DEVE sobreviver à serialização — `150.00` é emitido como `150.00`, **nunca** como `150`;
  - o valor NÃO PODE em nenhum momento transitar por `Float`, `Double` ou qualquer tipo de ponto flutuante
    binário — nem no domínio, nem na persistência, nem na serialização (Constitution X);
  - a garantia do sistema termina na saída: o que o cliente faz ao desserializar o número é escolha do
    parser dele, e está fora da fronteira deste serviço.
- **FR-045**: Sistema DEVE representar a moeda como código ISO 4217 de 3 letras maiúsculas, exatamente como
  persistida.
- **FR-046**: Sistema DEVE representar o instante do snapshot — campo `updated_at` da resposta — como
  data-hora ISO-8601 com **offset local** e precisão de **milissegundos**, no formato
  `2025-07-05T18:04:13.433-03:00`, derivada de `transaction.timestamp`. Especificamente:
  - o fuso de renderização é fornecido por configuração, com padrão `America/Sao_Paulo` (hoje `-03:00`);
  - a fração de segundo tem **exatamente 3 dígitos**, obtida por **truncamento** dos microssegundos
    persistidos — nunca por arredondamento, que poderia exibir um instante futuro;
  - a perda de precisão é **apenas de exibição**: o valor persistido permanece em microssegundos e é ele
    que decide ordenação (FR-016, FR-019).

  O ciclo completo é: o valor numérico recebido no evento é **persistido como recebido**, sem conversão; a
  conversão para data-hora acontece **apenas na resposta da consulta**. O sistema não gera esse instante a
  partir do próprio relógio: `updated_at` descreve *quando a transação ocorreu na origem*, não quando o
  registro foi gravado.
- **FR-047**: Para conta sem estado corrente, o sistema DEVE responder `404 Not Found`.
- **FR-048**: Para identificador de conta que viole o formato aceito, o sistema DEVE responder `400 Bad Request`
  sem consultar o armazenamento.
- **FR-049**: Para falha interna, o sistema DEVE responder `500 Internal Server Error` sem expor detalhes
  internos, mensagens de exceção, nomes de tabela ou stack traces.
- **FR-050**: Respostas de erro DEVEM ter formato consistente, contendo ao menos um código de erro e uma
  mensagem legível.
- **FR-051**: A consulta NÃO DEVE alterar o estado de nenhuma conta.

#### Observabilidade

- **FR-052**: Sistema DEVE emitir telemetria que permita contabilizar e distinguir, por conta e por transação:
  eventos recebidos; snapshots aplicados; descartes por inelegibilidade (com motivo); duplicados; eventos
  antigos; empates de timestamp; mensagens inválidas; **mensagens de outro fluxo descartadas (FR-004a)**;
  roteamentos para DLQ; tentativas de retry; e falhas de persistência.
- **FR-053**: Sistema DEVE expor latência de processamento de evento, latência da consulta REST e consumer lag.
- **FR-054**: Todo desfecho não-mutante exigido por FR-004a, FR-009, FR-020 e FR-024 DEVE ser observável — silencioso
  no efeito, nunca na telemetria.
- **FR-055**: Logs DEVEM ser estruturados e correlacionáveis por `account.id` e `transaction.id`,
  e NÃO DEVEM expor segredos ou credenciais.

### Key Entities

- **Evento Financeiro**: mensagem recebida do tópico. Carrega uma transação (`id`, `status`, `timestamp`,
  e opcionalmente `type`, `amount`, `currency`) e o estado da conta no instante da transação (`id`, `status`,
  `balance.amount`, `balance.currency`). O `balance` é o **snapshot autoritativo**, não um delta.

- **Elegibilidade**: julgamento derivado do evento — `APPROVED` **e** `ENABLED`. Determina se o evento pode
  concorrer à atualização do estado. É avaliada antes da ordenação.

- **Estado Corrente da Conta**: o que o sistema persiste e expõe. Composto por identificador da conta, saldo,
  moeda, marcador de frescor (timestamp do evento de origem) e identificador da última transação aplicada.
  Existe no máximo um por conta, e corresponde sempre ao evento elegível de maior timestamp já aceito.

- **Marcador de Frescor**: o `transaction.timestamp` do evento que produziu o estado corrente. É o critério
  único de decisão sobre substituição de estado. Só avança por evento elegível e aceito.

- **Mensagem Não Processável**: mensagem que não pode se tornar um evento válido, ou cujo processamento
  falhou permanentemente. Destina-se à DLQ com metadados de diagnóstico.

### Invariants

- **INV-001**: O saldo persistido de uma conta corresponde sempre ao snapshot elegível de maior
  `transaction.timestamp` já recebido para aquela conta.
- **INV-002**: Nenhum evento com `timestamp <= persisted.timestamp` altera o estado.
- **INV-003**: Nenhum evento inelegível altera saldo ou marcador de frescor.
- **INV-004**: O marcador de frescor de uma conta é monotonicamente crescente — nunca regride.
- **INV-005**: Processar o mesmo evento N vezes produz o mesmo estado que processá-lo uma vez.
- **INV-006**: Nenhum valor monetário transita por representação de ponto flutuante.
- **INV-007**: Nenhuma mensagem é confirmada sem ter atingido um desfecho terminal.
- **INV-008**: Nenhuma mensagem é descartada sem ter sido aplicada, deliberadamente ignorada de forma
  observável (por inelegibilidade ou por ser de outro fluxo), ou roteada para a DLQ.

### Matriz de decisão do processamento

Resumo normativo do comportamento observável. Cada linha é um desfecho terminal.

| # | Condição do evento | Saldo | Marcador de frescor | Offset | Telemetria | DLQ |
|---|---|---|---|---|---|---|
| 1 | Válido, elegível, `ts > persisted` | atualizado | avança | confirma | `APPLIED` | não |
| 2 | Válido, elegível, `ts < persisted`, transação diferente | inalterado | inalterado | confirma | `STALE_EVENT` | não |
| 3 | Válido, elegível, `ts == persisted`, mesma transação | inalterado | inalterado | confirma | `DUPLICATE_EVENT` | não |
| 4 | Válido, elegível, `ts == persisted`, transação diferente | inalterado | inalterado | confirma | `TIMESTAMP_TIE_REJECTED` | não |
| 5 | Válido, inelegível (`DECLINED` e/ou `DISABLED`) | inalterado | inalterado | confirma | `IGNORED_INELIGIBLE` + motivo | não |
| 5a | Mensagem de outro fluxo (bloco `transaction` integralmente ausente) | inalterado | inalterado | confirma | `UNSUPPORTED_MESSAGE_TYPE` | não |
| 6 | Inválido (não interpretável / campo estruturalmente obrigatório ausente) | inalterado | inalterado | confirma **após** aceite da DLQ | `INVALID_MESSAGE` | sim, sem retry |
| 7 | Falha transitória do armazenamento | inalterado | inalterado | **não confirma** | `TRANSIENT_FAILURE` + tentativa | não (ainda) |
| 8 | Falha permanente / retry esgotado | inalterado | inalterado | confirma **após** aceite da DLQ | `PERMANENT_FAILURE` | sim |
| 9 | Falha ao rotear para a DLQ | inalterado | inalterado | **não confirma** | `DLQ_FAILURE` | retenta |

### Contrato de mensageria

Nomes concretos são deliberadamente omitidos — o desafio e o projeto ainda não os fixaram (ver A-01).

| Aspecto | Requisito |
|---|---|
| Tópico de entrada | Um tópico, nome por configuração |
| Chave da mensagem | `account.id` quando o sistema controla a produção (FR-006) |
| Valor da mensagem | Documento com os blocos `transaction` e `account` descritos em FR-003 |
| Consumer group | Um group id dedicado, por configuração |
| Ordenação assumida | Apenas dentro da partição; nunca global (FR-007) |
| Confirmação de offset | Manual, após desfecho terminal (FR-034, FR-035) |
| Retry | Limitado, com backoff, apenas para falhas transitórias (FR-036, FR-037) |
| DLQ | Um tópico, nome por configuração, com metadados de diagnóstico (FR-038, FR-039) |

### Contrato da consulta REST

`GET /balances/{accountId}`

> **Este contrato é normativo.** Após a revisão de 2026-09-07 ele passou a seguir integralmente a amostra
> `contracts/balance-response.json` do enunciado do desafio — a posição mista anterior foi abandonada:
>
> | Aspecto | Fonte adotada | Motivo |
> |---|---|---|
> | Nome e formato do campo de instante (`updated_at`, offset local, milissegundos) | **amostra do desafio** | compatibilidade com a avaliação |
> | Representação do saldo (`balance.amount` como **número JSON**, escala 2 preservada) | **amostra do desafio** | serializado de decimal exato, a saída do serviço permanece exata; Constitution X proíbe *tipos* binários, não a sintaxe de número do JSON |
> | Identificação da conta (`id`) e ausência de `lastTransactionId` | **amostra do desafio** (revisado em 2026-09-07) | a rastreabilidade continua na tabela e nos logs; um campo extra faria um cliente estrito rejeitar a resposta inteira |
> | `owner` da amostra | **amostra do desafio** — exposto (revisado em 2026-09-07) | `ownerId` já era persistido, então expô-lo não exigiu migração, como esta linha previa |
>
> Onde a amostra e esta tabela divergirem em qualquer outro ponto, prevalece esta tabela. Resolvido em
> [Clarifications](#clarifications) (2026-09-06), revisado em 2026-09-07.

| Situação | Status | Corpo |
|---|---|---|
| Conta com estado corrente | `200` | exatamente: `id`, `owner`, `balance.amount` (número JSON, 2 casas), `balance.currency` (ISO 4217), `updated_at` (ISO-8601 com offset local, milissegundos) |
| Conta sem estado corrente | `404` | erro com código e mensagem |
| `accountId` fora do formato aceito | `400` | erro com código e mensagem |
| Falha interna | `500` | erro genérico, sem detalhes internos |

Formato aceito de `accountId`: não vazio e composto apenas por `[A-Za-z0-9-]` (ver A-05).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em qualquer sequência de eventos elegíveis embaralhados, repetidos ou atrasados para uma mesma
  conta, o saldo final observado corresponde ao evento de maior timestamp em **100%** das execuções.
- **SC-002**: Eventos inelegíveis, duplicados e antigos alteram o saldo em **0%** dos casos.
- **SC-003**: Com múltiplas instâncias processando a mesma conta simultaneamente, o saldo final é o do maior
  timestamp em **100%** das execuções, sem perda de atualização.
- **SC-004**: **100%** dos eventos recebidos terminam em um desfecho terminal rastreável na telemetria —
  aplicado, ignorado, rejeitado ou enviado à DLQ. Nenhum evento desaparece sem registro.
- **SC-005**: Após um evento elegível ser aplicado, a consulta subsequente do saldo daquela conta reflete o
  novo valor em **100%** das leituras.
- **SC-006**: O cliente obtém a resposta da consulta de saldo em menos de **200 ms** no percentil 95.
- **SC-007**: O sistema sustenta pelo menos **1.000 eventos por segundo** sem acúmulo sustentado de eventos
  pendentes de processamento.
- **SC-008**: Uma mensagem impossível de processar não bloqueia o processamento das mensagens seguintes por
  mais do que a janela de retry configurada.
- **SC-009**: Nenhum valor monetário **emitido pelo serviço** ou **persistido** difere do valor recebido no
  evento — **0** ocorrências de erro de arredondamento ou de precisão. Revisado em 2026-09-07:
  - **O que é medido**: os bytes do corpo da resposta e o atributo gravado no armazenamento. A renderização
    no cliente que consome a API está **fora de escopo** — ver *Out of Scope*.
  - **Como é aferido** — isto faz parte do critério, não é detalhe de execução: a verificação incide
    sobre o **texto da resposta**, nunca sobre o valor já desserializado. A escala é uma propriedade dos
    caracteres emitidos, não do número: qualquer aferição que primeiro converta o valor a um tipo
    numérico deixa de conseguir distinguir `150` de `150.00` e aprovaria as duas. Isso desqualifica
    tanto a validação por esquema quanto asserções sobre o corpo já convertido — as ferramentas
    concretas afetadas estão registradas em [plan.md](./plan.md) §6 e em
    [contracts/balances-api.yaml](./contracts/balances-api.yaml).
- **SC-010**: O reinício abrupto da aplicação não altera o saldo de nenhuma conta.

## Assumptions

- **A-01**: Nomes definitivos de tópico de entrada, DLQ e consumer group ainda não foram determinados pelo
  desafio nem pelo projeto. São tratados como configuração e serão fixados no `/speckit-plan`.
- **A-02**: ~~assumption~~ — **confirmado em [Clarifications](#clarifications) (2026-09-06).**
  `transaction.timestamp` está em **microssegundos** desde a época Unix e é o critério de ordenação
  autoritativo. Evidência: o gerador de eventos do repositório
  (`infra/redpanda/produce-transactions-events.sh`) usa `date +%s%6N`, e a amostra
  `contracts/transaction-event.json` traz `1751641364589998` — 16 dígitos, que em microssegundos
  correspondem a julho de 2025. A unidade afeta **apenas** a renderização de `updated_at` (FR-046); a
  ordenação e a corretude do saldo são indiferentes a ela.
- **A-03**: ~~assumption~~ — **decidido em [Clarifications](#clarifications) (2026-09-06).** O desafio não
  define desempate para timestamps iguais, e a regra adotada é a comparação **estritamente maior** (`>`):
  em empate de timestamp entre transações diferentes prevalece o primeiro evento aplicado, e o segundo é
  rejeitado de forma observável como `TIMESTAMP_TIE_REJECTED` (FR-023). **Consequência aceita**: para
  eventos distintos com timestamp idêntico, *qual* deles vence depende da ordem de chegada — este é o
  único ponto do sistema cujo resultado não é determinístico, e ele está fora do alcance de SC-003, que
  cobre eventos com timestamps distintos. Rationale: com timestamps em microssegundos a colisão entre
  duas transações da mesma conta é praticamente inatingível, e um desempate por `transaction.id` exigiria
  condição de escrita composta — complexidade real (Constitution XV) para um caso que a telemetria já
  torna visível caso ocorra.
- **A-04**: O sistema é uma **projeção** de uma origem autoritativa upstream; não é um ledger e não valida
  se o saldo do snapshot é aritmeticamente coerente com `transaction.amount`.
- **A-05**: O formato de `accountId` aceito na consulta é não vazio, composto por `[A-Za-z0-9-]`, seguindo o
  contrato já adotado em `specs/001-balance-update-api/contracts/`. Os geradores atuais produzem UUIDs,
  que satisfazem esse formato.
- **A-06**: ~~assumption~~ — **resolvido em [Clarifications](#clarifications) (2026-09-06) e promovido a
  FR-008a.** `transaction.status` e `account.status` ausentes ou desconhecidos tornam o evento
  **inelegível**, e não inválido. Rationale: a regra de elegibilidade é uma igualdade positiva
  (`= APPROVED`, `= ENABLED`); o que não a satisfaz não atualiza saldo. Isso é conservador, preserva a
  integridade financeira (Constitution I) e impede que uma mudança de schema no produtor — um status novo
  como `PENDING` — inunde a DLQ com mensagens que apenas precisavam ser ignoradas.
- **A-07**: Um evento elegível cuja moeda difere da moeda persistida é aplicado (o snapshot é autoritativo,
  Constitution VIII) e registrado como anomalia. O sistema não converte moedas.
- **A-08**: Timestamps no futuro são aceitos; o sistema não valida o relógio da origem.
- **A-09**: Saldo negativo é permitido. Não há validação de limite ou cheque especial nesta versão.
- **A-10**: A consulta de saldo não exige autenticação nesta versão.
- **A-11**: Uma única aplicação atende ingestão e consulta, conforme Constitution XV.
- **A-12**: Valores monetários possuem exatamente 2 casas decimais, conforme os geradores de eventos existentes.

## Out of Scope

- Eventos no formato `{"account": {...}}` sem bloco `transaction` (produzidos por
  `make kafka-produce-accounts-events`): não carregam snapshot de saldo e não atualizam saldo. Um fluxo de
  ciclo de vida de conta, se necessário, será uma feature própria. **Fora de escopo não significa fora de
  tratamento**: se uma mensagem desse formato chegar ao tópico de entrada, ela é descartada de forma
  observável, sem DLQ, conforme FR-004a.
- Histórico ou extrato de transações — apenas o estado corrente é mantido.
- Cálculo, projeção ou reconciliação de saldo a partir de transações.
- Autenticação, autorização e limites de consumo da API.
- Reprocessamento automático da DLQ (a DLQ é destino observável; a reinjeção é operação manual nesta versão).
- Conversão de moeda e contas multimoeda.
- **Como o cliente renderiza o saldo depois de desserializá-lo** (decidido em 2026-09-07). O serviço
  garante os bytes que emite — `150.00`, com a escala 2 (FR-044). O que acontece depois é escolha do
  parser: clientes JavaScript (Postman, Insomnia, REST Client, DevTools) leem o literal num `double` de
  64 bits, onde `150.00` e `150` são o mesmo valor, e exibem `150`. Não é defeito nem meta deste
  serviço — é uma propriedade do tipo numérico de quem consome. Só afeta valores com zero à direita.
- Remoção do código de exemplo `hello` — trabalho de limpeza a ser tratado no `/speckit-plan`.

## Divergências em relação à 001

| Tema | 001 (anterior) | Esta specification | Motivo |
|---|---|---|---|
| Conta inexistente na consulta | `200` com saldo zero (FR-011) | `404 Not Found` (FR-047) | Devolver `0.00` para uma conta desconhecida afirma um fato financeiro que o sistema não conhece; conflita com Constitution I |
| Elegibilidade | ausente | `APPROVED` + `ENABLED` obrigatórios (FR-008) | Constitution IX; o contrato da 001 não modelava `status` |
| Marcador de frescor em evento inelegível | não especificado | não avança (FR-010) | Evitar que um evento recusado suprima um evento válido posterior |
| DLQ, retry e offset | ausentes | FR-034 a FR-041 | Constitution XI e XII |
| Representação monetária na API | texto decimal | **número JSON** com escala 2 preservada, serializado de decimal exato (FR-044, revisado em 2026-09-07) | Constitution X proíbe *tipos* de ponto flutuante binário, não a sintaxe de número do JSON; a saída do serviço permanece exata |
