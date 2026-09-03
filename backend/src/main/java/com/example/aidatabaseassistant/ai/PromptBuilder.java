package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import org.springframework.stereotype.Component;
import com.example.aidatabaseassistant.dto.AnomalyDto;
import com.example.aidatabaseassistant.dto.TrendDirection;
import com.example.aidatabaseassistant.insight.DataInsightFacts;
import com.example.aidatabaseassistant.dto.IndexSuggestionDto;
import com.example.aidatabaseassistant.dto.OptimizationIssueDto;
import java.util.List;

import java.util.Locale;

@Component
public class PromptBuilder {

    // =========================================================
    // DIALECT HELPERS (Multi-DB: MySQL / PostgreSQL)
    // =========================================================
    //
    // PromptBuilder truoc day LUON gia dinh dialect la MySQL (chuoi
    // "Ban la chuyen gia MySQL..." hardcode), du connection thuc te
    // co the la PostgreSQL. Dieu nay khien Gemini de sinh ra ham/cu
    // phap chi MySQL moi co (IFNULL, GROUP_CONCAT, DATE_FORMAT,
    // backtick `...`) roi that bai khi chay tren PostgreSQL.
    //
    // 3 method duoi day suy ra dialect TRUC TIEP tu
    // DatabaseSchema.getDbType() (da co san, khong can doi signature
    // cua cac ham build...Prompt) va chi bo sung 1 doan luu y cu phap
    // khi dialect la PostgreSQL. Mac dinh (schema null, dbType null,
    // dbType = "mysql"/"excel") van la "MySQL" nhu code cu -> KHONG
    // lam thay doi hanh vi hien tai cho MySQL/Excel.

    private static final String DEFAULT_DIALECT_LABEL = "MySQL";

    private boolean isPostgres(String dbType) {
        return "postgres".equalsIgnoreCase(dbType)
                || "postgresql".equalsIgnoreCase(dbType);
    }

    private String resolveDialectLabel(DatabaseSchema schema) {
        if (schema != null && isPostgres(schema.getDbType())) {
            return "PostgreSQL";
        }
        return DEFAULT_DIALECT_LABEL;
    }

    /**
     * Ghi chu cac diem khac biet cu phap quan trong nhat giua MySQL
     * va PostgreSQL, chi chen vao prompt khi dialect la PostgreSQL
     * (voi MySQL/Excel giu nguyen prompt nhu cu, khong them gi ca).
     */
    private String dialectSyntaxNote(String dialectLabel) {
        if (!"PostgreSQL".equals(dialectLabel)) {
            return "";
        }

        return """

        LƯU Ý CÚ PHÁP POSTGRESQL (khác MySQL, PHẢI tuân theo):
        - Nếu cần quote tên bảng/cột, dùng dấu ngoặc kép "..." (KHÔNG dùng
          backtick `...` như MySQL).
        - Dùng COALESCE(...) thay vì IFNULL(...).
        - Dùng STRING_AGG(cột, ', ') thay vì GROUP_CONCAT(cột).
        - Dùng TO_CHAR(cột_ngày, 'YYYY-MM-DD') thay vì DATE_FORMAT(...).
        - So sánh chuỗi PHÂN BIỆT HOA/THƯỜNG theo mặc định; dùng ILIKE thay
          vì LIKE nếu câu hỏi cần so khớp không phân biệt hoa/thường.
        - LIMIT n vẫn dùng bình thường như MySQL.
        """;
    }

    public String buildGenerationPrompt(String question, DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Bạn là chuyên gia ")
                .append(dialectLabel)
                .append(" và Text-to-SQL.\n");

        sb.append("""

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

        sb.append(dialectSyntaxNote(dialectLabel));

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

    public String buildCorrectionPrompt(
            String previousSql,
            String errorMessage,
            DatabaseSchema schema
    ) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Câu SQL sau đây chạy bị lỗi trên ").append(dialectLabel)
                .append(". Hãy sửa lại cho đúng dựa vào schema. ");
        sb.append("Chỉ trả về câu SQL đã sửa, không giải thích, không dùng markdown code block.\n\n");

        sb.append(dialectSyntaxNote(dialectLabel));

        sb.append("Schema:\n");
        appendSchema(sb, schema);

        sb.append("\nSQL cũ:\n")
                .append(previousSql)
                .append("\n");

        sb.append("Lỗi:\n")
                .append(errorMessage)
                .append("\n");

        sb.append("SQL đã sửa:");

        return sb.toString();
    }

    public String buildCorrectionPrompt(
            String previousSql,
            String errorMessage,
            DatabaseSchema schema,
            String conversationHistory
    ) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Câu SQL sau đây chạy bị lỗi trên ").append(dialectLabel).append(".\n");

        sb.append("""
            Hãy sửa lại cho đúng dựa vào schema và lịch sử hội thoại.

            QUY TẮC BẮT BUỘC:
            1. Chỉ trả về DUY NHẤT một câu SQL SELECT đã sửa.
            2. Không giải thích.
            3. Không dùng markdown hoặc ```sql.
            4. Không dùng INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE,
               CREATE, RENAME, USE.
            5. Dùng đúng tên bảng và cột trong schema.
            6. Chỉ sửa SQL dựa trên lỗi thực tế và thông tin được cung cấp.
            7. Lịch sử hội thoại chỉ dùng để hiểu ngữ cảnh của câu hỏi hiện tại,
               không được lặp lại câu SQL cũ nếu không cần thiết.

            """);

        sb.append(dialectSyntaxNote(dialectLabel));

        if (conversationHistory != null && !conversationHistory.isBlank()) {
            sb.append("""
                LỊCH SỬ HỘI THOẠI GẦN NHẤT:
                """);
            sb.append(conversationHistory);

            sb.append("""

                """);
        }

        sb.append("SCHEMA:\n");
        appendSchema(sb, schema);

        sb.append("\nSQL CŨ:\n");
        sb.append(previousSql);

        sb.append("\n\nLỖI:\n");
        sb.append(errorMessage);

        sb.append("\n\nSQL ĐÃ SỬA:");

        return sb.toString();
    }

    public String buildExplanationPrompt(String sql, DatabaseSchema schema) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Bạn là chuyên gia ").append(dialectLabel).append(". ");

        sb.append("""
        Nhiệm vụ của bạn là giải thích câu SQL dưới
        đây bằng tiếng Việt, đơn giản để người không rành kỹ thuật cũng hiểu.

        CHIA GIẢI THÍCH THEO TỪNG MỆNH ĐỀ xuất hiện trong câu SQL (ví dụ:
        SELECT, FROM, JOIN, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT). CHỈ
        liệt kê mệnh đề nào THỰC SỰ có mặt trong câu SQL, không bịa thêm.

        """);

        if (schema != null) {
            sb.append("SCHEMA (dùng để giải thích đúng ý nghĩa tên bảng/cột):\n");
            appendSchema(sb, schema);
            sb.append("\n");
        }

        sb.append("""
        CHỈ trả về JSON hợp lệ đúng định dạng sau, KHÔNG thêm bất kỳ ký tự
        nào khác, KHÔNG dùng markdown code block (không dùng dấu ```):
        {
          "summary": "1 câu tóm tắt tổng quan câu SQL này làm gì",
          "steps": [
            {"clause": "phần SQL của mệnh đề này", "explanation": "giải thích ngắn gọn, dễ hiểu"}
          ]
        }

        """);

        sb.append("Câu SQL cần giải thích:\n").append(sql);

        return sb.toString();
    }

    public String buildChartReasonPrompt(String chartType, String dimensionColumn, java.util.List<String> numericColumns,
                                         int rowCount, String detectedPattern) {
        StringBuilder sb = new StringBuilder();

        sb.append("Hệ thống đã dùng thuật toán để tự động chọn loại biểu đồ ")
                .append(chartType)
                .append(" cho một kết quả truy vấn dữ liệu, dựa trên thông tin sau:\n");
        sb.append("- Trục X (danh mục/thời gian): ").append(dimensionColumn).append("\n");
        sb.append("- Cột số liệu: ").append(String.join(", ", numericColumns)).append("\n");
        sb.append("- Số dòng dữ liệu: ").append(rowCount).append("\n");
        sb.append("- Lý do kỹ thuật đã phát hiện: ").append(detectedPattern).append("\n\n");
        sb.append("Hãy viết lại lý do trên thành 1-2 câu tiếng Việt tự nhiên, ngắn gọn, dễ hiểu cho người dùng cuối. ");
        sb.append("CHỈ trả về đúng câu giải thích, không thêm tiêu đề, không dùng markdown, không lặp lại số liệu thô.");

        return sb.toString();
    }

    /**
     * Xay prompt de Gemini VIET LAI thanh van phong tu nhien cac SO LIEU DA
     * duoc DataInsightAnalyzer tinh SAN bang thuat toan thuan (xem
     * DataInsightService). Gemini KHONG duoc phep tu tinh toan hay bia them
     * bat ky con so/danh muc nao ngoai nhung gi duoc liet ke trong prompt -
     * day la yeu cau AN TOAN quan trong nhat cua tinh nang nay, vi day la
     * cong cu phan tich du lieu, sai so lieu la khong the chap nhan duoc.
     */
    public String buildDataInsightPrompt(DataInsightFacts facts) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
        Bạn là chuyên gia phân tích dữ liệu kinh doanh.

        Nhiệm vụ:
        VIẾT LẠI các số liệu ĐÃ ĐƯỢC TÍNH SẴN bên dưới thành một đoạn tóm
        tắt 2-3 câu tiếng Việt tự nhiên, mạch lạc, dễ hiểu cho người không
        rành kỹ thuật.

        QUY TẮC BẮT BUỘC:

        1. TUYỆT ĐỐI không bịa số liệu. CHỈ được nhắc tới đúng những con số,
           tên danh mục, mốc thời gian được liệt kê bên dưới, không thêm,
           không bớt, không đoán, không suy diễn ra bất cứ điều gì khác.
        2. TUYỆT ĐỐI không tự tính toán lại (không cộng, trừ, nhân, chia,
           không tính lại phần trăm, không làm tròn khác đi con số đã cho).
        3. Mục nào KHÔNG được cung cấp bên dưới (ví dụ không có tăng
           trưởng, không có tỷ trọng, không có bất thường) thì ĐỪNG nhắc
           tới mục đó trong câu trả lời, tuyệt đối không tự suy ra.
        4. Không dịch hoặc đổi tên cột, tên danh mục sang từ khác.
        5. CHỈ trả về đoạn văn tóm tắt, không thêm tiêu đề, không dùng
           markdown, không liệt kê gạch đầu dòng, không giải thích thêm
           ngoài đoạn tóm tắt.

        """);

        sb.append("SỐ LIỆU ĐÃ TÍNH SẴN (đáng tin cậy 100%, không được thay đổi):\n");
        sb.append("- Cột số liệu: ").append(facts.getNumericColumn()).append("\n");
        sb.append("- Cột danh mục/thời gian: ").append(facts.getDimensionColumn()).append("\n");
        sb.append(String.format(Locale.ROOT,
                "- Giá trị cao nhất: %s = %.2f%n", facts.getHighestLabel(), facts.getHighestValue()));
        sb.append(String.format(Locale.ROOT,
                "- Giá trị thấp nhất: %s = %.2f%n", facts.getLowestLabel(), facts.getLowestValue()));

        if (facts.getGrowthPercent() != null) {
            if (facts.getPeriodStartLabel() != null && facts.getPeriodEndLabel() != null) {
                sb.append(String.format(Locale.ROOT,
                        "- Tăng trưởng từ mốc %s đến mốc %s: %.1f%% (CHỈ được dùng đúng 2 mốc thời gian này khi nhắc tới con số tăng trưởng, TUYỆT ĐỐI không dùng nhầm mốc khác, ví dụ không được dùng mốc của giá trị cao/thấp nhất bên trên)%n",
                        facts.getPeriodStartLabel(), facts.getPeriodEndLabel(), facts.getGrowthPercent()));
            } else {
                sb.append(String.format(Locale.ROOT,
                        "- Tăng trưởng từ đầu đến cuối kỳ: %.1f%%%n", facts.getGrowthPercent()));
            }
        }
        if (facts.getTrend() != null) {
            sb.append("- Xu hướng: ").append(trendToVietnamese(facts.getTrend())).append("\n");
        }
        if (facts.getTopSharePercent() != null) {
            sb.append(String.format(Locale.ROOT,
                    "- Danh mục chiếm tỷ trọng cao nhất: %s = %.1f%% tổng%n",
                    facts.getTopShareLabel(), facts.getTopSharePercent()));
        }
        if (facts.getAnomalies() != null && !facts.getAnomalies().isEmpty()) {
            sb.append("- Điểm bất thường phát hiện được:\n");
            for (AnomalyDto anomaly : facts.getAnomalies()) {
                sb.append(String.format(Locale.ROOT,
                        "  + %s: %.2f (%s)%n", anomaly.getLabel(), anomaly.getValue(), anomaly.getDirection()));
            }
        }

        sb.append("\nHãy viết đoạn tóm tắt (2-3 câu) dựa ĐÚNG và CHỈ trên số liệu ở trên:");

        return sb.toString();
    }

    /**
     * Giong buildDataInsightPrompt: Gemini CHI duoc VIET LAI cac phat hien
     * (issues/suggestions) da duoc SqlOptimizationAnalyzer tinh SAN tu
     * EXPLAIN va index THAT cua MySQL - khong duoc tu bia them van de hieu
     * nang hay index nao khac ngoai danh sach duoc cung cap.
     */
    public String buildOptimizationPrompt(String sql, List<OptimizationIssueDto> issues,
                                          List<IndexSuggestionDto> suggestions) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
        Bạn là chuyên gia tối ưu hiệu năng MySQL.

        Nhiệm vụ:
        VIẾT LẠI các phát hiện hiệu năng ĐÃ ĐƯỢC PHÂN TÍCH SẴN bên dưới (dựa
        trên EXPLAIN thực tế của MySQL) thành một đoạn nhận xét 2-4 câu tiếng
        Việt tự nhiên, dễ hiểu cho người không rành kỹ thuật sâu.

        QUY TẮC BẮT BUỘC:

        1. TUYỆT ĐỐI không bịa thêm vấn đề hiệu năng nào ngoài danh sách bên
           dưới, không suy đoán nguyên nhân khác.
        2. TUYỆT ĐỐI không tự đề xuất index nào khác ngoài danh sách gợi ý
           bên dưới - danh sách đó đã dựa trên index THẬT đang tồn tại.
        3. Nếu danh sách vấn đề rỗng, chỉ cần xác nhận SQL không có vấn đề
           hiệu năng rõ rệt, không suy diễn thêm.
        4. CHỈ trả về đoạn nhận xét, không thêm tiêu đề, không dùng markdown,
           không liệt kê gạch đầu dòng.

        """);

        sb.append("SQL đang phân tích:\n").append(sql).append("\n\n");

        if (issues.isEmpty()) {
            sb.append("VẤN ĐỀ PHÁT HIỆN ĐƯỢC: Không có.\n");
        } else {
            sb.append("VẤN ĐỀ PHÁT HIỆN ĐƯỢC (đáng tin cậy 100%, không được đổi khác):\n");
            for (OptimizationIssueDto issue : issues) {
                sb.append("- [").append(issue.getSeverity()).append("] ")
                        .append(issue.getDescription()).append("\n");
            }
        }

        if (!suggestions.isEmpty()) {
            sb.append("\nGỢI Ý INDEX (đáng tin cậy 100%, không được đổi khác):\n");
            for (IndexSuggestionDto s : suggestions) {
                sb.append("- Bảng ").append(s.getTable())
                        .append(", cột: ").append(String.join(", ", s.getColumns()))
                        .append(" (").append(s.getReason()).append(") -> ")
                        .append(s.getCreateIndexSql()).append("\n");
            }
        }

        sb.append("\nHãy viết nhận xét (2-4 câu) dựa ĐÚNG và CHỈ trên thông tin ở trên:");

        return sb.toString();
    }

    private String trendToVietnamese(TrendDirection trend) {
        return switch (trend) {
            case INCREASING -> "tăng dần";
            case DECREASING -> "giảm dần";
            case STABLE -> "ổn định, không tăng giảm rõ rệt";
        };
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

    public String buildSuggestedQuestionsPrompt(DatabaseSchema schema, int maxQuestions) {
        StringBuilder sb = new StringBuilder();

        sb.append("Bạn là chuyên gia phân tích dữ liệu.\n\n");
        sb.append("Dưới đây là schema của một cơ sở dữ liệu:\n\n");
        appendSchema(sb, schema);

        sb.append("""
    Nhiệm vụ:
    Đề xuất tối đa %d câu hỏi bằng TIẾNG VIỆT mà người dùng có thể hỏi một hệ thống
    Text-to-SQL, CHỈ dựa trên các bảng/cột đã liệt kê ở trên.

    YÊU CẦU BẮT BUỘC:
    1. Câu hỏi ngắn gọn, tự nhiên, giống người dùng thật sự sẽ gõ.
    2. Đa dạng loại: đếm số lượng, liệt kê top N, tính tổng/trung bình,
       lọc theo điều kiện, và ít nhất 1 câu cần JOIN 2 bảng (nếu schema có khóa ngoại).
    3. TUYỆT ĐỐI không hỏi về bảng/cột không có trong schema ở trên.
    4. CHỈ trả về một JSON array of string, KHÔNG markdown, KHÔNG giải thích thêm.
       Ví dụ định dạng đúng: ["Có bao nhiêu khách hàng?", "Top 5 sản phẩm bán chạy nhất là gì?"]
    """.formatted(maxQuestions));

        return sb.toString();
    }

    // ===== THÊM MỚI: overload có lịch sử hội thoại =====
    public String buildGenerationPrompt(String question, DatabaseSchema schema, String conversationHistory) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Bạn là chuyên gia ").append(dialectLabel).append(" và Text-to-SQL.\n");

        sb.append("""

    Nhiệm vụ:
    Chuyển câu hỏi tiếng Việt thành DUY NHẤT một câu lệnh SQL SELECT hợp lệ.
    """);

        // Chỉ chèn block lịch sử nếu thực sự có (tránh phình prompt vô ích
        // ở lượt hỏi đầu tiên của conversation).
        if (conversationHistory != null && !conversationHistory.isBlank()) {
            sb.append("""

        LỊCH SỬ HỘI THOẠI GẦN NHẤT (để hiểu ngữ cảnh câu hỏi nối tiếp,
        ví dụ "so sánh nó với tháng 2", "còn năm ngoái thì sao"):
        """);
            sb.append(conversationHistory);
            sb.append("""

        LƯU Ý: câu hỏi hiện tại có thể tham chiếu ngầm tới câu hỏi/SQL
        phía trên (đại từ "nó", "đó", "cái đó"...). Hãy suy luận đúng
        ý định dựa trên lịch sử, nhưng CHỈ generate SQL cho câu hỏi
        HIỆN TẠI, không lặp lại SQL cũ.
        """);
        }

        sb.append("""

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
        13. KHÔNG được tự ý dùng DISTINCT. Chỉ dùng DISTINCT khi câu hỏi có từ: không trùng, duy nhất, distinct.
    14. Không dịch giá trị enum sang tiếng Việt.
    """);

        sb.append(dialectSyntaxNote(dialectLabel));

        sb.append("SCHEMA:\n");
        appendSchema(sb, schema);

        sb.append("\nCÂU HỎI HIỆN TẠI: ").append(question).append("\nSQL:");

        return sb.toString();
    }
}