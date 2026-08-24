package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    public String buildGenerationPrompt(String question, DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
        Bạn là chuyên gia MySQL và Text-to-SQL.

        Nhiệm vụ:
        Chuyển câu hỏi tiếng Việt thành DUY NHẤT một câu lệnh SQL SELECT hợp lệ.

        QUY TẮC BẮT BUỘC:

        1. Chỉ trả về SQL, không giải thích.
        2. Không dùng markdown hoặc ```sql.
        3. Chỉ sử dụng SELECT.
        4. Tuyệt đối KHÔNG dùng INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, CREATE, RENAME, USE.
        5. KHÔNG dùng SELECT * nếu câu hỏi không yêu cầu toàn bộ thông tin.
        6. Chỉ SELECT đúng những cột được nhắc tới trong câu hỏi.
        7. Nếu hỏi:
           - "tên khách hàng" -> chỉ lấy full_name
           - "tên sản phẩm" -> chỉ lấy name
           - "tên và giá" -> lấy name, price
           - "5 khách gần đây" -> full_name, created_at
           - "bao nhiêu" -> dùng COUNT(*) AS total
           - "tổng doanh thu" -> SUM(...) AS total
        8. Dùng đúng tên bảng và cột trong schema.
        9. Nếu cần JOIN thì dùng khóa ngoại trong schema.
        10. Luôn ưu tiên SQL ngắn gọn và chính xác.
        11. Nếu câu hỏi hỏi "đơn hàng nào", "khách hàng nào", "sản phẩm nào" mà yêu cầu toàn bộ thông tin thì phải trả về SELECT *.
        12. Chỉ chọn một vài cột khi câu hỏi nêu rõ các cột cần lấy (ví dụ: tên, giá, email...).
        13. KHÔNG được tự ý dùng DISTINCT.
        Chỉ dùng DISTINCT khi câu hỏi có từ:
        - không trùng
        - duy nhất
        - distinct
        14. Không dịch giá trị enum sang tiếng Việt.
        
        """);

        sb.append("SCHEMA:\n");
        appendSchema(sb, schema);

        sb.append("""

        VÍ DỤ:

        Q: Khách hàng nào sống ở Hà Nội?
        SQL:
        SELECT full_name
        FROM customers
        WHERE city='Hà Nội';

        Q: Sản phẩm nào có giá trên 5000000?
        SQL:
        SELECT name, price
        FROM products
        WHERE price > 5000000;

        Q: 5 khách hàng đăng ký gần đây nhất
        SQL:
        SELECT full_name, created_at
        FROM customers
        ORDER BY created_at DESC
        LIMIT 5;

        GIÁ TRỊ DỮ LIỆU QUAN TRỌNG
                
        Bảng orders.status chỉ có các giá trị:
        - completed
        - pending
        - cancelled
                
        Không được dịch sang:
        - Đã hoàn thành
        - Chờ xử lý
        - Đã hủy
        
        """);

        sb.append("CÂU HỎI:\n");
        sb.append(question);
        sb.append("\n\nSQL:");

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

            sb.append("TABLE ").append(table.getName());

            if (table.getDescription() != null &&
                    !table.getDescription().isBlank()) {
                sb.append(" // ").append(table.getDescription());
            }

            sb.append("\n");

            for (ColumnMetadata column : table.getColumns()) {

                sb.append("  - ")
                        .append(column.getName())
                        .append(" : ")
                        .append(column.getDataType());

                if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                    sb.append(" (PK)");
                }

                if (Boolean.TRUE.equals(column.getForeignKey())
                        && column.getReferencedTable() != null) {
                    sb.append(" (FK -> ")
                            .append(column.getReferencedTable())
                            .append(".")
                            .append(column.getReferencedColumn())
                            .append(")");
                }

                if (column.getDescription() != null &&
                        !column.getDescription().isBlank()) {
                    sb.append(" // ").append(column.getDescription());
                }

                sb.append("\n");
            }

            sb.append("\n");
        }
    }
}