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

        Set<String> foreignKeys = new HashSet<>();
        try (ResultSet fkRs = metaData.getImportedKeys(dbName, null, tableName)) {
            while (fkRs.next()) {
                foreignKeys.add(fkRs.getString("FKCOLUMN_NAME"));
            }
        }

        try (ResultSet colRs = metaData.getColumns(dbName, null, tableName, "%")) {
            while (colRs.next()) {
                String columnName = colRs.getString("COLUMN_NAME");

                ColumnMetadata column = ColumnMetadata.builder()
                        .table(table)
                        .name(columnName)
                        .dataType(colRs.getString("TYPE_NAME"))
                        .nullable(colRs.getInt("NULLABLE") == DatabaseMetaData.columnNullable)
                        .primaryKey(primaryKeys.contains(columnName))
                        .foreignKey(foreignKeys.contains(columnName))
                        .build();

                columns.add(column);
            }
        }

        return columns;
    }
}