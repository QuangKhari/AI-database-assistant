package com.example.aidatabaseassistant.db;

import com.example.aidatabaseassistant.security.SsrfProtection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Diem MO KET NOI JDBC DUY NHAT toi database DICH cua nguoi dung (khac voi
 * DB noi bo cua app - ai_db_assistant_system - van dung Spring
 * DataSource/JPA nhu binh thuong, KHONG di qua class nay).
 *
 * TRUOC DAY: QueryExecutor, ConnectionService, SchemaDiscoveryService moi
 * noi tu xay dung JDBC URL + tu goi DriverManager.getConnection() rieng ->
 * 3 noi code gan giong het nhau, de bi lech nhau khi sua 1 noi ma quen sua
 * 2 noi con lai (vi du: doi timeout, them SSRF check, them ho tro dbType
 * moi cho multi-DB sau nay).
 *
 * BAY GIO: gom lai 1 noi duy nhat. Moi luong mo ket noi toi DB cua user
 * (execute query, EXPLAIN, test connection, reconnect, discover schema)
 * DEU di qua class nay -> SSRF check va timeout LUON duoc ap dung nhat
 * quan, khong con nguy co "quen" o 1 trong 3 noi nhu truoc.
 */
@Component
@RequiredArgsConstructor
public class TargetDatabaseClient {

    private final SsrfProtection ssrfProtection;

    // Gioi han thoi gian THIET LAP ket noi va thoi gian CHO PHAN HOI tu
    // socket (mili giay). Neu host/port khong phan hoi (connection string
    // sai, firewall chan...), tranh treo vo thoi han o buoc ket noi.
    // Dung CHUNG 1 cap gia tri cho ca 3 noi truoc day dang dung 3 cap khac
    // nhau (QueryExecutor: 5000/15000, ConnectionService: 5000/10000,
    // SchemaDiscoveryService: 5000/15000) - chon cap "an toan nhat"
    // (socketTimeout dai hon) de khong lam gian doan discover schema tren
    // database lon.
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 15000;

    /**
     * Xay dung JDBC URL cho database DICH cua user. Diem mo rong DUY NHAT
     * khi them ho tro PostgreSQL/SQLite (Giai doan multi-DB) - chi can
     * them 1 nhanh if/else o day, khong phai sua lai nhieu noi khac.
     */
    public String buildJdbcUrl(String dbType, String host, Integer port, String databaseName) {
        if (!"mysql".equalsIgnoreCase(dbType)) {
            throw new IllegalArgumentException("Loại database chưa được hỗ trợ: " + dbType);
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                + "?connectTimeout=" + CONNECT_TIMEOUT_MS
                + "&socketTimeout=" + SOCKET_TIMEOUT_MS;
    }

    /**
     * Mo 1 JDBC Connection MOI toi database dich cua user (mac dinh MySQL -
     * giu tuong thich voi cac cho truoc day khong truyen dbType).
     *
     * Caller PHAI dung try-with-resources de dam bao connection duoc dong
     * dung cach - class nay khong tu dong dong connection thay caller.
     */
    public Connection openConnection(String host, Integer port, String databaseName,
                                     String username, String password) throws SQLException {
        return openConnection("mysql", host, port, databaseName, username, password);
    }

    /**
     * Mo 1 JDBC Connection MOI toi database dich cua user.
     *
     * QUAN TRONG: luon goi ssrfProtection.validateHost() TRUOC khi mo ket
     * noi, ke ca khi caller da tung goi validateHost() truoc do o 1 buoc
     * khac (vi du luc save connection) - vi host co the da bi doi ke tu
     * lan check truoc, va de dam bao KHONG co duong nao mo duoc ket noi
     * ma bo qua SSRF check.
     */
    public Connection openConnection(String dbType, String host, Integer port,
                                     String databaseName, String username, String password)
            throws SQLException {
        ssrfProtection.validateHost(host);
        String url = buildJdbcUrl(dbType, host, port, databaseName);
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * Mo ket noi, kiem tra con song (isValid) roi dong lai ngay - dung cho
     * nut "Test Connection" va cho reconnect(). Nuot SQLException va tra
     * ve false (khong throw ra ngoai) - giu dung hanh vi cu cua
     * ConnectionService truoc khi gom code. IllegalArgumentException (SSRF
     * bi chan, dbType khong ho tro) VAN duoc nem ra ngoai binh thuong,
     * khong bi nuot - giong hanh vi cu.
     */
    public boolean testConnection(String dbType, String host, Integer port,
                                  String databaseName, String username, String password) {
        try (Connection conn = openConnection(dbType, host, port, databaseName, username, password)) {
            return conn.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }
}