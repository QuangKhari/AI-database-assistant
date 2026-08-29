package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

        /*
         * SchemaEmbeddingService có:
         *
         * @Value("${gemini.embedding.model:gemini-embedding-001}")
         * private String embeddingModel;
         *
         * Nhưng test tạo service bằng new,
         * nên Spring không inject @Value.
         *
         * Vì vậy phải set thủ công.
         */
        ReflectionTestUtils.setField(
                service,
                "embeddingModel",
                "gemini-embedding-001"
        );
    }

    // =========================================================
    // 1. BẢNG MỚI -> TẠO EMBEDDING
    // =========================================================

    @Test
    void shouldCreateEmbeddingForNewTable() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription())
                .thenReturn("Danh sách khách hàng");
        when(table.getColumns())
                .thenReturn(List.of());

        /*
         * Service hiện tại không còn query:
         *
         * findBySchemaIdAndTableNameIgnoreCase()
         *
         * mà load toàn bộ embedding bằng:
         *
         * findBySchemaId()
         */
        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of());

        float[] vector = {
                0.1f,
                0.2f,
                0.3f
        };

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(vector);

        // Act
        service.ensureEmbeddings(schema);

        // Assert

        verify(llmClient, times(1))
                .generateEmbedding(anyString());

        ArgumentCaptor<TableEmbedding> captor =
                ArgumentCaptor.forClass(TableEmbedding.class);

        verify(tableEmbeddingRepository)
                .save(captor.capture());

        TableEmbedding saved = captor.getValue();

        assertEquals(
                schema,
                saved.getSchema()
        );

        assertEquals(
                "customers",
                saved.getTableName()
        );

        assertEquals(
                "[0.1,0.2,0.3]",
                saved.getVectorJson()
        );

        assertNotNull(
                saved.getContentHash()
        );

        assertEquals(
                "gemini-embedding-001",
                saved.getModelName()
        );

        assertNotNull(
                saved.getUpdatedAt()
        );
    }

    // =========================================================
    // 2. HASH KHÔNG ĐỔI -> KHÔNG GỌI EMBEDDING API
    // =========================================================

    @Test
    void shouldNotCallEmbeddingApiWhenContentHashHasNotChanged() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");

        when(table.getDescription())
                .thenReturn("Danh sách khách hàng");

        when(table.getColumns())
                .thenReturn(List.of());

        /*
         * Phải giống chính xác nội dung mà
         * buildEmbeddingText() của service tạo ra.
         */
        String content =
                "Bảng: customers - Danh sách khách hàng. Cột: ";

        String hash = sha256(content);

        TableEmbedding existing =
                TableEmbedding.builder()
                        .schema(schema)
                        .tableName("customers")
                        .vectorJson("[0.1,0.2,0.3]")
                        .contentHash(hash)
                        .modelName("gemini-embedding-001")
                        .build();

        /*
         * Service load toàn bộ embedding của schema.
         */
        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(existing));

        // Act
        service.ensureEmbeddings(schema);

        // Assert

        /*
         * Hash giống + model giống
         * => KHÔNG gọi Gemini.
         */
        verify(llmClient, never())
                .generateEmbedding(anyString());

        /*
         * Không cần update database.
         */
        verify(tableEmbeddingRepository, never())
                .save(any());
    }

    // =========================================================
    // 3. HASH THAY ĐỔI -> TẠO EMBEDDING MỚI
    // =========================================================

    @Test
    void shouldRegenerateEmbeddingWhenContentHashChanges() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");

        when(table.getDescription())
                .thenReturn("Mô tả mới");

        when(table.getColumns())
                .thenReturn(List.of());

        /*
         * Embedding cũ có hash cũ.
         */
        TableEmbedding existing =
                TableEmbedding.builder()
                        .schema(schema)
                        .tableName("customers")
                        .vectorJson("[0.1,0.2,0.3]")
                        .contentHash("old-hash")
                        .modelName("gemini-embedding-001")
                        .build();

        /*
         * Service tìm embedding cũ thông qua
         * findBySchemaId().
         */
        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(existing));

        float[] newVector = {
                0.9f,
                0.8f,
                0.7f
        };

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(newVector);

        // Act
        service.ensureEmbeddings(schema);

        // Assert

        /*
         * Hash thay đổi
         * => phải gọi embedding API.
         */
        verify(llmClient, times(1))
                .generateEmbedding(anyString());

        /*
         * Vì embedding đã tồn tại,
         * service update chính object existing.
         */
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

        assertEquals(
                "gemini-embedding-001",
                existing.getModelName()
        );

        assertNotNull(
                existing.getUpdatedAt()
        );
    }

    // =========================================================
    // 4. XÓA EMBEDDING CỦA BẢNG KHÔNG CÒN TỒN TẠI
    // =========================================================

    @Test
    void shouldDeleteStaleEmbeddings() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");

        when(table.getDescription())
                .thenReturn(null);

        when(table.getColumns())
                .thenReturn(List.of());

        /*
         * Khi description = null và không có column,
         * buildEmbeddingText() tạo:
         *
         * Bảng: customers. Cột:
         */
        String currentContent =
                "Bảng: customers. Cột: ";

        String currentHash = sha256(currentContent);

        /*
         * Embedding của bảng hiện tại.
         */
        TableEmbedding current =
                TableEmbedding.builder()
                        .schema(schema)
                        .tableName("customers")
                        .vectorJson("[0.1,0.2]")
                        .contentHash(currentHash)
                        .modelName("gemini-embedding-001")
                        .build();

        /*
         * Embedding của bảng cũ,
         * hiện không còn trong schema.
         */
        TableEmbedding stale =
                TableEmbedding.builder()
                        .schema(schema)
                        .tableName("old_orders")
                        .vectorJson("[0.3,0.4]")
                        .contentHash("old")
                        .modelName("gemini-embedding-001")
                        .build();

        /*
         * DB đang có:
         *
         * customers
         * old_orders
         *
         * Nhưng schema hiện tại chỉ có:
         *
         * customers
         */
        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(
                        current,
                        stale
                ));

        // Act
        service.ensureEmbeddings(schema);

        // Assert

        /*
         * old_orders không còn trong schema
         * => phải xóa.
         */
        verify(tableEmbeddingRepository)
                .deleteAll(List.of(stale));

        /*
         * customers không thay đổi
         * => không gọi embedding API.
         */
        verify(llmClient, never())
                .generateEmbedding(anyString());

        /*
         * customers cũng không cần save lại.
         */
        verify(tableEmbeddingRepository, never())
                .save(any());
    }

    // =========================================================
    // SHA-256 HELPER
    // =========================================================

    private String sha256(String input) {

        try {

            var digest =
                    java.security.MessageDigest
                            .getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            input.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8
                            )
                    );

            return java.util.HexFormat
                    .of()
                    .formatHex(hash);

        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }
}