package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.BenchmarkQuestionRequest;
import com.example.aidatabaseassistant.dto.BenchmarkQuestionResponse;
import com.example.aidatabaseassistant.dto.BenchmarkRunResponse;
import com.example.aidatabaseassistant.service.BenchmarkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    @PostMapping("/questions/{connectionId}")
    public ResponseEntity<BenchmarkQuestionResponse> addQuestion(Authentication authentication,
                                                                 @PathVariable Long connectionId,
                                                                 @Valid @RequestBody BenchmarkQuestionRequest request) {
        return ResponseEntity.ok(benchmarkService.addQuestion(authentication.getName(), connectionId, request));
    }

    @PostMapping("/run/{connectionId}")
    public ResponseEntity<BenchmarkRunResponse> runBenchmark(Authentication authentication,
                                                             @PathVariable Long connectionId) {
        return ResponseEntity.ok(benchmarkService.runBenchmark(authentication.getName(), connectionId));
    }
}