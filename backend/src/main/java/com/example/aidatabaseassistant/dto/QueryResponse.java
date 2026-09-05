package com.example.aidatabaseassistant.dto;

public record QueryResponse(
        Long conversationId,
        Long messageId,
        String generatedSql,
        String status,
        int timeoutSeconds,
        QueryResultDto result) {
}
