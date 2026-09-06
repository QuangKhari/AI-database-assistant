package com.example.aidatabaseassistant.db;

import com.example.aidatabaseassistant.security.SsrfProtection;
import lombok.RequiredArgsConstructor;
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
                "mysql",
                host,
                port,
                databaseName,
                username,
                password
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

        if ("excel".equalsIgnoreCase(dbType)) {

            String url = jdbcUrlBuilder.build(
                    dbType,
                    host,
                    port,
                    databaseName
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
                databaseName
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

        System.out.println("JVM TimeZone = " +
                java.util.TimeZone.getDefault().getID());

        try (Connection conn =
                     openConnection(
                             dbType,
                             host,
                             port,
                             databaseName,
                             username,
                             password
                     )) {

            return conn.isValid(3);

        } catch (SQLException e) {
            System.err.println("Lỗi kết nối database đích:");
            e.printStackTrace();
            return false;
        }
    }

    /*
     * FIX "connection locking":
     *
     * File .duckdb chi duoc GHI 1 LAN DUY NHAT luc ingest (xem
     * ExcelIngestionService.buildDuckDbFile - dung connection RIENG cua
     * no, KHONG di qua class nay). Moi truy cap SAU DO qua class nay
     * (schema discovery, chay SELECT, test connection...) deu CHI DOC.
     *
     * DuckDB chi cho phep 1 connection GHI (read-write) tai 1 thoi diem
     * cho 1 file .duckdb, nhung cho phep NHIEU connection DOC (read-only)
     * cung luc. Neu KHONG khai bao read-only, 2 request chay song song
     * toi cung 1 file Excel (vi du: dang xem schema + dang hoi cau khac
     * cung connection) se dinh loi khoa file kieu "IO Error: Could not
     * set lock on file" - day chinh la van de "connection locking" con
     * ton dong trong audit Excel/DuckDB truoc day.
     *
     * LUU Y: key Properties "duckdb.read_only" theo tai lieu chinh thuc
     * cua driver duckdb_jdbc (nhom duckdb.org/docs/stable/clients/java)
     * cho dong ban 1.5.x dang dung trong pom.xml. Neu nang cap driver
     * len major version khac trong tuong lai, kiem tra lai key nay
     * truoc khi tin tuong y nguyen.
     */
    private Properties buildDuckDbReadOnlyProperties() {
        Properties props = new Properties();
        props.setProperty("duckdb.read_only", "true");
        return props;
    }
}