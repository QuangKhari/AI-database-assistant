package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.ai.QuestionLanguage;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.exception.ConflictException;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.query.SQLCorrectionService;
import com.example.aidatabaseassistant.repository.*;
import com.example.aidatabaseassistant.security.ConnectionAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.text.Normalizer;
import java.util.Locale;

import java.util.List;

import com.example.aidatabaseassistant.query.ReadOnlyViolationException;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    @org.springframework.beans.factory.annotation.Value("${gemini.api.url}")
    private String modelUrl;

    @org.springframework.beans.factory.annotation.Value("${app.sse.timeout-ms:180000}")
    private long sseTimeoutMs;

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
    private final SchemaRetrievalService schemaRetrievalService;
    private final Executor sseTaskExecutor; // inject qua constructor
    private final ConnectionAccessGuard connectionAccessGuard;
    @org.springframework.beans.factory.annotation.Qualifier("sqlGenerationCacheManager")
    private final CacheManager sqlGenerationCacheManager;

    private static final int MAX_HISTORY_MESSAGES = 6; // 3 cặp hỏi-đáp gần nhất

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public PreviewResponse previewQuery(
            String username,
            QueryRequest request
    ) {

        User user = connectionAccessGuard.requireUser(username);
        DatabaseConnection connection =
                connectionAccessGuard.requireOwnedConnection(user, request.getDatabaseConnectionId());

        /*
         * CHẶN THAO TÁC GHI (INSERT/UPDATE/DELETE/ALTER/xoá/sửa/thêm...)
         * NGAY TỪ ĐẦU - KHÔNG đi qua luồng hỏi-đáp bình thường.
         *
         * Trước đây: câu hỏi kiểu "xoá bảng khách hàng" vẫn được
         * NL2SQLEngine sinh ra một câu SELECT giả (SELECT '...' AS
         * message) rồi đi tiếp qua toàn bộ luồng Preview -> Execute ->
         * chart/insight/summary giống một câu hỏi bình thường. Hệ quả:
         * FE hiển thị cả khối SQL "Đã thực thi", nút "Giải thích SQL"/
         * "Tối ưu SQL" cho một câu không hề có ý nghĩa truy vấn thật -
         * trải nghiệm rất kỳ quặc.
         *
         * Sửa: nhận diện thao tác ghi ngay tại Preview, KHÔNG load
         * schema, KHÔNG gọi RAG/Gemini, KHÔNG có SQL nào được sinh ra.
         * Trả về PreviewResponse với blocked=true để FE chỉ hiện MỘT
         * thông báo, không hiện khối SQL, không cho bấm "Thực thi SQL".
         */
        if (nl2SQLEngine.isWriteOperationQuestion(request.getQuestion())) {

            String blockedMessage =
                    nl2SQLEngine.blockedOperationMessage(request.getQuestion());

            log.info(
                    "[WRITE-OP BLOCKED] Chặn câu hỏi có thao tác ghi tại bước Preview, question=\"{}\"",
                    request.getQuestion()
            );

            return new PreviewResponse(
                    null,
                    false,
                    blockedMessage,
                    true
            );
        }

        /*
         * Luôn load FULL schema trước.
         *
         * fullSchema được dùng cho:
         * - Validator
         * - đảm bảo không giới hạn bảng hợp lệ bởi RAG
         */
        DatabaseSchema fullSchema =
                schemaLoaderService.loadCompleteSchema(
                        connection.getId()
                );

        /*
         * RAG chọn ra các bảng liên quan đến câu hỏi.
         *
         * filteredSchema chỉ được dùng để:
         * - build prompt
         * - generate SQL
         */
        DatabaseSchema filteredSchema =
                schemaRetrievalService.retrieveRelevantSchema(
                        request.getQuestion(),
                        fullSchema
                );

        String generatedSql =
                generateSqlWithCache(
                        connection.getId(),
                        request.getQuestion(),
                        filteredSchema,
                        fullSchema.getLastSyncedAt()
                );

        try {

            /*
             * SECURITY:
             * Validate bằng FULL schema.
             *
             * RAG không được biến thành whitelist bảng.
             */
            queryValidator.validate(
                    generatedSql,
                    fullSchema
            );

            return new PreviewResponse(
                    generatedSql,
                    true,
                    null,
                    false
            );

        } catch (ReadOnlyViolationException e) {

            return new PreviewResponse(
                    generatedSql,
                    false,
                    e.getMessage(),
                    false
            );

        } catch (IllegalArgumentException e) {

            return new PreviewResponse(
                    generatedSql,
                    false,
                    e.getMessage(),
                    false
            );
        }
    }

    // Method public CŨ — giữ nguyên signature cho 177 test hiện có
    // Method public CŨ — giữ nguyên signature cho các test/API hiện có.
    public QueryResponse processQuery(String username, QueryRequest request) {
        return processQuery(
                username,
                request,
                QueryProgressListener.NOOP
        );
    }

    public QueryResponse processQuery(
            String username,
            QueryRequest request,
            QueryProgressListener listener
    ) {
        return processQueryInternal(
                username,
                request,
                listener,
                false,
                null
        );
    }

    /**
     * Core xử lý query.
     *
     * deferSummary = false:
     * - Giữ nguyên hành vi /execute hiện tại.
     * - Summary chạy đồng bộ.
     *
     * deferSummary = true:
     * - Dùng cho SSE.
     * - Không chờ Summary trước khi trả QueryResponse.
     * - Summary được chạy background thông qua summaryListener.
     */
    private QueryResponse processQueryInternal(
            String username,
            QueryRequest request,
            QueryProgressListener listener,
            boolean deferSummary,
            Consumer<String> summaryListener
    ) {

        if (!rateLimitService.tryConsume(username)) {
            throw new RateLimitExceededException("Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút");
        }

        long t0 = System.currentTimeMillis();

        listener.onProgress("STATUS", "Đang xác thực người dùng và kết nối...");

        User user = connectionAccessGuard.requireUser(username);
        DatabaseConnection connection =
                connectionAccessGuard.requireOwnedConnection(user, request.getDatabaseConnectionId());

        /*
         * CHẶN THAO TÁC GHI - lớp bảo vệ thứ hai.
         *
         * Bình thường FE luôn gọi Preview trước (đã chặn ở
         * previewQuery()), nên nhánh này hiếm khi chạy tới. Nhưng vẫn
         * cần chặn lại ở đây để phòng trường hợp client gọi thẳng
         * /execute hoặc /execute/stream mà bỏ qua bước Preview.
         *
         * Xử lý: lưu lại đúng 1 lượt hỏi-đáp (để lịch sử conversation
         * nhất quán) với nội dung là thông báo chặn, KHÔNG có SQL,
         * KHÔNG chạy xuống DB, KHÔNG gọi chart/insight/summary.
         */
        if (nl2SQLEngine.isWriteOperationQuestion(request.getQuestion())) {

            log.info(
                    "[WRITE-OP BLOCKED] Chặn câu hỏi có thao tác ghi tại bước Execute, question=\"{}\"",
                    request.getQuestion()
            );

            listener.onProgress("STATUS", "Hoàn tất.");

            return buildBlockedQueryResponse(user, connection, request);
        }

        listener.onProgress("STATUS", "Đang tải schema database...");

        long tSchemaLoadStart = System.currentTimeMillis();
        DatabaseSchema fullSchema = schemaLoaderService.loadCompleteSchema(connection.getId());
        log.info("[TIMING] loadCompleteSchema: {} ms", System.currentTimeMillis() - tSchemaLoadStart);

        /*
         * TỐI ƯU HIỆU NĂNG:
         *
         * Khi request đã có generatedSql (từ bước Preview), backend đi thẳng vào
         * nhánh runWithGeneratedSql() bên dưới — nhánh này KHÔNG dùng filteredSchema
         * (self-correct dùng fullSchema). Gọi retrieveRelevantSchema() ở đây sẽ tốn
         * thêm 1 lần gọi Gemini Embedding API (300ms - 2s) hoàn toàn vô ích.
         *
         * Chỉ tính RAG (và gọi Embedding) khi thực sự cần generate SQL mới
         * (nhánh fallback không có generatedSql).
         */
        boolean hasGeneratedSqlFromPreview =
                request.getGeneratedSql() != null
                        && !request.getGeneratedSql().isBlank();

        DatabaseSchema filteredSchema = null;

        if (!hasGeneratedSqlFromPreview) {
            long tRagStart = System.currentTimeMillis();
            filteredSchema = schemaRetrievalService.retrieveRelevantSchema(request.getQuestion(), fullSchema);
            log.info("[TIMING] retrieveRelevantSchema (bao gom embedding call): {} ms", System.currentTimeMillis() - tRagStart);
        } else {
            log.info("[TIMING] retrieveRelevantSchema: bỏ qua (đã có generatedSql từ Preview, không cần RAG/embedding)");
        }

        Conversation conversation = getOrCreateConversation(user, connection, request);
        String conversationHistory =
                request.getConversationId() != null
                        ? buildConversationHistory(conversation.getId())
                        : null;

        Message userMessage = Message.builder()
                .conversation(conversation).role("user").content(request.getQuestion()).build();
        messageRepository.save(userMessage);

        log.info("Connection #{} encryptedPassword length={}", connection.getId(), connection.getEncryptedPassword() != null ? connection.getEncryptedPassword().length() : null);
        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        listener.onProgress("STATUS", "Đang sinh câu lệnh SQL từ AI và thực thi (tự sửa lỗi nếu cần)...");

        // QUAN TRỌNG: chọn đúng overload theo việc có/không có lịch sử.
        // Nếu luôn gọi bản 6-arg (kể cả history=null) thì các stub 5-arg
        // trong QueryServiceTest hiện có sẽ KHÔNG match -> vỡ hàng loạt test cũ.
        long tCorrectionStart = System.currentTimeMillis();
        SQLCorrectionService.AttemptResult result;

        if (hasGeneratedSqlFromPreview) {

            log.info(
                    "Execute sử dụng SQL từ Preview, bỏ qua bước generate SQL lần 2."
            );

            result =
                    sqlCorrectionService.runWithGeneratedSql(
                            request.getQuestion(),
                            request.getGeneratedSql(),
                            filteredSchema,
                            fullSchema,
                            connection,
                            rawPassword,
                            conversationHistory
                    );

        } else {

            /*
             * FALLBACK:
             *
             * Nếu client/API cũ không gửi generatedSql,
             * vẫn giữ nguyên hành vi cũ:
             *
             * RAG → Gemini → validate → execute.
             */
            result =
                    (conversationHistory == null)
                            ? sqlCorrectionService.run(
                            request.getQuestion(),
                            filteredSchema,
                            fullSchema,
                            connection,
                            rawPassword
                    )
                            : sqlCorrectionService.run(
                            request.getQuestion(),
                            filteredSchema,
                            fullSchema,
                            connection,
                            rawPassword,
                            conversationHistory
                    );
        }
        log.info(
                "[TIMING] sqlCorrectionService.run: {} ms, so lan thu: {}, success: {}",
                System.currentTimeMillis() - tCorrectionStart,
                result.getAttemptLogs().size(),
                result.isSuccess()
        );

        Message assistantMessage = Message.builder()
                .conversation(conversation)
                .role("assistant")
                .content(result.isSuccess()
                        ? "Đã trả lời thành công"
                        : "Không thể sinh SQL hợp lệ sau nhiều lần thử"
                )
                .generatedSql(result.getSql()).build();

        messageRepository.save(assistantMessage);

        var logs = result.getAttemptLogs();

        for (int i = 0; i < logs.size(); i++) {

            var log = logs.get(i);

            QueryLog queryLog = QueryLog.builder()
                    .message(assistantMessage)
                    .attemptNumber(i + 1)
                    .sqlText(log.getSql())
                    .status(log.isSuccess() ? "SUCCESS" : "FAILED")
                    .rowCount(log.getResult() != null ? log.getResult().getRowCount() : null)
                    .executionTimeMs(log.getResult() != null ? (int) log.getResult().getExecutionTimeMs() : null)
                    .errorMessage(log.getResult() != null ? log.getResult().getError() : null)
                    .question(request.getQuestion())
                    .modelUsed(extractModelName(modelUrl))
                    .retryCount(logs.size())
                    .build();

            queryLogRepository.save(queryLog);
        }

        String summary = null;

        /*
         * Hướng A:
         *
         * Với SSE, Summary KHÔNG nằm trên critical path.
         * Result + Chart + DataInsight được trả về trước.
         */
        if (result.isSuccess() && !deferSummary) {

            listener.onProgress(
                    "STATUS",
                    "Đang tạo tóm tắt và gợi ý biểu đồ..."
            );

            long tSummaryStart = System.currentTimeMillis();

            summary =
                    safeSummarize(
                            request.getQuestion(),
                            result.getFinalResult()
                    );

            log.info(
                    "[TIMING] safeSummarize: {} ms",
                    System.currentTimeMillis() - tSummaryStart
            );
        }

        long tChartStart = System.currentTimeMillis();

        ChartSuggestionResponse chartSuggestion =
                result.isSuccess()
                        ? buildChartSuggestion(
                        result.getFinalResult(),
                        fullSchema
                )
                        : null;

        DataInsightResponse dataInsight =
                result.isSuccess()
                        ? buildDataInsight(
                        result.getFinalResult(),
                        fullSchema
                )
                        : null;

        log.info(
                "[TIMING] chartSuggestion + dataInsight: {} ms",
                System.currentTimeMillis() - tChartStart
        );

        /*
         * Khi chạy SSE:
         *
         * - Không chờ Summary.
         * - QueryResponse trả summary = null.
         * - Summary sẽ được chạy background sau khi result đã sẵn sàng.
         */
        QueryResponse response =
                new QueryResponse(
                        conversation.getId(),
                        assistantMessage.getId(),
                        result.getSql(),
                        result.getFinalResult(),
                        summary,
                        logs.size(),
                        chartSuggestion,
                        dataInsight
                );
        persistQueryResponseSnapshot(
                assistantMessage.getId(),
                response
        );

        if (deferSummary && result.isSuccess() && summaryListener != null) {

            log.info(
                    "[TIMING] Query core hoàn tất trước Summary: {} ms",
                    System.currentTimeMillis() - t0
            );

            sseTaskExecutor.execute(() -> {

                long tBackgroundSummary =
                        System.currentTimeMillis();

                try {

                    log.info(
                            "Background Summary bắt đầu cho question=\"{}\"",
                            request.getQuestion()
                    );

                    String backgroundSummary =
                            safeSummarize(
                                    request.getQuestion(),
                                    result.getFinalResult()
                            );

                    log.info(
                            "[TIMING] Background safeSummarize: {} ms",
                            System.currentTimeMillis() - tBackgroundSummary
                    );

                    /*
                     * Cập nhật snapshot trong DB trước khi gửi summary về FE.
                     * Nếu user quay lại conversation sau đó, summary vẫn còn.
                     */
                    updatePersistedSummary(
                            assistantMessage.getId(),
                            backgroundSummary
                    );

                    summaryListener.accept(backgroundSummary);

                } catch (Exception e) {

                    log.warn(
                            "Background Summary thất bại cho question=\"{}\": {}",
                            request.getQuestion(),
                            e.toString()
                    );

                    /*
                     * safeSummarize vốn đã có fallback.
                     * Đoạn này chỉ là lớp bảo vệ cuối cùng nếu
                     * có lỗi bất ngờ ngoài safeSummarize().
                     */
                    String fallback =
                            QuestionLanguage.isEnglish(request.getQuestion())
                                    ? "Could not generate an automatic summary for this result. Please check the data table below."
                                    : "Không thể tạo tóm tắt tự động cho kết quả này. Vui lòng xem bảng dữ liệu bên dưới.";

                    try {
                        updatePersistedSummary(
                                assistantMessage.getId(),
                                fallback
                        );
                        summaryListener.accept(fallback);
                    } catch (Exception callbackError) {
                        log.debug(
                                "Không thể gửi background summary về SSE: {}",
                                callbackError.toString()
                        );
                    }
                }
            });

        } else {

            listener.onProgress("STATUS", "Hoàn tất.");

            log.info(
                    "[TIMING] TONG CONG ca request: {} ms",
                    System.currentTimeMillis() - t0
            );
        }

        return response;
    }

    /**
     * Lưu toàn bộ QueryResponse vào assistant message.
     *
     * Persistence lỗi không được làm hỏng query chính vì đây chỉ là
     * snapshot phục vụ việc khôi phục UI.
     */
    private void persistQueryResponseSnapshot(
            Long messageId,
            QueryResponse response
    ) {
        try {
            Message message = messageRepository.findById(messageId)
                    .orElse(null);

            if (message == null) {
                log.warn(
                        "[QUERY SNAPSHOT] Không tìm thấy messageId={} để lưu snapshot",
                        messageId
                );
                return;
            }

            message.setQueryResponseJson(
                    OBJECT_MAPPER.writeValueAsString(response)
            );

            messageRepository.save(message);

            log.debug(
                    "[QUERY SNAPSHOT] Đã lưu snapshot cho messageId={}",
                    messageId
            );

        } catch (Exception e) {
            log.warn(
                    "[QUERY SNAPSHOT] Không thể lưu snapshot messageId={}: {}",
                    messageId,
                    e.toString()
            );
        }
    }

    /**
     * Cập nhật summary vào JSON snapshot đã lưu.
     */
    private void updatePersistedSummary(
            Long messageId,
            String summary
    ) {
        try {
            Message message = messageRepository.findById(messageId)
                    .orElse(null);

            if (message == null
                    || message.getQueryResponseJson() == null
                    || message.getQueryResponseJson().isBlank()) {
                return;
            }

            ObjectNode root = (ObjectNode) OBJECT_MAPPER.readTree(
                    message.getQueryResponseJson()
            );

            if (summary == null) {
                root.putNull("summary");
            } else {
                root.put("summary", summary);
            }

            message.setQueryResponseJson(
                    OBJECT_MAPPER.writeValueAsString(root)
            );

            messageRepository.save(message);

            log.debug(
                    "[QUERY SNAPSHOT] Đã cập nhật summary messageId={}",
                    messageId
            );

        } catch (Exception e) {
            log.warn(
                    "[QUERY SNAPSHOT] Không thể cập nhật summary messageId={}: {}",
                    messageId,
                    e.toString()
            );
        }
    }

    private DataInsightResponse buildDataInsight(
            QueryResultDto finalResult,
            DatabaseSchema fullSchema
    ) {

        // Giong buildChartSuggestion: day la tinh nang BO SUNG, tuyet doi
        // khong duoc lam vo luong /execute chinh neu co loi bat ngo. Neu
        // khong tinh duoc (analyzer tra ve null) hoac loi, FE se tu dong
        // fallback ve hien thi "summary" (da co san, khong bi anh huong).
        try {

            return dataInsightService.analyze(
                    finalResult.getColumns(),
                    finalResult.getRows(),
                    SchemaDiscoveryService.buildKeyColumnMap(fullSchema)
            );

        } catch (Exception e) {

            return null;
        }
    }

    private ChartSuggestionResponse buildChartSuggestion(QueryResultDto finalResult, DatabaseSchema fullSchema) {
        try {

            return chartSuggestionService.suggest(
                    finalResult.getColumns(),
                    finalResult.getRows(),
                    SchemaDiscoveryService.buildKeyColumnMap(fullSchema));

        } catch (Exception e) {

            return null;
        }
    }
    private String safeSummarize(
            String question,
            QueryResultDto result
    ) {

        try {

            return summarizeResult(question, result);

        } catch (Exception e) {

            log.warn(
                    "AI Summary that bai, tra ve fallback. Cau hoi: '{}', ly do: {}",
                    question,
                    e.toString()
            );

            return QuestionLanguage.isEnglish(question)
                    ? "Could not generate an automatic summary for this result. Please check the data table below."
                    : "Không thể tạo tóm tắt tự động cho kết quả này. Vui lòng xem bảng dữ liệu bên dưới.";
        }
    }

    private String summarizeResult(
            String question,
            QueryResultDto result
    ) {

        List<Map<String, Object>> limitedRows =
                result.getRows().size() > 20
                        ? result.getRows().subList(0, 20)
                        : result.getRows();

        String prompt;

        if (QuestionLanguage.isEnglish(question)) {

            prompt = """
                Question: %s

                SQL result:
                %s

                REQUIREMENTS:
                - Summarize the result in 1-2 natural, concise English sentences.
                - Only use figures that literally appear in the result above.
                - Do NOT recompute totals, averages, percentages or any arithmetic yourself.
                - Do NOT alter, round, or infer any numbers.
                - If the result has multiple rows, highlight the key points directly from the data.
                """.formatted(
                    question,
                    limitedRows
            );

        } else {

            prompt = """
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
        }

        /*
         * QUAN TRỌNG:
         *
         * Summary là optional AI feature.
         * Không dùng generateResponse() vì method đó có retry + timeout 60s.
         */
        return llmClient.generateOptionalResponse(prompt);
    }

    /**
     * Xây dựng QueryResponse cho trường hợp câu hỏi bị chặn vì là thao
     * tác ghi (INSERT/UPDATE/DELETE/xoá/sửa/thêm...).
     *
     * Vẫn lưu lại đúng 1 lượt hỏi (user) - đáp (assistant) để lịch sử
     * conversation hiển thị nhất quán khi tải lại trang, nhưng:
     * - Message assistant KHÔNG có generatedSql -> FE (dựa vào
     *   `message.generatedSql`) sẽ KHÔNG hiện khối SQL / nút "Giải
     *   thích SQL" / "Tối ưu SQL" cho message này.
     * - KHÔNG tạo QueryLog (không có SQL nào được chạy).
     * - KHÔNG gọi chart suggestion / data insight / summary (Gemini).
     */
    private QueryResponse buildBlockedQueryResponse(
            User user,
            DatabaseConnection connection,
            QueryRequest request
    ) {
        Conversation conversation = getOrCreateConversation(user, connection, request);

        String blockedMessage =
                nl2SQLEngine.blockedOperationMessage(request.getQuestion());

        Message userMessage = Message.builder()
                .conversation(conversation)
                .role("user")
                .content(request.getQuestion())
                .build();
        messageRepository.save(userMessage);

        Message assistantMessage = Message.builder()
                .conversation(conversation)
                .role("assistant")
                .content(blockedMessage)
                .build();
        messageRepository.save(assistantMessage);

        QueryResponse response = new QueryResponse(
                conversation.getId(),
                assistantMessage.getId(),
                null,
                null,
                null,
                0,
                null,
                null
        );
        response.setBlocked(true);

        persistQueryResponseSnapshot(assistantMessage.getId(), response);

        return response;
    }

    private Conversation getOrCreateConversation(
            User user,
            DatabaseConnection connection,
            QueryRequest request
    ) {

        if (request.getConversationId() != null) {

            Conversation conversation =
                    conversationRepository
                            .findById(
                                    request.getConversationId()
                            )
                            .orElseThrow(() ->
                                    new ResourceNotFoundException(
                                            "Không tìm thấy conversation"
                                    )
                            );

            // Kiểm tra conversation thuộc user hiện tại
            if (!conversation.getUser().getId().equals(user.getId())) {
                throw new ForbiddenResourceException("Bạn không có quyền truy cập conversation này");
            }
            if (!conversation.getConnection().getId().equals(connection.getId())) {
                throw new ConflictException("Conversation không thuộc connection này");
            }

            return conversation;
        }

        Conversation conversation =
                Conversation.builder()
                        .user(user)
                        .connection(connection)
                        .title(
                                request.getQuestion().length() > 50
                                        ? request.getQuestion()
                                        .substring(0, 50) + "..."
                                        : request.getQuestion()
                        )
                        .build();

        return conversationRepository.save(
                conversation
        );
    }

    private String extractModelName(String url) {

        int start =
                url.indexOf("/models/") + 8;

        int end =
                url.indexOf(":", start);

        return url.substring(
                start,
                end
        );
    }
    private String buildConversationHistory(Long conversationId) {

        try {
            List<Message> allMessages =
                    messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

            if (allMessages.isEmpty()) {
                return null;
            }

            List<Message> recent =
                    allMessages.size() > MAX_HISTORY_MESSAGES
                            ? allMessages.subList(allMessages.size() - MAX_HISTORY_MESSAGES, allMessages.size())
                            : allMessages;

            StringBuilder sb = new StringBuilder();

            for (Message m : recent) {
                if ("user".equals(m.getRole())) {
                    sb.append("- Người dùng hỏi: ").append(m.getContent()).append("\n");
                } else {
                    // assistant message: nội dung chỉ là "Đã trả lời thành công",
                    // thứ có giá trị thật cho ngữ cảnh là SQL đã sinh ra
                    if (m.getGeneratedSql() != null && !m.getGeneratedSql().isBlank()) {
                        sb.append("  → SQL đã dùng: ").append(m.getGeneratedSql()).append("\n");
                    }
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.warn("Không lấy được lịch sử hội thoại cho conversation {}: {}", conversationId, e.toString());
            return null;
        }
    }

    public SseEmitter processQueryStreaming(
            String username,
            QueryRequest request
    ) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        emitter.onTimeout(() ->
                log.warn(
                        "SSE query timeout sau {}ms cho user={}, question=\"{}\"",
                        sseTimeoutMs,
                        username,
                        request.getQuestion()
                )
        );

        emitter.onError(ex ->
                log.warn(
                        "SSE query lỗi cho user={}, question=\"{}\": {}",
                        username,
                        request.getQuestion(),
                        ex.toString()
                )
        );

        emitter.onCompletion(() ->
                log.debug(
                        "SSE query hoàn tất cho user={}, question=\"{}\"",
                        username,
                        request.getQuestion()
                )
        );

        sseTaskExecutor.execute(() -> {

            try {

                QueryProgressListener listener =
                        (stage, message) -> {

                            try {

                                emitter.send(
                                        SseEmitter.event()
                                                .name(stage)
                                                .data(message)
                                );

                            } catch (IOException ignored) {

                                // Client đã đóng kết nối.
                                // Không làm hỏng quá trình xử lý backend.
                            }
                        };

                /*
                 * Summary callback:
                 *
                 * Được gọi từ background thread sau khi QueryResponse
                 * đã được gửi về frontend.
                 */
                Consumer<String> summaryListener =
                        summary -> {

                            try {

                                log.info(
                                        "Đang gửi SSE summary cho question=\"{}\", summary=\"{}\"",
                                        request.getQuestion(),
                                        summary
                                );

                                emitter.send(
                                        SseEmitter.event()
                                                .name("summary")
                                                .data(summary)
                                );

                                log.info(
                                        "SSE summary đã gửi thành công cho question=\"{}\"",
                                        request.getQuestion()
                                );

                                emitter.send(
                                        SseEmitter.event()
                                                .name("STATUS")
                                                .data("Hoàn tất.")
                                );

                                emitter.complete();

                                log.info(
                                        "SSE query hoàn tất sau khi gửi Summary cho question=\"{}\"",
                                        request.getQuestion()
                                );

                            } catch (IOException e) {

                                log.warn(
                                        "Không thể gửi Summary qua SSE vì client đã đóng kết nối. question=\"{}\", reason={}",
                                        request.getQuestion(),
                                        e.toString()
                                );

                                emitter.complete();

                            } catch (IllegalStateException e) {

                                log.warn(
                                        "SSE emitter không còn hợp lệ khi gửi Summary. question=\"{}\", reason={}",
                                        request.getQuestion(),
                                        e.toString()
                                );

                                emitter.complete();

                            }
                        };

                /*
                 * Hướng A:
                 *
                 * processQueryInternal trả QueryResponse ngay sau:
                 * - RAG
                 * - SQL execution
                 * - Chart
                 * - DataInsight
                 *
                 * KHÔNG chờ Summary.
                 */
                QueryResponse response =
                        processQueryInternal(
                                username,
                                request,
                                listener,
                                true,
                                summaryListener
                        );

                /*
                 * Gửi result NGAY.
                 *
                 * Không complete emitter ở đây vì background Summary
                 * vẫn cần dùng cùng SSE connection.
                 */
                emitter.send(
                        SseEmitter.event()
                                .name("result")
                                .data(response)
                );

                log.info(
                        "SSE result đã gửi trước Background Summary cho question=\"{}\"",
                        request.getQuestion()
                );

            } catch (Exception e) {

                try {

                    emitter.send(
                            SseEmitter.event()
                                    .name("error")
                                    .data(
                                            e.getMessage() != null
                                                    ? e.getMessage()
                                                    : "Lỗi không xác định"
                                    )
                    );

                } catch (IOException ignored) {
                    // Client đã đóng connection.
                }

                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String generateSqlWithCache(
            Long connectionId,
            String question,
            DatabaseSchema filteredSchema,
            java.time.LocalDateTime schemaVersion
    ) {

        String normalizedQuestion = normalizeQuestion(question);

        String normalizedSchemaVersion =
                schemaVersion != null
                        ? schemaVersion.toString()
                        : "schema-" + filteredSchema.getId();

        String cacheKey =
                connectionId
                        + ":"
                        + normalizedQuestion
                        + ":"
                        + normalizedSchemaVersion;

        Cache cache =
                sqlGenerationCacheManager.getCache(
                        com.example.aidatabaseassistant.config.CacheConfig.SQL_GENERATION_CACHE
                );

        if (cache != null) {

            String cachedSql = cache.get(
                    cacheKey,
                    String.class
            );

            if (cachedSql != null) {

                log.info(
                        "[SQL CACHE] HIT connectionId={}, schemaVersion={}, question=\"{}\"",
                        connectionId,
                        normalizedSchemaVersion,
                        question
                );

                return cachedSql;
            }
        }

        log.info(
                "[SQL CACHE] MISS connectionId={}, schemaVersion={}, question=\"{}\"",
                connectionId,
                normalizedSchemaVersion,
                question
        );

        long start = System.currentTimeMillis();

        String generatedSql =
                nl2SQLEngine.generateSQL(
                        question,
                        filteredSchema
                );

        long elapsed =
                System.currentTimeMillis() - start;

        log.info(
                "[TIMING] nl2SQLEngine.generateSQL: {} ms",
                elapsed
        );

        if (cache != null) {

            cache.put(
                    cacheKey,
                    generatedSql
            );

            log.info(
                    "[SQL CACHE] PUT connectionId={}, schemaVersion={}",
                    connectionId,
                    normalizedSchemaVersion
            );
        }

        return generatedSql;
    }

    private String normalizeQuestion(String question) {

        if (question == null) {
            return "";
        }

        String normalized =
                Normalizer.normalize(
                                question,
                                Normalizer.Form.NFKC
                        )
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ");

        // Cho phép các câu chỉ khác dấu câu cuối vẫn dùng chung cache.
        normalized =
                normalized.replaceAll(
                        "[?!.。！？]+$",
                        ""
                ).trim();

        return normalized;
    }
}