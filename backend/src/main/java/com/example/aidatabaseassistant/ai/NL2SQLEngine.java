package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class NL2SQLEngine {

    private final LLMClient llmClient;
    private final PromptBuilder promptBuilder;

    /**
     * Các từ khóa biểu thị thao tác thay đổi dữ liệu / cấu trúc database.
     *
     * Bao gồm cả tiếng Anh và tiếng Việt.
     *
     * Dùng Pattern.UNICODE_CHARACTER_CLASS để \b nhận diện đúng ranh giới
     * từ có dấu tiếng Việt. Mặc định Java chỉ coi ky tu ASCII (a-z, 0-9, _)
     * la "ky tu tu" - nhung tu bat dau bang mot ky tu co dau (vi du "đổi",
     * bat dau bang "đ") se KHONG BAO GIO khop du regex co "\bđổi\b", vi
     * Java khong coi vi tri giua khoang trang va "đ" la mot ranh gioi tu.
     */
    private static final Pattern WRITE_OPERATION = Pattern.compile(
            "(?i)\\b(" +
                    // SQL
                    "DELETE|UPDATE|INSERT|DROP|ALTER|TRUNCATE|CREATE|RENAME|" +
                    "REPLACE|MERGE|UPSERT|USE|" +

                    // Vietnamese
                    "xóa|xoá|xoa|" +
                    "xóa bỏ|xoá bỏ|xoa bo|" +
                    "thêm|them|" +
                    "cập nhật|cap nhat|" +
                    "sửa|sua|" +
                    "chỉnh sửa|chinh sua|" +
                    "thay đổi|thay doi|" +
                    "tạo|tao|" +
                    "tạo mới|tao moi|" +
                    "đổi|doi|" +
                    "xóa bảng|xoá bảng|xoa bang|" +
                    "xóa dữ liệu|xoá dữ liệu|xoa du lieu|" +
                    "thêm dữ liệu|them du lieu|" +
                    "chèn dữ liệu|chen du lieu" +
                    ")\\b",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    public String generateSQL(String question, DatabaseSchema schema) {

        /*
         * SECURITY:
         *
         * Kiểm tra câu hỏi trước khi gửi cho AI.
         *
         * Nếu người dùng yêu cầu INSERT / UPDATE / DELETE
         * hoặc thao tác thay đổi database thì không gọi LLM.
         */
        if (containsWriteOperation(question)) {
            return blockedWriteOperationSql(question);
        }

        /*
         * Nếu không phải thao tác ghi,
         * mới gửi câu hỏi cho AI để sinh SELECT.
         */
        String prompt = promptBuilder.buildGenerationPrompt(
                question,
                schema
        );

        return extractSql(
                llmClient.generateResponse(prompt)
        );
    }

    public String generateSQL(String question, DatabaseSchema schema, String conversationHistory) {

        if (conversationHistory == null || conversationHistory.isBlank()) {
            return generateSQL(question, schema); // fallback đúng path cũ, không tạo prompt mới
        }

        if (containsWriteOperation(question)) {
            return blockedWriteOperationSql(question);
        }

        String prompt = promptBuilder.buildGenerationPrompt(question, schema, conversationHistory);

        return extractSql(llmClient.generateResponse(prompt));
    }

    public String selfCorrect(
            String previousSql,
            String errorMessage,
            DatabaseSchema schema
    ) {

        return selfCorrect(
                previousSql,
                errorMessage,
                schema,
                null
        );
    }

    public String selfCorrect(
            String previousSql,
            String errorMessage,
            DatabaseSchema schema,
            String conversationHistory
    ) {

        String prompt;

        if (conversationHistory == null || conversationHistory.isBlank()) {

            prompt = promptBuilder.buildCorrectionPrompt(
                    previousSql,
                    errorMessage,
                    schema
            );

        } else {

            prompt = promptBuilder.buildCorrectionPrompt(
                    previousSql,
                    errorMessage,
                    schema,
                    conversationHistory
            );
        }

        return extractSql(
                llmClient.generateResponse(prompt)
        );
    }

    /**
     * Trả lời từ chối khi câu hỏi có thao tác ghi (INSERT/UPDATE/DELETE...).
     * Trả về đúng ngôn ngữ của câu hỏi (tiếng Anh nếu câu hỏi bằng tiếng
     * Anh) thay vì luôn ép tiếng Việt như trước đây.
     */
    private String blockedWriteOperationSql(String question) {
        if (QuestionLanguage.isEnglish(question)) {
            return """
                    SELECT 'INSERT, UPDATE, DELETE or database structure changes are not allowed' AS message
                    """.trim();
        }

        return """
                SELECT 'Không được phép thực hiện thao tác INSERT, UPDATE, DELETE hoặc thay đổi cấu trúc database' AS message
                """.trim();
    }

    private boolean containsWriteOperation(String question) {

        if (question == null || question.isBlank()) {
            return false;
        }

        return WRITE_OPERATION.matcher(question).find();
    }

    private String extractSql(String rawResponse) {

        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException(
                    "AI không trả về câu SQL."
            );
        }

        return rawResponse
                .trim()
                .replaceAll("(?i)```sql", "")
                .replaceAll("```", "")
                .trim();
    }
}