package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.security.ConnectionAccessGuard;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.security.SsrfProtection;
import com.example.aidatabaseassistant.db.TargetDatabaseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectionServiceTest {

    @Mock
    private DatabaseConnectionRepository connectionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EncryptionUtil encryptionUtil;

    @Mock
    private SsrfProtection ssrfProtection;

    @Mock
    private TargetDatabaseClient targetDatabaseClient;

    @Mock
    private ExcelIngestionService excelIngestionService;

    @Mock
    private ConnectionAccessGuard connectionAccessGuard;

    private ConnectionService connectionService;

    private User owner;
    private User otherUser;

    @BeforeEach
    void setUp() {

        connectionService = new ConnectionService(
                connectionRepository,
                userRepository,
                encryptionUtil,
                ssrfProtection,
                targetDatabaseClient,
                excelIngestionService,
                connectionAccessGuard
        );

        ReflectionTestUtils.setField(
                connectionService,
                "maxConnectionsPerUser",
                5
        );

        owner = User.builder()
                .id(1L)
                .username("owner")
                .build();

        otherUser = User.builder()
                .id(2L)
                .username("intruder")
                .build();
    }

    private DatabaseConnection sampleConnection() {
        return DatabaseConnection.builder()
                .id(10L)
                .user(owner)
                .name("My DB")
                .dbType("mysql")
                .host("localhost")
                .port(3306)
                .databaseName("shop")
                .username("root")
                .encryptedPassword("encrypted-secret")
                .build();
    }

    @Test
    void saveConnection_shouldEncryptPasswordAndPersist() {

        ConnectionRequest request = new ConnectionRequest();

        request.setName("My DB");
        request.setDbType("mysql");
        request.setHost("localhost");
        request.setPort(3306);
        request.setDatabaseName("shop");
        request.setUsername("root");
        request.setPassword("plain-secret");

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(encryptionUtil.encrypt("plain-secret"))
                .thenReturn("encrypted-secret");

        ConnectionResponse response =
                connectionService.saveConnection("owner", request);

        assertEquals("My DB", response.getName());
        assertEquals("mysql", response.getDbType());

        ArgumentCaptor<DatabaseConnection> captor =
                ArgumentCaptor.forClass(DatabaseConnection.class);

        verify(connectionRepository).save(captor.capture());

        assertEquals(
                "encrypted-secret",
                captor.getValue().getEncryptedPassword()
        );

        assertEquals(
                owner,
                captor.getValue().getUser()
        );
    }

    // =========================================================
    // MULTI-DB: saveConnection() phải chấp nhận postgres/postgresql
    // và chặn SỚM các dbType không được hỗ trợ.
    // =========================================================

    @Test
    void saveConnection_shouldAcceptPostgresDbType() {

        ConnectionRequest request = new ConnectionRequest();
        request.setName("Postgres DB");
        request.setDbType("postgres");
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabaseName("shop");
        request.setUsername("postgres");
        request.setPassword("plain-secret");

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(encryptionUtil.encrypt("plain-secret"))
                .thenReturn("encrypted-secret");

        ConnectionResponse response =
                connectionService.saveConnection("owner", request);

        assertEquals("postgres", response.getDbType());

        verify(connectionRepository).save(any());
    }

    @Test
    void saveConnection_shouldAcceptPostgresqlAliasDbType() {

        ConnectionRequest request = new ConnectionRequest();
        request.setName("Postgres DB");
        request.setDbType("postgresql");
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabaseName("shop");
        request.setUsername("postgres");
        request.setPassword("plain-secret");

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(encryptionUtil.encrypt("plain-secret"))
                .thenReturn("encrypted-secret");

        ConnectionResponse response =
                connectionService.saveConnection("owner", request);

        assertEquals("postgresql", response.getDbType());
    }

    @Test
    void saveConnection_shouldRejectUnsupportedDbType_beforeTouchingDb() {

        ConnectionRequest request = new ConnectionRequest();
        request.setName("Oracle DB");
        request.setDbType("oracle");
        request.setHost("localhost");
        request.setPort(1521);
        request.setDatabaseName("shop");
        request.setUsername("root");
        request.setPassword("plain-secret");

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.saveConnection("owner", request)
        );

        verify(ssrfProtection, never()).validateHost(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void saveConnection_shouldRejectBlankDbType() {

        ConnectionRequest request = new ConnectionRequest();
        request.setName("No type DB");
        request.setDbType("");
        request.setHost("localhost");
        request.setPort(3306);
        request.setDatabaseName("shop");
        request.setUsername("root");
        request.setPassword("plain-secret");

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.saveConnection("owner", request)
        );

        verify(connectionRepository, never()).save(any());
    }

    @Test
    void getConnectionsByUser_shouldReturnOnlyConnectionsOfThatUser() {

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(connectionRepository.findByUserId(1L))
                .thenReturn(List.of(sampleConnection()));

        List<ConnectionResponse> result =
                connectionService.getConnectionsByUser("owner");

        assertEquals(1, result.size());
        assertEquals("My DB", result.get(0).getName());
    }

    @Test
    void getConnection_shouldReturnConnection_whenRequestedByOwner() {

        DatabaseConnection connection = sampleConnection();

        when(connectionAccessGuard.requireOwnedConnection("owner", 10L))
                .thenReturn(connection);

        ConnectionResponse response =
                connectionService.getConnection("owner", 10L);

        assertEquals(10L, response.getId());
    }

    @Test
    void getConnection_shouldThrow_whenRequestedByNonOwner_IDOR() {

        when(connectionAccessGuard.requireOwnedConnection("intruder", 10L))
                .thenThrow(
                        new ForbiddenResourceException(
                                "Bạn không có quyền truy cập connection này"
                        )
                );

        assertThrows(
                ForbiddenResourceException.class,
                () -> connectionService.getConnection("intruder", 10L)
        );
    }

    @Test
    void getConnection_shouldThrow_whenConnectionDoesNotExist() {

        when(connectionAccessGuard.requireOwnedConnection("owner", 999L))
                .thenThrow(
                        new ResourceNotFoundException(
                                "Không tìm thấy connection"
                        )
                );

        assertThrows(
                ResourceNotFoundException.class,
                () -> connectionService.getConnection("owner", 999L)
        );
    }

    @Test
    void saveConnection_shouldValidateHost_forSsrf() {

        ConnectionRequest request = new ConnectionRequest();
        request.setName("My DB");
        request.setDbType("mysql");
        request.setHost("169.254.169.254");
        request.setPort(3306);
        request.setDatabaseName("shop");
        request.setUsername("root");
        request.setPassword("plain-secret");

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        doThrow(
                new IllegalArgumentException("Host không được phép")
        ).when(ssrfProtection)
                .validateHost("169.254.169.254");

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.saveConnection("owner", request)
        );

        verify(connectionRepository, never()).save(any());
    }

    @Test
    void saveConnection_shouldThrow_whenMaxConnectionsPerUserReached() {

        ConnectionRequest request = new ConnectionRequest();
        request.setName("4th DB");
        request.setDbType("mysql");
        request.setHost("db.example.com");
        request.setPort(3306);
        request.setDatabaseName("shop");
        request.setUsername("root");
        request.setPassword("plain-secret");

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(connectionRepository.countByUserId(1L))
                .thenReturn(5L);

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.saveConnection("owner", request)
        );

        verify(connectionRepository, never()).save(any());
    }

    @Test
    void updateConnection_shouldValidateHost_forSsrf() {

        DatabaseConnection connection = sampleConnection();

        ConnectionUpdateRequest request = new ConnectionUpdateRequest();
        request.setName("Renamed DB");
        request.setHost("127.0.0.1");
        request.setPort(3307);
        request.setDatabaseName("shop2");
        request.setUsername("root2");

        when(connectionAccessGuard.requireOwnedConnection("owner", 10L))
                .thenReturn(connection);

        doThrow(
                new IllegalArgumentException("Host không được phép")
        ).when(ssrfProtection)
                .validateHost("127.0.0.1");

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.updateConnection(
                        "owner",
                        10L,
                        request
                )
        );

        verify(connectionRepository, never()).save(any());
    }

    @Test
    void updateConnection_shouldValidateHost_afterOwnershipCheck() {

        ConnectionUpdateRequest request =
                new ConnectionUpdateRequest();

        request.setName("Hacked");
        request.setHost("evil.com");
        request.setPort(1);
        request.setDatabaseName("x");
        request.setUsername("x");

        when(connectionAccessGuard.requireOwnedConnection(
                "intruder",
                10L
        )).thenThrow(
                new ForbiddenResourceException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        assertThrows(
                ForbiddenResourceException.class,
                () -> connectionService.updateConnection(
                        "intruder",
                        10L,
                        request
                )
        );

        verify(
                ssrfProtection,
                never()
        ).validateHost(any());
    }

    @Test
    void updateConnection_shouldReEncryptPassword_whenNewPasswordProvided() {

        DatabaseConnection connection = sampleConnection();

        ConnectionUpdateRequest request =
                new ConnectionUpdateRequest();

        request.setName("Renamed DB");
        request.setHost("127.0.0.1");
        request.setPort(3307);
        request.setDatabaseName("shop2");
        request.setUsername("root2");
        request.setPassword("new-plain-secret");

        when(connectionAccessGuard.requireOwnedConnection("owner", 10L))
                .thenReturn(connection);

        when(encryptionUtil.encrypt("new-plain-secret"))
                .thenReturn("new-encrypted-secret");

        ConnectionResponse response =
                connectionService.updateConnection(
                        "owner",
                        10L,
                        request
                );

        assertEquals(
                "Renamed DB",
                response.getName()
        );

        assertEquals(
                "new-encrypted-secret",
                connection.getEncryptedPassword()
        );

        verify(connectionRepository).save(connection);
    }

    @Test
    void updateConnection_shouldKeepOldPassword_whenPasswordBlank() {

        DatabaseConnection connection = sampleConnection();

        String originalEncrypted =
                connection.getEncryptedPassword();

        ConnectionUpdateRequest request =
                new ConnectionUpdateRequest();

        request.setName("Renamed DB");
        request.setHost("127.0.0.1");
        request.setPort(3307);
        request.setDatabaseName("shop2");
        request.setUsername("root2");
        request.setPassword("   ");

        when(connectionAccessGuard.requireOwnedConnection("owner", 10L))
                .thenReturn(connection);

        connectionService.updateConnection(
                "owner",
                10L,
                request
        );

        assertEquals(
                originalEncrypted,
                connection.getEncryptedPassword()
        );

        verify(
                encryptionUtil,
                never()
        ).encrypt(any());
    }

    @Test
    void updateConnection_shouldThrow_whenRequestedByNonOwner_IDOR() {

        ConnectionUpdateRequest request =
                new ConnectionUpdateRequest();

        request.setName("Hacked");
        request.setHost("evil.com");
        request.setPort(1);
        request.setDatabaseName("x");
        request.setUsername("x");

        when(connectionAccessGuard.requireOwnedConnection(
                "intruder",
                10L
        )).thenThrow(
                new ForbiddenResourceException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        assertThrows(
                ForbiddenResourceException.class,
                () -> connectionService.updateConnection(
                        "intruder",
                        10L,
                        request
                )
        );

        verify(
                connectionRepository,
                never()
        ).save(any());
    }

    @Test
    void disconnect_shouldDeleteConnection_whenRequestedByOwner() {

        DatabaseConnection connection = sampleConnection();

        when(connectionAccessGuard.requireOwnedConnection("owner", 10L))
                .thenReturn(connection);

        connectionService.disconnect("owner", 10L);

        verify(connectionRepository)
                .delete(connection);
    }

    @Test
    void disconnect_shouldThrowAndNotDelete_whenRequestedByNonOwner_IDOR() {

        when(connectionAccessGuard.requireOwnedConnection(
                "intruder",
                20L
        )).thenThrow(
                new ForbiddenResourceException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        assertThrows(
                ForbiddenResourceException.class,
                () -> connectionService.disconnect(
                        "intruder",
                        20L
                )
        );

        verify(
                excelIngestionService,
                never()
        ).deleteDuckDbFile(anyString());

        verify(
                connectionRepository,
                never()
        ).delete(any());
    }

    @Test
    void reconnect_shouldThrow_beforeTouchingNetwork_whenRequestedByNonOwner_IDOR() {

        when(connectionAccessGuard.requireOwnedConnection(
                "intruder",
                10L
        )).thenThrow(
                new ForbiddenResourceException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        assertThrows(
                ForbiddenResourceException.class,
                () -> connectionService.reconnect(
                        "intruder",
                        10L
                )
        );

        verify(
                encryptionUtil,
                never()
        ).decrypt(any());
    }

    @Test
    void reconnect_withExcelConnection_shouldSkipSsrfValidation_andSucceed() {

        DatabaseConnection excelConnection =
                DatabaseConnection.builder()
                        .id(30L)
                        .user(owner)
                        .name("Sales Excel")
                        .dbType("excel")
                        .host("local-file")
                        .port(0)
                        .databaseName(
                                "/data/excel-dbs/user_1/sales.duckdb"
                        )
                        .username("excel-file")
                        .encryptedPassword("encrypted")
                        .build();

        when(connectionAccessGuard.requireOwnedConnection(
                "owner",
                30L
        )).thenReturn(excelConnection);

        when(encryptionUtil.decrypt("encrypted"))
                .thenReturn("-");

        when(targetDatabaseClient.testConnection(
                eq("excel"),
                eq("local-file"),
                eq(0),
                eq("/data/excel-dbs/user_1/sales.duckdb"),
                eq("excel-file"),
                eq("-"),
                eq(false)
        )).thenReturn(true);

        var result =
                connectionService.reconnect(
                        "owner",
                        30L
                );

        assertTrue(result.isSuccessful());

        verify(
                ssrfProtection,
                never()
        ).validateHost(anyString());
    }

    @Test
    void testConnection_shouldPropagateException_whenUnsupportedDbType() {

        ConnectionRequest request =
                new ConnectionRequest();

        request.setName("x");
        request.setDbType("postgres");
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabaseName("x");
        request.setUsername("x");
        request.setPassword("x");

        when(targetDatabaseClient.testConnection(
                "postgres",
                "localhost",
                5432,
                "x",
                "x",
                "x",
                false
        )).thenThrow(
                new IllegalArgumentException(
                        "Loại database chưa được hỗ trợ: postgres"
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.testConnection(request)
        );
    }

    @Test
    void saveConnection_shouldReject_whenUserAlreadyReachedConnectionLimit() {

        User user = User.builder()
                .id(1L)
                .username("khai")
                .email("khai@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .build();

        ConnectionRequest request =
                new ConnectionRequest();

        request.setName("Connection 6");
        request.setDbType("mysql");
        request.setHost("127.0.0.1");
        request.setPort(3306);
        request.setDatabaseName("test_db");
        request.setUsername("root");
        request.setPassword("password");

        when(connectionAccessGuard.requireUser("khai"))
                .thenReturn(user);

        when(connectionRepository.countByUserId(1L))
                .thenReturn(5L);

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connectionService.saveConnection(
                                "khai",
                                request
                        )
                );

        assertEquals(
                "Bạn đã đạt giới hạn tối đa 5 kết nối database. "
                        + "Vui lòng xóa bớt kết nối cũ trước khi thêm mới.",
                ex.getMessage()
        );

        verify(
                connectionRepository,
                never()
        ).save(any());

        verify(
                encryptionUtil,
                never()
        ).encrypt(any());
    }

    @Test
    void saveConnection_shouldAllow_whenUserHasLessThanConnectionLimit() {

        User user = User.builder()
                .id(1L)
                .username("khai")
                .email("khai@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .build();

        ConnectionRequest request =
                new ConnectionRequest();

        request.setName("Connection 5");
        request.setDbType("mysql");
        request.setHost("127.0.0.1");
        request.setPort(3306);
        request.setDatabaseName("test_db");
        request.setUsername("root");
        request.setPassword("password");

        when(connectionAccessGuard.requireUser("khai"))
                .thenReturn(user);

        when(connectionRepository.countByUserId(1L))
                .thenReturn(4L);

        when(encryptionUtil.encrypt("password"))
                .thenReturn("encrypted-password");

        DatabaseConnection savedConnection =
                DatabaseConnection.builder()
                        .id(5L)
                        .user(user)
                        .name("Connection 5")
                        .dbType("mysql")
                        .host("127.0.0.1")
                        .port(3306)
                        .databaseName("test_db")
                        .username("root")
                        .encryptedPassword("encrypted-password")
                        .build();

        when(connectionRepository.save(
                any(DatabaseConnection.class)
        )).thenReturn(savedConnection);

        ConnectionResponse response =
                connectionService.saveConnection(
                        "khai",
                        request
                );

        assertNotNull(response);

        verify(connectionRepository)
                .save(any(DatabaseConnection.class));

        verify(encryptionUtil)
                .encrypt("password");
    }

    @Test
    void saveExcelConnection_shouldCreateExcelConnection() {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[]{1, 2, 3}
                );

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(connectionRepository.countByUserId(owner.getId()))
                .thenReturn(0L);

        when(excelIngestionService.ingest(
                same(file),
                eq(owner.getId())
        )).thenReturn(
                "/data/excel-dbs/user_1/sales.duckdb"
        );

        when(encryptionUtil.encrypt("-"))
                .thenReturn("encrypted-placeholder");

        DatabaseConnection saved =
                DatabaseConnection.builder()
                        .id(10L)
                        .user(owner)
                        .name("Sales")
                        .dbType("excel")
                        .host("local-file")
                        .port(0)
                        .databaseName(
                                "/data/excel-dbs/user_1/sales.duckdb"
                        )
                        .username("excel-file")
                        .encryptedPassword(
                                "encrypted-placeholder"
                        )
                        .build();

        when(connectionRepository.save(
                any(DatabaseConnection.class)
        )).thenReturn(saved);

        ConnectionResponse response =
                connectionService.saveExcelConnection(
                        "owner",
                        file,
                        "Sales"
                );

        assertNotNull(response);

        assertEquals(
                "Sales",
                response.getName()
        );

        assertEquals(
                "excel",
                response.getDbType()
        );

        verify(excelIngestionService)
                .ingest(
                        same(file),
                        eq(owner.getId())
                );

        verify(connectionRepository)
                .save(any(DatabaseConnection.class));
    }

    @Test
    void disconnect_shouldDeleteDuckDbFile_whenConnectionIsExcel() {

        DatabaseConnection connection =
                DatabaseConnection.builder()
                        .id(20L)
                        .user(owner)
                        .name("Sales")
                        .dbType("excel")
                        .host("local-file")
                        .port(0)
                        .databaseName(
                                "/data/excel-dbs/user_1/sales.duckdb"
                        )
                        .username("excel-file")
                        .encryptedPassword("encrypted")
                        .build();

        when(connectionAccessGuard.requireOwnedConnection(
                "owner",
                20L
        )).thenReturn(connection);

        connectionService.disconnect("owner", 20L);

        verify(excelIngestionService)
                .deleteDuckDbFile(
                        "/data/excel-dbs/user_1/sales.duckdb"
                );

        verify(connectionRepository)
                .delete(connection);
    }

    @Test
    void disconnect_shouldNotDeleteDuckDbFile_whenConnectionIsMySql() {

        DatabaseConnection connection = sampleConnection();

        when(connectionAccessGuard.requireOwnedConnection(
                "owner",
                10L
        )).thenReturn(connection);

        connectionService.disconnect("owner", 10L);

        verify(
                excelIngestionService,
                never()
        ).deleteDuckDbFile(anyString());

        verify(connectionRepository)
                .delete(connection);
    }
}