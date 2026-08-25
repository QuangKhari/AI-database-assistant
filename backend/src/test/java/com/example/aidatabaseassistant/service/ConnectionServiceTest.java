package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.config.TargetDatabaseProperties;
import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionTestResponse;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionServiceTest {

    @Mock DatabaseConnectionRepository connectionRepository;
    @Mock UserRepository userRepository;
    @Mock EncryptionUtil encryptionUtil;
    @Mock TargetDatabaseClient targetDatabaseClient;
    @Mock ConnectionTestRateLimiter rateLimiter;
    private ConnectionService service;
    private User user;

    @BeforeEach
    void setUp() {
        TargetDatabaseProperties properties = new TargetDatabaseProperties();
        service = new ConnectionService(connectionRepository, userRepository, encryptionUtil,
                targetDatabaseClient, rateLimiter, properties);
        user = User.builder().id(7L).username("student").email("student@example.com").role(Role.USER).build();
    }

    @Test
    void saveTestsReadOnlyAndStoresOnlyEncryptedPassword() {
        ConnectionRequest request = request();
        when(userRepository.findByUsernameIgnoreCase("student")).thenReturn(Optional.of(user));
        when(connectionRepository.countByUserIdAndActiveTrue(7L)).thenReturn(0L);
        when(targetDatabaseClient.test(any())).thenReturn(success());
        when(encryptionUtil.encrypt("mysql-secret")).thenReturn("gcm:v1:cipher");
        when(connectionRepository.save(any())).thenAnswer(invocation -> {
            var connection = invocation.getArgument(0, com.example.aidatabaseassistant.entity.DatabaseConnection.class);
            connection.setId(11L);
            return connection;
        });

        var response = service.saveConnection("student", request);

        assertThat(response.getId()).isEqualTo(11L);
        verify(targetDatabaseClient).test(any());
        verify(encryptionUtil).encrypt("mysql-secret");
        verify(rateLimiter).check("student");
    }

    @Test
    void saveRejectsSixthActiveConnectionBeforeNetworkCall() {
        when(userRepository.findByUsernameIgnoreCase("student")).thenReturn(Optional.of(user));
        when(connectionRepository.countByUserIdAndActiveTrue(7L)).thenReturn(5L);

        assertThatThrownBy(() -> service.saveConnection("student", request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tối đa 5");
        verify(targetDatabaseClient, never()).test(any());
    }

    @Test
    void ownershipPreventsReadingAnotherUsersConnection() {
        when(connectionRepository.findByIdAndUserUsernameIgnoreCase(99L, "student"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConnection("student", 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thuộc tài khoản này");
    }

    private ConnectionRequest request() {
        ConnectionRequest request = new ConnectionRequest();
        request.setName("Local Store");
        request.setDbType("mysql");
        request.setHost("localhost");
        request.setPort(3309);
        request.setDatabaseName("sample_store");
        request.setUsername("aidb_reader");
        request.setPassword("mysql-secret");
        return request;
    }

    private ConnectionTestResponse success() {
        return new ConnectionTestResponse(true, true, "CONNECTION_OK", "Kết nối thành công", 20, "8.0");
    }
}
