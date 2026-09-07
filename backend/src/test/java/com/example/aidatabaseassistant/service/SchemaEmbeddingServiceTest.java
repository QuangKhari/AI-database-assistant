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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

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

    // FIX (audit Redis caching): SchemaEmbeddingService giờ cần
    // CacheManager (bean "sharedCacheManager") để tự tay evict cache
    // "tableEmbeddings" khi embedding thật sự thay đổi - xem
    // evictTableEmbeddingsCache() / getEmbeddingsByTableName().
    @Mock
    private CacheManager sharedCacheManager;

    private SchemaEmbeddingService service;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {

        objectMapper = new ObjectMapper();

        service = new SchemaEmbeddingService(
                llmClient,
                tableEmbeddingRepository,
                objectMapper,
                sharedCacheManager
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
    // 4. CACHE "tableEmbeddings" - EVICT CÓ ĐIỀU KIỆN
    //    (FIX audit Redis caching)
    // =========================================================

    @Test
    void shouldEvictTableEmbeddingsCache_whenNewEmbeddingCreated() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn("Danh sách khách hàng");
        when(table.getColumns()).thenReturn(List.of());

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of());

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        Cache tableEmbeddingsCache = mock(Cache.class);
        when(sharedCacheManager.getCache(com.example.aidatabaseassistant.config.CacheConfig.TABLE_EMBEDDINGS_CACHE))
                .thenReturn(tableEmbeddingsCache);

        // Act
        service.ensureEmbeddings(schema);

        // Assert: có embedding MỚI được tạo -> PHẢI evict cache của
        // đúng schemaId=1L, không phải evict "tất cả" hay evict key khác.
        verify(tableEmbeddingsCache).evict(1L);
    }

    @Test
    void shouldNotEvictTableEmbeddingsCache_whenNothingChanged() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn("Danh sách khách hàng");
        when(table.getColumns()).thenReturn(List.of());

        String content = "Bảng: customers - Danh sách khách hàng. Cột: ";
        String hash = sha256(content);

        TableEmbedding existing =
                TableEmbedding.builder()
                        .schema(schema)
                        .tableName("customers")
                        .vectorJson("[0.1,0.2,0.3]")
                        .contentHash(hash)
                        .modelName("gemini-embedding-001")
                        .build();

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(existing));

        // Act
        service.ensureEmbeddings(schema);

        // Assert: KHÔNG có gì thay đổi (hash + model trùng) -> KHÔNG
        // được đụng tới cache manager. Đây chính là điểm mấu chốt của
        // fix "evict có điều kiện" - ensureEmbeddings() chạy trên MỌI
        // câu hỏi (xem SchemaRetrievalService), nếu evict vô điều kiện
        // ở đây sẽ vô hiệu hóa toàn bộ lợi ích của cache.
        verifyNoInteractions(sharedCacheManager);
    }

    @Test
    void shouldEvictTableEmbeddingsCache_whenStaleEmbeddingDeleted() {

        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));

        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn(null);
        when(table.getColumns()).thenReturn(List.of());

        String currentContent = "Bảng: customers. Cột: ";
        String currentHash = sha256(currentContent);

        TableEmbedding current =
                TableEmbedding.builder()
                        .schema(schema)
                        .tableName("customers")
                        .vectorJson("[0.1,0.2]")
                        .contentHash(currentHash)
                        .modelName("gemini-embedding-001")
                        .build();

        TableEmbedding stale =
                TableEmbedding.builder()
                        .schema(schema)
                        .tableName("old_orders")
                        .vectorJson("[0.3,0.4]")
                        .contentHash("old")
                        .modelName("gemini-embedding-001")
                        .build();

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of(current, stale));

        Cache tableEmbeddingsCache = mock(Cache.class);
        when(sharedCacheManager.getCache(com.example.aidatabaseassistant.config.CacheConfig.TABLE_EMBEDDINGS_CACHE))
                .thenReturn(tableEmbeddingsCache);

        // Act
        service.ensureEmbeddings(schema);

        // Assert: "customers" không đổi, nhưng "old_orders" bị xóa vì
        // stale -> vẫn tính là CÓ thay đổi -> PHẢI evict.
        verify(tableEmbeddingsCache).evict(1L);
    }

    @Test
    void shouldNotFail_whenCacheNotConfigured() {
        // Neu sharedCacheManager.getCache(...) tra ve null (vi du ten
        // cache chua duoc dang ky, hoac app.cache.provider=none), method
        // KHONG duoc nem NullPointerException - phai bo qua nhe nhang,
        // giong het idiom cua SchemaMetadataService.evictFullSchemaCache().
        DatabaseSchema schema = mock(DatabaseSchema.class);
        TableMetadata table = mock(TableMetadata.class);

        when(schema.getId()).thenReturn(1L);
        when(schema.getTables()).thenReturn(List.of(table));
        when(table.getName()).thenReturn("customers");
        when(table.getDescription()).thenReturn("Danh sách khách hàng");
        when(table.getColumns()).thenReturn(List.of());

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of());
        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{0.1f, 0.2f});

        when(sharedCacheManager.getCache(com.example.aidatabaseassistant.config.CacheConfig.TABLE_EMBEDDINGS_CACHE))
                .thenReturn(null);

        assertDoesNotThrow(() -> service.ensureEmbeddings(schema));
    }

    // =========================================================
    // 5. getEmbeddingsByTableName() - cache read-through
    //    (FIX audit Redis caching: method mới, dùng bởi
    //    SchemaRetrievalService thay vì query repository trực tiếp)
    // =========================================================

    @Test
    void getEmbeddingsByTableName_shouldReturnNormalizedMap() {

        TableEmbedding customers =
                TableEmbedding.builder()
                        .tableName("Customers")   // chữ hoa lẫn lộn
                        .vectorJson("[0.1,0.2]")
                        .build();

        TableEmbedding orders =
                TableEmbedding.builder()
                        .tableName("  orders  ")  // có khoảng trắng thừa
                        .vectorJson("[0.3,0.4]")
                        .build();

        when(tableEmbeddingRepository.findBySchemaId(5L))
                .thenReturn(List.of(customers, orders));

        Map<String, float[]> result =
                service.getEmbeddingsByTableName(5L);

        assertEquals(2, result.size());

        // Key PHẢI được chuẩn hóa giống hệt normalizeName() của
        // SchemaRetrievalService (trim + lowercase) - nếu không, lookup
        // ở SchemaRetrievalService sẽ luôn miss.
        assertTrue(result.containsKey("customers"));
        assertTrue(result.containsKey("orders"));

        assertArrayEquals(
                new float[]{0.1f, 0.2f},
                result.get("customers")
        );

        assertArrayEquals(
                new float[]{0.3f, 0.4f},
                result.get("orders")
        );
    }

    @Test
    void getEmbeddingsByTableName_shouldSkipBlankTableNames() {

        TableEmbedding blank =
                TableEmbedding.builder()
                        .tableName("   ")
                        .vectorJson("[0.1,0.2]")
                        .build();

        when(tableEmbeddingRepository.findBySchemaId(5L))
                .thenReturn(List.of(blank));

        Map<String, float[]> result =
                service.getEmbeddingsByTableName(5L);

        assertTrue(result.isEmpty());
    }

    @Test
    void getEmbeddingsByTableName_shouldReturnNullVector_whenJsonInvalid() {
        // Giống hanh vi cu cua SchemaRetrievalService.fromJson(): JSON
        // loi -> tra null cho bang do, KHONG duoc lam crash ca method
        // (retrieval se tu loai bo bang co vector null o buoc sau).
        TableEmbedding corrupted =
                TableEmbedding.builder()
                        .tableName("customers")
                        .vectorJson("{not-valid-json")
                        .build();

        when(tableEmbeddingRepository.findBySchemaId(5L))
                .thenReturn(List.of(corrupted));

        Map<String, float[]> result =
                service.getEmbeddingsByTableName(5L);

        assertTrue(result.containsKey("customers"));
        assertNull(result.get("customers"));
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