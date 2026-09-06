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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public List<ConversationResponse> getConversations(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        return conversationRepository.findByUserId(user.getId())
                .stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    // MỚI: overload có filter connectionId - giữ nguyên overload cũ ở trên để
    // không phá test hiện có đang stub theo đúng 1 tham số (username).
    public List<ConversationResponse> getConversations(String username, Long connectionId) {
        if (connectionId == null) {
            return getConversations(username);
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        return conversationRepository.findByUserIdAndConnectionIdOrderByUpdatedAtDesc(user.getId(), connectionId)
                .stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    // MỚI: bản phân trang - dùng cho sidebar Chat khi user có nhiều hội thoại.
    // Overload riêng (thêm Pageable) - KHÔNG đụng 2 hàm getConversations() ở
    // trên để giữ nguyên toàn bộ test cũ.
    @Transactional(readOnly = true)
    public Page<ConversationResponse> getConversationsPaged(
            String username, Long connectionId, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        Page<Conversation> page = (connectionId != null)
                ? conversationRepository.findByUserIdAndConnectionIdOrderByUpdatedAtDesc(
                user.getId(), connectionId, pageable)
                : conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId(), pageable);

        return page.map(this::toConversationResponse);
    }

    public List<MessageResponse> getMessages(String username, Long conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy conversation"));

        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập conversation này");
        }

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
                m.getCreatedAt(), logs, Boolean.TRUE.equals(m.getPinned())
        );
    }

    public void deleteAllConversations(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        List<Conversation> conversations = conversationRepository.findByUserId(user.getId());
        conversationRepository.deleteAll(conversations);
    }

    public void deleteConversation(String username, Long conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy conversation"));

        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền xóa conversation này");
        }

        conversationRepository.delete(conversation);
    }

    public MessageResponse togglePin(String username, Long messageId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        Message message = messageRepository.findByIdWithOwner(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy message"));

        if (!message.getConversation().getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền thao tác message này");
        }

        boolean current = Boolean.TRUE.equals(message.getPinned());
        message.setPinned(!current);
        messageRepository.save(message);

        return toMessageResponse(message);
    }

    public List<MessageResponse> getPinnedMessages(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        return messageRepository.findPinnedByUserId(user.getId())
                .stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());
    }

    public org.springframework.data.domain.Page<MessageSearchResultResponse> searchMessages(
            String username, String keyword, boolean pinnedOnly,
            org.springframework.data.domain.Pageable pageable) {

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        String kw = keyword == null ? "" : keyword.trim();

        return messageRepository.searchByUser(user.getId(), kw, pinnedOnly, pageable)
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