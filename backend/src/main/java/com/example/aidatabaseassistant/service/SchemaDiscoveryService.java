package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.SchemaResponse;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SchemaDiscoveryService {

    private final DatabaseSchemaRepository schemaRepository;
    private final EncryptionUtil encryptionUtil;
    private final ConnectionService connectionService;
    private final TargetDatabaseClient targetDatabaseClient;

    @Transactional(readOnly = true)
    public SchemaResponse getSchema(String username, Long connectionId) {
        connectionService.getOwnedActiveConnection(username, connectionId);
        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Connection này chưa được đồng bộ schema"));
        return toResponse(schema);
    }

    @Transactional
    public SchemaResponse discoverSchema(String username, Long connectionId) {
        DatabaseConnection databaseConnection = connectionService
                .getOwnedActiveConnection(username, connectionId);
        String rawPassword = encryptionUtil.decrypt(databaseConnection.getEncryptedPassword());

        List<TableMetadata> discoveredTables;
        try (Connection connection = targetDatabaseClient.openReadOnlyConnection(
                new TargetDatabaseCredentials(
                        databaseConnection.getHost(), databaseConnection.getPort(),
                        databaseConnection.getDatabaseName(), databaseConnection.getUsername(), rawPassword))) {
            discoveredTables = discoverTables(connection.getMetaData(), databaseConnection.getDatabaseName());
        } catch (SQLException e) {
            throw new TargetDatabaseConnectionException(
                    "SCHEMA_SYNC_FAILED", "Không thể đọc metadata từ database đã chọn.");
        }

        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElseGet(() -> DatabaseSchema.builder()
                        .connection(databaseConnection)
                        .databaseName(databaseConnection.getDatabaseName())
                        .dbType(databaseConnection.getDbType())
                        .build());

        Map<String, String> tableDescriptions = new HashMap<>();
        Map<String, String> columnDescriptions = new HashMap<>();
        for (TableMetadata oldTable : schema.getTables()) {
            tableDescriptions.put(normalize(oldTable.getName()), oldTable.getDescription());
            for (ColumnMetadata oldColumn : oldTable.getColumns()) {
                columnDescriptions.put(columnKey(oldTable.getName(), oldColumn.getName()),
                        oldColumn.getDescription());
            }
        }

        schema.getTables().clear();
        for (TableMetadata table : discoveredTables) {
            table.setSchema(schema);
            String savedTableDescription = tableDescriptions.get(normalize(table.getName()));
            if (savedTableDescription != null && !savedTableDescription.isBlank()) {
                table.setDescription(savedTableDescription);
            }
            for (ColumnMetadata column : table.getColumns()) {
                column.setTable(table);
                String savedColumnDescription = columnDescriptions.get(
                        columnKey(table.getName(), column.getName()));
                if (savedColumnDescription != null && !savedColumnDescription.isBlank()) {
                    column.setDescription(savedColumnDescription);
                }
            }
            schema.getTables().add(table);
        }

        schema.setDatabaseName(databaseConnection.getDatabaseName());
        schema.setDbType(databaseConnection.getDbType());
        schema.setLastSyncedAt(LocalDateTime.now());
        return toResponse(schemaRepository.save(schema));
    }

    private List<TableMetadata> discoverTables(DatabaseMetaData metadata, String databaseName)
            throws SQLException {
        List<TableMetadata> tables = new ArrayList<>();
        try (ResultSet tableRows = metadata.getTables(databaseName, null, "%", new String[]{"TABLE"})) {
            while (tableRows.next()) {
                String tableName = tableRows.getString("TABLE_NAME");
                TableMetadata table = TableMetadata.builder()
                        .name(tableName)
                        .description(tableRows.getString("REMARKS"))
                        .build();
                table.setColumns(discoverColumns(metadata, databaseName, tableName, table));
                tables.add(table);
            }
        }
        tables.sort(Comparator.comparing(TableMetadata::getName, String.CASE_INSENSITIVE_ORDER));
        return tables;
    }

    private List<ColumnMetadata> discoverColumns(DatabaseMetaData metadata, String databaseName,
                                                  String tableName, TableMetadata table)
            throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet primaryKeyRows = metadata.getPrimaryKeys(databaseName, null, tableName)) {
            while (primaryKeyRows.next()) {
                primaryKeys.add(normalize(primaryKeyRows.getString("COLUMN_NAME")));
            }
        }

        Map<String, String[]> foreignKeys = new HashMap<>();
        try (ResultSet foreignKeyRows = metadata.getImportedKeys(databaseName, null, tableName)) {
            while (foreignKeyRows.next()) {
                foreignKeys.put(normalize(foreignKeyRows.getString("FKCOLUMN_NAME")), new String[]{
                        foreignKeyRows.getString("PKTABLE_NAME"),
                        foreignKeyRows.getString("PKCOLUMN_NAME")
                });
            }
        }

        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet columnRows = metadata.getColumns(databaseName, null, tableName, "%")) {
            while (columnRows.next()) {
                String columnName = columnRows.getString("COLUMN_NAME");
                String[] foreignKeyTarget = foreignKeys.get(normalize(columnName));
                columns.add(ColumnMetadata.builder()
                        .table(table)
                        .name(columnName)
                        .dataType(formatDataType(columnRows))
                        .nullable(columnRows.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
                        .primaryKey(primaryKeys.contains(normalize(columnName)))
                        .foreignKey(foreignKeyTarget != null)
                        .referencedTable(foreignKeyTarget == null ? null : foreignKeyTarget[0])
                        .referencedColumn(foreignKeyTarget == null ? null : foreignKeyTarget[1])
                        .description(columnRows.getString("REMARKS"))
                        .build());
            }
        }
        return columns;
    }

    private String formatDataType(ResultSet columnRows) throws SQLException {
        String type = columnRows.getString("TYPE_NAME");
        int size = columnRows.getInt("COLUMN_SIZE");
        int scale = columnRows.getInt("DECIMAL_DIGITS");
        String normalizedType = type.toUpperCase(Locale.ROOT);
        if ((normalizedType.contains("CHAR") || normalizedType.contains("BINARY")) && size > 0) {
            return type + "(" + size + ")";
        }
        if ((normalizedType.equals("DECIMAL") || normalizedType.equals("NUMERIC")) && size > 0) {
            return type + "(" + size + "," + Math.max(0, scale) + ")";
        }
        return type;
    }

    private SchemaResponse toResponse(DatabaseSchema schema) {
        List<SchemaResponse.TableInfo> tables = schema.getTables().stream()
                .sorted(Comparator.comparing(TableMetadata::getName, String.CASE_INSENSITIVE_ORDER))
                .map(table -> new SchemaResponse.TableInfo(
                        table.getId(), table.getName(), table.getDescription(),
                        table.getColumns().stream()
                                .map(column -> new SchemaResponse.ColumnInfo(
                                        column.getId(), column.getName(), column.getDataType(),
                                        Boolean.TRUE.equals(column.getNullable()),
                                        Boolean.TRUE.equals(column.getPrimaryKey()),
                                        Boolean.TRUE.equals(column.getForeignKey()),
                                        column.getReferencedTable(), column.getReferencedColumn(),
                                        column.getDescription()))
                                .toList()))
                .toList();
        return new SchemaResponse(schema.getId(), schema.getConnection().getId(),
                schema.getDatabaseName(), schema.getLastSyncedAt(), tables);
    }

    private String columnKey(String tableName, String columnName) {
        return normalize(tableName) + ":" + normalize(columnName);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
