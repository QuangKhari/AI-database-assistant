package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.ConversationContextMessage;
import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.dto.ChatPreviewRequest;
import com.example.aidatabaseassistant.dto.ChatPreviewResponse;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final UserRepository userRepository;
    private final ConnectionService connectionService;
    private final DatabaseSchemaRepository schemaRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final NL2SQLEngine nl2SQLEngine;
    private final QueryValidator queryValidator;
    private final RateLimitService rateLimitService;

    public ChatPreviewResponse preview(String username, ChatPreviewRequest request) {
        if (!rateLimitService.tryConsume(username)) {
            throw new RateLimitExceededException(
                    "Bạn đã gọi AI quá nhiều lần. Vui lòng thử lại sau một phút.");
        }

        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản"));
        DatabaseConnection connection = connectionService
                .getOwnedActiveConnection(username, request.getConnectionId());
        DatabaseSchema schema = schemaRepository.findByConnectionId(connection.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Hãy đồng bộ schema trước khi bắt đầu Chat"));

        Conversation conversation = resolveConversation(user, connection, request);
        List<ConversationContextMessage> context = conversation.getId() == null
                ? List.of()
                : loadRecentContext(conversation.getId());

        String generatedSql = nl2SQLEngine.generateSQL(
                request.getQuestion().trim(), schema, context);
        boolean valid = true;
        String validationError = null;
        try {
            queryValidator.validate(generatedSql, schema);
        } catch (IllegalArgumentException e) {
            valid = false;
            validationError = e.getMessage();
        }

        if (conversation.getId() == null) {
            conversation = conversationRepository.save(conversation);
        }

        Message userMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role("user")
                .content(request.getQuestion().trim())
                .build());
        Message assistantMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role("assistant")
                .content(valid ? "SQL preview đã sẵn sàng." : "SQL preview chưa vượt qua kiểm tra an toàn.")
                .generatedSql(generatedSql)
                .build());

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        return new ChatPreviewResponse(conversation.getId(), userMessage.getId(),
                assistantMessage.getId(), generatedSql, valid, validationError);
    }

    private Conversation resolveConversation(User user, DatabaseConnection connection,
                                             ChatPreviewRequest request) {
        if (request.getConversationId() != null) {
            return conversationRepository
                    .findByIdAndUserUsernameIgnoreCaseAndConnectionId(
                            request.getConversationId(), user.getUsername(), connection.getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Conversation không thuộc tài khoản hoặc connection đã chọn"));
        }
        String question = request.getQuestion().trim();
        return Conversation.builder()
                .user(user)
                .connection(connection)
                .title(question.length() > 60 ? question.substring(0, 60) + "..." : question)
                .build();
    }

    private List<ConversationContextMessage> loadRecentContext(Long conversationId) {
        List<Message> recent = new ArrayList<>(
                messageRepository.findTop6ByConversationIdOrderByCreatedAtDesc(conversationId));
        Collections.reverse(recent);
        return recent.stream()
                .map(message -> new ConversationContextMessage(
                        message.getRole(), message.getContent(), message.getGeneratedSql()))
                .toList();
    }
}
