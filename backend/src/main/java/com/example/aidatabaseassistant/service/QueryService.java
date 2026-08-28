package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.query.SQLCorrectionService;
import com.example.aidatabaseassistant.query.ReadOnlyViolationException;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QueryService {

    @Value("${gemini.api.url}")
    private String modelUrl;

    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final QueryLogRepository queryLogRepository;

    private final EncryptionUtil encryptionUtil;
    private final NL2SQLEngine nl2SQLEngine;
    private final QueryValidator queryValidator;
    private final SQLCorrectionService sqlCorrectionService;
    private final LLMClient llmClient;
    private final RateLimitService rateLimitService;
    private final ChartSuggestionService chartSuggestionService;
    private final SchemaLoaderService schemaLoaderService;
    private final DataInsightService dataInsightService;

    /*
     * RAG:
     *
     * Service này chịu trách nhiệm chọn ra những bảng liên quan
     * đến câu hỏi trước khi gửi schema cho AI.
     */
    private final SchemaRetrievalService schemaRetrievalService;


    // ============================================================
    // PREVIEW QUERY
    // ============================================================

    public PreviewResponse previewQuery(
            String username,
            QueryRequest request
    ) {

        /*
         * ========================================================
         * 1. Tìm user
         * ========================================================
         */
        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new IllegalArgumentException("Không tìm thấy user")
                );


        /*
         * ========================================================
         * 2. Tìm database connection
         * ========================================================
         */
        DatabaseConnection connection =
                connectionRepository.findById(
                                request.getDatabaseConnectionId()
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy connection"
                                )
                        );


        /*
         * ========================================================
         * 3. Kiểm tra quyền sở hữu connection
         * ========================================================
         */
        if (!connection.getUser().getId().equals(user.getId())) {

            throw new IllegalArgumentException(
                    "Bạn không có quyền truy cập connection này"
            );
        }


        /*
         * ========================================================
         * 4. Load FULL schema
         * ========================================================
         *
         * Đây là schema đầy đủ của database.
         *
         * Không dùng trực tiếp fullSchema để gửi cho AI nữa.
         */
        DatabaseSchema fullSchema =
                schemaLoaderService.loadCompleteSchema(
                        connection.getId()
                );


        /*
         * ========================================================
         * 5. RAG - lấy FILTERED schema
         * ========================================================
         *
         * filteredSchema chỉ chứa những bảng có khả năng
         * liên quan đến câu hỏi.
         *
         * Mục đích:
         * - giảm prompt
         * - giảm token
         * - giảm chi phí embedding/LLM
         * - tăng khả năng AI tập trung vào bảng liên quan
         */
        DatabaseSchema filteredSchema =
                schemaRetrievalService.retrieveRelevantSchema(
                        request.getQuestion(),
                        fullSchema
                );


        /*
         * ========================================================
         * 6. AI sinh SQL
         * ========================================================
         *
         * QUAN TRỌNG:
         *
         * AI nhận filteredSchema.
         */
        String generatedSql =
                nl2SQLEngine.generateSQL(
                        request.getQuestion(),
                        filteredSchema
                );


        /*
         * ========================================================
         * 7. Validate SQL
         * ========================================================
         *
         * Validator phải nhận FULL schema.
         *
         * Vì filteredSchema chỉ phục vụ RAG,
         * không phải source of truth của database.
         */
        try {

            queryValidator.validate(
                    generatedSql,
                    fullSchema
            );

            return new PreviewResponse(
                    generatedSql,
                    true,
                    null
            );

        } catch (ReadOnlyViolationException e) {

            return new PreviewResponse(
                    generatedSql,
                    false,
                    e.getMessage()
            );

        } catch (IllegalArgumentException e) {

            return new PreviewResponse(
                    generatedSql,
                    false,
                    e.getMessage()
            );
        }
    }


    // ============================================================
    // PROCESS QUERY
    // ============================================================

    public QueryResponse processQuery(
            String username,
            QueryRequest request
    ) {

        /*
         * ========================================================
         * 1. Rate limit
         * ========================================================
         */
        if (!rateLimitService.tryConsume(username)) {

            throw new IllegalStateException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }


        /*
         * ========================================================
         * 2. Tìm user
         * ========================================================
         */
        User user = userRepository.findByUsername(username)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy user"
                        )
                );


        /*
         * ========================================================
         * 3. Tìm connection
         * ========================================================
         */
        DatabaseConnection connection =
                connectionRepository.findById(
                                request.getDatabaseConnectionId()
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy connection"
                                )
                        );


        /*
         * ========================================================
         * 4. Kiểm tra quyền
         * ========================================================
         */
        if (!connection.getUser().getId().equals(user.getId())) {

            throw new IllegalArgumentException(
                    "Bạn không có quyền truy cập connection này"
            );
        }


        /*
         * ========================================================
         * 5. Load FULL schema
         * ========================================================
         */
        DatabaseSchema fullSchema =
                schemaLoaderService.loadCompleteSchema(
                        connection.getId()
                );


        /*
         * ========================================================
         * 6. RAG
         * ========================================================
         *
         * Từ fullSchema → filteredSchema.
         */
        DatabaseSchema filteredSchema =
                schemaRetrievalService.retrieveRelevantSchema(
                        request.getQuestion(),
                        fullSchema
                );


        /*
         * ========================================================
         * 7. Conversation
         * ========================================================
         */
        Conversation conversation =
                getOrCreateConversation(
                        user,
                        connection,
                        request
                );


        /*
         * ========================================================
         * 8. Lưu câu hỏi của user
         * ========================================================
         */
        Message userMessage = Message.builder()
                .conversation(conversation)
                .role("user")
                .content(request.getQuestion())
                .build();

        messageRepository.save(userMessage);


        /*
         * ========================================================
         * 9. Decrypt password database
         * ========================================================
         */
        String rawPassword =
                encryptionUtil.decrypt(
                        connection.getEncryptedPassword()
                );


        /*
         * ========================================================
         * 10. Generate + Validate + Execute + Self-correct
         * ========================================================
         *
         * filteredSchema:
         *      → AI
         *
         * fullSchema:
         *      → QueryValidator
         */
        SQLCorrectionService.AttemptResult result =
                sqlCorrectionService.run(
                        request.getQuestion(),
                        filteredSchema,
                        fullSchema,
                        connection,
                        rawPassword
                );


        /*
         * ========================================================
         * 11. Lưu assistant message
         * ========================================================
         */
        Message assistantMessage = Message.builder()
                .conversation(conversation)
                .role("assistant")
                .content(
                        result.isSuccess()
                                ? "Đã trả lời thành công"
                                : "Không thể sinh SQL hợp lệ sau nhiều lần thử"
                )
                .generatedSql(result.getSql())
                .build();

        messageRepository.save(assistantMessage);


        /*
         * ========================================================
         * 12. Lưu QueryLog
         * ========================================================
         */
        var logs = result.getAttemptLogs();

        for (int i = 0; i < logs.size(); i++) {

            var log = logs.get(i);

            QueryLog queryLog = QueryLog.builder()
                    .message(assistantMessage)
                    .attemptNumber(i + 1)
                    .sqlText(log.getSql())
                    .status(
                            log.isSuccess()
                                    ? "SUCCESS"
                                    : "FAILED"
                    )
                    .rowCount(
                            log.getResult() != null
                                    ? log.getResult().getRowCount()
                                    : null
                    )
                    .executionTimeMs(
                            log.getResult() != null
                                    ? (int) log.getResult()
                                    .getExecutionTimeMs()
                                    : null
                    )
                    .errorMessage(
                            log.getResult() != null
                                    ? log.getResult().getError()
                                    : null
                    )
                    .question(request.getQuestion())
                    .modelUsed(extractModelName(modelUrl))
                    .retryCount(logs.size())
                    .build();

            queryLogRepository.save(queryLog);
        }


        /*
         * ========================================================
         * 13. Summary
         * ========================================================
         */
        String summary =
                result.isSuccess()
                        ? summarizeResult(
                        request.getQuestion(),
                        result.getFinalResult()
                )
                        : null;


        /*
         * ========================================================
         * 14. Chart suggestion
         * ========================================================
         */
        ChartSuggestionResponse chartSuggestion =
                result.isSuccess()
                        ? buildChartSuggestion(
                        result.getFinalResult()
                )
                        : null;


        /*
         * ========================================================
         * 15. Data insight
         * ========================================================
         */
        DataInsightResponse dataInsight =
                result.isSuccess()
                        ? buildDataInsight(
                        result.getFinalResult()
                )
                        : null;


        /*
         * ========================================================
         * 16. Trả response
         * ========================================================
         */
        return new QueryResponse(
                conversation.getId(),
                assistantMessage.getId(),
                result.getSql(),
                result.getFinalResult(),
                summary,
                logs.size(),
                chartSuggestion,
                dataInsight
        );
    }


    // ============================================================
    // DATA INSIGHT
    // ============================================================

    private DataInsightResponse buildDataInsight(
            QueryResultDto finalResult
    ) {

        /*
         * Đây là feature bổ sung.
         *
         * Nếu DataInsight lỗi thì không được làm hỏng
         * toàn bộ /execute.
         */
        try {

            return dataInsightService.analyze(
                    finalResult.getColumns(),
                    finalResult.getRows()
            );

        } catch (Exception e) {

            return null;
        }
    }


    // ============================================================
    // CHART SUGGESTION
    // ============================================================

    private ChartSuggestionResponse buildChartSuggestion(
            QueryResultDto finalResult
    ) {

        /*
         * Chart suggestion cũng là feature bổ sung.
         *
         * Nếu lỗi -> trả null.
         */
        try {

            return chartSuggestionService.suggest(
                    finalResult.getColumns(),
                    finalResult.getRows()
            );

        } catch (Exception e) {

            return null;
        }
    }


    // ============================================================
    // SUMMARY
    // ============================================================

    private String summarizeResult(
            String question,
            QueryResultDto result
    ) {

        /*
         * Không đưa toàn bộ dữ liệu cho LLM.
         *
         * Chỉ lấy tối đa 20 dòng đầu tiên để summary.
         */
        List<java.util.Map<String, Object>> limitedRows =
                result.getRows().size() > 20
                        ? result.getRows().subList(0, 20)
                        : result.getRows();


        String prompt = """
                Câu hỏi: %s

                Kết quả SQL:
                %s

                YÊU CẦU:
                - Tóm tắt kết quả bằng 1-2 câu tiếng Việt tự nhiên, ngắn gọn.
                - Chỉ sử dụng các số liệu xuất hiện trong kết quả.
                - KHÔNG tự tính lại tổng, trung bình, phần trăm hoặc các phép tính số học.
                - KHÔNG thay đổi, làm tròn hoặc suy diễn số liệu.
                - Nếu kết quả có nhiều dòng, hãy nêu các điểm nổi bật dựa trực tiếp trên dữ liệu.
                """.formatted(
                question,
                limitedRows
        );


        return llmClient.generateResponse(prompt);
    }


    // ============================================================
    // GET OR CREATE CONVERSATION
    // ============================================================

    private Conversation getOrCreateConversation(
            User user,
            DatabaseConnection connection,
            QueryRequest request
    ) {

        /*
         * Nếu FE gửi conversationId
         * -> tiếp tục conversation cũ.
         */
        if (request.getConversationId() != null) {

            Conversation conversation =
                    conversationRepository
                            .findById(
                                    request.getConversationId()
                            )
                            .orElseThrow(() ->
                                    new IllegalArgumentException(
                                            "Không tìm thấy conversation"
                                    )
                            );


            /*
             * Conversation phải thuộc user hiện tại.
             */
            if (!conversation.getUser()
                    .getId()
                    .equals(user.getId())) {

                throw new IllegalArgumentException(
                        "Bạn không có quyền truy cập conversation này"
                );
            }


            /*
             * Conversation phải thuộc đúng database connection.
             */
            if (!conversation.getConnection()
                    .getId()
                    .equals(connection.getId())) {

                throw new IllegalArgumentException(
                        "Conversation không thuộc connection này"
                );
            }


            return conversation;
        }


        /*
         * Nếu chưa có conversationId
         * -> tạo conversation mới.
         */
        Conversation conversation =
                Conversation.builder()
                        .user(user)
                        .connection(connection)
                        .title(
                                request.getQuestion().length() > 50
                                        ? request.getQuestion()
                                        .substring(0, 50)
                                        + "..."
                                        : request.getQuestion()
                        )
                        .build();


        return conversationRepository.save(
                conversation
        );
    }


    // ============================================================
    // EXTRACT MODEL NAME
    // ============================================================

    private String extractModelName(String url) {

        int start = url.indexOf("/models/") + 8;
        int end = url.indexOf(":", start);

        return url.substring(start, end);
    }
}