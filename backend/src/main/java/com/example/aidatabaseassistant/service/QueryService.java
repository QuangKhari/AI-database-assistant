package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.query.SQLCorrectionService;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QueryService {

    @org.springframework.beans.factory.annotation.Value("${gemini.api.url}")
    private String modelUrl;
    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final QueryLogRepository queryLogRepository;
    private final EncryptionUtil encryptionUtil;
    private final NL2SQLEngine nl2SQLEngine;
    private final QueryValidator queryValidator;
    private final SQLCorrectionService sqlCorrectionService;
    private final LLMClient llmClient;
    private final RateLimitService rateLimitService;
    private final ChartSuggestionService chartSuggestionService;

    public PreviewResponse previewQuery(String username, QueryRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(request.getDatabaseConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
        }

        DatabaseSchema schema = schemaRepository.findByConnectionId(connection.getId())
                .orElseThrow(() -> new IllegalArgumentException("Chưa discover schema cho connection này"));

        String generatedSql = nl2SQLEngine.generateSQL(request.getQuestion(), schema);

        try {
            queryValidator.validate(generatedSql, schema);
            return new PreviewResponse(generatedSql, true, null);
        } catch (IllegalArgumentException e) {
            return new PreviewResponse(generatedSql, false, e.getMessage());
        }
    }

    public QueryResponse processQuery(String username, QueryRequest request) {
        if (!rateLimitService.tryConsume(username)) {
            throw new IllegalStateException("Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(request.getDatabaseConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
        }

        DatabaseSchema schema = schemaRepository.findByConnectionId(connection.getId())
                .orElseThrow(() -> new IllegalArgumentException("Chưa discover schema cho connection này"));

        Conversation conversation = getOrCreateConversation(user, connection, request);

        Message userMessage = Message.builder()
                .conversation(conversation)
                .role("user")
                .content(request.getQuestion())
                .build();
        messageRepository.save(userMessage);

        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        SQLCorrectionService.AttemptResult result = sqlCorrectionService.run(
                request.getQuestion(), schema, connection, rawPassword);

        Message assistantMessage = Message.builder()
                .conversation(conversation)
                .role("assistant")
                .content(result.isSuccess() ? "Đã trả lời thành công" : "Không thể sinh SQL hợp lệ sau nhiều lần thử")
                .generatedSql(result.getSql())
                .build();
        messageRepository.save(assistantMessage);

        var logs = result.getAttemptLogs();
        for (int i = 0; i < logs.size(); i++) {
            var log = logs.get(i);
            QueryLog queryLog = QueryLog.builder()
                    .message(assistantMessage)
                    .attemptNumber(i + 1)
                    .sqlText(log.getSql())
                    .status(log.isSuccess() ? "SUCCESS" : "FAILED")
                    .rowCount(log.getResult() != null ? log.getResult().getRowCount() : null)
                    .executionTimeMs(log.getResult() != null ? (int) log.getResult().getExecutionTimeMs() : null)
                    .errorMessage(log.getResult() != null ? log.getResult().getError() : null)
                    .question(request.getQuestion())
                    .modelUsed(extractModelName(modelUrl))
                    .retryCount(logs.size())
                    .build();
            queryLogRepository.save(queryLog);
        }

        String summary = result.isSuccess() ? summarizeResult(request.getQuestion(), result.getFinalResult()) : null;
        ChartSuggestionResponse chartSuggestion = result.isSuccess()
                ? buildChartSuggestion(result.getFinalResult())
                : null;

        return new QueryResponse(
                conversation.getId(),
                assistantMessage.getId(),
                result.getSql(),
                result.getFinalResult(),
                summary,
                logs.size(),
                chartSuggestion
        );
    }

    private ChartSuggestionResponse buildChartSuggestion(QueryResultDto finalResult) {
        try {
            return chartSuggestionService.suggest(finalResult.getColumns(), finalResult.getRows());
        } catch (Exception e) {
            return null;
        }
    }

    private String summarizeResult(String question, QueryResultDto result) {
        List<java.util.Map<String, Object>> limitedRows = result.getRows().size() > 20
                ? result.getRows().subList(0, 20)
                : result.getRows();

        String prompt = "Câu hỏi: " + question + "\nKết quả (hiển thị " + limitedRows.size()
                + "/" + result.getRowCount() + " dòng): " + limitedRows
                + "\nTóm tắt kết quả bằng 1-2 câu tiếng Việt tự nhiên, ngắn gọn.";
        return llmClient.generateResponse(prompt);
    }

    private Conversation getOrCreateConversation(User user, DatabaseConnection connection, QueryRequest request) {
        if (request.getConversationId() != null) {
            return conversationRepository.findById(request.getConversationId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy conversation"));
        }

        Conversation conversation = Conversation.builder()
                .user(user)
                .connection(connection)
                .title(request.getQuestion().length() > 50
                        ? request.getQuestion().substring(0, 50) + "..."
                        : request.getQuestion())
                .build();
        return conversationRepository.save(conversation);
    }

    private String extractModelName(String url) {
        int start = url.indexOf("/models/") + 8;
        int end = url.indexOf(":", start);
        return url.substring(start, end);
    }
}