package com.leonardomonaro.aiassistant.ai;

import java.util.List;

public interface AiClient {
    String generateResponse(List<AiMessage> conversationHistory);
}
