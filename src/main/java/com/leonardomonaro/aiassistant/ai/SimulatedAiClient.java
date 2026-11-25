package com.leonardomonaro.aiassistant.ai;

import com.leonardomonaro.aiassistant.entity.MessageRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class SimulatedAiClient implements AiClient {

    @Override
    public String generateResponse(List<AiMessage> conversationHistory) {
        String lastUserMessage = conversationHistory.reversed().stream()
                .filter(message -> message.role() == MessageRole.USER)
                .findFirst()
                .map(AiMessage::content)
                .orElse("Hello");

        return "This is a simulated response to: \"" + lastUserMessage + "\"";
    }
}
