package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.chart.ChartTypeClassifier;
import com.example.aidatabaseassistant.dto.ChartSuggestionRequest;
import com.example.aidatabaseassistant.dto.DataInsightResponse;
import com.example.aidatabaseassistant.dto.TrendDirection;
import com.example.aidatabaseassistant.insight.DataInsightAnalyzer;
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
class DataInsightServiceTest {

    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LLMClient llmClient;
    @Mock
    private SchemaDiscoveryService schemaDiscoveryService;

    private DataInsightService dataInsightService;

    @BeforeEach
    void setUp() {
        // Dung DataInsightAnalyzer (va ChartTypeClassifier ben trong) THAT,
        // khong mock, vi day la thuat toan thuan - chi mock phan phu thuoc
        // AI/network, giong tinh than ChartSuggestionServiceTest.
        // schemaDiscoveryService chi mock cho du tham so constructor - cac
        // test hien co deu goi overload analyze(columns, rows) hoac
        // analyze(request) (khong co username) nen KHONG dung toi mock nay,
        // khong can stub gi ca.
        dataInsightService = new DataInsightService(
                new DataInsightAnalyzer(new ChartTypeClassifier()), promptBuilder, llmClient, schemaDiscoveryService);
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    private List<String> revenueColumns() {
        return List.of("thang", "doanh_thu");
    }

    private List<Map<String, Object>> revenueRows() {
        return List.of(
                row("thang", 1, "doanh_thu", 1000),
                row("thang", 2, "doanh_thu", 1500),
                row("thang", 3, "doanh_thu", 2000));
    }

    @Test
    void analyze_shouldUseAiSummary_whenAiCallSucceeds() {
        when(promptBuilder.buildDataInsightPrompt(any())).thenReturn("some prompt");
        when(llmClient.generateResponse("some prompt"))
                .thenReturn("Doanh thu tăng đều qua các tháng, cao nhất vào tháng 3.");

        DataInsightResponse response = dataInsightService.analyze(revenueColumns(), revenueRows());

        assertNotNull(response);
        assertEquals("Doanh thu tăng đều qua các tháng, cao nhất vào tháng 3.", response.getSummary());
        assertEquals("doanh_thu", response.getNumericColumn());
        assertEquals("thang", response.getDimensionColumn());
        assertEquals("3", response.getHighestLabel());
        assertEquals(2000.0, response.getHighestValue());
        assertEquals(TrendDirection.UP, response.getTrend());
    }

    @Test
    void analyze_shouldFallbackToTemplateSummary_whenAiThrows() {
        when(promptBuilder.buildDataInsightPrompt(any())).thenReturn("some prompt");
        when(llmClient.generateResponse("some prompt"))
                .thenThrow(new RuntimeException("Gemini hết quota"));

        DataInsightResponse response = dataInsightService.analyze(revenueColumns(), revenueRows());

        // Van phai co day du con so va mot cau tom tat (khong duoc de trong
        // hay nem loi ra ngoai chi vi AI khong kha dung).
        assertNotNull(response);
        assertNotNull(response.getSummary());
        assertFalse(response.getSummary().isBlank());
        assertTrue(response.getSummary().contains("doanh_thu"));
    }

    @Test
    void analyze_shouldFallbackToTemplateSummary_whenAiReturnsBlank() {
        when(promptBuilder.buildDataInsightPrompt(any())).thenReturn("some prompt");
        when(llmClient.generateResponse("some prompt")).thenReturn("   ");

        DataInsightResponse response = dataInsightService.analyze(revenueColumns(), revenueRows());

        assertNotNull(response);
        assertNotNull(response.getSummary());
        assertFalse(response.getSummary().isBlank());
    }

    @Test
    void analyze_shouldReturnNull_andSkipAiCall_whenAnalyzerCannotDetermineFacts() {
        // Chi 1 dong du lieu -> khong du co so tin cay -> TUYET DOI khong
        // duoc goi AI de bia insight khi khong co du lieu chac chan.
        DataInsightResponse response = dataInsightService.analyze(
                revenueColumns(), List.of(row("thang", 1, "doanh_thu", 1000)));

        assertNull(response);
        org.mockito.Mockito.verifyNoInteractions(promptBuilder, llmClient);
    }

    @Test
    void analyze_withChartSuggestionRequestOverload_shouldDelegateToSameLogic() {
        ChartSuggestionRequest request = new ChartSuggestionRequest();
        request.setColumns(revenueColumns());
        request.setRows(revenueRows());

        when(promptBuilder.buildDataInsightPrompt(any())).thenReturn("some prompt");
        when(llmClient.generateResponse("some prompt")).thenReturn("Tóm tắt từ request.");

        DataInsightResponse response = dataInsightService.analyze(request);

        assertNotNull(response);
        assertEquals("Tóm tắt từ request.", response.getSummary());
        assertEquals("doanh_thu", response.getNumericColumn());
    }
}