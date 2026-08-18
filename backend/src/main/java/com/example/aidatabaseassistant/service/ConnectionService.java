package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
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

    public void disconnect(Long connectionId) {
        connectionRepository.deleteById(connectionId);
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
}