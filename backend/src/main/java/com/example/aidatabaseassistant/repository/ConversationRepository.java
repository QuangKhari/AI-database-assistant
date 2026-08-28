package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    List<Conversation> findByUserId(Long userId);

    List<Conversation> findAllByUserUsernameIgnoreCaseOrderByUpdatedAtDesc(String username);

    List<Conversation> findAllByUserUsernameIgnoreCaseAndConnectionIdOrderByUpdatedAtDesc(
            String username, Long connectionId);

    Optional<Conversation> findByIdAndUserUsernameIgnoreCase(Long id, String username);

    Optional<Conversation> findByIdAndUserUsernameIgnoreCaseAndConnectionId(
            Long id, String username, Long connectionId);
}
