package com.example.aidatabaseassistant.db;

import org.springframework.stereotype.Component;

@Component
public class JdbcUrlBuilder {

    private static final int MYSQL_CONNECT_TIMEOUT_MS = 5000;
    private static final int MYSQL_SOCKET_TIMEOUT_MS = 15000;

    private static final int POSTGRES_CONNECT_TIMEOUT_SECONDS = 5;
    private static final int POSTGRES_SOCKET_TIMEOUT_SECONDS = 15;

    public String build(
            String dbType,
            String host,
            Integer port,
            String databaseName
    ) {

        if (dbType == null || dbType.isBlank()) {
            throw new IllegalArgumentException(
                    "Loại database không được để trống"
            );
        }

        if ("excel".equalsIgnoreCase(dbType)) {
            return buildDuckDbUrl(databaseName);
        }

        if ("mysql".equalsIgnoreCase(dbType)) {
            return buildMySqlUrl(
                    host,
                    port,
                    databaseName
            );
        }

        if ("postgres".equalsIgnoreCase(dbType)
                || "postgresql".equalsIgnoreCase(dbType)) {

            return buildPostgresUrl(
                    host,
                    port,
                    databaseName
            );
        }

        throw new IllegalArgumentException(
                "Loại database chưa được hỗ trợ: " + dbType
        );
    }

    private String buildMySqlUrl(
            String host,
            Integer port,
            String databaseName
    ) {

        validateServerConnectionInfo(
                host,
                port,
                databaseName
        );

        return "jdbc:mysql://"
                + host
                + ":"
                + port
                + "/"
                + databaseName
                + "?connectTimeout="
                + MYSQL_CONNECT_TIMEOUT_MS
                + "&socketTimeout="
                + MYSQL_SOCKET_TIMEOUT_MS;
    }

    private String buildPostgresUrl(
            String host,
            Integer port,
            String databaseName
    ) {

        validateServerConnectionInfo(
                host,
                port,
                databaseName
        );

        return "jdbc:postgresql://"
                + host
                + ":"
                + port
                + "/"
                + databaseName
                + "?connectTimeout="
                + POSTGRES_CONNECT_TIMEOUT_SECONDS
                + "&socketTimeout="
                + POSTGRES_SOCKET_TIMEOUT_SECONDS;
    }

    private String buildDuckDbUrl(
            String databasePath
    ) {

        if (databasePath == null
                || databasePath.isBlank()) {

            throw new IllegalArgumentException(
                    "Đường dẫn DuckDB không được để trống"
            );
        }

        return "jdbc:duckdb:" + databasePath;
    }

    private void validateServerConnectionInfo(
            String host,
            Integer port,
            String databaseName
    ) {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "Host không được để trống"
            );
        }

        if (port == null
                || port < 1
                || port > 65535) {

            throw new IllegalArgumentException(
                    "Port phải nằm trong khoảng 1-65535"
            );
        }

        if (databaseName == null
                || databaseName.isBlank()) {

            throw new IllegalArgumentException(
                    "Tên database không được để trống"
            );
        }
    }
}