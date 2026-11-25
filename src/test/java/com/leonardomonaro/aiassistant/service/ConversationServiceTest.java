package com.leonardomonaro.aiassistant.service;

import com.leonardomonaro.aiassistant.ai.AiClient;
import com.leonardomonaro.aiassistant.ai.AiMessage;
import com.leonardomonaro.aiassistant.config.AiProperties;
import com.leonardomonaro.aiassistant.dto.SendMessageRequest;
import com.leonardomonaro.aiassistant.entity.Conversation;
import com.leonardomonaro.aiassistant.entity.Message;
import com.leonardomonaro.aiassistant.entity.MessageRole;
import com.leonardomonaro.aiassistant.repository.ConversationRepository;
import com.leonardomonaro.aiassistant.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AiClient aiClient;

    @Test
    void sendsRecentConversationContextToAiClientInChronologicalOrder() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = new Conversation("Test");
        conversation.touch(Instant.EPOCH);
        Message previousUser = new Message(conversation, MessageRole.USER, "First question", Instant.EPOCH);
        Message previousAssistant = new Message(conversation, MessageRole.ASSISTANT, "First answer", Instant.EPOCH);

        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationId(eq(conversationId), any(Pageable.class)))
                .thenReturn(List.of(previousAssistant, previousUser));
        when(aiClient.generateResponse(any())).thenReturn("Contextual answer");
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(3).sendMessage(conversationId, new SendMessageRequest(" Follow-up "));

        verify(aiClient).generateResponse(List.of(
                new AiMessage(MessageRole.USER, "First question"),
                new AiMessage(MessageRole.ASSISTANT, "First answer"),
                new AiMessage(MessageRole.USER, "Follow-up")));
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findByConversationId(eq(conversationId), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(2);
        assertThat(page.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    @Test
    void onlySendsCurrentQuestionWhenContextLimitIsOne() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = new Conversation("Test");
        conversation.touch(Instant.EPOCH);
        when(conversationRepository.findByIdForUpdate(conversationId)).thenReturn(Optional.of(conversation));
        when(aiClient.generateResponse(any())).thenReturn("Answer");
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(1).sendMessage(conversationId, new SendMessageRequest("Question"));

        verify(aiClient).generateResponse(List.of(new AiMessage(MessageRole.USER, "Question")));
        verify(messageRepository, never()).findByConversationId(any(), any());
    }

    private ConversationService service(int contextLimit) {
        AiProperties properties = new AiProperties(
                AiProperties.Provider.MOCK, URI.create("https://api.openai.com"), "gpt-4o-mini",
                Duration.ofSeconds(5), Duration.ofSeconds(30), contextLimit, 512, "Be helpful");
        return new ConversationService(conversationRepository, messageRepository, aiClient, properties);
    }
}
