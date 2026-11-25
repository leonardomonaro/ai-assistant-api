package com.leonardomonaro.aiassistant.dto;

import com.leonardomonaro.aiassistant.entity.MessageRole;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        MessageRole role,
        String content,
        Instant createdAt
) {
}
