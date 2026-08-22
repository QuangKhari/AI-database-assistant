package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final DatabaseConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final EncryptionUtil encryptionUtil;

    public boolean testConnection(ConnectionRequest request) {
        String url = buildJdbcUrl(request.getDbType(), request.getHost(), request.getPort(), request.getDatabaseName());

        try (Connection conn = DriverManager.getConnection(url, request.getUsername(), request.getPassword())) {
            return conn.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    public ConnectionResponse saveConnection(String username, ConnectionRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = DatabaseConnection.builder()
                .user(user)
                .name(request.getName())
                .dbType(request.getDbType())
                .host(request.getHost())
                .port(request.getPort())
                .databaseName(request.getDatabaseName())
                .username(request.getUsername())
                .encryptedPassword(encryptionUtil.encrypt(request.getPassword()))
                .build();

        connectionRepository.save(connection);
        return toResponse(connection);
    }

    public List<ConnectionResponse> getConnectionsByUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        return connectionRepository.findByUserId(user.getId())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }



    private String buildJdbcUrl(String dbType, String host, Integer port, String databaseName) {
        if ("mysql".equalsIgnoreCase(dbType)) {
            return "jdbc:mysql://" + host + ":" + port + "/" + databaseName;
        }
        throw new IllegalArgumentException("Loại database chưa được hỗ trợ: " + dbType);
    }

    private ConnectionResponse toResponse(DatabaseConnection connection) {
        return new ConnectionResponse(
                connection.getId(),
                connection.getName(),
                connection.getDbType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDatabaseName(),
                connection.getUsername()
        );
    }

    public ConnectionResponse getConnection(String username, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);
        return toResponse(connection);
    }

    public ConnectionResponse updateConnection(String username, Long connectionId, ConnectionUpdateRequest request) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);

        connection.setName(request.getName());
        connection.setHost(request.getHost());
        connection.setPort(request.getPort());
        connection.setDatabaseName(request.getDatabaseName());
        connection.setUsername(request.getUsername());

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            connection.setEncryptedPassword(encryptionUtil.encrypt(request.getPassword()));
        }

        connectionRepository.save(connection);
        return toResponse(connection);
    }

    public boolean reconnect(String username, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);
        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        String url = buildJdbcUrl(connection.getDbType(), connection.getHost(),
                connection.getPort(), connection.getDatabaseName());

        try (Connection conn = DriverManager.getConnection(url, connection.getUsername(), rawPassword)) {
            return conn.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    public void disconnect(String username, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);
        connectionRepository.delete(connection);
    }

    private DatabaseConnection getOwnedConnection(String username, Long connectionId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
        }

        return connection;
    }
}