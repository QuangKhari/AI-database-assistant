package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.BenchmarkQuestionRequest;
import com.example.aidatabaseassistant.dto.BenchmarkQuestionResponse;
import com.example.aidatabaseassistant.dto.BenchmarkRunResponse;
import com.example.aidatabaseassistant.service.BenchmarkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final BenchmarkService benchmarkService;
    private final com.example.aidatabaseassistant.service.RateLimitService rateLimitService;

    /**
     * Lấy danh sách câu hỏi benchmark của connection.
     *
     * GET /api/benchmark/questions/{connectionId}
     */
    @GetMapping("/questions/{connectionId}")
    public ResponseEntity<List<BenchmarkQuestionResponse>> getQuestions(
            Authentication authentication,
            @PathVariable Long connectionId,
            @RequestParam(required = false) String language) {

        return ResponseEntity.ok(
                benchmarkService.getQuestions(
                        authentication.getName(),
                        connectionId,
                        language
                )
        );
    }

    /**
     * Thêm một câu hỏi benchmark.
     *
     * POST /api/benchmark/questions/{connectionId}
     */
    @PostMapping("/questions/{connectionId}")
    public ResponseEntity<BenchmarkQuestionResponse> addQuestion(
            Authentication authentication,
            @PathVariable Long connectionId,
            @Valid @RequestBody BenchmarkQuestionRequest request) {

        return ResponseEntity.ok(
                benchmarkService.addQuestion(
                        authentication.getName(),
                        connectionId,
                        request
                )
        );
    }

    @PostMapping("/run/{connectionId}")
    public ResponseEntity<BenchmarkRunResponse> runBenchmark(
            Authentication authentication,
            @PathVariable Long connectionId) {
        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new com.example.aidatabaseassistant.exception.RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                benchmarkService.runBenchmark(authentication.getName(), connectionId)
        );
    }
}