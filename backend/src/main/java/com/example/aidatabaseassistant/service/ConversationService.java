package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ConversationResponse;
import com.example.aidatabaseassistant.dto.MessageResponse;
import com.example.aidatabaseassistant.dto.MessageSearchResultResponse;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.security.ConnectionAccessGuard;
import com.example.aidatabaseassistant.dto.QueryResponse;

import org.springframework.data.domain.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ConnectionAccessGuard connectionAccessGuard;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<ConversationResponse> getConversations(String username) {
        User user = connectionAccessGuard.requireUser(username);

        return conversationRepository.findByUserId(user.getId())
                .stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    public List<ConversationResponse> getConversations(
            String username,
            Long connectionId) {

        if (connectionId == null) {
            return getConversations(username);
        }

        User user = connectionAccessGuard.requireUser(username);

        return conversationRepository
                .findByUserIdAndConnectionIdOrderByUpdatedAtDesc(
                        user.getId(),
                        connectionId
                )
                .stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<ConversationResponse> getConversationsPaged(
            String username,
            Long connectionId,
            Pageable pageable) {

        User user = connectionAccessGuard.requireUser(username);

        Page<Conversation> page = (connectionId != null)
                ? conversationRepository
                .findByUserIdAndConnectionIdOrderByUpdatedAtDesc(
                        user.getId(),
                        connectionId,
                        pageable
                )
                : conversationRepository
                .findByUserIdOrderByUpdatedAtDesc(
                        user.getId(),
                        pageable
                );

        return page.map(this::toConversationResponse);
    }

    public List<MessageResponse> getMessages(
            String username,
            Long conversationId) {

        User user = connectionAccessGuard.requireUser(username);

        Conversation conversation = conversationRepository
                .findById(conversationId)
                .orElseThrow(
                        () -> new ResourceNotFoundException(
                                "Không tìm thấy conversation"
                        )
                );

        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new ForbiddenResourceException(
                    "Bạn không có quyền truy cập conversation này"
            );
        }

        List<Message> messages =
                messageRepository
                        .findByConversationIdOrderByCreatedAtAsc(
                                conversationId
                        );

        return messages.stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());
    }

    private ConversationResponse toConversationResponse(
            Conversation c) {

        return new ConversationResponse(
                c.getId(),
                c.getTitle(),
                c.getConnection().getId(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    private MessageResponse toMessageResponse(Message m) {

        List<MessageResponse.QueryLogResponse> logs =
                m.getQueryLogs()
                        .stream()
                        .map(log -> new MessageResponse.QueryLogResponse(
                                log.getAttemptNumber(),
                                log.getSqlText(),
                                log.getStatus(),
                                log.getRowCount(),
                                log.getExecutionTimeMs(),
                                log.getErrorMessage()
                        ))
                        .collect(Collectors.toList());

        QueryResponse queryResult = null;

        if (m.getQueryResponseJson() != null
                && !m.getQueryResponseJson().isBlank()) {

            try {

                queryResult = OBJECT_MAPPER.readValue(
                        m.getQueryResponseJson(),
                        QueryResponse.class
                );

            } catch (Exception e) {

                /*
                 * Không làm hỏng toàn bộ conversation,
                 * nhưng phải log lỗi để có thể phát hiện
                 * snapshot không deserialize được.
                 */
                log.warn(
                        "[QUERY SNAPSHOT] Không thể deserialize queryResponseJson cho messageId={}: {}",
                        m.getId(),
                        e.getMessage(),
                        e
                );

                queryResult = null;
            }
        }

        return new MessageResponse(
                m.getId(),
                m.getRole(),
                m.getContent(),
                m.getGeneratedSql(),
                m.getCreatedAt(),
                logs,
                Boolean.TRUE.equals(m.getPinned()),
                queryResult
        );
    }

    public void deleteAllConversations(String username) {

        User user = connectionAccessGuard.requireUser(username);

        List<Conversation> conversations =
                conversationRepository.findByUserId(user.getId());

        conversationRepository.deleteAll(conversations);
    }

    public void deleteConversation(
            String username,
            Long conversationId) {

        User user = connectionAccessGuard.requireUser(username);

        Conversation conversation =
                conversationRepository.findById(conversationId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException(
                                        "Không tìm thấy conversation"
                                )
                        );

        /*
         * Security / IDOR protection:
         * Chỉ chủ sở hữu conversation mới được phép xóa.
         */
        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new ForbiddenResourceException(
                    "Bạn không có quyền xóa conversation này"
            );
        }

        conversationRepository.delete(conversation);
    }

    public MessageResponse togglePin(
            String username,
            Long messageId) {

        User user = connectionAccessGuard.requireUser(username);

        Message message =
                messageRepository.findByIdWithOwner(messageId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException(
                                        "Không tìm thấy message"
                                )
                        );

        /*
         * Security / IDOR protection:
         * Message tồn tại nhưng thuộc conversation của user khác
         * thì phải trả về ForbiddenResourceException -> HTTP 403.
         */
        if (!message.getConversation()
                .getUser()
                .getId()
                .equals(user.getId())) {

            throw new ForbiddenResourceException(
                    "Bạn không có quyền thao tác message này"
            );
        }

        boolean current =
                Boolean.TRUE.equals(message.getPinned());

        message.setPinned(!current);

        messageRepository.save(message);

        return toMessageResponse(message);
    }

    public List<MessageResponse> getPinnedMessages(
            String username) {

        User user = connectionAccessGuard.requireUser(username);

        return messageRepository
                .findPinnedByUserId(user.getId())
                .stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());
    }

    public Page<MessageSearchResultResponse> searchMessages(
            String username,
            String keyword,
            boolean pinnedOnly,
            Pageable pageable) {

        User user = connectionAccessGuard.requireUser(username);

        String kw =
                keyword == null
                        ? ""
                        : keyword.trim();

        return messageRepository
                .searchByUser(
                        user.getId(),
                        kw,
                        pinnedOnly,
                        pageable
                )
                .map(m -> new MessageSearchResultResponse(
                        m.getId(),
                        m.getConversation().getId(),
                        m.getConversation().getTitle(),
                        m.getContent(),
                        m.getGeneratedSql(),
                        Boolean.TRUE.equals(m.getPinned()),
                        m.getCreatedAt()
                ));
    }
}