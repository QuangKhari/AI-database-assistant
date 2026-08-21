package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class QueryResponse {
    private Long conversationId;
    private Long messageId;
    private String generatedSql;
    private QueryResultDto result;
    private String summary;
    private int attemptCount;
}