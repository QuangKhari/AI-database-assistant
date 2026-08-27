package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.TableMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SchemaLoaderService {

    private final DatabaseSchemaRepository schemaRepository;
    private final TableMetadataRepository tableMetadataRepository;

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