package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.config.TargetDatabaseProperties;
import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionTestResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final DatabaseConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final EncryptionUtil encryptionUtil;
    private final TargetDatabaseClient targetDatabaseClient;
    private final ConnectionTestRateLimiter rateLimiter;
    private final TargetDatabaseProperties properties;

    public ConnectionTestResponse testConnection(String appUsername, ConnectionRequest request) {
        rateLimiter.check(appUsername);
        return targetDatabaseClient.test(credentials(request));
    }

    public ConnectionResponse saveConnection(String appUsername, ConnectionRequest request) {
        User user = findUser(appUsername);
        ensureConnectionSlotAvailable(user);
        rateLimiter.check(appUsername);
        ConnectionTestResponse testResult = targetDatabaseClient.test(credentials(request));
        requireSuccessfulReadOnlyTest(testResult);

        DatabaseConnection connection = DatabaseConnection.builder()
                .user(user)
                .name(request.getName().trim())
                .dbType("mysql")
                .host(request.getHost().trim().toLowerCase(Locale.ROOT))
                .port(request.getPort())
                .databaseName(request.getDatabaseName().trim())
                .username(request.getUsername().trim())
                .encryptedPassword(encryptionUtil.encrypt(request.getPassword()))
                .active(true)
                .lastTestedAt(LocalDateTime.now())
                .lastTestSuccessful(true)
                .build();

        return toResponse(connectionRepository.save(connection));
    }

    public List<ConnectionResponse> getConnectionsByUser(String appUsername) {
        return connectionRepository.findAllByUserUsernameIgnoreCaseOrderByCreatedAtDesc(appUsername)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ConnectionResponse getConnection(String appUsername, Long connectionId) {
        return toResponse(getOwnedConnection(appUsername, connectionId));
    }

    public ConnectionResponse updateConnection(
            String appUsername, Long connectionId, ConnectionUpdateRequest request) {
        DatabaseConnection connection = getOwnedConnection(appUsername, connectionId);
        String password = request.getPassword() == null || request.getPassword().isBlank()
                ? encryptionUtil.decrypt(connection.getEncryptedPassword())
                : request.getPassword();

        TargetDatabaseCredentials credentials = new TargetDatabaseCredentials(
                request.getHost().trim(), request.getPort(), request.getDatabaseName().trim(),
                request.getUsername().trim(), password);
        rateLimiter.check(appUsername);
        ConnectionTestResponse testResult = targetDatabaseClient.test(credentials);
        requireSuccessfulReadOnlyTest(testResult);

        if (!Boolean.TRUE.equals(connection.getActive())) {
            ensureConnectionSlotAvailable(appUsername);
        }
        connection.setName(request.getName().trim());
        connection.setHost(request.getHost().trim().toLowerCase(Locale.ROOT));
        connection.setPort(request.getPort());
        connection.setDatabaseName(request.getDatabaseName().trim());
        connection.setUsername(request.getUsername().trim());
        connection.setEncryptedPassword(encryptionUtil.encrypt(password));
        connection.setActive(true);
        connection.setLastTestedAt(LocalDateTime.now());
        connection.setLastTestSuccessful(true);

        return toResponse(connectionRepository.save(connection));
    }

    public ConnectionTestResponse reconnect(String appUsername, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(appUsername, connectionId);
        if (!Boolean.TRUE.equals(connection.getActive())) {
            ensureConnectionSlotAvailable(appUsername);
        }

        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());
        rateLimiter.check(appUsername);
        ConnectionTestResponse result = targetDatabaseClient.test(credentials(connection, rawPassword));
        connection.setLastTestedAt(LocalDateTime.now());
        connection.setLastTestSuccessful(result.successful());
        if (result.successful() && result.readOnlyVerified()) {
            connection.setActive(true);
            if (encryptionUtil.isLegacy(connection.getEncryptedPassword())) {
                connection.setEncryptedPassword(encryptionUtil.encrypt(rawPassword));
            }
        }
        connectionRepository.save(connection);
        return result;
    }

    public void disconnect(String appUsername, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(appUsername, connectionId);
        connection.setActive(false);
        connectionRepository.save(connection);
    }

    public DatabaseConnection getOwnedActiveConnection(String appUsername, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(appUsername, connectionId);
        if (!Boolean.TRUE.equals(connection.getActive())) {
            throw new IllegalArgumentException("Connection đang ở trạng thái ngắt kết nối");
        }
        return connection;
    }

    private User findUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản"));
    }

    private DatabaseConnection getOwnedConnection(String username, Long connectionId) {
        return connectionRepository.findByIdAndUserUsernameIgnoreCase(connectionId, username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection thuộc tài khoản này"));
    }

    private void ensureConnectionSlotAvailable(User user) {
        long activeConnections = connectionRepository.countByUserIdAndActiveTrue(user.getId());
        if (activeConnections >= properties.getMaxConnectionsPerUser()) {
            throw new IllegalArgumentException(
                    "Mỗi tài khoản chỉ được có tối đa " + properties.getMaxConnectionsPerUser()
                            + " connection đang hoạt động"
            );
        }
    }

    private void ensureConnectionSlotAvailable(String username) {
        long activeConnections = connectionRepository.countByUserUsernameIgnoreCaseAndActiveTrue(username);
        if (activeConnections >= properties.getMaxConnectionsPerUser()) {
            throw new IllegalArgumentException(
                    "Mỗi tài khoản chỉ được có tối đa " + properties.getMaxConnectionsPerUser()
                            + " connection đang hoạt động"
            );
        }
    }

    private void requireSuccessfulReadOnlyTest(ConnectionTestResponse result) {
        if (!result.successful() || !result.readOnlyVerified()) {
            throw new TargetDatabaseConnectionException(result.code(), result.message());
        }
    }

    private TargetDatabaseCredentials credentials(ConnectionRequest request) {
        return new TargetDatabaseCredentials(
                request.getHost().trim(), request.getPort(), request.getDatabaseName().trim(),
                request.getUsername().trim(), request.getPassword());
    }

    private TargetDatabaseCredentials credentials(DatabaseConnection connection, String rawPassword) {
        return new TargetDatabaseCredentials(
                connection.getHost(), connection.getPort(), connection.getDatabaseName(),
                connection.getUsername(), rawPassword);
    }

    private ConnectionResponse toResponse(DatabaseConnection connection) {
        return new ConnectionResponse(
                connection.getId(), connection.getName(), connection.getDbType(), connection.getHost(),
                connection.getPort(), connection.getDatabaseName(), connection.getUsername(),
                Boolean.TRUE.equals(connection.getActive()), connection.getLastTestedAt(),
                connection.getLastTestSuccessful(), connection.getCreatedAt(), connection.getUpdatedAt()
        );
    }
}
