package com.example.aidatabaseassistant.ai;

import java.util.regex.Pattern;

/**
 * Nhận diện ngôn ngữ (Tiếng Việt / English) của câu hỏi người dùng nhập vào.
 *
 * Đây là phần lõi để "hoàn thiện chức năng hỏi bằng tiếng Anh": trước đây
 * PromptBuilder LUÔN giả định câu hỏi là tiếng Việt (hardcode "Chuyển câu
 * hỏi tiếng Việt thành SQL", các câu trả lời phụ như tóm tắt kết quả cũng
 * luôn ép viết bằng tiếng Việt dù người dùng gõ tiếng Anh) -> trải nghiệm
 * hỏi bằng tiếng Anh bị nửa vời (SQL vẫn ra đúng vì Gemini hiểu tiếng Anh,
 * nhưng phần diễn giải/tóm tắt bằng lời thì luôn ra tiếng Việt).
 *
 * Heuristic đơn giản, không cần gọi thêm AI (tránh tốn thêm 1 lần gọi
 * Gemini chỉ để detect ngôn ngữ):
 * - Nếu câu hỏi có bất kỳ ký tự có dấu tiếng Việt (ă, â, đ, ê, ô, ơ, ư và
 *   các tổ hợp dấu thanh) -> chắc chắn là tiếng Việt.
 * - Nếu không có dấu tiếng Việt, nhưng chứa các từ tiếng Việt không dấu
 *   phổ biến ("bao nhieu", "khach hang", "doanh thu"...) -> vẫn coi là
 *   tiếng Việt (người Việt hay gõ không dấu).
 * - Ngược lại (toàn ký tự ASCII, không khớp từ tiếng Việt không dấu nào)
 *   -> coi là tiếng Anh.
 *
 * Mặc định fallback là tiếng Việt (VI) để KHÔNG làm thay đổi hành vi cũ
 * cho các câu hỏi mơ hồ/rỗng - tránh regression cho người dùng hiện tại.
 */
public final class QuestionLanguage {

    public static final String VI = "vi";
    public static final String EN = "en";

    private QuestionLanguage() {
    }

    private static final Pattern VIETNAMESE_DIACRITICS = Pattern.compile(
            "[àáạảãăằắặẳẵâầấậẩẫèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡ" +
                    "ùúụủũưừứựửữỳýỵỷỹđ" +
                    "ÀÁẠẢÃĂẰẮẶẲẴÂẦẤẬẨẪÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠ" +
                    "ÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ]"
    );

    // Từ tiếng Việt không dấu phổ biến trong câu hỏi Text-to-SQL, dùng để
    // vẫn nhận ra tiếng Việt ngay cả khi người dùng gõ không bỏ dấu.
    private static final Pattern VIETNAMESE_NO_DIACRITICS_WORDS = Pattern.compile(
            "(?i)\\b(" +
                    "bao nhieu|khach hang|don hang|san pham|doanh thu|" +
                    "tong so|trung binh|gan day|nam ngoai|thang nay|" +
                    "hoa don|nguoi dung|danh sach|liet ke|thong ke" +
                    ")\\b"
    );

    /**
     * Trả về {@link #VI} hoặc {@link #EN}. Không bao giờ trả về null.
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return VI; // fallback an toàn, giữ nguyên hành vi cũ
        }

        if (VIETNAMESE_DIACRITICS.matcher(text).find()) {
            return VI;
        }

        if (VIETNAMESE_NO_DIACRITICS_WORDS.matcher(text).find()) {
            return VI;
        }

        return EN;
    }

    public static boolean isEnglish(String text) {
        return EN.equals(detect(text));
    }
}