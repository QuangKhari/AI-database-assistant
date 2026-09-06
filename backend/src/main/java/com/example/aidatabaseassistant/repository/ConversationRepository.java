package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    List<Conversation> findByUserId(Long userId);

    // MỚI: FE cần lọc conversation theo connection đang chọn (mỗi connection
    // có schema/ngữ cảnh khác nhau). orderBy updatedAt desc để hội thoại mới
    // nhất lên đầu sidebar, giống hành vi FE đang mong đợi.
    List<Conversation> findByUserIdAndConnectionIdOrderByUpdatedAtDesc(Long userId, Long connectionId);

    // MỚI: bản có phân trang - CÙNG TÊN, chỉ khác chữ ký (thêm Pageable) nên
    // Spring Data JPA coi là derived query độc lập, không đụng tới overload
    // List ở trên (giữ nguyên để không phá ConversationServiceTest /
    // ConversationOwnershipIntegrationTest / ConversationSecurityIntegrationTest
    // đang stub theo đúng chữ ký cũ).
    Page<Conversation> findByUserIdAndConnectionIdOrderByUpdatedAtDesc(
            Long userId, Long connectionId, Pageable pageable);

    // MỚI: khi FE không lọc theo connectionId (xem toàn bộ hội thoại).
    Page<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);
}