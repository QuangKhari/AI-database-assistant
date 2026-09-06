package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ConversationResponse;
import com.example.aidatabaseassistant.dto.MessageResponse;
import com.example.aidatabaseassistant.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> getConversations(
            Authentication authentication,
            @RequestParam(required = false) Long connectionId) {
        return ResponseEntity.ok(conversationService.getConversations(authentication.getName(), connectionId));
    }

    // MỚI: route phân trang riêng - KHÔNG sửa route /api/conversations cũ ở
    // trên để mọi client hiện tại (kể cả các test) không bị ảnh hưởng.
    @GetMapping("/paged")
    public ResponseEntity<Page<ConversationResponse>> getConversationsPaged(
            Authentication authentication,
            @RequestParam(required = false) Long connectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {

        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(
                conversationService.getConversationsPaged(authentication.getName(), connectionId, pageable)
        );
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(conversationService.getMessages(authentication.getName(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConversation(Authentication authentication, @PathVariable Long id) {
        conversationService.deleteConversation(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }
}