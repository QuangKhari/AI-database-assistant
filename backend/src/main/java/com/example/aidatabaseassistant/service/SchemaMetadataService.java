package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.ColumnMetadataRepository;
import com.example.aidatabaseassistant.repository.TableMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SchemaMetadataService {

    private final TableMetadataRepository tableMetadataRepository;
    private final ColumnMetadataRepository columnMetadataRepository;

    public void updateTableDescription(String username, Long tableId, String description) {
        TableMetadata table = tableMetadataRepository
                .findByIdAndSchemaConnectionUserUsernameIgnoreCase(tableId, username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy table metadata"));
        table.setDescription(description);
        tableMetadataRepository.save(table);
    }

    public void updateColumnDescription(String username, Long columnId, String description) {
        ColumnMetadata column = columnMetadataRepository
                .findByIdAndTableSchemaConnectionUserUsernameIgnoreCase(columnId, username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy column metadata"));
        column.setDescription(description);
        columnMetadataRepository.save(column);
    }
}
