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
}