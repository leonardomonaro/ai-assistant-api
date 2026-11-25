package com.leonardomonaro.aiassistant.ai;

import com.leonardomonaro.aiassistant.entity.MessageRole;

public record AiMessage(MessageRole role, String content) {
}
