package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.CacheConfig;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.TableMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SchemaLoaderService {

    private final DatabaseSchemaRepository schemaRepository;
    private final TableMetadataRepository tableMetadataRepository;

    /*
     * CACHE: method nay chay o MOI request preview/execute -> cache theo
     * connectionId. Evict o SchemaDiscoveryService.discoverSchema() va
     * SchemaMetadataService.update*Description(). LUON dung Caffeine
     * (localCacheManager), KHONG Redis - xem ly do trong CacheConfig.java.
     */
    @Cacheable(
            cacheNames = CacheConfig.FULL_SCHEMA_CACHE,
            cacheManager = "localCacheManager",
            key = "#connectionId"
    )
    @Transactional(readOnly = true)
    public DatabaseSchema loadCompleteSchema(Long connectionId) {

        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Chưa discover schema cho connection này"));

        /*
         * Query riêng để fetch columns.
         * Không fetch tables + columns trong cùng một JPQL query
         * vì cả hai đều là List/Bag.
         */
        tableMetadataRepository.findBySchemaIdWithColumns(schema.getId());

        return schema;
    }
}