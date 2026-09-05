package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryValidatorTest {

    private final QueryValidator validator = new QueryValidator();
    private DatabaseSchema schema;

    @BeforeEach
    void setUp() {
        schema = DatabaseSchema.builder()
                .databaseName("sample_store")
                .tables(List.of(TableMetadata.builder().name("orders").build()))
                .build();
    }

    @Test
    void acceptsOneSelectUsingKnownTable() {
        assertThatCode(() -> validator.validate(
                "SELECT id FROM sample_store.orders LIMIT 10", schema))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWriteAndMultipleStatements() {
        assertThatThrownBy(() -> validator.validate("DELETE FROM orders", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chỉ cho phép câu lệnh SELECT");

        assertThatThrownBy(() -> validator.validate(
                "SELECT * FROM orders; SELECT * FROM orders", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("một câu lệnh SQL");
    }

    @Test
    void rejectsUnknownDatabaseAndDangerousSelectFunctions() {
        assertThatThrownBy(() -> validator.validate("SELECT * FROM private_db.orders", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database khác");

        assertThatThrownBy(() -> validator.validate("SELECT SLEEP(10) FROM orders", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không an toàn");
    }
}
