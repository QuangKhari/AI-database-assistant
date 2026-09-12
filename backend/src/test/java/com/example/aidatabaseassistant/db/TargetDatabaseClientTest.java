package com.example.aidatabaseassistant.db;

import com.example.aidatabaseassistant.security.SsrfProtection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TargetDatabaseClientTest {

    @Mock
    private SsrfProtection ssrfProtection;

    @Mock
    private JdbcUrlBuilder jdbcUrlBuilder;

    @Mock
    private Connection expectedConnection;

    @Mock
    private InetAddress resolvedAddress;

    private TargetDatabaseClient client;

    @BeforeEach
    void setUp() {
        client = new TargetDatabaseClient(
                ssrfProtection,
                jdbcUrlBuilder
        );
    }

    /**
     * Kiểm tra PostgreSQL connection không mở TCP thật.
     *
     * Luồng cần đảm bảo:
     *
     *     hostname
     *          ↓
     *     SSRF validation
     *          ↓
     *     JdbcUrlBuilder dùng hostname gốc
     *          ↓
     *     DriverManager.getConnection()
     *
     * SSRF vẫn được gọi để validate hostname,
     * nhưng JDBC URL phải giữ hostname gốc.
     */
    @Test
    void openConnection_shouldBuildPostgresUrl() throws Exception {

        when(ssrfProtection.resolveValidatedAddress("localhost"))
                .thenReturn(resolvedAddress);

        String url =
                "jdbc:postgresql://localhost:5432/shop"
                        + "?connectTimeout=5"
                        + "&socketTimeout=15";

        when(jdbcUrlBuilder.build(
                "postgres",
                "localhost",
                5432,
                "shop",
                false
        )).thenReturn(url);

        try (MockedStatic<DriverManager> driverManager =
                     mockStatic(DriverManager.class)) {

            driverManager
                    .when(() -> DriverManager.getConnection(
                            url,
                            "shop_user",
                            "shop_pass"
                    ))
                    .thenReturn(expectedConnection);

            Connection actual = client.openConnection(
                    "postgres",
                    "localhost",
                    5432,
                    "shop",
                    "shop_user",
                    "shop_pass"
            );

            assertSame(expectedConnection, actual);
        }

        // SSRF validation phải được thực hiện.
        verify(ssrfProtection)
                .resolveValidatedAddress("localhost");

        // JDBC URL phải dùng hostname gốc,
        // không dùng IP đã resolve.
        verify(jdbcUrlBuilder).build(
                "postgres",
                "localhost",
                5432,
                "shop",
                false
        );
    }

    /**
     * Kiểm tra PostgreSQL connection với SSL.
     *
     * Khi sslEnabled = true:
     *
     *     JdbcUrlBuilder phải nhận true
     *     và tạo URL có sslmode=require.
     */
    @Test
    void openConnection_shouldBuildPostgresUrl_withSsl() throws Exception {

        when(ssrfProtection.resolveValidatedAddress("localhost"))
                .thenReturn(resolvedAddress);

        String url =
                "jdbc:postgresql://localhost:5432/shop"
                        + "?sslmode=require"
                        + "&connectTimeout=5"
                        + "&socketTimeout=15";

        when(jdbcUrlBuilder.build(
                "postgres",
                "localhost",
                5432,
                "shop",
                true
        )).thenReturn(url);

        try (MockedStatic<DriverManager> driverManager =
                     mockStatic(DriverManager.class)) {

            driverManager.when(() -> DriverManager.getConnection(
                    url,
                    "shop_user",
                    "shop_pass"
            )).thenReturn(expectedConnection);

            Connection actual = client.openConnection(
                    "postgres",
                    "localhost",
                    5432,
                    "shop",
                    "shop_user",
                    "shop_pass",
                    true
            );

            assertSame(expectedConnection, actual);
        }

        verify(ssrfProtection)
                .resolveValidatedAddress("localhost");

        verify(jdbcUrlBuilder).build(
                "postgres",
                "localhost",
                5432,
                "shop",
                true
        );
    }

    /**
     * Kiểm tra MySQL vẫn hoạt động bình thường.
     *
     * Đây là regression test để đảm bảo việc xử lý PostgreSQL
     * không làm ảnh hưởng MySQL.
     */
    @Test
    void openConnection_shouldBuildMysqlUrl() throws Exception {

        when(ssrfProtection.resolveValidatedAddress("localhost"))
                .thenReturn(resolvedAddress);

        String url =
                "jdbc:mysql://localhost:3306/shop"
                        + "?connectTimeout=5000"
                        + "&socketTimeout=15000";

        when(jdbcUrlBuilder.build(
                "mysql",
                "localhost",
                3306,
                "shop",
                false
        )).thenReturn(url);

        try (MockedStatic<DriverManager> driverManager =
                     mockStatic(DriverManager.class)) {

            driverManager.when(() -> DriverManager.getConnection(
                    url,
                    "shop_user",
                    "shop_pass"
            )).thenReturn(expectedConnection);

            Connection actual = client.openConnection(
                    "mysql",
                    "localhost",
                    3306,
                    "shop",
                    "shop_user",
                    "shop_pass"
            );

            assertSame(expectedConnection, actual);
        }

        verify(ssrfProtection)
                .resolveValidatedAddress("localhost");

        verify(jdbcUrlBuilder).build(
                "mysql",
                "localhost",
                3306,
                "shop",
                false
        );
    }

    /**
     * Kiểm tra PostgreSQL Neon:
     *
     * Quan trọng nhất là hostname Neon phải được giữ nguyên
     * trong JDBC URL sau khi SSRF validation.
     */
    @Test
    void openConnection_shouldKeepNeonHostname_withSsl() throws Exception {

        String neonHost =
                "ep-quiet-voice-123456-pooler.c-5.us-east-2.aws.neon.tech";

        when(ssrfProtection.resolveValidatedAddress(neonHost))
                .thenReturn(resolvedAddress);

        String url =
                "jdbc:postgresql://" + neonHost + ":5432/neondb"
                        + "?sslmode=require"
                        + "&connectTimeout=5"
                        + "&socketTimeout=15";

        when(jdbcUrlBuilder.build(
                "postgres",
                neonHost,
                5432,
                "neondb",
                true
        )).thenReturn(url);

        try (MockedStatic<DriverManager> driverManager =
                     mockStatic(DriverManager.class)) {

            driverManager.when(() -> DriverManager.getConnection(
                    url,
                    "aidb_reader",
                    "test_password"
            )).thenReturn(expectedConnection);

            Connection actual = client.openConnection(
                    "postgres",
                    neonHost,
                    5432,
                    "neondb",
                    "aidb_reader",
                    "test_password",
                    true
            );

            assertSame(expectedConnection, actual);
        }

        // Host phải được SSRF validation.
        verify(ssrfProtection)
                .resolveValidatedAddress(neonHost);

        // Quan trọng:
        // JdbcUrlBuilder nhận hostname Neon,
        // KHÔNG phải IP sau DNS resolution.
        verify(jdbcUrlBuilder).build(
                "postgres",
                neonHost,
                5432,
                "neondb",
                true
        );
    }
}