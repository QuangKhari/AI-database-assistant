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
import com.example.aidatabaseassistant.security.SsrfProtection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    private ConnectionService connectionService;

    private User owner;
    private User otherUser;

    @BeforeEach
    void setUp() {

        connectionService = new ConnectionService(
                connectionRepository,
                userRepository,
                encryptionUtil,
                ssrfProtection
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

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

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

    @Test
    void getConnectionsByUser_shouldReturnOnlyConnectionsOfThatUser() {

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

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

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        ConnectionResponse response =
                connectionService.getConnection("owner", 10L);

        assertEquals(10L, response.getId());
    }

    @Test
    void getConnection_shouldThrow_whenRequestedByNonOwner_IDOR() {

        DatabaseConnection connection = sampleConnection();

        when(userRepository.findByUsername("intruder"))
                .thenReturn(Optional.of(otherUser));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.getConnection("intruder", 10L)
        );

        assertTrue(
                ex.getMessage().contains("không có quyền")
        );
    }

    @Test
    void getConnection_shouldThrow_whenConnectionDoesNotExist() {

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        when(connectionRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
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

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        doThrow(new IllegalArgumentException("Host không được phép"))
                .when(ssrfProtection).validateHost("169.254.169.254");

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

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        when(connectionRepository.countByUserId(1L)).thenReturn(5L);

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

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        doThrow(new IllegalArgumentException("Host không được phép"))
                .when(ssrfProtection).validateHost("127.0.0.1");

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.updateConnection("owner", 10L, request)
        );

        verify(connectionRepository, never()).save(any());
    }

    @Test
    void updateConnection_shouldValidateHost_afterOwnershipCheck() {
        // IDOR phai duoc uu tien kiem tra TRUOC validate host: neu khong
        // phai chu so huu thi khong duoc phep biet host co bi SSRF chan
        // hay khong (tranh ro ri thong tin qua thong bao loi khac nhau).

        DatabaseConnection connection = sampleConnection();

        ConnectionUpdateRequest request = new ConnectionUpdateRequest();
        request.setName("Hacked");
        request.setHost("evil.com");
        request.setPort(1);
        request.setDatabaseName("x");
        request.setUsername("x");

        when(userRepository.findByUsername("intruder"))
                .thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.updateConnection("intruder", 10L, request)
        );

        verify(ssrfProtection, never()).validateHost(any());
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

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        when(encryptionUtil.encrypt("new-plain-secret"))
                .thenReturn("new-encrypted-secret");

        ConnectionResponse response =
                connectionService.updateConnection(
                        "owner",
                        10L,
                        request
                );

        assertEquals("Renamed DB", response.getName());

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

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        connectionService.updateConnection(
                "owner",
                10L,
                request
        );

        assertEquals(
                originalEncrypted,
                connection.getEncryptedPassword()
        );

        verify(encryptionUtil, never()).encrypt(any());
    }

    @Test
    void updateConnection_shouldThrow_whenRequestedByNonOwner_IDOR() {

        DatabaseConnection connection = sampleConnection();

        ConnectionUpdateRequest request =
                new ConnectionUpdateRequest();

        request.setName("Hacked");
        request.setHost("evil.com");
        request.setPort(1);
        request.setDatabaseName("x");
        request.setUsername("x");

        when(userRepository.findByUsername("intruder"))
                .thenReturn(Optional.of(otherUser));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.updateConnection(
                        "intruder",
                        10L,
                        request
                )
        );

        verify(connectionRepository, never()).save(any());
    }

    @Test
    void disconnect_shouldDeleteConnection_whenRequestedByOwner() {

        DatabaseConnection connection = sampleConnection();

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        connectionService.disconnect("owner", 10L);

        verify(connectionRepository).delete(connection);
    }

    @Test
    void disconnect_shouldThrowAndNotDelete_whenRequestedByNonOwner_IDOR() {

        DatabaseConnection connection = sampleConnection();

        when(userRepository.findByUsername("intruder"))
                .thenReturn(Optional.of(otherUser));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.disconnect(
                        "intruder",
                        10L
                )
        );

        verify(
                connectionRepository,
                never()
        ).delete(any());
    }

    @Test
    void reconnect_shouldThrow_beforeTouchingNetwork_whenRequestedByNonOwner_IDOR() {

        DatabaseConnection connection = sampleConnection();

        when(userRepository.findByUsername("intruder"))
                .thenReturn(Optional.of(otherUser));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.reconnect(
                        "intruder",
                        10L
                )
        );

        // Ownership check phải chặn trước khi giải mã password
        // hoặc mở kết nối thật.
        verify(
                encryptionUtil,
                never()
        ).decrypt(any());
    }

    @Test
    void testConnection_shouldReturnFalse_whenUnsupportedDbType() {

        ConnectionRequest request =
                new ConnectionRequest();

        request.setName("x");
        request.setDbType("postgres");
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabaseName("x");
        request.setUsername("x");
        request.setPassword("x");

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

        ConnectionRequest request = new ConnectionRequest();
        request.setName("Connection 6");
        request.setDbType("mysql");
        request.setHost("127.0.0.1");
        request.setPort(3306);
        request.setDatabaseName("test_db");
        request.setUsername("root");
        request.setPassword("password");

        when(userRepository.findByUsername("khai"))
                .thenReturn(Optional.of(user));

        when(connectionRepository.countByUserId(1L))
                .thenReturn(5L);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> connectionService.saveConnection("khai", request)
        );

        assertEquals(
                "Bạn đã đạt giới hạn tối đa 5 kết nối database. "
                        + "Vui lòng xóa bớt kết nối cũ trước khi thêm mới.",
                ex.getMessage()
        );

        verify(connectionRepository, never())
                .save(any());

        verify(encryptionUtil, never())
                .encrypt(any());
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

        ConnectionRequest request = new ConnectionRequest();
        request.setName("Connection 5");
        request.setDbType("mysql");
        request.setHost("127.0.0.1");
        request.setPort(3306);
        request.setDatabaseName("test_db");
        request.setUsername("root");
        request.setPassword("password");

        when(userRepository.findByUsername("khai"))
                .thenReturn(Optional.of(user));

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

        when(connectionRepository.save(any(DatabaseConnection.class)))
                .thenReturn(savedConnection);

        ConnectionResponse response =
                connectionService.saveConnection("khai", request);

        assertNotNull(response);

        verify(connectionRepository)
                .save(any(DatabaseConnection.class));

        verify(encryptionUtil)
                .encrypt("password");
    }
}