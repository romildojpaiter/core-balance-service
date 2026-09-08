# PROJECT DISCOVERY — CORE BANKING SERVICE

Você está trabalhando no projeto `core-banking-service`.

Antes de modificar qualquer código, faça uma análise completa do repositório atual e construa uma visão técnica precisa do projeto.

## Objetivo

Conhecer o estado atual do projeto, sua arquitetura, infraestrutura, tecnologias, convenções, testes e restrições antes de iniciar a especificação orientada por SDD.

## Regra fundamental

NÃO implemente funcionalidades nesta etapa.

NÃO altere arquivos.

NÃO crie código.

NÃO substitua decisões existentes por preferências próprias.

Primeiro compreenda o projeto.

## Analise obrigatoriamente

### 1. Estrutura do projeto

Identifique:

* módulos;
* packages;
* camadas;
* adapters;
* ports;
* domínio;
* application services;
* infraestrutura;
* testes unitários;
* testes de integração;
* configurações;
* scripts;
* Docker;
* Makefile;
* CI/CD.

### 2. Stack tecnológica

Identifique as versões e tecnologias efetivamente utilizadas no projeto, incluindo:

* Kotlin;
* Java;
* Spring Boot;
* Spring Framework;
* Gradle;
* Kafka;
* DynamoDB;
* AWS SDK;
* Docker;
* Redpanda;
* JUnit;
* Mockito;
* MockMvc;
* JaCoCo;
* Konsist.

Não assuma versões. Leia os arquivos de configuração.

### 3. Arquitetura

Determine como a arquitetura hexagonal está organizada atualmente.

Identifique:

* domínio;
* input ports;
* output ports;
* application services;
* input adapters;
* output adapters;
* dependências entre camadas;
* regras arquiteturais existentes.

Verifique também os testes de arquitetura existentes.

### 4. Infraestrutura

Analise:

* Dockerfile;
* docker-compose;
* DynamoDB Local;
* Redpanda;
* Kafka;
* criação de tópicos;
* seed de dados;
* scripts;
* health checks;
* variáveis de ambiente;
* configurações de desenvolvimento.

Preserve a infraestrutura existente sempre que ela for compatível com a solução.

### 5. Kafka

Identifique:

* configuração do producer;
* configuração do consumer;
* tópicos;
* partitions;
* consumer groups;
* serialization/deserialization;
* retry;
* tratamento de erro;
* offset management.

Avalie como o sistema deverá utilizar `accountId` como chave de particionamento para preservar ordenação por conta.

### 6. DynamoDB

Analise:

* configuração;
* client;
* repositories existentes;
* tabelas;
* partition keys;
* atributos;
* operações disponíveis;
* índices;
* seed.

Para o domínio de saldo, considere como hipótese inicial:

PK = `ACCOUNT#{accountId}`

Mas NÃO implemente nem formalize essa decisão definitivamente nesta etapa.

### 7. Challenge

Leia todos os documentos disponíveis no projeto que descrevam o desafio.

Extraia:

* requisitos funcionais;
* requisitos não funcionais;
* formato dos eventos;
* campos;
* status;
* comportamento esperado;
* API;
* critérios de avaliação;
* restrições.

### 8. Eventos financeiros

Identifique o formato real dos eventos.

Especial atenção para:

* transaction.id;
* transaction.status;
* transaction.amount;
* account.id;
* account.status;
* account.balance;
* account.balance.currency;
* timestamp.

### 9. Problemas de consistência

Identifique os mecanismos necessários para tratar:

* eventos duplicados;
* eventos fora de ordem;
* processamento concorrente;
* retries;
* mensagens entregues novamente;
* falhas no DynamoDB;
* falhas no Kafka;
* mensagens inválidas.

### 10. Código existente

Identifique o que é:

* código de exemplo;
* código de infraestrutura;
* código reutilizável;
* código que deve ser removido;
* código que deve ser preservado.

O exemplo `hello` não deve fazer parte da implementação final do domínio de banking.

## Decisões já levantadas

Considere como contexto inicial, mas valide contra os arquivos do projeto:

* uma aplicação inicialmente é suficiente;
* REST expõe consulta de saldo;
* Kafka recebe eventos financeiros;
* DynamoDB mantém o estado corrente;
* Kafka deve utilizar `accountId` como chave;
* saldo recebido no evento é tratado como snapshot;
* não devemos recalcular o saldo a partir do amount;
* `BigDecimal` deve ser utilizado para valores monetários;
* eventos devem ser processados de forma idempotente;
* eventos fora de ordem não podem sobrescrever um estado mais recente;
* DynamoDB deve utilizar conditional writes para garantir atomicidade;
* `transaction.status = APPROVED` é requisito para atualização;
* `account.status = ENABLED` é requisito para atualização;
* eventos DECLINED/DISABLED devem ser ignorados com observabilidade adequada;
* offsets Kafka somente devem ser confirmados após processamento bem-sucedido;
* erros permanentes devem possuir estratégia de DLQ;
* retries devem contemplar falhas transitórias;
* locks distribuídos e `synchronized` não devem ser utilizados para resolver concorrência;
* Redis, Elasticsearch, CQRS, Event Sourcing, Saga e outros componentes não devem ser adicionados sem necessidade explícita.

## Resultado esperado

Ao final, produza um relatório técnico chamado:

`PROJECT_DISCOVERY.md`

O documento deve conter:

1. Current Architecture
2. Technology Stack
3. Repository Structure
4. Existing Infrastructure
5. Kafka Architecture
6. DynamoDB Architecture
7. Existing Tests
8. Challenge Requirements
9. Existing Constraints
10. Architectural Risks
11. Open Questions
12. Initial Assumptions
13. Recommended SDD Scope

Não faça implementação.

A próxima etapa será o `/constitution`.
