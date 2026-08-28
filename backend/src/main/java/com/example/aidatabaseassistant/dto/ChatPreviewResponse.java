package com.example.aidatabaseassistant.dto;

public record ChatPreviewResponse(
        Long conversationId,
        Long userMessageId,
        Long assistantMessageId,
        String generatedSql,
        boolean valid,
        String validationError) {
}
