package com.example.aidatabaseassistant.ai;

public record ConversationContextMessage(String role, String content, String generatedSql) {
}
