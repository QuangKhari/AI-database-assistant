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

import java.util.ArrayList;
import java.util.Collections;
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

    public BenchmarkQuestion addQuestion(Long connectionId, BenchmarkQuestionRequest request) {
        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        BenchmarkQuestion question = BenchmarkQuestion.builder()
                .connection(connection)
                .questionText(request.getQuestionText())
                .expectedSql(request.getExpectedSql())
                .build();

        return benchmarkQuestionRepository.save(question);
    }

    public BenchmarkRunResponse runBenchmark(Long connectionId) {
        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Chưa discover schema cho connection này"));

        List<BenchmarkQuestion> questions = benchmarkQuestionRepository.findByConnectionId(connectionId);
        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        List<BenchmarkResultDetail> details = new ArrayList<>();
        int correctCount = 0;

        for (BenchmarkQuestion question : questions) {
            long startTime = System.currentTimeMillis();
            String generatedSql = nl2SQLEngine.generateSQL(question.getQuestionText(), schema);
            long latencyMs = System.currentTimeMillis() - startTime;

            QueryResultDto generatedResult = queryExecutor.executeQuery(
                    connection.getHost(), connection.getPort(), connection.getDatabaseName(),
                    connection.getUsername(), rawPassword, generatedSql);

            QueryResultDto expectedResult = queryExecutor.executeQuery(
                    connection.getHost(), connection.getPort(), connection.getDatabaseName(),
                    connection.getUsername(), rawPassword, question.getExpectedSql());

            boolean isCorrect = compareResults(generatedResult, expectedResult);
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

            details.add(new BenchmarkResultDetail(
                    question.getQuestionText(), generatedSql, question.getExpectedSql(),
                    isCorrect, latencyMs, generatedResult.getError()));
        }

        double accuracy = questions.isEmpty() ? 0 : (double) correctCount / questions.size() * 100;

        return new BenchmarkRunResponse(questions.size(), correctCount, accuracy, details);
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
        int start = url.indexOf("/models/") + 8;
        int end = url.indexOf(":", start);
        return url.substring(start, end);
    }
}