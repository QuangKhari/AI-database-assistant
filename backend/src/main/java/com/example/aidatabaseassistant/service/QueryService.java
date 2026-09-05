package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.config.QueryExecutionProperties;
import com.example.aidatabaseassistant.dto.QueryExecuteRequest;
import com.example.aidatabaseassistant.dto.QueryResponse;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.QueryLog;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.QueryLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueryService {

    private final MessageRepository messageRepository;
    private final QueryLogRepository queryLogRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final ConnectionService connectionService;
    private final EncryptionUtil encryptionUtil;
    private final QueryValidator queryValidator;
    private final QueryExecutor queryExecutor;
    private final QueryExecutionProperties properties;
    private final QueryExecutionGuard executionGuard;

    public QueryResponse execute(String username, QueryExecuteRequest request) {
        Message message = messageRepository
                .findByIdAndConversationUserUsernameIgnoreCase(request.assistantMessageId(), username)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy SQL preview thuộc tài khoản này"));

        if (!"assistant".equalsIgnoreCase(message.getRole())
                || message.getGeneratedSql() == null
                || message.getGeneratedSql().isBlank()) {
            throw new IllegalArgumentException("Message không chứa SQL preview có thể thực thi");
        }
        if (Boolean.FALSE.equals(message.getGeneratedSqlValid())) {
            throw new IllegalArgumentException("SQL preview chưa vượt qua kiểm tra an toàn");
        }

        DatabaseConnection connection = connectionService.getOwnedActiveConnection(
                username, message.getConversation().getConnection().getId());
        DatabaseSchema schema = schemaRepository.findByConnectionId(connection.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Hãy đồng bộ schema trước khi chạy query"));

        String sql = message.getGeneratedSql().trim();
        queryValidator.validate(sql, schema);
        int timeoutSeconds = resolveTimeout(request.timeoutSeconds());

        QueryResultDto result;
        try (QueryExecutionGuard.Lease ignored = executionGuard.acquire(username)) {
            String password = encryptionUtil.decrypt(connection.getEncryptedPassword());
            result = queryExecutor.executeQuery(
                    new TargetDatabaseCredentials(
                            connection.getHost(), connection.getPort(), connection.getDatabaseName(),
                            connection.getUsername(), password),
                    sql, timeoutSeconds);
        }

        String status = statusOf(result);
        int attemptNumber = queryLogRepository.findByMessageIdOrderByAttemptNumberAsc(message.getId()).size() + 1;
        queryLogRepository.save(QueryLog.builder()
                .message(message)
                .attemptNumber(attemptNumber)
                .sqlText(sql)
                .status(status)
                .rowCount(result.getRowCount())
                .executionTimeMs((int) Math.min(Integer.MAX_VALUE, result.getExecutionTimeMs()))
                .errorMessage(result.getError())
                .build());

        return new QueryResponse(
                message.getConversation().getId(), message.getId(), sql,
                status, timeoutSeconds, result);
    }

    private int resolveTimeout(Integer requestedTimeoutSeconds) {
        int timeout = requestedTimeoutSeconds == null
                ? properties.getDefaultTimeoutSeconds()
                : requestedTimeoutSeconds;
        if (timeout < 1 || timeout > properties.getMaxTimeoutSeconds()) {
            throw new IllegalArgumentException(
                    "Query timeout phải nằm trong khoảng 1-"
                            + properties.getMaxTimeoutSeconds() + " giây");
        }
        return timeout;
    }

    private String statusOf(QueryResultDto result) {
        if (result.getErrorCode() == null) {
            return "SUCCESS";
        }
        return "QUERY_TIMEOUT".equals(result.getErrorCode()) ? "TIMEOUT" : "FAILED";
    }
}
