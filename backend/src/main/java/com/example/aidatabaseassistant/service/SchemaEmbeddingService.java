package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.TableEmbeddingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SchemaEmbeddingService {

    private static final String MODEL_NAME = "text-embedding-004";

    private final LLMClient llmClient;
    private final TableEmbeddingRepository tableEmbeddingRepository;
    private final ObjectMapper objectMapper;

    /**
     * Đảm bảo mỗi bảng trong schema có embedding mới nhất.
     *
     * Nếu nội dung bảng không thay đổi -> không gọi lại Embedding API.
     *
     * Embedding được định danh bởi:
     *      (schema_id, table_name)
     *
     * thay vì table_id vì TableMetadata có thể bị xoá/tạo lại
     * trong quá trình discoverSchema().
     */
    @Transactional
    public void ensureEmbeddings(DatabaseSchema schema) {

        for (TableMetadata table : schema.getTables()) {

            String content = buildEmbeddingText(table);
            String hash = sha256(content);

            /*
             * Tìm embedding dựa trên schema + tên bảng.
             *
             * KHÔNG dùng:
             * findByTableId(...)
             *
             * vì TableMetadata.id có thể thay đổi sau mỗi lần sync schema.
             */
            TableEmbedding existing = tableEmbeddingRepository
                    .findBySchemaIdAndTableNameIgnoreCase(
                            schema.getId(),
                            table.getName()
                    )
                    .orElse(null);

            /*
             * Nội dung bảng không thay đổi.
             *
             * Không cần gọi Gemini Embedding API lại.
             */
            if (existing != null
                    && existing.getContentHash().equals(hash)) {

                continue;
            }

            /*
             * Nội dung thay đổi hoặc chưa có embedding.
             */
            float[] vector = llmClient.generateEmbedding(content);

            TableEmbedding embedding;

            if (existing != null) {

                /*
                 * Đã có embedding -> cập nhật.
                 */
                embedding = existing;

            } else {

                /*
                 * Chưa có -> tạo mới.
                 */
                embedding = TableEmbedding.builder()
                        .schema(schema)
                        .tableName(table.getName())
                        .build();
            }

            embedding.setVectorJson(toJson(vector));
            embedding.setContentHash(hash);
            embedding.setModelName(MODEL_NAME);
            embedding.setUpdatedAt(LocalDateTime.now());

            tableEmbeddingRepository.save(embedding);
        }

        /*
         * Dọn các embedding của những bảng đã bị xoá
         * khỏi database thật.
         *
         * Ví dụ:
         *
         * Lần trước:
         * customers
         * orders
         * products
         *
         * User DROP TABLE products
         *
         * discoverSchema() lần sau chỉ còn:
         * customers
         * orders
         *
         * -> xoá embedding của products.
         */
        Set<String> currentNames = schema.getTables()
                .stream()
                .map(t -> t.getName().toLowerCase())
                .collect(Collectors.toSet());

        List<TableEmbedding> stale =
                tableEmbeddingRepository
                        .findBySchemaId(schema.getId())
                        .stream()
                        .filter(e ->
                                !currentNames.contains(
                                        e.getTableName().toLowerCase()
                                )
                        )
                        .toList();

        if (!stale.isEmpty()) {
            tableEmbeddingRepository.deleteAll(stale);
        }
    }

    /**
     * Tạo nội dung dùng để embedding cho một bảng.
     *
     * Bao gồm:
     * - tên bảng
     * - mô tả bảng
     * - tên cột
     * - kiểu dữ liệu
     * - mô tả cột
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

            if (column.getDescription() != null
                    && !column.getDescription().isBlank()) {

                sb.append(":")
                        .append(column.getDescription());
            }

            /*
             * Thêm thông tin PK/FK vào embedding.
             *
             * Điều này giúp retrieval hiểu quan hệ giữa
             * các bảng tốt hơn.
             */
            if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                sb.append(" [PK]");
            }

            if (Boolean.TRUE.equals(column.getForeignKey())
                    && column.getReferencedTable() != null) {

                sb.append(" [FK -> ")
                        .append(column.getReferencedTable());

                if (column.getReferencedColumn() != null) {
                    sb.append(".")
                            .append(column.getReferencedColumn());
                }

                sb.append("]");
            }

            sb.append(", ");
        }

        return sb.toString();
    }

    /**
     * Tạo SHA-256 hash cho nội dung embedding.
     *
     * Nếu content giống nhau -> hash giống nhau
     * -> không cần gọi Embedding API.
     */
    private String sha256(String input) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            input.getBytes(StandardCharsets.UTF_8)
                    );

            return HexFormat.of().formatHex(hash);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Không thể tạo SHA-256 hash",
                    e
            );
        }
    }

    /**
     * Chuyển float[] thành JSON để lưu vào TEXT.
     *
     * Ví dụ:
     *
     * [0.123, -0.456, 0.789, ...]
     */
    private String toJson(float[] vector) {

        try {

            return objectMapper.writeValueAsString(vector);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Không thể chuyển embedding vector thành JSON",
                    e
            );
        }
    }
}