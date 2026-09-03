package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    private DatabaseSchema emptySchema() {
        return DatabaseSchema.builder().id(1L).tables(java.util.List.of()).build();
    }

    @Test
    void buildGenerationPrompt_withoutHistory_shouldNotContainHistorySection() {
        String prompt = promptBuilder.buildGenerationPrompt("Doanh thu tháng 1", emptySchema());

        assertFalse(prompt.contains("LỊCH SỬ HỘI THOẠI"));
    }

    @Test
    void buildGenerationPrompt_withNullHistory_shouldBehaveLikeNoHistory() {
        String prompt = promptBuilder.buildGenerationPrompt("Doanh thu tháng 1", emptySchema(), null);

        assertFalse(prompt.contains("LỊCH SỬ HỘI THOẠI"));
    }

    @Test
    void buildGenerationPrompt_withBlankHistory_shouldBeTreatedAsNoHistory() {
        String prompt = promptBuilder.buildGenerationPrompt("Doanh thu tháng 1", emptySchema(), "   ");

        assertFalse(prompt.contains("LỊCH SỬ HỘI THOẠI"));
    }

    @Test
    void buildGenerationPrompt_withHistory_shouldEmbedHistoryBlock() {
        String history = "- Người dùng hỏi: Doanh thu tháng 1 là bao nhiêu?\n  → SQL đã dùng: SELECT SUM(total) FROM orders WHERE MONTH(created_at)=1\n";

        String prompt = promptBuilder.buildGenerationPrompt("So sánh nó với tháng 2", emptySchema(), history);

        assertTrue(prompt.contains("LỊCH SỬ HỘI THOẠI"));
        assertTrue(prompt.contains("Doanh thu tháng 1 là bao nhiêu?"));
        assertTrue(prompt.contains("So sánh nó với tháng 2")); // câu hỏi hiện tại vẫn phải có trong prompt
    }

    // =========================================================
    // MULTI-DB: dialect (MySQL/PostgreSQL) phải theo schema.dbType
    // =========================================================

    private DatabaseSchema schemaWithDbType(String dbType) {
        return DatabaseSchema.builder()
                .id(1L)
                .dbType(dbType)
                .tables(java.util.List.of())
                .build();
    }

    @Test
    void buildGenerationPrompt_withMysqlSchema_shouldMentionMySqlAndNoPostgresNote() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "Doanh thu tháng 1", schemaWithDbType("mysql"));

        assertTrue(prompt.contains("chuyên gia MySQL"));
        assertFalse(prompt.contains("LƯU Ý CÚ PHÁP POSTGRESQL"));
    }

    @Test
    void buildGenerationPrompt_withNullSchema_shouldDefaultToMySql() {
        // schema = null CHỈ hợp lệ cho buildExplanationPrompt (được cho
        // phép null theo javadoc). Ở đây dùng emptySchema() (dbType=null)
        // để mô phỏng "chưa biết dbType" -> phải mặc định về MySQL, giữ
        // đúng hành vi cũ, không phá vỡ các connection MySQL hiện có.
        String prompt = promptBuilder.buildGenerationPrompt("Doanh thu tháng 1", emptySchema());

        assertTrue(prompt.contains("chuyên gia MySQL"));
    }

    @Test
    void buildGenerationPrompt_withPostgresSchema_shouldMentionPostgresAndSyntaxNote() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "Doanh thu tháng 1", schemaWithDbType("postgres"));

        assertTrue(prompt.contains("chuyên gia PostgreSQL"));
        assertTrue(prompt.contains("LƯU Ý CÚ PHÁP POSTGRESQL"));
        assertTrue(prompt.contains("COALESCE"));
    }

    @Test
    void buildGenerationPrompt_withPostgresqlAlias_shouldBeTreatedAsPostgres() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "Doanh thu tháng 1", schemaWithDbType("postgresql"));

        assertTrue(prompt.contains("chuyên gia PostgreSQL"));
    }

    @Test
    void buildGenerationPrompt_withHistoryAndPostgresSchema_shouldAlsoMentionPostgres() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "So sánh nó với tháng 2", schemaWithDbType("postgres"), "lịch sử abc");

        assertTrue(prompt.contains("chuyên gia PostgreSQL"));
        assertTrue(prompt.contains("LƯU Ý CÚ PHÁP POSTGRESQL"));
    }

    @Test
    void buildCorrectionPrompt_withPostgresSchema_shouldMentionPostgresAndSyntaxNote() {
        String prompt = promptBuilder.buildCorrectionPrompt(
                "SELECT IFNULL(x,0) FROM t",
                "function ifnull does not exist",
                schemaWithDbType("postgres"));

        assertTrue(prompt.contains("lỗi trên PostgreSQL"));
        assertTrue(prompt.contains("LƯU Ý CÚ PHÁP POSTGRESQL"));
    }

    @Test
    void buildCorrectionPrompt_withHistoryAndPostgresSchema_shouldMentionPostgres() {
        String prompt = promptBuilder.buildCorrectionPrompt(
                "SELECT IFNULL(x,0) FROM t",
                "function ifnull does not exist",
                schemaWithDbType("postgres"),
                "lịch sử abc");

        assertTrue(prompt.contains("lỗi trên PostgreSQL"));
        assertTrue(prompt.contains("LƯU Ý CÚ PHÁP POSTGRESQL"));
    }

    @Test
    void buildExplanationPrompt_withPostgresSchema_shouldMentionPostgres() {
        String prompt = promptBuilder.buildExplanationPrompt(
                "SELECT * FROM t", schemaWithDbType("postgres"));

        assertTrue(prompt.contains("chuyên gia PostgreSQL"));
    }

    @Test
    void buildExplanationPrompt_withNullSchema_shouldDefaultToMySqlAndNotThrow() {
        String prompt = promptBuilder.buildExplanationPrompt("SELECT * FROM t", null);

        assertTrue(prompt.contains("chuyên gia MySQL"));
    }
}