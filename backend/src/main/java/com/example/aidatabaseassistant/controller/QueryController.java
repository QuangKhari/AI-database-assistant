package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.QueryRequest;
import com.example.aidatabaseassistant.dto.QueryResponse;
import com.example.aidatabaseassistant.service.QueryService;
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

    @PostMapping
    public ResponseEntity<QueryResponse> query(Authentication authentication,
                                               @Valid @RequestBody QueryRequest request) {
        return ResponseEntity.ok(queryService.processQuery(authentication.getName(), request));
    }
}