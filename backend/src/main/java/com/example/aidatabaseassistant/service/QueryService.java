package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.query.SQLCorrectionService;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

import java.util.List;

import com.example.aidatabaseassistant.query.ReadOnlyViolationException;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    @org.springframework.beans.factory.annotation.Value("${gemini.api.url}")
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
    private final SchemaRetrievalService schemaRetrievalService;
    private final Executor sseTaskExecutor; // inject qua constructor

    private static final int MAX_HISTORY_MESSAGES = 6; // 3 cặp hỏi-đáp gần nhất

    public PreviewResponse previewQuery(
            String username,
            QueryRequest request
    ) {

        User user =
                userRepository.findByUsername(username)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy user"
                                )
                        );

        DatabaseConnection connection =
                connectionRepository.findById(
                                request.getDatabaseConnectionId()
                        )
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy connection"
                                )
                        );

        if (!connection.getUser().getId().equals(user.getId())) {

            throw new IllegalArgumentException(
                    "Bạn không có quyền truy cập connection này"
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
                nl2SQLEngine.generateSQL(
                        request.getQuestion(),
                        filteredSchema
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

    // Method public CŨ — giữ nguyên signature cho 177 test hiện có
    public QueryResponse processQuery(String username, QueryRequest request) {
        return processQuery(username, request, QueryProgressListener.NOOP);
    }

    public QueryResponse processQuery(String username, QueryRequest request, QueryProgressListener listener) {

        if (!rateLimitService.tryConsume(username)) {
            throw new RateLimitExceededException("Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút");
        }

        listener.onProgress("STATUS", "Đang xác thực người dùng và kết nối...");

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(request.getDatabaseConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
        }

        listener.onProgress("STATUS", "Đang tải schema database...");

        DatabaseSchema fullSchema = schemaLoaderService.loadCompleteSchema(connection.getId());
        DatabaseSchema filteredSchema = schemaRetrievalService.retrieveRelevantSchema(request.getQuestion(), fullSchema);

        Conversation conversation = getOrCreateConversation(user, connection, request);
        String conversationHistory =
                request.getConversationId() != null
                        ? buildConversationHistory(conversation.getId())
                        : null;

        Message userMessage = Message.builder()
                .conversation(conversation).role("user").content(request.getQuestion()).build();
        messageRepository.save(userMessage);

        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        listener.onProgress("STATUS", "Đang sinh câu lệnh SQL từ AI và thực thi (tự sửa lỗi nếu cần)...");

        // QUAN TRỌNG: chọn đúng overload theo việc có/không có lịch sử.
        // Nếu luôn gọi bản 6-arg (kể cả history=null) thì các stub 5-arg
        // trong QueryServiceTest hiện có sẽ KHÔNG match -> vỡ hàng loạt test cũ.
        SQLCorrectionService.AttemptResult result =
                (conversationHistory == null)
                        ? sqlCorrectionService.run(request.getQuestion(), filteredSchema, fullSchema, connection, rawPassword)
                        : sqlCorrectionService.run(request.getQuestion(), filteredSchema, fullSchema, connection, rawPassword, conversationHistory);

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

        listener.onProgress("STATUS", "Đang tạo tóm tắt và gợi ý biểu đồ...");

        String summary = result.isSuccess() ? safeSummarize(request.getQuestion(), result.getFinalResult()) : null;
        ChartSuggestionResponse chartSuggestion = result.isSuccess() ? buildChartSuggestion(result.getFinalResult()) : null;
        DataInsightResponse dataInsight = result.isSuccess() ? buildDataInsight(result.getFinalResult()) : null;

        listener.onProgress("STATUS", "Hoàn tất.");

        return new QueryResponse(conversation.getId(), assistantMessage.getId(), result.getSql(),
                result.getFinalResult(), summary, logs.size(), chartSuggestion, dataInsight);
    }

    private DataInsightResponse buildDataInsight(
            QueryResultDto finalResult
    ) {

        // Giong buildChartSuggestion: day la tinh nang BO SUNG, tuyet doi
        // khong duoc lam vo luong /execute chinh neu co loi bat ngo. Neu
        // khong tinh duoc (analyzer tra ve null) hoac loi, FE se tu dong
        // fallback ve hien thi "summary" (da co san, khong bi anh huong).
        try {

            return dataInsightService.analyze(
                    finalResult.getColumns(),
                    finalResult.getRows()
            );

        } catch (Exception e) {

            return null;
        }
    }

    private ChartSuggestionResponse buildChartSuggestion(QueryResultDto finalResult) {
        try {

            return chartSuggestionService.suggest(
                    finalResult.getColumns(),
                    finalResult.getRows());

        } catch (Exception e) {

            return null;
        }
    }

    // Giong buildChartSuggestion/buildDataInsight: AI Summary la tinh nang
    // BO SUNG, tuyet doi khong duoc lam vo luong /execute chinh neu Gemini
    // loi/timeout/tra ve rong. LLMClient.generateResponse() nem thang
    // RuntimeException trong cac truong hop do, nen phai bat lai o day va
    // tra ve fallback thay vi de loi lan len Controller (=> 500 du SQL da
    // chay thanh cong).
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

            return "Không thể tạo tóm tắt tự động cho kết quả này. Vui lòng xem bảng dữ liệu bên dưới.";
        }
    }

    private String summarizeResult(
            String question,
            QueryResultDto result
    ) {

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
                                    new IllegalArgumentException(
                                            "Không tìm thấy conversation"
                                    )
                            );

            // Kiểm tra conversation thuộc user hiện tại
            if (!conversation.getUser().getId().equals(user.getId())) {

                throw new IllegalArgumentException(
                        "Bạn không có quyền truy cập conversation này"
                );
            }

            // Kiểm tra conversation thuộc đúng connection
            if (!conversation.getConnection().getId()
                    .equals(connection.getId())) {

                throw new IllegalArgumentException(
                        "Conversation không thuộc connection này"
                );
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

    /**
     * Lấy N message gần nhất của conversation, format thành text ngắn gọn
     * để nhúng vào prompt. Chỉ áp dụng khi conversation ĐÃ tồn tại từ trước
     * (request.getConversationId() != null) — conversation mới thì không
     * có gì để lấy, tránh query DB thừa.
     *
     * Nếu lỗi (ví dụ DB tạm thời chậm), trả về null thay vì ném exception —
     * đúng nguyên tắc kiến trúc: tính năng AI phụ trợ không được làm gãy
     * luồng /execute chính.
     */
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

    public SseEmitter processQueryStreaming(String username, QueryRequest request) {

        SseEmitter emitter = new SseEmitter(60_000L); // timeout 60s, tránh treo connection vô hạn

        sseTaskExecutor.execute(() -> {
            try {
                QueryProgressListener listener = (stage, message) -> {
                    try {
                        emitter.send(SseEmitter.event().name(stage).data(message));
                    } catch (IOException ignored) {
                        // client đã đóng kết nối (đóng tab, mất mạng...) — bỏ qua, không throw
                    }
                };

                QueryResponse response = processQuery(username, request, listener);

                emitter.send(SseEmitter.event().name("result").data(response));
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(e.getMessage() != null ? e.getMessage() : "Lỗi không xác định"));
                } catch (IOException ignored) { }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}