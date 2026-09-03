package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.service.ChartSuggestionService;
import com.example.aidatabaseassistant.service.DataInsightService;
import com.example.aidatabaseassistant.service.ExcelExportService;
import com.example.aidatabaseassistant.service.QueryService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.service.SqlExplanationService;
import com.example.aidatabaseassistant.service.SqlOptimizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryControllerTest {

    @Mock
    private QueryService queryService;

    @Mock
    private SqlExplanationService sqlExplanationService;

    @Mock
    private ChartSuggestionService chartSuggestionService;

    @Mock
    private DataInsightService dataInsightService;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private SqlOptimizationService sqlOptimizationService;

    @Mock
    private ExcelExportService excelExportService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private QueryController queryController;

    // =========================================================
    // EXPLAIN
    // =========================================================

    @Test
    void explain_shouldReturnOkResponse() {

        when(authentication.getName())
                .thenReturn("testuser");

        ExplainSqlRequest request =
                mock(ExplainSqlRequest.class);

        ExplainSqlResponse expectedResponse =
                mock(ExplainSqlResponse.class);

        when(sqlExplanationService.explain(
                "testuser",
                request
        )).thenReturn(expectedResponse);

        ResponseEntity<ExplainSqlResponse> response =
                queryController.explain(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                expectedResponse,
                response.getBody()
        );

        verify(sqlExplanationService)
                .explain(
                        "testuser",
                        request
                );
    }

    // =========================================================
    // PREVIEW
    // =========================================================

    @Test
    void preview_shouldReturnOkResponse() {

        when(authentication.getName())
                .thenReturn("testuser");

        QueryRequest request =
                mock(QueryRequest.class);

        PreviewResponse expectedResponse =
                mock(PreviewResponse.class);

        when(queryService.previewQuery(
                "testuser",
                request
        )).thenReturn(expectedResponse);

        ResponseEntity<PreviewResponse> response =
                queryController.preview(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                expectedResponse,
                response.getBody()
        );

        verify(queryService)
                .previewQuery(
                        "testuser",
                        request
                );
    }

    // =========================================================
    // EXECUTE
    // =========================================================

    @Test
    void execute_shouldReturnOkResponse() {

        when(authentication.getName())
                .thenReturn("testuser");

        QueryRequest request =
                mock(QueryRequest.class);

        QueryResponse expectedResponse =
                mock(QueryResponse.class);

        when(queryService.processQuery(
                "testuser",
                request
        )).thenReturn(expectedResponse);

        ResponseEntity<QueryResponse> response =
                queryController.execute(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                expectedResponse,
                response.getBody()
        );

        verify(queryService)
                .processQuery(
                        "testuser",
                        request
                );
    }

    // =========================================================
    // CHART SUGGESTION
    // =========================================================

    @Test
    void chartSuggestion_shouldReturnOkResponse_whenRateLimitAllows() {

        when(authentication.getName())
                .thenReturn("testuser");

        ChartSuggestionRequest request =
                mock(ChartSuggestionRequest.class);

        ChartSuggestionResponse expectedResponse =
                mock(ChartSuggestionResponse.class);

        when(rateLimitService.tryConsume("testuser"))
                .thenReturn(true);

        when(chartSuggestionService.suggest(
                "testuser",
                request
        )).thenReturn(expectedResponse);

        ResponseEntity<ChartSuggestionResponse> response =
                queryController.chartSuggestion(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                expectedResponse,
                response.getBody()
        );

        verify(rateLimitService)
                .tryConsume("testuser");

        verify(chartSuggestionService)
                .suggest(
                        "testuser",
                        request
                );
    }

    @Test
    void chartSuggestion_shouldThrowRateLimitExceededException_whenLimitExceeded() {

        when(authentication.getName())
                .thenReturn("testuser");

        ChartSuggestionRequest request =
                mock(ChartSuggestionRequest.class);

        when(rateLimitService.tryConsume("testuser"))
                .thenReturn(false);

        assertThrows(
                RateLimitExceededException.class,
                () -> queryController.chartSuggestion(
                        authentication,
                        request
                )
        );

        verify(rateLimitService)
                .tryConsume("testuser");

        verifyNoInteractions(chartSuggestionService);
    }

    // =========================================================
    // DATA INSIGHT
    // =========================================================

    @Test
    void dataInsight_shouldReturnOkResponse_whenRateLimitAllows() {

        when(authentication.getName())
                .thenReturn("testuser");

        ChartSuggestionRequest request =
                mock(ChartSuggestionRequest.class);

        DataInsightResponse expectedResponse =
                mock(DataInsightResponse.class);

        when(rateLimitService.tryConsume("testuser"))
                .thenReturn(true);

        when(dataInsightService.analyze(
                "testuser",
                request
        )).thenReturn(expectedResponse);

        ResponseEntity<DataInsightResponse> response =
                queryController.dataInsight(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                expectedResponse,
                response.getBody()
        );

        verify(rateLimitService)
                .tryConsume("testuser");

        verify(dataInsightService)
                .analyze(
                        "testuser",
                        request
                );
    }

    @Test
    void dataInsight_shouldThrowRateLimitExceededException_whenLimitExceeded() {

        when(authentication.getName())
                .thenReturn("testuser");

        ChartSuggestionRequest request =
                mock(ChartSuggestionRequest.class);

        when(rateLimitService.tryConsume("testuser"))
                .thenReturn(false);

        assertThrows(
                RateLimitExceededException.class,
                () -> queryController.dataInsight(
                        authentication,
                        request
                )
        );

        verify(rateLimitService)
                .tryConsume("testuser");

        verifyNoInteractions(dataInsightService);
    }

    // =========================================================
    // EXPORT EXCEL
    // =========================================================

    @Test
    void exportExcel_shouldReturnExcelFile() {

        ExcelExportRequest request =
                mock(ExcelExportRequest.class);

        byte[] expectedFile =
                new byte[]{
                        1, 2, 3, 4
                };

        when(excelExportService.export(request))
                .thenReturn(expectedFile);

        ResponseEntity<byte[]> response =
                queryController.exportExcel(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertArrayEquals(
                expectedFile,
                response.getBody()
        );

        assertNotNull(
                response.getHeaders()
                        .getContentType()
        );

        assertEquals(
                "application",
                response.getHeaders()
                        .getContentType()
                        .getType()
        );

        assertEquals(
                "vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getHeaders()
                        .getContentType()
                        .getSubtype()
        );

        assertEquals(
                "query-result.xlsx",
                response.getHeaders()
                        .getContentDisposition()
                        .getFilename()
        );

        assertEquals(
                expectedFile.length,
                response.getHeaders()
                        .getContentLength()
        );

        verify(excelExportService)
                .export(request);
    }

    @Test
    void exportExcel_shouldReturnEmptyExcelFile_whenServiceReturnsEmptyBytes() {

        ExcelExportRequest request =
                mock(ExcelExportRequest.class);

        byte[] expectedFile =
                new byte[0];

        when(excelExportService.export(request))
                .thenReturn(expectedFile);

        ResponseEntity<byte[]> response =
                queryController.exportExcel(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertNotNull(response.getBody());

        assertEquals(
                0,
                response.getBody().length
        );

        verify(excelExportService)
                .export(request);
    }

    @Test
    void exportExcel_shouldPropagateExceptionFromService() {

        ExcelExportRequest request =
                mock(ExcelExportRequest.class);

        when(excelExportService.export(request))
                .thenThrow(
                        new IllegalArgumentException(
                                "Danh sách cột không được để trống"
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> queryController.exportExcel(
                        authentication,
                        request
                )
        );

        verify(excelExportService)
                .export(request);
    }

    // =========================================================
    // OPTIMIZE
    // =========================================================

    @Test
    void optimize_shouldReturnOkResponse_whenRateLimitAllows() {

        when(authentication.getName())
                .thenReturn("testuser");

        OptimizeSqlRequest request =
                mock(OptimizeSqlRequest.class);

        OptimizeSqlResponse expectedResponse =
                mock(OptimizeSqlResponse.class);

        when(rateLimitService.tryConsume("testuser"))
                .thenReturn(true);

        when(sqlOptimizationService.optimize(
                "testuser",
                request
        )).thenReturn(expectedResponse);

        ResponseEntity<OptimizeSqlResponse> response =
                queryController.optimize(
                        authentication,
                        request
                );

        assertEquals(
                200,
                response.getStatusCode().value()
        );

        assertSame(
                expectedResponse,
                response.getBody()
        );

        verify(rateLimitService)
                .tryConsume("testuser");

        verify(sqlOptimizationService)
                .optimize(
                        "testuser",
                        request
                );
    }

    @Test
    void optimize_shouldThrowRateLimitExceededException_whenLimitExceeded() {

        when(authentication.getName())
                .thenReturn("testuser");

        OptimizeSqlRequest request =
                mock(OptimizeSqlRequest.class);

        when(rateLimitService.tryConsume("testuser"))
                .thenReturn(false);

        assertThrows(
                RateLimitExceededException.class,
                () -> queryController.optimize(
                        authentication,
                        request
                )
        );

        verify(rateLimitService)
                .tryConsume("testuser");

        verifyNoInteractions(sqlOptimizationService);
    }

    // =========================================================
    // STREAM
    // =========================================================

    @Test
    void executeStream_shouldDelegateToQueryService() {

        when(authentication.getName())
                .thenReturn("testuser");

        QueryRequest request =
                mock(QueryRequest.class);

        SseEmitter expectedEmitter =
                new SseEmitter();

        when(queryService.processQueryStreaming(
                "testuser",
                request
        )).thenReturn(expectedEmitter);

        SseEmitter response =
                queryController.executeStream(
                        authentication,
                        request
                );

        assertSame(
                expectedEmitter,
                response
        );

        verify(queryService)
                .processQueryStreaming(
                        "testuser",
                        request
                );
    }
}