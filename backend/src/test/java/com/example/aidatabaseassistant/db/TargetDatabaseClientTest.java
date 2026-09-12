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
     * Khong duoc mo ket noi TCP that toi localhost:5432 trong unit test
     * (ket qua se phu thuoc vao viec may chay test co dang lang nghe cong
     * do hay khong - flaky). Thay vao do, mockStatic DriverManager giong
     * cach QueryExecutorSecurityTest da lam, de kiem soat hoan toan gia
     * tri tra ve va khang dinh dung 2 dieu quan trong:
     *
     *     1. SSRF validation duoc goi voi dung host truoc khi mo ket noi,
     *        va IP DA DUOC VALIDATE (khong phai hostname goc) moi la gia
     *        tri thuc su duoc dung de mo connection - tranh DNS rebinding
     *        (driver tu resolve lai DNS lan 2 sau khi da qua whitelist).
     *     2. JdbcUrlBuilder duoc goi voi dung dbType/IP da validate/port/
     *        databaseName, va URL no tra ve duoc dung de mo connection
     *        (kem username/password)
     */
    @Test
    void openConnection_shouldBuildPostgresUrl() throws Exception {

        when(ssrfProtection.resolveValidatedAddress("localhost"))
                .thenReturn(resolvedAddress);
        when(resolvedAddress.getHostAddress()).thenReturn("127.0.0.1");

        String url =
                "jdbc:postgresql://127.0.0.1:5432/shop"
                        + "?connectTimeout=5"
                        + "&socketTimeout=15";

        when(jdbcUrlBuilder.build(
                "postgres",
                "127.0.0.1",
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

        verify(ssrfProtection).resolveValidatedAddress("localhost");
        verify(jdbcUrlBuilder).build(
                "postgres",
                "127.0.0.1",
                5432,
                "shop",
                false
        );
    }

    @Test
    void openConnection_shouldBuildPostgresUrl_withSsl() throws Exception {

        when(ssrfProtection.resolveValidatedAddress("localhost"))
                .thenReturn(resolvedAddress);
        when(resolvedAddress.getHostAddress()).thenReturn("127.0.0.1");

        String url =
                "jdbc:postgresql://127.0.0.1:5432/shop"
                        + "?sslmode=require"
                        + "&connectTimeout=5"
                        + "&socketTimeout=15";

        when(jdbcUrlBuilder.build(
                "postgres", "127.0.0.1", 5432, "shop", true
        )).thenReturn(url);

        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(
                    url, "shop_user", "shop_pass"
            )).thenReturn(expectedConnection);

            Connection actual = client.openConnection(
                    "postgres", "localhost", 5432, "shop",
                    "shop_user", "shop_pass", true
            );

            assertSame(expectedConnection, actual);
        }

        verify(ssrfProtection).resolveValidatedAddress("localhost");
        verify(jdbcUrlBuilder).build(
                "postgres", "127.0.0.1", 5432, "shop", true
        );
    }

}