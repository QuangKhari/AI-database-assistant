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

        TableMetadata customers = TableMetadata.builder()
                .name("customers")
                .build();

        TableMetadata orders = TableMetadata.builder()
                .name("orders")
                .build();

        schema = DatabaseSchema.builder()
                .tables(List.of(customers, orders))
                .build();
    }

    @Test
    void shouldAllowValidSelect() {

        String sql = "SELECT * FROM customers";

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
    void shouldRejectInsert() {

        String sql =
                "INSERT INTO customers(full_name) VALUES ('Test')";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldRejectUpdate() {

        String sql =
                "UPDATE customers SET full_name = 'Test'";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldRejectDelete() {

        String sql =
                "DELETE FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldRejectDrop() {

        String sql =
                "DROP TABLE customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldRejectAlter() {

        String sql =
                "ALTER TABLE customers ADD COLUMN test VARCHAR(100)";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldRejectTruncate() {

        String sql =
                "TRUNCATE TABLE customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldRejectMultipleStatements() {

        String sql =
                "SELECT * FROM customers; DELETE FROM customers";

        assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );
    }

    @Test
    void shouldRejectUnknownTable() {

        String sql =
                "SELECT * FROM products";

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> queryValidator.validate(sql, schema)
        );

        assertTrue(
                exception.getMessage().contains("Bảng không tồn tại")
        );
    }
}