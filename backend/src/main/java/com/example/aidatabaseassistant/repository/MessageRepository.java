package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    List<Message> findTop6ByConversationIdOrderByCreatedAtDesc(Long conversationId);

    @EntityGraph(attributePaths = {"conversation", "conversation.user", "conversation.connection"})
    Optional<Message> findByIdAndConversationUserUsernameIgnoreCase(Long id, String username);
}
