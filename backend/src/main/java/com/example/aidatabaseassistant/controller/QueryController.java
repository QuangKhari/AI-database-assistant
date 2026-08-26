package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ExplainSqlRequest;
import com.example.aidatabaseassistant.dto.ExplainSqlResponse;
import com.example.aidatabaseassistant.dto.PreviewResponse;
import com.example.aidatabaseassistant.dto.QueryRequest;
import com.example.aidatabaseassistant.dto.QueryResponse;
import com.example.aidatabaseassistant.service.QueryService;
import com.example.aidatabaseassistant.service.SqlExplanationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;
    private final SqlExplanationService sqlExplanationService;

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
}