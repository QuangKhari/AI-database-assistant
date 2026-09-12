package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SchemaRetrievalService {

    private static final Logger log =
            LoggerFactory.getLogger(SchemaRetrievalService.class);

    /*
     * Nếu database có <= 8 bảng:
     *
     * không cần RAG.
     *
     * Vì gửi toàn bộ schema vẫn đủ nhỏ.
     */

    private final LLMClient llmClient;

    private final SchemaEmbeddingService schemaEmbeddingService;

    private final DatabaseSchemaRepository databaseSchemaRepository;

    /*
     * Dùng TransactionTemplate thay vì @Transactional cho
     * loadSchemaForRag().
     *
     * Mục đích:
     *
     * - chỉ mở transaction trong thời gian LOAD schema
     * - initialize toàn bộ lazy columns
     * - kết thúc transaction
     * - sau đó mới gọi Gemini trong ensureEmbeddings()
     *
     * Tuyệt đối không giữ transaction trong lúc gọi HTTP Gemini.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Bật / tắt RAG bằng application.properties.
     *
     * schema.rag.enabled=true
     */
    @Value("${schema.rag.enabled:true}")
    private boolean ragEnabled;

    /**
     * Số bảng lấy từ semantic retrieval.
     *
     * schema.rag.top-k=5
     */
    @Value("${schema.rag.top-k:5}")
    private int topK;

    /**
     * Số bảng tối thiểu để kích hoạt RAG.
     *
     * Nếu schema có <= minTablesToActivate bảng:
     *
     *     không cần RAG, gửi toàn bộ schema vẫn đủ nhỏ,
     *     không tốn thêm 1 lần gọi embedding API.
     *
     * schema.rag.min-tables-to-activate=8
     */
    @Value("${schema.rag.min-tables-to-activate:8}")
    private int minTablesToActivate;

    /**
     * Ngưỡng cosine similarity tối thiểu để một bảng
     * được coi là "liên quan" tới câu hỏi.
     *
     * schema.rag.min-similarity=0.5
     */
    @Value("${schema.rag.min-similarity:0.62}")
    private double minSimilarity;

    /**
     * Lấy ra schema liên quan nhất với câu hỏi.
     *
     * Pipeline:
     *
     * question
     *      ↓
     * kiểm tra RAG
     *      ↓
     * load schema cho RAG
     *      ↓
     * initialize lazy columns
     *      ↓
     * transaction kết thúc
     *      ↓
     * ensure table embeddings
     *      ↓
     * question embedding
     *      ↓
     * cosine similarity
     *      ↓
     * Top-K tables
     *      ↓
     * FK expansion
     *      ↓
     * filtered schema
     */
    public DatabaseSchema retrieveRelevantSchema(
            String question,
            DatabaseSchema fullSchema) {

        /*
         * =========================================================
         * 0. VALIDATION
         * =========================================================
         */

        if (question == null || question.isBlank()) {
            return fullSchema;
        }

        if (fullSchema == null
                || fullSchema.getTables() == null
                || fullSchema.getTables().isEmpty()) {

            return fullSchema;
        }

        /*
         * =========================================================
         * 1. RAG OFF / DATABASE NHỎ
         * =========================================================
         *
         * Không load lại schema.
         *
         * Không gọi embedding API.
         *
         * Trả nguyên schema.
         */
        if (!ragEnabled
                || fullSchema.getTables().size()
                <= minTablesToActivate) {

            return fullSchema;
        }

        /*
         * =========================================================
         * 2. LOAD SCHEMA RIÊNG CHO RAG
         * =========================================================
         *
         * Đây là phần FIX MultipleBagFetchException + Lazy Loading.
         *
         * findByIdForRag():
         *
         *     FETCH connection
         *     FETCH tables
         *
         * Không FETCH columns trong JPQL vì:
         *
         *     DatabaseSchema.tables  -> List
         *     TableMetadata.columns   -> List
         *
         * Hibernate sẽ báo:
         *
         *     MultipleBagFetchException
         *
         * Vì vậy columns được initialize riêng trong một
         * transaction ngắn bởi loadSchemaForRag().
         */
        DatabaseSchema ragSchema;

        try {

            ragSchema =
                    loadSchemaForRag(
                            fullSchema.getId()
                    );

        } catch (Exception e) {

            /*
             * RAG là chức năng bổ sung.
             *
             * Nếu không thể load schema cho RAG:
             *
             *     không được làm hỏng query chính.
             *
             * Fallback về schema ban đầu.
             */
            log.warn(
                    "Không thể load đầy đủ schema {} cho RAG: {}",
                    fullSchema.getId(),
                    e.getMessage()
            );

            return fullSchema;
        }

        /*
         * =========================================================
         * 3. ĐẢM BẢO TABLE EMBEDDINGS ĐÃ TỒN TẠI
         * =========================================================
         *
         * QUAN TRỌNG:
         *
         * Transaction ở loadSchemaForRag() đã kết thúc.
         *
         * Vì vậy khi ensureEmbeddings() gọi:
         *
         *     llmClient.generateEmbedding(...)
         *
         * sẽ KHÔNG giữ connection của transaction load schema.
         *
         * ensureEmbeddings() cũng KHÔNG được có @Transactional.
         */
        try {

            schemaEmbeddingService.ensureEmbeddings(
                    ragSchema
            );

        } catch (Exception e) {

            /*
             * RAG là chức năng bổ sung.
             *
             * Nếu embedding service lỗi:
             *
             *     không được làm hỏng toàn bộ query.
             *
             * Fallback về full schema.
             */
            log.warn(
                    "Không thể đảm bảo schema embeddings cho schema {}: {}",
                    ragSchema.getId(),
                    e.getMessage()
            );

            return fullSchema;
        }

        /*
         * =========================================================
         * 4. EMBEDDING CỦA QUESTION
         * =========================================================
         */
        float[] questionVector;

        try {

            questionVector =
                    llmClient.generateEmbedding(question);

        } catch (Exception e) {

            /*
             * Nếu không thể tạo embedding cho question:
             *
             * RAG không thể thực hiện semantic retrieval.
             *
             * Fallback về full schema để query vẫn hoạt động.
             */
            log.warn(
                    "Không thể tạo question embedding cho schema {}: {}",
                    ragSchema.getId(),
                    e.getMessage()
            );

            return fullSchema;
        }

        /*
         * Question vector phải hợp lệ.
         *
         * Vector null / rỗng không thể dùng để tính similarity.
         */
        if (!isValidVector(questionVector)) {

            log.warn(
                    "Question embedding không hợp lệ cho schema {}",
                    ragSchema.getId()
            );

            return fullSchema;
        }

        /*
         * =========================================================
         * 5. LOAD TOÀN BỘ TABLE EMBEDDING CHỈ 1 LẦN
         * =========================================================
         *
         * Đọc thông qua SchemaEmbeddingService để sử dụng
         * @Cacheable("tableEmbeddings").
         *
         * Nếu cache hit:
         *
         *     không query DB.
         *
         * Nếu cache miss:
         *
         *     query toàn bộ embedding của schema 1 lần.
         */
        Map<String, float[]> embeddingsByName =
                schemaEmbeddingService.getEmbeddingsByTableName(
                        ragSchema.getId()
                );

        /*
         * =========================================================
         * 6. TÍNH SIMILARITY
         * =========================================================
         */
        List<Map.Entry<TableMetadata, Double>> scored =
                ragSchema.getTables()
                        .stream()

                        /*
                         * Chỉ tính với bảng có embedding.
                         */
                        .filter(table ->
                                table != null
                                        && table.getName() != null
                                        && embeddingsByName.containsKey(
                                        normalizeName(
                                                table.getName()
                                        )
                                )
                        )

                        /*
                         * Vector của từng bảng có thể bị lỗi:
                         *
                         * - JSON không hợp lệ
                         * - vector null
                         * - vector rỗng
                         * - khác dimension
                         * - zero-vector
                         *
                         * cosineSimilarity() sẽ trả NaN.
                         */
                        .map(table -> {

                            float[] tableVector =
                                    embeddingsByName.get(
                                            normalizeName(
                                                    table.getName()
                                            )
                                    );

                            double similarity =
                                    cosineSimilarity(
                                            questionVector,
                                            tableVector
                                    );

                            return Map.entry(
                                    table,
                                    similarity
                            );
                        })

                        /*
                         * Chỉ giữ similarity hợp lệ.
                         */
                        .filter(entry ->
                                Double.isFinite(
                                        entry.getValue()
                                )
                        )

                        /*
                         * Similarity cao nhất đứng đầu.
                         */
                        .sorted(
                                (a, b) ->
                                        Double.compare(
                                                b.getValue(),
                                                a.getValue()
                                        )
                        )

                        .collect(Collectors.toList());

        /*
         * Log toàn bộ similarity score để tiện theo dõi
         * và tinh chỉnh minSimilarity.
         */
        if (log.isDebugEnabled()) {

            log.debug(
                    "Similarity scores cho câu hỏi \"{}\": {}",
                    question,
                    scored.stream()
                            .map(e ->
                                    e.getKey().getName()
                                            + "="
                                            + String.format(
                                            Locale.ROOT,
                                            "%.3f",
                                            e.getValue()
                                    )
                            )
                            .collect(Collectors.joining(", "))
            );
        }

        /*
         * =========================================================
         * 7. LỌC THEO MIN SIMILARITY + TOP-K
         * =========================================================
         */
        List<TableMetadata> ranked =
                scored.stream()

                        /*
                         * Không lấy bảng có similarity quá thấp.
                         */
                        .filter(entry ->
                                entry.getValue()
                                        >= minSimilarity
                        )

                        /*
                         * Không lấy quá topK.
                         */
                        .limit(
                                Math.min(
                                        Math.max(1, topK),
                                        ragSchema.getTables().size()
                                )
                        )

                        .map(Map.Entry::getKey)

                        .collect(
                                Collectors.toCollection(
                                        ArrayList::new
                                )
                        );

        /*
         * =========================================================
         * 8. FALLBACK NẾU KHÔNG CÓ BẢNG PHÙ HỢP
         * =========================================================
         *
         * Không trả schema rỗng.
         *
         * Full schema an toàn hơn.
         */
        if (ranked.isEmpty()) {

            log.warn(
                    "Không có bảng nào đạt ngưỡng similarity >= {} " +
                            "cho schema {} (câu hỏi có thể không liên quan " +
                            "tới bảng nào, hoặc thiếu table embedding hợp lệ). " +
                            "Fallback về full schema.",
                    minSimilarity,
                    ragSchema.getId()
            );

            return fullSchema;
        }

        /*
         * =========================================================
         * 9. LƯU TÊN BẢNG ĐÃ ĐƯỢC CHỌN
         * =========================================================
         */
        Set<String> selectedNames =
                ranked.stream()
                        .map(TableMetadata::getName)
                        .filter(name ->
                                name != null
                                        && !name.isBlank()
                        )
                        .map(this::normalizeName)
                        .collect(Collectors.toSet());

        /*
         * =========================================================
         * 10. FK EXPANSION
         * =========================================================
         *
         * Không chỉ lấy Top-K.
         *
         * Nếu:
         *
         *     orders
         *       ↓ FK
         *     customers
         *
         * thì khi orders được chọn:
         *
         *     customers
         *
         * cũng được thêm vào context.
         *
         * Kiểm tra cả 2 chiều:
         *
         * A -> B
         * B -> A
         */
        List<TableMetadata> expanded =
                new ArrayList<>(ranked);

        /*
         * Dùng Set để tránh thêm trùng table.
         */
        Set<String> expandedNames =
                new HashSet<>(selectedNames);

        for (TableMetadata table :
                ragSchema.getTables()) {

            if (table == null
                    || table.getName() == null
                    || table.getName().isBlank()) {

                continue;
            }

            /*
             * Đã được chọn rồi -> bỏ qua.
             */
            if (expandedNames.contains(
                    normalizeName(table.getName()))) {

                continue;
            }

            /*
             * -----------------------------------------------------
             * Trường hợp 1:
             *
             * table hiện tại REFERENCES bảng được chọn.
             *
             * Ví dụ:
             *
             * orders.customer_id
             *      -> customers.id
             * -----------------------------------------------------
             */
            boolean referencesSelected =
                    table.getColumns() != null
                            && table.getColumns()
                            .stream()
                            .anyMatch(column ->
                                    Boolean.TRUE.equals(
                                            column.getForeignKey()
                                    )
                                            && column
                                            .getReferencedTable() != null
                                            && selectedNames.contains(
                                            normalizeName(
                                                    column
                                                            .getReferencedTable()
                                            )
                                    )
                            );

            /*
             * -----------------------------------------------------
             * Trường hợp 2:
             *
             * bảng được chọn REFERENCES table hiện tại.
             *
             * Ví dụ:
             *
             * orders
             *      -> customers
             *
             * customers là table hiện tại.
             * -----------------------------------------------------
             */
            boolean referencedBySelected =
                    ranked.stream()
                            .anyMatch(selectedTable ->
                                    selectedTable.getColumns() != null
                                            && selectedTable
                                            .getColumns()
                                            .stream()
                                            .anyMatch(column ->
                                                    Boolean.TRUE.equals(
                                                            column.getForeignKey()
                                                    )
                                                            && column
                                                            .getReferencedTable() != null
                                                            && normalizeName(
                                                            table.getName()
                                                    ).equals(
                                                            normalizeName(
                                                                    column
                                                                            .getReferencedTable()
                                                            )
                                                    )
                                            )
                            );

            /*
             * Có quan hệ FK với bảng Top-K
             * -> thêm vào context.
             */
            if (referencesSelected
                    || referencedBySelected) {

                expanded.add(table);

                expandedNames.add(
                        normalizeName(
                                table.getName()
                        )
                );
            }
        }

        log.info(
                "RAG chọn {} bảng (top-{}, min-similarity={}): {} " +
                        "| Sau FK expansion: {} bảng: {}",
                ranked.size(),
                topK,
                minSimilarity,
                ranked.stream()
                        .map(TableMetadata::getName)
                        .toList(),
                expanded.size(),
                expanded.stream()
                        .map(TableMetadata::getName)
                        .toList()
        );

        /*
         * =========================================================
         * 11. TẠO FILTERED SCHEMA
         * =========================================================
         *
         * Không sửa fullSchema.
         *
         * Tạo DatabaseSchema mới.
         *
         * Dùng ragSchema thay vì fullSchema vì ragSchema đã được
         * initialize đầy đủ connection + tables + columns.
         */
        return DatabaseSchema.builder()
                .id(ragSchema.getId())
                .connection(ragSchema.getConnection())
                .databaseName(ragSchema.getDatabaseName())
                .dbType(ragSchema.getDbType())
                .lastSyncedAt(
                        ragSchema.getLastSyncedAt()
                )
                .tables(expanded)
                .build();
    }

    /**
     * Load schema dành riêng cho RAG.
     *
     * =============================================================
     * QUAN TRỌNG VỀ TRANSACTION
     * =============================================================
     *
     * Method này KHÔNG dùng @Transactional vì method được gọi
     * nội bộ từ retrieveRelevantSchema().
     *
     * Thay vào đó sử dụng TransactionTemplate để transaction
     * được tạo và quản lý trực tiếp.
     *
     * Transaction chỉ tồn tại trong đoạn:
     *
     *     findByIdForRag()
     *             +
     *     initialize columns
     *
     * Sau khi execute() kết thúc:
     *
     *     transaction COMMIT
     *     connection được release
     *
     * rồi mới quay lại retrieveRelevantSchema().
     *
     * Sau đó ensureEmbeddings() mới được gọi.
     *
     * Vì vậy Gemini HTTP KHÔNG nằm trong transaction này.
     */
    private DatabaseSchema loadSchemaForRag(Long schemaId) {

        if (schemaId == null) {
            throw new IllegalArgumentException(
                    "schemaId không được null"
            );
        }

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(
                        transactionManager
                );

        DatabaseSchema schema =
                transactionTemplate.execute(status -> {

                    /*
                     * -------------------------------------------------
                     * 1. FETCH connection + tables
                     * -------------------------------------------------
                     *
                     * Query này KHÔNG fetch columns.
                     *
                     * Nếu fetch cả:
                     *
                     *     tables
                     *     columns
                     *
                     * Hibernate sẽ gây:
                     *
                     * MultipleBagFetchException
                     */
                    DatabaseSchema loadedSchema =
                            databaseSchemaRepository
                                    .findByIdForRag(schemaId)
                                    .orElseThrow(() ->
                                            new IllegalArgumentException(
                                                    "Không tìm thấy schema "
                                                            + schemaId
                                            )
                                    );

                    /*
                     * -------------------------------------------------
                     * 2. INITIALIZE COLUMNS
                     * -------------------------------------------------
                     *
                     * TableMetadata.columns là LAZY.
                     *
                     * Truy cập size() trong transaction sẽ buộc
                     * Hibernate load collection.
                     *
                     * Sau khi transaction kết thúc:
                     *
                     *     columns đã initialized.
                     *
                     * Vì vậy buildEmbeddingText() có thể gọi:
                     *
                     *     table.getColumns()
                     *
                     * mà không phụ thuộc vào Hibernate Session.
                     */
                    if (loadedSchema.getTables() != null) {

                        for (TableMetadata table :
                                loadedSchema.getTables()) {

                            if (table != null
                                    && table.getColumns() != null) {

                                table.getColumns().size();
                            }
                        }
                    }

                    return loadedSchema;
                });

        if (schema == null) {
            throw new IllegalStateException(
                    "Không thể load schema " + schemaId
            );
        }

        return schema;
    }

    /**
     * Tính cosine similarity giữa:
     *
     * question vector
     * và
     * table vector.
     */
    private double cosineSimilarity(
            float[] a,
            float[] b) {

        /*
         * Vector khác dimension
         * -> không thể tính.
         *
         * Trả NaN.
         */
        if (!isValidVector(a)
                || !isValidVector(b)
                || a.length != b.length) {

            return Double.NaN;
        }

        double dot = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {

            /*
             * Kiểm tra từng phần tử.
             */
            if (!Float.isFinite(a[i])
                    || !Float.isFinite(b[i])) {

                return Double.NaN;
            }

            dot += a[i] * b[i];

            normA += a[i] * a[i];

            normB += b[i] * b[i];
        }

        /*
         * Vector zero -> similarity không hợp lệ.
         */
        if (normA == 0 || normB == 0) {

            return Double.NaN;
        }

        double similarity =
                dot /
                        (Math.sqrt(normA)
                                * Math.sqrt(normB));

        /*
         * Bảo vệ thêm trường hợp số học bất thường.
         */
        return Double.isFinite(similarity)
                ? similarity
                : Double.NaN;
    }

    /**
     * Kiểm tra vector có hợp lệ hay không.
     *
     * Vector hợp lệ:
     *
     * - không null
     * - không rỗng
     * - tất cả phần tử là số hữu hạn
     */
    private boolean isValidVector(float[] vector) {

        if (vector == null
                || vector.length == 0) {

            return false;
        }

        for (float value : vector) {

            if (!Float.isFinite(value)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Chuẩn hóa tên bảng để so sánh.
     *
     * Dùng Locale.ROOT để tránh vấn đề locale
     * khi chạy trên các môi trường khác nhau.
     */
    private String normalizeName(String name) {

        return name
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}