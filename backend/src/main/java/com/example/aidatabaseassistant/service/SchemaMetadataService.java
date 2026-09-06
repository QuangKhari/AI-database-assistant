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

    /*
     * GHI CHU MERGE (tich hop FE):
     *
     * Ban truoc cua file nay co field "DatabaseSchemaRepository
     * databaseSchemaRepository" duoc them de phuc vu getSchema() - method
     * moi cho endpoint GET /api/schema/connections/{connectionId} (FE goi
     * moi lan mo trang xem schema cua 1 connection).
     *
     * Field do da bi XOA o day vi getSchema() ben duoi gio goi thang
     * SchemaLoaderService.loadCompleteSchema(connectionId) thay vi tu
     * query DatabaseSchemaRepository rieng - vua tranh trung lap logic
     * (2 cach load full schema cung ton tai trong code), vua giup
     * getSchema() duoc HUONG CACHE MIEN PHI (cung 1 cache "fullSchema"
     * voi luong hoi-dap, cung key connectionId, cung co che evict).
     *
     * Anh huong duy nhat can luu y: neu connectionId chua tung
     * discoverSchema(), thong bao loi tra ve FE gio la "Chưa discover
     * schema cho connection này" (tu SchemaLoaderService) thay vi "Không
     * tìm thấy schema" nhu truoc - chi khac text hien thi, van la
     * IllegalArgumentException -> HTTP 400 nhu cu.
     */
    private final SchemaLoaderService schemaLoaderService;

    /*
     * Khong dung @CacheEvict annotation o day vi connectionId KHONG PHAI
     * la tham so cua 2 method ben duoi (chi co tableId/columnId) - phai
     * load entity ra roi moi biet connectionId thuoc ve connection nao,
     * nen evict bang code (CacheManager.getCache(...).evict(...)) sau
     * khi da xac dinh duoc connectionId, thay vi dung SpEL key phuc tap.
     *
     * Ten bean "localCacheManager" phai trung voi ten bean khai bao trong
     * CacheConfig - Spring se tu chon dung bean nay vi ten tham so
     * constructor (do Lombok sinh ra) trung voi ten bean, khong can
     * @Qualifier.
     */
    private final CacheManager localCacheManager;

    @Transactional
    public void updateTableDescription(String username, Long tableId, String description) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        TableMetadata table = tableMetadataRepository.findById(tableId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy table metadata"));

        DatabaseConnection connection = table.getSchema().getConnection();
        checkOwnership(user, connection);

        table.setDescription(description);
        tableMetadataRepository.save(table);

        evictFullSchemaCache(connection.getId());
    }

    @Transactional
    public void updateColumnDescription(String username, Long columnId, String description) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        ColumnMetadata column = columnMetadataRepository.findById(columnId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy column metadata"));

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

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

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
        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập resource này");
        }
    }
}