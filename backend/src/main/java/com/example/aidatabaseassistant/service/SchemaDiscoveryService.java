package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SchemaDiscoveryService {

    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final EncryptionUtil encryptionUtil;

    public DatabaseSchema discoverSchema(Long connectionId) {
        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        String url = "jdbc:mysql://" + connection.getHost() + ":" + connection.getPort()
                + "/" + connection.getDatabaseName();
        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElse(DatabaseSchema.builder()
                        .connection(connection)
                        .databaseName(connection.getDatabaseName())
                        .dbType(connection.getDbType())
                        .build());

        List<TableMetadata> tables = schema.getTables();
        tables.clear();

        try (Connection conn = DriverManager.getConnection(url, connection.getUsername(), rawPassword)) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet tableRs = metaData.getTables(connection.getDatabaseName(), null, "%", new String[]{"TABLE"})) {
                while (tableRs.next()) {
                    String tableName = tableRs.getString("TABLE_NAME");

                    TableMetadata table = TableMetadata.builder()
                            .schema(schema)
                            .name(tableName)
                            .description(tableRs.getString("REMARKS"))
                            .build();

                    table.setColumns(discoverColumns(metaData, connection.getDatabaseName(), tableName, table));
                    tables.add(table);
                }
            }

            schema.setLastSyncedAt(LocalDateTime.now());

            return schemaRepository.save(schema);

        } catch (SQLException e) {
            throw new RuntimeException("Không thể đọc schema: " + e.getMessage(), e);
        }
    }

    private List<ColumnMetadata> discoverColumns(DatabaseMetaData metaData, String dbName,
                                                 String tableName, TableMetadata table) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();

        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet pkRs = metaData.getPrimaryKeys(dbName, null, tableName)) {
            while (pkRs.next()) {
                primaryKeys.add(pkRs.getString("COLUMN_NAME"));
            }
        }

        Map<String, String[]> foreignKeys = new HashMap<>();
        try (ResultSet fkRs = metaData.getImportedKeys(dbName, null, tableName)) {
            while (fkRs.next()) {
                String fkColumn = fkRs.getString("FKCOLUMN_NAME");
                String refTable = fkRs.getString("PKTABLE_NAME");
                String refColumn = fkRs.getString("PKCOLUMN_NAME");
                foreignKeys.put(fkColumn, new String[]{refTable, refColumn});
            }
        }

        try (ResultSet colRs = metaData.getColumns(dbName, null, tableName, "%")) {
            while (colRs.next()) {
                String columnName = colRs.getString("COLUMN_NAME");
                String[] fkTarget = foreignKeys.get(columnName);

                ColumnMetadata column = ColumnMetadata.builder()
                        .table(table)
                        .name(columnName)
                        .dataType(colRs.getString("TYPE_NAME"))
                        .nullable(colRs.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
                        .primaryKey(primaryKeys.contains(columnName))
                        .foreignKey(fkTarget != null)
                        .referencedTable(fkTarget != null ? fkTarget[0] : null)
                        .referencedColumn(fkTarget != null ? fkTarget[1] : null)
                        .description(colRs.getString("REMARKS"))
                        .build();

                columns.add(column);
            }
        }

        return columns;
    }
}