# Contrato da DLQ — `transactions-events.dlq`

**Feature**: `002-core-banking-balance` | **Plan**: [../plan.md](../plan.md)

Requisitos cobertos: FR-038, FR-039, FR-040, INV-008, Constitution XI.

---

## Quando uma mensagem chega aqui

Apenas dois caminhos produzem uma mensagem na DLQ. Ambos correspondem a linhas da matriz de decisão da
specification.

| Origem | Retry antes? | `x-failure-reason` | Matriz |
|---|---|---|---|
| Mensagem inválida (não interpretável, campo obrigatório ausente, moeda inválida, escala monetária > 2 casas, `accountId` fora do formato) | **Não** — zero tentativas | `INVALID_MESSAGE` | linha 6 |
| Falha transitória que esgotou a política de retry (4 tentativas) | Sim — 4 tentativas com backoff | `PERMANENT_FAILURE` | linha 8 |

**Não** vão para a DLQ: eventos inelegíveis (`DECLINED` / `DISABLED` / status desconhecido), duplicados,
antigos e empatados. Todos são desfechos de **sucesso** não-mutantes — ignorados de forma observável,
com offset confirmado (FR-012, FR-022).

---

## Chave e valor

| Campo | Conteúdo |
|---|---|
| **Key** | a chave original do registro, preservada sem alteração (normalmente `account.id`) |
| **Value** | o payload original, **byte a byte**, sem reserialização |

A preservação literal do payload (FR-039) é a razão pela qual o consumer recebe `String` em vez de deixar
o Kafka desserializar: o original permanece disponível dentro do listener mesmo quando o parse falha.
Isso torna a mensagem da DLQ diretamente reinjetável no tópico de origem.

---

## Headers

### Anexados automaticamente pelo `DeadLetterPublishingRecoverer`

| Header | Conteúdo | Requisito |
|---|---|---|
| `kafka_dlt-original-topic` | tópico de origem | FR-039 (origem) |
| `kafka_dlt-original-partition` | partição de origem | FR-039 (origem) |
| `kafka_dlt-original-offset` | offset de origem | FR-039 (origem) |
| `kafka_dlt-original-timestamp` | timestamp do registro original | FR-039 (instante) |
| `kafka_dlt-original-timestamp-type` | tipo do timestamp | — |
| `kafka_dlt-exception-fqcn` | classe da exceção | FR-039 (motivo) |
| `kafka_dlt-exception-message` | mensagem da exceção — **nomeia o campo que invalidou** | FR-039 (motivo) |
| `kafka_dlt-exception-stacktrace` | stack trace | diagnóstico |

### Acrescentados por este serviço

| Header | Valores | Por quê |
|---|---|---|
| `x-failure-reason` | `INVALID_MESSAGE` \| `PERMANENT_FAILURE` | Classificação estável e legível por máquina, independente do nome da classe de exceção — que pode mudar em refatoração. |
| `x-correlation-id` | correlation id do processamento | Liga a mensagem da DLQ aos logs estruturados do processamento que falhou. |

O mapper lança `UnprocessableEventException` já carregando o motivo textual do campo faltante. Esse texto
vira `kafka_dlt-exception-message`, então quem inspeciona a DLQ vê **qual campo** invalidou a mensagem,
não apenas que ela falhou.

---

## Particionamento

A DLQ é endereçada com `TopicPartition(dlqTopic, -1)`. O `-1` faz o produtor escolher a partição por hash
da chave, então a DLQ preserva o agrupamento por conta.

O comportamento padrão do recoverer — reusar o número da partição de origem — foi rejeitado
deliberadamente: ele acopla a contagem de partições da DLQ à do tópico de entrada, e uma DLQ com menos
partições passa a falhar na publicação, o que transformaria uma mensagem ruim em uma partição travada.

---

## Falha ao publicar na DLQ

Linha 9 da matriz, FR-040: **o offset não é confirmado**.

O recoverer lança, o container faz `seek` e a mensagem é reentregue. Ela nunca é descartada. É a única
situação em que uma mensagem pode circular indefinidamente — e é o comportamento correto: perder uma
mensagem seria pior do que reprocessá-la (Constitution I). O contador `balance.dlq.failures` torna a
situação visível, e a métrica de consumer lag mostra o efeito.

---

## Reprocessamento

Fora de escopo nesta versão (spec *Out of Scope*): a DLQ é um destino **observável**, e a reinjeção é
operação manual. Como chave e valor são preservados literalmente, a reinjeção é uma cópia direta para o
tópico de origem — nenhuma transformação necessária.

Inspeção local:

```bash
make kafka-consume TOPIC=transactions-events.dlq
```

---

## Exemplo

**Mensagem original** (campo `account.balance.currency` ausente):

```json
{"transaction":{"id":"tx-001","status":"APPROVED","timestamp":1751641364589998},
 "account":{"id":"acc-12345","status":"ENABLED","balance":{"amount":100.00}}}
```

**Registro na DLQ**:

```
key:   acc-12345
value: {"transaction":{"id":"tx-001",...}}          ← idêntico ao original
headers:
  kafka_dlt-original-topic     = transactions-events
  kafka_dlt-original-partition = 1
  kafka_dlt-original-offset    = 4711
  kafka_dlt-exception-fqcn     = ...kafka.exception.UnprocessableEventException
  kafka_dlt-exception-message  = missing required field: account.balance.currency
  x-failure-reason             = INVALID_MESSAGE
  x-correlation-id             = 0f0d1c2e-...
```
