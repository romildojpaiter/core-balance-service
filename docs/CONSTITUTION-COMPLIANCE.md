# Conformidade constitucional — Core Banking Balance

Revisão dos 15 princípios contra o código entregue (T099). A pergunta em cada linha não é "o
princípio foi respeitado?", mas **"o que impediria alguém de violá-lo amanhã sem perceber?"** — um
princípio que depende apenas de disciplina não está satisfeito, está apenas não violado ainda.

| # | Princípio | Como está satisfeito | O que impede a regressão |
|-|-|-|-|
| I | Financial Data Integrity | Conta desconhecida devolve `404`, nunca `0.00`. Saldo negativo é aceito. | `GetBalanceServiceTest`, `BalanceControllerTest`, `EndToEndBalanceFlowIntegrationTest` |
| II | Domain-Centric | Elegibilidade e conversão evento→saldo vivem em `TransactionEvent`, executáveis sem broker ou banco. | `HexagonalArchitectureTest` |
| III | Hexagonal Architecture | `domain` não importa nenhuma biblioteca de infraestrutura; `application` não importa cliente algum; adapters não se importam entre si. | `HexagonalArchitectureTest` (7 prefixos proibidos), verificado por mutação |
| IV | Idempotency | Reentrega reprova a condição por identidade de transação e é classificada como `Duplicate`. | `DynamoDbBalanceIntegrationTest`, `ConditionalWriteConcurrencyIntegrationTest` (16 threads, mesmo evento → exatamente uma escrita) |
| V | Out-of-Order Protection | A comparação de frescor está na `ConditionExpression`, avaliada **pelo banco**, não em memória. `EventTimestamp.isNewerThan` existe para testes e telemetria e **não** é usada na decisão de escrita. | `DynamoDbBalanceWriterTest` afirma a expressão literalmente; `ConditionalWriteConcurrencyIntegrationTest` |
| VI | Concurrency Safety | Nenhum `synchronized`, nenhum lock JVM, nenhum lock distribuído, nenhuma sequência ler-depois-escrever. | `NoLockingArchitectureTest` (verificado por mutação); o teste "no read precedes the write" |
| VII | Kafka Partition Ordering | A chave é usada como otimização de contenção; a corretude não a invoca em lugar nenhum. Divergência entre chave e payload → o payload vence, e a anomalia é contada. | `TransactionEventConsumerTest`; `TransactionEventConsumerIntegrationTest` converge fora de ordem |
| VIII | Authoritative Balance Snapshot | `Money` **não tem** `plus` nem `minus`: código que tente acumular transações não compila. `toBalance()` copia `account.balance` literalmente. | `TransactionEventToBalanceTest` (fixture com `amount=30.00` sobre `balance=150.00`) |
| IX | Transaction Eligibility | `APPROVED` **e** `ENABLED`, decidido no domínio. Status ausente ou desconhecido é inelegível, nunca inválido. | `TransactionEventEligibilityTest` (matriz completa), `MissingStatusTest` |
| X | Monetary Precision | `BigDecimal` no DTO, `S` no DynamoDB, texto na resposta. `Money.of` **lança** em vez de arredondar. Nenhum `Double`/`Float` em produção — sem lista de exceções. | `MonetaryPrecisionArchitectureTest` (verificado por mutação); `BalanceItemMapperTest`; `BalanceResponseContractTest` |
| XI | Resilience | Autoridade única de retry; o SDK da AWS não retenta. Backoff exponencial com jitter, orçamento calculável (~1,4 s). | `KafkaConsumerConfigTest` (inclui a variação do jitter) |
| XII | Kafka Processing Semantics | `AckMode.RECORD` com auto-commit desligado. Nenhum caminho confirma offset sem desfecho terminal; nenhuma mensagem é descartada em silêncio. | `KafkaConsumerConfigTest`; `TransactionEventDlqIntegrationTest` (inclui o caso negativo de FR-004a) |
| XIII | Observability | Cada ramo "não faz nada" — ignorado, duplicado, antigo, empate, descartado — tem contador e log. | `ProcessTransactionEventTelemetryTest` enumera as variantes reflexivamente: acrescentar uma sem telemetria quebra a build |
| XIV | Testability | 145 testes unitários, 20 de integração contra infraestrutura real, 3 de arquitetura verificados por mutação, cobertura 91,8%. | `./gradlew check` e `./gradlew integrationTest` |
| XV | Simplicity | Um deployable. Nenhum Redis, Elasticsearch, CQRS, Event Sourcing, Saga, lock distribuído ou segundo banco. Duas dependências acrescentadas (actuator, micrometer-prometheus), justificadas por FR-053. Nenhum linter novo. | Auditoria registrada em `build.gradle.kts`; [ADR-007](adr/007-single-deployable-with-profile-split.md) |

## Os dois princípios mais fáceis de satisfazer só no papel

**V (Out-of-Order Protection)** e **VI (Concurrency Safety)** seriam declaráveis com um comparativo em
memória e um `synchronized`, e ambos passariam qualquer teste de thread única. É por isso que a
condição é afirmada **literalmente como string** em `DynamoDbBalanceWriterTest` — é o único ponto do
sistema onde a corretude vive num texto interpretado por outro processo, e trocar `<` por `<=` não
quebraria nenhum outro teste — e por isso `ConditionalWriteConcurrencyIntegrationTest` usa uma
barreira e repete 20 vezes: sem a barreira, 32 threads executam quase em série e o teste passa sem
provar nada.
