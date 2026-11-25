package com.leonardomonaro.aiassistant.controller;

import com.leonardomonaro.aiassistant.dto.ConversationDetailResponse;
import com.leonardomonaro.aiassistant.dto.ConversationResponse;
import com.leonardomonaro.aiassistant.dto.CreateConversationRequest;
import com.leonardomonaro.aiassistant.dto.MessageResponse;
import com.leonardomonaro.aiassistant.dto.SendMessageRequest;
import com.leonardomonaro.aiassistant.dto.SendMessageResponse;
import com.leonardomonaro.aiassistant.exception.ApiError;
import com.leonardomonaro.aiassistant.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
@Tag(name = "Conversations", description = "Gerenciamento de conversas e mensagens")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @Operation(summary = "Cria uma conversa")
    @ApiResponse(responseCode = "201", description = "Conversa criada")
    @ApiResponse(responseCode = "400", description = "Corpo inválido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ConversationResponse> createConversation(
            @Valid @RequestBody(required = false) CreateConversationRequest request) {
        ConversationResponse response = conversationService.createConversation(request);
        return ResponseEntity.created(URI.create("/conversations/" + response.id())).body(response);
    }

    @GetMapping
    @Operation(summary = "Lista todas as conversas")
    public List<ConversationResponse> listConversations() {
        return conversationService.listConversations();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta uma conversa com seu histórico")
    @ApiResponse(responseCode = "200", description = "Conversa encontrada")
    @ApiResponse(responseCode = "404", description = "Conversa inexistente",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ConversationDetailResponse getConversation(@PathVariable UUID id) {
        return conversationService.getConversation(id);
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Envia uma mensagem ao assistente")
    @ApiResponse(responseCode = "201", description = "Mensagens do usuário e do assistente criadas")
    @ApiResponse(responseCode = "400", description = "UUID ou corpo inválido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Conversa inexistente",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "Falha do provedor de IA",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "504", description = "Tempo limite da chamada de IA excedido",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SendMessageResponse> sendMessage(
            @PathVariable UUID id,
            @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.sendMessage(id, request));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "Lista as mensagens de uma conversa")
    @ApiResponse(responseCode = "200", description = "Histórico encontrado")
    @ApiResponse(responseCode = "404", description = "Conversa inexistente",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<MessageResponse> listMessages(@PathVariable UUID id) {
        return conversationService.listMessages(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Exclui uma conversa e suas mensagens")
    @ApiResponse(responseCode = "204", description = "Conversa excluída", content = @Content)
    @ApiResponse(responseCode = "404", description = "Conversa inexistente",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> deleteConversation(@PathVariable UUID id) {
        conversationService.deleteConversation(id);
        return ResponseEntity.noContent().build();
    }
}
