package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.dto.ExplainSqlRequest;
import com.example.aidatabaseassistant.dto.ExplainSqlResponse;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqlExplanationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private DatabaseConnectionRepository connectionRepository;
    @Mock
    private DatabaseSchemaRepository schemaRepository;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LLMClient llmClient;

    private SqlExplanationService sqlExplanationService;

    private User owner;
    private User otherUser;
    private DatabaseConnection connection;

    @BeforeEach
    void setUp() {
        // Dung ObjectMapper THAT (khong mock) vi day chinh la logic can test:
        // parse JSON tra ve tu LLM.
        ObjectMapper objectMapper = new ObjectMapper();
        sqlExplanationService = new SqlExplanationService(
                userRepository, connectionRepository, schemaRepository, promptBuilder, llmClient, objectMapper);

        owner = User.builder().id(1L).username("owner").build();
        otherUser = User.builder().id(2L).username("intruder").build();
        connection = DatabaseConnection.builder().id(10L).user(owner).build();
    }

    @Test
    void explain_shouldReturnParsedExplanation_withoutConnectionId() {
        ExplainSqlRequest request = new ExplainSqlRequest();
        request.setSql("SELECT full_name FROM customers WHERE city = 'Hanoi'");

        when(promptBuilder.buildExplanationPrompt(eq(request.getSql()), isNull()))
                .thenReturn("some prompt");

        String llmJson = """
                {
                  "summary": "Lay ten khach hang o Ha Noi",
                  "steps": [
                    {"clause": "SELECT full_name", "explanation": "Lay cot ten day du"},
                    {"clause": "FROM customers", "explanation": "Tu bang khach hang"},
                    {"clause": "WHERE city = 'Hanoi'", "explanation": "Chi lay nguoi o Ha Noi"}
                  ]
                }
                """;
        when(llmClient.generateResponse("some prompt")).thenReturn(llmJson);

        ExplainSqlResponse response = sqlExplanationService.explain("owner", request);

        assertEquals(request.getSql(), response.getSql());
        assertEquals("Lay ten khach hang o Ha Noi", response.getSummary());
        assertEquals(3, response.getSteps().size());
        assertEquals("SELECT full_name", response.getSteps().get(0).getClause());

        // Khong co connectionId nen KHONG duoc dong cham den user/connection repo
        verifyNoInteractions(userRepository, connectionRepository, schemaRepository);
    }

    @Test
    void explain_shouldStripMarkdownCodeFences_beforeParsing() {
        ExplainSqlRequest request = new ExplainSqlRequest();
        request.setSql("SELECT COUNT(*) FROM orders");

        when(promptBuilder.buildExplanationPrompt(any(), isNull())).thenReturn("prompt");

        // AI van boc trong ```json du prompt da yeu cau khong lam vay
        String llmJsonWithFences = """
```json
                {"summary": "Dem so don hang", "steps": [{"clause": "SELECT COUNT(*)", "explanation": "Dem tong so dong"}]}
```
                """;
        when(llmClient.generateResponse("prompt")).thenReturn(llmJsonWithFences);

        ExplainSqlResponse response = sqlExplanationService.explain("owner", request);

        assertEquals("Dem so don hang", response.getSummary());
        assertEquals(1, response.getSteps().size());
    }

    @Test
    void explain_shouldUseSchemaContext_whenConnectionIdProvidedAndOwned() {
        ExplainSqlRequest request = new ExplainSqlRequest();
        request.setSql("SELECT * FROM customers");
        request.setDatabaseConnectionId(10L);

        DatabaseSchema schema = DatabaseSchema.builder().id(1L).connection(connection).build();

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.of(schema));
        when(promptBuilder.buildExplanationPrompt(request.getSql(), schema)).thenReturn("prompt-with-schema");
        when(llmClient.generateResponse("prompt-with-schema"))
                .thenReturn("{\"summary\": \"ok\", \"steps\": []}");

        ExplainSqlResponse response = sqlExplanationService.explain("owner", request);

        assertEquals("ok", response.getSummary());
        verify(promptBuilder).buildExplanationPrompt(request.getSql(), schema);
    }

    @Test
    void explain_shouldProceedWithoutSchema_whenSchemaNotYetDiscovered() {
        ExplainSqlRequest request = new ExplainSqlRequest();
        request.setSql("SELECT * FROM customers");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.empty());
        when(promptBuilder.buildExplanationPrompt(eq(request.getSql()), isNull())).thenReturn("prompt");
        when(llmClient.generateResponse("prompt")).thenReturn("{\"summary\": \"ok\", \"steps\": []}");

        // Khong duoc nem loi du chua co schema - chi bo qua context
        assertDoesNotThrow(() -> sqlExplanationService.explain("owner", request));
    }

    @Test
    void explain_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {
        ExplainSqlRequest request = new ExplainSqlRequest();
        request.setSql("SELECT * FROM customers");
        request.setDatabaseConnectionId(10L);

        when(userRepository.findByUsername("intruder")).thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        assertThrows(
                IllegalArgumentException.class,
                () -> sqlExplanationService.explain("intruder", request)
        );

        verifyNoInteractions(llmClient);
    }

    @Test
    void explain_shouldThrow_whenLlmReturnsInvalidJson() {
        ExplainSqlRequest request = new ExplainSqlRequest();
        request.setSql("SELECT 1");

        when(promptBuilder.buildExplanationPrompt(any(), isNull())).thenReturn("prompt");
        when(llmClient.generateResponse("prompt")).thenReturn("day khong phai JSON hop le");

        assertThrows(
                RuntimeException.class,
                () -> sqlExplanationService.explain("owner", request)
        );
    }
}