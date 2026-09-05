package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.config.QueryExecutionProperties;
import com.example.aidatabaseassistant.service.TargetDatabaseClient;
import com.example.aidatabaseassistant.service.TargetDatabaseCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryExecutorTest {

    @Mock TargetDatabaseClient targetDatabaseClient;
    @Mock Connection connection;
    @Mock Statement statement;
    @Mock ResultSet resultSet;
    @Mock ResultSetMetaData metadata;

    private QueryExecutor executor;
    private TargetDatabaseCredentials credentials;

    @BeforeEach
    void setUp() {
        QueryExecutionProperties properties = new QueryExecutionProperties();
        executor = new QueryExecutor(targetDatabaseClient, properties);
        credentials = new TargetDatabaseCredentials("localhost", 3306, "shop", "reader", "secret");
    }

    @Test
    void appliesLimitsReturnsRowsAndClosesJdbcResources() throws Exception {
        when(targetDatabaseClient.openReadOnlyConnection(credentials)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT id, total FROM orders")).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("total");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(7L);
        when(resultSet.getObject(2)).thenReturn(125000);

        var result = executor.executeQuery(credentials, "SELECT id, total FROM orders", 20);

        assertThat(result.getError()).isNull();
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getRows().get(0)).containsEntry("id", 7L).containsEntry("total", 125000);
        verify(statement).setQueryTimeout(20);
        verify(statement).setMaxRows(501);
        verify(resultSet).close();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void mapsTimeoutAndStillClosesConnection() throws Exception {
        when(targetDatabaseClient.openReadOnlyConnection(credentials)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT * FROM orders")).thenThrow(new SQLTimeoutException());

        var result = executor.executeQuery(credentials, "SELECT * FROM orders", 30);

        assertThat(result.getErrorCode()).isEqualTo("QUERY_TIMEOUT");
        assertThat(result.getError()).contains("30 giây");
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void returnsOnlyFiveHundredRowsAndMarksResultAsTruncated() throws Exception {
        when(targetDatabaseClient.openReadOnlyConnection(credentials)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT id FROM orders")).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        AtomicInteger cursor = new AtomicInteger();
        when(resultSet.next()).thenAnswer(ignored -> cursor.incrementAndGet() <= 501);
        when(resultSet.getObject(1)).thenAnswer(ignored -> cursor.get());

        var result = executor.executeQuery(credentials, "SELECT id FROM orders", 20);

        assertThat(result.getRowCount()).isEqualTo(500);
        assertThat(result.getRows()).hasSize(500);
        assertThat(result.isTruncated()).isTrue();
    }
}
