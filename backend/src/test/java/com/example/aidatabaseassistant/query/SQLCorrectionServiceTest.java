package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SQLCorrectionServiceTest {

    @Mock
    private NL2SQLEngine nl2SQLEngine;

    @Mock
    private QueryValidator queryValidator;

    @Mock
    private QueryExecutor queryExecutor;

    private SQLCorrectionService sqlCorrectionService;

    private DatabaseSchema schema;
    private DatabaseConnection connection;

    @BeforeEach
    void setUp() {
        sqlCorrectionService = new SQLCorrectionService(nl2SQLEngine, queryValidator, queryExecutor);

        schema = DatabaseSchema.builder().databaseName("shop").build();
        connection = DatabaseConnection.builder()
                .host("localhost")
                .port(3306)
                .databaseName("shop")
                .username("root")
                .build();
    }

    @Test
    void run_shouldSucceedOnFirstAttempt_whenSqlIsValidAndExecutesCleanly() {
        when(nl2SQLEngine.generateSQL(eq("dem so khach hang"), eq(schema)))
                .thenReturn("SELECT COUNT(*) FROM customers");

        QueryResultDto okResult = new QueryResultDto(List.of("count"), List.of(Map.of("count", 5)), 20, 1, null);
        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "pwd",
                "SELECT COUNT(*) FROM customers")).thenReturn(okResult);

        SQLCorrectionService.AttemptResult result =
                sqlCorrectionService.run("dem so khach hang", schema, connection, "pwd");

        assertTrue(result.isSuccess());
        assertEquals("SELECT COUNT(*) FROM customers", result.getSql());
        assertEquals(1, result.getAttemptLogs().size());
        assertSame(okResult, result.getFinalResult());

        verify(nl2SQLEngine, never()).selfCorrect(anyString(), anyString(), any());
    }

    @Test
    void run_shouldRetryAndEventuallySucceed_whenFirstAttemptsAreInvalidSql() {
        when(nl2SQLEngine.generateSQL(anyString(), eq(schema))).thenReturn("SELECT * FROM ghost_table");
        when(nl2SQLEngine.selfCorrect(eq("SELECT * FROM ghost_table"), anyString(), eq(schema)))
                .thenReturn("SELECT * FROM customers");

        doThrow(new IllegalArgumentException("Bảng không tồn tại trong schema: ghost_table"))
                .when(queryValidator).validate("SELECT * FROM ghost_table", schema);

        QueryResultDto okResult = new QueryResultDto(List.of("id"), List.of(Map.of("id", 1)), 10, 1, null);
        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "pwd",
                "SELECT * FROM customers")).thenReturn(okResult);

        SQLCorrectionService.AttemptResult result =
                sqlCorrectionService.run("lay khach hang", schema, connection, "pwd");

        assertTrue(result.isSuccess());
        assertEquals("SELECT * FROM customers", result.getSql());
        assertEquals(2, result.getAttemptLogs().size());
        assertFalse(result.getAttemptLogs().get(0).isSuccess());
        assertTrue(result.getAttemptLogs().get(1).isSuccess());

        verify(nl2SQLEngine, times(1)).selfCorrect(anyString(), anyString(), eq(schema));
    }

    @Test
    void run_shouldFailAfterMaxRetries_whenValidationAlwaysFails() {
        when(nl2SQLEngine.generateSQL(anyString(), eq(schema))).thenReturn("SELECT * FROM ghost_table");
        when(nl2SQLEngine.selfCorrect(anyString(), anyString(), eq(schema))).thenReturn("SELECT * FROM ghost_table");

        doThrow(new IllegalArgumentException("Bảng không tồn tại trong schema: ghost_table"))
                .when(queryValidator).validate("SELECT * FROM ghost_table", schema);

        SQLCorrectionService.AttemptResult result =
                sqlCorrectionService.run("lay khach hang", schema, connection, "pwd");

        assertFalse(result.isSuccess());
        // MAX_RETRIES = 3
        assertEquals(3, result.getAttemptLogs().size());
        assertTrue(result.getAttemptLogs().stream().noneMatch(SQLCorrectionService.AttemptLog::isSuccess));
        assertNotNull(result.getFinalResult().getError());

        // selfCorrect chi duoc goi giua cac lan thu, khong goi sau lan cuoi cung
        verify(nl2SQLEngine, times(2)).selfCorrect(anyString(), anyString(), eq(schema));
        verify(queryExecutor, never()).executeQuery(any(), any(), any(), any(), any(), any());
    }

    @Test
    void run_shouldTreatExecutionErrorAsFailedAttempt_andRetry() {
        when(nl2SQLEngine.generateSQL(anyString(), eq(schema))).thenReturn("SELECT * FROM customers");
        when(nl2SQLEngine.selfCorrect(anyString(), anyString(), eq(schema))).thenReturn("SELECT id FROM customers");

        // SQL hop le cu phap nhung DB tra loi loi (vi du sai ten cot)
        QueryResultDto dbErrorResult = new QueryResultDto(List.of(), List.of(), 5, 0, "Unknown column 'x'");
        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "pwd",
                "SELECT * FROM customers")).thenReturn(dbErrorResult);

        QueryResultDto okResult = new QueryResultDto(List.of("id"), List.of(Map.of("id", 1)), 8, 1, null);
        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "pwd",
                "SELECT id FROM customers")).thenReturn(okResult);

        SQLCorrectionService.AttemptResult result =
                sqlCorrectionService.run("lay khach hang", schema, connection, "pwd");

        assertTrue(result.isSuccess());
        assertEquals(2, result.getAttemptLogs().size());
        assertEquals("Unknown column 'x'", result.getAttemptLogs().get(0).getResult().getError());
    }
}