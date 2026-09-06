package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.service.ChartSuggestionService;
import com.example.aidatabaseassistant.service.DataInsightService;
import com.example.aidatabaseassistant.service.QueryService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.service.SqlExplanationService;
import com.example.aidatabaseassistant.service.SqlOptimizationService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import com.example.aidatabaseassistant.service.ExcelExportService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;
    private final SqlExplanationService sqlExplanationService;
    private final ChartSuggestionService chartSuggestionService;
    private final DataInsightService dataInsightService;
    private final RateLimitService rateLimitService;
    private final SqlOptimizationService sqlOptimizationService;
    private final ExcelExportService excelExportService;

    @PostMapping("/explain")
    public ResponseEntity<ExplainSqlResponse> explain(
            Authentication authentication,
            @Valid @RequestBody ExplainSqlRequest request) {
        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                sqlExplanationService.explain(
                        authentication.getName(),
                        request
                )
        );
    }

    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(
            Authentication authentication,
            @Valid @RequestBody QueryRequest request) {
        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                queryService.previewQuery(
                        authentication.getName(),
                        request
                )
        );
    }

    @PostMapping("/execute")
    public ResponseEntity<QueryResponse> execute(
            Authentication authentication,
            @Valid @RequestBody QueryRequest request) {

        return ResponseEntity.ok(
                queryService.processQuery(
                        authentication.getName(),
                        request
                )
        );
    }

    @PostMapping("/chart-suggestion")
    public ResponseEntity<ChartSuggestionResponse> chartSuggestion(
            Authentication authentication,
            @Valid @RequestBody ChartSuggestionRequest request) {

        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                chartSuggestionService.suggest(authentication.getName(), request)
        );
    }

    // Dùng chung ChartSuggestionRequest (columns + rows) với endpoint
    // chart-suggestion vì cả hai đều là tính năng STATELESS bổ sung,
    // phân tích lại trên cùng một kết quả truy vấn (columns/rows) mà FE đã có sẵn,
    // không cần lưu vào DB hay gắn với một conversation/message cụ thể.
    @PostMapping("/data-insight")
    public ResponseEntity<DataInsightResponse> dataInsight(
            Authentication authentication,
            @Valid @RequestBody ChartSuggestionRequest request) {

        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                dataInsightService.analyze(authentication.getName(), request)
        );
    }

    @PostMapping(
            value = "/export/excel",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    public ResponseEntity<byte[]> exportExcel(
            Authentication authentication,
            @Valid @RequestBody ExcelExportRequest request) {

        byte[] file =
                excelExportService.export(request);

        return ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                )
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"query-result.xlsx\""
                )
                .contentLength(file.length)
                .body(file);
    }

    @PostMapping("/optimize")
    public ResponseEntity<OptimizeSqlResponse> optimize(
            Authentication authentication,
            @Valid @RequestBody OptimizeSqlRequest request) {

        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                sqlOptimizationService.optimize(
                        authentication.getName(),
                        request
                )
        );
    }

    @PostMapping(value = "/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeStream(Authentication authentication, @Valid @RequestBody QueryRequest request) {
        return queryService.processQueryStreaming(authentication.getName(), request);
    }
}