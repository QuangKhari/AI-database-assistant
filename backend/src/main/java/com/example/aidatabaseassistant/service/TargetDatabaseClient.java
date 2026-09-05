package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.TargetDatabaseProperties;
import com.example.aidatabaseassistant.dto.ConnectionTestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TargetDatabaseClient {

    private static final Pattern GRANT_PATTERN = Pattern.compile(
            "^GRANT\\s+(.+?)\\s+ON\\s+(.+?)\\s+TO\\s+.+$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SAFE_PRIVILEGES = Set.of(
            "USAGE", "SELECT", "SHOW VIEW", "SHOW DATABASES"
    );

    private final TargetDatabaseProperties properties;
    private final TargetHostValidator hostValidator;

    public ConnectionTestResponse test(TargetDatabaseCredentials credentials) {
        long startedAt = System.nanoTime();
        try (Connection connection = openReadOnlyConnection(credentials)) {
            if (!connection.isValid(Math.max(1, properties.getConnectionTimeoutMs() / 1_000))) {
                return failed("CONNECTION_INVALID", "Database không phản hồi hợp lệ.", startedAt);
            }

            if (!hasReadOnlyGrants(connection, credentials.databaseName())) {
                return new ConnectionTestResponse(
                        false, false, "ACCOUNT_NOT_READ_ONLY",
                        "Tài khoản MySQL phải chỉ có quyền đọc (SELECT).",
                        elapsedMs(startedAt), null
                );
            }

            String version = safeServerVersion(connection);
            return new ConnectionTestResponse(
                    true, true, "CONNECTED",
                    "Kết nối thành công và tài khoản đã được xác minh chỉ đọc.",
                    elapsedMs(startedAt), version
            );
        } catch (com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException e) {
            return failed(e.getCode(), e.getMessage(), startedAt);
        } catch (SQLException e) {
            return mapSqlError(e, startedAt);
        }
    }

    public Connection openReadOnlyConnection(TargetDatabaseCredentials credentials) {
        long startedAt = System.nanoTime();
        hostValidator.validate(credentials.host());
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(buildUrl(credentials), jdbcProperties(credentials));
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException e) {
            closeQuietly(connection);
            ConnectionTestResponse error = mapSqlError(e, startedAt);
            throw new com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException(
                    error.code(), error.message());
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Preserve the original connection error.
        }
    }

    private Properties jdbcProperties(TargetDatabaseCredentials credentials) {
        Properties jdbcProperties = new Properties();
        jdbcProperties.setProperty("user", credentials.username());
        jdbcProperties.setProperty("password", credentials.password());
        jdbcProperties.setProperty("connectTimeout", String.valueOf(properties.getConnectionTimeoutMs()));
        jdbcProperties.setProperty("socketTimeout", String.valueOf(properties.getSocketTimeoutMs()));
        jdbcProperties.setProperty("autoReconnect", "false");
        jdbcProperties.setProperty("allowMultiQueries", "false");
        jdbcProperties.setProperty("useServerPrepStmts", "true");
        return jdbcProperties;
    }

    private boolean hasReadOnlyGrants(Connection connection, String databaseName) throws SQLException {
        boolean hasSelectOnTarget = false;
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(1, properties.getSocketTimeoutMs() / 1_000));
            try (ResultSet resultSet = statement.executeQuery("SHOW GRANTS FOR CURRENT_USER")) {
                while (resultSet.next()) {
                    String grant = resultSet.getString(1);
                    Matcher matcher = GRANT_PATTERN.matcher(grant);
                    if (!matcher.matches()) {
                        return false;
                    }

                    Set<String> privileges = Arrays.stream(matcher.group(1).split(","))
                            .map(value -> value.trim().toUpperCase(Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet());
                    if (!SAFE_PRIVILEGES.containsAll(privileges)) {
                        return false;
                    }

                    if (privileges.contains("SELECT") && scopeMatchesDatabase(matcher.group(2), databaseName)) {
                        hasSelectOnTarget = true;
                    }
                }
            }
        }
        return hasSelectOnTarget;
    }

    private boolean scopeMatchesDatabase(String rawScope, String databaseName) {
        String scope = rawScope.replace("`", "").trim();
        return scope.equals("*.*")
                || scope.equalsIgnoreCase(databaseName + ".*")
                || scope.toLowerCase(Locale.ROOT).startsWith(databaseName.toLowerCase(Locale.ROOT) + ".");
    }

    private String safeServerVersion(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseProductVersion();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private String buildUrl(TargetDatabaseCredentials credentials) {
        String host = credentials.host().contains(":") && !credentials.host().startsWith("[")
                ? "[" + credentials.host() + "]"
                : credentials.host();
        return "jdbc:mysql://" + host + ":" + credentials.port() + "/" + credentials.databaseName();
    }

    private ConnectionTestResponse mapSqlError(SQLException exception, long startedAt) {
        if (exception instanceof SQLTimeoutException || hasCause(exception, SocketTimeoutException.class)) {
            return failed("CONNECTION_TIMEOUT", "Không thể kết nối trong thời gian cho phép.", startedAt);
        }
        if ("28000".equals(exception.getSQLState()) || exception.getErrorCode() == 1045) {
            return failed("ACCESS_DENIED", "Tên đăng nhập hoặc mật khẩu MySQL không đúng.", startedAt);
        }
        if (exception.getErrorCode() == 1049) {
            return failed("DATABASE_NOT_FOUND", "Không tìm thấy database đã nhập.", startedAt);
        }
        if (exception.getSQLState() != null && exception.getSQLState().startsWith("08")) {
            return failed("NETWORK_ERROR", "Không thể kết nối tới máy chủ database.", startedAt);
        }
        return failed("CONNECTION_FAILED", "Không thể kiểm tra database với thông tin đã cung cấp.", startedAt);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private ConnectionTestResponse failed(String code, String message, long startedAt) {
        return new ConnectionTestResponse(false, false, code, message, elapsedMs(startedAt), null);
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
