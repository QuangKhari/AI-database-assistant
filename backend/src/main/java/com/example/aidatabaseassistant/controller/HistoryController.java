package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ConversationResponse;
import com.example.aidatabaseassistant.dto.MessageResponse;
import com.example.aidatabaseassistant.dto.MessageSearchResultResponse;
import com.example.aidatabaseassistant.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final ConversationService conversationService;

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> getHistory(Authentication authentication) {
        return ResponseEntity.ok(conversationService.getConversations(authentication.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<List<MessageResponse>> getHistoryDetail(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(conversationService.getMessages(authentication.getName(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteHistory(Authentication authentication, @PathVariable Long id) {
        conversationService.deleteConversation(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAllHistory(Authentication authentication) {
        conversationService.deleteAllConversations(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    // ===== MỚI: pin / unpin =====
    @PatchMapping("/messages/{messageId}/pin")
    public ResponseEntity<MessageResponse> togglePin(Authentication authentication, @PathVariable Long messageId) {
        return ResponseEntity.ok(conversationService.togglePin(authentication.getName(), messageId));
    }

    // ===== MỚI: danh sách message đã ghim =====
    @GetMapping("/pinned")
    public ResponseEntity<List<MessageResponse>> getPinned(Authentication authentication) {
        return ResponseEntity.ok(conversationService.getPinnedMessages(authentication.getName()));
    }

    // ===== MỚI: tìm kiếm toàn bộ lịch sử =====
    @GetMapping("/search")
    public ResponseEntity<Page<MessageSearchResultResponse>> search(
            Authentication authentication,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean pinnedOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(
                conversationService.searchMessages(authentication.getName(), keyword, pinnedOnly, pageable)
        );
    }
}