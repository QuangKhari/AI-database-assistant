package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.config.QueryExecutionProperties;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException;
import com.example.aidatabaseassistant.service.TargetDatabaseClient;
import com.example.aidatabaseassistant.service.TargetDatabaseCredentials;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class QueryExecutor {

    private final TargetDatabaseClient targetDatabaseClient;
    private final QueryExecutionProperties properties;

    /**
     * Compatibility overload for the offline benchmark service. User-facing execution always uses
     * the saved preview flow below with ownership checks in QueryService.
     */
    public QueryResultDto executeQuery(
            String host, Integer port, String databaseName,
            String username, String password, String sql) {
        return executeQuery(
                new TargetDatabaseCredentials(host, port, databaseName, username, password), sql, 20);
    }

    public QueryResultDto executeQuery(
            TargetDatabaseCredentials credentials, String sql, int timeoutSeconds) {
        long startedAt = System.nanoTime();
        int maxRows = properties.getMaxRows();

        try (Connection connection = targetDatabaseClient.openReadOnlyConnection(credentials);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows + 1);
            statement.setFetchSize(Math.min(100, maxRows));

            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                List<String> columns = uniqueColumnLabels(metadata);
                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;

                while (resultSet.next()) {
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= columns.size(); index++) {
                        row.put(columns.get(index - 1), resultSet.getObject(index));
                    }
                    rows.add(row);
                }

                return new QueryResultDto(
                        columns, rows, elapsedMs(startedAt), rows.size(), truncated, null, null);
            }
        } catch (TargetDatabaseConnectionException e) {
            return failed(e.getCode(), e.getMessage(), startedAt);
        } catch (SQLException e) {
            if (isTimeout(e)) {
                return failed("QUERY_TIMEOUT",
                        "Query đã vượt quá " + timeoutSeconds + " giây và bị dừng.", startedAt);
            }
            return failed("QUERY_FAILED",
                    "Không thể thực thi SQL trên Target Database.", startedAt);
        }
    }

    private List<String> uniqueColumnLabels(ResultSetMetaData metadata) throws SQLException {
        List<String> labels = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String baseLabel = metadata.getColumnLabel(index);
            int occurrence = occurrences.merge(baseLabel, 1, Integer::sum);
            labels.add(occurrence == 1 ? baseLabel : baseLabel + " (" + occurrence + ")");
        }
        return labels;
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLTimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private QueryResultDto failed(String code, String message, long startedAt) {
        return new QueryResultDto(
                List.of(), List.of(), elapsedMs(startedAt), 0, false, code, message);
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
