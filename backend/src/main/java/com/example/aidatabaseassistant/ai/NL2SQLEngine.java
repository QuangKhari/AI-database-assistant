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
                    ")\\b"
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

            return """
                    SELECT 'Không được phép thực hiện thao tác INSERT, UPDATE, DELETE hoặc thay đổi cấu trúc database' AS message
                    """.trim();
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

    public String selfCorrect(
            String previousSql,
            String errorMessage,
            DatabaseSchema schema
    ) {

        /*
         * Self-correction chỉ được dùng cho các lỗi SQL thông thường.
         *
         * Việc chặn READ-ONLY thực sự vẫn do QueryValidator đảm nhiệm.
         */
        String prompt = promptBuilder.buildCorrectionPrompt(
                previousSql,
                errorMessage,
                schema
        );

        return extractSql(
                llmClient.generateResponse(prompt)
        );
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