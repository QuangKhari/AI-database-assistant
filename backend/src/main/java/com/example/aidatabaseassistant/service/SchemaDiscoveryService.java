package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.CacheConfig;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.db.TargetDatabaseClient;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SchemaDiscoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(SchemaDiscoveryService.class);

    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final EncryptionUtil encryptionUtil;
    private final UserRepository userRepository;

    /*
     * Điểm duy nhất mở JDBC connection tới database
     * của user.
     *
     * TargetDatabaseClient chịu trách nhiệm:
     *
     * - SSRF protection
     * - build JDBC URL
     * - DriverManager.getConnection()
     * - hỗ trợ MySQL / PostgreSQL / DuckDB
     */
    private final TargetDatabaseClient targetDatabaseClient;

    /*
     * Schema RAG.
     *
     * Embedding lỗi không được làm discovery schema thất bại.
     */
    private final SchemaEmbeddingService schemaEmbeddingService;


    // =========================================================
    // 1. RESOLVE KEY COLUMN MAP
    // =========================================================

    /**
     * Trả về map:
     *
     *     columnName -> có phải PK/FK hay không
     *
     * Dữ liệu lấy từ schema đã lưu trong application database.
     *
     * Không kết nối lại database đích.
     *
     * Dùng cho ChartTypeClassifier / DataInsightAnalyzer.
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> resolveKeyColumnMap(
            String username,
            Long connectionId
    ) {

        if (connectionId == null) {
            return Map.of();
        }

        User user =
                userRepository.findByUsername(username)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy user"
                                )
                        );

        DatabaseConnection connection =
                connectionRepository.findById(connectionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy connection"
                                )
                        );

        /*
         * Ownership check.
         *
         * Không được bỏ qua.
         *
         * Nếu bỏ qua sẽ tạo IDOR:
         *
         * user A có thể đọc schema metadata
         * của connection user B.
         */
        if (!connection.getUser().getId().equals(user.getId())) {

            throw new IllegalArgumentException(
                    "Bạn không có quyền truy cập connection này"
            );
        }

        return schemaRepository
                .findByConnectionId(connectionId)
                .map(SchemaDiscoveryService::buildKeyColumnMap)
                .orElse(Map.of());
    }


    // =========================================================
    // 2. BUILD KEY COLUMN MAP
    // =========================================================

    /**
     * Tách riêng thành static method để QueryService
     * có thể tái sử dụng trực tiếp với fullSchema
     * đã có sẵn trong bộ nhớ.
     *
     * Không query database.
     * Không kiểm tra ownership.
     */
    public static Map<String, Boolean> buildKeyColumnMap(
            DatabaseSchema schema
    ) {

        if (schema == null
                || schema.getTables() == null) {

            return Map.of();
        }

        Map<String, Boolean> keyColumns =
                new HashMap<>();

        for (TableMetadata table :
                schema.getTables()) {

            if (table == null
                    || table.getColumns() == null) {

                continue;
            }

            for (ColumnMetadata column :
                    table.getColumns()) {

                if (column == null
                        || column.getName() == null
                        || column.getName().isBlank()) {

                    continue;
                }

                boolean isKey =
                        Boolean.TRUE.equals(
                                column.getPrimaryKey()
                        )
                                ||
                                Boolean.TRUE.equals(
                                        column.getForeignKey()
                                );

                /*
                 * Nếu cùng một column name xuất hiện
                 * ở nhiều table:
                 *
                 *     users.id
                 *     orders.id
                 *
                 * và chỉ một cái là key,
                 * vẫn ưu tiên true.
                 *
                 * An toàn hơn cho chart classification.
                 */
                keyColumns.merge(
                        column.getName()
                                .toLowerCase(Locale.ROOT),
                        isKey,
                        (existing, incoming) ->
                                existing || incoming
                );
            }
        }

        return keyColumns;
    }


    // =========================================================
    // 3. DISCOVER SCHEMA
    // =========================================================

    /*
     * CACHE: schema vua dong bo lai (bang/cot/mo ta co the da doi) -> BAT
     * BUOC xoa cache "fullSchema" cua dung connectionId nay, neu khong
     * QueryService se tiep tuc dung schema CU cho toi khi TTL het han.
     */
    @CacheEvict(
            cacheNames = CacheConfig.FULL_SCHEMA_CACHE,
            cacheManager = "localCacheManager",
            key = "#connectionId"
    )
    @Transactional
    public DatabaseSchema discoverSchema(
            String username,
            Long connectionId
    ) {

        // -----------------------------------------------------
        // 3.1 Load user
        // -----------------------------------------------------

        User user =
                userRepository.findByUsername(username)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy user"
                                )
                        );


        // -----------------------------------------------------
        // 3.2 Load connection
        // -----------------------------------------------------

        DatabaseConnection connection =
                connectionRepository.findById(connectionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy connection"
                                )
                        );


        // -----------------------------------------------------
        // 3.3 Ownership check
        // -----------------------------------------------------

        if (!connection.getUser().getId().equals(user.getId())) {

            throw new IllegalArgumentException(
                    "Bạn không có quyền truy cập connection này"
            );
        }


        // -----------------------------------------------------
        // 3.4 Decrypt password
        // -----------------------------------------------------

        String rawPassword =
                encryptionUtil.decrypt(
                        connection.getEncryptedPassword()
                );


        // -----------------------------------------------------
        // 3.5 Load existing schema
        // -----------------------------------------------------

        DatabaseSchema schema =
                schemaRepository
                        .findByConnectionId(connectionId)
                        .orElse(
                                DatabaseSchema.builder()
                                        .connection(connection)
                                        .databaseName(
                                                connection.getDatabaseName()
                                        )
                                        .dbType(
                                                connection.getDbType()
                                        )
                                        .build()
                        );


        // =====================================================
        // 3.6 BACKUP APPLICATION METADATA
        // =====================================================
        //
        // Discovery sẽ tạo lại TableMetadata /
        // ColumnMetadata.
        //
        // Nếu clear() trước khi backup:
        //
        //     description của user sẽ bị mất.
        //
        // Vì vậy phải lưu description trước.
        //
        // Table:
        //
        //     tableName
        //
        // Column:
        //
        //     tableName.columnName
        // =====================================================

        Map<String, String> existingTableDescriptions =
                new HashMap<>();

        Map<String, String> existingColumnDescriptions =
                new HashMap<>();

        List<TableMetadata> existingTables =
                schema.getTables();

        if (existingTables != null) {

            for (TableMetadata existingTable :
                    existingTables) {

                if (existingTable == null
                        || existingTable.getName() == null
                        || existingTable.getName().isBlank()) {

                    continue;
                }


                // -------------------------------------------------
                // Backup table description
                // -------------------------------------------------

                if (existingTable.getDescription() != null
                        && !existingTable
                        .getDescription()
                        .isBlank()) {

                    existingTableDescriptions.put(
                            normalizeName(
                                    existingTable.getName()
                            ),
                            existingTable.getDescription()
                    );
                }


                // -------------------------------------------------
                // Backup column descriptions
                // -------------------------------------------------

                if (existingTable.getColumns() == null) {
                    continue;
                }

                for (ColumnMetadata existingColumn :
                        existingTable.getColumns()) {

                    if (existingColumn == null
                            || existingColumn.getName() == null
                            || existingColumn.getName().isBlank()) {

                        continue;
                    }

                    if (existingColumn.getDescription() != null
                            && !existingColumn
                            .getDescription()
                            .isBlank()) {

                        existingColumnDescriptions.put(
                                buildColumnKey(
                                        existingTable.getName(),
                                        existingColumn.getName()
                                ),
                                existingColumn.getDescription()
                        );
                    }
                }
            }
        }


        // =====================================================
        // 3.7 CLEAR OLD TABLE METADATA
        // =====================================================

        List<TableMetadata> tables =
                schema.getTables();

        if (tables == null) {

            tables = new ArrayList<>();

            schema.setTables(tables);
        }

        tables.clear();


        // =====================================================
        // 3.8 OPEN TARGET DATABASE CONNECTION
        // =====================================================

        try (Connection conn =
                     targetDatabaseClient.openConnection(
                             connection.getDbType(),
                             connection.getHost(),
                             connection.getPort(),
                             connection.getDatabaseName(),
                             connection.getUsername(),
                             rawPassword
                     )) {

            DatabaseMetaData metaData =
                    conn.getMetaData();


            // =================================================
            // 3.9 XÁC ĐỊNH CATALOG / SCHEMA
            // =================================================
            //
            // MySQL:
            //
            //     catalog = databaseName
            //     schema  = null
            //
            // PostgreSQL:
            //
            //     catalog = null
            //     schema  = public
            //
            // DuckDB/Excel:
            //
            //     catalog = tên catalog THẬT của connection hiện tại
            //     (xem getCatalog() - fix audit Excel/DuckDB, trước đây
            //     luôn là null khiến getTables/getColumns quét lẫn cả
            //     catalog nội bộ "system"/"temp" của DuckDB)
            //     schema  = null
            // =================================================

            String catalog =
                    getCatalog(connection, metaData);

            String schemaPattern =
                    getSchemaPattern(connection);


            // =================================================
            // 3.10 DISCOVER TABLES
            // =================================================

            try (ResultSet tableRs =
                         metaData.getTables(
                                 catalog,
                                 schemaPattern,
                                 "%",
                                 new String[]{"TABLE"}
                         )) {

                while (tableRs.next()) {

                    String tableName =
                            tableRs.getString(
                                    "TABLE_NAME"
                            );

                    if (tableName == null
                            || tableName.isBlank()) {

                        continue;
                    }


                    // -----------------------------------------
                    // Description từ database
                    // -----------------------------------------

                    String databaseDescription =
                            tableRs.getString(
                                    "REMARKS"
                            );


                    // -----------------------------------------
                    // Ưu tiên description application
                    // -----------------------------------------

                    String description =
                            getPreservedDescription(
                                    existingTableDescriptions,
                                    normalizeName(tableName),
                                    databaseDescription
                            );


                    // -----------------------------------------
                    // Tạo TableMetadata
                    // -----------------------------------------

                    TableMetadata table =
                            TableMetadata.builder()
                                    .schema(schema)
                                    .name(tableName)
                                    .description(description)
                                    .build();


                    // -----------------------------------------
                    // Discover columns + PK + FK
                    // -----------------------------------------

                    table.setColumns(
                            discoverColumns(
                                    metaData,
                                    connection,
                                    tableName,
                                    table,
                                    existingColumnDescriptions
                            )
                    );


                    tables.add(table);
                }
            }


            // =================================================
            // 3.11 UPDATE SYNC TIME
            // =================================================

            schema.setLastSyncedAt(
                    LocalDateTime.now()
            );


            // =================================================
            // 3.12 SAVE SCHEMA
            // =================================================
            //
            // Lưu trước để đảm bảo schema.id tồn tại.
            //
            // TableEmbedding dùng:
            //
            //     schema_id
            //     table_name
            //
            // thay vì table_id.
            // =================================================

            DatabaseSchema savedSchema =
                    schemaRepository.save(schema);


            // =================================================
            // 3.13 SCHEMA RAG EMBEDDING
            // =================================================

            try {

                schemaEmbeddingService.ensureEmbeddings(
                        savedSchema
                );

            } catch (Exception e) {

                /*
                 * Embedding là chức năng bổ sung.
                 *
                 * Gemini/API lỗi không được làm
                 * Schema Discovery thất bại.
                 */
                log.warn(
                        "Không thể tạo schema embeddings cho schema {}: {}",
                        savedSchema.getId(),
                        e.getMessage()
                );

                log.debug(
                        "Chi tiết lỗi khi tạo schema embeddings",
                        e
                );
            }


            return savedSchema;

        } catch (SQLException e) {

            throw new RuntimeException(
                    "Không thể đọc schema: "
                            + e.getMessage(),
                    e
            );
        }
    }


    // =========================================================
    // 4. DISCOVER COLUMNS
    // =========================================================

    /**
     * Discover:
     *
     * - columns
     * - data type
     * - nullable
     * - primary key
     * - foreign key
     * - referenced table
     * - referenced column
     * - description
     *
     * Quan trọng:
     *
     * Không còn sử dụng dbName.
     *
     * Catalog/schema được xác định từ DatabaseConnection
     * để hỗ trợ cả MySQL và PostgreSQL.
     */
    private List<ColumnMetadata> discoverColumns(
            DatabaseMetaData metaData,
            DatabaseConnection connection,
            String tableName,
            TableMetadata table,
            Map<String, String> existingColumnDescriptions
    ) throws SQLException {

        List<ColumnMetadata> columns =
                new ArrayList<>();


        // -----------------------------------------------------
        // Xác định catalog/schema
        // -----------------------------------------------------

        String catalog =
                getCatalog(connection, metaData);

        String schemaPattern =
                getSchemaPattern(connection);


        // =====================================================
        // 4.1 PRIMARY KEYS
        // =====================================================

        Set<String> primaryKeys =
                new HashSet<>();

        try (ResultSet pkRs =
                     metaData.getPrimaryKeys(
                             catalog,
                             schemaPattern,
                             tableName
                     )) {

            while (pkRs.next()) {

                String columnName =
                        pkRs.getString(
                                "COLUMN_NAME"
                        );

                if (columnName != null) {

                    primaryKeys.add(
                            columnName
                    );
                }
            }
        }


        // =====================================================
        // 4.2 FOREIGN KEYS
        // =====================================================

        Map<String, String[]> foreignKeys =
                new HashMap<>();

        try (ResultSet fkRs =
                     metaData.getImportedKeys(
                             catalog,
                             schemaPattern,
                             tableName
                     )) {

            while (fkRs.next()) {

                String fkColumn =
                        fkRs.getString(
                                "FKCOLUMN_NAME"
                        );

                String refTable =
                        fkRs.getString(
                                "PKTABLE_NAME"
                        );

                String refColumn =
                        fkRs.getString(
                                "PKCOLUMN_NAME"
                        );

                if (fkColumn == null) {
                    continue;
                }

                foreignKeys.put(
                        fkColumn,
                        new String[]{
                                refTable,
                                refColumn
                        }
                );
            }
        }


        // =====================================================
        // 4.3 COLUMNS
        // =====================================================

        try (ResultSet colRs =
                     metaData.getColumns(
                             catalog,
                             schemaPattern,
                             tableName,
                             "%"
                     )) {

            while (colRs.next()) {

                String columnName =
                        colRs.getString(
                                "COLUMN_NAME"
                        );

                if (columnName == null
                        || columnName.isBlank()) {

                    continue;
                }


                // ---------------------------------------------
                // Foreign key target
                // ---------------------------------------------

                String[] fkTarget =
                        foreignKeys.get(
                                columnName
                        );


                // ---------------------------------------------
                // Description từ database
                // ---------------------------------------------

                String databaseDescription =
                        colRs.getString(
                                "REMARKS"
                        );


                // ---------------------------------------------
                // Description application ưu tiên hơn
                // ---------------------------------------------

                String description =
                        getPreservedDescription(
                                existingColumnDescriptions,
                                buildColumnKey(
                                        tableName,
                                        columnName
                                ),
                                databaseDescription
                        );


                // ---------------------------------------------
                // Build ColumnMetadata
                // ---------------------------------------------

                ColumnMetadata column =
                        ColumnMetadata.builder()
                                .table(table)
                                .name(columnName)
                                .dataType(
                                        colRs.getString(
                                                "TYPE_NAME"
                                        )
                                )
                                .nullable(
                                        colRs.getInt(
                                                "NULLABLE"
                                        )
                                                ==
                                                DatabaseMetaData
                                                        .columnNullable
                                )
                                .primaryKey(
                                        primaryKeys.contains(
                                                columnName
                                        )
                                )
                                .foreignKey(
                                        fkTarget != null
                                )
                                .referencedTable(
                                        fkTarget != null
                                                ? fkTarget[0]
                                                : null
                                )
                                .referencedColumn(
                                        fkTarget != null
                                                ? fkTarget[1]
                                                : null
                                )
                                .description(
                                        description
                                )
                                .build();


                columns.add(column);
            }
        }

        return columns;
    }


    // =========================================================
    // 5. PRESERVE DESCRIPTION
    // =========================================================

    /**
     * Priority:
     *
     * 1. Description đã lưu trong application
     * 2. DatabaseMetaData.REMARKS
     * 3. null
     */
    private String getPreservedDescription(
            Map<String, String> existingDescriptions,
            String key,
            String databaseDescription
    ) {

        String existingDescription =
                existingDescriptions.get(key);

        if (existingDescription != null
                && !existingDescription.isBlank()) {

            return existingDescription;
        }

        if (databaseDescription != null
                && !databaseDescription.isBlank()) {

            return databaseDescription;
        }

        return null;
    }


    // =========================================================
    // 6. BUILD COLUMN KEY
    // =========================================================

    /**
     * Tạo key:
     *
     *     tableName.columnName
     *
     * Ví dụ:
     *
     *     users.email
     */
    private String buildColumnKey(
            String tableName,
            String columnName
    ) {

        return normalizeName(tableName)
                + "."
                + normalizeName(columnName);
    }


    // =========================================================
    // 7. NORMALIZE NAME
    // =========================================================

    /**
     * Normalize table/column name để tránh khác biệt:
     *
     *     USER
     *     User
     *     user
     *
     * Locale.ROOT giúp kết quả ổn định
     * trên mọi môi trường.
     */
    private String normalizeName(
            String name
    ) {

        return name
                .trim()
                .toLowerCase(Locale.ROOT);
    }


    // =========================================================
    // 8. GET JDBC CATALOG
    // =========================================================

    /**
     * Xác định catalog truyền vào DatabaseMetaData.
     *
     * MySQL:
     *
     *     catalog = databaseName
     *
     * PostgreSQL:
     *
     *     catalog = null
     *
     * DuckDB/Excel:
     *
     *     catalog = TÊN CATALOG THẬT của connection hiện tại (lấy từ
     *     metaData.getConnection().getCatalog()), KHÔNG PHẢI null.
     *
     *     FIX (audit Excel/DuckDB - "catalog naming"): trước đây luôn
     *     trả về null cho DuckDB. Với JDBC, catalog=null nghĩa là "không
     *     lọc theo catalog" - getTables/getColumns/... sẽ quét qua TẤT
     *     CẢ catalog mà connection nhìn thấy được, bao gồm cả 2 catalog
     *     nội bộ luôn tồn tại sẵn của DuckDB là "system" và "temp" (chứa
     *     view/function hệ thống). Trước đây "chạy đúng" chỉ vì các
     *     catalog đó tình cờ không có object kiểu TABLE trùng tên - đây
     *     là hành vi MAY MẮN chứ không phải cố ý, dễ vỡ khi DuckDB thêm
     *     object mới vào catalog hệ thống ở version sau.
     *
     *     Catalog THẬT của 1 file .duckdb luôn là tên file KHÔNG kèm
     *     đuôi ".duckdb" (ví dụ file "abc123.duckdb" -> catalog
     *     "abc123") - lấy trực tiếp từ chính connection đang mở thay vì
     *     tự suy luận từ đường dẫn file, để không phụ thuộc vào quy ước
     *     đặt tên nội bộ của driver duckdb_jdbc có thể đổi khác đi.
     */
    private String getCatalog(
            DatabaseConnection connection,
            DatabaseMetaData metaData
    ) throws SQLException {

        if ("mysql".equalsIgnoreCase(
                connection.getDbType()
        )) {

            return connection.getDatabaseName();
        }

        if ("excel".equalsIgnoreCase(
                connection.getDbType()
        )) {

            return metaData.getConnection().getCatalog();
        }

        return null;
    }


    // =========================================================
    // 9. GET JDBC SCHEMA PATTERN
    // =========================================================

    /**
     * Xác định schemaPattern truyền vào DatabaseMetaData.
     *
     * PostgreSQL:
     *
     *     public
     *
     * MySQL:
     *
     *     null
     *
     * DuckDB:
     *
     *     null
     *
     * Hiện tại MVP chỉ discover schema public
     * của PostgreSQL.
     */
    private String getSchemaPattern(
            DatabaseConnection connection
    ) {

        if ("postgres".equalsIgnoreCase(
                connection.getDbType()
        )
                ||
                "postgresql".equalsIgnoreCase(
                        connection.getDbType()
                )) {

            return "public";
        }

        return null;
    }
}