package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.TableEmbeddingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaRetrievalServiceTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private TableEmbeddingRepository tableEmbeddingRepository;

    @Mock
    private SchemaEmbeddingService schemaEmbeddingService;

    private SchemaRetrievalService service;

    @BeforeEach
    void setUp() {

        service = new SchemaRetrievalService(
                llmClient,
                tableEmbeddingRepository,
                schemaEmbeddingService,
                new ObjectMapper()
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
        verifyNoInteractions(tableEmbeddingRepository);
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
        verifyNoInteractions(tableEmbeddingRepository);
    }

    @Test
    void shouldRetrieveTopKTables() {

        List<TableMetadata> tables = createTables(10);

        DatabaseSchema schema = createSchema(tables);

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

            float x = 1f - (i * 0.05f);

            TableEmbedding embedding = TableEmbedding.builder()
                    .schema(schema)
                    .tableName(table.getName())
                    .vectorJson("[" + x + ",0.0]")
                    .contentHash("hash-" + i)
                    .modelName("text-embedding-004")
                    .build();

            embeddings.add(embedding);
        }

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(embeddings);

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
         * findBySchemaId() chỉ được gọi 1 lần.
         *
         * Đây chính là test chống N+1 query.
         */
        verify(tableEmbeddingRepository, times(1))
                .findBySchemaId(1L);
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

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(embeddings);

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

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(embeddings);

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

        when(llmClient.generateEmbedding(anyString()))
                .thenReturn(new float[]{1f, 0f});

        when(tableEmbeddingRepository.findBySchemaId(1L))
                .thenReturn(List.of());

        service.retrieveRelevantSchema(
                "Tìm khách hàng",
                schema
        );

        var inOrder =
                inOrder(
                        schemaEmbeddingService,
                        llmClient,
                        tableEmbeddingRepository
                );

        inOrder.verify(schemaEmbeddingService)
                .ensureEmbeddings(schema);

        inOrder.verify(llmClient)
                .generateEmbedding("Tìm khách hàng");

        inOrder.verify(tableEmbeddingRepository)
                .findBySchemaId(1L);
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
                tableEmbeddingRepository,
                never()
        ).findBySchemaId(anyLong());

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

            when(table.getName())
                    .thenReturn("table_" + i);

            when(table.getColumns())
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
}