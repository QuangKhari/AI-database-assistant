package com.example.aidatabaseassistant.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlBuilderTest {

    private JdbcUrlBuilder jdbcUrlBuilder;

    @BeforeEach
    void setUp() {
        jdbcUrlBuilder = new JdbcUrlBuilder();
    }

    @Test
    void build_shouldReturnMySqlJdbcUrl() {

        String url = jdbcUrlBuilder.build(
                "mysql",
                "localhost",
                3306,
                "shop"
        );

        assertEquals(
                "jdbc:mysql://localhost:3306/shop"
                        + "?connectTimeout=5000"
                        + "&socketTimeout=15000",
                url
        );
    }

    @Test
    void build_shouldReturnPostgresJdbcUrl_withoutSsl() {

        String url = jdbcUrlBuilder.build(
                "postgres",
                "localhost",
                5432,
                "shop",
                false
        );

        assertEquals(
                "jdbc:postgresql://localhost:5432/shop"
                        + "?connectTimeout=5"
                        + "&socketTimeout=15",
                url
        );
    }

    @Test
    void build_shouldReturnPostgresJdbcUrl_withSsl() {

        String url = jdbcUrlBuilder.build(
                "postgres",
                "localhost",
                5432,
                "shop",
                true
        );

        assertEquals(
                "jdbc:postgresql://localhost:5432/shop"
                        + "?sslmode=require"
                        + "&connectTimeout=5"
                        + "&socketTimeout=15",
                url
        );
    }

    @Test
    void build_shouldAcceptPostgresqlAlias() {

        String url = jdbcUrlBuilder.build(
                "postgresql",
                "localhost",
                5432,
                "shop",
                true
        );

        assertEquals(
                "jdbc:postgresql://localhost:5432/shop"
                        + "?sslmode=require"
                        + "&connectTimeout=5"
                        + "&socketTimeout=15",
                url
        );
    }

    @Test
    void build_shouldReturnDuckDbUrl() {

        String url = jdbcUrlBuilder.build(
                "excel",
                "local-file",
                0,
                "/data/excel-dbs/user_1/sales.duckdb"
        );

        assertEquals(
                "jdbc:duckdb:/data/excel-dbs/user_1/sales.duckdb",
                url
        );
    }

    @Test
    void build_shouldThrow_whenDbTypeUnsupported() {

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> jdbcUrlBuilder.build(
                                "oracle",
                                "localhost",
                                1521,
                                "shop"
                        )
                );

        assertEquals(
                "Loại database chưa được hỗ trợ: oracle",
                exception.getMessage()
        );
    }

    @Test
    void build_shouldThrow_whenDbTypeBlank() {

        assertThrows(
                IllegalArgumentException.class,
                () -> jdbcUrlBuilder.build(
                        "",
                        "localhost",
                        3306,
                        "shop"
                )
        );
    }

    @Test
    void build_shouldThrow_whenHostBlank() {

        assertThrows(
                IllegalArgumentException.class,
                () -> jdbcUrlBuilder.build(
                        "postgres",
                        "",
                        5432,
                        "shop"
                )
        );
    }

    @Test
    void build_shouldThrow_whenPortInvalid() {

        assertThrows(
                IllegalArgumentException.class,
                () -> jdbcUrlBuilder.build(
                        "postgres",
                        "localhost",
                        0,
                        "shop"
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> jdbcUrlBuilder.build(
                        "postgres",
                        "localhost",
                        65536,
                        "shop"
                )
        );
    }

    @Test
    void build_shouldThrow_whenDatabaseNameBlank() {

        assertThrows(
                IllegalArgumentException.class,
                () -> jdbcUrlBuilder.build(
                        "postgres",
                        "localhost",
                        5432,
                        ""
                )
        );
    }
}