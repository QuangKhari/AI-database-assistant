package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    public String buildGenerationPrompt(String question, DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là chuyên gia SQL. Dựa vào schema MySQL dưới đây, viết CHÍNH XÁC một câu lệnh SELECT để trả lời câu hỏi. ");
        sb.append("Chỉ được dùng SELECT, tuyệt đối không dùng INSERT/UPDATE/DELETE/DROP/ALTER/TRUNCATE/CREATE/RENAME/USE. ");
        sb.append("Chỉ chọn đúng những cột mà câu hỏi cần, KHÔNG dùng SELECT * trừ khi câu hỏi yêu cầu toàn bộ thông tin. ");
        sb.append("KHÔNG dùng DISTINCT trừ khi cần loại bỏ trùng lặp rõ ràng theo yêu cầu câu hỏi. ");
        sb.append("Chỉ trả về câu SQL, không giải thích, không dùng markdown code block.\n\n");
        sb.append("Schema:\n");
        appendSchema(sb, schema);
        sb.append("\nCâu hỏi: ").append(question).append("\n");
        sb.append("SQL:");
        return sb.toString();
    }

    public String buildCorrectionPrompt(String previousSql, String errorMessage, DatabaseSchema schema) {
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
            sb.append("- ").append(table.getName());
            if (table.getDescription() != null && !table.getDescription().isBlank()) {
                sb.append(" (").append(table.getDescription()).append(")");
            }
            sb.append("(");
            for (ColumnMetadata column : table.getColumns()) {
                sb.append(column.getName()).append(" ").append(column.getDataType());
                if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                    sb.append(" PK");
                }
                if (Boolean.TRUE.equals(column.getForeignKey()) && column.getReferencedTable() != null) {
                    sb.append(" FK->").append(column.getReferencedTable()).append(".").append(column.getReferencedColumn());
                }
                sb.append(", ");
            }
            sb.append(")\n");
        }
    }
}