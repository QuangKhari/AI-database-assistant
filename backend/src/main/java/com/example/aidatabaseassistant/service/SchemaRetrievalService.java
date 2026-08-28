package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.repository.TableEmbeddingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SchemaRetrievalService {

    private static final int DEFAULT_TOP_K = 5;

    // DB nhỏ -> không cần RAG
    private static final int MIN_TABLES_TO_ACTIVATE = 8;

    private final LLMClient llmClient;
    private final TableEmbeddingRepository tableEmbeddingRepository;
    private final SchemaEmbeddingService schemaEmbeddingService;
    private final ObjectMapper objectMapper;

    @Value("${schema.rag.enabled:true}")
    private boolean ragEnabled;

    @Value("${schema.rag.top-k:5}")
    private int topK;

    public DatabaseSchema retrieveRelevantSchema(
            String question,
            DatabaseSchema fullSchema
    ) {

        /*
         * ============================================================
         * 1. DB nhỏ hoặc RAG bị tắt
         * ============================================================
         *
         * Không cần embedding.
         * Trả về toàn bộ schema.
         */
        if (!ragEnabled
                || fullSchema.getTables().size() <= MIN_TABLES_TO_ACTIVATE) {

            return fullSchema;
        }

        /*
         * ============================================================
         * 2. Đảm bảo embedding của các bảng đã tồn tại
         * ============================================================
         *
         * Nếu bảng chưa có embedding:
         * -> gọi Embedding API
         *
         * Nếu content_hash không đổi:
         * -> không gọi API lại.
         */
        schemaEmbeddingService.ensureEmbeddings(fullSchema);

        /*
         * ============================================================
         * 3. Load TOÀN BỘ embedding của schema chỉ 1 lần
         * ============================================================
         *
         * Trước đây:
         *
         *   findByTableId(...)
         *
         * được gọi bên trong similarityOf()
         *
         * => mỗi bảng lại query DB một lần
         * => N+1 query.
         *
         * Bây giờ:
         *
         *   findBySchemaId(...)
         *
         * chỉ chạy đúng 1 query.
         */
        Map<String, float[]> embeddingsByName =
                tableEmbeddingRepository
                        .findBySchemaId(fullSchema.getId())
                        .stream()
                        .collect(Collectors.toMap(
                                e -> e.getTableName().toLowerCase(),
                                e -> fromJson(e.getVectorJson()),
                                (existing, replacement) -> existing
                        ));

        /*
         * ============================================================
         * 4. Tạo embedding cho câu hỏi
         * ============================================================
         */
        float[] questionVector =
                llmClient.generateEmbedding(question);

        /*
         * ============================================================
         * 5. Tính cosine similarity
         * ============================================================
         *
         * Không query database ở bước này nữa.
         *
         * Chỉ lấy vector từ Map:
         *
         * embeddingsByName.get(tableName)
         */
        List<TableMetadata> ranked = fullSchema.getTables()
                .stream()
                .map(table -> Map.entry(
                        table,
                        similarityOf(table, questionVector, embeddingsByName)
                ))
                .sorted((a, b) ->
                        Double.compare(
                                b.getValue(),
                                a.getValue()
                        )
                )
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));

        /*
         * ============================================================
         * 6. Lấy tên các bảng được chọn
         * ============================================================
         */
        Set<String> selectedNames = ranked.stream()
                .map(table ->
                        table.getName().toLowerCase()
                )
                .collect(Collectors.toSet());

        /*
         * ============================================================
         * 7. Mở rộng theo Foreign Key
         * ============================================================
         *
         * Ví dụ:
         *
         * orders
         *    |
         *    FK -> customers
         *
         * Nếu RAG chọn orders
         * thì tự động thêm customers.
         *
         * Kiểm tra cả 2 chiều:
         *
         * A -> B
         * B -> A
         */
        List<TableMetadata> expanded =
                new ArrayList<>(ranked);

        for (TableMetadata table : fullSchema.getTables()) {

            /*
             * Bảng đã được chọn rồi -> bỏ qua
             */
            if (selectedNames.contains(
                    table.getName().toLowerCase())) {

                continue;
            }

            /*
             * --------------------------------------------------------
             * Trường hợp 1:
             *
             * table -> selectedTable
             *
             * Ví dụ:
             *
             * orders.customer_id
             *      FK -> customers.id
             *
             * customers đang được chọn
             * => thêm orders
             * --------------------------------------------------------
             */
            boolean referencesSelected =
                    table.getColumns()
                            .stream()
                            .anyMatch(column ->
                                    Boolean.TRUE.equals(
                                            column.getForeignKey()
                                    )
                                            && column.getReferencedTable() != null
                                            && selectedNames.contains(
                                            column.getReferencedTable()
                                                    .toLowerCase()
                                    )
                            );

            /*
             * --------------------------------------------------------
             * Trường hợp 2:
             *
             * selectedTable -> table
             *
             * Ví dụ:
             *
             * orders.customer_id
             *      FK -> customers.id
             *
             * orders đang được chọn
             * => thêm customers
             * --------------------------------------------------------
             */
            boolean referencedBySelected =
                    ranked.stream()
                            .anyMatch(selected ->
                                    selected.getColumns()
                                            .stream()
                                            .anyMatch(column ->
                                                    Boolean.TRUE.equals(
                                                            column.getForeignKey()
                                                    )
                                                            && column.getReferencedTable() != null
                                                            && table.getName()
                                                            .equalsIgnoreCase(
                                                                    column.getReferencedTable()
                                                            )
                                            )
                            );

            /*
             * Nếu có quan hệ FK với bảng đã chọn
             * => thêm bảng vào schema kết quả.
             */
            if (referencesSelected || referencedBySelected) {
                expanded.add(table);
            }
        }

        /*
         * ============================================================
         * 8. Tạo DatabaseSchema mới chỉ chứa bảng liên quan
         * ============================================================
         *
         * Không sửa fullSchema gốc.
         */
        return DatabaseSchema.builder()
                .id(fullSchema.getId())
                .connection(fullSchema.getConnection())
                .databaseName(fullSchema.getDatabaseName())
                .dbType(fullSchema.getDbType())
                .lastSyncedAt(fullSchema.getLastSyncedAt())
                .tables(expanded)
                .build();
    }

    /**
     * Tính similarity của một bảng với câu hỏi.
     *
     * QUAN TRỌNG:
     * Không query database ở đây.
     *
     * Embedding đã được load trước vào:
     *
     * Map<String, float[]> embeddingsByName
     */
    private double similarityOf(
            TableMetadata table,
            float[] questionVector,
            Map<String, float[]> embeddingsByName
    ) {

        float[] tableVector =
                embeddingsByName.get(
                        table.getName().toLowerCase()
                );

        /*
         * Chưa có embedding:
         * -> xếp cuối.
         */
        if (tableVector == null) {
            return -1;
        }

        return cosineSimilarity(
                questionVector,
                tableVector
        );
    }

    /**
     * Cosine Similarity:
     *
     * similarity = (A . B)
     *              ----------------
     *              |A| * |B|
     */
    private double cosineSimilarity(
            float[] a,
            float[] b
    ) {

        /*
         * Hai vector embedding phải có cùng số chiều.
         */
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }

        double dot = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {

            dot += a[i] * b[i];

            normA += a[i] * a[i];

            normB += b[i] * b[i];
        }

        /*
         * Tránh chia cho 0.
         */
        double denominator =
                Math.sqrt(normA) * Math.sqrt(normB);

        if (denominator == 0) {
            return -1;
        }

        return dot / denominator;
    }

    /**
     * Chuyển JSON:
     *
     * "[0.123,0.456,...]"
     *
     * thành:
     *
     * float[]
     */
    private float[] fromJson(String json) {

        try {

            return objectMapper.readValue(
                    json,
                    float[].class
            );

        } catch (Exception e) {

            throw new IllegalArgumentException(
                    "Không thể đọc vector embedding.",
                    e
            );
        }
    }
}