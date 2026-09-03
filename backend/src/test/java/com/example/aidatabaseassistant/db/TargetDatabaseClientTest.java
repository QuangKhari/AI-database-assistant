package com.example.aidatabaseassistant.db;

import com.example.aidatabaseassistant.security.SsrfProtection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

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
     *     1. SSRF validation duoc goi voi dung host truoc khi mo ket noi
     *     2. JdbcUrlBuilder duoc goi voi dung dbType/host/port/databaseName,
     *        va URL no tra ve duoc dung de mo connection (kem username/password)
     */
    @Test
    void openConnection_shouldBuildPostgresUrl() throws Exception {

        String url =
                "jdbc:postgresql://localhost:5432/shop"
                        + "?connectTimeout=5"
                        + "&socketTimeout=15";

        when(jdbcUrlBuilder.build(
                "postgres",
                "localhost",
                5432,
                "shop"
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

        verify(ssrfProtection).validateHost("localhost");
        verify(jdbcUrlBuilder).build(
                "postgres",
                "localhost",
                5432,
                "shop"
        );
    }
}