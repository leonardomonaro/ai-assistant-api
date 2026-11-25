package com.leonardomonaro.aiassistant.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.leonardomonaro.aiassistant.config.AiProperties;
import com.leonardomonaro.aiassistant.exception.AiServiceException;
import com.leonardomonaro.aiassistant.exception.AiTimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "openai")
public class OpenAiClient implements AiClient {

    private final RestClient restClient;
    private final AiProperties properties;

    public OpenAiClient(AiProperties properties, @Value("${AI_API_KEY:}") String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("AI_API_KEY is required when AI_PROVIDER=openai");
        }

        URI baseUrl = properties.baseUrl();
        boolean localHttp = "http".equalsIgnoreCase(baseUrl.getScheme())
                && List.of("localhost", "127.0.0.1", "[::1]").contains(String.valueOf(baseUrl.getHost()));
        if (baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || !("https".equalsIgnoreCase(baseUrl.getScheme()) || localHttp)
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
            throw new IllegalStateException("AI_BASE_URL must be an HTTPS origin or a local HTTP origin");
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.toString())
                .defaultHeaders(headers -> headers.setBearerAuth(apiKey.strip()))
                .requestFactory(requestFactory)
                .build();
        this.properties = properties;
    }

    @Override
    public String generateResponse(List<AiMessage> conversationHistory) {
        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(new OpenAiMessage("system", properties.systemPrompt()));
        conversationHistory.stream()
                .map(message -> new OpenAiMessage(
                        message.role().name().toLowerCase(Locale.ROOT),
                        message.content()))
                .forEach(messages::add);

        ChatCompletionResponse response;
        try {
            response = restClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new ChatCompletionRequest(properties.model(), messages, properties.maxCompletionTokens()))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, upstreamResponse) -> {
                        throw new AiServiceException("The AI provider returned an error");
                    })
                    .body(ChatCompletionResponse.class);
        } catch (RestClientException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) {
                    throw new AiTimeoutException("The AI provider did not respond in time", exception);
                }
            }
            throw new AiServiceException("Could not read a valid response from the AI provider", exception);
        }

        Choice firstChoice = response == null || response.choices() == null || response.choices().isEmpty()
                ? null : response.choices().getFirst();
        String content = firstChoice == null || firstChoice.message() == null
                ? null : firstChoice.message().content();
        if (!StringUtils.hasText(content) || content.indexOf(0) >= 0) {
            throw new AiServiceException("The AI provider returned no usable reply");
        }
        return content.strip();
    }

    private record ChatCompletionRequest(String model, List<OpenAiMessage> messages,
                                         @JsonProperty("max_completion_tokens") int maxCompletionTokens) {
    }

    private record OpenAiMessage(String role, String content) {
    }

    private record ChatCompletionResponse(List<Choice> choices) {
    }

    private record Choice(OpenAiMessage message) {
    }
}
