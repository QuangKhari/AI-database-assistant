package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ConversationResponse {
    private Long id;
    private String title;
    private Long connectionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}