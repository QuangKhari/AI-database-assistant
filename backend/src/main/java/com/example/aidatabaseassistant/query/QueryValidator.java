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
        Statement statement = parse(sql);
        checkReadOnly(statement);
        checkSchemaMatch(statement, schema);
    }

    private Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL không hợp lệ về cú pháp: " + e.getMessage());
        }
    }

    public void checkReadOnly(Statement statement) {
        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException("Chỉ cho phép câu lệnh SELECT");
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
            if (!knownTables.contains(clean)) {
                throw new IllegalArgumentException("Bảng không tồn tại trong schema: " + tableName);
            }
        }
    }
}