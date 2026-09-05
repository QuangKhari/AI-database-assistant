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
import java.util.regex.Pattern;

@Component
public class QueryValidator {

    private static final Pattern DANGEROUS_SELECT_PATTERN = Pattern.compile(
            "(?is)\\b(?:INTO\\s+(?:OUTFILE|DUMPFILE)|FOR\\s+UPDATE|LOCK\\s+IN\\s+SHARE\\s+MODE|"
                    + "LOAD_FILE\\s*\\(|SLEEP\\s*\\(|BENCHMARK\\s*\\()"
    );

    public void validate(String sql, DatabaseSchema schema) {
        Statement statement = parse(sql);
        checkReadOnly(statement);
        checkDangerousSelectFeatures(sql);
        checkSchemaMatch(statement, schema);
    }

    private Statement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL không được để trống");
        }
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
            if (statements.size() != 1) {
                throw new IllegalArgumentException("Chỉ được thực thi một câu lệnh SQL mỗi lần");
            }
            return statements.get(0);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL không hợp lệ về cú pháp: " + e.getMessage());
        }
    }

    private void checkDangerousSelectFeatures(String sql) {
        if (DANGEROUS_SELECT_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException(
                    "SQL chứa chức năng không an toàn hoặc tiêu tốn tài nguyên và không được phép thực thi");
        }
    }

    public void checkReadOnly(Statement statement) {
        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException(
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
            String clean = tableName.replaceAll("[`\"\\[\\]]", "").toLowerCase();
            if (clean.contains(".")) {
                String[] qualifiedName = clean.split("\\.");
                String databaseName = qualifiedName[qualifiedName.length - 2];
                if (!databaseName.equalsIgnoreCase(schema.getDatabaseName())) {
                    throw new IllegalArgumentException("Không được truy vấn database khác: " + tableName);
                }
                clean = qualifiedName[qualifiedName.length - 1];
            }
            if (!knownTables.contains(clean)) {
                throw new IllegalArgumentException("Bảng không tồn tại trong schema: " + tableName);
            }
        }
    }
}
