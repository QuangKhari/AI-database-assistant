package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaRetrievalServiceTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private SchemaEmbeddingService schemaEmbeddingService;

    @Mock
    private DatabaseSchemaRepository databaseSchemaRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private SchemaRetrievalService service;

    @BeforeEach
    void setUp() {

        service = new SchemaRetrievalService(
                llmClient,
                schemaEmbeddingService,
                databaseSchemaRepository,
                transactionManager
        );

        /*
         * Vì ragEnabled và topK được inject bằng @Value
         * nên trong unit test ta set thủ công.
         */
        ReflectionTestUtils.setField(
                service,
                "ragEnabled",
                true
        );

        ReflectionTestUtils.setField(
                service,
                "topK",
                5
        );

        ReflectionTestUtils.setField(
                service,
                "minTablesToActivate",
                8
        );
    }

    @Test
    void shouldReturnFullSchemaWhenRagIsDisabled() {

        DatabaseSchema schema = createSchema(10);

        ReflectionTestUtils.setField(
                service,
                "ragEnabled",
                false
        );

        DatabaseSchema result =
                service.retrieveRelevantSchema(
                        "Tìm khách hàng",
                        schema
                );

        assertSame(schema, result);

        verifyNoInteractions(schemaEmbeddingService);
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldReturnFullSchemaWhenDatabaseIsSmall() {

        DatabaseSchema schema = createSchema(5);

        DatabaseSchema result =
                service.retrieveRelevantSchema(
                        "Tìm khách hàng",
                        schema
                );

        assertSame(schema, result);

        verifyNoInteractions(schemaEmbeddingService);
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldRetrieveTopKTables() {

        List<TableMetadata> tables = createTables(10);

        DatabaseSchema schema = createSchema(tables);

        stubRagSchemaLoad(schema);

        /*
         * question vector
         */
        when(llmClient.generateEmbedding("Tìm khách hàng"))
                .thenReturn(new float[]{1f, 0f});

        /*
         * Mỗi table có embedding.
         *
         * customers -> similarity cao nhất
         * orders     -> thứ 2
         * products   -> thứ 3
         * ...
         */
        List<TableEmbedding> embeddings = new ArrayList<>();

        for (int i = 0; i < tables.size(); i++) {

            TableMetadata table = tables.get(i);

            double angle = Math.toRadians(i * 10);

            float x = (float) Math.cos(angle);
            float y = (float) Math.sin(angle);

            TableEmbedding embedding = TableEmbedding.builder()
                    .schema(schema)
                    .tableName(table.getName())
                    .vectorJson("[" + x + "," + y + "]")
                    .contentHash("hash-" + i)
                    .modelName("gemini-embedding-001")
                    .build();

            embeddings.add(embedding);
        }

        when(schemaEmbeddingService.getEmbeddingsByTableName(1L))
                .thenReturn(toEmbeddingMap(embeddings));

        DatabaseSchema result =
                service.retrieveRelevantSchema(
                        "Tìm khách hàng",
                        schema
                );

        /*
         * topK = 5
         *
         * Tuy nhiên FK expansion có thể làm số lượng tăng.
         * Ở test này không tạo FK nên phải đúng 5.
         */
        assertEquals(5, result.getTables().size());

        assertEquals(
                "table_0",
                result.getTables().get(0).getName()
        );

        assertEquals(
                "table_1",
                result.getTables().get(1).getName()
        );

        assertEquals(
                "table_2",
                result.getTables().get(2).getName()
        );

        assertEquals(
                "table_3",
                result.getTables().get(3).getName()
        );

        assertEquals(
                "table_4",
                result.getTables().get(4).getName()
        );

        verify(schemaEmbeddingService)
                .ensureEmbeddings(schema);

        verify(llmClient)
                .generateEmbedding("Tìm khách hàng");

        /*
         * Quan trọng:
         * getEmbeddingsByTableName() chỉ được gọi 1 lần.
         *
         * Đây chính là test chống N+1 query (trước đây verify thẳng
         * tableEmbeddingRepository.findBySchemaId - giờ SchemaRetrievalService
         * không còn gọi trực tiếp repository nữa, xem
         * SchemaEmbeddingService.getEmbeddingsByTableName).
         */
        verify(schemaEmbeddingService, times(1))
                .getEmbeddingsByTableName(1L);
    }

    @Test
    void shouldExcludeUnrelatedTablesBelowSimilarityThreshold() {

        /*
         * Mô phỏng schema có 1 bảng liên quan (customers)
         * và nhiều bảng "nhiễu" hoàn toàn không liên quan
         * (không FK tới customers), giống tình huống schema
         * thật lớn có nhiều domain khác nhau
         * (VD: employees, audit_logs, notifications...).
         */
        TableMetadata customers = mock(TableMetadata.class);

        when(customers.getName())
                .thenReturn("customers");

        when(customers.getColumns())
                .thenReturn(List.of());

        List<TableMetadata> tables =
                new ArrayList<>();

        tables.add(customers);

        for (int i = 0; i < 9; i++) {

            TableMetadata noise =
                    mock(TableMetadata.class);

            when(noise.getName())
                    .thenReturn("noise_table_" + i);

            when(noise.getColumns())
                    .thenReturn(List.of());

            tables.add(noise);
        }

        DatabaseSchema schema = createSchema(tables);

        stubRagSchemaLoad(schema);

        ReflectionTestUtils.setField(
                service,
                "minSimilarity",
                0.6
        );

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{1f, 0f});

        List<TableEmbedding> embeddings =
                new ArrayList<>();

        /*
         * customers gần như song song với question vector
         * -> similarity ~ 1.0, vượt ngưỡng 0.6.
         */
        embeddings.add(
                createEmbedding(
                        schema,
                        "customers",
                        "[1.0,0.0]"
                )
        );

        /*
         * Các bảng nhiễu gần như vuông góc với question vector
         * -> similarity ~ 0.0, dưới ngưỡng 0.6.
         */
        for (int i = 0; i < 9; i++) {

            embeddings.add(
                    createEmbedding(
                            schema,
                            "noise_table_" + i,
                            "[0.0,1.0]"
                    )
            );
        }

        when(schemaEmbeddingService.getEmbeddingsByTableName(1L))
                .thenReturn(toEmbeddingMap(embeddings));

        DatabaseSchema result =
                service.retrieveRelevantSchema(
                        "Tìm khách hàng",
                        schema
                );

        /*
         * Chỉ còn customers, các bảng nhiễu bị loại
         * dù topK = 5 (đủ chỗ để lấy thêm nếu không có threshold).
         */
        assertEquals(1, result.getTables().size());

        assertEquals(
                "customers",
                result.getTables().get(0).getName()
        );
    }

    @Test
    void shouldFallBackToFullSchemaWhenNoTableMeetsThreshold() {

        List<TableMetadata> tables = createTables(10);

        DatabaseSchema schema = createSchema(tables);

        stubRagSchemaLoad(schema);

        ReflectionTestUtils.setField(
                service,
                "minSimilarity",
                0.9
        );

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{1f, 0f});

        List<TableEmbedding> embeddings = new ArrayList<>();

        /*
         * Tất cả bảng đều gần như vuông góc với câu hỏi
         * -> similarity thấp, không bảng nào đạt ngưỡng 0.9.
         */
        for (TableMetadata table : tables) {

            embeddings.add(
                    createEmbedding(
                            schema,
                            table.getName(),
                            "[0.05,1.0]"
                    )
            );
        }

        when(schemaEmbeddingService.getEmbeddingsByTableName(1L))
                .thenReturn(toEmbeddingMap(embeddings));

        DatabaseSchema result =
                service.retrieveRelevantSchema(
                        "Câu hỏi không liên quan tới bảng nào",
                        schema
                );

        /*
         * Không bảng nào đạt ngưỡng -> fallback về full schema
         * thay vì trả schema rỗng.
         */
        assertSame(schema, result);
    }

    @Test
    void shouldExpandReferencedTablesThroughForeignKey() {

        TableMetadata customers = mock(TableMetadata.class);
        TableMetadata orders = mock(TableMetadata.class);
        TableMetadata products = mock(TableMetadata.class);

        when(customers.getName()).thenReturn("customers");
        when(orders.getName()).thenReturn("orders");
        when(products.getName()).thenReturn("products");

        /*
         * orders.customer_id -> customers
         */
        ColumnMetadata customerId = mock(ColumnMetadata.class);

        when(customerId.getForeignKey())
                .thenReturn(true);

        when(customerId.getReferencedTable())
                .thenReturn("customers");

        when(orders.getColumns())
                .thenReturn(List.of(customerId));

        when(customers.getColumns())
                .thenReturn(List.of());

        when(products.getColumns())
                .thenReturn(List.of());

        DatabaseSchema schema =
                createSchema(
                        List.of(
                                customers,
                                orders,
                                products
                        )
                );

        /*
         * Thêm dummy tables để vượt MIN_TABLES_TO_ACTIVATE = 8
         */
        List<TableMetadata> allTables =
                new ArrayList<>(schema.getTables());

        for (int i = 3; i < 10; i++) {

            TableMetadata table = mock(TableMetadata.class);

            when(table.getName())
                    .thenReturn("table_" + i);

            when(table.getColumns())
                    .thenReturn(List.of());

            allTables.add(table);
        }

        schema.setTables(allTables);

        stubRagSchemaLoad(schema);

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{1f, 0f});

        List<TableEmbedding> embeddings =
                new ArrayList<>();

        /*
         * customers được xếp cao nhất.
         */
        embeddings.add(
                createEmbedding(
                        schema,
                        "customers",
                        "[1.0,0.0]"
                )
        );

        for (int i = 1; i < allTables.size(); i++) {

            embeddings.add(
                    createEmbedding(
                            schema,
                            allTables.get(i).getName(),
                            "[0.1,1.0]"
                    )
            );
        }

        when(schemaEmbeddingService.getEmbeddingsByTableName(1L))
                .thenReturn(toEmbeddingMap(embeddings));

        DatabaseSchema result =
                service.retrieveRelevantSchema(
                        "Tìm thông tin khách hàng",
                        schema
                );

        /*
         * customers nằm trong top-K.
         *
         * orders tham chiếu tới customers
         * => orders phải được expand vào schema.
         */
        assertTrue(
                result.getTables()
                        .stream()
                        .anyMatch(t ->
                                t.getName().equalsIgnoreCase("customers"))
        );

        assertTrue(
                result.getTables()
                        .stream()
                        .anyMatch(t ->
                                t.getName().equalsIgnoreCase("orders"))
        );
    }

    @Test
    void shouldExpandTableReferencedBySelectedTable() {

        TableMetadata customers = mock(TableMetadata.class);
        TableMetadata orders = mock(TableMetadata.class);

        when(customers.getName())
                .thenReturn("customers");

        when(orders.getName())
                .thenReturn("orders");

        /*
         * customers.customer_id -> orders
         *
         * Test chiều ngược:
         * selected = orders
         * customers phải được expand.
         */
        ColumnMetadata column =
                mock(ColumnMetadata.class);

        when(column.getForeignKey())
                .thenReturn(true);

        when(column.getReferencedTable())
                .thenReturn("orders");

        when(customers.getColumns())
                .thenReturn(List.of(column));

        when(orders.getColumns())
                .thenReturn(List.of());

        List<TableMetadata> tables =
                new ArrayList<>();

        tables.add(orders);
        tables.add(customers);

        for (int i = 2; i < 10; i++) {

            TableMetadata table =
                    mock(TableMetadata.class);

            when(table.getName())
                    .thenReturn("table_" + i);

            when(table.getColumns())
                    .thenReturn(List.of());

            tables.add(table);
        }

        DatabaseSchema schema =
                createSchema(tables);

        stubRagSchemaLoad(schema);

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{1f, 0f});

        List<TableEmbedding> embeddings =
                new ArrayList<>();

        /*
         * orders được chọn.
         */
        embeddings.add(
                createEmbedding(
                        schema,
                        "orders",
                        "[1.0,0.0]"
                )
        );

        embeddings.add(
                createEmbedding(
                        schema,
                        "customers",
                        "[0.1,1.0]"
                )
        );

        for (int i = 2; i < tables.size(); i++) {

            embeddings.add(
                    createEmbedding(
                            schema,
                            tables.get(i).getName(),
                            "[0.1,1.0]"
                    )
            );
        }

        when(schemaEmbeddingService.getEmbeddingsByTableName(1L))
                .thenReturn(toEmbeddingMap(embeddings));

        DatabaseSchema result =
                service.retrieveRelevantSchema(
                        "Tìm đơn hàng",
                        schema
                );

        assertTrue(
                result.getTables()
                        .stream()
                        .anyMatch(t ->
                                t.getName().equalsIgnoreCase("orders"))
        );

        assertTrue(
                result.getTables()
                        .stream()
                        .anyMatch(t ->
                                t.getName().equalsIgnoreCase("customers"))
        );
    }

    @Test
    void shouldCallEnsureEmbeddingsBeforeRetrieval() {

        DatabaseSchema schema =
                createSchema(10);

        stubRagSchemaLoad(schema);

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{1f, 0f});

        when(schemaEmbeddingService.getEmbeddingsByTableName(1L))
                .thenReturn(java.util.Map.of());

        service.retrieveRelevantSchema(
                "Tìm khách hàng",
                schema
        );

        var inOrder =
                inOrder(
                        schemaEmbeddingService,
                        llmClient
                );

        inOrder.verify(schemaEmbeddingService)
                .ensureEmbeddings(schema);

        inOrder.verify(llmClient)
                .generateEmbedding("Tìm khách hàng");

        inOrder.verify(schemaEmbeddingService)
                .getEmbeddingsByTableName(1L);
    }

    @Test
    void shouldNotQueryEmbeddingRepositoryForSmallDatabase() {

        DatabaseSchema schema =
                createSchema(8);

        service.retrieveRelevantSchema(
                "Tìm dữ liệu",
                schema
        );

        verify(
                schemaEmbeddingService,
                never()
        ).getEmbeddingsByTableName(anyLong());

        verify(
                schemaEmbeddingService,
                never()
        ).ensureEmbeddings(any());
    }

    private DatabaseSchema createSchema(int numberOfTables) {

        return createSchema(
                createTables(numberOfTables)
        );
    }

    private DatabaseSchema createSchema(
            List<TableMetadata> tables) {

        DatabaseConnection connection =
                mock(DatabaseConnection.class);

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("test_db")
                        .dbType("MYSQL")
                        .tables(tables)
                        .build();

        return schema;
    }

    private List<TableMetadata> createTables(
            int numberOfTables) {

        List<TableMetadata> tables =
                new ArrayList<>();

        for (int i = 0; i < numberOfTables; i++) {

            TableMetadata table =
                    mock(TableMetadata.class);

            /*
             * lenient(): createTables() la helper dung chung cho nhieu test.
             *
             * Voi cac test RAG khong kich hoat (RAG disabled / DB nho -
             * shouldReturnFullSchemaWhenRagIsDisabled,
             * shouldReturnFullSchemaWhenDatabaseIsSmall,
             * shouldNotQueryEmbeddingRepositoryForSmallDatabase),
             * retrieveRelevantSchema() tra ve full schema NGAY LAP TUC va
             * khong bao gio goi getName()/getColumns() cua tung table.
             *
             * Neu khong danh dau lenient, Mockito strict mode se bao loi
             * UnnecessaryStubbingException cho nhung test do, du day la
             * hanh vi dung (early return), khong phai bug.
             */
            lenient().when(table.getName())
                    .thenReturn("table_" + i);

            lenient().when(table.getColumns())
                    .thenReturn(List.of());

            tables.add(table);
        }

        return tables;
    }

    private TableEmbedding createEmbedding(
            DatabaseSchema schema,
            String tableName,
            String vectorJson) {

        return TableEmbedding.builder()
                .schema(schema)
                .tableName(tableName)
                .vectorJson(vectorJson)
                .contentHash("hash-" + tableName)
                .modelName("text-embedding-004")
                .build();
    }

    /*
     * FIX (audit Redis caching): SchemaRetrievalService KHONG con doc
     * truc tiep tu TableEmbeddingRepository nua - no goi
     * schemaEmbeddingService.getEmbeddingsByTableName(schemaId), method
     * co @Cacheable tra ve san Map<String, float[]> (xem
     * SchemaEmbeddingService). Helper nay giu nguyen cach cac test o
     * tren XAY DUNG du lieu (List<TableEmbedding> + vectorJson dang
     * chuoi JSON), chi doi "diem stub" tu repository sang service, thay
     * vi phai viet lai tung test de tu tay dung Map<String, float[]>.
     *
     * Viec parse JSON + chuan hoa ten bang o day PHAI khop dung logic
     * that trong SchemaEmbeddingService.getEmbeddingsByTableName()
     * (trim + toLowerCase(Locale.ROOT)) de test phan anh dung hanh vi
     * production.
     */

    private void stubRagSchemaLoad(DatabaseSchema schema) {

        /*
         * TransactionTemplate bên production sẽ gọi:
         *
         * transactionManager.getTransaction(...)
         *
         * sau đó execute callback,
         * rồi commit transaction.
         */
        when(transactionManager.getTransaction(any()))
                .thenReturn(transactionStatus);

        doNothing()
                .when(transactionManager)
                .commit(transactionStatus);

        /*
         * Repository được gọi bên trong transaction.
         *
         * Trả lại chính schema fixture của test để:
         *
         * verify(schemaEmbeddingService)
         *     .ensureEmbeddings(schema)
         *
         * vẫn đúng object mà test đã tạo.
         */
        when(databaseSchemaRepository.findByIdForRag(schema.getId()))
                .thenReturn(java.util.Optional.of(schema));
    }

    private Map<String, float[]> toEmbeddingMap(List<TableEmbedding> embeddings) {

        ObjectMapper mapper = new ObjectMapper();
        Map<String, float[]> result = new java.util.HashMap<>();

        for (TableEmbedding embedding : embeddings) {
            try {
                result.put(
                        embedding.getTableName().trim().toLowerCase(Locale.ROOT),
                        mapper.readValue(embedding.getVectorJson(), float[].class)
                );
            } catch (Exception e) {
                throw new RuntimeException(
                        "Test fixture lỗi: vectorJson không hợp lệ cho bảng "
                                + embedding.getTableName(), e);
            }
        }

        return result;
    }
}