package com.leonardomonaro.aiassistant.service;

import com.leonardomonaro.aiassistant.ai.AiClient;
import com.leonardomonaro.aiassistant.ai.AiMessage;
import com.leonardomonaro.aiassistant.config.AiProperties;
import com.leonardomonaro.aiassistant.dto.ConversationDetailResponse;
import com.leonardomonaro.aiassistant.dto.ConversationResponse;
import com.leonardomonaro.aiassistant.dto.CreateConversationRequest;
import com.leonardomonaro.aiassistant.dto.MessageResponse;
import com.leonardomonaro.aiassistant.dto.SendMessageRequest;
import com.leonardomonaro.aiassistant.dto.SendMessageResponse;
import com.leonardomonaro.aiassistant.entity.Conversation;
import com.leonardomonaro.aiassistant.entity.Message;
import com.leonardomonaro.aiassistant.entity.MessageRole;
import com.leonardomonaro.aiassistant.exception.ConversationNotFoundException;
import com.leonardomonaro.aiassistant.repository.ConversationRepository;
import com.leonardomonaro.aiassistant.repository.MessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "New conversation";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AiClient aiClient;
    private final AiProperties aiProperties;

    public ConversationService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            AiClient aiClient,
            AiProperties aiProperties) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.aiClient = aiClient;
        this.aiProperties = aiProperties;
    }

    @Transactional
    public ConversationResponse createConversation(CreateConversationRequest request) {
        String title = request == null || request.title() == null || request.title().isBlank()
                ? DEFAULT_TITLE
                : request.title().strip();
        return toConversationResponse(conversationRepository.save(new Conversation(title)));
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> listConversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDescIdAsc().stream()
                .map(this::toConversationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailResponse getConversation(UUID conversationId) {
        Conversation conversation = findConversation(conversationId);
        return new ConversationDetailResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                loadMessages(conversationId));
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> listMessages(UUID conversationId) {
        findConversation(conversationId);
        return loadMessages(conversationId);
    }

    private List<MessageResponse> loadMessages(UUID conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional
    public SendMessageResponse sendMessage(UUID conversationId, SendMessageRequest request) {
        Conversation conversation = findConversationForUpdate(conversationId);
        String userContent = request.content().strip();
        Instant userTimestamp = nextTimestamp(conversation.getUpdatedAt());
        String assistantContent = aiClient.generateResponse(loadContext(conversationId, userContent));
        Instant assistantTimestamp = nextTimestamp(userTimestamp);

        Message userMessage = messageRepository.save(
                new Message(conversation, MessageRole.USER, userContent, userTimestamp));
        Message assistantMessage = messageRepository.save(
                new Message(conversation, MessageRole.ASSISTANT, assistantContent, assistantTimestamp));
        conversation.touch(assistantTimestamp);

        return new SendMessageResponse(
                toMessageResponse(userMessage),
                toMessageResponse(assistantMessage));
    }

    @Transactional
    public void deleteConversation(UUID conversationId) {
        Conversation conversation = findConversationForUpdate(conversationId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
    }

    private Conversation findConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    private Conversation findConversationForUpdate(UUID conversationId) {
        return conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    private List<AiMessage> loadContext(UUID conversationId, String userContent) {
        List<AiMessage> context = new ArrayList<>();
        int previousMessageLimit = aiProperties.maxContextMessages() - 1;
        if (previousMessageLimit > 0) {
            PageRequest page = PageRequest.of(0, previousMessageLimit, NEWEST_FIRST);
            messageRepository.findByConversationId(conversationId, page).reversed()
                    .forEach(message -> context.add(new AiMessage(message.getRole(), message.getContent())));
        }
        context.add(new AiMessage(MessageRole.USER, userContent));
        return List.copyOf(context);
    }

    private Instant nextTimestamp(Instant previousTimestamp) {
        // PostgreSQL stores timestamps with microsecond precision.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(previousTimestamp) ? now : previousTimestamp.plus(1, ChronoUnit.MICROS);
    }

    private ConversationResponse toConversationResponse(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt());
    }

    private MessageResponse toMessageResponse(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt());
    }
}
