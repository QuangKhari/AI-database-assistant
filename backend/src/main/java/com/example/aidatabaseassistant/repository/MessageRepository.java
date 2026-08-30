package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    // Dùng cho toggle pin: load kèm conversation + user để check quyền sở hữu 1 lần
    @Query("SELECT m FROM Message m JOIN FETCH m.conversation c JOIN FETCH c.user WHERE m.id = :id")
    Optional<Message> findByIdWithOwner(@Param("id") Long id);

    // Danh sách message đã pin của 1 user, mới nhất trước
    @Query("SELECT m FROM Message m WHERE m.conversation.user.id = :userId AND m.pinned = true " +
            "ORDER BY m.createdAt DESC")
    List<Message> findPinnedByUserId(@Param("userId") Long userId);

    // Search toàn bộ lịch sử của user theo keyword trong câu hỏi hoặc SQL sinh ra
    @Query("SELECT m FROM Message m JOIN FETCH m.conversation c " +
            "WHERE c.user.id = :userId " +
            "AND (:pinnedOnly = false OR m.pinned = true) " +
            "AND (LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "     OR LOWER(COALESCE(m.generatedSql, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "ORDER BY m.createdAt DESC")
    Page<Message> searchByUser(@Param("userId") Long userId,
                               @Param("keyword") String keyword,
                               @Param("pinnedOnly") boolean pinnedOnly,
                               Pageable pageable);
}