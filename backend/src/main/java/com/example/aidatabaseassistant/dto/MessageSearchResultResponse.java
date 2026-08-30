package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class MessageSearchResultResponse {
    private Long messageId;
    private Long conversationId;
    private String conversationTitle;
    private String content;
    private String generatedSql;
    private Boolean pinned;
    private LocalDateTime createdAt;
}