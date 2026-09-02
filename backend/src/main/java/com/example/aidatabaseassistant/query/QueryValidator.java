package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class QueryValidator {

    public void validate(String sql, DatabaseSchema schema) {
        rejectBlockComments(sql);
        rejectFileAccessAttempts(sql);

        Statement statement = parse(sql);
        checkReadOnly(statement);
        checkSchemaMatch(statement, schema);
    }

    private Statement parse(String sql) {
        try {
            net.sf.jsqlparser.statement.Statements statements =
                    CCJSqlParserUtil.parseStatements(sql);

            if (statements.getStatements().size() != 1) {
                throw new IllegalArgumentException(
                        "Chỉ cho phép đúng 1 câu lệnh SQL, không được nối nhiều câu lệnh bằng dấu ';'"
                );
            }

            return statements.getStatements().get(0);

        } catch (IllegalArgumentException e) {
            throw e;

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "SQL không hợp lệ về cú pháp: " + e.getMessage()
            );
        }
    }

    public void checkReadOnly(Statement statement) {
        if (!(statement instanceof Select)) {
            throw new ReadOnlyViolationException(
                    "Chỉ cho phép câu lệnh SELECT. Các câu lệnh INSERT, UPDATE, DELETE, " +
                            "DROP, ALTER, TRUNCATE, CREATE, RENAME, USE đều bị chặn."
            );
        }
    }

    public void checkSchemaMatch(Statement statement, DatabaseSchema schema) {
        Set<String> knownTables = new HashSet<>();

        for (TableMetadata table : schema.getTables()) {
            knownTables.add(table.getName().toLowerCase());
        }

        TablesNamesFinder finder = new TablesNamesFinder();

        for (String tableName : finder.getTableList(statement)) {
            String clean = tableName
                    .replaceAll("[`\"\\[\\]]", "")
                    .toLowerCase();

            if (!knownTables.contains(clean)) {
                throw new IllegalArgumentException(
                        "Bảng không tồn tại trong schema: " + tableName
                );
            }
        }
    }

    private void rejectBlockComments(String sql) {

        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException(
                    "SQL không được để trống"
            );
        }

        if (sql.contains("/*") || sql.contains("*/")) {
            throw new IllegalArgumentException(
                    "SQL không được chứa block comment /* ... */"
            );
        }
    }

    /**
     * CHỐNG GHI/ĐỌC FILE TRÊN SERVER MYSQL (P0):
     *
     * "SELECT ... INTO OUTFILE '/path'" và "SELECT ... INTO DUMPFILE '/path'"
     * VẪN LÀ 1 câu lệnh kiểu SELECT trong JSqlParser, nên checkReadOnly()
     * (chỉ kiểm tra statement instanceof Select) KHÔNG chặn được - nếu tài
     * khoản DB đang dùng có quyền FILE, AI có thể bị dẫn dụ (qua câu hỏi
     * tự nhiên hoặc prompt injection trong dữ liệu) sinh ra câu SQL ghi 1
     * file bất kỳ lên ổ đĩa server (ví dụ ghi webshell). Tương tự,
     * "LOAD_FILE('/etc/passwd')" là 1 hàm dùng được ngay bên trong SELECT
     * để ĐỌC file bất kỳ trên server, cũng không phải DDL/DML nên không
     * bị checkReadOnly() chặn.
     *
     * Chặn bằng kiểm tra chuỗi (case-insensitive, cho phép khoảng trắng/
     * xuống dòng linh hoạt giữa các từ khóa) TRƯỚC khi parse, cùng cách
     * tiếp cận với rejectBlockComments() ở trên - đơn giản, không phụ
     * thuộc phiên bản JSqlParser cụ thể, và không có lý do hợp lệ nào để
     * 1 câu hỏi NL2SQL cần dùng các cú pháp này.
     */
    private static final java.util.regex.Pattern FILE_ACCESS_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\bINTO\\s+(OUTFILE|DUMPFILE)\\b|\\bLOAD_FILE\\s*\\(",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

    private void rejectFileAccessAttempts(String sql) {

        if (FILE_ACCESS_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException(
                    "SQL không được chứa lệnh đọc/ghi file trên server "
                            + "(INTO OUTFILE, INTO DUMPFILE, LOAD_FILE)"
            );
        }
    }
}