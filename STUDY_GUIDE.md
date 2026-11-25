# Guia de estudos — AI Assistant API

Este guia explica como a aplicação foi organizada, quais problemas cada classe resolve e como uma requisição percorre o sistema. Leia junto com o código e use os testes para experimentar alterações com segurança.

## 1. Visão geral

A aplicação oferece uma API HTTP para criar conversas e trocar mensagens com um assistente. Cada conversa e mensagem é salva no banco. Ao receber uma nova pergunta, o serviço recupera as mensagens mais recentes, organiza o histórico em ordem cronológica e o entrega a uma implementação de `AiClient`.

Existem duas implementações desse contrato:

- `SimulatedAiClient`: produz uma resposta local e previsível. É o padrão e não usa internet ou chave.
- `OpenAiClient`: chama de verdade o endpoint de chat da OpenAI com autenticação Bearer.

Essa separação é um exemplo do princípio de inversão de dependência: a regra de negócio conhece a interface `AiClient`, mas não depende diretamente de uma empresa ou SDK específico.

## 2. Dependências do Maven

O arquivo `pom.xml` descreve o projeto e suas dependências.

- `spring-boot-starter-web`: cria endpoints REST, serializa JSON e fornece o `RestClient` usado na chamada externa.
- `spring-boot-starter-data-jpa`: adiciona JPA, Hibernate, transações e repositórios.
- `spring-boot-starter-validation`: disponibiliza Bean Validation, como `@NotBlank` e `@Size`.
- `spring-boot-starter-actuator`: expõe informações operacionais, como `/actuator/health`.
- `postgresql`: driver JDBC usado em execução normal.
- `springdoc-openapi-starter-webmvc-ui`: gera o documento OpenAPI e a tela Swagger UI.
- `spring-boot-starter-test`: reúne JUnit 5, AssertJ, Mockito, Spring Test e MockMvc.
- `h2`: banco em memória usado apenas durante testes, sem substituir o PostgreSQL da aplicação.

O `spring-boot-maven-plugin` empacota a aplicação como um JAR executável. A propriedade `java.version` faz o compilador usar Java 21.

## 3. Classe de inicialização

### `AiAssistantApiApplication`

`@SpringBootApplication` reúne três ideias:

1. Marca uma classe de configuração do Spring.
2. Ativa configurações automáticas de acordo com as dependências presentes.
3. Procura componentes nos pacotes abaixo de `com.leonardomonaro.aiassistant`.

`@ConfigurationPropertiesScan` procura classes como `AiProperties` e permite transformar propriedades textuais em um objeto Java tipado.

O método `main` chama `SpringApplication.run`, que cria o contexto do Spring, configura o servidor HTTP, o DataSource, o JPA e todos os componentes.

## 4. Configuração

### `application.properties`

O arquivo define os padrões para desenvolvimento local.

- conexão com o PostgreSQL;
- `open-in-view=false`, para evitar consultas JPA inesperadas na camada web;
- atualização automática do esquema em ambiente de estudo;
- provedor de IA `mock` por padrão;
- modelo, URL, prompt, timeouts, tamanho do contexto e limite de geração;
- caminhos do Swagger e do OpenAPI;
- endpoints permitidos do Actuator.

A sintaxe `${AI_PROVIDER:mock}` significa: use a variável `AI_PROVIDER`; se ela não existir, use `mock`.

`application-dev.properties` seleciona a porta 8090 e importa o arquivo local `.env`. Um perfil é um conjunto de configurações ativado pelo nome. `application-test.properties` usa H2 em memória e cria o esquema no início do contexto de teste.

### `AiProperties`

É um `record` anotado com `@ConfigurationProperties(prefix = "app.ai")`. O Spring associa, por exemplo, `app.ai.read-timeout` ao componente `readTimeout`.

Um `record` é adequado aqui porque as configurações são imutáveis depois da criação. `Duration` permite valores legíveis como `5s` e `30s`. Com `@Validated`, o Spring pode interromper a inicialização se um limite estiver fora da faixa permitida. A chave é lida separadamente pelo cliente OpenAI. Isso evita incluí-la no texto gerado pelo método `toString()` do record.

### `OpenApiConfig`

`@Configuration` indica uma classe que declara objetos gerenciados pelo Spring. O método anotado com `@Bean` cria o objeto `OpenAPI`, que adiciona título, versão, contato e licença à documentação.

### `JsonConfig`

O Spring usa Jackson para ler e escrever JSON. Esta configuração faz Jackson rejeitar números e valores booleanos em campos de texto. Por exemplo, o cliente precisa enviar `{"content":"123"}` se quiser que a mensagem seja o texto `123`.

## 5. Entidades e banco de dados

### `Conversation`

`@Entity` transforma a classe em uma entidade JPA e `@Table` define a tabela `conversations`.

Campos importantes:

- `id`: UUID gerado pelo Hibernate com `@GeneratedValue`.
- `title`: obrigatório e limitado a 120 caracteres no banco.
- `createdAt`: instante da criação, não pode ser atualizado.
- `updatedAt`: instante da última interação.

`@PrePersist` executa `initializeTimestamps` antes do primeiro `INSERT`. Depois de cada interação, `touch` marca o instante da resposta como a última atualização da conversa.

O construtor vazio é exigido pelo JPA. Ele é `protected` para desencorajar o uso incorreto sem impedir o Hibernate de utilizá-lo.

### `Message`

Representa uma linha da tabela `messages`.

`@ManyToOne(fetch = FetchType.LAZY)` cria a relação: várias mensagens pertencem a uma conversa. `LAZY` evita carregar a conversa inteira quando isso não é necessário.

`@JoinColumn(name = "conversation_id")` define a coluna de chave estrangeira. O índice composto por `conversation_id` e `created_at` acelera a recuperação ordenada do histórico.

`role` usa `@Enumerated(EnumType.STRING)`, portanto o banco armazena `USER` ou `ASSISTANT`, e não números frágeis dependentes da ordem do enum.

`content` usa `TEXT`, pois respostas do assistente podem ser maiores que um `VARCHAR` comum. A entrada do usuário continua limitada pelo DTO. O serviço atribui `createdAt` com precisão de microssegundos e garante que a próxima mensagem da conversa tenha um horário posterior ao da anterior. Isso evita ambiguidades de ordenação quando duas mensagens são criadas rapidamente.

### `MessageRole`

Enumera os dois papéis aceitos pelo domínio. Usar um enum evita textos inválidos ou variações como `user`, `User` e `usuario` dentro do banco.

## 6. Repositórios

### `ConversationRepository`

Estende `JpaRepository<Conversation, UUID>`. Com isso, Spring Data implementa métodos como `save`, `findById`, `findAll` e `delete` sem que seja necessário escrever SQL.

`findAllByOrderByUpdatedAtDescIdAsc` é uma consulta derivada do nome do método. Ela lista primeiro as conversas mais recentemente atualizadas e usa o UUID como desempate. `findByIdForUpdate` usa `@Query` para declarar uma consulta JPQL, que se refere à entidade e a seus campos. `@Lock(PESSIMISTIC_WRITE)` pede ao banco que bloqueie a linha enquanto a transação estiver aberta.

### `MessageRepository`

Também estende `JpaRepository` e contém três operações específicas:

- buscar todas as mensagens de uma conversa em ordem cronológica;
- buscar uma página de mensagens com ordenação escolhida pelo serviço;
- excluir as mensagens pertencentes a uma conversa em uma instrução de banco, usando `@Modifying` e `@Query`.

O parâmetro `Pageable` permite limitar o contexto enviado à IA. Isso impede que uma conversa muito longa cresça indefinidamente em cada requisição externa.

## 7. DTOs e validação

DTO significa Data Transfer Object. Esses objetos descrevem os dados aceitos ou devolvidos pela API sem expor diretamente as entidades JPA.

### DTOs de entrada

- `CreateConversationRequest`: recebe um título opcional com até 120 caracteres.
- `SendMessageRequest`: exige conteúdo não vazio com até 4.000 caracteres.

`@NotBlank` rejeita `null`, texto vazio e texto composto apenas por espaços. `@Size` controla o comprimento. `@Pattern` impede caracteres nulos, que o PostgreSQL rejeita em campos de texto. No controller, `@Valid` pede ao Spring que execute essas regras antes de chamar o serviço.

### DTOs de saída

- `ConversationResponse`: resumo de uma conversa.
- `ConversationDetailResponse`: conversa acompanhada de suas mensagens.
- `MessageResponse`: representação pública de uma mensagem.
- `SendMessageResponse`: reúne a mensagem do usuário e a resposta do assistente.

Todos são `record`s: objetos imutáveis, concisos e adequados para respostas JSON.

## 8. Integração com inteligência artificial

### `AiClient`

É a interface que a regra de negócio utiliza. Ela recebe uma lista de `AiMessage` e devolve o texto da resposta. Para adicionar outro provedor no futuro, crie uma nova implementação sem alterar o `ConversationService`.

### `AiMessage`

É o modelo pequeno compartilhado entre o serviço e os clientes. Ele não carrega detalhes de JPA nem IDs desnecessários para o provedor.

### `SimulatedAiClient`

`@Component` registra a classe no Spring. `@ConditionalOnProperty` só a registra quando `app.ai.provider=mock` ou quando a propriedade está ausente.

Ela localiza a mensagem mais recente do usuário e devolve uma resposta determinística. Isso permite executar a aplicação e testar todo o fluxo sem custo, chave ou dependência externa.

### `OpenAiClient`

É registrado apenas quando `app.ai.provider=openai`.

No construtor:

1. Lê a chave injetada por `@Value` e verifica se ela existe.
2. Valida a origem do serviço e configura timeouts de conexão e leitura em `SimpleClientHttpRequestFactory`.
3. Cria um `RestClient` com URL base e cabeçalho Bearer e desativa redirecionamentos no transporte.

No método `generateResponse`:

1. adiciona a mensagem de sistema;
2. converte `USER` e `ASSISTANT` para os papéis esperados pela API;
3. cria o corpo com modelo, mensagens e limite de geração;
4. faz `POST /v1/chat/completions`;
5. extrai o conteúdo da primeira escolha;
6. converte falhas técnicas em exceções compreensíveis para a aplicação.

Os records privados representam apenas os campos necessários do JSON externo. Isso evita adicionar um SDK e mantém a integração visível para fins didáticos.

Um timeout de conexão ou leitura vira `AiTimeoutException`. Uma resposta inválida, um erro HTTP do provedor ou outro problema de conexão vira `AiServiceException`. A resposta de erro da nossa API contém uma mensagem genérica sobre a falha externa, sem o corpo original ou a chave. No modo OpenAI, o provedor recebe o texto da conversa usado como contexto.

## 9. Regra de negócio

### `ConversationService`

`@Service` marca a classe como componente de negócio. Todas as dependências chegam pelo construtor, o que deixa a classe testável e explicita o que ela precisa para funcionar.

Métodos somente de leitura usam `@Transactional(readOnly = true)`. Métodos que salvam ou excluem usam `@Transactional`, que agrupa operações em uma unidade de trabalho. A conversa é bloqueada durante o envio ou a exclusão. Se dois envios ocorrerem ao mesmo tempo, o segundo aguarda o primeiro e lê o histórico atualizado. Se o provedor falhar, a transação termina sem gravar a pergunta. Se o banco falhar ao gravar qualquer parte da interação, a transação reverte as duas mensagens. Manter o bloqueio durante a chamada HTTP simplifica essa coordenação, com o custo de esperar pela resposta antes de liberar a conversa.

Responsabilidades dos métodos:

- `createConversation`: normaliza o título e salva a conversa.
- `listConversations`: lista em ordem de atualização.
- `getConversation`: busca a conversa e todo o histórico.
- `listMessages`: valida a existência da conversa e lista suas mensagens.
- `sendMessage`: bloqueia a conversa, monta o contexto, chama a IA e grava a pergunta e a resposta juntas.
- `deleteConversation`: exclui primeiro as mensagens e depois a conversa.

O método `loadContext` reserva uma posição da janela para a pergunta atual. Busca as interações anteriores da mais recente para a mais antiga para aplicar o limite no banco, inverte essa parte e acrescenta a pergunta ao final. Uma janela de tamanho 1 usa apenas a pergunta atual. A janela limita os dados enviados ao provedor e não altera o histórico armazenado.

## 10. Controller e HTTP

### `ConversationController`

`@RestController` combina controller MVC e serialização automática do retorno como JSON. `@RequestMapping("/conversations")` define o prefixo comum.

Principais anotações:

- `@PostMapping`, `@GetMapping` e `@DeleteMapping`: associam método Java a método e rota HTTP.
- `@PathVariable`: transforma o trecho `{id}` em UUID.
- `@RequestBody`: converte JSON no DTO.
- `@Valid`: executa Bean Validation.
- `@Operation` e `@ApiResponse`: enriquecem a documentação do Swagger.

`ResponseEntity` é usado quando é necessário controlar status e cabeçalhos. A criação retorna `201 Created` e `Location`; a exclusão retorna `204 No Content`. Consultas retornam `200 OK` automaticamente.

## 11. Tratamento de exceções

### Exceções de domínio e integração

- `ConversationNotFoundException`: conversa inexistente.
- `AiServiceException`: erro do provedor.
- `AiTimeoutException`: tempo limite de conexão ou leitura excedido.

### `GlobalExceptionHandler`

`@RestControllerAdvice` centraliza as falhas dos controllers e serviços. `ResponseEntityExceptionHandler` preserva os códigos e cabeçalhos que o Spring MVC atribui aos próprios erros. Os métodos com `@ExceptionHandler` cuidam das falhas específicas da aplicação.

- conversa ausente: 404;
- validação, JSON inválido ou UUID inválido: 400;
- método ou conteúdo não suportado: 405 ou 415;
- falha do provedor: 502;
- timeout externo: 504;
- erro inesperado: 500, com mensagem genérica e tipo de erro registrado no log.

`ApiError` mantém o formato consistente com horário, status, mensagem, caminho e mapa de erros de campos.

## 12. Fluxo completo de envio de mensagem

Considere `POST /conversations/{id}/messages`:

1. O servidor converte `{id}` em UUID e o JSON em `SendMessageRequest`.
2. Bean Validation rejeita conteúdo vazio ou maior que 4.000 caracteres.
3. O controller chama `ConversationService.sendMessage`.
4. O serviço busca a conversa e bloqueia sua linha até o fim da transação.
5. O repositório busca as mensagens anteriores, reservando uma posição na janela para a pergunta atual.
6. O serviço ordena o histórico, converte as mensagens em `AiMessage` e acrescenta a pergunta.
7. O `AiClient` ativo gera a resposta simulada ou chama a OpenAI.
8. O banco recebe as mensagens `USER` e `ASSISTANT` na mesma transação.
9. `updatedAt` da conversa é atualizado.
10. A transação é confirmada e as duas mensagens são retornadas com status 201.

## 13. Testes

### `ConversationApiIntegrationTest`

`@SpringBootTest` inicia o contexto completo. `@AutoConfigureMockMvc` cria o `MockMvc`, que simula requisições HTTP sem abrir uma porta de rede. `@ActiveProfiles("test")` seleciona H2 e o provedor simulado.

Os testes verificam criação, envio, histórico, listagem, exclusão, entradas inválidas, os códigos HTTP e a documentação OpenAPI. Repositórios reais e Hibernate são usados contra o H2. O teste observa o cliente simulado para conferir o histórico recebido e simula falhas para verificar que o envio não deixa mensagens parciais.

### `ConversationServiceTest`

`@ExtendWith(MockitoExtension.class)` ativa Mockito. As dependências são objetos de teste, então o teste controla o histórico devolvido pelo repositório. Um `ArgumentCaptor` inspeciona o tamanho e a ordenação da consulta. A verificação do `AiClient` confirma a sequência recebida e o comportamento quando a janela tem tamanho 1.

### `OpenAiClientTest`

Um servidor HTTP local recebe a requisição e devolve respostas controladas. Os testes conferem autenticação com uma chave fictícia, ordem do contexto, limite de geração, JSON malformado, redirecionamento, erro HTTP e timeout de leitura. O exercício valida o protocolo implementado sem enviar dados para a OpenAI.

## 14. Docker Compose e Maven Wrapper

`compose.yaml` inicia um PostgreSQL com volume persistente e healthcheck. Copie `.env.example` para `.env` e defina sua própria `POSTGRES_PASSWORD` antes do primeiro uso. A aplicação lê a mesma configuração no perfil `dev`. O Git ignora o arquivo que contém a sua senha.

`mvnw` e `mvnw.cmd` permitem usar a versão definida do Maven em Linux/macOS ou Windows. Isso torna o build mais reproduzível entre computadores.

Comandos úteis:

```bash
./mvnw test
./mvnw package
docker compose up -d --wait postgres
docker compose logs postgres
docker compose stop postgres
```

## 15. Sugestões de exercícios

1. Adicione paginação ao endpoint `GET /conversations`.
2. Crie um endpoint para alterar o título.
3. Adicione um terceiro papel de sistema apenas no modelo de integração.
4. Substitua `ddl-auto=update` por migrations Flyway.
5. Use o servidor HTTP de teste para simular uma resposta do provedor com conteúdo ausente.
6. Adicione autenticação somente depois de compreender o fluxo atual.

Essas evoluções são úteis, mas foram deixadas fora da versão inicial para manter o projeto claro e adequado a quem está consolidando os fundamentos de backend.
