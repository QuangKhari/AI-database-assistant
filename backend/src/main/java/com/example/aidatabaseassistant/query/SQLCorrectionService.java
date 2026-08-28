package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SQLCorrectionService {

    private static final int MAX_RETRIES = 3;

    private final NL2SQLEngine nl2SQLEngine;
    private final QueryValidator queryValidator;
    private final QueryExecutor queryExecutor;

    public AttemptResult run(String question, DatabaseSchema schema,
                             DatabaseConnection connection, String rawPassword) {

        AttemptResult attemptResult = new AttemptResult();

        String currentSql;
        String lastError = null;

        try {
            currentSql = nl2SQLEngine.generateSQL(question, schema);
        } catch (ReadOnlyViolationException e) {

            lastError = e.getMessage();

            attemptResult.success = false;
            attemptResult.sql = null;

            attemptResult.finalResult = new QueryResultDto(
                    List.of(),
                    List.of(),
                    0,
                    0,
                    lastError
            );

            return attemptResult;
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

            AttemptLog log = new AttemptLog();
            log.sql = currentSql;

            try {
                queryValidator.validate(currentSql, schema);

                QueryResultDto queryResult = queryExecutor.executeQuery(
                        connection.getHost(),
                        connection.getPort(),
                        connection.getDatabaseName(),
                        connection.getUsername(),
                        rawPassword,
                        currentSql
                );

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

            } catch (ReadOnlyViolationException e) {

                /*
                 * SECURITY:
                 * Nếu SQL không phải SELECT thì dừng ngay.
                 * Không cho AI self-correct thành một câu SELECT khác.
                 */
                lastError = e.getMessage();

                log.result = new QueryResultDto(
                        List.of(),
                        List.of(),
                        0,
                        0,
                        lastError
                );

                log.success = false;
                attemptResult.attemptLogs.add(log);

                attemptResult.success = false;
                attemptResult.sql = currentSql;
                attemptResult.finalResult = new QueryResultDto(
                        List.of(),
                        List.of(),
                        0,
                        0,
                        lastError
                );

                return attemptResult;

            } catch (IllegalArgumentException e) {

                lastError = e.getMessage();

                log.result = new QueryResultDto(
                        List.of(),
                        List.of(),
                        0,
                        0,
                        lastError
                );
            }

            log.success = false;
            attemptResult.attemptLogs.add(log);

            /*
             * Chỉ self-correct các lỗi thông thường:
             * - SQL syntax error
             * - bảng/cột không tồn tại
             * - lỗi thực thi query
             *
             * ReadOnlyViolationException đã return ở trên.
             */
            if (attempt < MAX_RETRIES) {
                currentSql = nl2SQLEngine.selfCorrect(
                        currentSql,
                        lastError,
                        schema
                );
            }
        }

        attemptResult.success = false;
        attemptResult.sql = currentSql;
        attemptResult.finalResult = new QueryResultDto(
                List.of(),
                List.of(),
                0,
                0,
                lastError
        );

        return attemptResult;
    }

    @Getter
    public static class AttemptResult {
        boolean success;
        String sql;
        QueryResultDto finalResult;
        List<AttemptLog> attemptLogs = new ArrayList<>();
    }

    @Getter
    public static class AttemptLog {
        String sql;
        boolean success;
        QueryResultDto result;
    }

}
