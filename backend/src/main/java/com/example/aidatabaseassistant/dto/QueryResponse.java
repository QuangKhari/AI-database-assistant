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

    // Null neu query that bai (khong co du lieu de de xuat bieu do).
    private ChartSuggestionResponse chartSuggestion;

    // Null neu khong xac dinh duoc cot dimension+numeric ro rang (vd chi 1
    // dong, khong co cot so...) - luc do FE nen hien thi "summary" o tren.
    private DataInsightResponse dataInsight;
}