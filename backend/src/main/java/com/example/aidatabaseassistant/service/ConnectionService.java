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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.aidatabaseassistant.security.SsrfProtection;
import com.example.aidatabaseassistant.db.TargetDatabaseClient;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final DatabaseConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final EncryptionUtil encryptionUtil;
    // Van giu ssrfProtection rieng: saveConnection()/updateConnection() can
    // validate host TRUOC khi luu vao DB (chua he mo ket noi that o buoc
    // do). Viec MO ket noi that (testConnection/reconnect) gio di qua
    // targetDatabaseClient - noi DUY NHAT mo JDBC Connection toi DB user.
    private final SsrfProtection ssrfProtection;
    private final TargetDatabaseClient targetDatabaseClient;
    private final ExcelIngestionService excelIngestionService;
    // Khong khai bao "final" vi day la field duoc inject bang @Value (field
    // injection), tach biet voi cac dependency con lai dang duoc constructor-inject
    // qua @RequiredArgsConstructor. Neu de "final" thi Lombok se doi hoi truyen
    // gia tri nay qua constructor -> pha vo constructor 4-tham-so hien tai dang
    // duoc goi truc tiep trong ConnectionServiceTest.
    @Value("${connection.max-per-user:5}")
    private int maxConnectionsPerUser = 5;

    public boolean testConnection(ConnectionRequest request) {
        return targetDatabaseClient.testConnection(
                request.getDbType(),
                request.getHost(),
                request.getPort(),
                request.getDatabaseName(),
                request.getUsername(),
                request.getPassword()
        );
    }

    public ConnectionResponse saveConnection(String username, ConnectionRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        // SSRF guard: truoc day chi testConnection() goi validateHost(), nen
        // saveConnection() co the luu thang mot host noi bo (vi du 127.0.0.1,
        // 169.254.169.254 - metadata endpoint cua cloud...) ma khong bi chan,
        // roi sau nay reconnect()/updateConnection() van vo tu ket noi toi do.
        ssrfProtection.validateHost(request.getHost());

        long currentCount = connectionRepository.countByUserId(user.getId());
        if (currentCount >= maxConnectionsPerUser) {
            throw new IllegalArgumentException(
                    "Bạn đã đạt giới hạn tối đa " + maxConnectionsPerUser + " kết nối database. "
                            + "Vui lòng xóa bớt kết nối cũ trước khi thêm mới.");
        }

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

    /**
     * Tao 1 DatabaseConnection tu file Excel upload len. Khac voi
     * saveConnection() (nhan ConnectionRequest voi host/port/username/
     * password bat buoc), o day KHONG co cac gia tri do - dung placeholder
     * co dinh, va cot databaseName duoc tai su dung de luu DUONG DAN file
     * .duckdb (xem TargetDatabaseClient.buildJdbcUrl).
     */
    public ConnectionResponse saveExcelConnection(
            String username,
            org.springframework.web.multipart.MultipartFile file,
            String name) {

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        long currentCount = connectionRepository.countByUserId(user.getId());
        if (currentCount >= maxConnectionsPerUser) {
            throw new IllegalArgumentException(
                    "Bạn đã đạt giới hạn tối đa " + maxConnectionsPerUser + " kết nối database. "
                            + "Vui lòng xóa bớt kết nối cũ trước khi thêm mới.");
        }

        String duckDbFilePath = excelIngestionService.ingest(file, user.getId());

        DatabaseConnection connection = DatabaseConnection.builder()
                .user(user)
                .name(name)
                .dbType("excel")
                .host("local-file")
                .port(0)
                .databaseName(duckDbFilePath)
                .username("excel-file")
                .encryptedPassword(encryptionUtil.encrypt("-"))
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

        // Ownership check (getOwnedConnection) phai chay TRUOC de IDOR test
        // (updateConnection_shouldThrow_whenRequestedByNonOwner_IDOR) khong bi
        // anh huong boi loi validate host. Sau khi xac nhan la chu so huu, host
        // moi van phai duoc kiem tra SSRF vi user co the doi host sang dia chi
        // noi bo trong luc update.
        ssrfProtection.validateHost(request.getHost());

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

        return targetDatabaseClient.testConnection(
                connection.getDbType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDatabaseName(),
                connection.getUsername(),
                rawPassword
        );
    }

    public void disconnect(String username, Long connectionId) {
        DatabaseConnection connection =
                getOwnedConnection(username, connectionId);

        if ("excel".equalsIgnoreCase(connection.getDbType())) {
            excelIngestionService.deleteDuckDbFile(
                    connection.getDatabaseName()
            );
        }

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