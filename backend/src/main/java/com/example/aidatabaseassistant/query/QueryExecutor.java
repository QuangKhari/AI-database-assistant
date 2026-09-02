package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.dto.QueryResultDto;
import org.springframework.stereotype.Component;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import com.example.aidatabaseassistant.db.TargetDatabaseClient;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class QueryExecutor {

    // TRUOC DAY: tu goi ssrfProtection.validateHost() + tu build JDBC URL +
    // tu goi DriverManager.getConnection() ngay trong class nay (trung lap
    // voi ConnectionService/SchemaDiscoveryService). BAY GIO: gom qua
    // TargetDatabaseClient - noi DUY NHAT mo ket noi JDBC toi DB cua user.
    private final TargetDatabaseClient targetDatabaseClient;
    private static final int MAX_ROWS = 500;

    // Gioi han thoi gian THUC THI cau query tren DB (giay). Neu AI sinh ra 1 cau
    // query nang (vi du JOIN nhieu bang tren dataset lon, hoac thieu index), ket
    // noi se khong bi treo vo thoi han - JDBC driver se huy query va nem loi ro rang.
    private static final int QUERY_TIMEOUT_SECONDS = 10;



    public QueryResultDto executeQuery(String host, Integer port, String databaseName,
                                       String username, String password, String sql) {
        long start = System.currentTimeMillis();

        try (Connection conn = targetDatabaseClient.openConnection(host, port, databaseName, username, password);
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
            return new QueryResultDto(List.of(), List.of(), executionTime, 0, buildSafeDatabaseErrorMessage(e));
        }
    }

    /**
     * Chay EXPLAIN that + doc index that cua cac bang lien quan, dung cho
     * tinh nang SQL Optimization. Dung CHUNG mot Connection cho ca 2 buoc
     * de giam so lan mo ket noi toi DB cua nguoi dung.
     */
    public SqlOptimizationRawData collectOptimizationData(String host, Integer port, String databaseName,
                                                          String username, String password, String sql) {

        try (Connection conn = targetDatabaseClient.openConnection(host, port, databaseName, username, password)) {

            List<Map<String, Object>> explainRows = runExplain(conn, sql);

            // QUAN TRONG: KHONG duoc lay ten bang tu cot "table" cua EXPLAIN
            // de tra index - MySQL tra ve ALIAS trong cot do neu cau SQL co
            // dat alias (vi du "c" thay vi "customers"), se khien
            // getIndexInfo() tra cuu nham mot bang khong ton tai va luon
            // tra ve rong => goi y index sai (tuong chua co index trong khi
            // thuc ra da co). Phai lay TEN BANG THAT truc tiep tu cau SQL
            // bang TablesNamesFinder (giong cach QueryValidator dang lam).
            Set<String> realTableNames = extractRealTableNames(sql);

            Map<String, Set<String>> indexedColumns = fetchIndexedColumns(conn, databaseName, realTableNames);

            return new SqlOptimizationRawData(explainRows, indexedColumns, null);

        } catch (Exception e) {
            return new SqlOptimizationRawData(
                    List.of(),
                    Map.of(),
                    buildSafeDatabaseErrorMessage(e)
            );
        }
    }

    private Set<String> extractRealTableNames(String sql) {
        try {
            var statement = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder finder = new TablesNamesFinder();
            Set<String> names = new LinkedHashSet<>();
            for (String tableName : finder.getTableList(statement)) {
                names.add(tableName.replaceAll("[`\"\\[\\]]", ""));
            }
            return names;
        } catch (Exception e) {
            // Khong parse duoc (rat hiem vi SQL da qua QueryValidator truoc
            // do) -> tra ve rong, chi mat phan goi y index, khong lam vo
            // hieu EXPLAIN.
            return Set.of();
        }
    }

    private List<Map<String, Object>> runExplain(Connection conn, String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);

            ResultSet rs = stmt.executeQuery("EXPLAIN " + sql);
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        }

        return rows;
    }

    // Doc index THAT tu MySQL bang DatabaseMetaData thay vi doan mo hinh -
    // day la diem khac biet quan trong giup goi y index dang tin cay: khong
    // bao gio goi y "them index" cho cot da co index san.
    private Map<String, Set<String>> fetchIndexedColumns(Connection conn, String databaseName,
                                                         Set<String> tableNames) throws SQLException {
        Map<String, Set<String>> result = new HashMap<>();
        DatabaseMetaData metaData = conn.getMetaData();

        for (String table : tableNames) {
            Set<String> columns = new HashSet<>();

            try (ResultSet rs = metaData.getIndexInfo(databaseName, null, table, false, false)) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    if (columnName != null) {
                        columns.add(columnName.toLowerCase(Locale.ROOT));
                    }
                }
            }

            result.put(table.toLowerCase(Locale.ROOT), columns);
        }

        return result;
    }

    private String buildSafeDatabaseErrorMessage(Exception e) {

        if (e instanceof java.sql.SQLException) {

            String sqlState = ((SQLException) e).getSQLState();

            if (sqlState != null && sqlState.startsWith("08")) {
                return "Không thể kết nối tới cơ sở dữ liệu. "
                        + "Vui lòng kiểm tra host, port hoặc trạng thái của database.";
            }

            return "Không thể thực hiện truy vấn trên cơ sở dữ liệu.";
        }

        return "Không thể thực hiện truy vấn.";
    }
}