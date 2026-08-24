package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.aidatabaseassistant.entity.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;

@Service
@RequiredArgsConstructor
@Transactional
public class BenchmarkService {

    @org.springframework.beans.factory.annotation.Value("${gemini.api.url}")
    private String modelUrl;
    private final BenchmarkQuestionRepository benchmarkQuestionRepository;
    private final BenchmarkResultRepository benchmarkResultRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final EncryptionUtil encryptionUtil;
    private final NL2SQLEngine nl2SQLEngine;
    private final QueryExecutor queryExecutor;
    private final UserRepository userRepository;

    public BenchmarkQuestionResponse addQuestion(String username, Long connectionId, BenchmarkQuestionRequest request) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);

        BenchmarkQuestion question = BenchmarkQuestion.builder()
                .connection(connection)
                .questionText(request.getQuestionText())
                .expectedSql(request.getExpectedSql())
                .build();

        BenchmarkQuestion saved = benchmarkQuestionRepository.save(question);

        return new BenchmarkQuestionResponse(
                saved.getId(),
                saved.getQuestionText(),
                saved.getExpectedSql(),
                connectionId
        );
    }

    public BenchmarkRunResponse runBenchmark(String username, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);

        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Chưa discover schema cho connection này"));

        List<BenchmarkQuestion> questions =
                benchmarkQuestionRepository.findByConnectionId(connectionId);

        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        List<BenchmarkResultDetail> details = new ArrayList<>();
        int correctCount = 0;

        for (BenchmarkQuestion question : questions) {

            long startTime = System.currentTimeMillis();

            String generatedSql = null;
            String errorMessage = null;

            try {
                // Gọi Gemini có retry khi gặp 429
                generatedSql = generateSqlWithRetry(
                        question.getQuestionText(),
                        schema
                );

            } catch (Exception e) {
                errorMessage = e.getMessage();
            }

            long latencyMs = System.currentTimeMillis() - startTime;

            boolean isCorrect = false;

            QueryResultDto generatedResult = null;

            // Chỉ execute SQL nếu AI sinh SQL thành công
            if (generatedSql != null && !generatedSql.isBlank()) {

                try {
                    generatedResult = queryExecutor.executeQuery(
                            connection.getHost(),
                            connection.getPort(),
                            connection.getDatabaseName(),
                            connection.getUsername(),
                            rawPassword,
                            generatedSql
                    );

                    QueryResultDto expectedResult = queryExecutor.executeQuery(
                            connection.getHost(),
                            connection.getPort(),
                            connection.getDatabaseName(),
                            connection.getUsername(),
                            rawPassword,
                            question.getExpectedSql()
                    );

                    isCorrect = compareResults(
                            generatedResult,
                            expectedResult
                    );

                    if (generatedResult.getError() != null) {
                        errorMessage = generatedResult.getError();
                    }

                } catch (Exception e) {
                    errorMessage = e.getMessage();
                }
            }

            if (isCorrect) {
                correctCount++;
            }

            BenchmarkResult result = BenchmarkResult.builder()
                    .benchmarkQuestion(question)
                    .generatedSql(generatedSql)
                    .expectedSql(question.getExpectedSql())
                    .isCorrect(isCorrect)
                    .latencyMs(latencyMs)
                    .modelUsed(extractModelName(modelUrl))
                    .build();

            benchmarkResultRepository.save(result);

            details.add(
                    new BenchmarkResultDetail(
                            question.getQuestionText(),
                            generatedSql,
                            question.getExpectedSql(),
                            isCorrect,
                            latencyMs,
                            errorMessage
                    )
            );

            /*
             * Gemini Free Tier:
             * 15 requests/phút
             *
             * Chờ 5 giây giữa các câu để giảm nguy cơ 429.
             */
            sleep(5000);
        }

        double accuracy = questions.isEmpty()
                ? 0
                : (double) correctCount / questions.size() * 100;

        return new BenchmarkRunResponse(
                questions.size(),
                correctCount,
                accuracy,
                details
        );
    }

    private String generateSqlWithRetry(
            String question,
            DatabaseSchema schema) {

        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {

            try {
                return nl2SQLEngine.generateSQL(question, schema);

            } catch (RuntimeException e) {

                String message = e.getMessage();

                boolean isRateLimit =
                        message != null &&
                                (
                                        message.contains("429") ||
                                                message.contains("Too Many Requests") ||
                                                message.contains("RESOURCE_EXHAUSTED")
                                );

                if (!isRateLimit) {
                    throw e;
                }

                System.out.println(
                        "Gemini rate limit (429). " +
                                "Attempt " + attempt + "/" + maxRetries
                );

                if (attempt == maxRetries) {
                    throw e;
                }

                // Chờ 40 giây trước khi retry
                sleep(40000);
            }
        }

        throw new RuntimeException("Không thể generate SQL");
    }

    private boolean compareResults(QueryResultDto generated,
                                   QueryResultDto expected) {

        // 1. Nếu một trong hai câu SQL bị lỗi -> sai
        if (generated.getError() != null || expected.getError() != null) {
            return false;
        }

        // 2. Khác số dòng -> sai
        if (generated.getRowCount() != expected.getRowCount()) {
            return false;
        }

        List<Map<String, Object>> generatedRows = generated.getRows();
        List<Map<String, Object>> expectedRows = expected.getRows();

        // 3. Cả hai đều không có dữ liệu -> đúng
        if (generatedRows.isEmpty() && expectedRows.isEmpty()) {
            return true;
        }

        // 4. Nếu số cột khác nhau -> sai
        if (generatedRows.get(0).size() != expectedRows.get(0).size()) {
            return false;
        }

        /*
         * Không so sánh tên column/alias.
         *
         * Ví dụ:
         *
         * SELECT AVG(price) AS total
         *
         * và
         *
         * SELECT AVG(price) AS average
         *
         * đều trả về cùng một giá trị.
         *
         * Vì vậy benchmark chỉ tập trung vào dữ liệu.
         */

        List<String> generatedNormalized = normalizeRows(generatedRows);
        List<String> expectedNormalized = normalizeRows(expectedRows);

        // 5. Không phụ thuộc thứ tự dòng
        Collections.sort(generatedNormalized);
        Collections.sort(expectedNormalized);

        return generatedNormalized.equals(expectedNormalized);
    }

    private List<String> normalizeRows(List<Map<String, Object>> rows) {

        return rows.stream()
                .map(row -> row.values().stream()
                        .map(value -> {

                            if (value == null) {
                                return "null";
                            }

                            return value.toString().trim();
                        })
                        .collect(Collectors.joining("|")))
                .sorted()
                .toList();
    }

    private String extractModelName(String url) {
        int start = url.indexOf("/models/") + 8;
        int end = url.indexOf(":", start);
        return url.substring(start, end);
    }

    private DatabaseConnection getOwnedConnection(String username, Long connectionId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
        }

        return connection;
    }

    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new RuntimeException(
                    "Benchmark bị gián đoạn",
                    e
            );
        }
    }
}