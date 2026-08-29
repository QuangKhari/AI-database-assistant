package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.TableEmbeddingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
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
    private static final int MIN_TABLES_TO_ACTIVATE = 8;

    private final LLMClient llmClient;
    private final TableEmbeddingRepository tableEmbeddingRepository;
    private final SchemaEmbeddingService schemaEmbeddingService;
    private final ObjectMapper objectMapper;

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
     * Lấy ra schema liên quan nhất với câu hỏi.
     *
     * Pipeline:
     *
     * question
     *      ↓
     * question embedding
     *      ↓
     * table embeddings
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
         * Không gọi embedding API.
         *
         * Trả nguyên schema.
         */
        if (!ragEnabled
                || fullSchema.getTables().size()
                <= MIN_TABLES_TO_ACTIVATE) {

            return fullSchema;
        }

        /*
         * =========================================================
         * 2. ĐẢM BẢO TABLE EMBEDDINGS ĐÃ TỒN TẠI
         * =========================================================
         *
         * Nếu discovery đã tạo embedding:
         *
         *     không gọi lại API nếu hash + model không đổi.
         *
         * Nếu thiếu:
         *
         *     tự động tạo.
         */
        try {
            schemaEmbeddingService.ensureEmbeddings(
                    fullSchema
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
                    fullSchema.getId(),
                    e.getMessage()
            );

            return fullSchema;
        }

        /*
         * =========================================================
         * 3. EMBEDDING CỦA QUESTION
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
                    fullSchema.getId(),
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
                    fullSchema.getId()
            );

            return fullSchema;
        }

        /*
         * =========================================================
         * 4. LOAD TOÀN BỘ TABLE EMBEDDING CHỈ 1 LẦN
         * =========================================================
         *
         * Không query DB cho từng table.
         *
         * Một schema có 50 bảng:
         *
         *     1 query
         *
         * thay vì:
         *
         *     50 queries.
         */
        Map<String, float[]> embeddingsByName =
                tableEmbeddingRepository
                        .findBySchemaId(fullSchema.getId())
                        .stream()
                        .filter(e ->
                                e.getTableName() != null
                                        && !e.getTableName().isBlank()
                        )
                        .collect(Collectors.toMap(
                                e -> normalizeName(
                                        e.getTableName()
                                ),
                                e -> fromJson(
                                        e.getVectorJson()
                                ),
                                (a, b) -> a
                        ));

        /*
         * =========================================================
         * 5. TÍNH SIMILARITY
         * =========================================================
         */
        List<TableMetadata> ranked =
                fullSchema.getTables()
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
                         * Những bảng này sẽ bị loại bỏ.
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
                         *
                         * Không được để vector invalid lọt vào Top-K.
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

                        /*
                         * Không lấy quá số bảng thực tế.
                         */
                        .limit(
                                Math.min(
                                        Math.max(1, topK),
                                        fullSchema.getTables().size()
                                )
                        )

                        .map(Map.Entry::getKey)

                        .collect(
                                Collectors.toCollection(
                                        ArrayList::new
                                )
                        );

        /*
         * Nếu vì lý do nào đó không có embedding hợp lệ:
         *
         * không nên trả schema rỗng.
         *
         * Fallback về full schema an toàn hơn.
         */
        if (ranked.isEmpty()) {

            log.warn(
                    "Không tìm thấy table embedding hợp lệ cho schema {}. " +
                            "Fallback về full schema.",
                    fullSchema.getId()
            );

            return fullSchema;
        }

        /*
         * =========================================================
         * 6. LƯU TÊN BẢNG ĐÃ ĐƯỢC CHỌN
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
         * 7. FK EXPANSION
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
                fullSchema.getTables()) {

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
                                                            .getReferencedTable()
                                                            != null
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

        /*
         * =========================================================
         * 8. TẠO FILTERED SCHEMA
         * =========================================================
         *
         * Quan trọng:
         *
         * Không sửa fullSchema.
         *
         * Tạo DatabaseSchema mới.
         */
        return DatabaseSchema.builder()
                .id(fullSchema.getId())
                .connection(fullSchema.getConnection())
                .databaseName(fullSchema.getDatabaseName())
                .dbType(fullSchema.getDbType())
                .lastSyncedAt(
                        fullSchema.getLastSyncedAt()
                )
                .tables(expanded)
                .build();
    }

    /**
     * Tính cosine similarity giữa:
     *
     * question vector
     * và
     * table vector.
     *
     * Công thức:
     *
     *             A . B
     * --------------------------------
     *       ||A|| * ||B||
     */
    private double cosineSimilarity(
            float[] a,
            float[] b) {

        /*
         * Vector khác dimension
         * -> không thể tính.
         *
         * Trả NaN thay vì -1.
         *
         * NaN sẽ bị filter trước khi Top-K.
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
             *
             * NaN / Infinity sẽ làm vector invalid.
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
     * Chuyển JSON TEXT trong database
     * thành float[].
     *
     * Nếu JSON lỗi:
     *
     *     trả null
     *
     * để retrieval bỏ qua embedding lỗi
     * thay vì làm crash toàn bộ query.
     */
    private float[] fromJson(String json) {

        if (json == null || json.isBlank()) {
            return null;
        }

        try {

            return objectMapper.readValue(
                    json,
                    float[].class
            );

        } catch (Exception e) {

            log.warn(
                    "Không thể đọc embedding vector từ database: {}",
                    e.getMessage()
            );

            return null;
        }
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