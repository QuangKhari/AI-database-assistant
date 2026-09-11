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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.aidatabaseassistant.security.SsrfProtection;

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
    private final com.example.aidatabaseassistant.security.ConnectionAccessGuard connectionAccessGuard;
    private final SsrfProtection ssrfProtection;

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

    /*
     * Dùng TransactionTemplate (thay vì @Transactional) cho
     * discoverAndPersistSchema().
     *
     * Lý do giống hệt SchemaRetrievalService.loadSchemaForRag():
     *
     * - transaction chỉ tồn tại trong lúc discovery (JDBC target DB)
     *   + persist (schemaRepository.save)
     * - transaction PHẢI kết thúc (commit, release Hikari connection)
     *   TRƯỚC KHI discoverSchema() gọi ensureEmbeddings() (Gemini HTTP)
     *
     * Không thể dùng @Transactional trên method private vì method đó
     * được gọi qua self-invocation (this.xxx() trong cùng class) —
     * self-invocation bỏ qua Spring AOP proxy nên @Transactional sẽ
     * không có tác dụng gì. TransactionTemplate không bị ảnh hưởng bởi
     * self-invocation vì nó tạo transaction thủ công, không dựa vào proxy.
     */
    private final PlatformTransactionManager transactionManager;


    // =========================================================
    //RESOLVE KEY COLUMN MAP
    // =========================================================
    @Transactional(readOnly = true)
    public Map<String, Boolean> resolveKeyColumnMap(
            String username,
            Long connectionId
    ) {

        if (connectionId == null) {
            return Map.of();
        }

        DatabaseConnection connection = connectionAccessGuard.requireOwnedConnection(username, connectionId);

        return schemaRepository
                .findByConnectionId(connectionId)
                .map(SchemaDiscoveryService::buildKeyColumnMap)
                .orElse(Map.of());
    }


    // =========================================================
    //BUILD KEY COLUMN MAP
    // =========================================================
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
    //DISCOVER SCHEMA
    // =========================================================
    @CacheEvict(
            cacheNames = CacheConfig.FULL_SCHEMA_CACHE,
            cacheManager = "localCacheManager",
            key = "#connectionId"
    )
    public DatabaseSchema discoverSchema(
            String username,
            Long connectionId
    ) {

        /*
         * =====================================================
         * 1. DISCOVERY + PERSIST (có transaction JPA ngắn)
         * =====================================================
         *
         * Transaction COMMIT và Hikari connection được release
         * ngay khi discoverAndPersistSchema() return.
         */
        DatabaseSchema savedSchema =
                discoverAndPersistSchema(username, connectionId);

        /*
         * =====================================================
         * 2. SCHEMA RAG EMBEDDING (KHÔNG transaction)
         * =====================================================
         *
         * QUAN TRỌNG:
         *
         * Tại đây transaction JPA của bước 1 đã kết thúc hoàn toàn.
         *
         * Vì vậy ensureEmbeddings() gọi Gemini HTTP (nhiều lần,
         * có retry khi 429) cho từng bảng thay đổi mà KHÔNG giữ
         * bất kỳ Hikari connection nào của app idle trong lúc chờ.
         *
         * Trước đây @Transactional nằm trên chính discoverSchema()
         * khiến ensureEmbeddings() — dù bản thân nó không còn
         * @Transactional — vẫn JOIN vào transaction đang mở này
         * (propagation REQUIRED mặc định của Spring). Bỏ
         * @Transactional trên ensureEmbeddings() một mình không đủ;
         * phải bỏ luôn ở method gọi nó (discoverSchema) như ở đây.
         */
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
    }

    /**
     * Thực hiện toàn bộ phần discovery (JDBC metadata của target DB)
     * + persist (schemaRepository.save) bên trong MỘT transaction JPA
     * ngắn, dùng TransactionTemplate để transaction này chắc chắn kết
     * thúc trước khi discoverSchema() gọi ensureEmbeddings().
     */
    private DatabaseSchema discoverAndPersistSchema(
            String username,
            Long connectionId
    ) {

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        return transactionTemplate.execute(status -> {

            DatabaseConnection connection =
                    connectionAccessGuard.requireOwnedConnection(
                            username,
                            connectionId
                    );

            if (!"excel".equalsIgnoreCase(connection.getDbType())) {

                ssrfProtection.validateHost(
                        connection.getHost()
                );
            }

            String rawPassword =
                    encryptionUtil.decrypt(
                            connection.getEncryptedPassword()
                    );


            // -----------------------------------------------------
            // Load existing schema
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

            schema.setConnection(connection);


            // =====================================================
            //BACKUP APPLICATION METADATA
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
            //CLEAR OLD TABLE METADATA
            // =====================================================

            List<TableMetadata> tables =
                    schema.getTables();

            if (tables == null) {

                tables = new ArrayList<>();

                schema.setTables(tables);
            }

            tables.clear();


            // =====================================================
            // OPEN TARGET DATABASE CONNECTION
            // =====================================================

            try (Connection conn =
                         targetDatabaseClient.openConnection(
                                 connection.getDbType(),
                                 connection.getHost(),
                                 connection.getPort(),
                                 connection.getDatabaseName(),
                                 connection.getUsername(),
                                 rawPassword,
                                 connection.isSslEnabled()
                         )) {

                DatabaseMetaData metaData =
                        conn.getMetaData();


                // =================================================
                //XÁC ĐỊNH CATALOG / SCHEMA
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
                //DISCOVER TABLES
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
                //UPDATE SYNC TIME
                // =================================================

                schema.setLastSyncedAt(
                        LocalDateTime.now()
                );


                // =================================================
                //SAVE SCHEMA
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

                return savedSchema;

            } catch (SQLException e) {

                throw new RuntimeException(
                        "Không thể đọc schema: "
                                + e.getMessage(),
                        e
                );
            }
        });
    }


    // =========================================================
    //DISCOVER COLUMNS
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
        //PRIMARY KEYS
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
        //FOREIGN KEYS
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
        //COLUMNS
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
    //PRESERVE DESCRIPTION
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
    //BUILD COLUMN KEY
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
    //NORMALIZE NAME
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
    //GET JDBC CATALOG
    // =========================================================
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
    //GET JDBC SCHEMA PATTERN
    // =========================================================

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