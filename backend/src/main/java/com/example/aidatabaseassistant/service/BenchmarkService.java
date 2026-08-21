package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BenchmarkService {

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
            String generatedSql = nl2SQLEngine.generateSQL(question.getQuestionText(), schema);

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
                    .build();
            benchmarkResultRepository.save(result);

            details.add(new BenchmarkResultDetail(
                    question.getQuestionText(), generatedSql, question.getExpectedSql(),
                    isCorrect, generatedResult.getError()));
        }

        double accuracy = questions.isEmpty() ? 0 : (double) correctCount / questions.size() * 100;

        return new BenchmarkRunResponse(questions.size(), correctCount, accuracy, details);
    }

    private boolean compareResults(QueryResultDto generated, QueryResultDto expected) {
        if (generated.getError() != null || expected.getError() != null) {
            return false;
        }
        if (generated.getRowCount() != expected.getRowCount()) {
            return false;
        }

        List<String> generatedRows = normalizeRows(generated.getRows());
        List<String> expectedRows = normalizeRows(expected.getRows());

        Collections.sort(generatedRows);
        Collections.sort(expectedRows);

        return generatedRows.equals(expectedRows);
    }

    private List<String> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> row.values().stream()
                        .map(v -> v == null ? "null" : v.toString())
                        .sorted()
                        .collect(Collectors.joining("|")))
                .collect(Collectors.toList());
    }
}