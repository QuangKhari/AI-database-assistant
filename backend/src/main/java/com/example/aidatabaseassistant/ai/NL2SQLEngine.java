package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NL2SQLEngine {

    private final LLMClient llmClient;

    public String generateSQL(String question, DatabaseSchema schema) {
        String prompt = buildPrompt(question, schema);
        return extractSql(llmClient.generateResponse(prompt));
    }

    public String selfCorrect(String previousSql, String errorMessage, DatabaseSchema schema) {
        String prompt = buildCorrectionPrompt(previousSql, errorMessage, schema);
        return extractSql(llmClient.generateResponse(prompt));
    }

    private String buildPrompt(String question, DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là chuyên gia SQL. Dựa vào schema MySQL dưới đây, viết CHÍNH XÁC một câu lệnh SELECT để trả lời câu hỏi. ");
        sb.append("Chỉ được dùng SELECT, tuyệt đối không dùng INSERT/UPDATE/DELETE/DROP/ALTER. ");
        sb.append("Chỉ trả về câu SQL, không giải thích, không dùng markdown code block.\n\n");
        sb.append("Schema:\n");
        appendSchema(sb, schema);
        sb.append("\nCâu hỏi: ").append(question).append("\n");
        sb.append("SQL:");
        return sb.toString();
    }

    private String buildCorrectionPrompt(String previousSql, String errorMessage, DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("Câu SQL sau đây chạy bị lỗi trên MySQL. Hãy sửa lại cho đúng dựa vào schema. ");
        sb.append("Chỉ trả về câu SQL đã sửa, không giải thích, không dùng markdown code block.\n\n");
        sb.append("Schema:\n");
        appendSchema(sb, schema);
        sb.append("\nSQL cũ:\n").append(previousSql).append("\n");
        sb.append("Lỗi:\n").append(errorMessage).append("\n");
        sb.append("SQL đã sửa:");
        return sb.toString();
    }

    private void appendSchema(StringBuilder sb, DatabaseSchema schema) {
        for (TableMetadata table : schema.getTables()) {
            sb.append("- ").append(table.getName()).append("(");
            for (ColumnMetadata column : table.getColumns()) {
                sb.append(column.getName()).append(" ").append(column.getDataType());
                if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                    sb.append(" PK");
                }
                sb.append(", ");
            }
            sb.append(")\n");
        }
    }

    private String extractSql(String rawResponse) {
        return rawResponse.trim()
                .replaceAll("(?i)```sql", "")
                .replaceAll("```", "")
                .trim();
    }
}