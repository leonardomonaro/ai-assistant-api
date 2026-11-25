package com.leonardomonaro.aiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "content is required")
        @Size(max = 4000, message = "content must contain at most 4000 characters")
        @Pattern(regexp = "[^\\x00]*", message = "content must not contain null characters")
        String content
) {
}
