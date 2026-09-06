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
    // HỎI BẰNG TIẾNG ANH: buildGenerationPrompt phải detect ngôn ngữ
    // câu hỏi (qua QuestionLanguage) và dựng prompt bằng đúng ngôn ngữ đó.
    // =========================================================

    @Test
    void buildGenerationPrompt_withEnglishQuestion_shouldUseEnglishHeaderAndRules() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "How many customers do we have?", emptySchema());

        assertTrue(prompt.contains("You are a MySQL and Text-to-SQL expert."));
        assertTrue(prompt.contains("MANDATORY RULES:"));
        assertTrue(prompt.contains("NEVER use INSERT, UPDATE, DELETE"));

        // KHÔNG được lẫn hướng dẫn tiếng Việt vào prompt tiếng Anh.
        assertFalse(prompt.contains("QUY TẮC BẮT BUỘC"));
        assertFalse(prompt.contains("Chuyển câu hỏi"));
    }

    @Test
    void buildGenerationPrompt_withEnglishQuestion_shouldUseEnglishExamplesAndQuestionLabel() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "Which products cost more than 500000?", emptySchema());

        assertTrue(prompt.contains("EXAMPLES:"));
        assertTrue(prompt.contains("IMPORTANT DATA VALUES"));
        assertTrue(prompt.contains("QUESTION:\nWhich products cost more than 500000?"));

        // KHÔNG được lẫn ví dụ/nhãn tiếng Việt.
        assertFalse(prompt.contains("VÍ DỤ:"));
        assertFalse(prompt.contains("CÂU HỎI:"));
    }

    @Test
    void buildGenerationPrompt_withVietnameseQuestion_shouldStillUseVietnameseInstructions() {
        // Regression: câu hỏi tiếng Việt (có dấu) không được bị ảnh hưởng
        // bởi thay đổi hỗ trợ tiếng Anh.
        String prompt = promptBuilder.buildGenerationPrompt("Doanh thu tháng 1", emptySchema());

        assertTrue(prompt.contains("Bạn là chuyên gia MySQL và Text-to-SQL."));
        assertTrue(prompt.contains("QUY TẮC BẮT BUỘC"));
        assertTrue(prompt.contains("VÍ DỤ:"));
        assertTrue(prompt.contains("CÂU HỎI:\nDoanh thu tháng 1"));

        assertFalse(prompt.contains("MANDATORY RULES"));
        assertFalse(prompt.contains("You are a"));
    }

    @Test
    void buildGenerationPrompt_withEnglishQuestionAndPostgresSchema_shouldMentionPostgresInEnglishHeader() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "How many orders were placed?", schemaWithDbType("postgres"));

        assertTrue(prompt.contains("You are a PostgreSQL and Text-to-SQL expert."));
        // Ghi chú khác biệt cú pháp Postgres hiện tại dùng chung 1 bản
        // (tiếng Việt) cho cả 2 ngôn ngữ câu hỏi - không phải bug, chỉ là
        // phần chưa được dịch, nên vẫn phải xuất hiện khi dbType=postgres.
        assertTrue(prompt.contains("LƯU Ý CÚ PHÁP POSTGRESQL"));
    }

    @Test
    void buildGenerationPrompt_withEnglishQuestionAndHistory_shouldUseEnglishHistoryBlock() {
        String history = "- User asked: What was revenue in January?\n  → SQL used: SELECT SUM(total) FROM orders WHERE MONTH(created_at)=1\n";

        String prompt = promptBuilder.buildGenerationPrompt(
                "Compare it with February", emptySchema(), history);

        assertTrue(prompt.contains("RECENT CONVERSATION HISTORY"));
        assertTrue(prompt.contains("What was revenue in January?"));
        assertTrue(prompt.contains("CURRENT QUESTION: Compare it with February"));
        assertTrue(prompt.contains("MANDATORY RULES:"));

        assertFalse(prompt.contains("LỊCH SỬ HỘI THOẠI"));
        assertFalse(prompt.contains("CÂU HỎI HIỆN TẠI"));
    }

    @Test
    void buildGenerationPrompt_withEnglishQuestion_withoutHistory_shouldNotContainHistorySection() {
        String prompt = promptBuilder.buildGenerationPrompt(
                "Show me the top 5 recent orders", emptySchema(), null);

        assertFalse(prompt.contains("RECENT CONVERSATION HISTORY"));
        assertFalse(prompt.contains("LỊCH SỬ HỘI THOẠI"));
    }

    // =========================================================
    // MULTI-DB: dialect (MySQL/PostgreSQL) phải theo schema.dbType
    // =========================================================

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