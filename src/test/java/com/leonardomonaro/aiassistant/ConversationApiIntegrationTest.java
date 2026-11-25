package com.leonardomonaro.aiassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leonardomonaro.aiassistant.ai.AiClient;
import com.leonardomonaro.aiassistant.ai.AiMessage;
import com.leonardomonaro.aiassistant.entity.MessageRole;
import com.leonardomonaro.aiassistant.exception.AiServiceException;
import com.leonardomonaro.aiassistant.exception.AiTimeoutException;
import com.leonardomonaro.aiassistant.repository.ConversationRepository;
import com.leonardomonaro.aiassistant.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @MockitoSpyBean
    private AiClient aiClient;

    @BeforeEach
    void cleanDatabase() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
    }

    @Test
    void completesConversationLifecycleInSimulatedMode() throws Exception {
        MvcResult creation = mockMvc.perform(post("/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Java studies\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/conversations/")))
                .andExpect(jsonPath("$.title").value("Java studies"))
                .andReturn();

        JsonNode createdConversation = objectMapper.readTree(creation.getResponse().getContentAsString());
        String conversationId = createdConversation.get("id").asText();

        mockMvc.perform(post("/conversations/{id}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"What is dependency injection?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.assistantMessage.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.assistantMessage.content", containsString("simulated response")));

        mockMvc.perform(get("/conversations/{id}/messages", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"));

        mockMvc.perform(get("/conversations/{id}", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages", hasSize(2)));

        mockMvc.perform(get("/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(delete("/conversations/{id}", conversationId))
                .andExpect(status().isNoContent());
        assertThat(messageRepository.count()).isZero();
        assertThat(conversationRepository.count()).isZero();

        mockMvc.perform(get("/conversations/{id}", conversationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsBlankAndOversizedMessages() throws Exception {
        MvcResult creation = mockMvc.perform(post("/conversations"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("New conversation"))
                .andReturn();
        String conversationId = objectMapper.readTree(creation.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post("/conversations/{id}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.content").value("content is required"));

        String oversized = "a".repeat(4001);
        String request = objectMapper.writeValueAsString(Map.of("content", oversized));
        mockMvc.perform(post("/conversations/{id}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.content")
                        .value("content must contain at most 4000 characters"));
    }

    @Test
    void rejectsMalformedConversationId() throws Exception {
        mockMvc.perform(get("/conversations/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter: id"));
    }

    @Test
    void preservesFrameworkClientErrorStatuses() throws Exception {
        String id = createConversation();
        mockMvc.perform(post("/conversations/{id}/messages", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or invalid JSON"));
        mockMvc.perform(post("/conversations/{id}/messages", id))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/conversations/{id}/messages", id)
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(put("/conversations"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")));
        mockMvc.perform(get("/unknown-route")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsTitlesAndMessagesThatCannotBeStored() throws Exception {
        mockMvc.perform(post("/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "x".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").exists());
        mockMvc.perform(post("/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"a\\u0000b\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/conversations/{id}/messages", createConversation())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"a\\u0000b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.content").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"content\":123}", "{\"content\":true}", "{\"content\":\"Question\"} {}"})
    void rejectsInvalidJsonFieldTypesAndTrailingValues(String body) throws Exception {
        mockMvc.perform(post("/conversations/{id}/messages", createConversation())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addsTheEarlierTurnToContextAndKeepsMessagesInOrder() throws Exception {
        String id = createConversation();
        mockMvc.perform(post("/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"First\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Second\"}"))
                .andExpect(status().isCreated());

        verify(aiClient).generateResponse(List.of(
                new AiMessage(MessageRole.USER, "First"),
                new AiMessage(MessageRole.ASSISTANT, "This is a simulated response to: \"First\""),
                new AiMessage(MessageRole.USER, "Second")));
        mockMvc.perform(get("/conversations/{id}/messages", id))
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].content").value("First"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[2].content").value("Second"))
                .andExpect(jsonPath("$[3].role").value("ASSISTANT"));
    }

    @Test
    void providerFailuresDoNotLeavePartialTurns() throws Exception {
        String id = createConversation();
        doThrow(new AiServiceException("Provider error"))
                .doThrow(new AiTimeoutException("Provider timed out", null))
                .when(aiClient).generateResponse(List.of(new AiMessage(MessageRole.USER, "Question")));
        mockMvc.perform(post("/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Question\"}"))
                .andExpect(status().isBadGateway());
        mockMvc.perform(post("/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Question\"}"))
                .andExpect(status().isGatewayTimeout());
        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void documentsMessageErrorsAndBodylessDelete() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/conversations/{id}/messages'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/conversations/{id}/messages'].post.responses['502']").exists())
                .andExpect(jsonPath("$.paths['/conversations/{id}/messages'].post.responses['504']").exists())
                .andExpect(jsonPath("$.paths['/conversations/{id}'].delete.responses['204'].content").doesNotExist());
    }

    private String createConversation() throws Exception {
        MvcResult creation = mockMvc.perform(post("/conversations")).andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(creation.getResponse().getContentAsString()).get("id").asText();
    }
}
