package com.example.aidatabaseassistant.db;

import com.example.aidatabaseassistant.security.SsrfProtection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Điểm MỞ KẾT NỐI JDBC DUY NHẤT tới database đích của user.
 *
 * Database nội bộ của application vẫn dùng Spring Data JPA/DataSource
 * và KHÔNG đi qua class này.
 *
 * Các database đích được hỗ trợ:
 *
 *     - MySQL
 *     - PostgreSQL
 *     - Excel/DuckDB
 *
 * Mọi connection tới database của user đều phải đi qua đây để đảm bảo:
 *
 *     1. SSRF protection
 *     2. JDBC URL được build thống nhất
 *     3. Không duplicate DriverManager.getConnection()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetDatabaseClient {

    private final SsrfProtection ssrfProtection;
    private final JdbcUrlBuilder jdbcUrlBuilder;

    /**
     * Mở connection tới database mặc định là MySQL.
     *
     * Giữ overload này để không phá vỡ những caller/test cũ
     * đang sử dụng API 5 tham số.
     */
    public Connection openConnection(
            String host,
            Integer port,
            String databaseName,
            String username,
            String password
    ) throws SQLException {

        return openConnection(
                "mysql", host, port, databaseName, username, password, false
        );
    }

    /**
     * Mở connection tới database đích.
     *
     * Với Excel/DuckDB:
     *
     *     host/port/username/password
     *     không được sử dụng.
     *
     * Với MySQL/PostgreSQL:
     *
     *     SSRF validation được thực hiện trước khi mở socket.
     */
    public Connection openConnection(
            String dbType,
            String host,
            Integer port,
            String databaseName,
            String username,
            String password
    ) throws SQLException {
        return openConnection(dbType, host, port, databaseName, username, password, false);
    }

    public Connection openConnection(
            String dbType,
            String host,
            Integer port,
            String databaseName,
            String username,
            String password,
            boolean sslEnabled
    ) throws SQLException {

        if ("excel".equalsIgnoreCase(dbType)) {

            String url = jdbcUrlBuilder.build(
                    dbType,
                    host,
                    port,
                    databaseName,
                    false
            );

            return DriverManager.getConnection(url, buildDuckDbReadOnlyProperties());
        }

        /*
         * SSRF check phải xảy ra ngay trước khi mở connection.
         *
         * Không được dựa vào việc caller đã validate trước đó
         * vì host có thể đã thay đổi.
         */
        ssrfProtection.validateHost(host);

        String url = jdbcUrlBuilder.build(
                dbType,
                host,
                port,
                databaseName,
                sslEnabled
        );

        return DriverManager.getConnection(
                url,
                username,
                password
        );
    }

    /**
     * Test connection:
     *
     *     open connection
     *     ↓
     *     isValid()
     *     ↓
     *     close
     *
     * SQLException được chuyển thành false.
     *
     * IllegalArgumentException như:
     *
     *     - unsupported dbType
     *     - invalid port
     *     - SSRF violation
     *
     * vẫn được throw ra ngoài.
     */
    public boolean testConnection(
            String dbType,
            String host,
            Integer port,
            String databaseName,
            String username,
            String password
    ) {
        return testConnection(dbType, host, port, databaseName, username, password, false);
    }

    public boolean testConnection(
            String dbType,
            String host,
            Integer port,
            String databaseName,
            String username,
            String password,
            boolean sslEnabled
    ) {

        log.debug("JVM TimeZone = {}", java.util.TimeZone.getDefault().getID());

        try (Connection conn =
                     openConnection(
                             dbType,
                             host,
                             port,
                             databaseName,
                             username,
                             password,
                             sslEnabled
                     )) {

            return conn.isValid(3);

        } catch (SQLException e) {
            log.warn("Lỗi kết nối database đích: {}", e.getMessage());
            return false;
        }
    }

    private Properties buildDuckDbReadOnlyProperties() {
        Properties props = new Properties();
        props.setProperty("duckdb.read_only", "true");
        return props;
    }
}