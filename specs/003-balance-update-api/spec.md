# Feature Specification: Balance Update API

**Feature Branch**: `001-balance-update-api`

**Created**: 2026-09-05

**Status**: Draft

**Input**: User description: "Processamento de eventos de atualização de saldo de uma conta. Você deverá construir uma API de consulta de saldo. O desafio tem duas partes: 1. Ingestão — consumir transações financeiras de um tópico Kafka e persistir no DynamoDB 2. Exposição — disponibilizar um endpoint REST para consulta do saldo mais atual de uma conta"

## Clarifications

### Session 2026-09-05

- Q: Quando duas transações para a mesma conta chegam simultaneamente, como o sistema garante que o saldo final seja o correto? → A: O evento carrega o **saldo absoluto** (snapshot) da conta naquele instante, não um delta. O sistema persiste o snapshot cujo `timestamp` é o **maior** entre todos os eventos recebidos. Um evento só é aceito se seu `timestamp` for **estritamente maior** que o `timestamp` já armazenado. Essa única regra resolve simultaneamente eventos fora de ordem, duplicados e atualizações concorrentes (via escrita condicional atômica).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consultar saldo atual da conta (Priority: P1)

Como cliente de um banco digital, quero consultar o saldo atual da minha conta para ter visibilidade do meu patrimônio financeiro e tomar decisões de gasto informadas.

**Why this priority**: Esta é a funcionalidade principal do sistema - fornecer ao cliente a informação mais crítica sobre sua conta. Sem isso, o sistema não entrega valor.

**Independent Test**: Pode ser testada independentemente publicando eventos de saldo diretamente no tópico e verificando se o endpoint REST retorna o snapshot mais recente (maior timestamp).

**Acceptance Scenarios**:

1. **Given** uma conta "acc-12345" que ainda não possui nenhum snapshot de saldo armazenado, **When** o cliente consulta o saldo via endpoint REST, **Then** o sistema retorna um saldo de valor zero com sucesso.

2. **Given** uma conta "acc-12345" com snapshot armazenado (timestamp=200, amount=150.00), **When** o cliente consulta o saldo, **Then** o sistema retorna 150.00 como saldo atual.

3. **Given** uma conta "acc-12345" com snapshot armazenado (timestamp=200, amount=150.00), **When** um novo evento com timestamp=250 e amount=180.00 é processado, **Then** a próxima consulta retorna 180.00.

---

### User Story 2 - Processar eventos de atualização de saldo em tempo real (Priority: P1)

Como sistema de core banking, preciso processar eventos de atualização de saldo vindos do barramento de mensagens para manter o saldo das contas atualizado de forma consistente e confiável.

**Why this priority**: Esta é a funcionalidade de ingestão que alimenta o sistema de dados. Sem isso, não há saldo para consultar.

**Independent Test**: Pode ser testada publicando eventos no tópico e verificando se o estado persistido reflete o snapshot de maior timestamp.

**Acceptance Scenarios**:

1. **Given** um evento com transaction.id="tx-001", account.id="acc-12345", timestamp=100, balance.amount=100.00, **When** o evento é consumido do tópico, **Then** o saldo da conta "acc-12345" passa a ser 100.00 (primeiro snapshot armazenado).

2. **Given** uma conta "acc-12345" com snapshot (timestamp=100, amount=100.00), **When** um evento com timestamp=200 e amount=150.00 é consumido, **Then** o saldo passa a ser 150.00.

3. **Given** um evento com formato inválido (JSON malformado ou campos obrigatórios ausentes), **When** o evento é consumido, **Then** o evento é rejeitado sem alterar o saldo de nenhuma conta e um log de erro é registrado.

---

### User Story 3 - Tratar eventos fora de ordem (Priority: P1)

Como sistema de core banking, preciso garantir que o saldo refleta a transação mais recente mesmo quando as mensagens chegam fora de ordem.

**Why this priority**: É o coração do desafio. Sem esta regra, um snapshot atrasado poderia sobrescrever um snapshot mais novo, corrompendo o saldo.

**Independent Test**: Pode ser testada publicando eventos com timestamps fora de ordem e verificando que o saldo final corresponde ao snapshot de maior timestamp.

**Acceptance Scenarios**:

1. **Given** os eventos T1 (timestamp=100, amount=100), T2 (timestamp=200, amount=150) e T3 (timestamp=150, amount=120), **When** os eventos chegam na ordem T1, T2, T3, **Then** o saldo final é 150 (o snapshot do timestamp=200), e não 120.

2. **Given** uma conta com snapshot armazenado (timestamp=200, amount=150), **When** chega um evento com timestamp=150 e amount=120, **Then** o evento é ignorado e o saldo permanece 150.

---

### User Story 4 - Garantir idempotência no processamento de eventos (Priority: P2)

Como sistema de core banking, preciso garantir que eventos duplicados sejam processados de forma idempotente para não sobrescrever o saldo de forma incorreta.

**Why this priority**: Essencial para integridade financeira, mas derivada da regra de maior timestamp e pode ser confirmada após o fluxo básico.

**Independent Test**: Pode ser testada publicando o mesmo evento múltiplas vezes e verificando que o saldo não é alterado indevidamente.

**Acceptance Scenarios**:

1. **Given** um evento com transaction.id="tx-001" e timestamp=200 já processado, **When** o mesmo evento é consumido novamente, **Then** o saldo não é alterado (mesmo timestamp não é estritamente maior que o armazenado).

2. **Given** uma conta com snapshot (timestamp=200, amount=150), **When** três eventos idênticos (timestamp=200) são processados, **Then** o saldo final permanece 150.

---

### User Story 5 - Garantir consistência em atualizações concorrentes (Priority: P2)

Como sistema de core banking, preciso garantir que duas atualizações simultâneas para a mesma conta produzam o saldo correto (o de maior timestamp), sem perda de atualização.

**Why this priority**: Evita corrupção de saldo sob carga, mas depende da regra de maior timestamp já definida.

**Independent Test**: Pode ser testada publicando dois eventos concorrentes de timestamps diferentes e verificando que vence o de maior timestamp.

**Acceptance Scenarios**:

1. **Given** uma conta "acc-12345", **When** dois eventos são processados concorrentemente — evento A (timestamp=200, amount=150) e evento B (timestamp=250, amount=180) — **Then** o saldo final é 180, pois o timestamp=250 é maior.

---

### Edge Cases

- **O que acontece quando o identificador da conta está ausente no evento?**
  O evento é rejeitado, um log de erro é registrado, e nenhuma alteração de saldo é realizada.

- **O que acontece quando o identificador da transação está ausente?**
  O evento é rejeitado com erro de validação.

- **O que acontece quando o timestamp está ausente ou é inválido?**
  O evento é rejeitado com erro de validação. O timestamp é essencial para a regra de ordenação.

- **O que acontece quando o valor (amount) é ausente, negativo ou inválido?**
  O evento é rejeitado com erro de validação. O valor é um saldo absoluto e deve ser um número válido.

- **O que acontece quando a moeda (currency) está ausente?**
  O evento é rejeitado com erro de validação ou recebe uma moeda padrão assumida (ver Assumptions).

- **O que acontece quando um evento chega com timestamp igual ao armazenado?**
  O evento é ignorado (a regra exige timestamp estritamente maior). Não há alteração de saldo.

- **O que acontece quando uma conta não possui nenhum snapshot armazenado?**
  A conta é criada implicitamente ao receber seu primeiro evento aceito. Antes disso, a consulta retorna saldo zero.

- **O que acontece quando o saldo fica negativo?**
  O sistema permite saldo negativo (sem validação de limite nesta versão), persistindo o valor absoluto informado no snapshot.

- **O que acontece quando eventos com mesmo timestamp mas conteúdo diferente chegam?**
  Apenas um deles é aplicado (a condição de timestamp estritamente maior aceita somente o primeiro); o outro é ignorado.

## Requirements *(mandatory)*

### Functional Requirements

#### Ingestão de Eventos de Saldo

- **FR-001**: Sistema DEVE consumir eventos de atualização de saldo de um tópico de mensagens
- **FR-002**: Sistema DEVE validar que cada evento contém: identificador da transação, identificador da conta, timestamp do evento, valor do saldo e moeda
- **FR-003**: Sistema DEVE rejeitar eventos com formato inválido ou campos obrigatórios ausentes
- **FR-004**: Sistema DEVE persistir o estado do saldo de uma conta a partir de cada evento aceito

#### Regra de Ordenação (maior timestamp vence)

- **FR-005**: Sistema DEVE aceitar um evento somente se seu timestamp for **estritamente maior** que o timestamp do snapshot atualmente armazenado para aquela conta, ou se a conta ainda não possui snapshot armazenado
- **FR-006**: Sistema DEVE ignorar eventos cujo timestamp seja **menor ou igual** ao timestamp armazenado (cobre duplicidade e eventos fora de ordem)
- **FR-007**: Sistema DEVE garantir que atualizações concorrentes para a mesma conta sejam atômicas — o saldo efetivo é sempre o do maior timestamp, sem perda de atualização
- **FR-008**: Sistema DEVE ignorar eventos duplicados (mesmo transaction.id) sem alterar o saldo

#### Consulta de Saldo

- **FR-009**: Sistema DEVE expor endpoint REST para consulta de saldo por identificador de conta
- **FR-010**: Sistema DEVE retornar o saldo mais atual (maior timestamp) disponível para a conta consultada
- **FR-011**: Sistema DEVE retornar saldo zero para contas que não possuem snapshot armazenado
- **FR-012**: Sistema DEVE retornar erro apropriado quando o identificador de conta é inválido ou ausente na consulta

#### Integridade Financeira

- **FR-013**: Sistema DEVE preservar o valor monetário com precisão de 2 casas decimais
- **FR-014**: Sistema DEVE persistir, para cada conta, o valor do saldo, a moeda, o timestamp do snapshot e o identificador da última transação aplicada
- **FR-015**: Sistema DEVE registrar log de auditoria para cada evento processado contendo: ID da transação, ID da conta, timestamp do evento, valor e moeda
- **FR-016**: Sistema DEVE garantir que as operações de atualização de saldo sejam atômicas (nunca parcialmente aplicadas)

### Key Entities

- **Evento de Saldo (snapshot)**: Representa o saldo absoluto de uma conta em um instante. Contém identificador da transação, identificador da conta, timestamp, valor e moeda. Não representa um delta (crédito/débito), e sim o estado resultante.

- **Saldo de Conta**: Estado persistido de uma conta. Composto por valor, moeda, timestamp do snapshot de origem e identificador da última transação aplicada. Sempre corresponde ao evento de maior timestamp já aceito.

- **Conta**: Representa uma conta bancária identificada por um identificador único. Criada implicitamente ao receber seu primeiro snapshot aceito. Pode ter saldo positivo, zero ou negativo.

### Invariantes

- **INV-001**: O saldo armazenado de uma conta corresponde sempre ao snapshot de **maior timestamp** entre todos os eventos aceitos para aquela conta
- **INV-002**: Nenhum evento com timestamp menor ou igual ao timestamp armazenado altera o estado da conta
- **INV-003**: Um evento duplicado (mesmo transaction.id) nunca altera o estado armazenado
- **INV-004**: O valor do saldo é sempre um número válido, preservado com 2 casas decimais
- **INV-005**: A moeda é consistente para a conta em todos os snapshots aceitos

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Clientes podem consultar o saldo de suas contas em menos de 200 milissegundos após um evento ser processado

- **SC-002**: Sistema processa pelo menos 1000 eventos por segundo sem degradação de performance

- **SC-003**: 100% dos eventos processados são rastreáveis via logs de auditoria

- **SC-004**: Em uma sequência de eventos fora de ordem, o saldo final é sempre o do maior timestamp (0% de corrupção)

- **SC-005**: Eventos duplicados e eventos com timestamp menor ou igual ao armazenado nunca alteram o saldo

- **SC-006**: Atualizações concorrentes para a mesma conta produzem sempre o saldo de maior timestamp, sem perda de atualização

- **SC-007**: Sistema permanece disponível para consultas mesmo quando há falha temporária no consumidor de mensagens

## Assumptions

- O formato do evento é um **snapshot** do saldo, com campos: `transaction.id`, `transaction.timestamp`, `account.id`, `account.balance.amount`, `account.balance.currency`

- O `timestamp` do evento é o critério de ordenação autoritativo e está em formato epoch (microssegundos)

- A moeda é informada no evento (ex.: `BRL`); quando ausente, o evento é rejeitado

- O valor do saldo é um número decimal com até 2 casas decimais

- Não há validação de limite/cheque especial nesta versão — o saldo pode ser negativo

- Contas são criadas implicitamente quando recebem seu primeiro snapshot aceito

- A consulta de saldo não requer autenticação nesta versão (será adicionada posteriormente)

- O sistema upstream garante que cada evento tem um transaction.id único

- A regra de maior timestamp resolve eventos fora de ordem e duplicados sem necessidade de reordenação prévia nem de janela de atraso — eventos atrasados são simplesmente ignorados se seu timestamp for menor ou igual ao armazenado
