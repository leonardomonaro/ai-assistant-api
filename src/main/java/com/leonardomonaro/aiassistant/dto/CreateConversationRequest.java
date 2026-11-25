package com.leonardomonaro.aiassistant.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @Size(max = 120, message = "title must contain at most 120 characters")
        @Pattern(regexp = "[^\\x00]*", message = "title must not contain null characters")
        String title
) {
}
