package com.example.aidatabaseassistant.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
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

    /**
     * true khi câu hỏi bị chặn vì là thao tác ghi (INSERT/UPDATE/DELETE...)
     * và được xử lý qua nhánh "chỉ hiện thông báo" thay vì luồng hỏi-đáp
     * bình thường (không có SQL, không có result/chart/insight/summary).
     *
     * Field này KHÔNG nằm trong constructor cũ (8 tham số) để không phá
     * vỡ các chỗ gọi/test hiện có - mặc định là false, chỉ set true qua
     * setter ở nhánh chặn thao tác ghi trong QueryService.
     */
    private boolean blocked;

    /**
     * Constructor CŨ - giữ nguyên signature 8 tham số cho các chỗ gọi và
     * test hiện có (vd QueryControllerStreamingIntegrationTest).
     */
    public QueryResponse(
            Long conversationId,
            Long messageId,
            String generatedSql,
            QueryResultDto result,
            String summary,
            int attemptCount,
            ChartSuggestionResponse chartSuggestion,
            DataInsightResponse dataInsight
    ) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.generatedSql = generatedSql;
        this.result = result;
        this.summary = summary;
        this.attemptCount = attemptCount;
        this.chartSuggestion = chartSuggestion;
        this.dataInsight = dataInsight;
        this.blocked = false;
    }
}