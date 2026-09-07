package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.OptimizationIssueDto;
import com.example.aidatabaseassistant.dto.OptimizeSqlRequest;
import com.example.aidatabaseassistant.dto.OptimizeSqlResponse;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.optimization.SqlOptimizationAnalyzer;
import com.example.aidatabaseassistant.optimization.SqlOptimizationResult;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.query.ReadOnlyViolationException;
import com.example.aidatabaseassistant.query.SqlOptimizationRawData;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Tinh nang SQL Optimization (Muc 3 - tai lieu mo rong): phan tich EXPLAIN
 * that de phat hien Full Table Scan / filesort / bang tam va goi y
 * CREATE INDEX. TOAN BO phat hien do SqlOptimizationAnalyzer tinh bang
 * thuat toan thuan tu EXPLAIN + index THAT cua MySQL - Gemini CHI duoc
 * dung de VIET LAI thanh van phong tu nhien, giong het nguyen tac cua
 * DataInsightService/ChartSuggestionService.
 */
@Service
@RequiredArgsConstructor
public class SqlOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(SqlOptimizationService.class);

    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final EncryptionUtil encryptionUtil;
    private final QueryValidator queryValidator;
    private final QueryExecutor queryExecutor;
    private final SchemaLoaderService schemaLoaderService;
    private final SqlOptimizationAnalyzer analyzer;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final com.example.aidatabaseassistant.security.ConnectionAccessGuard connectionAccessGuard;

    public OptimizeSqlResponse optimize(String username, OptimizeSqlRequest request) {

        DatabaseConnection connection = connectionAccessGuard.requireOwnedConnection(
                username, request.getDatabaseConnectionId());

        if (!"mysql".equalsIgnoreCase(connection.getDbType())) {
            throw new IllegalArgumentException("Tính năng tối ưu SQL hiện chỉ hỗ trợ MySQL");
        }

        DatabaseSchema fullSchema = schemaLoaderService.loadCompleteSchema(connection.getId());

        // Validate lai (chi SELECT + bang phai co that trong schema) DU
        // client co the da qua /execute truoc do - khong tin tuong SQL
        // client tu gui thang len /optimize.
        try {
            queryValidator.validate(request.getSql(), fullSchema);
        } catch (ReadOnlyViolationException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        SqlOptimizationRawData rawData = queryExecutor.collectOptimizationData(
                connection.getHost(), connection.getPort(), connection.getDatabaseName(),
                connection.getUsername(), rawPassword, request.getSql());

        if (rawData.getError() != null) {
            throw new IllegalStateException(
                    "Không thể chạy EXPLAIN cho câu SQL này: " + rawData.getError());
        }

        SqlOptimizationResult result = analyzer.analyze(
                request.getSql(), rawData.getExplainRows(), rawData.getIndexedColumnsByTable());

        String aiSummary = generateSummary(request.getSql(), result);

        return new OptimizeSqlResponse(
                request.getSql(),
                result.getExplainRows(),
                result.getIssues(),
                result.getSuggestions(),
                aiSummary
        );
    }

    private String generateSummary(String sql, SqlOptimizationResult result) {
        try {
            String prompt = promptBuilder.buildOptimizationPrompt(
                    sql, result.getIssues(), result.getSuggestions());
            String aiText = llmClient.generateResponse(prompt).trim();
            return aiText.isBlank() ? fallbackSummary(result) : aiText;
        } catch (Exception e) {
            log.warn("Không thể sinh nhận xét tối ưu SQL bằng AI, dùng câu mẫu. Lỗi: {}", e.getMessage());
            return fallbackSummary(result);
        }
    }

    private String fallbackSummary(SqlOptimizationResult result) {
        if (result.getIssues().isEmpty()) {
            return "Không phát hiện vấn đề hiệu năng rõ rệt (không có full table scan, "
                    + "filesort hay bảng tạm) trong câu SQL này.";
        }

        List<String> parts = result.getIssues().stream()
                .map(OptimizationIssueDto::getDescription)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Phát hiện ").append(result.getIssues().size())
                .append(" vấn đề hiệu năng: ").append(String.join(" ", parts));

        if (!result.getSuggestions().isEmpty()) {
            sb.append(" Nên thêm index cho các cột được liệt kê để cải thiện tốc độ truy vấn.");
        }

        return sb.toString();
    }
}