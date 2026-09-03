package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.aidatabaseassistant.entity.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final QueryValidator queryValidator;
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
                    // Validate cả 2 SQL trước khi execute bất kỳ câu nào
                    queryValidator.validate(generatedSql, schema);
                    queryValidator.validate(question.getExpectedSql(), schema);

                    // Chỉ execute sau khi cả 2 đều hợp lệ.
                    //
                    // QUAN TRỌNG: phải truyền connection.getDbType() - nếu
                    // dùng overload 6-tham-số (không có dbType) thì
                    // QueryExecutor sẽ MẶC ĐỊNH mở connection theo MySQL bất
                    // kể connection thực tế là PostgreSQL/Excel, khiến
                    // benchmark chạy sai driver và luôn lỗi trên các
                    // connection không phải MySQL.
                    generatedResult = queryExecutor.executeQuery(
                            connection.getDbType(),
                            connection.getHost(),
                            connection.getPort(),
                            connection.getDatabaseName(),
                            connection.getUsername(),
                            rawPassword,
                            generatedSql
                    );

                    QueryResultDto expectedResult = queryExecutor.executeQuery(
                            connection.getDbType(),
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

        // Có lỗi SQL thì thất bại
        if (generated.getError() != null || expected.getError() != null) {
            return false;
        }

        // Khác số dòng => sai
        if (generated.getRowCount() != expected.getRowCount()) {
            return false;
        }

        List<Map<String, Object>> generatedRows = generated.getRows();
        List<Map<String, Object>> expectedRows = expected.getRows();

        if (generatedRows.isEmpty() && expectedRows.isEmpty()) {
            return true;
        }

        // Lấy danh sách cột của SQL chuẩn
        List<String> expectedColumns =
                new ArrayList<>(expectedRows.get(0).keySet());

        List<String> generatedNormalized = generatedRows.stream()
                .map(row -> expectedColumns.stream()
                        .map(col -> String.valueOf(row.get(col)))
                        .collect(Collectors.joining("|")))
                .sorted()
                .toList();

        List<String> expectedNormalized = expectedRows.stream()
                .map(row -> expectedColumns.stream()
                        .map(col -> String.valueOf(row.get(col)))
                        .collect(Collectors.joining("|")))
                .sorted()
                .toList();

        return generatedNormalized.equals(expectedNormalized);
    }

    private List<String> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> row.values().stream()
                        .map(v -> v == null ? "null" : v.toString())
                        .sorted()
                        .collect(Collectors.joining("|")))
                .collect(Collectors.toList());
    }

    private String extractModelName(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }

        int modelStart = url.indexOf("/models/");

        if (modelStart < 0) {
            return "unknown";
        }

        int start = modelStart + "/models/".length();

        int end = url.indexOf(":", start);

        if (end < 0) {
            end = url.length();
        }

        if (start >= end) {
            return "unknown";
        }

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