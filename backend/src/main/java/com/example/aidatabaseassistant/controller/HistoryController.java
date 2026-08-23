package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ConversationResponse;
import com.example.aidatabaseassistant.dto.MessageResponse;
import com.example.aidatabaseassistant.service.ConversationService;
import lombok.RequiredArgsConstructor;
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
}