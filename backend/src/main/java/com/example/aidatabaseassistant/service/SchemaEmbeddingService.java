package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.config.CacheConfig;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableEmbedding;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.TableEmbeddingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
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

    private final CacheManager sharedCacheManager;

    @Value("${gemini.embedding.model:gemini-embedding-001}")
    private String embeddingModel;

    /**
     * Đảm bảo tất cả table hiện tại có embedding hợp lệ.
     *
     * QUAN TRỌNG:
     * Không dùng @Transactional ở đây.
     *
     * Lý do:
     * method này có thể gọi Gemini HTTP API nhiều lần.
     * Nếu giữ transaction trong toàn bộ method thì connection
     * HikariCP có thể bị giữ trong lúc chờ Gemini/retry 429.
     */
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
         * Nếu trong quá trình generate embedding xảy ra exception
         * sau khi một hoặc nhiều embedding đã được save,
         * finally vẫn có thể invalidate cache.
         */
        boolean changed = false;

        try {

            /*
             * =====================================================
             * 1. LOAD EMBEDDING HIỆN TẠI
             * =====================================================
             *
             * Chỉ query DB một lần.
             */
            List<TableEmbedding> existingEmbeddings = tableEmbeddingRepository.findBySchemaId(schema.getId());

            /*
             * Map theo tên bảng để lookup O(1).
             */
            Map<String, TableEmbedding> existingByName = existingEmbeddings.stream()
                            .collect(Collectors.toMap(
                                    e -> normalizeName(e.getTableName()),
                                    e -> e,
                                    (a, b) -> a
                            ));

            /*
             * Tên các bảng hiện tại trong schema.
             *
             * Dùng để phát hiện embedding stale.
             */
            Set<String> currentTableNames = schema.getTables().stream()
                            .map(TableMetadata::getName)
                            .filter(name -> name != null && !name.isBlank())
                            .map(this::normalizeName)
                            .collect(Collectors.toSet());

            /*
             * =====================================================
             * 2. XỬ LÝ EMBEDDING CHO TỪNG TABLE
             * =====================================================
             */
            for (TableMetadata table : schema.getTables()) {

                if (table == null
                        || table.getName() == null
                        || table.getName().isBlank()) {

                    continue;
                }

                /*
                 * Tạo semantic text từ:
                 *
                 * - table name
                 * - description
                 * - columns
                 * - datatype
                 * - PK
                 * - FK
                 * - referenced table/column
                 */
                String content = buildEmbeddingText(table);

                /*
                 * Hash nội dung schema.
                 */
                String hash = sha256(content);

                /*
                 * Tìm embedding cũ.
                 */
                TableEmbedding existing = existingByName.get(normalizeName(table.getName()));

                /*
                 * =================================================
                 * 3. KIỂM TRA EMBEDDING ĐÃ CÓ VÀ CÒN HỢP LỆ
                 * =================================================
                 *
                 * Nếu:
                 *
                 * hash giống
                 * +
                 * model giống
                 *
                 * => không gọi Gemini.
                 */
                if (existing != null
                        && hash.equals(
                        existing.getContentHash()
                )
                        && embeddingModel.equals(
                        existing.getModelName()
                )) {

                    continue;
                }

                /*
                 * =================================================
                 * 4. GỌI GEMINI
                 * =================================================
                 *
                 * Không có transaction bao quanh method.
                 *
                 * Vì vậy connection DB không bị giữ trong lúc
                 * chờ HTTP/retry.
                 */
                float[] vector = llmClient.generateEmbedding(content);

                /*
                 * =================================================
                 * 5. UPDATE / CREATE EMBEDDING
                 * =================================================
                 */
                TableEmbedding embedding = existing != null
                                ? existing
                                : TableEmbedding.builder()
                                .schema(schema)
                                .tableName(table.getName())
                                .build();

                embedding.setVectorJson(toJson(vector));

                embedding.setContentHash(hash);

                embedding.setModelName(embeddingModel);

                embedding.setUpdatedAt(LocalDateTime.now());

                /*
                 * save() của Spring Data tự quản lý transaction
                 * cho thao tác persistence này nếu không có outer
                 * transaction.
                 */
                tableEmbeddingRepository.save(embedding);

                changed = true;
            }

            /*
             * =====================================================
             * 6. XÓA EMBEDDING STALE
             * =====================================================
             */
            List<TableEmbedding> stale = existingEmbeddings.stream()
                            .filter(e ->
                                    e.getTableName() != null
                                            && !currentTableNames.contains(
                                            normalizeName(e.getTableName())
                                    )
                            )
                            .toList();

            if (!stale.isEmpty()) {

                tableEmbeddingRepository.deleteAll(stale);

                changed = true;
            }

        } finally {

            /*
             * =====================================================
             * 7. INVALIDATE CACHE
             * =====================================================
             *
             * Đây là điểm quan trọng của lần sửa này.
             *
             * Nếu:
             *
             * table 1 -> save OK
             * table 2 -> save OK
             * table 3 -> Gemini exception
             *
             * thì finally vẫn chạy.
             *
             * Cache cũ sẽ bị xóa nếu DB embedding đã thay đổi.
             *
             * Exception KHÔNG bị nuốt.
             * Nó vẫn được throw lên SchemaRetrievalService.
             */
            if (changed) {
                evictTableEmbeddingsCache(schema.getId());
            }
        }
    }

    /**
     * Lấy embedding của toàn bộ table trong schema.
     *
     * Kết quả:
     *
     * Map<tableName, vector>
     *
     * Method này được cache bằng Caffeine/Redis tùy cấu hình.
     */
    @Cacheable(
            cacheNames = CacheConfig.TABLE_EMBEDDINGS_CACHE,
            cacheManager = "sharedCacheManager",
            key = "#schemaId"
    )
    public Map<String, float[]> getEmbeddingsByTableName(
            Long schemaId) {

        Map<String, float[]> result = new HashMap<>();

        for (TableEmbedding embedding : tableEmbeddingRepository.findBySchemaId(schemaId)) {

            if (embedding == null) {
                continue;
            }

            String tableName = embedding.getTableName();

            if (tableName == null || tableName.isBlank()) {
                continue;
            }

            String normalizedName = normalizeName(tableName);

            float[] vector = fromJson(embedding.getVectorJson());

            /*
             * vector có thể null nếu JSON lỗi.
             *
             * Retrieval sẽ tự bỏ qua vector invalid.
             */
            result.put(normalizedName, vector
            );
        }

        return result;
    }

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
            return null;
        }
    }

    private void evictTableEmbeddingsCache(Long schemaId) {

        Cache cache = sharedCacheManager.getCache(CacheConfig.TABLE_EMBEDDINGS_CACHE);

        if (cache != null) {
            cache.evict(schemaId);
        }
    }

    /**
     * Tạo semantic representation cho table.
     */
    private String buildEmbeddingText(TableMetadata table) {

        StringBuilder sb = new StringBuilder();

        sb.append("Bảng: ").append(table.getName());

        if (table.getDescription() != null
                && !table.getDescription().isBlank()) {

            sb.append(" - ").append(table.getDescription());
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
            if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                sb.append(" [PRIMARY KEY]");
            }

            /*
             * Foreign key
             */
            if (Boolean.TRUE.equals(column.getForeignKey())) {
                sb.append(" [FOREIGN KEY");

                if (column.getReferencedTable() != null) {

                    sb.append(" -> ").append(column.getReferencedTable());
                }

                if (column.getReferencedColumn() != null) {

                    sb.append(".").append(column.getReferencedColumn());
                }

                sb.append("]");
            }

            /*
             * Column description
             */
            if (column.getDescription() != null
                    && !column.getDescription().isBlank()) {

                sb.append(": ").append(column.getDescription());
            }

            sb.append(", ");
        }

        return sb.toString();
    }

    private String sha256(String input) {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            return java.util.HexFormat
                    .of()
                    .formatHex(hash);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Không thể tạo SHA-256 hash", e
            );
        }
    }

    private String toJson(float[] vector) {

        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {

            throw new RuntimeException(
                    "Không thể serialize embedding vector", e
            );
        }
    }

    private String normalizeName(String name) {

        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }
}