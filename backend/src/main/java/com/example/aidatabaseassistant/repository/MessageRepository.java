package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    List<Message> findTop6ByConversationIdOrderByCreatedAtDesc(Long conversationId);
}
