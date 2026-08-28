package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ConversationResponse;
import com.example.aidatabaseassistant.dto.MessageResponse;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    public List<ConversationResponse> getConversations(String username, Long connectionId) {
        List<Conversation> conversations = connectionId == null
                ? conversationRepository.findAllByUserUsernameIgnoreCaseOrderByUpdatedAtDesc(username)
                : conversationRepository.findAllByUserUsernameIgnoreCaseAndConnectionIdOrderByUpdatedAtDesc(
                        username, connectionId);
        return conversations
                .stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    public List<MessageResponse> getMessages(String username, Long conversationId) {
        Conversation conversation = conversationRepository
                .findByIdAndUserUsernameIgnoreCase(conversationId, username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy conversation"));

        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        return messages.stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());
    }

    private ConversationResponse toConversationResponse(Conversation c) {
        return new ConversationResponse(
                c.getId(), c.getTitle(), c.getConnection().getId(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    private MessageResponse toMessageResponse(Message m) {
        List<MessageResponse.QueryLogResponse> logs = m.getQueryLogs().stream()
                .map(log -> new MessageResponse.QueryLogResponse(
                        log.getAttemptNumber(), log.getSqlText(), log.getStatus(),
                        log.getRowCount(), log.getExecutionTimeMs(), log.getErrorMessage()
                ))
                .collect(Collectors.toList());

        return new MessageResponse(
                m.getId(), m.getRole(), m.getContent(), m.getGeneratedSql(),
                m.getCreatedAt(), logs
        );
    }

    @Transactional
    public void deleteAllConversations(String username) {
        List<Conversation> conversations =
                conversationRepository.findAllByUserUsernameIgnoreCaseOrderByUpdatedAtDesc(username);
        conversationRepository.deleteAll(conversations);
    }

    @Transactional
    public void deleteConversation(String username, Long conversationId) {
        Conversation conversation = conversationRepository
                .findByIdAndUserUsernameIgnoreCase(conversationId, username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy conversation"));
        conversationRepository.delete(conversation);
    }
}
