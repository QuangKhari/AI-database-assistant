package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.CacheConfig;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.repository.ColumnMetadataRepository;
import com.example.aidatabaseassistant.repository.TableMetadataRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SchemaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);

    private final TableMetadataRepository tableMetadataRepository;
    private final ColumnMetadataRepository columnMetadataRepository;
    private final UserRepository userRepository;
    private final SchemaLoaderService schemaLoaderService;
    private final CacheManager localCacheManager;
    private final com.example.aidatabaseassistant.security.ConnectionAccessGuard connectionAccessGuard;

    @Transactional
    public void updateTableDescription(String username, Long tableId, String description) {
        User user = connectionAccessGuard.requireUser(username);

        TableMetadata table = tableMetadataRepository.findById(tableId)
                .orElseThrow(() -> new com.example.aidatabaseassistant.exception.ResourceNotFoundException("Không tìm thấy table metadata"));

        DatabaseConnection connection = table.getSchema().getConnection();
        checkOwnership(user, connection);

        table.setDescription(description);
        tableMetadataRepository.save(table);

        evictFullSchemaCache(connection.getId());
    }

    @Transactional
    public void updateColumnDescription(String username, Long columnId, String description) {
        User user = connectionAccessGuard.requireUser(username);

        ColumnMetadata column = columnMetadataRepository.findById(columnId)
                .orElseThrow(() -> new com.example.aidatabaseassistant.exception.ResourceNotFoundException("Không tìm thấy column metadata"));

        DatabaseConnection connection = column.getTable().getSchema().getConnection();
        checkOwnership(user, connection);

        column.setDescription(description);
        columnMetadataRepository.save(column);

        evictFullSchemaCache(connection.getId());
    }

    /**
     * Dung cho GET /api/schema/connections/{connectionId} (FE hien thi
     * danh sach bang/cot). Goi qua SchemaLoaderService de dung chung cache
     * "fullSchema" voi luong hoi-dap - xem ghi chu merge o field
     * schemaLoaderService phia tren.
     */
    @Transactional(readOnly = true)
    public DatabaseSchema getSchema(String username, Long connectionId) {

        User user = connectionAccessGuard.requireUser(username);

        DatabaseSchema schema = schemaLoaderService.loadCompleteSchema(connectionId);

        checkOwnership(user, schema.getConnection());

        return schema;
    }

    /**
     * Xoa cache "fullSchema" cua 1 connection cu the.
     *
     * Neu khong goi ham nay: QueryService (va bay gio ca endpoint FE xem
     * schema qua getSchema() o tren) se tiep tuc dua mo ta CU vao cache
     * cho toi khi cache het han (TTL mac dinh 60 phut).
     */
    private void evictFullSchemaCache(Long connectionId) {
        Cache cache = localCacheManager.getCache(CacheConfig.FULL_SCHEMA_CACHE);
        if (cache != null) {
            cache.evict(connectionId);
            log.debug("Đã xóa cache fullSchema cho connectionId={}", connectionId);
        }
    }

    private void checkOwnership(User user, DatabaseConnection connection) {
        connectionAccessGuard.requireOwnership(user, connection);
    }
}