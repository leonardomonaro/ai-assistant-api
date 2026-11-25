package com.leonardomonaro.aiassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardomonaro.aiassistant.config.AiProperties;
import com.leonardomonaro.aiassistant.entity.MessageRole;
import com.leonardomonaro.aiassistant.exception.AiServiceException;
import com.leonardomonaro.aiassistant.exception.AiTimeoutException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiClientTest {

    private static final String COMPLETION_PATH = "/v1/chat/completions";
    private static final List<AiMessage> HISTORY = List.of(
            new AiMessage(MessageRole.USER, "Earlier question"),
            new AiMessage(MessageRole.ASSISTANT, "Earlier reply"),
            new AiMessage(MessageRole.USER, "Current question"));

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsBearerTokenAndOrderedHistoryAndReadsTheReply() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        handleCompletion(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            requestBody.set(exchange.getRequestBody().readAllBytes());
            sendJson(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\" Local reply \"}}]}");
        });

        assertThat(client(Duration.ofSeconds(3)).generateResponse(HISTORY)).isEqualTo("Local reply");
        assertThat(authorization.get()).isEqualTo("Bearer test-token");
        assertThat(method.get()).isEqualTo("POST");
        JsonNode body = new ObjectMapper().readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(512);
        assertThat(body.path("messages")).extracting(message -> message.path("role").asText())
                .containsExactly("system", "user", "assistant", "user");
        assertThat(body.path("messages")).extracting(message -> message.path("content").asText())
                .containsExactly("Be helpful", "Earlier question", "Earlier reply", "Current question");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"choices\":[]}", "{\"choices\":[null]}",
            "{\"choices\":[{\"message\":{\"content\":\" \"}}]}"})
    void rejectsUnreadableOrUnusableResponses(String json) {
        handleCompletion(exchange -> sendJson(exchange, 200, json));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).generateResponse(HISTORY))
                .isExactlyInstanceOf(AiServiceException.class);
    }

    @Test
    void mapsProviderErrorsWithoutExposingTheirBody() {
        handleCompletion(exchange -> sendJson(exchange, 429, "{\"error\":\"private upstream information\"}"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).generateResponse(HISTORY))
                .isExactlyInstanceOf(AiServiceException.class)
                .hasMessage("The AI provider returned an error");
    }

    @Test
    void doesNotFollowUpstreamRedirects() {
        AtomicBoolean redirected = new AtomicBoolean();
        server.createContext("/redirected", exchange -> {
            redirected.set(true);
            sendJson(exchange, 200, "{}");
            exchange.close();
        });
        handleCompletion(exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(307, -1);
        });

        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).generateResponse(HISTORY))
                .isExactlyInstanceOf(AiServiceException.class);
        assertThat(redirected).isFalse();
    }

    @Test
    void mapsAnActualReadTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        handleCompletion(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            assertThatThrownBy(() -> client(Duration.ofMillis(500)).generateResponse(HISTORY))
                    .isInstanceOf(AiTimeoutException.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void requiresApiKey() {
        assertThatThrownBy(() -> new OpenAiClient(properties(Duration.ofSeconds(3)), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_API_KEY");
    }

    private OpenAiClient client(Duration readTimeout) {
        return new OpenAiClient(properties(readTimeout), "test-token");
    }

    private AiProperties properties(Duration readTimeout) {
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new AiProperties(AiProperties.Provider.OPENAI, baseUrl, "gpt-4o-mini",
                Duration.ofSeconds(2), readTimeout, 20, 512, "Be helpful");
    }

    private void handleCompletion(HttpHandler handler) {
        server.createContext(COMPLETION_PATH, exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
