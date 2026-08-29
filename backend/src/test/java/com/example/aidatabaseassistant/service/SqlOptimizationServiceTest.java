package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.OptimizeSqlRequest;
import com.example.aidatabaseassistant.dto.OptimizeSqlResponse;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.optimization.SqlOptimizationAnalyzer;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.query.SqlOptimizationRawData;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqlOptimizationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private DatabaseConnectionRepository connectionRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private QueryExecutor queryExecutor;
    @Mock
    private SchemaLoaderService schemaLoaderService;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LLMClient llmClient;

    private SqlOptimizationService service;

    private User owner;
    private User otherUser;
    private DatabaseConnection connection;
    private DatabaseSchema schema;

    @BeforeEach
    void setUp() {
        // Dung QueryValidator va SqlOptimizationAnalyzer THAT (khong mock)
        // vi day chinh la logic can kiem tra - chi mock phan JDBC/AI/network.
        service = new SqlOptimizationService(
                userRepository, connectionRepository, encryptionUtil,
                new QueryValidator(), queryExecutor, schemaLoaderService,
                new SqlOptimizationAnalyzer(), promptBuilder, llmClient);

        owner = User.builder().id(1L).username("owner").build();
        otherUser = User.builder().id(2L).username("intruder").build();

        connection = DatabaseConnection.builder()
                .id(10L).user(owner).dbType("mysql")
                .host("localhost").port(3306).databaseName("shop_db")
                .username("root").encryptedPassword("enc-pass")
                .build();

        TableMetadata ordersTable = TableMetadata.builder().id(1L).name("orders").build();
        schema = DatabaseSchema.builder().id(1L).connection(connection)
                .tables(List.of(ordersTable)).build();
    }

    private Map<String, Object> explainRow(String table, String type, Long rows, String extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("select_type", "SIMPLE");
        row.put("table", table);
        row.put("type", type);
        row.put("possible_keys", null);
        row.put("key", null);
        row.put("rows", rows);
        row.put("Extra", extra);
        return row;
    }

    @Test
    void optimize_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {
        OptimizeSqlRequest request = new OptimizeSqlRequest();
        request.setSql("SELECT * FROM orders");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("intruder")).thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        assertThrows(IllegalArgumentException.class,
                () -> service.optimize("intruder", request));

        verifyNoInteractions(queryExecutor, llmClient);
    }

    @Test
    void optimize_shouldThrow_whenDbTypeIsNotMysql() {
        connection.setDbType("postgres");

        OptimizeSqlRequest request = new OptimizeSqlRequest();
        request.setSql("SELECT * FROM orders");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.optimize("owner", request));
        assertTrue(ex.getMessage().contains("MySQL"));
    }

    @Test
    void optimize_shouldThrow_whenSqlIsNotSelect() {
        OptimizeSqlRequest request = new OptimizeSqlRequest();
        request.setSql("DELETE FROM orders");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);

        assertThrows(IllegalArgumentException.class,
                () -> service.optimize("owner", request));

        verifyNoInteractions(queryExecutor);
    }

    @Test
    void optimize_shouldThrow_whenExplainFails() {
        OptimizeSqlRequest request = new OptimizeSqlRequest();
        request.setSql("SELECT * FROM orders");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("real-pass");
        when(queryExecutor.collectOptimizationData(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SqlOptimizationRawData(List.of(), Map.of(), "Connection refused"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.optimize("owner", request));
        assertTrue(ex.getMessage().contains("Connection refused"));
    }

    @Test
    void optimize_shouldReturnAiSummary_whenAllStepsSucceed() {
        OptimizeSqlRequest request = new OptimizeSqlRequest();
        request.setSql("SELECT * FROM orders WHERE status = 'pending'");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("real-pass");

        List<Map<String, Object>> explainRows = List.of(
                explainRow("orders", "ALL", 5000L, null));
        when(queryExecutor.collectOptimizationData(
                "localhost", 3306, "shop_db", "root", "real-pass", request.getSql()))
                .thenReturn(new SqlOptimizationRawData(
                        explainRows, Map.of("orders", java.util.Set.of()), null));

        when(promptBuilder.buildOptimizationPrompt(any(), any(), any())).thenReturn("prompt");
        when(llmClient.generateResponse("prompt"))
                .thenReturn("SQL đang quét toàn bộ bảng orders, nên thêm index cho cột status.");

        OptimizeSqlResponse response = service.optimize("owner", request);

        assertEquals(request.getSql(), response.getSql());
        assertEquals(1, response.getIssues().size());
        assertEquals(1, response.getSuggestions().size());
        assertEquals("SQL đang quét toàn bộ bảng orders, nên thêm index cho cột status.",
                response.getAiSummary());
    }

    @Test
    void optimize_shouldFallbackToTemplateSummary_whenAiThrows() {
        OptimizeSqlRequest request = new OptimizeSqlRequest();
        request.setSql("SELECT * FROM orders WHERE status = 'pending'");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("real-pass");

        List<Map<String, Object>> explainRows = List.of(
                explainRow("orders", "ALL", 5000L, null));
        when(queryExecutor.collectOptimizationData(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SqlOptimizationRawData(
                        explainRows, Map.of("orders", java.util.Set.of()), null));

        when(promptBuilder.buildOptimizationPrompt(any(), any(), any())).thenReturn("prompt");
        when(llmClient.generateResponse("prompt")).thenThrow(new RuntimeException("Gemini hết quota"));

        OptimizeSqlResponse response = service.optimize("owner", request);

        assertNotNull(response.getAiSummary());
        assertFalse(response.getAiSummary().isBlank());
        assertTrue(response.getAiSummary().contains("orders"));
    }
}