package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.config.QueryExecutionProperties;
import com.example.aidatabaseassistant.dto.QueryExecuteRequest;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.QueryLog;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.QueryLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock MessageRepository messageRepository;
    @Mock QueryLogRepository queryLogRepository;
    @Mock DatabaseSchemaRepository schemaRepository;
    @Mock ConnectionService connectionService;
    @Mock EncryptionUtil encryptionUtil;
    @Mock QueryExecutor queryExecutor;

    private QueryService service;
    private Message assistantMessage;
    private DatabaseConnection connection;
    private DatabaseSchema schema;

    @BeforeEach
    void setUp() {
        QueryExecutionProperties properties = new QueryExecutionProperties();
        service = new QueryService(
                messageRepository, queryLogRepository, schemaRepository, connectionService,
                encryptionUtil, new QueryValidator(), queryExecutor, properties,
                new QueryExecutionGuard());

        User user = User.builder().id(1L).username("student").build();
        connection = DatabaseConnection.builder()
                .id(2L).user(user).active(true).host("localhost").port(3306)
                .databaseName("shop").username("reader").encryptedPassword("encrypted")
                .build();
        Conversation conversation = Conversation.builder()
                .id(3L).user(user).connection(connection).build();
        assistantMessage = Message.builder()
                .id(4L).conversation(conversation).role("assistant")
                .content("SQL preview đã sẵn sàng.")
                .generatedSql("SELECT id FROM orders").generatedSqlValid(true)
                .build();
        schema = DatabaseSchema.builder()
                .connection(connection).databaseName("shop")
                .tables(List.of(TableMetadata.builder().name("orders").build()))
                .build();
    }

    @Test
    void executesOnlyOwnedSavedPreviewWithDefaultTimeoutAndPersistsLog() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 7L);
        QueryResultDto result = new QueryResultDto(
                List.of("id"), List.of(row), 15, 1, false, null, null);

        when(messageRepository.findByIdAndConversationUserUsernameIgnoreCase(4L, "student"))
                .thenReturn(Optional.of(assistantMessage));
        when(connectionService.getOwnedActiveConnection("student", 2L)).thenReturn(connection);
        when(schemaRepository.findByConnectionId(2L)).thenReturn(Optional.of(schema));
        when(encryptionUtil.decrypt("encrypted")).thenReturn("secret");
        when(queryExecutor.executeQuery(any(TargetDatabaseCredentials.class),
                eq("SELECT id FROM orders"), eq(20))).thenReturn(result);
        when(queryLogRepository.findByMessageIdOrderByAttemptNumberAsc(4L)).thenReturn(List.of());

        var response = service.execute("student", new QueryExecuteRequest(4L, null));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.timeoutSeconds()).isEqualTo(20);
        assertThat(response.result().getRows()).containsExactly(row);
        ArgumentCaptor<QueryLog> logCaptor = ArgumentCaptor.forClass(QueryLog.class);
        verify(queryLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(logCaptor.getValue().getAttemptNumber()).isEqualTo(1);
    }
}
