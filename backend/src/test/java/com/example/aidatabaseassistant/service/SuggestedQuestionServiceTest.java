package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.dto.SuggestedQuestionsResponse;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuggestedQuestionServiceTest {

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

    // Dùng ObjectMapper THẬT (không mock) vì logic parse/serialize JSON
    // chính là điều cần test, giống cách SqlExplanationServiceTest đang làm.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SuggestedQuestionService suggestedQuestionService;

    private User owner;
    private User otherUser;
    private DatabaseConnection connection;
    private DatabaseSchema schema;

    @BeforeEach
    void setUp() {

        suggestedQuestionService = new SuggestedQuestionService(
                userRepository,
                connectionRepository,
                schemaRepository,
                promptBuilder,
                llmClient,
                objectMapper
        );

        owner = User.builder().id(1L).username("owner").build();
        otherUser = User.builder().id(2L).username("intruder").build();

        connection = DatabaseConnection.builder()
                .id(10L)
                .user(owner)
                .name("Sample DB")
                .dbType("mysql")
                .build();

        ColumnMetadata createdAtColumn = ColumnMetadata.builder()
                .id(1L)
                .name("created_at")
                .dataType("datetime")
                .build();

        TableMetadata customersTable = TableMetadata.builder()
                .id(1L)
                .name("customers")
                .columns(new ArrayList<>(List.of(createdAtColumn)))
                .build();

        schema = DatabaseSchema.builder()
                .id(50L)
                .connection(connection)
                .databaseName("shop")
                .dbType("mysql")
                .tables(new ArrayList<>(List.of(customersTable)))
                .build();
    }

    private void stubOwnedConnectionAndSchema() {
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.of(schema));
        when(promptBuilder.buildSuggestedQuestionsPrompt(eq(schema), eq(8))).thenReturn("PROMPT");
    }

    // ===== Happy path: gọi AI thành công =====

    @Test
    void getSuggestions_shouldReturnAiSource_whenGeminiRespondsValidJson() {

        stubOwnedConnectionAndSchema();

        when(llmClient.generateResponse("PROMPT"))
                .thenReturn("[\"Có bao nhiêu khách hàng?\",\"Top 5 sản phẩm giá cao nhất?\"]");

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        assertEquals("ai", response.getSource());
        assertEquals(2, response.getQuestions().size());
        assertEquals("Có bao nhiêu khách hàng?", response.getQuestions().get(0));

        ArgumentCaptor<DatabaseSchema> captor = ArgumentCaptor.forClass(DatabaseSchema.class);
        verify(schemaRepository).save(captor.capture());

        assertNotNull(captor.getValue().getSuggestedQuestionsJson());
        assertNotNull(captor.getValue().getSuggestedQuestionsGeneratedAt());
    }

    @Test
    void getSuggestions_shouldStripMarkdownFences_beforeParsingJson() {

        stubOwnedConnectionAndSchema();

        when(llmClient.generateResponse("PROMPT"))
                .thenReturn("```json\n[\"Câu hỏi 1\",\"Câu hỏi 2\"]\n```");

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        assertEquals("ai", response.getSource());
        assertEquals(List.of("Câu hỏi 1", "Câu hỏi 2"), response.getQuestions());
    }

    @Test
    void getSuggestions_shouldDedupeAndLimitToMaxQuestions() {

        stubOwnedConnectionAndSchema();

        // 10 phần tử, có 1 trùng lặp ("Q1" xuất hiện lại ở cuối)
        when(llmClient.generateResponse("PROMPT"))
                .thenReturn("[\"Q1\",\"Q2\",\"Q3\",\"Q4\",\"Q5\",\"Q6\",\"Q7\",\"Q8\",\"Q9\",\"Q1\"]");

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        // distinct() giữ Q1..Q9 (9 phần tử) -> limit(8) -> Q1..Q8
        assertEquals(8, response.getQuestions().size());
        assertEquals(List.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8"), response.getQuestions());
    }

    // ===== Fallback template khi AI lỗi =====

    @Test
    void getSuggestions_shouldFallbackToTemplate_whenGeminiThrowsException() {

        stubOwnedConnectionAndSchema();

        when(llmClient.generateResponse("PROMPT"))
                .thenThrow(new RuntimeException("Gemini timeout"));

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        assertEquals("template", response.getSource());
        assertTrue(response.getQuestions().contains("Có bao nhiêu customers?"));
        // bảng "customers" có cột created_at -> phải có thêm câu hỏi "gần đây nhất"
        assertTrue(response.getQuestions().contains("Hiển thị 10 customers gần đây nhất"));

        verify(schemaRepository).save(any(DatabaseSchema.class));
    }

    @Test
    void getSuggestions_shouldFallbackToTemplate_whenGeminiReturnsInvalidJson() {

        stubOwnedConnectionAndSchema();

        when(llmClient.generateResponse("PROMPT"))
                .thenReturn("Đây không phải JSON hợp lệ, xin lỗi tôi không thể trả lời.");

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        assertEquals("template", response.getSource());
        assertFalse(response.getQuestions().isEmpty());
    }

    @Test
    void getSuggestions_shouldUseTableDescription_inTemplateFallback_whenAvailable() {

        // Ghi đè description cho bảng để verify template ưu tiên description hơn tên bảng
        schema.getTables().get(0).setDescription("khách hàng của cửa hàng");

        stubOwnedConnectionAndSchema();

        when(llmClient.generateResponse("PROMPT")).thenThrow(new RuntimeException("lỗi"));

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        assertTrue(response.getQuestions().contains("Có bao nhiêu khách hàng của cửa hàng?"));
    }

    // ===== Cache =====

    @Test
    void getSuggestions_shouldReturnCache_whenSchemaNotResyncedSinceLastGeneration() {

        LocalDateTime generatedAt = LocalDateTime.now();
        schema.setSuggestedQuestionsJson("[\"Câu hỏi đã cache\"]");
        schema.setSuggestedQuestionsGeneratedAt(generatedAt);
        schema.setLastSyncedAt(generatedAt.minusMinutes(10)); // sync trước khi cache -> cache còn hợp lệ

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.of(schema));

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        assertEquals("cache", response.getSource());
        assertEquals(List.of("Câu hỏi đã cache"), response.getQuestions());

        verifyNoInteractions(llmClient);
        verify(schemaRepository, never()).save(any());
    }

    @Test
    void getSuggestions_shouldIgnoreCache_whenSchemaResyncedAfterCacheGenerated() {

        LocalDateTime generatedAt = LocalDateTime.now().minusHours(1);
        schema.setSuggestedQuestionsJson("[\"Câu hỏi cũ\"]");
        schema.setSuggestedQuestionsGeneratedAt(generatedAt);
        schema.setLastSyncedAt(generatedAt.plusMinutes(30)); // resync SAU khi cache -> cache stale

        stubOwnedConnectionAndSchema();

        when(llmClient.generateResponse("PROMPT"))
                .thenReturn("[\"Câu hỏi mới\"]");

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, false);

        assertEquals("ai", response.getSource());
        assertEquals(List.of("Câu hỏi mới"), response.getQuestions());
    }

    @Test
    void getSuggestions_shouldForceAiCall_whenRefreshTrue_evenIfCacheValid() {

        LocalDateTime generatedAt = LocalDateTime.now();
        schema.setSuggestedQuestionsJson("[\"Câu hỏi cache cũ\"]");
        schema.setSuggestedQuestionsGeneratedAt(generatedAt);
        schema.setLastSyncedAt(generatedAt.minusMinutes(10));

        stubOwnedConnectionAndSchema();

        when(llmClient.generateResponse("PROMPT"))
                .thenReturn("[\"Câu hỏi refresh mới\"]");

        SuggestedQuestionsResponse response =
                suggestedQuestionService.getSuggestions("owner", 10L, true);

        assertEquals("ai", response.getSource());
        assertEquals(List.of("Câu hỏi refresh mới"), response.getQuestions());
    }

    // ===== Validation / quyền truy cập =====

    @Test
    void getSuggestions_shouldThrow_whenRequestedByNonOwner_IDOR() {

        when(userRepository.findByUsername("intruder")).thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> suggestedQuestionService.getSuggestions("intruder", 10L, false)
        );

        assertTrue(ex.getMessage().contains("không có quyền"));
        verifyNoInteractions(llmClient);
    }

    @Test
    void getSuggestions_shouldThrow_whenSchemaNotYetDiscovered() {

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> suggestedQuestionService.getSuggestions("owner", 10L, false)
        );
    }

    @Test
    void getSuggestions_shouldThrow_whenSchemaHasNoTables() {

        schema.setTables(new ArrayList<>());

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.of(schema));

        assertThrows(
                IllegalArgumentException.class,
                () -> suggestedQuestionService.getSuggestions("owner", 10L, false)
        );
    }

    @Test
    void getSuggestions_shouldThrow_whenConnectionNotFound() {

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> suggestedQuestionService.getSuggestions("owner", 999L, false)
        );
    }
}