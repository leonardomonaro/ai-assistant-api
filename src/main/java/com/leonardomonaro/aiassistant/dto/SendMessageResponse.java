package com.leonardomonaro.aiassistant.dto;

public record SendMessageResponse(
        MessageResponse userMessage,
        MessageResponse assistantMessage
) {
}
