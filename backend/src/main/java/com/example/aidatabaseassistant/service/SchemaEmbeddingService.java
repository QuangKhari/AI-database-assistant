package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.TableEmbeddingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SchemaEmbeddingService {

    private final LLMClient llmClient;
    private final TableEmbeddingRepository tableEmbeddingRepository;
    private final ObjectMapper objectMapper;

    /**
     * Model embedding đang sử dụng.
     *
     * Lấy từ application.properties:
     *
     * gemini.embedding.model=gemini-embedding-001
     */
    @Value("${gemini.embedding.model:gemini-embedding-001}")
    private String embeddingModel;

    /**
     * Tạo hoặc cập nhật embedding cho toàn bộ bảng trong schema.
     *
     * Logic:
     *
     * 1. Load toàn bộ embedding hiện tại của schema chỉ bằng 1 query.
     * 2. Tạo Map<tableName, TableEmbedding> để lookup nhanh.
     * 3. Với từng bảng:
     *      - tạo nội dung embedding
     *      - tính SHA-256
     *      - nếu hash + model giống nhau -> bỏ qua
     *      - nếu bảng mới / nội dung thay đổi / model thay đổi
     *        -> gọi Gemini Embedding API
     * 4. Xóa embedding của những bảng không còn tồn tại trong schema.
     */
    @Transactional
    public void ensureEmbeddings(DatabaseSchema schema) {

        if (schema == null) {
            throw new IllegalArgumentException(
                    "DatabaseSchema không được null"
            );
        }

        if (schema.getId() == null) {
            throw new IllegalArgumentException(
                    "DatabaseSchema phải được lưu trước khi tạo embedding"
            );
        }

        /*
         * =========================================================
         * 1. LOAD TOÀN BỘ EMBEDDING CỦA SCHEMA CHỈ 1 LẦN
         * =========================================================
         *
         * Trước đây nếu đặt findBySchemaIdAndTableName() trong vòng for:
         *
         *     for (table : tables) {
         *         repository.findBySchemaIdAndTableName(...)
         *     }
         *
         * thì 20 bảng = 20 query DB.
         *
         * Bây giờ:
         *
         *     findBySchemaId()
         *
         * chỉ chạy 1 query.
         */
        List<TableEmbedding> existingEmbeddings =
                tableEmbeddingRepository.findBySchemaId(schema.getId());

        /*
         * Map theo tên bảng để lookup O(1).
         *
         * Key:
         *     customers
         *
         * Value:
         *     TableEmbedding tương ứng.
         */
        Map<String, TableEmbedding> existingByName =
                existingEmbeddings.stream()
                        .collect(Collectors.toMap(
                                e -> e.getTableName().toLowerCase(),
                                e -> e,
                                (a, b) -> a
                        ));

        /*
         * Lưu tên các bảng hiện tại.
         *
         * Dùng ở cuối method để phát hiện embedding stale.
         */
        Set<String> currentTableNames =
                schema.getTables().stream()
                        .map(TableMetadata::getName)
                        .filter(name -> name != null)
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());

        /*
         * =========================================================
         * 2. XỬ LÝ TỪNG BẢNG
         * =========================================================
         */
        for (TableMetadata table : schema.getTables()) {

            if (table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            /*
             * Tạo text đại diện cho schema của bảng.
             *
             * Ví dụ:
             *
             * Bảng: customers - Thông tin khách hàng.
             * Cột:
             * id(BIGINT) [PRIMARY KEY],
             * full_name(VARCHAR),
             * email(VARCHAR),
             * ...
             */
            String content = buildEmbeddingText(table);

            /*
             * Tạo hash của nội dung.
             *
             * Nếu schema không thay đổi:
             *
             * content -> giống
             * hash -> giống
             *
             * => không cần gọi API embedding lại.
             */
            String hash = sha256(content);

            /*
             * Tìm embedding cũ bằng:
             *
             * schemaId + tableName
             *
             * Không dùng table.getId().
             */
            TableEmbedding existing =
                    existingByName.get(
                            table.getName().toLowerCase()
                    );

            /*
             * =====================================================
             * 3. CACHE CHECK
             * =====================================================
             *
             * Chỉ skip khi:
             *
             * - embedding đã tồn tại
             * - content không thay đổi
             * - model không thay đổi
             *
             * Điều kiện modelName rất quan trọng.
             *
             * Ví dụ:
             *
             * embedding cũ:
             *     text-embedding-004
             *
             * embedding mới:
             *     gemini-embedding-001
             *
             * Hash có thể giống nhau nhưng vector được tạo bởi
             * model khác nhau -> bắt buộc regenerate.
             */
            if (existing != null
                    && hash.equals(existing.getContentHash())
                    && embeddingModel.equals(existing.getModelName())) {

                continue;
            }

            /*
             * =====================================================
             * 4. GỌI GEMINI EMBEDDING API
             * =====================================================
             */
            float[] vector =
                    llmClient.generateEmbedding(content);

            /*
             * Nếu embedding cũ tồn tại:
             *
             *     update record
             *
             * Nếu chưa tồn tại:
             *
             *     create record mới.
             */
            TableEmbedding embedding =
                    existing != null
                            ? existing
                            : TableEmbedding.builder()
                            .schema(schema)
                            .tableName(table.getName())
                            .build();

            /*
             * Lưu vector dưới dạng JSON TEXT.
             */
            embedding.setVectorJson(
                    toJson(vector)
            );

            /*
             * Lưu hash để lần sau biết nội dung có thay đổi không.
             */
            embedding.setContentHash(hash);

            /*
             * Lưu model đã sử dụng để tạo vector.
             */
            embedding.setModelName(embeddingModel);

            /*
             * Cập nhật thời gian.
             */
            embedding.setUpdatedAt(
                    LocalDateTime.now()
            );

            tableEmbeddingRepository.save(embedding);
        }

        /*
         * =========================================================
         * 5. XÓA EMBEDDING STALE
         * =========================================================
         *
         * Ví dụ:
         *
         * Database thật:
         *
         *     customers
         *     orders
         *
         * Trước đây:
         *
         *     customers -> embedding
         *     orders    -> embedding
         *     products  -> embedding
         *
         * User DROP TABLE products.
         *
         * Sau discover:
         *
         *     customers
         *     orders
         *
         * products không còn trong schema.
         *
         * => phải xóa embedding products.
         */
        List<TableEmbedding> stale =
                existingEmbeddings.stream()
                        .filter(e ->
                                !currentTableNames.contains(
                                        e.getTableName().toLowerCase()
                                )
                        )
                        .toList();

        if (!stale.isEmpty()) {
            tableEmbeddingRepository.deleteAll(stale);
        }
    }

    /**
     * Tạo nội dung semantic representation cho một bảng.
     *
     * Embedding không chỉ dựa vào tên bảng.
     *
     * Bao gồm:
     *
     * - tên bảng
     * - description
     * - tên cột
     * - datatype
     * - primary key
     * - foreign key
     * - referenced table
     * - referenced column
     * - description của column
     */
    private String buildEmbeddingText(TableMetadata table) {

        StringBuilder sb = new StringBuilder();

        sb.append("Bảng: ")
                .append(table.getName());

        if (table.getDescription() != null
                && !table.getDescription().isBlank()) {

            sb.append(" - ")
                    .append(table.getDescription());
        }

        sb.append(". Cột: ");

        for (ColumnMetadata column : table.getColumns()) {

            sb.append(column.getName())
                    .append("(")
                    .append(column.getDataType())
                    .append(")");

            /*
             * Primary key
             */
            if (Boolean.TRUE.equals(
                    column.getPrimaryKey())) {

                sb.append(" [PRIMARY KEY]");
            }

            /*
             * Foreign key
             */
            if (Boolean.TRUE.equals(
                    column.getForeignKey())) {

                sb.append(" [FOREIGN KEY");

                if (column.getReferencedTable() != null) {

                    sb.append(" -> ")
                            .append(column.getReferencedTable());
                }

                if (column.getReferencedColumn() != null) {

                    sb.append(".")
                            .append(column.getReferencedColumn());
                }

                sb.append("]");
            }

            /*
             * Description của column
             */
            if (column.getDescription() != null
                    && !column.getDescription().isBlank()) {

                sb.append(": ")
                        .append(column.getDescription());
            }

            sb.append(", ");
        }

        return sb.toString();
    }

    /**
     * SHA-256 dùng để phát hiện nội dung schema có thay đổi hay không.
     */
    private String sha256(String input) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            input.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return java.util.HexFormat
                    .of()
                    .formatHex(hash);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Không thể tạo SHA-256 hash",
                    e
            );
        }
    }

    /**
     * Chuyển float[] thành JSON.
     *
     * Ví dụ:
     *
     * [0.12, -0.31, 0.55, ...]
     */
    private String toJson(float[] vector) {

        try {

            return objectMapper.writeValueAsString(
                    vector
            );

        } catch (Exception e) {

            throw new RuntimeException(
                    "Không thể serialize embedding vector",
                    e
            );
        }
    }
}