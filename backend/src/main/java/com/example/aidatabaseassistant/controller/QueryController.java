package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.service.ChartSuggestionService;
import com.example.aidatabaseassistant.service.QueryService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.service.SqlExplanationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.aidatabaseassistant.service.DataInsightService;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;
    private final SqlExplanationService sqlExplanationService;
    private final ChartSuggestionService chartSuggestionService;
    private final RateLimitService rateLimitService;

    @PostMapping("/explain")
    public ResponseEntity<ExplainSqlResponse> explain(Authentication authentication,
                                                      @Valid @RequestBody ExplainSqlRequest request) {
        return ResponseEntity.ok(sqlExplanationService.explain(authentication.getName(), request));
    }

    @PostMapping("/preview")
    public ResponseEntity<PreviewResponse> preview(Authentication authentication,
                                                   @Valid @RequestBody QueryRequest request) {
        return ResponseEntity.ok(queryService.previewQuery(authentication.getName(), request));
    }

    @PostMapping("/execute")
    public ResponseEntity<QueryResponse> execute(Authentication authentication,
                                                 @Valid @RequestBody QueryRequest request) {
        return ResponseEntity.ok(queryService.processQuery(authentication.getName(), request));
    }

    @PostMapping("/chart-suggestion")
    public ResponseEntity<ChartSuggestionResponse> chartSuggestion(Authentication authentication,
                                                                   @Valid @RequestBody ChartSuggestionRequest request) {
        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new IllegalStateException("Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút");
        }
        return ResponseEntity.ok(chartSuggestionService.suggest(request));
    }
}