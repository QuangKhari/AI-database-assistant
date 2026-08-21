package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.QueryRequest;
import com.example.aidatabaseassistant.dto.QueryResponse;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class QueryService {

    private static final int MAX_RETRIES = 3;

    private final RateLimitService rateLimitService;
    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final QueryLogRepository queryLogRepository;
    private final EncryptionUtil encryptionUtil;
    private final NL2SQLEngine nl2SQLEngine;
    private final QueryValidator queryValidator;
    private final QueryExecutor queryExecutor;
    private final LLMClient llmClient;

    public QueryResponse processQuery(String username, QueryRequest request) {
        if (!rateLimitService.tryConsume(username)) {
            throw new IllegalStateException("Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(request.getDatabaseConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

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

        AttemptResult result = validateAndExecute(request.getQuestion(), schema, connection, rawPassword);

        Message assistantMessage = Message.builder()
                .conversation(conversation)
                .role("assistant")
                .content(buildAssistantContent(result))
                .generatedSql(result.sql)
                .build();
        messageRepository.save(assistantMessage);

        for (int i = 0; i < result.attemptLogs.size(); i++) {
            AttemptLog log = result.attemptLogs.get(i);
            QueryLog queryLog = QueryLog.builder()
                    .message(assistantMessage)
                    .attemptNumber(i + 1)
                    .sqlText(log.sql)
                    .status(log.success ? "SUCCESS" : "FAILED")
                    .rowCount(log.result != null ? log.result.getRowCount() : null)
                    .executionTimeMs(log.result != null ? (int) log.result.getExecutionTimeMs() : null)
                    .errorMessage(log.result != null ? log.result.getError() : null)
                    .build();
            queryLogRepository.save(queryLog);
        }

        String summary = result.success ? summarizeResult(request.getQuestion(), result.finalResult) : null;

        return new QueryResponse(
                conversation.getId(),
                assistantMessage.getId(),
                result.sql,
                result.finalResult,
                summary,
                result.attemptLogs.size()
        );
    }

    private AttemptResult validateAndExecute(String question, DatabaseSchema schema,
                                             DatabaseConnection connection, String rawPassword) {
        AttemptResult attemptResult = new AttemptResult();
        String currentSql = nl2SQLEngine.generateSQL(question, schema);
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            AttemptLog log = new AttemptLog();
            log.sql = currentSql;

            try {
                queryValidator.validate(currentSql, schema);

                QueryResultDto queryResult = queryExecutor.executeQuery(
                        connection.getHost(), connection.getPort(), connection.getDatabaseName(),
                        connection.getUsername(), rawPassword, currentSql);

                log.result = queryResult;

                if (queryResult.getError() == null) {
                    log.success = true;
                    attemptResult.attemptLogs.add(log);
                    attemptResult.success = true;
                    attemptResult.sql = currentSql;
                    attemptResult.finalResult = queryResult;
                    return attemptResult;
                }

                lastError = queryResult.getError();

            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                log.result = new QueryResultDto(java.util.List.of(), java.util.List.of(), 0, 0, lastError);
            }

            log.success = false;
            attemptResult.attemptLogs.add(log);

            if (attempt < MAX_RETRIES) {
                currentSql = nl2SQLEngine.selfCorrect(currentSql, lastError, schema);
            }
        }

        attemptResult.success = false;
        attemptResult.sql = currentSql;
        attemptResult.finalResult = new QueryResultDto(java.util.List.of(), java.util.List.of(), 0, 0, lastError);
        return attemptResult;
    }

    private String summarizeResult(String question, QueryResultDto result) {
        String prompt = "Câu hỏi: " + question + "\nKết quả (dạng bảng, " + result.getRowCount()
                + " dòng): " + result.getRows() + "\nTóm tắt kết quả bằng 1-2 câu tiếng Việt tự nhiên, ngắn gọn.";
        return llmClient.generateResponse(prompt);
    }

    private String buildAssistantContent(AttemptResult result) {
        return result.success ? "Đã trả lời thành công" : "Không thể sinh SQL hợp lệ sau " + MAX_RETRIES + " lần thử";
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

    private static class AttemptResult {
        boolean success;
        String sql;
        QueryResultDto finalResult;
        java.util.List<AttemptLog> attemptLogs = new java.util.ArrayList<>();
    }

    private static class AttemptLog {
        String sql;
        boolean success;
        QueryResultDto result;
    }
}