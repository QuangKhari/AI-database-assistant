package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ChatPreviewRequest;
import com.example.aidatabaseassistant.dto.ChatPreviewResponse;
import com.example.aidatabaseassistant.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/preview")
    public ResponseEntity<ChatPreviewResponse> preview(Authentication authentication,
                                                       @Valid @RequestBody ChatPreviewRequest request) {
        return ResponseEntity.ok(chatService.preview(authentication.getName(), request));
    }
}
