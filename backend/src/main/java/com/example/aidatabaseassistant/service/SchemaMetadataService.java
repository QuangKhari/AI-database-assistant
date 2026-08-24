package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.ColumnMetadataRepository;
import com.example.aidatabaseassistant.repository.TableMetadataRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;

@Service
@RequiredArgsConstructor
public class SchemaMetadataService {

    private final TableMetadataRepository tableMetadataRepository;
    private final ColumnMetadataRepository columnMetadataRepository;
    private final UserRepository userRepository;

    public void updateTableDescription(String username, Long tableId, String description) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        TableMetadata table = tableMetadataRepository.findById(tableId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy table metadata"));

        checkOwnership(user, table.getSchema().getConnection());

        table.setDescription(description);
        tableMetadataRepository.save(table);
    }

    public void updateColumnDescription(String username, Long columnId, String description) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        ColumnMetadata column = columnMetadataRepository.findById(columnId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy column metadata"));

        checkOwnership(user, column.getTable().getSchema().getConnection());

        column.setDescription(description);
        columnMetadataRepository.save(column);
    }

    private void checkOwnership(User user, DatabaseConnection connection) {
        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập resource này");
        }
    }
}