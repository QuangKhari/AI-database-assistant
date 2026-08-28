package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.TableEmbeddingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaEmbeddingServiceTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private TableEmbeddingRepository tableEmbeddingRepository;

    private SchemaEmbeddingService service;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        service = new SchemaEmbeddingService(
                llmClient,
                tableEmbeddingRepository,
                objectMapper
        );
    }

    @Test
    void shouldCreateEmbeddingForNewTable() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn("Danh sách khách hàng");
        when(table.getColumns()).thenReturn(List.of());

        when(tableEmbeddingRepository
                .findBySchemaIdAndTableNameIgnoreCase(1L, "customers"))
                .thenReturn(Optional.empty());

        float[] vector = {0.1f, 0.2f, 0.3f};

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(vector);

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of());

        service.ensureEmbeddings(schema);

        verify(llmClient, times(1))
                .generateEmbedding(anyString());

        ArgumentCaptor<TableEmbedding> captor =
                ArgumentCaptor.forClass(TableEmbedding.class);

        verify(tableEmbeddingRepository)
                .save(captor.capture());

        TableEmbedding saved = captor.getValue();

        assertEquals(schema, saved.getSchema());
        assertEquals("customers", saved.getTableName());
        assertEquals("[0.1,0.2,0.3]", saved.getVectorJson());
        assertNotNull(saved.getContentHash());
        assertEquals("text-embedding-004", saved.getModelName());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void shouldNotCallEmbeddingApiWhenContentHashHasNotChanged() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn("Danh sách khách hàng");
        when(table.getColumns()).thenReturn(List.of());

        /*
         * Hash này cần tương ứng với nội dung:
         *
         * Bảng: customers - Danh sách khách hàng. Cột:
         */
        String content =
                "Bảng: customers - Danh sách khách hàng. Cột: ";

        String hash = sha256(content);

        TableEmbedding existing = TableEmbedding.builder()
                .schema(schema)
                .tableName("customers")
                .vectorJson("[0.1,0.2,0.3]")
                .contentHash(hash)
                .modelName("text-embedding-004")
                .build();

        when(tableEmbeddingRepository
                .findBySchemaIdAndTableNameIgnoreCase(1L, "customers"))
                .thenReturn(Optional.of(existing));

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(existing));

        service.ensureEmbeddings(schema);

        verify(llmClient, never())
                .generateEmbedding(anyString());

        verify(tableEmbeddingRepository, never())
                .save(any());
    }

    @Test
    void shouldRegenerateEmbeddingWhenContentHashChanges() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn("Mô tả mới");
        when(table.getColumns()).thenReturn(List.of());

        TableEmbedding existing = TableEmbedding.builder()
                .schema(schema)
                .tableName("customers")
                .vectorJson("[0.1,0.2,0.3]")
                .contentHash("old-hash")
                .modelName("text-embedding-004")
                .build();

        when(tableEmbeddingRepository
                .findBySchemaIdAndTableNameIgnoreCase(1L, "customers"))
                .thenReturn(Optional.of(existing));

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(existing));

        float[] newVector = {0.9f, 0.8f, 0.7f};

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(newVector);

        service.ensureEmbeddings(schema);

        verify(llmClient, times(1))
                .generateEmbedding(anyString());

        verify(tableEmbeddingRepository)
                .save(existing);

        assertEquals(
                "[0.9,0.8,0.7]",
                existing.getVectorJson()
        );

        assertNotEquals(
                "old-hash",
                existing.getContentHash()
        );
    }

    @Test
    void shouldDeleteStaleEmbeddings() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn(null);
        when(table.getColumns()).thenReturn(List.of());

        when(tableEmbeddingRepository
                .findBySchemaIdAndTableNameIgnoreCase(1L, "customers"))
                .thenReturn(Optional.empty());

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{0.1f, 0.2f});

        TableEmbedding current = TableEmbedding.builder()
                .schema(schema)
                .tableName("customers")
                .vectorJson("[0.1,0.2]")
                .contentHash("hash")
                .modelName("text-embedding-004")
                .build();

        TableEmbedding stale = TableEmbedding.builder()
                .schema(schema)
                .tableName("old_orders")
                .vectorJson("[0.3,0.4]")
                .contentHash("old")
                .modelName("text-embedding-004")
                .build();

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(current, stale));

        service.ensureEmbeddings(schema);

        verify(tableEmbeddingRepository)
                .deleteAll(List.of(stale));
    }

    private String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    input.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            return java.util.HexFormat.of().formatHex(hash);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}