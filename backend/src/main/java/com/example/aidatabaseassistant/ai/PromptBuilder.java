package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.dto.AnomalyDto;
import com.example.aidatabaseassistant.dto.IndexSuggestionDto;
import com.example.aidatabaseassistant.dto.OptimizationIssueDto;
import com.example.aidatabaseassistant.dto.TrendDirection;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.insight.DataInsightFacts;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class PromptBuilder {

    // =========================================================
    // DIALECT HELPERS
    // =========================================================

    private static final String DEFAULT_DIALECT_LABEL = "MySQL";

    /**
     * Kiểm tra database có phải PostgreSQL hay không.
     *
     * MySQL và Excel vẫn giữ hành vi mặc định như trước.
     */
    private boolean isPostgres(String dbType) {
        return "postgres".equalsIgnoreCase(dbType)
                || "postgresql".equalsIgnoreCase(dbType);
    }

    /**
     * Xác định dialect trực tiếp từ DatabaseSchema.
     *
     * - PostgreSQL -> PostgreSQL
     * - MySQL -> MySQL
     * - Excel -> MySQL-compatible prompt như hành vi cũ
     * - null -> MySQL
     */
    private String resolveDialectLabel(DatabaseSchema schema) {
        if (schema != null && isPostgres(schema.getDbType())) {
            return "PostgreSQL";
        }

        return DEFAULT_DIALECT_LABEL;
    }

    /**
     * Các khác biệt cú pháp quan trọng giữa MySQL và PostgreSQL.
     *
     * Chỉ thêm block này khi database thực tế là PostgreSQL.
     */
    private String dialectSyntaxNote(String dialectLabel) {
        if (!"PostgreSQL".equals(dialectLabel)) {
            return "";
        }

        return """

        LƯU Ý CÚ PHÁP POSTGRESQL (PHẢI tuân theo):
        - Nếu cần quote tên bảng/cột, dùng dấu ngoặc kép "..." (KHÔNG dùng backtick `...`).
        - Dùng COALESCE(...) thay vì IFNULL(...).
        - Dùng STRING_AGG(cột, ', ') thay vì GROUP_CONCAT(cột).
        - Dùng TO_CHAR(cột_ngày, 'YYYY-MM-DD') thay vì DATE_FORMAT(...).
        - So sánh chuỗi phân biệt hoa/thường theo mặc định.
        - Nếu cần tìm kiếm không phân biệt hoa/thường, dùng ILIKE thay vì LIKE.
        - LIMIT n vẫn dùng bình thường.
        - Không sử dụng các hàm chỉ có trong MySQL nếu PostgreSQL không hỗ trợ.
        """;
    }

    // =========================================================
    // GENERATION PROMPT
    // =========================================================

    public String buildGenerationPrompt(
            String question,
            DatabaseSchema schema
    ) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);
        boolean english = QuestionLanguage.isEnglish(question);

        sb.append(header(dialectLabel, english));

        sb.append(
                english
                        ? generationRulesEn()
                        : generationRulesVi()
        );

        sb.append(dialectSyntaxNote(dialectLabel));

        sb.append("SCHEMA:\n");
        appendSchema(sb, schema);

        sb.append(
                english
                        ? generationExamplesEn()
                        : generationExamplesVi()
        );

        sb.append(
                english
                        ? "\nQUESTION:\n"
                        : "\nCÂU HỎI:\n"
        );

        sb.append(question);
        sb.append("\n\nSQL:");

        return sb.toString();
    }

    /**
     * Header thay đổi theo:
     * - dialect
     * - ngôn ngữ câu hỏi
     */
    private String header(
            String dialectLabel,
            boolean english
    ) {
        return english
                ? "You are a " + dialectLabel + " and Text-to-SQL expert.\n"
                : "Bạn là chuyên gia " + dialectLabel + " và Text-to-SQL.\n";
    }

    // =========================================================
    // SQL GENERATION RULES - VIETNAMESE
    // =========================================================

    private String generationRulesVi() {
        return """

    NHIỆM VỤ:
    Chuyển câu hỏi của người dùng thành DUY NHẤT một câu lệnh SQL SELECT hợp lệ.

    QUY TẮC BẮT BUỘC:

    1. Chỉ trả về SQL, không giải thích.

    2. Không dùng markdown hoặc ```sql.

    3. Chỉ sử dụng SELECT.

    4. Tuyệt đối KHÔNG dùng:
       INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE,
       CREATE, RENAME, USE.

    5. KHÔNG dùng SELECT * nếu câu hỏi không yêu cầu toàn bộ thông tin.

    6. CHỈ SELECT các cột và chỉ số mà người dùng thực sự yêu cầu
       xuất hiện trong kết quả.

       Không SELECT thêm cột chỉ vì cột đó được sử dụng cho:
       - ORDER BY
       - GROUP BY
       - WHERE
       - JOIN
       - lọc hoặc xếp hạng

       Nếu một cột chỉ được dùng để tính toán, lọc, sắp xếp,
       gom nhóm hoặc JOIN thì KHÔNG đưa cột đó vào SELECT,
       trừ khi người dùng yêu cầu giá trị đó trong kết quả.

    7. Nếu câu hỏi yêu cầu một giá trị được TÍNH TOÁN và muốn giá trị đó
       xuất hiện trong kết quả thì PHẢI SELECT chính giá trị đó.

       Nếu giá trị tính toán CHỈ được dùng để lọc hoặc xác định đối tượng
       (không liên quan gì đến việc xếp hạng/so sánh giá trị đó) thì
       KHÔNG cần SELECT giá trị đó.

       QUAN TRỌNG: câu hỏi dạng "top N ... cao nhất/thấp nhất/nhiều
       nhất/ít nhất" LUÔN được coi là đang hỏi VỀ chính giá trị đó (giá,
       doanh thu, số lượng, số đơn...), nên PHẢI SELECT giá trị dùng để
       xếp hạng, dù câu hỏi không lặp lại tên cột. Không SELECT giá trị
       này sẽ khiến kết quả không có cột số liệu nào để vẽ biểu đồ/phân
       tích - luôn ưu tiên SELECT thêm giá trị xếp hạng trừ khi thực sự
       chắc chắn người dùng chỉ muốn mỗi tên/định danh đối tượng.

       Ví dụ:
       - "tổng doanh thu" -> SUM(...) AS total_revenue
       - "doanh thu của từng khách hàng" ->
         SELECT tên khách hàng + SUM(...) AS total_revenue
       - "top 5 sản phẩm có doanh thu cao nhất" ->
         SELECT tên sản phẩm + SUM(...) AS total_revenue
       - "sản phẩm nào có giá cao nhất?" ->
         SELECT product_name, unit_price
         FROM products
         ORDER BY unit_price DESC
         LIMIT 1;
       - "khách hàng nào đặt nhiều đơn nhất?" ->
         SELECT c.full_name, COUNT(o.order_id) AS so_don
         FROM customers c
         JOIN orders o ON c.customer_id = o.customer_id
         GROUP BY c.customer_id, c.full_name
         ORDER BY so_don DESC
         LIMIT 1;

    8. Nếu câu hỏi yêu cầu nhiều chỉ số thì PHẢI SELECT tất cả các chỉ số đó.

       MỖI chỉ số phải có alias RIÊNG BIỆT.

       TUYỆT ĐỐI KHÔNG được dùng cùng một alias cho hai biểu thức
       hoặc hai metric khác nhau trong cùng một SELECT.

       Ví dụ SAI:

       SELECT
           SUM(amount) AS total,
           COUNT(*) AS total
       FROM orders;

       Ví dụ ĐÚNG:

       SELECT
           SUM(amount) AS total_revenue,
           COUNT(*) AS total_orders
       FROM orders;

    9. Alias phải mô tả rõ ràng và nhất quán ý nghĩa của chỉ số.

       Ví dụ:
       - doanh thu -> total_revenue
       - số đơn hàng -> total_orders
       - số lượng -> total_quantity
       - giá trung bình -> average_price
       - số lượng bản ghi -> total_count

       Không dùng alias chung chung như "total" khi câu hỏi yêu cầu
       nhiều chỉ số hoặc có nhiều phép tính trong cùng một SELECT.

    10. Nếu câu hỏi yêu cầu:
        "cao nhất", "thấp nhất", "lớn nhất", "nhỏ nhất",
        "nhiều nhất", "ít nhất" hoặc "top N":

        - PHẢI SELECT đối tượng được hỏi.
        - Chỉ SELECT giá trị dùng để xếp hạng nếu người dùng
          thực sự yêu cầu giá trị đó xuất hiện trong kết quả.
        - Giá trị chỉ dùng để ORDER BY, WHERE, HAVING hoặc xác định
          thứ hạng thì KHÔNG cần SELECT.
        - Dùng ORDER BY đúng giá trị dùng để xếp hạng.
        - Dùng LIMIT khi câu hỏi yêu cầu số lượng cụ thể.
    11. Dùng đúng tên bảng và cột trong schema.

    12. Nếu cần JOIN thì dùng khóa ngoại được khai báo trong schema.

    13. Luôn ưu tiên SQL ngắn gọn, chính xác và dễ đọc.

    14. Nếu câu hỏi hỏi:
        "đơn hàng nào", "khách hàng nào", "sản phẩm nào"
        và yêu cầu toàn bộ thông tin thì phải dùng SELECT *.

    15. Chỉ chọn một vài cột khi câu hỏi nêu rõ các cột cần lấy.

    16. KHÔNG được tự ý dùng DISTINCT.

        Chỉ dùng DISTINCT khi câu hỏi có ý nghĩa:
        - không trùng
        - duy nhất
        - distinct

    17. Không dịch giá trị enum sang tiếng Việt.

        Giữ nguyên giá trị đúng như dữ liệu trong database.

                18. Khi sử dụng GROUP BY và ORDER BY một giá trị tổng hợp,
                                                                                                 có thể đặt alias cho giá trị tổng hợp và sử dụng alias đó trong ORDER BY.

                                                                                                 Tuy nhiên, nếu giá trị tổng hợp CHỈ được dùng để xếp hạng
                                                                                                 và người dùng không yêu cầu metric đó xuất hiện trong kết quả,
                                                                                                 KHÔNG thêm giá trị tổng hợp vào SELECT chỉ để tạo alias.

                                                                                                 Ví dụ:

                                                                                                 SELECT c.full_name
                                                                                                 FROM customers c
                                                                                                 JOIN orders o ON c.customer_id = o.customer_id
                                                                                                 GROUP BY c.customer_id, c.full_name
                                                                                                 ORDER BY COUNT(o.order_id) DESC
                                                                                                 LIMIT 1;

    19. Không tự ý thêm điều kiện WHERE không được yêu cầu.

    20. Không tự ý thêm JOIN nếu không cần thiết để trả lời câu hỏi.

    21. Không tự ý giới hạn kết quả bằng LIMIT nếu người dùng không yêu cầu giới hạn.

    22. Không tự ý đổi tên bảng hoặc tên cột.

    23. Nếu câu hỏi không đủ rõ để xác định một cột,
        ưu tiên thông tin trong schema và ngữ cảnh hội thoại gần nhất.

    """;
    }

    // =========================================================
    // SQL GENERATION RULES - ENGLISH
    // =========================================================

    private String generationRulesEn() {
        return """

    TASK:
    Convert the user's question into EXACTLY ONE valid SQL SELECT statement.

    MANDATORY RULES:

    1. Return ONLY the SQL statement, no explanation.

    2. Do not use markdown or ```sql code fences.

    3. Only SELECT statements are allowed.

    4. NEVER use INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, CREATE, RENAME, USE.

    5. Do NOT use SELECT * unless the question actually asks for the full record.

                6. ONLY SELECT columns and metrics that the user actually asks
                                             to appear in the result.

                                             Do NOT SELECT extra columns merely because they are used for:
                                             - ORDER BY
                                             - GROUP BY
                                             - WHERE
                                             - JOIN
                                             - filtering or ranking

                                             If a column is only used for calculation, filtering, sorting,
                                             grouping, joining, or ranking, do NOT include it in SELECT
                                             unless the user explicitly asks for that value in the result.

                7. If the question asks for a CALCULATED VALUE and expects that value
                                                                        to appear in the result, SELECT that value.

                                                                        If the calculated value is ONLY used for filtering or identifying
                                                                        an entity (unrelated to ranking/comparing that value), it does
                                                                        NOT need to be selected.

                                                                        IMPORTANT: questions shaped like "top N ... highest/lowest/most/
                                                                        least" are ALWAYS considered to be asking ABOUT that value (price,
                                                                        revenue, quantity, order count...), so the ranking value MUST be
                                                                        SELECTed even if the question does not repeat the column name.
                                                                        Omitting it leaves the result with no numeric column to chart or
                                                                        analyze - always prefer SELECTing the ranking value unless it is
                                                                        clearly certain the user only wants the entity's name/identity.

                                                                        Examples:
                                                                        - "total revenue" -> SUM(...) AS total_revenue
                                                                        - "revenue for each customer" ->
                                                                          SELECT customer name + SUM(...) AS total_revenue
                                                                        - "top 5 products by revenue" ->
                                                                          SELECT product name + SUM(...) AS total_revenue
                                                                        - "Which product has the highest price?" ->
                                                                          SELECT product_name, unit_price
                                                                          FROM products
                                                                          ORDER BY unit_price DESC
                                                                          LIMIT 1;
                                                                        - "Which customer has placed the most orders?" ->
                                                                          SELECT c.full_name, COUNT(o.order_id) AS order_count
                                                                          FROM customers c
                                                                          JOIN orders o ON c.customer_id = o.customer_id
                                                                          GROUP BY c.customer_id, c.full_name
                                                                          ORDER BY order_count DESC
                                                                          LIMIT 1;

                8. If the question asks for multiple metrics, SELECT ALL requested metrics.

                                                                         EVERY calculated metric MUST have a UNIQUE alias.

                                                                         NEVER use the same alias for two different expressions or metrics
                                                                         in the same SELECT statement.

                                                                         WRONG:

                                                                         SELECT
                                                                             SUM(amount) AS total,
                                                                             COUNT(*) AS total
                                                                         FROM orders;

                                                                         CORRECT:

                                                                         SELECT
                                                                             SUM(amount) AS total_revenue,
                                                                             COUNT(*) AS total_orders
                                                                         FROM orders;

    9. Use meaningful and unique aliases for calculated metrics.

       Examples:
       - revenue -> total_revenue or total
       - number of orders -> total_orders
       - quantity -> total_quantity
       - average price -> average_price
       - count of records -> total

                10. If the question asks for:
                                                                        "highest", "lowest", "largest", "smallest",
                                                                        "most", "least", or "top N":

                                                                        - MUST SELECT the requested entity.
                                                                        - SELECT the ranking value ONLY if the user explicitly asks
                                                                          for that value to appear in the result.
                                                                        - A value used only for ORDER BY, WHERE, HAVING, or ranking
                                                                          does NOT need to appear in SELECT.
                                                                        - ORDER BY the correct ranking value.
                                                                        - Use LIMIT when a specific number is requested.

    11. Use the exact table and column names from the schema.

    12. Use foreign keys defined in the schema whenever a JOIN is needed.

    13. Always prefer the shortest, most accurate SQL possible.

    14. If the question asks:
        "which orders", "which customers", "which products"
        and clearly wants the full record, use SELECT *.

    15. Only select a subset of columns when the question explicitly names
        the fields it wants.

    16. Do NOT add DISTINCT on your own.

        Only use it when explicitly requested:
        - distinct
        - unique
        - no duplicates

    17. Do NOT translate enum values into another language.

        Keep them exactly as stored in the database.

                18. When using GROUP BY and ORDER BY an aggregate value, you may use
                                                                                                an alias for the aggregate in ORDER BY.

                                                                                                However, if the aggregate is used ONLY for ranking and the user
                                                                                                does not ask for that metric in the result, do NOT add that
                                                                                                aggregate to SELECT just to create an alias.

                                                                                                Example:

                                                                                                SELECT c.full_name
                                                                                                FROM customers c
                                                                                                JOIN orders o ON c.customer_id = o.customer_id
                                                                                                GROUP BY c.customer_id, c.full_name
                                                                                                ORDER BY COUNT(o.order_id) DESC
                                                                                                LIMIT 1;

    19. Do not add WHERE conditions that were not requested.

    20. Do not add unnecessary JOINs.

    21. Do not add LIMIT unless the user asks for a specific limit.

    22. Do not rename tables or columns.

    23. If the question is ambiguous, use the schema and recent conversation
        context to infer the intended meaning.

    """;
    }

    // =========================================================
    // GENERATION EXAMPLES - VIETNAMESE
    // =========================================================

    private String generationExamplesVi() {
        return """

    VÍ DỤ:

    Q: Khách hàng nào sống ở Hà Nội?
    SQL:
    SELECT full_name
    FROM customers
    WHERE city = 'Hà Nội';

    Q: 5 khách hàng đăng ký gần đây nhất
    SQL:
    SELECT full_name, created_at
    FROM customers
    ORDER BY created_at DESC
    LIMIT 5;

    Q: Top 5 sản phẩm có doanh thu cao nhất
    SQL:
    SELECT p.product_name,
           SUM(oi.quantity * oi.unit_price) AS total_revenue
    FROM products p
    JOIN order_items oi
      ON p.product_id = oi.product_id
    GROUP BY p.product_id, p.product_name
    ORDER BY total_revenue DESC
    LIMIT 5;

    Q: Tính tổng doanh thu và số lượng đơn hàng của từng khách hàng,
       sắp xếp theo doanh thu giảm dần
    SQL:
    SELECT c.full_name,
           SUM(oi.quantity * oi.unit_price) AS total_revenue,
           COUNT(DISTINCT o.order_id) AS total_orders
    FROM customers c
    JOIN orders o
      ON c.customer_id = o.customer_id
    JOIN order_items oi
      ON o.order_id = oi.order_id
    GROUP BY c.customer_id, c.full_name
    ORDER BY total_revenue DESC;

    GIÁ TRỊ DỮ LIỆU QUAN TRỌNG:

    Bảng orders.status chỉ có các giá trị:
    - completed
    - pending
    - cancelled

    Không được dịch các giá trị này sang tiếng Việt.

    """;
    }

    // =========================================================
    // GENERATION EXAMPLES - ENGLISH
    // =========================================================

    private String generationExamplesEn() {
        return """

    EXAMPLES:

    Q: Which customers live in Hanoi?
    SQL:
    SELECT full_name
    FROM customers
    WHERE city = 'Hanoi';

    Q: 5 most recently registered customers
    SQL:
    SELECT full_name, created_at
    FROM customers
    ORDER BY created_at DESC
    LIMIT 5;

    Q: Top 5 products by revenue
    SQL:
    SELECT p.product_name,
           SUM(oi.quantity * oi.unit_price) AS total_revenue
    FROM products p
    JOIN order_items oi
      ON p.product_id = oi.product_id
    GROUP BY p.product_id, p.product_name
    ORDER BY total_revenue DESC
    LIMIT 5;

    Q: Calculate total revenue and number of orders for each customer
    SQL:
    SELECT c.full_name,
           SUM(oi.quantity * oi.unit_price) AS total_revenue,
           COUNT(DISTINCT o.order_id) AS total_orders
    FROM customers c
    JOIN orders o
      ON c.customer_id = o.customer_id
    JOIN order_items oi
      ON o.order_id = oi.order_id
    GROUP BY c.customer_id, c.full_name
    ORDER BY total_revenue DESC;

    IMPORTANT DATA VALUES:

    The orders.status column only ever contains these exact values:
    - completed
    - pending
    - cancelled

    Do NOT translate or rewrite them.

    """;
    }

    // =========================================================
    // CORRECTION PROMPT
    // =========================================================

    public String buildCorrectionPrompt(
            String previousSql,
            String errorMessage,
            DatabaseSchema schema
    ) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Câu SQL sau đây chạy bị lỗi trên ")
                .append(dialectLabel)
                .append(".\n\n");

        sb.append("""
        Hãy sửa lại cho đúng dựa vào schema và lỗi thực tế.

        QUY TẮC BẮT BUỘC:
        1. Chỉ trả về DUY NHẤT một câu SQL SELECT đã sửa.
        2. Không giải thích.
        3. Không dùng markdown hoặc ```sql.
        4. Không dùng INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE,
           CREATE, RENAME, USE.
        5. Dùng đúng tên bảng và cột trong schema.
        6. Chỉ sửa SQL dựa trên lỗi thực tế và thông tin được cung cấp.
        7. Không tự ý thêm chức năng không liên quan.
        8. Không tự ý thêm DISTINCT.
        9. Không tự ý thêm LIMIT nếu không cần thiết.
        10. Nếu SQL cũ đã đúng về mặt ý nghĩa, chỉ sửa phần gây lỗi cú pháp
            hoặc tương thích database.

        """);

        sb.append(dialectSyntaxNote(dialectLabel));

        sb.append("SCHEMA:\n");
        appendSchema(sb, schema);

        sb.append("\nSQL CŨ:\n");
        sb.append(previousSql);

        sb.append("\n\nLỖI:\n");
        sb.append(errorMessage);

        sb.append("\n\nSQL ĐÃ SỬA:");

        return sb.toString();
    }

    // =========================================================
    // CORRECTION PROMPT WITH CONVERSATION HISTORY
    // =========================================================

    public String buildCorrectionPrompt(
            String previousSql,
            String errorMessage,
            DatabaseSchema schema,
            String conversationHistory
    ) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Câu SQL sau đây chạy bị lỗi trên ")
                .append(dialectLabel)
                .append(".\n\n");

        sb.append("""
        Hãy sửa lại cho đúng dựa trên schema, lỗi thực tế
        và lịch sử hội thoại gần nhất.

        QUY TẮC BẮT BUỘC:
        1. Chỉ trả về DUY NHẤT một câu SQL SELECT đã sửa.
        2. Không giải thích.
        3. Không dùng markdown hoặc ```sql.
        4. Không dùng INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE,
           CREATE, RENAME, USE.
        5. Dùng đúng tên bảng và cột trong schema.
        6. Chỉ sửa SQL dựa trên lỗi thực tế và thông tin được cung cấp.
        7. Lịch sử hội thoại chỉ dùng để hiểu ngữ cảnh của câu hỏi hiện tại.
        8. Không lặp lại SQL cũ nếu không cần thiết.
        9. Không tự ý thêm DISTINCT.
        10. Không tự ý thêm LIMIT nếu không được yêu cầu.
        11. Không tự ý thay đổi ý nghĩa của câu hỏi hiện tại.

        """);

        sb.append(dialectSyntaxNote(dialectLabel));

        if (conversationHistory != null
                && !conversationHistory.isBlank()) {

            sb.append("""
                LỊCH SỬ HỘI THOẠI GẦN NHẤT:
                """);

            sb.append(conversationHistory);

            sb.append("\n\n");
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

    // =========================================================
    // EXPLANATION PROMPT
    // =========================================================

    public String buildExplanationPrompt(
            String sql,
            DatabaseSchema schema
    ) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);

        sb.append("Bạn là chuyên gia ")
                .append(dialectLabel)
                .append(". ");

        sb.append("""
        Nhiệm vụ của bạn là giải thích câu SQL dưới đây bằng tiếng Việt,
        đơn giản để người không rành kỹ thuật cũng hiểu.

        CHIA GIẢI THÍCH THEO TỪNG MỆNH ĐỀ xuất hiện trong câu SQL
        (ví dụ: SELECT, FROM, JOIN, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT).

        CHỈ liệt kê những mệnh đề THỰC SỰ có mặt trong câu SQL.
        Không được bịa thêm mệnh đề.

        """);

        if (schema != null) {
            sb.append("SCHEMA (dùng để giải thích đúng ý nghĩa tên bảng/cột):\n");

            appendSchema(sb, schema);

            sb.append("\n");
        }

        sb.append("""
        CHỈ trả về JSON hợp lệ đúng định dạng sau,
        KHÔNG thêm bất kỳ ký tự nào khác,
        KHÔNG dùng markdown code block:

        {
          "summary": "1 câu tóm tắt tổng quan câu SQL này làm gì",
          "steps": [
            {
              "clause": "phần SQL của mệnh đề này",
              "explanation": "giải thích ngắn gọn, dễ hiểu"
            }
          ]
        }

        """);

        sb.append("Câu SQL cần giải thích:\n")
                .append(sql);

        return sb.toString();
    }

    // =========================================================
    // CHART REASON PROMPT
    // =========================================================

    public String buildChartReasonPrompt(
            String chartType,
            String dimensionColumn,
            List<String> numericColumns,
            int rowCount,
            String detectedPattern
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("Hệ thống đã dùng thuật toán để tự động chọn loại biểu đồ ")
                .append(chartType)
                .append(" cho một kết quả truy vấn dữ liệu, dựa trên thông tin sau:\n");

        sb.append("- Trục X (danh mục/thời gian): ")
                .append(dimensionColumn)
                .append("\n");

        sb.append("- Cột số liệu: ")
                .append(String.join(", ", numericColumns))
                .append("\n");

        sb.append("- Số dòng dữ liệu: ")
                .append(rowCount)
                .append("\n");

        sb.append("- Lý do kỹ thuật đã phát hiện: ")
                .append(detectedPattern)
                .append("\n\n");

        sb.append("""
        Hãy viết lại lý do trên thành 1-2 câu tiếng Việt tự nhiên,
        ngắn gọn, dễ hiểu cho người dùng cuối.

        CHỈ trả về đúng câu giải thích.
        Không thêm tiêu đề.
        Không dùng markdown.
        Không lặp lại số liệu thô.
        Không tự thêm nhận xét về xu hướng nếu hệ thống chưa cung cấp.
        """);

        return sb.toString();
    }

    // =========================================================
    // DATA INSIGHT PROMPT
    // =========================================================

    /**
     * Gemini chỉ được diễn đạt lại các số liệu đã được
     * DataInsightAnalyzer tính toán sẵn.
     *
     * Gemini không được tự tính toán hoặc tạo thêm số liệu.
     */
    public String buildDataInsightPrompt(
            DataInsightFacts facts
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
        Bạn là chuyên gia phân tích dữ liệu kinh doanh.

        Nhiệm vụ:
        VIẾT LẠI các số liệu ĐÃ ĐƯỢC TÍNH SẴN bên dưới thành một đoạn
        tóm tắt 2-3 câu tiếng Việt tự nhiên, mạch lạc, dễ hiểu cho người
        không rành kỹ thuật.

        QUY TẮC BẮT BUỘC:

        1. TUYỆT ĐỐI không bịa số liệu.
           CHỈ được nhắc tới đúng những con số, tên danh mục,
           mốc thời gian được liệt kê bên dưới.

        2. TUYỆT ĐỐI không tự tính toán lại.
           Không cộng, trừ, nhân, chia, tính lại phần trăm
           hoặc làm tròn khác đi con số đã cho.

        3. Mục nào KHÔNG được cung cấp bên dưới
           (ví dụ: không có tăng trưởng, không có tỷ trọng,
           không có bất thường) thì ĐỪNG nhắc tới mục đó.

        4. Không dịch hoặc đổi tên cột, tên danh mục sang từ khác.

        5. CHỈ trả về đoạn văn tóm tắt.
           Không thêm tiêu đề.
           Không dùng markdown.
           Không liệt kê gạch đầu dòng.
           Không giải thích thêm ngoài đoạn tóm tắt.

        """);

        sb.append("""
        SỐ LIỆU ĐÃ TÍNH SẴN
        (đáng tin cậy 100%, không được thay đổi):

        """);

        sb.append("- Cột số liệu: ")
                .append(facts.getNumericColumn())
                .append("\n");

        sb.append("- Cột danh mục/thời gian: ")
                .append(facts.getDimensionColumn())
                .append("\n");

        sb.append(String.format(
                Locale.ROOT,
                "- Giá trị cao nhất: %s = %.2f%n",
                facts.getHighestLabel(),
                facts.getHighestValue()
        ));

        sb.append(String.format(
                Locale.ROOT,
                "- Giá trị thấp nhất: %s = %.2f%n",
                facts.getLowestLabel(),
                facts.getLowestValue()
        ));

        if (facts.getGrowthPercent() != null) {

            if (facts.getPeriodStartLabel() != null
                    && facts.getPeriodEndLabel() != null) {

                sb.append(String.format(
                        Locale.ROOT,
                        "- Tăng trưởng từ mốc %s đến mốc %s: %.1f%% "
                                + "(CHỈ được dùng đúng 2 mốc thời gian này "
                                + "khi nhắc tới con số tăng trưởng, "
                                + "TUYỆT ĐỐI không dùng nhầm mốc khác)%n",
                        facts.getPeriodStartLabel(),
                        facts.getPeriodEndLabel(),
                        facts.getGrowthPercent()
                ));

            } else {

                sb.append(String.format(
                        Locale.ROOT,
                        "- Tăng trưởng từ đầu đến cuối kỳ: %.1f%%%n",
                        facts.getGrowthPercent()
                ));
            }
        }

        if (facts.getTrend() != null) {

            sb.append("- Xu hướng: ")
                    .append(trendToVietnamese(facts.getTrend()))
                    .append("\n");
        }

        if (facts.getTopSharePercent() != null) {

            sb.append(String.format(
                    Locale.ROOT,
                    "- Danh mục chiếm tỷ trọng cao nhất: %s = %.1f%% tổng%n",
                    facts.getTopShareLabel(),
                    facts.getTopSharePercent()
            ));
        }

        if (facts.getAnomalies() != null
                && !facts.getAnomalies().isEmpty()) {

            sb.append("- Điểm bất thường phát hiện được:\n");

            for (AnomalyDto anomaly : facts.getAnomalies()) {

                sb.append(String.format(
                        Locale.ROOT,
                        "  + %s: %.2f (%s)%n",
                        anomaly.getLabel(),
                        anomaly.getValue(),
                        anomaly.getDirection()
                ));
            }
        }

        sb.append("""

        Hãy viết đoạn tóm tắt 2-3 câu dựa ĐÚNG và CHỈ trên
        số liệu ở trên.
        """);

        return sb.toString();
    }

    // =========================================================
    // OPTIMIZATION PROMPT
    // =========================================================

    /**
     * Gemini chỉ diễn đạt lại các phát hiện đã được
     * SqlOptimizationAnalyzer tính toán từ EXPLAIN thực tế.
     *
     * Gemini không được tự tạo thêm issue hoặc index.
     */
    public String buildOptimizationPrompt(
            String sql,
            List<OptimizationIssueDto> issues,
            List<IndexSuggestionDto> suggestions
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
        Bạn là chuyên gia tối ưu hiệu năng cơ sở dữ liệu.

        Nhiệm vụ:
        VIẾT LẠI các phát hiện hiệu năng ĐÃ ĐƯỢC PHÂN TÍCH SẴN bên dưới
        dựa trên EXPLAIN thực tế của database thành một đoạn nhận xét
        2-4 câu tiếng Việt tự nhiên, dễ hiểu cho người không rành kỹ thuật sâu.

        QUY TẮC BẮT BUỘC:

        1. TUYỆT ĐỐI không bịa thêm vấn đề hiệu năng nào ngoài danh sách
           bên dưới.

        2. TUYỆT ĐỐI không tự đề xuất index nào khác ngoài danh sách
           gợi ý bên dưới.

        3. Nếu danh sách vấn đề rỗng, chỉ cần xác nhận SQL không có
           vấn đề hiệu năng rõ rệt dựa trên kết quả phân tích.

        4. CHỈ sử dụng thông tin đã được cung cấp.

        5. Không tự tính toán lại số liệu.

        6. CHỈ trả về đoạn nhận xét.
           Không thêm tiêu đề.
           Không dùng markdown.
           Không liệt kê gạch đầu dòng.

        """);

        sb.append("SQL đang phân tích:\n")
                .append(sql)
                .append("\n\n");

        if (issues == null || issues.isEmpty()) {

            sb.append("VẤN ĐỀ PHÁT HIỆN ĐƯỢC: Không có.\n");

        } else {

            sb.append("""
                    VẤN ĐỀ PHÁT HIỆN ĐƯỢC
                    (đáng tin cậy 100%, không được đổi khác):
                    """);

            for (OptimizationIssueDto issue : issues) {

                sb.append("- [")
                        .append(issue.getSeverity())
                        .append("] ")
                        .append(issue.getDescription())
                        .append("\n");
            }
        }

        if (suggestions != null && !suggestions.isEmpty()) {

            sb.append("""

                    GỢI Ý INDEX
                    (đáng tin cậy 100%, không được đổi khác):
                    """);

            for (IndexSuggestionDto suggestion : suggestions) {

                sb.append("- Bảng ")
                        .append(suggestion.getTable())
                        .append(", cột: ")
                        .append(String.join(
                                ", ",
                                suggestion.getColumns()
                        ))
                        .append(" (")
                        .append(suggestion.getReason())
                        .append(") -> ")
                        .append(suggestion.getCreateIndexSql())
                        .append("\n");
            }
        }

        sb.append("""

        Hãy viết nhận xét 2-4 câu dựa ĐÚNG và CHỈ trên
        thông tin ở trên.
        """);

        return sb.toString();
    }

    // =========================================================
    // TREND
    // =========================================================

    private String trendToVietnamese(
            TrendDirection trend
    ) {
        return switch (trend) {
            case UP -> "tăng dần";
            case DOWN -> "giảm dần";
            case FLAT -> "ổn định, không tăng giảm rõ rệt";
        };
    }

    // =========================================================
    // SCHEMA APPENDER
    // =========================================================

    private void appendSchema(
            StringBuilder sb,
            DatabaseSchema schema
    ) {
        if (schema == null
                || schema.getTables() == null
                || schema.getTables().isEmpty()) {

            sb.append("(Không có schema)\n");
            return;
        }

        for (TableMetadata table : schema.getTables()) {

            sb.append("TABLE ")
                    .append(table.getName());

            if (table.getDescription() != null
                    && !table.getDescription().isBlank()) {

                sb.append(" // ")
                        .append(table.getDescription());
            }

            sb.append("\n");

            if (table.getColumns() == null) {
                sb.append("\n");
                continue;
            }

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

                if (column.getDescription() != null
                        && !column.getDescription().isBlank()) {

                    sb.append(" // ")
                            .append(column.getDescription());
                }

                sb.append("\n");
            }

            sb.append("\n");
        }
    }

    // =========================================================
    // SUGGESTED QUESTIONS
    // =========================================================

    public String buildSuggestedQuestionsPrompt(
            DatabaseSchema schema,
            int maxQuestions
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
        Bạn là chuyên gia phân tích dữ liệu.

        Dưới đây là schema của một cơ sở dữ liệu:

        """);

        appendSchema(sb, schema);

        sb.append(
                """

                NHIỆM VỤ:
                Đề xuất tối đa %d câu hỏi bằng TIẾNG VIỆT mà người dùng
                có thể hỏi một hệ thống Text-to-SQL, CHỈ dựa trên các
                bảng/cột đã liệt kê ở trên.

                YÊU CẦU BẮT BUỘC:

                1. Câu hỏi ngắn gọn, tự nhiên, giống người dùng thật sự sẽ gõ.

                2. Đa dạng loại câu hỏi:
                   - đếm số lượng
                   - liệt kê
                   - top N
                   - tính tổng
                   - tính trung bình
                   - lọc theo điều kiện
                   - ít nhất 1 câu cần JOIN 2 bảng nếu schema có khóa ngoại.

                3. TUYỆT ĐỐI không hỏi về bảng/cột không có trong schema.

                4. Không tự tạo tên bảng hoặc tên cột.

                5. Không hỏi những câu cần dữ liệu mà schema không cung cấp.

                6. CHỈ trả về một JSON array of string.

                7. KHÔNG markdown.

                8. KHÔNG giải thích thêm.

                Ví dụ định dạng đúng:
                ["Có bao nhiêu khách hàng?",
                 "Top 5 sản phẩm bán chạy nhất là gì?"]
                """.formatted(maxQuestions)
        );

        return sb.toString();
    }

    // =========================================================
    // GENERATION PROMPT WITH CONVERSATION HISTORY
    // =========================================================

    public String buildGenerationPrompt(
            String question,
            DatabaseSchema schema,
            String conversationHistory
    ) {
        StringBuilder sb = new StringBuilder();

        String dialectLabel = resolveDialectLabel(schema);
        boolean english = QuestionLanguage.isEnglish(question);

        sb.append(header(dialectLabel, english));

        /*
         * Lịch sử phải được đưa vào trước rules/schema để Gemini
         * hiểu context nhưng vẫn nhận rules đầy đủ.
         */
        if (conversationHistory != null
                && !conversationHistory.isBlank()) {

            sb.append(
                    english
                            ? """

                            RECENT CONVERSATION HISTORY:
                            The following conversation history is provided only
                            to understand follow-up questions such as:
                            "compare it with February",
                            "what about last year?",
                            "how many were there?"

                            """
                            : """

                            LỊCH SỬ HỘI THOẠI GẦN NHẤT:
                            Lịch sử dưới đây chỉ dùng để hiểu các câu hỏi nối tiếp
                            như:
                            "so sánh nó với tháng 2",
                            "còn năm ngoái thì sao?",
                            "có bao nhiêu?"

                            """
            );

            sb.append(conversationHistory);

            sb.append(
                    english
                            ? """

                            IMPORTANT:
                            The current question may implicitly refer to the
                            previous question or result using words such as
                            "it", "that", "those", "the same", or "last month".
                            Use the history to infer the intended meaning,
                            but generate SQL ONLY for the CURRENT question.
                            Do not repeat the previous SQL unless the current
                            question explicitly requires it.

                            """
                            : """

                            LƯU Ý:
                            Câu hỏi hiện tại có thể tham chiếu ngầm tới câu hỏi
                            hoặc kết quả trước đó bằng các từ như "nó", "đó",
                            "cái đó", "tương tự", "tháng trước"...
                            Hãy dùng lịch sử để suy luận đúng ý định,
                            nhưng CHỈ generate SQL cho câu hỏi HIỆN TẠI.
                            Không lặp lại SQL cũ nếu câu hỏi hiện tại không yêu cầu.

                            """
            );
        }

        sb.append(
                english
                        ? generationRulesEn()
                        : generationRulesVi()
        );

        sb.append(dialectSyntaxNote(dialectLabel));

        sb.append("SCHEMA:\n");
        appendSchema(sb, schema);

        if (english) {
            sb.append("\nCURRENT QUESTION: ")
                    .append(question);
        } else {
            sb.append("\nCÂU HỎI HIỆN TẠI:\n")
                    .append(question);
        }

        sb.append("\nSQL:");

        return sb.toString();
    }
}