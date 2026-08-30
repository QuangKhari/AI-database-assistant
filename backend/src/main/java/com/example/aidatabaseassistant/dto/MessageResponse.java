package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class MessageResponse {
    private Long id;
    private String role;
    private String content;
    private String generatedSql;
    private LocalDateTime createdAt;
    private List<QueryLogResponse> queryLogs;
    private Boolean pinned;   // MỚI

    @Getter
    @AllArgsConstructor
    public static class QueryLogResponse {
        private Integer attemptNumber;
        private String sqlText;
        private String status;
        private Integer rowCount;
        private Integer executionTimeMs;
        private String errorMessage;
    }
}