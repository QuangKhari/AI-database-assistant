package com.example.aidatabaseassistant.integration;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.ConnectionService;
import com.example.aidatabaseassistant.service.SchemaDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SsrfProtectionIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseConnectionRepository connectionRepository;

    @Autowired
    private EncryptionUtil encryptionUtil;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private SchemaDiscoveryService schemaDiscoveryService;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.findByUsername("ssrf_test_user")
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .username("ssrf_test_user")
                                .email("ssrf_test_user@example.com")
                                .passwordHash("test")
                                .build()
                ));
    }

    private DatabaseConnection createConnection(String host) {
        return connectionRepository.saveAndFlush(
                DatabaseConnection.builder()
                        .user(user)
                        .name("SSRF Test")
                        .dbType("mysql")
                        .host(host)
                        .port(3306)
                        .databaseName("test")
                        .username("root")
                        .encryptedPassword(
                                encryptionUtil.encrypt("password")
                        )
                        .build()
        );
    }

    @Test
    void reconnect_shouldRejectLocalhost() {

        DatabaseConnection connection =
                createConnection("localhost");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connectionService.reconnect(
                                user.getUsername(),
                                connection.getId()
                        )
                );

        assertEquals(
                "Host không được phép",
                exception.getMessage()
        );
    }

    @Test
    void reconnect_shouldRejectLoopbackAddress() {

        DatabaseConnection connection =
                createConnection("127.0.0.1");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connectionService.reconnect(
                                user.getUsername(),
                                connection.getId()
                        )
                );

        assertEquals(
                "Host không được phép",
                exception.getMessage()
        );
    }

    @Test
    void reconnect_shouldRejectCloudMetadataAddress() {

        DatabaseConnection connection =
                createConnection("169.254.169.254");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connectionService.reconnect(
                                user.getUsername(),
                                connection.getId()
                        )
                );

        assertEquals(
                "Host không được phép",
                exception.getMessage()
        );
    }

    @Test
    void discoverSchema_shouldRejectLocalhost() {

        DatabaseConnection connection =
                createConnection("localhost");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> schemaDiscoveryService.discoverSchema(
                                user.getUsername(),
                                connection.getId()
                        )
                );

        assertEquals(
                "Host không được phép",
                exception.getMessage()
        );
    }

    @Test
    void discoverSchema_shouldRejectLoopbackAddress() {

        DatabaseConnection connection =
                createConnection("127.0.0.1");

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> schemaDiscoveryService.discoverSchema(
                                user.getUsername(),
                                connection.getId()
                        )
                );

        assertEquals(
                "Host không được phép",
                exception.getMessage()
        );
    }
}