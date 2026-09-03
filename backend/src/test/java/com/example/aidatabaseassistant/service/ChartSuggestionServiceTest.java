package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.chart.ChartTypeClassifier;
import com.example.aidatabaseassistant.dto.ChartSuggestionResponse;
import com.example.aidatabaseassistant.dto.ChartType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChartSuggestionServiceTest {

    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LLMClient llmClient;
    @Mock
    private SchemaDiscoveryService schemaDiscoveryService;

    private ChartSuggestionService chartSuggestionService;

    @BeforeEach
    void setUp() {
        // Dung ChartTypeClassifier THAT (khong mock) vi la thuat toan thuan,
        // khong co ly do gia lap - chi mock phan phu thuoc AI/network.
        // schemaDiscoveryService chi mock cho du tham so constructor - cac
        // test hien co deu goi overload suggest(columns, rows) (khong co
        // username) nen KHONG dung toi mock nay, khong can stub gi ca.
        chartSuggestionService = new ChartSuggestionService(new ChartTypeClassifier(), promptBuilder, llmClient, schemaDiscoveryService);
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void suggest_shouldUseAiReason_whenAiCallSucceeds() {
        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                row("thang", 1, "doanh_thu", 1000),
                row("thang", 2, "doanh_thu", 1500)
        );

        when(promptBuilder.buildChartReasonPrompt(any(), any(), any(), any(Integer.class), any()))
                .thenReturn("some prompt");
        when(llmClient.generateResponse("some prompt"))
                .thenReturn("Doanh thu tăng dần theo từng tháng nên biểu đồ đường thể hiện rõ xu hướng.");

        ChartSuggestionResponse response = chartSuggestionService.suggest(columns, rows);

        assertEquals(ChartType.LINE, response.getChartType());
        assertEquals("Doanh thu tăng dần theo từng tháng nên biểu đồ đường thể hiện rõ xu hướng.", response.getReason());
    }

    @Test
    void suggest_shouldFallbackToTemplateReason_whenAiThrows() {
        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                row("thang", 1, "doanh_thu", 1000),
                row("thang", 2, "doanh_thu", 1500)
        );

        when(promptBuilder.buildChartReasonPrompt(any(), any(), any(), any(Integer.class), any()))
                .thenReturn("some prompt");
        when(llmClient.generateResponse("some prompt"))
                .thenThrow(new RuntimeException("Gemini het quota"));

        ChartSuggestionResponse response = chartSuggestionService.suggest(columns, rows);

        // Van phai co chartType dung va co reason (khong duoc de trong/null
        // hay nem loi ra ngoai chi vi AI khong kha dung).
        assertEquals(ChartType.LINE, response.getChartType());
        assertNotNull(response.getReason());
        assertFalse(response.getReason().isBlank());
    }

    @Test
    void suggest_shouldSkipAiCall_whenChartTypeIsTable() {
        ChartSuggestionResponse response = chartSuggestionService.suggest(List.of("full_name"), List.of(
                row("full_name", "Nguyen Van A"),
                row("full_name", "Tran Thi B")
        ));

        assertEquals(ChartType.TABLE, response.getChartType());
        assertNotNull(response.getReason());
        org.mockito.Mockito.verifyNoInteractions(llmClient);
    }
}