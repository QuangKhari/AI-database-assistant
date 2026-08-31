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
}
