package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
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

    /**
     * Xoá một câu hỏi benchmark.
     *
     * DELETE /api/benchmark/questions/{connectionId}/{questionId}
     */
    @DeleteMapping("/questions/{connectionId}/{questionId}")
    public ResponseEntity<Void> deleteQuestion(
            Authentication authentication,
            @PathVariable Long connectionId,
            @PathVariable Long questionId) {

        benchmarkService.deleteQuestion(
                authentication.getName(),
                connectionId,
                questionId
        );

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/run/{connectionId}")
    public ResponseEntity<BenchmarkRunResponse> runBenchmark(
            Authentication authentication,
            @PathVariable Long connectionId,
            @RequestParam(required = false) String language) {

        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new com.example.aidatabaseassistant.exception.RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                benchmarkService.runBenchmark(
                        authentication.getName(),
                        connectionId,
                        language
                )
        );
    }

    @PostMapping("/questions/{connectionId}/generate-sql")
    public ResponseEntity<BenchmarkGenerateSqlResponse> generateExpectedSql(
            Authentication authentication,
            @PathVariable Long connectionId,
            @Valid @RequestBody BenchmarkGenerateSqlRequest request) {

        return ResponseEntity.ok(
                benchmarkService.generateExpectedSql(
                        authentication.getName(),
                        connectionId,
                        request
                )
        );
    }
}