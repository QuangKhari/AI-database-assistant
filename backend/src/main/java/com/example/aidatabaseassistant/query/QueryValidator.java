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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QueryValidator {

    public void validate(String sql, DatabaseSchema schema) {
        rejectBlockComments(sql);
        rejectFileAccessAttempts(sql);

        Statement statement = parse(sql);
        checkReadOnly(statement);
        checkSchemaMatch(statement, schema);
        checkDuplicateAliases(sql);
    }

    private Statement parse(String sql) {
        try {
            net.sf.jsqlparser.statement.Statements statements = CCJSqlParserUtil.parseStatements(sql);

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

    private static final java.util.regex.Pattern FILE_ACCESS_PATTERN =
            java.util.regex.Pattern.compile(
                    "\\bINTO\\s+(OUTFILE|DUMPFILE)\\b"
                            + "|\\bLOAD_FILE\\s*\\("
                            + "|\\bpg_read_file\\s*\\("
                            + "|\\bpg_read_binary_file\\s*\\("
                            + "|\\bpg_ls_dir\\s*\\("
                            + "|\\blo_export\\s*\\("
                            + "|\\blo_import\\s*\\("
                            + "|\\bTO\\s+PROGRAM\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

    private static final java.util.regex.Pattern COPY_STATEMENT_PATTERN =
            java.util.regex.Pattern.compile(
                    "^\\s*COPY\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

    private void rejectFileAccessAttempts(String sql) {

        if (FILE_ACCESS_PATTERN.matcher(sql).find()
                || COPY_STATEMENT_PATTERN.matcher(sql).find()) {

            throw new IllegalArgumentException(
                    "SQL không được chứa lệnh đọc/ghi file hoặc thực thi "
                            + "lệnh hệ thống trên server database "
                            + "(INTO OUTFILE/DUMPFILE, LOAD_FILE, "
                            + "pg_read_file, pg_ls_dir, lo_export/lo_import, "
                            + "COPY, TO PROGRAM)"
            );
        }
    }

    // =========================================================
    // DUPLICATE ALIAS PROTECTION
    // =========================================================

    private static final Pattern AS_ALIAS_PATTERN =
            Pattern.compile(
                    "\\bAS\\s+([A-Za-z_][A-Za-z0-9_$]*)\\b",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Set<String> SQL_TYPE_KEYWORDS = Set.of(
            "int", "integer", "bigint", "smallint", "tinyint",
            "decimal", "numeric", "float", "double", "real",
            "char", "varchar", "text", "nchar", "nvarchar",
            "date", "datetime", "timestamp", "time", "year",
            "boolean", "bool", "binary", "varbinary", "blob",
            "json", "jsonb", "unsigned", "signed", "uuid", "money"
    );

    private void checkDuplicateAliases(String sql) {

        if (sql == null || sql.isBlank()) {
            return;
        }

        Matcher matcher = AS_ALIAS_PATTERN.matcher(sql);

        Map<String, Integer> aliasCounts = new HashMap<>();

        while (matcher.find()) {

            String alias = matcher.group(1).trim().toLowerCase(java.util.Locale.ROOT);

            if (SQL_TYPE_KEYWORDS.contains(alias)) {
                continue;
            }

            aliasCounts.merge(
                    alias,
                    1,
                    Integer::sum
            );
        }

        for (Map.Entry<String, Integer> entry : aliasCounts.entrySet()) {

            if (entry.getValue() > 1) {

                throw new IllegalArgumentException(
                        "SQL chứa alias bị trùng: "
                                + entry.getKey()
                                + ". Mỗi cột/metric trong SELECT "
                                + "phải có alias duy nhất."
                );
            }
        }
    }
}