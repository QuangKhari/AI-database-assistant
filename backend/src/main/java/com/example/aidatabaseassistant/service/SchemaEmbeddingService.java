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

    /*
     * FIX (audit Redis caching): dung de xoa CHU DONG cache
     * "tableEmbeddings" (Caffeine hoac Redis tuy app.cache.provider) khi
     * embedding THAT SU thay doi trong ensureEmbeddings() - xem
     * evictTableEmbeddingsCacheIfChanged().
     *
     * Ten field PHAI trung voi ten bean "sharedCacheManager" khai bao
     * trong CacheConfig - Spring resolve theo TEN bean khi co nhieu hon
     * 1 bean cung kieu CacheManager (localCacheManager/sharedCacheManager),
     * giong het cach SchemaMetadataService.localCacheManager dang lam.
     */
    private final CacheManager sharedCacheManager;

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
        // FIX (audit Redis caching): chỉ xóa cache "tableEmbeddings" khi
        // THẬT SỰ có ít nhất 1 embedding được tạo/cập nhật/xóa - KHÔNG
        // được evict vô điều kiện, vì ensureEmbeddings() được gọi trên
        // MỌI câu hỏi (xem SchemaRetrievalService.retrieveRelevantSchema)
        // và phần lớn các lần gọi đó không có gì thay đổi (hash + model
        // trùng -> continue). Evict vô điều kiện ở đây sẽ xóa sạch cache
        // ngay trước khi đọc lại nó, vô hiệu hóa toàn bộ lợi ích cache.
        boolean changed = false;

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
            changed = true;
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
            changed = true;
        }

        if (changed) {
            evictTableEmbeddingsCache(schema.getId());
        }
    }

    /**
     * Đọc toàn bộ embedding của 1 schema, dạng
     * Map&lt;tenBangChuanHoa, vector&gt; - sẵn sàng để
     * SchemaRetrievalService tính cosine similarity mà KHÔNG cần biết gì
     * về TableEmbedding entity/cách lưu JSON.
     *
     * FIX (audit Redis caching): method này TRƯỚC ĐÂY nằm thẳng trong
     * SchemaRetrievalService.retrieveRelevantSchema(), query DB
     * (tableEmbeddingRepository.findBySchemaId) MỖI LẦN người dùng hỏi 1
     * câu - kể cả khi embedding CHƯA HỀ thay đổi từ lần hỏi trước. Cache
     * "tableEmbeddings" đã được cấu hình sẵn trong CacheConfig (Caffeine
     * hoặc Redis tùy app.cache.provider) nhưng CHƯA TỪNG được áp dụng ở
     * đâu cả - đây là @Cacheable ĐẦU TIÊN thực sự dùng cache này.
     *
     * Đặt method ở đây (không phải trực tiếp trong SchemaRetrievalService)
     * để tránh self-invocation: Spring AOP proxy (@Cacheable) CHỈ hoạt
     * động khi được gọi TỪ BÊN NGOÀI class - gọi nội bộ qua "this." sẽ bỏ
     * qua proxy và cache sẽ không bao giờ được áp dụng.
     *
     * Giá trị trả về CHỈ là Map&lt;String, float[]&gt; (không phải
     * List&lt;TableEmbedding&gt;) vì TableEmbedding là JPA entity có quan
     * hệ @ManyToOne LAZY tới DatabaseSchema - serialize thẳng entity này
     * sang Redis sẽ gặp đúng vấn đề proxy Hibernate / vòng lặp vô hạn đã
     * nêu trong javadoc của CacheConfig (lý do "fullSchema" phải giữ
     * riêng ở Caffeine). Map thuần là kiểu DỮ LIỆU AN TOÀN, đã có sẵn
     * trong whitelist PolymorphicTypeValidator của CacheConfig.
     */
    @Cacheable(
            cacheNames = CacheConfig.TABLE_EMBEDDINGS_CACHE,
            cacheManager = "sharedCacheManager",
            key = "#schemaId"
    )
    public Map<String, float[]> getEmbeddingsByTableName(Long schemaId) {

        Map<String, float[]> result = new java.util.HashMap<>();

        for (TableEmbedding embedding :
                tableEmbeddingRepository.findBySchemaId(schemaId)) {

            if (embedding == null) {
                continue;
            }

            String tableName = embedding.getTableName();

            if (tableName == null || tableName.isBlank()) {
                continue;
            }

            String normalizedName =
                    tableName.trim()
                            .toLowerCase(java.util.Locale.ROOT);

            float[] vector =
                    fromJson(embedding.getVectorJson());

            /*
             * Dùng HashMap.put() thay vì Collectors.toMap()
             * vì vector có thể là null khi JSON bị lỗi.
             *
             * Ví dụ:
             * "{not-valid-json"
             *        ↓
             * fromJson()
             *        ↓
             * null
             *
             * Kết quả:
             * customers -> null
             *
             * Retrieval phía sau sẽ tự bỏ qua vector null.
             */
            result.put(normalizedName, vector);
        }

        return result;
    }

    /**
     * Chuyển JSON TEXT trong database thành float[].
     *
     * Nếu JSON lỗi: trả null để retrieval bỏ qua embedding lỗi thay vì
     * làm crash toàn bộ query (đối xứng với toJson() bên dưới, và cùng
     * hành vi "bỏ qua khi lỗi" mà SchemaRetrievalService.fromJson() vốn
     * làm trước khi logic này được chuyển về đây).
     */
    private float[] fromJson(String json) {

        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, float[].class);
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