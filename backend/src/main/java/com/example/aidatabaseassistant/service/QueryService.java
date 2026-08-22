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

@Service
@RequiredArgsConstructor
public class QueryService {

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

    public PreviewResponse previewQuery(String username, QueryRequest request) {
        DatabaseConnection connection = connectionRepository.findById(request.getDatabaseConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

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
                    .build();
            queryLogRepository.save(queryLog);
        }

        String summary = result.isSuccess() ? summarizeResult(request.getQuestion(), result.getFinalResult()) : null;

        return new QueryResponse(
                conversation.getId(),
                assistantMessage.getId(),
                result.getSql(),
                result.getFinalResult(),
                summary,
                logs.size()
        );
    }

    private String summarizeResult(String question, QueryResultDto result) {
        String prompt = "Câu hỏi: " + question + "\nKết quả (dạng bảng, " + result.getRowCount()
                + " dòng): " + result.getRows() + "\nTóm tắt kết quả bằng 1-2 câu tiếng Việt tự nhiên, ngắn gọn.";
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
}