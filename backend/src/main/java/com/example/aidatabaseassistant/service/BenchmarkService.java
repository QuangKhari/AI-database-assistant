package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import com.example.aidatabaseassistant.entity.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkService {

    @org.springframework.beans.factory.annotation.Value(
            "${benchmark.max-questions-per-connection:10}")
    private int maxQuestionsPerConnection = 10;

    @org.springframework.beans.factory.annotation.Value("${gemini.api.url}")
    private String modelUrl;

    /**
     * Khoảng nghỉ giữa 2 câu hỏi liên tiếp (trong CÙNG một luồng/lane).
     *
     * Mặc định 5000ms để né rate-limit 15 requests/phút của Gemini Free
     * Tier. Nếu dùng API key trả phí (rate limit cao hơn nhiều), có thể
     * hạ giá trị này trong application-local.properties, ví dụ:
     *
     *   benchmark.delay-between-questions-ms=500
     */
    @org.springframework.beans.factory.annotation.Value(
            "${benchmark.delay-between-questions-ms:3000}")
    private long delayBetweenQuestionsMs = 3000;

    /**
     * Số lần thử lại tối đa khi Gemini trả về 429 (1 lần gọi ban đầu +
     * (maxRetries - 1) lần retry).
     */
    @org.springframework.beans.factory.annotation.Value(
            "${benchmark.max-retries:3}")
    private int maxRetries = 3;

    /**
     * Thời gian chờ trước khi retry sau khi bị 429.
     *
     * Free Tier reset theo cửa sổ 60s nên mặc định chờ 40s. Với API key
     * trả phí có thể hạ xuống vài giây.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${benchmark.retry-backoff-ms:10000}")
    private long retryBackoffMs = 10000;

    /**
     * Số "lane" chạy song song khi runBenchmark.
     *
     * = 1 (mặc định): chạy tuần tự y hệt hành vi cũ, an toàn tuyệt đối
     * cho Gemini Free Tier (15 requests/phút).
     *
     * > 1: các câu hỏi được chia đều vào N lane, mỗi lane tự chạy tuần
     * tự và tự nghỉ delayBetweenQuestionsMs giữa các câu của lane đó,
     * nên tổng thời gian chạy giảm gần đúng theo hệ số N. CHỈ nên tăng
     * giá trị này khi dùng Gemini API key trả phí (rate limit cao hơn
     * 15 RPM rất nhiều) - nếu vẫn dùng Free Tier, tăng song song sẽ làm
     * tăng nguy cơ dính 429 vì nhiều lane cùng gọi Gemini gần như đồng
     * thời.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${benchmark.parallelism:1}")
    private int parallelism = 1;
    private final BenchmarkQuestionRepository benchmarkQuestionRepository;
    private final BenchmarkResultRepository benchmarkResultRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final EncryptionUtil encryptionUtil;
    private final NL2SQLEngine nl2SQLEngine;
    private final QueryExecutor queryExecutor;
    private final QueryValidator queryValidator;
    private final UserRepository userRepository;
    private final SchemaLoaderService schemaLoaderService;
    private final com.example.aidatabaseassistant.security.ConnectionAccessGuard connectionAccessGuard;

    public BenchmarkQuestionResponse addQuestion( String username,
                                                  Long connectionId,
                                                  BenchmarkQuestionRequest request) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);
        String language = request.getLanguage().trim().toUpperCase();
        if (!language.equals("VI") && !language.equals("EN")) {
            throw new IllegalArgumentException( "Language phải là VI hoặc EN" );
        }

        long currentCount =
                benchmarkQuestionRepository.countByConnectionId(connectionId);
        if (currentCount >= maxQuestionsPerConnection) {
            throw new IllegalArgumentException(
                    "Connection này đã đạt giới hạn tối đa "
                            + maxQuestionsPerConnection
                            + " câu hỏi benchmark. Vui lòng xóa bớt câu hỏi cũ"
                            + " trước khi thêm mới - benchmark chạy đồng bộ và"
                            + " gọi Gemini thật cho từng câu, nên số câu hỏi"
                            + " càng nhiều thì 1 lần chạy càng lâu và càng tốn"
                            + " token."
            );
        }

        BenchmarkQuestion question =
                BenchmarkQuestion
                        .builder()
                        .connection(connection)
                        .language(language)
                        .questionText(
                                request.getQuestionText().trim() )
                        .expectedSql( request.getExpectedSql().trim() )
                        .build();
        BenchmarkQuestion saved = benchmarkQuestionRepository.save(question);
        return new BenchmarkQuestionResponse(
                saved.getId(),
                saved.getLanguage(),
                saved.getQuestionText(),
                saved.getExpectedSql(),
                connectionId );
    }

    public List<BenchmarkQuestionResponse> getQuestions(
            String username,
            Long connectionId,
            String language) {

        // Kiểm tra ownership trước khi đọc dữ liệu.
        getOwnedConnection(username, connectionId);

        List<BenchmarkQuestion> questions;

        if (language == null || language.isBlank()) {
            questions =
                    benchmarkQuestionRepository.findByConnectionId(
                            connectionId
                    );
        } else {

            String normalizedLanguage =
                    language.trim().toUpperCase();

            if (!normalizedLanguage.equals("VI")
                    && !normalizedLanguage.equals("EN")) {

                throw new IllegalArgumentException(
                        "Language phải là VI hoặc EN"
                );
            }

            questions =
                    benchmarkQuestionRepository
                            .findByConnectionIdAndLanguage(
                                    connectionId,
                                    normalizedLanguage
                            );
        }

        return questions.stream()
                .map(question ->
                        new BenchmarkQuestionResponse(
                                question.getId(),
                                question.getLanguage(),
                                question.getQuestionText(),
                                question.getExpectedSql(),
                                connectionId
                        )
                )
                .toList();
    }

    public void deleteQuestion(String username, Long connectionId, Long questionId) {
        // Xác nhận connection tồn tại và thuộc về user gọi request.
        getOwnedConnection(username, connectionId);

        BenchmarkQuestion question = benchmarkQuestionRepository
                .findByIdAndConnectionId(questionId, connectionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy câu hỏi benchmark này trong connection"
                ));

        // cascade = ALL + orphanRemoval trên BenchmarkQuestion.results nên
        // các BenchmarkResult liên quan cũng tự động bị xoá theo, không cần
        // xoá tay từng result trước.
        benchmarkQuestionRepository.delete(question);
    }

    public BenchmarkRunResponse runBenchmark(String username, Long connectionId, String language) {
        DatabaseConnection connection = getOwnedConnection(username, connectionId);

        DatabaseSchema schema =
                schemaLoaderService.loadCompleteSchema(connectionId);

        List<BenchmarkQuestion> allQuestions;

        if (language == null || language.isBlank()) {

            // ALL
            allQuestions =
                    benchmarkQuestionRepository
                            .findByConnectionId(connectionId);

        } else {

            String normalizedLanguage =
                    language.trim().toUpperCase();

            if (!normalizedLanguage.equals("VI")
                    && !normalizedLanguage.equals("EN")) {

                throw new IllegalArgumentException(
                        "Language phải là VI hoặc EN"
                );
            }

            allQuestions =
                    benchmarkQuestionRepository
                            .findByConnectionIdAndLanguage(
                                    connectionId,
                                    normalizedLanguage
                            );
        }

        List<BenchmarkQuestion> questions =
                allQuestions.size() > maxQuestionsPerConnection
                        ? allQuestions.subList(
                        0,
                        maxQuestionsPerConnection
                )
                        : allQuestions;

        String rawPassword = encryptionUtil.decrypt(connection.getEncryptedPassword());

        int effectiveParallelism = Math.max(
                1,
                Math.min(parallelism, Math.max(1, questions.size()))
        );

        AtomicInteger correctCount = new AtomicInteger(0);

        // Giữ đúng thứ tự câu hỏi trong response dù chạy song song nhiều lane.
        BenchmarkResultDetail[] orderedDetails =
                new BenchmarkResultDetail[questions.size()];

        if (effectiveParallelism == 1) {

            // Hành vi tuần tự y hệt trước đây - an toàn tuyệt đối cho
            // Gemini Free Tier.
            for (int i = 0; i < questions.size(); i++) {

                orderedDetails[i] = processQuestion(
                        questions.get(i),
                        schema,
                        connection,
                        rawPassword,
                        correctCount
                );

                if (i < questions.size() - 1) {
                    sleep(delayBetweenQuestionsMs);
                }
            }

        } else {

            // Chia câu hỏi round-robin vào N lane, mỗi lane chạy tuần tự
            // và tự nghỉ delayBetweenQuestionsMs giữa các câu CỦA LANE ĐÓ
            // -> tổng thời gian chạy giảm gần đúng theo hệ số N.
            ExecutorService executor =
                    Executors.newFixedThreadPool(effectiveParallelism);

            try {

                List<Future<?>> futures = new ArrayList<>();

                for (int lane = 0; lane < effectiveParallelism; lane++) {

                    int laneIndex = lane;

                    Callable<Void> task = () -> {

                        for (int i = laneIndex;
                             i < questions.size();
                             i += effectiveParallelism) {

                            orderedDetails[i] = processQuestion(
                                    questions.get(i),
                                    schema,
                                    connection,
                                    rawPassword,
                                    correctCount
                            );

                            boolean hasNextInLane =
                                    i + effectiveParallelism < questions.size();

                            if (hasNextInLane) {
                                sleep(delayBetweenQuestionsMs);
                            }
                        }

                        return null;
                    };

                    futures.add(executor.submit(task));
                }

                // Chờ tất cả lane chạy xong trước khi trả kết quả.
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Benchmark chạy song song bị lỗi", e
                        );
                    }
                }

            } finally {
                executor.shutdown();
                try {
                    executor.awaitTermination(1, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        List<BenchmarkResultDetail> details = List.of(orderedDetails);

        double accuracy = questions.isEmpty()
                ? 0
                : (double) correctCount.get() / questions.size() * 100;

        return new BenchmarkRunResponse(
                questions.size(),
                correctCount.get(),
                accuracy,
                details
        );
    }

    /**
     * Chạy 1 câu hỏi benchmark: gọi Gemini sinh SQL, validate, execute cả
     * 2 SQL (generated + expected), so sánh kết quả, lưu BenchmarkResult
     * và trả về BenchmarkResultDetail tương ứng.
     *
     * Tách riêng để dùng chung được cho cả chế độ tuần tự (parallelism=1)
     * và chế độ nhiều lane song song (parallelism>1).
     */
    private BenchmarkResultDetail processQuestion(
            BenchmarkQuestion question,
            DatabaseSchema schema,
            DatabaseConnection connection,
            String rawPassword,
            AtomicInteger correctCount) {

        long startTime = System.currentTimeMillis();

        String generatedSql = null;
        String errorMessage = null;

        try {
            // Gọi Gemini có retry khi gặp 429
            generatedSql = generateSqlWithRetry(
                    question.getQuestionText(),
                    schema
            );

        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        long latencyMs = System.currentTimeMillis() - startTime;

        boolean isCorrect = false;

        QueryResultDto generatedResult;

        // Chỉ execute SQL nếu AI sinh SQL thành công
        if (generatedSql != null && !generatedSql.isBlank()) {

            try {
                // Validate cả 2 SQL trước khi execute bất kỳ câu nào
                queryValidator.validate(generatedSql, schema);
                queryValidator.validate(question.getExpectedSql(), schema);

                // Chỉ execute sau khi cả 2 đều hợp lệ.
                //
                // QUAN TRỌNG: phải truyền connection.getDbType() - nếu
                // dùng overload 6-tham-số (không có dbType) thì
                // QueryExecutor sẽ MẶC ĐỊNH mở connection theo MySQL bất
                // kể connection thực tế là PostgreSQL/Excel, khiến
                // benchmark chạy sai driver và luôn lỗi trên các
                // connection không phải MySQL.
                generatedResult = queryExecutor.executeQuery(
                        connection.getDbType(),
                        connection.getHost(),
                        connection.getPort(),
                        connection.getDatabaseName(),
                        connection.getUsername(),
                        rawPassword,
                        generatedSql
                );

                QueryResultDto expectedResult = queryExecutor.executeQuery(
                        connection.getDbType(),
                        connection.getHost(),
                        connection.getPort(),
                        connection.getDatabaseName(),
                        connection.getUsername(),
                        rawPassword,
                        question.getExpectedSql()
                );

                isCorrect = compareResults(
                        generatedResult,
                        expectedResult
                );

                if (generatedResult.getError() != null) {
                    errorMessage = generatedResult.getError();
                }

            } catch (Exception e) {
                errorMessage = e.getMessage();
            }
        }

        if (isCorrect) {
            correctCount.incrementAndGet();
        }

        BenchmarkResult result = BenchmarkResult.builder()
                .benchmarkQuestion(question)
                .generatedSql(generatedSql)
                .expectedSql(question.getExpectedSql())
                .isCorrect(isCorrect)
                .latencyMs(latencyMs)
                .modelUsed(extractModelName(modelUrl))
                .build();

        benchmarkResultRepository.save(result);

        return new BenchmarkResultDetail(
                question.getQuestionText(),
                generatedSql,
                question.getExpectedSql(),
                isCorrect,
                latencyMs,
                errorMessage
        );
    }

    private String generateSqlWithRetry(
            String question,
            DatabaseSchema schema) {

        for (int attempt = 1; attempt <= maxRetries; attempt++) {

            try {
                return nl2SQLEngine.generateSQL(question, schema);

            } catch (RuntimeException e) {

                if (!isRateLimitError(e)) {
                    throw e;
                }

                log.warn(
                        "Gemini rate limit (429). Attempt {}/{}",
                        attempt, maxRetries
                );

                if (attempt == maxRetries) {
                    throw e;
                }

                // Chờ trước khi retry (cấu hình qua benchmark.retry-backoff-ms).
                sleep(retryBackoffMs);
            }
        }

        throw new RuntimeException("Không thể generate SQL");
    }

    /**
     * Kiểm tra xem lỗi có phải do Gemini rate limit (429) hay không.
     *
     * QUAN TRỌNG: LLMClient.callWithRetry() bọc MỌI lỗi có thể retry được
     * (network timeout, 5xx, 429) vào một RuntimeException với message
     * chung chung ("Không thể kết nối tới dịch vụ AI...") sau khi tự nó
     * đã retry hết số lần cho phép ở tầng LLMClient. Nếu chỉ kiểm tra
     * e.getMessage() ở tầng BenchmarkService (như code cũ) thì sẽ KHÔNG
     * BAO GIỜ nhận diện được đây là lỗi 429, vì message gốc chứa "429"/
     * "RESOURCE_EXHAUSTED" nằm ở exception gốc (cause), không phải ở
     * exception được throw ra. Hệ quả: retry-backoff-ms không bao giờ
     * được kích hoạt, benchmark fail câu hỏi ngay khi gặp 429 thay vì
     * chờ rồi thử lại.
     *
     * Fix: duyệt toàn bộ cause chain, vừa kiểm tra message vừa kiểm tra
     * trực tiếp status code 429 của HttpStatusCodeException (nếu có).
     */
    private boolean isRateLimitError(Throwable error) {

        Throwable current = error;

        while (current != null) {

            if (current instanceof HttpStatusCodeException httpError
                    && httpError.getStatusCode().value() == 429) {
                return true;
            }

            String message = current.getMessage();

            if (message != null &&
                    (
                            message.contains("429") ||
                                    message.contains("Too Many Requests") ||
                                    message.contains("RESOURCE_EXHAUSTED")
                    )) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private boolean compareResults(
            QueryResultDto generated,
            QueryResultDto expected) {

        /*
         * =========================================================
         * 1. KIỂM TRA ERROR
         * =========================================================
         */

        if (generated == null || expected == null) {
            return false;
        }

        if (generated.getError() != null
                || expected.getError() != null) {

            return false;
        }

        /*
         * =========================================================
         * 2. KIỂM TRA SỐ DÒNG
         * =========================================================
         */

        if (generated.getRowCount()
                != expected.getRowCount()) {

            return false;
        }

        List<Map<String, Object>> generatedRows =
                generated.getRows();

        List<Map<String, Object>> expectedRows =
                expected.getRows();

        /*
         * =========================================================
         * 3. KHÔNG CÓ DỮ LIỆU
         * =========================================================
         *
         * Hai query đều trả 0 row:
         *
         *     => kết quả tương đương.
         */

        if (generatedRows.isEmpty()
                && expectedRows.isEmpty()) {

            return true;
        }

        /*
         * Trường hợp bất thường:
         *
         * rowCount giống nhau nhưng một bên không có rows.
         */
        if (generatedRows.isEmpty()
                || expectedRows.isEmpty()) {

            return false;
        }

        /*
         * =========================================================
         * 4. KIỂM TRA SỐ CỘT
         * =========================================================
         *
         * KHÔNG dùng tên alias để map.
         *
         * Ví dụ:
         *
         * Generated:
         *
         *     product_name | total
         *
         * Expected:
         *
         *     product_name | total_revenue
         *
         * Hai kết quả vẫn có thể hoàn toàn tương đương.
         *
         * Vì vậy dùng thứ tự cột JDBC:
         *
         *     column 1 <-> column 1
         *     column 2 <-> column 2
         */

        List<String> generatedColumns =
                generated.getColumns();

        List<String> expectedColumns =
                expected.getColumns();

        if (generatedColumns == null
                || expectedColumns == null) {

            return false;
        }

        if (generatedColumns.size()
                != expectedColumns.size()) {

            return false;
        }

        /*
         * =========================================================
         * 5. KIỂM TRA TỪNG ROW THEO ORDINAL COLUMN
         * =========================================================
         *
         * Không phụ thuộc:
         *
         *     alias
         *     tên column
         *
         * Chỉ phụ thuộc:
         *
         *     column position
         *     actual value
         */

        List<String> generatedNormalized =
                normalizeRowsByColumnOrder(
                        generatedRows,
                        generatedColumns
                );

        List<String> expectedNormalized =
                normalizeRowsByColumnOrder(
                        expectedRows,
                        expectedColumns
                );

        /*
         * Không phụ thuộc thứ tự row.
         *
         * Ví dụ:
         *
         * Generated:
         * A
         * B
         *
         * Expected:
         * B
         * A
         *
         * vẫn được coi là đúng nếu dữ liệu tương đương.
         */
        generatedNormalized =
                generatedNormalized.stream()
                        .sorted()
                        .toList();

        expectedNormalized =
                expectedNormalized.stream()
                        .sorted()
                        .toList();

        return generatedNormalized.equals(
                expectedNormalized
        );
    }

    private List<String> normalizeRowsByColumnOrder(
            List<Map<String, Object>> rows,
            List<String> columns) {

        return rows.stream()
                .map(row ->
                        columns.stream()
                                .map(column ->
                                        normalizeValue(
                                                row.get(column)
                                        )
                                )
                                .collect(
                                        Collectors.joining("|")
                                )
                )
                .toList();
    }

    private String normalizeValue(Object value) {

        if (value == null) {
            return "<NULL>";
        }

        return value.toString().trim();
    }
    private List<String> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> row.values().stream()
                        .map(v -> v == null ? "null" : v.toString())
                        .sorted()
                        .collect(Collectors.joining("|")))
                .collect(Collectors.toList());
    }

    private String extractModelName(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }

        int modelStart = url.indexOf("/models/");

        if (modelStart < 0) {
            return "unknown";
        }

        int start = modelStart + "/models/".length();

        int end = url.indexOf(":", start);

        if (end < 0) {
            end = url.length();
        }

        if (start >= end) {
            return "unknown";
        }

        return url.substring(start, end);
    }

    private DatabaseConnection getOwnedConnection(String username, Long connectionId) {
        return connectionAccessGuard.requireOwnedConnection(username, connectionId);
    }

    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new RuntimeException(
                    "Benchmark bị gián đoạn",
                    e
            );
        }
    }

    public BenchmarkGenerateSqlResponse generateExpectedSql(
            String username,
            Long connectionId,
            BenchmarkGenerateSqlRequest request) {

        getOwnedConnection(username, connectionId);

        DatabaseSchema schema =
                schemaLoaderService.loadCompleteSchema(connectionId);

        String question = request.getQuestionText().trim();

        if (question.isBlank()) {
            throw new IllegalArgumentException(
                    "Câu hỏi benchmark không được để trống"
            );
        }

        String generatedSql =
                generateSqlWithRetry(question, schema);

        if (generatedSql == null || generatedSql.isBlank()) {
            throw new IllegalArgumentException(
                    "AI không tạo được SQL cho câu hỏi này"
            );
        }

        // Chỉ kiểm tra SQL, KHÔNG thực thi.
        queryValidator.validate(generatedSql, schema);

        return new BenchmarkGenerateSqlResponse(generatedSql);
    }
}