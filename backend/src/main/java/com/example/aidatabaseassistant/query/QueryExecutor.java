package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.dto.QueryResultDto;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QueryExecutor {

    private static final int MAX_ROWS = 500;

    // Gioi han thoi gian THUC THI cau query tren DB (giay). Neu AI sinh ra 1 cau
    // query nang (vi du JOIN nhieu bang tren dataset lon, hoac thieu index), ket
    // noi se khong bi treo vo thoi han - JDBC driver se huy query va nem loi ro rang.
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    // Gioi han thoi gian THIET LAP ket noi va thoi gian CHO PHAN HOI tu socket
    // (mili giay). Neu host/port khong phan hoi (vi du connection string sai,
    // firewall chan), tranh treo vo thoi han o buoc ket noi.
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 15000;

    public QueryResultDto executeQuery(String host, Integer port, String databaseName,
                                       String username, String password, String sql) {
        String url = "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                + "?connectTimeout=" + CONNECT_TIMEOUT_MS
                + "&socketTimeout=" + SOCKET_TIMEOUT_MS;
        long start = System.currentTimeMillis();

        try (Connection conn = DriverManager.getConnection(url, username, password);
             Statement stmt = conn.createStatement()) {

            stmt.setMaxRows(MAX_ROWS);
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ResultSet rs = stmt.executeQuery(sql);

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columns.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }

            long executionTime = System.currentTimeMillis() - start;
            return new QueryResultDto(columns, rows, executionTime, rows.size(), null);

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - start;
            return new QueryResultDto(List.of(), List.of(), executionTime, 0, e.getMessage());
        }
    }
}