package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.db.TargetDatabaseClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.example.aidatabaseassistant.entity.User;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SchemaDiscoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(SchemaDiscoveryService.class);

    /**
     * Tra ve map "ten cot (viet thuong) -> co phai PK/FK hay khong" tu
     * schema DA LUU (khong tu ket noi lai DB - dung schema cache trong
     * database_schemas/table_metadata/column_metadata). Dung cho
     * ChartTypeClassifier/DataInsightAnalyzer de loai PK/FK khoi measure
     * chinh xac hon thay vi chi doan ten (xem Muc 2).
     *
     * Neu connectionId la null (client cu chua truyen) -> tra ve Map rong,
     * ChartTypeClassifier se tu fallback ve doan ten nhu truoc gio.
     *
     * Van kiem tra quyen so huu connection nhu discoverSchema() o tren -
     * TUYET DOI khong duoc bo qua, neu khong se thanh IDOR (user A do
     * duoc list cot cua connection user B qua endpoint chart-suggestion).
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> resolveKeyColumnMap(String username, Long connectionId) {
        if (connectionId == null) {
            return Map.of();
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
        }

        return schemaRepository.findByConnectionId(connectionId)
                .map(SchemaDiscoveryService::buildKeyColumnMap)
                .orElse(Map.of());
    }

    /**
     * Tach rieng thanh method static de QueryService co the tai su dung
     * TRUC TIEP voi fullSchema DA CO SAN TRONG BO NHO (khong query lai DB,
     * khong check quyen so huu lai lan nua vi processQuery() da check roi
     * o buoc load connection/schema ban dau) - xem Phan 2.9.
     */
    public static Map<String, Boolean> buildKeyColumnMap(DatabaseSchema schema) {
        if (schema == null || schema.getTables() == null) {
            return Map.of();
        }
        Map<String, Boolean> keyColumns = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            for (ColumnMetadata column : table.getColumns()) {
                boolean isKey = Boolean.TRUE.equals(column.getPrimaryKey())
                        || Boolean.TRUE.equals(column.getForeignKey());
                // Neu cung 1 ten cot xuat hien o nhieu bang (vi du "id" o ca
                // 2 bang khac nhau) va CHI 1 trong so do la key -> van uu
                // tien coi la key (an toan hon, giu dung triet ly cu cua
                // isIdLikeColumn: tha bo sot con hon nhan nham).
                keyColumns.merge(column.getName().toLowerCase(Locale.ROOT), isKey,
                        (existing, incoming) -> existing || incoming);
            }
        }
        return keyColumns;
    }

    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final EncryptionUtil encryptionUtil;
    private final UserRepository userRepository;
    // TRUOC DAY: tu goi ssrfProtection.validateHost() + tu build JDBC URL +
    // tu goi DriverManager.getConnection() ngay trong class nay. BAY GIO:
    // gom qua TargetDatabaseClient - noi DUY NHAT mo ket noi JDBC toi DB
    // cua user (SSRF check van duoc ap dung, nam ben trong openConnection()).
    private final TargetDatabaseClient targetDatabaseClient;

    // Schema RAG

    // Schema RAG
    private final SchemaEmbeddingService schemaEmbeddingService;

    @Transactional
    public DatabaseSchema discoverSchema(
            String username,
            Long connectionId) {

        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy user"
                        ));

        DatabaseConnection connection =
                connectionRepository.findById(connectionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy connection"
                                ));

        if (!connection.getUser().getId().equals(user.getId())) {

            throw new IllegalArgumentException(
                    "Bạn không có quyền truy cập connection này"
            );
        }

        String rawPassword =
                encryptionUtil.decrypt(
                        connection.getEncryptedPassword()
                );

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

        /*
         * =========================================================
         * BẢO TOÀN APPLICATION METADATA TRƯỚC KHI DISCOVERY
         * =========================================================
         *
         * Schema discovery tạo lại TableMetadata / ColumnMetadata
         * sau mỗi lần refresh.
         *
         * Nếu chỉ gọi:
         *
         *     tables.clear()
         *
         * rồi tạo object mới từ DatabaseMetaData:
         *
         *     description = REMARKS
         *
         * thì description do user nhập qua SchemaMetadataService
         * có thể bị mất.
         *
         * Vì vậy cần lưu description cũ trước khi clear().
         *
         * Key table:
         *
         *     tableName
         *
         * Key column:
         *
         *     tableName.columnName
         */
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

                /*
                 * Chỉ lưu description có giá trị.
                 */
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

                /*
                 * Lưu description của từng column.
                 */
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

        /*
         * =========================================================
         * XÓA METADATA CŨ
         * =========================================================
         *
         * Sau khi description đã được backup:
         *
         *     có thể clear() để discovery lại schema mới.
         */
        List<TableMetadata> tables =
                schema.getTables();

        tables.clear();

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

            try (ResultSet tableRs =
                         metaData.getTables(
                                 connection.getDatabaseName(),
                                 null,
                                 "%",
                                 new String[]{"TABLE"}
                         )) {

                while (tableRs.next()) {

                    String tableName =
                            tableRs.getString("TABLE_NAME");

                    /*
                     * Description từ database.
                     *
                     * MySQL có thể trả null / blank nếu table
                     * không có COMMENT.
                     */
                    String databaseDescription =
                            tableRs.getString("REMARKS");

                    /*
                     * Ưu tiên description do application/user
                     * đã lưu trước đó.
                     *
                     * Nếu chưa có:
                     *
                     *     dùng REMARKS từ database.
                     */
                    String description =
                            getPreservedDescription(
                                    existingTableDescriptions,
                                    normalizeName(tableName),
                                    databaseDescription
                            );

                    TableMetadata table =
                            TableMetadata.builder()
                                    .schema(schema)
                                    .name(tableName)
                                    .description(description)
                                    .build();

                    table.setColumns(
                            discoverColumns(
                                    metaData,
                                    connection.getDatabaseName(),
                                    tableName,
                                    table,
                                    existingColumnDescriptions
                            )
                    );

                    tables.add(table);
                }
            }

            schema.setLastSyncedAt(
                    LocalDateTime.now()
            );

            /*
             * Lưu schema trước để đảm bảo schema.id đã tồn tại.
             *
             * TableEmbedding sử dụng:
             *     (schema_id, table_name)
             *
             * thay vì table_id vì TableMetadata có thể bị xoá/tạo lại
             * trong mỗi lần discoverSchema().
             */
            DatabaseSchema savedSchema =
                    schemaRepository.save(schema);

            /*
             * Schema discovery là chức năng chính.
             * Embedding chỉ là chức năng bổ sung cho RAG.
             *
             * Nếu Gemini Embedding API lỗi, discovery schema
             * vẫn phải thành công.
             */
            try {

                schemaEmbeddingService.ensureEmbeddings(
                        savedSchema
                );

            } catch (Exception e) {

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

    private List<ColumnMetadata> discoverColumns(
            DatabaseMetaData metaData,
            String dbName,
            String tableName,
            TableMetadata table,
            Map<String, String> existingColumnDescriptions
    ) throws SQLException {

        List<ColumnMetadata> columns =
                new ArrayList<>();

        Set<String> primaryKeys =
                new HashSet<>();

        try (ResultSet pkRs =
                     metaData.getPrimaryKeys(
                             dbName,
                             null,
                             tableName
                     )) {

            while (pkRs.next()) {

                primaryKeys.add(
                        pkRs.getString("COLUMN_NAME")
                );
            }
        }

        Map<String, String[]> foreignKeys =
                new HashMap<>();

        try (ResultSet fkRs =
                     metaData.getImportedKeys(
                             dbName,
                             null,
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

                foreignKeys.put(
                        fkColumn,
                        new String[]{
                                refTable,
                                refColumn
                        }
                );
            }
        }

        try (ResultSet colRs =
                     metaData.getColumns(
                             dbName,
                             null,
                             tableName,
                             "%"
                     )) {

            while (colRs.next()) {

                String columnName =
                        colRs.getString(
                                "COLUMN_NAME"
                        );

                String[] fkTarget =
                        foreignKeys.get(
                                columnName
                        );

                /*
                 * Description từ database.
                 */
                String databaseDescription =
                        colRs.getString("REMARKS");

                /*
                 * Ưu tiên description do user/application
                 * đã lưu trước đó.
                 *
                 * Nếu chưa có:
                 *
                 *     dùng REMARKS từ database.
                 */
                String description =
                        getPreservedDescription(
                                existingColumnDescriptions,
                                buildColumnKey(
                                        tableName,
                                        columnName
                                ),
                                databaseDescription
                        );

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
                                                == DatabaseMetaData
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
                                .description(description)
                                .build();

                columns.add(column);
            }
        }

        return columns;
    }

    /**
     * Lấy description ưu tiên theo thứ tự:
     *
     * 1. Description đã được user/application lưu.
     * 2. Description từ DatabaseMetaData.REMARKS.
     * 3. null.
     *
     * Điều này giúp schema refresh không làm mất
     * metadata mà user đã nhập cho AI.
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

    /**
     * Tạo key duy nhất cho column:
     *
     *     tableName.columnName
     *
     * Dùng normalized name để tránh lỗi khác
     * chữ hoa / chữ thường.
     */
    private String buildColumnKey(
            String tableName,
            String columnName
    ) {

        return normalizeName(tableName)
                + "."
                + normalizeName(columnName);
    }

    /**
     * Chuẩn hóa tên table/column.
     *
     * Locale.ROOT giúp kết quả ổn định
     * trên mọi môi trường chạy application.
     */
    private String normalizeName(String name) {

        return name
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}