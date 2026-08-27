package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.chart.ChartClassificationResult;
import com.example.aidatabaseassistant.chart.ChartTypeClassifier;
import com.example.aidatabaseassistant.dto.ChartSeriesDto;
import com.example.aidatabaseassistant.dto.ChartSuggestionRequest;
import com.example.aidatabaseassistant.dto.ChartSuggestionResponse;
import com.example.aidatabaseassistant.dto.ChartType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChartSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(ChartSuggestionService.class);

    private final ChartTypeClassifier classifier;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;

    public ChartSuggestionResponse suggest(ChartSuggestionRequest request) {
        return suggest(request.getColumns(), request.getRows());
    }

    public ChartSuggestionResponse suggest(List<String> columns, List<Map<String, Object>> rows) {
        ChartClassificationResult result = classifier.classify(columns, rows);
        String reason = generateReason(result, rows == null ? 0 : rows.size());

        return new ChartSuggestionResponse(
                result.getChartType(),
                result.getAlternatives(),
                result.getDimensionColumn(),
                result.getXAxisLabels(),
                result.getSeries(),
                reason
        );
    }

    private String generateReason(ChartClassificationResult result, int rowCount) {
        if (result.getChartType() == ChartType.TABLE) {
            return capitalize(result.getReasonHint()) + ".";
        }

        try {
            List<String> numericColumnNames = result.getSeries().stream()
                    .map(ChartSeriesDto::getName)
                    .toList();

            String prompt = promptBuilder.buildChartReasonPrompt(
                    result.getChartType().name(),
                    result.getDimensionColumn(),
                    numericColumnNames,
                    rowCount,
                    result.getReasonHint()
            );

            String aiReason = llmClient.generateResponse(prompt).trim();
            return aiReason.isBlank() ? fallbackReason(result) : aiReason;
        } catch (Exception e) {
            log.warn("Không thể sinh lý do bằng AI cho chart suggestion, dùng fallback. Lỗi: {}", e.getMessage());
            return fallbackReason(result);
        }
    }

    private String fallbackReason(ChartClassificationResult result) {
        return "Đề xuất biểu đồ " + displayName(result.getChartType()) + " vì " + result.getReasonHint() + ".";
    }

    private String displayName(ChartType type) {
        return switch (type) {
            case BAR -> "cột (Bar)";
            case LINE -> "đường (Line)";
            case PIE -> "tròn (Pie)";
            case DONUT -> "vành khuyên (Donut)";
            case TABLE -> "dạng bảng";
        };
    }

    private String capitalize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}