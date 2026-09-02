package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.dto.AnomalyDto;
import com.example.aidatabaseassistant.dto.ChartSuggestionRequest;
import com.example.aidatabaseassistant.dto.DataInsightResponse;
import com.example.aidatabaseassistant.insight.DataInsightAnalyzer;
import com.example.aidatabaseassistant.insight.DataInsightFacts;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Toan bo SO LIEU (tang truong %, cao/thap nhat, xu huong, ty trong, bat
 * thuong) luon do DataInsightAnalyzer (thuat toan thuan) tinh - KHONG BAO
 * GIO de AI tu tinh, vi AI co the bia so sai. Gemini CHI duoc dung de VIET
 * LAI cac con so nay thanh van phong tu nhien; neu Gemini loi thi fallback
 * ve cau mau ghep truc tiep tu facts (dam bao con so luon dung 100%).
 */
@Service
@RequiredArgsConstructor
public class DataInsightService {

    private static final Logger log = LoggerFactory.getLogger(DataInsightService.class);

    private final DataInsightAnalyzer analyzer;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final SchemaDiscoveryService schemaDiscoveryService;

    // Giu lai overload cu (khong username) de tuong thich nguoc - bat buoc,
    // vi DataInsightServiceTest.analyze_withChartSuggestionRequestOverload_...
    // dang goi truc tiep ban 1-tham-so nay (khong sua thi test se KHONG BIEN
    // DICH DUOC, khong chi la fail runtime).
    public DataInsightResponse analyze(ChartSuggestionRequest request) {
        return analyze(request.getColumns(), request.getRows());
    }

    public DataInsightResponse analyze(String username, ChartSuggestionRequest request) {
        Map<String, Boolean> schemaKeyColumns =
                schemaDiscoveryService.resolveKeyColumnMap(username, request.getConnectionId());

        return analyze(request.getColumns(), request.getRows(), schemaKeyColumns);
    }

    public DataInsightResponse analyze(List<String> columns, List<Map<String, Object>> rows) {
        return analyze(columns, rows, Map.of());
    }

    public DataInsightResponse analyze(List<String> columns, List<Map<String, Object>> rows,
                                       Map<String, Boolean> schemaKeyColumns) {
        DataInsightFacts facts = analyzer.analyze(columns, rows, schemaKeyColumns);
        if (facts == null) {
            // Khong du dieu kien (vd khong xac dinh duoc cot so lieu ro rang)
            // - tra ve null de QueryService fallback ve summary cu, TUYET
            // DOI khong duoc bia insight khi khong co co so tin cay.
            return null;
        }

        String summary = generateSummary(facts);

        return new DataInsightResponse(
                facts.getNumericColumn(), facts.getDimensionColumn(),
                facts.getHighestLabel(), facts.getHighestValue(),
                facts.getLowestLabel(), facts.getLowestValue(),
                facts.getGrowthPercent(), facts.getTrend(),
                facts.getPeriodStartLabel(), facts.getPeriodEndLabel(),
                facts.getTopShareLabel(), facts.getTopSharePercent(),
                facts.getAnomalies(), summary
        );
    }

    private String generateSummary(DataInsightFacts facts) {
        try {
            String prompt = promptBuilder.buildDataInsightPrompt(facts);
            String aiSummary = llmClient.generateResponse(prompt).trim();
            return aiSummary.isBlank() ? fallbackSummary(facts) : aiSummary;
        } catch (Exception e) {
            log.warn("Không thể sinh data insight bằng AI, dùng câu tóm tắt mẫu. Lỗi: {}", e.getMessage());
            return fallbackSummary(facts);
        }
    }

    private String fallbackSummary(DataInsightFacts facts) {
        // Dung dinh dang so kieu quoc te (dau CHAM thap phan) thay vi locale
        // vi-VN (dau PHAY) - de nhat quan voi cach Gemini thuong viet so khi
        // AI hoat dong binh thuong, tranh doi dinh dang giua 2 truong hop.
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "%s cao nhất tại %s (%.2f), thấp nhất tại %s (%.2f).",
                facts.getNumericColumn(), facts.getHighestLabel(), facts.getHighestValue(),
                facts.getLowestLabel(), facts.getLowestValue()));

        if (facts.getGrowthPercent() != null) {
            if (facts.getPeriodStartLabel() != null && facts.getPeriodEndLabel() != null) {
                sb.append(String.format(Locale.ROOT, " Tăng trưởng %.1f%% từ %s đến %s.",
                        facts.getGrowthPercent(), facts.getPeriodStartLabel(), facts.getPeriodEndLabel()));
            } else {
                sb.append(String.format(Locale.ROOT, " Tăng trưởng %.1f%% từ đầu đến cuối kỳ.",
                        facts.getGrowthPercent()));
            }
        }
        if (facts.getTopSharePercent() != null) {
            sb.append(String.format(Locale.ROOT, " '%s' chiếm %.1f%% tổng.",
                    facts.getTopShareLabel(), facts.getTopSharePercent()));
        }
        if (facts.getAnomalies() != null && !facts.getAnomalies().isEmpty()) {
            AnomalyDto first = facts.getAnomalies().get(0);
            sb.append(String.format(Locale.ROOT, " Phát hiện điểm %s tại %s.",
                    first.getDirection(), first.getLabel()));
        }
        return sb.toString();
    }
}