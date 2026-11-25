package com.leonardomonaro.aiassistant.repository;

import com.leonardomonaro.aiassistant.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

    List<Message> findByConversationId(UUID conversationId, Pageable pageable);

    @Modifying
    @Query("delete from Message m where m.conversation.id = :conversationId")
    void deleteByConversationId(UUID conversationId);
}
