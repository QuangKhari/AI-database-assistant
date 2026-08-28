package com.example.aidatabaseassistant.query;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SQLCorrectionService {

    private static final int MAX_RETRIES = 3;

    private final NL2SQLEngine nl2SQLEngine;
    private final QueryValidator queryValidator;
    private final QueryExecutor queryExecutor;

    /**
     * Chạy quá trình:
     *
     * 1. AI sinh SQL dựa trên filteredSchema (schema sau RAG).
     * 2. Validator kiểm tra SQL dựa trên fullSchema.
     * 3. Nếu SQL lỗi -> AI self-correct dựa trên filteredSchema.
     * 4. Tối đa MAX_RETRIES lần.
     *
     * filteredSchema:
     *      Schema đã được SchemaRetrievalService lọc
     *      -> dùng cho AI để giảm lượng schema đưa vào prompt.
     *
     * fullSchema:
     *      Toàn bộ schema thật của database
     *      -> dùng cho QueryValidator để đảm bảo không chặn
     *         những bảng/cột hợp lệ nhưng không nằm trong filtered schema.
     */
    public AttemptResult run(
            String question,
            DatabaseSchema filteredSchema,
            DatabaseSchema fullSchema,
            DatabaseConnection connection,
            String rawPassword
    ) {

        AttemptResult attemptResult = new AttemptResult();

        String currentSql;
        String lastError = null;

        /*
         * =========================================================
         * BƯỚC 1: AI SINH SQL BAN ĐẦU
         * =========================================================
         *
         * Quan trọng:
         * AI chỉ nhận filteredSchema.
         *
         * Điều này giúp RAG giảm schema context gửi cho LLM.
         */
        try {

            currentSql = nl2SQLEngine.generateSQL(
                    question,
                    filteredSchema
            );

        } catch (ReadOnlyViolationException e) {

            /*
             * Trường hợp câu hỏi chứa yêu cầu:
             * INSERT / UPDATE / DELETE / DROP / ...
             *
             * NL2SQLEngine chặn ngay từ trước khi gọi LLM.
             */

            lastError = e.getMessage();

            attemptResult.success = false;
            attemptResult.sql = null;

            attemptResult.finalResult = new QueryResultDto(
                    List.of(),
                    List.of(),
                    0,
                    0,
                    lastError
            );

            return attemptResult;
        }

        /*
         * =========================================================
         * BƯỚC 2: THỬ CHẠY SQL
         * =========================================================
         */
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

            AttemptLog log = new AttemptLog();

            log.sql = currentSql;

            try {

                /*
                 * =================================================
                 * SECURITY:
                 *
                 * Validator LUÔN nhận fullSchema.
                 *
                 * Không dùng filteredSchema ở đây.
                 *
                 * Vì filteredSchema chỉ phục vụ AI/RAG,
                 * không phải source of truth để xác định
                 * database có bảng nào.
                 * =================================================
                 */
                queryValidator.validate(
                        currentSql,
                        fullSchema
                );

                /*
                 * =================================================
                 * THỰC THI SQL
                 * =================================================
                 */
                QueryResultDto queryResult = queryExecutor.executeQuery(
                        connection.getHost(),
                        connection.getPort(),
                        connection.getDatabaseName(),
                        connection.getUsername(),
                        rawPassword,
                        currentSql
                );

                log.result = queryResult;

                /*
                 * =================================================
                 * QUERY THÀNH CÔNG
                 * =================================================
                 */
                if (queryResult.getError() == null) {

                    log.success = true;

                    attemptResult.attemptLogs.add(log);

                    attemptResult.success = true;
                    attemptResult.sql = currentSql;
                    attemptResult.finalResult = queryResult;

                    return attemptResult;
                }

                /*
                 * QueryValidator pass nhưng database
                 * trả về lỗi khi execute.
                 */
                lastError = queryResult.getError();

            } catch (ReadOnlyViolationException e) {

                /*
                 * =================================================
                 * SECURITY:
                 *
                 * Nếu AI sinh ra INSERT / UPDATE / DELETE / ...
                 * thì DỪNG NGAY.
                 *
                 * Không cho self-correction tiếp tục.
                 * =================================================
                 */

                lastError = e.getMessage();

                log.result = new QueryResultDto(
                        List.of(),
                        List.of(),
                        0,
                        0,
                        lastError
                );

                log.success = false;

                attemptResult.attemptLogs.add(log);

                attemptResult.success = false;
                attemptResult.sql = currentSql;

                attemptResult.finalResult = new QueryResultDto(
                        List.of(),
                        List.of(),
                        0,
                        0,
                        lastError
                );

                return attemptResult;

            } catch (IllegalArgumentException e) {

                /*
                 * =================================================
                 * SQL không hợp lệ hoặc không khớp schema.
                 *
                 * Ví dụ:
                 * - SQL syntax error
                 * - bảng không tồn tại
                 * - nhiều statement
                 * =================================================
                 */

                lastError = e.getMessage();

                log.result = new QueryResultDto(
                        List.of(),
                        List.of(),
                        0,
                        0,
                        lastError
                );
            }

            /*
             * Attempt hiện tại thất bại.
             */
            log.success = false;

            attemptResult.attemptLogs.add(log);

            /*
             * =================================================
             * BƯỚC 3: SELF-CORRECTION
             * =================================================
             *
             * Chỉ self-correct nếu vẫn còn attempt.
             *
             * AI tiếp tục nhận filteredSchema.
             */
            if (attempt < MAX_RETRIES) {

                currentSql = nl2SQLEngine.selfCorrect(
                        currentSql,
                        lastError,
                        filteredSchema
                );
            }
        }

        /*
         * =========================================================
         * BƯỚC 4: TẤT CẢ ATTEMPT ĐỀU THẤT BẠI
         * =========================================================
         */

        attemptResult.success = false;
        attemptResult.sql = currentSql;

        attemptResult.finalResult = new QueryResultDto(
                List.of(),
                List.of(),
                0,
                0,
                lastError
        );

        return attemptResult;
    }

    /**
     * Kết quả tổng hợp của toàn bộ quá trình query.
     */
    @Getter
    public static class AttemptResult {

        /**
         * true nếu có ít nhất một attempt chạy thành công.
         */
        boolean success;

        /**
         * SQL cuối cùng được sử dụng.
         */
        String sql;

        /**
         * Kết quả cuối cùng.
         */
        QueryResultDto finalResult;

        /**
         * Log của từng attempt.
         */
        List<AttemptLog> attemptLogs = new ArrayList<>();
    }

    /**
     * Log của một lần thử.
     */
    @Getter
    public static class AttemptLog {

        /**
         * SQL được thử ở attempt này.
         */
        String sql;

        /**
         * Attempt có thành công hay không.
         */
        boolean success;

        /**
         * Kết quả query của attempt.
         */
        QueryResultDto result;
    }
}