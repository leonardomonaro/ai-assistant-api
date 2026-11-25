package com.leonardomonaro.aiassistant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.ai")
@Validated
public record AiProperties(
        @NotNull Provider provider,
        @NotNull URI baseUrl,
        @NotBlank String model,
        @NotNull @DurationMin(millis = 1) Duration connectTimeout,
        @NotNull @DurationMin(millis = 1) Duration readTimeout,
        @Positive @Max(100) int maxContextMessages,
        @Positive @Max(4096) int maxCompletionTokens,
        @NotBlank @Size(max = 4000) String systemPrompt
) {
    public enum Provider {
        MOCK, OPENAI
    }
}
