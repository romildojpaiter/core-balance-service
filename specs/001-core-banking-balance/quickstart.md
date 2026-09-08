# Quickstart — Validação do Core Banking Balance

**Feature**: `002-core-banking-balance` | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-06

Roteiro de validação executável: como provar, ponta a ponta, que a feature satisfaz a specification.
Este documento é um **guia de execução e verificação**, não de implementação — o código vem de
`tasks.md`.

Detalhes de contrato estão em [contracts/](./contracts/); detalhes de modelo em
[data-model.md](./data-model.md).

---

## Pré-requisitos

- Docker e Docker Compose
- JDK 21 (ou apenas Docker — o `Dockerfile` traz sua própria toolchain)
- `make`

Nenhuma infraestrutura nova é necessária: DynamoDB Local e Redpanda já estão no `docker-compose.yml` do
starter kit.

---

## Setup

```bash
# Sobe DynamoDB Local + console, cria a tabela AccountBalances
make db-up

# Sobe Redpanda + console, cria transactions-events e transactions-events.dlq (3 partições cada)
make kafka-up

# Sobe a aplicação
make up && make logs
```

Consoles disponíveis:

| Serviço | URL |
|---|---|
| DynamoDB Admin | http://localhost:8001 |
| Redpanda Console | http://localhost:8081 |
| Aplicação | http://localhost:8080 |
| Métricas | http://localhost:8080/actuator/prometheus |

> **Nota**: `infra/redpanda/config.sh` desabilita a criação automática de tópicos. Os dois tópicos
> **precisam** ser criados pelo seed — é o que `make kafka-up` faz. Se um tópico não existir, a produção
> falha em vez de criar silenciosamente.

---

## Verificação da build

```bash
./gradlew check
```

Deve passar: testes unitários, testes de arquitetura Konsist e o gate JaCoCo de **90% de instruções**.

Três testes de arquitetura precisam estar ativos e verdes:

| Teste | Verifica |
|---|---|
| `HexagonalArchitectureTest` | direção de dependência sobre `..challenge.balance..` — e **não** sobre `..hello..`, que não existe |
| `MonetaryPrecisionArchitectureTest` | nenhuma classe de produção usa `Float`/`Double` |
| proibição de locks | nenhuma classe de produção usa `synchronized` ou `java.util.concurrent.locks` |

Se `HexagonalArchitectureTest` ainda escopar `hello`, ele passa sobre um conjunto vazio e **não prova
nada** — ver risco R-07 do plano.

```bash
make integration-test    # sobe a infra e roda o source set integrationTest
```

---

## Cenário 1 — Aplicar um snapshot elegível

**Cobre**: US2 cenário 1, FR-013, FR-016, FR-017

```bash
ACC=acc-quickstart-1

echo "{\"transaction\":{\"id\":\"tx-001\",\"type\":\"CREDIT\",\"amount\":100.00,\"currency\":\"BRL\",\"status\":\"APPROVED\",\"timestamp\":1725552000000100},\"account\":{\"id\":\"$ACC\",\"owner\":\"own-1\",\"status\":\"ENABLED\",\"balance\":{\"amount\":100.00,\"currency\":\"BRL\"}}}" \
  | docker compose run --rm --entrypoint rpk redpanda-seed \
      topic produce transactions-events --brokers redpanda:9092 -f '%v\n'

curl -s http://localhost:8080/balances/$ACC | jq
```

**Esperado**:

```json
{
  "id": "acc-quickstart-1",
  "owner": "own-1",
  "balance": { "amount": 100.00, "currency": "BRL" },
  "updated_at": "2024-09-05T13:00:00.000-03:00"
}
```

Confira em particular (forma revisada em 2026-09-07):

- **exatamente quatro chaves** — `id`, `owner`, `balance`, `updated_at`. Nenhum `lastTransactionId`: o
  contrato declara `additionalProperties: false`, então uma chave a mais faz um cliente estrito rejeitar
  a resposta inteira (FR-043a);
- `amount` vem **sem aspas**, como número JSON, e ainda assim com **duas casas** — `100.00`, nunca `100`
  (FR-044). É a escala sobrevivendo à serialização que prova que o valor não passou por `Double`;
- `updated_at` tem **exatamente três** dígitos fracionários com offset local (FR-046) — não seis, não `Z`.

> ### ⚠️ Leia isto antes de conferir `amount` em qualquer cenário
>
> **A ferramenta com que você inspeciona a resposta pode apagar as casas decimais.** O valor sai daqui
> como `100.00`; o que você vê depende de quem leu os bytes antes de te mostrar:
>
> | Como você lê a resposta | `amount` exibido |
> |---|---|
> | `curl` puro, ou `curl … \| od -c` | `100.00` ✅ |
> | `jq .` | `100.00` ✅ |
> | Postman, Insomnia, REST Client do VS Code, DevTools | `100` ❌ |
> | `python3 -m json.tool` | `100.0` ❌ |
>
> Clientes JavaScript executam `JSON.parse`, que converte o literal num `double` de 64 bits — onde
> `100.00` e `100` são o mesmo valor — e reimprimem a forma mais curta. **A perda acontece no
> visualizador, depois de o valor sair do serviço**, e não indica defeito.
>
> Por isso **todos os cenários deste guia usam `curl … | jq`**. Se precisar de prova incontestável,
> use `curl -s … | od -c` e leia os bytes: `" a m o u n t " : 1 0 0 . 0 0`.
>
> Vale notar o alcance: só valores com zero à direita são afetados. `183.12` chega íntegro em qualquer
> cliente; `100.00` e `0.00` não. Registrado em [research.md R19](./research.md), adendo de 2026-09-07.

Teste do truncamento: publique um evento com timestamp `1725552000000999` (µs). O `updated_at` deve
terminar em `.000`, **não** em `.001` — os microssegundos são truncados, nunca arredondados.

---

## Cenário 2 — Snapshot mais novo substitui

**Cobre**: US2 cenário 2, FR-019

Publique `tx-002` com `timestamp = 1725552000000200` e `balance.amount = 150.00` para a mesma conta.

**Esperado**: `amount = 150.00`. O `lastTransactionId` não aparece na resposta — para confirmar qual
evento venceu, use `make db-scan` ou o log estruturado da ingestão.

---

## Cenário 3 — Evento fora de ordem é rejeitado

**Cobre**: US4 cenário 1, FR-020, INV-002, INV-004

Publique `tx-003` com `timestamp = 1725552000000150` (menor que o persistido) e
`balance.amount = 120.00`.

**Esperado**: a consulta continua devolvendo `150.00` (e, na tabela, `lastTransactionId = "tx-002"`). O marcador
não regride.

**Verificação de observabilidade** — a rejeição precisa ser visível, não silenciosa (FR-054):

```bash
curl -s http://localhost:8080/actuator/prometheus | grep balance_events_outcome
# esperado: contador com outcome="stale" incrementado
```

---

## Cenário 4 — Duplicata é sucesso não-mutante

**Cobre**: US4 cenário 2, FR-024, INV-005

Publique `tx-002` de novo, **exatamente igual**, três vezes.

**Esperado**: saldo idêntico; contador com `outcome="duplicate"` incrementado em 3; nenhuma mensagem na
DLQ; offsets avançam.

---

## Cenário 5 — Empate de timestamp

**Cobre**: US4 cenário 3, FR-023, A-03

Publique `tx-777` com `timestamp = 1725552000000200` (igual ao persistido, transação **diferente**) e
`balance.amount = 180.00`.

**Esperado**: saldo permanece `150.00`; contador `outcome="timestamp_tie"` incrementado.

---

## Cenário 6 — Eventos inelegíveis não corrompem o estado

**Cobre**: US3, FR-008 a FR-012, INV-003

Publique, para a mesma conta, com `timestamp = 1725552000000300` e `balance.amount = 999.00`:

1. `transaction.status = "DECLINED"`, `account.status = "ENABLED"`
2. `transaction.status = "APPROVED"`, `account.status = "DISABLED"`
3. ambos negativos
4. `transaction.status` **ausente** (ver observação abaixo)

**Esperado em todos**: saldo permanece `150.00` e `updated_at` continua refletindo o evento `...000200` — o marcador
de frescor **não avança** (FR-010). Contador `outcome="ignored_ineligible"` com as tags de razão
(`TRANSACTION_NOT_APPROVED`, `ACCOUNT_NOT_ENABLED`). Nada na DLQ (FR-012).

**Teste decisivo do marcador**: em seguida publique um evento **elegível** com
`timestamp = 1725552000000250` (maior que o persistido `...200`, menor que o inelegível `...300`) e
`balance.amount = 175.00`.

**Esperado**: `"175.00"` é aplicado. Se o marcador tivesse avançado com o evento inelegível, este evento
válido seria suprimido — que é exatamente o que FR-010 existe para impedir.

> **Observação sobre o caso 4**: status ausente é tratado como **inelegível**, não inválido — regra
> normativa desde **FR-008a** (clarificação de 2026-09-06). Vale também para status desconhecido:
> acrescente um caso 5 com `transaction.status = "PENDING"` e espere o mesmo desfecho. Nada disso vai
> para a DLQ.

---

## Cenário 7 — Ordem embaralhada converge

**Cobre**: US4 cenário 4, SC-001

Para uma conta nova, publique em ordem de chegada embaralhada:

| Transação | timestamp | amount |
|---|---|---|
| T1 | `...100` | `100.00` |
| T2 | `...200` | `150.00` |
| T3 | `...150` | `120.00` |

Publique nas ordens `T3,T1,T2` / `T2,T3,T1` / `T1,T3,T2` para contas distintas.

**Esperado**: em todas as contas o saldo final é `150.00` — o snapshot de maior timestamp,
independentemente da ordem de chegada.

---

## Cenário 8 — Mensagem inválida vai para a DLQ sem retry

**Cobre**: US6 cenário 1, FR-004, FR-037, FR-038, FR-039

```bash
echo '{"transaction":{"id":"tx-bad"},"account":{"id":"acc-bad"}}' \
  | docker compose run --rm --entrypoint rpk redpanda-seed \
      topic produce transactions-events --brokers redpanda:9092 -f '%v\n'

make kafka-consume TOPIC=transactions-events.dlq
```

**Esperado**:

- a mensagem aparece na DLQ com o payload **original** preservado
- header `x-failure-reason = INVALID_MESSAGE`
- header `kafka_dlt-exception-message` **nomeia o campo** que faltou
- headers de origem (`kafka_dlt-original-topic` / `-partition` / `-offset`) presentes
- **nenhum retry**: nos logs, uma única tentativa de processamento
- nenhum saldo alterado
- o offset avança (o consumer não trava)

Repita com JSON malformado, com `timestamp` não numérico e com `balance.amount` de três casas decimais
(`100.001`) — todos devem produzir o mesmo comportamento.

### Cenário 8b — Mensagem de outro fluxo NÃO vai para a DLQ

**Cobre**: FR-004a, matriz linha 5a

```bash
# Formato {"account": {...}} — sem bloco transaction
make kafka-produce-accounts-events TOPIC=transactions-events COUNT=5

make kafka-consume TOPIC=transactions-events.dlq
```

**Esperado**:

- as cinco mensagens **não** aparecem na DLQ — este é o ponto do cenário;
- o contador `balance_events_unsupported_total{reason="no_transaction_block"}` incrementa em 5;
- o offset avança e as mensagens seguintes são processadas normalmente;
- nenhum saldo alterado.

**Teste de fronteira** — o par que trava a regra. Publique os dois payloads quase idênticos:

```bash
# (a) bloco transaction AUSENTE  -> descarte observável, NÃO vai para a DLQ
'{"account":{"id":"acc-b1","status":"ENABLED","balance":{"amount":10.00,"currency":"BRL"}}}'

# (b) bloco transaction PRESENTE porém vazio -> mensagem inválida, VAI para a DLQ
'{"transaction":{},"account":{"id":"acc-b2","status":"ENABLED","balance":{"amount":10.00,"currency":"BRL"}}}'
```

Se ambos acabarem no mesmo destino, a triagem de FR-004a está errada.

Contrato completo em [contracts/dlq-message.md](./contracts/dlq-message.md).

---

### Cenário 8c — Evento sem titular vai para a DLQ

**Cobre**: FR-003a, FR-004, matriz linha 6 — regra nova de 2026-09-07

```bash
ACC="acc-sem-owner-$RANDOM"
# Payload completo e válido em tudo, EXCETO account.owner
docker compose exec -T redpanda rpk topic produce transactions-events --brokers redpanda:9092 -f '%k\t%v\n' <<EOF
$ACC\t{"transaction":{"id":"tx-no-owner","status":"APPROVED","timestamp":1751749453433589},"account":{"id":"$ACC","status":"ENABLED","balance":{"amount":123.45,"currency":"BRL"}}}
EOF

curl -s -w " [%{http_code}]\n" http://localhost:8080/balances/$ACC
```

**Esperado**:

- a resposta é **`404`** — o saldo **não** foi aplicado, apesar de `balance.amount` ser perfeitamente
  válido;
- a mensagem aparece na DLQ com `x-failure-reason = INVALID_MESSAGE` e
  `kafka_dlt-exception-message` nomeando **`account.owner`**;
- nenhum retry ocorre: o offset avança após o aceite da DLQ.

Este cenário existe para tornar visível o **custo** da decisão de FR-003a: um campo que não influencia o
valor do saldo passou a poder impedir a atualização daquela conta. É deliberado sob Constitution I — na
dúvida, não mutar estado — e a DLQ é onde isso fica visível em vez de silencioso. Se este cenário
começar a disparar em volume em produção, a causa é o produtor, não este serviço.

---

## Cenário 9 — Consulta REST: os quatro status

**Cobre**: FR-042 a FR-050

| Comando | Status esperado |
|---|---|
| `curl -i localhost:8080/balances/acc-quickstart-1` | `200` |
| `curl -i localhost:8080/balances/acc-inexistente` | `404` com `code=BALANCE_NOT_FOUND` |
| `curl -i "localhost:8080/balances/conta%20invalida"` | `400` com `code=INVALID_ACCOUNT_ID` |
| `make db-down` e depois consultar | `500` com `code=INTERNAL_ERROR` |

Verificações adicionais:

- O `404` **não** devolve `0.00` — devolve erro (FR-047, divergência deliberada em relação à spec 003).
- O `400` não toca no armazenamento: com `make db-down`, um `accountId` inválido ainda responde `400`,
  não `500`.
- O `500` **não** contém nome de tabela, endpoint, mensagem de exceção ou stack trace (FR-049).
- O corpo do `200` tem **exatamente** `id`, `owner`, `balance`, `updated_at` — valide com
  `curl -s localhost:8080/balances/acc-quickstart-1 | jq 'keys'`, que deve imprimir essas quatro chaves
  e nenhuma outra (FR-043, `additionalProperties: false`).

Contrato completo em [contracts/balances-api.yaml](./contracts/balances-api.yaml).

---

## Cenário 10 — Concorrência e múltiplas instâncias

**Cobre**: US5, FR-025 a FR-028, SC-003, Constitution VI

Este é o cenário mais importante e o mais fácil de validar mal. Um teste que roda threads "ao mesmo
tempo" sem barreira de sincronização normalmente as executa em série e passa sem provar nada.

Coberto por `ConditionalWriteConcurrencyIntegrationTest` (ver
[plan.md § Testing Strategy](./plan.md#10-testing-strategy)), que exige:

1. **Barreira real**: `CountDownLatch` liberando todas as threads no mesmo instante.
2. **Contabilidade completa**: sucessos + `ConditionalCheckFailedException` = número de tentativas.
   Nenhuma escrita pode desaparecer.
3. **Repetição**: o cenário roda N vezes e o resultado é idêntico em todas — determinismo, não sorte de
   agendamento.

Validação manual com múltiplas instâncias:

```bash
docker compose up --scale app=3 -d

make kafka-produce-transactions-events TOPIC=transactions-events COUNT=1000

# Depois que o lag zerar:
curl -s http://localhost:8080/actuator/prometheus | grep records_lag_max
```

**Esperado**: nenhum saldo regride; a soma de
`applied + ignored_ineligible + duplicate + stale + timestamp_tie + dlq` é igual ao total de eventos
recebidos (SC-004 — nenhum evento desaparece sem registro).

---

## Cenário 11 — Reinício abrupto não altera saldo

**Cobre**: US6 cenário 4, SC-010

```bash
make kafka-produce-transactions-events TOPIC=transactions-events COUNT=200
docker compose kill app          # SIGKILL durante o processamento
docker compose up app -d
```

**Esperado**: os eventos cujo offset não foi confirmado são reprocessados e classificados como
`duplicate` ou `stale`. Nenhum saldo muda em relação ao estado anterior ao kill. Nenhuma intervenção
manual necessária.

---

## Cenário 12 — Observabilidade

**Cobre**: FR-052 a FR-055, Constitution XIII

```bash
make logs | head -20
```

**Esperado**: cada linha é JSON contendo `accountId`, `transactionId` e `correlationId` quando
conhecidos. Nenhuma credencial em nenhum log.

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E \
  'balance_events|balance_event_processing|balance_persistence|balance_dlq|records_lag_max|http_server_requests'
```

**Checklist**:

- [x] `balance_events_received_total` presente
- [x] `balance_events_outcome_total` com as cinco variantes de `outcome`
- [x] `balance_event_processing_seconds` (latência de processamento)
- [x] `http_server_requests_seconds` com `uri="/balances/{accountId}"` (latência da API)
- [x] `kafka_consumer_fetch_manager_records_lag_max` (consumer lag)
- [x] `balance_persistence_failures_total` — **só aparece depois de uma falha real**: um contador
      Micrometer não é publicado enquanto vale zero. Verificado por
      `MicrometerBalanceTelemetryTest` e por `DynamoDbBalanceWriterTest`, que exercitam os três
      modos de falha transitória. Não é um item que se possa marcar num ambiente saudável
- [x] `balance_dlq_published_total`
- [x] `balance_events_unsupported_total` com tag `reason` (FR-004a)
- [x] **Nenhuma métrica tem `accountId` ou `transactionId` como tag** — cardinalidade (verificado:
      zero ocorrências em `balance_*` na stack real)

Execução real de 2026-09-06, após os cenários acima:

```
balance_events_outcome_total{outcome="APPLIED",reason="NONE"}                                 2.0
balance_events_outcome_total{outcome="IGNORED_INELIGIBLE",reason="ACCOUNT_NOT_ENABLED"}       1.0
balance_events_outcome_total{outcome="IGNORED_INELIGIBLE",reason="TRANSACTION_NOT_APPROVED"}  1.0
balance_events_outcome_total{outcome="REJECTED",reason="DUPLICATE_EVENT"}                     1.0
balance_events_outcome_total{outcome="REJECTED",reason="STALE_EVENT"}                         1.0
balance_events_unsupported_total{reason="NO_TRANSACTION_BLOCK"}                               1.0
balance_dlq_published_total{anomaly="DLQ_PUBLISHED"}                                          1.0
```

---

## Checklist final contra os Success Criteria

| SC | Como validar | Cenário |
|---|---|---|
| ✅ SC-001 saldo final = maior timestamp em 100% das sequências | ordens embaralhadas para contas distintas | 7 |
| ✅ SC-002 inelegíveis/duplicados/antigos alteram saldo em 0% | contadores vs. saldo | 3, 4, 6 |
| ✅ SC-003 múltiplas instâncias, sem perda de atualização | teste de concorrência + `--scale app=3` | 10 |
| ✅ SC-004 100% dos eventos com desfecho rastreável | soma dos contadores = eventos produzidos | 10 |
| ✅ SC-005 consulta reflete o snapshot recém-aplicado | leitura consistente após publicar | 1, 2 |
| ✅ SC-006 p95 < 200 ms na consulta | `http_server_requests_seconds` | 12 |
| ⚠️ SC-007 ≥ 1.000 eventos/s sem lag sustentado | produzir em volume e observar o lag | 10 — **não medido**: exige um teste de carga sustentado, fora do escopo desta validação funcional |
| ✅ SC-008 mensagem ruim não bloqueia além da janela de retry | DLQ sem retry + offset avança | 8 |
| ✅ SC-009 zero erro de arredondamento | `amount` no round-trip completo | 1 |
| ✅ SC-010 reinício abrupto não altera saldo | `docker compose kill` | 11 |

---

### Resultado da execução de 2026-09-06

Validado contra a stack Docker completa (`docker compose up -d --scale app=3`), com DynamoDB Local e
Redpanda reais:

- Cenários 1 a 6, 8, 8b, 9, 11 e 12 executados manualmente — todos conforme o esperado.
- Cenário 7 e 10 cobertos por `ConditionalWriteConcurrencyIntegrationTest` (32 threads liberadas por
  barreira, 20 repetições) e, na stack real, por 100 eventos embaralhados na mesma conta com **três
  instâncias** consumindo em paralelo: as três respondem o snapshot de maior timestamp,
  `tx-1751800000100000` / `100000.00`.
- DLQ com **exatamente um** registro — o inválido, com `kafka_dlt-exception-message =
  'transaction.timestamp' is missing`. A mensagem de outro fluxo **não** está lá (FR-004a).
- `updated_at` renderizado como `2025-07-05T18:04:13.433-03:00`, idêntico à amostra do desafio.

**Nota de infraestrutura**: `docker-compose.yml` publicava o app em `8080:8080` fixo, o que faz
`--scale app=3` falhar com *port is already allocated*. A porta virou o range `8080-8083` (8081 é do
`redpanda-console`, e o Docker pula portas ocupadas dentro de um range). A primeira réplica continua
em 8080, então nada mais nesta página muda.

---

## Teardown

```bash
make stop
make clean-containers
```

---

## Nenhum bloqueador pendente

Os dois itens que estavam abertos foram fechados na sessão de clarificações de 2026-09-06
(ver [spec.md § Clarifications](./spec.md#clarifications)):

- **R-01** resolvido por **FR-008a**: status ausente é tratado como desconhecido — ambos tornam o evento
  **inelegível**, nunca inválido. O Cenário 6 caso 4 reflete essa regra.
- **R-02** resolvido — e **revisto em 2026-09-07**: a posição mista foi abandonada e o corpo da resposta
  passou a seguir a amostra do desafio integralmente (`id`, `owner`, `balance` com `amount` como número
  JSON, `updated_at`). `lastTransactionId` continua persistido, mas não é exposto. Os Cenários 1 e 9
  refletem essa forma.

Duas regras **novas** da sessão de 2026-09-06 têm cenários próprios: FR-004a (Cenário 8b) e o
truncamento de `updated_at` (Cenário 1). A sessão de **2026-09-07** acrescentou o **Cenário 8c**
(FR-003a — evento sem titular) e alterou a forma do corpo esperado nos Cenários 1, 2, 3, 5, 6, 7 e 9.
