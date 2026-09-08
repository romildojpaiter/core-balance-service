---
description: "Task list — Core Banking Balance"
---

# Tasks: Core Banking — Saldo Corrente por Eventos

**Input**: `specs/001-core-banking-balance/` — [spec.md](./spec.md), [plan.md](./plan.md),
[research.md](./research.md), [data-model.md](./data-model.md), [quickstart.md](./quickstart.md),
[contracts/](./contracts/)

**Constitution**: `.specify/memory/constitution.md` v1.0.0

**Branch**: `001-core-banking-balance`

**Revisão**: gerado após a sessão de clarificações de 2026-09-06 e **estendido em 2026-09-07** com a
[Phase 13](#phase-13-correção-do-contrato-revisão-de-2026-09-07), que corrige o contrato REST e a
obrigatoriedade do titular. Ver [o que mudou](#o-que-mudou-nesta-revisão).

> As tasks T001–T100 estão concluídas e **permanecem como registro do que foi feito**. Onde uma decisão
> daquela sessão foi revogada em 2026-09-07, a task afetada recebeu uma nota de superação em vez de ser
> reescrita: apagar o raciocínio anterior tornaria a mudança incompreensível para quem ler depois.

---

## Formato

```text
- [ ] T### [P?] [US#?] Descrição com caminho de arquivo
  - **Objetivo**: por que a task existe
  - **Arquivos**: caminhos afetados
  - **Depende de**: task IDs (ou "—")
  - **Requisitos**: FR / INV / SC / Constitution
  - **Concluído quando**: critério verificável
  - **Testes esperados**: teste que acompanha a task
```

- **[P]**: paralelizável — arquivos distintos, sem dependência pendente
- **[US#]**: user story da [spec.md](./spec.md) que a task serve (rastreabilidade)

## Organização

As fases seguem a estrutura de 12 fases pedida na geração anterior (cleanup → domain → ports →
application → adapters → observability → tests → integration → quality), **não** o agrupamento por user
story do template padrão. A rastreabilidade por story é preservada pelos rótulos `[US#]` e pela
[matriz de rastreabilidade](#matriz-de-rastreabilidade).

**Sobre testes**: cada task de produção declara em *Testes esperados* o teste unitário que a acompanha —
Constitution XIV exige que toda regra crítica seja verificável, e o gate JaCoCo de 90% está ligado a
`check`. A **Phase 10** não é onde os testes começam a existir: é onde a matriz comportamental
transversal, os testes de adapter e os de integração são escritos como conjunto coerente.

## Decisões normativas já fechadas

A antiga **Phase 0** (bloqueadores) não existe mais: as duas ambiguidades foram resolvidas na sessão de
clarificações de 2026-09-06 e estão registradas em [spec.md § Clarifications](./spec.md#clarifications).
As decisões abaixo são **normativas** e várias tasks dependem delas:

| Decisão | Regra | Onde |
|---|---|---|
| Status ausente ou desconhecido | **Inelegível**, nunca inválido. Ausente e desconhecido são o mesmo caso | FR-008a |
| Mensagem sem bloco `transaction` inteiro | **Descarte observável**, sem DLQ e sem retry (`UNSUPPORTED_MESSAGE_TYPE`). Bloco presente porém incompleto continua indo para a DLQ | FR-004a, matriz linha 5a |
| Empate de `transaction.timestamp` | Comparação estritamente maior (`>`); vence o primeiro aplicado, sem desempate determinístico | A-03, FR-023 |
| Unidade do timestamp | **Microssegundos** desde a época Unix | A-02 |
| Instante na resposta REST | Campo **`updated_at`**, ISO-8601 com offset local (fuso configurável, padrão `America/Sao_Paulo`) e milissegundos por **truncamento** | FR-046 |
| Representação monetária na resposta | ~~**Texto** decimal, nunca número JSON~~ → **superado em 2026-09-07**: **número JSON** com escala 2 preservada, serializado de `BigDecimal`. Constitution X proíbe *tipos* de ponto flutuante binário, não a sintaxe de número do JSON | FR-044, R19 |
| Corpo da resposta REST | **Exatamente quatro chaves** — `id`, `owner`, `balance`, `updated_at`. `lastTransactionId` continua persistido, mas não é exposto | FR-043, FR-043a |
| Evento sem `account.owner` | **Mensagem inválida**: DLQ, sem retry. Toda conta tem titular, então um evento sem ele descreve uma conta que não pode existir | FR-003a |

## Convenções de caminho

Abreviações: `«main»` = `src/main/kotlin/br/com/itau/challenge/balance`,
`«test»` = `src/test/kotlin/br/com/itau/challenge/balance`,
`«it»` = `src/integrationTest/kotlin/br/com/itau/challenge/balance`.

---

## Phase 1: Project cleanup

**Propósito**: remover integralmente o exemplo `hello`, preservando toda a infraestrutura, e ativar a
barreira arquitetural que hoje está inerte.

**Nota de ordenação**: remover primeiro é seguro — com zero classes de produção o JaCoCo reporta 100%
(divisor zero), então o gate não quebra no intervalo. A vantagem é grande: `HexagonalArchitectureTest`
passa a proteger o código novo desde a primeira linha, em vez de ser corrigido no fim.

- [X] T001 Remover as 16 classes de produção do exemplo `hello` em `«main»/`
  - **Objetivo**: o exemplo é scaffolding e não pode fazer parte da implementação final.
  - **Arquivos** (remover): `«main»/adapter/input/kafka/GreetingTemplateConsumer.kt`,
    `«main»/adapter/input/kafka/dto/GreetingTemplateMessage.kt`,
    `«main»/adapter/input/web/GreetingController.kt`,
    `«main»/adapter/input/web/dto/GreetingResponse.kt`,
    `«main»/adapter/output/dynamodb/DynamoDbGreetingTemplateProvider.kt`,
    `«main»/adapter/output/dynamodb/DynamoDbGreetingTemplateWriter.kt`,
    `«main»/application/GreetingService.kt`, `«main»/application/SaveGreetingTemplateService.kt`,
    `«main»/domain/exception/BlankRequesterNameException.kt`,
    `«main»/domain/exception/InvalidGreetingTemplateException.kt`,
    `«main»/domain/model/Greeting.kt`, `«main»/domain/model/GreetingTemplate.kt`,
    `«main»/port/input/GetGreetingUseCase.kt`, `«main»/port/input/SaveGreetingTemplateUseCase.kt`,
    `«main»/port/output/GreetingTemplateProvider.kt`, `«main»/port/output/GreetingTemplateRepository.kt`
  - **Preservar**: `«main»/adapter/output/dynamodb/DynamoDbConfig.kt` (infraestrutura reutilizável),
    `src/main/kotlin/br/com/itau/challenge/Application.kt`
  - **Depende de**: —
  - **Requisitos**: Constitution — Legacy example code
  - **Concluído quando**: nenhum arquivo `Greeting*` resta em `src/main`; `DynamoDbConfig.kt` intacto.
  - **Testes esperados**: nenhum novo; T002 remove os testes correspondentes na sequência.

- [X] T002 Remover os 10 testes unitários do exemplo `hello` em `«test»/`
  - **Objetivo**: restaurar a compilação de `src/test` após T001.
  - **Arquivos** (remover): `«test»/adapter/input/kafka/GreetingTemplateConsumerTest.kt`,
    `«test»/adapter/input/web/GreetingControllerTest.kt`,
    `«test»/adapter/output/dynamodb/DynamoDbGreetingTemplateProviderTest.kt`,
    `«test»/adapter/output/dynamodb/DynamoDbGreetingTemplateWriterTest.kt`,
    `«test»/application/GreetingServiceTest.kt`, `«test»/application/SaveGreetingTemplateServiceTest.kt`,
    `«test»/domain/exception/BlankRequesterNameExceptionTest.kt`,
    `«test»/domain/exception/InvalidGreetingTemplateExceptionTest.kt`,
    `«test»/domain/model/GreetingTemplateTest.kt`, `«test»/domain/model/GreetingTest.kt`
  - **Preservar**: `src/test/kotlin/br/com/itau/challenge/ApplicationTests.kt`
  - **Depende de**: T001
  - **Requisitos**: Constitution — Legacy example code
  - **Concluído quando**: `src/test` compila; `ApplicationTests.kt` preservado.
  - **Testes esperados**: `./gradlew compileTestKotlin` passa.

- [X] T003 Remover os 2 testes de integração do exemplo `hello` em `«it»/`
  - **Objetivo**: restaurar a compilação do source set `integrationTest`.
  - **Arquivos** (remover): `«it»/adapter/input/kafka/GreetingTemplateConsumerIntegrationTest.kt`,
    `«it»/adapter/output/dynamodb/DynamoDbGreetingTemplateIntegrationTest.kt`
  - **Depende de**: T001
  - **Requisitos**: Constitution — Legacy example code
  - **Concluído quando**: `./gradlew compileIntegrationTestKotlin` passa.
  - **Testes esperados**: compilação do source set.

- [X] T004 [P] Remover os dados e o cliente HTTP do exemplo e ajustar o alvo `http` no `Makefile`
  - **Objetivo**: eliminar os artefatos de apoio do `hello` sem tocar em nenhum serviço de
    infraestrutura.
  - **Arquivos** (remover): `infra/dynamodb/greeting-messages.json`,
    `infra/redpanda/greeting-templates-seed.jsonl`, `http/hello.http`;
    (modificar) `Makefile` — alvo `http` referencia `balances.http`; `IMAGE` → `core-banking-balance`
  - **Preservar**: `http/http-client.env.json`, todos os serviços do `docker-compose.yml`,
    `infra/redpanda/config.sh`; os scripts de seed são atualizados em T037 e T048
  - **Depende de**: —
  - **Requisitos**: Constitution — Local infrastructure MUST be preserved and extended
  - **Concluído quando**: os três arquivos foram removidos; `docker compose config` continua válido;
    nenhum serviço adicionado nem removido.
  - **Testes esperados**: `make help` executa; `docker compose config` sem erro.

- [X] T005 Remover a configuração do exemplo de `src/main/resources/application.yaml`
  - **Objetivo**: eliminar o bloco `greeting-templates` e o `group-id` do exemplo, preservando o
    esqueleto de `spring.kafka` e `dynamodb` estendido em T036, T047 e T054.
  - **Arquivos**: `src/main/resources/application.yaml`
  - **Depende de**: T001
  - **Requisitos**: FR-001, FR-002
  - **Concluído quando**: nenhuma chave `greeting*` resta; `spring.application.name` →
    `core-banking-balance-service`; a aplicação ainda sobe.
  - **Testes esperados**: `ApplicationTests.contextLoads` verde.

- [X] T006 Reescopar `HexagonalArchitectureTest` de `..challenge.hello..` para `..challenge.balance..`
  - **Objetivo**: **o teste atual é um falso verde.** Ele escopa `br.com.itau.challenge.hello..`, pacote
    que não existe, e passa sobre um conjunto vazio — a barreira que Constitution III exige não está
    ativa. Corrigir agora faz o teste proteger o código novo desde a primeira classe.
  - **Arquivos**: `«test»/HexagonalArchitectureTest.kt`
  - **Depende de**: T001
  - **Requisitos**: Constitution II, III; Sync Impact Report da constitution
  - **Concluído quando**: `scopeFromPackage("br.com.itau.challenge.balance..")`; as quatro `Layer`
    apontam para `..balance.domain..`, `..balance.port..`, `..balance.application..`,
    `..balance.adapter..`; a proibição de imports no domínio cobre `org.springframework`,
    `software.amazon`, `org.apache.kafka`, `tools.jackson`, `com.fasterxml.jackson`, `io.micrometer` e
    `jakarta` — não apenas Spring.
  - **Testes esperados**: o próprio teste, verde e **efetivo** (verificar introduzindo temporariamente um
    import de Spring no domínio e confirmando que falha).

- [X] T007 [P] Acrescentar `spring-boot-starter-actuator` e `micrometer-registry-prometheus` em `build.gradle.kts`
  - **Objetivo**: FR-053 exige **consumer lag**, que não é observável sem as métricas do cliente Kafka
    ligadas pelo Micrometer. Adicionar cedo evita que o contexto Spring fique inconstruível entre a
    Phase 5 e a Phase 9.
  - **Arquivos**: `build.gradle.kts`
  - **Depende de**: —
  - **Requisitos**: FR-052, FR-053, Constitution XIII e XV (justificado no plano — Complexity Tracking)
  - **Concluído quando**: as duas dependências declaradas; `jacocoCoverageExclusions`,
    `coverageMinimum = 0.90` e o source set `integrationTest` inalterados.
  - **Testes esperados**: `./gradlew dependencies` resolve; `./gradlew check` verde.

- [X] T008 Verificar ausência de referências residuais ao exemplo em `src/`, `infra/`, `Makefile` e `docker-compose.yml`, e validar a build com `./gradlew check`
  - **Objetivo**: garantir que a remoção foi completa, não parcial.
  - **Arquivos**: verificação em `src/`, `infra/`, `Makefile`, `docker-compose.yml`,
    `src/main/resources/application.yaml`
  - **Depende de**: T001, T002, T003, T004, T005, T006, T007
  - **Requisitos**: Constitution — Legacy example code
  - **Concluído quando**: `grep -ri "greeting\|hello" --include="*.kt" --include="*.yaml" --include="*.yml"
    --include="*.sh" --include="Makefile" src infra Makefile docker-compose.yml` não retorna nada
    (ocorrências em `README.md` são tratadas em T098); `./gradlew check` verde.
  - **Testes esperados**: `./gradlew check` (unitários + Konsist + JaCoCo) verde.

**Checkpoint**: repositório limpo, infraestrutura intacta, barreira arquitetural ativa, build verde.

---

## Phase 2: Domain

**Propósito**: expressar as regras de banking em Kotlin puro. Nenhuma classe desta fase importa Spring,
AWS SDK, Kafka, Jackson, Micrometer ou `jakarta` — T006 falha a build se importar.

Modelo detalhado em [data-model.md](./data-model.md) §1–§4.

- [X] T009 [P] Criar as 5 exceções de domínio em `«main»/domain/exception/`
  - **Objetivo**: vocabulário único de violação de invariante, sem dependência de framework.
  - **Arquivos**: `«main»/domain/exception/InvalidAccountIdException.kt`, `InvalidMoneyException.kt`,
    `InvalidCurrencyCodeException.kt`, `InvalidTransactionEventException.kt`,
    `BalanceNotFoundException.kt`
  - **Depende de**: T006
  - **Requisitos**: FR-047, FR-048, Constitution II
  - **Concluído quando**: as cinco classes existem, estendem `RuntimeException`, e nenhuma carrega
    anotação de framework nem status HTTP.
  - **Testes esperados**: cobertas indiretamente pelos testes dos value objects (T010–T015).

- [X] T010 [P] Criar o value object `AccountId` em `«main»/domain/model/AccountId.kt`
  - **Objetivo**: o formato de `accountId` é invariante de negócio, não de transporte — validá-lo aqui é
    o que permite a T051 responder `400` sem tocar no armazenamento.
  - **Arquivos**: `«main»/domain/model/AccountId.kt`
  - **Depende de**: T009
  - **Requisitos**: FR-048, A-05
  - **Concluído quando**: rejeita vazio, branco e qualquer caractere fora de `^[A-Za-z0-9-]+$` com
    `InvalidAccountIdException`; aceita UUID.
  - **Testes esperados**: `«test»/domain/model/AccountIdTest.kt` — aceita alfanumérico e hífen; rejeita
    vazio, espaço, `#`, acentuado.

- [X] T011 [P] Criar os value objects `TransactionId` e `OwnerId` em `«main»/domain/model/`
  - **Objetivo**: identificadores tipados; `TransactionId` é a base da detecção de duplicidade.
  - **Arquivos**: `«main»/domain/model/TransactionId.kt`, `«main»/domain/model/OwnerId.kt`
  - **Depende de**: T009
  - **Requisitos**: FR-017, FR-023
  - **Concluído quando**: ambos rejeitam valor em branco com `InvalidTransactionEventException`.
  - **Testes esperados**: `«test»/domain/model/TransactionIdTest.kt`, `«test»/domain/model/OwnerIdTest.kt`.

- [X] T012 [P] Criar o value object `CurrencyCode` em `«main»/domain/model/CurrencyCode.kt`
  - **Objetivo**: moeda é preservada como entregue, nunca inferida nem convertida.
  - **Arquivos**: `«main»/domain/model/CurrencyCode.kt`
  - **Depende de**: T009
  - **Requisitos**: FR-015, FR-045
  - **Concluído quando**: aceita exatamente `^[A-Z]{3}$`; rejeita minúscula, 2 e 4 letras, e vazio com
    `InvalidCurrencyCodeException`.
  - **Testes esperados**: `«test»/domain/model/CurrencyCodeTest.kt`.

- [X] T013 Criar o value object `Money` em `«main»/domain/model/Money.kt`
  - **Objetivo**: garantia **estrutural** de INV-006. `Money` não expõe construtor a partir de
    `Double`/`Float` e **não expõe `plus` nem `minus`** — a ausência do método torna a violação de
    Constitution VIII impossível de escrever, em vez de apenas desaconselhada.
  - **Arquivos**: `«main»/domain/model/Money.kt`
  - **Depende de**: T012, T009
  - **Requisitos**: FR-018, INV-006, A-09, A-12, Constitution VIII e X
  - **Concluído quando**: campos `amount: BigDecimal` e `currency: CurrencyCode`; escala normalizada para
    exatamente 2 via `setScale(2, RoundingMode.UNNECESSARY)` — que **lança** em vez de arredondar quando
    a escala real excede 2; valores negativos aceitos; expõe apenas igualdade e `toPlainString()`.
  - **Testes esperados**: `«test»/domain/model/MoneyTest.kt` — `150.0` → `150.00`; `100.001` lança
    `InvalidMoneyException`; `-50.25` aceito; ausência de sobrecarga `Double` verificada por compilação.

- [X] T014 [P] Criar o value object `EventTimestamp` em `«main»/domain/model/EventTimestamp.kt`
  - **Objetivo**: encapsular o marcador de frescor e sua unidade. Isolar a unidade aqui torna uma
    eventual troca micros↔millis uma mudança de um arquivo.
  - **Arquivos**: `«main»/domain/model/EventTimestamp.kt`
  - **Depende de**: T009
  - **Requisitos**: FR-016, A-02 (microssegundos, confirmado), Constitution V
  - **Concluído quando**: `micros: Long` obrigatoriamente `> 0`; implementa `Comparable`; expõe
    `isNewerThan(other)`. **KDoc registra** que essa comparação serve a testes e telemetria e **não** é
    usada para decidir a escrita — a decisão é do DynamoDB (FR-021).
  - **Testes esperados**: `«test»/domain/model/EventTimestampTest.kt` — rejeita 0 e negativo; ordena
    corretamente.

- [X] T015 [P] Criar os enums de status em `«main»/domain/model/`
  - **Objetivo**: implementar a **igualdade positiva** de FR-008a: o que não é exatamente
    `APPROVED`/`ENABLED` não atualiza saldo — sem que isso seja erro de mensagem.
  - **Arquivos**: `«main»/domain/model/TransactionStatus.kt`, `«main»/domain/model/AccountStatus.kt`,
    `«main»/domain/model/TransactionType.kt`
  - **Depende de**: T009
  - **Requisitos**: FR-008, **FR-008a**, Constitution IX
  - **Concluído quando**: `TransactionStatus.from(raw: String?)` devolve `APPROVED` só para `"APPROVED"`
    exato e `NOT_APPROVED` para tudo mais, **inclusive `null`**; idem `AccountStatus.from`;
    `TransactionType.from` com fallback `UNKNOWN`. **Nenhuma das três funções lança** — é isso que faz
    status ausente cair na linha 5 da matriz, e não na linha 6.
  - **Testes esperados**: `«test»/domain/model/TransactionStatusTest.kt`,
    `«test»/domain/model/AccountStatusTest.kt` — `"DECLINED"`, `"approved"`, `""`, `null`, `"PENDING"`
    todos mapeiam para o valor negativo.

- [X] T016 Criar a entidade `Account` em `«main»/domain/model/Account.kt`
  - **Objetivo**: representar o estado da conta **no instante do evento** — não é o estado persistido.
  - **Arquivos**: `«main»/domain/model/Account.kt`
  - **Depende de**: T010, T011, T013, T015
  - **Requisitos**: FR-003, FR-013
  - **Concluído quando**: campos `id: AccountId`, `ownerId: OwnerId?`, `status: AccountStatus`,
    `balance: Money`; imutável.
  - **Testes esperados**: `«test»/domain/model/AccountTest.kt` — construção e igualdade estrutural.

- [X] T017 Criar a entidade `Transaction` em `«main»/domain/model/Transaction.kt`
  - **Objetivo**: representar a transação. `amount` é **informativo** — Constitution VIII proíbe
    aplicá-lo a um saldo.
  - **Arquivos**: `«main»/domain/model/Transaction.kt`
  - **Depende de**: T011, T013, T014, T015
  - **Requisitos**: FR-003, FR-014, Constitution VIII
  - **Concluído quando**: campos `id`, `status`, `timestamp`, `type`, `amount: Money?`; **KDoc registra
    que `amount` nunca influencia o saldo**.
  - **Testes esperados**: `«test»/domain/model/TransactionTest.kt`.

- [X] T018 [P] Criar `IneligibilityReason` e `EligibilityDecision` em `«main»/domain/eligibility/`
  - **Objetivo**: tornar a inelegibilidade um valor de domínio com razões enumeradas, para que FR-011
    (registrar o motivo) seja estrutural.
  - **Arquivos**: `«main»/domain/eligibility/IneligibilityReason.kt`,
    `«main»/domain/eligibility/EligibilityDecision.kt`
  - **Depende de**: T009
  - **Requisitos**: FR-008, FR-011, Constitution IX
  - **Concluído quando**: `IneligibilityReason` = `{TRANSACTION_NOT_APPROVED, ACCOUNT_NOT_ENABLED}`;
    `EligibilityDecision` é `sealed` com `Eligible` e `Ineligible(reasons: Set<IneligibilityReason>)` —
    o `Set` permite **as duas razões simultaneamente** (spec US3 cenário 3).
  - **Testes esperados**: coberto por T059.

- [X] T019 Criar o agregado `Balance` em `«main»/domain/model/Balance.kt`
  - **Objetivo**: o estado corrente projetado de uma conta — o que é persistido e exposto.
  - **Arquivos**: `«main»/domain/model/Balance.kt`
  - **Depende de**: T010, T011, T013, T014
  - **Requisitos**: FR-029, FR-031, FR-013, FR-015, FR-016, FR-017
  - **Concluído quando**: campos `accountId`, `ownerId: OwnerId?`, `money`, `asOf: EventTimestamp`,
    `lastTransactionId`. **Não existe construtor que receba um saldo anterior** — derivar saldo é
    estruturalmente impossível.
  - **Testes esperados**: `«test»/domain/model/BalanceTest.kt` — campos obrigatórios e igualdade.

- [X] T020 Criar o agregado `TransactionEvent` em `«main»/domain/model/TransactionEvent.kt`
  - **Objetivo**: agregado raiz da ingestão — o único ponto que julga elegibilidade e produz um
    `Balance`. Constitution IX exige que a elegibilidade seja avaliada no **domínio**, nunca no adapter.
  - **Arquivos**: `«main»/domain/model/TransactionEvent.kt`
  - **Depende de**: T016, T017, T018, T019
  - **Requisitos**: FR-008, FR-008a, FR-013, FR-014, Constitution VIII e IX
  - **Concluído quando**: `evaluateEligibility(): EligibilityDecision` devolve `Eligible` somente com
    `APPROVED` **e** `ENABLED`; `toBalance(): Balance` copia `account.balance` **literalmente** e tem
    pré-condição de elegibilidade.
  - **Testes esperados**: T059 (matriz de elegibilidade) e T060 (snapshot literal).

- [X] T021 [P] Criar `RejectionReason` e `ProcessingOutcome` em `«main»/domain/outcome/`
  - **Objetivo**: representar os cinco desfechos de negócio — linhas 1 a 5 da matriz de decisão. A
    linha 5a (mensagem de outro fluxo) **não** entra aqui: ela é decidida no adapter, antes de a
    mensagem virar evento de domínio.
  - **Arquivos**: `«main»/domain/outcome/RejectionReason.kt`,
    `«main»/domain/outcome/ProcessingOutcome.kt`
  - **Depende de**: T018, T019
  - **Requisitos**: FR-012, FR-022, FR-023, matriz de decisão da spec
  - **Concluído quando**: `RejectionReason` = `{DUPLICATE_EVENT, STALE_EVENT, TIMESTAMP_TIE_REJECTED}`;
    `ProcessingOutcome` é `sealed` com `Applied(balance)`, `IgnoredIneligible(reasons)` e
    `Rejected(reason, persisted: Balance?)`.
  - **Testes esperados**: coberto por T062 e T063.

**Checkpoint**: domínio completo e testável sem container, broker ou datastore (Constitution II).

---

## Phase 3: Input ports

- [X] T022 [P] Criar `ProcessTransactionEventUseCase` em `«main»/port/input/ProcessTransactionEventUseCase.kt`
  - **Objetivo**: contrato de ingestão, consumido pelo adapter Kafka.
  - **Arquivos**: `«main»/port/input/ProcessTransactionEventUseCase.kt`
  - **Depende de**: T020, T021
  - **Requisitos**: FR-001, FR-008, Constitution III
  - **Concluído quando**: `fun process(event: TransactionEvent): ProcessingOutcome`; nenhum tipo de
    transporte (payload, header, offset) aparece na assinatura.
  - **Testes esperados**: nenhum próprio (interface); verificado por T006.

- [X] T023 [P] Criar `GetBalanceUseCase` em `«main»/port/input/GetBalanceUseCase.kt`
  - **Objetivo**: contrato de consulta, consumido pelo adapter Web.
  - **Arquivos**: `«main»/port/input/GetBalanceUseCase.kt`
  - **Depende de**: T010, T019
  - **Requisitos**: FR-042, FR-043, Constitution III
  - **Concluído quando**: `fun getBalance(accountId: AccountId): Balance`; nenhum status HTTP na
    assinatura — a ausência de saldo é sinalizada por `BalanceNotFoundException`.
  - **Testes esperados**: nenhum próprio (interface); verificado por T006.

---

## Phase 4: Output ports

- [X] T024 [P] Criar `BalanceWriteResult` em `«main»/port/output/BalanceWriteResult.kt`
  - **Objetivo**: transportar o resultado da escrita condicional para a aplicação **sem** vazar
    vocabulário do DynamoDB.
  - **Arquivos**: `«main»/port/output/BalanceWriteResult.kt`
  - **Depende de**: T019
  - **Requisitos**: FR-019, FR-020, FR-023
  - **Concluído quando**: `sealed` com `Applied`, `Duplicate(persisted)`, `Stale(persisted)`,
    `TimestampTie(persisted)`. **Falha transitória não é variante deste tipo** — é exceção, porque
    precisa subir até o container Kafka para acionar retry.
  - **Testes esperados**: coberto por T062.

- [X] T025 [P] Criar `BalanceWriter` em `«main»/port/output/BalanceWriter.kt`
  - **Objetivo**: contrato de escrita condicional do estado corrente.
  - **Arquivos**: `«main»/port/output/BalanceWriter.kt`
  - **Depende de**: T019, T024
  - **Requisitos**: FR-032, FR-021, Constitution V e VI
  - **Concluído quando**: `fun save(balance: Balance): BalanceWriteResult`. **KDoc registra que a
    condição de frescor é avaliada atomicamente pelo armazenamento** — nenhuma implementação pode
    satisfazer o contrato com comparação em memória.
  - **Testes esperados**: nenhum próprio (interface).

- [X] T026 [P] Criar `BalanceReader` em `«main»/port/output/BalanceReader.kt`
  - **Objetivo**: contrato de leitura do estado corrente.
  - **Arquivos**: `«main»/port/output/BalanceReader.kt`
  - **Depende de**: T010, T019
  - **Requisitos**: FR-030, FR-033
  - **Concluído quando**: `fun findByAccountId(accountId: AccountId): Balance?`; KDoc registra a
    exigência de leitura fortemente consistente.
  - **Testes esperados**: nenhum próprio (interface).

- [X] T027 [P] Criar `BalanceTelemetry` em `«main»/port/output/BalanceTelemetry.kt`
  - **Objetivo**: tornar a lista de coisas observáveis um **contrato revisável em um arquivo** contra
    FR-052, em vez de chamadas espalhadas — e manter a aplicação testável sem `MeterRegistry`.
  - **Arquivos**: `«main»/port/output/BalanceTelemetry.kt`
  - **Depende de**: T021
  - **Requisitos**: FR-052, FR-054, FR-055, **FR-004a**, Constitution XIII
  - **Concluído quando**: métodos `eventReceived`, `outcomeRecorded(outcome, context, elapsed)`,
    **`unsupportedMessage(reason, context)`** (FR-004a), `persistenceFailed(context, transient, cause)`
    e `anomalyDetected`; todos os parâmetros são tipos de domínio ou primitivos — nenhum tipo de
    Micrometer.
  - **Testes esperados**: nenhum próprio (interface); exaustividade verificada por T063.

**Checkpoint**: todos os contratos definidos; adapters e aplicação podem ser escritos em paralelo.

---

## Phase 5: Application

**⚠️ Nota de ordenação**: a partir de T028 o contexto Spring exige um bean `BalanceTelemetry`. Se você
precisa de `ApplicationTests.contextLoads` verde a cada checkpoint, antecipe **T056**. Caso contrário o
contexto só volta a subir no fim da Phase 9 — os testes unitários (com mocks) permanecem verdes em ambos
os casos.

- [X] T028 Implementar `ProcessTransactionEventService` em `«main»/application/ProcessTransactionEventService.kt`
  - **Objetivo**: orquestrar elegibilidade → escrita condicional → desfecho → telemetria. A aplicação
    **não decide** elegibilidade (é do domínio, T020) nem ordenação (é do DynamoDB, T033).
  - **Arquivos**: `«main»/application/ProcessTransactionEventService.kt`
  - **Depende de**: T020, T021, T022, T024, T025, T027
  - **Requisitos**: FR-008 a FR-012, FR-019 a FR-024, FR-052, FR-054, INV-003, INV-005
  - **Concluído quando**:
    1. evento inelegível → devolve `IgnoredIneligible(reasons)` e **`BalanceWriter` nunca é chamado**
       (INV-003 — é assim que o marcador de frescor não avança);
    2. evento elegível → `event.toBalance()` é passado a `BalanceWriter.save`;
    3. cada `BalanceWriteResult` mapeia para o `ProcessingOutcome` correspondente;
    4. **exceção transitória do writer é propagada**, não capturada — o container Kafka precisa vê-la
       para acionar retry (FR-034, FR-036);
    5. toda variante de desfecho chama `BalanceTelemetry.outcomeRecorded` exatamente uma vez, com o
       `EventContext` preenchido apenas com `accountId` e `transactionId` — os campos de transporte
       (`topic`, `partition`, `offset`) ficam nulos aqui e são preenchidos pelo consumer, porque a
       aplicação não conhece Kafka (Constitution III).
  - **Testes esperados**: T061, T062, T063.

- [X] T029 [P] Implementar `GetBalanceService` em `«main»/application/GetBalanceService.kt`
  - **Objetivo**: consulta do estado corrente.
  - **Arquivos**: `«main»/application/GetBalanceService.kt`
  - **Depende de**: T023, T026, T009
  - **Requisitos**: FR-042, FR-047, FR-051, Constitution I
  - **Concluído quando**: devolve o `Balance` quando existe; lança `BalanceNotFoundException` quando o
    reader devolve `null` — **nunca devolve `0.00`** para conta desconhecida; nenhuma escrita ocorre.
  - **Testes esperados**: T064.

---

## Phase 6: DynamoDB

**Propósito**: **é aqui que mora toda a corretude de concorrência, ordenação e idempotência** — em uma
única expressão condicional avaliada pelo banco.

- [X] T030 [P] Criar `BalanceTableAttributes` em `«main»/adapter/output/dynamodb/BalanceTableAttributes.kt`
  - **Objetivo**: concentrar os nomes de atributo e a derivação da partition key em um único lugar, para
    que o domínio nunca veja a chave.
  - **Arquivos**: `«main»/adapter/output/dynamodb/BalanceTableAttributes.kt`
  - **Depende de**: T010
  - **Requisitos**: FR-030, FR-031, ADR-008
  - **Concluído quando**: constantes `PK`, `ACCOUNT_ID`, `OWNER_ID`, `BALANCE_AMOUNT`,
    `BALANCE_CURRENCY`, `LAST_EVENT_TIMESTAMP`, `LAST_TRANSACTION_ID`, `UPDATED_AT`; função
    `partitionKey(accountId) = "ACCOUNT#" + accountId.value`.
  - **Testes esperados**: coberto por T075.

- [X] T031 Implementar `BalanceItemMapper` em `«main»/adapter/output/dynamodb/BalanceItemMapper.kt`
  - **Objetivo**: traduzir `Balance` ↔ item DynamoDB **sem que nenhum valor monetário passe por
    `Double`**.
  - **Arquivos**: `«main»/adapter/output/dynamodb/BalanceItemMapper.kt`
  - **Depende de**: T019, T030
  - **Requisitos**: FR-018, FR-031, INV-006, Constitution X, ADR-006
  - **Concluído quando**:
    1. `balanceAmount` escrito como **`S`** via `AttributeValue.s(money.amount.toPlainString())` — o tipo
       `N` remove zeros à direita (`150.00` → `"150"`) e destruiria a escala que FR-044 exige;
    2. `lastEventTimestamp` escrito como **`N`** — aqui a comparação numérica é obrigatória, pois é o
       operando da condição de frescor;
    3. `ownerId` omitido quando ausente;
    4. `updatedAt` (atributo do item) é relógio da aplicação e **não é usado em nenhuma decisão** — não
       confundir com o campo `updated_at` da resposta REST, que deriva do evento (T050);
    5. a leitura reconstrói `Money` preservando escala 2.
  - **Testes esperados**: T075 — round-trip preserva valor e escala; `pk` = `ACCOUNT#{id}`.

- [X] T032 Estender `DynamoDbConfig` com timeouts e retry do SDK desligado em `«main»/adapter/output/dynamodb/DynamoDbConfig.kt`
  - **Objetivo**: estabelecer a **autoridade única de retry**. O SDK retenta 3× por padrão; com 4
    tentativas do container por cima, o total vira 12 e o orçamento de SC-008 deixa de ser calculável.
  - **Arquivos**: `«main»/adapter/output/dynamodb/DynamoDbConfig.kt` (modificar — preservar o bean e as
    credenciais locais existentes)
  - **Depende de**: —
  - **Requisitos**: FR-036, SC-008, Constitution XI, ADR-005
  - **Concluído quando**: `ClientOverrideConfiguration` com `apiCallAttemptTimeout = 1s`,
    `apiCallTimeout = 2s` e estratégia de retry sem tentativas adicionais; o bean continua funcionando
    contra DynamoDB Local.
  - **Testes esperados**: verificado em T089 (integração real).

- [X] T033 Implementar a escrita condicional em `«main»/adapter/output/dynamodb/DynamoDbBalanceWriter.kt`
  - **Objetivo**: **o núcleo de corretude do sistema.** A condição é avaliada pelo DynamoDB dentro da
    mesma operação atômica que escreve — não existe janela entre avaliar e escrever, então nenhum
    entrelaçamento de threads, consumers ou instâncias pode produzir *lost update*.
  - **Arquivos**: `«main»/adapter/output/dynamodb/DynamoDbBalanceWriter.kt`
  - **Depende de**: T025, T030, T031, T032
  - **Requisitos**: FR-019, FR-020, FR-021, FR-032, INV-001, INV-002, INV-004, Constitution V e VI,
    ADR-004
  - **Concluído quando**: `PutItem` com
    `ConditionExpression = "attribute_not_exists(#pk) OR #lastEventTimestamp < :incomingTimestamp"`,
    `ExpressionAttributeNames` mapeando `#pk` e `#lastEventTimestamp` (aliases usados **mesmo onde o nome
    não é reservado**, como defesa contra renomeações futuras), `:incomingTimestamp` como `N`, e
    `ReturnValuesOnConditionCheckFailure = ALL_OLD`. Sucesso devolve `Applied`.
    **Nenhum `GetItem` precede o `PutItem`** (FR-028). A comparação é `<` **estrito**, o que implementa
    a decisão A-03: empate rejeita, sem desempate determinístico.
    > A ADR-001 existente propõe `timestamp < :newTimestamp` — `timestamp` **é palavra reservada** no
    > DynamoDB e a expressão falha em runtime sem alias. ADR-004 a substitui (T096).
  - **Testes esperados**: T071 (expressão exata), T081 (comportamento real).

- [X] T034 Implementar a classificação de `ConditionalCheckFailedException` e a tradução de erros em `«main»/adapter/output/dynamodb/DynamoDbBalanceWriter.kt`
  - **Objetivo**: distinguir duplicado, antigo e empate (FR-023) **sem uma segunda chamada** — usar
    `GetItem` de diagnóstico reintroduziria a corrida que a condição acabou de eliminar.
  - **Arquivos**: `«main»/adapter/output/dynamodb/DynamoDbBalanceWriter.kt` (mesmo arquivo de T033)
  - **Depende de**: T033, T040
  - **Requisitos**: FR-022, FR-023, FR-036, FR-037, INV-005, Constitution XI
  - **Concluído quando**:
    1. o item de `e.item()` (`ALL_OLD`) é classificado **nesta ordem**:
       `persisted.lastTransactionId == incoming.transactionId` → `Duplicate`;
       `persisted.lastEventTimestamp == incoming.micros` → `TimestampTie`; senão → `Stale`.
       A ordem importa: identidade antes de timestamp garante que a reentrega do mesmo evento seja
       sempre `Duplicate`, mesmo com timestamp empatado (FR-023);
    2. `ProvisionedThroughputExceededException`, `RequestLimitExceededException`,
       `InternalServerErrorException` e timeouts do SDK são traduzidos para
       `TransientProcessingException` — **exceção da AWS não vaza para o adapter de entrada**
       (Constitution III);
    3. se `ALL_OLD` não estiver disponível no ambiente, o fallback documentado é colapsar para um rótulo
       genérico: a **corretude é idêntica**, apenas a telemetria perde granularidade.
  - **Testes esperados**: T072, T073.

- [X] T035 [P] Implementar `DynamoDbBalanceReader` em `«main»/adapter/output/dynamodb/DynamoDbBalanceReader.kt`
  - **Objetivo**: leitura que nunca devolve estado anterior a uma escrita concluída.
  - **Arquivos**: `«main»/adapter/output/dynamodb/DynamoDbBalanceReader.kt`
  - **Depende de**: T026, T030, T031, T032
  - **Requisitos**: FR-030, FR-033, SC-005, ADR-003
  - **Concluído quando**: `GetItem` com `consistentRead = true`; item ausente → `null`; sem cache, sem
    `Query`, sem `Scan`.
  - **Testes esperados**: T074.

- [X] T036 Configurar a tabela em `src/main/resources/application.yaml`
  - **Objetivo**: nome da tabela por configuração, nunca fixado em código.
  - **Arquivos**: `src/main/resources/application.yaml`
  - **Depende de**: T005
  - **Requisitos**: FR-001
  - **Concluído quando**: `dynamodb.table-name: ${BALANCE_TABLE_NAME:AccountBalances}`; endpoint e região
    preservados do starter.
  - **Testes esperados**: `ApplicationTests.contextLoads`.

- [X] T037 Criar a tabela `AccountBalances` em `infra/dynamodb/seed.sh`
  - **Objetivo**: **estender** o script existente, não substituí-lo — Constitution exige preservar a
    infraestrutura local.
  - **Arquivos**: `infra/dynamodb/seed.sh` (modificar), `docker-compose.yml` (variáveis do serviço `app`
    e do `dynamodb-seed`), `Makefile` (alvo `db-scan`)
  - **Depende de**: T004
  - **Requisitos**: FR-029, FR-030, ADR-008
  - **Concluído quando**: cria a tabela com `AttributeName=pk,AttributeType=S`, `KeyType=HASH`,
    `PAY_PER_REQUEST`; a estrutura de espera e a idempotência do script original preservadas; o
    `batch-write-item` e a referência a `greeting-messages.json` removidos (a tabela nasce vazia e é
    populada por eventos); `BALANCE_TABLE_NAME` substitui `GREETING_TABLE_NAME` no compose.
  - **Testes esperados**: T087 — `make db-up` cria a tabela com o schema correto.

**Checkpoint**: persistência correta sob concorrência. Ordenação e idempotência já existem, mesmo sem
consumer.

---

## Phase 7: Kafka

**Propósito**: implementar a ingestão. O consumer **não trata erro** — deixa a exceção subir para o
container, que é a autoridade única de retry e DLQ. A exceção a essa regra é FR-004a, que **não** usa
exceção justamente para ficar fora do caminho da DLQ.

- [X] T038 [P] Criar `BalanceKafkaProperties` em `«main»/adapter/input/kafka/config/BalanceKafkaProperties.kt`
  - **Objetivo**: ser o único lugar do código que conhece nomes de tópico, group id e política de retry.
  - **Arquivos**: `«main»/adapter/input/kafka/config/BalanceKafkaProperties.kt`
  - **Depende de**: —
  - **Requisitos**: FR-001, FR-002, FR-038, A-01
  - **Concluído quando**: `@ConfigurationProperties("balance.kafka")` tipado com `topic`, `dlqTopic`,
    `consumerGroupId`, `concurrency` e o bloco `retry` (`maxAttempts`, `initialInterval`, `multiplier`,
    `maxInterval`, `jitter`); validado na inicialização.
  - **Testes esperados**: `ApplicationTests.contextLoads` com os defaults.

- [X] T039 [P] Criar os DTOs de mensagem em `«main»/adapter/input/kafka/dto/TransactionEventMessage.kt`
  - **Objetivo**: transportar o payload sem validar nada. **Todos os campos são nullable** — isso é
    deliberado: permite que o mapper (T042) diga *qual* campo faltou, e essa informação chega à DLQ no
    header `kafka_dlt-exception-message`.
  - **Arquivos**: `«main»/adapter/input/kafka/dto/TransactionEventMessage.kt` (com `TransactionMessage`,
    `AccountMessage`, `BalanceMessage` aninhados)
  - **Depende de**: —
  - **Requisitos**: FR-003, FR-039, INV-006
  - **Concluído quando**: espelha `contracts/transaction-event.schema.json`; `amount` declarado como
    **`BigDecimal?`** — Jackson desserializa direto para `BigDecimal` sem passar por `double` quando o
    tipo alvo é declarado, e é essa a garantia de INV-006 na borda de entrada. **`transaction` é
    nullable** para que FR-004a possa distinguir bloco ausente de bloco incompleto; `created_at`
    presente mas não usado.
  - **Testes esperados**: T065.

- [X] T040 [P] Criar as exceções de transporte em `«main»/adapter/input/kafka/exception/`
  - **Objetivo**: separar erro **permanente** de **transitório** — é essa distinção que o
    `DefaultErrorHandler` usa para decidir entre DLQ imediata e retry (FR-036, FR-037).
  - **Arquivos**: `«main»/adapter/input/kafka/exception/UnprocessableEventException.kt`,
    `«main»/port/output/TransientProcessingException.kt`
  - **Depende de**: —
  - **Requisitos**: FR-036, FR-037, Constitution XI
  - **Concluído quando**: `UnprocessableEventException` carrega o payload original e o motivo textual do
    campo faltante; `TransientProcessingException` envolve a causa. Nenhuma das duas vive no domínio.
    **`TransientProcessingException` mora em `port.output`, não no adapter Kafka**: quem a lança é o
    `DynamoDbBalanceWriter` (T034), e um adapter de saída não pode importar um adapter de entrada — a
    tabela de dependências de plan.md §2 não permite essa aresta. Como port, ela é parte do contrato que
    `BalanceWriter` oferece a qualquer chamador.
  - **Testes esperados**: cobertas por T066 e T073.

- [X] T041 [P] Criar `MessageInterpretation` e `UnsupportedReason` em `«main»/adapter/input/kafka/mapper/`
  - **Objetivo**: **habilitar FR-004a.** O descarte de mensagem de outro fluxo é um desfecho terminal de
    **sucesso**, então não pode ser sinalizado por exceção — qualquer exceção que suba do listener é
    capturada pelo `DefaultErrorHandler`, que a rotearia para a DLQ, exatamente o que se quer evitar. Um
    tipo selado mantém o descarte fora do caminho de erro.
  - **Arquivos**: `«main»/adapter/input/kafka/mapper/MessageInterpretation.kt`,
    `«main»/adapter/input/kafka/mapper/UnsupportedReason.kt`
  - **Depende de**: T020
  - **Requisitos**: **FR-004a**, FR-035, INV-008, matriz linha 5a
  - **Concluído quando**: `MessageInterpretation` é `sealed` com `Interpreted(event: TransactionEvent)` e
    `Unsupported(reason: UnsupportedReason)`; `UnsupportedReason` = `{NO_TRANSACTION_BLOCK}`. Mensagem
    **inválida não é variante** deste tipo — continua sendo `UnprocessableEventException` lançada.
    Ambos vivem no **adapter**, não no domínio: distinguir "que tipo de mensagem é esta" é concern de
    transporte, não regra de banking.
  - **Testes esperados**: T068.

- [X] T042 Implementar `TransactionEventMessageMapper` com triagem em três vias em `«main»/adapter/input/kafka/mapper/TransactionEventMessageMapper.kt`
  - **Objetivo**: traduzir JSON → domínio e **decidir as duas fronteiras**: outro fluxo × inválido, e
    inválido × inelegível.
  - **Arquivos**: `«main»/adapter/input/kafka/mapper/TransactionEventMessageMapper.kt`
  - **Depende de**: T020, T039, T040, T041
  - **Requisitos**: FR-003, **FR-004**, **FR-004a**, FR-005, **FR-008a**, Edge Cases
  - **Concluído quando** `interpret(payload: String): MessageInterpretation` faz, nesta ordem:
    1. JSON malformado → `UnprocessableEventException`;
    2. **bloco `transaction` integralmente ausente** → devolve `Unsupported(NO_TRANSACTION_BLOCK)` e
       **não lança** (FR-004a). É o formato `{"account": {...}}` de
       `make kafka-produce-accounts-events` — mensagem de outro fluxo, não defeito;
    3. ausência de `transaction.id`, `transaction.timestamp`, `account.id`, `account.balance.amount` ou
       `account.balance.currency` → `UnprocessableEventException` com mensagem que **nomeia o campo**.
       Aqui o bloco `transaction` **existe**, então falta de campo dentro dele é defeito;
    4. `InvalidMoneyException` / `InvalidCurrencyCodeException` / `InvalidAccountIdException` envolvidas
       em `UnprocessableEventException`;
    5. status via `from(raw)` — **nunca falha**, mapeia ausente e desconhecido para o valor negativo
       (FR-008a).
    A distinção crítica: **bloco ausente por completo** ≠ **bloco presente porém vazio ou nulo**.
    `{"transaction":{}}` e `{"transaction":null}` vão para a **DLQ**.
  - **Testes esperados**: T065, T066, T067, **T068**.

- [X] T043 Implementar `TransactionEventConsumer` em `«main»/adapter/input/kafka/TransactionEventConsumer.kt`
  - **Objetivo**: ponto de entrada da ingestão. Recebe o payload como **`String`** — escolha deliberada:
    manter o original disponível dentro do listener é o que permite publicar na DLQ byte a byte
    (FR-039).
  - **Arquivos**: `«main»/adapter/input/kafka/TransactionEventConsumer.kt`
  - **Depende de**: T022, T027, T038, T041, T042
  - **Requisitos**: FR-001, FR-002, **FR-004a**, FR-035, FR-039, FR-052
  - **Concluído quando**: `@KafkaListener` com tópico, group id e `concurrency` da configuração; chama
    `mapper.interpret(payload)`; com `Unsupported`, chama `BalanceTelemetry.unsupportedMessage` e
    **retorna normalmente sem chamar o use case** — o retorno normal é o que autoriza o container a
    confirmar o offset (linha 5a); com `Interpreted`, chama o use case — **sem** chamar `outcomeRecorded`, que pertence a T028
    (registrar nos dois lugares contaria cada desfecho duas vezes);
    **não captura exceção do use case** — capturar criaria uma segunda camada de retry, o que ADR-005
    proíbe.
  - **Testes esperados**: T069.

- [X] T044 Configurar a fábrica de container e o acknowledgment em `«main»/adapter/input/kafka/config/KafkaConsumerConfig.kt`
  - **Objetivo**: garantir que nenhum offset seja confirmado antes de um desfecho terminal.
  - **Arquivos**: `«main»/adapter/input/kafka/config/KafkaConsumerConfig.kt`
  - **Depende de**: T038
  - **Requisitos**: FR-034, FR-035, INV-007, Constitution XII
  - **Concluído quando**: `ConcurrentKafkaListenerContainerFactory` com `enable-auto-commit: false` e
    `AckMode.RECORD` — o container commita **depois** do retorno normal do listener, e não commita
    quando ele lança. **Nenhum código de commit manual**: `MANUAL_IMMEDIATE` exigiria coordenar o
    `Acknowledgment` com o `ackAfterHandle` do error handler, criando dois lugares que decidem commit
    para a mesma semântica.
  - **Testes esperados**: T070; comportamento real em T084.

- [X] T045 Configurar retry com backoff exponencial e jitter em `«main»/adapter/input/kafka/config/KafkaConsumerConfig.kt`
  - **Objetivo**: autoridade **única** de retry, com janela calculável.
  - **Arquivos**: `«main»/adapter/input/kafka/config/KafkaConsumerConfig.kt` (mesmo arquivo de T044)
  - **Depende de**: T040, T044
  - **Requisitos**: FR-036, FR-037, SC-008, Constitution XI, ADR-005
  - **Concluído quando**:
    1. `DefaultErrorHandler` com `ExponentialBackOff(initial = 200ms, multiplier = 2.0, max = 5s,
       jitter = 100ms)` e 4 tentativas — janela total ≈ 1,4 s, **duas ordens de grandeza abaixo** de
       `max.poll.interval.ms` (5 min), de modo que o retry nunca dispara rebalance;
    2. `addNotRetryableExceptions(UnprocessableEventException)` e
       `addRetryableExceptions(TransientProcessingException)`;
    3. exceção não classificada é tratada como **não-retryable** — escolha conservadora: uma exceção
       desconhecida provavelmente é bug determinístico, e retentá-la só atrasa a partição;
    4. o jitter é justificado no KDoc: sem ele, K consumers que sofrem o mesmo timeout retentam
       sincronizados e o throttle se auto-perpetua.
    > Se `ExponentialBackOff.jitter` não existir na versão do Spring em uso (risco R-04), implementar um
    > `BackOff` próprio somando deslocamento aleatório — **não** remover o jitter.
  - **Testes esperados**: T070.

- [X] T046 Configurar a DLQ em `«main»/adapter/input/kafka/config/KafkaConsumerConfig.kt`
  - **Objetivo**: destino observável para mensagem inválida e para retry esgotado — e **apenas** para
    esses dois casos (FR-004a mantém mensagem de outro fluxo fora daqui).
  - **Arquivos**: `«main»/adapter/input/kafka/config/KafkaConsumerConfig.kt` (mesmo arquivo de T044)
  - **Depende de**: T045
  - **Requisitos**: FR-038, FR-039, FR-040, INV-008,
    [contracts/dlq-message.md](./contracts/dlq-message.md)
  - **Concluído quando**:
    1. `DeadLetterPublishingRecoverer` sobre `KafkaTemplate<String, String>`;
    2. resolver de destino devolve `TopicPartition(dlqTopic, -1)` — o `-1` faz o produtor escolher a
       partição por hash da chave, preservando o agrupamento por conta. O padrão (reusar o número da
       partição de origem) é rejeitado porque acopla a contagem de partições da DLQ à do tópico de
       entrada, e uma DLQ com menos partições transformaria uma mensagem ruim em partição travada;
    3. `setHeadersFunction` acrescenta `x-failure-reason` (`INVALID_MESSAGE` | `PERMANENT_FAILURE`) e
       `x-correlation-id`, além dos `kafka_dlt-*` automáticos;
    4. o **valor publicado é o payload original**, sem reserialização; a chave original é preservada;
    5. falha ao publicar → o recoverer lança, o container faz `seek`, o offset **não** é confirmado
       (FR-040).
  - **Testes esperados**: T070 (resolver e headers), T084 (comportamento real).

- [X] T047 Configurar Kafka em `src/main/resources/application.yaml`
  - **Objetivo**: nomes e política por configuração (A-01).
  - **Arquivos**: `src/main/resources/application.yaml`
  - **Depende de**: T005, T038
  - **Requisitos**: FR-001, FR-002, FR-038, FR-041
  - **Concluído quando**: bloco `balance.kafka` com `topic: ${TRANSACTIONS_TOPIC:transactions-events}`,
    `dlq-topic: ${TRANSACTIONS_DLQ_TOPIC:transactions-events.dlq}`,
    `consumer-group-id: ${KAFKA_CONSUMER_GROUP_ID:core-banking-balance-consumer}`,
    `concurrency: ${KAFKA_CONSUMER_CONCURRENCY:3}` e o bloco `retry`; `spring.kafka.consumer` com
    `enable-auto-commit: false`; `spring.kafka.producer` com `acks: all` e `enable-idempotence: true`
    para o publisher da DLQ; `spring.kafka.listener.ack-mode: RECORD`.
  - **Testes esperados**: `ApplicationTests.contextLoads`.

- [X] T048 Criar os tópicos e chavear o produtor de teste em `infra/redpanda/seed.sh`
  - **Objetivo**: `infra/redpanda/config.sh` **desabilita a criação automática de tópicos** — eles
    precisam ser criados explicitamente, senão a produção falha em vez de criar silenciosamente.
  - **Arquivos**: `infra/redpanda/seed.sh` (modificar),
    `infra/redpanda/produce-transactions-events.sh` (modificar), `docker-compose.yml`, `Makefile`
  - **Depende de**: T004
  - **Requisitos**: FR-001, FR-006, FR-038, ADR-002
  - **Concluído quando**: `transactions-events` e `transactions-events.dlq` criados com **3 partições**
    cada, preservando a estrutura de espera e a idempotência do script original; a publicação de
    `greeting-templates-seed.jsonl` removida; `produce-transactions-events.sh` publica com **chave =
    `account.id`** (`-f '%k %v\n'`) — hoje publica sem chave; `infra/redpanda/config.sh` **intacto**.
    **`produce-accounts-events.sh` é preservado sem alteração** — ele é a fonte das mensagens de outro
    fluxo que T068 e T090 usam para exercitar FR-004a.
  - **Testes esperados**: T088.

**Checkpoint**: ingestão fim a fim funcional; US2, US3, US4 e US6 exercitáveis manualmente.

---

## Phase 8: REST

**Propósito**: expor `GET /balances/{accountId}`. Contrato em
[contracts/balances-api.yaml](./contracts/balances-api.yaml).

- [X] T049 [P] [US1] Criar os DTOs de resposta em `«main»/adapter/input/web/dto/`
  - **Objetivo**: expor dinheiro como **texto**, nunca número JSON — um `BigDecimal` serializado sem
    aspas vira `150.0` ou `150` conforme a configuração, e é lido como ponto flutuante pela maioria dos
    clientes.
  - **Arquivos**: `«main»/adapter/input/web/dto/BalanceResponse.kt`,
    `«main»/adapter/input/web/dto/ErrorResponse.kt`
  - **Depende de**: —
  - **Requisitos**: FR-043, FR-044, FR-045, FR-046, FR-050, INV-006
  - **Concluído quando**: `BalanceResponse(accountId: String, balance: BalanceAmountResponse,
    lastTransactionId: String, updated_at: String)` com `BalanceAmountResponse(amount: String,
    currency: String)`; `ErrorResponse(code: String, message: String)`. **`amount` é `String`.**
    O campo **`updated_at`** mantém deliberadamente o snake_case da amostra do desafio ao lado de campos
    camelCase — inconsistência de estilo assumida em troca de compatibilidade com a avaliação. Em Kotlin
    é uma anotação de nome de propriedade no DTO; não contamina domínio nem persistência.
  - **Testes esperados**: T076 verifica que `amount` é serializado **entre aspas**.
  - > **Superado em 2026-09-07 por [T104](#phase-13-correção-do-contrato-revisão-de-2026-09-07)**: o
    > corpo passa a ter quatro chaves (`id`, `owner`, `balance`, `updated_at`) e `amount` passa a
    > `BigDecimal`, serializado como número JSON. O argumento acima descrevia o parser do cliente, não a
    > saída do serviço — ver [R19](./research.md).

- [X] T050 [P] [US1] Implementar `BalanceResponseMapper` em `«main»/adapter/input/web/mapper/BalanceResponseMapper.kt`
  - **Objetivo**: traduzir domínio → DTO preservando exatidão decimal e o formato temporal exigido.
  - **Arquivos**: `«main»/adapter/input/web/mapper/BalanceResponseMapper.kt`
  - **Depende de**: T019, T049
  - **Requisitos**: FR-044, FR-045, **FR-046**, ADR-010
  - **Concluído quando**:
    1. `amount` ← `toPlainString()` (escala já normalizada em 2 por `Money`);
    2. `updated_at` ← `Instant.EPOCH.plus(asOf.micros, MICROS)`, convertido para o fuso configurado
       (`balance.api.timezone`, padrão `America/Sao_Paulo`) e formatado em ISO-8601 com offset local e
       **exatamente 3 dígitos** fracionários — ex.: `2025-07-05T18:04:13.433-03:00`;
    3. **truncamento, não arredondamento**: `truncatedTo(ChronoUnit.MILLIS)`. Arredondar `...589998` µs
       para `...590` ms exibiria um instante que nunca existiu e, no limite, um instante futuro;
    4. a perda de precisão é **só de exibição** — o valor persistido continua em microssegundos e é ele
       que decide ordenação (FR-016, FR-019).
  - **Testes esperados**: T077.
  - > **Parcialmente superado em 2026-09-07 por [T105](#phase-13-correção-do-contrato-revisão-de-2026-09-07)**:
    > o item 1 (`amount` ← `toPlainString()`) deixa de valer; `amount` passa a sair como `BigDecimal`.
    > Os itens 2 a 4, sobre `updated_at`, **continuam válidos e inalterados**.

- [X] T051 [US1] Implementar `BalanceController` em `«main»/adapter/input/web/BalanceController.kt`
  - **Objetivo**: expor o endpoint. A construção de `AccountId` acontece **antes** de qualquer chamada ao
    use case, o que satisfaz FR-048 ("sem consultar o armazenamento") **por construção**.
  - **Arquivos**: `«main»/adapter/input/web/BalanceController.kt`
  - **Depende de**: T010, T023, T049, T050
  - **Requisitos**: FR-042, FR-043, FR-048, FR-051
  - **Concluído quando**: `@GetMapping("/balances/{accountId}", produces = APPLICATION_JSON_VALUE)`;
    delega ao `GetBalanceUseCase`; nenhuma validação, formatação ou decisão de status no controller.
  - **Testes esperados**: T076.

- [X] T052 [US1] Implementar `BalanceExceptionHandler` em `«main»/adapter/input/web/BalanceExceptionHandler.kt`
  - **Objetivo**: mapear exceções de domínio para status HTTP sem vazar detalhe interno.
  - **Arquivos**: `«main»/adapter/input/web/BalanceExceptionHandler.kt`
  - **Depende de**: T009, T049
  - **Requisitos**: FR-047, FR-048, FR-049, FR-050
  - **Concluído quando**: `@RestControllerAdvice` mapeando `InvalidAccountIdException` → `400`
    (`INVALID_ACCOUNT_ID`), `BalanceNotFoundException` → `404` (`BALANCE_NOT_FOUND`), `Exception` → `500`
    (`INTERNAL_ERROR`). O handler de `500` **loga a exceção completa no servidor e devolve mensagem
    fixa** — nada de `e.message` no corpo (FR-049).
  - **Testes esperados**: T076.

- [X] T053 [P] [US1] Implementar `CorrelationIdFilter` em `«main»/adapter/input/web/CorrelationIdFilter.kt`
  - **Objetivo**: correlacionar a consulta com os logs estruturados.
  - **Arquivos**: `«main»/adapter/input/web/CorrelationIdFilter.kt`
  - **Depende de**: —
  - **Requisitos**: FR-055, Constitution XIII
  - **Concluído quando**: `OncePerRequestFilter` lê `X-Correlation-Id` ou gera UUID; popula MDC com
    `correlationId` e `accountId`; devolve o header na resposta; **limpa o MDC em `finally`**.
  - **Testes esperados**: T076 verifica a presença do header.

- [X] T054 [US1] Configurar o fuso de renderização em `src/main/resources/application.yaml`
  - **Objetivo**: o fuso de `updated_at` é decisão operacional, não constante de código.
  - **Arquivos**: `src/main/resources/application.yaml`
  - **Depende de**: T005, T050
  - **Requisitos**: FR-046, risco R-15
  - **Concluído quando**: `balance.api.timezone: ${BALANCE_API_TIMEZONE:America/Sao_Paulo}`.
    **Registrar no comentário** que `America/Sao_Paulo` é `-03:00` hoje porque o Brasil aboliu o horário
    de verão em 2019; se voltar, a renderização alterna com `-02:00`. Se a avaliação exigir offset fixo,
    configurar `-03:00` literal.
  - **Testes esperados**: T077 verifica que o fuso configurado é respeitado.

**Checkpoint**: MVP funcional — as seis user stories implementadas. Falta a observabilidade que
Constitution XIII torna obrigatória.

---

## Phase 9: Observability

**Propósito**: FR-054 exige que todo desfecho **não-mutante** seja observável — "silencioso no efeito,
nunca na telemetria". Sem esta fase, os ramos "não faz nada" são indistinguíveis do sistema quebrado.

- [X] T055 [P] Configurar logging estruturado em `src/main/resources/application.yaml`
  - **Objetivo**: logs em JSON com os campos do MDC, **sem dependência nova** — o Boot 4.1 suporta
    nativamente.
  - **Arquivos**: `src/main/resources/application.yaml`
  - **Depende de**: T005
  - **Requisitos**: FR-055, Constitution XIII e XV
  - **Concluído quando**: `logging.structured.format.console: ecs`; cada linha sai como JSON com
    timestamp, nível, logger, thread, mensagem e campos do MDC; **nenhum `logstash-logback-encoder`**.
  - **Testes esperados**: T090 — inspeção de `make logs`.

- [X] T056 Implementar `MicrometerBalanceTelemetry` em `«main»/adapter/output/observability/MicrometerBalanceTelemetry.kt`
  - **Objetivo**: único lugar que conhece Micrometer, e onde log e contador de cada desfecho ficam lado a
    lado — garantindo que nenhum desfecho seja contado sem ser logado.
  - **Arquivos**: `«main»/adapter/output/observability/MicrometerBalanceTelemetry.kt`
  - **Depende de**: T007, T021, T027
  - **Requisitos**: FR-052, FR-053, FR-054, **FR-004a**, Constitution XIII
  - **Concluído quando**: implementa `BalanceTelemetry` com `balance.events.received`,
    `balance.events.outcome` (tags `outcome`, `reason`), `balance.event.processing` (Timer),
    **`balance.events.unsupported` (tag `reason`, FR-004a)**, `balance.persistence.failures` (tag
    `type`), `balance.retries`, `balance.dlq.published`, `balance.dlq.failures`,
    `balance.events.key.mismatch`, `balance.events.currency.changed`;
    **nenhuma métrica usa `accountId` ou `transactionId` como tag** — alta cardinalidade em Prometheus é
    um incidente esperando acontecer. A atribuição por conta e transação de FR-052 é feita pelos
    **logs** (MDC); as métricas fazem a **agregação**.
  - **Testes esperados**: T078.

- [X] T057 Popular e limpar o MDC no consumer em `«main»/adapter/input/kafka/TransactionEventConsumer.kt`
  - **Objetivo**: correlacionar os logs de ingestão. **MDC vazado entre mensagens em um pool de threads
    atribui eventos à conta errada** — pior que não ter log.
  - **Arquivos**: `«main»/adapter/input/kafka/TransactionEventConsumer.kt` (modificar)
  - **Depende de**: T043, T056
  - **Requisitos**: FR-052, FR-055, Constitution XIII
  - **Concluído quando**: MDC recebe `correlationId` (header `x-correlation-id` ou UUID gerado),
    `accountId`, `transactionId`, `kafkaTopic`, `kafkaPartition`, `kafkaOffset`; **sempre limpo em
    `finally`**; mensagem inválida ou de outro fluxo carrega apenas o que foi possível extrair; nenhum
    log inclui credencial ou payload completo em nível `INFO`.
  - **Testes esperados**: T069 verifica set e clear.

- [X] T058 [P] Expor os endpoints de gestão em `src/main/resources/application.yaml`
  - **Objetivo**: health e métricas acessíveis sem expor superfície desnecessária.
  - **Arquivos**: `src/main/resources/application.yaml`
  - **Depende de**: T007
  - **Requisitos**: FR-053, Constitution XIII, risco R-11
  - **Concluído quando**: `management.endpoints.web.exposure.include: health,info,prometheus` — **apenas
    esses três**; `/actuator/health` responde; `/actuator/prometheus` expõe
    `kafka.consumer.fetch.manager.records.lag.max` (consumer lag, ligado automaticamente pelo Boot com
    Micrometer no classpath — não escrevemos código para isso). **Sem health check customizado de
    DynamoDB**: um `DescribeTable` a cada probe adiciona carga e transforma throttle em pod reiniciado.
  - **Testes esperados**: T090 — checklist de métricas do quickstart Cenário 12.

**Checkpoint**: contexto Spring completo e bootável; todos os desfechos observáveis.

---

## Phase 10: Tests

**Propósito**: escrever a matriz comportamental transversal, os testes de adapter e os de integração.
Constitution XIV exige que os Princípios I, IV, V, VI, VIII, IX e X tenham **cada um** pelo menos um
teste que falha se a regra for violada.

### Domínio

- [X] T059 [P] [US3] Testar a matriz de elegibilidade em `«test»/domain/model/TransactionEventEligibilityTest.kt`
  - **Objetivo**: Constitution IX é o princípio mais fácil de violar sem perceber.
  - **Arquivos**: `«test»/domain/model/TransactionEventEligibilityTest.kt`
  - **Depende de**: T020
  - **Requisitos**: FR-008, **FR-008a**, FR-011, Constitution IX
  - **Concluído quando**: cobre a matriz 2×2 completa — `APPROVED`+`ENABLED` → `Eligible`;
    `DECLINED`+`ENABLED` → `Ineligible(TRANSACTION_NOT_APPROVED)`; `APPROVED`+`DISABLED` →
    `Ineligible(ACCOUNT_NOT_ENABLED)`; `DECLINED`+`DISABLED` → **ambas as razões**; e, por FR-008a,
    status **ausente** e status **desconhecido** (`"PENDING"`) produzem o mesmo resultado que o status
    explicitamente negativo.
  - **Testes esperados**: este é o teste.

- [X] T060 [P] [US2] Testar o snapshot literal em `«test»/domain/model/TransactionEventToBalanceTest.kt`
  - **Objetivo**: provar que o saldo **não** é derivado de aritmética (Constitution VIII).
  - **Arquivos**: `«test»/domain/model/TransactionEventToBalanceTest.kt`
  - **Depende de**: T020
  - **Requisitos**: FR-013, FR-014, US2 cenário 3
  - **Concluído quando**: evento `DEBIT` com `transaction.amount = 30.00` e
    `account.balance.amount = 70.00` produz `Balance` com **`70.00`** — o valor carregado, não o
    resultado de qualquer subtração; saldo negativo persistido normalmente (US2 cenário 4).
  - **Testes esperados**: este é o teste.

### Aplicação

- [X] T061 [US3] Testar que evento inelegível não chama o writer em `«test»/application/ProcessTransactionEventServiceTest.kt`
  - **Objetivo**: INV-003 — é assim que o marcador de frescor não avança e um evento válido posterior não
    é suprimido (FR-010).
  - **Arquivos**: `«test»/application/ProcessTransactionEventServiceTest.kt`
  - **Depende de**: T028
  - **Requisitos**: FR-009, FR-010, FR-012, INV-003
  - **Concluído quando**: com evento inelegível, `verify(balanceWriter, never()).save(any())`; o desfecho
    é `IgnoredIneligible` com as razões; a telemetria recebe o motivo.
  - **Testes esperados**: este é o teste.

- [X] T062 [US4] Testar o mapeamento de `BalanceWriteResult` → `ProcessingOutcome` em `«test»/application/ProcessTransactionEventServiceTest.kt`
  - **Objetivo**: cada rejeição do banco vira o desfecho correto e **confirmável** (FR-022).
  - **Arquivos**: `«test»/application/ProcessTransactionEventServiceTest.kt`
  - **Depende de**: T028
  - **Requisitos**: FR-019, FR-022, FR-023, FR-024
  - **Concluído quando**: `Applied` → `Applied`; `Duplicate` → `Rejected(DUPLICATE_EVENT)`; `Stale` →
    `Rejected(STALE_EVENT)`; `TimestampTie` → `Rejected(TIMESTAMP_TIE_REJECTED)`; exceção transitória do
    writer é **propagada**, não capturada.
  - **Testes esperados**: este é o teste.

- [X] T063 Testar a exaustividade da telemetria em `«test»/application/ProcessTransactionEventTelemetryTest.kt`
  - **Objetivo**: transformar FR-054 de aspiração em verificação.
  - **Arquivos**: `«test»/application/ProcessTransactionEventTelemetryTest.kt`
  - **Depende de**: T028
  - **Requisitos**: FR-052, FR-054, Constitution XIII
  - **Concluído quando**: **cada variante** de `ProcessingOutcome` produz exatamente uma chamada de
    `BalanceTelemetry.outcomeRecorded` — o teste enumera as variantes, de modo que acrescentar uma nova
    sem telemetria quebra a build.
  - **Testes esperados**: este é o teste.

- [X] T064 [P] [US1] Testar `GetBalanceService` em `«test»/application/GetBalanceServiceTest.kt`
  - **Objetivo**: garantir que conta desconhecida nunca vira `0.00`.
  - **Arquivos**: `«test»/application/GetBalanceServiceTest.kt`
  - **Depende de**: T029
  - **Requisitos**: FR-047, FR-051, Constitution I
  - **Concluído quando**: reader devolve `Balance` → retorna; reader devolve `null` → lança
    `BalanceNotFoundException`; nenhuma escrita ocorre.
  - **Testes esperados**: este é o teste.

### Adapter Kafka

- [X] T065 [P] [US2] Testar o mapeamento de evento válido em `«test»/adapter/input/kafka/mapper/TransactionEventMessageMapperTest.kt`
  - **Objetivo**: provar que a amostra real do desafio é traduzida corretamente.
  - **Arquivos**: `«test»/adapter/input/kafka/mapper/TransactionEventMessageMapperTest.kt`
  - **Depende de**: T042
  - **Requisitos**: FR-003, INV-006
  - **Concluído quando**: o payload de `contracts/transaction-event.json` produz `Interpreted` com o
    `TransactionEvent` esperado; `amount` chega como `BigDecimal` com escala 2 e **não passa por
    `Double`** em nenhum ponto.
  - **Testes esperados**: este é o teste.

- [X] T066 [P] [US6] Testar as mensagens inválidas em `«test»/adapter/input/kafka/mapper/InvalidMessageTest.kt`
  - **Objetivo**: cobrir cada Edge Case de mensagem inválida da spec.
  - **Arquivos**: `«test»/adapter/input/kafka/mapper/InvalidMessageTest.kt`
  - **Depende de**: T042
  - **Requisitos**: FR-004, FR-005, FR-037, Edge Cases
  - **Concluído quando**: JSON malformado; cada campo obrigatório ausente **um por vez**
    (`transaction.id`, `transaction.timestamp`, `account.id`, `account.balance`,
    `account.balance.amount`, `account.balance.currency`); `timestamp` não numérico; `amount` com 3 casas
    decimais; `accountId` fora do formato — **todos** lançam `UnprocessableEventException` com mensagem
    que **nomeia o campo**.
  - **Testes esperados**: este é o teste.

- [X] T067 [P] [US3] Testar que status ausente é inelegível, não inválido, em `«test»/adapter/input/kafka/mapper/MissingStatusTest.kt`
  - **Objetivo**: fixar em teste a assimetria que separa a linha 5 da linha 6 da matriz de decisão.
  - **Arquivos**: `«test»/adapter/input/kafka/mapper/MissingStatusTest.kt`
  - **Depende de**: T042
  - **Requisitos**: **FR-008a**, Edge Cases
  - **Concluído quando**: `transaction.status` ausente, `account.status` ausente, e valores desconhecidos
    (`"PENDING"`, `"WAT"`) **não lançam** — produzem `Interpreted` com evento inelegível.
  - **Testes esperados**: este é o teste.

- [X] T068 [P] [US6] Testar a triagem de FR-004a em `«test»/adapter/input/kafka/mapper/UnsupportedMessageTest.kt`
  - **Objetivo**: **o par de fronteira.** FR-004a se apoia numa distinção sutil — bloco `transaction`
    ausente por completo versus presente porém vazio — e os dois payloads são quase idênticos com
    destinos **opostos**. Sem este teste, um refactor descuidado manda evento real para o descarte
    silencioso (risco R-14).
  - **Arquivos**: `«test»/adapter/input/kafka/mapper/UnsupportedMessageTest.kt`
  - **Depende de**: T041, T042
  - **Requisitos**: **FR-004a**, FR-004, FR-035, INV-008, matriz linha 5a
  - **Concluído quando**:
    1. `{"account":{...}}` sem bloco `transaction` → `Unsupported(NO_TRANSACTION_BLOCK)` e **não lança**;
    2. `{"transaction":{},"account":{...}}` → **lança** `UnprocessableEventException`;
    3. `{"transaction":null,"account":{...}}` → **lança** `UnprocessableEventException`;
    4. um payload real de `make kafka-produce-accounts-events` → `Unsupported`.
    Os casos 1 e 2 devem estar no mesmo arquivo e adjacentes: é a leitura lado a lado que torna a regra
    óbvia para quem revisar.
  - **Testes esperados**: este é o teste.

- [X] T069 [P] Testar o consumer em `«test»/adapter/input/kafka/TransactionEventConsumerTest.kt`
  - **Objetivo**: garantir delegação limpa, ramo de descarte correto e MDC higiênico.
  - **Arquivos**: `«test»/adapter/input/kafka/TransactionEventConsumerTest.kt`
  - **Depende de**: T043, T057
  - **Requisitos**: **FR-004a**, FR-055, ADR-005
  - **Concluído quando**: com `Interpreted`, delega ao use case; com `Unsupported`, **não chama o use
    case**, chama `telemetry.unsupportedMessage` e **retorna normalmente** (sem lançar — lançar mandaria
    para a DLQ); popula o MDC e **o limpa mesmo quando o use case lança**; **não captura** a exceção do
    use case (verificar que ela sai do método).
  - **Testes esperados**: este é o teste.

- [X] T070 [P] [US6] Testar a configuração de retry e DLQ em `«test»/adapter/input/kafka/config/KafkaConsumerConfigTest.kt`
  - **Objetivo**: política de resiliência é configuração — e configuração errada só aparece em produção,
    a menos que seja testada.
  - **Arquivos**: `«test»/adapter/input/kafka/config/KafkaConsumerConfigTest.kt`
  - **Depende de**: T044, T045, T046
  - **Requisitos**: FR-034, FR-036, FR-037, FR-038, FR-039
  - **Concluído quando**: `UnprocessableEventException` classificada como **não-retryable**;
    `TransientProcessingException` como retryable; exceção desconhecida como não-retryable; o backoff usa
    os valores configurados e tem jitter; o resolver de DLQ devolve partição **`-1`**; a função de
    headers acrescenta `x-failure-reason` e `x-correlation-id`.
  - **Testes esperados**: este é o teste. *Se a lógica estiver embutida em classes `@Configuration`
    difíceis de instanciar, extrair para funções puras (risco R-06 — não relaxar o gate de cobertura).*

### Adapter DynamoDB

- [X] T071 [US4] Testar a expressão condicional em `«test»/adapter/output/dynamodb/DynamoDbBalanceWriterTest.kt`
  - **Objetivo**: a expressão é o núcleo de corretude — precisa ser verificada literalmente.
  - **Arquivos**: `«test»/adapter/output/dynamodb/DynamoDbBalanceWriterTest.kt`
  - **Depende de**: T033
  - **Requisitos**: FR-019, FR-021, FR-032, Constitution V
  - **Concluído quando**: o `PutItemRequest` capturado carrega
    `attribute_not_exists(#pk) OR #lastEventTimestamp < :incomingTimestamp`, os
    `expressionAttributeNames` para `#pk` e `#lastEventTimestamp`, `:incomingTimestamp` como `N`, e
    `ReturnValuesOnConditionCheckFailure = ALL_OLD`. **Verificar também que nenhum `GetItem` foi chamado
    antes do `PutItem`** (FR-028).
  - **Testes esperados**: este é o teste.

- [X] T072 [US4] Testar a classificação de rejeição em `«test»/adapter/output/dynamodb/ConditionalFailureClassificationTest.kt`
  - **Objetivo**: FR-023 exige distinguir três rejeições que o banco reporta como uma só exceção.
  - **Arquivos**: `«test»/adapter/output/dynamodb/ConditionalFailureClassificationTest.kt`
  - **Depende de**: T034
  - **Requisitos**: FR-023, INV-005, A-03
  - **Concluído quando**: `ConditionalCheckFailedException` cujo item tem `lastTransactionId` **igual** ao
    recebido → `Duplicate` (inclusive quando o timestamp também empata — a ordem dos testes importa);
    timestamp **igual** com transação diferente → `TimestampTie`; timestamp **maior** com transação
    diferente → `Stale`; transação já sobrescrita e reentregue → `Stale`.
  - **Testes esperados**: este é o teste.

- [X] T073 Testar a tradução de falhas do DynamoDB em `«test»/adapter/output/dynamodb/DynamoDbFailureTranslationTest.kt`
  - **Objetivo**: garantir que exceção da AWS **não vaza** para o adapter de entrada (Constitution III) e
    que a classificação transitório/permanente está correta.
  - **Arquivos**: `«test»/adapter/output/dynamodb/DynamoDbFailureTranslationTest.kt`
  - **Depende de**: T034
  - **Requisitos**: FR-036, FR-037, Constitution III e XI
  - **Concluído quando**: `ProvisionedThroughputExceededException`, `RequestLimitExceededException`,
    `InternalServerErrorException` e timeout do SDK → `TransientProcessingException`; nenhuma exceção
    `software.amazon.*` escapa do adapter.
  - **Testes esperados**: este é o teste.

- [X] T074 [P] [US1] Testar o reader em `«test»/adapter/output/dynamodb/DynamoDbBalanceReaderTest.kt`
  - **Objetivo**: garantir leitura consistente e ausência tratada como ausência.
  - **Arquivos**: `«test»/adapter/output/dynamodb/DynamoDbBalanceReaderTest.kt`
  - **Depende de**: T035
  - **Requisitos**: FR-033, FR-047, ADR-003
  - **Concluído quando**: o `GetItemRequest` capturado tem `consistentRead = true`; item ausente → `null`;
    `balanceAmount` textual reconstruído com escala 2.
  - **Testes esperados**: este é o teste.

- [X] T075 [P] Testar o round-trip do item em `«test»/adapter/output/dynamodb/BalanceItemMapperTest.kt`
  - **Objetivo**: provar que a escala sobrevive à persistência — a razão de `balanceAmount` ser `S`.
  - **Arquivos**: `«test»/adapter/output/dynamodb/BalanceItemMapperTest.kt`
  - **Depende de**: T031
  - **Requisitos**: FR-018, FR-044, INV-006, SC-009
  - **Concluído quando**: `Balance` → item → `Balance` preserva valor **e escala** (`150.00` continua
    `150.00`, não `150`); `pk` = `ACCOUNT#{id}`; `ownerId` omitido quando ausente; `lastEventTimestamp` é
    `N`; `balanceAmount` é `S`.
  - **Testes esperados**: este é o teste.

### Adapter Web

- [X] T076 [US1] Testar o endpoint com MockMvc em `«test»/adapter/input/web/BalanceControllerTest.kt`
  - **Objetivo**: cobrir os quatro status e a serialização do corpo.
  - **Arquivos**: `«test»/adapter/input/web/BalanceControllerTest.kt`
  - **Depende de**: T051, T052, T053
  - **Requisitos**: FR-042 a FR-050, US1 cenários 1–4
  - **Concluído quando**: `200` com corpo exato, **`amount` entre aspas** e o campo chamado
    **`updated_at`** (não `lastEventAt`); `404` com `code=BALANCE_NOT_FOUND`; `400` com
    `code=INVALID_ACCOUNT_ID` **verificando que o use case nunca foi chamado** (FR-048); `500` com corpo
    genérico **sem** `e.message`, nome de tabela ou stack trace (FR-049); header `X-Correlation-Id` na
    resposta.
  - **Testes esperados**: este é o teste.

- [X] T077 [P] [US1] Testar o mapper de resposta em `«test»/adapter/input/web/mapper/BalanceResponseMapperTest.kt`
  - **Objetivo**: fixar a formatação decimal e temporal, incluindo o truncamento.
  - **Arquivos**: `«test»/adapter/input/web/mapper/BalanceResponseMapperTest.kt`
  - **Depende de**: T050, T054
  - **Requisitos**: FR-044, FR-045, **FR-046**, ADR-010
  - **Concluído quando**: `150.00` → `"150.00"`; `-50.25` → `"-50.25"`; micros → ISO-8601 com **offset
    local** e **exatamente 3** dígitos fracionários; **truncamento verificado explicitamente**:
    `...589998` µs → `.589`, **nunca** `.590`; o fuso configurado é respeitado (testar com dois fusos
    diferentes para provar que não está fixo em código).
  - **Testes esperados**: este é o teste.

### Observabilidade e arquitetura

- [X] T078 [P] Testar a telemetria em `«test»/adapter/output/observability/MicrometerBalanceTelemetryTest.kt`
  - **Objetivo**: garantir contadores corretos e **cardinalidade segura**.
  - **Arquivos**: `«test»/adapter/output/observability/MicrometerBalanceTelemetryTest.kt`
  - **Depende de**: T056
  - **Requisitos**: FR-052, FR-053, **FR-004a**, risco R-11
  - **Concluído quando**: com `SimpleMeterRegistry`, cada desfecho incrementa o contador com as tags
    corretas, **incluindo `balance.events.unsupported` com tag `reason`**; **nenhuma métrica tem
    `accountId` ou `transactionId` como tag**.
  - **Testes esperados**: este é o teste.

- [X] T079 [P] Criar `MonetaryPrecisionArchitectureTest` em `«test»/MonetaryPrecisionArchitectureTest.kt`
  - **Objetivo**: transformar Constitution X de prosa em algo que quebra a build.
  - **Arquivos**: `«test»/MonetaryPrecisionArchitectureTest.kt`
  - **Depende de**: T006
  - **Requisitos**: FR-018, INV-006, Constitution X e XIV
  - **Concluído quando**: Konsist afirma que **nenhuma classe de produção** declara propriedade,
    parâmetro ou retorno `Float`/`Double`; verificado como efetivo introduzindo temporariamente um
    `Double` e confirmando a falha.
  - **Testes esperados**: este é o teste.

- [X] T080 [P] [US5] Criar o teste de ausência de locks em `«test»/NoLockingArchitectureTest.kt`
  - **Objetivo**: FR-027 proíbe `synchronized` e locks — hoje isso é apenas texto.
  - **Arquivos**: `«test»/NoLockingArchitectureTest.kt`
  - **Depende de**: T006
  - **Requisitos**: FR-027, FR-028, Constitution VI e XIV
  - **Concluído quando**: Konsist afirma que nenhuma classe de produção usa `synchronized` nem importa
    `java.util.concurrent.locks`.
  - **Testes esperados**: este é o teste.

### Integração — autoria

- [X] T081 [US4] Criar `DynamoDbBalanceIntegrationTest` em `«it»/adapter/output/dynamodb/DynamoDbBalanceIntegrationTest.kt`
  - **Objetivo**: Constitution XIV exige que duplicidade e fora de ordem sejam exercidos **no nível da
    persistência**, não apenas em memória.
  - **Arquivos**: `«it»/adapter/output/dynamodb/DynamoDbBalanceIntegrationTest.kt`
  - **Depende de**: T033, T034, T035, T037
  - **Requisitos**: FR-019 a FR-024, FR-032, FR-033, INV-001, INV-002, INV-004, INV-005
  - **Concluído quando**, contra DynamoDB Local real: primeiro evento escreve; timestamp **maior**
    substitui; **menor** rejeitado como `Stale`; **igual com transação diferente** rejeitado como
    `TimestampTie`; **igual com mesma transação** é `Duplicate`; `ALL_OLD` devolve o item; leitura
    consistente devolve o que acabou de ser escrito; escala 2 preservada no round-trip. Limpeza em
    `@AfterEach`, seguindo o padrão do starter.
  - **Testes esperados**: este é o teste.

- [X] T082 [US5] Criar `ConditionalWriteConcurrencyIntegrationTest` em `«it»/adapter/output/dynamodb/ConditionalWriteConcurrencyIntegrationTest.kt`
  - **Objetivo**: **o teste mais importante do conjunto.** Um teste que roda threads "ao mesmo tempo" sem
    barreira normalmente as executa em série e passa sem provar nada (risco R-08).
  - **Arquivos**: `«it»/adapter/output/dynamodb/ConditionalWriteConcurrencyIntegrationTest.kt`
  - **Depende de**: T081
  - **Requisitos**: FR-025, FR-026, FR-027, FR-028, SC-003, INV-004, Constitution VI
  - **Concluído quando**:
    1. **32 threads** com timestamps distintos embaralhados escrevem a mesma conta, liberadas por um
       `CountDownLatch` para maximizar a sobreposição real;
    2. estado final = snapshot de **maior** timestamp;
    3. **contabilidade completa**: sucessos + `ConditionalCheckFailedException` = 32 — nenhuma escrita
       desaparece silenciosamente;
    4. o cenário roda **N vezes (≥ 20)** com resultado idêntico — determinismo, não sorte de agendamento;
    5. fora de ordem estrito: aplica ts=300, depois tenta 100, 200, 299 — todas rejeitadas, marcador
       permanece 300;
    6. 16 threads com o **mesmo** evento: exatamente uma escreve, quinze recebem `Duplicate`, estado
       idêntico ao de uma execução única (INV-005).
  - **Testes esperados**: este é o teste.

- [X] T083 [US2] Criar `TransactionEventConsumerIntegrationTest` em `«it»/adapter/input/kafka/TransactionEventConsumerIntegrationTest.kt`
  - **Objetivo**: provar que o `@KafkaListener` **real** consome do broker **real** e persiste na tabela
    **real** — seguindo o padrão do `GreetingTemplateConsumerIntegrationTest` removido.
  - **Arquivos**: `«it»/adapter/input/kafka/TransactionEventConsumerIntegrationTest.kt`
  - **Depende de**: T043, T047, T048, T081
  - **Requisitos**: FR-001, FR-013, SC-005
  - **Concluído quando**: `@SpringBootTest` com o listener de produção; publica evento elegível no tópico
    real; o saldo aparece na tabela real dentro do timeout.
  - **Testes esperados**: este é o teste.

- [X] T084 [US6] Criar `TransactionEventDlqIntegrationTest` em `«it»/adapter/input/kafka/TransactionEventDlqIntegrationTest.kt`
  - **Objetivo**: verificar as linhas 5a e 6 da matriz — **o que vai e o que NÃO vai** para a DLQ.
  - **Arquivos**: `«it»/adapter/input/kafka/TransactionEventDlqIntegrationTest.kt`
  - **Depende de**: T046, T048, T083
  - **Requisitos**: FR-004a, FR-037, FR-038, FR-039, SC-008, INV-008,
    [contracts/dlq-message.md](./contracts/dlq-message.md)
  - **Concluído quando**:
    1. JSON malformado aparece na DLQ com o payload **original** e a chave preservados; headers
       `kafka_dlt-original-*`, `x-failure-reason=INVALID_MESSAGE` e `kafka_dlt-exception-message`
       nomeando o campo; **nenhum retry**; nenhum saldo alterado; o offset avança;
    2. **mensagem `{"account":{...}}` NÃO aparece na DLQ** (FR-004a), o offset avança mesmo assim, e a
       mensagem seguinte é processada normalmente. Este é o caso que prova que a DLQ continua sendo
       sinal de defeito, e não depósito de tudo que o consumer não entende.
  - **Testes esperados**: este é o teste.

- [X] T085 Criar `EndToEndBalanceFlowIntegrationTest` em `«it»/EndToEndBalanceFlowIntegrationTest.kt`
  - **Objetivo**: provar o fluxo completo — ingestão embaralhada → persistência → consulta REST.
  - **Arquivos**: `«it»/EndToEndBalanceFlowIntegrationTest.kt`
  - **Depende de**: T051, T083
  - **Requisitos**: SC-001, SC-002, SC-004, SC-005
  - **Concluído quando**: publica sequência embaralhada + duplicada + `DECLINED` + `DISABLED` + uma
    mensagem de outro fluxo para a mesma conta; `GET /balances/{id}` reflete o snapshot de **maior
    timestamp elegível**; o corpo obedece ao contrato de `balances-api.yaml`.
  - **Testes esperados**: este é o teste.

- [X] T086 [P] [US1] Validar a resposta contra `specs/001-core-banking-balance/contracts/balances-api.yaml`
  - **Objetivo**: o contrato REST mudou duas vezes nesta feature. Um teste que compara a resposta real
    com o arquivo de contrato impede que os dois divirjam silenciosamente de novo.
  - **Arquivos**: `«test»/adapter/input/web/BalanceResponseContractTest.kt`
  - **Depende de**: T076
  - **Requisitos**: FR-043 a FR-046, FR-050
  - **Concluído quando**: a resposta `200` casa com o schema `BalanceResponse` do OpenAPI — nomes de
    campo exatos (`accountId`, `balance.amount`, `balance.currency`, `lastTransactionId`,
    **`updated_at`**), `amount` casando `^-?\d+\.\d{2}$` como **string**, e nenhuma propriedade extra
    (`additionalProperties: false`); as respostas de erro casam com `ErrorResponse`.
  - **Testes esperados**: este é o teste.

**Checkpoint**: Constitution XIV satisfeita — cada princípio crítico tem teste que falha se violado.

---

## Phase 11: Integration

- [X] T087 Validar DynamoDB Local e o schema da tabela via `infra/dynamodb/seed.sh`
  - **Objetivo**: confirmar que a tabela nasce com a chave correta.
  - **Arquivos**: verificação — `docker-compose.yml`, `infra/dynamodb/seed.sh`
  - **Depende de**: T037
  - **Requisitos**: FR-029, FR-030
  - **Concluído quando**: `make db-up` cria `AccountBalances`; `aws dynamodb describe-table` mostra
    `pk` (S) como `HASH` e nenhum índice secundário; `make db-scan` funciona; o console em
    `localhost:8001` lista a tabela; **rodar duas vezes é idempotente**.
  - **Testes esperados**: verificação manual + T081 verde.

- [X] T088 Validar Redpanda e os tópicos via `infra/redpanda/seed.sh`
  - **Objetivo**: confirmar criação explícita (auto-criação está desabilitada) e o particionamento por
    conta.
  - **Arquivos**: verificação — `docker-compose.yml`, `infra/redpanda/seed.sh`,
    `infra/redpanda/produce-transactions-events.sh`
  - **Depende de**: T048
  - **Requisitos**: FR-006, FR-038, ADR-002
  - **Concluído quando**: `make kafka-up` cria `transactions-events` e `transactions-events.dlq` com **3
    partições** cada; `rpk topic describe` confirma; `make kafka-produce-transactions-events` publica
    **com chave**; mensagens da mesma conta caem na mesma partição; **`make kafka-produce-accounts-events`
    continua funcionando** (é a fonte das mensagens de outro fluxo); **rodar duas vezes é idempotente**.
  - **Testes esperados**: verificação manual + T083 verde.

- [X] T089 Executar a suíte de integração completa com `make integration-test`
  - **Objetivo**: validar todos os adapters contra infraestrutura real de uma vez.
  - **Arquivos**: verificação — source set `src/integrationTest`
  - **Depende de**: T081, T082, T083, T084, T085, T087, T088
  - **Requisitos**: Constitution — Implementation gate; Constitution XIV
  - **Concluído quando**: `make integration-test` verde do zero (`make clean-containers` antes), sem
    testes ignorados e sem flakiness em **3 execuções consecutivas** — concorrência é o tipo de teste que
    passa por acidente.
  - **Testes esperados**: os cinco testes de integração.

- [X] T090 Validar a stack completa via `docker-compose.yml` e os cenários 1–9 do quickstart
  - **Objetivo**: provar que a aplicação containerizada funciona com a rede do compose, não só a partir
    do IDE.
  - **Arquivos**: verificação — `docker-compose.yml`, `Dockerfile`, [quickstart.md](./quickstart.md)
  - **Depende de**: T089
  - **Requisitos**: SC-001, SC-002, SC-005, SC-008, SC-009, FR-004a, FR-042 a FR-050
  - **Concluído quando**: `make up` sobe tudo; os cenários 1 a 9 do quickstart passam — incluindo o
    **Cenário 8b** (mensagens de outro fluxo não vão para a DLQ) e o teste de **truncamento** do
    `updated_at` no Cenário 1; o teste decisivo do marcador no Cenário 6 (evento elegível com ts
    intermediário é aplicado depois de um inelegível com ts maior).
  - **Testes esperados**: quickstart Cenários 1–9 e 8b.

- [X] T091 [US5] Validar múltiplas instâncias e reinício abrupto via `docker compose up --scale app=3`
  - **Objetivo**: provar que escalar horizontalmente não muda nada na estratégia de consistência
    (Constitution VI) e que crash não corrompe saldo.
  - **Arquivos**: verificação — `docker-compose.yml`
  - **Depende de**: T090
  - **Requisitos**: FR-025, FR-041, SC-003, SC-004, SC-010, INV-005
  - **Concluído quando**: com 3 réplicas e 1.000 eventos produzidos, nenhum saldo regride e a soma
    `applied + ignored_ineligible + duplicate + stale + timestamp_tie + unsupported + dlq` **é igual ao
    total de eventos recebidos** (SC-004 — nenhum evento desaparece); `docker compose kill app` no meio
    do processamento, seguido de restart, **não altera nenhum saldo** (SC-010) e os eventos reentregues
    são classificados como `duplicate`/`stale`. **Nenhuma configuração precisou mudar para escalar.**
  - **Testes esperados**: quickstart Cenários 10 e 11.

**Checkpoint**: sistema validado contra infraestrutura real, em múltiplas instâncias.

---

## Phase 12: Quality

- [X] T092 Executar o gate completo com `./gradlew check`
  - **Objetivo**: gate obrigatório do *Implementation gate* da constitution.
  - **Arquivos**: verificação — `build.gradle.kts`
  - **Depende de**: T059 a T086
  - **Requisitos**: Constitution — Implementation gate; Constitution XIV
  - **Concluído quando**: testes unitários, testes Konsist e `jacocoTestCoverageVerification` verdes;
    cobertura de instruções **≥ 90%**; `coverageMinimum` **não** relaxado; `jacocoCoverageExclusions`
    contendo apenas `Application`/`ApplicationKt`.
  - **Testes esperados**: toda a suíte unitária.

- [X] T093 Confirmar a efetividade dos testes de arquitetura em `«test»/`
  - **Objetivo**: um teste de arquitetura que passa sobre um conjunto vazio é pior que nenhum — foi
    exatamente o defeito corrigido em T006. Verificar que a correção é real.
  - **Arquivos**: `«test»/HexagonalArchitectureTest.kt`, `«test»/MonetaryPrecisionArchitectureTest.kt`,
    `«test»/NoLockingArchitectureTest.kt`
  - **Depende de**: T006, T079, T080, T092
  - **Requisitos**: Constitution II, III, VI, X, XIV
  - **Concluído quando**: cada um dos três testes é verificado com uma violação temporária que **deve**
    quebrá-lo (import de Spring no domínio; `Double` em produção; `synchronized`), e a violação é
    revertida em seguida. Confirmar que o escopo Konsist encontra classes (contagem > 0).
  - **Testes esperados**: os três testes de arquitetura, comprovadamente efetivos.

- [X] T094 Executar a build containerizada com `make test` e `make build`
  - **Objetivo**: garantir que a build funciona fora do ambiente local do desenvolvedor.
  - **Arquivos**: verificação — `Dockerfile`, `Makefile`
  - **Depende de**: T092
  - **Requisitos**: Constitution — Implementation gate
  - **Concluído quando**: `make test` (estágio `test`, que roda `./gradlew check`) verde; `make build`
    produz a imagem `runtime`; o multi-stage do `Dockerfile` **não foi alterado**.
  - **Testes esperados**: build Docker completa.

- [X] T095 [P] Auditar análise estática e formatação em `build.gradle.kts`
  - **Objetivo**: verificar o que **existe** antes de adicionar qualquer coisa.
  - **Arquivos**: `build.gradle.kts`, `.github/`, `.editorconfig`
  - **Depende de**: T092
  - **Requisitos**: Constitution XV
  - **Concluído quando**: está documentado que o projeto **não** possui ktlint, detekt nem spotless
    configurados, e que Konsist + JaCoCo são os gates existentes. **Não adicionar ferramenta nova nesta
    feature** — seria complexidade sem requisito. Registrar como recomendação separada se desejado.
  - **Testes esperados**: nenhum — é auditoria.

- [X] T096 [P] Criar os 7 ADRs novos em `docs/adr/`
  - **Objetivo**: o *Development Workflow* da constitution exige que decisões com consequências
    duradouras sejam registradas como ADR, referenciando os princípios que implementam.
  - **Arquivos**: `docs/adr/004-conditional-put-with-return-values-on-failure.md`,
    `docs/adr/005-single-retry-authority.md`,
    `docs/adr/006-decimal-string-money-representation.md`,
    `docs/adr/007-single-deployable-with-profile-split.md`,
    `docs/adr/008-account-partition-key-design.md`,
    `docs/adr/009-three-way-message-triage.md`,
    `docs/adr/010-response-instant-rendering.md`
  - **Depende de**: T033, T045, T031, T041, T050, T056
  - **Requisitos**: Constitution — Architectural decisions;
    [plan.md §17](./plan.md#17-architectural-decisions)
  - **Concluído quando**: cada ADR tem Status, Context, Decision, Rationale, Consequences e
    Alternatives, e referencia explicitamente os princípios da constitution que implementa. **009** e
    **010** documentam as decisões que saíram da sessão de clarificações de 2026-09-06.
  - **Testes esperados**: nenhum — é documentação.

- [X] T097 [P] Atualizar os 3 ADRs existentes em `docs/adr/`
  - **Objetivo**: a ADR-001 contém uma expressão que **falha em runtime** — deixá-la como "Accepted"
    induz ao erro na próxima leitura.
  - **Arquivos**: `docs/adr/001-conditional-write-for-idempotency.md`,
    `docs/adr/002-account-id-as-kafka-message-key.md`,
    `docs/adr/003-strong-consistency-for-balance-reads.md`
  - **Depende de**: T096
  - **Requisitos**: Constitution VII; [plan.md §17](./plan.md#17-architectural-decisions)
  - **Concluído quando**: ADR-001 marcada **Superseded by ADR-004**, com nota sobre `timestamp` ser
    palavra reservada e sobre `ReturnValuesOnConditionCheckFailure`; ADR-002 com contexto atualizado e
    reforço de que a chave é **otimização de contenção, nunca argumento de corretude**; ADR-003 com
    contexto atualizado.
  - **Testes esperados**: nenhum — é documentação.

- [X] T098 [P] Atualizar `README.md` e criar `http/balances.http`
  - **Objetivo**: a documentação do repositório ainda descreve o fluxo `hello`.
  - **Arquivos**: `README.md`, `http/balances.http`
  - **Depende de**: T090
  - **Requisitos**: FR-042; Constitution — Local infrastructure
  - **Concluído quando**: o README descreve o fluxo de saldo (diagrama, variáveis de ambiente **incluindo
    `BALANCE_API_TIMEZONE`**, alvos do Makefile, tópicos, tabela); nenhuma referência a
    `greeting`/`hello` resta; `http/balances.http` exercita os quatro status e funciona com `make http`.
  - **Testes esperados**: `make http` executa contra a aplicação rodando.

- [X] T099 Revisar a conformidade constitucional dos 15 princípios contra `.specify/memory/constitution.md`
  - **Objetivo**: o *Review gate* exige verificação explícita — "uma violação bloqueia a mudança; não é
    ticket de follow-up".
  - **Arquivos**: verificação de todo o repositório
  - **Depende de**: T092, T093, T094, T096, T097, T098
  - **Requisitos**: Constitution — Review gate, todos os 15 princípios
  - **Concluído quando**: cada princípio tem evidência apontada (teste, arquivo ou ADR). Verificar em
    particular os que são fáceis de satisfazer só no papel:
    **V** — a comparação de frescor está na `ConditionExpression`, não em memória;
    **VI** — nenhum `synchronized`/lock (T080) e nenhuma sequência ler-depois-escrever;
    **VIII** — `Money` não tem aritmética e `Balance` não aceita saldo anterior;
    **X** — `amount` é texto em toda fronteira, inclusive na resposta REST
    *(revisto em 2026-09-07 por [T112](#phase-13-correção-do-contrato-revisão-de-2026-09-07): decimal
    exato em toda fronteira — texto na persistência, número JSON com escala 2 na resposta)*;
    **XII** — nenhum caminho confirma offset sem desfecho terminal;
    **XIII** — cada ramo "não faz nada" tem telemetria (T063, T078), **incluindo o descarte de FR-004a**.
  - **Testes esperados**: a suíte completa como evidência.

- [X] T100 Executar o quickstart completo conforme `specs/001-core-banking-balance/quickstart.md`
  - **Objetivo**: validação final ponta a ponta contra os 10 Success Criteria.
  - **Arquivos**: verificação — [quickstart.md](./quickstart.md)
  - **Depende de**: T099
  - **Requisitos**: SC-001 a SC-010
  - **Concluído quando**: os 13 cenários passam a partir de um ambiente limpo (`make clean-containers`
    antes); o checklist final de Success Criteria está inteiro marcado; o checklist de métricas do
    Cenário 12 está inteiro marcado, **incluindo `balance_events_unsupported_total`**.
  - **Testes esperados**: quickstart completo.

**Checkpoint**: feature completa, verificada e documentada.

---

## Phase 13: Correção do contrato (revisão de 2026-09-07)

**Propósito**: alinhar o código às três clarificações de 2026-09-07, já absorvidas por
[spec.md](./spec.md#clarifications), [plan.md](./plan.md), [research.md](./research.md),
[data-model.md](./data-model.md) e pelos dois contratos. **Nenhuma linha de `src/` foi alterada por
aquela sessão** — as Phases 1 a 12 continuam verdes porque validam o contrato antigo.

> **A suíte verde deixou de ser sinal de conformidade.** `./gradlew check` passa hoje porque o código e
> os testes concordam entre si, e ambos contradizem os contratos publicados.
> `BalanceResponseContractTest` lê `balances-api.yaml` do disco, e esse arquivo já mudou: **é o primeiro
> teste que falha** quando esta fase começa. Outros seis arquivos de teste codificam a decisão anterior
> e **não falham por acidente** — precisam ser invertidos deliberadamente (T107, T108, T109).

**Ordem**: domínio antes da borda. Tornar `ownerId` não-anulável (T101) quebra a compilação em cascata e
expõe de uma vez todos os pontos que assumiam o contrário — mais barato do que descobri-los um a um
partindo da borda.

**Sobre a métrica** (pergunta levantada na sessão de clarificação): um evento sem titular percorre o
caminho **já existente** de mensagem inválida e portanto já é contado por `balance.dlq.published`
(`ANOMALY_DLQ_PUBLISHED`), com o motivo no header `x-failure-reason = INVALID_MESSAGE` e no log
estruturado. **Nenhuma métrica nova é criada**: uma métrica dedicada a este motivo seria funcionalidade
não especificada, e o motivo já é observável onde a cardinalidade não custa.

- [X] T101 [US2] Tornar `ownerId` obrigatório no domínio em `«main»/domain/model/`
  - **Objetivo**: FR-003a afirma que toda conta tem titular. Enquanto o tipo for `OwnerId?`, a regra
    depende de disciplina em cada borda; com `OwnerId`, o compilador a aplica.
  - **Arquivos**: `«main»/domain/model/Account.kt`, `«main»/domain/model/Balance.kt`,
    `«main»/domain/model/OwnerId.kt`
  - **Depende de**: —
  - **Requisitos**: FR-003a, INV-006, Constitution I
  - **Concluído quando**: `Account.ownerId: OwnerId` e `Balance.ownerId: OwnerId`, sem `?`;
    `TransactionEvent.toBalance()` continua compilando sem alteração (já repassa `account.ownerId`); a
    mensagem de `OwnerId` deixa de dizer *"when present"* e passa a `"account.owner must not be blank"`.
    **A build quebra nos pontos que assumiam anulabilidade** — T102, T103, T106, T107. Essa lista é o
    resultado esperado da task, não um efeito colateral dela.
  - **Testes esperados**: T107.

- [X] T102 [US2] Exigir `account.owner` em `«main»/adapter/input/kafka/mapper/TransactionEventMessageMapper.kt`
  - **Objetivo**: transformar a ausência de titular em mensagem inválida — DLQ, sem retry — e nomeá-la
    na ordem em que um operador lê o payload.
  - **Arquivos**: `«main»/adapter/input/kafka/mapper/TransactionEventMessageMapper.kt`
  - **Depende de**: T101
  - **Requisitos**: FR-003a, FR-004, matriz linha 6
  - **Concluído quando**: `ownerId = OwnerId(required(account.owner, "account.owner", payload))`,
    posicionado **entre** `account.id` e `account.balance` — a DLQ carrega apenas a primeira falha, e a
    ordem decide qual campo o operador vê primeiro. Valor em branco também é recusado: `OwnerId` lança
    `InvalidTransactionEventException`, já convertida em `UnprocessableEventException` pelo `catch`
    existente. **`TransactionEventMessage.AccountMessage.owner` permanece `String? = null`**: o DTO
    precisa poder representar a ausência para que o mapper a recuse com o nome do campo; torná-lo
    não-anulável moveria a falha para o binding e perderia essa informação.
  - **Testes esperados**: T107.

- [X] T103 [US2] Persistir e ler `ownerId` como obrigatório em `«main»/adapter/output/dynamodb/BalanceItemMapper.kt`
  - **Objetivo**: o atributo deixa de ser condicional na escrita e opcional na leitura.
  - **Arquivos**: `«main»/adapter/output/dynamodb/BalanceItemMapper.kt`
  - **Depende de**: T101
  - **Requisitos**: FR-003a, FR-043a, INV-006
  - **Concluído quando**: `toItem` escreve `ownerId` incondicionalmente, sem o `let`; `toBalance` usa
    `OwnerId(required(item, BalanceTableAttributes.OWNER_ID))` — o mesmo helper dos demais atributos
    obrigatórios, que lança `IllegalStateException` nomeando o atributo. Um item sem titular passa a ser
    **item corrompido**, não item legítimo: nenhum caminho desta feature consegue gravá-lo, e inventar
    um titular ausente afirmaria um fato que o sistema não conhece (Constitution I). O comentário
    *"omitted rather than written as NULL"* sai junto — ele documenta uma decisão revogada.
    `lastTransactionId` **continua sendo persistido** (FR-043a): é o operando que distingue reentrega de
    conflito na escrita condicional.
  - **Testes esperados**: T107.

- [X] T104 [P] [US1] Reescrever `BalanceResponse` em `«main»/adapter/input/web/dto/BalanceResponse.kt`
  - **Objetivo**: fechar o corpo nas quatro chaves do contrato. `additionalProperties: false` significa
    que uma chave a mais não é ruído inofensivo — faz um cliente estrito rejeitar a resposta inteira.
  - **Arquivos**: `«main»/adapter/input/web/dto/BalanceResponse.kt`
  - **Depende de**: —
  - **Requisitos**: FR-043, FR-043a, FR-044, INV-006, R19
  - **Concluído quando**: `BalanceResponse(id: String, owner: String, balance: BalanceAmountResponse,
    @get:JsonProperty("updated_at") updatedAt: String)` e `BalanceAmountResponse(amount: BigDecimal,
    currency: String)`. `accountId` → `id`; `owner` acrescentado; `lastTransactionId` **removido**
    (FR-043a — continua persistido e nos logs); `amount` de `String` para `BigDecimal`. O KDoc que hoje
    defende o texto (*"`amount` is a `String`, and that is the point"*) é **substituído**: passa a
    registrar que a escala sobrevive à serialização — `BigDecimal("150.00")` sai como `150.00`, não
    `150` — e que a garantia do serviço termina na saída. Nenhum `Double` em ponto algum.
  - **Testes esperados**: T108.

- [X] T105 [US1] Ajustar `BalanceResponseMapper` em `«main»/adapter/input/web/mapper/BalanceResponseMapper.kt`
  - **Objetivo**: mapear os campos novos e parar de converter o saldo para texto.
  - **Arquivos**: `«main»/adapter/input/web/mapper/BalanceResponseMapper.kt`
  - **Depende de**: T101, T104
  - **Requisitos**: FR-043, FR-044, FR-045, FR-046, ADR-010
  - **Concluído quando**: `id` ← `balance.accountId.value`; `owner` ← `balance.ownerId.value` (não-nulo
    por T101); `amount` ← `balance.money.amount`, **sem `toPlainString()`**; `currency` inalterado;
    `updated_at` **inalterado** — o truncamento para milissegundos e o fuso configurável não fazem parte
    desta revisão (FR-046, ADR-010). `Money.toPlainString()` continua existindo e continua sendo usado
    pela persistência, onde o texto ainda é a representação correta (`S` preserva a escala; `N` não).
  - **Testes esperados**: T108.

- [X] T106 Atualizar as fixtures em `«test»/support/TestFixtures.kt`
  - **Objetivo**: enquanto o parâmetro for `String?`, os testes revertidos continuam conseguindo
    construir o estado que a spec passou a proibir.
  - **Arquivos**: `«test»/support/TestFixtures.kt`
  - **Depende de**: T101
  - **Requisitos**: FR-003a
  - **Concluído quando**: `account(ownerId: String = OWNER_ID)` e `balance(ownerId: String = OWNER_ID)`
    — sem `?` e sem `?.let`. Quem precisar do caso "sem titular" (T107) o constrói como **payload JSON**
    sem o campo, que é a única fronteira onde a ausência ainda é representável; nenhuma fixture de
    domínio consegue mais expressá-la.
  - **Testes esperados**: nenhum próprio; a evidência é T107 e T108 compilando.

- [X] T107 [US2] Inverter os testes de ingestão em `«test»/domain/model/` e `«test»/adapter/`
  - **Objetivo**: quatro arquivos afirmam hoje o contrário da decisão de 2026-09-07. Como afirmam a
    ausência de titular como comportamento **correto**, nenhum deles falha por acidente.
  - **Arquivos**: `«test»/domain/model/TransactionEventToBalanceTest.kt`,
    `«test»/adapter/output/dynamodb/BalanceItemMapperTest.kt`,
    `«test»/adapter/input/kafka/mapper/TransactionEventMessageMapperTest.kt`,
    `«test»/domain/model/ValueObjectInvariantsTest.kt`
  - **Depende de**: T101, T102, T103, T106
  - **Requisitos**: FR-003a, FR-004
  - **Concluído quando**:
    1. `TransactionEventToBalanceTest`: o caso *"an absent owner is carried through as absent rather
       than invented"* é **removido** — a situação que ele descreve deixou de ser representável; no
       lugar, um caso verifica que o titular é propagado do evento para o `Balance`;
    2. `BalanceItemMapperTest`: *"an absent owner is omitted rather than written as null"* vira *"the
       owner is always written"*, e um caso novo verifica que `toBalance` de um item sem `ownerId`
       lança `IllegalStateException` nomeando o atributo;
    3. `TransactionEventMessageMapperTest`: caso novo — payload sem `account.owner` resulta em
       `UnprocessableEventException` cuja mensagem nomeia **`account.owner`**; e `"owner": ""` também é
       recusado. Um deles fica **adjacente ao caso feliz**, para que a diferença de um único campo entre
       "aplicado" e "DLQ" seja legível lado a lado (mesmo motivo de T068);
    4. `ValueObjectInvariantsTest`: mantido como está; apenas o nome do caso muda se citar
       *"when present"*.
  - **Testes esperados**: são eles.

- [X] T108 [US1] Inverter os testes de resposta em `«test»/adapter/input/web/`
  - **Objetivo**: os três testes de resposta afirmam a forma antiga do corpo. `BalanceResponseContractTest`
    é o único que falha sozinho, porque lê o contrato do disco — os outros dois passariam indefinidamente.
  - **Arquivos**: `«test»/adapter/input/web/BalanceControllerTest.kt`,
    `«test»/adapter/input/web/mapper/BalanceResponseMapperTest.kt`,
    `«test»/adapter/input/web/BalanceResponseContractTest.kt`
  - **Depende de**: T104, T105, T106
  - **Requisitos**: FR-043, FR-043a, FR-044, INV-006
  - **Concluído quando**:
    1. as chaves esperadas passam a ser exatamente `id`, `owner`, `balance`, `updated_at`, e um caso
       afirma que `accountId` e `lastTransactionId` **não** aparecem;
    2. o caso que hoje exige `amount` **entre aspas** passa a exigir número JSON — verificado no corpo
       **bruto** (`"amount":183.12`), não no mapa desserializado, que já perdeu a distinção;
    3. **a escala é verificada explicitamente**: um saldo de `150.00` serializa como `150.00`, não
       `150`. É a única propriedade que separa esta decisão de uma violação de Constitution X, e é
       exatamente a que quebraria em silêncio se alguém trocasse `BigDecimal` por `Double`;
    4. `BalanceResponseContractTest` continua lendo `balances-api.yaml` do disco. **Não copiar o
       contrato para dentro do teste** — o valor dele está em falhar quando os dois divergem.
  - **Testes esperados**: são eles.

- [X] T109 [US2] Cobrir o evento sem titular nos testes de integração em `«it»/`
  - **Objetivo**: provar ponta a ponta o custo aceito de FR-003a — um evento com saldo válido que
    **não** atualiza o saldo.
  - **Arquivos**: `«it»/adapter/input/kafka/TransactionEventDlqIntegrationTest.kt`,
    `«it»/EndToEndBalanceFlowIntegrationTest.kt`
  - **Depende de**: T102, T105
  - **Requisitos**: FR-003a, FR-004, SC-008
  - **Concluído quando**: caso novo no teste de DLQ — payload válido em tudo **exceto** `account.owner`
    → registro na DLQ com `x-failure-reason = INVALID_MESSAGE` e `kafka_dlt-exception-message` nomeando
    `account.owner`, **e o saldo daquela conta permanece inexistente**. A segunda asserção é a que prova
    a regra: sem ela o teste só prova que a DLQ recebeu alguma coisa. `EndToEndBalanceFlowIntegrationTest`
    passa a afirmar o corpo novo. Requer infraestrutura viva (`make db-up`); corresponde ao **Cenário 8c**
    do quickstart.
  - **Testes esperados**: são eles.

- [X] T110 [P] Emendar a ADR-006 em `docs/adr/006-decimal-string-money-representation.md`
  - **Objetivo**: o título afirma *money as decimal text at every boundary*, e a fronteira REST deixou
    de ser texto. É a ADR que alguém lerá para entender por que o dinheiro é tratado assim.
  - **Arquivos**: `docs/adr/006-decimal-string-money-representation.md`
  - **Depende de**: T104
  - **Requisitos**: FR-044, R19, Constitution X
  - **Concluído quando**: seção de atualização datada de 2026-09-07 que (a) **preserva** a decisão
    original para domínio, ingestão e persistência, onde ela continua valendo; (b) registra que a
    fronteira REST passou a número JSON; (c) registra qual argumento mudou — o antigo descrevia o
    parser do cliente, não a saída deste serviço; (d) registra a verificação empírica de que Jackson
    emite `BigDecimal("150.00")` como `150.00`. **Emendar, não reescrever**: uma ADR que apaga o
    raciocínio anterior perde a razão de existir.
  - **Testes esperados**: revisão manual.

- [X] T111 [P] Atualizar os exemplos em `README.md` e `http/balances.http`
  - **Objetivo**: são as duas cópias do corpo da resposta que vivem fora dos contratos; deixá-las para
    trás cria duas fontes de verdade divergentes.
  - **Arquivos**: `README.md`, `http/balances.http`
  - **Depende de**: T104
  - **Requisitos**: FR-043, FR-044
  - **Concluído quando**: o corpo de exemplo do README (hoje `accountId`, `"183.12"` entre aspas,
    `lastTransactionId`) passa às quatro chaves com `183.12` sem aspas; em `balances.http` a asserção
    `typeof response.body.balance.amount === "string"` passa a `"number"`, e a verificação das duas
    casas decimais passa a ser feita sobre o **texto bruto** da resposta — depois do parse do cliente
    HTTP a escala já não existe. **Não deixar a asserção antiga comentada ao lado da nova.**
  - **Testes esperados**: execução manual via `make http`.

- [X] T112 Verificação final da correção conforme `specs/001-core-banking-balance/quickstart.md`
  - **Objetivo**: fechar a fase com evidência, não com compilação. Vale aqui a mesma regra do resto do
    plano: uma task não está pronta porque o código compila.
  - **Arquivos**: verificação — nenhum arquivo novo
  - **Depende de**: T101 a T111
  - **Requisitos**: FR-003a, FR-043, FR-043a, FR-044, SC-008, Constitution I, X, XIV
  - **Concluído quando**:
    1. `./gradlew check` verde, cobertura de instruções ainda **≥ 90%**;
    2. `./gradlew integrationTest` verde com infraestrutura viva;
    3. `make build` e `make test` verdes no contêiner — o `COPY` dos contratos no Dockerfile garante
       que T108 realmente roda lá dentro, em vez de ser silenciosamente pulado;
    4. no stack completo, `curl … | jq 'keys'` devolve **exatamente** `["balance","id","owner","updated_at"]`,
       e o corpo bruto traz `"amount":183.12` sem aspas;
    5. **Cenário 8c** do quickstart executado: conta sem titular → `404` e registro na DLQ;
    6. a afirmação do **T099** sobre Constitution X é reescrita para: `amount` é decimal exato em toda
       fronteira — **texto** na persistência, **número JSON com escala 2** na resposta. T099 continua
       marcado: ele registra o que foi verificado em 2026-09-06. Esta é a verificação de 2026-09-07.
  - **Testes esperados**: a suíte completa mais o quickstart.

**Checkpoint**: código e artefatos de design voltam a concordar, e a suíte verde volta a ser sinal.

---

## O que mudou nesta revisão

### Sessão de 2026-09-06 — geração original das T001–T100

| Mudança | Impacto |
|---|---|
| **Phase 0 removida** | T001/T002 da versão anterior (bloqueadores de spec) estão resolvidos; a numeração recuou em 2 |
| **FR-004a — comportamento novo** | **T041** (`MessageInterpretation` + `UnsupportedReason`), triagem em T042, ramo em T043, método na porta em T027, métrica em T056, **T068** (par de fronteira), T084 caso 2, T091 na contabilidade |
| **`updated_at` substitui `lastEventAt`** | T049 (DTO), T050 (truncamento + fuso), **T054** (`BALANCE_API_TIMEZONE`, nova), T076, T077, **T086** (conformidade com o OpenAPI, nova) |
| **FR-008a normativa** | T015, T020, T059 e T067 deixam de depender de uma leitura assumida |
| **A-03 e A-02 decididas** | T033 e T072 (empate rejeita, sem desempate); T014 (microssegundos confirmados) |
| **ADRs 009 e 010** | T096 passa de 5 para 7 ADRs |

Total: **98 → 100 tasks**.

### Sessão de 2026-09-07 — Phase 13 acrescentada (T101–T112)

Três decisões daquela sessão revogam decisões de 2026-09-06 e por isso a correção é uma fase nova, não
uma edição das tasks antigas.

| Mudança | Impacto |
|---|---|
| **FR-044 revisada** — saldo como **número JSON**, não texto | **T104** (DTO), **T105** (mapper), **T108** (o teste que exigia aspas passa a exigir número e a fixar a escala), **T110** (ADR-006 emendada), **T111** (README e `.http`). Supera T049 e o item 1 de T050 |
| **FR-043 revisada + FR-043a nova** — corpo fechado em quatro chaves; `lastTransactionId` persistido mas não exposto | **T104**, **T108**, **T111**; `balances-api.yaml` com `additionalProperties: false` |
| **FR-003a nova** — `account.owner` estruturalmente obrigatório | **T101** (domínio não-anulável), **T102** (mapper Kafka), **T103** (item DynamoDB), **T106** (fixtures), **T107** (quatro testes de ingestão invertidos), **T109** (DLQ ponta a ponta) |
| **Sete testes codificam a decisão antiga** | T107, T108 e T109 os invertem deliberadamente. Só `BalanceResponseContractTest` falha sozinho — os outros seis passariam indefinidamente |
| **Nenhuma métrica nova** | O evento sem titular já é contado por `balance.dlq.published`; criar uma métrica dedicada seria funcionalidade não especificada |

Total: **100 → 112 tasks**.

---

## Dependências e ordem de execução

### Dependências entre fases

```
Phase 1 (cleanup)          → sem dependências
Phase 2 (domain)           → Phase 1 (T006 precisa existir para guardar o código novo)
Phase 3 (input ports)      → Phase 2
Phase 4 (output ports)     → Phase 2
Phase 5 (application)      → Phases 3 e 4
Phase 6 (dynamodb)         → Phase 4  (pode correr em paralelo com Phase 5)
Phase 7 (kafka)            → Phases 3 e 5
Phase 8 (rest)             → Phases 3 e 5
Phase 9 (observability)    → Phases 5, 6, 7  (T007 já feito na Phase 1)
Phase 10 (tests)           → a fase que cada teste cobre
Phase 11 (integration)     → Phases 6, 7, 8, 10
Phase 12 (quality)         → Phase 11
Phase 13 (correção)        → Phases 8, 10, 11  (revisão de 2026-09-07; não bloqueia nem é bloqueada
                             pelas Phases 1 a 12, que já estão concluídas)
```

**Dentro da Phase 13** a ordem é domínio → borda:

```
T101 → T102, T103, T106 → T107
T101, T104 → T105 → T108
T102, T105 → T109
T104 → T110, T111
tudo → T112
```

### Caminho crítico

```
T006 → T013 → T019 → T020 → T025 → T028 → T033 → T034 → T043 → T046 → T082 → T089 → T100
```

`T033`/`T034` (escrita condicional) e `T082` (teste de concorrência) são os dois pontos onde um erro
compromete a corretude financeira do sistema inteiro. Nenhum dos dois deve ser apressado.

### Ordenação com impacto prático

| Restrição | Consequência |
|---|---|
| **T006 antes da Phase 2** | O teste de arquitetura protege o código novo desde a primeira classe, em vez de ser corrigido no fim |
| **T007 na Phase 1** | Sem isso, o contexto Spring fica inconstruível entre T028 e T056 |
| **T056 pode ser antecipado** | Para `ApplicationTests.contextLoads` verde a cada checkpoint, execute T056 logo após a Phase 5. Caso contrário o contexto só volta a subir no fim da Phase 9 — os unitários permanecem verdes de qualquer forma |
| **T041 antes de T042** | A triagem de FR-004a precisa do tipo selado antes de o mapper poder devolvê-lo |
| **T040 antes de T034** | O writer traduz exceções da AWS para `TransientProcessingException`, definida na Phase 7. Alternativa: mover T040 para a Phase 6 |
| **T054 antes de T077** | O teste do mapper de resposta verifica que o fuso configurado é respeitado |
| **T101 antes de tudo na Phase 13** | Tornar `ownerId` não-anulável quebra a compilação em cascata e revela de uma vez cada ponto que assumia o contrário. Começar pela borda faria descobri-los um a um |
| **T106 antes de T107** | Enquanto as fixtures aceitarem `ownerId = null`, os testes revertidos continuam conseguindo construir o estado que a spec passou a proibir |

### Sobre o estado da suíte antes da Phase 13

`./gradlew check` está **verde e desalinhado**: o código não mudou, mas `balances-api.yaml` e
`transaction-event.schema.json` mudaram. Enquanto a Phase 13 não rodar, a suíte verde mede a coerência
do código consigo mesmo, não a conformidade com o contrato publicado.

### Oportunidades de paralelismo

**Phase 2** — após T009: T010, T011, T012, T014, T015 em paralelo. Depois T013; depois
T016/T017/T018/T019; por fim T020/T021.

**Phase 4** — T024, T025, T026, T027 são quatro interfaces independentes.

**Phases 6, 7 e 8** — após a Phase 5, as três correm em paralelo: DynamoDB, Kafka e REST tocam pacotes
disjuntos e conversam apenas através dos ports.

**Phase 10** — quase todos os testes marcados `[P]` são independentes. Os de integração (T081–T085) são
sequenciais entre si por compartilharem infraestrutura.

**Phase 12** — T095, T096, T097, T098 são independentes.

**Phase 13** — T104 é independente das tasks de ingestão (arquivos disjuntos), e T110/T111 são
documentação que só depende de T104. As duas frentes — ingestão (T101→T103, T107) e resposta
(T104→T105, T108) — encontram-se apenas em T106 e T112.

---

## Matriz de rastreabilidade

### User stories → tasks

| Story | Prioridade | Implementação | Testes | Teste independente |
|---|---|---|---|---|
| **US1** — Consultar saldo | P1 | T049, T050, T051, T052, T053, T054, T029, T035, **T104, T105** | T064, T074, T076, T077, T086, **T108** | Popular estado e verificar 200/404/400/500, com o corpo de quatro chaves |
| **US2** — Aplicar snapshot | P1 | T013, T019, T020, T028, T033, T039, T042, T043, **T101, T102, T103** | T060, T065, T075, T083, **T107, T109** | Publicar evento elegível e verificar estado; evento sem titular vai para a DLQ |
| **US3** — Ignorar inelegíveis | P1 | T015, T018, T020, T028 | T059, T061, T067 | `DECLINED`/`DISABLED` não mudam saldo **nem marcador** |
| **US4** — Duplicados e fora de ordem | P1 | T033, T034 | T062, T071, T072, T081 | Publicar embaralhado/repetido; saldo = maior timestamp |
| **US5** — Concorrência | P2 | T033 (mesma expressão condicional) | T080, T082, T091 | 32 threads simultâneas; estado determinístico |
| **US6** — Falhas sem perda | P2 | T040, **T041**, T044, T045, T046 | T066, **T068**, T070, T084 | Injetar falhas e mensagens de outro fluxo; verificar retry, DLQ e offset |

**US5 não tem task de implementação própria.** A concorrência é resolvida pela mesma expressão
condicional de US4 — é a tese do desenho (Constitution VI). O que US5 acrescenta é **verificação**
(T082), não código.

### Requisitos funcionais → tasks

| Requisitos | Tasks |
|---|---|
| FR-001, FR-002 | T038, T043, T047, T048 |
| FR-003, FR-004, FR-005 | T039, T042, T066 |
| **FR-003a** (titular obrigatório) | **T101, T102, T103, T106, T107, T109, T112** |
| **FR-004a** | **T027, T041, T042, T043, T056, T068, T069, T084, T091** |
| FR-006, FR-007 | T048, T088 |
| FR-008, **FR-008a**, FR-009 a FR-012 | T015, T018, T020, T028, T042, T059, T061, T067 |
| FR-013 a FR-018 | T013, T019, T020, T031, T060, T075 |
| FR-019 a FR-024 | T024, T033, T034, T062, T071, T072, T081 |
| FR-025 a FR-028 | T033, T080, T082, T091 |
| FR-029 a FR-033 | T030, T031, T035, T037, T074, T087 |
| FR-034 a FR-041 | T040, T044, T045, T046, T070, T084 |
| FR-042 a FR-051 | T049 a T054, T076, T086 |
| **FR-043, FR-043a** (corpo fechado) | **T104, T105, T108, T111, T112** |
| **FR-044** (número JSON, escala 2) | **T104, T105, T108, T110, T111, T112** |
| **FR-046** (`updated_at`) | **T049, T050, T054, T077, T086** — inalterado em 2026-09-07 |
| FR-052 a FR-055 | T027, T055, T056, T057, T058, T063, T078 |

### Invariantes → tasks de verificação

| Invariante | Verificada por |
|---|---|
| INV-001, INV-002, INV-004 | T071, T081, T082 |
| INV-003 | T061 |
| INV-005 | T072, T081, T082, T091 |
| INV-006 | T013, T031, T049, T075, T079, **T103, T104, T108** |
| INV-007 | T044, T084 |
| INV-008 | T063, T068, T078, T084 |

### Success criteria → tasks de validação

| SC | Validado por |
|---|---|
| SC-001, SC-002 | T085, T090 |
| SC-003 | T082, T091 |
| SC-004 | T091 |
| SC-005 | T074, T083 |
| SC-006 | T058, T100 |
| SC-007 | T091 |
| SC-008 | T045, T084, **T109** |
| SC-009 | T075, T090 |
| SC-010 | T091 |

---

## Estratégia de implementação

### MVP funcional — T001 a T054 (Phases 1 a 8)

Ao fim da Phase 8 as **seis** user stories estão implementadas: ingestão, triagem, elegibilidade,
ordenação, idempotência, concorrência, resiliência e consulta. A aplicação é demonstrável ponta a ponta.

**Isso não é entregável.** Constitution XIII torna a observabilidade obrigatória e XIV exige os testes.
"MVP" aqui significa funcionalmente completo, não pronto para produção.

### Entrega incremental

| Incremento | Tasks | O que passa a funcionar |
|---|---|---|
| 1. Base limpa | T001–T008 | Build verde, barreira arquitetural **ativa** |
| 2. Regras de negócio | T009–T029 | Elegibilidade e snapshot testáveis sem infraestrutura |
| 3. Persistência correta | T030–T037 | Ordenação, idempotência e concorrência resolvidas |
| 4. Ingestão | T038–T048 | Eventos reais atualizam saldo; DLQ e triagem funcionando |
| 5. Consulta | T049–T054 | API responde no contrato acordado |
| 6. Observabilidade | T055–T058 | Desfechos silenciosos ficam visíveis |
| 7. Verificação | T059–T091 | Cada princípio com teste que falha se violado |
| 8. Fechamento | T092–T100 | Gates e ADRs |
| 9. Correção do contrato | T101–T112 | Titular obrigatório na ingestão; resposta no contrato acordado |

Cada incremento é um ponto de parada válido para revisão.

### Estratégia com múltiplas pessoas

Após a Phase 5, três frentes independentes:

- **Pessoa A**: Phase 6 (DynamoDB) → T081, T082 — a frente mais crítica
- **Pessoa B**: Phase 7 (Kafka) → T083, T084 — inclui a triagem de FR-004a
- **Pessoa C**: Phase 8 (REST) + Phase 9 (observabilidade) → T076, T077, T078, T086

As três tocam pacotes disjuntos e se comunicam apenas através dos ports das Phases 3 e 4.

---

## Notas

- `[P]` = arquivos distintos, sem dependência pendente
- Commitar por task ou por grupo lógico
- **Não relaxar `coverageMinimum = 0.90`.** Se um adapter for difícil de cobrir, extrair a lógica
  testável para funções puras (risco R-06) — a dificuldade de teste é sinal de desenho, não motivo para
  baixar o gate
- **T082 é o teste que mais facilmente passa por acidente.** Sem `CountDownLatch`, sem repetição e sem
  contabilidade de sucessos + falhas, ele prova muito menos do que parece
- **T068 é o teste que protege contra o risco R-14.** O par de payloads quase idênticos com destinos
  opostos deve ficar adjacente no arquivo: é a leitura lado a lado que torna a regra óbvia
- Riscos completos em [plan.md §16](./plan.md#16-risks)
