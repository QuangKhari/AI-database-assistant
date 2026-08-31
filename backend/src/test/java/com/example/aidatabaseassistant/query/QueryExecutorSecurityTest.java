package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.security.SsrfProtection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mockStatic;

class QueryExecutorSecurityTest {

    private SsrfProtection ssrfProtection;
    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {

        ssrfProtection = org.mockito.Mockito.mock(
                SsrfProtection.class
        );

        queryExecutor =
                new QueryExecutor(ssrfProtection);
    }

    @Test
    void executeQuery_shouldNotExposeCredential_whenDatabaseConnectionFails()
            throws Exception {

        String secretPassword =
                "SuperSecretPassword123!";

        SQLException databaseException =
                new SQLException(
                        "Access denied for user 'root' " +
                                "using password '" +
                                secretPassword + "'",
                        "28000"
                );

        try (MockedStatic<DriverManager> driverManager =
                     mockStatic(DriverManager.class)) {

            driverManager
                    .when(() ->
                            DriverManager.getConnection(
                                    org.mockito.ArgumentMatchers.anyString(),
                                    org.mockito.ArgumentMatchers.anyString(),
                                    org.mockito.ArgumentMatchers.anyString()
                            )
                    )
                    .thenThrow(databaseException);

            QueryResultDto result =
                    queryExecutor.executeQuery(
                            "localhost",
                            3306,
                            "shop",
                            "root",
                            secretPassword,
                            "SELECT * FROM customers"
                    );

            assertNotNull(result);

            String error =
                    result.getError();

            assertNotNull(error);

            assertFalse(
                    error.contains(secretPassword),
                    "QueryResultDto.error không được chứa password DB"
            );

            assertFalse(
                    error.contains("using password"),
                    "QueryResultDto.error không được chứa thông tin password"
            );
        }
    }
}