# Phase 0 — Research: Core Banking Balance

**Feature**: `002-core-banking-balance` | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-06

Este documento resolve os pontos que a specification deixou em aberto (`NEEDS CLARIFICATION` e
assumptions A-01 em diante) e registra as decisões técnicas que sustentam o plano. Todas as versões
foram lidas dos arquivos do projeto, nenhuma foi assumida.

---

## R1 — Nomes de tópico, DLQ e consumer group

**Pergunta**: a spec (A-01) deixou os nomes concretos para o plano.

**Decision**:

| Item | Valor | Variável |
|---|---|---|
| Tópico de entrada | `transactions-events` | `TRANSACTIONS_TOPIC` |
| DLQ | `transactions-events.dlq` | `TRANSACTIONS_DLQ_TOPIC` |
| Consumer group | `core-banking-balance-consumer` | `KAFKA_CONSUMER_GROUP_ID` |
| Partições (ambos) | 3 | seed |

**Rationale**: `transactions-events` espelha o nome do gerador já existente
(`infra/redpanda/produce-transactions-events.sh`), então o desenvolvedor produz eventos de teste com
`make kafka-produce-transactions-events TOPIC=transactions-events` sem inventar nada. O sufixo `.dlq` é a
convenção mais legível em consoles Kafka e mantém os dois tópicos adjacentes na listagem. Três partições
seguem a recomendação da ADR-002 existente (capacidade para ~1.000 eventos/s de SC-007 com folga de
~40%).

**Alternatives considered**:
- `balance-updates` (nome da ADR-002): descreve o efeito, não o evento. O tópico carrega transações; o
  saldo é consequência. Rejeitado por acoplar o nome do tópico ao consumidor.
- Sufixo `-dlt` (convenção padrão do Spring Kafka): funcionalmente idêntico. Escolhido `.dlq` por ser o
  vocabulário usado pela própria specification (FR-038).
- 1 partição (como o seed atual de `greeting-templates`): impede paralelismo e contradiz o objetivo de
  SC-007.

---

## R2 — Mecanismo de escrita condicional atômica no DynamoDB

**Pergunta**: como garantir `persistedTimestamp < incomingTimestamp` atomicamente (FR-021,
Constitution V)?

**Decision**: `PutItem` com

```
ConditionExpression = "attribute_not_exists(#pk) OR #lastEventTimestamp < :incomingTimestamp"
ExpressionAttributeNames  = { "#pk": "pk", "#lastEventTimestamp": "lastEventTimestamp" }
ExpressionAttributeValues = { ":incomingTimestamp": N(<micros>) }
ReturnValuesOnConditionCheckFailure = ALL_OLD
```

**Rationale**: o DynamoDB avalia a condição e aplica a mutação como uma **única operação** sobre a
partition key. Não existe janela entre avaliar e escrever, então nenhum entrelaçamento de threads,
consumers ou instâncias pode produzir *lost update*. `ALL_OLD` devolve o item persistido dentro da
própria `ConditionalCheckFailedException`, permitindo classificar duplicado/stale/tie sem uma segunda
chamada — o que evita introduzir a corrida que a condição acabou de eliminar.

Dois defeitos concretos da ADR-001 existente motivaram a supersessão:
1. `timestamp` **é palavra reservada** no DynamoDB; a expressão literal da ADR-001 falha em tempo de
   execução sem `ExpressionAttributeNames`.
2. `attribute_not_exists(accountId)` testa um atributo que não é a chave da tabela desenhada aqui.

**Alternatives considered**:

| Alternativa | Rejeitada porque |
|---|---|
| `UpdateItem` com a mesma condição | Semântica equivalente, mas exige `UpdateExpression` para todos os atributos. `PutItem` substitui o item inteiro, que é exatamente a semântica de snapshot. |
| `GetItem` + comparação em memória + `PutItem` | Viola FR-021 explicitamente: a janela entre leitura e escrita é onde o *lost update* mora. |
| `TransactWriteItems` com `ConditionCheck` | Dobra custo e latência para a mesma garantia que uma condição de item único já fornece. |
| Optimistic locking com atributo `version` | `lastEventTimestamp` já é um número monotônico que cumpre o papel de versão. Um segundo atributo seria redundante e mais uma coisa para manter sincronizada. |
| Tabela de idempotência separada | Segunda escrita por evento, dobra custo e storage, e cria a questão de consistência entre as duas tabelas. |

---

## R3 — Representação monetária ponta a ponta

**Pergunta**: como satisfazer Constitution X e INV-006 sem que nenhum valor passe por ponto flutuante em
nenhuma etapa?

**Decision**:

| Etapa | Representação |
|---|---|
| JSON de entrada | número; Jackson desserializa direto para `BigDecimal` (tipo alvo declarado) |
| Domínio | `BigDecimal` com escala **exatamente 2**, dentro de `Money` |
| DynamoDB | atributo `S` (String), `BigDecimal.toPlainString()` |
| JSON de saída | `String` |

**Rationale**: a descoberta decisiva é que o tipo `N` do DynamoDB **normaliza o número e remove zeros à
direita** — `150.00` é lido de volta como `"150"`. O valor não é corrompido, mas a escala é perdida, e
FR-044 exige exatamente duas casas na resposta. Guardar como `S` faz o valor retornar byte a byte
idêntico, sem reconstrução de escala em nenhum ponto. Não perdemos nada, porque nenhuma expressão do
DynamoDB compara, ordena ou soma saldo.

Na saída, `String` evita que o cliente receba `150.0` ou `150` conforme a configuração do serializador, e
evita que parsers JSON de linguagens dinâmicas convertam para `double` na leitura — que é a forma mais
comum de corromper dinheiro em uma API.

`Money` não expõe `plus` nem `minus`. A ausência do método torna a violação de Constitution VIII
(derivar saldo por aritmética) impossível de escrever, em vez de apenas desaconselhada.

**Alternatives considered**:
- `N` no DynamoDB: perde a escala (comprovado pela normalização de trailing zeros). Rejeitado.
- `Long` em centavos: obriga aritmética de conversão em todas as bordas e esconde a moeda do tipo.
  Rejeitado — mais oportunidades de erro do que resolve.
- `BigDecimal` serializado como número JSON na resposta: viola o espírito de Constitution X na fronteira
  com o cliente. Rejeitado.

---

## R4 — Semântica de acknowledgment do Kafka

**Pergunta**: como garantir que nenhum offset seja confirmado antes de um desfecho terminal (FR-034,
Constitution XII), incluindo o caso de falha ao publicar na DLQ (FR-040)?

**Decision**: `enable-auto-commit: false` + `AckMode.RECORD` + `DefaultErrorHandler` com
`DeadLetterPublishingRecoverer`. Sem código de commit manual.

**Rationale**: com `AckMode.RECORD`, o container commita o offset **depois** que o listener retorna
normalmente. Se o listener lança, o container não commita — ele faz `seek` e reentrega após o backoff. Se
o retry se esgota, o recoverer publica na DLQ; o commit acontece só depois que a publicação é aceita. Se
a publicação falha, o recoverer lança, o container faz `seek`, e a mensagem é reentregue — que é
exatamente a linha 9 da matriz de decisão da spec, obtida sem escrever uma linha de tratamento.

**Alternatives considered**:
- `AckMode.MANUAL_IMMEDIATE` com `Acknowledgment` injetado: exigiria coordenar o ack manual com o
  `ackAfterHandle` do error handler, criando **dois** lugares que decidem commit, para obter a mesma
  semântica. Rejeitado por ser mais código e mais superfície de erro.
- `enable-auto-commit: true`: proibido explicitamente pela Constitution XII — pode confirmar registros
  não processados.
- `AckMode.BATCH` (padrão): commita o lote inteiro após o loop; um erro no meio do lote reprocessa
  registros já aplicados. Funciona sob idempotência, mas torna o raciocínio sobre "qual offset está
  confirmado" mais difícil. `RECORD` é mais caro em commits e muito mais fácil de justificar.

---

## R5 — Onde o retry deve viver

**Pergunta**: como evitar retry simultâneo em múltiplas camadas (requisito explícito do input)?

**Decision**: o `DefaultErrorHandler` do container Kafka é a **única** autoridade de retry no caminho de
ingestão. O retry interno do AWS SDK é desligado via `ClientOverrideConfiguration`. Timeouts explícitos:
`apiCallAttemptTimeout = 1s`, `apiCallTimeout = 2s`.

**Rationale**: o AWS SDK v2 retenta 3 vezes por padrão com backoff próprio para erros de throttling. Com
4 tentativas do container por cima, o número real de tentativas vira 12 e o tempo total vira o produto
dos backoffs — o orçamento de SC-008 ("mensagem ruim não bloqueia a partição além da janela de retry")
deixa de ser calculável. Uma única camada torna o comportamento previsível e o cálculo de
`max.poll.interval.ms` verificável.

Consequência aceita: no caminho de **leitura** REST não há retry algum, então um throttle instantâneo
vira `500`. Defensável porque o cliente HTTP pode retentar, a falha é visível, e não há camada escondida.

**Alternatives considered**:
- Manter o retry do SDK e reduzir as tentativas do container: colocaria a política de resiliência em dois
  arquivos com vocabulários diferentes.
- `@Retryable` do Spring Retry na aplicação: terceira camada, e colocaria política de transporte na
  camada de aplicação — violação de Constitution III.
- Retry só no SDK: não alcança falhas fora da chamada AWS (parsing, mapeamento) e não tem caminho para
  DLQ.

---

## R6 — Backoff, jitter e limite de tentativas

**Decision**: `ExponentialBackOff(initialInterval = 200ms, multiplier = 2.0, maxInterval = 5s,
jitter = 100ms)` com `maxAttempts = 4`. Janela total ≈ 1,4 s.

**Rationale**: a janela precisa ser curta o suficiente para ficar **duas ordens de grandeza** abaixo de
`max.poll.interval.ms` (5 min), senão o retry dispara rebalance — que reordena mensagens e agrava
exatamente o problema que estamos tratando. 1,4 s satisfaz isso com folga enorme.

O jitter existe porque, sem ele, K consumers que sofrem o mesmo timeout no mesmo instante retentam
exatamente juntos: a segunda onda de requisições é tão sincronizada quanto a primeira e o throttle se
auto-perpetua. O jitter descorrelaciona as ondas.

**Risco identificado**: `ExponentialBackOff.jitter` precisa ser confirmado na versão do Spring Framework
que acompanha o Boot 4.1. Fallback: um `BackOff` customizado de ~15 linhas somando deslocamento
aleatório. Registrado como R-04 no plano.

---

## R7 — Classificação duplicado × stale × empate

**Pergunta**: FR-023 exige distinguir três rejeições que o DynamoDB reporta como uma única
`ConditionalCheckFailedException`.

**Decision**: usar o item devolvido por `ReturnValuesOnConditionCheckFailure=ALL_OLD` e testar **nesta
ordem**:

```
1. persisted.lastTransactionId == incoming.transactionId  → DUPLICATE_EVENT
2. persisted.lastEventTimestamp == incoming.timestamp     → TIMESTAMP_TIE_REJECTED
3. caso contrário                                          → STALE_EVENT
```

**Rationale**: a ordem implementa literalmente a definição da spec — `DUPLICATE_EVENT` é "o
`transaction.id` recebido é igual ao que originou o estado corrente". Testar identidade antes de
timestamp garante que a reentrega do mesmo evento seja sempre classificada como duplicada, mesmo quando o
timestamp empata. E a reentrega de uma transação que já foi sobrescrita por uma mais nova cai
corretamente em `STALE_EVENT`, porque nesse momento ela não é mais a transação que originou o estado.

**Ponto importante**: esta classificação é **telemetria, não corretude**. Se `ALL_OLD` não estiver
disponível, o comportamento financeiro é idêntico — apenas o rótulo colapsa para `CONDITION_FAILED`.
Nenhuma decisão de escrita depende dela.

---

## R8 — Status ausente ou desconhecido: inválido ou inelegível?

**Pergunta**: a spec se contradiz. FR-003 lista `transaction.status`/`account.status` como campos
obrigatórios e FR-004 diz que campo obrigatório ausente torna a mensagem **inválida** (DLQ). A seção
Edge Cases e a assumption A-06 dizem que status ausente ou desconhecido torna o evento **inelegível**
(ignorado, sem DLQ).

**Decision**: prevalece A-06 — status ausente ou desconhecido é **inelegível**, não inválido. Modelado
como `TransactionStatus.from(raw: String?)` e `AccountStatus.from(raw: String?)`, que nunca falham e
mapeiam qualquer coisa diferente de `"APPROVED"` / `"ENABLED"` para `NOT_APPROVED` / `NOT_ENABLED`.

**Rationale**: três razões convergem.
1. A regra de elegibilidade da Constitution IX é uma **igualdade positiva**: só `APPROVED` + `ENABLED`
   atualiza. O que não satisfaz a igualdade simplesmente não atualiza — não precisa ser erro.
2. É a leitura conservadora sob Constitution I: ignorar de forma observável nunca corrompe estado,
   enquanto mandar para a DLQ um evento que talvez fosse legítimo perde informação operacional.
3. Edge Cases e A-06 são regras mais específicas que FR-003/FR-004, e A-06 é declarada explicitamente
   como assumption com rationale.

**Status**: **RESOLVIDO** na sessão de clarificações de 2026-09-06. A spec foi emendada: FR-003 e
FR-004 excluem explicitamente `transaction.status` e `account.status` da lista de campos cuja ausência
invalida a mensagem, e **FR-008a** normatiza que ausente e desconhecido recebem tratamento idêntico. O
risco R-01 do plano está fechado.

---

## R9 — Forma do contrato REST

**Pergunta**: `contracts/balance-response.json` (amostra bruta do desafio) diverge do contrato REST
descrito na spec.

| Amostra do desafio | Contrato da spec |
|---|---|
| `id` | `accountId` |
| `owner` | ausente |
| `balance.amount` = `183.12` (número) | `"183.12"` (texto) |
| `updated_at` com offset `-03:00` | `lastEventAt` UTC com microssegundos |
| — | `lastTransactionId` |

**Decision**: **revisada em 2026-09-06.** Posição **mista, campo a campo**: o campo de instante adota o
nome e o formato da amostra (`updated_at`, offset local, milissegundos); a representação monetária, o
`accountId` e o `lastTransactionId` seguem a spec.

**Rationale**: a seção Governance da constitution estabelece que a specification prevalece sobre
artefatos conflitantes, e a amostra viola Constitution X ao representar dinheiro como número JSON. Além
disso, a spec adiciona `lastTransactionId`, que é informação genuinamente útil para rastrear qual evento
produziu o estado.

**Rationale da revisão**: o campo de instante é o mais provável de ser comparado literalmente por uma
verificação automatizada, e adotá-lo da amostra não custa nada em corretude — é renderização. Já o
`amount` como número JSON **não** foi concedido: é a forma mais comum de corromper dinheiro numa API, e
Constitution X não admite exceção por conveniência de avaliação. `id`/`owner` continuam não expostos, mas
`ownerId` é persistido, então expô-los depois não exige migração.

**Status**: **RESOLVIDO**; risco R-02 fechado. Se a forma mudar de novo, a mudança segue local a
`BalanceResponse` e `BalanceResponseMapper`.

---

## R10 — Prefixo `ACCOUNT#` na partition key

**Decision**: `pk = "ACCOUNT#" + accountId`, com `accountId` também guardado como atributo cru.

**Rationale**: custo real é uma concatenação isolada em `BalanceTableAttributes`, invisível ao domínio.
Benefício é opcionalidade: a tabela aceita outros tipos de item (`OWNER#`, `AUDIT#`) sem migração de
chave, e não há risco de colisão entre um `accountId` e um identificador de outra entidade com o mesmo
valor. É o idioma de single-table design recomendado pela AWS.

FR-030 ("recuperável diretamente pelo identificador da conta") continua satisfeito: `GetItem` com a chave
derivada é acesso direto, não busca.

**Alternatives considered**: `pk = accountId` puro é igualmente correto e marginalmente mais simples.
Escolhido o prefixo porque o custo é uma função de uma linha e o benefício é barato de manter.

---

## R11 — Logging estruturado sem dependência nova

**Pergunta**: FR-055 exige logs estruturados e correlacionáveis.

**Decision**: usar o suporte nativo do Spring Boot:

```yaml
logging:
  structured:
    format:
      console: ecs
```

**Rationale**: o Boot 4.1 emite JSON com todos os campos do MDC sem nenhuma dependência adicional e sem
`logback-spring.xml`. Como Constitution XV exige justificar cada dependência nova, evitar uma é
estritamente melhor do que justificá-la.

**Alternatives considered**: `logstash-logback-encoder` — dependência nova para capacidade que o
framework já tem. Rejeitado.

---

## R12 — Métricas e consumer lag

**Decision**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`. Duas dependências novas,
justificadas em Complexity Tracking do plano.

**Rationale**: FR-053 exige explicitamente **consumer lag**, que não é observável a partir do código da
aplicação sem consultar o `AdminClient` manualmente. Com Micrometer no classpath, o Boot registra
automaticamente as métricas do cliente Kafka, incluindo
`kafka.consumer.fetch.manager.records.lag.max`. Escrever isso à mão seria mais código do que a
dependência que ele evitaria.

`micrometer-registry-prometheus` é necessário porque sem um registry concreto as métricas não saem da
JVM. Não adiciona nenhum componente de infraestrutura ao ambiente local — é apenas um endpoint.

**Cardinalidade**: `accountId` e `transactionId` vão para **logs** (MDC), nunca para **tags de métrica**.
FR-052 pede atribuição por conta e transação — os logs fazem a atribuição, as métricas fazem a agregação.
Tag de alta cardinalidade em Prometheus é um incidente de produção esperando acontecer.

---

## R13 — Telemetria atrás de um output port

**Decision**: interface `BalanceTelemetry` em `port/output`, implementada por
`MicrometerBalanceTelemetry` em `adapter/output/observability`.

**Rationale**: dois benefícios concretos. `ProcessTransactionEventService` fica testável sem
`MeterRegistry`, e a lista completa de coisas observáveis vira um **contrato revisável em um arquivo**
contra FR-052, em vez de chamadas espalhadas por vários. É possível escrever um teste que afirma que cada
variante de `ProcessingOutcome` produz exatamente uma chamada de telemetria — tornando FR-054 ("todo
desfecho não-mutante deve ser observável") verificável em vez de aspiracional.

Custo: uma interface e uma classe.

**Alternatives considered**: chamar Micrometer direto na aplicação. Não viola o teste de arquitetura
(só o domínio é proibido de importar infraestrutura), mas quebra a testabilidade sem `MeterRegistry` e
dispersa o cumprimento de FR-052.

---

## R14 — Payload consumido como `String`

**Decision**: manter `StringDeserializer` + `ObjectMapper` da aplicação, como no starter.

**Rationale**: não é apenas herança do starter. Manter o payload cru disponível **dentro** do listener é
o que permite publicar o original na DLQ byte a byte, como FR-039 exige. Um `JsonDeserializer`
falharia antes do listener e o payload original ficaria acessível apenas por caminhos indiretos
(`ErrorHandlingDeserializer` + header de payload bruto), com mais configuração para um resultado pior.

Consequência de desenho: todos os campos do DTO são nullable, e o mapper — não o Jackson — decide o que
falta. Isso permite que a exceção diga *qual* campo invalidou a mensagem, e essa informação chega à DLQ
no header `kafka_dlt-exception-message`.

---

## R15 — Deployment: um ou dois deployables?

**Decision**: um deployable agora; separação futura por perfis Spring (`api` / `consumer`) sobre o mesmo
jar.

**Rationale**: Constitution XV é explícita — "uma aplicação é suficiente até prova em contrário" — e a
spec (A-11) confirma. Nenhum requisito atual demanda escala independente entre ingestão e consulta.

A separação já está estruturalmente preparada: os dois adapters de entrada não se conhecem e conversam
apenas com input ports. Quando um requisito aparecer (perfis de escala divergentes, ou isolamento de
falha entre backlog de ingestão e latência de consulta), o caminho é anotar `@Profile` e ajustar
`spring.main.web-application-type` — sem novo módulo Gradle, sem duplicar código.

---

## R16 — Estado da infraestrutura existente (o que preservar)

Levantado diretamente dos arquivos, não assumido:

| Componente | Versão / configuração | Decisão |
|---|---|---|
| Kotlin | 2.3.21, JVM toolchain 21 | preservar |
| Spring Boot | 4.1.0 | preservar |
| AWS SDK BOM | 2.46.7 | preservar |
| Jackson | `tools.jackson` (Jackson 3) | preservar — atenção: pacote `tools.jackson`, não `com.fasterxml` |
| Konsist | 0.17.3 | preservar, corrigir escopo |
| JaCoCo | 0.8.12, gate 0.90 ligado a `check` | preservar sem relaxar |
| DynamoDB Local | `amazon/dynamodb-local:3.3.0`, `-sharedDb -inMemory` | preservar |
| Redpanda | `v26.1.14`, KRaft single-node | preservar |
| `auto_create_topics_enabled` | **desabilitado** por `infra/redpanda/config.sh` | preservar — por isso o seed precisa criar os dois tópicos explicitamente |
| Source set `integrationTest` | fora de `check`, roda com `make integration-test` | preservar |
| `Dockerfile` | multi-stage `base`/`test`/`builder`/`runtime` | preservar sem alteração |

**Observação relevante**: o pacote raiz do código de exemplo já é
`br.com.itau.challenge.balance` — o starter usa `balance` como pacote e `Greeting*` como classes. O que
muda é o conteúdo, não a estrutura de pacotes.

**Defeito encontrado**: `HexagonalArchitectureTest` escopa `br.com.itau.challenge.hello..`, pacote que
**não existe**. O teste passa sobre um conjunto vazio — é um falso verde, e a barreira arquitetural que
a Constitution III exige não está ativa hoje. O próprio Sync Impact Report da constitution já registra
essa lacuna. Correção obrigatória.

---

## R17 — Triagem de mensagem de outro fluxo (FR-004a)

**Pergunta**: o tópico pode receber mensagens no formato `{"account": {...}}`, sem bloco `transaction`
(produzidas por `make kafka-produce-accounts-events`). A spec as coloca em *Out of Scope*, mas não dizia
o que o sistema **faz** ao recebê-las. Pela regra anterior elas caíam em FR-004 e iam para a DLQ.

**Decision** (clarificação de 2026-09-06, FR-004a): descarte **observável**, sem DLQ e sem retry, com o
motivo `UNSUPPORTED_MESSAGE_TYPE`. É desfecho terminal de sucesso e autoriza a confirmação do offset.
Mensagem com bloco `transaction` **presente porém incompleto** continua sendo **inválida** e vai para a
DLQ.

**Rationale**: a DLQ existe para o que exige intervenção humana. Um evento de ciclo de vida de conta
chegando num tópico de transações não é defeito de dados — é uma mensagem de outro fluxo. Se os dois
formatos coexistirem no ambiente de avaliação, a regra anterior encheria a DLQ de mensagens saudáveis,
mascarando as realmente quebradas e disparando alarme falso.

**Consequência de desenho não óbvia**: o descarte **não pode ser sinalizado por exceção**. Qualquer
exceção que suba do listener é capturada pelo `DefaultErrorHandler`, que a rotearia para a DLQ —
exatamente o que se quer evitar. Por isso o mapper passa a devolver um tipo selado:

```
sealed interface MessageInterpretation
    Interpreted(event: TransactionEvent)
    Unsupported(reason: UnsupportedReason)
// inválido continua sendo UnprocessableEventException lançada
```

A fronteira precisa é: **bloco `transaction` integralmente ausente** → descarte;
**bloco presente porém incompleto** (`{"transaction":{}}`, `{"transaction":null}`) → DLQ.

**Alternatives considered**:
- Lançar exceção e excluí-la da DLQ no error handler: esconde uma regra de negócio dentro da
  configuração de transporte e a torna invisível na revisão.
- Pré-checar no consumer antes de chamar o mapper: duplica a leitura do payload e espalha a decisão por
  dois arquivos.
- Manter tudo indo para a DLQ: mais simples, mas destrói o valor da DLQ como sinal.

---

## R18 — Renderização de `updated_at` na resposta REST

**Pergunta**: qual nome e formato para o instante do snapshot na resposta?

**Decision** (clarificação de 2026-09-06): campo **`updated_at`**, ISO-8601 com **offset local** (fuso
configurável via `BALANCE_API_TIMEZONE`, padrão `America/Sao_Paulo`) e **milissegundos por truncamento** —
`2025-07-05T18:04:13.433-03:00`. Substitui o `lastEventAt` em UTC com microssegundos da revisão anterior.

**Rationale**: o campo de instante é o mais provável de ser comparado literalmente por uma verificação
automatizada do desafio, e adotá-lo da amostra não custa nada em corretude, porque é apenas renderização.
O valor persistido continua em microssegundos e é ele que decide ordenação.

Dois pontos que a implementação não pode escolher sozinha:

1. **Truncamento, não arredondamento.** Arredondar `...589998` µs para `...590` ms exibiria um instante
   que nunca existiu — e, no limite de um segundo, um instante no futuro. Usa-se
   `truncatedTo(ChronoUnit.MILLIS)`.
2. **A precisão perdida é só de exibição.** Dois eventos separados por 1 µs renderizam `updated_at`
   idêntico, mas o banco os ordena corretamente; `lastTransactionId` na resposta desambigua qual evento
   produziu o estado.

**O que NÃO foi concedido à amostra**: `balance.amount` permanece **texto**. Um número JSON é lido como
ponto flutuante pela maioria dos clientes, e Constitution X não admite exceção por conveniência de
avaliação. `id` e `owner` também não são expostos na v1, embora `ownerId` seja persistido para que
expô-los depois não exija migração.

**Alternatives considered**: UTC com microssegundos (mais preciso, diverge da amostra avaliada);
arredondamento (pode exibir instante futuro); adotar a amostra inteira incluindo `amount` numérico
(viola Constitution X — exigiria emenda à constitution, não ao plano).

---


## R19 — Saldo na resposta REST: número JSON ou texto?

**Pergunta**: `balance.amount` deve sair como `183.12` (número JSON) ou `"183.12"` (texto)?

**Decision** (clarificação de 2026-09-07): **número JSON**, serializado a partir de `BigDecimal`, com a
escala 2 preservada. **Reverte a parte monetária de R3 e R9.**

**Rationale**: a objeção original ao número JSON era que "o cliente lê como ponto flutuante". Ela é
verdadeira e continua verdadeira — mas descreve o comportamento do *parser do cliente*, não a saída
deste serviço. Verificado empiricamente contra a versão de Jackson em uso:

```
BigDecimal("183.12")  ->  {"amount":183.12}
BigDecimal("150.00")  ->  {"amount":150.00}     <- a escala sobrevive; NÃO vira 150
BigDecimal("0.00")    ->  {"amount":0.00}
BigDecimal("-50.25")  ->  {"amount":-50.25}
```

Ou seja, a representação decimal exata que a Constitution X exige **é preservada até a última fronteira
que este sistema controla**. O texto do princípio proíbe *tipos* de ponto flutuante binário (`Float`,
`Double`) e exige representação decimal exata — nenhuma dessas duas cláusulas é violada por um número
JSON emitido de um `BigDecimal`. A garantia termina onde termina o sistema.

A condição inegociável: `amount` sai de `BigDecimal`, **nunca** de `Double`. Isso não depende de
disciplina — `MonetaryPrecisionArchitectureTest` já falha a build se qualquer `Double`/`Float` aparecer
em código de produção, e um teste fixa que `150.00` serializa como `150.00`.

**Consequência documental**: a ADR-006 ("Money as Decimal Text at Every Boundary") deixa de descrever o
sistema na fronteira REST e precisa ser emendada — não é cosmético, é a ADR que alguém lerá para
entender por que o dinheiro é tratado assim.

**Alternativas consideradas**:

| Alternativa | Por que não |
|---|---|
| Manter texto `"183.12"` | Mantém a leitura mais estrita da Constitution X, mas não casa com a amostra que a avaliação compara. Foi a decisão de 2026-09-06, revertida com conhecimento do trade-off |
| Número JSON tratado como violação, com emenda formal da constitution | Desnecessário sob a leitura literal do princípio: emendar uma constitution para permitir algo que ela já permite enfraquece o instrumento |
| Número JSON serializado de `Double` | Violação direta e inequívoca da Constitution X. Bloqueado estruturalmente pelo teste de arquitetura |

### Adendo de 2026-09-07 — o risco aceito materializou-se na primeira inspeção manual

A decisão acima **permanece**, mas a observação de campo que a seguiu mudou o que os artefatos precisam
dizer. Com a aplicação de pé, uma resposta desta conta foi inspecionada e apareceu como `"amount": 150`.
Reproduzido byte a byte, o serviço estava correto e o cliente é que perdeu a escala:

| Como o corpo é lido | `amount` exibido | Perdeu escala? |
|---|---|---|
| `od -c` / `curl` puro — bytes na rede | `150.00` | não — **é o que o serviço emite** |
| `jq .` | `150.00` | não (jq preserva o literal de números não modificados) |
| `python3 json.dumps(json.load(...))` | `150.0` | sim, uma casa |
| JavaScript `JSON.parse` + `JSON.stringify` | `150` | sim, as duas |

O último caso cobre **Postman, Insomnia, o REST Client do VS Code e o DevTools do navegador**: todos
executam `JSON.parse`, que converte o literal num `double` de 64 bits — onde `150.00` e `150` são o mesmo
valor — e reimprimem a forma mais curta.

**O que isso confirma, e o que não confirma.** Confirma que a garantia de FR-044 termina exatamente onde
R19 disse que terminaria, e que a perda a partir dali é real e visível na primeira conferência manual —
não uma preocupação teórica. **Não** indica defeito: `BalanceResponseContractTest` afere o corpo bruto e
continua verde, e `MonetaryPrecisionArchitectureTest` continua proibindo `Double` em produção.

**Consequência para os artefatos** (o que esta rodada de plan propaga): o efeito precisa estar escrito
onde alguém o encontrará *antes* de concluir que o sistema está quebrado — no contrato, no quickstart e
no README. Ver também a limitação de expressividade do JSON Schema registrada em
[balances-api.yaml](./contracts/balances-api.yaml).

**Observação que restringe o alcance do problema**: só aparece em valores com zero à direita. `183.12` —
o valor da amostra do desafio — chega íntegro a qualquer cliente; `150.00` e `0.00` não. A amostra,
portanto, **nunca exercita o caso**: segui-la não é evidência de compatibilidade.

---

## R20 — `account.owner` obrigatório e corpo da resposta fechado

**Pergunta**: o que fazer quando um evento não traz `account.owner`, e quais chaves a resposta expõe?

**Decision** (clarificação de 2026-09-07): `account.owner` é **estruturalmente obrigatório**; um evento
sem ele é **mensagem inválida** → DLQ sem retry. A resposta expõe **exatamente** `id`, `owner`,
`balance`, `updated_at`; `lastTransactionId` sai do corpo e **continua persistido**.

**Rationale**: no domínio do problema toda conta pertence a um titular, então um evento sem titular
descreve uma conta que não pode existir — é defeito do produtor, não caso de negócio. Tratá-lo como
inválido é a leitura conservadora que a Constitution I pede: na dúvida, **não** mutar estado.

Sobre a resposta: o `additionalProperties: false` do contrato torna uma chave extra suficiente para um
cliente estrito rejeitar a resposta inteira. `lastTransactionId` era um campo de diagnóstico; a
rastreabilidade que ele oferecia continua existindo na tabela e nos logs estruturados (MDC).

**Risco assumido, registrado como FR-003a**: um evento com saldo perfeitamente válido que omita o
titular **não atualiza o saldo** daquela conta. Um campo sem nenhuma influência sobre o valor ganhou
poder de interromper a atualização. É defensável — a alternativa é gravar um saldo cuja conta o sistema
não sabe descrever — mas é uma superfície de falha nova, e a DLQ é onde ela fica visível em vez de
silenciosa. Monitorar `balance_dlq_published_total` cobre a detecção; nenhuma métrica nova é necessária,
porque o header `kafka_dlt-exception-message` já nomeia o campo ausente.

**Alternativas consideradas**:

| Alternativa | Por que não |
|---|---|
| `owner` opcional, `null` na resposta | Mantém o saldo atualizando sempre, mas admite no sistema uma conta sem titular — que o próprio dono do domínio afirma não existir |
| `owner` opcional, omitido da resposta quando ausente | Além do acima, obriga todo cliente a tratar duas formas de resposta para a mesma situação |
| `owner` obrigatório, mas evento sem ele **ignorado** em vez de DLQ | Confundiria defeito de produtor com regra de negócio: o descarte observável é reservado a mensagens de **outro fluxo** (FR-004a), e diluí-lo faria a DLQ deixar de ser sinal |
| Expor `lastTransactionId` e atualizar o contrato | Preserva o diagnóstico no corpo, mas rompe a compatibilidade com a amostra que a avaliação compara |

---

## Resumo: NEEDS CLARIFICATION resolvidos

| Origem | Questão | Resolvido em |
|---|---|---|
| A-01 | nomes de tópico, DLQ e consumer group | R1 |
| FR-021 | mecanismo de escrita condicional atômica | R2 |
| FR-018 / X | representação monetária ponta a ponta | R3 |
| FR-034 / XII | semântica de acknowledgment | R4 |
| input do usuário | evitar retry em múltiplas camadas | R5 |
| FR-036 | backoff, jitter, limite | R6 |
| FR-023 | classificação das três rejeições | R7 |
| FR-003 × A-06 | status ausente: inválido ou inelegível | R8 (**pendente de emenda à spec**) |
| contracts × spec | forma do contrato REST | R9 (**pendente de confirmação**) |
| discovery | prefixo `ACCOUNT#` na PK | R10 |
| FR-055 | logging estruturado | R11 |
| FR-053 | consumer lag e métricas | R12 |
| FR-052/054 | onde a telemetria vive | R13 |
| FR-039 | preservação do payload na DLQ | R14 |
| A-11 / XV | um ou dois deployables | R15 |
| discovery | o que preservar do starter | R16 |
| **FR-004a** | mensagem de outro fluxo: DLQ ou descarte | **R17** (clarificado 2026-09-06) |
| **FR-046** | nome e formato do instante na resposta | **R18** (clarificado 2026-09-06) |
| **FR-044** | saldo na resposta: número JSON ou texto | **R19** (clarificado 2026-09-07) |
| **FR-003a / FR-043** | `account.owner` obrigatório e exposto; corpo fechado em 4 chaves | **R20** (clarificado 2026-09-07) |

**Nenhum item permanece aberto.** R8 e R9 foram fechados na sessão de clarificações de 2026-09-06,
junto com R17 e R18. A sessão de **2026-09-07** reabriu deliberadamente o contrato REST e produziu R19 e
R20, que **revertem parte de R9**: a amostra do desafio passou a ser normativa para o corpo da resposta.
Os riscos R-01, R-02 e R-09 seguem resolvidos, e não há bloqueador para o `/speckit-implement`.
