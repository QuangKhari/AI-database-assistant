package com.example.aidatabaseassistant.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuestionLanguageTest {

    // ===================== Tiếng Việt có dấu =====================

    @Test
    void detect_shouldReturnVi_forVietnameseWithDiacritics() {
        String[] questions = {
                "Doanh thu tháng 1 là bao nhiêu?",
                "Khách hàng nào sống ở Hà Nội?",
                "Hãy đổi tên khách hàng này thành ABC",
                "5 sản phẩm bán chạy nhất",
                "Đơn hàng nào có giá trị lớn nhất?"
        };

        for (String q : questions) {
            assertEquals(QuestionLanguage.VI, QuestionLanguage.detect(q), "Sai với câu: " + q);
            assertFalse(QuestionLanguage.isEnglish(q), "Sai với câu: " + q);
        }
    }

    // ===================== Tiếng Việt không dấu (từ khóa phổ biến) =====================

    @Test
    void detect_shouldReturnVi_forVietnameseWithoutDiacritics() {
        String[] questions = {
                "bao nhieu khach hang o Ha Noi",
                "doanh thu thang 1",
                "liet ke danh sach don hang",
                "thong ke san pham ban chay nhat",
                "trung binh gia tri don hang gan day"
        };

        for (String q : questions) {
            assertEquals(QuestionLanguage.VI, QuestionLanguage.detect(q), "Sai với câu: " + q);
            assertFalse(QuestionLanguage.isEnglish(q), "Sai với câu: " + q);
        }
    }

    // ===================== Tiếng Anh =====================

    @Test
    void detect_shouldReturnEn_forEnglishQuestions() {
        String[] questions = {
                "How many customers do we have?",
                "Show me the top 5 best selling products",
                "What is the total revenue this month?",
                "List all orders placed last week",
                "Which customer has the highest total spending?"
        };

        for (String q : questions) {
            assertEquals(QuestionLanguage.EN, QuestionLanguage.detect(q), "Sai với câu: " + q);
            assertTrue(QuestionLanguage.isEnglish(q), "Sai với câu: " + q);
        }
    }

    // ===================== Edge cases =====================

    @Test
    void detect_shouldDefaultToVi_whenQuestionIsNull() {
        assertEquals(QuestionLanguage.VI, QuestionLanguage.detect(null));
        assertFalse(QuestionLanguage.isEnglish(null));
    }

    @Test
    void detect_shouldDefaultToVi_whenQuestionIsBlank() {
        assertEquals(QuestionLanguage.VI, QuestionLanguage.detect("   "));
        assertFalse(QuestionLanguage.isEnglish("   "));
    }

    @Test
    void detect_shouldDefaultToVi_whenQuestionIsEmptyString() {
        assertEquals(QuestionLanguage.VI, QuestionLanguage.detect(""));
    }

    @Test
    void detect_shouldBeCaseInsensitive_forVietnameseNoDiacriticsWords() {
        // "DOANH THU" viết hoa toàn bộ vẫn phải nhận diện được là tiếng Việt
        assertEquals(QuestionLanguage.VI, QuestionLanguage.detect("DOANH THU thang nay la bao nhieu"));
    }

    @Test
    void detect_shouldReturnVi_whenQuestionHasVietnameseDiacriticsEvenWithEnglishColumnName() {
        // Câu hỏi tiếng Việt nhưng có lẫn một từ tiếng Anh (ví dụ tên cột)
        // vẫn phải được nhận là VI vì có dấu tiếng Việt.
        assertEquals(QuestionLanguage.VI,
                QuestionLanguage.detect("Hiển thị cột total_revenue theo tháng"));
    }

    @Test
    void detect_shouldReturnEn_forPlainAsciiQuestionWithSqlKeyword() {
        // Trường hợp quan trọng: câu hỏi tiếng Anh có chứa từ khóa SQL
        // (DELETE) - NL2SQLEngine sẽ chặn thao tác ghi, nhưng ngôn ngữ
        // detect được vẫn phải là EN để trả về thông báo đúng ngôn ngữ.
        assertEquals(QuestionLanguage.EN, QuestionLanguage.detect("Please DELETE all orders"));
    }
}