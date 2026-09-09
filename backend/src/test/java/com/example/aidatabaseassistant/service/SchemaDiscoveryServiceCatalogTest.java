package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.db.TargetDatabaseClient;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.security.ConnectionAccessGuard;
import com.example.aidatabaseassistant.security.SsrfProtection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 *
 * Truoc day SchemaDiscoveryService.getCatalog() LUON tra ve null cho
 * dbType="excel", khien DatabaseMetaData.getTables()/getColumns()/...
 * khong loc theo catalog - co the quet lan ca 2 catalog noi bo luon ton
 * tai san cua DuckDB la "system" va "temp".
 *
 * Test nay goi truc tiep method PRIVATE getCatalog(connection, metaData)
 * bang reflection (khong can dung toan bo pipeline discoverSchema, vi
 * getCatalog khong dung bat ky field nao khac cua SchemaDiscoveryService)
 * de khoa lai dung 3 nhanh hanh vi: mysql / postgres / excel.
 */
@ExtendWith(MockitoExtension.class)
class SchemaDiscoveryServiceCatalogTest {

    @Mock
    private DatabaseConnectionRepository connectionRepository;
    @Mock
    private DatabaseSchemaRepository schemaRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TargetDatabaseClient targetDatabaseClient;
    @Mock
    private SchemaEmbeddingService schemaEmbeddingService;

    @Mock
    private DatabaseMetaData metaData;
    @Mock
    private Connection liveConnection;
    @Mock
    private ConnectionAccessGuard connectionAccessGuard;
    @Mock
    private SsrfProtection ssrfProtection;

    private SchemaDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new SchemaDiscoveryService(
                connectionRepository,
                schemaRepository,
                encryptionUtil,
                userRepository,
                connectionAccessGuard,
                ssrfProtection,
                targetDatabaseClient,
                schemaEmbeddingService
        );
    }

    private String invokeGetCatalog(DatabaseConnection connection) throws Exception {
        Method method = SchemaDiscoveryService.class.getDeclaredMethod(
                "getCatalog", DatabaseConnection.class, DatabaseMetaData.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(service, connection, metaData);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap de assertThrows ben ngoai bat dung SQLException goc,
            // khong phai InvocationTargetException bao ngoai cua reflection.
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private DatabaseConnection connectionWithType(String dbType) {
        return DatabaseConnection.builder()
                .id(1L)
                .dbType(dbType)
                .databaseName(dbType.equals("mysql") ? "shop_db" : "/data/excel-dbs/user_1/sales.duckdb")
                .build();
    }

    @Test
    void getCatalog_forMysql_shouldReturnDatabaseName() throws Exception {
        DatabaseConnection connection = connectionWithType("mysql");

        String catalog = invokeGetCatalog(connection);

        assertEquals("shop_db", catalog);
        // MySQL khong can hoi live connection - chi dung tu DTO co san.
        verifyNoInteractions(metaData);
    }

    @Test
    void getCatalog_forPostgres_shouldReturnNull() throws Exception {
        DatabaseConnection connection = connectionWithType("postgres");

        String catalog = invokeGetCatalog(connection);

        assertNull(catalog);
        verifyNoInteractions(metaData);
    }

    @Test
    void getCatalog_forPostgresql_variantName_shouldReturnNull() throws Exception {
        // dbType co the luu la "postgresql" (day du) hoac "postgres" (rut
        // gon) tuy noi tao connection - ca hai deu phai duoc xu ly giong
        // nhau (xem JdbcUrlBuilder.build cung chap nhan ca hai).
        DatabaseConnection connection = connectionWithType("postgresql");

        assertNull(invokeGetCatalog(connection));
    }

    @Test
    void getCatalog_forExcel_shouldReturnRealCatalogFromLiveConnection() throws Exception {
        // Day la phan CON THIEU truoc day: khong duoc tra null cho excel
        // nua, ma phai hoi catalog THAT tu chinh connection dang mo.
        DatabaseConnection connection = connectionWithType("excel");

        when(metaData.getConnection()).thenReturn(liveConnection);
        when(liveConnection.getCatalog()).thenReturn("sales");

        String catalog = invokeGetCatalog(connection);

        assertEquals("sales", catalog);
        verify(metaData).getConnection();
        verify(liveConnection).getCatalog();
    }

    @Test
    void getCatalog_forExcel_shouldNotUseDatabaseNamePathAsCatalog() throws Exception {
        // Regression quan trong: KHONG duoc tu suy doan catalog tu
        // duong dan file (vi du tach ten file bang tay) - phai luon hoi
        // driver DuckDB that su, vi quy uoc dat ten catalog la chi tiet
        // noi bo cua driver duckdb_jdbc, co the doi khac trong tuong lai.
        DatabaseConnection connection = connectionWithType("excel");

        when(metaData.getConnection()).thenReturn(liveConnection);
        when(liveConnection.getCatalog()).thenReturn("abc123");

        String catalog = invokeGetCatalog(connection);

        // KHONG phai la duong dan file day du, cung khong phai suy doan
        // thu cong tu databaseName ("/data/excel-dbs/user_1/sales.duckdb")
        assertEquals("abc123", catalog);
        assertNotEquals(connection.getDatabaseName(), catalog);
    }

    @Test
    void getCatalog_forExcel_whenLiveConnectionThrows_shouldPropagateSqlException() throws Exception {
        DatabaseConnection connection = connectionWithType("excel");

        when(metaData.getConnection()).thenReturn(liveConnection);
        when(liveConnection.getCatalog()).thenThrow(new SQLException("connection closed"));

        assertThrows(SQLException.class, () -> invokeGetCatalog(connection));
    }
}