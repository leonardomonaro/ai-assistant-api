# AI Assistant API

API REST de conversas com um assistente virtual, desenvolvida com Java 21 e Spring Boot. O projeto persiste o histórico no PostgreSQL, reutiliza mensagens anteriores como contexto e pode responder por meio da API da OpenAI ou por um modo simulado que não exige chave.

Projeto educacional de portfólio para praticar desenvolvimento backend e integração com IA conversacional. O foco é acompanhar o caminho de uma requisição HTTP até o banco e entender como o histórico ajuda a compor a próxima interação.

## Funcionalidades

- Criação, listagem, consulta e exclusão de conversas.
- Envio e consulta de mensagens.
- Persistência de conversas e mensagens no PostgreSQL.
- Histórico recente enviado à IA para manter o contexto.
- Integração real com o endpoint `chat/completions` da OpenAI.
- Modo `mock` para desenvolvimento sem chave de API.
- Limite de 4.000 caracteres por mensagem e 120 por título.
- Validação de entrada e respostas de erro padronizadas.
- Timeouts de conexão e leitura da API externa.
- Documentação interativa com Swagger/OpenAPI.
- Testes unitários e de integração dos endpoints.
- PostgreSQL pronto para uso com Docker Compose.

## Tecnologias

- Java 21
- Spring Boot 3.5
- Spring Web e RestClient
- Spring Data JPA / Hibernate
- Bean Validation
- PostgreSQL 17
- H2 apenas nos testes automatizados
- JUnit 5, Mockito e MockMvc
- Springdoc OpenAPI / Swagger UI
- Maven e Maven Wrapper
- Docker Compose

## Arquitetura

```text
HTTP request
    -> Controller (HTTP e validação)
    -> Service (regras e transação)
    -> Repository (persistência JPA)
    -> PostgreSQL

ConversationService
    -> AiClient
       -> SimulatedAiClient (padrão)
       -> OpenAiClient (AI_PROVIDER=openai)
```

Os pacotes têm responsabilidades separadas:

```text
src/main/java/com/leonardomonaro/aiassistant
├── ai          # contrato e implementações dos clientes de IA
├── config      # propriedades e configuração do OpenAPI
├── controller  # endpoints REST
├── dto         # objetos de entrada e saída da API
├── entity      # entidades persistidas pelo JPA
├── exception   # exceções e tratamento global
├── repository  # acesso ao banco de dados
└── service     # regras de negócio e transações
```

O diretório `src/main/resources` contém a configuração, `src/test` reúne os testes e `postman` contém a coleção de requisições. Na raiz ficam o `pom.xml`, o Maven Wrapper e o `compose.yaml`.

### Decisões técnicas

- DTOs definem o contrato HTTP. As entidades JPA ficam na camada de persistência.
- Uma transação grava a pergunta e a resposta juntas. Um bloqueio na linha da conversa coordena envios e exclusões simultâneos daquela conversa. O bloqueio fica ativo durante a geração da resposta, então outro envio para a mesma conversa pode esperar.
- O banco guarda todo o histórico. A geração usa apenas as mensagens mais recentes, incluindo a nova pergunta. O prompt de sistema é acrescentado pelo cliente OpenAI fora desse limite.
- `AiClient` permite alternar entre a resposta simulada e a chamada HTTP sem alterar os endpoints. O modo simulado ecoa a pergunta mais recente para demonstrar o fluxo.
- O esquema é criado e atualizado pelo Hibernate no ambiente de estudo. A API e o PostgreSQL escutam em loopback por padrão.

## Pré-requisitos

- JDK 21
- Docker com Docker Compose

O Maven Wrapper baixa a versão usada pelo projeto. No Windows, use `.\mvnw.cmd` nos comandos Maven.

## Configuração

Clone o repositório e copie o exemplo de configuração local.

```bash
git clone https://github.com/leonardomonaro/ai-assistant-api.git
cd ai-assistant-api
cp .env.example .env
```

No PowerShell, use `Copy-Item .env.example .env`. Abra o `.env` na raiz e preencha `POSTGRES_PASSWORD` com uma senha própria e exclusiva para o banco local antes de iniciar o Compose. Use uma sequência aleatória de letras e números para manter a leitura do arquivo simples no Compose e no Spring. Mantenha `AI_PROVIDER=mock` e deixe `AI_API_KEY` vazio no modo simulado.

O Compose lê o `.env` e o perfil `dev` importa o mesmo arquivo. Use linhas no formato `CHAVE=valor`. Variáveis exportadas no ambiente têm precedência. O Git ignora o `.env` e suas variantes locais. O repositório inclui apenas o modelo `.env.example`.

Principais variáveis:

| Variável | Padrão | Descrição |
|---|---|---|
| `AI_PROVIDER` | `mock` | Use `mock` ou `openai` |
| `AI_API_KEY` | vazio | Chave exigida apenas no modo `openai` |
| `AI_MODEL` | `gpt-4o-mini` | Modelo enviado ao provedor |
| `AI_BASE_URL` | `https://api.openai.com` | Origem confiável do provedor, sem caminho. Use HTTPS. HTTP em loopback é aceito para testes locais |
| `AI_CONNECT_TIMEOUT` | `5s` | Timeout para conectar ao provedor |
| `AI_READ_TIMEOUT` | `30s` | Timeout para aguardar a resposta |
| `AI_MAX_CONTEXT_MESSAGES` | `20` | Janela entre 1 e 100 mensagens, incluindo a pergunta atual |
| `AI_MAX_COMPLETION_TOKENS` | `512` | Limite de geração enviado ao provedor, entre 1 e 4.096 |
| `AI_SYSTEM_PROMPT` | instrução padrão de assistência | Instrução de sistema enviada ao provedor |
| `POSTGRES_DB` | `ai_assistant` | Nome do banco local |
| `POSTGRES_USER` | `ai_assistant` | Usuário local |
| `POSTGRES_PASSWORD` | sem padrão | Senha obrigatória do banco local, definida por você |
| `POSTGRES_PORT` | `5432` | Porta exposta no host |
| `DATABASE_URL` | URL JDBC montada com porta e nome do banco local | Sobrescreve a URL de conexão |
| `JPA_DDL_AUTO` | `update` | Estratégia de esquema do Hibernate. Use `validate` com um esquema já provisionado |

## Execução local no modo simulado

Inicie o PostgreSQL:

```bash
docker compose up -d --wait postgres
```

Inicie a aplicação com o perfil de desenvolvimento:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

A API ficará disponível em `http://localhost:8090`. Confira a saúde da aplicação em `http://localhost:8090/actuator/health`.

```bash
curl --fail http://localhost:8090/actuator/health
curl --fail http://localhost:8090/conversations
```

Para encerrar o banco sem apagar os dados:

```bash
docker compose stop postgres
```

O PostgreSQL usa as credenciais do `.env` na primeira inicialização do volume. Se você mudar usuário, senha ou nome do banco depois, ajuste o banco existente ou use um volume novo. Se a porta 5432 estiver ocupada, defina outra em `POSTGRES_PORT` antes de iniciar o Compose e a aplicação.

## Integração real com OpenAI

Defina `AI_PROVIDER=openai` e preencha `AI_API_KEY` no `.env` local. Use uma chave de um projeto da OpenAI com acesso ao modelo escolhido. Mantenha o arquivo fora do Git e reinicie a aplicação com o mesmo comando usado no modo simulado.

O modo `openai` envia o prompt de sistema e o conteúdo recente da conversa para `POST /v1/chat/completions`. Use dados que possam ser compartilhados com o provedor. Uma chave ausente impede a inicialização nesse modo. Uma falha de conexão, resposta HTTP de erro ou resposta inválida retorna `502 Bad Gateway`. O tempo limite de conexão ou leitura retorna `504 Gateway Timeout`. Em ambos os casos, a interação fica sem novas mensagens persistidas.

Os testes automatizados usam o modo simulado e um servidor HTTP local para verificar o contrato do cliente OpenAI. Uma chamada real requer chave válida, faturamento configurado e acesso externo.

## Endpoints

| Método | Endpoint | Resultado |
|---|---|---|
| `POST` | `/conversations` | Cria uma conversa (`201`) |
| `GET` | `/conversations` | Lista conversas (`200`) |
| `GET` | `/conversations/{id}` | Retorna conversa e histórico (`200`) |
| `POST` | `/conversations/{id}/messages` | Salva a pergunta e gera a resposta (`201`) |
| `GET` | `/conversations/{id}/messages` | Lista as mensagens (`200`) |
| `DELETE` | `/conversations/{id}` | Exclui conversa e mensagens (`204`) |

Use um UUID em `{id}`. Um UUID inválido ou um corpo inválido retorna `400`. Uma conversa inexistente retorna `404`. Envie os corpos como `application/json`. A listagem de conversas mostra primeiro as mais recentemente atualizadas e o histórico é retornado da mensagem mais antiga para a mais recente.

### Criar uma conversa

O corpo é opcional. Sem título, a API usa `New conversation`.

```bash
curl -i -X POST http://localhost:8090/conversations \
  -H 'Content-Type: application/json' \
  -d '{"title":"Estudos de Java"}'
```

Resposta resumida:

```json
{
  "id": "f54e522d-52e2-4cd3-a6b5-b2b570a72a07",
  "title": "Estudos de Java",
  "createdAt": "2026-11-25T16:00:00Z",
  "updatedAt": "2026-11-25T16:00:00Z"
}
```

### Enviar uma mensagem

Substitua o UUID de exemplo pelo `id` retornado ao criar a sua conversa.

```bash
curl -i -X POST http://localhost:8090/conversations/f54e522d-52e2-4cd3-a6b5-b2b570a72a07/messages \
  -H 'Content-Type: application/json' \
  -d '{"content":"Explique injeção de dependências."}'
```

A resposta contém as duas mensagens persistidas:

```json
{
  "userMessage": {
    "id": "84a417f2-fd01-48eb-a263-2b84fb6f991a",
    "role": "USER",
    "content": "Explique injeção de dependências.",
    "createdAt": "2026-11-25T16:01:00Z"
  },
  "assistantMessage": {
    "id": "bed29bd8-48b8-4603-a8e9-22798527b495",
    "role": "ASSISTANT",
    "content": "This is a simulated response to: \"Explique injeção de dependências.\"",
    "createdAt": "2026-11-25T16:01:00Z"
  }
}
```

### Erro de validação

```json
{
  "timestamp": "2026-11-25T16:02:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/conversations/f54e522d-52e2-4cd3-a6b5-b2b570a72a07/messages",
  "validationErrors": {
    "content": "content is required"
  }
}
```

## Swagger e Postman

Com a aplicação em execução:

- Swagger UI: `http://localhost:8090/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8090/v3/api-docs`
- Collection: `postman/AI-Assistant-API.postman_collection.json`

Importe a collection no Postman, execute primeiro **Create conversation** e as demais requisições reutilizarão automaticamente o `conversationId` criado.

## Testes e build

Execute todos os testes:

```bash
./mvnw clean test
```

Gere o JAR:

```bash
./mvnw clean package
```

Os testes usam H2 e um servidor HTTP local. Eles cobrem o ciclo dos endpoints, validação, exclusão, histórico, janela de contexto, falhas do provedor e timeout. Para validar a aplicação com PostgreSQL, inicie o Compose, execute o perfil `dev` e percorra o fluxo da coleção Postman no modo simulado.

Com o banco em execução, o JAR também pode ser iniciado a partir da raiz do projeto.

```bash
java -jar target/ai-assistant-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

## Limitações atuais

- O projeto é voltado a estudos e execução local. A aplicação não possui autenticação, autorização, paginação ou limitação de requisições. O gerenciamento do esquema usa o Hibernate, sem migrações versionadas.
- Envios e exclusões da mesma conversa aguardam a geração em andamento. O tempo de resposta no modo `openai` depende do provedor.
- A suíte verifica o cliente OpenAI com um servidor local. A validação ponta a ponta com a OpenAI depende de uma chave válida e de acesso ao serviço.
- O Spring MVC 6.2.19 continua na aplicação. Na verificação de 25 de novembro de 2026, o Maven Central não oferecia uma atualização na linha 6.2 que corrigisse os dois avisos restantes. Os avisos envolvem SSE com fragmentos de visualização e `XsltView`, recursos que a aplicação não utiliza. A correção pública começa no Spring Framework 7.0.9 e requer migração para o Spring Boot 4. [CVE-2026-47890](https://github.com/advisories/GHSA-j9f9-w8pj-32f8) · [CVE-2026-47884](https://github.com/advisories/GHSA-pc63-qcmh-9cmg)

## Material de estudo

O arquivo [STUDY_GUIDE.md](STUDY_GUIDE.md) explica as classes, anotações, dependências e o fluxo interno da aplicação em detalhes.

## Licença

Distribuído sob a licença MIT. Consulte [LICENSE](LICENSE).
