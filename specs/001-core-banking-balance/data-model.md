# Phase 1 — Data Model: Core Banking Balance

**Feature**: `002-core-banking-balance` | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-06

Modelo de domínio, modelo de persistência e o mapeamento entre eles. Nenhum tipo deste documento
carrega anotação de framework ou conhece transporte.

---

## 1. Value objects

| Tipo | Campos | Invariantes | Exceção na violação |
|---|---|---|---|
| `AccountId` | `value: String` | não vazio; casa `^[A-Za-z0-9-]+$` (A-05) | `InvalidAccountIdException` |
| `OwnerId` | `value: String` | não vazio | `InvalidTransactionEventException` |
| `TransactionId` | `value: String` | não vazio | `InvalidTransactionEventException` |
| `CurrencyCode` | `value: String` | exatamente 3 letras maiúsculas `[A-Z]{3}` (ISO 4217, FR-045) | `InvalidCurrencyCodeException` |
| `Money` | `amount: BigDecimal`, `currency: CurrencyCode` | escala de entrada ≤ 2; normalizada para exatamente 2 via `setScale(2, RoundingMode.UNNECESSARY)`; negativo permitido (A-09); **sem** construtor a partir de `Double`/`Float`; **sem** operações aritméticas | `InvalidMoneyException` |
| `EventTimestamp` | `micros: Long` | `> 0`; microssegundos desde epoch Unix (A-02); `Comparable` | `InvalidTransactionEventException` |

### Notas de desenho

**`Money` não tem `plus` nem `minus`.** Constitution VIII proíbe derivar saldo por aritmética. Não
oferecer o método torna a violação impossível de escrever, em vez de apenas proibida por convenção.
`Money` expõe apenas igualdade estrutural e `toPlainString()`.

**`Money` nunca aceita `Double`.** A ausência de sobrecarga é a garantia estrutural de INV-006; o teste
de arquitetura `MonetaryPrecisionArchitectureTest` reforça proibindo `Float`/`Double` em toda a produção.

**`RoundingMode.UNNECESSARY`** faz `setScale` lançar quando a escala real excede 2, em vez de arredondar
silenciosamente — Constitution X proíbe arredondamento implícito.

**`EventTimestamp.isNewerThan(other)`** existe para testes e telemetria. O serviço **não** usa essa
comparação para decidir a escrita: a decisão é do DynamoDB (Constitution V, FR-021).

---

## 2. Enums de status

| Tipo | Valores | Regra de parsing |
|---|---|---|
| `TransactionStatus` | `APPROVED`, `NOT_APPROVED` | `from(raw: String?)`: `"APPROVED"` → `APPROVED`; **qualquer outra coisa, incluindo `null`** → `NOT_APPROVED` |
| `AccountStatus` | `ENABLED`, `NOT_ENABLED` | `from(raw: String?)`: `"ENABLED"` → `ENABLED`; **qualquer outra coisa, incluindo `null`** → `NOT_ENABLED` |
| `TransactionType` | `CREDIT`, `DEBIT`, `UNKNOWN` | `from(raw: String?)` com fallback `UNKNOWN`; puramente informativo |

`from` **nunca lança**. Isso é o que implementa a regra de igualdade positiva normatizada em **FR-008a**:
o que não é exatamente `APPROVED`/`ENABLED` não atualiza saldo, mas também não é erro de mensagem. Status
**ausente** e status **desconhecido** recebem tratamento idêntico. Resolvido na sessão de clarificações de
2026-09-06 (ver R8 em [research.md](./research.md)); o antigo conflito FR-003/FR-004 × A-06 não existe
mais.

`TransactionType` nunca influencia nenhuma decisão. Um `DEBIT` com `transaction.amount = 30.00` e
`account.balance.amount = 70.00` persiste `70.00` — o snapshot, não o resultado de uma subtração.

---

## 3. Entidades e agregados

### `Account` — estado da conta no instante do evento

| Campo | Tipo | Obrigatório | Origem |
|---|---|---|---|
| `id` | `AccountId` | sim | `account.id` |
| `ownerId` | `OwnerId` | **sim** | `account.owner` — obrigatório desde 2026-09-07 (FR-003a) |
| `status` | `AccountStatus` | sim (derivado, nunca falha) | `account.status` |
| `balance` | `Money` | sim | `account.balance` |

Não é o estado persistido — é um bloco do evento recebido.

### `Transaction` — a transação que originou o evento

| Campo | Tipo | Obrigatório | Origem |
|---|---|---|---|
| `id` | `TransactionId` | sim | `transaction.id` |
| `status` | `TransactionStatus` | sim (derivado, nunca falha) | `transaction.status` |
| `timestamp` | `EventTimestamp` | sim | `transaction.timestamp` |
| `type` | `TransactionType` | sim (derivado) | `transaction.type` |
| `amount` | `Money?` | não | `transaction.amount` + `transaction.currency` |

`amount` é **informativo**. Nenhum caminho de código o combina com um saldo (Constitution VIII, FR-014).

### `TransactionEvent` — agregado raiz da ingestão

| Campo | Tipo |
|---|---|
| `transaction` | `Transaction` |
| `account` | `Account` |

Comportamento:

```
evaluateEligibility(): EligibilityDecision
    Eligible                      quando transaction.status == APPROVED && account.status == ENABLED
    Ineligible(reasons)           caso contrário, com reasons ⊆ {TRANSACTION_NOT_APPROVED, ACCOUNT_NOT_ENABLED}

toBalance(): Balance
    pré-condição: evaluateEligibility() é Eligible
    Balance(account.id, account.ownerId, account.balance, transaction.timestamp, transaction.id)
```

`toBalance()` copia `account.balance` literalmente. `Balance` não tem construtor que aceite um saldo
anterior — derivar saldo é estruturalmente impossível.

### `Balance` — agregado raiz da persistência (estado corrente projetado)

| Campo | Tipo | Obrigatório | FR |
|---|---|---|---|
| `accountId` | `AccountId` | sim | FR-031 |
| `ownerId` | `OwnerId` | **sim** | — |
| `money` | `Money` | sim | FR-013, FR-015 |
| `asOf` | `EventTimestamp` | sim | FR-016 (marcador de frescor) |
| `lastTransactionId` | `TransactionId` | sim | FR-017 |

Exatamente um por conta (FR-029). É o que a API expõe e o que o DynamoDB guarda.

---

## 4. Elegibilidade e desfecho

```
EligibilityDecision (sealed)
├── Eligible
└── Ineligible(reasons: Set<IneligibilityReason>)

IneligibilityReason (enum)
├── TRANSACTION_NOT_APPROVED
└── ACCOUNT_NOT_ENABLED

ProcessingOutcome (sealed)
├── Applied(balance: Balance)
├── IgnoredIneligible(reasons: Set<IneligibilityReason>)
└── Rejected(reason: RejectionReason, persisted: Balance?)

RejectionReason (enum)
├── DUPLICATE_EVENT          transaction.id == estado corrente
├── STALE_EVENT              timestamp menor, transação diferente
└── TIMESTAMP_TIE_REJECTED   timestamp igual, transação diferente
```

`Ineligible` pode carregar **as duas** razões simultaneamente (spec US3 cenário 3).

Estas cinco variantes correspondem exatamente às linhas 1–5 da matriz de decisão da spec. As linhas 6–9
(inválido, transitório, permanente, falha de DLQ) **não** são `ProcessingOutcome`: são falhas de
transporte tratadas pelo container Kafka, porque não são decisões de negócio.

---

## 5. Resultado da escrita (port de saída)

```
BalanceWriteResult (sealed)      // port/output
├── Applied
├── Duplicate(persisted: Balance)
├── Stale(persisted: Balance)
└── TimestampTie(persisted: Balance)
```

Produzido por `BalanceWriter`, consumido por `ProcessTransactionEventService`, que o traduz para
`ProcessingOutcome`. Falhas transitórias **não** são variantes deste tipo — são exceções
(`TransientProcessingException`), porque precisam subir até o container Kafka para acionar retry.

---

## 5a. Interpretação da mensagem (fronteira do adapter Kafka)

FR-004a exige distinguir **mensagem de outro fluxo** de **mensagem defeituosa**, e as duas têm destinos
opostos. Como o descarte de FR-004a é um desfecho terminal de **sucesso**, ele não pode ser sinalizado por
exceção — qualquer exceção que suba do listener é roteada para a DLQ pelo `DefaultErrorHandler`, que é
exatamente o que se quer evitar.

```
MessageInterpretation (sealed)   // adapter/input/kafka/mapper
├── Interpreted(event: TransactionEvent)      // segue para o use case
└── Unsupported(reason: UnsupportedReason)    // descarte observável, offset confirma

UnsupportedReason (enum)
└── NO_TRANSACTION_BLOCK
```

Mensagem **inválida** não é variante deste tipo: continua sendo `UnprocessableEventException` lançada,
porque precisa alcançar o error handler para ir à DLQ.

| Payload | Resultado | Destino | Matriz |
|---|---|---|---|
| `{"account":{...}}` — sem bloco `transaction` | `Unsupported(NO_TRANSACTION_BLOCK)` | descarte observável | linha **5a** |
| `{"transaction":{},...}` — bloco presente, vazio | `UnprocessableEventException` | DLQ, sem retry | linha 6 |
| `{"transaction":null,...}` | `UnprocessableEventException` | DLQ, sem retry | linha 6 |

Estes três casos são a **fronteira** de FR-004a: payloads quase idênticos com destinos opostos. É o par
de testes que trava a regra.

`MessageInterpretation` vive no adapter, não no domínio: distinguir "que tipo de mensagem é esta" é
concern de transporte, não regra de banking. Por isso `ProcessingOutcome` **não** ganha uma variante nova
— a mensagem descartada nunca chega a ser um evento de domínio.

---

## 6. Modelo de persistência — tabela `AccountBalances`

| Propriedade | Valor |
|---|---|
| Partition key | `pk` (S) = `ACCOUNT#{accountId}` |
| Sort key | nenhuma |
| Índices | nenhum |
| Billing | `PAY_PER_REQUEST` |
| Itens por conta | exatamente 1 |

### Atributos

| Atributo | Tipo DynamoDB | Origem no domínio | Obrigatório | Nota |
|---|---|---|---|---|
| `pk` | `S` | derivado de `accountId` | sim | `"ACCOUNT#" + accountId` |
| `accountId` | `S` | `Balance.accountId.value` | sim | forma crua, para inspeção operacional |
| `ownerId` | `S` | `Balance.ownerId.value` | **sim** | nunca ausente: um evento sem titular não chega a ser persistido (FR-003a) |
| `balanceAmount` | **`S`** | `Balance.money.amount.toPlainString()` | sim | texto decimal, escala exatamente 2 — **inalterado** pela revisão de 2026-09-07: `N` continua proibido aqui porque remove zeros à direita |
| `balanceCurrency` | `S` | `Balance.money.currency.value` | sim | ISO 4217 |
| `lastEventTimestamp` | **`N`** | `Balance.asOf.micros` | sim | marcador de frescor; operando da condição |
| `lastTransactionId` | `S` | `Balance.lastTransactionId.value` | sim | base da detecção de duplicidade |
| `updatedAt` | `S` | relógio da aplicação | sim | ISO-8601 UTC; **operacional**, nunca usado em decisão |

### As duas escolhas de tipo que importam

**`balanceAmount` é `S`, não `N`.** O tipo `N` do DynamoDB normaliza o número e remove zeros à direita:
`150.00` volta como `"150"`. O valor não corrompe, mas a escala se perde — e FR-044 exige exatamente
duas casas na resposta. Como `S`, o valor retorna byte a byte idêntico, sem reconstrução de escala. Não
perdemos nada: nenhuma expressão do DynamoDB compara, ordena ou soma saldo.

**`lastEventTimestamp` é `N`, não `S`.** Aqui a comparação numérica é obrigatória — é o operando da
condição de frescor. Como `S`, a comparação seria lexicográfica e quebraria assim que o número de dígitos
mudasse. `Long` em microssegundos cabe folgadamente nos 38 dígitos de precisão do tipo `N`.

Nenhum dos dois caminhos passa por `Double`: `AttributeValue.s(String)` e `AttributeValue.n(String)`
recebem `String`.

### `updatedAt` não é o marcador de frescor

`updatedAt` é hora de relógio da aplicação e serve só para diagnóstico operacional. O marcador de
frescor é `lastEventTimestamp`, derivado do **evento** (Constitution V exige "derivado do evento, não do
tempo de processamento"). Nenhuma condição, comparação ou decisão usa `updatedAt`.

---

## 7. Operações de persistência

### Escrita — `PutItem` condicional

```
ConditionExpression:  attribute_not_exists(#pk) OR #lastEventTimestamp < :incomingTimestamp
ExpressionAttributeNames:   #pk -> pk , #lastEventTimestamp -> lastEventTimestamp
ExpressionAttributeValues:  :incomingTimestamp -> N(<micros>)
ReturnValuesOnConditionCheckFailure: ALL_OLD
```

| Estado persistido | Resultado |
|---|---|
| ausente | escreve → `Applied` |
| `lastEventTimestamp < incoming` | escreve (substitui o item inteiro) → `Applied` |
| `lastEventTimestamp >= incoming` | `ConditionalCheckFailedException` → classificar |

Classificação a partir do item devolvido por `ALL_OLD`, **nesta ordem** (FR-023):

```
persisted.lastTransactionId == incoming.transactionId  → Duplicate
persisted.lastEventTimestamp == incoming.micros        → TimestampTie
caso contrário                                          → Stale
```

Identidade antes de timestamp: a reentrega do mesmo evento é sempre `Duplicate`, mesmo com timestamp
empatado. Uma transação já sobrescrita por outra mais nova reentregue cai em `Stale` — correto, porque
ela não é mais a transação que originou o estado.

### Leitura — `GetItem` consistente

```
Key:           { pk: S("ACCOUNT#" + accountId) }
ConsistentRead: true          # ADR-003, FR-033
```

Item ausente → `null` → `BalanceNotFoundException` → `404`. **Nunca** `0.00` para conta desconhecida.

---

## 8. Mapeamento evento → domínio → item

| JSON de entrada | Domínio | Item DynamoDB | Resposta REST |
|---|---|---|---|
| `account.id` | `Account.id` / `Balance.accountId` | `pk`, `accountId` | **`id`** |
| `account.owner` | `Account.ownerId` | `ownerId` | **`owner`** — sempre presente |
| `account.status` | `Account.status` | *(não persistido)* | — |
| `account.balance.amount` | `Money.amount` | `balanceAmount` (S) | `balance.amount` (**número JSON**, escala 2) |
| `account.balance.currency` | `Money.currency` | `balanceCurrency` | `balance.currency` |
| `account.created_at` | *(não mapeado)* | — | — |
| `transaction.id` | `Transaction.id` / `Balance.lastTransactionId` | `lastTransactionId` | *(não exposto — FR-043a)* |
| `transaction.status` | `Transaction.status` | *(não persistido)* | — |
| `transaction.timestamp` | `EventTimestamp` / `Balance.asOf` | `lastEventTimestamp` (N) | `updated_at` (ISO-8601, offset local, 3 dígitos truncados) |
| `transaction.type` | `Transaction.type` | *(não persistido)* | — |
| `transaction.amount` | `Transaction.amount` (informativo) | *(não persistido)* | — |
| `transaction.currency` | moeda de `Transaction.amount` | — | — |
| — | — | `updatedAt` (relógio) | — |

**`ownerId` é persistido e exposto** (revisado em 2026-09-07). A decisão anterior de persistir sem expor
é o que tornou esta mudança gratuita: o campo já estava na tabela, então passar a devolvê-lo como `owner`
não exigiu migração alguma — exatamente o cenário que aquela decisão antecipava.

**`lastTransactionId` é persistido e não exposto** (FR-043a). O sentido é o inverso do caso acima: ele
não é um campo de conveniência, é o **operando** que distingue reentrega de conflito na escrita
condicional (INV-005, FR-023). Removê-lo da tabela quebraria a idempotência; removê-lo da resposta só
move a rastreabilidade para a tabela e para os logs.

**Status não são persistidos.** Só a decisão que produziram importa: se o evento foi aplicado, era
`APPROVED` + `ENABLED` por construção. Guardá-los sugeriria, falsamente, que o item descreve a conta —
ele descreve apenas o saldo.

---

## 9. Rastreabilidade das invariantes

| Invariante | Garantida por | Verificada em |
|---|---|---|
| INV-001 saldo = snapshot de maior timestamp elegível | `ConditionExpression` | integração + concorrência |
| INV-002 `ts <= persisted` não altera estado | `<` estrito na condição | integração |
| INV-003 inelegível não altera saldo nem marcador | serviço retorna antes de chamar `BalanceWriter` | unitário |
| INV-004 marcador monotonicamente crescente | `<` estrito na condição | concorrência |
| INV-005 N execuções ≡ 1 execução | condição + classificação `Duplicate` | integração |
| INV-006 nenhum ponto flutuante | `Money` sem ctor `Double`; `S` no DynamoDB; **`BigDecimal` serializado como número JSON** com escala preservada; Konsist proíbe `Double`/`Float` em produção | unitário + arquitetura |
| INV-007 nenhum commit sem desfecho terminal | `AckMode.RECORD` + `DefaultErrorHandler` | integração |
| INV-008 nada descartado sem registro | telemetria em todos os ramos + DLQ + `balance.events.unsupported` | integração |
| FR-004a mensagem de outro fluxo descartada, nunca na DLQ | `MessageInterpretation.Unsupported` fora do caminho de erro | unitário (par de fronteira) + integração |
| FR-029 um estado por conta | partition key única, sem sort key | modelo |
| FR-030 recuperável pelo id | `GetItem` por chave derivada | integração |
| FR-032 atualização atômica e condicional | `PutItem` condicional | integração + concorrência |
| FR-033 leitura não retorna estado anterior a escrita concluída | `ConsistentRead = true` | integração |
