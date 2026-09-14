package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.aidatabaseassistant.security.SsrfProtection;
import com.example.aidatabaseassistant.db.TargetDatabaseClient;
import com.example.aidatabaseassistant.dto.ConnectionTestResult;
import com.example.aidatabaseassistant.security.ConnectionAccessGuard;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private final ConnectionAccessGuard connectionAccessGuard;
    private final DatabaseSchemaRepository databaseSchemaRepository;
    @Value("${connection.max-per-user:20}")
    private int maxConnectionsPerUser = 20;

    @PostConstruct
    public void debugConfig() {
        log.debug("maxConnectionsPerUser = {}", maxConnectionsPerUser);
    }
    // Danh sach dbType duoc JdbcUrlBuilder ho tro cho connection nhap tay
    // (KHONG bao gom "excel" - excel di qua saveExcelConnection() rieng,
    // khong nhan dbType tu request).
    private static final List<String> SUPPORTED_MANUAL_DB_TYPES =
            List.of("mysql", "postgres", "postgresql");

    public ConnectionTestResult testConnection(ConnectionRequest request) {
        long startTime = System.currentTimeMillis();

        // KHÔNG bọc try/catch quanh lời gọi này: SQLException (sai host/port/
        // credentials) đã được TargetDatabaseClient.testConnection() bắt và
        // trả về false bên trong rồi. IllegalArgumentException (dbType không
        // được hỗ trợ, vd "oracle") phải tiếp tục ném ra ngoài để
        // GlobalExceptionHandler trả 400 kèm message rõ ràng - nếu nuốt vào
        // đây thành "successful: false" thì người dùng không biết lý do thật
        // là do gõ sai dbType chứ không phải do sai mật khẩu.
        boolean successful = targetDatabaseClient.testConnection(
                request.getDbType(),
                request.getHost(),
                request.getPort(),
                request.getDatabaseName(),
                request.getUsername(),
                request.getPassword(),
                request.isSslEnabled()
        );

        long durationMs = System.currentTimeMillis() - startTime;

        String code = successful ? "CONNECTION_OK" : "CONNECTION_FAILED";
        String message = successful
                ? "Kết nối database thành công."
                : "Không thể kết nối tới database. Vui lòng kiểm tra lại host, port, tên đăng nhập và mật khẩu.";

        // readOnlyVerified và serverVersion: giữ giống hệt reconnect() hiện
        // tại (false / null) vì BE hiện chưa thật sự verify quyền chỉ đọc hay
        // đọc server version ở bước test - tránh báo sai thông tin chưa có.
        return new ConnectionTestResult(
                successful,
                false,
                code,
                message,
                durationMs,
                null
        );
    }

    /**
     * Validate dbType SỚM, ngay khi lưu connection.
     *
     * Trước đây saveConnection() lưu thẳng dbType từ request mà không
     * kiểm tra gì - nếu người dùng gõ nhầm ("postgress", "oracle"...),
     * lỗi "Loại database chưa được hỗ trợ" chỉ lộ ra SAU đó, ở bước
     * discoverSchema()/testConnection()/query, gây khó hiểu vì connection
     * đã "lưu thành công" nhưng dùng không được.
     */
    private void validateDbType(String dbType) {
        if (dbType == null
                || SUPPORTED_MANUAL_DB_TYPES.stream()
                .noneMatch(dbType::equalsIgnoreCase)) {

            throw new IllegalArgumentException(
                    "Loại database chưa được hỗ trợ: " + dbType
                            + ". Chỉ hỗ trợ: mysql, postgres/postgresql."
            );
        }
    }

    public ConnectionResponse saveConnection(String username, ConnectionRequest request) {
        User user = connectionAccessGuard.requireUser(username);

        validateDbType(request.getDbType());

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
                .sslEnabled(
                        "postgres".equalsIgnoreCase(request.getDbType())
                                || "postgresql".equalsIgnoreCase(request.getDbType())
                                ? request.isSslEnabled()
                                : false
                )
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

        User user = connectionAccessGuard.requireUser(username);

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
        User user = connectionAccessGuard.requireUser(username);

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
                connection.isSslEnabled(),
                connection.getHost(),
                connection.getPort(),
                connection.getDatabaseName(),
                connection.getUsername(),
                connection.isActive(),
                connection.getLastTestedAt(),
                connection.getLastTestSuccessful(),
                connection.getCreatedAt(),
                connection.getUpdatedAt()
        );
    }

    public ConnectionResponse getConnection(String username, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);
        return toResponse(connection);
    }

    public ConnectionResponse updateConnection(String username, Long connectionId, ConnectionUpdateRequest request) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);

        boolean isExcel = "excel".equalsIgnoreCase(connection.getDbType());

        if (!isExcel) {
            ssrfProtection.validateHost(request.getHost());
        }

        connection.setName(request.getName());

        if (!isExcel) {
            connection.setHost(request.getHost());
            connection.setPort(request.getPort());
            connection.setDatabaseName(request.getDatabaseName());
            connection.setUsername(request.getUsername());
            connection.setSslEnabled(
                    "postgres".equalsIgnoreCase(connection.getDbType())
                            || "postgresql".equalsIgnoreCase(connection.getDbType())
                            ? request.isSslEnabled()
                            : false
            );

            if (request.getPassword() != null && !request.getPassword().isBlank()) {
                connection.setEncryptedPassword(encryptionUtil.encrypt(request.getPassword()));
            }
        }

        connectionRepository.save(connection);
        return toResponse(connection);
    }

    public ConnectionTestResult reconnect(String username, Long connectionId) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);

        // SSRF protection:
        // Phải kiểm tra host trước khi thực hiện bất kỳ kết nối mạng nào.
        //
        // FIX (audit Excel/DuckDB): với dbType="excel", host luôn là
        // chuỗi giả "local-file" (không phải hostname thật - xem
        // saveExcelConnection()), KHÔNG mở bất kỳ socket mạng nào nên
        // không có rủi ro SSRF. Trước đây gọi validateHost() vô điều
        // kiện khiến InetAddress.getAllByName("local-file") luôn ném
        // UnknownHostException -> reconnect() cho MỌI connection Excel
        // đều báo lỗi 400 "Không thể phân giải host", dù file .duckdb
        // hoàn toàn bình thường.
        if (!"excel".equalsIgnoreCase(connection.getDbType())) {
            ssrfProtection.validateHost(connection.getHost());
        }
        long startTime = System.currentTimeMillis();

        String rawPassword = encryptionUtil.decrypt(
                connection.getEncryptedPassword()
        );

        boolean successful;

        try {
            successful = targetDatabaseClient.testConnection(
                    connection.getDbType(),
                    connection.getHost(),
                    connection.getPort(),
                    connection.getDatabaseName(),
                    connection.getUsername(),
                    rawPassword,
                    connection.isSslEnabled()
            );
        } catch (Exception e) {
            successful = false;
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // Cập nhật trạng thái connection
        connection.setLastTestedAt(java.time.LocalDateTime.now());
        connection.setLastTestSuccessful(successful);
        connection.setActive(successful);

        connectionRepository.save(connection);

        String code = successful
                ? "CONNECTION_OK"
                : "CONNECTION_FAILED";

        String message = successful
                ? "Kết nối database thành công."
                : "Không thể kết nối tới database.";

        return new ConnectionTestResult(
                successful,
                false,
                code,
                message,
                durationMs,
                null
        );
    }

    @Transactional
    public void disconnect(String username, Long connectionId) {
        DatabaseConnection connection =
                getOwnedConnection(username, connectionId);

        if ("excel".equalsIgnoreCase(connection.getDbType())) {
            excelIngestionService.deleteDuckDbFile(
                    connection.getDatabaseName()
            );
        }

        // Xoa schema da dong bo (neu co) truoc de tranh loi
        // foreign key constraint khi xoa connection.
        databaseSchemaRepository.deleteByConnectionId(connectionId);

        connectionRepository.delete(connection);
    }

    private DatabaseConnection getOwnedConnection(String username, Long connectionId) {
        return connectionAccessGuard.requireOwnedConnection(username, connectionId);
    }
}