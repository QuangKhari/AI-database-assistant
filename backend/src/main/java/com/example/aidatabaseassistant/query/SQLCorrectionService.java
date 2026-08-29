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
         * Schema đang dùng để AI generate/self-correct.
         *
         * Bắt đầu bằng filteredSchema (RAG) để giữ prompt nhỏ.
         *
         * =========================================================
         * TẠI SAO CẦN "MỞ RỘNG" SCHEMA KHI CÓ LỖI:
         * =========================================================
         *
         * Nếu RAG (Top-K + FK expansion) bỏ sót 1 bảng cần thiết cho
         * câu hỏi, AI sẽ sinh SQL sai / tham chiếu nhầm bảng.
         *
         * Trước đây: mọi lần selfCorrect() đều dùng lại đúng
         * filteredSchema ban đầu -> AI không có thêm thông tin gì mới
         * để tự sửa -> lặp lại lỗi tương tự cho tới khi hết MAX_RETRIES.
         *
         * Bây giờ: ngay khi 1 lần thử thất bại, chuyển sang fullSchema
         * cho các lần selfCorrect còn lại, để AI có đủ ngữ cảnh tự sửa.
         *
         * Việc mở rộng chỉ xảy ra SAU KHI THẤT BẠI, nên không ảnh hưởng
         * tới chi phí prompt ở trường hợp bình thường (RAG đủ chính xác).
         */
        DatabaseSchema schemaForGeneration = filteredSchema;

        try {

            currentSql =
                    nl2SQLEngine.generateSQL(
                            question,
                            schemaForGeneration
                    );

        } catch (ReadOnlyViolationException e) {

            lastError = e.getMessage();

            attemptResult.success = false;
            attemptResult.sql = null;

            attemptResult.finalResult =
                    new QueryResultDto(
                            List.of(),
                            List.of(),
                            0,
                            0,
                            lastError
                    );

            return attemptResult;
        }

        for (int attempt = 1;
             attempt <= MAX_RETRIES;
             attempt++) {

            AttemptLog log = new AttemptLog();

            log.sql = currentSql;

            try {

                /*
                 * SECURITY:
                 *
                 * Validator luôn dùng FULL schema.
                 *
                 * filteredSchema/schemaForGeneration chỉ dùng cho AI/RAG,
                 * không dùng để quyết định bảng nào được phép truy cập.
                 */
                queryValidator.validate(
                        currentSql,
                        fullSchema
                );

                QueryResultDto queryResult =
                        queryExecutor.executeQuery(
                                connection.getHost(),
                                connection.getPort(),
                                connection.getDatabaseName(),
                                connection.getUsername(),
                                rawPassword,
                                currentSql
                        );

                log.result = queryResult;

                if (queryResult.getError() == null) {

                    log.success = true;

                    attemptResult.attemptLogs.add(log);

                    attemptResult.success = true;
                    attemptResult.sql = currentSql;
                    attemptResult.finalResult = queryResult;

                    return attemptResult;
                }

                lastError =
                        queryResult.getError();

            } catch (ReadOnlyViolationException e) {

                /*
                 * SECURITY:
                 * Nếu SQL không phải SELECT thì dừng ngay.
                 * Không cho AI self-correct thành một câu SELECT khác.
                 */
                lastError = e.getMessage();

                log.result =
                        new QueryResultDto(
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

                attemptResult.finalResult =
                        new QueryResultDto(
                                List.of(),
                                List.of(),
                                0,
                                0,
                                lastError
                        );

                return attemptResult;

            } catch (IllegalArgumentException e) {

                lastError = e.getMessage();

                log.result =
                        new QueryResultDto(
                                List.of(),
                                List.of(),
                                0,
                                0,
                                lastError
                        );
            }

            log.success = false;

            attemptResult.attemptLogs.add(log);

            /*
             * =====================================================
             * MỞ RỘNG SCHEMA SAU LẦN THẤT BẠI ĐẦU TIÊN
             * =====================================================
             *
             * Chỉ chuyển 1 lần (khi đang còn là filteredSchema),
             * và chỉ khi filteredSchema thực sự khác fullSchema
             * (RAG không kích hoạt thì 2 schema này đã là cùng
             * 1 object -> không cần làm gì thêm).
             */
            if (schemaForGeneration == filteredSchema
                    && filteredSchema != fullSchema) {

                schemaForGeneration = fullSchema;
            }

            /*
             * Chỉ self-correct các lỗi thông thường:
             * - SQL syntax error
             * - bảng/cột không tồn tại
             * - lỗi thực thi query
             *
             * ReadOnlyViolationException đã return ở trên.
             */
            if (attempt < MAX_RETRIES) {

                currentSql =
                        nl2SQLEngine.selfCorrect(
                                currentSql,
                                lastError,
                                schemaForGeneration
                        );
            }
        }

        attemptResult.success = false;
        attemptResult.sql = currentSql;

        attemptResult.finalResult =
                new QueryResultDto(
                        List.of(),
                        List.of(),
                        0,
                        0,
                        lastError
                );

        return attemptResult;
    }

    @Getter
    public static class AttemptResult {

        boolean success;

        String sql;

        QueryResultDto finalResult;

        List<AttemptLog> attemptLogs =
                new ArrayList<>();
    }

    @Getter
    public static class AttemptLog {

        String sql;

        boolean success;

        QueryResultDto result;
    }
}